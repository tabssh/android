package io.github.tabssh.protocols.tor

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app native Tor client — the bundled counterpart to Orbot.
 *
 * Spawns the bundled `tor` binary (cross-compiled per ABI under
 * `app/src/main/jniLibs/<abi>/libtor.so` — the `lib*.so` naming trick is what
 * makes Android's installer copy the file into
 * `Context.applicationInfo.nativeLibraryDir` AND mark it executable; the file
 * is a real ELF executable, not a shared object — identical to how the mosh
 * client is bundled).
 *
 * tor is run client-only as a loopback SOCKS proxy. The rest of the app routes
 * through it exactly like an external SOCKS5 proxy (JSch `ProxySOCKS5`), so no
 * Tor-specific code lives in the connection path — see NetworkRoute.builtInTor.
 *
 * Lifecycle is owned by [TorManager]; this object only knows how to locate and
 * launch the binary and report when it has bootstrapped.
 */
object TorNativeClient {

    private const val TAG = "TorNativeClient"
    private const val BINARY_FILE_NAME = "libtor.so"
    private const val BOOTSTRAP_MARKER = "Bootstrapped 100%"

    // Pinned versions of the statically-linked native stack. These MUST track
    // the pins in deps/tor/Dockerfile and deps/tor/build-android.sh — the bundled binary
    // has no cheap runtime version query, so the About dialog reports these
    // build-time constants. Update here and in both build files together.
    const val TOR_VERSION = "0.4.9.11"
    const val OPENSSL_VERSION = "3.0.13"
    const val LIBEVENT_VERSION = "2.1.13"
    const val ZLIB_VERSION = "1.3.1"

    /**
     * @return absolute path to the bundled tor binary for this device's primary
     *         ABI, or null if no binary is bundled in this APK build.
     */
    fun resolveBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val candidate = File(nativeDir, BINARY_FILE_NAME)
        if (!candidate.exists()) {
            Logger.d(TAG, "tor binary not bundled at ${candidate.absolutePath}")
            return null
        }
        if (!candidate.canExecute()) {
            try { candidate.setExecutable(true) } catch (_: Exception) {}
            if (!candidate.canExecute()) {
                Logger.w(TAG, "tor at ${candidate.absolutePath} is not executable")
                return null
            }
        }
        return candidate
    }

    /** True when a bundled, runnable tor binary exists for this device's ABI. */
    fun isAvailable(context: Context): Boolean = resolveBinary(context) != null

    /**
     * Launch tor as a client-only loopback SOCKS proxy on [socksPort].
     * Configuration is passed entirely on the command line (no torrc file to
     * parse), with a private, app-owned [dataDir] for tor's state cache.
     *
     * @throws IllegalStateException if the binary is not bundled.
     */
    fun spawn(context: Context, socksPort: Int, dataDir: File): Session {
        val binary = resolveBinary(context)
            ?: throw IllegalStateException("tor native binary is not bundled in this APK build")
        require(socksPort in 1..65535) { "Invalid Tor SOCKS port: $socksPort" }
        if (!dataDir.exists()) dataDir.mkdirs()

        val pb = ProcessBuilder(
            binary.absolutePath,
            "--SocksPort", "127.0.0.1:$socksPort",
            "--DataDirectory", dataDir.absolutePath,
            "--ClientOnly", "1",
            "--AvoidDiskWrites", "1",
            "--SafeSocks", "1",
            "--Log", "notice stderr",
            "--RunAsDaemon", "0"
        ).apply {
            redirectErrorStream(true)
            directory(File(context.filesDir.absolutePath))
        }
        Logger.i(TAG, "Spawning tor on loopback SOCKS 127.0.0.1:$socksPort (binary=${binary.absolutePath})")
        return Session(pb.start())
    }

    /**
     * A running tor process. Watches tor's log for the bootstrap-complete
     * marker; callers await [awaitBootstrap] before routing traffic.
     */
    class Session internal constructor(private val process: Process) {

        private val bootstrapped = AtomicBoolean(false)
        private val bootstrapLatch = CountDownLatch(1)
        private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val logJob: Job = logScope.launch {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Logger.d(TAG, "tor: $line")
                    if (line.contains(BOOTSTRAP_MARKER)) {
                        if (bootstrapped.compareAndSet(false, true)) {
                            bootstrapLatch.countDown()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.d(TAG, "tor log pump exited: ${e.message}")
            } finally {
                // Process ended — unblock any waiter so it can observe failure.
                bootstrapLatch.countDown()
            }
        }

        /**
         * Block until tor reports "Bootstrapped 100%" or [timeoutMs] elapses.
         * @return true if bootstrap completed, false on timeout or early exit.
         */
        fun awaitBootstrap(timeoutMs: Long): Boolean {
            bootstrapLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return bootstrapped.get() && isAlive()
        }

        fun isAlive(): Boolean = try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }

        fun close() {
            try { logJob.cancel() } catch (_: Exception) {}
            try { logScope.cancel() } catch (_: Exception) {}
            try { process.inputStream.close() } catch (_: Exception) {}
            try { process.outputStream.close() } catch (_: Exception) {}
            try { process.errorStream.close() } catch (_: Exception) {}
            try { if (isAlive()) process.destroy() } catch (_: Exception) {}
        }
    }
}
