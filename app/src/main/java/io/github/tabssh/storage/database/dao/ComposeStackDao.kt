package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ComposeStack
import kotlinx.coroutines.flow.Flow

@Dao
interface ComposeStackDao {

    @Query("SELECT * FROM compose_stacks WHERE docker_host_id = :hostId ORDER BY name ASC")
    fun getStacksForHost(hostId: Long): Flow<List<ComposeStack>>

    @Query("SELECT * FROM compose_stacks WHERE docker_host_id = :hostId ORDER BY name ASC")
    suspend fun getStacksForHostList(hostId: Long): List<ComposeStack>

    @Query("SELECT * FROM compose_stacks WHERE id = :id")
    suspend fun getById(id: Long): ComposeStack?

    @Query("SELECT * FROM compose_stacks WHERE docker_host_id = :hostId AND name = :name")
    suspend fun getByHostAndName(hostId: Long, name: String): ComposeStack?

    @Insert
    suspend fun insert(stack: ComposeStack): Long

    @Update
    suspend fun update(stack: ComposeStack)

    @Delete
    suspend fun delete(stack: ComposeStack)

    @Query("DELETE FROM compose_stacks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Cascade-by-convention — call from the DockerHost delete path. */
    @Query("DELETE FROM compose_stacks WHERE docker_host_id = :hostId")
    suspend fun deleteForHost(hostId: Long)

    @Query("UPDATE compose_stacks SET last_known_status = :status, updated_at = :timestamp WHERE id = :id")
    suspend fun updateLastKnownStatus(id: Long, status: String?, timestamp: Long)
}
