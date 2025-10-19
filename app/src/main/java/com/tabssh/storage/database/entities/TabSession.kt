package com.tabssh.storage.database.entities

import androidx.room.*

/**
 * Entity representing a saved tab session for persistence across app restarts
 * Stores terminal state, connection info, and session data
 */
@Entity(
    tableName = "tab_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["connection_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("connection_id"),
        Index("tab_id", unique = true),
        Index("is_active")
    ]
)
data class TabSession(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "tab_id")
    val tabId: String,

    @ColumnInfo(name = "connection_id")
    val connectionId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,

    // Terminal state
    @ColumnInfo(name = "terminal_content")
    val terminalContent: String = "",

    @ColumnInfo(name = "cursor_row")
    val cursorRow: Int = 0,

    @ColumnInfo(name = "cursor_col")
    val cursorCol: Int = 0,

    @ColumnInfo(name = "scroll_position")
    val scrollPosition: Int = 0,

    // Session environment
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String = "/",

    @ColumnInfo(name = "environment_vars")
    val environmentVars: String = "{}",

    // Session metadata
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_activity")
    val lastActivity: Long,

    @ColumnInfo(name = "session_state")
    val sessionState: String = "DISCONNECTED",

    // Terminal settings
    @ColumnInfo(name = "terminal_rows")
    val terminalRows: Int = 24,

    @ColumnInfo(name = "terminal_cols")
    val terminalCols: Int = 80,

    @ColumnInfo(name = "font_size")
    val fontSize: Float = 14f,

    // Connection state
    @ColumnInfo(name = "connection_state")
    val connectionState: String = "DISCONNECTED",

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    // Tab metadata
    @ColumnInfo(name = "has_unread_output")
    val hasUnreadOutput: Boolean = false,

    @ColumnInfo(name = "unread_lines")
    val unreadLines: Int = 0,

    @ColumnInfo(name = "tab_order")
    val tabOrder: Int = 0
) {
    companion object {
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR = "ERROR"
        const val STATE_RECONNECTING = "RECONNECTING"
    }
}