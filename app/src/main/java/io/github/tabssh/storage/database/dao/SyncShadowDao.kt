package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tabssh.storage.database.entities.SyncShadow

/**
 * H6 — shadow snapshot access for the diff-at-collect backstop.
 */
@Dao
interface SyncShadowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<SyncShadow>)

    @Query("SELECT entity_key FROM sync_shadow WHERE entity_type = :entityType")
    suspend fun getKeys(entityType: String): List<String>

    @Query("DELETE FROM sync_shadow WHERE entity_type = :entityType")
    suspend fun clearType(entityType: String)
}
