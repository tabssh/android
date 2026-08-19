package io.github.tabssh.terminal.emulator

/**
 * Terminal character buffer with formatting.
 *
 * The [url] field carries an OSC 8 hyperlink URL when the character was written
 * while an OSC 8 link was active (via ANSIParser).  null = no link.
 */
data class TerminalChar(
    val char: Char,
    val fgColor: Int,
    val bgColor: Int,
    val bold: Boolean,
    val underline: Boolean,
    val reverse: Boolean,
    val url: String? = null
) {
    companion object {
        fun empty(): TerminalChar = TerminalChar(' ', 7, 0, false, false, false)
    }
}

/**
 * Terminal buffer for storing character grid and scrollback
 */
class TerminalBuffer(
    rows: Int,
    cols: Int,
    private var maxScrollbackLines: Int = DEFAULT_SCROLLBACK_LINES
) {

    companion object {
        // Default scrollback bound. The buffer used to default to unlimited, which
        // let a chatty or hostile server grow the process heap without limit until
        // the app was OOM-killed. Callers that genuinely want no limit must ask for
        // it explicitly via setScrollbackLimit(-1).
        const val DEFAULT_SCROLLBACK_LINES = 10000
    }

    // A zero or negative grid would make every screen[rows - 1] access throw, so
    // the dimensions are clamped to a usable minimum on construction and resize.
    private var rows: Int = rows.coerceAtLeast(1)
    private var cols: Int = cols.coerceAtLeast(1)

    private var screen = Array(this.rows) { Array(this.cols) { TerminalChar(' ', 7, 0, false, false, false) } }

    // Per-row soft-wrap flag.  true = this row ends with an auto-wrap (the line
    // continues on the next row); false = this row ends with a hard newline or is
    // the last row.  Used by getScreenContent() to decide whether to insert '\n'.
    private var rowWrapped = BooleanArray(this.rows)

    private val scrollback = mutableListOf<Array<TerminalChar>>()

    // Active ANSI palette (16 ARGB entries) installed by the theme, or null when
    // the renderer's built-in default palette should be used.
    private var colorPalette: IntArray? = null

    private var cursorX = 0
    private var cursorY = 0

    // Deferred auto-wrap (xterm/VT "pending wrap" state). When a printable
    // character lands in the last column with DECAWM on, the cursor stays put
    // and this flag is raised instead of wrapping immediately. The wrap only
    // happens if another printable character arrives; any cursor movement
    // (CR, LF, BS, TAB, positioning, resize, clear) cancels it. Without this,
    // a CR/LF right after the final column produced a phantom blank row and
    // falsely marked the filled row as soft-wrapped.
    private var pendingWrap = false

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
        pendingWrap = false
    }

    /**
     * Absolute cursor positioning (CUP / HVP). With DECOM (origin mode) set,
     * row 1 means the top of the DECSTBM scrolling region and the cursor may
     * not be placed outside it. Without DECOM this is plain screen-absolute
     * positioning.
     */
    fun setCursorPositionAbsolute(x: Int, y: Int) {
        if (originMode) {
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = (scrollTop + y).coerceIn(scrollTop, scrollBottom)
            pendingWrap = false
        } else {
            setCursorPosition(x, y)
        }
    }

    fun getCursorPosition(): Pair<Int, Int> = Pair(cursorX, cursorY)

    /**
     * DECTCEM (ESC[?25h / ESC[?25l). Renderers ask for this so a full-screen
     * app that hides the cursor while redrawing does not leave a block
     * flickering over its output.
     */
    fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
    }

    fun isCursorVisible(): Boolean = cursorVisible

    /**
     * True when the scrolling region covers the whole screen and the main screen
     * is active — the only situation in which departing rows belong in scrollback.
     * A DECSTBM region (vi's status line, tmux panes) must scroll in place, and
     * alternate-screen rows are editor frames the user never asked to keep.
     */
    private fun scrollbackEligible(): Boolean =
        scrollTop == 0 && scrollBottom == rows - 1 && !alternateScreen

    fun scrollUp() {
        // Move the departing line to scrollback (full-region main screen only)
        if (scrollbackEligible()) {
            scrollback.add(screen[scrollTop].copyOf())

            // Limit scrollback size based on preference
            if (maxScrollbackLines != -1 && scrollback.size > maxScrollbackLines) {
                scrollback.removeAt(0)
            }
        }

        // Shift the region's lines up, carrying wrap flags with them
        for (i in scrollTop + 1..scrollBottom) {
            screen[i - 1] = screen[i]
            rowWrapped[i - 1] = rowWrapped[i]
        }

        // Clear the region's last line and its wrap flag
        screen[scrollBottom] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
        rowWrapped[scrollBottom] = false
    }

    fun scrollDown() {
        // Pull the most recent scrollback line back onto the screen when there is
        // one; otherwise insert a blank line, which is what reverse index expects.
        // Previously an empty scrollback made it a no-op.
        val restored = if (scrollbackEligible()) scrollback.removeLastOrNull() else null
        for (i in scrollBottom downTo scrollTop + 1) {
            screen[i] = screen[i - 1]
            rowWrapped[i] = rowWrapped[i - 1]
        }
        // Scrollback rows keep the width they had when they were pushed, so a line
        // restored after a resize has to be re-fitted or the renderer would index
        // past the end of it.
        screen[scrollTop] = Array(cols) { c -> restored?.getOrNull(c) ?: TerminalChar(' ', 7, 0, false, false, false) }
        rowWrapped[scrollTop] = false
    }

    fun clear() {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                screen[row][col] = TerminalChar(' ', 7, 0, false, false, false)
            }
        }
        rowWrapped.fill(false)
        cursorX = 0
        cursorY = 0
        pendingWrap = false
    }

    fun getLine(row: Int): Array<TerminalChar>? {
        return if (row in 0 until rows) screen[row] else null
    }

    fun setTitle(newTitle: String) {
        title = newTitle
    }

    fun getTitle(): String = title

    fun getScrollbackSize(): Int = scrollback.size

    /**
     * Install the active 16-entry ANSI colour palette (index 0..15 = ARGB).
     * Shorter arrays are rejected so the renderer never indexes past the end.
     */
    fun setColors(colors: IntArray) {
        colorPalette = if (colors.size >= 16) colors.copyOf() else null
    }

    /** The palette installed by [setColors], or null to use the renderer default. */
    fun getColorPalette(): IntArray? = colorPalette

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

    /**
     * Lines shifted by insert/delete-line stop at the scrolling region's last row:
     * IL/DL inside a DECSTBM region must never push rows past its bottom margin.
     */
    private fun shiftBoundary(row: Int): Int =
        if (row in scrollTop..scrollBottom) scrollBottom else rows - 1

    fun insertLine(row: Int) {
        if (row in 0 until rows) {
            val bottom = shiftBoundary(row)
            for (i in bottom downTo row + 1) {
                screen[i] = screen[i - 1]
                rowWrapped[i] = rowWrapped[i - 1]
            }
            screen[row] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
            rowWrapped[row] = false
        }
    }

    fun deleteLine(row: Int) {
        if (row in 0 until rows) {
            val bottom = shiftBoundary(row)
            for (i in row until bottom) {
                screen[i] = screen[i + 1]
                rowWrapped[i] = rowWrapped[i + 1]
            }
            screen[bottom] = Array(cols) { TerminalChar(' ', 7, 0, false, false, false) }
            rowWrapped[bottom] = false
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
    private var cursorVisible = true
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

    /** Returns true if [row] soft-wraps into the next row (auto-wrap, no hard newline). */
    fun isRowWrapped(row: Int): Boolean = row in 0 until rows && rowWrapped[row]

    // OSC 8 hyperlink state: the URL that is "active" while the cursor is inside
    // an OSC 8 link span.  ANSIParser sets this on the open tag and clears it on
    // the close tag; writeChar() stamps the current value onto every cell it writes.
    private var currentLinkUrl: String? = null

    /** Set or clear the active OSC 8 hyperlink URL (null = no link). */
    fun setCurrentLinkUrl(url: String?) {
        currentLinkUrl = url
    }

    private var bracketedPasteMode = false

    fun setBracketedPasteMode(enabled: Boolean) {
        bracketedPasteMode = enabled
    }

    fun isBracketedPasteModeActive(): Boolean = bracketedPasteMode

    /**
     * Return the OSC 8 URL embedded in the cell at [row],[col], or null if the
     * cell has no link or the coordinates are out of range.
     */
    fun getUrlAt(row: Int, col: Int): String? =
        if (row in 0 until rows && col in 0 until cols) screen[row][col].url else null

    fun saveCursor() {
        savedCursorX = cursorX
        savedCursorY = cursorY
    }

    fun restoreCursor() {
        cursorX = savedCursorX
        cursorY = savedCursorY
        pendingWrap = false
    }

    fun clearScreen() {
        clear()
    }

    fun resetCharacterAttributes() {
        currentAttrs = CharacterAttributes()
    }

    fun resize(newRows: Int, newCols: Int) {
        val oldScreen = screen
        val oldRowWrapped = rowWrapped
        val oldAlternate = if (alternateScreen) mainScreen else alternateScreenBuffer
        rows = newRows.coerceAtLeast(1)
        cols = newCols.coerceAtLeast(1)
        screen = resizedGrid(oldScreen)
        rowWrapped = BooleanArray(rows)

        // Carry the wrap flags across for the rows that survived the resize
        val copyRows = minOf(oldScreen.size, rows)
        for (r in 0 until copyRows) {
            rowWrapped[r] = oldRowWrapped.getOrElse(r) { false }
        }

        // The off-screen buffer must be resized too. Leaving it at the old
        // dimensions made the next alternate-screen switch index out of bounds
        // whenever the view had grown (rotation, keyboard hide, font change).
        val resizedOther = oldAlternate?.let { resizedGrid(it) }
        if (alternateScreen) {
            mainScreen = resizedOther ?: Array(rows) { Array(cols) { TerminalChar(' ', 7, 0, false, false, false) } }
            alternateScreenBuffer = screen
        } else {
            mainScreen = screen
            alternateScreenBuffer = resizedOther
        }

        // Adjust cursor position and the scrolling region to the new geometry
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        pendingWrap = false
        scrollTop = scrollTop.coerceIn(0, rows - 1)
        scrollBottom = scrollBottom.coerceIn(scrollTop, rows - 1)
    }

    /**
     * Copy [source] into a grid of the current [rows] x [cols], padding with blanks.
     */
    private fun resizedGrid(source: Array<Array<TerminalChar>>): Array<Array<TerminalChar>> {
        val target = Array(rows) { Array(cols) { TerminalChar(' ', 7, 0, false, false, false) } }
        val copyRows = minOf(source.size, rows)
        for (r in 0 until copyRows) {
            val copyCols = minOf(source[r].size, cols)
            for (c in 0 until copyCols) {
                target[r][c] = source[r][c]
            }
        }
        return target
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
        pendingWrap = false
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

    // No default argument here: a defaulted parameter would make this overload
    // a candidate for every bare scrollUp() call and make resolution ambiguous.
    fun scrollUp(lines: Int) {
        repeat(lines.coerceIn(0, rows)) { scrollUp() }
    }

    fun scrollDown(lines: Int) {
        repeat(lines.coerceIn(0, rows)) { scrollDown() }
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        // Clamp the top FIRST and derive the bottom's lower bound from the clamped
        // value: a remote host can send ESC[999;5r, and coerceIn(999, rows - 1)
        // has min > max, which throws IllegalArgumentException on the UI thread.
        val clampedTop = top.coerceIn(0, rows - 1)
        scrollTop = clampedTop
        scrollBottom = bottom.coerceIn(clampedTop, rows - 1)
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
            pendingWrap = false
        }
    }

    /**
     * Move the cursor down one line, scrolling the active region when it is
     * sitting on the region's last row. Scrolling on `cursorY >= rows` alone
     * ignored DECSTBM entirely, so a program with a scroll region (vi's status
     * line, tmux) scrolled rows it must leave untouched.
     */
    private fun advanceLine() {
        if (cursorY == scrollBottom) {
            scrollUp()
        } else {
            cursorY = (cursorY + 1).coerceAtMost(rows - 1)
        }
    }

    fun writeChar(ch: Char) {
        when (ch) {
            '\n' -> {
                // Hard newline — the current row is NOT soft-wrapped
                pendingWrap = false
                rowWrapped[cursorY] = false
                advanceLine()
            }
            '\r' -> {
                pendingWrap = false
                cursorX = 0
            }
            '\t' -> {
                // Move to next tab stop (every 8 columns)
                pendingWrap = false
                cursorX = ((cursorX / 8) + 1) * 8
                if (cursorX >= cols) {
                    cursorX = cols - 1
                }
            }
            '\b' -> {
                pendingWrap = false
                if (cursorX > 0) cursorX--
            }
            else -> {
                // Deferred auto-wrap: the previous character filled the last
                // column, so the wrap it owed is performed now that another
                // printable character has actually arrived.
                if (pendingWrap) {
                    pendingWrap = false
                    rowWrapped[cursorY] = true
                    cursorX = 0
                    advanceLine()
                }
                if (cursorX < cols && cursorY < rows) {
                    // IRM (ESC[4h): the character is inserted, so everything from
                    // the cursor rightwards shifts one cell and the last cell of
                    // the row falls off. Without this the mode was accepted and
                    // then silently overwrote, corrupting line editors that rely
                    // on it instead of redrawing.
                    if (insertMode) {
                        for (c in cols - 1 downTo cursorX + 1) {
                            screen[cursorY][c] = screen[cursorY][c - 1]
                        }
                    }
                    screen[cursorY][cursorX] = TerminalChar(
                        ch,
                        currentAttrs.fgColor,
                        currentAttrs.bgColor,
                        currentAttrs.bold,
                        currentAttrs.underline,
                        currentAttrs.reverse,
                        currentLinkUrl
                    )
                    if (cursorX == cols - 1 && wrapMode) {
                        // Last column with DECAWM on: hold the cursor here and
                        // defer the wrap until the next printable character.
                        pendingWrap = true
                    } else {
                        cursorX++
                    }
                }
            }
        }
    }
}
