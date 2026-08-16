package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.NetworkRoute
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for reusable network routes (proxies and SSH jump hosts).
 */
@Dao
interface NetworkRouteDao {

    @Query("SELECT * FROM network_routes ORDER BY sort_order, name")
    fun getAll(): Flow<List<NetworkRoute>>

    @Query("SELECT * FROM network_routes ORDER BY sort_order, name")
    suspend fun getAllList(): List<NetworkRoute>

    @Query("SELECT * FROM network_routes WHERE enabled = 1 ORDER BY sort_order, name")
    suspend fun getEnabledList(): List<NetworkRoute>

    @Query("SELECT * FROM network_routes WHERE id = :id")
    suspend fun getById(id: String): NetworkRoute?

    /**
     * Routes that reference a given saved connection as their jump host — used
     * to cascade a cleanup when that connection is deleted.
     */
    @Query("SELECT * FROM network_routes WHERE connection_id = :connectionId")
    suspend fun getByConnection(connectionId: String): List<NetworkRoute>

    @Query("SELECT COUNT(*) FROM network_routes")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: NetworkRoute): Long

    @Update
    suspend fun update(route: NetworkRoute)

    @Delete
    suspend fun delete(route: NetworkRoute)

    @Query("DELETE FROM network_routes WHERE id = :id")
    suspend fun deleteById(id: String)
}
