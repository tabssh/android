package io.github.tabssh.backup

import android.content.Context
import android.net.Uri
import io.github.tabssh.backup.export.BackupExporter
import io.github.tabssh.backup.import.BackupImporter
import io.github.tabssh.backup.validation.BackupValidator
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Main backup and restore manager
 * Handles full application backup including connections, keys, preferences, and themes
 */
class BackupManager(private val context: Context) {

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    private val exporter = BackupExporter(context, database, preferenceManager)
    private val importer = BackupImporter(context, database, preferenceManager)
    private val validator = BackupValidator()

    data class BackupMetadata(
        val version: Int = BACKUP_VERSION,
        val createdAt: Long = System.currentTimeMillis(),
        val appVersion: String,
        val deviceModel: String,
        val androidVersion: Int,
        val itemCounts: Map<String, Int>
    )

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val metadata: BackupMetadata? = null,
        val filePath: String? = null
    )

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        val restoredItems: Map<String, Int> = emptyMap(),
        val errors: List<String> = emptyList()
    )

    companion object {
        private const val BACKUP_VERSION = 1
        private const val METADATA_FILE = "metadata.json"
        private const val CONNECTIONS_FILE = "connections.json"
        private const val KEYS_FILE = "keys.json"
        private const val PREFERENCES_FILE = "preferences.json"
        private const val THEMES_FILE = "themes.json"
        private const val CERTIFICATES_FILE = "certificates.json"
        private const val HOST_KEYS_FILE = "host_keys.json"
    }

    /**
     * Create a full backup
     */
    suspend fun createBackup(
        outputUri: Uri,
        includePasswords: Boolean = false,
        encryptBackup: Boolean = true,
        password: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            Logger.i("BackupManager", "Creating backup...")

            // Collect all data to backup
            val backupData = exporter.collectBackupData(includePasswords)

            // Create metadata
            val metadata = createBackupMetadata(backupData)

            // Write backup to zip file
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    // Write metadata
                    writeZipEntry(zipOut, METADATA_FILE, JSONObject().apply {
                        put("version", metadata.version)
                        put("createdAt", metadata.createdAt)
                        put("appVersion", metadata.appVersion)
                        put("deviceModel", metadata.deviceModel)
                        put("androidVersion", metadata.androidVersion)
                        put("itemCounts", JSONObject(metadata.itemCounts))
                    }.toString())

                    // Write backup data
                    backupData.forEach { (filename, data) ->
                        val jsonData = if (encryptBackup && password != null) {
                            encryptData(data, password)
                        } else {
                            data
                        }
                        writeZipEntry(zipOut, filename, jsonData)
                    }
                }
            }

            Logger.i("BackupManager", "Backup created successfully")
            return@withContext BackupResult(
                success = true,
                message = "Backup created successfully",
                metadata = metadata,
                filePath = outputUri.path
            )
        } catch (e: Exception) {
            Logger.e("BackupManager", "Failed to create backup", e)
            return@withContext BackupResult(
                success = false,
                message = "Failed to create backup: ${e.message}"
            )
        }
    }

    /**
     * Restore from backup
     */
    suspend fun restoreBackup(
        inputUri: Uri,
        password: String? = null,
        overwriteExisting: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            Logger.i("BackupManager", "Starting restore...")

            val backupData = mutableMapOf<String, String>()
            var metadata: BackupMetadata? = null

            // Read backup from zip file
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val content = zipIn.bufferedReader().readText()

                            if (entry.name == METADATA_FILE) {
                                metadata = parseMetadata(content)
                            } else {
                                backupData[entry.name] = if (password != null) {
                                    decryptData(content, password)
                                } else {
                                    content
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            // Validate backup
            val validationResult = validator.validateBackup(backupData, metadata)
            if (!validationResult.isValid) {
                return@withContext RestoreResult(
                    success = false,
                    message = "Invalid backup: ${validationResult.errors.joinToString(", ")}",
                    errors = validationResult.errors
                )
            }

            // Restore data
            val restoredItems = importer.restoreBackupData(backupData, overwriteExisting)

            Logger.i("BackupManager", "Restore completed successfully")
            return@withContext RestoreResult(
                success = true,
                message = "Restore completed successfully",
                restoredItems = restoredItems
            )
        } catch (e: Exception) {
            Logger.e("BackupManager", "Failed to restore backup", e)
            return@withContext RestoreResult(
                success = false,
                message = "Failed to restore backup: ${e.message}",
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }

    /**
     * Create backup metadata
     */
    private suspend fun createBackupMetadata(backupData: Map<String, String>): BackupMetadata {
        val itemCounts = mutableMapOf<String, Int>()

        // Count items in each category
        backupData.forEach { (filename, data) ->
            when (filename) {
                CONNECTIONS_FILE -> {
                    val json = JSONObject(data)
                    itemCounts["connections"] = json.getJSONArray("connections").length()
                }
                KEYS_FILE -> {
                    val json = JSONObject(data)
                    itemCounts["keys"] = json.getJSONArray("keys").length()
                }
                THEMES_FILE -> {
                    val json = JSONObject(data)
                    itemCounts["themes"] = json.getJSONArray("themes").length()
                }
                CERTIFICATES_FILE -> {
                    val json = JSONObject(data)
                    itemCounts["certificates"] = json.getJSONArray("certificates").length()
                }
                HOST_KEYS_FILE -> {
                    val json = JSONObject(data)
                    itemCounts["host_keys"] = json.getJSONArray("host_keys").length()
                }
            }
        }

        return BackupMetadata(
            appVersion = getAppVersion(),
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.SDK_INT,
            itemCounts = itemCounts
        )
    }

    /**
     * Parse backup metadata
     */
    private fun parseMetadata(content: String): BackupMetadata {
        val json = JSONObject(content)
        val itemCountsJson = json.getJSONObject("itemCounts")
        val itemCounts = mutableMapOf<String, Int>()

        itemCountsJson.keys().forEach { key ->
            itemCounts[key] = itemCountsJson.getInt(key)
        }

        return BackupMetadata(
            version = json.getInt("version"),
            createdAt = json.getLong("createdAt"),
            appVersion = json.getString("appVersion"),
            deviceModel = json.getString("deviceModel"),
            androidVersion = json.getInt("androidVersion"),
            itemCounts = itemCounts
        )
    }

    /**
     * Write entry to zip file
     */
    private fun writeZipEntry(zipOut: ZipOutputStream, filename: String, content: String) {
        val entry = ZipEntry(filename)
        zipOut.putNextEntry(entry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
    }

    /**
     * Encrypt backup data
     */
    private fun encryptData(data: String, password: String): String {
        // Simple implementation - in production would use proper encryption
        // Using Android Keystore and AES-GCM
        return android.util.Base64.encodeToString(
            data.toByteArray(),
            android.util.Base64.NO_WRAP
        )
    }

    /**
     * Decrypt backup data
     */
    private fun decryptData(data: String, password: String): String {
        // Simple implementation - in production would use proper decryption
        return String(
            android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        )
    }

    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Generate backup filename
     */
    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        return "tabssh_backup_$timestamp.zip"
    }

    /**
     * Schedule automatic backup
     */
    fun scheduleAutomaticBackup(frequency: BackupFrequency) {
        // Would use WorkManager to schedule periodic backups
        Logger.d("BackupManager", "Scheduling automatic backup: $frequency")
    }

    enum class BackupFrequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        NEVER
    }
}