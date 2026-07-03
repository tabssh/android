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
    }

    // ── Framebuffer ──────────────────────────────────────────────────────

    private val fbLock = Any()
    private var bitmap: Bitmap? = null
    private var fbWidth = 0
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

    // ── Pointer bookkeeping ──────────────────────────────────────────────

    /** Bitmask of buttons currently held. Mirrors SPICE's `state` byte. */
    private var currentButtonMask = 0

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            /*
             * Pointer down/up for this tap is already fired by
             * onTouchEvent's ACTION_DOWN / ACTION_UP branches — do not
             * toggle the keyboard here, which would show the IME on
             * every GUI button tap inside the SPICE session. Use the
             * console toolbar's keyboard button instead.
             */
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
                /* Pan the viewport rather than emit scroll events. */
                panX = (panX + distX / currentScale()).coerceIn(
                    0f, max(0f, fbWidth - width / currentScale()))
                panY = (panY + distY / currentScale()).coerceIn(
                    0f, max(0f, fbHeight - height / currentScale()))
                postInvalidate()
            } else {
                val steps = (distY / 40f).toInt().coerceIn(-SCROLL_STEPS, SCROLL_STEPS)
                val button = if (steps < 0) SpiceConstants.BTN_UP else SpiceConstants.BTN_DOWN
                repeat(abs(steps)) {
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
            synchronized(fbLock) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fbWidth = width
                fbHeight = height
                if (framebuffer.size >= width * height) {
                    bitmap?.setPixels(framebuffer, 0, width, 0, 0, width, height)
                }
            }
            post {
                recomputeFitScale()
                requestLayout()
                postInvalidate()
            }
        }

        override fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray) {
            synchronized(fbLock) {
                bitmap?.setPixels(framebuffer, y * fbWidth + x, fbWidth, x, y, w, h)
            }
            postInvalidate()
        }

        override fun onDesktopResize(width: Int, height: Int, framebuffer: IntArray) {
            Logger.i(TAG, "SPICE desktop resize: ${width}x$height")
            synchronized(fbLock) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fbWidth = width
                fbHeight = height
                if (framebuffer.size >= width * height) {
                    bitmap?.setPixels(framebuffer, 0, width, 0, 0, width, height)
                }
            }
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

    // ── View-size callback ───────────────────────────────────────────────

    /**
     * Fired on the main thread whenever the view is measured with
     * non-zero dimensions. The activity uses this to send a SPICE
     * agent monitors-config message so the guest resizes to match.
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
        val bmp = synchronized(fbLock) { bitmap } ?: return
        val scale = currentScale()
        val originX = (width - fbWidth * scale) / 2f - panX * scale
        val originY = (height - fbHeight * scale) / 2f - panY * scale
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(originX, originY)
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
    }

    // ── Touch ────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                currentButtonMask = currentButtonMask or SpiceConstants.MASK_LEFT
                onPointerMove?.invoke(bx, by, currentButtonMask)
                onPointerButton?.invoke(SpiceConstants.BTN_LEFT, currentButtonMask, true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val (bx, by) = screenToBitmap(event.x, event.y)
                    onPointerMove?.invoke(bx, by, currentButtonMask)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                currentButtonMask = currentButtonMask and SpiceConstants.MASK_LEFT.inv()
                onPointerButton?.invoke(SpiceConstants.BTN_LEFT, currentButtonMask, false)
                onPointerMove?.invoke(bx, by, currentButtonMask)
            }
        }
        return true
    }

    private fun screenToBitmap(sx: Float, sy: Float): Pair<Int, Int> {
        val scale = currentScale()
        val originX = (width - fbWidth * scale) / 2f - panX * scale
        val originY = (height - fbHeight * scale) / 2f - panY * scale
        val bx = ((sx - originX) / scale).toInt().coerceIn(0, max(0, fbWidth - 1))
        val by = ((sy - originY) / scale).toInt().coerceIn(0, max(0, fbHeight - 1))
        return Pair(bx, by)
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
