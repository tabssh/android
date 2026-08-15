package io.github.tabssh.terminal.emulator

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for terminal buffer functionality against the current TerminalBuffer API.
 */
class TerminalBufferTest {

    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer(rows = 24, cols = 80, maxScrollbackLines = 1000)
    }

    // Convenience: write a whole string through the char-at-a-time API.
    private fun TerminalBuffer.write(text: String) = text.forEach { writeChar(it) }

    @Test
    fun `test buffer initialization`() {
        assertEquals(24, buffer.getRows())
        assertEquals(80, buffer.getCols())
        assertEquals(0, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())
        assertEquals(0, buffer.getScrollbackSize())
    }

    @Test
    fun `test character writing`() {
        buffer.writeChar('H')
        buffer.writeChar('i')

        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(0, 1)?.char)
        assertEquals(0, buffer.getCursorRow())
        assertEquals(2, buffer.getCursorCol())
    }

    @Test
    fun `test newline advances row without carriage return`() {
        // LF moves the cursor down one row but does not reset the column
        // (the buffer implements LF and CR separately, VT100-style).
        buffer.writeChar('H')
        buffer.writeChar('\n')
        buffer.writeChar('i')

        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(1, 1)?.char)
        assertEquals(1, buffer.getCursorRow())
        assertEquals(2, buffer.getCursorCol())
    }

    @Test
    fun `test carriage return handling`() {
        buffer.write("Hello")
        buffer.writeChar('\r')
        buffer.write("Hi")

        // CR returns to column 0, so "Hi" overwrites the start of "Hello".
        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(0, 1)?.char)
        assertEquals('l', buffer.getChar(0, 2)?.char) // Rest of "Hello" remains
    }

    @Test
    fun `test tab handling`() {
        buffer.writeChar('\t')
        assertEquals(8, buffer.getCursorCol()) // Tab stops every 8 columns

        buffer.writeChar('X')
        buffer.writeChar('\t')
        assertEquals(16, buffer.getCursorCol()) // Next tab stop
    }

    @Test
    fun `test line wrapping`() {
        // Fill a line completely; the buffer wraps eagerly on the 80th column.
        buffer.write("A".repeat(80))

        assertEquals('A', buffer.getChar(0, 79)?.char)
        assertEquals(1, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())

        // Next character lands at the start of the wrapped row.
        buffer.writeChar('B')
        assertEquals('B', buffer.getChar(1, 0)?.char)
        assertEquals(1, buffer.getCursorRow())
        assertEquals(1, buffer.getCursorCol())
    }

    @Test
    fun `test cursor positioning`() {
        // setCursorPosition(x, y): first arg is the column, second is the row.
        buffer.setCursorPosition(5, 10)
        assertEquals(5, buffer.getCursorCol())
        assertEquals(10, buffer.getCursorRow())

        // Bounds checking clamps to the grid.
        buffer.setCursorPosition(-1, -1)
        assertEquals(0, buffer.getCursorCol())
        assertEquals(0, buffer.getCursorRow())

        buffer.setCursorPosition(100, 100)
        assertEquals(79, buffer.getCursorCol()) // cols - 1
        assertEquals(23, buffer.getCursorRow()) // rows - 1
    }

    @Test
    fun `test cursor save and restore`() {
        buffer.setCursorPosition(10, 20)
        buffer.saveCursor()

        buffer.setCursorPosition(5, 15)
        assertEquals(5, buffer.getCursorCol())
        assertEquals(15, buffer.getCursorRow())

        buffer.restoreCursor()
        assertEquals(10, buffer.getCursorCol())
        assertEquals(20, buffer.getCursorRow())
    }

    @Test
    fun `test screen clearing`() {
        buffer.write("Hello World")
        buffer.clearScreen()

        assertEquals(' ', buffer.getChar(0, 0)?.char)
        assertEquals(0, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())
    }

    @Test
    fun `test scrolling up moves a line to scrollback`() {
        for (i in 0 until 24) {
            buffer.write("Line $i")
            if (i < 23) buffer.writeChar('\n')
        }

        val before = buffer.getScrollbackSize()
        buffer.scrollUp()
        assertEquals(before + 1, buffer.getScrollbackSize())
    }

    @Test
    fun `test resize functionality`() {
        buffer.write("Test content")

        buffer.resize(30, 100)
        assertEquals(30, buffer.getRows())
        assertEquals(100, buffer.getCols())

        // Content is preserved where the grids overlap.
        assertEquals('T', buffer.getChar(0, 0)?.char)
    }

    @Test
    fun `test alternate screen buffer round trip`() {
        buffer.write("Main screen content")

        // Switching to the alternate screen clears it; writes there do not
        // touch the main screen.
        buffer.useAlternateScreen(true)
        buffer.write("Alternate screen content")

        // Switching back restores the original main-screen content.
        buffer.useAlternateScreen(false)
        assertTrue(buffer.getVisibleText().contains("Main screen"))
    }

    @Test
    fun `test character attributes are stamped onto written cells`() {
        buffer.setCharacterAttributes(fgColor = 1, bgColor = 2, bold = true, underline = true)
        buffer.writeChar('X')

        val char = buffer.getChar(0, 0)!!
        assertEquals('X', char.char)
        assertEquals(1, char.fgColor)
        assertEquals(2, char.bgColor)
        assertTrue(char.bold)
        assertTrue(char.underline)
    }

    @Test
    fun `test scroll region confines scrolling and skips scrollback`() {
        buffer.setScrollRegion(5, 15)

        // Rows outside the region must not move, and a partial region never
        // contributes to scrollback — those rows are still on screen.
        buffer.setCursorPosition(0, 4)
        buffer.write("above")
        buffer.setCursorPosition(0, 5)
        buffer.write("top")

        val before = buffer.getScrollbackSize()
        buffer.scrollUp()

        assertEquals(before, buffer.getScrollbackSize())
        // Row 4 sits above the region and is untouched.
        assertEquals('a', buffer.getChar(4, 0)?.char)
        // Row 5 is the region's top and now holds the (blank) old row 6.
        assertEquals(' ', buffer.getChar(5, 0)?.char)
    }

    @Test
    fun `test scroll region with an out of range top does not throw`() {
        // A remote host can send ESC[999;5r; the clamped top must also become
        // the lower bound of the bottom, or coerceIn gets min greater than max.
        buffer.setScrollRegion(998, 4)
        buffer.scrollUp()
    }

    @Test
    fun `test scrollback management honours the limit`() {
        val maxScrollback = 5
        val smallBuffer = TerminalBuffer(3, 10, maxScrollback)

        // Feed more lines than the scrollback can hold.
        for (i in 0..10) {
            smallBuffer.writeChar('L')
            smallBuffer.writeChar('\n')
        }

        assertTrue(smallBuffer.getScrollbackSize() <= maxScrollback)
    }

    @Test
    fun `insert mode shifts the rest of the line right`() {
        buffer.write("ABCD")
        buffer.setCursorPosition(1, 0)
        buffer.setInsertMode(true)
        buffer.writeChar('X')

        // IRM: "ABCD" with X inserted at column 1 becomes "AXBCD".
        assertEquals('A', buffer.getChar(0, 0)?.char)
        assertEquals('X', buffer.getChar(0, 1)?.char)
        assertEquals('B', buffer.getChar(0, 2)?.char)
        assertEquals('C', buffer.getChar(0, 3)?.char)
        assertEquals('D', buffer.getChar(0, 4)?.char)
    }

    @Test
    fun `insert mode off overwrites in place`() {
        buffer.write("ABCD")
        buffer.setCursorPosition(1, 0)
        buffer.writeChar('X')

        assertEquals('A', buffer.getChar(0, 0)?.char)
        assertEquals('X', buffer.getChar(0, 1)?.char)
        assertEquals('C', buffer.getChar(0, 2)?.char)
    }

    @Test
    fun `origin mode makes absolute positioning relative to the scroll region`() {
        buffer.setScrollRegion(5, 15)
        buffer.setOriginMode(true)

        // Row 0 in origin mode is the top of the region.
        buffer.setCursorPositionAbsolute(0, 0)
        assertEquals(5, buffer.getCursorRow())

        buffer.setCursorPositionAbsolute(0, 3)
        assertEquals(8, buffer.getCursorRow())

        // The cursor may not escape the region.
        buffer.setCursorPositionAbsolute(0, 99)
        assertEquals(15, buffer.getCursorRow())
    }

    @Test
    fun `origin mode off leaves absolute positioning screen-relative`() {
        buffer.setScrollRegion(5, 15)
        buffer.setCursorPositionAbsolute(0, 0)
        assertEquals(0, buffer.getCursorRow())
    }

    @Test
    fun `cursor visibility tracks DECTCEM`() {
        assertTrue(buffer.isCursorVisible())
        buffer.setCursorVisible(false)
        assertFalse(buffer.isCursorVisible())
        buffer.setCursorVisible(true)
        assertTrue(buffer.isCursorVisible())
    }

    @Test
    fun `test terminal char basics`() {
        val char = TerminalChar('A', fgColor = 1, bgColor = 2, bold = true, underline = false, reverse = true)

        assertEquals('A', char.char)
        assertEquals(1, char.fgColor)
        assertEquals(2, char.bgColor)
        assertTrue(char.bold)
        assertFalse(char.underline)
        assertTrue(char.reverse)
        assertNull(char.url)

        val empty = TerminalChar.empty()
        assertEquals(' ', empty.char)
        assertEquals(7, empty.fgColor)
        assertEquals(0, empty.bgColor)
    }
}
