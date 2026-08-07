package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * PortForward entity — a saved, persistent SSH port-forward rule.
 *
 * Unlike the runtime-only tunnels created ad hoc on a live session, a
 * PortForward survives app/device restarts and knows its own SSH endpoint.
 * It is a standalone feature (not attached to a single ConnectionProfile,
 * since one server can have many forwards).
 *
 * The SSH endpoint is specified one of two ways:
 *   - `connectionId` set  → reuse an existing saved ConnectionProfile
 *     (its host, port, username, identity/key).
 *   - `connectionId` null → a manual endpoint entered by the user
 *     (`sshHost` + `sshPort` + `sshUsername` + optional `identityId`),
 *     without creating a saved connection. The coordinator builds an
 *     ephemeral, non-persisted ConnectionProfile from these fields.
 *
 * The forward parameters depend on `type` (see ForwardType).
 */
@Entity(
    tableName = "port_forwards",
    indices = [
        Index("connection_id"),
        Index("identity_id")
    ]
)
@Serializable
data class PortForward(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "type")
    val type: String = ForwardType.LOCAL.name,

    // --- SSH endpoint: saved connection OR manual host ---

    @ColumnInfo(name = "connection_id")
    val connectionId: String? = null,

    @ColumnInfo(name = "ssh_host")
    val sshHost: String? = null,

    @ColumnInfo(name = "ssh_port")
    val sshPort: Int = 22,

    @ColumnInfo(name = "ssh_username")
    val sshUsername: String? = null,

    @ColumnInfo(name = "identity_id")
    val identityId: String? = null,

    // --- Forward parameters (meaning depends on type) ---

    // LOCAL: remote target host reachable from the SSH server.
    // REMOTE: local target host the server should reach back to.
    // DYNAMIC: unused (SOCKS chooses the destination per request).
    @ColumnInfo(name = "host_ip")
    val hostIp: String = "localhost",

    // LOCAL/REMOTE: the target port. DYNAMIC: unused.
    @ColumnInfo(name = "remote_port")
    val remotePort: Int = 0,

    // The local (device-side) port. 0 = use remotePort (LOCAL only).
    // DYNAMIC: the SOCKS listen port.
    @ColumnInfo(name = "local_port")
    val localPort: Int = 0,

    // --- Toggles ---

    // Master on/off. A disabled forward is never started (manually or on boot).
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    // Start automatically on app launch / device boot. Only fires when enabled.
    @ColumnInfo(name = "auto_start")
    val autoStart: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0
) {
    /**
     * The forward type, parsed from the stored string. Unknown values fall
     * back to LOCAL so a bad row can never crash the list.
     */
    val forwardType: ForwardType
        get() = ForwardType.fromString(type)

    /**
     * The effective device-side port. A blank (0) local port on a LOCAL
     * forward mirrors the remote port, matching the documented UX
     * ("Local port — blank = same as remote").
     */
    val effectiveLocalPort: Int
        get() = if (localPort > 0) localPort else remotePort

    /**
     * True when this forward uses a saved ConnectionProfile rather than a
     * manually entered SSH endpoint.
     */
    val usesSavedConnection: Boolean
        get() = !connectionId.isNullOrBlank()

    /**
     * Human-readable one-line summary of what this forward does, per type.
     */
    fun getSummary(): String {
        return when (forwardType) {
            ForwardType.LOCAL ->
                "localhost:$effectiveLocalPort → $hostIp:$remotePort"
            ForwardType.REMOTE ->
                "server:$remotePort → $hostIp:$localPort"
            ForwardType.DYNAMIC ->
                "SOCKS proxy on localhost:$localPort"
        }
    }
}

/**
 * The three SSH port-forward directions. Stored as the enum name string.
 */
@Serializable
enum class ForwardType(val displayName: String, val flag: String) {
    LOCAL("Local", "-L"),
    REMOTE("Remote", "-R"),
    DYNAMIC("Dynamic (SOCKS)", "-D");

    companion object {
        fun fromString(value: String): ForwardType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}
