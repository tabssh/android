package io.github.tabssh.ui.views

import io.github.tabssh.ui.views.ScrollbarThumbGeometry.DragClaim
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the pure scrollback-thumb geometry used by TerminalView's
 * transient right-edge scrollbar overlay.
 *
 * Conventions under test: track = view height; scrollY = 0 is the live
 * bottom and scrollY = maxScroll is the oldest line; the thumb maps that
 * linearly with top-of-track = oldest, bottom-of-track = live; drag
 * direction disambiguation must let horizontal movement fall through so
 * the Issue #168 edge-swipe tab fling keeps working.
 */
class ScrollbarThumbGeometryTest {

    // ── thumbLengthPx ────────────────────────────────────────────────────────

    @Test
    fun `thumb length is proportional to viewport over total content`() {
        // 1000px viewport + 3000px scrollback → thumb = 1000 * 1000/4000 = 250px.
        assertEquals(250f, ScrollbarThumbGeometry.thumbLengthPx(1000f, 3000f, 48f), 0.01f)
    }

    @Test
    fun `thumb length is clamped up to the minimum on deep scrollback`() {
        // Proportional value would be ~9.9px — the 48px floor wins.
        assertEquals(48f, ScrollbarThumbGeometry.thumbLengthPx(1000f, 100_000f, 48f), 0.01f)
    }

    @Test
    fun `thumb length never exceeds the track`() {
        // A minimum larger than the track is capped at the track length.
        assertEquals(40f, ScrollbarThumbGeometry.thumbLengthPx(40f, 10f, 144f), 0.01f)
    }

    @Test
    fun `thumb length is zero without scrollback or without a track`() {
        assertEquals(0f, ScrollbarThumbGeometry.thumbLengthPx(1000f, 0f, 48f), 0.01f)
        assertEquals(0f, ScrollbarThumbGeometry.thumbLengthPx(0f, 500f, 48f), 0.01f)
    }

    // ── thumbTopPx ───────────────────────────────────────────────────────────

    @Test
    fun `thumb sits at the bottom of the track at the live position`() {
        // scrollY = 0 (live) → top = track − thumb length.
        assertEquals(750f, ScrollbarThumbGeometry.thumbTopPx(1000f, 3000f, 0f, 250f), 0.01f)
    }

    @Test
    fun `thumb sits at the top of the track at the oldest position`() {
        assertEquals(0f, ScrollbarThumbGeometry.thumbTopPx(1000f, 3000f, 3000f, 250f), 0.01f)
    }

    @Test
    fun `thumb position maps scroll linearly at the midpoint`() {
        assertEquals(375f, ScrollbarThumbGeometry.thumbTopPx(1000f, 3000f, 1500f, 250f), 0.01f)
    }

    @Test
    fun `thumb position clamps a scroll beyond the shrunken maximum`() {
        // Buffer shrank mid-fling: scrollY above maxScroll must clamp to top = 0.
        assertEquals(0f, ScrollbarThumbGeometry.thumbTopPx(1000f, 500f, 900f, 250f), 0.01f)
    }

    @Test
    fun `thumb position is zero when the thumb fills the track`() {
        assertEquals(0f, ScrollbarThumbGeometry.thumbTopPx(1000f, 3000f, 1500f, 1000f), 0.01f)
    }

    // ── dragScrollDelta ──────────────────────────────────────────────────────

    @Test
    fun `dragging down moves toward the live bottom`() {
        // Positive dy (finger down) must decrease scrollY.
        assertTrue(ScrollbarThumbGeometry.dragScrollDelta(10f, 1000f, 3000f, 250f) < 0f)
    }

    @Test
    fun `dragging across the full usable range traverses the full scrollback`() {
        // Usable range = 1000 − 250 = 750px; dragging up by 750px covers 3000px.
        assertEquals(3000f, ScrollbarThumbGeometry.dragScrollDelta(-750f, 1000f, 3000f, 250f), 0.01f)
    }

