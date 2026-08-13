package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.tabssh.hypervisor.spice.SpiceConstants
import io.github.tabssh.hypervisor.spice.SpiceKeyMap
import io.github.tabssh.hypervisor.spice.SpiceListener
import io.github.tabssh.utils.logging.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-based SPICE framebuffer view.
 *
 * Structural twin of [VncView], but drives the SPICE inputs channel
 * (PS/2 scancodes + numeric button IDs) instead of RFB (X11 keysyms +
 * pointer bitmask). Everything that is not protocol-specific — the
 * viewport transform, pinch/pan/scroll gestures, IME plumbing, and
 * bitmap access rules — is intentionally identical so a future refactor
 * can hoist the common parts into a shared base without a behaviour
 * change for either side.
 *
 * Scaling model:
 *  - "fit" scale: bitmap scaled uniformly to fill the view (initial state).
 *  - [userScale]: multiplier applied on top of fit scale via pinch gesture.
 *    Range [0.5 … 4.0].
 *  - [panX] / [panY]: viewport offset in bitmap pixels (clamped to edges).
 *
 * Rendering happens on the main thread triggered by [postInvalidate]
 * from the SPICE worker thread. Bitmap access is guarded by [fbLock].
 *
 * Threading contract:
 *  - [SpiceListener] callbacks arrive on the native worker; every one
 *    of them touches the bitmap under [fbLock] and defers the redraw
 *    with [postInvalidate], which is thread-safe.
 *  - The three input emitters ([onKeyEvent], [onPointerMove],
 *    [onPointerButton]) fire on whichever thread the touch/keyboard
 *    event was dispatched — usually the main thread. The consuming
 *    [io.github.tabssh.hypervisor.spice.SpiceClient] is documented to
 *    accept calls from any thread, so no marshalling is done here.
 */
class SpiceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val TAG = "SpiceView"
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 4.0f
        /* Wheel pointer events emitted per scroll fling step. */
        private const val SCROLL_STEPS = 3
        // Longer than GestureDetector's own long-press timeout so the existing
        // hold-to-right-click gesture fires first; only a hold past this point
        // opens the shared session context menu (same menu TerminalView opens).
        private const val CONTEXT_MENU_TIMEOUT_MS = 900L
    }

    // ── Framebuffer ──────────────────────────────────────────────────────

    private val fbLock = Any()
    private var bitmap: Bitmap? = null

    // Written by the native SPICE worker under fbLock, read without the lock
    // by the gesture, layout and coordinate-mapping code on the main thread.
    // @Volatile is what makes those unlocked reads see a whole, current value
    // instead of a torn pair after a desktop resize.
    @Volatile
    private var fbWidth = 0

    @Volatile
    private var fbHeight = 0

    // ── Viewport transform ───────────────────────────────────────────────

    /** Fit-to-view scale (recomputed on layout and resize). */
    private var fitScale = 1.0f
    /** User-applied zoom multiplier (1.0 = no extra zoom). */
    private var userScale = 1.0f
    /** Viewport pan offset in bitmap pixels. */
    private var panX = 0f
    private var panY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    // ── Input callbacks ──────────────────────────────────────────────────

    /**
     * Fired for every keyboard event. Arguments are the PS/2 scancode
     * (extended codes have 0xE0 in the high byte) and press/release
     * flag. Modifier keys are emitted as separate events.
     */
    var onKeyEvent: ((Int, Boolean) -> Unit)? = null

    /**
     * Fired on pointer motion. Arguments are absolute framebuffer
     * coordinates and the current mask of held buttons — the SPICE
     * position message needs both.
     */
    var onPointerMove: ((Int, Int, Int) -> Unit)? = null

    /**
     * Fired on button transitions. Arguments are the SPICE button ID
     * (see [SpiceConstants.BTN_LEFT] etc.), the resulting button-state
     * mask after the transition, and press/release flag.
     */
    var onPointerButton: ((Int, Int, Boolean) -> Unit)? = null

    /** Called when the Android soft keyboard (IME) commits text. */
    var onTextInput: ((String) -> Unit)? = null

    /**
     * Called when the Android soft keyboard (IME) deletes a character
     * before the cursor. Wired to a synthetic Backspace scancode by
     * the default IME connection.
     */
    var onBackspace: (() -> Unit)? = null

    /**
     * Called with (x, y) when the user holds a single-finger touch past
     * [CONTEXT_MENU_TIMEOUT_MS] — opens the same shared session context
     * menu TerminalView's long-press opens. Fires in addition to (after)
     * the shorter-timeout right-click gesture already bound to long-press,
     * so a plain long-press still right-clicks and only a longer hold
     * surfaces the menu.
     */
    var onContextMenuRequested: ((Float, Float) -> Unit)? = null

    // ── Pointer bookkeeping ──────────────────────────────────────────────

    /** Bitmask of buttons currently held. Mirrors SPICE's `state` byte. */
    private var currentButtonMask = 0

    /**
     * SPICE button ID of the press currently in progress. Remembered at
     * ACTION_DOWN so the matching release reports the same button — a
     * hardware mouse's right/middle press must not be released as a left.
     */
    private var pressedButton = SpiceConstants.BTN_LEFT

    private val contextMenuHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var contextMenuRunnable: Runnable? = null
    private var contextMenuDownX = 0f
    private var contextMenuDownY = 0f
    private val contextMenuTouchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            /*
             * Pointer down/up for this tap is already fired by
             * onTouchEvent's ACTION_DOWN / ACTION_UP branches. Focus +
             * show the IME here too, for UX parity with TerminalView's
             * single-tap keyboard behaviour (TerminalView.
             * onSingleTapConfirmed / toggleKeyboard) — a tap inside the
             * console should raise the keyboard the same way a tap
             * inside a terminal does. The console toolbar's keyboard
             * button remains available to hide it again.
             */
            requestSoftKeyboard()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            /* Toggle between fit-to-screen and 2× zoom. */
            userScale = if (userScale > 1.05f) 1.0f else 2.0f
            panX = 0f
            panY = 0f
            recomputeFitScale()
            postInvalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (bx, by) = screenToBitmap(e.x, e.y)
            onPointerMove?.invoke(bx, by, currentButtonMask)
            onPointerButton?.invoke(SpiceConstants.BTN_RIGHT,
                                     currentButtonMask or SpiceConstants.MASK_RIGHT, true)
            postDelayed({
                onPointerButton?.invoke(SpiceConstants.BTN_RIGHT, currentButtonMask, false)
            }, 100)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distX: Float,
            distY: Float,
        ): Boolean {
            if (e2.pointerCount > 1) return false
            if (userScale > 1.05f) {
                /*
                 * Pan the viewport rather than emit scroll events.
                 * onDraw and screenToBitmap both place the bitmap with a
                 * CENTRED origin, so pan 0 is the centre of the image and the
                 * legal range is symmetric about it. The old top-left bounds
                 * (0 .. fbWidth - viewport) let the user pan a full half-
                 * viewport past the right/bottom edge and made the left/top
                 * half unreachable.
                 */
                val extX = max(0f, (fbWidth - width / currentScale()) / 2f)
                val extY = max(0f, (fbHeight - height / currentScale()) / 2f)
                panX = (panX + distX / currentScale()).coerceIn(-extX, extX)
                panY = (panY + distY / currentScale()).coerceIn(-extY, extY)
                postInvalidate()
            } else {
                val steps = (distY / 40f).toInt().coerceIn(-SCROLL_STEPS, SCROLL_STEPS)
                val (bx, by) = screenToBitmap(e2.x, e2.y)
                val button = if (steps < 0) SpiceConstants.BTN_UP else SpiceConstants.BTN_DOWN
                repeat(abs(steps)) {
                    // Position the pointer first: SPICE routes a wheel click to
                    // whatever is under the last reported position, so without
                    // this the scroll lands wherever the pointer was left.
                    onPointerMove?.invoke(bx, by, currentButtonMask)
                    onPointerButton?.invoke(button, currentButtonMask, true)
                    onPointerButton?.invoke(button, currentButtonMask, false)
                }
            }
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userScale = (userScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            recomputeFitScale()
            postInvalidate()
            return true
        }
    })

    // ── SpiceListener adapter ────────────────────────────────────────────

    /**
     * Returns a [SpiceListener] that feeds decoded frames into this
     * view. Wire it up before starting the [SpiceClient]. Clipboard
     * routing mirrors [VncView.asRfbListener] — incoming clipboard
     * text goes through [io.github.tabssh.utils.ClipboardHelper] so
     * any pending sensitive-clear timer is cancelled.
     */
    fun asSpiceListener(): SpiceListener = object : SpiceListener {
        override fun onConnected(width: Int, height: Int, name: String, framebuffer: IntArray) {
            Logger.i(TAG, "SPICE connected: ${width}x$height '$name'")
            if (!allocateSurface(width, height, framebuffer)) return
            post {
                recomputeFitScale()
                requestLayout()
                postInvalidate()
            }
        }

        override fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray) {
            synchronized(fbLock) {
                val bmp = bitmap ?: return
                // setPixels throws rather than clipping, and an uncaught throw
                // on the native worker thread takes the whole session (and,
                // from JNI, potentially the process) down. The rect is
                // server-supplied, so validate it against the surface we
                // actually allocated and drop the single update on mismatch.
                val fits = w > 0 && h > 0 && x >= 0 && y >= 0 &&
                    bmp.width == fbWidth && bmp.height == fbHeight &&
                    x + w <= fbWidth && y + h <= fbHeight &&
                    framebuffer.size >= (y + h - 1) * fbWidth + x + w
                if (!fits) {
                    Logger.w(TAG, "Dropping update ($x,$y) ${w}x$h — does not fit ${fbWidth}x$fbHeight")
                    return
                }
                bmp.setPixels(framebuffer, y * fbWidth + x, fbWidth, x, y, w, h)
            }
            postInvalidate()
        }

        override fun onDesktopResize(width: Int, height: Int, framebuffer: IntArray) {
            Logger.i(TAG, "SPICE desktop resize: ${width}x$height")
            if (!allocateSurface(width, height, framebuffer)) return
            post {
                recomputeFitScale()
                requestLayout()
                postInvalidate()
            }
        }

        override fun onAgentConnected() {
            Logger.i(TAG, "SPICE agent connected — clipboard sync available")
        }

        override fun onClipboardText(text: String) {
            io.github.tabssh.utils.ClipboardHelper.copy(
                context, label = "SPICE clipboard", text = text, sensitive = false)
        }

        override fun onError(message: String) {
            Logger.e(TAG, "SPICE error: $message")
        }

        override fun onDisconnected(reason: String) {
            Logger.i(TAG, "SPICE disconnected: $reason")
        }
    }

    /**
     * (Re)allocate the framebuffer bitmap for a server-announced geometry
     * and paint [framebuffer] into it when it carries real pixels.
     *
     * Returns false — leaving the current surface untouched — when the
     * geometry is not usable. The SPICE server is a trust boundary: an
     * out-of-range or overflowing width/height would otherwise reach
     * `Bitmap.createBitmap` as an OOM or a negative allocation, and
     * `width * height` in Int silently wraps for large values, so the
     * bound is checked in Long.
     */
    private fun allocateSurface(width: Int, height: Int, framebuffer: IntArray): Boolean {
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 ||
            width > SpiceConstants.MAX_DIMENSION || height > SpiceConstants.MAX_DIMENSION
        ) {
            Logger.w(TAG, "Rejecting SPICE geometry ${width}x$height — out of range")
            return false
        }
        synchronized(fbLock) {
            val dimsMatch = bitmap != null && fbWidth == width && fbHeight == height
            // Reuse the existing bitmap on an unchanged geometry so the last
            // decoded frame stays on screen across a reconnect instead of
            // flashing black until the first full update paints.
            if (!dimsMatch) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fbWidth = width
                fbHeight = height
            }
            // A fresh connect passes an all-zero framebuffer (real pixels
            // arrive later via onFramebufferUpdate); painting it would blank
            // a retained frame for nothing.
            if (framebuffer.size.toLong() >= pixels && framebuffer.any { it != 0 }) {
                bitmap?.setPixels(framebuffer, 0, width, 0, 0, width, height)
            }
        }
        return true
    }

    // ── View-size callback ───────────────────────────────────────────────

    /**
     * Fired on the main thread whenever the view is measured with
     * non-zero dimensions.
     *
     * Purely informational for now: the native bridge exposes no
     * monitors-config entry point, so nothing here resizes the guest. The
     * host may use it to report or record the viewport size.
     */
    var onViewSizeReady: ((width: Int, height: Int) -> Unit)? = null

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFitScale()
        if (bitmap != null) invalidate()
        if (w > 0 && h > 0) onViewSizeReady?.invoke(w, h)
    }

    private fun recomputeFitScale() {
        if (fbWidth <= 0 || fbHeight <= 0 || width <= 0 || height <= 0) return
        fitScale = min(width.toFloat() / fbWidth, height.toFloat() / fbHeight)
    }

    private fun currentScale() = fitScale * userScale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = currentScale()
        // The whole draw runs under fbLock. The native SPICE worker recycles
        // and replaces this bitmap (onConnected / onDesktopResize) and writes
        // into it (onFramebufferUpdate) under the same lock; releasing the
        // lock after merely reading the reference let the worker recycle the
        // bitmap between that read and drawBitmap ("trying to use a recycled
        // bitmap") and let a concurrent setPixels tear the frame.
        synchronized(fbLock) {
            val bmp = bitmap ?: return
            val originX = (width - fbWidth * scale) / 2f - panX * scale
            val originY = (height - fbHeight * scale) / 2f - panY * scale
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(originX, originY)
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Grab focus immediately so hardware key events and the IME
                // route to this view without requiring the tap to finish
                // first (mirrors TerminalView.onTouchEvent's ACTION_DOWN).
                requestFocus()
                val (bx, by) = screenToBitmap(event.x, event.y)
                // A real mouse reports which physical button went down, so its
                // right/middle clicks reach the guest as right/middle instead
                // of being flattened to a left click. A finger reports no
                // button state at all, which stays a left click.
                val button = pressedMouseButton(event)
                pressedButton = button
                currentButtonMask = currentButtonMask or maskFor(button)
                onPointerMove?.invoke(bx, by, currentButtonMask)
                onPointerButton?.invoke(button, currentButtonMask, true)
                armContextMenuTimer(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val (bx, by) = screenToBitmap(event.x, event.y)
                    onPointerMove?.invoke(bx, by, currentButtonMask)
                    if (abs(event.x - contextMenuDownX) > contextMenuTouchSlop ||
                        abs(event.y - contextMenuDownY) > contextMenuTouchSlop
                    ) {
                        cancelContextMenuTimer()
                    }
                } else {
                    cancelContextMenuTimer()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                val button = pressedButton
                pressedButton = SpiceConstants.BTN_LEFT
                currentButtonMask = currentButtonMask and maskFor(button).inv()
                onPointerButton?.invoke(button, currentButtonMask, false)
                onPointerMove?.invoke(bx, by, currentButtonMask)
                cancelContextMenuTimer()
            }
        }
        return true
    }

    /**
     * Handle events from a real pointing device (USB/Bluetooth mouse,
     * DeX/ChromeOS trackpad). Android delivers wheel scrolls and hover moves
     * here, never through [onTouchEvent], so without this the wheel did
     * nothing and the guest cursor did not follow the mouse.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                val vs = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                // SPICE has no scroll amount: each wheel notch is a press and
                // release of button 4 (up) or 5 (down). There is no horizontal
                // wheel button in the SPICE mouse enum, so AXIS_HSCROLL has
                // nothing to map onto and is deliberately ignored.
                if (vs != 0f) {
                    val button = if (vs > 0f) SpiceConstants.BTN_UP else SpiceConstants.BTN_DOWN
                    val notches = kotlin.math.ceil(abs(vs).toDouble()).toInt()
                        .coerceIn(1, SCROLL_STEPS)
                    repeat(notches) {
                        onPointerMove?.invoke(bx, by, currentButtonMask)
                        onPointerButton?.invoke(button, currentButtonMask, true)
                        onPointerButton?.invoke(button, currentButtonMask, false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                onPointerMove?.invoke(bx, by, mouseButtonMask(event))
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Translate Android's mouse button state into a SPICE state mask. */
    private fun mouseButtonMask(event: MotionEvent): Int {
        var mask = 0
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or SpiceConstants.MASK_LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or SpiceConstants.MASK_MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or SpiceConstants.MASK_RIGHT
        return mask
    }

    /**
     * SPICE button ID for the button that went down in [event]. Falls back
     * to the left button for touch events, which report no button state.
     */
    private fun pressedMouseButton(event: MotionEvent): Int {
        val state = event.buttonState
        return when {
            state and MotionEvent.BUTTON_SECONDARY != 0 -> SpiceConstants.BTN_RIGHT
            state and MotionEvent.BUTTON_TERTIARY != 0 -> SpiceConstants.BTN_MIDDLE
            else -> SpiceConstants.BTN_LEFT
        }
    }

    /** State-mask bit corresponding to a SPICE button ID. */
    private fun maskFor(button: Int): Int = when (button) {
        SpiceConstants.BTN_MIDDLE -> SpiceConstants.MASK_MIDDLE
        SpiceConstants.BTN_RIGHT -> SpiceConstants.MASK_RIGHT
        else -> SpiceConstants.MASK_LEFT
    }

    private fun armContextMenuTimer(x: Float, y: Float) {
        cancelContextMenuTimer()
        contextMenuDownX = x
        contextMenuDownY = y
        val runnable = Runnable {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onContextMenuRequested?.invoke(x, y)
        }
        contextMenuRunnable = runnable
        contextMenuHandler.postDelayed(runnable, CONTEXT_MENU_TIMEOUT_MS)
    }

    private fun cancelContextMenuTimer() {
        contextMenuRunnable?.let { contextMenuHandler.removeCallbacks(it) }
        contextMenuRunnable = null
    }

    private fun screenToBitmap(sx: Float, sy: Float): Pair<Int, Int> {
        val scale = currentScale()
        val originX = (width - fbWidth * scale) / 2f - panX * scale
        val originY = (height - fbHeight * scale) / 2f - panY * scale
        val bx = ((sx - originX) / scale).toInt().coerceIn(0, max(0, fbWidth - 1))
        val by = ((sy - originY) / scale).toInt().coerceIn(0, max(0, fbHeight - 1))
        return Pair(bx, by)
    }

    /**
     * Focus this view and show the soft keyboard, unless a hardware
     * keyboard is currently attached (matches TabTerminalActivity's
     * `hasHardwareKeyboard()` gate so a Bluetooth/USB keyboard user isn't
     * interrupted by a redundant IME popup on every tap).
     */
    private fun requestSoftKeyboard() {
        val cfg = resources.configuration
        val hasHardwareKeyboard = cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
        if (hasHardwareKeyboard) return
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Keyboard ─────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = SpiceKeyMap.translate(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        if (t.needsShift) onKeyEvent?.invoke(SpiceConstants.SC_LEFT_SHIFT, true)
        onKeyEvent?.invoke(t.scancode, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val t = SpiceKeyMap.translate(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        onKeyEvent?.invoke(t.scancode, false)
        if (t.needsShift) onKeyEvent?.invoke(SpiceConstants.SC_LEFT_SHIFT, false)
        return true
    }

    /**
     * Send a raw scancode directly (for on-screen keyboard bars and
     * modifier toggles). Does not synthesise a shift bracket — the
     * caller is expected to handle modifier state themselves.
     */
    fun sendScancode(scancode: Int, down: Boolean) {
        onKeyEvent?.invoke(scancode, down)
    }

    /**
     * Send a Unicode character as a PS/2 scancode pair, bracketed with
     * shift make/break when the character requires it. Returns false
     * when the character has no scancode mapping — non-Latin scripts
     * should be delivered via the SPICE agent clipboard channel
     * instead.
     */
    fun sendChar(ch: Char): Boolean {
        val t = SpiceKeyMap.translateChar(ch) ?: return false
        if (t.needsShift) onKeyEvent?.invoke(SpiceConstants.SC_LEFT_SHIFT, true)
        onKeyEvent?.invoke(t.scancode, true)
        onKeyEvent?.invoke(t.scancode, false)
        if (t.needsShift) onKeyEvent?.invoke(SpiceConstants.SC_LEFT_SHIFT, false)
        return true
    }

    init {
        /*
         * Must be focusable so the view can receive hardware key events
         * and so the Android IME will attach to it when focused.
         */
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ── IME / soft-keyboard support ──────────────────────────────────────

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Return an [InputConnection] that routes soft-keyboard input to
     * the active SPICE session. Design mirrors [VncView] — raw
     * terminal input type, no auto-correct, no fullscreen extract UI —
     * so the on-screen keyboard behaves identically across VNC and
     * SPICE consoles.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                              EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (text.isNotEmpty()) onTextInput?.invoke(text.toString())
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { onBackspace?.invoke() }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DEL) {
                    onBackspace?.invoke()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    // ── Zoom helpers ─────────────────────────────────────────────────────

    /** Reset zoom and pan to fit-to-screen (same as first load). */
    fun resetZoom() {
        userScale = 1.0f
        panX = 0f
        panY = 0f
        recomputeFitScale()
        postInvalidate()
    }

    /** Zoom to 1:1 pixel mapping (userScale = 1 / fitScale). */
    fun zoomActual() {
        if (fitScale > 0f) userScale = 1f / fitScale
        panX = 0f
        panY = 0f
        postInvalidate()
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    fun recycle() {
        onViewSizeReady = null
        synchronized(fbLock) {
            bitmap?.recycle()
            bitmap = null
        }
    }
}
