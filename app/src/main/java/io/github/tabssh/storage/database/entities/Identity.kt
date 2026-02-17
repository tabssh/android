package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.tabssh.ssh.auth.AuthType

/**
 * Identity entity - Reusable credential set
 * Allows users to create named identities with username and authentication method
 * that can be shared across multiple connections
 * 
 * Example: "work-admin" identity with username "admin" and specific SSH key
 * can be used for 20 different servers
 */
@Entity(tableName = "identities")
data class Identity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "username")
    val username: String,
    
    @ColumnInfo(name = "auth_type")
    val authType: AuthType,
    
    @ColumnInfo(name = "key_id")
    val keyId: String? = null,

    @ColumnInfo(name = "password")
    val password: String? = null, // Encrypted password for PASSWORD auth type

    @ColumnInfo(name = "description")
    val description: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis(),
    
    // Sync metadata fields
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,
    
    @ColumnInfo(name = "sync_version")
    val syncVersion: Int = 1,
    
    @ColumnInfo(name = "sync_device_id")
    val syncDeviceId: String? = null
) {
    /**
     * Get display name with username
     */
    fun getDisplayName(): String {
        return "$name ($username)"
    }
    
    /**
     * Get auth type display string
     */
    fun getAuthTypeDisplay(): String {
        return when (authType) {
            AuthType.PASSWORD -> "Password"
            AuthType.PUBLIC_KEY -> "SSH Key"
            AuthType.KEYBOARD_INTERACTIVE -> "Keyboard Interactive"
            AuthType.GSSAPI -> "GSSAPI"
        }
    }
}
