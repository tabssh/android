package io.github.tabssh.sync.data

import android.content.Context
import androidx.preference.PreferenceManager as AndroidPreferenceManager
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.models.MergeResult
import io.github.tabssh.sync.models.MergeStrategy
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.utils.logging.Logger
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies synced data to local database
 */
class SyncDataApplier {

    companion object {
        private const val TAG = "SyncDataApplier"
    }

    private val context: Context
    private val database: TabSSHDatabase
    private val preferenceManager: PreferenceManager

    // Credential managers — resolved from Application singleton so that
    // Keystore-backed secrets in the sync payload are restored on this device.
    private val app: TabSSHApplication?
        get() = context.applicationContext as? TabSSHApplication

    // Simple constructor for SAF sync
    constructor(context: Context) {
        this.context = context
        this.database = TabSSHDatabase.getDatabase(context)
        this.preferenceManager = PreferenceManager(context)
    }

    // Full constructor for advanced use
    constructor(
        context: Context,
        database: TabSSHDatabase,
        preferenceManager: PreferenceManager
    ) {
        this.context = context
        this.database = database
        this.preferenceManager = preferenceManager
    }

    /**
     * Apply all data from a sync package (replaces local data)
     */
    suspend fun applyAll(data: SyncDataPackage): ApplyResult = withContext(Dispatchers.IO) {
        try {
            var appliedCount = 0

            database.withTransaction {
            // Apply connections
            data.connections.forEach { connection ->
                try {
                    database.connectionDao().insertConnection(connection)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply connection: ${connection.name}", e)
                }
            }

            // Apply keys
            data.keys.forEach { key ->
                try {
                    database.keyDao().insertKey(key)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply key: ${key.name}", e)
                }
            }

            // Apply themes
            data.themes.forEach { theme ->
                try {
                    database.themeDao().insertTheme(theme)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply theme: ${theme.name}", e)
                }
            }

            // Apply host keys
            data.hostKeys.forEach { hostKey ->
                try {
                    database.hostKeyDao().insertHostKey(hostKey)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply host key: ${hostKey.hostname}", e)
                }
            }

            // Apply preferences
            appliedCount += applyPreferences(data.preferences)

            // Wave 5.3 — apply workspaces (last-write-wins via REPLACE).
            data.workspaces.forEach { ws ->
                try {
                    database.workspaceDao().upsert(ws)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply workspace: ${ws.name}", e)
                }
            }

            // Wave 5.4 — snippets / identities / groups, REPLACE on PK conflict.
            data.snippets.forEach { s ->
                try {
                    database.snippetDao().insertSnippet(s)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply snippet: ${s.name}", e)
                }
            }
            data.identities.forEach { id ->
                try {
                    database.identityDao().insert(id)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply identity: ${id.name}", e)
                }
            }
            data.groups.forEach { g ->
                try {
                    database.connectionGroupDao().insertGroup(g)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply group: ${g.name}", e)
                }
            }

            // Wave 7.1 — hypervisors / trusted_certificates, REPLACE on PK conflict.
            data.hypervisors.forEach { h ->
                try {
                    database.hypervisorDao().upsertForSync(h)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply hypervisor: ${h.name}", e)
                }
            }
            data.certificates.forEach { c ->
                try {
                    database.certificateDao().insertCertificate(c)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply certificate: ${c.fingerprint}", e)
                }
            }

            // Wave 11 — macros / monitor_slots, REPLACE on PK conflict.
            data.macros.forEach { m ->
                try {
                    database.macroDao().insertMacro(m)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply macro: ${m.id}", e)
                }
            }
            data.monitorSlots.forEach { slot ->
                try {
                    database.monitorSlotDao().insertOrReplace(slot)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply monitor slot: ${slot.id}", e)
                }
            }

            // Audit 2026-05-16 — hypervisor account metadata. Password
            // remains Keystore-bound on each device, not transferred.
            // Cross-device Long-PK collision risk is the same as
            // HypervisorProfile; documented in AI.md §9.4.
            data.hypervisorAccounts.forEach { a ->
                try {
                    val existing = database.hypervisorAccountDao().getById(a.id)
                    if (existing == null) {
                        database.hypervisorAccountDao().insert(a)
                    } else {
                        database.hypervisorAccountDao().update(a)
                    }
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply hypervisor account: ${a.name}", e)
                }
            }

            // Wave 13 — VNC hosts (last-write-wins REPLACE on UUID PK).
            data.vncHosts.forEach { h ->
                try {
                    database.vncHostDao().insert(h)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply VNC host: ${h.name}", e)
                }
            }
            // Wave 13 — VNC identity metadata (password not in row; Keystore-bound on each device).
            data.vncIdentities.forEach { vi ->
                try {
                    database.vncIdentityDao().insert(vi)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply VNC identity: ${vi.name}", e)
                }
            }

            // Wave 14 — cloud provider account metadata. Token comes through the
            // secrets mechanism (cloud_token_{id}) and is applied outside the tx.
            data.cloudAccounts.forEach { ca ->
                try {
                    database.cloudAccountDao().upsert(ca)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply cloud account: ${ca.name}", e)
                }
            }

            } // end withTransaction

            // Apply credentials outside the Room transaction — Keystore
            // AES-GCM encrypt/decrypt is I/O and must not run inside a DB tx.
            if (data.secrets.isNotEmpty()) {
                applySecrets(data.secrets)
            } else {
                Logger.d(TAG, "No secrets in sync payload (pre-v14 or empty device)")
            }

            Logger.i(TAG, "Applied $appliedCount items from sync data")
            ApplyResult.Success(appliedCount)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply sync data", e)
            ApplyResult.Error("Failed to apply sync data: ${e.message}")
        }
    }

