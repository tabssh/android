package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.Identity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Identity entity
 * Provides database access for reusable credential identities
 */
@Dao
interface IdentityDao {
    
    @Query("SELECT * FROM identities ORDER BY name ASC")
    fun getAllIdentities(): Flow<List<Identity>>
    
    @Query("SELECT * FROM identities ORDER BY name ASC")
    suspend fun getAllIdentitiesList(): List<Identity>
    
    @Query("SELECT * FROM identities WHERE id = :identityId")
    suspend fun getIdentityById(identityId: String): Identity?
    
    @Query("SELECT * FROM identities WHERE name = :name LIMIT 1")
    suspend fun getIdentityByName(name: String): Identity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: Identity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(identities: List<Identity>)
    
    @Update
    suspend fun update(identity: Identity)
    
    @Delete
    suspend fun delete(identity: Identity)
    
    @Query("DELETE FROM identities WHERE id = :identityId")
    suspend fun deleteById(identityId: String)
    
    @Query("DELETE FROM identities")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM identities")
    suspend fun getIdentityCount(): Int
    
    /**
     * Update sync metadata
     */
    @Query("UPDATE identities SET last_synced_at = :timestamp, sync_version = sync_version + 1, sync_device_id = :deviceId WHERE id = :identityId")
    suspend fun updateSyncMetadata(identityId: String, timestamp: Long, deviceId: String)
}
