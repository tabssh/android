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
import kotlin.test.assertNull

/**
 * A program that splits the screen (herdr, tmux, vim) wraps a long link at its own pane edge: the row stops at the
 * pane's right border and the next row picks up at the pane's left edge. The link must still be one link, underlined
 * on both rows and tappable from either, and the border glyph must not become part of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewPaneUrlWrapTest {

    private val cols = 60
    private val rows = 12
    private val border = '│'
    private val url = "https://example.com/" + "a".repeat(30)

    private fun at(row: Int, col: Int) = "\u001b[${row + 1};${col + 1}H"

    /**
     * Sidebar on the left (columns 0..13) with its border at column 14 beside the lower rows only, a right border at
     * column 53 on every row, and the link written into the pane (columns 15..52): its first 38 characters on row 2,
     * the rest on row 3.
     */
    private fun paneView(): TerminalView {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Application>())
        view.initialize(rows, cols)
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val emulator = TerminalEmulator(output, cols, rows, 200, null)
        val script = StringBuilder()
        for (r in 0 until rows) {
            script.append(at(r, 53)).append(border)
            if (r >= 6) script.append(at(r, 14)).append(border)
        }
        script.append(at(2, 0)).append("main")
        script.append(at(3, 0)).append("agent")
        script.append(at(2, 15)).append(url.take(38))
        script.append(at(3, 15)).append(url.drop(38))
        val bytes = script.toString().toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
        setField(view, "termuxBuffer", emulator.screen)
        setField(view, "terminalBuffer", null)
        return view
    }

    private fun setField(view: TerminalView, name: String, value: Any?) {
        val field = TerminalView::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(view, value)
    }

    private fun detectUrlAt(view: TerminalView, row: Int, col: Int): String? {
        val cellHeightField = TerminalView::class.java.getDeclaredField("cellHeight").apply { isAccessible = true }
        if ((cellHeightField.get(view) as Float) <= 0f) cellHeightField.set(view, 20f)
        val cellWidthField = TerminalView::class.java.getDeclaredField("cellWidth").apply { isAccessible = true }
        val cellWidth = cellWidthField.get(view) as Float
        val cellHeight = cellHeightField.get(view) as Float
        // The middle of the cell, so the tap lands in this column whatever the cell width.
        val x = view.paddingLeft + (col + 0.5f) * cellWidth
        val gridTop = TerminalView::class.java.getDeclaredMethod("getGridTop").apply { isAccessible = true }.invoke(view) as Float
        val y = gridTop + row * cellHeight + 1f
        return TerminalView::class.java.getDeclaredMethod("detectUrlAtPosition", Float::class.java, Float::class.java)
            .apply { isAccessible = true }.invoke(view, x, y) as String?
    }

    @Test
    fun `a link wrapped at the pane edge is found from either row`() {
        val view = paneView().also { it.paneDetectionOverride = true }

        assertEquals(url, detectUrlAt(view, row = 2, col = 20))
        assertEquals(url, detectUrlAt(view, row = 3, col = 17))
    }

    @Test
    fun `the border next to the link is not part of it`() {
        val view = paneView().also { it.paneDetectionOverride = true }

        assertEquals(url, detectUrlAt(view, row = 2, col = 52))
    }

    @Test
    fun `both rows of the wrapped link are underlined in the pane columns only`() {
        val view = paneView().also { it.paneDetectionOverride = true }
        TerminalView::class.java.getDeclaredMethod("rebuildUrlUnderlines", Int::class.java)
            .apply { isAccessible = true }.invoke(view, rows)

        @Suppress("UNCHECKED_CAST")
        val cache = TerminalView::class.java.getDeclaredField("urlUnderlineCache")
            .apply { isAccessible = true }.get(view) as Map<Int, List<IntRange>>
        assertEquals(listOf(15..52), cache[2])
        assertEquals(listOf(15..26), cache[3])
    }

    @Test
    fun `without pane detection the continuation row is not recognised as a link`() {
        val view = paneView().also { it.paneDetectionOverride = false }

        assertNull(detectUrlAt(view, row = 3, col = 17))
    }
}
