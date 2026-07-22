package io.github.tabssh.ui.tabs

import android.view.KeyEvent
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Browser-style tab management for SSH (and, as of the VNC-tab-swipe
 * integration, VNC) sessions. Core innovation of TabSSH.
 *
 * VNC-tab-swipe integration step 3 (AI.md §11.7.2 / TODO.AI.md): the
 * backing store is now the sealed [Tab] type so a future [VncTab] can live
 * in the same ordered list as [SSHTab]s. This step is deliberately
 * additive — every pre-existing accessor (`getActiveTab()`, `getAllTabs()`,
 * `getTab()`, `tabsFlow`, `TabManagerListener`) keeps its original
 * SSH-only signature and behavior, filtered from the sealed list, so none
 * of TabTerminalActivity/SessionPersistenceManager/ConnectionLauncher/
 * TaskerWorker need to change. New sealed-aware accessors
 * (`getAllTabsSealed()`, `getActiveTabSealed()`, `allTabsFlow`,
 * `createVncTab()`) are added alongside for steps 4-6 to adopt
 * incrementally as VNC rendering/gating actually lands.
 */
class TabManager(private val database: TabSSHDatabase, private val maxTabs: Int = 10) {

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0

    /** SSH-only view of [tabs], preserving the pre-step-3 iteration order. */
    private fun sshTabs(): List<SSHTab> = tabs.filterIsInstance<Tab.Ssh>().map { it.sshTab }

    // Live snapshot of [tabs] for cross-screen observers (e.g. the
    // Connections-tab "Active Sessions" strip). Re-emitted every time
    // a tab is created/closed/moved. Title-changes already arrive via
    // each SSHTab's own `title` Flow — observers should collect both
    // this and the per-tab title flows.
    //
    // SSH-only for backward compatibility (ConnectionsFragment's "Active
    // Sessions" strip is SSH-only today) — see [allTabsFlow] for the
    // sealed-type equivalent.
    private val _tabsFlow = MutableStateFlow<List<SSHTab>>(emptyList())
    val tabsFlow: StateFlow<List<SSHTab>> = _tabsFlow.asStateFlow()

    // Sealed-type snapshot of [tabs], for VNC-aware consumers (steps 4-6:
    // TerminalPagerAdapter, swipe gating, entry-point consolidation).
    private val _allTabsFlow = MutableStateFlow<List<Tab>>(emptyList())
    val allTabsFlow: StateFlow<List<Tab>> = _allTabsFlow.asStateFlow()

    private fun publishTabs() {
        _tabsFlow.value = sshTabs()
        _allTabsFlow.value = tabs.toList()
    }

    // Per-tab observer jobs that bridge SSHTab.connectionState into the
    // TabManagerListener.onTabConnectionStateChanged callback. Without this
    // the activity never sees DISCONNECTED transitions and the auto-close
    // path in TabTerminalActivity.updateTabIcon() never fires (Issue #50).
    private val tabObserverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tabObservers = mutableMapOf<String, Job>()

    /**
     * Get active tab index
     */
    fun getActiveTabIndex(): Int = activeTabIndex
    // CopyOnWriteArrayList: add/remove happens on UI thread; the four
    // notify* helpers may be called from whichever thread published a
    // state change — matching the pattern used by SSHSessionManager,
    // SSHConnection, PortForwardingManager, and TerminalEmulator.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<TabManagerListener>()

