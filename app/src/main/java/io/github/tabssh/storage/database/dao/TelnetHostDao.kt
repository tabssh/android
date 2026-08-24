package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.TelnetHost
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for direct Telnet hosts.
 */
@Dao
interface TelnetHostDao {

    @Query("SELECT * FROM telnet_hosts ORDER BY sort_order, name")
    fun getAll(): Flow<List<TelnetHost>>

    @Query("SELECT * FROM telnet_hosts ORDER BY sort_order, name")
    suspend fun getAllList(): List<TelnetHost>

    @Query("SELECT * FROM telnet_hosts WHERE id = :id")
    suspend fun getById(id: String): TelnetHost?

    @Query("SELECT * FROM telnet_hosts WHERE group_id = :groupId ORDER BY sort_order, name")
    suspend fun getByGroup(groupId: String): List<TelnetHost>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: TelnetHost)

    @Update
    suspend fun update(host: TelnetHost)

    @Delete
    suspend fun delete(host: TelnetHost)

    @Query("DELETE FROM telnet_hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Orphan-safe group delete — nullify group_id for all telnet hosts that
     * belong to the given group. Call this inside the same transaction that
     * deletes the ConnectionGroup row so no host is left with a dangling
     * group_id foreign key.
     */
    @Query("UPDATE telnet_hosts SET group_id = NULL WHERE group_id = :groupId")
    suspend fun nullifyGroupId(groupId: String)
}
