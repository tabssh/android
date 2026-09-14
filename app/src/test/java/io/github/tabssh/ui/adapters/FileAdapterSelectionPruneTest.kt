package io.github.tabssh.ui.adapters

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [FileAdapter.pruneSelection].
 *
 * Before the fix, `setLocalFiles`/`setRemoteFiles` replaced the backing list
 * without touching the selection sets, so a refresh after a delete, a rename,
 * or a directory change left selections pointing at rows that were gone — and
 * the next bulk action ran against them.
 */
class FileAdapterSelectionPruneTest {

    private data class Row(val key: String, val size: Long)

    @Test
    fun `selection for a row that left the list is dropped`() {
        val selection = mutableSetOf(Row("a", 1), Row("b", 2))
        FileAdapter.pruneSelection(selection, setOf("a")) { it.key }
        assertEquals(setOf(Row("a", 1)), selection)
    }

    @Test
    fun `a row that came back changed keeps its selection`() {
        // Same file, new size after a save-back: matched on key, so the user's
        // selection survives the refresh.
        val selection = mutableSetOf(Row("a", 1))
        FileAdapter.pruneSelection(selection, setOf("a")) { it.key }
        assertEquals(setOf(Row("a", 1)), selection)
    }

    @Test
    fun `navigating into a directory with no overlap clears the selection`() {
        val selection = mutableSetOf(Row("a", 1), Row("b", 2))
        FileAdapter.pruneSelection(selection, setOf("x", "y")) { it.key }
        assertEquals(emptySet<Row>(), selection)
    }

    @Test
    fun `an empty selection is left alone`() {
        val selection = mutableSetOf<Row>()
        FileAdapter.pruneSelection(selection, setOf("a")) { it.key }
        assertEquals(emptySet<Row>(), selection)
    }
}
