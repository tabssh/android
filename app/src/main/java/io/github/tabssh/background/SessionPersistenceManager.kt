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

    // App lifecycle state. This starts false so that the first onActivityStarted
    // counts as a real 0→1 foreground transition and reaches onAppForegrounded().
    // Initialised to true, a cold start began already "in the foreground", the
    // !isAppInForeground guard below never passed, and restoreSessionState() was
    // therefore unreachable on a fresh process — saved sessions were only ever
    // replayed for a process that had been backgrounded and was still alive.
    private var isAppInForeground = false
    private var activeActivityCount = 0
    private var lastBackgroundTime = 0L
    
    // Session preservation settings. These are fixed: no preference screen
    // exposes them, so there is nothing that can change them at runtime.
    private val preserveSessionsOnBackground = true
    // 24 hours
    private val maxBackgroundTime = 24 * 60 * 60 * 1000L
    // 30 seconds
    private val autoSaveInterval = 30000L
    
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
        // lastBackgroundTime is only 0 before this process has ever been
        // backgrounded, which makes it an exact test for a cold start. The
        // in-memory duration means nothing then — the process did not exist for
        // whatever gap preceded it — so the age check falls back to the saved
        // rows' own timestamps.
        val isColdStart = lastBackgroundTime == 0L
        val backgroundDuration = if (isColdStart) 0L else System.currentTimeMillis() - lastBackgroundTime

        Logger.i("SessionPersistenceManager", "App foregrounded after ${backgroundDuration}ms (coldStart=$isColdStart)")

        // Restore sessions if they were preserved
        persistenceScope.launch {
            restoreSessionsIfNeeded(backgroundDuration, isColdStart)
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
                    
                    // Check every minute
                    delay(60000)
                    
                } catch (e: CancellationException) {
                    // Normal coroutine cancellation (job replaced by startBackgroundMonitoring
                    // or scope shut down on cleanup). Must be re-thrown so structured
                    // concurrency can cancel this coroutine — swallowing it here would
                    // log a false ERROR on every foreground/background transition.
                    throw e
                } catch (e: Exception) {
                    Logger.e("SessionPersistenceManager", "Error in background monitoring", e)
                    // Wait longer on error
                    delay(60000)
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
                    saveTabSession(tab, index)
                }

                Logger.i("SessionPersistenceManager", "Saved session state for ${tabs.size} tabs")

            } catch (e: Exception) {
                Logger.e("SessionPersistenceManager", "Failed to save session state", e)
            }
        }
    }
    
    private suspend fun saveTabSession(tab: SSHTab, tabIndex: Int) {
        try {
            // Guard: the TabSession FK requires the connection profile to exist
            // in the `connections` table. Ephemeral / quick-connect profiles are
            // created in-memory and never persisted, so inserting a session for
            // them would throw SQLITE_CONSTRAINT_FOREIGNKEY. Skip those tabs —
            // same check TabManager.saveTabState() uses.
            val profileInDb = database.connectionDao().getConnectionById(tab.profile.id)
            if (profileInDb == null) {
                Logger.d("SessionPersistenceManager", "Skipping session save for ephemeral profile: ${tab.getDisplayTitle()}")
                return
            }

            val terminal = tab.termuxBridge
            val stats = tab.getConnectionStats()

            // Scrollback is captured for every tab being saved, not just the
            // UI-focused one. `stats.isActive` means "this tab is the visible
            // tab" (SSHTab.activate()/deactivate()), so gating on it used to
            // persist a blank terminal for every background tab — invisible
            // while those rows were also never restored (see the `isActive`
            // field below), but a guaranteed blank restore now that they are.
            val scrollbackContent = compressTerminalContent(terminal.getScrollbackContent())

            val tabSession = TabSession(
                sessionId = java.util.UUID.randomUUID().toString(),
                tabId = tab.tabId,
                connectionId = tab.profile.id,
                title = tab.getDisplayTitle(),
                // `is_active` is this table's "current persisted row for this
                // tab" marker — deactivateAllSessions() above just zeroed every
                // older row, and restoreSessionState() selects strictly on it.
                // It was being written from stats.isActive, which is SSHTab's
                // unrelated "currently visible tab" flag, so a save taken while
                // a tab was not on screen inserted its only row with is_active=0
                // and the very next restore reported "No saved sessions to
                // restore" despite having just logged a successful save.
                // Connection liveness stays on sessionState/connectionState
                // below, which now read the tab's real ConnectionState instead
                // of the same on-screen flag — a connected tab that simply was
                // not the visible one used to persist as DISCONNECTED.
                isActive = true,
                terminalContent = scrollbackContent,
                cursorRow = terminal.getCursorRow(),
                cursorCol = terminal.getCursorCol(),
                scrollPosition = 0,
                workingDirectory = "/",
                environmentVars = "{}",
                createdAt = System.currentTimeMillis(),
                lastActivity = stats.lastActivity,
                sessionState = if (stats.connectionState.isConnected()) {
                    TabSession.STATE_CONNECTED
                } else {
                    TabSession.STATE_DISCONNECTED
                },
                terminalRows = terminal.getRows(),
                terminalCols = terminal.getCols(),
                fontSize = 14f,
                connectionState = stats.connectionState.name,
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
                        val tab = tabManager.createTab(connectionProfile, cursorStyle, app.preferencesManager.getTranscriptRows())

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
    
    private suspend fun restoreSessionsIfNeeded(backgroundDuration: Long, isColdStart: Boolean) {
        val age = if (isColdStart) coldStartSessionAge() else backgroundDuration
        // Only restore if the saved state isn't older than the cutoff
        if (age < maxBackgroundTime) {
            restoreSessionState()
        } else {
            Logger.i("SessionPersistenceManager", "Saved sessions too old (${age}ms), not restoring sessions")
            clearOldSessions()
        }
    }

    /**
     * Age of the freshest saved session, used in place of the elapsed background
     * time on a cold start.
     *
     * A process that has just started has no record of when it was last
     * backgrounded, so without this the age would always evaluate to 0 and a
     * months-old set of sessions would be restored in full — precisely what
     * [maxBackgroundTime] exists to prevent.
     *
     * @return the age in milliseconds, or 0 when there is nothing saved (in
     *     which case the restore is a no-op anyway).
     */
    private suspend fun coldStartSessionAge(): Long {
        return try {
            val newest = database.tabSessionDao().getActiveSessionsList()
                .maxOfOrNull { it.lastActivity } ?: return 0L
            if (newest > 0L) (System.currentTimeMillis() - newest).coerceAtLeast(0L) else 0L
        } catch (e: Exception) {
            Logger.e("SessionPersistenceManager", "Failed to read saved session age", e)
            0L
        }
    }
    
    private suspend fun restoreTabTerminalState(tab: SSHTab, session: TabSession) {
        try {
            // Restore the terminal size BEFORE the scrollback is replayed, so the
            // transcript reflows at the width it was captured at. The signature is
            // resize(newColumns, newRows) — passing rows first sized the emulator
            // transposed (e.g. 40x120 for a 120x40 session) and wrapped every
            // restored line at the wrong column.
            tab.termuxBridge.resize(session.terminalCols, session.terminalRows)

            // Replay the saved scrollback into the fresh emulator. Line endings
            // are normalised to CRLF first: the saved text is plain screen text
            // with bare '\n', and a terminal treats a bare line feed as "down
            // one row, same column", which would staircase the restored history
            // diagonally across the screen instead of returning it to column 0.
            session.terminalContent.takeIf { it.isNotEmpty() }?.let { compressedContent ->
                val content = decompressTerminalContent(compressedContent)
                if (content.isNotEmpty()) {
                    val normalized = content.replace("\r\n", "\n").replace("\n", "\r\n")
                    tab.termuxBridge.injectLocally(normalized.toByteArray(Charsets.UTF_8))
                }
            }

            // A restored tab is never connected — the restore path deliberately
            // does not call connect() — so show the disconnected indicator
            // rather than a bare title that reads as a live session.
            tab.refreshTitleStatus()

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
            // Return uncompressed if compression fails
            content
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
            // Return as-is if decompression fails
            compressedContent
        }
    }
    
    /**
     * Force save current session state
     */
    suspend fun forceSaveSession() {
        saveSessionState(immediate = true)
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
