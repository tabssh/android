package io.github.tabssh.audit

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.AuditLogEntry
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audit log manager — records security-relevant events for compliance and review.
 *
 * ## Two control planes
 *
 * 1. **User preference** (`audit_log_enabled` in Settings → Logging → Audit):
 *    default OFF for privacy; the user opts in explicitly.
 *
 * 2. **MDM / Android Enterprise Managed App Config** (via [RestrictionsManager]):
 *    An IT administrator can push `mdm_audit_enabled = true` through their EMM
 *    console (Intune, Jamf, Workspace ONE, etc.). When the MDM bundle is present
 *    and sets `mdm_audit_enabled`, it takes precedence over the user preference
 *    and the toggle in Settings is greyed out. The MDM can also configure:
 *      - `mdm_audit_log_commands`  (bool, default true)
 *      - `mdm_audit_log_output`    (bool, default false)
 *      - `mdm_audit_retention_days` (int, default 90)
 *      - `mdm_syslog_host`         (string — UDP syslog receiver, RFC 5424)
 *      - `mdm_syslog_port`         (int,    default 514)
 *
 * ## Event types
 * See [AuditLogEntry] constants: SESSION_START/END, AUTH_SUCCESS/FAILURE,
 * KEY_USAGE, HOST_KEY_ACCEPTED/REJECTED/CHANGED, COMMAND, SFTP_*, PORT_FORWARD_*,
 * X11_FORWARD, CONFIG_CHANGE.
 *
 * ## Syslog forwarding
 * When `mdm_syslog_host` is set, every audit entry is also forwarded as a
 * UDP syslog message (RFC 5424 framing, facility AUTHPRIV=80, severity mapped
 * from event type). Forwarding is fire-and-forget — a send failure logs a
 * warning but never prevents the local DB insert.
 */
