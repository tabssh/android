package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.VncHost
import kotlinx.coroutines.flow.Flow

@Dao
interface VncHostDao {

    @Query("SELECT * FROM vnc_hosts ORDER BY name ASC")
    fun getAllHosts(): Flow<List<VncHost>>

    @Query("SELECT * FROM vnc_hosts ORDER BY name ASC")
    suspend fun getAllHostsList(): List<VncHost>

    @Query("SELECT * FROM vnc_hosts WHERE id = :id")
    suspend fun getById(id: String): VncHost?

    @Query("SELECT * FROM vnc_hosts WHERE group_id = :groupId ORDER BY name ASC")
    suspend fun getByGroup(groupId: String): List<VncHost>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: VncHost)

    @Update
    suspend fun update(host: VncHost)

    @Delete
    suspend fun delete(host: VncHost)

    @Query("DELETE FROM vnc_hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE vnc_hosts SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM vnc_hosts")
    suspend fun getCount(): Int

    /**
     * Orphan-safe group delete — nullify group_id for all VNC hosts that
     * belong to the given group. Call this inside the same transaction that
     * deletes the ConnectionGroup row so no host is left with a dangling
     * group_id foreign key.
     */
    @Query("UPDATE vnc_hosts SET group_id = NULL WHERE group_id = :groupId")
    suspend fun nullifyGroupId(groupId: String)
}
