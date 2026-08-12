package io.github.tabssh.ui.tabs

import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the TabManager dual-index-space bug: [TabManager]
 * stores every tab kind (SSH, VNC, console) in one unified list, but
 * [TabManager.getAllTabs] exposes an SSH-only filtered view. Feeding a
 * position from that filtered view into the unified-index APIs
 * ([TabManager.closeTab]/[TabManager.switchToTab]) closes or switches to
 * the wrong tab once a non-SSH tab exists earlier in the list.
 *
 * Repro fixture: unified list [Vnc, Ssh(A), Ssh(B)]. The SSH-only list is
 * [A, B], so B sits at SSH-only index 1 — which is A's position in the
 * unified list. This test proves the two index spaces really do diverge
 * (documenting the bug that made the old code wrong) and that the
 * id-based API ([TabManager.closeTabById]/[TabManager.switchToTabById])
 * closes/switches to the correct tab regardless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabManagerDualIndexSpaceTest {

    private lateinit var tabManager: TabManager

    @Before
    fun setUp() {
        // createTab() launches a per-tab connection-state observer on
        // Dispatchers.Main; the JVM unit-test environment has no Main
        // dispatcher installed by default.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tabManager = TabManager(mock(TabSSHDatabase::class.java))
    }

    @After
    fun tearDown() {
        tabManager.cleanup()
        Dispatchers.resetMain()
    }

    private fun profile(name: String) = ConnectionProfile(
        name = name,
        host = "$name.example.com",
        username = "user"
    )

    @Test
    fun `SSH-only index space diverges from the unified list once a VNC tab exists`() {
        val vnc = requireNotNull(tabManager.createVncTab(vncHost = null, ephemeralDisplayName = "vnc"))
        val tabA = requireNotNull(tabManager.createTab(profile("A")))
        val tabB = requireNotNull(tabManager.createTab(profile("B")))

        // Unified (pager) order: [Vnc, A, B].
        val unified = tabManager.getAllTabsSealed()
        assertEquals(listOf(vnc.tabId, tabA.tabId, tabB.tabId), unified.map { it.tabId })

        // SSH-only order: [A, B] — B sits at SSH-only index 1.
        val sshOnly = tabManager.getAllTabs()
        assertEquals(listOf(tabA.tabId, tabB.tabId), sshOnly.map { it.tabId })
        val bIndexInSshOnlyList = sshOnly.indexOfFirst { it.tabId == tabB.tabId }
        assertEquals(1, bIndexInSshOnlyList)

        // The legacy bug: that same index (1) in the unified list is A, not
        // B. A caller that resolved an index via getAllTabs() and fed it
        // straight into closeTab(index)/switchToTab(index) would act on A.
        assertEquals(tabA.tabId, unified[bIndexInSshOnlyList].tabId)
        assertNotEquals(tabB.tabId, unified[bIndexInSshOnlyList].tabId)
    }

    @Test
    fun `closeTabById closes B and never A, with a Vnc tab preceding both`() {
        tabManager.createVncTab(vncHost = null, ephemeralDisplayName = "vnc")
        val tabA = requireNotNull(tabManager.createTab(profile("A")))
        val tabB = requireNotNull(tabManager.createTab(profile("B")))
        assertEquals(3, tabManager.getTabCount())

        val closed = tabManager.closeTabById(tabB.tabId)

        assertEquals(tabB.tabId, closed?.tabId)
        assertEquals(2, tabManager.getTabCount())
        // A must still be open — this is exactly what the SSH-only-index
        // bug used to break (closing A instead of B).
        val remaining = tabManager.getAllTabsSealed().map { it.tabId }
        assertTrue(tabA.tabId in remaining)
        assertTrue(tabB.tabId !in remaining)
    }

    @Test
    fun `closeTabById on a missing id is a no-op`() {
        tabManager.createVncTab(vncHost = null, ephemeralDisplayName = "vnc")
        requireNotNull(tabManager.createTab(profile("A")))
        val before = tabManager.getTabCount()

        val closed = tabManager.closeTabById("does-not-exist")

        assertNull(closed)
        assertEquals(before, tabManager.getTabCount())
    }

    @Test
    fun `switchToTabById switches to B and never A, with a Vnc tab preceding both`() {
        tabManager.createVncTab(vncHost = null, ephemeralDisplayName = "vnc")
        val tabA = requireNotNull(tabManager.createTab(profile("A")))
        val tabB = requireNotNull(tabManager.createTab(profile("B")))

        val switched = tabManager.switchToTabById(tabB.tabId)

        assertTrue(switched)
        val active = tabManager.getActiveTabSealed()
        assertEquals(tabB.tabId, active?.tabId)
        assertNotEquals(tabA.tabId, active?.tabId)
    }
}
