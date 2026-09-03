package io.github.tabssh.ui.views

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import io.github.tabssh.terminal.TermuxBridge
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression test for TODO.AI.md item 55: copying a soft-wrapped selection
 * fused words together across the wrap point ("but a" + "running" ->
 * "but arunning"), dropping the separating space while hard newlines stayed
 * correct.
 *
 * [TerminalView.getSelectedText] used to hand the whole selection rectangle
 * to Termux's own multi-row TerminalBuffer.getSelectedText() in one call.
 * That method only ever treats a row as "wrapped" via the real mLineWrap
 * flag. A genuine DECAWM auto-wrap (a continuous byte stream hitting the
 * column limit) always sets that flag and the boundary character the
 * terminal actually wrote is preserved verbatim wherever it landed, so nonce
 * is ever lost there. But mosh (and any other absolute-cursor-positioning
 * renderer) never replays the wrap escape sequence, so TabSSH falls back to
 * a full-row-width heuristic (joinFullLines) for those sessions instead —
 * and a genuine word-wrap layout engine never writes the separating space to
 * either row in the first place, so there is nothing left in the buffer to
 * recover. [TerminalView.getWrapAwareSelectionText] handles the two cases
 * differently: it never adds anything on a real mLineWrap, and it supplies
 * exactly one space when the heuristic (not the real flag) is why two rows
 * are being joined, since that heuristic path can only ever represent a
 * word-boundary wrap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewSelectionSoftWrapSpaceTest {

    private val cols = 20

    private fun newView(rows: Int): TerminalView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(rows, cols)
        return view
    }

    /** A no-op TerminalOutput — the tests never write back to a remote host. */
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

    /** Feeds [data] through a real Termux TerminalEmulator as one continuous byte stream. */
    private fun termuxViewFromStream(rows: Int, data: String): Pair<TerminalView, TerminalEmulator> {
        val view = newView(rows)
        val emulator = TerminalEmulator(silentOutput(), cols, rows, 200, null)
        val bytes = data.toByteArray(Charsets.US_ASCII)
        emulator.append(bytes, bytes.size)
        setField(view, "termuxBuffer", emulator.screen)
        setField(view, "terminalBuffer", null)
        return Pair(view, emulator)
    }

    /**
     * Writes [row0] and [row1] with explicit cursor-positioning escapes instead
     * of a continuous stream, so Termux's own DECAWM never engages and neither
     * row's mLineWrap flag is ever set — the same shape mosh's screen-diff
     * sync produces, since it pokes cells directly instead of replaying the
     * remote's original wrap sequence.
     */
    private fun termuxViewFromAbsolutePositioning(
        rows: Int,
        row0: String,
        row1: String,
        moshAlive: Boolean
    ): TerminalView {
        val view = newView(rows)
        val emulator = TerminalEmulator(silentOutput(), cols, rows, 200, null)
        val script = "[1;1H$row0[2;1H$row1"
        val bytes = script.toByteArray(Charsets.US_ASCII)
        emulator.append(bytes, bytes.size)
        setField(view, "termuxBuffer", emulator.screen)
        setField(view, "terminalBuffer", null)
        val bridge = mock(TermuxBridge::class.java)
        whenever(bridge.isMoshSessionAlive()).thenReturn(moshAlive)
        setField(view, "termuxBridge", bridge)
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
    fun `real DECAWM wrap at a word boundary keeps the space exactly once`() {
        // 19 filler chars + a boundary space at column 20, then the next word.
        val text = "x".repeat(cols - 1) + " next"
        val (view, emulator) = termuxViewFromStream(rows = 3, data = text)

        assertEquals(true, emulator.screen.getLineWrap(0), "row 0 should have really auto-wrapped")
        select(view, startRow = 0, startCol = 0, endRow = 1, endCol = 3)

        assertEquals(text, view.getSelectedText())
    }

    @Test
    fun `real DECAWM mid-word hard wrap never injects a space`() {
        // One unbroken token longer than two row widths - the only way this
        // can wrap at all is a real, mid-token DECAWM auto-wrap. It must
        // spill past row 1 (not just fill it) because Termux only sets a
        // row's wrap flag when the cursor actually wraps OFF that row.
        val token = "b".repeat(cols * 2 + 5)
        val (view, emulator) = termuxViewFromStream(rows = 3, data = token)

        assertEquals(true, emulator.screen.getLineWrap(0))
        assertEquals(true, emulator.screen.getLineWrap(1))
        select(view, startRow = 0, startCol = 0, endRow = 2, endCol = 4)

        assertEquals(token, view.getSelectedText())
    }

    @Test
    fun `mosh full-width heuristic join restores the consumed word-boundary space`() {
        // Row 0 fills every column with no trailing space (the separator was
        // never written by the reflow), row 1 continues with the next word.
        val row0 = "a".repeat(cols)
        val row1 = "running"
        val view = termuxViewFromAbsolutePositioning(rows = 3, row0 = row0, row1 = row1, moshAlive = true)

        select(view, startRow = 0, startCol = 0, endRow = 1, endCol = row1.length - 1)

        assertEquals("$row0 $row1", view.getSelectedText())
    }

    @Test
    fun `same full-width rows on a non-mosh session stay hard-newlined, not fused`() {
        // Same buffer shape as the mosh case, but joinFullLines is only ever
        // enabled for mosh - a plain SSH session with no real wrap flag must
        // keep treating this as two separate lines, never fusing them.
        val row0 = "a".repeat(cols)
        val row1 = "running"
        val view = termuxViewFromAbsolutePositioning(rows = 3, row0 = row0, row1 = row1, moshAlive = false)

        select(view, startRow = 0, startCol = 0, endRow = 1, endCol = row1.length - 1)

        assertEquals("$row0\n$row1", view.getSelectedText())
    }

    @Test
    fun `explicit hard newline is preserved without fusion or an extra space`() {
        val (view, _) = termuxViewFromStream(rows = 3, data = "short line\r\nnext line")

        select(view, startRow = 0, startCol = 0, endRow = 1, endCol = 8)

        assertEquals("short line\nnext line", view.getSelectedText())
    }

    @Test
    fun `trailing blank cells past the printed content are not copied as garbage`() {
        val (view, _) = termuxViewFromStream(rows = 2, data = "hi")

        // Select the whole row width even though only 2 columns were printed.
        select(view, startRow = 0, startCol = 0, endRow = 0, endCol = cols - 1)

        assertEquals("hi", view.getSelectedText())
    }
}
