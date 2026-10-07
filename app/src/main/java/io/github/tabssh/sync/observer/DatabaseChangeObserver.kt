package io.github.tabssh.sync.observer

import android.content.Context
import androidx.room.InvalidationTracker
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.worker.SyncWorkScheduler
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*

/**
 * Observes database changes and triggers sync when data is modified
 */
class DatabaseChangeObserver(private val context: Context) {

    companion object {
        private const val TAG = "DatabaseChangeObserver"
        // 30 seconds
        private const val DEBOUNCE_DELAY = 30_000L

        // Every table SyncDataCollector reads — the sync-on-change trigger must
        // cover the full sync surface, not a subset (AI.md PART 10).
        private val SYNCED_TABLES = arrayOf(
            "connections",
            "stored_keys",
            "themes",
            "host_keys",
            "workspaces",
            "snippets",
            "identities",
            "connection_groups",
            "hypervisors",
            "trusted_certificates",
            "macros",
            "monitor_slots",
            "hypervisor_accounts",
            "vnc_hosts",
            "vnc_identities",
            "cloud_accounts",
            "port_forwards",
            "network_routes",
            "pane_groups",
            "container_hosts",
            "registry_credentials",
            "compose_stacks",
            "single_container_configs",
            "container_auto_update_policies",
            "telnet_hosts",
            "domains",
            "vps_hosts"
        )
    }

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)

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
     * Observe all synced database tables via Room's InvalidationTracker,
     * which fires per-write without holding row contents in memory.
     */
    private suspend fun observeAllChanges() {
        val observer = object : InvalidationTracker.Observer(SYNCED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                onDataChanged(tables)
            }
        }
        database.invalidationTracker.addObserver(observer)
        try {
            awaitCancellation()
        } finally {
            database.invalidationTracker.removeObserver(observer)
        }
    }

    /**
     * Handle data change event
     */
    private fun onDataChanged(tables: Set<String>) {
        if (!preferenceManager.isSyncEnabled()) return
        if (!preferenceManager.isSyncOnChangeEnabled()) return
        Logger.d(TAG, "Database changed: ${tables.joinToString(", ")}")
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
        SyncWorkScheduler(context).scheduleImmediateSync()
        Logger.d(TAG, "Scheduled sync work request")
    }

    /**
     * Check if observer is running
     */
    fun isObserving(): Boolean {
        return observerJob?.isActive == true
    }
}
