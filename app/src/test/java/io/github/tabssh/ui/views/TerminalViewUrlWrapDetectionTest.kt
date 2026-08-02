package io.github.tabssh.ui.views

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for long-URL soft-wrap detection in [TerminalView.detectUrlAtPosition].
 *
 * A URL that soft-wraps across more than 9 terminal rows used to fail detection
 * entirely (or return a truncated fragment) because the wrap-aware reconstruction
 * window was additionally clamped to +-4 rows around the tap point, even though
 * the actual wrap segment (segStart..segEnd) was already correctly bounded. Any
 * tap more than 4 rows from the row containing the URL's scheme prefix built a
 * window that never reached that prefix, so the URL regex — which requires a
 * scheme to start a match — found nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewUrlWrapDetectionTest {

    private val cols = 80

    private fun newView(rows: Int): TerminalView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(rows, cols)
        return view
    }

    private fun cellSize(view: TerminalView): Pair<Float, Float> {
        val cellWidthField = TerminalView::class.java.getDeclaredField("cellWidth")
        cellWidthField.isAccessible = true
        val cellHeightField = TerminalView::class.java.getDeclaredField("cellHeight")
        cellHeightField.isAccessible = true
        return Pair(cellWidthField.get(view) as Float, cellHeightField.get(view) as Float)
    }

    private fun detectUrlAt(view: TerminalView, row: Int, col: Int): String? {
        val (cellWidth, cellHeight) = cellSize(view)
        val x = view.paddingLeft + col * cellWidth + 1f
        val y = view.paddingTop + row * cellHeight + 1f
        val method = TerminalView::class.java.getDeclaredMethod(
            "detectUrlAtPosition", Float::class.java, Float::class.java
        )
        method.isAccessible = true
        return method.invoke(view, x, y) as String?
    }

    @Test
    fun `long URL spanning many soft-wrapped rows is detected from any row`() {
        // 13 rows of 80 cols each -> a URL far longer than the old +-4-row window.
        val url = "https://example.com/" + "a".repeat(cols * 12) + "end"
        val line = "user@host:~$ curl $url"

        val wrappedRows = (line.length + cols - 1) / cols
        val view = newView(rows = wrappedRows + 4)
        view.sendData(line.toByteArray(Charsets.US_ASCII))

        for (tapRow in 0 until wrappedRows) {
            val detected = detectUrlAt(view, tapRow, col = 5)
            assertEquals(url, detected, "tap on row $tapRow should resolve the full wrapped URL")
        }
    }

    @Test
    fun `short single-row URL is still detected`() {
        val url = "https://example.com/short"
        val line = "see $url please"
        val view = newView(rows = 24)
        view.sendData(line.toByteArray(Charsets.US_ASCII))

        val detected = detectUrlAt(view, row = 0, col = 6)
        assertEquals(url, detected)
    }

    @Test
    fun `tap away from any url returns null`() {
        val line = "just some plain text with no links here"
        val view = newView(rows = 24)
        view.sendData(line.toByteArray(Charsets.US_ASCII))

        assertNull(detectUrlAt(view, row = 0, col = 2))
    }
}
