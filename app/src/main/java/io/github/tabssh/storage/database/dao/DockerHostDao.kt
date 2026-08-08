package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.DockerHost
import kotlinx.coroutines.flow.Flow

@Dao
interface DockerHostDao {

    @Query("SELECT * FROM docker_hosts ORDER BY name ASC")
    fun getAllHosts(): Flow<List<DockerHost>>

    @Query("SELECT * FROM docker_hosts ORDER BY name ASC")
    suspend fun getAllList(): List<DockerHost>

    @Query("SELECT * FROM docker_hosts WHERE id = :id")
    suspend fun getById(id: Long): DockerHost?

    @Insert
    suspend fun insert(host: DockerHost): Long

    @Update
    suspend fun update(host: DockerHost)

    @Delete
    suspend fun delete(host: DockerHost)

    @Query("DELETE FROM docker_hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE docker_hosts SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)

    @Query("UPDATE docker_hosts SET last_update_check = :timestamp WHERE id = :id")
    suspend fun updateLastUpdateCheck(id: Long, timestamp: Long)

    /**
     * Orphan-safe connection delete — nullify linked_connection_id for all
     * Docker hosts that reference the deleted ConnectionProfile. Call
     * alongside connectionDao.deleteConnection() so no docker_hosts row is
     * left with a dangling linked_connection_id.
     */
    @Query("UPDATE docker_hosts SET linked_connection_id = NULL WHERE linked_connection_id = :connectionId")
    suspend fun clearLinkedConnectionId(connectionId: String)
}
