package io.github.tabssh.ui.views

import org.junit.Test
import kotlin.test.assertEquals

class TerminalViewMouseCellTest {

    @Test
    fun `first cell is one not zero`() {
        assertEquals(1, TerminalView.mouseCell(0f, 0f, 10f, 80))
        assertEquals(1, TerminalView.mouseCell(9.9f, 0f, 10f, 80))
    }

    @Test
    fun `second cell starts at one cell width`() {
        assertEquals(2, TerminalView.mouseCell(10f, 0f, 10f, 80))
    }

    @Test
    fun `origin offset is subtracted`() {
        assertEquals(1, TerminalView.mouseCell(24f, 20f, 10f, 80))
        assertEquals(3, TerminalView.mouseCell(45f, 20f, 10f, 80))
    }

    @Test
    fun `touch before the grid clamps to the first cell`() {
        assertEquals(1, TerminalView.mouseCell(-50f, 20f, 10f, 80))
    }

    @Test
    fun `touch past the grid clamps to the last cell`() {
        assertEquals(80, TerminalView.mouseCell(5000f, 0f, 10f, 80))
    }

    @Test
    fun `zero cell size does not divide by zero`() {
        assertEquals(5, TerminalView.mouseCell(4.5f, 0f, 0f, 80))
    }
}