    @Test
    fun `drag delta round-trips with thumb position`() {
        // Moving the thumb from live to a new top via the drag delta lands the
        // scroll exactly where thumbTopPx maps that top back from.
        val track = 1000f
        val maxScroll = 3000f
        val len = ScrollbarThumbGeometry.thumbLengthPx(track, maxScroll, 48f)
        val startTop = ScrollbarThumbGeometry.thumbTopPx(track, maxScroll, 0f, len)
        // Drag the thumb up 300px from the live position.
        val newScroll = 0f + ScrollbarThumbGeometry.dragScrollDelta(-300f, track, maxScroll, len)
        assertEquals(startTop - 300f, ScrollbarThumbGeometry.thumbTopPx(track, maxScroll, newScroll, len), 0.01f)
    }

    @Test
    fun `drag delta is zero without scrollback or usable range`() {
        assertEquals(0f, ScrollbarThumbGeometry.dragScrollDelta(50f, 1000f, 0f, 250f), 0.01f)
        assertEquals(0f, ScrollbarThumbGeometry.dragScrollDelta(50f, 1000f, 3000f, 1000f), 0.01f)
    }

    // ── isInThumbTouchTarget ─────────────────────────────────────────────────

    @Test
    fun `touch inside the strip and on the thumb hits`() {
        assertTrue(
            ScrollbarThumbGeometry.isInThumbTouchTarget(
                xPx = 1070f, yPx = 500f, viewWidthPx = 1080f, stripWidthPx = 72f,
                thumbTopPx = 400f, thumbLenPx = 200f, padPx = 36f
            )
        )
    }

    @Test
    fun `touch inside the strip within the vertical pad hits`() {
        // 30px above the thumb top, pad is 36px — still a grab.
        assertTrue(
            ScrollbarThumbGeometry.isInThumbTouchTarget(
                xPx = 1070f, yPx = 370f, viewWidthPx = 1080f, stripWidthPx = 72f,
                thumbTopPx = 400f, thumbLenPx = 200f, padPx = 36f
            )
        )
    }

    @Test
    fun `touch left of the strip misses even at thumb height`() {
        assertFalse(
            ScrollbarThumbGeometry.isInThumbTouchTarget(
                xPx = 900f, yPx = 500f, viewWidthPx = 1080f, stripWidthPx = 72f,
                thumbTopPx = 400f, thumbLenPx = 200f, padPx = 36f
            )
        )
    }

    @Test
    fun `touch inside the strip but far from the thumb misses`() {
        assertFalse(
            ScrollbarThumbGeometry.isInThumbTouchTarget(
                xPx = 1070f, yPx = 100f, viewWidthPx = 1080f, stripWidthPx = 72f,
                thumbTopPx = 400f, thumbLenPx = 200f, padPx = 36f
            )
        )
    }

    // ── classifyDrag — thumb-drag vs tab-swipe disambiguation ────────────────

    @Test
    fun `movement within slop stays undecided`() {
        assertEquals(DragClaim.UNDECIDED, ScrollbarThumbGeometry.classifyDrag(5f, -5f, 24f))
    }

    @Test
    fun `predominantly vertical movement claims the drag`() {
        assertEquals(DragClaim.CLAIM, ScrollbarThumbGeometry.classifyDrag(10f, -80f, 24f))
        assertEquals(DragClaim.CLAIM, ScrollbarThumbGeometry.classifyDrag(-10f, 80f, 24f))
    }

    @Test
    fun `predominantly horizontal movement passes through for the tab swipe`() {
        // A leftward flick from the right edge must reach the edge-swipe
        // fling handler (Issue #168) untouched.
        assertEquals(DragClaim.PASS, ScrollbarThumbGeometry.classifyDrag(-80f, 10f, 24f))
        assertEquals(DragClaim.PASS, ScrollbarThumbGeometry.classifyDrag(80f, -10f, 24f))
    }

    @Test
    fun `an exact diagonal passes through so the tab swipe wins ties`() {
        assertEquals(DragClaim.PASS, ScrollbarThumbGeometry.classifyDrag(50f, 50f, 24f))
    }

    @Test
    fun `vertical-only movement past slop claims even with zero horizontal`() {
        assertEquals(DragClaim.CLAIM, ScrollbarThumbGeometry.classifyDrag(0f, 30f, 24f))
    }
}
