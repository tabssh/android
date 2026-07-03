package io.github.tabssh.hypervisor.spice

import io.github.tabssh.utils.logging.Logger

/**
 * Gatekeeper for the SPICE native library.
 *
 * Loads `libtabssh_native.so` exactly once and reports whether it was
 * built against a real libspice-client-glib prebuilt or the empty
 * scaffold that ships in fresh clones (see `libs/spice/README.md`).
 * Every SPICE code path — connector, JNI bindings, display view —
 * MUST call [isSpiceAvailable] first and take the VNC fallback when
 * it returns false. This lets a single APK ship to both
 * SPICE-enabled and SPICE-disabled builds without any [UnsatisfiedLinkError]
 * at runtime.
 *
 * Task #12 lands the loader alongside the CMake harness; the real
 * JNI bindings (connect, disconnect, framebuffer callbacks) land in
 * task #13 and hang off this same class.
 */
object SpiceLoader {
    private const val TAG = "SpiceLoader"

    /**
     * Result of the one-shot native library load. `null` until the
     * first call to [isSpiceAvailable]; `false` if either
     * `System.loadLibrary` threw or the native symbol reported no
     * SPICE prebuilts at build time.
     */
    @Volatile
    private var available: Boolean? = null

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
     * Returns 1 when the native library was compiled with
     * `TABSSH_SPICE_AVAILABLE=1`, 0 otherwise. Implemented in
     * `app/src/main/cpp/spice_stub.c`.
     */
    @JvmStatic
    private external fun nativeIsSpiceAvailable(): Int
}
