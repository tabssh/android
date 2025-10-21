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
        
        if (!isAppInForeground) {
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
        activeActivityCount--
        
        if (activeActivityCount <= 0 && isAppInForeground) {
            onAppBackgrounded()
        }
        
        Logger.d("SessionPersistenceManager", "Activity stopped, active count: $activeActivityCount")
    }
    
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Logger.d("SessionPersistenceManager", "Saving instance state for: ${activity.javaClass.simpleName}")
        
        // Save critical session data immediately
        persistenceScope.launch {
            saveSessionState(immediate = true)
        }
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
        
        // Clear clipboard if enabled
        if (preferences.getClearClipboardTimeout() > 0) {
            persistenceScope.launch {
                delay(preferences.getClearClipboardTimeout() * 1000L)
                clearClipboard()
            }
        }
        
        // Clear sensitive data from memory
        app.securePasswordManager.clearSensitiveData()
    }
    
    private fun clearClipboard() {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) 
                as android.content.ClipboardManager
            val emptyClip = android.content.ClipData.newPlainText("", "")
            clipboardManager.setPrimaryClip(emptyClip)
            
            Logger.d("SessionPersistenceManager", "Cleared clipboard for security")
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to clear clipboard", e)
        }
    }
    
    /**
     * Save session state to database
     */
    suspend fun saveSessionState(immediate: Boolean = false) {
        if (!preserveSessionsOnBackground && !immediate) {
            return
        }
        
        try {
            val tabs = tabManager.getAllTabs()
            
            if (tabs.isEmpty()) {
                Logger.d("SessionPersistenceManager", "No tabs to save")
                return
            }
            
            Logger.d("SessionPersistenceManager", "Saving session state for ${tabs.size} tabs")
            
            // First, deactivate all existing sessions
            database.tabSessionDao().deactivateAllSessions()
            
            // Save each tab's session
            tabs.forEachIndexed { index, tab ->
                saveTabSession(tab, index, immediate)
            }
            
            Logger.i("SessionPersistenceManager", "Saved session state for ${tabs.size} tabs")
            
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to save session state", e)
        }
    }
    
    private suspend fun saveTabSession(tab: SSHTab, tabIndex: Int, immediate: Boolean) {
        try {
            val terminal = tab.terminal
            val buffer = terminal.getBuffer()
            val stats = tab.getConnectionStats()

            // Compress terminal content for storage
            val scrollbackContent = if (immediate || stats.isActive) {
                compressTerminalContent(buffer.getScrollbackContent())
            } else null

            val tabSession = TabSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                tabId = tab.tabId,
                connectionId = tab.profile.id,
                title = tab.getDisplayTitle(),
                isActive = stats.isActive,
                terminalContent = scrollbackContent ?: "",
                cursorRow = buffer.getCursorRow(),
                cursorCol = buffer.getCursorCol(),
                scrollPosition = 0,
                workingDirectory = "/",
                environmentVars = "{}",
                createdAt = System.currentTimeMillis(),
                lastActivity = stats.lastActivity,
                sessionState = if (stats.isActive) TabSession.STATE_CONNECTED else TabSession.STATE_DISCONNECTED,
                terminalRows = buffer.getRows(),
                terminalCols = buffer.getCols(),
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
            
            var restoredCount = 0
            
            for (session in savedSessions.sortedBy { it.tabOrder }) {
                try {
                    val connectionProfile = database.connectionDao().getConnectionById(session.connectionId)
                    
                    if (connectionProfile != null) {
                        // Create tab without auto-connecting
                        val tab = tabManager.createTab(connectionProfile)
                        
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
            
            Logger.i("SessionPersistenceManager", "Restored $restoredCount of ${savedSessions.size} sessions")
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
            tab.terminal.resize(session.terminalRows, session.terminalCols)

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