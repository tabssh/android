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
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
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

    // Termux bridge for proper VT100/ANSI emulation
    private var termuxBridge: TermuxBridge? = null
    private var termuxBuffer: com.termux.terminal.TerminalBuffer? = null

    // Output stream for sending data
    private var outputStream: OutputStream? = null

    // Rendering components
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private val cursorPaint = Paint()

    // Touch and input handling
    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val scroller: OverScroller
    private var scrollY = 0

    // Pinch-to-zoom state
    private var isScaling = false
    private var minFontSize = 8f
    private var maxFontSize = 32f

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

    // URL detection
    private val urlPattern = Regex(
        "(https?://[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})+(?:/[^\\s]*)?)|" +
        "(www\\.[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})+(?:/[^\\s]*)?)",
        RegexOption.IGNORE_CASE
    )
    var onUrlDetected: ((String) -> Unit)? = null

    // Performance optimization: dirty region tracking
    private var dirtyRows = java.util.BitSet(256) // Track which rows need redrawing
    private var fullRedrawNeeded = true // Force full redraw on first draw or after resize
    private var lastRenderedRows = 0 // Track row count changes
    private var lastRenderedCols = 0 // Track column count changes
    private var lastCursorRow = -1 // Track cursor position changes
    private var lastCursorCol = -1

    // Performance: text drawing cache
    private val rowTextBuilder = StringBuilder(256) // Reusable StringBuilder
    private val clipRect = RectF() // Reusable clip rect

    // Context menu callback for long press on text
    var onContextMenuRequested: ((x: Float, y: Float) -> Unit)? = null
    
    // Multi-touch gesture handler for tmux/screen shortcuts
    private var terminalGestureHandler: io.github.tabssh.terminal.gestures.TerminalGestureHandler? = null
    var onCommandSent: ((ByteArray) -> Unit)? = null

    init {
        // Enable focus and touch
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener(this)

        // Configure scroller and gesture detectors
        scroller = OverScroller(context)
        gestureDetector = GestureDetector(context, TerminalGestureListener())
        scaleGestureDetector = ScaleGestureDetector(context, PinchZoomListener())

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

    // Store current bridge listener for removal
    private var currentBridgeListener: TermuxBridgeListener? = null

    /**
     * Attach Termux bridge for proper VT100/ANSI terminal emulation
     * This is the preferred method for SSH connections
     */
    fun attachTerminalEmulator(bridge: TermuxBridge) {
        Logger.i("TerminalView", "attachTerminalEmulator called for bridge: ${bridge.hashCode()}")

        // Clear old emulator references
        terminalListener?.let { listener ->
            terminalEmulator?.removeListener(listener)
        }
        terminalListener = null
        terminalEmulator = null
        terminalBuffer = null

        // Remove listener from old bridge if different
        if (termuxBridge != null && termuxBridge != bridge) {
            currentBridgeListener?.let { listener ->
                termuxBridge?.removeListener(listener)
                Logger.d("TerminalView", "Removed listener from old bridge")
            }
        }

        // Set up Termux bridge
        termuxBridge = bridge
        termuxBuffer = bridge.getBuffer()
        terminalRows = bridge.getRows()
        terminalCols = bridge.getCols()

        Logger.d("TerminalView", "Bridge state: buffer=${termuxBuffer != null}, rows=$terminalRows, cols=$terminalCols, emulator=${bridge.getEmulator() != null}")

        // Create and set up listener for screen changes
        val listener = object : TermuxBridgeListener {
            override fun onConnected() {
                Logger.i("TerminalView", "Termux bridge CONNECTED - terminal should start receiving data")
                post { invalidate() }
            }

            override fun onDisconnected() {
                Logger.i("TerminalView", "Termux bridge disconnected")
            }

            override fun onScreenChanged() {
                // Update buffer reference and redraw
                termuxBuffer = bridge.getBuffer()
                Logger.d("TerminalView", "onScreenChanged - scheduling redraw")
                post {
                    invalidate()
                }
            }

            override fun onTitleChanged(title: String) {
                Logger.d("TerminalView", "Terminal title: $title")
            }

            override fun onBell() {
                Logger.d("TerminalView", "Terminal bell")
                // `terminal_bell_visual` (default true, gated on
                // `terminal_bell` parent) → 120ms invert flash. Posted to
                // the UI thread because onBell can fire from the emulator
                // background thread.
                val prefs = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(context)
                if (prefs.getBoolean("terminal_bell", true) &&
                    prefs.getBoolean("terminal_bell_visual", true)) {
                    post { triggerVisualBell() }
                }
            }

            override fun onColorsChanged() {
                post { invalidate() }
            }

            override fun onCursorStateChanged(visible: Boolean) {
                post { invalidate() }
            }

            override fun onCopyToClipboard(text: String) {
                Logger.d("TerminalView", "Copy to clipboard: ${text.take(50)}...")
            }

            override fun onPasteFromClipboard() {
                Logger.d("TerminalView", "Paste from clipboard requested")
            }

            override fun onError(e: Exception) {
                Logger.e("TerminalView", "Termux bridge error", e)
            }
        }

        currentBridgeListener = listener
        bridge.addListener(listener)

        calculateCellDimensions()

        // Force refresh the buffer reference in case bridge is already connected
        termuxBuffer = bridge.getBuffer()

        // If bridge is already connected, ensure we redraw with current content
        if (bridge.isConnected.value) {
            Logger.i("TerminalView", "Bridge already connected - forcing immediate redraw")
            // Post multiple redraws to ensure content is visible
            post {
                termuxBuffer = bridge.getBuffer()
                invalidate()
            }
            postDelayed({
                termuxBuffer = bridge.getBuffer()
                invalidate()
            }, 100)
            postDelayed({
                termuxBuffer = bridge.getBuffer()
                invalidate()
            }, 500)
        }

        invalidate()

        Logger.i("TerminalView", "Attached Termux bridge: ${terminalRows}x${terminalCols}, listener added, connected=${bridge.isConnected.value}")
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
                // Performance: Mark current cursor row dirty (most data updates cursor line)
                Logger.d("TerminalView", "Terminal data received: ${data.size} bytes")
                post {
                    // Mark cursor row and a few surrounding rows dirty (new lines, scrolling)
                    val bridge = termuxBridge
                    if (bridge != null) {
                        val cursorRow = bridge.getCursorRow()
                        markRowsDirty(maxOf(0, cursorRow - 2), minOf(terminalRows - 1, cursorRow + 2))
                    } else {
                        markAllRowsDirty()
                    }
                    invalidateDirtyRows()
                }
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
                post {
                    markAllRowsDirty()
                    invalidate()
                }
            }

            override fun onTerminalDisconnected() {
                Logger.i("TerminalView", "Terminal disconnected")
                post {
                    markAllRowsDirty()
                    invalidate()
                }
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
     * Performance optimization: Mark specific rows as dirty for partial redraw
     */
    private fun markRowsDirty(startRow: Int, endRow: Int) {
        for (row in startRow..minOf(endRow, terminalRows - 1)) {
            dirtyRows.set(row)
        }
    }

    /**
     * Performance optimization: Mark all rows dirty (full redraw)
     */
    private fun markAllRowsDirty() {
        fullRedrawNeeded = true
        dirtyRows.set(0, terminalRows)
    }

    /**
     * Performance optimization: Invalidate only dirty regions
     * Uses partial invalidation when possible to reduce GPU work
     */
    private fun invalidateDirtyRows() {
        if (fullRedrawNeeded || dirtyRows.cardinality() > terminalRows / 2) {
            // More than half dirty - just do full redraw
            invalidate()
        } else {
            // Partial invalidation - find bounding rect of dirty rows
            val firstDirty = dirtyRows.nextSetBit(0)
            if (firstDirty >= 0) {
                val lastDirty = dirtyRows.length() - 1
                val top = (paddingTop + firstDirty * cellHeight).toInt()
                val bottom = (paddingTop + (lastDirty + 1) * cellHeight).toInt()
                invalidate(0, top, width, bottom)
            }
        }
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
        // Try Termux bridge first (preferred for SSH connections)
        termuxBridge?.let { bridge ->
            bridge.sendText(text)
            return
        }

        // Fall back to old terminal emulator
        terminalEmulator?.sendText(text) ?: run {
            outputStream?.let { stream ->
                try {
                    stream.write(text.toByteArray())
                    stream.flush()
                } catch (e: Exception) {
                    Logger.e("TerminalView", "Error sending text: ${e.message}")
                }
            } ?: Logger.w("TerminalView", "Unable to send text - no output stream or bridge attached")
        }
    }

    /**
     * Send special key sequence
     */
    fun sendKeySequence(sequence: String) {
        sendText(sequence)
    }

    // --- Sticky modifier state from custom keyboard bar -------------------
    //
    // The custom bar's CTL/ALT buttons toggle a one-shot modifier that the
    // bar itself applies to its own taps. IME keystrokes do not flow through
    // the bar, so without this hook a CTL+letter chord typed letter-via-IME
    // would arrive as a literal letter. We therefore lift the modifier
    // state into TerminalView so onKeyDown() and the InputConnection paths
    // can both consume it.
    private var pendingCtrl = false
    private var pendingAlt = false

    /** Notified after a pending modifier is consumed so the bar can clear UI. */
    var onModifierConsumed: (() -> Unit)? = null

    /** Set/clear the pending one-shot modifier. */
    fun setPendingModifier(modifier: String?) {
        pendingCtrl = modifier == "CTL"
        pendingAlt = modifier == "ALT"
    }

    fun isPendingCtrl(): Boolean = pendingCtrl
    fun isPendingAlt(): Boolean = pendingAlt

    private fun consumePendingModifier() {
        if (pendingCtrl || pendingAlt) {
            pendingCtrl = false
            pendingAlt = false
            onModifierConsumed?.invoke()
        }
    }

    /**
     * Send a single character with the pending bar modifier applied. Used by
     * the InputConnection commitText path where we don't have a full KeyEvent.
     * Returns true if a modifier was applied (and therefore consumed).
     */
    fun sendCharWithPendingModifier(c: Char): Boolean {
        return when {
            pendingCtrl -> {
                val upper = c.uppercaseChar()
                if (upper in 'A'..'Z') {
                    sendText(((upper.code - 'A'.code + 1).toChar()).toString())
                } else {
                    sendText(c.toString())
                }
                consumePendingModifier()
                true
            }
            pendingAlt -> {
                sendKeySequence("\u001b$c")
                consumePendingModifier()
                true
            }
            else -> false
        }
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
     * Set font size and recalculate terminal layout
     * @param sizeInSp Font size in SP units (8-32)
     */
    fun setFontSize(sizeInSp: Int) {
        val clampedSize = sizeInSp.coerceIn(8, 32)
        textPaint.textSize = clampedSize * resources.displayMetrics.density
        calculateCellDimensions()

        // Recalculate terminal dimensions based on new cell size
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom

        if (availableWidth > 0 && availableHeight > 0) {
            terminalCols = (availableWidth / cellWidth).toInt().coerceAtLeast(80)
            terminalRows = (availableHeight / cellHeight).toInt().coerceAtLeast(24)
            terminalEmulator?.resize(terminalCols, terminalRows)
        }

        invalidate()
        Logger.d("TerminalView", "Font size changed to ${clampedSize}sp (${terminalCols}x${terminalRows})")
    }

    /**
     * Get current font size in SP units
     */
    fun getFontSize(): Int {
        return (textPaint.textSize / resources.displayMetrics.density).toInt()
    }

    /**
     * Set terminal font typeface
     * @param fontValue Font value from preferences (e.g., "jetbrains_mono_nerd")
     */
    fun setFont(fontValue: String) {
        val typeface = io.github.tabssh.utils.FontManager.getTypeface(context, fontValue)
        textPaint.typeface = typeface
        calculateCellDimensions()

        // Recalculate terminal dimensions
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom

        if (availableWidth > 0 && availableHeight > 0) {
            terminalCols = (availableWidth / cellWidth).toInt().coerceAtLeast(80)
            terminalRows = (availableHeight / cellHeight).toInt().coerceAtLeast(24)
            terminalEmulator?.resize(terminalCols, terminalRows)
        }

        fullRedrawNeeded = true
        invalidate()
        Logger.d("TerminalView", "Font changed to: $fontValue")
    }

    /**
     * Set terminal font by typeface directly
     */
    fun setTypeface(typeface: android.graphics.Typeface) {
        textPaint.typeface = typeface
        calculateCellDimensions()
        fullRedrawNeeded = true
        invalidate()
    }

    /**
     * Enable custom gesture support for terminal multiplexers
     * @param multiplexerType The multiplexer type (tmux, screen, zellij, or none)
     * @param customPrefix Optional custom prefix notation (e.g., "C-a", "C-Space")
     */
    fun enableGestureSupport(
        multiplexerType: io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType,
        customPrefix: String? = null
    ) {
        if (multiplexerType == io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.NONE) {
            terminalGestureHandler = null
            Logger.d("TerminalView", "Gesture support disabled")
        } else {
            terminalGestureHandler = io.github.tabssh.terminal.gestures.TerminalGestureHandler(context) { gestureType ->
                // Get command for gesture with custom prefix
                val command = io.github.tabssh.terminal.gestures.GestureCommandMapper.getCommand(
                    gestureType, 
                    multiplexerType,
                    customPrefix
                )
                val description = io.github.tabssh.terminal.gestures.GestureCommandMapper.getDescription(
                    gestureType, 
                    multiplexerType,
                    customPrefix
                )
                
                command?.let {
                    // Send command via callback
                    onCommandSent?.invoke(it)
                    Logger.d("TerminalView", "Gesture detected: $description")
                }
            }
            
            val prefixInfo = if (!customPrefix.isNullOrEmpty()) {
                " with custom prefix: ${io.github.tabssh.terminal.gestures.PrefixParser.getDescription(customPrefix)}"
            } else {
                ""
            }
            Logger.d("TerminalView", "Gesture support enabled for $multiplexerType$prefixInfo")
        }
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
     * Toggle soft keyboard (mobile-first UX)
     */
    fun toggleKeyboard() {
        if (hasWindowFocus()) {
            if (inputMethodManager.isActive(this)) {
                // Keyboard is visible - hide it
                inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
                Logger.d("TerminalView", "Hiding keyboard")
            } else {
                // Keyboard is hidden - show it
                requestFocus()
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                Logger.d("TerminalView", "Showing keyboard")
            }
        }
    }

    /**
     * Show context menu at position (called on long press of non-URL text)
     */
    private fun showCustomContextMenu(x: Float, y: Float) {
        // Haptic feedback for long press
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        
        // Notify parent activity to show context menu
        onContextMenuRequested?.invoke(x, y)
    }

    /** Multiplier from `terminal_line_spacing` pref (100–200%, stored as Int). */
    private var lineSpacingMultiplier: Float = 1.2f

    /** Visual bell — briefly invert the terminal's foreground/background
     *  colors as a non-audible BEL indicator. Implemented via a transient
     *  overlay flag the draw path checks; auto-clears after 120ms. */
    @Volatile private var visualBellActive = false
    private fun triggerVisualBell() {
        visualBellActive = true
        invalidate()
        postDelayed({
            visualBellActive = false
            invalidate()
        }, 120)
    }

    fun setLineSpacingPercent(percent: Int) {
        lineSpacingMultiplier = (percent.coerceIn(100, 200)) / 100f
        calculateCellDimensions()
        requestLayout()
        invalidate()
    }

    private fun calculateCellDimensions() {
        val fontMetrics = textPaint.fontMetrics
        cellHeight = (fontMetrics.bottom - fontMetrics.top) * lineSpacingMultiplier
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

                // Resize the appropriate emulator
                termuxBridge?.resize(terminalCols, terminalRows)
                terminalBuffer?.resize(terminalRows, terminalCols)

                Logger.d("TerminalView", "Terminal resized: ${terminalRows}x${terminalCols}")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Prefer Termux buffer rendering if available
        termuxBuffer?.let { buffer ->
            renderTermuxBuffer(canvas, buffer)
            // Visual bell overlay — XOR-ish full-screen flash via translucent
            // accent. Cheap; auto-clears in 120ms via postDelayed.
            if (visualBellActive) {
                canvas.drawColor(0x40FFFFFF.toInt())
            }
            return
        }

        // Fall back to old emulator rendering
        terminalRenderer?.let { renderer ->
            terminalBuffer?.let { buffer ->
                Logger.d("TerminalView", "Rendering terminal: ${buffer.getRows()}x${buffer.getCols()}, scroll=$scrollY")
                renderer.render(canvas, buffer, paddingLeft.toFloat(), paddingTop.toFloat(),
                    cellWidth, cellHeight, scrollY)
            } ?: run {
                Logger.w("TerminalView", "Terminal buffer is null in onDraw")
            }
        } ?: run {
            Logger.w("TerminalView", "Terminal renderer is null in onDraw")
        }
    }

    /**
     * Render terminal content from Termux buffer
     * Uses direct TerminalRow access for proper character rendering
     */
    private fun renderTermuxBuffer(canvas: Canvas, buffer: com.termux.terminal.TerminalBuffer) {
        val bridge = termuxBridge ?: run {
            Logger.w("TerminalView", "renderTermuxBuffer: bridge is null")
            return
        }
        val emulator = bridge.getEmulator() ?: run {
            Logger.w("TerminalView", "renderTermuxBuffer: emulator is null")
            return
        }
        val rows = bridge.getRows()
        val cols = bridge.getCols()
        val cursorRow = bridge.getCursorRow()
        val cursorCol = bridge.getCursorCol()
        val cursorVisible = bridge.isCursorVisible()

        val startX = paddingLeft.toFloat()
        val startY = paddingTop.toFloat()

        // Draw all rows using direct TerminalRow access
        for (row in 0 until rows) {
            val rowTop = startY + row * cellHeight
            val rowBottom = startY + (row + 1) * cellHeight
            val y = rowBottom - textPaint.descent()

            // Clear row background
            canvas.drawRect(startX, rowTop, width.toFloat(), rowBottom, backgroundPaint)

            // Get the TerminalRow using proper Termux API
            val internalRow = try {
                buffer.externalToInternalRow(row)
            } catch (e: Exception) {
                continue
            }

            val terminalRow = try {
                buffer.allocateFullLineIfNecessary(internalRow)
            } catch (e: Exception) {
                Logger.w("TerminalView", "Error getting row $row: ${e.message}")
                continue
            }

            // Access the character array directly
            val lineChars = terminalRow.mText
            val charsUsed = terminalRow.spaceUsed

            // Draw each cell in the row
            var charIndex = 0
            var col = 0
            while (col < cols && charIndex < charsUsed) {
                val x = startX + col * cellWidth

                // Get character at this position
                val char = if (charIndex < lineChars.size) lineChars[charIndex] else ' '

                // Handle surrogate pairs for Unicode characters
                val codePoint: Int
                val charsConsumed: Int
                if (Character.isHighSurrogate(char) && charIndex + 1 < lineChars.size) {
                    codePoint = Character.toCodePoint(char, lineChars[charIndex + 1])
                    charsConsumed = 2
                } else {
                    codePoint = char.code
                    charsConsumed = 1
                }

                // Get style for this column
                val style = try {
                    terminalRow.getStyle(col)
                } catch (e: Exception) {
                    0L
                }

                // Extract colors and effects from style
                val fg = com.termux.terminal.TextStyle.decodeForeColor(style)
                val bg = com.termux.terminal.TextStyle.decodeBackColor(style)
                val effect = com.termux.terminal.TextStyle.decodeEffect(style)

                // Draw background if not default
                if (bg != com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND) {
                    backgroundPaint.color = termuxColorToAndroid(bg)
                    canvas.drawRect(x, rowTop, x + cellWidth, rowBottom, backgroundPaint)
                    backgroundPaint.color = Color.BLACK
                }

                // Draw character if visible
                if (codePoint != 0 && codePoint != ' '.code) {
                    textPaint.color = termuxColorToAndroid(fg)

                    // Apply text effects
                    textPaint.isFakeBoldText = (effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                    textPaint.textSkewX = if ((effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) -0.25f else 0f
                    textPaint.isUnderlineText = (effect and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0

                    val charStr = String(Character.toChars(codePoint))
                    canvas.drawText(charStr, x, y, textPaint)

                    // Reset effects
                    textPaint.isFakeBoldText = false
                    textPaint.textSkewX = 0f
                    textPaint.isUnderlineText = false
                }

                // Calculate character width (some chars are double-width)
                val charWidth = com.termux.terminal.WcWidth.width(codePoint)
                col += if (charWidth > 0) charWidth else 1
                charIndex += charsConsumed
            }
        }

        // Draw cursor based on style (0=block, 1=underline, 2=bar/I-beam)
        if (cursorVisible && cursorRow in 0 until rows && cursorCol in 0 until cols) {
            val cursorX = startX + cursorCol * cellWidth
            val cursorY = startY + cursorRow * cellHeight
            cursorPaint.color = Color.WHITE
            cursorPaint.alpha = 200

            when (bridge.getCursorStyle()) {
                0 -> { // Block cursor - filled rectangle
                    cursorPaint.alpha = 128
                    canvas.drawRect(cursorX, cursorY, cursorX + cellWidth, cursorY + cellHeight, cursorPaint)
                }
                1 -> { // Underline cursor - thin line at bottom
                    val underlineHeight = maxOf(2f, cellHeight * 0.15f)
                    canvas.drawRect(cursorX, cursorY + cellHeight - underlineHeight, cursorX + cellWidth, cursorY + cellHeight, cursorPaint)
                }
                2 -> { // Bar/I-beam cursor - thin vertical line
                    val barWidth = maxOf(2f, cellWidth * 0.15f)
                    canvas.drawRect(cursorX, cursorY, cursorX + barWidth, cursorY + cellHeight, cursorPaint)
                }
                else -> { // Default to bar
                    val barWidth = maxOf(2f, cellWidth * 0.15f)
                    canvas.drawRect(cursorX, cursorY, cursorX + barWidth, cursorY + cellHeight, cursorPaint)
                }
            }
        }
    }

    /**
     * Convert Termux color index to Android Color.
     *
     * Wave 4.a — Termux's TextStyle.decodeForeColor returns either:
     *   - 0..511 for indexed values (incl. 256/257/258 special FG/BG/CURSOR)
     *   - 0xFFRRGGBB (i.e. negative signed Int with alpha 0xFF) for 24-bit
     *     true-color from `SGR 38;2;R;G;B` / `SGR 48;2;R;G;B`.
     *
     * We MUST detect the true-color form before the `colorIndex < 16` check —
     * otherwise a negative signed value passes that test and we index
     * `defaultColors[negative]` → ArrayIndexOutOfBoundsException. Android's
     * Color int is ARGB, so the encoded value is a valid Color as-is.
     */
    private fun termuxColorToAndroid(colorIndex: Int): Int {
        return when {
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND -> Color.WHITE
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND -> Color.BLACK
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR -> Color.WHITE
            // True-color (alpha byte set to 0xFF). Cover both signed and the
            // unsigned interpretation defensively.
            (colorIndex and 0xFF000000.toInt()) == 0xFF000000.toInt() -> colorIndex
            colorIndex < 16 -> defaultColors[colorIndex]
            colorIndex < 256 -> {
                // 256-color palette
                if (colorIndex < 232) {
                    // Color cube (6x6x6): indices 16-231
                    val idx = colorIndex - 16
                    val r = (idx / 36) * 51
                    val g = ((idx / 6) % 6) * 51
                    val b = (idx % 6) * 51
                    Color.rgb(r, g, b)
                } else {
                    // Grayscale: indices 232-255
                    val gray = (colorIndex - 232) * 10 + 8
                    Color.rgb(gray, gray, gray)
                }
            }
            else -> Color.WHITE
        }
    }

    /**
     * Get text content at screen coordinates
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @return Line of text at the coordinates, or null if out of bounds
     */
    private fun getTextAtPosition(x: Float, y: Float): Pair<Int, String>? {
        val row = ((y - paddingTop + scrollY) / cellHeight).toInt()
        val col = ((x - paddingLeft) / cellWidth).toInt()

        if (row < 0 || col < 0 || row >= terminalRows || col >= terminalCols) {
            return null
        }

        // Try Termux buffer first - use getSelectedText for row text
        termuxBuffer?.let { buffer ->
            val lineText = try {
                buffer.getSelectedText(0, row, terminalCols, row) ?: ""
            } catch (e: Exception) {
                ""
            }
            return Pair(row, lineText)
        }

        // Fall back to old buffer
        terminalBuffer?.let { buffer ->
            val lineChars = buffer.getLine(row)
            val lineText = lineChars?.map { it.char }?.joinToString("") ?: ""
            return Pair(row, lineText)
        }

        return null
    }

    /**
     * Detect URL at the given position
     * @param x Touch X coordinate
     * @param y Touch Y coordinate
     * @return Detected URL or null
     */
    private fun detectUrlAtPosition(x: Float, y: Float): String? {
        val (row, lineText) = getTextAtPosition(x, y) ?: return null

        // Find all URLs in the line
        val matches = urlPattern.findAll(lineText)

        // Calculate the column position
        val col = ((x - paddingLeft) / cellWidth).toInt()

        // Check if the touch position is within any URL
        for (match in matches) {
            if (col >= match.range.first && col <= match.range.last) {
                var url = match.value
                // Add http:// prefix if it starts with www.
                if (url.startsWith("www.", ignoreCase = true) && !url.startsWith("http", ignoreCase = true)) {
                    url = "http://$url"
                }
                return url
            }
        }

        return null
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Handle pinch-to-zoom first (multi-touch)
        if (event.pointerCount >= 2) {
            scaleGestureDetector.onTouchEvent(event)
            if (isScaling) {
                return true
            }
        }

        // Handle custom multi-touch gestures (tmux/screen shortcuts)
        if (terminalGestureHandler != null && event.pointerCount >= 2) {
            if (terminalGestureHandler?.onTouchEvent(event) == true) {
                return true
            }
        }

        // Handle single-finger gestures (scroll, tap, long press)
        if (!isScaling) {
            gestureDetector.onTouchEvent(event)
        }

        // Reset scaling flag when touch ends
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isScaling = false
        }

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
        // OR in any sticky modifier set by the custom keyboard bar so a CTL
        // tap on the bar followed by an IME letter still produces a control
        // code, not a literal character (Issue #37).
        val isCtrl = event.isCtrlPressed || pendingCtrl
        // Wave 3.8 — distinguish AltGr (right-Alt only) from real Alt. EU
        // keyboards use AltGr to type # @ € € via the layout. We must NOT
        // treat AltGr like an Esc-prefix Alt — let the layout's unicodeChar
        // through unmodified.
        val isLeftAlt = (event.metaState and KeyEvent.META_ALT_LEFT_ON) != 0
        val isRightAlt = (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
        val isAltGr = isRightAlt && !isLeftAlt
        val isAlt = (event.isAltPressed || pendingAlt) && !isAltGr
        val isShift = event.isShiftPressed

        // Handle Ctrl+letter combinations (send control codes)
        if (isCtrl && !isAlt) {
            val ctrlCode = getCtrlCode(keyCode)
            if (ctrlCode != null) {
                sendText(ctrlCode)
                consumePendingModifier()
                return true
            }
        }

        // Handle Alt+letter combinations (send ESC + letter)
        if (isAlt && !isCtrl) {
            val char = event.unicodeChar.toChar()
            if (char.isLetterOrDigit() || char in "!@#$%^&*()") {
                sendKeySequence("\u001b$char")
                consumePendingModifier()
                return true
            }
        }

        when (keyCode) {
            // Basic keys
            KeyEvent.KEYCODE_ENTER -> {
                sendText("\r")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                sendText("\b")
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                sendKeySequence("\u001b[3~")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendText("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendText("\u001b")
                return true
            }

            // Arrow keys
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

            // Navigation keys
            KeyEvent.KEYCODE_MOVE_HOME -> {
                sendKeySequence("\u001b[H")
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                sendKeySequence("\u001b[F")
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                sendKeySequence("\u001b[5~")
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                sendKeySequence("\u001b[6~")
                return true
            }
            KeyEvent.KEYCODE_INSERT -> {
                sendKeySequence("\u001b[2~")
                return true
            }

            // Function keys F1-F12
            KeyEvent.KEYCODE_F1 -> {
                sendKeySequence("\u001bOP")
                return true
            }
            KeyEvent.KEYCODE_F2 -> {
                sendKeySequence("\u001bOQ")
                return true
            }
            KeyEvent.KEYCODE_F3 -> {
                sendKeySequence("\u001bOR")
                return true
            }
            KeyEvent.KEYCODE_F4 -> {
                sendKeySequence("\u001bOS")
                return true
            }
            KeyEvent.KEYCODE_F5 -> {
                sendKeySequence("\u001b[15~")
                return true
            }
            KeyEvent.KEYCODE_F6 -> {
                sendKeySequence("\u001b[17~")
                return true
            }
            KeyEvent.KEYCODE_F7 -> {
                sendKeySequence("\u001b[18~")
                return true
            }
            KeyEvent.KEYCODE_F8 -> {
                sendKeySequence("\u001b[19~")
                return true
            }
            KeyEvent.KEYCODE_F9 -> {
                sendKeySequence("\u001b[20~")
                return true
            }
            KeyEvent.KEYCODE_F10 -> {
                sendKeySequence("\u001b[21~")
                return true
            }
            KeyEvent.KEYCODE_F11 -> {
                sendKeySequence("\u001b[23~")
                return true
            }
            KeyEvent.KEYCODE_F12 -> {
                sendKeySequence("\u001b[24~")
                return true
            }
        }

        // Handle text input
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0) {
            // If a bar modifier is pending and the produced char is a single
            // codepoint, route through the helper so CTL/ALT applies.
            val chars = Character.toChars(unicodeChar)
            if ((pendingCtrl || pendingAlt) && chars.size == 1 &&
                sendCharWithPendingModifier(chars[0])) {
                return true
            }
            sendText(String(chars))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * Get control code for Ctrl+letter combination
     * Ctrl+A = 0x01, Ctrl+B = 0x02, ..., Ctrl+Z = 0x1A
     */
    private fun getCtrlCode(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> "\u0001"
            KeyEvent.KEYCODE_B -> "\u0002"
            KeyEvent.KEYCODE_C -> "\u0003"
            KeyEvent.KEYCODE_D -> "\u0004"
            KeyEvent.KEYCODE_E -> "\u0005"
            KeyEvent.KEYCODE_F -> "\u0006"
            KeyEvent.KEYCODE_G -> "\u0007"
            KeyEvent.KEYCODE_H -> "\u0008"
            KeyEvent.KEYCODE_I -> "\u0009"
            KeyEvent.KEYCODE_J -> "\u000A"
            KeyEvent.KEYCODE_K -> "\u000B"
            KeyEvent.KEYCODE_L -> "\u000C"
            KeyEvent.KEYCODE_M -> "\u000D"
            KeyEvent.KEYCODE_N -> "\u000E"
            KeyEvent.KEYCODE_O -> "\u000F"
            KeyEvent.KEYCODE_P -> "\u0010"
            KeyEvent.KEYCODE_Q -> "\u0011"
            KeyEvent.KEYCODE_R -> "\u0012"
            KeyEvent.KEYCODE_S -> "\u0013"
            KeyEvent.KEYCODE_T -> "\u0014"
            KeyEvent.KEYCODE_U -> "\u0015"
            KeyEvent.KEYCODE_V -> "\u0016"
            KeyEvent.KEYCODE_W -> "\u0017"
            KeyEvent.KEYCODE_X -> "\u0018"
            KeyEvent.KEYCODE_Y -> "\u0019"
            KeyEvent.KEYCODE_Z -> "\u001A"
            KeyEvent.KEYCODE_SPACE -> "\u0000"  // Ctrl+Space = NUL
            KeyEvent.KEYCODE_LEFT_BRACKET -> "\u001B"  // Ctrl+[ = ESC
            KeyEvent.KEYCODE_BACKSLASH -> "\u001C"  // Ctrl+\
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "\u001D"  // Ctrl+]
            else -> null
        }
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {
        editorInfo.inputType = EditorInfo.TYPE_NULL
        // IME_ACTION_DONE makes the soft keyboard show a recognizable
        // ENTER glyph (down-left arrow) AND routes its press through
        // performEditorAction(IME_ACTION_DONE) which our InputConnection
        // handles. Without an explicit action, IMEs would emit
        // performEditorAction(IME_ACTION_UNSPECIFIED) which our switch
        // ignored — that's Issue #49 (Android IME ENTER does nothing
        // while custom-bar ENT works).
        editorInfo.imeOptions = EditorInfo.IME_ACTION_DONE or
                                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                                EditorInfo.IME_FLAG_NO_EXTRACT_UI

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

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Single tap = toggle keyboard (mobile-first UX)
            toggleKeyboard()
            Logger.d("TerminalView", "Single tap - toggling keyboard")
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double tap = select word at position
            selectWordAtPosition(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Long press detection: URL or text context menu
            val url = detectUrlAtPosition(e.x, e.y)
            if (url != null) {
                // URL detected - show URL menu
                onUrlDetected?.invoke(url)
                Logger.d("TerminalView", "URL detected: $url")
            } else {
                // No URL - show text context menu
                showCustomContextMenu(e.x, e.y)
                Logger.d("TerminalView", "Long press - showing context menu")
            }
        }
    }

    /**
     * Listener for pinch-to-zoom gestures (mobile-friendly font size control)
     */
    private inner class PinchZoomListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var initialFontSize = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            initialFontSize = textPaint.textSize / resources.displayMetrics.density
            Logger.d("TerminalView", "Pinch zoom started at font size: $initialFontSize")
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newSize = (initialFontSize * scaleFactor).coerceIn(minFontSize, maxFontSize)

            // Update font size
            textPaint.textSize = newSize * resources.displayMetrics.density
            calculateCellDimensions()
            invalidate()

            Logger.d("TerminalView", "Pinch zoom: scale=$scaleFactor, newSize=$newSize")
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            val finalSize = textPaint.textSize / resources.displayMetrics.density
            Logger.d("TerminalView", "Pinch zoom ended at font size: $finalSize")

            // Notify listener of font size change
            onFontSizeChanged?.invoke(finalSize)
        }
    }

    /**
     * Callback for font size changes (from pinch-to-zoom)
     */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /**
     * Select word at touch position (for double-tap gesture)
     */
    private fun selectWordAtPosition(x: Float, y: Float) {
        // Get text at touch position using existing method
        val result = getTextAtPosition(x, y) ?: return
        val text = result.second  // Extract string from Pair<Int, String>

        // Guard against empty text
        if (text.isEmpty()) return

        // Find the word at this position
        val col = ((x - paddingLeft) / cellWidth).toInt().coerceIn(0, text.length - 1)

        if (text.isBlank() || col >= text.length) return

        // Find word boundaries
        var wordStart = col
        var wordEnd = col

        // Expand left to find word start
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) {
            wordStart--
        }

        // Expand right to find word end
        while (wordEnd < text.length - 1 && !text[wordEnd + 1].isWhitespace()) {
            wordEnd++
        }

        // Extract the word
        val word = text.substring(wordStart, wordEnd + 1).trim()

        if (word.isNotBlank()) {
            // `terminal_copy_on_select` (default true) gates auto-copy on
            // double-tap. Off → just visually select, leave clipboard alone.
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context)
            val copyOnSelect = prefs.getBoolean("terminal_copy_on_select", true)
            if (copyOnSelect) {
                io.github.tabssh.utils.ClipboardHelper.copy(
                    context, label = "Selected Text", text = word, sensitive = true
                )
                android.widget.Toast.makeText(context, "Copied: $word", android.widget.Toast.LENGTH_SHORT).show()
            }
            Logger.d("TerminalView", "Double-tap selected word: $word (copied=$copyOnSelect)")
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
        // Clean up old terminal emulator listener
        terminalListener?.let { listener ->
            terminalEmulator?.removeListener(listener)
        }
        terminalListener = null

        // Clean up Termux bridge listener
        currentBridgeListener?.let { listener ->
            termuxBridge?.removeListener(listener)
            Logger.d("TerminalView", "Removed bridge listener on detach")
        }
        currentBridgeListener = null
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
        repeat(beforeLength) { terminalView.sendText("\u007F") } // DEL, not BS
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        // Handle deletion in code points (for multi-byte characters)
        repeat(beforeLength) { terminalView.sendText("\u007F") } // DEL, not BS
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
        // Issue #49: terminals always interpret the IME's ENTER button as a
        // line submit, regardless of which action the IME chose to bind to
        // it (DONE, GO, SEND, NEXT, UNSPECIFIED, NONE — Gboard sends
        // UNSPECIFIED with TYPE_NULL fields, which our previous switch
        // ignored, swallowing every ENTER press).
        terminalView.sendText("\r")
        return true
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
        text?.let {
            // Convert newline to carriage return for SSH compatibility
            val converted = it.toString().replace("\n", "\r")
            // If the bar has CTL/ALT pending and we got a single character,
            // apply the modifier so chords like CTL+c work via IME (Issue #37).
            if (converted.length == 1 &&
                (terminalView.isPendingCtrl() || terminalView.isPendingAlt()) &&
                terminalView.sendCharWithPendingModifier(converted[0])) {
                return true
            }
            terminalView.sendText(converted)
        }
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
