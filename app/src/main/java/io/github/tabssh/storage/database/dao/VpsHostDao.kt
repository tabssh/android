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

    // ipv4 is the preferred import-merge key: a host's tenant label or
    // hostname alias can be renamed in a later export, but its IPv4 address
    // is effectively stable — matching on it avoids spuriously creating a
    // duplicate row when only the name changed.
    @Query("SELECT * FROM vps_hosts WHERE ipv4 = :ipv4 COLLATE NOCASE")
    suspend fun getByIpv4(ipv4: String): VpsHost?

    @Query("SELECT * FROM vps_hosts WHERE renewal_date IS NOT NULL")
    suspend fun getAllWithRenewal(): List<VpsHost>

    // Overdue-renewal confirmation flow: "yes" clears canceled_at (and the
    // caller separately updates renewal_date via `update()`); "no" sets it.
    @Query("UPDATE vps_hosts SET canceled_at = :canceledAt WHERE id = :id")
    suspend fun setCanceledAt(id: String, canceledAt: Long?)

    // Grace-window purge: only rows still marked canceled 30+ days ago are
    // removed — confirming "renewed" (which clears canceled_at) or simply
    // ignoring the prompt (canceled_at stays null) both keep the row.
    @Query("DELETE FROM vps_hosts WHERE canceled_at IS NOT NULL AND canceled_at < :cutoff")
    suspend fun deleteStaleCanceled(cutoff: Long)

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
