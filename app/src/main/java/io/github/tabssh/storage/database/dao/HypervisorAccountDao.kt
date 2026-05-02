package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.HypervisorAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface HypervisorAccountDao {

    @Query("SELECT * FROM hypervisor_accounts ORDER BY name ASC")
    fun getAllAccounts(): Flow<List<HypervisorAccount>>

    @Query("SELECT * FROM hypervisor_accounts ORDER BY name ASC")
    suspend fun getAllAccountsList(): List<HypervisorAccount>

    @Query("SELECT * FROM hypervisor_accounts WHERE id = :id")
    suspend fun getById(id: Long): HypervisorAccount?

    @Insert
    suspend fun insert(account: HypervisorAccount): Long

    @Update
    suspend fun update(account: HypervisorAccount)

    @Delete
    suspend fun delete(account: HypervisorAccount)

    @Query("DELETE FROM hypervisor_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM hypervisor_accounts")
    suspend fun getCount(): Int
}
