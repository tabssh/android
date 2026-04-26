package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wave 2.5 — A workspace is a named collection of connections that should be
 * opened together as tabs. Saving a workspace snapshots the IDs of currently
 * open tabs; opening one calls `connectToProfile` for each (with a small
 * delay between to avoid hammering the device).
 *
 * `connectionIdsJson` is just a JSON array of ConnectionProfile.id strings —
 * we don't enforce referential integrity at the DB level so a deleted
 * connection in a workspace is silently skipped at open time.
 */
@Serializable
@Entity(tableName = "workspaces")
data class Workspace(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "connection_ids")
    val connectionIdsJson: String, // JSON array of ConnectionProfile.id

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    // Sync metadata fields (match other entities for consistency)
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String = ""
)
