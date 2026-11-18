package io.github.tabssh.ui.views

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A full-screen program (vim, neovim, tmux, herdr) puts panes side by side, so selecting inside the
 * right pane must copy only that pane's text instead of every row's text from the left edge, and the
 * Block button must still give a plain rectangle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewBlockSelectionTest {

    private val cols = 20

    private fun silentOutput() = object : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun setField(view: TerminalView, name: String, value: Any?) {
        val field = TerminalView::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(view, value)
    }

    /** Two panes, a 6-column sidebar and a text pane, drawn row by row with cursor positioning. */
    private fun twoPaneView(border: Boolean = true): TerminalView {
        val rows = 4
        val view = TerminalView(ApplicationProvider.getApplicationContext<Application>())
        view.initialize(rows, cols)
        val emulator = TerminalEmulator(silentOutput(), cols, rows, 200, null)
        val divider = if (border) "|" else " "
        val screen = listOf("menu  $divider alpha beta", "agent $divider gamma delta", "new   $divider epsilon", "      $divider zeta")
        val script = screen.mapIndexed { i, line -> "\u001b[${i + 1};1H$line" }.joinToString("")
        val bytes = script.toByteArray(Charsets.US_ASCII)
        emulator.append(bytes, bytes.size)
        setField(view, "termuxBuffer", emulator.screen)
        setField(view, "terminalBuffer", null)
        return view
    }

    private fun select(view: TerminalView, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        setField(view, "selectionAnchorRow", startRow)
        setField(view, "selectionAnchorCol", startCol)
        setField(view, "selectionFocusRow", endRow)
        setField(view, "selectionFocusCol", endCol)
        setField(view, "selectionActive", true)
        // The same start-of-selection step the touch path runs: read the pane borders off the screen.
        TerminalView::class.java.getDeclaredMethod("beginSelectionState").apply { isAccessible = true }.invoke(view)
    }

    @Test
    fun `without a pane border a run of text reaches the left edge on the rows between the handles`() {
        val view = twoPaneView(border = false)
        select(view, startRow = 0, startCol = 8, endRow = 2, endCol = 10)

        val text = view.getSelectedText()!!
        assertEquals(true, "agent" in text, "no border means no panes to tell apart: $text")
    }

    @Test
    fun `a run of text stays inside the pane it starts in`() {
        val view = twoPaneView()
        select(view, startRow = 0, startCol = 8, endRow = 2, endCol = 12)

        assertEquals("alpha beta\n gamma delta\n epsil", view.getSelectedText())
    }

    @Test
    fun `a run of text dragged upward from the right pane is the same`() {
        val view = twoPaneView()
        select(view, startRow = 2, startCol = 12, endRow = 0, endCol = 8)

        assertEquals("alpha beta\n gamma delta\n epsil", view.getSelectedText())
    }

    @Test
    fun `select all takes the whole screen whatever panes it holds`() {
        val view = twoPaneView()
        select(view, startRow = 0, startCol = 8, endRow = 2, endCol = 12)
        view.selectAll()

        assertEquals(true, "agent" in view.getSelectedText()!!)
    }

    @Test
    fun `a block keeps to the columns between the handles`() {
        val view = twoPaneView()
        select(view, startRow = 0, startCol = 8, endRow = 2, endCol = 12)
        view.setBlockSelection(true)

        assertEquals("alpha\ngamma\nepsil", view.getSelectedText())
    }

    @Test
    fun `a block is the same whichever way the handles were dragged`() {
        val view = twoPaneView()
        select(view, startRow = 2, startCol = 12, endRow = 0, endCol = 8)
        view.setBlockSelection(true)

        assertEquals("alpha\ngamma\nepsil", view.getSelectedText())
    }

    @Test
    fun `pane borders are the columns with a vertical line on at least half the rows`() {
        val rows = listOf("ab | cd", "ef | gh", "ij   kl", "mn | op")
        assertEquals(listOf(3), TerminalView.paneSeparatorColumns(rows))
        assertEquals(emptyList(), TerminalView.paneSeparatorColumns(listOf("a|b", "cd", "ef")))
        assertEquals(emptyList(), TerminalView.paneSeparatorColumns(listOf("a|b", "c|d")))
    }

    @Test
    fun `a selection span is cut to the pane that holds its start`() {
        val separators = listOf(10, 30)
        // Start in the middle pane (cols 11..29): first row from the start, middle rows the whole pane, last row to the end.
        assertEquals(15..29, TerminalView.selectionSpan(0, 0, 15, 2, 20, 40, separators))
        assertEquals(11..29, TerminalView.selectionSpan(1, 0, 15, 2, 20, 40, separators))
        assertEquals(11..20, TerminalView.selectionSpan(2, 0, 15, 2, 20, 40, separators))
        // An end dragged into the next pane is held at this pane's edge.
        assertEquals(11..29, TerminalView.selectionSpan(2, 0, 15, 2, 35, 40, separators))
        // No borders: the usual full-width shape.
        assertEquals(15..39, TerminalView.selectionSpan(0, 0, 15, 2, 20, 40, emptyList()))
        assertEquals(0..39, TerminalView.selectionSpan(1, 0, 15, 2, 20, 40, emptyList()))
    }

    @Test
    fun `block columns are ordered left to right`() {
        assertEquals(3 to 9, TerminalView.blockColumns(9, 3))
        assertEquals(3 to 9, TerminalView.blockColumns(3, 9))
    }
}
