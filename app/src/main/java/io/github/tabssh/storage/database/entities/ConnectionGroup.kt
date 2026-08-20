package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Database entity representing a connection group/folder for organizing connections
 */
@Entity(
    tableName = "connection_groups",
    indices = [
        Index("parent_id"),
        Index("sort_order")
    ]
)
@Serializable
data class ConnectionGroup(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    // For nested folders (optional, can be null for root level)
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,

    // Icon identifier (e.g., "folder", "server", "cloud")
    @ColumnInfo(name = "icon")
    val icon: String? = null,

    // Hex color code for visual distinction (e.g., "#FF5722")
    @ColumnInfo(name = "color")
    val color: String? = null,

    // Display order in list
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    // Expansion state (true = collapsed, false = expanded)
    @ColumnInfo(name = "is_collapsed")
    val isCollapsed: Boolean = false,

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
    val syncDeviceId: String = "",

    // "" = normal user group, "vm_hosts" = VM Hosts system group, "cloud" = Cloud Instances system group
    @ColumnInfo(name = "group_type")
    val groupType: String = ""
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
