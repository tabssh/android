package io.github.tabssh.storage.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for SSH connection profiles
 */
@Dao
interface ConnectionDao {
    
    @Query("SELECT * FROM connections ORDER BY sort_order, name")
    fun getAllConnections(): Flow<List<ConnectionProfile>>
    
    @Query("SELECT * FROM connections ORDER BY sort_order, name")
    fun getAllConnectionsLiveData(): LiveData<List<ConnectionProfile>>
    
    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: Long): ConnectionProfile?
    
    @Query("SELECT * FROM connections WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ConnectionProfile?

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getConnectionById(id: String): ConnectionProfile?

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getConnection(id: String): ConnectionProfile?
    
    @Query("SELECT * FROM connections WHERE group_id = :groupId ORDER BY sort_order, name")
    suspend fun getConnectionsByGroup(groupId: String): List<ConnectionProfile>
    
    @Query("SELECT * FROM connections WHERE name LIKE :query OR host LIKE :query ORDER BY name")
    suspend fun searchConnections(query: String): List<ConnectionProfile>

    @Query("SELECT * FROM connections ORDER BY name")
    suspend fun getAllConnectionsList(): List<ConnectionProfile>
    
    @Query("SELECT * FROM connections ORDER BY last_connected DESC LIMIT :limit")
    suspend fun getRecentConnections(limit: Int = 10): List<ConnectionProfile>

    @Query("SELECT * FROM connections WHERE connection_count > 0 ORDER BY connection_count DESC, last_connected DESC LIMIT :limit")
    suspend fun getFrequentlyUsedConnections(limit: Int = 10): List<ConnectionProfile>
    
    @Query("SELECT * FROM connections WHERE group_id IS NULL ORDER BY name ASC")
    fun getUngroupedConnections(): Flow<List<ConnectionProfile>>
    
    @Query("SELECT COUNT(*) FROM connections")
    suspend fun getConnectionCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnection(connection: ConnectionProfile): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnections(connections: List<ConnectionProfile>)
    
    @Update
    suspend fun updateConnection(connection: ConnectionProfile)
    
    @Delete
    suspend fun deleteConnection(connection: ConnectionProfile)
    
    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteConnectionById(id: String)
    
    @Query("DELETE FROM connections")
    suspend fun deleteAllConnections()
    
    @Query("UPDATE connections SET last_connected = :timestamp, connection_count = connection_count + 1 WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE connections SET sort_order = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)
    
    @Query("SELECT DISTINCT group_id FROM connections WHERE group_id IS NOT NULL")
    suspend fun getAllGroups(): List<String>
    
    @Transaction
    suspend fun swapSortOrder(id1: String, id2: String) {
        val conn1 = getConnectionById(id1)
        val conn2 = getConnectionById(id2)
        if (conn1 != null && conn2 != null) {
            updateSortOrder(id1, conn2.sortOrder)
            updateSortOrder(id2, conn1.sortOrder)
        }
    }
}