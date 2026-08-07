package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.PortForward
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for saved SSH port-forward rules.
 */
@Dao
interface PortForwardDao {

    @Query("SELECT * FROM port_forwards ORDER BY sort_order, name")
    fun getAll(): Flow<List<PortForward>>

    @Query("SELECT * FROM port_forwards ORDER BY sort_order, name")
    suspend fun getAllList(): List<PortForward>

    @Query("SELECT * FROM port_forwards WHERE id = :id")
    suspend fun getById(id: String): PortForward?

    /**
     * Forwards eligible for auto-start: both the master `enabled` switch and
     * `auto_start` must be on. Used by the boot receiver / app launch.
     */
    @Query("SELECT * FROM port_forwards WHERE enabled = 1 AND auto_start = 1 ORDER BY sort_order, name")
    suspend fun getAutoStartEnabled(): List<PortForward>

    /**
     * Forwards that reference a given saved connection — used to cascade a
     * cleanup when that connection is deleted.
     */
    @Query("SELECT * FROM port_forwards WHERE connection_id = :connectionId")
    suspend fun getByConnection(connectionId: String): List<PortForward>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(portForward: PortForward): Long

    @Update
    suspend fun update(portForward: PortForward)

    @Delete
    suspend fun delete(portForward: PortForward)

    @Query("DELETE FROM port_forwards WHERE id = :id")
    suspend fun deleteById(id: String)
}
