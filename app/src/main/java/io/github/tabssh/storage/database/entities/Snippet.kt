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
     * Check if snippet contains variables (placeholders).
     * Supported syntaxes:
     *   {name}                 — basic placeholder
     *   {?name}                — prompt-style (semantically identical at substitution)
     *   {?name:default}        — prompt with default value pre-filled
     *   {?name|hint}           — prompt with hint text
     *   {?name:default|hint}   — prompt with default + hint
     */
    fun hasVariables(): Boolean {
        return command.contains(Regex("\\{[?]?[^}]+\\}"))
    }

    /** Get distinct variable names in source order (preserves backward compat). */
    fun getVariables(): List<String> {
        return getVariableSpecs().map { it.name }.distinct()
    }

    /**
     * Wave 2.1 — rich variable specs with optional default + hint.
     * Distinct by name (first occurrence wins for default/hint).
     */
    fun getVariableSpecs(): List<VarSpec> {
        // Match either {?name…} OR {name}. Capture groups:
        //   1 = leading '?' (prompt-style marker, optional)
        //   2 = name
        //   3 = default (optional, after ':')
        //   4 = hint    (optional, after '|')
        val re = Regex("""\{(\?)?([^}:|]+)(?::([^}|]*))?(?:\|([^}]*))?\}""")
        val seen = linkedMapOf<String, VarSpec>()
        for (m in re.findAll(command)) {
            val name = m.groupValues[2].trim()
            if (name.isEmpty()) continue
            if (seen.containsKey(name)) continue
            seen[name] = VarSpec(
                name = name,
                default = m.groupValues[3].takeIf { it.isNotEmpty() },
                hint = m.groupValues[4].takeIf { it.isNotEmpty() },
                prompt = m.groupValues[1] == "?"
            )
        }
        return seen.values.toList()
    }

    /**
     * Replace variables with provided values. Handles both `{name}` and any
     * `{?name…}` form (default/hint suffixes are stripped at substitution).
     */
    fun applyVariables(values: Map<String, String>): String {
        val re = Regex("""\{(\?)?([^}:|]+)(?::([^}|]*))?(?:\|([^}]*))?\}""")
        return re.replace(command) { m ->
            val name = m.groupValues[2].trim()
            values[name] ?: m.groupValues[3] // fall back to declared default
        }
    }

    data class VarSpec(
        val name: String,
        val default: String? = null,
        val hint: String? = null,
        val prompt: Boolean = false
    )
}
