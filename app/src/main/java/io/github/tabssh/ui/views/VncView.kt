package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyCharacterMap
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
        // pointer events per scroll fling step
        private const val SCROLL_STEPS = 3
        // tap → click if released within
        private const val CLICK_TIMEOUT_MS = 200L
        // Longer than GestureDetector's own long-press timeout so the existing
        // hold-to-right-click gesture fires first; only a hold past this point
        // opens the shared session context menu (same menu TerminalView opens).
        private const val CONTEXT_MENU_TIMEOUT_MS = 900L

        /**
         * Compute the pan offset that keeps the bitmap point ([oldBitmapX],
         * [oldBitmapY]) — the content under the pinch focal point before the
         * scale changed — pinned under that same screen-space focal point
         * ([focusX], [focusY]) at [newScale]. Without this, pinch-zoom
         * scales around the bitmap centre and the content drifts out from
         * under the fingers. Clamped to valid pan bounds (same bounds
         * [onScroll] uses). Pure function — no View state — so the
         * focal-anchoring math is unit-testable without Robolectric.
         */
        internal fun focalPan(
            viewWidth: Int,
            viewHeight: Int,
            fbWidth: Int,
            fbHeight: Int,
            focusX: Float,
            focusY: Float,
            oldBitmapX: Float,
            oldBitmapY: Float,
            newScale: Float
        ): Pair<Float, Float> {
            if (newScale <= 0f) return Pair(0f, 0f)
            // Rendering origin is centred ((view - fb*scale)/2 - pan*scale),
            // so the valid pan range is symmetric around 0 — half the
            // off-screen extent in each direction. An asymmetric [0, extent]
            // clamp here would make the top/left edge unreachable and let
            // the view scroll past the bottom/right edge.
            val extentX = max(0f, (fbWidth - viewWidth / newScale) / 2f)
            val extentY = max(0f, (fbHeight - viewHeight / newScale) / 2f)
            val panX = (((viewWidth - fbWidth * newScale) / 2f - focusX) / newScale + oldBitmapX)
                .coerceIn(-extentX, extentX)
            val panY = (((viewHeight - fbHeight * newScale) / 2f - focusY) / newScale + oldBitmapY)
                .coerceIn(-extentY, extentY)
            return Pair(panX, panY)
        }
    }

    // ── Framebuffer ──────────────────────────────────────────────────────

    private val fbLock = Any()
    private var bitmap: Bitmap? = null
    // Written by the RFB reader thread under [fbLock] but read unlocked on the
    // main thread by the gesture and layout code. @Volatile is what makes those
    // reads see the new geometry after a desktop resize instead of a stale or
    // torn value.
    @Volatile
    private var fbWidth = 0

    @Volatile
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

    /**
     * Called with (x, y) when the user holds a single-finger touch past
     * [CONTEXT_MENU_TIMEOUT_MS] — opens the same shared session context
     * menu TerminalView's long-press opens. Fires in addition to (after)
     * the shorter-timeout right-click gesture already bound to long-press,
     * so a plain long-press still right-clicks and only a longer hold
     * surfaces the menu.
     */
    var onContextMenuRequested: ((Float, Float) -> Unit)? = null

    // ── Touch bookkeeping ─────────────────────────────────────────────────

    private var lastButtonMask = 0
    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0L

    private val contextMenuHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var contextMenuRunnable: Runnable? = null
    private var contextMenuDownX = 0f
    private var contextMenuDownY = 0f
    private val contextMenuTouchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Pointer down/up for this tap is already fired by onTouchEvent
            // ACTION_DOWN / ACTION_UP. Focus + show the IME here too, for UX
            // parity with TerminalView's single-tap keyboard behaviour
            // (TerminalView.onSingleTapConfirmed / toggleKeyboard) — a tap
            // inside the console should raise the keyboard the same way a
            // tap inside a terminal does. The VNC toolbar's keyboard button
            // remains available to hide it again.
            requestSoftKeyboard()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Toggle between fit-to-screen and 2× zoom.
            userScale = if (userScale > 1.05f) 1.0f else 2.0f
            panX = 0f
            panY = 0f
            recomputeFitScale()
            postInvalidate()
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
            // handled by ScaleGestureDetector
            if (e2.pointerCount > 1) return false
            if (userScale > 1.05f) {
                // Pan the viewport
                // Same symmetric bounds as focalPan — the rendering origin
                // is centred, so pan 0 is the centre, not the top-left.
                val extX = max(0f, (fbWidth - width / currentScale()) / 2f)
                val extY = max(0f, (fbHeight - height / currentScale()) / 2f)
                panX = (panX + distX / currentScale()).coerceIn(-extX, extX)
                panY = (panY + distY / currentScale()).coerceIn(-extY, extY)
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
            val oldScale = currentScale()
            val originX = (width - fbWidth * oldScale) / 2f - panX * oldScale
            val originY = (height - fbHeight * oldScale) / 2f - panY * oldScale
            val oldBitmapX = (detector.focusX - originX) / oldScale
            val oldBitmapY = (detector.focusY - originY) / oldScale
            userScale = (userScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            recomputeFitScale()
            val (newPanX, newPanY) = focalPan(
                width, height, fbWidth, fbHeight,
                detector.focusX, detector.focusY,
                oldBitmapX, oldBitmapY, currentScale()
            )
            panX = newPanX
            panY = newPanY
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
                val dimsMatch = bitmap != null && fbWidth == width && fbHeight == height
                // Reuse the existing bitmap when the geometry is unchanged so the
                // last decoded frame stays on screen while a reconnect is in
                // flight (toggle-OFF path). Recreating would blank the view to
                // black until the server's first full update paints — the flash
                // this seamless-reconnect polish exists to avoid.
                if (!dimsMatch) {
                    bitmap?.recycle()
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    fbWidth = width
                    fbHeight = height
                }
                // A fresh connect passes an all-zero framebuffer (real pixels
                // arrive later via onFramebufferUpdate), so skip painting it and
                // keep whatever was there. A re-attach after a background park
                // passes the client's retained, non-blank framebuffer — paint it
                // now so the frame is visible immediately, before resume()'s
                // refresh lands.
                if (framebuffer.size == width * height && framebuffer.any { it != 0 }) {
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
                val bmp = bitmap ?: return
                // setPixels throws rather than clipping, and an uncaught throw
                // here kills the RFB reader thread and drops the session. Drop
                // the single rect instead if the geometry does not line up.
                val fits = w > 0 && h > 0 && x >= 0 && y >= 0 &&
                    bmp.width == fbWidth && bmp.height == fbHeight &&
                    x + w <= fbWidth && y + h <= fbHeight &&
                    framebuffer.size >= (y + h - 1) * fbWidth + x + w
                if (!fits) {
                    Logger.w(TAG, "Dropping update ($x,$y) ${w}×$h — does not fit ${fbWidth}×$fbHeight")
                    return
                }
                bmp.setPixels(framebuffer, y * fbWidth + x, fbWidth, x, y, w, h)
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
            // Route through ClipboardHelper so VNC server clipboard events cancel any pending sensitive clear.
            io.github.tabssh.utils.ClipboardHelper.copy(context, label = "VNC clipboard", text = text, sensitive = false)
        }

        override fun onError(message: String) {
            Logger.e(TAG, "RFB error: $message")
        }

        override fun onDisconnected(reason: String) {
            Logger.i(TAG, "RFB disconnected: $reason")
        }
    }

    // ── View-size callback (used by VncConsoleChannel to send SetDesktopSize) ──

    /**
     * Fired on the main thread whenever the view is measured with non-zero
     * dimensions.  [VncConsoleChannel.resizeToPixels] is wired here by
     * [VMConsoleActivity.switchToGraphical] so the VNC server's framebuffer
     * is resized to match the visible pixel area rather than an arbitrary
     * character-grid size.  Fired on every raw size change with no debounce
     * here — [VncConsoleChannel.resizeToPixels] itself debounces the actual
     * SetDesktopSize send so a rapid burst of size changes (soft-keyboard
     * show/hide) settles on one request instead of one per intermediate size.
     */
    var onViewSizeReady: ((width: Int, height: Int) -> Unit)? = null

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFitScale()
        // If we already have framebuffer data (e.g. the first update arrived while
        // the view was GONE and its dimensions were 0), force a redraw now that we
        // have real dimensions and a valid fitScale.
        if (bitmap != null) invalidate()
        // Notify the channel so it can send SetDesktopSize to the server.
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
        // The whole draw runs under fbLock. The RFB reader thread recycles and
        // replaces this bitmap (onConnected / onDesktopResize) and writes into
        // it (onFramebufferUpdate) under the same lock; releasing the lock after
        // merely reading the reference let the reader recycle the bitmap between
        // that read and drawBitmap, crashing with "Canvas: trying to use a
        // recycled bitmap", and let a concurrent setPixels tear the frame.
        // fbWidth/fbHeight are written under the lock too, so they are read here.
        synchronized(fbLock) {
            val bmp = bitmap ?: return
            // Bitmap origin in screen space (centred + panned)
            val originX = (width - fbWidth * scale) / 2f - panX * scale
            val originY = (height - fbHeight * scale) / 2f - panY * scale

            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate(originX, originY)
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Track raw drag for click-and-hold (e.g. selection drag in VM)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Grab focus immediately so hardware key events and the IME
                // route to this view without requiring the tap to finish
                // first (mirrors TerminalView.onTouchEvent's ACTION_DOWN).
                requestFocus()
                pressX = event.x; pressY = event.y; pressTime = System.currentTimeMillis()
                val (bx, by) = screenToBitmap(event.x, event.y)
                // A real mouse reports which physical button went down, so its
                // right/middle clicks reach the VM as right/middle instead of
                // being flattened to a left click. A finger reports no button
                // state at all, which stays a left click.
                val mouseMask = mouseButtonMask(event)
                lastButtonMask = if (mouseMask != 0) mouseMask else RfbConstants.BTN_LEFT
                firePointer(bx, by, lastButtonMask)
                armContextMenuTimer(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val (bx, by) = screenToBitmap(event.x, event.y)
                    firePointer(bx, by, lastButtonMask)
                    if (kotlin.math.abs(event.x - contextMenuDownX) > contextMenuTouchSlop ||
                        kotlin.math.abs(event.y - contextMenuDownY) > contextMenuTouchSlop
                    ) {
                        cancelContextMenuTimer()
                    }
                } else {
                    cancelContextMenuTimer()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                firePointer(bx, by, 0)
                lastButtonMask = 0
                cancelContextMenuTimer()
            }
        }
        return true
    }

    /**
     * Handle events from a real pointing device (USB/Bluetooth mouse,
     * DeX/ChromeOS trackpad). Android delivers wheel scrolls and hover moves
     * here, never through [onTouchEvent], so without this the wheel did
     * nothing and the remote cursor did not follow the mouse.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                val vs = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hs = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                // RFB has no scroll amount: each wheel notch is one press and
                // release of buttons 4/5 (vertical) or 6/7 (horizontal).
                if (vs != 0f) {
                    clickButton(bx, by,
                        if (vs > 0f) RfbConstants.BTN_SCROLL_UP else RfbConstants.BTN_SCROLL_DOWN,
                        wheelNotches(vs))
                }
                if (hs != 0f) {
                    clickButton(bx, by,
                        if (hs > 0f) RfbConstants.BTN_SCROLL_RIGHT else RfbConstants.BTN_SCROLL_LEFT,
                        wheelNotches(hs))
                }
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                val (bx, by) = screenToBitmap(event.x, event.y)
                firePointer(bx, by, mouseButtonMask(event))
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Number of discrete wheel notches represented by a scroll axis value. */
    private fun wheelNotches(axisValue: Float): Int =
        kotlin.math.ceil(kotlin.math.abs(axisValue).toDouble()).toInt()
            .coerceIn(1, SCROLL_STEPS)

    /** Press and release [button] [times] times at the given framebuffer point. */
    private fun clickButton(bx: Int, by: Int, button: Int, times: Int) {
        repeat(times) {
            firePointer(bx, by, button)
            firePointer(bx, by, 0)
        }
    }

    /** Translate Android's mouse button state into an RFB button mask. */
    private fun mouseButtonMask(event: MotionEvent): Int {
        var mask = 0
        val state = event.buttonState
        if (state and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or RfbConstants.BTN_LEFT
        if (state and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or RfbConstants.BTN_MIDDLE
        if (state and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or RfbConstants.BTN_RIGHT
        return mask
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

    private fun firePointer(bx: Int, by: Int, mask: Int) {
        onPointerEvent?.invoke(bx, by, mask)
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
        sendCodePoint(ch.code)
    }

    /**
     * Send a Unicode code point as a key press + release.
     *
     * Takes a code point rather than a [Char] so characters outside the BMP
     * (emoji, supplementary planes) can be sent as one keysym instead of two
     * meaningless surrogate halves.
     */
    fun sendCodePoint(cp: Int) {
        val keysym = codePointToKeysym(cp)
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
                // Printable characters. getUnicodeChar() returns 0 when a
                // modifier suppresses the character (Ctrl+C reports 0), so fall
                // back to the unmodified character the same way
                // VncConsoleChannel does — otherwise every Ctrl/Alt chord was
                // dropped before it reached the server. Dead keys set
                // COMBINING_ACCENT (bit 31), making the value negative; mask it
                // off and send the base accent character.
                val direct = event.unicodeChar
                val raw = if (direct != 0) direct else event.getUnicodeChar(0)
                val cp = if (raw < 0) raw and KeyCharacterMap.COMBINING_ACCENT_MASK else raw
                if (cp > 0) codePointToKeysym(cp) else null
            }
        }
    }

    /**
     * Map a Unicode code point to an X11 keysym.
     *
     * Latin-1 code points are their own keysyms; everything above U+00FF uses
     * the X11 Unicode convention `0x01000000 | codepoint`. Sending the bare
     * code point (the previous behaviour) collides with the X11 function-key
     * and keypad ranges, so non-Latin-1 keystrokes arrived at the server as
     * unrelated keys.
     */
    private fun codePointToKeysym(cp: Int): Long =
        if (cp <= 0xFF) cp.toLong() else 0x01000000L or cp.toLong()

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

    // ── Zoom helpers (called from VMConsoleActivity VNC toolbar) ─────────

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

    // ── Cleanup ───────────────────────────────────────────────────────────

    fun recycle() {
        onViewSizeReady = null
        synchronized(fbLock) {
            bitmap?.recycle()
            bitmap = null
        }
    }
}
