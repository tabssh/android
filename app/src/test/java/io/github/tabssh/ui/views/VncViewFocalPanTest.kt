package io.github.tabssh.ui.views

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for [VncView.focalPan] — the pinch-zoom focal-point math.
 * Before this fix, [VncView]'s `onScale` always scaled around the bitmap
 * centre, so pinch-zoomed content visibly drifted out from under the
 * fingers instead of staying pinned under the pinch focal point.
 */
class VncViewFocalPanTest {

    // focalPan is `internal`, so it's directly callable from this
    // same-package, same-module test — no reflection needed.
    private fun invoke(
        viewWidth: Int,
        viewHeight: Int,
        fbWidth: Int,
        fbHeight: Int,
        focusX: Float,
        focusY: Float,
        oldBitmapX: Float,
        oldBitmapY: Float,
        newScale: Float
    ): Pair<Float, Float> = VncView.focalPan(
        viewWidth, viewHeight, fbWidth, fbHeight,
        focusX, focusY, oldBitmapX, oldBitmapY, newScale
    )

    @Test
    fun `keeps the bitmap point under the focal point after scaling up`() {
        // A 1000x1000 framebuffer fit into an 800x600 view, pinching in
        // around bitmap point (400, 400) with the focal point at (500, 350).
        // Mirrors VncView.screenToBitmap's origin math: with the resulting
        // pan applied, the bitmap point under the focal point must still be
        // (oldBitmapX, oldBitmapY) — that's the whole point of focalPan,
        // otherwise content drifts out from under the pinch fingers.
        val viewWidth = 800f
        val viewHeight = 600f
        val fbWidth = 1000
        val fbHeight = 1000
        val focusX = 500f
        val focusY = 350f
        val oldBitmapX = 400f
        val oldBitmapY = 400f
        val newScale = 1.5f
        val (panX, panY) = invoke(
            viewWidth = viewWidth.toInt(), viewHeight = viewHeight.toInt(),
            fbWidth = fbWidth, fbHeight = fbHeight,
            focusX = focusX, focusY = focusY,
            oldBitmapX = oldBitmapX, oldBitmapY = oldBitmapY,
            newScale = newScale
        )
        val originX = (viewWidth - fbWidth * newScale) / 2f - panX * newScale
        val originY = (viewHeight - fbHeight * newScale) / 2f - panY * newScale
        val recoveredBitmapX = (focusX - originX) / newScale
        val recoveredBitmapY = (focusY - originY) / newScale
        assertEquals(oldBitmapX, recoveredBitmapX, 0.01f)
        assertEquals(oldBitmapY, recoveredBitmapY, 0.01f)
    }

    @Test
    fun `pan is clamped to non-negative bounds`() {
        val (panX, panY) = invoke(
            viewWidth = 800, viewHeight = 600,
            fbWidth = 1000, fbHeight = 1000,
            focusX = 0f, focusY = 0f,
            oldBitmapX = 0f, oldBitmapY = 0f,
            newScale = 2.0f
        )
        assertTrue(panX >= 0f)
        assertTrue(panY >= 0f)
    }

    @Test
    fun `pan is clamped to the maximum scroll extent`() {
        val (panX, panY) = invoke(
            viewWidth = 800, viewHeight = 600,
            fbWidth = 1000, fbHeight = 1000,
            focusX = 10000f, focusY = 10000f,
            oldBitmapX = 1000f, oldBitmapY = 1000f,
            newScale = 2.0f
        )
        val maxPanX = 1000f - 800f / 2.0f
        val maxPanY = 1000f - 600f / 2.0f
        assertEquals(maxPanX, panX, 0.01f)
        assertEquals(maxPanY, panY, 0.01f)
    }

    @Test
    fun `zero or negative scale returns a zero pan rather than dividing by zero`() {
        val (panX, panY) = invoke(
            viewWidth = 800, viewHeight = 600,
            fbWidth = 1000, fbHeight = 1000,
            focusX = 400f, focusY = 300f,
            oldBitmapX = 400f, oldBitmapY = 400f,
            newScale = 0f
        )
        assertEquals(0f, panX)
        assertEquals(0f, panY)
    }
}
