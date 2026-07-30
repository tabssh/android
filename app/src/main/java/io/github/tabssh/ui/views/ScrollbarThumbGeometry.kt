package io.github.tabssh.ui.views

import kotlin.math.abs

/**
 * Pure geometry for the TerminalView scrollback thumb overlay.
 *
 * Coordinate model: the track is the full view height. The content the track
 * represents is (viewport + scrollback) = (trackPx + maxScrollPx). scrollYPx
 * follows TerminalView's convention: 0 = live bottom, maxScrollPx = oldest
 * scrollback line. The thumb maps that linearly with top-of-track = oldest
 * and bottom-of-track = live, so the thumb sits at the bottom while attached
 * to the live screen and rises as the user scrolls into history.
 *
 * No Android dependencies — every function is a pure float mapping so the
 * whole model is unit-testable on the JVM.
 */
object ScrollbarThumbGeometry {

    /**
     * Outcome of the right-edge gesture disambiguation between a thumb drag
     * (vertical) and the Issue #168 edge-swipe tab fling (horizontal).
     */
    enum class DragClaim {
        /** Movement still within slop — keep watching, decide later. */
        UNDECIDED,
        /** Predominantly vertical — the thumb owns this pointer until up. */
        CLAIM,
        /** Predominantly horizontal — fall through to normal gesture handling. */
        PASS
    }

    /**
     * Thumb length in pixels: proportional to viewport / (viewport + scrollback),
     * raised to [minThumbPx] so it stays grabbable, capped at the track length.
     * Returns 0 when there is no track or no scrollback (thumb not shown).
     */
    fun thumbLengthPx(trackPx: Float, maxScrollPx: Float, minThumbPx: Float): Float {
        if (trackPx <= 0f || maxScrollPx <= 0f) return 0f
        val proportional = trackPx * trackPx / (trackPx + maxScrollPx)
        return proportional.coerceAtLeast(minThumbPx).coerceAtMost(trackPx)
    }

    /**
     * Top edge of the thumb for a given scroll position. scrollYPx = 0 (live)
     * puts the thumb at the bottom of the track; scrollYPx = maxScrollPx
     * (oldest) puts it at the top. Values are clamped so a mid-fling buffer
     * shrink can never draw the thumb outside the track.
     */
    fun thumbTopPx(trackPx: Float, maxScrollPx: Float, scrollYPx: Float, thumbLenPx: Float): Float {
        val range = trackPx - thumbLenPx
        if (range <= 0f || maxScrollPx <= 0f) return 0f
        val fraction = 1f - (scrollYPx / maxScrollPx).coerceIn(0f, 1f)
        return fraction * range
    }

    /**
     * Scroll delta produced by dragging the thumb [dyPx] pixels (positive =
     * finger moved down). Dragging down moves toward the live bottom, so the
     * returned delta is negative for positive dyPx. A drag across the full
     * usable range (track − thumb) traverses the full scrollback.
     */
    fun dragScrollDelta(dyPx: Float, trackPx: Float, maxScrollPx: Float, thumbLenPx: Float): Float {
        val range = trackPx - thumbLenPx
        if (range <= 0f || maxScrollPx <= 0f) return 0f
        return -dyPx * maxScrollPx / range
    }

    /**
     * True when a touch-down lands in the thumb's grab target: inside the
     * right-edge strip of [stripWidthPx] AND vertically within the thumb
     * extended by [padPx] on both ends (generous slop for finger accuracy).
     */
    fun isInThumbTouchTarget(
        xPx: Float,
        yPx: Float,
        viewWidthPx: Float,
        stripWidthPx: Float,
        thumbTopPx: Float,
        thumbLenPx: Float,
        padPx: Float
    ): Boolean {
        if (xPx < viewWidthPx - stripWidthPx) return false
        return yPx >= thumbTopPx - padPx && yPx <= thumbTopPx + thumbLenPx + padPx
    }

    /**
     * Direction disambiguation for a pointer that went down on the thumb.
     * Within slop on both axes → UNDECIDED. Once slop is exceeded, a strictly
     * dominant vertical component claims the drag; a horizontal-or-equal
     * component passes so the Issue #168 edge-swipe tab fling keeps working
     * from the same right-edge strip.
     */
    fun classifyDrag(dxPx: Float, dyPx: Float, slopPx: Float): DragClaim {
        if (abs(dxPx) <= slopPx && abs(dyPx) <= slopPx) return DragClaim.UNDECIDED
        return if (abs(dyPx) > abs(dxPx)) DragClaim.CLAIM else DragClaim.PASS
    }
}
