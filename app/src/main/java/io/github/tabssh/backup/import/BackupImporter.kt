package io.github.tabssh.backup.import

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.*
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Handles importing data from backup
 */
class BackupImporter(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val preferenceManager: PreferenceManager
) {

    /**
     * Restore backup data
     */
    suspend fun restoreBackupData(
        backupData: Map<String, String>,
        overwriteExisting: Boolean
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val restoredItems = mutableMapOf<String, Int>()

        // Restore connections
        backupData["connections.json"]?.let { data ->
            val count = restoreConnections(data, overwriteExisting)
            restoredItems["connections"] = count
            Logger.d("BackupImporter", "Restored $count connections")
        }

        // Restore SSH keys
        backupData["keys.json"]?.let { data ->
            val count = restoreKeys(data, overwriteExisting)
            restoredItems["keys"] = count
            Logger.d("BackupImporter", "Restored $count keys")
        }

        // Restore preferences
        backupData["preferences.json"]?.let { data ->
            restorePreferences(data)
            restoredItems["preferences"] = 1
            Logger.d("BackupImporter", "Restored preferences")
        }

        // Restore themes
        backupData["themes.json"]?.let { data ->
            val count = restoreThemes(data, overwriteExisting)
            restoredItems["themes"] = count
            Logger.d("BackupImporter", "Restored $count themes")
        }

        // Restore certificates
        backupData["certificates.json"]?.let { data ->
            val count = restoreCertificates(data, overwriteExisting)
            restoredItems["certificates"] = count
            Logger.d("BackupImporter", "Restored $count certificates")
        }

        // Restore host keys
        backupData["host_keys.json"]?.let { data ->
            val count = restoreHostKeys(data, overwriteExisting)
            restoredItems["host_keys"] = count
            Logger.d("BackupImporter", "Restored $count host keys")
        }

        return@withContext restoredItems
    }

    private suspend fun restoreConnections(data: String, overwriteExisting: Boolean): Int {
        val json = JSONObject(data)
        val connectionsArray = json.getJSONArray("connections")
        var restoredCount = 0

        for (i in 0 until connectionsArray.length()) {
            val connectionJson = connectionsArray.getJSONObject(i)

            val existingConnection = database.connectionDao().getConnection(connectionJson.getString("id"))
            if (existingConnection != null && !overwriteExisting) {
                Logger.d("BackupImporter", "Skipping existing connection: ${connectionJson.getString("name")}")
                continue
            }

            val connection = ConnectionProfile(
                id = connectionJson.getString("id"),
                name = connectionJson.getString("name"),
                host = connectionJson.getString("host"),
                port = connectionJson.getInt("port"),
                username = connectionJson.getString("username"),
                authType = connectionJson.getString("authType"),
                keyId = connectionJson.optString("keyId", null),
                groupId = connectionJson.optString("groupId", null),
                theme = connectionJson.optString("theme", null),
                createdAt = System.currentTimeMillis(),
                lastConnected = connectionJson.optLong("lastConnected", 0),
                connectionCount = connectionJson.optInt("connectionCount", 0),
                advancedSettings = connectionJson.optString("advancedSettings", null)
            )

            database.connectionDao().insertConnection(connection)

            // Restore password if present
            if (connectionJson.has("encryptedPassword")) {
                val encryptedPassword = connectionJson.getString("encryptedPassword")
                val password = String(android.util.Base64.decode(encryptedPassword, android.util.Base64.NO_WRAP))
                preferenceManager.setConnectionPassword(connection.id, password)
            }

            restoredCount++
        }

        return restoredCount
    }

    private suspend fun restoreKeys(data: String, overwriteExisting: Boolean): Int {
        val json = JSONObject(data)
        val keysArray = json.getJSONArray("keys")
        var restoredCount = 0

        for (i in 0 until keysArray.length()) {
            val keyJson = keysArray.getJSONObject(i)

            val existingKey = database.keyDao().getKey(keyJson.getString("keyId"))
            if (existingKey != null && !overwriteExisting) {
                Logger.d("BackupImporter", "Skipping existing key: ${keyJson.getString("name")}")
                continue
            }

            val key = StoredKey(
                keyId = keyJson.getString("keyId"),
                name = keyJson.getString("name"),
                keyType = keyJson.getString("keyType"),
                comment = keyJson.optString("comment", null),
                fingerprint = keyJson.getString("fingerprint"),
                createdAt = keyJson.getLong("createdAt"),
                lastUsed = keyJson.optLong("lastUsed", 0),
                requiresPassphrase = keyJson.getBoolean("requiresPassphrase")
            )

            database.keyDao().insertKey(key)
            restoredCount++
        }

        return restoredCount
    }

    private fun restorePreferences(data: String) {
        val json = JSONObject(data)

        // Restore general preferences
        json.optJSONObject("general")?.let { general ->
            preferenceManager.setAutoBackup(general.optBoolean("autoBackup", true))
            preferenceManager.setBackupFrequency(general.optString("backupFrequency", "weekly"))
            preferenceManager.setStartupBehavior(general.optString("startupBehavior", "last_session"))
            preferenceManager.setLanguage(general.optString("language", "system"))
        }

        // Restore security preferences
        json.optJSONObject("security")?.let { security ->
            preferenceManager.setPasswordStorageLevel(security.optString("passwordStorageLevel", "encrypted"))
            preferenceManager.setRequireBiometricForSensitive(security.optBoolean("requireBiometric", true))
            preferenceManager.setStrictHostKeyChecking(security.optBoolean("strictHostKeyChecking", true))
            preferenceManager.setClearClipboardTimeout(security.optInt("clearClipboardTimeout", 60))
        }

        // Restore terminal preferences
        json.optJSONObject("terminal")?.let { terminal ->
            preferenceManager.setTerminalTheme(terminal.optString("theme", "dracula"))
            preferenceManager.setFontSize(terminal.optDouble("fontSize", 14.0).toFloat())
            preferenceManager.setFontFamily(terminal.optString("fontFamily", "Roboto Mono"))
            preferenceManager.setCursorStyle(terminal.optString("cursorStyle", "block"))
            preferenceManager.setCursorBlink(terminal.optBoolean("cursorBlink", true))
            preferenceManager.setScrollbackLines(terminal.optInt("scrollbackLines", 1000))
        }

        // Restore UI preferences
        json.optJSONObject("ui")?.let { ui ->
            preferenceManager.setMaxTabs(ui.optInt("maxTabs", 10))
            preferenceManager.setConfirmTabClose(ui.optBoolean("confirmTabClose", true))
            preferenceManager.setAppTheme(ui.optString("appTheme", "system"))
            preferenceManager.setDynamicColors(ui.optBoolean("dynamicColors", true))
        }
    }

    private suspend fun restoreThemes(data: String, overwriteExisting: Boolean): Int {
        val json = JSONObject(data)
        val themesArray = json.getJSONArray("themes")
        var restoredCount = 0

        for (i in 0 until themesArray.length()) {
            val themeJson = themesArray.getJSONObject(i)

            val existingTheme = database.themeDao().getTheme(themeJson.getString("id"))
            if (existingTheme != null && !overwriteExisting) {
                Logger.d("BackupImporter", "Skipping existing theme: ${themeJson.getString("name")}")
                continue
            }

            // Parse ANSI colors from JSON if available
            val ansiColors = themeJson.optString("ansiColors", "[]")
            val backgroundColor = themeJson.optInt("backgroundColor", 0xFF000000.toInt())
            val foregroundColor = themeJson.optInt("foregroundColor", 0xFFFFFFFF.toInt())
            val cursorColor = themeJson.optInt("cursorColor", 0xFFFFFFFF.toInt())
            val selectionColor = themeJson.optInt("selectionColor", 0x80808080.toInt())

            val theme = ThemeDefinition(
                themeId = themeJson.getString("id"),
                name = themeJson.getString("name"),
                author = themeJson.optString("author", "Unknown"),
                isDark = themeJson.getBoolean("isDark"),
                isBuiltIn = false,
                backgroundColor = backgroundColor,
                foregroundColor = foregroundColor,
                cursorColor = cursorColor,
                selectionColor = selectionColor,
                ansiColors = ansiColors,
                createdAt = themeJson.optLong("createdAt", System.currentTimeMillis())
            )

            database.themeDao().insertTheme(theme)
            restoredCount++
        }

        return restoredCount
    }

    private suspend fun restoreCertificates(data: String, overwriteExisting: Boolean): Int {
        val json = JSONObject(data)
        val certificatesArray = json.getJSONArray("certificates")
        var restoredCount = 0

        for (i in 0 until certificatesArray.length()) {
            val certJson = certificatesArray.getJSONObject(i)

            val hostname = certJson.getString("hostname")
            val port = certJson.getInt("port")

            val existingCert = database.certificateDao().getCertificateByHostAndPort(hostname, port)
            if (existingCert != null && !overwriteExisting) {
                Logger.d("BackupImporter", "Skipping existing certificate: $hostname:$port")
                continue
            }

            val certificate = TrustedCertificate(
                id = "$hostname:$port",
                hostname = hostname,
                port = port,
                fingerprint = certJson.getString("fingerprint"),
                algorithm = "SHA-256",
                certificateData = certJson.getString("certificateData"),
                subject = certJson.getString("subject"),
                issuer = certJson.getString("issuer"),
                serialNumber = certJson.optString("serialNumber", "UNKNOWN"),
                notBefore = certJson.getLong("notBefore"),
                notAfter = certJson.getLong("notAfter"),
                expiresAt = certJson.getLong("notAfter"),
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis()
            )

            database.certificateDao().insertCertificate(certificate)
            restoredCount++
        }

        return restoredCount
    }

    private suspend fun restoreHostKeys(data: String, overwriteExisting: Boolean): Int {
        val json = JSONObject(data)
        val hostKeysArray = json.getJSONArray("host_keys")
        var restoredCount = 0

        for (i in 0 until hostKeysArray.length()) {
            val hostKeyJson = hostKeysArray.getJSONObject(i)

            val hostname = hostKeyJson.getString("hostname")
            val port = hostKeyJson.getInt("port")

            val existingKey = database.hostKeyDao().getHostKey(hostname, port)
            if (existingKey != null && !overwriteExisting) {
                Logger.d("BackupImporter", "Skipping existing host key: $hostname:$port")
                continue
            }

            val hostKey = HostKeyEntry(
                id = "$hostname:$port",
                hostname = hostname,
                port = port,
                keyType = hostKeyJson.getString("keyType"),
                fingerprint = hostKeyJson.getString("fingerprint"),
                publicKey = hostKeyJson.getString("publicKey"),
                firstSeen = hostKeyJson.getLong("addedAt"),
                lastVerified = hostKeyJson.getLong("lastVerified")
            )

            database.hostKeyDao().insertHostKey(hostKey)
            restoredCount++
        }

        return restoredCount
    }
}