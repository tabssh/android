package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.SingleContainerConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface SingleContainerConfigDao {

    @Query("SELECT * FROM single_container_configs WHERE docker_host_id = :hostId ORDER BY name ASC")
    fun getConfigsForHost(hostId: Long): Flow<List<SingleContainerConfig>>

    @Query("SELECT * FROM single_container_configs WHERE docker_host_id = :hostId ORDER BY name ASC")
    suspend fun getConfigsForHostList(hostId: Long): List<SingleContainerConfig>

    @Query("SELECT * FROM single_container_configs WHERE id = :id")
    suspend fun getById(id: Long): SingleContainerConfig?

    @Query("SELECT * FROM single_container_configs WHERE docker_host_id = :hostId AND name = :name")
    suspend fun getByHostAndName(hostId: Long, name: String): SingleContainerConfig?

    @Insert
    suspend fun insert(config: SingleContainerConfig): Long

    @Update
    suspend fun update(config: SingleContainerConfig)

    @Delete
    suspend fun delete(config: SingleContainerConfig)

    @Query("DELETE FROM single_container_configs WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Cascade-by-convention — call from the DockerHost delete path. */
    @Query("DELETE FROM single_container_configs WHERE docker_host_id = :hostId")
    suspend fun deleteForHost(hostId: Long)

    @Query("UPDATE single_container_configs SET last_known_status = :status, updated_at = :timestamp WHERE id = :id")
    suspend fun updateLastKnownStatus(id: Long, status: String?, timestamp: Long)
}
