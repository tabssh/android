package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * DAO for sync state operations
 */
@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType AND entity_id = :entityId LIMIT 1")
    suspend fun getSyncState(entityType: String, entityId: String): SyncState?

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType AND entity_id = :entityId LIMIT 1")
    fun getSyncStateFlow(entityType: String, entityId: String): Flow<SyncState?>

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType")
    suspend fun getSyncStatesByType(entityType: String): List<SyncState>

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType")
    fun getSyncStatesByTypeFlow(entityType: String): Flow<List<SyncState>>

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType AND last_synced_at > :timestamp")
    suspend fun getEntitiesModifiedSince(entityType: String, timestamp: Long): List<SyncState>

    @Query("SELECT * FROM sync_state WHERE conflict_status = :conflictStatus")
    suspend fun getConflictingEntities(conflictStatus: String = SyncState.CONFLICT_STATUS_PENDING): List<SyncState>

    @Query("SELECT * FROM sync_state WHERE conflict_status IS NOT NULL")
    fun getConflictingEntitiesFlow(): Flow<List<SyncState>>

    @Query("SELECT COUNT(*) FROM sync_state WHERE conflict_status = :conflictStatus")
    suspend fun getConflictCount(conflictStatus: String = SyncState.CONFLICT_STATUS_PENDING): Int

    @Query("SELECT COUNT(*) FROM sync_state WHERE conflict_status IS NOT NULL")
    fun getConflictCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(syncState: SyncState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncStates(syncStates: List<SyncState>)

    @Update
    suspend fun updateSyncState(syncState: SyncState)

    @Delete
    suspend fun deleteSyncState(syncState: SyncState)

    @Query("DELETE FROM sync_state WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteSyncStateByEntity(entityType: String, entityId: String)

    @Query("DELETE FROM sync_state WHERE entity_type = :entityType")
    suspend fun deleteSyncStatesByType(entityType: String)

    @Query("DELETE FROM sync_state")
    suspend fun deleteAllSyncStates()

    @Query("UPDATE sync_state SET conflict_status = :status WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun updateConflictStatus(entityType: String, entityId: String, status: String?)

    @Query("UPDATE sync_state SET last_synced_at = :timestamp, sync_version = :version WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun updateSyncTimestamp(entityType: String, entityId: String, timestamp: Long, version: Long)

    @Query("SELECT MAX(last_synced_at) FROM sync_state")
    suspend fun getLastSyncTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM sync_state")
    suspend fun getSyncStateCount(): Int
}
