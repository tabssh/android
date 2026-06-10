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
            // Wire formats supported: v2 (entity-serialised) and v3
            // (single-JSON .tabssh). Anything newer means the backup was
            // produced by a future build we don't know yet.
            if (metadata.version > 3) {
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
            // v2 wraps every entity list under "items"; v1 used the table-plural
            // key ("connections"). Accept either.
            val arr = when {
                json.has("items") -> json.getJSONArray("items")
                json.has("connections") -> json.getJSONArray("connections")
                else -> { errors.add("Missing items array in connections.json"); null }
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val connection = arr.getJSONObject(i)
                    if (!connection.has("id")) errors.add("Connection at index $i missing id")
                    if (!connection.has("name")) errors.add("Connection at index $i missing name")
                    if (!connection.has("host")) errors.add("Connection at index $i missing host")
                    if (!connection.has("port")) errors.add("Connection at index $i missing port")
                    if (!connection.has("username")) errors.add("Connection at index $i missing username")
                    if (!connection.has("authType") && !connection.has("auth_type"))
                        errors.add("Connection at index $i missing authType")

                    val port = connection.optInt("port", -1)
                    if (port < 1 || port > 65535) {
                        errors.add("Connection at index $i has invalid port: $port")
                    }
                    // v2 ConnectionProfile.authType stores AuthType.name
                    // (PASSWORD/PUBLIC_KEY/...); v1 used SSH wire names.
                    val authType = connection.optString("authType",
                        connection.optString("auth_type", ""))
                    val ok = authType in listOf(
                        "password", "publickey", "keyboard-interactive",
                        "PASSWORD", "PUBLIC_KEY", "KEYBOARD_INTERACTIVE"
                    )
                    if (!ok) warnings.add("Connection at index $i has unknown authType: $authType")
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
            val arr = when {
                json.has("items") -> json.getJSONArray("items")
                json.has("keys") -> json.getJSONArray("keys")
                else -> { errors.add("Missing items array in keys.json"); null }
            }
            if (arr != null) {
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
            val arr = when {
                json.has("items") -> json.getJSONArray("items")
                json.has("themes") -> json.getJSONArray("themes")
                else -> { warnings.add("No themes found in backup"); null }
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val theme = arr.getJSONObject(i)
                    // v2 entity uses `themeId`; v1 hand-rolled used `id`.
                    if (!theme.has("themeId") && !theme.has("id"))
                        errors.add("Theme at index $i missing themeId")
                    if (!theme.has("name")) errors.add("Theme at index $i missing name")
                    if (!theme.has("isDark")) errors.add("Theme at index $i missing isDark")
                    // v2 carries ansiColors directly; v1 used themeData.
                    if (!theme.has("ansiColors") && !theme.has("themeData"))
                        warnings.add("Theme at index $i missing ansiColors")
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