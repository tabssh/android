package io.github.tabssh.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.TabSession
import io.github.tabssh.ui.tabs.SSHTab
import io.github.tabssh.ui.tabs.TabManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages session persistence for background/app switching support
 * Ensures SSH connections and terminal state survive app backgrounding and switching
 */
class SessionPersistenceManager(
    private val context: Context,
    private val tabManager: TabManager
) : Application.ActivityLifecycleCallbacks {
    
    private val app = context.applicationContext as TabSSHApplication
    private val database = app.database
    
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Serialises all saveSessionState() calls so concurrent callers (auto-save,
    // onActivitySaveInstanceState, onAppBackgrounded) never interleave their
    // deactivateAllSessions() + insertSession() pairs and produce duplicate active rows.
    private val saveMutex = Mutex()

    // App lifecycle state
    private var isAppInForeground = true
    private var activeActivityCount = 0
    private var lastBackgroundTime = 0L
    
    // Session preservation settings
    private var preserveSessionsOnBackground = true
    private var maxBackgroundTime = 24 * 60 * 60 * 1000L // 24 hours
    private var autoSaveInterval = 30000L // 30 seconds
    
    // Background monitoring
    private var backgroundMonitoringJob: Job? = null
    
    init {
        Logger.d("SessionPersistenceManager", "Session persistence manager initialized")
        startAutoSave()
    }
    
    // Application lifecycle callbacks
    
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Logger.d("SessionPersistenceManager", "Activity created: ${activity.javaClass.simpleName}")
    }
    
    override fun onActivityStarted(activity: Activity) {
        activeActivityCount++
        // Trigger foreground only on the 0→1 transition to avoid false positives
        // from late-registered callbacks (where count starts at 0 even though
        // activities were already running) or from transparent dialog activities
        // that inflate the count above 1.
        if (activeActivityCount == 1 && !isAppInForeground) {
            onAppForegrounded()
        }

        Logger.d("SessionPersistenceManager", "Activity started, active count: $activeActivityCount")
    }
    
    override fun onActivityResumed(activity: Activity) {
        Logger.d("SessionPersistenceManager", "Activity resumed: ${activity.javaClass.simpleName}")
    }
    
    override fun onActivityPaused(activity: Activity) {
        Logger.d("SessionPersistenceManager", "Activity paused: ${activity.javaClass.simpleName}")
    }
    
    override fun onActivityStopped(activity: Activity) {
        // Clamp at 0 — the SPM may be registered after activities are already
        // running, so it can receive onStop without a matching onStart.
        if (activeActivityCount > 0) activeActivityCount--
        // Use == 0 (not <= 0) so a spurious extra stop at 0 doesn't re-trigger
        // onAppBackgrounded() when we're already in the background state.
        if (activeActivityCount == 0 && isAppInForeground) {
            onAppBackgrounded()
        }

        Logger.d("SessionPersistenceManager", "Activity stopped, active count: $activeActivityCount")
    }
    
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Intentionally NOT saving here. onActivitySaveInstanceState fires for every
        // Activity on every rotation and system-initiated save — with Dispatchers.IO it
        // can launch multiple concurrent saves that race through deactivateAllSessions()
        // + insertSession() and create duplicate active rows (which become duplicate tabs
        // on the next fresh-start restore). Session state is already saved reliably by
        // the 30-second auto-save and the onAppBackgrounded() save; the extra save here
        // provided no real benefit and was the main driver of the "1 connection → N tabs" bug.
        Logger.d("SessionPersistenceManager", "Instance state save skipped: ${activity.javaClass.simpleName}")
    }
    
    override fun onActivityDestroyed(activity: Activity) {
        Logger.d("SessionPersistenceManager", "Activity destroyed: ${activity.javaClass.simpleName}")
    }
    
    // App foreground/background handling
    
    private fun onAppForegrounded() {
        isAppInForeground = true
        val backgroundDuration = if (lastBackgroundTime > 0) {
            System.currentTimeMillis() - lastBackgroundTime
        } else 0
        
        Logger.i("SessionPersistenceManager", "App foregrounded after ${backgroundDuration}ms")
        
        // Restore sessions if they were preserved
        persistenceScope.launch {
            restoreSessionsIfNeeded(backgroundDuration)
        }
        
        // Resume connection monitoring
        startBackgroundMonitoring()
    }
    
    private fun onAppBackgrounded() {
        isAppInForeground = false
        lastBackgroundTime = System.currentTimeMillis()
        
        Logger.i("SessionPersistenceManager", "App backgrounded")
        
        // Save session state immediately
        persistenceScope.launch {
            saveSessionState(immediate = true)
        }
        
        // Apply background security policies
        applyBackgroundSecurityPolicies()
        
        // Start background monitoring
        startBackgroundMonitoring()
    }
    
    private fun startBackgroundMonitoring() {
        backgroundMonitoringJob?.cancel()
        backgroundMonitoringJob = persistenceScope.launch {
            while (isActive) {
                try {
                    // Monitor connections and save state periodically
                    if (!isAppInForeground) {
                        monitorBackgroundConnections()
                    }
                    
                    delay(60000) // Check every minute
                    
                } catch (e: CancellationException) {
                    // Normal coroutine cancellation (job replaced by startBackgroundMonitoring
                    // or scope shut down on cleanup). Must be re-thrown so structured
                    // concurrency can cancel this coroutine — swallowing it here would
                    // log a false ERROR on every foreground/background transition.
                    throw e
                } catch (e: Exception) {
                    Logger.e("SessionPersistenceManager", "Error in background monitoring", e)
                    delay(60000) // Wait longer on error
                }
            }
        }
    }
    
    private suspend fun monitorBackgroundConnections() {
        val connectionStats = app.sshSessionManager.getConnectionStatistics()
        Logger.d("SessionPersistenceManager", "Background monitoring: ${connectionStats.connectedConnections} active connections")
        
        // Check if we've been backgrounded too long
        val backgroundDuration = System.currentTimeMillis() - lastBackgroundTime
        if (backgroundDuration > maxBackgroundTime) {
            Logger.w("SessionPersistenceManager", "App backgrounded for ${backgroundDuration}ms, closing connections")
            closeConnectionsForLongBackground()
        }
        
        // Perform maintenance
        app.sshSessionManager.performMaintenance()
        
        // Save session state
        saveSessionState()
    }
    
    private suspend fun closeConnectionsForLongBackground() {
        Logger.i("SessionPersistenceManager", "Closing connections due to long background time")
        
        // Save all session states before closing
        saveSessionState(immediate = true)
        
        // Close SSH connections but preserve session data
        app.sshSessionManager.closeAllConnections()
    }
    
    private fun applyBackgroundSecurityPolicies() {
        val preferences = app.preferencesManager
        
        // Auto-lock if enabled
        if (preferences.isAutoLockOnBackground()) {
            Logger.d("SessionPersistenceManager", "Applying auto-lock on background")
            // This would trigger app lock mechanism
        }
        
        // Clipboard auto-clear is intentionally NOT handled here.
        //
        // The previous implementation scheduled an unconditional clear on
        // every app-background whenever security_clear_clipboard_timeout
        // was > 0. That violated two invariants:
        //   1. We only own clips WE wrote — clearing arbitrary clipboard
        //      contents copied from other apps is not our business.
        //   2. Even for our own clips, only sensitive credentials
        //      (passwords, passphrases) should auto-clear; terminal
        //      selections, snippets, URLs, and log/crash-report copies
        //      must persist so the user can paste them elsewhere.
        //
        // ClipboardHelper.copy(..., sensitive = true) already handles the
        // correct case: it stamps a per-copy ownership token into the
        // ClipDescription label, schedules a delayed clear, and verifies
        // the label still matches before wiping — so an external app
        // writing to the clipboard after us cancels the wipe.
        //
        // Nothing to do here beyond that.

        // Clear sensitive data from memory
        app.securePasswordManager.clearSensitiveData()
    }


    /**
     * Save session state to database
     */
    suspend fun saveSessionState(immediate: Boolean = false) {
        if (!preserveSessionsOnBackground && !immediate) {
            return
        }
        // Serialise all saves. Without this lock, concurrent callers (rotation
        // fires onActivitySaveInstanceState for each Activity simultaneously, the
        // auto-save timer fires while a background save is in flight, etc.) can
        // interleave their deactivateAllSessions() + insertSession() pairs and
        // insert multiple active rows for the same tab — those duplicates multiply
        // into duplicate tabs on the next fresh-start restore.
        saveMutex.withLock {
            try {
                val tabs = tabManager.getAllTabs()

                if (tabs.isEmpty()) {
                    Logger.d("SessionPersistenceManager", "No tabs to save")
                    return@withLock
                }

                Logger.d("SessionPersistenceManager", "Saving session state for ${tabs.size} tabs")

                // Deactivate then insert as an atomic unit (protected by saveMutex).
                database.tabSessionDao().deactivateAllSessions()

                tabs.forEachIndexed { index, tab ->
                    saveTabSession(tab, index, immediate)
                }

                Logger.i("SessionPersistenceManager", "Saved session state for ${tabs.size} tabs")

            } catch (e: Exception) {
                Logger.e("SessionPersistenceManager", "Failed to save session state", e)
            }
        }
    }
    
    private suspend fun saveTabSession(tab: SSHTab, tabIndex: Int, immediate: Boolean) {
        try {
            val terminal = tab.termuxBridge
            val stats = tab.getConnectionStats()

            // Compress terminal content for storage (get scrollback from bridge)
            val scrollbackContent = if (immediate || stats.isActive) {
                compressTerminalContent(terminal.getScrollbackContent())
            } else null

            val tabSession = TabSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                tabId = tab.tabId,
                connectionId = tab.profile.id,
                title = tab.getDisplayTitle(),
                isActive = stats.isActive,
                terminalContent = scrollbackContent ?: "",
                cursorRow = terminal.getCursorRow(),
                cursorCol = terminal.getCursorCol(),
                scrollPosition = 0,
                workingDirectory = "/",
                environmentVars = "{}",
                createdAt = System.currentTimeMillis(),
                lastActivity = stats.lastActivity,
                sessionState = if (stats.isActive) TabSession.STATE_CONNECTED else TabSession.STATE_DISCONNECTED,
                terminalRows = terminal.getRows(),
                terminalCols = terminal.getCols(),
                fontSize = 14f,
                connectionState = if (stats.isActive) "CONNECTED" else "DISCONNECTED",
                lastError = null,
                hasUnreadOutput = false,
                unreadLines = 0,
                tabOrder = tabIndex
            )
            
            database.tabSessionDao().insertSession(tabSession)
            
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to save tab session for ${tab.profile.getDisplayName()}", e)
        }
    }
    
    /**
     * Restore session state from database
     */
    suspend fun restoreSessionState(): Boolean {
        return try {
            val savedSessions = database.tabSessionDao().getActiveSessionsList()
            
            if (savedSessions.isEmpty()) {
                Logger.d("SessionPersistenceManager", "No saved sessions to restore")
                return false
            }
            
            Logger.d("SessionPersistenceManager", "Restoring ${savedSessions.size} saved sessions")

            // Filter out sessions whose tabs are already alive in TabManager.
            // The Android lifecycle fires onAppBackgrounded → onAppForegrounded
            // during a normal BACK (TabTerminalActivity → MainActivity) because
            // onStop(TabTerminalActivity) momentarily brings activeActivityCount to 0
            // before onStart(MainActivity) runs. Without this guard we create a
            // second (dead) SSHTab for every live session the user navigates away from.
            val liveTabIds = tabManager.getAllTabs().map { it.tabId }.toSet()
            val sessionsToRestore = savedSessions
                .sortedBy { it.tabOrder }
                // Deduplicate by tabId — concurrent save races can insert multiple active
                // rows for the same tab. Keep only the first (lowest tabOrder) per tabId
                // to prevent restoring duplicate tabs for one real connection.
                .distinctBy { it.tabId }
                .filter { it.tabId !in liveTabIds }
                // VNC-kind sessions aren't restorable via this SSH-only path yet
                // (tracked in TODO.AI.md — VNC-tab-swipe integration, step 3).
                .filter { it.tabKind == TabSession.TAB_KIND_SSH && it.connectionId != null }

            if (sessionsToRestore.isEmpty()) {
                Logger.d("SessionPersistenceManager", "All saved sessions already alive — skipping restore")
                return false
            }

            var restoredCount = 0

            for (session in sessionsToRestore) {
                try {
                    val sessionConnectionId = session.connectionId ?: continue
                    val connectionProfile = database.connectionDao().getConnectionById(sessionConnectionId)

                    if (connectionProfile != null) {
                        // Create tab without auto-connecting (using user's preferred cursor style)
                        val cursorStyle = app.preferencesManager.getCursorStyleInt()
                        val tab = tabManager.createTab(connectionProfile, cursorStyle)

                        if (tab != null) {
                            // Restore terminal state
                            restoreTabTerminalState(tab, session)
                            restoredCount++
                        }
                    }

                } catch (e: Exception) {
                    Logger.e("SessionPersistenceManager", "Failed to restore session ${session.sessionId}", e)
                }
            }
            
            Logger.i("SessionPersistenceManager", "Restored $restoredCount of ${sessionsToRestore.size} sessions")
            true
            
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to restore session state", e)
            false
        }
    }
    
    private suspend fun restoreSessionsIfNeeded(backgroundDuration: Long) {
        // Only restore if app wasn't backgrounded too long
        if (backgroundDuration < maxBackgroundTime) {
            restoreSessionState()
        } else {
            Logger.i("SessionPersistenceManager", "App backgrounded too long (${backgroundDuration}ms), not restoring sessions")
            clearOldSessions()
        }
    }
    
    private suspend fun restoreTabTerminalState(tab: SSHTab, session: TabSession) {
        try {
            // Restore terminal.size
            tab.termuxBridge.resize(session.terminalRows, session.terminalCols)

            // Restore scrollback content if available
            session.terminalContent.takeIf { it.isNotEmpty() }?.let { compressedContent ->
                val content = decompressTerminalContent(compressedContent)
                // This would restore terminal buffer content
                // Implementation depends on terminal buffer restoration capability
            }
            
            // Restore cursor position
            // tab.terminal.getBuffer().setCursorPosition(session.cursorRow, session.cursorCol)
            
            Logger.d("SessionPersistenceManager", "Restored terminal state for tab: ${session.title}")
            
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to restore terminal state", e)
        }
    }
    
    private suspend fun clearOldSessions() {
        try {
            database.tabSessionDao().deleteAllSessions()
            Logger.d("SessionPersistenceManager", "Cleared old session data")
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to clear old sessions", e)
        }
    }
    
    private fun startAutoSave() {
        persistenceScope.launch {
            while (isActive) {
                delay(autoSaveInterval)
                
                if (isAppInForeground) {
                    saveSessionState()
                }
            }
        }
    }
    
    private fun compressTerminalContent(content: String): String {
        // Simple compression - could use more sophisticated algorithms
        return try {
            val baos = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(baos).use { gzipOut ->
                gzipOut.write(content.toByteArray())
                gzipOut.finish()
            }
            android.util.Base64.encodeToString(
                baos.toByteArray(),
                android.util.Base64.NO_WRAP
            )
        } catch (e: Exception) {
            Logger.w("SessionPersistenceManager", "Failed to compress terminal content", e)
            content // Return uncompressed if compression fails
        }
    }
    
    private fun decompressTerminalContent(compressedContent: String): String {
        return try {
            val compressedBytes = android.util.Base64.decode(compressedContent, android.util.Base64.NO_WRAP)
            java.util.zip.GZIPInputStream(
                java.io.ByteArrayInputStream(compressedBytes)
            ).use { gzipIn ->
                gzipIn.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Logger.w("SessionPersistenceManager", "Failed to decompress terminal content", e)
            compressedContent // Return as-is if decompression fails
        }
    }
    
    /**
     * Configure session persistence settings
     */
    fun configureSettings(
        preserveSessions: Boolean = true,
        maxBackgroundTimeHours: Int = 24,
        autoSaveIntervalSeconds: Int = 30
    ) {
        preserveSessionsOnBackground = preserveSessions
        maxBackgroundTime = maxBackgroundTimeHours * 60 * 60 * 1000L
        autoSaveInterval = autoSaveIntervalSeconds * 1000L
        
        Logger.d("SessionPersistenceManager", "Session persistence configured: preserve=$preserveSessions, maxBg=${maxBackgroundTimeHours}h, autoSave=${autoSaveIntervalSeconds}s")
    }
    
    /**
     * Force save current session state
     */
    suspend fun forceSaveSession() {
        saveSessionState(immediate = true)
    }
    
    /**
     * Get session persistence statistics
     */
    suspend fun getSessionStatistics(): SessionStatistics {
        val activeSessions = database.tabSessionDao().getActiveSessionsList()
        val totalSessions = database.tabSessionDao().getAllTabs()

        return SessionStatistics(
            isAppInForeground = isAppInForeground,
            activeActivityCount = activeActivityCount,
            lastBackgroundTime = lastBackgroundTime,
            preserveSessionsEnabled = preserveSessionsOnBackground,
            activeSavedSessions = activeSessions.size,
            totalSavedSessions = totalSessions.size,
            backgroundDuration = if (lastBackgroundTime > 0 && !isAppInForeground) {
                System.currentTimeMillis() - lastBackgroundTime
            } else 0L
        )
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("SessionPersistenceManager", "Cleaning up session persistence manager")
        
        backgroundMonitoringJob?.cancel()
        persistenceScope.cancel()
    }
}

/**
 * Session persistence statistics
 */
data class SessionStatistics(
    val isAppInForeground: Boolean,
    val activeActivityCount: Int,
    val lastBackgroundTime: Long,
    val preserveSessionsEnabled: Boolean,
    val activeSavedSessions: Int,
    val totalSavedSessions: Int,
    val backgroundDuration: Long
)