package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing a connection group/folder for organizing connections
 */
@Entity(tableName = "connection_groups")
data class ConnectionGroup(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "parent_id")
    val parentId: String? = null, // For nested folders (optional, can be null for root level)

    @ColumnInfo(name = "icon")
    val icon: String? = null, // Icon identifier (e.g., "folder", "server", "cloud")

    @ColumnInfo(name = "color")
    val color: String? = null, // Hex color code for visual distinction (e.g., "#FF5722")

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0, // Display order in list

    @ColumnInfo(name = "is_collapsed")
    val isCollapsed: Boolean = false, // Expansion state (true = collapsed, false = expanded)

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    // Sync metadata fields (for future cloud sync)
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String = ""
) {
    /**
     * Check if this is a root-level group (no parent)
     */
    fun isRootLevel(): Boolean = parentId == null

    /**
     * Get display name (never empty)
     */
    fun getDisplayName(): String = if (name.isNotBlank()) name else "Unnamed Group"
}
