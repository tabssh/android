package io.github.tabssh.backup.validation

import io.github.tabssh.backup.BackupManager
import org.json.JSONObject

/**
 * Validates backup data integrity and format
 */
class BackupValidator {

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
            if (metadata.version > 1) {
                errors.add("Unsupported backup version: ${metadata.version}")
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
            if (!json.has("connections")) {
                errors.add("Missing connections array in connections.json")
            } else {
                val connections = json.getJSONArray("connections")
                for (i in 0 until connections.length()) {
                    val connection = connections.getJSONObject(i)

                    // Validate required fields
                    if (!connection.has("id")) errors.add("Connection at index $i missing id")
                    if (!connection.has("name")) errors.add("Connection at index $i missing name")
                    if (!connection.has("host")) errors.add("Connection at index $i missing host")
                    if (!connection.has("port")) errors.add("Connection at index $i missing port")
                    if (!connection.has("username")) errors.add("Connection at index $i missing username")
                    if (!connection.has("authType")) errors.add("Connection at index $i missing authType")

                    // Validate port range
                    val port = connection.optInt("port", -1)
                    if (port < 1 || port > 65535) {
                        errors.add("Connection at index $i has invalid port: $port")
                    }

                    // Validate auth type
                    val authType = connection.optString("authType")
                    if (authType !in listOf("password", "publickey", "keyboard-interactive")) {
                        warnings.add("Connection at index $i has unknown authType: $authType")
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Invalid JSON in connections.json: ${e.message}")
        }

        return ValidationResult(false, errors, warnings)
    }

    private fun validateKeysData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)
            if (!json.has("keys")) {
                errors.add("Missing keys array in keys.json")
            } else {
                val keys = json.getJSONArray("keys")
                for (i in 0 until keys.length()) {
                    val key = keys.getJSONObject(i)

                    // Validate required fields
                    if (!key.has("keyId")) errors.add("Key at index $i missing keyId")
                    if (!key.has("name")) errors.add("Key at index $i missing name")
                    if (!key.has("keyType")) errors.add("Key at index $i missing keyType")
                    if (!key.has("fingerprint")) errors.add("Key at index $i missing fingerprint")

                    // Validate key type
                    val keyType = key.optString("keyType")
                    if (keyType !in listOf("RSA", "DSA", "ECDSA", "Ed25519")) {
                        warnings.add("Key at index $i has unknown keyType: $keyType")
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Invalid JSON in keys.json: ${e.message}")
        }

        return ValidationResult(false, errors, warnings)
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
            errors.add("Invalid JSON in preferences.json: ${e.message}")
        }

        return ValidationResult(false, errors, warnings)
    }

    private fun validateThemesData(data: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val json = JSONObject(data)
            if (!json.has("themes")) {
                // Themes are optional
                warnings.add("No themes found in backup")
            } else {
                val themes = json.getJSONArray("themes")
                for (i in 0 until themes.length()) {
                    val theme = themes.getJSONObject(i)

                    // Validate required fields
                    if (!theme.has("id")) errors.add("Theme at index $i missing id")
                    if (!theme.has("name")) errors.add("Theme at index $i missing name")
                    if (!theme.has("isDark")) errors.add("Theme at index $i missing isDark")
                    if (!theme.has("themeData")) errors.add("Theme at index $i missing themeData")
                }
            }
        } catch (e: Exception) {
            errors.add("Invalid JSON in themes.json: ${e.message}")
        }

        return ValidationResult(false, errors, warnings)
    }

    /**
     * Check if backup file is encrypted
     */
    fun isBackupEncrypted(data: String): Boolean {
        // Simple check - encrypted data would be base64 and not valid JSON
        return try {
            JSONObject(data)
            false
        } catch (e: Exception) {
            // If it's not valid JSON, it might be encrypted
            data.matches(Regex("^[A-Za-z0-9+/]+=*$"))
        }
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