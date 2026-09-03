package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.tabssh.R

/** Split direction for a 2-window Panes group. Persisted on [io.github.tabssh.storage.database.entities.PaneGroup.splitDirection]. */
object PanesSplitDirection {
    const val HORIZONTAL = "horizontal"
    const val VERTICAL = "vertical"
}

/**
 * Tiled grid ViewGroup for a Panes tab (up to 6 [TerminalView]s in one
 * tab-strip slot). Computes a row/column layout from the pane count,
 * auto-stacks to a single scrollable column below `screenWidthDp < 600`
 * (matching the plan's narrow-screen requirement), and highlights the
 * focused tile's border on tap.
 *
 * For exactly 2 windows, [PanesSplitDirection.HORIZONTAL] (default) keeps
 * the original side-by-side (1 row x 2 columns) layout; VERTICAL stacks
 * them (2 rows x 1 column) — see `PaneGroup.splitDirection`.
 *
 * Reordering/resizing beyond the auto grid is intentionally out of scope
 * for this cut — windows are laid out in [PaneWindow.gridPosition] order in a
 * fixed near-square grid; see TODO.AI.md for drag-to-resize/reorder as a
 * follow-up.
 */
class PanesGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val UNFOCUSED_BORDER_COLOR = Color.TRANSPARENT
        private const val NARROW_WIDTH_DP = 600
    }

    /** One tile: the border frame wrapping a caller-supplied content view, plus a per-window close button. */
    private class Tile(
        context: Context,
        val content: View,
        onClose: () -> Unit
    ) : FrameLayout(context) {
        // Day/night-aware focus border — resolved once per tile, not per focus change
        private val focusBorderColor = ContextCompat.getColor(context, R.color.pane_focus_border)

        init {
            addView(
                content,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            // 48dp minimum touch target — a smaller close button over a live
            // terminal is too easy to miss and taps fall through to focus
            val closeSizePx = resources.getDimensionPixelSize(R.dimen.min_touch_target)
            val closeButton = TextView(context).apply {
                text = context.getString(R.string.pane_window_close_glyph)
                setTextColor(ContextCompat.getColor(context, R.color.white))
                gravity = Gravity.CENTER
                setBackgroundColor(ContextCompat.getColor(context, R.color.pane_close_scrim))
                contentDescription = context.getString(R.string.pane_window_close_content_description)
                setOnClickListener { onClose() }
            }
            addView(
                closeButton,
                LayoutParams(closeSizePx, closeSizePx, Gravity.TOP or Gravity.END)
            )
            val borderPx = resources.getDimensionPixelSize(R.dimen.border_width)
            setPadding(borderPx, borderPx, borderPx, borderPx)
            setBackgroundColor(UNFOCUSED_BORDER_COLOR)
        }

        fun setFocused(focused: Boolean) {
            setBackgroundColor(if (focused) focusBorderColor else UNFOCUSED_BORDER_COLOR)
        }
    }

    private val stackScroll = ScrollView(context)
    private val stackContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val gridContainer = FrameLayout(context)

    private var tiles: List<Tile> = emptyList()
    private var onTileClicked: ((Int) -> Unit)? = null
    private var splitDirection: String = PanesSplitDirection.HORIZONTAL

    init {
        addView(gridContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stackScroll.addView(
            stackContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    /**
     * Set the pane content views (in grid order), the focus-tap callback,
     * the per-window close callback, and (for exactly 2 windows) the split
     * direction to use.
     */
    fun setContents(
        contents: List<View>,
        splitDirection: String = PanesSplitDirection.HORIZONTAL,
        onTileClicked: (Int) -> Unit,
        onTileClosed: (Int) -> Unit
    ) {
        this.onTileClicked = onTileClicked
        this.splitDirection = splitDirection
        gridContainer.removeAllViews()
        stackContainer.removeAllViews()
        removeView(stackScroll)

        tiles = contents.mapIndexed { index, content ->
            (content.parent as? ViewGroup)?.removeView(content)
            Tile(context, content, onClose = { onTileClosed(index) }).also { tile ->
                tile.setOnClickListener {
                    // MotionEvent-based hit testing isn't needed — the whole
                    // tile is the tap target, matching the plan's "click to
                    // focus" requirement.
                    onTileClicked(index)
                }
            }
        }

        if (isNarrowScreen()) {
            tiles.forEach { tile ->
                stackContainer.addView(
                    tile,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.pane_stacked_tile_height)
                    )
                )
            }
            addView(stackScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            layoutGrid()
        }
    }

    /** Update which tile shows the focus border, by grid index. */
    fun setFocusedIndex(index: Int) {
        tiles.forEachIndexed { i, tile -> tile.setFocused(i == index) }
    }

    private fun isNarrowScreen(): Boolean {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return widthDp < NARROW_WIDTH_DP
    }

    /**
     * Near-square row/column split for [count] panes (1..6):
     * 1→1x1, 2→1x2 (or 2x1 if [splitDirection] is vertical), 3→1x3,
     * 4→2x2, 5→2x3(last row 2), 6→2x3.
     */
    private fun gridDimensions(count: Int): Pair<Int, Int> = when (count) {
        1 -> 1 to 1
        2 -> if (splitDirection == PanesSplitDirection.VERTICAL) 2 to 1 else 1 to 2
        3 -> 1 to 3
        4 -> 2 to 2
        5 -> 2 to 3
        else -> 2 to 3
    }

    private fun layoutGrid() {
        val count = tiles.size
        if (count == 0) return
        val (rows, cols) = gridDimensions(count)
        var index = 0
        for (row in 0 until rows) {
            val remaining = count - index
            val colsThisRow = if (row == rows - 1) remaining.coerceAtMost(cols) else cols
            for (col in 0 until colsThisRow) {
                val tile = tiles[index]
                gridContainer.addView(tile, LayoutParams(0, 0))
                index++
            }
        }
        // Deferred to onLayout via requestLayout — actual pixel placement
        // happens in onLayout below since row/col counts vary per pane count.
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (tiles.isEmpty() || isNarrowScreen()) return
        val count = tiles.size
        val (rows, cols) = gridDimensions(count)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        val rowHeight = h / rows
        var index = 0
        for (row in 0 until rows) {
            val remaining = count - index
            val colsThisRow = if (row == rows - 1) remaining.coerceAtMost(cols) else cols
            if (colsThisRow == 0) continue
            val colWidth = w / colsThisRow
            for (col in 0 until colsThisRow) {
                val tile = tiles.getOrNull(index) ?: continue
                val tileLeft = col * colWidth
                val tileTop = row * rowHeight
                // Bug fix: children added to gridContainer via LayoutParams(0, 0)
                // (an exact-zero measure request) were never re-measured before
                // this manual layout() call — View.layout() sets final bounds
                // but does NOT re-trigger measurement, so every tile's content
                // stayed permanently measured at 0x0 and never rendered,
                // regardless of connection state. Explicitly re-measure each
                // tile to its final size immediately before positioning it.
                tile.measure(
                    MeasureSpec.makeMeasureSpec(colWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
                )
                tile.layout(tileLeft, tileTop, tileLeft + colWidth, tileTop + rowHeight)
                index++
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
}
