package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * H6 — soft-delete tombstone. Records that a synced entity was deleted so the
 * deletion propagates across devices instead of being resurrected by the next
 * peer upload.
 *
 * The underlying entity row stays hard-deleted (every existing SELECT/list
 * query is untouched); only a small tombstone row survives. Tombstones travel
 * inside the sync payload and are unioned across devices. A remote tombstone
 * deletes the matching local row unless a strictly-newer local copy exists
 * (resurrection); a local tombstone suppresses re-adding an incoming row.
 *
 * [entityKey] is the *stable cross-device* identity, NOT necessarily the Room
 * primary key. For the 14 UUID-keyed entities it is the UUID. For the two
 * Long-autoincrement entities (HypervisorProfile, HypervisorAccount) the Long
 * id is meaningless across devices, so [entityKey] is a natural key
 * (HypervisorProfile: `name|type`; HypervisorAccount: `name|authType|username`)
 * — see [io.github.tabssh.sync.tombstone.TombstoneRecorder].
 */
@Serializable
@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entity_type", "entity_key"]
)
data class SyncTombstone(
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_key")
    val entityKey: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long,

    @ColumnInfo(name = "device_id")
    val deviceId: String
)
