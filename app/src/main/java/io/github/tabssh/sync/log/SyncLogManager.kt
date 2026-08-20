package io.github.tabssh.sync.log

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.SyncLogEntry
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolutionOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the dedicated Sync Log: a durable, user-viewable record of every sync
 * conflict and how it was resolved. Deliberately separate from
 * [io.github.tabssh.utils.logging.Logger] — conflict text (entity names,
 * changed field values, hostnames) must never reach the app/debug log, only
 * this user-facing history (see [SyncLogEntry] KDoc). Retention is capped the
 * same way as [io.github.tabssh.audit.AuditLogManager].
 */
class SyncLogManager(
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager,
    private val metadataManager: SyncMetadataManager
) {

    constructor(context: Context) : this(
        TabSSHDatabase.getDatabase(context),
        PreferenceManager(context),
        SyncMetadataManager(context)
    )

    private val dao = database.syncLogDao()

    /**
     * Records a conflict that was resolved without ever prompting the user —
     * either the headless timestamp-based auto-resolver, or an
     * auto-resolvable field the merge engine settled on its own.
     */
    suspend fun recordAutoMerged(conflict: Conflict) = record(conflict, SyncLogEntry.RESOLUTION_AUTO_MERGED)

    /** Records a conflict resolved by an explicit user (or user-equivalent) choice. */
    suspend fun recordResolution(conflict: Conflict, option: ConflictResolutionOption) =
        record(conflict, resolutionTextFor(option))

    /** Records a conflict a headless sync could not resolve and deferred to the next foreground open. */
    suspend fun recordDeferred(conflict: Conflict) = record(conflict, SyncLogEntry.RESOLUTION_DEFERRED)

    private suspend fun record(conflict: Conflict, resolution: String) = withContext(Dispatchers.IO) {
        dao.insert(
            SyncLogEntry(
                deviceId = metadataManager.getDeviceId(),
                deviceName = metadataManager.getDeviceName(),
                entityType = conflict.entityType,
                entityId = conflict.entityId,
                description = conflict.description,
                resolution = resolution
            )
        )
        checkAndCleanup()
    }

    private fun resolutionTextFor(option: ConflictResolutionOption): String = when (option) {
        ConflictResolutionOption.KEEP_LOCAL -> SyncLogEntry.RESOLUTION_KEPT_LOCAL
        ConflictResolutionOption.KEEP_REMOTE -> SyncLogEntry.RESOLUTION_KEPT_REMOTE
        ConflictResolutionOption.KEEP_BOTH -> SyncLogEntry.RESOLUTION_KEPT_BOTH
        ConflictResolutionOption.SKIP -> SyncLogEntry.RESOLUTION_SKIPPED
    }

    private suspend fun checkAndCleanup() {
        val cutoff = System.currentTimeMillis() - (preferenceManager.getSyncLogMaxAgeDays().toLong() * 86_400_000L)
        dao.deleteOlderThan(cutoff)
    }
}
