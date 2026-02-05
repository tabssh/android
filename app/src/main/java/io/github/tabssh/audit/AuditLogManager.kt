package io.github.tabssh.audit

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.AuditLogEntry
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager for audit logging of terminal sessions
 * Handles logging, cleanup, and privacy-first defaults
 */
class AuditLogManager(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferencesManager: PreferenceManager
) {
    
    private val auditDao = database.auditLogDao()
    
    companion object {
        const val PREF_AUDIT_ENABLED = "audit_log_enabled"
        const val PREF_AUDIT_MAX_SIZE_MB = "audit_log_max_size_mb"
        const val PREF_AUDIT_MAX_AGE_DAYS = "audit_log_max_age_days"
        const val PREF_AUDIT_LOG_COMMANDS = "audit_log_commands"
        const val PREF_AUDIT_LOG_OUTPUT = "audit_log_output"
        const val PREF_AUDIT_AUTO_CLEANUP = "audit_log_auto_cleanup"
        
        const val DEFAULT_MAX_SIZE_MB = 100L
        const val DEFAULT_MAX_AGE_DAYS = 30
    }
    
    fun isEnabled(): Boolean = preferencesManager.getBoolean(PREF_AUDIT_ENABLED, false)
    
    suspend fun logConnect(connection: ConnectionProfile, sessionId: String, success: Boolean) {
        if (!isEnabled()) return
        
        val entry = AuditLogEntry(
            connectionId = connection.id,
            sessionId = sessionId,
            eventType = if (success) AuditLogEntry.EVENT_AUTH_SUCCESS else AuditLogEntry.EVENT_AUTH_FAILURE,
            user = connection.username,
            host = connection.host,
            port = connection.port,
            sizeBytes = 200
        )
        auditDao.insert(entry)
        checkAndCleanup()
    }
    
    suspend fun logDisconnect(connection: ConnectionProfile, sessionId: String) {
        if (!isEnabled()) return
        
        val entry = AuditLogEntry(
            connectionId = connection.id,
            sessionId = sessionId,
            eventType = AuditLogEntry.EVENT_DISCONNECT,
            user = connection.username,
            host = connection.host,
            port = connection.port,
            sizeBytes = 200
        )
        auditDao.insert(entry)
    }
    
    suspend fun logCommand(connection: ConnectionProfile, sessionId: String, command: String) {
        if (!isEnabled()) return
        if (!preferencesManager.getBoolean(PREF_AUDIT_LOG_COMMANDS, true)) return
        
        val entry = AuditLogEntry(
            connectionId = connection.id,
            sessionId = sessionId,
            eventType = AuditLogEntry.EVENT_COMMAND,
            command = command,
            user = connection.username,
            host = connection.host,
            port = connection.port,
            sizeBytes = (command.length + 200).toLong()
        )
        auditDao.insert(entry)
        checkAndCleanup()
    }
    
    suspend fun checkAndCleanup() {
        withContext(Dispatchers.IO) {
            val maxSizeMB = preferencesManager.getLong(PREF_AUDIT_MAX_SIZE_MB, DEFAULT_MAX_SIZE_MB)
            val currentSize = auditDao.getTotalSize() ?: 0L
            
            if (currentSize > maxSizeMB * 1024 * 1024) {
                auditDao.deleteOldest(100)
            }
            
            val maxAgeDays = preferencesManager.getInt(PREF_AUDIT_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS)
            val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
            auditDao.deleteOlderThan(cutoff)
        }
    }
    
    suspend fun getRecentLogs(limit: Int = 100): List<AuditLogEntry> {
        return auditDao.getRecent(limit)
    }
    
    suspend fun deleteAllLogs() {
        auditDao.deleteAll()
    }
}
