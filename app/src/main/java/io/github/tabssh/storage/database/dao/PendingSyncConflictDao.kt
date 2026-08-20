package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tabssh.storage.database.entities.PendingSyncConflict
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for conflicts a headless sync deferred to the user.
 */
@Dao
interface PendingSyncConflictDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: PendingSyncConflict)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conflicts: List<PendingSyncConflict>)

    @Delete
    suspend fun delete(conflict: PendingSyncConflict)

    @Query("DELETE FROM pending_sync_conflicts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_sync_conflicts")
    suspend fun deleteAll()

    @Query("SELECT * FROM pending_sync_conflicts ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingSyncConflict>

    @Query("SELECT * FROM pending_sync_conflicts ORDER BY created_at ASC")
    fun getAllFlow(): Flow<List<PendingSyncConflict>>

    @Query("SELECT COUNT(*) FROM pending_sync_conflicts")
    suspend fun getCount(): Int
}
