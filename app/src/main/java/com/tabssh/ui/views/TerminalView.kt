package com.tabssh.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.tabssh.R
import com.tabssh.terminal.emulator.TerminalEmulator
import com.tabssh.terminal.emulator.TerminalListener
import com.tabssh.terminal.input.KeyboardHandler
import com.tabssh.terminal.renderer.TerminalRenderer
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.*

/**
 * Custom view that displays and interacts with the terminal emulator
 * Handles rendering, input, touch, and accessibility
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Core components
    private var terminal: TerminalEmulator? = null
    private var keyboardHandler: KeyboardHandler? = null
    private val renderer = TerminalRenderer()
    
    // Input management
    private val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var inputConnection: TerminalInputConnection? = null
    
    // View state
    private var isInitialized = false
    private var terminalWidth = 0
    private var terminalHeight = 0
    private var scrollY = 0f
    private var maxScrollY = 0f
    
    // Touch handling
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // Selection
    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    private var isSelecting = false
    
    // Rendering optimization
    private val renderScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastRenderTime = 0L
    private val minRenderInterval = 16L // ~60fps
    
    // Accessibility
    private var lastAnnouncementTime = 0L
    private val announcementCooldown = 1000L // 1 second
    
    init {
        setupView()
        loadThemeAttributes(attrs)
    }
    
    private fun setupView() {
        // Make view focusable for keyboard input
        isFocusable = true
        isFocusableInTouchMode = true
        
        // Enable drawing
        setWillNotDraw(false)
        
        // Set up input connection
        inputConnection = TerminalInputConnection(this)
        
        Logger.d("TerminalView", "Terminal view initialized")
    }
    
    private fun loadThemeAttributes(attrs: AttributeSet?) {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.TerminalView)
            try {
                // Load terminal-specific attributes
                val fontSize = typedArray.getDimension(R.styleable.TerminalView_fontSize, 14f)
                val fontFamily = typedArray.getString(R.styleable.TerminalView_fontFamily) ?: "monospace"
                val showScrollbar = typedArray.getBoolean(R.styleable.TerminalView_showScrollbar, true)
                
                // Apply to renderer
                renderer.setFontSize(fontSize)
                setupFont(fontFamily)
                
                Logger.d("TerminalView", "Theme attributes loaded: fontSize=$fontSize, font=$fontFamily")
                
            } finally {
                typedArray.recycle()
            }
        }
    }
    
    private fun setupFont(fontFamily: String) {
        val typeface = when (fontFamily.lowercase()) {
            "roboto mono", "roboto-mono" -> Typeface.create("monospace", Typeface.NORMAL)
            "source code pro" -> Typeface.create("monospace", Typeface.NORMAL)
            else -> Typeface.MONOSPACE
        }
        renderer.setTypeface(typeface)
    }
    
    /**
     * Connect terminal emulator to this view
     */
    fun setTerminal(terminalEmulator: TerminalEmulator) {
        // Disconnect old terminal
        terminal?.removeListener(terminalListener)
        
        // Connect new terminal
        terminal = terminalEmulator
        keyboardHandler = KeyboardHandler(terminalEmulator)
        
        // Set up terminal listener for rendering updates
        terminalEmulator.addListener(terminalListener)
        
        // Calculate terminal size based on current view size
        if (width > 0 && height > 0) {
            updateTerminalSize()
        }
        
        isInitialized = true
        invalidate() // Trigger redraw
        
        Logger.i("TerminalView", "Terminal connected to view")
    }
    
    /**
     * Terminal event listener for rendering updates
     */
    private val terminalListener = object : TerminalListener {
        override fun onDataReceived(data: ByteArray) {
            // Schedule redraw on main thread
            post { 
                invalidateWithThrottle()
                announceNewContent()
            }
        }
        
        override fun onTerminalResized(rows: Int, cols: Int) {
            post {
                Logger.d("TerminalView", "Terminal resized to ${cols}x${rows}")
                requestLayout()
                invalidate()
            }
        }
        
        override fun onTitleChanged(title: String) {
            // Announce title changes for accessibility
            post {
                announceForAccessibility("Terminal title changed to $title")
            }
        }
        
        override fun onBellTriggered() {
            post {
                // Handle terminal bell - could vibrate or flash screen
                performHapticFeedback(HAPTIC_FEEDBACK_LONG_PRESS)
            }
        }
        
        override fun onTerminalError(error: Exception) {
            post {
                Logger.e("TerminalView", "Terminal error", error)
                announceForAccessibility("Terminal error: ${error.message}")
            }
        }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        if (width != terminalWidth || height != terminalHeight) {
            terminalWidth = width
            terminalHeight = height
            updateTerminalSize()
        }
    }
    
    private fun updateTerminalSize() {
        if (terminalWidth <= 0 || terminalHeight <= 0) return
        
        val (rows, cols) = renderer.calculateTerminalSize(terminalWidth, terminalHeight)
        terminal?.resize(rows, cols)
        
        // Update scroll limits
        val buffer = terminal?.getBuffer()
        if (buffer != null) {
            val contentHeight = buffer.getRows() * renderer.getCharHeight()
            maxScrollY = maxOf(0f, contentHeight - terminalHeight)
        }
        
        Logger.d("TerminalView", "Updated terminal size to ${cols}x${rows}")
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isInitialized) {
            drawPlaceholder(canvas)
            return
        }
        
        val currentTerminal = terminal
        val buffer = currentTerminal?.getBuffer()
        
        if (buffer != null) {
            try {
                // Render terminal content
                renderer.render(canvas, buffer, width, height, scrollY.toInt())
                
                // Update render time for performance monitoring
                lastRenderTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
                Logger.e("TerminalView", "Error rendering terminal", e)
                drawErrorState(canvas)
            }
        } else {
            drawDisconnectedState(canvas)
        }
    }
    
    private fun drawPlaceholder(canvas: Canvas) {
        val paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.terminal_foreground)
            textSize = 48f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        canvas.drawColor(ContextCompat.getColor(context, R.color.terminal_background))
        canvas.drawText(
            "Terminal Initializing...",
            width / 2f,
            height / 2f,
            paint
        )
    }
    
    private fun drawDisconnectedState(canvas: Canvas) {
        val paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.gray_500)
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        canvas.drawColor(ContextCompat.getColor(context, R.color.terminal_background))
        canvas.drawText(
            "Not Connected",
            width / 2f,
            height / 2f - 50f,
            paint
        )
        
        paint.textSize = 24f
        canvas.drawText(
            "Tap to connect or create a new connection",
            width / 2f,
            height / 2f + 20f,
            paint
        )
    }
    
    private fun drawErrorState(canvas: Canvas) {
        val paint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.error)
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        canvas.drawColor(ContextCompat.getColor(context, R.color.terminal_background))
        canvas.drawText(
            "Terminal Error",
            width / 2f,
            height / 2f,
            paint
        )
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                
                // Request focus for keyboard input
                if (!hasFocus()) {
                    requestFocus()
                }
                
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastTouchX
                val deltaY = event.y - lastTouchY
                
                // Handle scrolling
                if (!isDragging && (kotlin.math.abs(deltaY) > 20)) {
                    isDragging = true
                }
                
                if (isDragging) {
                    scrollY = (scrollY - deltaY).coerceIn(0f, maxScrollY)
                    invalidate()
                }
                
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Single tap - show/hide keyboard
                    toggleKeyboard()
                }
                isDragging = false
                return true
            }
        }
        
        return super.onTouchEvent(event)
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = keyboardHandler
        if (handler != null && terminal?.isActive?.value == true) {
            return handler.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handler = keyboardHandler
        if (handler != null && terminal?.isActive?.value == true) {
            return handler.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }
    
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.actionLabel = null
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        
        return inputConnection
    }
    
    override fun onCheckIsTextEditor(): Boolean = true
    
    private fun toggleKeyboard() {
        if (inputMethodManager.isActive(this)) {
            hideKeyboard()
        } else {
            showKeyboard()
        }
    }
    
    fun showKeyboard() {
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        Logger.d("TerminalView", "Showing keyboard")
    }
    
    fun hideKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
        Logger.d("TerminalView", "Hiding keyboard")
    }
    
    fun scrollUp() {
        scrollY = (scrollY - renderer.getCharHeight() * 3).coerceAtLeast(0f)
        invalidate()
    }
    
    fun scrollDown() {
        scrollY = (scrollY + renderer.getCharHeight() * 3).coerceAtMost(maxScrollY)
        invalidate()
    }
    
    fun copySelectedText(): String? {
        val start = selectionStart
        val end = selectionEnd
        val buffer = terminal?.getBuffer()
        
        if (start != null && end != null && buffer != null) {
            // Extract selected text
            val selectedText = extractTextInRange(buffer, start, end)
            
            // Copy to clipboard
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Terminal", selectedText)
            clipboardManager.setPrimaryClip(clip)
            
            // Clear selection
            clearSelection()
            
            Logger.d("TerminalView", "Copied ${selectedText.length} characters to clipboard")
            return selectedText
        }
        
        return null
    }
    
    fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        renderer.clearSelection()
        invalidate()
    }
    
    private fun extractTextInRange(
        buffer: com.tabssh.terminal.emulator.TerminalBuffer,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>
    ): String {
        val (startRow, startCol) = start
        val (endRow, endCol) = end
        
        val text = StringBuilder()
        
        for (row in startRow..endRow) {
            val line = buffer.getLine(row) ?: continue
            val lineStart = if (row == startRow) startCol else 0
            val lineEnd = if (row == endRow) endCol else line.size - 1
            
            for (col in lineStart..lineEnd) {
                if (col < line.size) {
                    text.append(line[col].char)
                }
            }
            
            if (row < endRow) {
                text.append('\n')
            }
        }
        
        return text.toString()
    }
    
    private fun invalidateWithThrottle() {
        val now = System.currentTimeMillis()
        if (now - lastRenderTime >= minRenderInterval) {
            invalidate()
        } else {
            // Schedule delayed invalidation
            renderScope.launch {
                delay(minRenderInterval)
                if (isAttachedToWindow) {
                    post { invalidate() }
                }
            }
        }
    }
    
    private fun announceNewContent() {
        // Throttle announcements for accessibility
        val now = System.currentTimeMillis()
        if (now - lastAnnouncementTime > announcementCooldown) {
            // Only announce if significant new content
            announceForAccessibility("Terminal updated")
            lastAnnouncementTime = now
        }
    }
    
    // Public API
    
    fun getTerminal(): TerminalEmulator? = terminal
    
    fun sendText(text: String) {
        keyboardHandler?.onTextInput(text)
    }
    
    fun paste() {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboardManager.primaryClip
        
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                sendText(text)
                Logger.d("TerminalView", "Pasted ${text.length} characters")
            }
        }
    }
    
    fun selectAll() {
        val buffer = terminal?.getBuffer()
        if (buffer != null) {
            selectionStart = Pair(0, 0)
            selectionEnd = Pair(buffer.getRows() - 1, buffer.getCols() - 1)
            renderer.setSelection(selectionStart, selectionEnd)
            invalidate()
            announceForAccessibility("All text selected")
        }
    }
    
    fun increaseFontSize() {
        val currentSize = renderer.getFontSize()
        val newSize = (currentSize * 1.2f).coerceAtMost(32f)
        renderer.setFontSize(newSize)
        updateTerminalSize()
        invalidate()
        announceForAccessibility("Font size increased")
    }
    
    fun decreaseFontSize() {
        val currentSize = renderer.getFontSize()
        val newSize = (currentSize / 1.2f).coerceAtLeast(8f)
        renderer.setFontSize(newSize)
        updateTerminalSize()
        invalidate()
        announceForAccessibility("Font size decreased")
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderScope.cancel()
        terminal?.removeListener(terminalListener)
    }
}

/**
 * Custom input connection for terminal input
 */
private class TerminalInputConnection(private val terminalView: TerminalView) : BaseInputConnection(terminalView, false) {
    
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (!text.isNullOrEmpty()) {
            terminalView.sendText(text.toString())
            return true
        }
        return super.commitText(text, newCursorPosition)
    }
    
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // Send backspace for delete operations
        if (beforeLength > 0) {
            repeat(beforeLength) {
                terminalView.sendText("\u007F") // Backspace
            }
            return true
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }
    
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        terminalView.onKeyDown(event.keyCode, event)
        return true
    }
}