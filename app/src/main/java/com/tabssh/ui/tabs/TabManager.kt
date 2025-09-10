package io.github.tabssh.ui.tabs

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.terminal.emulator.TerminalEmulator
import io.github.tabssh.terminal.emulator.TerminalManager
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the tabbed interface - the core innovation of TabSSH
 * Provides browser-like tab management with SSH session integration
 */
class TabManager(
    private val terminalManager: TerminalManager,
    private val preferenceManager: PreferenceManager
) {
    private val tabs = mutableListOf<SSHTab>()
    private val tabsById = ConcurrentHashMap<String, SSHTab>()
    
    private val _activeTabIndex = MutableStateFlow(-1)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()
    
    private val _tabList = MutableStateFlow<List<SSHTab>>(emptyList())
    val tabList: StateFlow<List<SSHTab>> = _tabList.asStateFlow()
    
    private val _hasUnreadTabs = MutableStateFlow(false)
    val hasUnreadTabs: StateFlow<Boolean> = _hasUnreadTabs.asStateFlow()
    
    // Tab management settings
    private var maxTabs = 10
    private var confirmTabClose = true
    private var rememberTabOrder = true
    
    // Coroutine scope for tab operations
    private val tabScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Tab event listeners
    private val listeners = mutableListOf<TabManagerListener>()
    
    // Tab history for "reopen closed tab" functionality
    private val closedTabsHistory = mutableListOf<ClosedTabInfo>()
    private val maxClosedTabHistory = 10
    
    init {
        loadSettings()
        Logger.d("TabManager", "Tab manager initialized")
    }
    
    private fun loadSettings() {
        maxTabs = preferenceManager.getMaxTabs()
        confirmTabClose = preferenceManager.isConfirmTabClose()
        
        Logger.d("TabManager", "Tab settings: maxTabs=$maxTabs, confirmClose=$confirmTabClose")
    }
    
    /**
     * Create a new tab with the given connection profile
     */
    suspend fun createTab(
        connectionProfile: ConnectionProfile,
        sshConnection: SSHConnection? = null
    ): SSHTab? {
        
        // Check tab limit
        if (tabs.size >= maxTabs) {
            Logger.w("TabManager", "Cannot create tab: maximum tabs ($maxTabs) reached")
            notifyListeners { onTabLimitReached(maxTabs) }
            return null
        }
        
        return try {
            // Create terminal for this tab
            val terminal = terminalManager.createTerminal(
                terminalId = "tab_${System.currentTimeMillis()}",
                rows = terminalManager.getDefaultSize().first,
                cols = terminalManager.getDefaultSize().second
            )
            
            // Create the SSH tab
            val tab = SSHTab(connectionProfile, terminal).apply {
                tabIndex = tabs.size
            }
            
            // Connect to SSH if connection provided
            sshConnection?.let { connection ->
                val connected = tab.connect(connection)
                if (!connected) {
                    Logger.e("TabManager", "Failed to connect tab to SSH")
                    terminal.cleanup()
                    return null
                }
            }
            
            // Add tab to collections
            tabs.add(tab)
            tabsById[tab.tabId] = tab
            
            // Set as active if it's the first tab
            if (tabs.size == 1) {
                setActiveTab(0)
            }
            
            // Update state flows
            updateTabState()
            
            // Setup tab monitoring
            setupTabMonitoring(tab)
            
            Logger.i("TabManager", "Created tab: ${connectionProfile.getDisplayName()}")
            notifyListeners { onTabCreated(tab) }
            
            tab
            
        } catch (e: Exception) {
            Logger.e("TabManager", "Error creating tab for ${connectionProfile.getDisplayName()}", e)
            null
        }
    }
    
    private fun setupTabMonitoring(tab: SSHTab) {
        // Monitor tab state changes
        tabScope.launch {
            tab.hasUnreadOutput.collect { hasUnread ->
                if (hasUnread) {
                    checkUnreadTabs()
                }
            }
        }
        
        tabScope.launch {
            tab.connectionState.collect { state ->
                notifyListeners { onTabConnectionStateChanged(tab, state) }
            }
        }
    }
    
    /**
     * Close a tab by index
     */
    suspend fun closeTab(index: Int, force: Boolean = false): Boolean {
        if (index !in tabs.indices) {
            Logger.w("TabManager", "Cannot close tab: invalid index $index")
            return false
        }
        
        val tab = tabs[index]
        
        // Check if tab can be closed safely
        if (!force && !tab.canClose() && confirmTabClose) {
            Logger.d("TabManager", "Tab close requires confirmation: ${tab.profile.getDisplayName()}")
            notifyListeners { onTabCloseConfirmationRequired(tab, index) }
            return false
        }
        
        return closeTabInternal(index)
    }
    
    private suspend fun closeTabInternal(index: Int): Boolean {
        val tab = tabs[index]
        
        Logger.d("TabManager", "Closing tab: ${tab.profile.getDisplayName()}")
        
        // Save tab info for potential reopening
        val closedTabInfo = ClosedTabInfo(
            connectionProfile = tab.profile,
            tabTitle = tab.getDisplayTitle(),
            closedAt = System.currentTimeMillis()
        )
        
        closedTabsHistory.add(0, closedTabInfo)
        if (closedTabsHistory.size > maxClosedTabHistory) {
            closedTabsHistory.removeAt(maxClosedTabHistory)
        }
        
        // Cleanup tab resources
        tab.cleanup()
        
        // Remove from collections
        tabs.removeAt(index)
        tabsById.remove(tab.tabId)
        
        // Update remaining tab indices
        for (i in index until tabs.size) {
            tabs[i].tabIndex = i
        }
        
        // Handle active tab change
        if (tabs.isEmpty()) {
            _activeTabIndex.value = -1
        } else if (index <= _activeTabIndex.value) {
            val newActiveIndex = when {
                tabs.isEmpty() -> -1
                index == tabs.size -> tabs.size - 1 // Was last tab
                else -> index // Activate tab that moved into this position
            }
            setActiveTab(newActiveIndex)
        }
        
        updateTabState()
        
        Logger.i("TabManager", "Closed tab: ${tab.profile.getDisplayName()}")
        notifyListeners { onTabClosed(tab, index) }
        
        return true
    }
    
    /**
     * Close all tabs
     */
    suspend fun closeAllTabs() {
        Logger.d("TabManager", "Closing all tabs (${tabs.size} tabs)")
        
        // Close tabs from end to beginning to avoid index issues
        val tabCount = tabs.size
        for (i in tabCount - 1 downTo 0) {
            closeTabInternal(i)
        }
        
        Logger.i("TabManager", "Closed all tabs")
        notifyListeners { onAllTabsClosed() }
    }
    
    /**
     * Set active tab by index
     */
    fun setActiveTab(index: Int) {
        if (index < -1 || index >= tabs.size) {
            Logger.w("TabManager", "Cannot set active tab: invalid index $index")
            return
        }
        
        // Deactivate current tab
        if (_activeTabIndex.value in tabs.indices) {
            tabs[_activeTabIndex.value].deactivate()
        }
        
        _activeTabIndex.value = index
        
        // Activate new tab
        if (index in tabs.indices) {
            tabs[index].activate()
            Logger.d("TabManager", "Set active tab to: ${tabs[index].profile.getDisplayName()}")
        }
        
        checkUnreadTabs()
        notifyListeners { onActiveTabChanged(index) }
    }
    
    /**
     * Get active tab
     */
    fun getActiveTab(): SSHTab? {
        val index = _activeTabIndex.value
        return if (index in tabs.indices) tabs[index] else null
    }
    
    /**
     * Get tab by ID
     */
    fun getTabById(tabId: String): SSHTab? {
        return tabsById[tabId]
    }
    
    /**
     * Get tab by index
     */
    fun getTab(index: Int): SSHTab? {
        return if (index in tabs.indices) tabs[index] else null
    }
    
    /**
     * Get all tabs
     */
    fun getAllTabs(): List<SSHTab> = tabs.toList()
    
    /**
     * Get tab count
     */
    fun getTabCount(): Int = tabs.size
    
    /**
     * Move tab to new position (drag and drop)
     */
    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices || fromIndex == toIndex) {
            Logger.w("TabManager", "Cannot move tab: invalid indices $fromIndex -> $toIndex")
            return
        }
        
        Logger.d("TabManager", "Moving tab from $fromIndex to $toIndex")
        
        val tab = tabs.removeAt(fromIndex)
        tabs.add(toIndex, tab)
        
        // Update tab indices
        for (i in tabs.indices) {
            tabs[i].tabIndex = i
        }
        
        // Update active tab index if needed
        val activeIndex = _activeTabIndex.value
        _activeTabIndex.value = when (activeIndex) {
            fromIndex -> toIndex
            in (minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex)) -> {
                if (fromIndex < toIndex) activeIndex - 1 else activeIndex + 1
            }
            else -> activeIndex
        }
        
        updateTabState()
        notifyListeners { onTabMoved(fromIndex, toIndex) }
    }
    
    /**
     * Switch to next tab (Ctrl+Tab)
     */
    fun switchToNextTab() {
        if (tabs.isEmpty()) return
        
        val currentIndex = _activeTabIndex.value
        val nextIndex = if (currentIndex >= tabs.size - 1) 0 else currentIndex + 1
        setActiveTab(nextIndex)
    }
    
    /**
     * Switch to previous tab (Ctrl+Shift+Tab)
     */
    fun switchToPreviousTab() {
        if (tabs.isEmpty()) return
        
        val currentIndex = _activeTabIndex.value
        val prevIndex = if (currentIndex <= 0) tabs.size - 1 else currentIndex - 1
        setActiveTab(prevIndex)
    }
    
    /**
     * Switch to specific tab number (Ctrl+1-9)
     */
    fun switchToTabNumber(number: Int) {
        val index = number - 1 // Convert to 0-based index
        if (index in tabs.indices) {
            setActiveTab(index)
        }
    }
    
    /**
     * Reopen last closed tab (Ctrl+Shift+T)
     */
    suspend fun reopenClosedTab(): SSHTab? {
        if (closedTabsHistory.isEmpty()) {
            Logger.d("TabManager", "No closed tabs to reopen")
            return null
        }
        
        val closedTab = closedTabsHistory.removeAt(0)
        Logger.d("TabManager", "Reopening closed tab: ${closedTab.connectionProfile.getDisplayName()}")
        
        // Create new tab with same profile
        return createTab(closedTab.connectionProfile)
    }
    
    /**
     * Duplicate current tab
     */
    suspend fun duplicateActiveTab(): SSHTab? {
        val activeTab = getActiveTab() ?: return null
        
        Logger.d("TabManager", "Duplicating active tab: ${activeTab.profile.getDisplayName()}")
        return createTab(activeTab.profile)
    }
    
    /**
     * Get tab statistics
     */
    fun getTabStatistics(): TabManagerStats {
        val connectedTabs = tabs.count { it.isConnected() }
        val unreadTabs = tabs.count { it.hasUnreadOutput.value }
        val totalSessions = tabs.size
        
        return TabManagerStats(
            totalTabs = totalSessions,
            connectedTabs = connectedTabs,
            unreadTabs = unreadTabs,
            activeTabIndex = _activeTabIndex.value,
            maxTabs = maxTabs,
            closedTabsInHistory = closedTabsHistory.size
        )
    }
    
    private fun updateTabState() {
        _tabList.value = tabs.toList()
        checkUnreadTabs()
    }
    
    private fun checkUnreadTabs() {
        val hasUnread = tabs.any { tab ->
            tab.hasUnreadOutput.value && !tab.isActive.value
        }
        _hasUnreadTabs.value = hasUnread
    }
    
    /**
     * Handle keyboard shortcuts
     */
    fun handleKeyboardShortcut(keyCode: Int, isCtrl: Boolean, isShift: Boolean, isAlt: Boolean) {
        if (!isCtrl) return
        
        when (keyCode) {
            // Ctrl+T - New tab (handled by activity)
            // Ctrl+W - Close tab
            87 -> { // W key
                tabScope.launch {
                    val activeIndex = _activeTabIndex.value
                    if (activeIndex in tabs.indices) {
                        closeTab(activeIndex)
                    }
                }
            }
            // Ctrl+Tab - Next tab
            61 -> { // Tab key
                if (isShift) {
                    switchToPreviousTab()
                } else {
                    switchToNextTab()
                }
            }
            // Ctrl+Shift+T - Reopen closed tab
            84 -> { // T key
                if (isShift) {
                    tabScope.launch {
                        reopenClosedTab()
                    }
                }
            }
            // Ctrl+1-9 - Switch to tab number
            in 8..16 -> { // Number keys 1-9
                switchToTabNumber(keyCode - 7)
            }
        }
    }
    
    /**
     * Save tab session state for restoration
     */
    suspend fun saveTabState() {
        // Implementation would save tab state to database
        // for restoration after app restart
        Logger.d("TabManager", "Saving tab state (${tabs.size} tabs)")
    }
    
    /**
     * Restore tab session state
     */
    suspend fun restoreTabState() {
        // Implementation would restore tabs from database
        Logger.d("TabManager", "Restoring tab state")
    }
    
    /**
     * Apply updated settings
     */
    fun applySettings() {
        loadSettings()
        
        // Apply new settings to existing tabs if needed
        Logger.d("TabManager", "Applied updated settings")
    }
    
    /**
     * Add tab manager listener
     */
    fun addListener(listener: TabManagerListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove tab manager listener
     */
    fun removeListener(listener: TabManagerListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: TabManagerListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup all tabs and resources
     */
    fun cleanup() {
        Logger.d("TabManager", "Cleaning up tab manager")
        
        tabScope.launch {
            closeAllTabs()
        }
        
        tabScope.cancel()
        listeners.clear()
        closedTabsHistory.clear()
        
        Logger.i("TabManager", "Tab manager cleanup complete")
    }
}

/**
 * Information about a closed tab for reopening
 */
data class ClosedTabInfo(
    val connectionProfile: ConnectionProfile,
    val tabTitle: String,
    val closedAt: Long
)

/**
 * Tab manager statistics
 */
data class TabManagerStats(
    val totalTabs: Int,
    val connectedTabs: Int,
    val unreadTabs: Int,
    val activeTabIndex: Int,
    val maxTabs: Int,
    val closedTabsInHistory: Int
)

/**
 * Interface for tab manager events
 */
interface TabManagerListener {
    fun onTabCreated(tab: SSHTab) {}
    fun onTabClosed(tab: SSHTab, index: Int) {}
    fun onTabMoved(fromIndex: Int, toIndex: Int) {}
    fun onActiveTabChanged(index: Int) {}
    fun onTabConnectionStateChanged(tab: SSHTab, state: com.tabssh.ssh.connection.ConnectionState) {}
    fun onTabCloseConfirmationRequired(tab: SSHTab, index: Int) {}
    fun onTabLimitReached(maxTabs: Int) {}
    fun onAllTabsClosed() {}
}