package com.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Database entity representing a stored SSH private key
 */
@Entity(tableName = "stored_keys")
data class StoredKey(
    @PrimaryKey
    val keyId: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "key_type")
    val keyType: String, // RSA, DSA, ECDSA, Ed25519
    
    @ColumnInfo(name = "comment")
    val comment: String? = null,
    
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = 0,
    
    @ColumnInfo(name = "requires_passphrase")
    val requiresPassphrase: Boolean = false,
    
    @ColumnInfo(name = "key_size")
    val keySize: Int? = null
) {
    fun getDisplayName(): String {
        return if (comment.isNullOrBlank()) {
            "$name ($keyType)"
        } else {
            "$name - $comment"
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