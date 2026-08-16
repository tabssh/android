package io.github.tabssh.protocols.tor

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Owns the lifecycle of the single bundled tor process.
 *
 * A NetworkRoute with `built_in_tor = true` calls [ensureStarted] at connect
 * time; the returned loopback SOCKS port is then used exactly like any other
 * SOCKS5 proxy. The process is shared across all Tor-routed connections and
 * kept running until [stop] (or process death) — starting tor is expensive
 * (circuit bootstrap), so it is not torn down between individual connects.
 */
class TorManager private constructor(private val appContext: Context) {

    private val lock = Any()

    @Volatile
    private var session: TorNativeClient.Session? = null

    @Volatile
    private var socksPort: Int = 0

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
                return socksPort
            }
            // Clean up a dead session before restarting.
            existing?.close()
            session = null
            socksPort = 0

            val port = findFreeLoopbackPort()
            val dataDir = File(appContext.filesDir, TOR_DATA_DIR)
            val started = TorNativeClient.spawn(appContext, port, dataDir)
            if (!started.awaitBootstrap(BOOTSTRAP_TIMEOUT_MS)) {
                started.close()
                throw IllegalStateException("Tor failed to bootstrap within ${BOOTSTRAP_TIMEOUT_MS}ms")
            }
            session = started
            socksPort = port
            Logger.i(TAG, "Tor bootstrapped; loopback SOCKS on 127.0.0.1:$port")
            return port
        }
    }

    /** Stop the tor process if running. Safe to call when already stopped. */
    fun stop() {
        synchronized(lock) {
            session?.close()
            session = null
            socksPort = 0
        }
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

        @Volatile
        private var INSTANCE: TorManager? = null

        fun getInstance(context: Context): TorManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
