package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * H6 — diff-at-collect backstop bookkeeping. Snapshots the set of stable keys
 * that existed for each synced entity type at the end of the last successful
 * sync. On the next collect the live key set is diffed against this shadow;
 * any key that vanished without an explicit tombstone (bulk/cascade/future
 * un-instrumented deletes) is tombstoned automatically.
 *
 * This table is purely local bookkeeping — it never travels in the sync
 * payload, so it is deliberately NOT `@Serializable`. [entityKey] uses the same
 * stable cross-device identity scheme as [SyncTombstone].
 */
@Entity(
    tableName = "sync_shadow",
    primaryKeys = ["entity_type", "entity_key"]
)
data class SyncShadow(
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_key")
    val entityKey: String
)
