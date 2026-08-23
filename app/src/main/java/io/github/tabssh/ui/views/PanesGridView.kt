package io.github.tabssh.ui.views

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.LinearLayout

/**
 * Tiled grid ViewGroup for a Panes tab (up to 6 [TerminalView]s in one
 * tab-strip slot). Computes a row/column layout from the pane count,
 * auto-stacks to a single scrollable column below `screenWidthDp < 600`
 * (matching the plan's narrow-screen requirement), and highlights the
 * focused tile's border on tap.
 *
 * Reordering/resizing beyond the auto grid is intentionally out of scope
 * for this cut — panes are laid out in [PaneEntry.gridPosition] order in a
 * fixed near-square grid; see TODO.AI.md for drag-to-resize/reorder as a
 * follow-up.
 */
class PanesGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val FOCUS_BORDER_COLOR = 0xFF4CAF50.toInt()
        private const val UNFOCUSED_BORDER_COLOR = Color.TRANSPARENT
        private const val BORDER_WIDTH_DP = 2
        private const val NARROW_WIDTH_DP = 600
    }

    /** One tile: the border frame wrapping a caller-supplied content view. */
    private class Tile(context: Context, val content: android.view.View) : FrameLayout(context) {
        init {
            addView(
                content,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            setPadding(borderPx(), borderPx(), borderPx(), borderPx())
            setBackgroundColor(UNFOCUSED_BORDER_COLOR)
        }

        fun setFocused(focused: Boolean) {
            setBackgroundColor(if (focused) FOCUS_BORDER_COLOR else UNFOCUSED_BORDER_COLOR)
        }

        private fun borderPx(): Int =
            (BORDER_WIDTH_DP * resources.displayMetrics.density).toInt()
    }

    private val stackScroll = ScrollView(context)
    private val stackContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val gridContainer = FrameLayout(context)

    private var tiles: List<Tile> = emptyList()
    private var onTileClicked: ((Int) -> Unit)? = null

    init {
        addView(gridContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stackScroll.addView(
            stackContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    /** Set the pane content views (in grid order) and the focus-tap callback. */
    fun setContents(contents: List<android.view.View>, onTileClicked: (Int) -> Unit) {
        this.onTileClicked = onTileClicked
        gridContainer.removeAllViews()
        stackContainer.removeAllViews()
        removeView(stackScroll)

        tiles = contents.mapIndexed { index, content ->
            (content.parent as? ViewGroup)?.removeView(content)
            Tile(context, content).also { tile ->
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
                        (240 * resources.displayMetrics.density).toInt()
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
     * 1→1x1, 2→1x2, 3→1x3, 4→2x2, 5→2x3(last row 2), 6→2x3.
     */
    private fun gridDimensions(count: Int): Pair<Int, Int> = when (count) {
        1 -> 1 to 1
        2 -> 1 to 2
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
                tile.layout(tileLeft, tileTop, tileLeft + colWidth, tileTop + rowHeight)
                index++
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
}
