package io.github.tabssh.terminal.renderer

import android.graphics.*
import android.text.TextPaint
import io.github.tabssh.terminal.emulator.TerminalBuffer
import io.github.tabssh.terminal.emulator.TerminalChar
import io.github.tabssh.utils.logging.Logger
import kotlin.math.ceil
import kotlin.math.max

/**
 * Efficient terminal text renderer using Canvas
 * Handles text rendering with colors, attributes, and cursor
 */
class TerminalRenderer(
    private var terminalColors: IntArray = getDefaultColors()
) {
    // Paint objects for rendering
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = 42f // Will be updated
    }
    
    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val cursorPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val selectionPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    // Text metrics
    private var charWidth = 0f
    private var charHeight = 0f
    private var charAscent = 0f
    private var charDescent = 0f
    
    // Rendering settings
    private var fontSize = 14f
    private var lineSpacing = 1.2f
    private var showCursor = true
    private var cursorBlink = true
    private var cursorBlinkState = true
    
    // Selection
    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    
    // Cache for better performance
    private val charCache = mutableMapOf<Int, Bitmap>()
    private var lastCacheCleanup = System.currentTimeMillis()
    private val cacheCleanupInterval = 30000 // 30 seconds
    
    // Cursor blinking
    private var lastCursorBlink = System.currentTimeMillis()
    private val cursorBlinkInterval = 500L // 500ms
    
    companion object {
        /**
         * Default terminal colors (16-color ANSI palette)
         */
        fun getDefaultColors(): IntArray {
            return intArrayOf(
                // Normal colors (0-7)
                0xFF000000.toInt(), // Black
                0xFFCD0000.toInt(), // Red
                0xFF00CD00.toInt(), // Green
                0xFFCDCD00.toInt(), // Yellow
                0xFF0000EE.toInt(), // Blue
                0xFFCD00CD.toInt(), // Magenta
                0xFF00CDCD.toInt(), // Cyan
                0xFFE5E5E5.toInt(), // White
                
                // Bright colors (8-15)
                0xFF7F7F7F.toInt(), // Bright Black (Gray)
                0xFFFF0000.toInt(), // Bright Red
                0xFF00FF00.toInt(), // Bright Green
                0xFFFFFF00.toInt(), // Bright Yellow
                0xFF5C5CFF.toInt(), // Bright Blue
                0xFFFF00FF.toInt(), // Bright Magenta
                0xFF00FFFF.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        }
    }
    
    init {
        updateTextMetrics()
        setupPaints()
        Logger.d("TerminalRenderer", "Terminal renderer initialized")
    }
    
    private fun updateTextMetrics() {
        val fontMetrics = textPaint.fontMetrics
        charHeight = (fontMetrics.bottom - fontMetrics.top) * lineSpacing
        charAscent = -fontMetrics.top
        charDescent = fontMetrics.bottom
        charWidth = textPaint.measureText("M") // Use 'M' as typical monospace width
        
        Logger.d("TerminalRenderer", "Text metrics: char=${charWidth}x${charHeight}, fontSize=$fontSize")
    }
    
    private fun setupPaints() {
        cursorPaint.color = terminalColors[7] // White cursor by default
        selectionPaint.color = 0x660066FF // Semi-transparent blue
    }
    
    /**
     * Render the terminal buffer to a canvas
     */
    fun render(
        canvas: Canvas,
        buffer: TerminalBuffer,
        canvasWidth: Int,
        canvasHeight: Int,
        scrollY: Int = 0
    ) {
        val startTime = System.currentTimeMillis()
        
        // Clear canvas with background color
        canvas.drawColor(terminalColors[0]) // Black background
        
        // Calculate visible area
        val visibleRows = (canvasHeight / charHeight).toInt() + 2
        val visibleCols = (canvasWidth / charWidth).toInt() + 2
        val startRow = (scrollY / charHeight).toInt()
        
        // Update cursor blink state
        updateCursorBlink()
        
        // Render visible text
        renderVisibleText(canvas, buffer, startRow, visibleRows, visibleCols, scrollY)
        
        // Render selection if active
        renderSelection(canvas, buffer, scrollY)
        
        // Render cursor
        if (showCursor) {
            renderCursor(canvas, buffer, scrollY)
        }
        
        // Clean cache periodically
        cleanupCacheIfNeeded()
        
        val renderTime = System.currentTimeMillis() - startTime
        if (renderTime > 16) { // More than 1 frame at 60fps
            Logger.d("TerminalRenderer", "Slow render: ${renderTime}ms")
        }
    }
    
    private fun renderVisibleText(
        canvas: Canvas,
        buffer: TerminalBuffer,
        startRow: Int,
        visibleRows: Int,
        visibleCols: Int,
        scrollY: Int
    ) {
        val endRow = (startRow + visibleRows).coerceAtMost(buffer.getRows())
        
        for (row in startRow until endRow) {
            if (buffer.isLineDirty(row) || !isUsingCache()) {
                renderLine(canvas, buffer, row, visibleCols, scrollY)
            }
        }
        
        // Clear dirty flags after rendering
        buffer.clearDirtyFlags()
    }
    
    private fun renderLine(
        canvas: Canvas,
        buffer: TerminalBuffer,
        row: Int,
        maxCols: Int,
        scrollY: Int
    ) {
        val line = buffer.getLine(row) ?: return
        val y = (row * charHeight - scrollY).toFloat()
        
        // Skip rendering if line is not visible
        if (y + charHeight < 0 || y > canvas.height) return
        
        val cols = minOf(line.size, maxCols)
        var currentBgColor = -1
        var bgStartX = 0f
        
        for (col in 0 until cols) {
            val char = line[col]
            val x = col * charWidth
            
            // Render background if color changed or end of line
            val bgColor = char.getEffectiveBgColor()
            if (bgColor != currentBgColor) {
                if (currentBgColor >= 0 && currentBgColor != 0) { // Don't render default black background
                    renderBackground(canvas, bgStartX, y, x - bgStartX, charHeight, currentBgColor)
                }
                currentBgColor = bgColor
                bgStartX = x
            }
            
            // Render character
            renderChar(canvas, char, x, y + charAscent)
        }
        
        // Render final background segment
        if (currentBgColor >= 0 && currentBgColor != 0) {
            renderBackground(canvas, bgStartX, y, cols * charWidth - bgStartX, charHeight, currentBgColor)
        }
    }
    
    private fun renderBackground(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        colorIndex: Int
    ) {
        backgroundPaint.color = getColor(colorIndex)
        canvas.drawRect(x, y, x + width, y + height, backgroundPaint)
    }
    
    private fun renderChar(canvas: Canvas, char: TerminalChar, x: Float, y: Float) {
        // Set text color and style
        val fgColor = char.getEffectiveFgColor()
        textPaint.color = getColor(fgColor)
        textPaint.isFakeBoldText = char.isBold
        textPaint.isUnderlineText = char.isUnderline
        textPaint.isStrikeThruText = char.isStrikethrough
        
        // Draw the character
        if (char.isPrintable()) {
            canvas.drawText(char.char.toString(), x, y, textPaint)
            
            // Additional rendering for special attributes
            if (char.isBlink && cursorBlinkState) {
                // Render blinking characters only when blink state is on
                return
            }
            
            if (char.isReverse) {
                // Reverse video is handled by swapping colors in getEffectiveFgColor/BgColor
                // Additional reverse rendering could go here
            }
        }
    }
    
    private fun renderSelection(canvas: Canvas, buffer: TerminalBuffer, scrollY: Int) {
        val start = selectionStart
        val end = selectionEnd
        if (start == null || end == null) return
        
        val (startRow, startCol) = start
        val (endRow, endCol) = end
        
        // Ensure start is before end
        val (firstRow, firstCol, lastRow, lastCol) = if (startRow < endRow || (startRow == endRow && startCol <= endCol)) {
            arrayOf(startRow, startCol, endRow, endCol)
        } else {
            arrayOf(endRow, endCol, startRow, startCol)
        }
        
        for (row in firstRow..lastRow) {
            val y = (row * charHeight - scrollY).toFloat()
            
            val lineStartCol = if (row == firstRow) firstCol else 0
            val lineEndCol = if (row == lastRow) lastCol else buffer.getCols() - 1
            
            val x1 = lineStartCol * charWidth
            val x2 = (lineEndCol + 1) * charWidth
            
            canvas.drawRect(x1, y, x2, y + charHeight, selectionPaint)
        }
    }
    
    private fun renderCursor(canvas: Canvas, buffer: TerminalBuffer, scrollY: Int) {
        if (!cursorBlink || cursorBlinkState) {
            val cursorRow = buffer.getCursorRow()
            val cursorCol = buffer.getCursorCol()
            
            val x = cursorCol * charWidth
            val y = (cursorRow * charHeight - scrollY).toFloat()
            
            // Different cursor styles could be implemented here
            when (getCursorStyle()) {
                CursorStyle.BLOCK -> {
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, cursorPaint)
                }
                CursorStyle.UNDERLINE -> {
                    canvas.drawRect(x, y + charHeight - 4, x + charWidth, y + charHeight, cursorPaint)
                }
                CursorStyle.VERTICAL_BAR -> {
                    canvas.drawRect(x, y, x + 3, y + charHeight, cursorPaint)
                }
            }
        }
    }
    
    private fun updateCursorBlink() {
        if (cursorBlink) {
            val now = System.currentTimeMillis()
            if (now - lastCursorBlink > cursorBlinkInterval) {
                cursorBlinkState = !cursorBlinkState
                lastCursorBlink = now
            }
        } else {
            cursorBlinkState = true
        }
    }
    
    private fun getColor(colorIndex: Int): Int {
        return if (colorIndex in terminalColors.indices) {
            terminalColors[colorIndex]
        } else {
            terminalColors[7] // Default to white
        }
    }
    
    private fun isUsingCache(): Boolean {
        // Disable caching for now to ensure correctness
        // Could be enabled later for performance optimization
        return false
    }
    
    private fun cleanupCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCacheCleanup > cacheCleanupInterval) {
            charCache.clear()
            lastCacheCleanup = now
        }
    }
    
    // Public methods for configuration
    
    fun setFontSize(size: Float) {
        fontSize = size
        textPaint.textSize = size
        updateTextMetrics()
        charCache.clear() // Clear cache as font changed
    }
    
    fun getFontSize(): Float = fontSize
    
    fun setLineSpacing(spacing: Float) {
        lineSpacing = spacing
        updateTextMetrics()
    }
    
    fun getLineSpacing(): Float = lineSpacing
    
    fun setTypeface(typeface: Typeface?) {
        textPaint.typeface = typeface ?: Typeface.MONOSPACE
        updateTextMetrics()
        charCache.clear()
    }
    
    fun getTypeface(): Typeface = textPaint.typeface
    
    fun setTerminalColors(colors: IntArray) {
        if (colors.size >= 16) {
            terminalColors = colors
            setupPaints()
        }
    }
    
    fun getTerminalColors(): IntArray = terminalColors
    
    fun setCursorVisible(visible: Boolean) {
        showCursor = visible
    }
    
    fun isCursorVisible(): Boolean = showCursor
    
    fun setCursorBlink(blink: Boolean) {
        cursorBlink = blink
        if (!blink) {
            cursorBlinkState = true
        }
    }
    
    fun isCursorBlink(): Boolean = cursorBlink
    
    fun setCursorColor(color: Int) {
        cursorPaint.color = color
    }
    
    fun getCursorColor(): Int = cursorPaint.color
    
    fun setSelection(start: Pair<Int, Int>?, end: Pair<Int, Int>?) {
        selectionStart = start
        selectionEnd = end
    }
    
    fun clearSelection() {
        selectionStart = null
        selectionEnd = null
    }
    
    fun hasSelection(): Boolean = selectionStart != null && selectionEnd != null
    
    // Calculate dimensions
    fun getCharWidth(): Float = charWidth
    fun getCharHeight(): Float = charHeight
    
    fun calculateTerminalSize(canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> {
        val cols = max(1, (canvasWidth / charWidth).toInt())
        val rows = max(1, (canvasHeight / charHeight).toInt())
        return Pair(rows, cols)
    }
    
    fun calculateCanvasSize(rows: Int, cols: Int): Pair<Int, Int> {
        val width = ceil(cols * charWidth).toInt()
        val height = ceil(rows * charHeight).toInt()
        return Pair(width, height)
    }
    
    // Cursor styles
    enum class CursorStyle {
        BLOCK, UNDERLINE, VERTICAL_BAR
    }
    
    private var cursorStyle = CursorStyle.BLOCK
    
    fun setCursorStyle(style: CursorStyle) {
        cursorStyle = style
    }
    
    fun getCursorStyle(): CursorStyle = cursorStyle
}