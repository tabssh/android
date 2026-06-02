package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo

/**
 * Lightweight projection of [AuditLogEntry] that omits the (potentially large)
 * [output] column. Used for list and filter views so Room only reads the columns
 * the UI actually needs; the full entry (including [output]) is fetched lazily
 * only when the user requests a detail view, copy, or export.
 */
data class AuditLogSummary(
    val id: String,

    @ColumnInfo(name = "connection_id")
    val connectionId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "command")
    val command: String? = null,

    @ColumnInfo(name = "exit_code")
    val exitCode: Int? = null,

    @ColumnInfo(name = "user")
    val user: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null
)
