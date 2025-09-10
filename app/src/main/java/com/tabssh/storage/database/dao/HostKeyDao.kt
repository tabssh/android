package com.tabssh.storage.database.dao

import androidx.room.*
import com.tabssh.storage.database.entities.HostKeyEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for host keys (known_hosts)
 */
@Dao
interface HostKeyDao {
    
    @Query("SELECT * FROM host_keys ORDER BY hostname, port")
    fun getAllHostKeys(): Flow<List<HostKeyEntry>>
    
    @Query("SELECT * FROM host_keys WHERE id = :id")
    suspend fun getHostKeyById(id: String): HostKeyEntry?
    
    @Query("SELECT * FROM host_keys WHERE hostname = :hostname AND port = :port")
    suspend fun getHostKey(hostname: String, port: Int): HostKeyEntry?
    
    @Query("SELECT * FROM host_keys WHERE hostname LIKE :hostname")
    suspend fun getHostKeysByHostname(hostname: String): List<HostKeyEntry>
    
    @Query("SELECT * FROM host_keys WHERE fingerprint = :fingerprint")
    suspend fun getHostKeysByFingerprint(fingerprint: String): List<HostKeyEntry>
    
    @Query("SELECT COUNT(*) FROM host_keys")
    suspend fun getHostKeyCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHostKey(hostKey: HostKeyEntry)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHostKeys(hostKeys: List<HostKeyEntry>)
    
    @Update
    suspend fun updateHostKey(hostKey: HostKeyEntry)
    
    @Delete
    suspend fun deleteHostKey(hostKey: HostKeyEntry)
    
    @Query("DELETE FROM host_keys WHERE id = :id")
    suspend fun deleteHostKeyById(id: String)
    
    @Query("DELETE FROM host_keys WHERE hostname = :hostname")
    suspend fun deleteHostKeysByHostname(hostname: String)
    
    @Query("DELETE FROM host_keys")
    suspend fun deleteAllHostKeys()
    
    @Query("UPDATE host_keys SET last_verified = :timestamp WHERE id = :id")
    suspend fun updateLastVerified(id: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE host_keys SET trust_level = :trustLevel WHERE id = :id")
    suspend fun updateTrustLevel(id: String, trustLevel: String)
    
    @Query("SELECT DISTINCT hostname FROM host_keys ORDER BY hostname")
    suspend fun getAllHostnames(): List<String>
    
    /**
     * Check if a host key exists and matches
     */
    suspend fun verifyHostKey(hostname: String, port: Int, publicKey: String, fingerprint: String): HostKeyVerificationResult {
        val existingKey = getHostKey(hostname, port)
        
        return when {
            existingKey == null -> HostKeyVerificationResult.NEW_HOST
            existingKey.publicKey == publicKey && existingKey.fingerprint == fingerprint -> {
                updateLastVerified(existingKey.id)
                HostKeyVerificationResult.ACCEPTED
            }
            else -> HostKeyVerificationResult.CHANGED_KEY
        }
    }
}

enum class HostKeyVerificationResult {
    ACCEPTED,           // Key matches known host
    NEW_HOST,          // First time connecting
    CHANGED_KEY,       // Host key has changed (security risk)
    INVALID_KEY        // Malformed key
}