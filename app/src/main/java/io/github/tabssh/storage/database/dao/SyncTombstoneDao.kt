package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tabssh.storage.database.entities.SyncTombstone

/**
 * H6 — tombstone access. Two insert strategies by design:
 *  - [record] (REPLACE): explicit delete-site instrumentation writes the
 *    accurate deletedAt/deviceId, overwriting any earlier approximate row.
 *  - [recordIfAbsent] (IGNORE): the diff-at-collect backstop only fills gaps —
 *    it must never clobber an accurate explicit timestamp with `now`.
 */
@Dao
interface SyncTombstoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(tombstone: SyncTombstone)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordIfAbsent(tombstone: SyncTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordAll(tombstones: List<SyncTombstone>)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAll(): List<SyncTombstone>

    @Query("SELECT * FROM sync_tombstones WHERE entity_type = :entityType")
    suspend fun getByType(entityType: String): List<SyncTombstone>

    @Query("SELECT * FROM sync_tombstones WHERE entity_type = :entityType AND entity_key = :entityKey LIMIT 1")
    suspend fun find(entityType: String, entityKey: String): SyncTombstone?

    /**
     * Clears a tombstone when its entity is legitimately resurrected by a
     * strictly-newer copy from a peer, so the resurrection is not re-deleted.
     */
    @Query("DELETE FROM sync_tombstones WHERE entity_type = :entityType AND entity_key = :entityKey")
    suspend fun clear(entityType: String, entityKey: String)

    @Query("DELETE FROM sync_tombstones WHERE deleted_at < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)
}
