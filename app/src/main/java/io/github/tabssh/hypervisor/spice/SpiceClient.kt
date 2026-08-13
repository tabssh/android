package io.github.tabssh.hypervisor.spice

import io.github.tabssh.hypervisor.console.ConsoleDisconnectReason
import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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

        /**
         * Largest pixel count accepted from a native framebuffer callback.
         * Guards the `width * height` products below against a hostile
         * server-declared geometry overflowing Int before it is used as an
         * array bound.
         */
        private const val MAX_PIXELS = SpiceConstants.MAX_DIMENSION.toLong() *
            SpiceConstants.MAX_DIMENSION.toLong()
    }

    /**
     * Opaque C-side pointer to the `tabssh_spice_session` struct, or
     * `0L` when no session is currently allocated.
     *
     * Atomic rather than `@Volatile`: [stop], [onNativeError] and
     * [onNativeDisconnected] can all run concurrently (UI thread vs. the
     * native glib worker), and a plain read-then-zero lets two of them
     * observe the same non-zero handle and call `nativeDestroySession`
     * twice on it. `getAndSet(0L)` makes exactly one caller the owner of
     * the teardown.
     */
    private val nativeHandle = AtomicLong(0L)

    /**
     * Guards the handle against use-after-free across the JNI boundary.
     * Input senders take the read lock (many concurrent senders are fine);
     * whoever wins the [nativeHandle] exchange takes the write lock before
     * destroying, so no `nativeSendXxx` can still be inside C code holding
     * a handle that is about to be freed.
     */
    private val handleLock = ReentrantReadWriteLock()

    private val running = AtomicBoolean(false)

    /**
     * Session-end hook for the tab close-policy gate. Fired exactly once when
     * the native layer ends the session while it was still running — i.e. any
     * end that is NOT a user-initiated [stop] (stop() flips [running] first,
     * so the native disconnect callback it triggers never fires this). Native
     * already discriminates: onNativeDisconnected → CLEAN, onNativeError → ERROR.
     * Lives on the client so it survives view recycling, like the RFB hook.
     */
    @Volatile var onSessionEnded: ((ConsoleDisconnectReason, String) -> Unit)? = null

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
        nativeHandle.set(handle)
        val started = try {
            nativeStartSession(handle, this)
        } catch (t: Throwable) {
            Logger.e(TAG, "nativeStartSession threw", t)
            destroyOwned(nativeHandle.getAndSet(0L))
            running.set(false)
            listener?.onError("Failed to start SPICE session: ${t.message}")
            return false
        }
        if (!started) {
            destroyOwned(nativeHandle.getAndSet(0L))
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
        val handle = nativeHandle.getAndSet(0L)
        if (handle == 0L) return
        try { nativeStopSession(handle) } catch (t: Throwable) {
            Logger.w(TAG, "nativeStopSession threw", t)
        }
        destroyOwned(handle)
        Logger.i(TAG, "SPICE session stopped")
    }

    /**
     * Free a handle this caller exclusively claimed from [nativeHandle].
     * Takes the write lock so any in-flight `nativeSendXxx` on another
     * thread has left C code before the session struct goes away.
     */
    private fun destroyOwned(handle: Long) {
        if (handle == 0L) return
        handleLock.write {
            try { nativeDestroySession(handle) } catch (t: Throwable) {
                Logger.w(TAG, "nativeDestroySession threw", t)
            }
        }
    }

    /**
     * Run [block] with a live handle held stable for its duration, or do
     * nothing when the session is already torn down. Every input path goes
     * through here so no scancode or pointer event can reach a freed
     * session struct.
     */
    private inline fun withHandle(what: String, block: (Long) -> Unit) {
        handleLock.read {
            val handle = nativeHandle.get()
            if (handle == 0L) return
            try { block(handle) } catch (t: Throwable) {
                Logger.w(TAG, "$what threw", t)
            }
        }
    }

    /**
     * Send a PS/2 scancode to the guest inputs channel. [down] is
     * `true` for key-press, `false` for key-release. Matches the
     * `spice_inputs_channel_key_press` / `_key_release` API.
     */
    fun sendKeyEvent(scancode: Int, down: Boolean) {
        withHandle("nativeSendKeyEvent") { handle ->
            nativeSendKeyEvent(handle, scancode, down)
        }
    }

    /**
     * Move the guest pointer to absolute coordinates [x], [y] with
     * [buttonMask] bits set for currently-held buttons (bit 0 = left,
     * bit 1 = middle, bit 2 = right, bit 3 = scroll-up, bit 4 =
     * scroll-down). Matches the SPICE `motion` message convention.
     */
    fun sendPointerMove(x: Int, y: Int, buttonMask: Int) {
        withHandle("nativeSendPointerMove") { handle ->
            nativeSendPointerMove(handle, x, y, buttonMask)
        }
    }

    /**
     * Report a pointer button transition. [button] is the SPICE mouse
     * button ID (see [SpiceConstants.BTN_LEFT] etc.); [buttonState] is
     * the mask of buttons still held after this transition (same bit
     * layout as [sendPointerMove]'s buttonMask); [down] is true for
     * press, false for release.
     */
    fun sendPointerButton(button: Int, buttonState: Int, down: Boolean) {
        withHandle("nativeSendPointerButton") { handle ->
            nativeSendPointerButton(handle, button, buttonState, down)
        }
    }

    /**
     * Send UTF-8 clipboard text to the guest agent. No-op if the
     * agent has not connected yet — the C side drops the payload and
     * logs a debug line so callers do not need to gate on
     * [SpiceListener.onAgentConnected] themselves.
     */
    fun sendClipboardText(text: String) {
        withHandle("nativeSendClipboardText") { handle ->
            nativeSendClipboardText(handle, text)
        }
    }

    // Callbacks invoked from JNI. Kept package-private so nothing
    // outside this file's translation unit can spoof them, and
    // annotated @JvmName to keep the mangled name stable across
    // Kotlin compiler upgrades — the C side hardcodes these names.
    //
    // Everything arriving here is wire-derived and therefore hostile:
    // the geometry and the payload length both come from the SPICE
    // server. Each callback validates its own arguments and drops the
    // event rather than forwarding a self-inconsistent update that would
    // blow up inside Bitmap.setPixels on the UI thread.

    /**
     * True when [width] x [height] is a plausible display geometry and
     * [framebuffer] is large enough to actually hold it.
     */
    private fun isValidSurface(width: Int, height: Int, framebuffer: IntArray): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > SpiceConstants.MAX_DIMENSION || height > SpiceConstants.MAX_DIMENSION) return false
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_PIXELS) return false
        return framebuffer.size.toLong() >= pixels
    }

    @Suppress("unused")
    @JvmName("onNativeConnected")
    internal fun onNativeConnected(width: Int, height: Int, name: String, framebuffer: IntArray) {
        if (!isValidSurface(width, height, framebuffer)) {
            Logger.w(TAG, "onNativeConnected: rejecting bad geometry ${width}x$height " +
                "(framebuffer=${framebuffer.size})")
            listener?.onError("SPICE server announced an invalid display size")
            return
        }
        listener?.onConnected(width, height, name, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeFramebufferUpdate")
    internal fun onNativeFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray) {
        if (x < 0 || y < 0 || !isValidSurface(w, h, framebuffer)) {
            Logger.w(TAG, "onNativeFramebufferUpdate: dropping bad rect ${w}x$h@$x,$y " +
                "(framebuffer=${framebuffer.size})")
            return
        }
        listener?.onFramebufferUpdate(x, y, w, h, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeDesktopResize")
    internal fun onNativeDesktopResize(width: Int, height: Int, framebuffer: IntArray) {
        if (!isValidSurface(width, height, framebuffer)) {
            Logger.w(TAG, "onNativeDesktopResize: dropping bad geometry ${width}x$height " +
                "(framebuffer=${framebuffer.size})")
            return
        }
        listener?.onDesktopResize(width, height, framebuffer)
    }

    @Suppress("unused")
    @JvmName("onNativeCursorUpdate")
    internal fun onNativeCursorUpdate(hotX: Int, hotY: Int, w: Int, h: Int,
                                       pixels: IntArray, mask: ByteArray) {
        if (!isValidSurface(w, h, pixels)) {
            Logger.w(TAG, "onNativeCursorUpdate: dropping bad cursor ${w}x$h (pixels=${pixels.size})")
            return
        }
        if (hotX < 0 || hotY < 0 || hotX >= w || hotY >= h) {
            Logger.w(TAG, "onNativeCursorUpdate: dropping out-of-range hotspot $hotX,$hotY in ${w}x$h")
            return
        }
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
        // A native error is terminal; claim the handle so subsequent
        // calls are no-ops even before the caller notices. getAndSet is
        // what makes this safe against a concurrent stop() — only one of
        // the two ever sees a non-zero handle, so the session is
        // destroyed exactly once.
        val handle = nativeHandle.getAndSet(0L)
        // CAS instead of set: true→false means the session died on its own,
        // false means stop() already claimed the shutdown (user-initiated).
        val wasRunning = running.compareAndSet(true, false)
        listener?.onError(message)
        destroyOwned(handle)
        if (wasRunning) {
            onSessionEnded?.invoke(ConsoleDisconnectReason.ERROR, message)
        }
    }

    @Suppress("unused")
    @JvmName("onNativeDisconnected")
    internal fun onNativeDisconnected(reason: String) {
        val handle = nativeHandle.getAndSet(0L)
        // CAS instead of set: only a still-running session reports CLEAN —
        // the disconnect triggered by stop()'s own nativeStopSession is
        // user-initiated and must not reach the close-policy gate.
        val wasRunning = running.compareAndSet(true, false)
        listener?.onDisconnected(reason)
        destroyOwned(handle)
        if (wasRunning) {
            onSessionEnded?.invoke(ConsoleDisconnectReason.CLEAN, reason)
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
    private external fun nativeSendPointerButton(handle: Long, button: Int, buttonState: Int, down: Boolean)
    private external fun nativeSendClipboardText(handle: Long, text: String)
}
