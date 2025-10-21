package io.github.tabssh.storage.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import io.github.tabssh.storage.database.entities.StoredKey
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for SSH keys
 */
@Dao
interface KeyDao {
    
    @Query("SELECT * FROM stored_keys ORDER BY name")
    fun getAllKeys(): Flow<List<StoredKey>>
    
    @Query("SELECT * FROM stored_keys ORDER BY name")
    fun getAllKeysLiveData(): LiveData<List<StoredKey>>
    
    @Query("SELECT * FROM stored_keys WHERE keyId = :keyId")
    suspend fun getKeyById(keyId: String): StoredKey?

    @Query("SELECT * FROM stored_keys WHERE keyId = :keyId")
    suspend fun getKey(keyId: String): StoredKey?
    
    @Query("SELECT * FROM stored_keys WHERE name = :name")
    suspend fun getKeyByName(name: String): StoredKey?
    
    @Query("SELECT * FROM stored_keys WHERE fingerprint = :fingerprint")
    suspend fun getKeyByFingerprint(fingerprint: String): StoredKey?
    
    @Query("SELECT * FROM stored_keys WHERE key_type = :keyType ORDER BY name")
    suspend fun getKeysByType(keyType: String): List<StoredKey>
    
    @Query("SELECT * FROM stored_keys ORDER BY last_used DESC LIMIT :limit")
    suspend fun getRecentlyUsedKeys(limit: Int = 5): List<StoredKey>
    
    @Query("SELECT COUNT(*) FROM stored_keys")
    suspend fun getKeyCount(): Int
    
    @Insert
    suspend fun insertKey(key: StoredKey)
    
    @Insert
    suspend fun insertKeys(keys: List<StoredKey>)
    
    @Update
    suspend fun updateKey(key: StoredKey)
    
    @Delete
    suspend fun deleteKey(key: StoredKey)
    
    @Query("DELETE FROM stored_keys WHERE keyId = :keyId")
    suspend fun deleteKeyById(keyId: String)
    
    @Query("DELETE FROM stored_keys")
    suspend fun deleteAllKeys()
    
    @Query("UPDATE stored_keys SET last_used = :timestamp WHERE keyId = :keyId")
    suspend fun updateLastUsed(keyId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT DISTINCT key_type FROM stored_keys")
    suspend fun getAllKeyTypes(): List<String>
    
    @Query("SELECT * FROM stored_keys WHERE name LIKE :query OR comment LIKE :query ORDER BY name")
    suspend fun searchKeys(query: String): List<StoredKey>
}