class AuditLogManager(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferencesManager: PreferenceManager
) {

    private val auditDao = database.auditLogDao()

    // Cached MDM bundle — refreshed by MdmRestrictionsReceiver on every
    // ACTION_APPLICATION_RESTRICTIONS_CHANGED broadcast.
    @Volatile private var mdmBundle: Bundle? = null

    companion object {
        // User-preference keys (shared with preferences_audit.xml)
        const val PREF_AUDIT_ENABLED        = "audit_log_enabled"
        const val PREF_AUDIT_MAX_SIZE_MB    = "audit_log_max_size_mb"
        const val PREF_AUDIT_MAX_AGE_DAYS   = "audit_log_max_age_days"
        const val PREF_AUDIT_LOG_COMMANDS   = "audit_log_commands"
        const val PREF_AUDIT_LOG_OUTPUT     = "audit_log_output"
        const val PREF_AUDIT_AUTO_CLEANUP   = "audit_log_auto_cleanup"

        // MDM managed-config keys (must match res/xml/app_restrictions.xml)
        const val MDM_AUDIT_ENABLED         = "mdm_audit_enabled"
        const val MDM_AUDIT_LOG_COMMANDS    = "mdm_audit_log_commands"
        const val MDM_AUDIT_LOG_OUTPUT      = "mdm_audit_log_output"
        const val MDM_AUDIT_RETENTION_DAYS  = "mdm_audit_retention_days"
        const val MDM_SYSLOG_HOST           = "mdm_syslog_host"
        const val MDM_SYSLOG_PORT           = "mdm_syslog_port"

        const val DEFAULT_MAX_SIZE_MB   = 100L
        const val DEFAULT_MAX_AGE_DAYS  = 30
        const val MDM_DEFAULT_RET_DAYS  = 90
        const val MDM_DEFAULT_SYSLOG_PORT = 514

        // RFC 5424 priority = facility * 8 + severity.
        // AUTHPRIV (facility 10) × 8 = 80. Severity: info=6, warn=4, err=3.
        private const val SYSLOG_PRI_INFO = 86   // 80 + 6
        private const val SYSLOG_PRI_WARN = 84   // 80 + 4
        private const val SYSLOG_PRI_ERR  = 83   // 80 + 3
    }

    // ── MDM ──────────────────────────────────────────────────────────────────

    /** Called by [MdmRestrictionsReceiver] whenever the MDM bundle changes. */
    fun onMdmRestrictionsChanged() {
        mdmBundle = loadMdmBundle()
    }

    private fun loadMdmBundle(): Bundle? {
        return try {
            val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            rm?.applicationRestrictions?.takeIf { !it.isEmpty }
        } catch (_: Exception) { null }
    }

    /** True if an MDM has pushed `mdm_audit_enabled = true`. */
    fun isMdmManaged(): Boolean = getMdmBundle()?.getBoolean(MDM_AUDIT_ENABLED, false) == true

    private fun getMdmBundle(): Bundle? {
        if (mdmBundle == null) mdmBundle = loadMdmBundle()
        return mdmBundle
    }

    // ── Enablement ───────────────────────────────────────────────────────────

    /**
     * True when auditing is on. MDM overrides user preference — if the MDM
     * forces it on the user cannot turn it off; if MDM forces it off the user
     * cannot turn it on.
     */
    fun isEnabled(): Boolean {
        val mdm = getMdmBundle()
        if (mdm != null && mdm.containsKey(MDM_AUDIT_ENABLED)) {
            return mdm.getBoolean(MDM_AUDIT_ENABLED, false)
        }
        return preferencesManager.getBoolean(PREF_AUDIT_ENABLED, false)
    }

    private fun shouldLogCommands(): Boolean {
        val mdm = getMdmBundle()
        if (mdm != null && mdm.containsKey(MDM_AUDIT_LOG_COMMANDS)) {
            return mdm.getBoolean(MDM_AUDIT_LOG_COMMANDS, true)
        }
        return preferencesManager.getBoolean(PREF_AUDIT_LOG_COMMANDS, true)
    }

    private fun shouldLogOutput(): Boolean {
        val mdm = getMdmBundle()
        if (mdm != null && mdm.containsKey(MDM_AUDIT_LOG_OUTPUT)) {
            return mdm.getBoolean(MDM_AUDIT_LOG_OUTPUT, false)
        }
        return preferencesManager.getBoolean(PREF_AUDIT_LOG_OUTPUT, false)
    }

    private fun retentionDays(): Int {
        val mdm = getMdmBundle()
        if (mdm != null && mdm.containsKey(MDM_AUDIT_RETENTION_DAYS)) {
            return mdm.getInt(MDM_AUDIT_RETENTION_DAYS, MDM_DEFAULT_RET_DAYS)
        }
        return preferencesManager.getInt(PREF_AUDIT_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS)
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    suspend fun logSessionStart(connection: ConnectionProfile, sessionId: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SESSION_START,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port
        ))
    }

    suspend fun logSessionEnd(connection: ConnectionProfile, sessionId: String, durationMs: Long) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SESSION_END,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"duration_ms":$durationMs}"""
        ))
    }

    // ── Auth events ──────────────────────────────────────────────────────────

    suspend fun logAuthSuccess(connection: ConnectionProfile, sessionId: String, method: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_AUTH_SUCCESS,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"method":"$method"}"""
        ))
    }

    suspend fun logAuthFailure(connection: ConnectionProfile, sessionId: String, method: String, reason: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_AUTH_FAILURE,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"method":"$method","reason":"${reason.take(200)}"}"""
        ))
    }

    suspend fun logKeyUsage(connection: ConnectionProfile, sessionId: String, keyName: String, fingerprint: String?) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_KEY_USAGE,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"key":"${keyName.take(100)}","fingerprint":"${fingerprint ?: "unknown"}"}"""
        ))
    }

    // ── Host key trust events ────────────────────────────────────────────────

    suspend fun logHostKeyAccepted(connection: ConnectionProfile, sessionId: String, fingerprint: String, isNew: Boolean) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_HOST_KEY_ACCEPTED,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"fingerprint":"${fingerprint.take(100)}","new":$isNew}"""
        ))
    }

    suspend fun logHostKeyRejected(connection: ConnectionProfile, sessionId: String, fingerprint: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_HOST_KEY_REJECTED,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"fingerprint":"${fingerprint.take(100)}"}"""
        ))
    }

    suspend fun logHostKeyChanged(connection: ConnectionProfile, sessionId: String, oldFingerprint: String, newFingerprint: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_HOST_KEY_CHANGED,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"old":"${oldFingerprint.take(100)}","new":"${newFingerprint.take(100)}"}"""
        ))
    }

    // ── Command / output ─────────────────────────────────────────────────────

    /** Kept for source compatibility with existing call sites. */
    suspend fun logConnect(connection: ConnectionProfile, sessionId: String, success: Boolean) {
        if (success) logAuthSuccess(connection, sessionId, "unknown")
        else         logAuthFailure(connection, sessionId, "unknown", "")
    }

    /** Kept for source compatibility with existing call sites. */
    suspend fun logDisconnect(connection: ConnectionProfile, sessionId: String) =
        logSessionEnd(connection, sessionId, 0)

    suspend fun logCommand(connection: ConnectionProfile, sessionId: String, command: String) {
        if (!isEnabled()) return
        if (!shouldLogCommands()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_COMMAND,
            command      = command.take(4096),
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            sizeBytes    = (command.length + 200).toLong()
        ))
        checkAndCleanup()
    }

    suspend fun logOutput(connection: ConnectionProfile, sessionId: String, output: String) {
        if (!isEnabled()) return
        if (!shouldLogOutput()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_OUTPUT,
            output       = output.take(8192),
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            sizeBytes    = (output.length + 200).toLong()
        ))
    }

    // ── SFTP ─────────────────────────────────────────────────────────────────

    suspend fun logSftpUpload(connection: ConnectionProfile, sessionId: String, remotePath: String, bytes: Long) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SFTP_UPLOAD,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"path":"${remotePath.take(500)}","bytes":$bytes}""",
            sizeBytes    = bytes + 200
        ))
    }

    suspend fun logSftpDownload(connection: ConnectionProfile, sessionId: String, remotePath: String, bytes: Long) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SFTP_DOWNLOAD,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"path":"${remotePath.take(500)}","bytes":$bytes}"""
        ))
    }

    suspend fun logSftpDelete(connection: ConnectionProfile, sessionId: String, remotePath: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SFTP_DELETE,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"path":"${remotePath.take(500)}"}"""
        ))
    }

    suspend fun logSftpMkdir(connection: ConnectionProfile, sessionId: String, remotePath: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_SFTP_MKDIR,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"path":"${remotePath.take(500)}"}"""
        ))
    }

    // ── Port forwarding ──────────────────────────────────────────────────────

    suspend fun logPortForwardOpen(connection: ConnectionProfile, sessionId: String, spec: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_PORT_FORWARD_OPEN,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"spec":"${spec.take(200)}"}"""
        ))
    }

    suspend fun logPortForwardClose(connection: ConnectionProfile, sessionId: String, spec: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = connection.id,
            sessionId    = sessionId,
            eventType    = AuditLogEntry.EVENT_PORT_FORWARD_CLOSE,
            user         = connection.username,
            host         = connection.host,
            port         = connection.port,
            metadata     = """{"spec":"${spec.take(200)}"}"""
        ))
    }

    // ── Config change ────────────────────────────────────────────────────────

    /**
     * Log a settings/configuration change. The `detail` string should describe
     * what changed (key + old/new value) — avoid including raw secrets.
     */
    suspend fun logConfigChange(actor: String, detail: String) {
        if (!isEnabled()) return
        insert(AuditLogEntry(
            connectionId = "",
            sessionId    = "",
            eventType    = AuditLogEntry.EVENT_CONFIG_CHANGE,
            user         = actor,
            host         = "local",
            port         = 0,
            metadata     = """{"detail":"${detail.take(500)}"}"""
        ))
    }

    // ── Syslog forwarding ────────────────────────────────────────────────────

    /**
     * Forward an entry to the MDM-configured syslog host (fire-and-forget UDP).
     * RFC 5424 framing: `<PRI>1 TIMESTAMP HOSTNAME APP-NAME - - - MSG`
     */
    private fun syslogForward(entry: AuditLogEntry) {
        val host = getMdmBundle()?.getString(MDM_SYSLOG_HOST)?.takeIf { it.isNotBlank() } ?: return
        val port = getMdmBundle()?.getInt(MDM_SYSLOG_PORT, MDM_DEFAULT_SYSLOG_PORT) ?: MDM_DEFAULT_SYSLOG_PORT
        val pri = when {
            entry.eventType.contains("FAIL")    -> SYSLOG_PRI_ERR
            entry.eventType.contains("CHANGED") -> SYSLOG_PRI_WARN
            entry.eventType.contains("REJECT")  -> SYSLOG_PRI_WARN
            else                                -> SYSLOG_PRI_INFO
        }
        val ts  = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date(entry.timestamp))
        val hostname = android.os.Build.MODEL.replace(' ', '-').take(64)
        val msg = "<$pri>1 $ts $hostname TabSSH - - - ${entry.getDisplayEvent()}"
        try {
            val bytes = msg.toByteArray(Charsets.UTF_8)
            java.net.DatagramSocket().use { sock ->
                sock.soTimeout = 2000
                sock.send(java.net.DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName(host), port))
            }
        } catch (e: Exception) {
            Logger.w("AuditLogManager", "Syslog forward to $host:$port failed: ${e.message}")
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private suspend fun insert(entry: AuditLogEntry) {
        withContext(Dispatchers.IO) {
            try {
                auditDao.insert(entry)
                syslogForward(entry)
            } catch (e: Exception) {
                Logger.e("AuditLogManager", "Failed to insert audit entry", e)
            }
        }
    }

    suspend fun checkAndCleanup() {
        withContext(Dispatchers.IO) {
            val maxSizeMB  = preferencesManager.getLong(PREF_AUDIT_MAX_SIZE_MB, DEFAULT_MAX_SIZE_MB)
            val currentSize = auditDao.getTotalSize() ?: 0L
            if (currentSize > maxSizeMB * 1024 * 1024) {
                auditDao.deleteOldest(100)
            }
            val cutoff = System.currentTimeMillis() - (retentionDays().toLong() * 86_400_000L)
            auditDao.deleteOlderThan(cutoff)
        }
    }

    suspend fun getRecentLogs(limit: Int = 100): List<AuditLogEntry> = auditDao.getRecent(limit)

    suspend fun deleteAllLogs() = auditDao.deleteAll()
}
