package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ConnectableHost
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the internal-only `connectable_hosts` registry —
 * a unified lookup of terminal-capable Hosts-tab connections and live Cloud
 * Account instances, used by Panes (and later Cluster Commands) as one
 * stable ID space. Never rendered as its own user-facing list.
 */
@Dao
interface ConnectableHostDao {

    @Query("SELECT * FROM connectable_hosts ORDER BY name")
    fun getAll(): Flow<List<ConnectableHost>>

    @Query("SELECT * FROM connectable_hosts ORDER BY name")
    suspend fun getAllList(): List<ConnectableHost>

    @Query("SELECT * FROM connectable_hosts WHERE id = :id")
    suspend fun getById(id: String): ConnectableHost?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hosts: List<ConnectableHost>)

    @Query("DELETE FROM connectable_hosts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM connectable_hosts WHERE source_type = :sourceType")
    suspend fun deleteBySourceType(sourceType: String)

    @Query(
        "DELETE FROM connectable_hosts WHERE source_type = 'cloud_instance' " +
            "AND cloud_account_id = :cloudAccountId"
    )
    suspend fun deleteByCloudAccount(cloudAccountId: String)
}
