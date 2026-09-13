package io.github.tabssh.hypervisor.spice

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import java.io.File

/**
 * Gatekeeper for the SPICE native library.
 *
 * Loads `libtabssh_native.so` exactly once and reports whether it was
 * built against a real libspice-client-glib prebuilt or is simply
 * absent from this APK. The library is cross-compiled out-of-tree by
 * the `spice-libs.yml` CI workflow (see `deps/prereqs/spice/Dockerfile` +
 * `deps/prereqs/spice/build-android.sh`), published as a prerelease, and dropped
 * into `app/src/main/jniLibs/<abi>/libtabssh_native.so` at build time
 * by `scripts/fetch-spice-libs.sh`. When no such release exists yet the
 * APK simply ships without the library.
 *
 * Every SPICE code path — connector, JNI bindings, display view —
 * MUST call [isSpiceAvailable] first and take the VNC fallback when
 * it returns false. This lets a single APK ship to both
 * SPICE-enabled and SPICE-disabled builds without any [UnsatisfiedLinkError]
 * at runtime: a missing or scaffold-only library degrades to VNC.
 */
object SpiceLoader {
    private const val TAG = "SpiceLoader"

    /** File name for the native-crash marker, in the app's private files dir. */
    private const val NATIVE_CRASH_FILE_NAME = "native_crash.log"

    /**
     * Result of the one-shot native library load. `null` until the
     * first call to [isSpiceAvailable]; `false` if either
     * `System.loadLibrary` threw or the native symbol reported no
     * SPICE prebuilts at build time.
     */
    @Volatile
    private var available: Boolean? = null

    /**
     * Absolute path to the native-crash marker file, in the app's
     * private files dir. Used both by [isSpiceAvailable] (to tell the
     * native signal handler where to write) and by
     * [io.github.tabssh.TabSSHApplication] on the next launch (to fold
     * any marker left by a previous native crash into the Debug Log).
     */
    fun nativeCrashMarkerFile(): File? =
        try {
            File(TabSSHApplication.get().filesDir, NATIVE_CRASH_FILE_NAME)
        } catch (e: IllegalStateException) {
            null
        }

    /**
     * True iff `libtabssh_native.so` loaded successfully AND was
     * built against real libspice prebuilts. Callers MUST branch on
     * this before touching any other SPICE JNI entry point.
     */
    fun isSpiceAvailable(): Boolean {
        available?.let { return it }
        synchronized(this) {
            available?.let { return it }
            val result = try {
                System.loadLibrary("tabssh_native")
                val native = nativeIsSpiceAvailable() == 1
                if (!native) {
                    Logger.i(TAG, "libtabssh_native.so loaded but no SPICE prebuilts — SPICE disabled")
                } else {
                    installNativeCrashHandler()
                }
                native
            } catch (e: UnsatisfiedLinkError) {
                Logger.e(TAG, "Failed to load libtabssh_native.so", e)
                false
            } catch (e: Throwable) {
                Logger.e(TAG, "Unexpected error loading SPICE native library", e)
                false
            }
            available = result
            return result
        }
    }

    /**
     * Installs a process-wide native signal handler (SIGSEGV/SIGABRT/
     * SIGBUS/SIGILL/SIGFPE) so a fault inside the SPICE native stack —
     * the only in-process native code this app loads; Tor and Mosh run
     * as separate subprocesses and cannot crash this process — writes a
     * minimal crash marker before re-raising the signal, instead of
     * silently dying with nothing in the Debug Log. Never throws; a
     * failure here just means native crashes stay undiagnosable, same
     * as before this existed.
     */
    private fun installNativeCrashHandler() {
        val markerFile = nativeCrashMarkerFile() ?: return
        try {
            val installed = nativeInstallCrashHandler(markerFile.absolutePath) == 1
            if (!installed) {
                Logger.w(TAG, "Native crash handler failed to install")
            }
        } catch (e: Throwable) {
            Logger.w(TAG, "Native crash handler install threw", e)
        }
    }

    /**
     * Returns 1 when the native library was compiled with
     * `TABSSH_SPICE_AVAILABLE=1`, 0 otherwise. Implemented in
     * `deps/prereqs/spice/cpp/spice_client.c`.
     */
    @JvmStatic
    private external fun nativeIsSpiceAvailable(): Int

    /**
     * Installs the async-signal-safe crash handler described above,
     * writing its marker to [path]. Returns 1 on success, 0 on failure
     * (e.g. the marker file could not be opened). Implemented in
     * `deps/prereqs/spice/cpp/spice_client.c`.
     */
    @JvmStatic
    private external fun nativeInstallCrashHandler(path: String): Int
}
