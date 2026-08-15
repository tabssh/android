package io.github.tabssh.ssh.connection

import com.jcraft.jsch.SocketFactory
import io.github.tabssh.utils.logging.Logger
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #12 — connection-phase socket factory with two jobs:
 *
 * 1. **Timing visibility.** JSch's default socket path buries DNS + TCP
 *    connect inside the opaque `session.connect (TCP+kex+auth)` step. This
 *    factory logs DNS resolution time (with the address-family breakdown)
 *    and each TCP connect attempt (family + duration + outcome) so a slow
 *    connect report shows exactly where the seconds went.
 *
 * 2. **Multi-address fallback.** JSch's `Util.createSocket` resolves the
 *    hostname to ONE address (`InetAddress.getByName`) and tries only that.
 *    On dual-stack DNS where the first answer is an unreachable/black-holed
 *    IPv6 address, that means waiting out the full connect timeout even
 *    though a working IPv4 address was in the same DNS answer — the classic
 *    "app X connects instantly, app Y takes 30 s" signature. OpenSSH (and
 *    ConnectBot) iterate over ALL resolved addresses; this factory does the
 *    same, sequentially, honoring `ipMode` ordering.
 *
 * JSch calls `SocketFactory.createSocket(host, port)` WITHOUT the session
 * timeout (verified against jsch 2.27.7 bytecode), so this factory enforces
 * the connect budget itself: the total budget is [totalTimeoutMs]; each
 * non-final address gets an equal slice (floored at [MIN_ATTEMPT_MS]) so a
 * black-holed first address cannot starve the fallback; the final address
 * gets whatever budget remains. `InetAddress.getAllByName` itself has no
 * timeout parameter and is not covered by the per-address connect slices —
 * a nameserver that never answers (dead resolver, black-holed DNS over a
 * VPN/firewall) hangs that call indefinitely regardless of [totalTimeoutMs].
 * [resolveWithTimeout] runs it on [dnsExecutor] and bounds the wait so this
 * class's own "connect budget" claim holds for the DNS phase too.
 */
class TimingSocketFactory(
    private val ipMode: String,
    totalTimeoutMs: Int,
) : SocketFactory {

    private val budgetMs: Int =
        if (totalTimeoutMs > 0) totalTimeoutMs else DEFAULT_BUDGET_MS

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val deadline = System.currentTimeMillis() + budgetMs

        val tDns = System.currentTimeMillis()
        val resolved = resolveWithTimeout(host, budgetMs)
        val dnsMs = System.currentTimeMillis() - tDns
        val v4 = resolved.count { it is Inet4Address }
        val v6 = resolved.size - v4
        Logger.i(
            TAG,
            "TIMING $host: dns-resolve took $dnsMs ms " +
                "(${resolved.size} address(es): $v4 IPv4, $v6 IPv6)"
        )

        // ipMode "ipv4"/"ipv6" hosts arrive pre-resolved to a literal from
        // resolveHostForIpMode(), so ordering only matters for "auto" —
        // still, partition defensively so an unresolved ipv4/ipv6 profile
        // behaves correctly too. "auto" keeps resolver (RFC 6724) order.
        val ordered: List<InetAddress> = when (ipMode) {
            "ipv4" -> resolved.sortedByDescending { it is Inet4Address }
            "ipv6" -> resolved.sortedBy { it is Inet4Address }
            else -> resolved.toList()
        }

        var lastError: IOException? = null
        for ((idx, addr) in ordered.withIndex()) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) break
            val isLast = idx == ordered.lastIndex
            val attemptMs =
                if (isLast) remaining
                else minOf(remaining, maxOf(MIN_ATTEMPT_MS, budgetMs / ordered.size))
            val family = if (addr is Inet4Address) "IPv4" else "IPv6"
            val tConn = System.currentTimeMillis()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(addr, port), attemptMs)
                Logger.i(
                    TAG,
                    "TIMING $host: tcp-connect ($family, attempt ${idx + 1}/${ordered.size}) " +
                        "took ${System.currentTimeMillis() - tConn} ms"
                )
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                Logger.w(
                    TAG,
                    "TIMING $host: tcp-connect ($family, attempt ${idx + 1}/${ordered.size}) " +
                        "FAILED after ${System.currentTimeMillis() - tConn} ms: ${e.message}"
                )
                lastError = e
            }
        }
        throw lastError
            ?: IOException("connect to $host:$port timed out after $budgetMs ms (no address reachable)")
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    /**
     * Resolve [host] with a hard [timeoutMs] wall-clock bound. Runs the
     * blocking `InetAddress.getAllByName` call on [dnsExecutor] so a dead
     * resolver only leaks one daemon thread (reclaimed once the OS-level
     * lookup eventually gives up) instead of hanging this connect attempt —
     * and every caller waiting on it — forever.
     */
    @Throws(IOException::class)
    private fun resolveWithTimeout(host: String, timeoutMs: Int): Array<InetAddress> {
        val future = dnsExecutor.submit<Array<InetAddress>> { InetAddress.getAllByName(host) }
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IOException("DNS resolution for $host timed out after ${timeoutMs}ms", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IOException(e.cause?.message ?: "DNS resolution for $host failed", e.cause)
        }
    }

    private companion object {
        const val TAG = "TimingSocketFactory"
        // Matches the historical profile.connectTimeout default.
        const val DEFAULT_BUDGET_MS = 30_000
        // Floor per non-final attempt so many resolved addresses can't
        // shrink slices below a workable TCP handshake window.
        const val MIN_ATTEMPT_MS = 4_000

        private val dnsThreadCount = AtomicInteger(0)

        // Cached (not fixed) pool: lookups are rare and short-lived in the
        // common case, but a black-holed resolver's thread lingers past its
        // caller's timeout (getAllByName is not interruptible) — cached pool
        // reclaims idle threads instead of accumulating a fixed set of
        // permanently blocked ones. Daemon so a stuck lookup can't keep the
        // process alive.
        val dnsExecutor = Executors.newCachedThreadPool(ThreadFactory { runnable ->
            Thread(runnable, "dns-resolve-${dnsThreadCount.incrementAndGet()}").apply {
                isDaemon = true
            }
        })
    }
}
