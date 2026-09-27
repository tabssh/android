package io.github.tabssh.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [PaneWindow.windowId] is the key the pane view layer binds each
 * `TerminalView` to, so its stability is load-bearing: a view may only ever
 * be re-issued for the same window. These cover the two ways a window's other
 * fields change underneath it — the grid renumbering on close, and the
 * session swap on reconnect — plus the case that motivated adding the field at
 * all (one host open in two windows).
 */
class PaneWindowIdentityTest {

    private fun window(hostId: String, gridPosition: Int) =
        PaneWindow(hostId = hostId, sshTab = null, gridPosition = gridPosition)

    @Test
    fun `same host in two windows gets distinct ids`() {
        // The case that made hostId unusable as a key: one host open twice
        // with different working directories.
        val a = window("host-1", 0)
        val b = window("host-1", 1)
        assertNotEquals(a.windowId, b.windowId)
    }

    @Test
    fun `windowId survives copy with a new session`() {
        // reconnectPaneWindow -> replaceWindowSession replaces sshTab but must
        // keep the same window, so the view is rebuilt rather than rebound.
        val original = window("host-1", 0)
        val reconnected = original.copy(sshTab = null)
        assertEquals(original.windowId, reconnected.windowId)
    }

    @Test
    fun `windowId survives grid renumbering after a close`() {
        // closeWindow renumbers the survivors' gridPosition. The window that
        // slides from index 1 to index 0 is the same window.
        val surviving = window("host-1", 1)
        val renumbered = surviving.copy(gridPosition = 0)
        assertEquals(surviving.windowId, renumbered.windowId)
    }

    @Test
    fun `every constructed window is unique without an explicit id`() {
        val windows = (1..50).map { window("host-$it", it) }
        assertEquals(50, windows.map { it.windowId }.toSet().size)
    }
}
