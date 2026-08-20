package io.github.tabssh.backup.validation

import io.github.tabssh.backup.BackupManager
import io.github.tabssh.utils.logging.Logger
import org.json.JSONObject

/**
 * Validates backup data integrity and format.
 *
 * There is exactly one wire format ([BackupManager.BACKUP_VERSION]). Any other
 * version is an unsupported archive and is rejected — this validator has no
 * legacy read path and never attempts a best-effort parse of an older shape.
 */
class BackupValidator {

    private companion object {
        private const val TAG = "BackupValidator"

        /** Magic prefix written by SyncEncryptor on an encrypted archive. */
        private const val ENCRYPTED_MAGIC = "TABSSH_SYNC_V3"
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    /**
     * Validate backup data
     */
    fun validateBackup(
        backupData: Map<String, String>,
        metadata: BackupManager.BackupMetadata?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // Validate metadata
        if (metadata == null) {
            errors.add("Missing backup metadata")
        } else {
            // One writer, one reader, one version. Anything else — older or
            // newer — is not a format this build can read.
            if (metadata.version != BackupManager.BACKUP_VERSION) {
                errors.add(
                    "Unsupported backup format: version ${metadata.version} " +
                        "(this build reads version ${BackupManager.BACKUP_VERSION} only)"
                )
            }
        }

        // Validate connections file
        backupData["connections.json"]?.let { data ->
            val result = validateConnectionsData(data)
            errors.addAll(result.errors)
            warnings.addAll(result.warnings)
        }

        // Validate keys file
        backupData["keys.json"]?.let { data ->
            val result = validateKeysData(data)
            errors.addAll(result.errors)
            warnings.addAll(result.warnings)
        }

        // Validate preferences file
        backupData["preferences.json"]?.let { data ->
            val result = validatePreferencesData(data)
            errors.addAll(result.errors)
            warnings.addAll(result.warnings)
        }

        // Validate themes file
        backupData["themes.json"]?.let { data ->
            val result = validateThemesData(data)
            errors.addAll(result.errors)
            warnings.addAll(result.warnings)
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun validateConnectionsData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)
            val arr = json.optJSONArray("items")
            if (arr == null) {
                errors.add("Missing items array in connections.json")
            } else {
                for (i in 0 until arr.length()) {
                    val connection = arr.getJSONObject(i)
                    if (!connection.has("id")) errors.add("Connection at index $i missing id")
                    if (!connection.has("name")) errors.add("Connection at index $i missing name")
                    if (!connection.has("host")) errors.add("Connection at index $i missing host")
                    if (!connection.has("port")) errors.add("Connection at index $i missing port")
                    if (!connection.has("username")) errors.add("Connection at index $i missing username")
                    if (!connection.has("authType")) errors.add("Connection at index $i missing authType")

                    val port = connection.optInt("port", -1)
                    if (port < 1 || port > 65535) {
                        errors.add("Connection at index $i has invalid port: $port")
                    }
                    // ConnectionProfile.authType stores AuthType.name.
                    val authType = connection.optString("authType", "")
                    val ok = authType in io.github.tabssh.ssh.auth.AuthType.entries.map { it.name }
                    if (!ok) warnings.add("Connection at index $i has unknown authType: $authType")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse connections.json: ${e.message}")
            errors.add("Invalid JSON in connections.json: ${e.message}")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    private fun validateKeysData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)
            val arr = json.optJSONArray("items")
            if (arr == null) {
                errors.add("Missing items array in keys.json")
            } else {
                for (i in 0 until arr.length()) {
                    val key = arr.getJSONObject(i)
                    if (!key.has("keyId")) errors.add("Key at index $i missing keyId")
                    if (!key.has("name")) errors.add("Key at index $i missing name")
                    if (!key.has("keyType")) errors.add("Key at index $i missing keyType")
                    if (!key.has("fingerprint")) errors.add("Key at index $i missing fingerprint")
                    val keyType = key.optString("keyType")
                    if (keyType !in listOf("RSA", "DSA", "ECDSA", "Ed25519")) {
                        warnings.add("Key at index $i has unknown keyType: $keyType")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse keys.json: ${e.message}")
            errors.add("Invalid JSON in keys.json: ${e.message}")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    private fun validatePreferencesData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)

            // Check for expected preference categories
            val expectedCategories = listOf("general", "security", "terminal", "ui")
            expectedCategories.forEach { category ->
                if (!json.has(category)) {
                    warnings.add("Missing preference category: $category")
                }
            }

            // Validate specific preferences
            json.optJSONObject("terminal")?.let { terminal ->
                val fontSize = terminal.optDouble("fontSize", 0.0)
                if (fontSize < 8 || fontSize > 32) {
                    warnings.add("Invalid terminal font size: $fontSize")
                }

                val scrollbackLines = terminal.optInt("scrollbackLines", 0)
                if (scrollbackLines < 100 || scrollbackLines > 10000) {
                    warnings.add("Invalid scrollback lines: $scrollbackLines")
                }
            }

            json.optJSONObject("ui")?.let { ui ->
                val maxTabs = ui.optInt("maxTabs", 0)
                if (maxTabs < 1 || maxTabs > 50) {
                    warnings.add("Invalid max tabs: $maxTabs")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse preferences.json: ${e.message}")
            errors.add("Invalid JSON in preferences.json: ${e.message}")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    private fun validateThemesData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)
            val arr = json.optJSONArray("items")
            if (arr == null) {
                warnings.add("No themes found in backup")
            } else {
                for (i in 0 until arr.length()) {
                    val theme = arr.getJSONObject(i)
                    if (!theme.has("themeId")) errors.add("Theme at index $i missing themeId")
                    if (!theme.has("name")) errors.add("Theme at index $i missing name")
                    if (!theme.has("isDark")) errors.add("Theme at index $i missing isDark")
                    if (!theme.has("ansiColors")) warnings.add("Theme at index $i missing ansiColors")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse themes.json: ${e.message}")
            errors.add("Invalid JSON in themes.json: ${e.message}")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    /**
     * Check whether a backup file is encrypted.
     *
     * A backup produced by [BackupManager] is either plain UTF-8 JSON or an
     * AES-GCM ciphertext carrying the [ENCRYPTED_MAGIC] header written by
     * [io.github.tabssh.sync.encryption.SyncEncryptor]. That output is raw
     * binary, so the check must be made on the bytes — a String view would
     * mangle them.
     */
    fun isBackupEncrypted(data: ByteArray): Boolean {
        if (data.size < ENCRYPTED_MAGIC.length) return false
        return String(data, 0, ENCRYPTED_MAGIC.length, Charsets.ISO_8859_1) == ENCRYPTED_MAGIC
    }

    /**
     * Get backup summary
     */
    fun getBackupSummary(metadata: BackupManager.BackupMetadata): String {
        val items = metadata.itemCounts
        return buildString {
            appendLine("Backup Summary:")
            appendLine("Version: ${metadata.version}")
            appendLine("Created: ${java.util.Date(metadata.createdAt)}")
            appendLine("App Version: ${metadata.appVersion}")
            appendLine()
            appendLine("Contents:")
            items.forEach { (type, count) ->
                appendLine("  ${type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: $count")
            }
        }
    }
}
