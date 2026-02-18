package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Database entity representing a known host key entry
 */
@Serializable
@Entity(tableName = "host_keys")
data class HostKeyEntry(
    @PrimaryKey
    val id: String, // host:port format
    
    @ColumnInfo(name = "hostname")
    val hostname: String,
    
    @ColumnInfo(name = "port")
    val port: Int,
    
    @ColumnInfo(name = "key_type")
    val keyType: String, // ssh-rsa, ssh-ed25519, etc.
    
    @ColumnInfo(name = "public_key")
    val publicKey: String, // Base64 encoded public key
    
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    
    @ColumnInfo(name = "first_seen")
    val firstSeen: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_verified")
    val lastVerified: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "trust_level")
    val trustLevel: String = "UNKNOWN", // UNKNOWN, ACCEPTED, VERIFIED

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
    companion object {
        fun createId(hostname: String, port: Int): String {
            return "$hostname:$port"
        }
    }
    
    fun getShortFingerprint(): String {
        return if (fingerprint.length > 20) {
            "${fingerprint.take(8)}...${fingerprint.takeLast(8)}"
        } else {
            fingerprint
        }
    }
}