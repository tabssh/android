package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.AuditLogEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for audit log operations
 */
@Dao
interface AuditLogDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntry)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AuditLogEntry>)
    
    @Update
    suspend fun update(entry: AuditLogEntry)
    
    @Delete
    suspend fun delete(entry: AuditLogEntry)
    
    @Query("SELECT * FROM audit_log WHERE id = :id")
    suspend fun getById(id: String): AuditLogEntry?
    
    @Query("SELECT * FROM audit_log WHERE connection_id = :connectionId ORDER BY timestamp DESC")
    fun getByConnectionId(connectionId: String): Flow<List<AuditLogEntry>>
    
    @Query("SELECT * FROM audit_log WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySessionId(sessionId: String): List<AuditLogEntry>
    
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AuditLogEntry>
    
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<AuditLogEntry>>
    
    @Query("SELECT * FROM audit_log WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<AuditLogEntry>
    
    @Query("SELECT * FROM audit_log WHERE event_type = :eventType ORDER BY timestamp DESC")
    suspend fun getByEventType(eventType: String): List<AuditLogEntry>
    
    @Query("SELECT SUM(size_bytes) FROM audit_log")
    suspend fun getTotalSize(): Long?
    
    @Query("SELECT SUM(size_bytes) FROM audit_log WHERE connection_id = :connectionId")
    suspend fun getTotalSizeForConnection(connectionId: String): Long?
    
    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM audit_log WHERE connection_id = :connectionId")
    suspend fun getCountForConnection(connectionId: String): Int
    
    @Query("DELETE FROM audit_log WHERE connection_id = :connectionId")
    suspend fun deleteByConnectionId(connectionId: String)
    
    @Query("DELETE FROM audit_log WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long): Int
    
    @Query("DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int
    
    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM audit_log WHERE connection_id = :connectionId AND event_type = :eventType ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEventForConnection(connectionId: String, eventType: String): AuditLogEntry?
}
