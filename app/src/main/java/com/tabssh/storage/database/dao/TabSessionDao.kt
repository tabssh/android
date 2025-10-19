package com.tabssh.storage.database.dao

import androidx.room.*
import com.tabssh.storage.database.entities.TabSession
import kotlinx.coroutines.flow.Flow

/**
 * DAO for TabSession entities
 * Manages saved tab sessions for persistence across app restarts
 */
@Dao
interface TabSessionDao {

    @Query("SELECT * FROM tab_sessions WHERE is_active = 1 ORDER BY last_activity DESC")
    fun getAllActiveSessions(): Flow<List<TabSession>>

    @Query("SELECT * FROM tab_sessions WHERE tab_id = :tabId LIMIT 1")
    suspend fun getSessionByTabId(tabId: String): TabSession?

    @Query("SELECT * FROM tab_sessions WHERE connection_id = :connectionId")
    suspend fun getSessionsByConnectionId(connectionId: String): List<TabSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TabSession)

    @Update
    suspend fun updateSession(session: TabSession)

    @Delete
    suspend fun deleteSession(session: TabSession)

    @Query("DELETE FROM tab_sessions WHERE tab_id = :tabId")
    suspend fun deleteSessionByTabId(tabId: String)

    @Query("DELETE FROM tab_sessions WHERE connection_id = :connectionId")
    suspend fun deleteSessionsByConnectionId(connectionId: String)

    @Query("UPDATE tab_sessions SET is_active = 0")
    suspend fun deactivateAllSessions()

    @Query("SELECT * FROM tab_sessions WHERE is_active = 1")
    suspend fun getAllTabs(): List<TabSession>

    @Query("SELECT * FROM tab_sessions WHERE is_active = 1 ORDER BY tab_order ASC")
    suspend fun getActiveSessionsList(): List<TabSession>

    @Query("DELETE FROM tab_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT COUNT(*) FROM tab_sessions WHERE is_active = 1")
    suspend fun getActiveSessionCount(): Int

    @Query("""
        UPDATE tab_sessions
        SET terminal_content = :content,
            cursor_row = :cursorRow,
            cursor_col = :cursorCol,
            last_activity = :timestamp
        WHERE tab_id = :tabId
    """)
    suspend fun updateSessionContent(
        tabId: String,
        content: String,
        cursorRow: Int,
        cursorCol: Int,
        timestamp: Long
    )

    @Query("""
        UPDATE tab_sessions
        SET environment_vars = :envVars,
            working_directory = :workingDir,
            last_activity = :timestamp
        WHERE tab_id = :tabId
    """)
    suspend fun updateSessionEnvironment(
        tabId: String,
        envVars: String,
        workingDir: String,
        timestamp: Long
    )

    @Query("DELETE FROM tab_sessions WHERE is_active = 0 AND last_activity < :cutoffTime")
    suspend fun deleteOldInactiveSessions(cutoffTime: Long)
}