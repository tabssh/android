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
    fun `test scroll region set does not crash`() {
        // Region-aware scrolling is not implemented: scrollUp()/scrollDown()
        // ignore the region. This verifies setScrollRegion accepts a valid
        // range and the buffer keeps functioning. See AUDIT.AI.md.
        buffer.setScrollRegion(5, 15)

        val before = buffer.getScrollbackSize()
        buffer.scrollUp()
        assertEquals(before + 1, buffer.getScrollbackSize())
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
