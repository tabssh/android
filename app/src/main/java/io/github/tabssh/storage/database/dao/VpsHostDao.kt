package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.tabssh.storage.database.entities.VpsHost
import kotlinx.coroutines.flow.Flow

@Dao
interface VpsHostDao {

    @Query("SELECT * FROM vps_hosts ORDER BY tenant ASC, hostname ASC")
    fun getAll(): Flow<List<VpsHost>>

    @Query("SELECT * FROM vps_hosts ORDER BY tenant ASC, hostname ASC")
    suspend fun getAllList(): List<VpsHost>

    @Query("SELECT * FROM vps_hosts WHERE id = :id")
    suspend fun getById(id: String): VpsHost?

    @Query("SELECT * FROM vps_hosts WHERE tenant = :tenant COLLATE NOCASE AND hostname = :hostname COLLATE NOCASE")
    suspend fun getByTenantAndHostname(tenant: String, hostname: String): VpsHost?

    @Query("SELECT * FROM vps_hosts WHERE renewal_date IS NOT NULL")
    suspend fun getAllWithRenewal(): List<VpsHost>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: VpsHost)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hosts: List<VpsHost>)

    @Update
    suspend fun update(host: VpsHost)

    @Delete
    suspend fun delete(host: VpsHost)

    @Query("DELETE FROM vps_hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM vps_hosts")
    suspend fun getCount(): Int
}
