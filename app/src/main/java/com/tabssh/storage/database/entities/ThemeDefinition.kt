package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity representing a custom terminal theme
 */
@Entity(tableName = "themes")
data class ThemeDefinition(
    @PrimaryKey
    val themeId: String,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "author")
    val author: String? = null,
    
    @ColumnInfo(name = "version")
    val version: String = "1.0",
    
    @ColumnInfo(name = "is_dark")
    val isDark: Boolean = false,
    
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
    
    @ColumnInfo(name = "background_color")
    val backgroundColor: Int,
    
    @ColumnInfo(name = "foreground_color")
    val foregroundColor: Int,
    
    @ColumnInfo(name = "cursor_color")
    val cursorColor: Int,
    
    @ColumnInfo(name = "selection_color")
    val selectionColor: Int,
    
    @ColumnInfo(name = "ansi_colors")
    val ansiColors: String, // JSON array of 16 colors
    
    @ColumnInfo(name = "ui_colors")
    val uiColors: String? = null, // JSON object with UI color overrides
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_modified")
    val lastModified: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "usage_count")
    val usageCount: Int = 0
) {
    fun getDisplayName(): String {
        return if (author.isNullOrBlank()) {
            name
        } else {
            "$name by $author"
        }
    }
}