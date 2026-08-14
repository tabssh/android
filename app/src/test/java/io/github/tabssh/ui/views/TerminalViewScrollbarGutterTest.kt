package io.github.tabssh.ui.views

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Regression test for the "text hidden at the right edge" bug: the grid was
 * sized to the full view width while the always-visible scrollbar track was
 * drawn on top of the rightmost column, hiding the last character of every
 * full-width line. The grid must now leave a gutter at least as wide as the
 * scrollbar's footprint (track width + edge margin + text gap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TerminalViewScrollbarGutterTest {

    private fun field(view: TerminalView, name: String): Any {
        val f = TerminalView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(view)!!
    }

    @Test
    fun `grid never extends under the scrollbar gutter`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = TerminalView(context)
        view.initialize(24, 80)

        val width = 1080
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, 1920)
        // updateGridSize is debounced behind the main-looper handler.
        shadowOf(Looper.getMainLooper()).idle()

        val cellWidth = field(view, "cellWidth") as Float
        // Robolectric's legacy graphics can report zero-width text metrics;
        // the geometry assertion is only meaningful with real metrics.
        assumeTrue(cellWidth > 0f)

        val cols = field(view, "terminalCols") as Int
        val gridRight = view.paddingLeft + cols * cellWidth

        // The scrollbar footprint: 4dp track + 4dp edge margin + 4dp gap.
        val reservePx = 12f * context.resources.displayMetrics.density
        assertTrue(
            gridRight <= width - view.paddingRight - reservePx + 0.5f,
            "grid right edge ${gridRight}px must clear the ${reservePx}px scrollbar gutter (view width $width)"
        )
    }
}
