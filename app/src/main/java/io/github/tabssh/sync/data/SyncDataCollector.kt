package io.github.tabssh.sync.data

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.sync.models.SyncItemCounts
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Collects data for sync operations
 */
class SyncDataCollector(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager,
    private val metadataManager: SyncMetadataManager
) {

    companion object {
        private const val TAG = "SyncDataCollector"
    }

    /**
     * Collect all data for sync
     */
    suspend fun collectAllSyncData(): SyncDataPackage = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Collecting all sync data")

        val connections = collectConnections()
        val keys = collectKeys()
        val themes = collectThemes()
        val preferences = collectPreferences()
        val hostKeys = collectHostKeys()

        val itemCounts = SyncItemCounts(
            connections = connections.size,
            keys = keys.size,
            themes = themes.size,
            preferences = preferences.size,
            hostKeys = hostKeys.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)

        Logger.d(TAG, "Collected ${itemCounts.total()} items for sync")

        SyncDataPackage(
            connections = connections,
            keys = keys,
            themes = themes,
            preferences = preferences,
            hostKeys = hostKeys,
            metadata = metadata
        )
    }

    /**
     * Collect data changed since timestamp
     */
    suspend fun collectChangedSince(timestamp: Long): SyncDataPackage = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Collecting data changed since: $timestamp")

        val connections = collectConnections().filter { it.modifiedAt > timestamp }
        val keys = collectKeys().filter { it.modifiedAt > timestamp }
        val themes = collectThemes().filter { it.modifiedAt > timestamp }
        val hostKeys = collectHostKeys().filter { it.modifiedAt > timestamp }

        val preferences = if (hasPreferencesChanged(timestamp)) {
            collectPreferences()
        } else {
            emptyMap()
        }

        val itemCounts = SyncItemCounts(
            connections = connections.size,
            keys = keys.size,
            themes = themes.size,
            preferences = preferences.size,
            hostKeys = hostKeys.size
        )

        val metadata = metadataManager.createSyncMetadata(itemCounts)

        Logger.d(TAG, "Collected ${itemCounts.total()} changed items")

        SyncDataPackage(
            connections = connections,
            keys = keys,
            themes = themes,
            preferences = preferences,
            hostKeys = hostKeys,
            metadata = metadata
        )
    }

    /**
     * Collect connection profiles
     */
    private suspend fun collectConnections(): List<ConnectionProfile> {
        return try {
            val connectionsFlow = database.connectionDao().getAllConnections()
            connectionsFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect connections", e)
            emptyList()
        }
    }

    /**
     * Collect SSH keys
     */
    private suspend fun collectKeys(): List<StoredKey> {
        return try {
            val keysFlow = database.keyDao().getAllKeys()
            keysFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect keys", e)
            emptyList()
        }
    }

    /**
     * Collect themes
     */
    private suspend fun collectThemes(): List<ThemeDefinition> {
        return try {
            val themesFlow = database.themeDao().getAllThemes()
            themesFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect themes", e)
            emptyList()
        }
    }

    /**
     * Collect host keys
     */
    private suspend fun collectHostKeys(): List<HostKeyEntry> {
        return try {
            val hostKeysFlow = database.hostKeyDao().getAllHostKeys()
            hostKeysFlow.first()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect host keys", e)
            emptyList()
        }
    }

    /**
     * Collect preferences as map
     */
    private fun collectPreferences(): Map<String, Any> {
        val prefs = mutableMapOf<String, Any>()

        try {
            prefs["general"] = collectGeneralPreferences()
            prefs["security"] = collectSecurityPreferences()
            prefs["terminal"] = collectTerminalPreferences()
            prefs["ui"] = collectUIPreferences()
            prefs["connection"] = collectConnectionPreferences()
            prefs["sync"] = collectSyncPreferences()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect preferences", e)
        }

        return prefs
    }

    private fun collectGeneralPreferences(): Map<String, Any> {
        return mapOf(
            "autoBackup" to preferenceManager.isAutoBackupEnabled(),
            "backupFrequency" to preferenceManager.getBackupFrequency(),
            "startupBehavior" to preferenceManager.getStartupBehavior(),
            "language" to preferenceManager.getLanguage()
        )
    }

    private fun collectSecurityPreferences(): Map<String, Any> {
        return mapOf(
            "passwordStorageLevel" to preferenceManager.getPasswordStorageLevel(),
            "requireBiometric" to preferenceManager.isRequireBiometricForSensitive(),
            "strictHostKeyChecking" to preferenceManager.isStrictHostKeyChecking(),
            "clearClipboardTimeout" to preferenceManager.getClearClipboardTimeout(),
            "autoLockEnabled" to preferenceManager.isAutoLockOnBackground(),
            "lockTimeout" to preferenceManager.getAutoLockTimeout()
        )
    }

    private fun collectTerminalPreferences(): Map<String, Any> {
        return mapOf(
            "theme" to preferenceManager.getTerminalTheme(),
            "fontSize" to preferenceManager.getFontSize(),
            "fontFamily" to preferenceManager.getFontFamily(),
            "cursorStyle" to preferenceManager.getCursorStyle(),
            "cursorBlink" to preferenceManager.isCursorBlinkEnabled(),
            "scrollbackLines" to preferenceManager.getScrollbackLines(),
            "terminalBell" to preferenceManager.isBellNotificationEnabled()
        )
    }

    private fun collectUIPreferences(): Map<String, Any> {
        return mapOf(
            "maxTabs" to preferenceManager.getMaxTabs(),
            "confirmTabClose" to preferenceManager.isConfirmTabClose(),
            "appTheme" to preferenceManager.getAppTheme(),
            "dynamicColors" to preferenceManager.isDynamicColors()
        )
    }

    private fun collectConnectionPreferences(): Map<String, Any> {
        return mapOf(
            "defaultUsername" to preferenceManager.getDefaultUsername(),
            "defaultPort" to preferenceManager.getDefaultPort(),
            "connectTimeout" to preferenceManager.getConnectTimeout(),
            "autoReconnect" to preferenceManager.isAutoReconnect(),
            "compression" to preferenceManager.isCompressionEnabled()
        )
    }

    private fun collectSyncPreferences(): Map<String, Any> {
        return mapOf(
            "enabled" to preferenceManager.isSyncEnabled(),
            "frequency" to preferenceManager.getSyncFrequency(),
            "wifiOnly" to preferenceManager.isSyncWifiOnly(),
            "syncConnections" to preferenceManager.isSyncConnectionsEnabled(),
            "syncKeys" to preferenceManager.isSyncKeysEnabled(),
            "syncSettings" to preferenceManager.isSyncSettingsEnabled(),
            "syncThemes" to preferenceManager.isSyncThemesEnabled(),
            "lastSyncTime" to preferenceManager.getLastSyncTime()
        )
    }

    /**
     * Check if preferences have changed since timestamp
     */
    private fun hasPreferencesChanged(timestamp: Long): Boolean {
        val lastModified = preferenceManager.getPreferencesLastModified()
        return lastModified > timestamp
    }

    /**
     * Get item counts for current data
     */
    suspend fun getItemCounts(): SyncItemCounts = withContext(Dispatchers.IO) {
        SyncItemCounts(
            connections = database.connectionDao().getConnectionCount(),
            keys = database.keyDao().getKeyCount(),
            themes = database.themeDao().getThemeCount(),
            preferences = collectPreferences().size,
            hostKeys = database.hostKeyDao().getHostKeyCount()
        )
    }

    /**
     * Collect specific connection by ID
     */
    suspend fun collectConnection(id: String): ConnectionProfile? {
        return try {
            database.connectionDao().getConnectionById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect connection: $id", e)
            null
        }
    }

    /**
     * Collect specific key by ID
     */
    suspend fun collectKey(id: String): StoredKey? {
        return try {
            database.keyDao().getKeyById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect key: $id", e)
            null
        }
    }

    /**
     * Collect specific theme by ID
     */
    suspend fun collectTheme(id: String): ThemeDefinition? {
        return try {
            database.themeDao().getThemeById(id)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to collect theme: $id", e)
            null
        }
    }
}
