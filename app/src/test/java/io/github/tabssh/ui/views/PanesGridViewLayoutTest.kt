package io.github.tabssh.ui.views

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PanesGridView.setContents] keeps existing tiles when the caller re-supplies
 * the same views, so the grid can be re-laid-out without tearing the panes
 * down. That reuse path is what made the layout idempotent — and it is where a
 * re-add of a still-parented view would throw from ViewGroup.addView.
 */
@RunWith(RobolectricTestRunner::class)
class PanesGridViewLayoutTest {

    // PanesGridView.Tile builds MaterialButtons, which require a
    // Theme.MaterialComponents descendant — Robolectric's bare application
    // context does not carry the app theme.
    private val context: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.Theme_TabSSH
    )

    private fun view() = View(context)

    private fun grid(vararg tiles: View) = PanesGridView(context).apply {
        setContents(
            contents = tiles.toList(),
            onTileClicked = {},
            onTileClosed = {},
            onTileReconnect = {}
        )
    }


    /**
     * True when [candidate] is attached somewhere beneath [root]. Counting the
     * grid's own `childCount` proves nothing — that is the container, not the
     * tiles — so the tiles are located by walking each view's parent chain.
     */
    private fun isAttachedUnder(candidate: View, root: ViewGroup): Boolean {
        var node: ViewParent? = candidate.parent
        while (node != null) {
            if (node === root) return true
            node = (node as? View)?.parent
        }
        return false
    }

    @Test
    fun `re-supplying the same views re-lays out without throwing`() {
        // The regression: the reuse path skipped the teardown, then re-added
        // tiles that were still parented, crashing in ViewGroup.addView.
        val a = view()
        val b = view()
        val gridView = grid(a, b)

        gridView.setContents(contents = listOf(a, b), onTileClicked = {}, onTileClosed = {}, onTileReconnect = {})

        assertTrue("both tiles should be attached after re-layout", isAttachedUnder(a, gridView) && isAttachedUnder(b, gridView))
    }

    @Test
    fun `repeated identical re-layout stays stable`() {
        val a = view()
        val b = view()
        val gridView = grid(a, b)

        repeat(5) { gridView.setContents(contents = listOf(a, b), onTileClicked = {}, onTileClosed = {}, onTileReconnect = {}) }

        assertTrue(isAttachedUnder(a, gridView) && isAttachedUnder(b, gridView))
    }

    @Test
    fun `swapping a view in place replaces that tile`() {
        val a = view()
        val b = view()
        val replacement = view()
        val gridView = grid(a, b)

        gridView.setContents(contents = listOf(a, replacement), onTileClicked = {}, onTileClosed = {}, onTileReconnect = {})

        // The dropped view must be gone, not merely absent from a map while
        // still parented inside its old tile.
        assertTrue(isAttachedUnder(a, gridView))
        assertFalse("replaced view should have been detached", isAttachedUnder(b, gridView))
    }

    @Test
    fun `re-laying out after a split-direction change keeps both tiles`() {
        val a = view()
        val b = view()
        val gridView = grid(a, b)

        gridView.setContents(
            contents = listOf(a, b),
            splitDirection = "vertical",
            onTileClicked = {},
            onTileClosed = {},
            onTileReconnect = {}
        )

        // Both tiles must survive re-layout — the reuse path exists so the
        // TerminalView input connection isn't dropped by a rebuild.
        assertTrue(isAttachedUnder(a, gridView) && isAttachedUnder(b, gridView))
    }
}
