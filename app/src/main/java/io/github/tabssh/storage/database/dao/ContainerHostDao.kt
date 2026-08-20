package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerHostDao {

    @Query("SELECT * FROM container_hosts ORDER BY name ASC")
    fun getAllHosts(): Flow<List<ContainerHost>>

    @Query("SELECT * FROM container_hosts ORDER BY name ASC")
    suspend fun getAllList(): List<ContainerHost>

    @Query("SELECT * FROM container_hosts WHERE id = :id")
    suspend fun getById(id: Long): ContainerHost?

    @Insert
    suspend fun insert(host: ContainerHost): Long

    @Update
    suspend fun update(host: ContainerHost)

    @Delete
    suspend fun delete(host: ContainerHost)

    @Query("DELETE FROM container_hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE container_hosts SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)

    @Query("UPDATE container_hosts SET last_update_check = :timestamp WHERE id = :id")
    suspend fun updateLastUpdateCheck(id: Long, timestamp: Long)

    /**
     * Orphan-safe connection delete — nullify linked_connection_id for all
     * Docker hosts that reference the deleted ConnectionProfile. Call
     * alongside connectionDao.deleteConnection() so no container_hosts row is
     * left with a dangling linked_connection_id.
     */
    @Query("UPDATE container_hosts SET linked_connection_id = NULL WHERE linked_connection_id = :connectionId")
    suspend fun clearLinkedConnectionId(connectionId: String)
}
