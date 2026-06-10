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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import io.github.tabssh.terminal.emulator.TerminalEmulator
import io.github.tabssh.terminal.emulator.TerminalBuffer
import io.github.tabssh.terminal.emulator.TerminalListener
import io.github.tabssh.terminal.renderer.TerminalRenderer
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.utils.logging.Logger

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

    // Rendering components
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint()
    private val cursorPaint = Paint()

    // Cursor blink state — toggled every 500 ms when blink is enabled.
    // Starts true so the cursor is visible immediately (even before the
    // emulator attaches and DECTCEM has been negotiated).
    private var cursorBlinkPhase = true
    private val cursorBlinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cursorBlinkRunnable: Runnable = object : Runnable {
        override fun run() {
            cursorBlinkPhase = !cursorBlinkPhase
            invalidate()
            cursorBlinkHandler.postDelayed(this, CURSOR_BLINK_INTERVAL_MS)
        }
    }

    // Resize debounce — keyboard animation fires two onSizeChanged events
    // ~30 ms apart (intermediate size then final size). Sending both to the
    // SSH server causes a double SIGWINCH and confuses some TUIs. We hold
    // the resize 80 ms; only the last event in a burst is forwarded.
    private val resizeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingResize: Runnable? = null

    // Touch and input handling
    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val scroller: OverScroller
    // Float scroll position for sub-row precision. Using a float here lets
    // the canvas translate by the fractional row offset so rows glide smoothly
    // instead of snapping one full row at a time.
    private var scrollYf = 0f
    // Integer scroll position used by callers that need whole-pixel values
    // (row coordinate conversions, Termux renderer, search highlights).
    // Named scrollYInt to avoid shadowing View.getScrollY().
    private val scrollYInt: Int get() = scrollYf.toInt()

    /**
     * Scroll direction convention.
     *
     * false (default) = standard mobile convention: swipe UP → see older
     *   scrollback. Matches JuiceSSH, Termux, ConnectBot.
     * true = reversed: swipe DOWN → see older scrollback. The old behaviour
     *   before this preference was added.
     *
     * Set by the host activity from the `terminal_reverse_scroll` preference.
     */
    var reverseScrollDirection: Boolean = false
    /** Accumulated scroll pixels for mouse-wheel forwarding (sub-cell precision). */
    private var mouseScrollAccum = 0f

    // Reusable buffer for drawText — avoids one String allocation per glyph per frame.
    private val charBuf = CharArray(2)

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

    // Issue #168 — edge-swipe callback. Fires when a single-finger fling
    // starts within EDGE_SWIPE_DP (24dp — matches system back-gesture
    // inset) of the left or right edge AND the dominant axis is
    // horizontal AND the swipe heads back into the viewport. We react
    // to a fling (not a static drag), so the system back gesture wins
    // on slow pulls and ours wins on quick flicks. Direction:
    //   -1 = previous tab (swipe right from left edge),
    //   +1 = next tab     (swipe left  from right edge).
    var onEdgeSwipe: ((direction: Int) -> Unit)? = null
    private val edgeSwipeDp = 24

    // ─────────────────────────────────────────────────────────────────
    // Drag-to-select range copy (issue #73)
    //
    // Long-press → context menu → "Select text…" calls
    // `enterSelectionMode(x, y)`, which sets both anchor and focus to
    // the touched cell and notifies the host to start a floating
    // ActionMode with a Copy item. While `selectionActive` is true,
    // single-finger drags update the focus endpoint and the
    // gestureDetector's tap/double-tap/URL paths are bypassed. Tapping
    // outside the highlight calls `exitSelectionMode`, which the host
    // is also expected to call from `ActionMode.onDestroyActionMode`.
    //
    // Coordinates are screen-cell coords (col ∈ [0, terminalCols),
    // row ∈ [0, terminalRows)). v1 is visible-screen-only — no
    // scrollback selection. The user must scroll to bring older lines
    // into view BEFORE entering selection mode.
    // ─────────────────────────────────────────────────────────────────
    private var selectionActive = false
    private var selectionAnchorCol = 0
    private var selectionAnchorRow = 0
    private var selectionFocusCol = 0
    private var selectionFocusRow = 0
    /** -1 = none, 0 = anchor handle being dragged, 1 = focus handle. */
    private var selectionDragHandle = -1
    private val selectionPaint = Paint().apply {
        color = 0x55_4FC3F7.toInt()        // translucent light-blue
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val selectionHandlePaint = Paint().apply {
        color = 0xFF_4FC3F7.toInt()        // solid light-blue
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    /** Hit radius for handle drag detection, in pixels. ~40dp — large enough for comfortable drag. */
    private val handleHitRadiusPx by lazy { 40f * resources.displayMetrics.density }
    /** Visual radius for the handle bubble, in pixels. ~14dp — clearly visible without obscuring text. */
    private val handleDrawRadiusPx by lazy { 14f * resources.displayMetrics.density }

    // ── Find-in-scrollback search highlights ────────────────────────────────
    private var searchMatches: List<SearchMatch> = emptyList()
    private var searchCurrentMatchIndex: Int = -1
    /** Translucent amber for non-active matches. */
    private val searchHighlightPaint = Paint().apply {
        color = 0x66_FFD740.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    /** Opaque orange for the active match (scrolled-to hit). */
    private val searchCurrentMatchPaint = Paint().apply {
        color = 0xCC_FF6D00.toInt()
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /**
     * A single search hit in external-row space.
     *
     * [externalRow]: Termux external row — negative for scrollback (−1 = most
     *                recent scrollback line), non-negative for visible screen rows.
     * [colStart] / [colEnd]: inclusive/exclusive column bounds of the matched text.
     */
    data class SearchMatch(val externalRow: Int, val colStart: Int, val colEnd: Int)

    /** Replace the search highlight set and repaint. */
    fun setSearchHighlights(matches: List<SearchMatch>, currentIndex: Int) {
        searchMatches = matches
        searchCurrentMatchIndex = currentIndex
        invalidate()
    }

    /** Remove all search highlights. */
    fun clearSearchHighlights() {
        if (searchMatches.isEmpty() && searchCurrentMatchIndex == -1) return
        searchMatches = emptyList()
        searchCurrentMatchIndex = -1
        invalidate()
    }

    /**
     * Scroll the terminal scrollback by one page in the given direction.
     *
     * @param direction  +1 = scroll toward older content (page up),
     *                   -1 = scroll toward newest content (page down).
     *
     * One page = the number of rows that fit in the current view height,
     * matching the convention of ConnectBot / Termux volume-key paging.
     * The scroll is animated via the OverScroller so the user gets the same
     * deceleration feel as a finger fling.
     */
    fun scrollByPage(direction: Int) {
        if (cellHeight <= 0f || terminalRows <= 0) return
        val pagePx = (terminalRows * cellHeight).toInt()
        val from   = scrollYInt
        val to     = (from + direction * pagePx).coerceIn(0, maxScrollYPx())
        scroller.startScroll(0, from, 0, to - from, 200)
        postInvalidateOnAnimation()
    }

    /**
     * Scroll the view so that the match at [index] is vertically centred,
     * then update the current-match pointer and repaint.
     */
    fun scrollToSearchMatch(index: Int) {
        if (index !in searchMatches.indices) return
        searchCurrentMatchIndex = index
        val extRow = searchMatches[index].externalRow
        val rows = termuxBridge?.getRows() ?: return
        // Place the match at the vertical centre of the visible area.
        val targetScrollRows = (rows / 2 - extRow).coerceAtLeast(0)
        scrollYf = (targetScrollRows * cellHeight).toFloat().coerceIn(0f, maxScrollYPx().toFloat())
        invalidate()
    }

    /**
     * When true, the next ACTION_DOWN on the terminal starts selection
     * mode at the touch point (instead of scrolling / toggling the
     * keyboard). Armed via the SEL key on the multi-row keyboard bar
     * and consumed on the first touch — single-shot, so an accidental
     * SEL tap doesn't leave the view stuck in a non-scroll state.
     */
    private var selectionArmed = false
    // Pending runnable that defers entering selection mode until we confirm
    // the touch is single-finger (not the first finger of a pinch gesture).
    private var selectionArmRunnable: Runnable? = null

    /** Called by the host activity when the SEL key fires. */
    fun armSelectionForNextDrag() {
        selectionArmed = true
        // Tiny visual hint that something will happen on next touch —
        // background tint shift is too disruptive, so we just bump the
        // drag-handle highlight colour briefly. The host activity also
        // shows a toast; this is a belt-and-suspenders affordance.
        invalidate()
    }

    /**
     * Host activities subscribe to know when to start the floating
     * ActionMode. Fires once each time the user enters selection mode.
     */
    var onSelectionStarted: (() -> Unit)? = null

    init {
        // Enable focus and touch
        isFocusable = true
        isFocusableInTouchMode = true
        setOnTouchListener(this)

        // Configure scroller and gesture detectors
        scroller = OverScroller(context)
        gestureDetector = GestureDetector(context, TerminalGestureListener())
        scaleGestureDetector = ScaleGestureDetector(context, PinchZoomListener())

        // Setup default text paint. Init with the bundled JetBrains Mono
        // Nerd Font (better hinting than the system mono on most OEM ROMs).
        // The host activity calls `setFont(prefValue)` shortly after — this
        // is just so a TerminalView shown before that read still draws with
        // a decent typeface instead of the previous Typeface.MONOSPACE.
        textPaint.typeface = io.github.tabssh.utils.FontManager.getTypeface(
            context, "jetbrains_mono_nerd"
        )
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
                post {
                    val prefs = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(context)
                    val blink = prefs.getBoolean("terminal_cursor_blink", true)
                    // Control the blink timer based on DECTCEM state, but never let a
                    // DECTCEM hide (\033[?25l) set cursorBlinkPhase to false. On mobile
                    // the cursor disappearing at the prompt (e.g. Claude Code sends
                    // ?25l during tool execution and doesn't restore it after a tab
                    // switch) is a hard UX failure. stopCursorBlink() already leaves
                    // cursorBlinkPhase=true, so just don't override it with visible=false.
                    if (visible) {
                        if (blink) startCursorBlink() else stopCursorBlink()
                    } else {
                        if (blink) stopCursorBlink()
                        // cursorBlinkPhase intentionally left as-is (stays true).
                    }
                    invalidate()
                }
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

        // Start cursor blink if the preference is enabled
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean("terminal_cursor_blink", true)) {
            startCursorBlink()
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
                // The four-arg `invalidate(left, top, right, bottom)` is
                // deprecated — modern Android composer ignores the rect
                // and always invalidates the whole view. The dirty-row
                // tracking above stays useful for any future on-canvas
                // optimisation; the call itself is now full-invalidate.
                invalidate()
            }
        }
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
        // Any user input resets cursor blink to the visible phase. This ensures
        // the cursor is never stuck invisible (e.g. after a DECTCEM ?25l that
        // was never followed by ?25h due to a tab switch or network lag).
        if (!cursorBlinkPhase) {
            cursorBlinkPhase = true
            invalidate()
        }

        // Try Termux bridge first (preferred for SSH connections)
        termuxBridge?.let { bridge ->
            bridge.sendText(text)
            return
        }

        // Fall back to local terminal emulator (in-memory, no remote send).
        // Real SSH/VNC paths always have a TermuxBridge attached.
        terminalEmulator?.sendText(text)
            ?: Logger.w("TerminalView", "Unable to send text - no bridge attached")
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
                val ctrlChar: Char = when {
                    upper in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar()
                    c == ' ' || c == '@' -> '\u0000'
                    c == '[' -> '\u001B'
                    c == '\\' -> '\u001C'
                    c == ']' -> '\u001D'
                    c == '^' -> '\u001E'
                    c == '_' -> '\u001F'
                    c == '?' -> '\u007F'
                    else -> c
                }
                sendText(ctrlChar.toString())
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
     * Toggle soft keyboard (mobile-first UX).
     *
     * `inputMethodManager.isActive(this)` checks "is the IME bound to
     * this view" — NOT "is the IME visible". After the user explicitly
     * hides the keyboard (e.g. via back-press or a second tap), the IME
     * stays bound to TerminalView but is no longer drawn. Using
     * `isActive` then routed every subsequent toggle into the hide
     * branch, which is a no-op when already hidden, so the keyboard
     * never reappeared. Symptom: works once, then dead until the
     * activity is recreated.
     *
     * Real IME visibility lives in WindowInsets — query it via
     * `WindowInsetsCompat.Type.ime()`.
     */
    fun toggleKeyboard() {
        if (!hasWindowFocus()) return
        val visible = ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) {
            inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
            Logger.d("TerminalView", "Hiding keyboard")
        } else {
            requestFocus()
            inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            Logger.d("TerminalView", "Showing keyboard")
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

        if (cellWidth <= 0 || cellHeight <= 0) return

        val newCols = (availableWidth / cellWidth).toInt().coerceAtLeast(40)
        val newRows = (availableHeight / cellHeight).toInt().coerceAtLeast(10)

        if (newCols == terminalCols && newRows == terminalRows) return

        // Cancel any resize that hasn't fired yet; the final settled size wins.
        pendingResize?.let { resizeHandler.removeCallbacks(it) }

        val r = Runnable {
            pendingResize = null
            terminalCols = newCols
            terminalRows = newRows
            termuxBridge?.resize(terminalCols, terminalRows)
            terminalBuffer?.resize(terminalRows, terminalCols)
            Logger.d("TerminalView", "Terminal resized: ${terminalRows}x${terminalCols}")
        }
        pendingResize = r
        resizeHandler.postDelayed(r, RESIZE_DEBOUNCE_MS)
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
                Logger.d("TerminalView", "Rendering terminal: ${buffer.getRows()}x${buffer.getCols()}, scroll=$scrollYInt")
                renderer.render(canvas, buffer, paddingLeft.toFloat(), paddingTop.toFloat(),
                    cellWidth, cellHeight, scrollYInt)
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

        // Convert pixel scroll offset to row offset (negative = into scrollback).
        val scrollRows = if (cellHeight > 0f) (scrollYInt / cellHeight).toInt() else 0

        // Sub-row smooth scrolling: apply fractional pixel offset as a canvas
        // translate so rows glide continuously rather than snapping one full row
        // at a time. The translate shifts content UP by the sub-row fraction;
        // one extra row (row `rows`) is drawn at the bottom to fill the resulting
        // gap. The canvas is saved/restored so overlays drawn after the loop are
        // not affected by the translate.
        // NOTE: must use (scrollYf % cellHeight), NOT (scrollYf - scrollY).
        // View.getScrollY() is always 0 because we never call View.scrollTo();
        // using it made fracOffset equal the full scrollYf, shifting all content
        // off-screen the moment the user scrolled.
        val fracOffset = if (cellHeight > 0f) scrollYf % cellHeight else 0f
        if (fracOffset > 0f) canvas.save()
        if (fracOffset > 0f) canvas.translate(0f, -fracOffset)

        // Draw all rows (plus one extra at the bottom when scrolling is fractional
        // so the translate gap is filled).
        val extraRow = if (fracOffset > 0f) 1 else 0
        for (row in 0 until rows + extraRow) {
            val rowTop = startY + row * cellHeight
            val rowBottom = startY + (row + 1) * cellHeight
            val y = rowBottom - textPaint.descent()

            // Clear row background
            canvas.drawRect(startX, rowTop, width.toFloat(), rowBottom, backgroundPaint)

            // Negative externalRow values index into the scrollback transcript.
            val internalRow = try {
                buffer.externalToInternalRow(row - scrollRows)
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

            // ── Pass 1: non-default cell backgrounds ────────────────────────
            // Backgrounds are per-cell; text is batched below. Separating the
            // two passes avoids switching paint state between rect and text ops.
            var bgCharIdx = 0
            var bgCol = 0
            while (bgCol < cols && bgCharIdx < charsUsed) {
                val style = try { terminalRow.getStyle(bgCol) } catch (_: Exception) { 0L }
                val bg = com.termux.terminal.TextStyle.decodeBackColor(style)
                if (bg != com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND) {
                    val bx = startX + bgCol * cellWidth
                    backgroundPaint.color = termuxColorToAndroid(bg)
                    canvas.drawRect(bx, rowTop, bx + cellWidth, rowBottom, backgroundPaint)
                }
                val ch = if (bgCharIdx < lineChars.size) lineChars[bgCharIdx] else ' '
                val cp = if (Character.isHighSurrogate(ch) && bgCharIdx + 1 < lineChars.size)
                    Character.toCodePoint(ch, lineChars[bgCharIdx + 1]) else ch.code
                val cw = com.termux.terminal.WcWidth.width(cp)
                bgCol += if (cw > 0) cw else 1
                bgCharIdx += if (cp > 0xFFFF) 2 else 1
            }
            backgroundPaint.color = currentTheme?.background ?: Color.BLACK

            // ── Pass 2: text in style runs ──────────────────────────────────
            // Batching runs reduces canvas.drawText calls from ~cols (1 per char)
            // to ~runs (1 per style change). For typical ASCII output this is
            // a 10–30× reduction in JNI draw calls, eliminating the scroll jank.
            //
            // Run invariants:
            //   - All chars share the same fg colour + effects (bold/italic/underline)
            //   - All chars are single-cell-width (double-width chars flush & draw solo)
            // Spaces and NUL cells break the run but are not drawn themselves.
            val runBuf = StringBuilder(cols)
            var runStartCol = 0
            var runStyle   = 0L

            fun flushRun() {
                if (runBuf.isEmpty()) return
                val fg  = com.termux.terminal.TextStyle.decodeForeColor(runStyle)
                val eff = com.termux.terminal.TextStyle.decodeEffect(runStyle)
                textPaint.color          = termuxColorToAndroid(fg)
                textPaint.isFakeBoldText = (eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                textPaint.textSkewX      = if ((eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) -0.25f else 0f
                textPaint.isUnderlineText= (eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                canvas.drawText(runBuf.toString(), startX + runStartCol * cellWidth, y, textPaint)
                textPaint.isFakeBoldText = false
                textPaint.textSkewX      = 0f
                textPaint.isUnderlineText= false
                runBuf.clear()
            }

            var charIndex = 0
            var col = 0
            while (col < cols && charIndex < charsUsed) {
                val char = if (charIndex < lineChars.size) lineChars[charIndex] else ' '
                val codePoint: Int
                val charsConsumed: Int
                if (Character.isHighSurrogate(char) && charIndex + 1 < lineChars.size) {
                    codePoint   = Character.toCodePoint(char, lineChars[charIndex + 1])
                    charsConsumed = 2
                } else {
                    codePoint   = char.code
                    charsConsumed = 1
                }

                val style     = try { terminalRow.getStyle(col) } catch (_: Exception) { 0L }
                val charWidth = com.termux.terminal.WcWidth.width(codePoint)
                val isVisible = codePoint != 0 && codePoint != ' '.code

                if (isVisible) {
                    // Style differs or double-width char → flush current run first.
                    val sameStyle = runBuf.isNotEmpty() &&
                        com.termux.terminal.TextStyle.decodeForeColor(style) == com.termux.terminal.TextStyle.decodeForeColor(runStyle) &&
                        com.termux.terminal.TextStyle.decodeEffect(style)    == com.termux.terminal.TextStyle.decodeEffect(runStyle)
                    val isWide = charWidth > 1
                    if (!sameStyle || isWide) flushRun()

                    if (isWide) {
                        // Double-width glyph: draw solo, then advance two cells.
                        val fg  = com.termux.terminal.TextStyle.decodeForeColor(style)
                        val eff = com.termux.terminal.TextStyle.decodeEffect(style)
                        textPaint.color          = termuxColorToAndroid(fg)
                        textPaint.isFakeBoldText = (eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                        textPaint.textSkewX      = if ((eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) -0.25f else 0f
                        textPaint.isUnderlineText= (eff and com.termux.terminal.TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
                        val wideCount = Character.toChars(codePoint, charBuf, 0)
                        canvas.drawText(charBuf, 0, wideCount, startX + col * cellWidth, y, textPaint)
                        textPaint.isFakeBoldText = false
                        textPaint.textSkewX      = 0f
                        textPaint.isUnderlineText= false
                    } else {
                        // Normal single-cell char: append to run.
                        if (runBuf.isEmpty()) { runStartCol = col; runStyle = style }
                        val count = Character.toChars(codePoint, charBuf, 0)
                        runBuf.append(charBuf, 0, count)
                    }
                } else {
                    // Space / NUL breaks any active run.
                    flushRun()
                }

                col      += if (charWidth > 0) charWidth else 1
                charIndex += charsConsumed
            }
            flushRun()
        }

        // Restore canvas after the translated row drawing so that selection
        // handles, search highlights, and the cursor are drawn at true screen
        // coordinates (not offset by the sub-row fraction).
        if (fracOffset > 0f) canvas.restore()

        // Selection overlay sits between glyphs and cursor — text below
        // remains readable through the translucent fill, and the cursor
        // still draws on top.
        drawSelectionOverlay(canvas)

        // Search highlights — drawn above selection so they're always visible.
        drawSearchHighlights(canvas, scrollRows)

        // Draw cursor based on style (0=block, 1=underline, 2=bar/I-beam).
        // Hide only when scrolled into scrollback (cursor belongs to the live screen)
        // or out of bounds. DECTCEM (\033[?25l) is intentionally ignored here — on
        // mobile, losing the cursor at the prompt (e.g. Claude Code hide/show race
        // after a tab switch) is a hard UX failure. The blink timer is still paused
        // on DECTCEM hide to avoid visual noise during batch ncurses redraws.
        if (cursorBlinkPhase && scrollRows == 0 && cursorRow in 0 until rows && cursorCol in 0 until cols) {
            val cursorX = startX + cursorCol * cellWidth
            val cursorY = startY + cursorRow * cellHeight
            cursorPaint.color = currentTheme?.cursor ?: Color.WHITE
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
            // Theme-driven defaults. `currentTheme` is set by `applyTheme()`;
            // when null (no theme loaded yet) fall back to white-on-black so
            // the first frame renders sanely. Previously these were hardcoded
            // Color.WHITE / Color.BLACK / Color.WHITE which made the 23 built-in
            // themes look identical for COLOR_INDEX_FOREGROUND/BACKGROUND/CURSOR
            // cells (i.e. nearly every cell in a typical session).
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND ->
                currentTheme?.foreground ?: Color.WHITE
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND ->
                currentTheme?.background ?: Color.BLACK
            colorIndex == com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR ->
                currentTheme?.cursor ?: Color.WHITE
            // True-color (alpha byte set to 0xFF). Cover both signed and the
            // unsigned interpretation defensively.
            (colorIndex and 0xFF000000.toInt()) == 0xFF000000.toInt() -> colorIndex
            colorIndex < 16 ->
                // ANSI 0-15. Theme.ansiColors is the per-theme palette; fall
                // back to the hardcoded defaults if the theme's palette has
                // fewer entries than expected (defensive against custom theme
                // JSON with a short array).
                currentTheme?.ansiColors?.getOrNull(colorIndex) ?: defaultColors[colorIndex]
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
        // Compute row the same way renderTermuxBuffer does: screen row + scrollback
        // rows. The old formula ((y - paddingTop + scrollYInt) / cellHeight) mixes
        // pixels and integer-divides once, which gives different results from the
        // two-step version due to truncation and does not match the render path.
        val scrollRows = if (cellHeight > 0f) (scrollYInt / cellHeight).toInt() else 0
        val screenRow  = if (cellHeight > 0f) ((y - paddingTop) / cellHeight).toInt() else 0
        val row = screenRow + scrollRows
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
     * Return the text content of a row by index, without requiring a touch coordinate.
     * Used by detectUrlAtPosition to read adjacent rows for word-wrap URL joining.
     */
    private fun getRowText(row: Int): String {
        if (row < 0 || row >= terminalRows) return ""
        termuxBuffer?.let { buffer ->
            return try {
                buffer.getSelectedText(0, row, terminalCols, row) ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        terminalBuffer?.let { buffer ->
            val lineChars = buffer.getLine(row)
            return lineChars?.map { it.char }?.joinToString("") ?: ""
        }
        return ""
    }

    /**
     * Detect URL at the given position.
     * Also checks the following row to catch URLs that span a terminal word-wrap boundary.
     */
    private fun detectUrlAtPosition(x: Float, y: Float): String? {
        val (row, lineText) = getTextAtPosition(x, y) ?: return null
        val col = ((x - paddingLeft) / cellWidth).toInt()

        // Check if the touch point is within any URL on the current row.
        val matchInRow = urlPattern.findAll(lineText).firstOrNull { col in it.range }
        if (matchInRow != null) {
            var url = matchInRow.value
            if (url.startsWith("www.", ignoreCase = true) && !url.startsWith("http", ignoreCase = true)) {
                url = "http://$url"
            }
            return url
        }

        // The URL may be split across a word-wrap boundary: the tail of the current row
        // continues at the start of the next row with no whitespace between them. Try
        // joining the two rows and matching the combined text.
        val nextRowText = getRowText(row + 1)
        if (nextRowText.isNotEmpty()) {
            // Trim trailing whitespace from current row before joining — the terminal
            // buffer pads short lines with spaces up to terminalCols.
            val combined = lineText.trimEnd() + nextRowText
            val combinedMatches = urlPattern.findAll(combined)
            // The touch column falls somewhere in the current row's portion, so the
            // match must start at or before col to be the URL the user tapped on.
            for (match in combinedMatches) {
                if (match.range.first <= col) {
                    var url = match.value
                    if (url.startsWith("www.", ignoreCase = true) && !url.startsWith("http", ignoreCase = true)) {
                        url = "http://$url"
                    }
                    return url
                }
            }
        }

        return null
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // SEL key armed → defer entering selection mode by one frame so we can
        // cancel if a second finger arrives (i.e. the user is pinching, not
        // dragging a selection). ACTION_DOWN always has pointerCount==1 even for
        // pinch gestures — the second finger fires ACTION_POINTER_DOWN afterward.
        if (selectionArmed && event.actionMasked == MotionEvent.ACTION_DOWN) {
            selectionArmed = false
            val ex = event.x; val ey = event.y
            val r = Runnable {
                selectionArmRunnable = null
                enterSelectionMode(ex, ey)
                selectionDragHandle = 1  // pre-grab focus handle
            }
            selectionArmRunnable = r
            postDelayed(r, android.view.ViewConfiguration.getDoubleTapTimeout().toLong() / 4)
            return true
        }
        // Second finger arrived — cancel the pending selection entry so the
        // pinch-to-zoom handler gets a clean slate.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            selectionArmRunnable?.let { removeCallbacks(it); selectionArmRunnable = null }
        }

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

        // Selection mode swallows single-finger taps + drags so the
        // gesture detector doesn't fire keyboard-toggle / double-tap-
        // word-select / URL-detect while the user is shaping a range.
        if (handleSelectionTouch(event)) return true

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
            MotionEvent.ACTION_UP -> {
                // Route taps through performClick so accessibility services
                // can announce the click. Real input handling happens via
                // the gestureDetector wired up in onTouch(); this is just
                // the a11y contract.
                performClick()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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

        // Handle Ctrl+letter combinations (send control codes).
        // IMPORTANT: skip when Shift is also pressed — Ctrl+Shift+letter
        // is reserved for app-level commands (new tab / close tab /
        // palette / etc.). If we mapped it to a control code here we'd
        // both send a wrong byte to the shell AND prevent the activity
        // from seeing the shortcut, since this method already returns
        // true once it sends the byte. Falling through means the
        // activity's onKeyDown gets a fair shot.
        if (isCtrl && !isAlt && !isShift) {
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
                sendText("")
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

            // Arrow keys — Issue #171, xterm-style modifier propagation.
            // Plain: \e[A. Shift+Up: \e[1;2A. Ctrl+Up: \e[1;5A. Alt+Up:
            // \e[1;3A. Combinations are 1 + (1=Shift, 2=Alt, 4=Ctrl).
            // tmux, vim, less and most modern TUIs read these to switch
            // panes / jump words / extend selection.
            //
            // When the remote sets DECCKM (\033[?1h — application cursor mode,
            // e.g. vim normal mode), plain arrows must use SS3 (\033OA) not CSI
            // (\033[A). Modifier-qualified chords always use parameterised CSI.
            KeyEvent.KEYCODE_DPAD_UP -> {
                val appMode = isApplicationCursorKeysMode()
                sendKeySequence(if (appMode) ss3ArrowSeq('A', isShift, isAlt, isCtrl) else arrowSeq('A', isShift, isAlt, isCtrl))
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val appMode = isApplicationCursorKeysMode()
                sendKeySequence(if (appMode) ss3ArrowSeq('B', isShift, isAlt, isCtrl) else arrowSeq('B', isShift, isAlt, isCtrl))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val appMode = isApplicationCursorKeysMode()
                sendKeySequence(if (appMode) ss3ArrowSeq('C', isShift, isAlt, isCtrl) else arrowSeq('C', isShift, isAlt, isCtrl))
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val appMode = isApplicationCursorKeysMode()
                sendKeySequence(if (appMode) ss3ArrowSeq('D', isShift, isAlt, isCtrl) else arrowSeq('D', isShift, isAlt, isCtrl))
                return true
            }

            // Navigation keys — same modifier wrapping for HOME/END;
            // PAGE_UP/PAGE_DOWN/INS use the `\e[N~` family which has its
            // own modifier form `\e[N;<mod>~`.
            KeyEvent.KEYCODE_MOVE_HOME -> { sendKeySequence(arrowSeq('H', isShift, isAlt, isCtrl)); return true }
            KeyEvent.KEYCODE_MOVE_END -> { sendKeySequence(arrowSeq('F', isShift, isAlt, isCtrl)); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { sendKeySequence(tildeSeq(5, isShift, isAlt, isCtrl)); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { sendKeySequence(tildeSeq(6, isShift, isAlt, isCtrl)); return true }
            KeyEvent.KEYCODE_INSERT -> { sendKeySequence(tildeSeq(2, isShift, isAlt, isCtrl)); return true }

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
     * Issue #171 — xterm modifier-encoding helpers. Modifier param is
     * 1 + (1=Shift, 2=Alt, 4=Ctrl). With no modifier we emit the legacy
     * short form (\e[A) for compatibility with the smallest set of
     * receivers; with any modifier we use the parameterised form
     * (\e[1;<mod>A or \e[N;<mod>~).
     */
    private fun modifierCode(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    private fun arrowSeq(letter: Char, shift: Boolean, alt: Boolean, ctrl: Boolean): String {
        val mod = modifierCode(shift, alt, ctrl)
        return if (mod == 1) "\u001b[$letter" else "\u001b[1;$mod$letter"
    }

    /**
     * SS3 form of an arrow key for application cursor key mode (DECCKM active).
     * When vim or another TUI sets \033[?1h, arrow keys must use SS3 (\033OA)
     * instead of CSI (\033[A). With any modifier pressed the parameterised CSI
     * form is still used since SS3 has no modifier extension in xterm protocol.
     */
    private fun ss3ArrowSeq(letter: Char, shift: Boolean, alt: Boolean, ctrl: Boolean): String {
        val mod = modifierCode(shift, alt, ctrl)
        return if (mod == 1) "\u001bO$letter" else "\u001b[1;$mod$letter"
    }

    /**
     * Returns true when the Termux emulator reports application cursor key mode
     * (DECCKM, \033[?1h) is active — i.e. the remote app has asked the terminal
     * to send SS3 sequences for arrow keys. False when no bridge or no emulator.
     */
    fun isApplicationCursorKeysMode(): Boolean =
        termuxBridge?.getEmulator()?.isCursorKeysApplicationMode() == true

    private fun tildeSeq(num: Int, shift: Boolean, alt: Boolean, ctrl: Boolean): String {
        val mod = modifierCode(shift, alt, ctrl)
        return if (mod == 1) "\u001b[$num~" else "\u001b[$num;$mod~"
    }

    /**
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
            val termuxEmulator = termuxBridge?.getEmulator()
            if (termuxEmulator != null && termuxEmulator.isMouseTrackingActive()) {
                // Remote app (e.g. tmux with mouse on) owns scroll — forward as
                // mouse wheel events so it can scroll its own scrollback.
                // Accumulate sub-cell distances and fire one tick per cell height
                // to produce a smooth, natural feel without event flooding.
                mouseScrollAccum += distanceY
                val cellH = if (cellHeight > 0f) cellHeight else 20f
                val ticks = (mouseScrollAccum / cellH).toInt()
                if (ticks != 0) {
                    mouseScrollAccum -= ticks * cellH
                    val col = ((e2.x / (if (cellWidth > 0f) cellWidth else cellH)) + 1).toInt().coerceIn(1, terminalCols)
                    val row = ((e2.y / cellH) + 1).toInt().coerceIn(1, terminalRows)
                    val button = if (ticks > 0)
                        com.termux.terminal.TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    else
                        com.termux.terminal.TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                    repeat(Math.abs(ticks)) { termuxEmulator.sendMouseEvent(button, col, row, true) }
                }
                return true
            }
            // No remote mouse mode — scroll the local scrollback buffer.
            // distanceY > 0 when the finger moved UP (Android GestureDetector
            // convention: prevY - currY). Standard mobile convention matches
            // JuiceSSH/Termux: swipe UP → see older content (distanceY > 0
            // → scrollYf increases). reverseScrollDirection = true inverts this
            // to match the old TabSSH behaviour where swipe DOWN showed older content.
            val scrollDelta = if (reverseScrollDirection) -distanceY else distanceY
            scrollYf = (scrollYf + scrollDelta).coerceIn(0f, maxScrollYPx().toFloat())
            mouseScrollAccum = 0f
            // invalidate() requests the redraw immediately rather than waiting
            // for the next vsync post — this is what gives 1:1 finger tracking.
            // The framework still coalesces invalidations within the same frame
            // so we don't pay for multiple redraws if onScroll fires twice before
            // the next Choreographer beat.
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            // Issue #168 — edge swipes for tab switching. Detect BEFORE
            // the vertical-fling scrollback handler so a horizontal edge
            // swipe doesn't bounce the scrollback.
            val down = e1
            if (down != null && Math.abs(velocityX) > Math.abs(velocityY) * 1.5) {
                val edgePx = edgeSwipeDp * resources.displayMetrics.density
                val w = width
                val onLeftEdge = down.x < edgePx
                val onRightEdge = down.x > w - edgePx
                if (onLeftEdge && velocityX > 0) {
                    onEdgeSwipe?.invoke(-1)
                    return true
                }
                if (onRightEdge && velocityX < 0) {
                    onEdgeSwipe?.invoke(+1)
                    return true
                }
            }
            // When remote mouse tracking is active, fling has no meaning
            // (mouse protocol has no velocity) — consume and clear accumulator.
            val termuxEmulator = termuxBridge?.getEmulator()
            if (termuxEmulator != null && termuxEmulator.isMouseTrackingActive()) {
                mouseScrollAccum = 0f
                return true
            }
            // velocityY < 0 = finger was moving UP. Standard convention: swipe UP →
            // older content → scrollYf increases. So pass -velocityY (negate so an
            // upward fling produces positive scroll movement toward the past).
            // reverseScrollDirection: leave as +velocityY (old behaviour).
            val flingVelocity = if (reverseScrollDirection) velocityY.toInt() else -velocityY.toInt()
            scroller.fling(0, scrollYInt, 0, flingVelocity, 0, 0, 0, maxScrollYPx())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Single tap = toggle keyboard.
            toggleKeyboard()
            Logger.d("TerminalView", "Single tap — toggling keyboard")
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double tap = select word at position
            selectWordAtPosition(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Long press on a URL → URL open/copy dialog.
            // Long press on anything else → terminal action menu.
            // Copy/paste lives on the dedicated clipboard key in the keyboard
            // bar — text selection is NOT triggered from long press.
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val url = detectUrlAtPosition(e.x, e.y)
            if (url != null) {
                onUrlDetected?.invoke(url)
                Logger.d("TerminalView", "Long press on URL: $url")
            } else {
                onContextMenuRequested?.invoke(e.x, e.y)
                Logger.d("TerminalView", "Long press — showing terminal menu")
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

    // ─────────────────────────────────────────────────────────────────
    // Drag-to-select range copy — public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Convert a touch pixel position to a (col, row) cell, clamped to
     * the visible viewport. Used by both selection entry and drag.
     */
    private fun pixelToCell(x: Float, y: Float): Pair<Int, Int> {
        val col = ((x - paddingLeft) / cellWidth).toInt()
            .coerceIn(0, (terminalCols - 1).coerceAtLeast(0))
        val row = ((y - paddingTop) / cellHeight).toInt()
            .coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
        return col to row
    }

    /**
     * Enter selection mode anchored at the cell under the given pixel
     * coordinates. Initial focus = anchor (single-cell selection).
     * Triggers `onSelectionStarted` so the host activity can start its
     * floating ActionMode.
     */
    fun enterSelectionMode(x: Float, y: Float) {
        val (col, row) = pixelToCell(x, y)
        selectionAnchorCol = col
        selectionAnchorRow = row
        selectionFocusCol = col
        selectionFocusRow = row
        selectionActive = true
        selectionDragHandle = -1
        // Reclaim focus so IME input keeps coming to the terminal. The SEL
        // key button steals focus when tapped; without this the keyboard is
        // visible but keystrokes go nowhere.
        requestFocus()
        invalidate()
        onSelectionStarted?.invoke()
    }

    /**
     * Long-press entry point: select the word under the touch and pre-grab the
     * focus handle so the user can immediately drag to extend the selection
     * without a second touch. Called by the host activity from
     * `onContextMenuRequested` (fired by `onLongPress`).
     *
     * The long-press gesture leaves the touch stream active. Pre-setting
     * `selectionDragHandle = 1` means subsequent ACTION_MOVE events in
     * `handleSelectionTouch` are attributed to the focus handle, not
     * incorrectly forwarded to the GestureDetector where they would scroll.
     *
     * If no word is found at the position (whitespace/empty), falls back to a
     * single-cell anchor so the user can still drag to select.
     */
    fun beginWordSelectionAtTouch(x: Float, y: Float) {
        val result = getTextAtPosition(x, y)
        if (result != null) {
            val (row, text) = result
            val col = ((x - paddingLeft) / cellWidth).toInt()
                .coerceIn(0, (terminalCols - 1).coerceAtLeast(0))
            if (text.isNotEmpty() && col < text.length && !text[col].isWhitespace()) {
                var wordStart = col
                var wordEnd = col
                while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) wordStart--
                while (wordEnd < text.length - 1 && !text[wordEnd + 1].isWhitespace()) wordEnd++
                selectionAnchorRow = row
                selectionAnchorCol = wordStart
                selectionFocusRow = row
                selectionFocusCol = wordEnd
                selectionActive = true
                selectionDragHandle = 1
                requestFocus()
                invalidate()
                onSelectionStarted?.invoke()
                return
            }
        }
        val (col, row) = pixelToCell(x, y)
        selectionAnchorCol = col
        selectionAnchorRow = row
        selectionFocusCol = col
        selectionFocusRow = row
        selectionActive = true
        selectionDragHandle = 1
        requestFocus()
        invalidate()
        onSelectionStarted?.invoke()
    }

    /**
     * Clear the selection state and redraw. Called by the host activity
     * from `ActionMode.onDestroyActionMode`, and from
     * `onTouch` when the user taps outside the highlight.
     */
    fun exitSelectionMode() {
        if (!selectionActive) return
        selectionActive = false
        selectionDragHandle = -1
        // Restore focus so typing works immediately after dismissing the
        // selection (e.g. tap-outside-to-cancel, ActionMode dismissed).
        requestFocus()
        invalidate()
    }

    /**
     * Select the entire visible terminal screen. Sets anchor to (0,0) and
     * focus to the last cell on the last visible row, then fires
     * onSelectionStarted so the host activity raises the ActionMode bar.
     */
    fun selectAll() {
        selectionAnchorRow = 0
        selectionAnchorCol = 0
        selectionFocusRow = (terminalRows - 1).coerceAtLeast(0)
        selectionFocusCol = (terminalCols - 1).coerceAtLeast(0)
        selectionActive = true
        selectionDragHandle = -1
        requestFocus()
        invalidate()
    }

    /**
     * Read the currently-selected text from the Termux buffer. Returns
     * null if no buffer or selection is empty. The range is normalised
     * (anchor and focus may be in any order) and converted to the
     * line-by-line rectangle the user sees on screen.
     */
    fun getSelectedText(): String? {
        if (!selectionActive) return null
        val buffer = termuxBridge?.getScreen() ?: termuxBuffer ?: return null
        val (startRow, startCol, endRow, endCol) = normalisedSelection()
        return try {
            // Termux's getSelectedText takes (col1, row1, col2, row2) and
            // reads line-by-line through the rectangle. We pass through
            // the user-visible cells; +1 on the trailing column / row
            // would over-include, so use the same inclusive convention
            // the existing scrollback-extraction path uses.
            buffer.getSelectedText(startCol, startRow, endCol, endRow)
        } catch (e: Exception) {
            Logger.w("TerminalView", "getSelectedText failed: ${e.message}")
            null
        }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Normalise the (anchor, focus) pair into (startRow, startCol,
     * endRow, endCol) so that startRow ≤ endRow, and on a single-row
     * selection startCol ≤ endCol. Returns a 4-int destructurable
     * tuple — Kotlin's compiler-generated component1..N over a data
     * class is overkill here, so we use a private inline value class.
     */
    private fun normalisedSelection(): SelectionRange {
        val a = selectionAnchorRow
        val b = selectionFocusRow
        return if (a < b || (a == b && selectionAnchorCol <= selectionFocusCol)) {
            SelectionRange(a, selectionAnchorCol, b, selectionFocusCol)
        } else {
            SelectionRange(b, selectionFocusCol, a, selectionAnchorCol)
        }
    }

    private data class SelectionRange(
        val startRow: Int, val startCol: Int,
        val endRow: Int, val endCol: Int
    )

    /**
     * Hit-test the touch against the two selection handles. Returns
     * 0 for anchor, 1 for focus, -1 for neither.
     */
    private fun hitTestHandle(x: Float, y: Float): Int {
        if (!selectionActive) return -1
        val anchorPx = cellCenterPx(selectionAnchorCol, selectionAnchorRow + 1)
        val focusPx = cellCenterPx(selectionFocusCol, selectionFocusRow + 1)
        val dxA = x - anchorPx.first; val dyA = y - anchorPx.second
        val dxF = x - focusPx.first;  val dyF = y - focusPx.second
        val da = dxA * dxA + dyA * dyA
        val df = dxF * dxF + dyF * dyF
        val r2 = handleHitRadiusPx * handleHitRadiusPx
        return when {
            da <= r2 && da <= df -> 0
            df <= r2             -> 1
            else                 -> -1
        }
    }

    /** Pixel-center of the bottom edge of a given cell (where handles sit). */
    private fun cellCenterPx(col: Int, row: Int): Pair<Float, Float> {
        val px = paddingLeft + col * cellWidth + cellWidth / 2f
        val py = paddingTop + row * cellHeight
        return px to py
    }

    /**
     * Draw the highlight rectangles + handle bubbles for the active
     * selection. Called from `renderTermuxBuffer` after the row glyphs
     * are drawn so the highlight overlays — alpha is low enough that
     * text underneath remains readable.
     */
    private fun drawSelectionOverlay(canvas: Canvas) {
        if (!selectionActive || cellWidth <= 0f || cellHeight <= 0f) return
        val (startRow, startCol, endRow, endCol) = normalisedSelection()
        val startX = paddingLeft.toFloat()
        val startY = paddingTop.toFloat()

        for (row in startRow..endRow) {
            val colA = if (row == startRow) startCol else 0
            val colB = if (row == endRow) endCol else (terminalCols - 1)
            val left = startX + colA * cellWidth
            val right = startX + (colB + 1) * cellWidth
            val top = startY + row * cellHeight
            val bottom = top + cellHeight
            canvas.drawRect(left, top, right, bottom, selectionPaint)
        }

        val (ax, ay) = cellCenterPx(selectionAnchorCol, selectionAnchorRow + 1)
        val (fx, fy) = cellCenterPx(selectionFocusCol, selectionFocusRow + 1)
        canvas.drawCircle(ax, ay, handleDrawRadiusPx, selectionHandlePaint)
        canvas.drawCircle(fx, fy, handleDrawRadiusPx, selectionHandlePaint)
    }

    /**
     * Draw amber highlights over every visible search match.
     *
     * [scrollRows]: how many rows above the live screen are currently scrolled
     * into view (same value used by [renderTermuxBuffer]). A match at external
     * row `extRow` occupies visual row `extRow + scrollRows`; only rows in
     * `[0, terminalRows)` are visible.
     */
    private fun drawSearchHighlights(canvas: Canvas, scrollRows: Int) {
        if (searchMatches.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val startX = paddingLeft.toFloat()
        val startY = paddingTop.toFloat()
        for ((idx, match) in searchMatches.withIndex()) {
            val visualRow = match.externalRow + scrollRows
            if (visualRow < 0 || visualRow >= terminalRows) continue
            val left  = startX + match.colStart * cellWidth
            val right = startX + match.colEnd.coerceAtMost(terminalCols) * cellWidth
            val top   = startY + visualRow * cellHeight
            val bottom = top + cellHeight
            canvas.drawRect(left, top, right, bottom,
                if (idx == searchCurrentMatchIndex) searchCurrentMatchPaint else searchHighlightPaint)
        }
    }

    /**
     * Selection-aware portion of touch dispatch. Called from `onTouch`
     * BEFORE the gestureDetector / scaleGestureDetector paths so we
     * can swallow taps and drags while the user is shaping a
     * selection. Returns true if the event was handled here and
     * should not be forwarded to the gesture detectors.
     */
    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        if (!selectionActive) return false
        // Only react to single-finger touches; two-finger gestures
        // (pinch/zoom) handled upstream.
        if (event.pointerCount > 1) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val handle = hitTestHandle(event.x, event.y)
                if (handle >= 0) {
                    selectionDragHandle = handle
                    return true
                }

                // Handle hit-test missed, but the user may be tapping near a
                // handle. The handles are drawn below the selection highlight
                // (at the bottom of the last selected row), so pixelToCell
                // maps that region to a row OUTSIDE sr..er. Without this
                // guard, any tap on a visible handle circle immediately calls
                // exitSelectionMode() — the selection vanishes and the user
                // must long-press again. Chrome-style: only exit on a tap
                // that is clearly away from both handles.
                val anchorPx = cellCenterPx(selectionAnchorCol, selectionAnchorRow + 1)
                val focusPx  = cellCenterPx(selectionFocusCol,  selectionFocusRow  + 1)
                // Generous proximity threshold: 2× the visual radius so the
                // whole handle area is forgiving.
                val snapRadius2 = (handleHitRadiusPx * 2f).let { it * it }
                val dxA = event.x - anchorPx.first;  val dyA = event.y - anchorPx.second
                val dxF = event.x - focusPx.first;   val dyF = event.y - focusPx.second
                val nearAnchor = dxA * dxA + dyA * dyA <= snapRadius2
                val nearFocus  = dxF * dxF + dyF * dyF <= snapRadius2
                if (nearFocus || nearAnchor) {
                    // Snap to the closest handle so the subsequent drag
                    // works even though the precise hit-test missed.
                    val da = dxA * dxA + dyA * dyA
                    val df = dxF * dxF + dyF * dyF
                    selectionDragHandle = if (nearAnchor && da <= df) 0 else 1
                    return true
                }

                // Touch on a non-handle cell: did the user tap inside
                // the existing highlight (do nothing — let the action
                // mode stay) or outside (exit selection mode)?
                val (col, row) = pixelToCell(event.x, event.y)
                val (sr, sc, er, ec) = normalisedSelection()
                val insideRow = row in sr..er
                val insideCol = when {
                    sr == er         -> col in sc..ec
                    row == sr        -> col >= sc
                    row == er        -> col <= ec
                    else             -> insideRow
                }
                if (!(insideRow && insideCol)) {
                    exitSelectionMode()
                    return true
                }
                // Tap inside selection — claim the focus handle so a
                // drag from inside expands the selection naturally.
                selectionDragHandle = 1
                selectionFocusCol = col
                selectionFocusRow = row
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectionDragHandle < 0) return false
                val (col, row) = pixelToCell(event.x, event.y)
                if (selectionDragHandle == 0) {
                    selectionAnchorCol = col
                    selectionAnchorRow = row
                } else {
                    selectionFocusCol = col
                    selectionFocusRow = row
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectionDragHandle = -1
                return true
            }
        }
        return false
    }

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
            // Visually highlight the word so the user sees handles and the
            // ActionMode bar. The host activity's onSelectionStarted callback
            // raises the floating Copy/Select All/Paste bar.
            selectionAnchorRow = result.first
            selectionAnchorCol = wordStart
            selectionFocusRow = result.first
            selectionFocusCol = wordEnd
            selectionActive = true
            selectionDragHandle = -1
            requestFocus()
            invalidate()
            onSelectionStarted?.invoke()

            // `terminal_copy_on_select` (default true) auto-copies on double-tap.
            // Off → visual selection only; user must tap Copy in the ActionMode bar.
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context)
            val copyOnSelect = prefs.getBoolean("terminal_copy_on_select", true)
            if (copyOnSelect) {
                io.github.tabssh.utils.ClipboardHelper.copy(
                    context, label = "Selected Text", text = word, sensitive = true
                )
            }
            Logger.d("TerminalView", "Double-tap selected word: $word (copied=$copyOnSelect)")
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            // Clamp to current maxScrollYPx() — the scrollback buffer can shrink
            // mid-fling (e.g. the user types `clear`) leaving scroller.currY above
            // the new maximum, which produces a blank strip at the bottom of the view.
            scrollYf = scroller.currY.toFloat().coerceIn(0f, maxScrollYPx().toFloat())
            postInvalidateOnAnimation()
        }
    }

    /**
     * Maximum scrollY in pixels — backed by the Termux transcript (SSH path)
     * or the custom TerminalBuffer scrollback (standalone path).
     */
    private fun maxScrollYPx(): Int {
        if (cellHeight <= 0f) return 0
        val rows = termuxBridge?.getScreen()?.activeTranscriptRows
            ?: terminalBuffer?.getScrollbackSize()
            ?: 0
        return (rows * cellHeight).toInt()
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
        stopCursorBlink()
        pendingResize?.let { resizeHandler.removeCallbacks(it) }
        pendingResize = null
    }

    // ── Cursor blink helpers ─────────────────────────────────────────────────

    private fun startCursorBlink() {
        cursorBlinkHandler.removeCallbacks(cursorBlinkRunnable)
        cursorBlinkPhase = true
        cursorBlinkHandler.postDelayed(cursorBlinkRunnable, CURSOR_BLINK_INTERVAL_MS)
    }

    private fun stopCursorBlink() {
        cursorBlinkHandler.removeCallbacks(cursorBlinkRunnable)
        cursorBlinkPhase = true   // leave cursor visible when blink stops
    }

    companion object {
        private const val CURSOR_BLINK_INTERVAL_MS = 500L
        // Keyboard open/close animation fires two onSizeChanged events ~30 ms
        // apart. Debounce longer than the animation gap so only the settled
        // final size is forwarded to the SSH server via SIGWINCH.
        private const val RESIZE_DEBOUNCE_MS = 80L
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