    /**
     * Restore Keystore-backed credentials from the sync secrets map.
     *
     * Entries whose key starts with "ssh_key_" are base64-encoded JSch bytes
     * and are stored via [KeyStorage.importKeyFromBackup]. All other entries
     * are password/token strings stored via [SecurePasswordManager.storePassword].
     */
    private suspend fun applySecrets(secrets: Map<String, String>) {
        val pm: SecurePasswordManager? = app?.securePasswordManager
        val ks: KeyStorage? = app?.keyStorage
        var passwordCount = 0
        var keyCount = 0
        secrets.forEach { (alias, value) ->
            if (value.isEmpty()) return@forEach
            try {
                if (alias.startsWith("ssh_key_")) {
                    if (ks != null) {
                        val keyId = alias.removePrefix("ssh_key_")
                        val bytes = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
                        ks.importKeyFromBackup(keyId, bytes)
                        keyCount++
                    }
                } else if (alias.startsWith("conn_pw_")) {
                    // Connection passwords live in PreferenceManager SharedPreferences,
                    // not SecurePasswordManager — route them to the correct store.
                    val connId = alias.removePrefix("conn_pw_")
                    preferenceManager.setConnectionPassword(connId, value)
                    passwordCount++
                } else {
                    if (pm != null) {
                        pm.storePassword(alias, value,
                            SecurePasswordManager.StorageLevel.ENCRYPTED)
                        passwordCount++
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to restore secret $alias: ${e.message}")
            }
        }
        Logger.i(TAG, "Restored $passwordCount passwords and $keyCount SSH keys from sync")
    }

    /**
     * Apply merge result to database
     */
    suspend fun applyMergeResult(
        connectionResult: MergeResult<ConnectionProfile>,
        keyResult: MergeResult<StoredKey>,
        themeResult: MergeResult<ThemeDefinition>,
        hostKeyResult: MergeResult<HostKeyEntry>,
        preferences: Map<String, JsonElement>
    ): ApplyResult = withContext(Dispatchers.IO) {
        try {
            var appliedCount = 0

            appliedCount += applyConnections(connectionResult)
            appliedCount += applyKeys(keyResult)
            appliedCount += applyThemes(themeResult)
            appliedCount += applyHostKeys(hostKeyResult)
            appliedCount += applyPreferences(preferences)

            Logger.d(TAG, "Applied $appliedCount sync changes successfully")

            ApplyResult.Success(appliedCount)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply sync data", e)
            ApplyResult.Error("Failed to apply sync data: ${e.message}")
        }
    }

    /**
     * Apply connections
     */
    private suspend fun applyConnections(result: MergeResult<ConnectionProfile>): Int {
        var count = 0

        result.added.forEach { connection ->
            try {
                database.connectionDao().insertConnection(connection)
                count++
                Logger.d(TAG, "Added connection: ${connection.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add connection: ${connection.name}", e)
            }
        }

        result.updated.forEach { connection ->
            try {
                database.connectionDao().updateConnection(connection)
                count++
                Logger.d(TAG, "Updated connection: ${connection.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update connection: ${connection.name}", e)
            }
        }

        result.deleted.forEach { connectionId ->
            try {
                database.connectionDao().deleteConnectionById(connectionId)
                count++
                Logger.d(TAG, "Deleted connection: $connectionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete connection: $connectionId", e)
            }
        }

        return count
    }

    /**
     * Apply keys
     */
    private suspend fun applyKeys(result: MergeResult<StoredKey>): Int {
        var count = 0

        result.added.forEach { key ->
            try {
                database.keyDao().insertKey(key)
                count++
                Logger.d(TAG, "Added key: ${key.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add key: ${key.name}", e)
            }
        }

        result.updated.forEach { key ->
            try {
                database.keyDao().updateKey(key)
                count++
                Logger.d(TAG, "Updated key: ${key.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update key: ${key.name}", e)
            }
        }

        result.deleted.forEach { keyId ->
            try {
                database.keyDao().deleteKeyById(keyId)
                count++
                Logger.d(TAG, "Deleted key: $keyId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete key: $keyId", e)
            }
        }

        return count
    }

    /**
     * Apply themes
     */
    private suspend fun applyThemes(result: MergeResult<ThemeDefinition>): Int {
        var count = 0

        result.added.forEach { theme ->
            try {
                database.themeDao().insertTheme(theme)
                count++
                Logger.d(TAG, "Added theme: ${theme.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add theme: ${theme.name}", e)
            }
        }

        result.updated.forEach { theme ->
            try {
                database.themeDao().updateTheme(theme)
                count++
                Logger.d(TAG, "Updated theme: ${theme.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update theme: ${theme.name}", e)
            }
        }

        result.deleted.forEach { themeId ->
            try {
                database.themeDao().deleteThemeById(themeId)
                count++
                Logger.d(TAG, "Deleted theme: $themeId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete theme: $themeId", e)
            }
        }

        return count
    }

    /**
     * Apply host keys
     */
    private suspend fun applyHostKeys(result: MergeResult<HostKeyEntry>): Int {
        var count = 0

        result.added.forEach { hostKey ->
            try {
                database.hostKeyDao().insertHostKey(hostKey)
                count++
                Logger.d(TAG, "Added host key: ${hostKey.hostname}:${hostKey.port}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add host key: ${hostKey.hostname}:${hostKey.port}", e)
            }
        }

        result.updated.forEach { hostKey ->
            try {
                database.hostKeyDao().updateHostKey(hostKey)
                count++
                Logger.d(TAG, "Updated host key: ${hostKey.hostname}:${hostKey.port}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update host key: ${hostKey.hostname}:${hostKey.port}", e)
            }
        }

        result.deleted.forEach { hostKeyId ->
            try {
                database.hostKeyDao().deleteHostKeyById(hostKeyId)
                count++
                Logger.d(TAG, "Deleted host key: $hostKeyId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete host key: $hostKeyId", e)
            }
        }

        return count
    }

    /**
     * Apply preferences from JsonElement map
     */
    private fun applyPreferences(preferences: Map<String, JsonElement>): Int {
        var count = 0

        try {
            val general = (preferences["general"] as? JsonObject)?.toAnyMap()
            general?.let {
                count += applyGeneralPreferences(it)
            }

            val security = (preferences["security"] as? JsonObject)?.toAnyMap()
            security?.let {
                count += applySecurityPreferences(it)
            }

            val terminal = (preferences["terminal"] as? JsonObject)?.toAnyMap()
            terminal?.let {
                count += applyTerminalPreferences(it)
            }

            val ui = (preferences["ui"] as? JsonObject)?.toAnyMap()
            ui?.let {
                count += applyUIPreferences(it)
            }

            val connection = (preferences["connection"] as? JsonObject)?.toAnyMap()
            connection?.let {
                count += applyConnectionPreferences(it)
            }

            val keyboard = (preferences["keyboard"] as? JsonObject)?.toAnyMap()
            keyboard?.let {
                count += applyKeyboardPreferences(it)
            }

            val notifications = (preferences["notifications"] as? JsonObject)?.toAnyMap()
            notifications?.let {
                count += applyNotificationPreferences(it)
            }

            val monitoring = (preferences["monitoring"] as? JsonObject)?.toAnyMap()
            monitoring?.let {
                count += applyMonitoringPreferences(it)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply preferences", e)
        }

        return count
    }

    /**
     * Convert JsonObject to Map<String, Any>
     */
    private fun JsonObject.toAnyMap(): Map<String, Any> {
        return this.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> {
                    value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                }
                is JsonObject -> value.toAnyMap()
                else -> value.toString()
            }
        }
    }

    private fun applyGeneralPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "autoBackup" -> preferenceManager.setAutoBackupEnabled(value as Boolean)
                    "backupFrequency" -> preferenceManager.setBackupFrequency(value as String)
                    "startupBehavior" -> preferenceManager.setStartupBehavior(value as String)
                    "language" -> preferenceManager.setLanguage(value as String)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply general preference: $key", e)
            }
        }
        return count
    }

    private fun applySecurityPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "passwordStorageLevel" -> {
                        val level = when (value) {
                            is Number -> value.toString()
                            is String -> value
                            else -> "encrypted"
                        }
                        preferenceManager.setPasswordStorageLevel(level)
                    }
                    "requireBiometric" -> preferenceManager.setRequireBiometricForSensitive(value as Boolean)
                    "strictHostKeyChecking" -> preferenceManager.setStrictHostKeyChecking(value as Boolean)
                    "clearClipboardTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 60
                        }
                        preferenceManager.setClearClipboardTimeout(timeout)
                    }
                    "autoLockEnabled" -> preferenceManager.setAutoLockOnBackground(value as Boolean)
                    "lockTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 300
                        }
                        preferenceManager.setAutoLockTimeout(timeout)
                    }
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply security preference: $key", e)
            }
        }
        return count
    }

    private fun applyTerminalPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "theme" -> preferenceManager.setTerminalTheme(value as String)
                    "fontSize" -> {
                        val size = when (value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloat()
                            else -> 14f
                        }
                        preferenceManager.setFontSize(size)
                    }
                    "fontFamily" -> preferenceManager.setFontFamily(value as String)
                    "cursorStyle" -> preferenceManager.setCursorStyle(value as String)
                    "cursorBlink" -> preferenceManager.setCursorBlinkEnabled(value as Boolean)
                    "scrollbackLines" -> {
                        val lines = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 1000
                        }
                        preferenceManager.setScrollbackLines(lines)
                    }
                    "terminalBell" -> preferenceManager.setBellNotificationEnabled(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply terminal preference: $key", e)
            }
        }
        return count
    }

    private fun applyUIPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "maxTabs" -> {
                        val tabs = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 10
                        }
                        preferenceManager.setMaxTabs(tabs)
                    }
                    "confirmTabClose" -> preferenceManager.setConfirmTabClose(value as Boolean)
                    "appTheme" -> preferenceManager.setAppTheme(value as String)
                    "dynamicColors" -> preferenceManager.setDynamicColors(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply UI preference: $key", e)
            }
        }
        return count
    }

    private fun applyConnectionPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "defaultUsername" -> preferenceManager.setDefaultUsername(value as String)
                    "defaultPort" -> {
                        val port = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 22
                        }
                        preferenceManager.setDefaultPort(port)
                    }
                    "connectTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 15
                        }
                        preferenceManager.setConnectTimeout(timeout)
                    }
                    "autoReconnect" -> preferenceManager.setAutoReconnect(value as Boolean)
                    "compression" -> preferenceManager.setCompressionEnabled(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply connection preference: $key", e)
            }
        }
        return count
    }

    private fun applyKeyboardPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "rowCount" -> {
                        val rows = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: return@forEach
                            else -> return@forEach
                        }
                        preferenceManager.setKeyboardRowCount(rows)
                    }
                    "layoutVersion" -> {
                        val version = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: return@forEach
                            else -> return@forEach
                        }
                        preferenceManager.setKeyboardLayoutVersion(version)
                    }
                    "layoutCustomized" -> preferenceManager.setKeyboardLayoutCustomized(value as Boolean)
                    "layoutJson" -> preferenceManager.setKeyboardLayoutJson(value as String)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply keyboard preference: $key", e)
            }
        }
        return count
    }

    private fun applyNotificationPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val editor = defaultPrefs.edit()
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "notifications_enabled",
                    "show_connection_notifications",
                    "show_error_notifications",
                    "show_file_transfer_notifications",
                    "notification_vibrate" -> editor.putBoolean(key, value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply notification preference: $key", e)
            }
        }
        editor.apply()
        return count
    }

    private fun applyMonitoringPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val editor = defaultPrefs.edit()
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "monitoring_enabled",
                    "monitoring_run_in_battery_saver",
                    "monitoring_notify_down",
                    "monitoring_notify_recovery" -> editor.putBoolean(key, value as Boolean)
                    // ListPreference value — stored and restored as String.
                    "monitoring_alert_cooldown_minutes" -> editor.putString(key, value as String)
                    // SeekBarPreference values — must be stored as Int to avoid
                    // ClassCastException when the Preference UI inflates.
                    "monitoring_default_cpu_threshold",
                    "monitoring_default_memory_threshold",
                    "monitoring_default_disk_threshold" -> {
                        val intVal = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: 0
                            else -> 0
                        }
                        editor.putInt(key, intVal)
                    }
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply monitoring preference: $key", e)
            }
        }
        editor.apply()
        return count
    }

    /**
     * Import single connection
     */
    suspend fun importConnection(connection: ConnectionProfile, strategy: MergeStrategy): Boolean {
        return try {
            when (strategy) {
                MergeStrategy.KEEP_LOCAL -> false
                MergeStrategy.KEEP_REMOTE, MergeStrategy.MERGE -> {
                    database.connectionDao().insertConnection(connection)
                    Logger.d(TAG, "Imported connection: ${connection.name}")
                    true
                }
                MergeStrategy.KEEP_BOTH -> {
                    val newConnection = connection.copy(id = java.util.UUID.randomUUID().toString())
                    database.connectionDao().insertConnection(newConnection)
                    Logger.d(TAG, "Imported duplicate connection: ${newConnection.name}")
                    true
                }
                MergeStrategy.SKIP -> false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import connection", e)
            false
        }
    }

    /**
     * Import single key
     */
    suspend fun importKey(key: StoredKey, strategy: MergeStrategy): Boolean {
        return try {
            when (strategy) {
                MergeStrategy.KEEP_LOCAL -> false
                MergeStrategy.KEEP_REMOTE, MergeStrategy.MERGE -> {
                    database.keyDao().insertKey(key)
                    Logger.d(TAG, "Imported key: ${key.name}")
                    true
                }
                MergeStrategy.KEEP_BOTH -> {
                    val newKey = key.copy(keyId = java.util.UUID.randomUUID().toString())
                    database.keyDao().insertKey(newKey)
                    Logger.d(TAG, "Imported duplicate key: ${newKey.name}")
                    true
                }
                MergeStrategy.SKIP -> false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import key", e)
            false
        }
    }
}

/**
 * Result of apply operation
 */
sealed class ApplyResult {
    data class Success(val appliedCount: Int) : ApplyResult()
    data class Error(val message: String) : ApplyResult()
}
