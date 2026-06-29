package io.github.tabssh.network

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Ladder-style reachability probe used to turn a generic socket failure
 * ("nothing received from server on port", "socket error") into a specific
 * actionable message.
 *
 * Probes are cheap (DNS + a single TCP connect) and run in the order a
 * connection would be established:
 *   1. DNS resolution
 *   2. TCP connect to the SSH port
 *   3. If SSH is up and the failure was a fast mosh exit, infer UDP block
 *
 * UDP itself is not probed — mosh's UDP traffic is the only thing that would
 * ever generate a reply, and any reply requires a running mosh-server with
 * the negotiated key. A silent UDP firewall is indistinguishable from a
 * working one without that key, so the SSH-up + mosh-failed-fast pairing is
 * the strongest signal we can produce client-side.
 */
object ConnectionDiagnostic {

    private const val TAG = "ConnectionDiagnostic"

    private const val PROBE_TIMEOUT_MS = 4000

    sealed class Result(val userMessage: String) {
        class DnsFailed(host: String) :
            Result("Cannot resolve \"$host\" — check the hostname or your DNS settings.")

        class HostUnreachable(host: String) :
            Result("Host $host is not responding — check your network, VPN, or the hostname.")

        class SshDown(host: String, port: Int) :
            Result("Host $host is reachable but SSH port $port is not accepting connections — sshd may be down or the port is wrong.")

        class MoshUdpBlocked(host: String) :
            Result("SSH on $host works, but the mosh UDP reply never arrived. Open UDP 60000–61000 on the server firewall (firewall-cmd --permanent --add-port=60000-61000/udp && firewall-cmd --reload) or use \"Try SSH instead\".")

        class Reachable(host: String, port: Int) :
            Result("Host $host:$port is reachable — the failure was after the connection established (auth, protocol, or remote command).")
    }

    /**
     * Run the ladder. [moshFailedFast] should be true when the prior failure
     * looked like a UDP block (mosh exited non-zero within ~2 minutes); it
     * controls the message used when SSH is reachable.
     */
    suspend fun diagnose(host: String, sshPort: Int, moshFailedFast: Boolean): Result =
        withContext(Dispatchers.IO) {
            val resolved = try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                Logger.i(TAG, "DNS failed for $host: ${e.message}")
                return@withContext Result.DnsFailed(host)
            } catch (e: Exception) {
                Logger.w(TAG, "DNS lookup error for $host", e)
                return@withContext Result.DnsFailed(host)
            }

            if (!tcpConnect(resolved, sshPort)) {
                // SSH port closed/filtered. Try a second well-known port (443)
                // to distinguish "host is down" from "only SSH is down".
                val hostUp = tcpConnect(resolved, 443) || tcpConnect(resolved, 80)
                return@withContext if (hostUp) Result.SshDown(host, sshPort)
                else Result.HostUnreachable(host)
            }

            if (moshFailedFast) {
                return@withContext Result.MoshUdpBlocked(host)
            }

            Result.Reachable(host, sshPort)
        }

    private fun tcpConnect(addr: InetAddress, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(addr, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            Logger.d(TAG, "TCP probe ${addr.hostAddress}:$port failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
