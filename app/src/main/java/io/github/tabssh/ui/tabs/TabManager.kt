package io.github.tabssh.ui.tabs

import android.view.KeyEvent
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger

/**
 * Browser-style tab management for SSH sessions
 * Core innovation of TabSSH
 */
class TabManager(private val maxTabs: Int = 10) {

    private val tabs = mutableListOf<SSHTab>()
    private var activeTabIndex = 0

    /**
     * Get active tab index
     */
    fun getActiveTabIndex(): Int = activeTabIndex
    private val listeners = mutableListOf<TabManagerListener>()

    /**
     * Create new tab with connection profile
     */
    fun createTab(profile: ConnectionProfile): SSHTab? {
        if (tabs.size >= maxTabs) {
            Logger.w("TabManager", "Maximum tabs reached: $maxTabs")
            return null
        }

        // Create Termux terminal emulator bridge for the new tab
        val termuxBridge = TermuxBridge(
            columns = 80,
            rows = 24,
            transcriptRows = 2000
        )

        val tab = SSHTab(
            profile = profile,
            termuxBridge = termuxBridge
        )

        tabs.add(tab)
        activeTabIndex = tabs.size - 1

        Logger.d("TabManager", "Created new tab: ${profile.name}")
        notifyTabCreated(tab)
        return tab
    }

    /**
     * Close tab by index
     */
    fun closeTab(index: Int) {
        if (index in 0 until tabs.size) {
            val tab = tabs[index]
            tab.disconnect()
            tabs.removeAt(index)

            // Adjust active tab index
            if (activeTabIndex >= index && activeTabIndex > 0) {
                activeTabIndex--
            }

            Logger.d("TabManager", "Closed tab: ${tab.title}")
            notifyTabClosed(tab, index)
        }
    }

    /**
     * Switch to tab by index
     */
    fun switchToTab(index: Int) {
        if (index in 0 until tabs.size && index != activeTabIndex) {
            val previousTab = getActiveTab()
            activeTabIndex = index
            val newTab = getActiveTab()

            Logger.d("TabManager", "Switched to tab: ${newTab?.title}")
            notifyActiveTabChanged(index)
        }
    }

    /**
     * Get active tab
     */
    fun getActiveTab(): SSHTab? {
        return if (activeTabIndex in 0 until tabs.size) {
            tabs[activeTabIndex]
        } else null
    }

    /**
     * Get all tabs
     */
    fun getAllTabs(): List<SSHTab> = tabs.toList()

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
                // Ctrl+Tab - Next tab
                val nextIndex = (activeTabIndex + 1) % tabs.size
                switchToTab(nextIndex)
                true
            }
            isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_TAB -> {
                // Ctrl+Shift+Tab - Previous tab
                val prevIndex = if (activeTabIndex == 0) tabs.size - 1 else activeTabIndex - 1
                switchToTab(prevIndex)
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
        }
    }

    /**
     * Get tab by index
     */
    fun getTab(index: Int): SSHTab? {
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
        val prevIndex = if (activeTabIndex == 0) tabs.size - 1 else activeTabIndex - 1
        switchToTab(prevIndex)
    }

    /**
     * Switch to next tab
     */
    fun switchToNextTab() {
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
     * Save tab state to database for session persistence
     */
    fun saveTabState() {
        Logger.d("TabManager", "Saving tab states to database")

        // Save each tab's state to the database
        tabs.forEachIndexed { index, tab ->
            val tabStats = tab.getConnectionStats()

            // Create TabSession entity
            val tabSession = io.github.tabssh.storage.database.entities.TabSession(
                sessionId = tab.tabId, // Use tabId as sessionId
                tabId = tab.tabId,
                connectionId = tab.profile.id,
                tabOrder = index,
                isActive = tab.isActive.value,
                terminalRows = tabStats.terminalRows,
                terminalCols = tabStats.terminalCols,
                terminalContent = tab.getTerminalContent(),
                cursorRow = tabStats.terminalRows / 2, // Approximation
                cursorCol = 0,
                title = tab.getDisplayTitle(),
                lastActivity = tabStats.lastActivity,
                createdAt = System.currentTimeMillis(),
                environmentVars = "", // Could be expanded to save env vars
                workingDirectory = "" // Could be expanded to save working dir
            )

            // Note: This would need access to database, which should be injected
            // For now, just log what would be saved
            Logger.d("TabManager", "Would save tab: ${tab.getDisplayTitle()} (order=$index)")
        }

        Logger.i("TabManager", "Tab state persistence: ${tabs.size} tabs would be saved")
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("TabManager", "Cleaning up tab manager")
        tabs.forEach { it.disconnect() }
        tabs.clear()
        listeners.clear()
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
