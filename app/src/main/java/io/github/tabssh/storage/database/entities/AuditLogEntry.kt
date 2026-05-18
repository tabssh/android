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
        // Session lifecycle
        const val EVENT_SESSION_START   = "SESSION_START"
        const val EVENT_SESSION_END     = "SESSION_END"
        const val EVENT_CONNECT         = "CONNECT"
        const val EVENT_DISCONNECT      = "DISCONNECT"
        // Auth
        const val EVENT_AUTH_SUCCESS    = "AUTH_SUCCESS"
        const val EVENT_AUTH_FAILURE    = "AUTH_FAILURE"
        const val EVENT_KEY_USAGE       = "KEY_USAGE"      // SSH key used for auth
        // Host key trust
        const val EVENT_HOST_KEY_ACCEPTED = "HOST_KEY_ACCEPTED"
        const val EVENT_HOST_KEY_REJECTED = "HOST_KEY_REJECTED"
        const val EVENT_HOST_KEY_CHANGED  = "HOST_KEY_CHANGED"  // TOFU mismatch
        // Commands and output
        const val EVENT_COMMAND         = "COMMAND"
        const val EVENT_OUTPUT          = "OUTPUT"
        // File operations
        const val EVENT_SFTP_UPLOAD     = "SFTP_UPLOAD"
        const val EVENT_SFTP_DOWNLOAD   = "SFTP_DOWNLOAD"
        const val EVENT_SFTP_DELETE     = "SFTP_DELETE"
        const val EVENT_SFTP_MKDIR      = "SFTP_MKDIR"
        const val EVENT_FILE_TRANSFER   = "FILE_TRANSFER"   // kept for back-compat
        // Forwarding
        const val EVENT_PORT_FORWARD_OPEN  = "PORT_FORWARD_OPEN"
        const val EVENT_PORT_FORWARD_CLOSE = "PORT_FORWARD_CLOSE"
        const val EVENT_PORT_FORWARD       = "PORT_FORWARD"  // kept for back-compat
        const val EVENT_X11_FORWARD        = "X11_FORWARD"
        // Admin / configuration
        const val EVENT_CONFIG_CHANGE   = "CONFIG_CHANGE"   // settings changed by MDM or user
    }
    
    fun getDisplayTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    fun getDisplayEvent(): String {
        return when (eventType) {
            EVENT_SESSION_START      -> "Session started — $user@$host:$port"
            EVENT_SESSION_END        -> "Session ended — $user@$host:$port"
            EVENT_CONNECT            -> "Connected to $host:$port"
            EVENT_DISCONNECT         -> "Disconnected from $host:$port"
            EVENT_AUTH_SUCCESS       -> "Authenticated — $user@$host:$port"
            EVENT_AUTH_FAILURE       -> "Auth failed — $user@$host:$port"
            EVENT_KEY_USAGE          -> "Key auth — ${metadata ?: "key used"}"
            EVENT_HOST_KEY_ACCEPTED  -> "Host key accepted — $host"
            EVENT_HOST_KEY_REJECTED  -> "Host key REJECTED — $host"
            EVENT_HOST_KEY_CHANGED   -> "⚠️ Host key CHANGED — $host"
            EVENT_COMMAND            -> "Command: ${command?.take(60) ?: ""}"
            EVENT_OUTPUT             -> "Output captured"
            EVENT_SFTP_UPLOAD        -> "SFTP upload: ${metadata ?: ""}"
            EVENT_SFTP_DOWNLOAD      -> "SFTP download: ${metadata ?: ""}"
            EVENT_SFTP_DELETE        -> "SFTP delete: ${metadata ?: ""}"
            EVENT_SFTP_MKDIR         -> "SFTP mkdir: ${metadata ?: ""}"
            EVENT_FILE_TRANSFER      -> "File transfer"
            EVENT_PORT_FORWARD_OPEN  -> "Port forward opened: ${metadata ?: ""}"
            EVENT_PORT_FORWARD_CLOSE -> "Port forward closed: ${metadata ?: ""}"
            EVENT_PORT_FORWARD       -> "Port forwarding"
            EVENT_X11_FORWARD        -> "X11 forwarding"
            EVENT_CONFIG_CHANGE      -> "Config changed: ${metadata ?: ""}"
            else                     -> eventType
        }
    }
}
