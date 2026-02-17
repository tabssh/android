package io.github.tabssh.sync.data

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

            Logger.i(TAG, "Applied $appliedCount items from sync data")
            ApplyResult.Success(appliedCount)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply sync data", e)
            ApplyResult.Error("Failed to apply sync data: ${e.message}")
        }
    }

    /**
     * Apply merge result to database
     */
    suspend fun applyMergeResult(
        connectionResult: MergeResult<ConnectionProfile>,
        keyResult: MergeResult<StoredKey>,
        themeResult: MergeResult<ThemeDefinition>,
        hostKeyResult: MergeResult<HostKeyEntry>,
        preferences: Map<String, Any>
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
     * Apply preferences
     */
    private fun applyPreferences(preferences: Map<String, Any>): Int {
        var count = 0

        try {
            @Suppress("UNCHECKED_CAST")
            val general = preferences["general"] as? Map<String, Any>
            general?.let {
                count += applyGeneralPreferences(it)
            }

            @Suppress("UNCHECKED_CAST")
            val security = preferences["security"] as? Map<String, Any>
            security?.let {
                count += applySecurityPreferences(it)
            }

            @Suppress("UNCHECKED_CAST")
            val terminal = preferences["terminal"] as? Map<String, Any>
            terminal?.let {
                count += applyTerminalPreferences(it)
            }

            @Suppress("UNCHECKED_CAST")
            val ui = preferences["ui"] as? Map<String, Any>
            ui?.let {
                count += applyUIPreferences(it)
            }

            @Suppress("UNCHECKED_CAST")
            val connection = preferences["connection"] as? Map<String, Any>
            connection?.let {
                count += applyConnectionPreferences(it)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply preferences", e)
        }

        return count
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
