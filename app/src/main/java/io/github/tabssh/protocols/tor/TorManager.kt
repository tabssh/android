package io.github.tabssh.protocols.tor

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the lifecycle of the single bundled tor process.
 *
 * A NetworkRoute with `built_in_tor = true` acquires a usage lease at connect
 * time; the returned loopback SOCKS port is then used exactly like any other
 * SOCKS5 proxy. The process is shared across all Tor-routed connections and
 * kept running while one or more active route users hold a lease.
 */
class TorManager private constructor(private val appContext: Context) {

    private val lock = Any()
    private val activeUsers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Volatile
    private var session: TorNativeClient.Session? = null

    @Volatile
    private var socksPort: Int = 0

    private val _status = MutableStateFlow<TorStatus>(TorStatus.Stopped)

    /** Live tor state — process spawned vs. actually bootstrapped/usable. */
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    /** True when a bundled tor binary exists for this device's ABI. */
    fun isAvailable(): Boolean = TorNativeClient.isAvailable(appContext)

    /**
     * Ensure tor is running and bootstrapped, returning its loopback SOCKS
     * port. Idempotent: a healthy existing process is reused.
     *
     * @throws IllegalStateException if the tor binary is not bundled or the
     *         process fails to bootstrap within [BOOTSTRAP_TIMEOUT_MS].
     */
    fun ensureStarted(): Int {
        synchronized(lock) {
            val existing = session
            if (existing != null && existing.isAlive() && socksPort > 0) {
                _status.value = TorStatus.Connected
                return socksPort
            }
            _status.value = TorStatus.Starting
            var started: TorNativeClient.Session? = null
            try {
                // Clean up a dead session before restarting.
                existing?.close()
                session = null
                socksPort = 0

                val port = findFreeLoopbackPort()
                val dataDir = File(appContext.filesDir, TOR_DATA_DIR)
                started = TorNativeClient.spawn(appContext, port, dataDir) { percent ->
                    _status.value = TorStatus.Bootstrapping(percent)
                }
                if (!started.awaitBootstrap(BOOTSTRAP_TIMEOUT_MS)) {
                    throw IllegalStateException("Tor failed to bootstrap within ${BOOTSTRAP_TIMEOUT_MS}ms")
                }
                session = started
                socksPort = port
                _status.value = TorStatus.Connected
                Logger.i(TAG, "Tor bootstrapped; loopback SOCKS on 127.0.0.1:$port")
                return port
            } catch (e: Exception) {
                try { started?.close() } catch (_: Exception) {}
                session = null
                socksPort = 0
                val reason = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName.ifBlank { "Unknown Tor startup error" }
                _status.value = TorStatus.Failed(reason)
                if (e is IllegalStateException && e.message == reason) throw e
                throw IllegalStateException(reason, e)
            }
        }
    }

    /** Start Tor and hold it until this owner releases its usage lease. */
    fun acquireUsage(owner: Any): Int = synchronized(lock) {
        val port = ensureStarted()
        activeUsers.add(owner)
        port
    }

    /** Release one route user's lease and stop Tor when the last user leaves. */
    fun releaseUsage(owner: Any) {
        synchronized(lock) {
            activeUsers.remove(owner)
            if (activeUsers.isEmpty()) stopLocked()
        }
    }

    /** Bootstrap Tor and verify its SOCKS5 listener; stop a temporary check process. */
    fun checkStatus() {
        synchronized(lock) {
            check(isAvailable()) { "Bundled Tor is not available for this device" }
            val temporary = activeUsers.isEmpty()
            try {
                val port = ensureStarted()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), SOCKS_CHECK_TIMEOUT_MS)
                    socket.soTimeout = SOCKS_CHECK_TIMEOUT_MS
                    socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                    socket.getOutputStream().flush()
                    val response = ByteArray(2)
                    var offset = 0
                    while (offset < response.size) {
                        val read = socket.getInputStream().read(response, offset, response.size - offset)
                        check(read > 0) { "Tor SOCKS listener closed during status check" }
                        offset += read
                    }
                    check(response[0] == 5.toByte() && response[1] == 0.toByte()) {
                        "Tor SOCKS listener returned an invalid response"
                    }
                }
                if (temporary) stopLocked()
            } catch (e: Exception) {
                if (temporary) stopLocked()
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _status.value = TorStatus.Failed(reason)
                throw IllegalStateException(reason, e)
            }
        }
    }

    /** Stop the tor process if running. Safe to call when already stopped. */
    fun stop() {
        synchronized(lock) {
            activeUsers.clear()
            stopLocked()
        }
    }

    private fun stopLocked() {
        session?.close()
        session = null
        socksPort = 0
        _status.value = TorStatus.Stopped
    }

    fun isRunning(): Boolean = session?.isAlive() == true

    /**
     * Bind an ephemeral port on the loopback interface, then release it, so tor
     * can claim it. The brief race window is acceptable: the port is on
     * 127.0.0.1 and reclaimed microseconds later by our own child.
     */
    private fun findFreeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    companion object {
        private const val TAG = "TorManager"
        private const val TOR_DATA_DIR = "tor"
        private const val BOOTSTRAP_TIMEOUT_MS = 90_000L
        private const val SOCKS_CHECK_TIMEOUT_MS = 5_000

        @Volatile
        private var INSTANCE: TorManager? = null

        fun getInstance(context: Context): TorManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
