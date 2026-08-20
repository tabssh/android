package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A dedicated, user-viewable record of every sync conflict and how it was
 * resolved — auto-merged, kept-local, kept-remote, kept-both, or deferred
 * pending user review. This is deliberately separate from the app/debug log
 * ([io.github.tabssh.utils.logging.Logger]): conflict outcomes are
 * user-facing sync history, never developer diagnostics, and must never
 * carry secret material.
 *
 * [description] is a short, safe summary (entity type + field name, e.g.
 * "connection 'prod-db': host changed on both devices") — never the raw
 * conflicting values, which may include hostnames/usernames a user would
 * not want retained indefinitely; retention is capped the same way as
 * [io.github.tabssh.audit.AuditLogManager].
 */
@Entity(
    tableName = "sync_log",
    indices = [Index("timestamp")]
)
data class SyncLogEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "device_name")
    val deviceName: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "description")
    val description: String,

    /** One of the [Resolution] constants. */
    @ColumnInfo(name = "resolution")
    val resolution: String
) {
    companion object {
        const val RESOLUTION_AUTO_MERGED = "auto_merged"
        const val RESOLUTION_KEPT_LOCAL = "kept_local"
        const val RESOLUTION_KEPT_REMOTE = "kept_remote"
        const val RESOLUTION_KEPT_BOTH = "kept_both"
        const val RESOLUTION_SKIPPED = "skipped"
        const val RESOLUTION_DEFERRED = "deferred_pending"
    }
}
