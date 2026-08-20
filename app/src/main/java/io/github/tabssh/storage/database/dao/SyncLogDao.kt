package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tabssh.storage.database.entities.SyncLogEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the dedicated Sync Log — mirrors [AuditLogDao]'s
 * shape (recent/flow/retention-prune queries) so the two logs behave
 * consistently.
 */
@Dao
interface SyncLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncLogEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SyncLogEntry>)

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<SyncLogEntry>

    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAsFlow(limit: Int = 200): Flow<List<SyncLogEntry>>

    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun getCount(): Int

    @Query("DELETE FROM sync_log WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int

    @Query("DELETE FROM sync_log WHERE id IN (SELECT id FROM sync_log ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("DELETE FROM sync_log")
    suspend fun deleteAll()
}
