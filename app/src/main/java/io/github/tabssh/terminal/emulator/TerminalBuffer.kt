package io.github.tabssh.terminal.emulator

/**
 * Terminal character buffer with formatting
 */
data class TerminalChar(
    val char: Char,
    val fgColor: Int,
    val bgColor: Int,
    val bold: Boolean,
    val underline: Boolean,
    val reverse: Boolean
) {
    companion object {
        fun empty(): TerminalChar = TerminalChar(' ', 7, 0, false, false, false)
    }
}

/**
 * Terminal buffer for storing character grid and scrollback
 */
class TerminalBuffer(
    private var rows: Int, 
    private var cols: Int,
    private var maxScrollbackLines: Int = -1 // -1 = unlimited
) {

    private var screen = Array(rows) { Array(cols) { TerminalChar(' ', 7, 0, false, false, false) } }
    private val scrollback = mutableListOf<Array<TerminalChar>>()
    private var cursorX = 0
    private var cursorY = 0
    private var title = "Terminal"

    /**
     * Update scrollback limit (minimum 250, -1 for unlimited)
     */
    fun setScrollbackLimit(lines: Int) {
        maxScrollbackLines = when {
            lines == -1 -> -1 // unlimited
            lines < 250 -> 250 // enforce minimum
            else -> lines
        }
        
        // Trim existing scrollback if needed
        if (maxScrollbackLines != -1 && scrollback.size > maxScrollbackLines) {
            val toRemove = scrollback.size - maxScrollbackLines
            repeat(toRemove) {
                scrollback.removeAt(0)
            }
        }
    }

    fun getRows(): Int = rows
    fun getCols(): Int = cols

    fun setChar(row: Int, col: Int, char: Char, fgColor: Int, bgColor: Int, 
               bold: Boolean, underline: Boolean, reverse: Boolean) {
        if (row in 0 until rows && col in 0 until cols) {
            screen[row][col] = TerminalChar(char, fgColor, bgColor, bold, underline, reverse)
        }
    }

    fun getChar(row: Int, col: Int): TerminalChar? {
        return if (row in 0 until rows && col in 0 until cols) {
            screen[row][col]
        } else null
    }

    fun setCursorPosition(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
    }

    fun getCursorPosition(): Pair<Int, Int> = Pair(cursorX, cursorY)

    fun scrollUp() {
        // Move first line to scrollback
        scrollback.add(screen[0].copyOf())
        
        // Shift all lines up
        for (i in 1 until rows) {
            screen[i - 1] = screen[i]
        }
        
        // Clear last line
        screen[rows - 1] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
        
        // Limit scrollback size based on preference
        if (maxScrollbackLines != -1 && scrollback.size > maxScrollbackLines) {
            scrollback.removeAt(0)
        }
    }

    fun scrollDown() {
        // Move last line from scrollback
        if (scrollback.isNotEmpty()) {
            val line = scrollback.removeLastOrNull()
            if (line != null) {
                for (i in rows - 1 downTo 1) {
                    screen[i] = screen[i - 1]
                }
                screen[0] = line
            }
        }
    }

    fun clear() {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
        cursorX = 0
        cursorY = 0
    }

    fun getLine(row: Int): Array<TerminalChar>? {
        return if (row in 0 until rows) screen[row] else null
    }

    fun setTitle(newTitle: String) {
        title = newTitle
    }

    fun getTitle(): String = title

    fun getScrollbackSize(): Int = scrollback.size

    fun setColors(colors: IntArray) {
        // Apply color palette
    }

    fun eraseFromCursor() {
        // Clear from cursor to end of screen
        for (row in cursorY until rows) {
            val startCol = if (row == cursorY) cursorX else 0
            for (col in startCol until cols) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun eraseToCursor() {
        // Clear from beginning to cursor
        for (row in 0..cursorY) {
            val endCol = if (row == cursorY) cursorX else cols - 1
            for (col in 0..endCol) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun eraseLineFromCursor(row: Int) {
        if (row in 0 until rows) {
            for (col in cursorX until cols) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun eraseLineToCursor(row: Int) {
        if (row in 0 until rows) {
            for (col in 0..cursorX) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun eraseLine(row: Int) {
        if (row in 0 until rows) {
            for (col in 0 until cols) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun insertLine(row: Int) {
        if (row in 0 until rows) {
            for (i in rows - 1 downTo row + 1) {
                screen[i] = screen[i - 1]
            }
            screen[row] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
        }
    }

    fun deleteLine(row: Int) {
        if (row in 0 until rows) {
            for (i in row until rows - 1) {
                screen[i] = screen[i + 1]
            }
            screen[rows - 1] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
        }
    }

    fun getVisibleText(): String {
        val sb = StringBuilder()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                sb.append(screen[row][col].char)
            }
            if (row < rows - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Get scrollback content as string
     */
    fun getScrollbackContent(): String {
        val sb = StringBuilder()
        for (line in scrollback) {
            for (cell in line) {
                sb.append(cell.char)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // Cursor management
    private var savedCursorX = 0
    private var savedCursorY = 0
    private var currentAttrs = CharacterAttributes()
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var insertMode = false
    private var wrapMode = true
    private var originMode = false
    private var alternateScreen = false
    private var mainScreen = screen
    private var alternateScreenBuffer: Array<Array<TerminalChar>>? = null

    data class CharacterAttributes(
        var fgColor: Int = 7,
        var bgColor: Int = 0,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var blink: Boolean = false,
        var reverse: Boolean = false
    )

    fun getCursorRow(): Int = cursorY
    fun getCursorCol(): Int = cursorX

    fun saveCursor() {
        savedCursorX = cursorX
        savedCursorY = cursorY
    }

    fun restoreCursor() {
        cursorX = savedCursorX
        cursorY = savedCursorY
    }

    fun clearScreen() {
        clear()
    }

    fun resetCharacterAttributes() {
        currentAttrs = CharacterAttributes()
    }

    fun resize(newRows: Int, newCols: Int) {
        val oldScreen = screen
        rows = newRows
        cols = newCols
        screen = Array(rows) { Array(cols) { TerminalChar(' ', 7, 0, false, false, false) } }

        // Copy existing content
        val copyRows = minOf(oldScreen.size, rows)
        for (r in 0 until copyRows) {
            val copyCols = minOf(oldScreen[r].size, cols)
            for (c in 0 until copyCols) {
                screen[r][c] = oldScreen[r][c]
            }
        }

        // Adjust cursor position
        cursorX = minOf(cursorX, cols - 1)
        cursorY = minOf(cursorY, rows - 1)
    }

    fun setCharacterAttributes(
        fgColor: Int? = null,
        bgColor: Int? = null,
        bold: Boolean? = null,
        underline: Boolean? = null,
        blink: Boolean? = null,
        reverse: Boolean? = null
    ) {
        fgColor?.let { currentAttrs.fgColor = it }
        bgColor?.let { currentAttrs.bgColor = it }
        bold?.let { currentAttrs.bold = it }
        underline?.let { currentAttrs.underline = it }
        blink?.let { currentAttrs.blink = it }
        reverse?.let { currentAttrs.reverse = it }
    }

    fun moveCursor(deltaX: Int, deltaY: Int) {
        cursorX = (cursorX + deltaX).coerceIn(0, cols - 1)
        cursorY = (cursorY + deltaY).coerceIn(0, rows - 1)
    }

    fun clearToEndOfScreen() {
        eraseFromCursor()
    }

    fun clearToBeginningOfScreen() {
        eraseToCursor()
    }

    fun clearLine() {
        if (cursorY in 0 until rows) {
            for (col in 0 until cols) {
                screen[cursorY][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
    }

    fun scrollUp(lines: Int = 1) {
        repeat(lines) { scrollUp() }
    }

    fun scrollDown(lines: Int = 1) {
        repeat(lines) { scrollDown() }
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(top, rows - 1)
    }

    fun setInsertMode(enabled: Boolean) {
        insertMode = enabled
    }

    fun setWrapMode(enabled: Boolean) {
        wrapMode = enabled
    }

    fun setOriginMode(enabled: Boolean) {
        originMode = enabled
    }

    fun useAlternateScreen(use: Boolean) {
        if (use && !alternateScreen) {
            // Switch to alternate screen
            mainScreen = screen
            alternateScreenBuffer = alternateScreenBuffer ?: Array(rows) {
                Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
            }
            screen = alternateScreenBuffer!!
            alternateScreen = true
            clear()
        } else if (!use && alternateScreen) {
            // Switch back to main screen
            alternateScreenBuffer = screen
            screen = mainScreen
            alternateScreen = false
        }
    }

    fun writeChar(ch: Char) {
        when (ch) {
            '\n' -> {
                cursorY++
                if (cursorY >= rows) {
                    scrollUp()
                    cursorY = rows - 1
                }
            }
            '\r' -> cursorX = 0
            '\t' -> {
                // Move to next tab stop (every 8 columns)
                cursorX = ((cursorX / 8) + 1) * 8
                if (cursorX >= cols) {
                    cursorX = cols - 1
                }
            }
            '\b' -> {
                if (cursorX > 0) cursorX--
            }
            else -> {
                if (cursorX < cols && cursorY < rows) {
                    screen[cursorY][cursorX] = TerminalChar(
                        ch,
                        currentAttrs.fgColor,
                        currentAttrs.bgColor,
                        currentAttrs.bold,
                        currentAttrs.underline,
                        currentAttrs.reverse
                    )
                    cursorX++
                    if (cursorX >= cols && wrapMode) {
                        cursorX = 0
                        cursorY++
                        if (cursorY >= rows) {
                            scrollUp()
                            cursorY = rows - 1
                        }
                    }
                }
            }
        }
    }
}