    /**
     * Create new tab with connection profile
     * @param cursorStyle 0=block, 1=underline, 2=bar (I-beam)
     */
    fun createTab(profile: ConnectionProfile, cursorStyle: Int = 2): SSHTab? {
        if (tabs.size >= maxTabs) {
            Logger.w("TabManager", "Maximum tabs reached: $maxTabs")
            return null
        }

        // Create Termux terminal emulator bridge for the new tab
        val termuxBridge = TermuxBridge(
            columns = 80,
            rows = 24,
            transcriptRows = 2000,
            cursorStyle = cursorStyle
        )

        val tab = SSHTab(
            profile = profile,
            termuxBridge = termuxBridge
        )

        tabs.add(Tab.Ssh(tab))
        activeTabIndex = tabs.size - 1

        // Observe this tab's connection state so the activity learns about
        // DISCONNECTED transitions (Issue #50). Without this, exiting the
        // shell ends the SSH read loop but the UI hangs on the dead tab.
        //
        // CRITICAL guard against the auto-close-on-create regression: a
        // brand-new SSHTab starts at DISCONNECTED. The previous attempt
        // (`drop(1).distinctUntilChanged()`) was supposed to swallow the
        // StateFlow replay but the user still hit the auto-close 60 ms
        // after createTab — most likely because the StateFlow re-emits
        // DISCONNECTED at a moment our `drop(1)` couldn't reach (e.g. the
        // launch's coroutine resuming after another DISCONNECTED was set).
        //
        // Bullet-proof fix: track whether the tab has EVER been CONNECTED.
        // DISCONNECTED is only forwarded to the activity (where it triggers
        // auto-close) if the tab actually reached CONNECTED at some point.
        // Other states (CONNECTING, AUTHENTICATING, ERROR) always forward.
        tabObservers[tab.tabId] = tabObserverScope.launch {
            var hasBeenConnected = false
            // StateFlow is already distinct so no distinctUntilChanged()
            // (Kotlin coroutines flags it as a deprecated no-op).
            tab.connectionState.collect { state ->
                    when (state) {
                        io.github.tabssh.ssh.connection.ConnectionState.CONNECTED -> {
                            hasBeenConnected = true
                            notifyTabConnectionStateChanged(tab, state)
                        }
                        io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED -> {
                            if (hasBeenConnected) {
                                notifyTabConnectionStateChanged(tab, state)
                            }
                            // else: initial replay or pre-connect; suppress.
                        }
                        else -> {
                            notifyTabConnectionStateChanged(tab, state)
                        }
                    }
                }
        }

        Logger.d("TabManager", "Created new tab: ${profile.name}")
        publishTabs()
        notifyTabCreated(tab)
        return tab
    }

    /**
     * Create a new VNC tab (VNC-tab-swipe integration step 3). Unlike
     * [createTab] this does not wire a [TabManagerListener] observer yet —
     * that interface is still SSH-only (see class doc); steps 4-6 add
     * VNC-aware observation once TerminalPagerAdapter/TabTerminalActivity
     * actually consume [allTabsFlow].
     */
    fun createVncTab(vncHost: io.github.tabssh.storage.database.entities.VncHost?, ephemeralDisplayName: String? = null): VncTab? {
        if (tabs.size >= maxTabs) {
            Logger.w("TabManager", "Maximum tabs reached: $maxTabs")
            return null
        }

        val tab = VncTab(vncHost = vncHost, ephemeralDisplayName = ephemeralDisplayName)
        tabs.add(Tab.Vnc(tab))
        activeTabIndex = tabs.size - 1

        Logger.d("TabManager", "Created new VNC tab: ${tab.getDisplayTitle()}")
        publishTabs()
        return tab
    }

    /**
     * Close tab by index
     */
    fun closeTab(index: Int) {
        if (index in 0 until tabs.size) {
            val entry = tabs[index]
            // cleanup() = disconnect() + termuxBridge.cleanup() + connectionScope.cancel()
            // (SSHTab) or rfbClient.stop() + VncBackgroundSessionStore.discard() (VncTab).
            // Previously only disconnect() was called, leaking the tab's
            // SupervisorJob scope and TermuxBridge resources for the remainder
            // of the process lifetime.
            when (entry) {
                is Tab.Ssh -> entry.sshTab.cleanup()
                is Tab.Vnc -> entry.vncTab.cleanup()
            }
            tabs.removeAt(index)
            tabObservers.remove(entry.tabId)?.cancel()

            // Adjust active tab index
            if (activeTabIndex >= index && activeTabIndex > 0) {
                activeTabIndex--
            }

            Logger.d("TabManager", "Closed tab: ${entry.tabId}")
            publishTabs()
            if (entry is Tab.Ssh) {
                notifyTabClosed(entry.sshTab, index)
            }
        }
    }

    /**
     * Switch to tab by index
     */
    fun switchToTab(index: Int) {
        if (index in 0 until tabs.size && index != activeTabIndex) {
            activeTabIndex = index
            val newTab = getActiveTabSealed()

            Logger.d("TabManager", "Switched to tab: ${newTab?.tabId}")
            notifyActiveTabChanged(index)
        }
    }

    /**
     * Get active tab. SSH-only — returns `null` if the active tab is a VNC
     * tab. See [getActiveTabSealed] for the VNC-aware equivalent.
     */
    fun getActiveTab(): SSHTab? = (getActiveTabSealed() as? Tab.Ssh)?.sshTab

    /** Get active tab, whichever kind it is (VNC-tab-swipe integration step 3). */
    fun getActiveTabSealed(): Tab? {
        return if (activeTabIndex in 0 until tabs.size) {
            tabs[activeTabIndex]
        } else null
    }

