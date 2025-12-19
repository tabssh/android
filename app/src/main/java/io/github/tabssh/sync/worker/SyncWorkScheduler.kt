package io.github.tabssh.sync.worker

import android.content.Context
import androidx.work.*
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.models.SyncConfiguration
import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Schedules and manages sync workers
 */
class SyncWorkScheduler(private val context: Context) {

    companion object {
        private const val TAG = "SyncWorkScheduler"
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60L
    }

    private val workManager = WorkManager.getInstance(context)
    private val preferenceManager = PreferenceManager(context)

    /**
     * Schedule periodic sync
     */
    fun schedulePeriodicSync(config: SyncConfiguration? = null) {
        val syncConfig = config ?: getSyncConfiguration()

        if (!syncConfig.enabled) {
            Logger.d(TAG, "Sync disabled, cancelling periodic sync")
            cancelPeriodicSync()
            return
        }

        val constraints = buildConstraints(syncConfig)

        val interval = syncConfig.syncFrequencyMinutes.toLong()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            interval, TimeUnit.MINUTES,
            15, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .addTag(SyncWorker.WORK_NAME)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15, TimeUnit.MINUTES
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )

        Logger.d(TAG, "Scheduled periodic sync every $interval minutes")
    }

    /**
     * Cancel periodic sync
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME)
        Logger.d(TAG, "Cancelled periodic sync")
    }

    /**
     * Schedule immediate one-time sync
     */
    fun scheduleImmediateSync() {
        val config = getSyncConfiguration()
        val constraints = buildConstraints(config)

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(SyncWorker.WORK_NAME_ONE_TIME)
            .setInitialDelay(0, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            oneTimeWorkRequest
        )

        Logger.d(TAG, "Scheduled immediate sync")
    }

    /**
     * Update sync schedule with new configuration
     */
    fun updateSyncSchedule(config: SyncConfiguration) {
        if (config.enabled) {
            schedulePeriodicSync(config)
        } else {
            cancelPeriodicSync()
        }
    }

    /**
     * Build work constraints from configuration
     */
    private fun buildConstraints(config: SyncConfiguration): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(
                if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(config.requiresCharging)
            .setRequiresBatteryNotLow(config.batteryNotLowRequired)
            .build()
    }

    /**
     * Get sync configuration from preferences
     */
    private fun getSyncConfiguration(): SyncConfiguration {
        return SyncConfiguration(
            enabled = preferenceManager.isSyncEnabled(),
            wifiOnly = preferenceManager.isSyncWifiOnly(),
            syncFrequencyMinutes = parseSyncFrequency(preferenceManager.getSyncFrequency()),
            syncConnections = preferenceManager.isSyncConnectionsEnabled(),
            syncKeys = preferenceManager.isSyncKeysEnabled(),
            syncSettings = preferenceManager.isSyncSettingsEnabled(),
            syncThemes = preferenceManager.isSyncThemesEnabled(),
            autoResolveConflicts = preferenceManager.isAutoResolveConflictsEnabled(),
            requiresCharging = false,
            batteryNotLowRequired = true
        )
    }

    /**
     * Parse sync frequency from preference string
     */
    private fun parseSyncFrequency(frequency: String): Int {
        return when (frequency) {
            "15min" -> 15
            "30min" -> 30
            "1h" -> 60
            "3h" -> 180
            "6h" -> 360
            "12h" -> 720
            "24h" -> 1440
            "manual" -> 0
            else -> DEFAULT_SYNC_INTERVAL_MINUTES.toInt()
        }
    }

    /**
     * Check if periodic sync is scheduled
     */
    fun isPeriodicSyncScheduled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(SyncWorker.WORK_NAME).get()
        return workInfos.any { !it.state.isFinished }
    }

    /**
     * Cancel all sync work
     */
    fun cancelAllSync() {
        workManager.cancelAllWorkByTag(SyncWorker.WORK_NAME)
        workManager.cancelAllWorkByTag(SyncWorker.WORK_NAME_ONE_TIME)
        Logger.d(TAG, "Cancelled all sync work")
    }
}
