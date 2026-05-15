package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.tabssh.ssh.auth.AuthType
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Database entity representing an SSH connection profile
 */
@Serializable
@Entity(tableName = "connections")
data class ConnectionProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "host")
    val host: String,
    
    @ColumnInfo(name = "port")
    val port: Int = 22,
    
    @ColumnInfo(name = "username")
    val username: String,
    
    /**
     * Wave 2.3 — protocol. "ssh" (default) or "telnet". Telnet skips key/
     * password auth entirely and just opens a TCP connection; auth happens
     * interactively at the remote prompt.
     */
    @ColumnInfo(name = "protocol")
    val protocol: String = "ssh",

    @ColumnInfo(name = "auth_type")
    val authType: String = AuthType.PASSWORD.name,
    
    @ColumnInfo(name = "key_id")
    val keyId: String? = null,
    
    @ColumnInfo(name = "save_password")
    val savePassword: Boolean = false,
    
    @ColumnInfo(name = "terminal_type")
    val terminalType: String = "xterm-256color",
    
    @ColumnInfo(name = "encoding")
    val encoding: String = "UTF-8",
    
    @ColumnInfo(name = "compression")
    val compression: Boolean = true,
    
    @ColumnInfo(name = "keep_alive")
    val keepAlive: Boolean = true,
    
    @ColumnInfo(name = "x11_forwarding")
    val x11Forwarding: Boolean = false,
    
    @ColumnInfo(name = "use_mosh")
    val useMosh: Boolean = false,

    @ColumnInfo(name = "multiplexer_mode")
    val multiplexerMode: String = "OFF", // OFF, AUTO_ATTACH, CREATE_NEW, ASK

    @ColumnInfo(name = "multiplexer_session_name")
    val multiplexerSessionName: String? = null, // Custom session name (e.g., "main", "dev")

    @ColumnInfo(name = "port_knock_enabled")
    val portKnockEnabled: Boolean? = null, // null = use global default, true/false = override
    
    @ColumnInfo(name = "port_knock_sequence")
    val portKnockSequence: String? = null, // JSON: [{"port":7000,"protocol":"TCP"},{"port":8000,"protocol":"UDP"}]
    
    @ColumnInfo(name = "port_knock_delay_ms")
    val portKnockDelayMs: Int = 100, // Delay between knocks in milliseconds
    
    @ColumnInfo(name = "connect_timeout")
    val connectTimeout: Int = 15,
    
    @ColumnInfo(name = "read_timeout")
    val readTimeout: Int = 30,
    
    @ColumnInfo(name = "proxy_host")
    val proxyHost: String? = null,

    @ColumnInfo(name = "proxy_port")
    val proxyPort: Int? = null,

    @ColumnInfo(name = "proxy_type")
    val proxyType: String? = null, // "HTTP", "SOCKS4", "SOCKS5", "SSH"

    @ColumnInfo(name = "proxy_username")
    val proxyUsername: String? = null,

    @ColumnInfo(name = "proxy_auth_type")
    val proxyAuthType: String? = null, // For SSH jump host

    @ColumnInfo(name = "proxy_key_id")
    val proxyKeyId: String? = null, // For SSH jump host with key auth
    
    @ColumnInfo(name = "identity_id")
    val identityId: String? = null, // Link to reusable identity
    
    @ColumnInfo(name = "theme")
    val theme: String = "dracula",

    @ColumnInfo(name = "post_connect_script")
    val postConnectScript: String? = null, // Commands to run after connection (one per line)

    /**
     * Wave 1.2 — per-host environment variables. Multi-line "KEY=value"
     * lines applied via JSch session.setEnv() before opening the shell
     * channel. Server must allow them in sshd_config (AcceptEnv) or they
     * are silently ignored. DB v17 → v18.
     */
    @ColumnInfo(name = "env_vars")
    val envVars: String? = null,

    /**
     * Wave 1.5 — per-host SSH agent forwarding. JSch's `ChannelShell.setAgentForwarding(true)`.
     */
    @ColumnInfo(name = "agent_forwarding")
    val agentForwarding: Boolean = false,

    /**
     * Issue #37 — Remote command to run instead of opening a login shell.
     *
     * Maps to the OpenSSH `RemoteCommand` directive. When non-blank, the
     * connection opens a JSch `ChannelExec` with this as its command (with
     * a PTY allocated, equivalent to `RequestTTY yes`) instead of the
     * usual `ChannelShell`.
     *
     * Required for hosts like `shell.sourceforge.net` that need an explicit
     * command (e.g. `create`) to spawn a shell, forced-`command="…"` jails
     * in `authorized_keys`, gateway/menu hosts, and SFTP-only accounts.
     *
     * Empty / null = open a normal login shell (default, what 99% of hosts
     * want). DB v23 → v24.
     */
    @ColumnInfo(name = "remote_command")
    val remoteCommand: String? = null,

    /** Issue #6 — "auto" / "ipv4" / "ipv6". DB v24 → v25. */
    @ColumnInfo(name = "ip_mode")
    val ipMode: String = "auto",

    @ColumnInfo(name = "font_size_override")
    val fontSizeOverride: Int? = null, // null = use global default, otherwise override

    /**
     * Wave 3.1 — Per-host color tag. ARGB int (e.g. 0xFFE53935 = red).
     * 0 (default) = no tag. Rendered as a thin coloured strip on the
     * connection row and a small dot on the tab.
     */
    @ColumnInfo(name = "color_tag")
    val colorTag: Int = 0,

    @ColumnInfo(name = "group_id")
    val groupId: String? = null,
    
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = 0,
    
    @ColumnInfo(name = "connection_count")
    val connectionCount: Int = 0,

    @ColumnInfo(name = "advanced_settings")
    val advancedSettings: String? = null, // JSON string

    /**
     * Per-host notification alert prefs. NotificationAlertMode values:
     *   0 = NEVER (silent), 1 = ALWAYS, 2 = ON_ERROR.
     * The persistent per-host status notification always uses the
     * `ssh_silent` channel; these only control whether the *additional*
     * one-shot alert (on `ssh_alerts` channel) fires on a state event.
     * Defaults to NEVER for both — no surprise sound out of the box.
     * DB v29 → v30.
     */
    @ColumnInfo(name = "notif_sound_mode")
    val notifSoundMode: Int = 0,

    @ColumnInfo(name = "notif_vibrate_mode")
    val notifVibrateMode: Int = 0,

    // Sync metadata fields
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String = "",

    /**
     * DB v30 → v31 — OCI instance binding.
     *
     * When non-null this profile was created via the OCI Manager "SSH Connect"
     * flow and is linked to the OCI Compute instance with this OCID. Used to
     * look up and re-use the saved SSH settings (username, port, auth method,
     * key) the next time the user taps SSH Connect for the same instance.
     *
     * Null for every profile created through the normal add-connection flow.
     */
    @ColumnInfo(name = "oci_instance_id")
    val ociInstanceId: String? = null
) {
    fun getAuthTypeEnum(): AuthType {
        return try {
            AuthType.valueOf(authType)
        } catch (e: IllegalArgumentException) {
            AuthType.PASSWORD
        }
    }
    
    fun getDisplayName(): String {
        return if (name.isNotBlank()) name else "$username@$host:$port"
    }
    
    fun isActive(): Boolean {
        // Determine if this connection profile is currently active
        // by checking if it has been recently connected
        val recentThreshold = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes
        return lastConnected > 0 && lastConnected > recentThreshold
    }
}