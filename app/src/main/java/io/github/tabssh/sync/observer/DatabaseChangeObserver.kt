package io.github.tabssh.sync.observer

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.worker.SyncWorker
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

/**
 * Observes database changes and triggers sync when data is modified
 */
class DatabaseChangeObserver(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseChangeObserver"
        private const val DEBOUNCE_DELAY = 30_000L // 30 seconds
    }

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    private val workManager = WorkManager.getInstance(context)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var observerJob: Job? = null
    private var debounceJob: Job? = null

    /**
     * Start observing database changes
     */
    fun startObserving() {
        if (observerJob?.isActive == true) {
            Logger.d(TAG, "Observer already running")
            return
        }

        observerJob = scope.launch {
            observeAllChanges()
        }

        Logger.d(TAG, "Started observing database changes")
    }

    /**
     * Stop observing
     */
    fun stopObserving() {
        observerJob?.cancel()
        debounceJob?.cancel()
        Logger.d(TAG, "Stopped observing database changes")
    }

    /**
     * Observe all database tables
     */
    private suspend fun observeAllChanges() {
        val connectionsFlow = database.connectionDao().getAllConnections()
        val keysFlow = database.keyDao().getAllKeys()
        val themesFlow = database.themeDao().getAllThemes()
        val hostKeysFlow = database.hostKeyDao().getAllHostKeys()

        combine(
            connectionsFlow,
            keysFlow,
            themesFlow,
            hostKeysFlow
        ) { connections, keys, themes, hostKeys ->
            DatabaseChangeEvent(
                connectionCount = connections.size,
                keyCount = keys.size,
                themeCount = themes.size,
                hostKeyCount = hostKeys.size,
                timestamp = System.currentTimeMillis()
            )
        }.collect { event ->
            onDataChanged(event)
        }
    }

    /**
     * Handle data change event
     */
    private fun onDataChanged(event: DatabaseChangeEvent) {
        if (!preferenceManager.isSyncEnabled()) {
            return
        }

        if (!preferenceManager.isSyncOnChangeEnabled()) {
            return
        }

        Logger.d(TAG, "Database changed: ${event.connectionCount} connections, ${event.keyCount} keys, " +
                "${event.themeCount} themes, ${event.hostKeyCount} host keys")

        scheduleDelayedSync()
    }

    /**
     * Schedule sync with debounce delay
     */
    private fun scheduleDelayedSync() {
        debounceJob?.cancel()

        debounceJob = scope.launch {
            delay(DEBOUNCE_DELAY)

            if (!isActive) return@launch

            Logger.d(TAG, "Triggering sync after debounce delay")

            triggerSync()
        }
    }

    /**
     * Trigger sync immediately
     */
    fun triggerSync() {
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(0, TimeUnit.SECONDS)
            .addTag("sync_on_change")
            .build()

        workManager.enqueue(syncRequest)

        Logger.d(TAG, "Enqueued sync work request")
    }

    /**
     * Check if observer is running
     */
    fun isObserving(): Boolean {
        return observerJob?.isActive == true
    }
}

/**
 * Database change event
 */
private data class DatabaseChangeEvent(
    val connectionCount: Int,
    val keyCount: Int,
    val themeCount: Int,
    val hostKeyCount: Int,
    val timestamp: Long
)
