package io.github.tabssh.terminal.emulator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Bounds, resize and scrollback tests for [TerminalBuffer].
 *
 * These cover crash and memory-growth paths a remote host can drive: an
 * endless output stream, a rotation while the alternate screen is active, and
 * degenerate terminal dimensions.
 */
class TerminalBufferBoundsTest {

    private fun writeLines(buffer: TerminalBuffer, count: Int) {
        repeat(count) { i ->
            "line $i".forEach { buffer.writeChar(it) }
            buffer.writeChar('\n')
        }
    }

    @Test
    fun `scrollback honours the constructor limit`() {
        val buffer = TerminalBuffer(24, 80, 300)
        writeLines(buffer, 2000)
        assertTrue(buffer.getScrollbackSize() <= 300, "scrollback grew to ${buffer.getScrollbackSize()}")
    }

    @Test
    fun `scrollback honours a limit lowered after the fact`() {
        val buffer = TerminalBuffer(24, 80, 5000)
        writeLines(buffer, 4000)
        buffer.setScrollbackLimit(250)
        assertTrue(buffer.getScrollbackSize() <= 250)
    }

    @Test
    fun `degenerate dimensions are clamped instead of throwing`() {
        val buffer = TerminalBuffer(0, 0)
        assertEquals(1, buffer.getRows())
        assertEquals(1, buffer.getCols())
        buffer.writeChar('x')
        assertNotNull(buffer.getLine(0))
    }

    @Test
    fun `resize while the alternate screen is active keeps both grids usable`() {
        val buffer = TerminalBuffer(24, 80)
        buffer.useAlternateScreen(true)
        // A rotation used to leave the off-screen grid at the old size, so the
        // next write after switching back indexed past the end of a row.
        buffer.resize(40, 100)
        buffer.writeChar('a')
        buffer.useAlternateScreen(false)
        buffer.setCursorPosition(99, 39)
        buffer.writeChar('b')

        assertEquals(40, buffer.getRows())
        assertEquals(100, buffer.getCols())
        assertEquals(100, buffer.getLine(39)?.size)
        assertEquals(100, buffer.getLine(0)?.size)
    }

    @Test
    fun `resize clamps the cursor into the new grid`() {
        val buffer = TerminalBuffer(24, 80)
        buffer.setCursorPosition(79, 23)
        buffer.resize(10, 20)
        assertTrue(buffer.getCursorRow() < 10)
        assertTrue(buffer.getCursorCol() < 20)
        buffer.writeChar('z')
    }

    @Test
    fun `scroll down inserts a blank row when the scrollback is empty`() {
        val buffer = TerminalBuffer(5, 10)
        buffer.scrollDown()
        val top = buffer.getLine(0)
        assertNotNull(top)
        assertEquals(10, top.size)
    }
}
