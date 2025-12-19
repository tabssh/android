package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity for tracking sync state of individual items
 */
@Entity(
    tableName = "sync_state",
    indices = [
        Index(value = ["entity_type", "entity_id"], unique = true),
        Index(value = ["entity_type", "last_synced_at"]),
        Index(value = ["conflict_status"])
    ]
)
data class SyncState(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "sync_hash")
    val syncHash: String,

    @ColumnInfo(name = "conflict_status")
    val conflictStatus: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ENTITY_TYPE_CONNECTION = "connection"
        const val ENTITY_TYPE_KEY = "key"
        const val ENTITY_TYPE_THEME = "theme"
        const val ENTITY_TYPE_HOST_KEY = "host_key"
        const val ENTITY_TYPE_PREFERENCE = "preference"

        const val CONFLICT_STATUS_PENDING = "pending"
        const val CONFLICT_STATUS_RESOLVED = "resolved"

        fun createId(entityType: String, entityId: String): String {
            return "$entityType:$entityId"
        }
    }

    fun hasConflict(): Boolean = conflictStatus != null

    fun isConflictPending(): Boolean = conflictStatus == CONFLICT_STATUS_PENDING
}
