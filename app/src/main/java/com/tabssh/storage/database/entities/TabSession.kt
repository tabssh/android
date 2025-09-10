package com.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing a saved tab session for restoration
 */
@Entity(tableName = "tab_sessions")
data class TabSession(
    @PrimaryKey
    val sessionId: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "connection_id")
    val connectionId: String,
    
    @ColumnInfo(name = "tab_title")
    val tabTitle: String,
    
    @ColumnInfo(name = "tab_order")
    val tabOrder: Int,
    
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,
    
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String? = null,
    
    @ColumnInfo(name = "environment_variables")
    val environmentVariables: String? = null, // JSON string
    
    @ColumnInfo(name = "terminal_size_rows")
    val terminalSizeRows: Int = 24,
    
    @ColumnInfo(name = "terminal_size_cols")
    val terminalSizeCols: Int = 80,
    
    @ColumnInfo(name = "cursor_row")
    val cursorRow: Int = 0,
    
    @ColumnInfo(name = "cursor_col")
    val cursorCol: Int = 0,
    
    @ColumnInfo(name = "scrollback_content")
    val scrollbackContent: String? = null, // Compressed terminal content
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_activity")
    val lastActivity: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "session_duration")
    val sessionDuration: Long = 0, // milliseconds
    
    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long = 0
)