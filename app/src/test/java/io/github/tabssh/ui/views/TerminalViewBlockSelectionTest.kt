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
 * right pane must copy only that pane's columns instead of every row's text from the left edge.
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
    private fun twoPaneView(): TerminalView {
        val rows = 4
        val view = TerminalView(ApplicationProvider.getApplicationContext<Application>())
        view.initialize(rows, cols)
        val emulator = TerminalEmulator(silentOutput(), cols, rows, 200, null)
        val screen = listOf("menu  | alpha beta", "agent | gamma delta", "new   | epsilon", "      | zeta")
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
    }

    @Test
    fun `a run of text reaches into the sidebar on the rows between the handles`() {
        val view = twoPaneView()
        select(view, startRow = 0, startCol = 8, endRow = 2, endCol = 10)

        val text = view.getSelectedText()!!
        assertEquals(true, "agent" in text, "the sidebar text on the middle row is swept in: $text")
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
    fun `block columns are ordered left to right`() {
        assertEquals(3 to 9, TerminalView.blockColumns(9, 3))
        assertEquals(3 to 9, TerminalView.blockColumns(3, 9))
    }
}
