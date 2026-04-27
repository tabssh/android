package io.github.tabssh.protocols.mosh

import android.content.Context
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Wave 9.2 — In-app native Mosh client.
 *
 * Spawns the bundled `mosh-client` binary (cross-compiled per ABI under
 * `app/src/main/jniLibs/<abi>/libmosh-client.so` — the `lib*.so` naming
 * trick is required so Android's APK installer copies the file into
 * `Context.applicationInfo.nativeLibraryDir` AND marks it executable.
 * The file itself isn't a shared object; it's a real ELF executable).
 *
 * Public API:
 *   - [resolveBinary]: returns the on-disk path or null if not bundled
 *     for this ABI yet (build pipeline is multi-ABI; not all may be
 *     vendored at any given time).
 *   - [spawn]: launches mosh-client and returns a [Session] holding the
 *     stdio pipes the caller wires into TermuxBridge.
 *
 * Process model:
 *   mosh-client positional args: `host port`
 *   env: MOSH_KEY=<base64> — required, comes from MoshHandoff bootstrap.
 *
 * Lifecycle: caller is responsible for [Session.close]. We don't auto-
 * destroy on GC because the SSH session that bootstrapped Mosh might
 * itself be long gone — the Mosh session is supposed to outlive its
 * parent SSH (that's the point).
 */
object MoshNativeClient {

    private const val TAG = "MoshNativeClient"
    private const val BINARY_FILE_NAME = "libmosh-client.so"

    /**
     * @return absolute path to the bundled mosh-client for this device's
     *         primary ABI, or null if no binary is bundled.
     */
    fun resolveBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val candidate = File(nativeDir, BINARY_FILE_NAME)
        if (!candidate.exists()) {
            Logger.d(TAG, "mosh-client binary not bundled at ${candidate.absolutePath}")
            return null
        }
        if (!candidate.canExecute()) {
            // Should never happen — Android marks jniLibs entries executable —
            // but defensively chmod in case the user is on a quirky device.
            try { candidate.setExecutable(true) } catch (_: Exception) {}
            if (!candidate.canExecute()) {
                Logger.w(TAG, "mosh-client at ${candidate.absolutePath} is not executable")
                return null
            }
        }
        return candidate
    }

    /**
     * Launch mosh-client. Caller wires [Session.input] / [Session.output] to
     * TermuxBridge. On any failure throws — caller surfaces to the user.
     */
    fun spawn(
        context: Context,
        host: String,
        port: Int,
        moshKeyBase64: String
    ): Session {
        val binary = resolveBinary(context)
            ?: throw IllegalStateException("mosh-client native binary is not bundled in this APK build")
        val pb = ProcessBuilder(binary.absolutePath, host, port.toString()).apply {
            environment()["MOSH_KEY"] = moshKeyBase64
            // mosh-client writes to TERM-driven curses; xterm-256color is the
            // matching TermuxBridge setting.
            environment()["TERM"] = "xterm-256color"
            // Don't merge stderr — keep it separate so we can log it.
            redirectErrorStream(false)
            // Working dir: app's data dir (mosh-client writes nothing to disk
            // but a missing CWD makes it fail spawn on some kernels).
            directory(File(context.filesDir.absolutePath))
        }
        Logger.i(TAG, "Spawning mosh-client $host:$port (binary=${binary.absolutePath})")
        return Session(pb.start())
    }

    class Session internal constructor(private val process: Process) {

        val input: InputStream get() = process.inputStream
        val output: OutputStream get() = process.outputStream
        private val errScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val errJob: Job = errScope.launch {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Logger.w(TAG, "mosh-client stderr: $line")
                }
            } catch (e: Exception) {
                Logger.d(TAG, "stderr pump exited: ${e.message}")
            }
        }

        fun isAlive(): Boolean = process.isAlive

        fun exitValue(): Int? = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }

        fun close() {
            try { errJob.cancel() } catch (_: Exception) {}
            try { errScope.cancel() } catch (_: Exception) {}
            try { process.outputStream.close() } catch (_: Exception) {}
            try { process.inputStream.close() } catch (_: Exception) {}
            try { process.errorStream.close() } catch (_: Exception) {}
            try {
                if (process.isAlive) process.destroy()
            } catch (_: Exception) {}
        }
    }
}
