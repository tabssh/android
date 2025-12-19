package io.github.tabssh.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.tabssh.sync.GoogleDriveSyncManager
import io.github.tabssh.sync.models.SyncTrigger
import io.github.tabssh.utils.logging.Logger

/**
 * WorkManager worker for background sync operations
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "sync_periodic"
        const val WORK_NAME_ONE_TIME = "sync_one_time"
    }

    override suspend fun doWork(): Result {
        Logger.d(TAG, "Starting background sync")

        return try {
            val syncManager = GoogleDriveSyncManager(applicationContext)

            if (!syncManager.isSyncEnabled()) {
                Logger.d(TAG, "Sync is disabled, skipping")
                return Result.success()
            }

            val result = syncManager.performSync(SyncTrigger.SCHEDULED)

            if (result.success) {
                Logger.d(TAG, "Background sync completed successfully")
                Result.success()
            } else {
                Logger.e(TAG, "Background sync failed: ${result.message}")

                if (result.conflicts.isNotEmpty()) {
                    Logger.d(TAG, "Sync has ${result.conflicts.size} conflicts")
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Background sync error", e)
            Result.retry()
        }
    }
}
