package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.TextPaint
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.*
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.tabssh.terminal.emulator.TerminalEmulator
import io.github.tabssh.terminal.emulator.TerminalBuffer
import io.github.tabssh.terminal.emulator.TerminalListener
import io.github.tabssh.terminal.renderer.TerminalRenderer
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.utils.logging.Logger
import java.io.OutputStream

/**
 * Custom terminal view implementing VT100/ANSI terminal emulation
 * Core component for SSH terminal display and interaction
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), View.OnTouchListener {

    // Terminal configuration
    private var terminalRows = 24
    private var terminalCols = 80
    private var cellWidth = 0f
    private var cellHeight = 0f

    // Terminal components
    private var terminalEmulator: TerminalEmulator? = null
    private var terminalBuffer: TerminalBuffer? = null
    private var terminalRenderer: TerminalRenderer? = null
    private var terminalListener: TerminalListener? = null

    // Output stream for sending data
    private var outputStream: OutputStream? = null

    // Rendering components
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private val cursorPaint = Paint()

    // Touch and input handling
    private val gestureDetector: GestureDetector
    private val scroller: OverScroller
    private var scrollY = 0

    // Terminal colors and theme
    private var currentTheme: Theme? = null
    private val defaultColors = IntArray(16) {
        when (it) {
            0 -> Color.BLACK       // Black
            1 -> Color.RED         // Red
            2 -> Color.GREEN       // Green
            3 -> Color.YELLOW      // Yellow
            4 -> Color.BLUE        // Blue
            5 -> Color.MAGENTA     // Magenta
            6 -> Color.CYAN        // Cyan
            7 -> Color.WHITE       // White
            8 -> Color.GRAY        // Bright Black
            9 -> Color.RED         // Bright Red
            10 -> Color.GREEN      // Bright Green
            11 -> Color.YELLOW     // Bright Yellow
            12 -> Color.BLUE       // Bright Blue
            13 -> Color.MAGENTA    // Bright Magenta
            14 -> Color.CYAN       // Bright Cyan
            15 -> Color.WHITE      // Bright White
            else -> Color.WHITE
        }
    }

    // Input method handling
    private val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // Accessibility
    private var accessibilityHelper: TerminalAccessibilityHelper? = null

    init {
        // Enable focus and touch
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener(this)

        // Configure scroller and gesture detector
        scroller = OverScroller(context)
        gestureDetector = GestureDetector(context, TerminalGestureListener())

        // Setup default text paint
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = 14f * resources.displayMetrics.density
        textPaint.color = Color.WHITE

        // Setup background paint
        backgroundPaint.color = Color.BLACK
        backgroundPaint.style = Paint.Style.FILL

        // Setup cursor paint
        cursorPaint.color = Color.WHITE
        cursorPaint.alpha = 128

        // Calculate cell dimensions
        calculateCellDimensions()

        // Initialize accessibility
        setupAccessibility()

        Logger.d("TerminalView", "Terminal view initialized")
    }

    /**
     * Initialize terminal emulator and buffer
     */
    fun initialize(rows: Int = 24, cols: Int = 80) {
        terminalRows = rows
        terminalCols = cols

        // Clear any existing listener/emulator before reinitializing
        terminalListener?.let { listener ->
            terminalEmulator?.removeListener(listener)
        }
        terminalListener = null

        terminalBuffer = TerminalBuffer(terminalRows, terminalCols)
        terminalEmulator = TerminalEmulator(terminalBuffer!!)
        terminalRenderer = TerminalRenderer(textPaint, backgroundPaint, cursorPaint)

        // Set up listener to redraw when terminal receives data
        setupTerminalListener()

        calculateCellDimensions()
        requestLayout()

        Logger.d("TerminalView", "Terminal initialized: ${rows}x${cols}")
    }

    /**
     * Attach an existing terminal emulator (for displaying tab content)
     */
    fun attachTerminalEmulator(emulator: TerminalEmulator) {
        // Remove existing listener from current emulator (if any) before switching
        terminalListener?.let { listener ->
            terminalEmulator?.removeListener(listener)
        }
        terminalListener = null

        terminalEmulator = emulator
        terminalBuffer = emulator.getBuffer()
        terminalRows = emulator.getRows()
        terminalCols = emulator.getCols()

        // Set up listener to redraw when terminal receives data
        setupTerminalListener()

        calculateCellDimensions()
        invalidate()

        Logger.d("TerminalView", "Attached terminal emulator: ${terminalRows}x${terminalCols}")
    }

    /**
     * Set up terminal listener to handle data updates
     */
    private fun setupTerminalListener() {
        val emulator = terminalEmulator ?: return

        // Remove any existing listener before adding a new one to avoid duplicates
        terminalListener?.let { emulator.removeListener(it) }

        val listener = object : TerminalListener {
            override fun onDataReceived(data: ByteArray) {
                // Redraw terminal when data arrives
                post { invalidate() }
                Logger.d("TerminalView", "Terminal data received, invalidating view")
            }

            override fun onDataSent(data: ByteArray) {
                // Could show sent data indicator if needed
            }

            override fun onTitleChanged(title: String) {
                // Terminal title changed (e.g., from OSC sequences)
                Logger.d("TerminalView", "Terminal title changed: $title")
            }

            override fun onTerminalError(error: Exception) {
                Logger.e("TerminalView", "Terminal error", error)
            }

            override fun onTerminalConnected() {
                Logger.i("TerminalView", "Terminal connected")
                post { invalidate() }
            }

            override fun onTerminalDisconnected() {
                Logger.i("TerminalView", "Terminal disconnected")
                post { invalidate() }
            }
        }

        terminalListener = listener
        emulator.addListener(listener)
    }

    /**
     * Set the output stream for sending user input
     */
    fun setOutputStream(stream: OutputStream) {
        outputStream = stream
        terminalEmulator?.attachOutputStream(stream)
        Logger.d("TerminalView", "Output stream set for terminal view")
    }

    /**
     * Connect terminal to SSH streams
     */
    fun connectToStreams(inputStream: java.io.InputStream, outputStream: java.io.OutputStream) {
        terminalEmulator?.connect(inputStream, outputStream)
        this.outputStream = outputStream
        Logger.i("TerminalView", "Connected terminal to SSH streams")
    }

    /**
     * Send data to terminal for processing
     */
    fun sendData(data: ByteArray) {
        terminalEmulator?.processInput(data)
        post { invalidate() }
    }

    /**
     * Send text input to remote terminal
     */
    fun sendText(text: String) {
        terminalEmulator?.sendText(text) ?: run {
            outputStream?.let { stream ->
                try {
                    stream.write(text.toByteArray())
                    stream.flush()
                } catch (e: Exception) {
                    Logger.e("TerminalView", "Error sending text: ${e.message}")
                }
            } ?: Logger.w("TerminalView", "Unable to send text - no output stream attached")
        }
    }

    /**
     * Send special key sequence
     */
    fun sendKeySequence(sequence: String) {
        sendText(sequence)
    }

    /**
     * Apply terminal theme
     */
    fun applyTheme(theme: Theme) {
        currentTheme = theme

        // Update colors
        backgroundPaint.color = theme.background
        textPaint.color = theme.foreground

        // Update terminal buffer colors
        terminalBuffer?.let { buffer ->
            buffer.setColors(theme.ansiColors)
        }

        invalidate()
    }

    /**
     * Get terminal size
     */
    fun getTerminalSize(): Pair<Int, Int> = Pair(terminalCols, terminalRows)

    /**
     * Clear terminal screen
     */
    fun clearScreen() {
        terminalBuffer?.clear()
        invalidate()
    }

    /**
     * Toggle soft keyboard
     */
    fun toggleKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_FORCED)
        Logger.d("TerminalView", "Showing keyboard")
    }

    private fun calculateCellDimensions() {
        val fontMetrics = textPaint.fontMetrics
        cellHeight = fontMetrics.bottom - fontMetrics.top
        cellWidth = textPaint.measureText("M") // Use 'M' as reference for monospace
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (terminalCols * cellWidth + paddingLeft + paddingRight).toInt()
        val desiredHeight = (terminalRows * cellHeight + paddingTop + paddingBottom).toInt()

        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val availableWidth = w - paddingLeft - paddingRight
        val availableHeight = h - paddingTop - paddingBottom

        if (cellWidth > 0 && cellHeight > 0) {
            val newCols = (availableWidth / cellWidth).toInt()
            val newRows = (availableHeight / cellHeight).toInt()

            if (newCols != terminalCols || newRows != terminalRows) {
                terminalCols = newCols.coerceAtLeast(40)
                terminalRows = newRows.coerceAtLeast(10)

                terminalBuffer?.resize(terminalRows, terminalCols)

                Logger.d("TerminalView", "Terminal resized: ${terminalRows}x${terminalCols}")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Draw terminal content
        terminalRenderer?.let { renderer ->
            terminalBuffer?.let { buffer ->
                renderer.render(canvas, buffer, paddingLeft.toFloat(), paddingTop.toFloat(),
                    cellWidth, cellHeight, scrollY)
            }
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                sendText("\r")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                sendText("\b")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendText("\t")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendKeySequence("\u001b[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendKeySequence("\u001b[B")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendKeySequence("\u001b[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendKeySequence("\u001b[D")
                return true
            }
        }

        // Handle text input
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            sendText(String(Character.toChars(unicodeChar)))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {
        editorInfo.inputType = EditorInfo.TYPE_NULL
        editorInfo.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return TerminalInputConnection(this)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private fun setupAccessibility() {
        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "Terminal"
                info.contentDescription = "SSH Terminal"

                terminalBuffer?.let { buffer ->
                    info.text = buffer.getVisibleText()
                }
            }
        })
    }

    /**
     * Gesture listener for terminal interactions
     */
    private inner class TerminalGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollY = (scrollY + distanceY).coerceAtLeast(0f).toInt()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(0, scrollY, 0, -velocityY.toInt(), 0, 0, 0,
                terminalBuffer?.getScrollbackSize() ?: 0)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleKeyboard()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            requestFocus()
            inputMethodManager.showSoftInput(this@TerminalView, InputMethodManager.SHOW_FORCED)
            Logger.d("TerminalView", "Single tap - showing keyboard")
            return true
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        terminalListener?.let { listener ->
            terminalEmulator?.removeListener(listener)
        }
        terminalListener = null
    }
}

/**
 * Helper class for comprehensive accessibility support
 * Provides TalkBack integration, high contrast modes, and accessibility actions
 */
private class TerminalAccessibilityHelper(private val terminalView: TerminalView) {

    /**
     * Announce terminal output changes to accessibility services
     */
    fun announceOutputChange(text: String) {
        if (text.isNotBlank()) {
            terminalView.announceForAccessibility(text)
        }
    }

    /**
     * Get terminal content description for accessibility
     */
    fun getContentDescription(buffer: io.github.tabssh.terminal.emulator.TerminalBuffer): String {
        return buildString {
            append("Terminal window. ")
            append("${buffer.getRows()} rows by ${buffer.getCols()} columns. ")

            val visibleText = buffer.getVisibleText()
            if (visibleText.isNotBlank()) {
                append("Current content: ")
                append(visibleText.take(200)) // Limit for accessibility
                if (visibleText.length > 200) {
                    append("... and more")
                }
            }
        }
    }

    /**
     * Get accessibility actions for terminal
     */
    fun getAccessibilityActions(): List<AccessibilityAction> {
        return listOf(
            AccessibilityAction("Scroll up", "Scroll terminal output up"),
            AccessibilityAction("Scroll down", "Scroll terminal output down"),
            AccessibilityAction("Clear screen", "Clear terminal screen"),
            AccessibilityAction("Copy text", "Copy terminal text to clipboard")
        )
    }

    data class AccessibilityAction(val label: String, val description: String)
}

/**
 * Custom InputConnection for terminal input handling
 */
private class TerminalInputConnection(private val terminalView: TerminalView) : InputConnection {

    override fun getHandler(): android.os.Handler? = null

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    override fun getSelectedText(flags: Int): CharSequence = ""

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { terminalView.sendText("\b") }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        // Handle deletion in code points (for multi-byte characters)
        repeat(beforeLength) { terminalView.sendText("\b") }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let { terminalView.sendText(it.toString()) }
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = false

    override fun finishComposingText(): Boolean = true

    override fun setSelection(start: Int, end: Int): Boolean = false

    override fun performEditorAction(editorAction: Int): Boolean {
        when (editorAction) {
            EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND -> {
                terminalView.sendText("\r")
                return true
            }
        }
        return false
    }

    override fun performContextMenuAction(id: Int): Boolean = false

    override fun beginBatchEdit(): Boolean = false

    override fun endBatchEdit(): Boolean = false

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        terminalView.dispatchKeyEvent(event)
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let { terminalView.sendText(it.toString()) }
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun closeConnection() {
        // No special cleanup needed for terminal input connection
    }

    override fun commitContent(inputContentInfo: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
        // Terminal doesn't support rich content input
        return false
    }
}
