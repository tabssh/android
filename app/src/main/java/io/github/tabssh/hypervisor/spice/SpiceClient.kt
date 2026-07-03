package io.github.tabssh.hypervisor.spice

import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level SPICE session facade.
 *
 * Shape mirrors [io.github.tabssh.hypervisor.console.rfb.RfbClient] so
 * the display view (task #14) can drive VNC and SPICE through a common
 * rendering path. All heavy lifting lives in `libtabssh_native.so`;
 * this class is a Kotlin-side shell that manages the native handle
 * lifetime, guards against use-after-stop, and marshals the SPICE
 * availability check into a clean failure when the app was built
 * without libspice-client-glib prebuilts.
 *
 * Threading contract:
 * - [start] / [stop] are safe to call from any thread; both are
 *   idempotent.
 * - Input methods ([sendKeyEvent], [sendPointerMove],
 *   [sendPointerButton]) may be called from the UI thread and are
 *   forwarded straight to the native inputs channel — the JNI layer
 *   handles the g_main_context dispatch.
 * - [SpiceListener] callbacks fire from the native worker thread that
 *   runs the glib main loop; the implementation must marshal to the
 *   main thread itself before touching UI state.
 *
 * @param params Session parameters — see [SpiceConnectionParams].
 * @param listener Callback sink. May be replaced with `listener = ...`
 *   at any time; the latest value wins for every subsequent callback.
 */
class SpiceClient(
    private val params: SpiceConnectionParams,
    var listener: SpiceListener? = null,
) {
    companion object {
        private const val TAG = "SpiceClient"
    }

    /**
     * Opaque C-side pointer to the `tabssh_spice_session` struct, or
     * `0L` when no session is currently allocated. Written only from
     * within the [running] transition so start/stop are strictly
     * ordered; read from JNI callback paths that hold their own
     * global reference to this Kotlin object.
     */
    @Volatile
    private var nativeHandle: Long = 0L

    private val running = AtomicBoolean(false)

    /**
     * Attempt to open the session. Returns `false` if SPICE is
     * unavailable in this build, if the native library refused to
     * allocate a session, or if the transport handshake failed to
     * start. On failure the client remains in a stopped state and may
     * not be reused — construct a fresh [SpiceClient] to retry.
     */
    fun start(): Boolean {
        if (!SpiceLoader.isSpiceAvailable()) {
            Logger.w(TAG, "start(): SPICE unavailable in this build")
            listener?.onError("SPICE is not available in this build of TabSSH")
            return false
        }
        if (!running.compareAndSet(false, true)) {
            Logger.w(TAG, "start(): already running, ignoring")
            return true
        }
        val handle = try {
            nativeCreateSession(
                params.host,
                params.port,
                params.tlsPort,
                params.password,
                params.caCert,
                params.hostSubject,
                params.tlsVerify,
            )
        } catch (t: Throwable) {
            Logger.e(TAG, "nativeCreateSession threw", t)
            running.set(false)
            listener?.onError("Failed to create SPICE session: ${t.message}")
            return false
        }
        if (handle == 0L) {
            running.set(false)
            listener?.onError("Failed to allocate SPICE session")
            return false
        }
        nativeHandle = handle
        val started = try {
            nativeStartSession(handle, this)
        } catch (t: Throwable) {
            Logger.e(TAG, "nativeStartSession threw", t)
            nativeHandle = 0L
            try { nativeDestroySession(handle) } catch (_: Throwable) {}
            running.set(false)
            listener?.onError("Failed to start SPICE session: ${t.message}")
            return false
        }
        if (!started) {
            nativeHandle = 0L
            try { nativeDestroySession(handle) } catch (_: Throwable) {}
            running.set(false)
            listener?.onError("SPICE session refused to start")
            return false
        }
        Logger.i(TAG, "SPICE session started (handle=0x${handle.toString(16)})")
        return true
    }

    /**
     * Close the session. Safe to call multiple times; only the first
     * call does any work. After [stop] returns, [SpiceListener] will
     * not receive any further callbacks and [start] cannot be reused.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle == 0L) return
        try { nativeStopSession(handle) } catch (t: Throwable) {
            Logger.w(TAG, "nativeStopSession threw", t)
        }
        try { nativeDestroySession(handle) } catch (t: Throwable) {
            Logger.w(TAG, "nativeDestroySession threw", t)
        }
        Logger.i(TAG, "SPICE session stopped")
    }

    /**
     * Send a PS/2 scancode to the guest inputs channel. [down] is
     * `true` for key-press, `false` for key-release. Matches the
     * `spice_inputs_channel_key_press` / `_key_release` API.
     */
    fun sendKeyEvent(scancode: Int, down: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        try { nativeSendKeyEvent(handle, scancode, down) } catch (t: Throwable) {
            Logger.w(TAG, "nativeSendKeyEvent threw", t)
        }
    }

    /**
     * Move the guest pointer to absolute coordinates [x], [y] with
     * [buttonMask] bits set for currently-held buttons (bit 0 = left,
     * bit 1 = middle, bit 2 = right, bit 3 = scroll-up, bit 4 =
     * scroll-down). Matches the SPICE `motion` message convention.
     */
    fun sendPointerMove(x: Int, y: Int, buttonMask: Int) {
        val handle = nativeHandle
        if (handle == 0L) return
        try { nativeSendPointerMove(handle, x, y, buttonMask) } catch (t: Throwable) {
            Logger.w(TAG, "nativeSendPointerMove threw", t)
        }
    }

    /**
     * Report a pointer button transition. [buttonMask] identifies the
     * button (same bit layout as [sendPointerMove]); [down] is true
     * for press, false for release.
     */
    fun sendPointerButton(buttonMask: Int, down: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        try { nativeSendPointerButton(handle, buttonMask, down) } catch (t: Throwable) {
            Logger.w(TAG, "nativeSendPointerButton threw", t)
        }
    }

    /**
     * Send UTF-8 clipboard text to the guest agent. No-op if the
     * agent has not connected yet — the C side drops the payload and
     * logs a debug line so callers do not need to gate on
     * [SpiceListener.onAgentConnected] themselves.
     */
    fun sendClipboardText(text: String) {
        val handle = nativeHandle
        if (handle == 0L) return
        try { nativeSendClipboardText(handle, text) } catch (t: Throwable) {
            Logger.w(TAG, "nativeSendClipboardText threw", t)
        }
    }

    // Callbacks invoked from JNI. Kept package-private so nothing
    // outside this file's translation unit can spoof them, and
    // annotated @JvmName to keep the mangled name stable across
    // Kotlin compiler upgrades — the C side hardcodes these names.

    @Suppress("unused")
    @JvmName("onNativeConnected")
    internal fun onNativeConnected(width: Int, height: Int, name: String, framebuffer: IntArray) {
        listener?.onConnected(width, height, name, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeFramebufferUpdate")
    internal fun onNativeFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray) {
        listener?.onFramebufferUpdate(x, y, w, h, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeDesktopResize")
    internal fun onNativeDesktopResize(width: Int, height: Int, framebuffer: IntArray) {
        listener?.onDesktopResize(width, height, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeCursorUpdate")
    internal fun onNativeCursorUpdate(hotX: Int, hotY: Int, w: Int, h: Int,
                                       pixels: IntArray, mask: ByteArray) {
        listener?.onCursorUpdate(hotX, hotY, w, h, pixels, mask)
    }

    @Suppress("unused")
    @JvmName("onNativeAgentConnected")
    internal fun onNativeAgentConnected() {
        listener?.onAgentConnected()
    }

    @Suppress("unused")
    @JvmName("onNativeClipboardText")
    internal fun onNativeClipboardText(text: String) {
        listener?.onClipboardText(text)
    }

    @Suppress("unused")
    @JvmName("onNativeError")
    internal fun onNativeError(message: String) {
        // A native error is terminal; drop the handle so subsequent
        // calls are no-ops even before the caller notices.
        val handle = nativeHandle
        nativeHandle = 0L
        running.set(false)
        listener?.onError(message)
        if (handle != 0L) {
            try { nativeDestroySession(handle) } catch (_: Throwable) {}
        }
    }

    @Suppress("unused")
    @JvmName("onNativeDisconnected")
    internal fun onNativeDisconnected(reason: String) {
        val handle = nativeHandle
        nativeHandle = 0L
        running.set(false)
        listener?.onDisconnected(reason)
        if (handle != 0L) {
            try { nativeDestroySession(handle) } catch (_: Throwable) {}
        }
    }

    private external fun nativeCreateSession(
        host: String,
        port: Int,
        tlsPort: Int,
        password: String,
        caCert: ByteArray?,
        hostSubject: String?,
        tlsVerify: Boolean,
    ): Long

    private external fun nativeStartSession(handle: Long, self: SpiceClient): Boolean
    private external fun nativeStopSession(handle: Long)
    private external fun nativeDestroySession(handle: Long)
    private external fun nativeSendKeyEvent(handle: Long, scancode: Int, down: Boolean)
    private external fun nativeSendPointerMove(handle: Long, x: Int, y: Int, buttonMask: Int)
    private external fun nativeSendPointerButton(handle: Long, buttonMask: Int, down: Boolean)
    private external fun nativeSendClipboardText(handle: Long, text: String)
}