    /**
     * Get all tabs. SSH-only, in unified-list order.
     *
     * CAVEAT: once VNC tabs are actually created (step 6), the index of a
     * tab in this filtered list no longer matches its index in the
     * unified `ViewPager2`/`tabs` order — callers that derive a
     * `ViewPager2`/`switchToTab`/`getTab`/`closeTab` index from
     * `getAllTabs().indexOfFirst { ... }` must migrate to
     * [getAllTabsSealed] first. Harmless today: `createVncTab` has no
     * caller yet, so `tabs` only ever contains `Tab.Ssh` entries and the
     * two index spaces are identical.
     */
    fun getAllTabs(): List<SSHTab> = sshTabs()

    /** Get all tabs, whichever kind they are, in unified-list (pager) order. */
    fun getAllTabsSealed(): List<Tab> = tabs.toList()

    /**
     * Handle keyboard shortcuts (Tmux-style)
     */
    fun handleKeyboardShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val isCtrlPressed = event.isCtrlPressed

        return when {
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_T -> {
                // Ctrl+T - New tab (would need connection profile)
                true
            }
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_W -> {
                // Ctrl+W - Close current tab
                closeTab(activeTabIndex)
                true
            }
            isCtrlPressed && keyCode == KeyEvent.KEYCODE_TAB -> {
                // Ctrl+Tab - Next tab. Guard against empty tab list (modulo would
                // throw ArithmeticException if a shortcut fires after the last
                // tab closes but the consumer hasn't been removed yet).
                if (tabs.isNotEmpty()) {
                    val nextIndex = (activeTabIndex + 1) % tabs.size
                    switchToTab(nextIndex)
                }
                true
            }
            isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_TAB -> {
                // Ctrl+Shift+Tab - Previous tab. Same empty-list guard.
                if (tabs.isNotEmpty()) {
                    val prevIndex = if (activeTabIndex == 0) tabs.size - 1 else activeTabIndex - 1
                    switchToTab(prevIndex)
                }
                true
            }
            isCtrlPressed && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9 -> {
                // Ctrl+1-9 - Switch to tab number
                val tabIndex = keyCode - KeyEvent.KEYCODE_1
                switchToTab(tabIndex)
                true
            }
            else -> false
        }
    }

    /**
     * Add tab listener
     */
    fun addListener(listener: TabManagerListener) {
        listeners.add(listener)
    }

    /**
     * Remove tab listener
     */
    fun removeListener(listener: TabManagerListener) {
        listeners.remove(listener)
    }

    // Notification methods
    private fun notifyTabCreated(tab: SSHTab) {
        listeners.forEach { it.onTabCreated(tab) }
    }

    private fun notifyTabClosed(tab: SSHTab, index: Int) {
        listeners.forEach { it.onTabClosed(tab, index) }
    }

    private fun notifyActiveTabChanged(index: Int) {
        listeners.forEach { it.onActiveTabChanged(index) }
    }

    private fun notifyTabConnectionStateChanged(tab: SSHTab, state: io.github.tabssh.ssh.connection.ConnectionState) {
        listeners.forEach { it.onTabConnectionStateChanged(tab, state) }
    }

    /**
     * Get tab count
     */
    fun getTabCount(): Int = tabs.size

    /**
     * Move tab position
     */
    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until tabs.size && toIndex in 0 until tabs.size) {
            val tab = tabs.removeAt(fromIndex)
            tabs.add(toIndex, tab)

            // Adjust active index
            when {
                activeTabIndex == fromIndex -> activeTabIndex = toIndex
                activeTabIndex in (minOf(fromIndex, toIndex) + 1)..maxOf(fromIndex, toIndex) -> {
                    activeTabIndex += if (fromIndex < toIndex) -1 else 1
                }
            }

            Logger.d("TabManager", "Moved tab from $fromIndex to $toIndex")
            publishTabs()
        }
    }

    /**
     * Get tab by index. SSH-only — returns `null` if the tab at that index
     * is a VNC tab. See [getTabSealed] for the VNC-aware equivalent.
     */
    fun getTab(index: Int): SSHTab? = (getTabSealed(index) as? Tab.Ssh)?.sshTab

    /** Get tab by index, whichever kind it is. */
    fun getTabSealed(index: Int): Tab? {
        return if (index in 0 until tabs.size) tabs[index] else null
    }

    /**
     * Set active tab
     */
    fun setActiveTab(index: Int) {
        switchToTab(index)
    }

    /**
     * Switch to previous tab
     */
    fun switchToPreviousTab() {
        if (tabs.isEmpty()) return
        val prevIndex = if (activeTabIndex == 0) tabs.size - 1 else activeTabIndex - 1
        switchToTab(prevIndex)
    }

    /**
     * Switch to next tab
     */
    fun switchToNextTab() {
        if (tabs.isEmpty()) return
        val nextIndex = (activeTabIndex + 1) % tabs.size
        switchToTab(nextIndex)
    }

    /**
     * Switch to tab by number (1-based)
     */
    fun switchToTabNumber(number: Int) {
        val index = number - 1
        if (index in 0 until tabs.size) {
            switchToTab(index)
        }
    }

    /**
     * Close all tabs
     */
    fun closeAllTabs() {
        tabs.indices.reversed().forEach { index ->
            closeTab(index)
        }
    }

    /**
     * Persist the current tab list to the database so it can be restored
     * after a process death. Called from onPause() on Dispatchers.IO.
     * Upserts each live tab and removes any stale sessions for tabs that
     * are no longer open.
     */
    suspend fun saveTabState() {
        Logger.d("TabManager", "Saving tab states to database")
        val dao = database.tabSessionDao()
        val now = System.currentTimeMillis()
        val liveIds = mutableSetOf<String>()

        val connDao = database.connectionDao()
        // Snapshot: tabs is a plain ArrayList mutated on the main thread.
        // saveTabState runs on Dispatchers.IO; iterating the live list
        // races with addTab/closeTab and causes ConcurrentModificationException.
        // SSH-only for now — VncTab has no TabSession row/restore path yet
        // (that's step 6's entry-point consolidation). Index is taken from
        // the full unified list so ordering stays stable once VNC tabs are
        // interspersed, rather than renumbering around them.
        val snapshot = tabs.toList()
        snapshot.forEachIndexed { index, entry ->
            val tab = (entry as? Tab.Ssh)?.sshTab ?: return@forEachIndexed
            liveIds.add(tab.tabId)

            // Guard: the TabSession FK requires the connection profile to exist
            // in the `connections` table. Ephemeral / quick-connect profiles are
            // created in-memory and never persisted, so inserting a session for
            // them would throw SQLITE_CONSTRAINT_FOREIGNKEY. Skip those tabs.
            val profileInDb = connDao.getConnectionById(tab.profile.id)
            if (profileInDb == null) {
                Logger.d("TabManager", "Skipping session save for ephemeral profile: ${tab.getDisplayTitle()}")
                return@forEachIndexed
            }

            val tabStats = tab.getConnectionStats()
            val tabSession = io.github.tabssh.storage.database.entities.TabSession(
                sessionId = tab.tabId,
                tabId = tab.tabId,
                connectionId = tab.profile.id,
                tabOrder = index,
                isActive = tab.isActive.value,
                terminalRows = tabStats.terminalRows,
                terminalCols = tabStats.terminalCols,
                terminalContent = tab.getTerminalContent(),
                cursorRow = tabStats.terminalRows / 2,
                cursorCol = 0,
                title = tab.getDisplayTitle(),
                lastActivity = tabStats.lastActivity,
                createdAt = now,
                environmentVars = "",
                workingDirectory = ""
            )
            val existing = dao.getSessionByTabId(tab.tabId)
            if (existing != null) {
                dao.updateSession(tabSession)
            } else {
                dao.insertSession(tabSession)
            }
            Logger.d("TabManager", "Saved tab: ${tab.getDisplayTitle()} (order=$index)")
        }

        // Remove sessions for tabs that are no longer open.
        val allSaved = dao.getAllTabs()
        allSaved.filter { it.tabId !in liveIds }.forEach { stale ->
            dao.deleteSession(stale)
        }

        Logger.i("TabManager", "Tab state persistence: ${snapshot.size} tab(s) saved")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("TabManager", "Cleaning up tab manager")
        tabObservers.values.forEach { it.cancel() }
        tabObservers.clear()
        // cleanup() also tears down each tab's connectionScope and the
        // Termux bridge (SSHTab) or rfbClient + parked session (VncTab) —
        // disconnect() alone leaked both.
        tabs.forEach { entry ->
            when (entry) {
                is Tab.Ssh -> entry.sshTab.cleanup()
                is Tab.Vnc -> entry.vncTab.cleanup()
            }
        }
        tabs.clear()
        listeners.clear()
        // Publish so the Connections-tab "Active Sessions" strip — and any
        // other tabsFlow consumer — gets the empty-list emission and can
        // hide itself. Otherwise the strip would show stale entries until
        // a future `createTab` / `closeTab` triggers a publish.
        publishTabs()
    }
}

/**
 * Tab state enumeration
 */
enum class TabState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}


/**
 * Tab manager event listener
 */
interface TabManagerListener {
    fun onTabCreated(tab: SSHTab) {}
    fun onTabClosed(tab: SSHTab, index: Int) {}
    fun onActiveTabChanged(index: Int) {}
    fun onTabConnectionStateChanged(tab: SSHTab, state: io.github.tabssh.ssh.connection.ConnectionState) {}
}
