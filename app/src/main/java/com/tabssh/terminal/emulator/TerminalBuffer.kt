package io.github.tabssh.terminal.emulator

import io.github.tabssh.utils.logging.Logger
import java.util.*

/**
 * Terminal buffer that stores character data and attributes
 * Implements VT100/ANSI terminal screen buffer with scrollback
 */
class TerminalBuffer(
    private var rows: Int,
    private var cols: Int,
    private val maxScrollbackLines: Int = 10000
) {
    // Screen buffer (visible area)
    private var screenBuffer: Array<Array<TerminalChar>>
    
    // Scrollback buffer (history)
    private val scrollbackBuffer = LinkedList<Array<TerminalChar>>()
    
    // Cursor position
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    
    // Terminal state
    private var insertMode = false
    private var originMode = false
    private var wrapMode = true
    private var reverseVideo = false
    
    // Scroll region
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    
    // Current character attributes
    private var currentFgColor = 7 // White
    private var currentBgColor = 0 // Black
    private var currentBold = false
    private var currentUnderline = false
    private var currentReverse = false
    private var currentBlink = false
    
    // Alternate screen buffer
    private var alternateScreenBuffer: Array<Array<TerminalChar>>? = null
    private var alternateScrollback: LinkedList<Array<TerminalChar>>? = null
    private var isAlternateScreen = false
    
    // Dirty tracking for efficient rendering
    private val dirtyLines = BooleanArray(rows)
    private var entireScreenDirty = true
    
    init {
        screenBuffer = createEmptyBuffer(rows, cols)
        Logger.d("TerminalBuffer", "Created terminal buffer ${cols}x${rows} with scrollback $maxScrollbackLines")
    }
    
    private fun createEmptyBuffer(rows: Int, cols: Int): Array<Array<TerminalChar>> {
        return Array(rows) { 
            Array(cols) { 
                TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
        }
    }
    
    /**
     * Resize the terminal buffer
     */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        
        Logger.d("TerminalBuffer", "Resizing terminal from ${cols}x${rows} to ${newCols}x${newRows}")
        
        val oldBuffer = screenBuffer
        val oldRows = rows
        val oldCols = cols
        
        rows = newRows
        cols = newCols
        scrollBottom = rows - 1
        
        // Create new buffer
        screenBuffer = createEmptyBuffer(rows, cols)
        
        // Copy old content to new buffer
        val copyRows = minOf(oldRows, newRows)
        val copyCols = minOf(oldCols, newCols)
        
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                screenBuffer[r][c] = oldBuffer[r][c]
            }
        }
        
        // Adjust cursor position
        cursorRow = minOf(cursorRow, rows - 1)
        cursorCol = minOf(cursorCol, cols - 1)
        
        markAllDirty()
    }
    
    /**
     * Write a character at the current cursor position
     */
    fun writeChar(ch: Char) {
        // Handle special characters
        when (ch) {
            '\r' -> {
                cursorCol = 0
                return
            }
            '\n' -> {
                newLine()
                return
            }
            '\t' -> {
                tab()
                return
            }
            '\b' -> {
                backspace()
                return
            }
            '\u0007' -> { // Bell
                // Bell handling would trigger notification/vibration
                return
            }
        }
        
        // Handle printable characters
        if (ch >= ' ' || ch.code >= 160) { // Printable ASCII and extended
            if (cursorCol >= cols) {
                if (wrapMode) {
                    newLine()
                } else {
                    cursorCol = cols - 1
                }
            }
            
            if (insertMode && cursorCol < cols - 1) {
                // Shift characters to the right
                for (i in cols - 1 downTo cursorCol + 1) {
                    if (i - 1 >= 0) {
                        screenBuffer[cursorRow][i] = screenBuffer[cursorRow][i - 1]
                    }
                }
            }
            
            screenBuffer[cursorRow][cursorCol] = TerminalChar(
                ch, currentFgColor, currentBgColor, currentBold, 
                currentUnderline, currentReverse, currentBlink
            )
            
            markLineDirty(cursorRow)
            cursorCol++
        }
    }
    
    /**
     * Write a string to the terminal
     */
    fun writeString(str: String) {
        str.forEach { ch ->
            writeChar(ch)
        }
    }
    
    private fun newLine() {
        cursorCol = 0
        if (cursorRow >= scrollBottom) {
            scrollUp()
        } else {
            cursorRow++
        }
    }
    
    private fun tab() {
        cursorCol = ((cursorCol / 8) + 1) * 8
        if (cursorCol >= cols) {
            cursorCol = cols - 1
        }
    }
    
    private fun backspace() {
        if (cursorCol > 0) {
            cursorCol--
        }
    }
    
    /**
     * Scroll the screen up by one line
     */
    fun scrollUp(lines: Int = 1) {
        repeat(lines) {
            // Save the top line to scrollback
            if (scrollTop == 0) {
                scrollbackBuffer.addLast(screenBuffer[scrollTop].copyOf())
                
                // Limit scrollback size
                while (scrollbackBuffer.size > maxScrollbackLines) {
                    scrollbackBuffer.removeFirst()
                }
            }
            
            // Shift lines up
            for (r in scrollTop until scrollBottom) {
                screenBuffer[r] = screenBuffer[r + 1]
                markLineDirty(r)
            }
            
            // Clear the bottom line
            screenBuffer[scrollBottom] = Array(cols) {
                TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
            markLineDirty(scrollBottom)
        }
    }
    
    /**
     * Scroll the screen down by one line
     */
    fun scrollDown(lines: Int = 1) {
        repeat(lines) {
            // Shift lines down
            for (r in scrollBottom downTo scrollTop + 1) {
                screenBuffer[r] = screenBuffer[r - 1]
                markLineDirty(r)
            }
            
            // Clear the top line
            screenBuffer[scrollTop] = Array(cols) {
                TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
            markLineDirty(scrollTop)
        }
    }
    
    /**
     * Clear the screen
     */
    fun clearScreen() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                screenBuffer[r][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
        }
        markAllDirty()
        Logger.d("TerminalBuffer", "Screen cleared")
    }
    
    /**
     * Clear from cursor to end of screen
     */
    fun clearToEndOfScreen() {
        // Clear from cursor to end of current line
        for (c in cursorCol until cols) {
            screenBuffer[cursorRow][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
        }
        markLineDirty(cursorRow)
        
        // Clear all lines below
        for (r in cursorRow + 1 until rows) {
            for (c in 0 until cols) {
                screenBuffer[r][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
            markLineDirty(r)
        }
    }
    
    /**
     * Clear from beginning of screen to cursor
     */
    fun clearToBeginningOfScreen() {
        // Clear all lines above
        for (r in 0 until cursorRow) {
            for (c in 0 until cols) {
                screenBuffer[r][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
            }
            markLineDirty(r)
        }
        
        // Clear from beginning of current line to cursor
        for (c in 0..cursorCol) {
            screenBuffer[cursorRow][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
        }
        markLineDirty(cursorRow)
    }
    
    /**
     * Clear the current line
     */
    fun clearLine() {
        for (c in 0 until cols) {
            screenBuffer[cursorRow][c] = TerminalChar(' ', currentFgColor, currentBgColor, currentBold, currentUnderline, currentReverse, currentBlink)
        }
        markLineDirty(cursorRow)
    }
    
    /**
     * Move cursor to position (row, col) - 0-indexed
     */
    fun setCursorPosition(row: Int, col: Int) {
        cursorRow = if (originMode) {
            (scrollTop + row).coerceIn(scrollTop, scrollBottom)
        } else {
            row.coerceIn(0, rows - 1)
        }
        cursorCol = col.coerceIn(0, cols - 1)
    }
    
    /**
     * Move cursor relatively
     */
    fun moveCursor(deltaRow: Int, deltaCol: Int) {
        setCursorPosition(cursorRow + deltaRow, cursorCol + deltaCol)
    }
    
    /**
     * Save cursor position
     */
    fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
    }
    
    /**
     * Restore cursor position
     */
    fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
    }
    
    /**
     * Set character attributes
     */
    fun setCharacterAttributes(
        fgColor: Int = currentFgColor,
        bgColor: Int = currentBgColor,
        bold: Boolean = currentBold,
        underline: Boolean = currentUnderline,
        reverse: Boolean = currentReverse,
        blink: Boolean = currentBlink
    ) {
        currentFgColor = fgColor
        currentBgColor = bgColor
        currentBold = bold
        currentUnderline = underline
        currentReverse = reverse
        currentBlink = blink
    }
    
    /**
     * Reset character attributes to defaults
     */
    fun resetCharacterAttributes() {
        currentFgColor = 7 // White
        currentBgColor = 0 // Black
        currentBold = false
        currentUnderline = false
        currentReverse = false
        currentBlink = false
    }
    
    /**
     * Switch to alternate screen buffer
     */
    fun useAlternateScreen(use: Boolean) {
        if (use && !isAlternateScreen) {
            // Save current screen
            alternateScreenBuffer = screenBuffer.map { it.copyOf() }.toTypedArray()
            alternateScrollback = LinkedList(scrollbackBuffer)
            
            // Clear screen for alternate buffer
            clearScreen()
            setCursorPosition(0, 0)
            isAlternateScreen = true
            
            Logger.d("TerminalBuffer", "Switched to alternate screen")
            
        } else if (!use && isAlternateScreen) {
            // Restore main screen
            alternateScreenBuffer?.let { altBuffer ->
                screenBuffer = altBuffer
                alternateScreenBuffer = null
            }
            
            alternateScrollback?.let { altScrollback ->
                scrollbackBuffer.clear()
                scrollbackBuffer.addAll(altScrollback)
                alternateScrollback = null
            }
            
            isAlternateScreen = false
            markAllDirty()
            
            Logger.d("TerminalBuffer", "Switched to main screen")
        }
    }
    
    /**
     * Set scroll region
     */
    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(scrollTop, rows - 1)
        
        // Move cursor to origin
        if (originMode) {
            setCursorPosition(0, 0)
        }
    }
    
    // Getters
    fun getCursorRow() = cursorRow
    fun getCursorCol() = cursorCol
    fun getRows() = rows
    fun getCols() = cols
    fun getScrollbackSize() = scrollbackBuffer.size
    
    fun getChar(row: Int, col: Int): TerminalChar? {
        return if (row in 0 until rows && col in 0 until cols) {
            screenBuffer[row][col]
        } else null
    }
    
    fun getLine(row: Int): Array<TerminalChar>? {
        return if (row in 0 until rows) {
            screenBuffer[row]
        } else null
    }
    
    fun getScrollbackLine(index: Int): Array<TerminalChar>? {
        return if (index in 0 until scrollbackBuffer.size) {
            scrollbackBuffer[index]
        } else null
    }
    
    // Dirty tracking
    fun isLineDirty(row: Int): Boolean = row in dirtyLines.indices && dirtyLines[row]
    fun isScreenDirty(): Boolean = entireScreenDirty
    
    fun markLineDirty(row: Int) {
        if (row in dirtyLines.indices) {
            dirtyLines[row] = true
        }
    }
    
    fun markAllDirty() {
        dirtyLines.fill(true)
        entireScreenDirty = true
    }
    
    fun clearDirtyFlags() {
        dirtyLines.fill(false)
        entireScreenDirty = false
    }
    
    /**
     * Get screen content as string
     */
    fun getScreenContent(): String {
        val sb = StringBuilder()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                sb.append(screenBuffer[r][c].char)
            }
            if (r < rows - 1) sb.append('\n')
        }
        return sb.toString()
    }
    
    /**
     * Get scrollback content as string
     */
    fun getScrollbackContent(): String {
        val sb = StringBuilder()
        scrollbackBuffer.forEach { line ->
            line.forEach { char -> sb.append(char.char) }
            sb.append('\n')
        }
        return sb.toString()
    }
    
    // Mode setters
    fun setInsertMode(insert: Boolean) { insertMode = insert }
    fun setOriginMode(origin: Boolean) { originMode = origin }
    fun setWrapMode(wrap: Boolean) { wrapMode = wrap }
    fun setReverseVideo(reverse: Boolean) { 
        reverseVideo = reverse
        markAllDirty()
    }
    
    // Mode getters
    fun isInsertMode() = insertMode
    fun isOriginMode() = originMode
    fun isWrapMode() = wrapMode
    fun isReverseVideo() = reverseVideo
    fun isAlternateScreen() = isAlternateScreen
}