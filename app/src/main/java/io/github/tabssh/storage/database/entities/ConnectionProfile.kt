package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.tabssh.ssh.auth.AuthType
import java.util.UUID

/**
 * Database entity representing an SSH connection profile
 */
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

    // Sync metadata fields
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = 0,

    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String = ""
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