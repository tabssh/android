package io.github.tabssh.sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.tabssh.sync.SAFSyncManager
import io.github.tabssh.sync.SyncFileStatus
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.utils.logging.Logger

/**
 * WorkManager worker for background sync operations using Storage Access Framework
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
            val syncManager = SAFSyncManager(applicationContext)

            // Check if sync is configured
            if (!syncManager.isConfigured()) {
                Logger.d(TAG, "Sync is not configured, skipping")
                return Result.success()
            }

            // Check if sync file is accessible
            val fileStatus = syncManager.checkSyncFile()
            if (fileStatus != SyncFileStatus.OK) {
                Logger.w(TAG, "Sync file not accessible: $fileStatus")
                return Result.failure()
            }

            val collector = SyncDataCollector(applicationContext)

            // H6 — two-way sync: pull the peer state and merge it locally
            // BEFORE re-uploading. Without the download+apply step the worker
            // was upload-only, so a second device's changes were never ingested
            // and its deletes were resurrected on the next union upload.
            val remote = syncManager.download()
            if (remote != null) {
                SyncDataApplier(applicationContext).applyAll(remote)
                Logger.d(TAG, "Applied remote sync state")
            }

            // Collect the merged local state. collectAll runs the tombstone
            // backstop against the shadow captured at the last successful sync,
            // so deletions since then are emitted in this payload.
            val payload = collector.collectAll()

            // Upload the merged result.
            val success = syncManager.upload(payload)

            if (success) {
                // Refresh the shadow baseline only after a successful upload so
                // the next backstop diffs against what we actually persisted.
                collector.snapshotState()
                Logger.d(TAG, "Background sync completed successfully")
                Result.success()
            } else {
                Logger.e(TAG, "Background sync failed")
                Result.retry()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Background sync error", e)
            Result.retry()
        }
    }
}
