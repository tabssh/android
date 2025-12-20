package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing a command snippet
 * Snippets are quick-access commands that can be inserted into terminal
 */
@Entity(tableName = "snippets")
data class Snippet(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "command")
    val command: String,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "category")
    val category: String = "General",

    @ColumnInfo(name = "tags")
    val tags: String = "", // Comma-separated tags

    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    // Sync metadata fields
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String = ""
) {
    /**
     * Get tags as a list
     */
    fun getTagsList(): List<String> {
        return if (tags.isBlank()) {
            emptyList()
        } else {
            tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /**
     * Check if snippet contains variables (placeholders)
     * Variables are in the format {variable_name}
     */
    fun hasVariables(): Boolean {
        return command.contains(Regex("\\{[^}]+\\}"))
    }

    /**
     * Get list of variable names in the command
     */
    fun getVariables(): List<String> {
        val regex = Regex("\\{([^}]+)\\}")
        return regex.findAll(command).map { it.groupValues[1] }.toList()
    }

    /**
     * Replace variables with provided values
     * @param values Map of variable name to value
     * @return Command with variables replaced
     */
    fun applyVariables(values: Map<String, String>): String {
        var result = command
        values.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }
}
