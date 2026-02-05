package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing an audit log entry
 * Tracks all terminal commands and activities for security/compliance
 */
@Entity(
    tableName = "audit_log",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["connection_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["connection_id"]),
        Index(value = ["timestamp"]),
        Index(value = ["session_id"])
    ]
)
data class AuditLogEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "connection_id")
    val connectionId: String,
    
    @ColumnInfo(name = "session_id")
    val sessionId: String, // Groups entries by session
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "event_type")
    val eventType: String, // "COMMAND", "CONNECT", "DISCONNECT", "AUTH_SUCCESS", "AUTH_FAILURE"
    
    @ColumnInfo(name = "command")
    val command: String? = null, // The actual command executed
    
    @ColumnInfo(name = "output")
    val output: String? = null, // Command output (optional, can be large)
    
    @ColumnInfo(name = "exit_code")
    val exitCode: Int? = null, // Command exit code if available
    
    @ColumnInfo(name = "user")
    val user: String, // Username for the session
    
    @ColumnInfo(name = "host")
    val host: String, // Hostname
    
    @ColumnInfo(name = "port")
    val port: Int,
    
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0, // Entry size for cleanup calculations
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null // JSON for additional context
) {
    companion object {
        const val EVENT_COMMAND = "COMMAND"
        const val EVENT_CONNECT = "CONNECT"
        const val EVENT_DISCONNECT = "DISCONNECT"
        const val EVENT_AUTH_SUCCESS = "AUTH_SUCCESS"
        const val EVENT_AUTH_FAILURE = "AUTH_FAILURE"
        const val EVENT_FILE_TRANSFER = "FILE_TRANSFER"
        const val EVENT_PORT_FORWARD = "PORT_FORWARD"
        const val EVENT_X11_FORWARD = "X11_FORWARD"
    }
    
    fun getDisplayTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    fun getDisplayEvent(): String {
        return when (eventType) {
            EVENT_COMMAND -> "Command: ${command?.take(50) ?: ""}"
            EVENT_CONNECT -> "Connected to $host:$port"
            EVENT_DISCONNECT -> "Disconnected from $host:$port"
            EVENT_AUTH_SUCCESS -> "Authentication successful"
            EVENT_AUTH_FAILURE -> "Authentication failed"
            EVENT_FILE_TRANSFER -> "File transfer"
            EVENT_PORT_FORWARD -> "Port forwarding"
            EVENT_X11_FORWARD -> "X11 forwarding"
            else -> eventType
        }
    }
}
