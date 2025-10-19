package com.tabssh.backup.export

import android.content.Context
import com.tabssh.storage.database.TabSSHDatabase
import com.tabssh.storage.database.entities.TrustedCertificate
import com.tabssh.storage.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

/**
 * Handles exporting data for backup
 */
class BackupExporter(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager
) {

    /**
     * Collect all data to be backed up
     */
    suspend fun collectBackupData(includePasswords: Boolean): Map<String, String> = withContext(Dispatchers.IO) {
        val backupData = mutableMapOf<String, String>()

        // Export connections
        backupData["connections.json"] = exportConnections(includePasswords)

        // Export SSH keys
        backupData["keys.json"] = exportKeys()

        // Export preferences
        backupData["preferences.json"] = exportPreferences()

        // Export themes
        backupData["themes.json"] = exportThemes()

        // Export trusted certificates
        backupData["certificates.json"] = exportCertificates()

        // Export known host keys
        backupData["host_keys.json"] = exportHostKeys()

        return@withContext backupData
    }

    private suspend fun exportConnections(includePasswords: Boolean): String {
        val connectionsFlow = database.connectionDao().getAllConnections()
        val connections = connectionsFlow.first() // Collect the Flow once
        val json = JSONObject()
        val connectionsArray = JSONArray()

        connections.forEach { connection ->
            val connectionJson = JSONObject().apply {
                put("id", connection.id)
                put("name", connection.name)
                put("host", connection.host)
                put("port", connection.port)
                put("username", connection.username)
                put("authType", connection.authType)
                put("keyId", connection.keyId)
                put("groupId", connection.groupId)
                put("theme", connection.theme)
                put("lastConnected", connection.lastConnected)
                put("connectionCount", connection.connectionCount)
                put("advancedSettings", connection.advancedSettings)

                if (includePasswords && connection.authType == "password") {
                    // Include encrypted password if requested
                    val password = preferenceManager.getConnectionPassword(connection.id)
                    if (password != null) {
                        put("encryptedPassword", android.util.Base64.encodeToString(
                            password.toByteArray(),
                            android.util.Base64.NO_WRAP
                        ))
                    }
                }
            }
            connectionsArray.put(connectionJson)
        }

        json.put("connections", connectionsArray)
        return json.toString(2)
    }

    private suspend fun exportKeys(): String {
        val keysFlow = database.keyDao().getAllKeys()
        val keys = keysFlow.first()
        val json = JSONObject()
        val keysArray = JSONArray()

        keys.forEach { key ->
            val keyJson = JSONObject().apply {
                put("keyId", key.keyId)
                put("name", key.name)
                put("keyType", key.keyType)
                put("comment", key.comment)
                put("fingerprint", key.fingerprint)
                put("createdAt", key.createdAt)
                put("lastUsed", key.lastUsed)
                put("requiresPassphrase", key.requiresPassphrase)
                // Note: Private key data is stored separately in secure storage
                // and would need special handling for export
            }
            keysArray.put(keyJson)
        }

        json.put("keys", keysArray)
        return json.toString(2)
    }

    private fun exportPreferences(): String {
        val json = JSONObject()

        // Export all preference categories
        json.put("general", JSONObject().apply {
            put("autoBackup", preferenceManager.isAutoBackupEnabled())
            put("backupFrequency", preferenceManager.getBackupFrequency())
            put("startupBehavior", preferenceManager.getStartupBehavior())
            put("language", preferenceManager.getLanguage())
        })

        json.put("security", JSONObject().apply {
            put("passwordStorageLevel", preferenceManager.getPasswordStorageLevel())
            put("requireBiometric", preferenceManager.isRequireBiometricForSensitive())
            put("strictHostKeyChecking", preferenceManager.isStrictHostKeyChecking())
            put("clearClipboardTimeout", preferenceManager.getClearClipboardTimeout())
        })

        json.put("terminal", JSONObject().apply {
            put("theme", preferenceManager.getTerminalTheme())
            put("fontSize", preferenceManager.getFontSize())
            put("fontFamily", preferenceManager.getFontFamily())
            put("cursorStyle", preferenceManager.getCursorStyle())
            put("cursorBlink", preferenceManager.isCursorBlinkEnabled())
            put("scrollbackLines", preferenceManager.getScrollbackLines())
        })

        json.put("ui", JSONObject().apply {
            put("maxTabs", preferenceManager.getMaxTabs())
            put("confirmTabClose", preferenceManager.isConfirmTabClose())
            put("appTheme", preferenceManager.getAppTheme())
            put("dynamicColors", preferenceManager.isDynamicColors())
        })

        return json.toString(2)
    }

    private suspend fun exportThemes(): String {
        val themesFlow = database.themeDao().getAllThemes()
        val themes = themesFlow.first()
        val json = JSONObject()
        val themesArray = JSONArray()

        themes.forEach { theme ->
            val themeJson = JSONObject().apply {
                put("id", theme.themeId)
                put("name", theme.name)
                put("author", theme.author)
                put("isDark", theme.isDark)
                put("themeData", theme.ansiColors ?: "")
                put("isCustom", !theme.isBuiltIn)
                put("createdAt", theme.createdAt)
            }
            themesArray.put(themeJson)
        }

        json.put("themes", themesArray)
        return json.toString(2)
    }

    private suspend fun exportCertificates(): String {
        val certificates = database.certificateDao().getAllCertificates().first()
        val json = JSONObject()
        val certificatesArray = JSONArray()

        certificates.forEach { cert: TrustedCertificate ->
            val certJson = JSONObject().apply {
                put("hostname", cert.hostname)
                put("port", cert.port)
                put("fingerprint", cert.fingerprint)
                put("subject", cert.subject)
                put("issuer", cert.issuer)
                put("notBefore", cert.notBefore)
                put("notAfter", cert.notAfter)
                put("certificateData", cert.certificateData)
            }
            certificatesArray.put(certJson)
        }

        json.put("certificates", certificatesArray)
        return json.toString(2)
    }

    private suspend fun exportHostKeys(): String {
        val hostKeysFlow = database.hostKeyDao().getAllHostKeys()
        val hostKeys = hostKeysFlow.first()
        val json = JSONObject()
        val hostKeysArray = JSONArray()

        hostKeys.forEach { hostKey ->
            val hostKeyJson = JSONObject().apply {
                put("hostname", hostKey.hostname)
                put("port", hostKey.port)
                put("keyType", hostKey.keyType)
                put("fingerprint", hostKey.fingerprint)
                put("publicKey", hostKey.publicKey)
                put("addedAt", hostKey.firstSeen)
                put("lastVerified", hostKey.lastVerified)
            }
            hostKeysArray.put(hostKeyJson)
        }

        json.put("host_keys", hostKeysArray)
        return json.toString(2)
    }
}