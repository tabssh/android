package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.HypervisorProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface HypervisorDao {
    
    @Query("SELECT * FROM hypervisors ORDER BY name ASC")
    fun getAllHypervisors(): Flow<List<HypervisorProfile>>
    
    @Query("SELECT * FROM hypervisors WHERE id = :id")
    suspend fun getById(id: Long): HypervisorProfile?
    
    @Query("SELECT * FROM hypervisors WHERE type = :type ORDER BY name ASC")
    suspend fun getByType(type: io.github.tabssh.storage.database.entities.HypervisorType): List<HypervisorProfile>
    
    @Insert
    suspend fun insert(hypervisor: HypervisorProfile): Long
    
    @Update
    suspend fun update(hypervisor: HypervisorProfile)
    
    @Delete
    suspend fun delete(hypervisor: HypervisorProfile)
    
    @Query("DELETE FROM hypervisors WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("UPDATE hypervisors SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)
}
