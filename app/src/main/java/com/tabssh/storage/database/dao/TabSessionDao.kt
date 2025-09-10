package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.TabSession
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for tab sessions
 */
@Dao
interface TabSessionDao {
    
    @Query("SELECT * FROM tab_sessions ORDER BY tab_order")
    fun getAllSessions(): Flow<List<TabSession>>
    
    @Query("SELECT * FROM tab_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): TabSession?
    
    @Query("SELECT * FROM tab_sessions WHERE connection_id = :connectionId")
    suspend fun getSessionsByConnectionId(connectionId: String): List<TabSession>
    
    @Query("SELECT * FROM tab_sessions WHERE is_active = 1 ORDER BY tab_order")
    suspend fun getActiveSessions(): List<TabSession>
    
    @Query("SELECT COUNT(*) FROM tab_sessions WHERE is_active = 1")
    suspend fun getActiveSessionCount(): Int
    
    @Query("SELECT MAX(tab_order) FROM tab_sessions")
    suspend fun getMaxTabOrder(): Int?
    
    @Insert
    suspend fun insertSession(session: TabSession)
    
    @Insert
    suspend fun insertSessions(sessions: List<TabSession>)
    
    @Update
    suspend fun updateSession(session: TabSession)
    
    @Delete
    suspend fun deleteSession(session: TabSession)
    
    @Query("DELETE FROM tab_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    
    @Query("DELETE FROM tab_sessions WHERE connection_id = :connectionId")
    suspend fun deleteSessionsByConnectionId(connectionId: String)
    
    @Query("DELETE FROM tab_sessions WHERE is_active = 0 AND last_activity < :cutoffTime")
    suspend fun deleteOldInactiveSessions(cutoffTime: Long)
    
    @Query("DELETE FROM tab_sessions")
    suspend fun deleteAllSessions()
    
    @Query("UPDATE tab_sessions SET is_active = 0")
    suspend fun deactivateAllSessions()
    
    @Query("UPDATE tab_sessions SET is_active = 1 WHERE sessionId = :sessionId")
    suspend fun activateSession(sessionId: String)
    
    @Query("UPDATE tab_sessions SET last_activity = :timestamp WHERE sessionId = :sessionId")
    suspend fun updateLastActivity(sessionId: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE tab_sessions SET tab_order = :order WHERE sessionId = :sessionId")
    suspend fun updateTabOrder(sessionId: String, order: Int)
    
    @Query("UPDATE tab_sessions SET session_duration = :duration WHERE sessionId = :sessionId")
    suspend fun updateSessionDuration(sessionId: String, duration: Long)
    
    @Query("UPDATE tab_sessions SET bytes_transferred = :bytes WHERE sessionId = :sessionId")
    suspend fun updateBytesTransferred(sessionId: String, bytes: Long)
    
    @Transaction
    suspend fun swapTabOrder(sessionId1: String, sessionId2: String) {
        val session1 = getSessionById(sessionId1)
        val session2 = getSessionById(sessionId2)
        if (session1 != null && session2 != null) {
            updateTabOrder(sessionId1, session2.tabOrder)
            updateTabOrder(sessionId2, session1.tabOrder)
        }
    }
    
    @Transaction
    suspend fun saveTabState(sessionId: String, 
                           workingDirectory: String?,
                           environmentVariables: String?,
                           terminalRows: Int,
                           terminalCols: Int,
                           cursorRow: Int,
                           cursorCol: Int,
                           scrollbackContent: String?) {
        val session = getSessionById(sessionId)
        if (session != null) {
            val updatedSession = session.copy(
                workingDirectory = workingDirectory,
                environmentVariables = environmentVariables,
                terminalSizeRows = terminalRows,
                terminalSizeCols = terminalCols,
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                scrollbackContent = scrollbackContent,
                lastActivity = System.currentTimeMillis()
            )
            updateSession(updatedSession)
        }
    }
}