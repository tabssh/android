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

    /** Wave 7.1 — Sync upsert: REPLACE on PK conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForSync(hypervisor: HypervisorProfile): Long

    @Query("SELECT * FROM hypervisors ORDER BY name ASC")
    suspend fun getAllList(): List<HypervisorProfile>
    
    @Update
    suspend fun update(hypervisor: HypervisorProfile)
    
    @Delete
    suspend fun delete(hypervisor: HypervisorProfile)
    
    @Query("DELETE FROM hypervisors WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("UPDATE hypervisors SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)

    @Query("UPDATE hypervisors SET is_xen_orchestra = :isXO WHERE id = :id")
    suspend fun updateIsXenOrchestra(id: Long, isXO: Boolean)
}
