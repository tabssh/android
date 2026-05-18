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
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.console.rfb.RfbListener
import io.github.tabssh.utils.logging.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-based VNC framebuffer view.
 *
 * Renders a VNC server's pixel output via [RfbListener] callbacks.
 * Touch and keyboard events are translated back to RFB pointer/key events
 * and delivered through [onPointerEvent] / [onKeyEvent] lambdas.
 *
 * Scaling model:
 *  - "fit" scale: bitmap scaled uniformly to fill the view (initial state).
 *  - [userScale]: multiplier applied on top of fit scale via pinch gesture.
 *    Range [0.5 … 4.0].
 *  - [panX] / [panY]: viewport offset in bitmap pixels (clamped to edges).
 *
 * Rendering happens on the main thread triggered by [postInvalidate] from
 * the RFB reader thread.  Bitmap access is guarded by [fbLock].
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "VncView"
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 4.0f
        private const val SCROLL_STEPS = 3        // pointer events per scroll fling step
        private const val CLICK_TIMEOUT_MS = 200L // tap → click if released within
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
    /** Viewport pan offset in screen pixels. */
    private var panX = 0f
    private var panY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    // ── Input callbacks ───────────────────────────────────────────────────

    /** Called with (fbX, fbY, buttonMask). */
    var onPointerEvent: ((Int, Int, Int) -> Unit)? = null
    /** Called with (keysym, isDown). */
    var onKeyEvent: ((Long, Boolean) -> Unit)? = null
    /** Called when the Android soft keyboard (IME) commits text. */
    var onTextInput: ((String) -> Unit)? = null
    /** Called when the Android soft keyboard (IME) deletes a character before the cursor. */
    var onBackspace: (() -> Unit)? = null

    // ── Touch bookkeeping ─────────────────────────────────────────────────

    private var lastButtonMask = 0
    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0L

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (bx, by) = screenToBitmap(e.x, e.y)
            firePointer(bx, by, RfbConstants.BTN_LEFT)
            firePointer(bx, by, 0)
            // Toggle the soft keyboard so the user can type without needing a
            // separate button — show it if hidden, hide it if currently active.
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.isActive(this@VncView)) {
                imm.hideSoftInputFromWindow(windowToken, 0)
            } else {
                requestFocus()
                imm.showSoftInput(this@VncView, InputMethodManager.SHOW_IMPLICIT)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (bx, by) = screenToBitmap(e.x, e.y)
            firePointer(bx, by, RfbConstants.BTN_RIGHT)
            postDelayed({ firePointer(bx, by, 0) }, 100)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distX: Float,
            distY: Float
        ): Boolean {
            if (e2.pointerCount > 1) return false // handled by ScaleGestureDetector
            if (userScale > 1.05f) {
                // Pan the viewport
                panX = (panX + distX / currentScale()).coerceIn(
                    0f, max(0f, fbWidth - width / currentScale()))
                panY = (panY + distY / currentScale()).coerceIn(
                    0f, max(0f, fbHeight - height / currentScale()))
                postInvalidate()
            } else {
                // Send scroll wheel events to the VM
                val steps = (distY / 40f).toInt().coerceIn(-SCROLL_STEPS, SCROLL_STEPS)
                val (bx, by) = screenToBitmap(e2.x, e2.y)
                val btn = if (steps < 0) RfbConstants.BTN_SCROLL_UP else RfbConstants.BTN_SCROLL_DOWN
                repeat(kotlin.math.abs(steps)) {
                    firePointer(bx, by, btn)
                    firePointer(bx, by, 0)
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

    // ── RfbListener adapter ───────────────────────────────────────────────

    /**
     * Returns an [RfbListener] that feeds decoded frames into this view.
     * Wire it up before starting [io.github.tabssh.hypervisor.console.rfb.RfbClient].
     */
    fun asRfbListener(): RfbListener = object : RfbListener {
        override fun onConnected(width: Int, height: Int, name: String, framebuffer: IntArray) {
            Logger.i(TAG, "VNC connected: ${width}×$height '$name'")
            synchronized(fbLock) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fbWidth = width
                fbHeight = height
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
            Logger.i(TAG, "VNC desktop resize: ${width}×$height")
            synchronized(fbLock) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fbWidth = width
                fbHeight = height
            }
            post {
                recomputeFitScale()
                requestLayout()
                postInvalidate()
            }
        }

        override fun onBell() {
            // Haptic feedback on bell
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        override fun onClipboardText(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("VNC clipboard", text)
            )
        }

        override fun onError(message: String) {
            Logger.e(TAG, "RFB error: $message")
        }

        override fun onDisconnected(reason: String) {
            Logger.i(TAG, "RFB disconnected: $reason")
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFitScale()
        // If we already have framebuffer data (e.g. the first update arrived while
        // the view was GONE and its dimensions were 0), force a redraw now that we
        // have real dimensions and a valid fitScale.
        if (bitmap != null) invalidate()
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

        // Bitmap origin in screen space (centred + panned)
        val originX = (width - fbWidth * scale) / 2f - panX * scale
        val originY = (height - fbHeight * scale) / 2f - panY * scale

        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(originX, originY)
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Track raw drag for click-and-hold (e.g. selection drag in VM)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressX = event.x; pressY = event.y; pressTime = System.currentTimeMillis()
                val (bx, by) = screenToBitmap(event.x, event.y)
                lastButtonMask = RfbConstants.BTN_LEFT
                firePointer(bx, by, lastButtonMask)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val (bx, by) = screenToBitmap(event.x, event.y)
                    firePointer(bx, by, lastButtonMask)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                firePointer(bx, by, 0)
                lastButtonMask = 0
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

    private fun firePointer(bx: Int, by: Int, mask: Int) {
        onPointerEvent?.invoke(bx, by, mask)
    }

    // ── Keyboard ──────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = androidKeyToKeysym(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onKeyEvent?.invoke(keysym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = androidKeyToKeysym(keyCode, event) ?: return super.onKeyUp(keyCode, event)
        onKeyEvent?.invoke(keysym, false)
        return true
    }

    /**
     * Send a keysym directly (for on-screen keyboard / custom keyboard bar).
     */
    fun sendKey(keysym: Long, down: Boolean) {
        onKeyEvent?.invoke(keysym, down)
    }

    /** Send a Unicode character as a key press + release. */
    fun sendChar(ch: Char) {
        val keysym = ch.code.toLong()
        onKeyEvent?.invoke(keysym, true)
        onKeyEvent?.invoke(keysym, false)
    }

    private fun androidKeyToKeysym(keyCode: Int, event: KeyEvent): Long? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> RfbConstants.KEY_BACK_SPACE
            KeyEvent.KEYCODE_TAB        -> RfbConstants.KEY_TAB
            KeyEvent.KEYCODE_ENTER      -> RfbConstants.KEY_RETURN
            KeyEvent.KEYCODE_ESCAPE     -> RfbConstants.KEY_ESCAPE
            KeyEvent.KEYCODE_FORWARD_DEL -> RfbConstants.KEY_DELETE
            KeyEvent.KEYCODE_INSERT     -> RfbConstants.KEY_INSERT
            KeyEvent.KEYCODE_MOVE_HOME  -> RfbConstants.KEY_HOME
            KeyEvent.KEYCODE_MOVE_END   -> RfbConstants.KEY_END
            KeyEvent.KEYCODE_PAGE_UP    -> RfbConstants.KEY_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN  -> RfbConstants.KEY_PAGE_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT  -> RfbConstants.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_UP    -> RfbConstants.KEY_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> RfbConstants.KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN  -> RfbConstants.KEY_DOWN
            KeyEvent.KEYCODE_F1  -> RfbConstants.KEY_F1
            KeyEvent.KEYCODE_F2  -> RfbConstants.KEY_F2
            KeyEvent.KEYCODE_F3  -> RfbConstants.KEY_F3
            KeyEvent.KEYCODE_F4  -> RfbConstants.KEY_F4
            KeyEvent.KEYCODE_F5  -> RfbConstants.KEY_F5
            KeyEvent.KEYCODE_F6  -> RfbConstants.KEY_F6
            KeyEvent.KEYCODE_F7  -> RfbConstants.KEY_F7
            KeyEvent.KEYCODE_F8  -> RfbConstants.KEY_F8
            KeyEvent.KEYCODE_F9  -> RfbConstants.KEY_F9
            KeyEvent.KEYCODE_F10 -> RfbConstants.KEY_F10
            KeyEvent.KEYCODE_F11 -> RfbConstants.KEY_F11
            KeyEvent.KEYCODE_F12 -> RfbConstants.KEY_F12
            KeyEvent.KEYCODE_SHIFT_LEFT  -> RfbConstants.KEY_SHIFT_L
            KeyEvent.KEYCODE_SHIFT_RIGHT -> RfbConstants.KEY_SHIFT_R
            KeyEvent.KEYCODE_CTRL_LEFT   -> RfbConstants.KEY_CTRL_L
            KeyEvent.KEYCODE_CTRL_RIGHT  -> RfbConstants.KEY_CTRL_R
            KeyEvent.KEYCODE_ALT_LEFT    -> RfbConstants.KEY_ALT_L
            KeyEvent.KEYCODE_ALT_RIGHT   -> RfbConstants.KEY_ALT_R
            KeyEvent.KEYCODE_META_LEFT   -> RfbConstants.KEY_SUPER_L
            KeyEvent.KEYCODE_META_RIGHT  -> RfbConstants.KEY_SUPER_R
            else -> {
                // Printable characters: use Unicode code point as keysym
                val ch = event.unicodeChar
                if (ch > 0) ch.toLong() else null
            }
        }
    }

    // ── Accessibility ─────────────────────────────────────────────────────

    init {
        // Must be focusable so the VNC view can receive hardware key events
        // and so the Android IME will attach to it when it gains focus.
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ── IME / soft-keyboard support ───────────────────────────────────────

    /**
     * Tell the IME framework this view accepts text input.  Without this,
     * tapping the view never shows the soft keyboard, and typed characters
     * are silently discarded by the default [View.onCreateInputConnection].
     */
    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Return an [InputConnection] that routes soft-keyboard input to the
     * active VNC session.
     *
     * Key design choices:
     *  - [InputType.TYPE_NULL] tells the IME this is a raw terminal — no
     *    auto-correct, no suggestions, no spell-check, no word wrap.
     *  - [EditorInfo.IME_FLAG_NO_FULLSCREEN] prevents the IME from taking
     *    over the screen and hiding the VNC framebuffer on small devices.
     *  - [EditorInfo.IME_FLAG_NO_EXTRACT_UI] suppresses the extract-mode
     *    text bar that would normally appear above the keyboard.
     *  - Soft-keyboard delete is routed via [onBackspace] rather than
     *    through [onKeyEvent] so the single-threaded writer executor in
     *    [VncConsoleChannel] serialises it correctly with text input.
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
                // Some IMEs (e.g. Gboard) send KEYCODE_DEL as a KeyEvent rather
                // than calling deleteSurroundingText.  Route it the same way.
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DEL) {
                    onBackspace?.invoke()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    fun recycle() {
        synchronized(fbLock) {
            bitmap?.recycle()
            bitmap = null
        }
    }
}
