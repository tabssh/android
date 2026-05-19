package io.github.tabssh.backup

import android.content.Context
import android.net.Uri
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.backup.export.BackupExporter
import io.github.tabssh.backup.import.BackupImporter
import io.github.tabssh.backup.validation.BackupValidator
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Main backup and restore manager
 * Handles full application backup including connections, keys, preferences, and themes
 */
class BackupManager(private val context: Context) {

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    // Resolve credential managers from the Application singleton so that
    // encrypted backups include all Keystore-backed secrets (passwords,
    // tokens, SSH key material). Null-safe: if the app hasn't initialised
    // yet the exporter/importer will skip the secrets section gracefully.
    private val app: TabSSHApplication?
        get() = context.applicationContext as? TabSSHApplication
    private val exporter by lazy {
        BackupExporter(context, database, preferenceManager,
            app?.securePasswordManager, app?.keyStorage)
    }
    private val importer by lazy {
        BackupImporter(context, database, preferenceManager,
            app?.securePasswordManager, app?.keyStorage)
    }
    private val validator = BackupValidator()
    // P0 fix: real password-based encryption for backups. Reuses the
    // sync subsystem's SyncEncryptor (AES-256-GCM + PBKDF2 100k iter,
    // see SyncEncryptor.kt) instead of the previous Base64-only stub
    // that silently failed to encrypt anything despite the
    // `encryptBackup=true` UI promise.
    private val encryptor = SyncEncryptor()

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
        private const val BACKUP_VERSION = 3
        private const val METADATA_FILE = "metadata.json"
        // Wire-format `v` field on each entity file (set by BackupExporter).
        // Bumped from 1 to 2 in the 2026-05-16 backup-coverage audit:
        // every Room entity is now serialised in full so restore reproduces
        // the exact source row. v1 backups still restore via the legacy
        // hand-rolled path in BackupImporter.
        // v3 (2026-05-17): single-JSON format (.tabssh extension) replaces ZIP.
        // Old ZIP backups are still restored via the legacy ZipInputStream path.
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

            // Collect all data to backup.
            // includeSecrets adds a secrets.json with all Keystore-backed
            // credentials (SSH key bytes, hypervisor/VNC/cloud passwords,
            // OCI tokens). Only safe inside an encrypted backup envelope.
            val includeSecrets = encryptBackup && password != null
            val backupData = exporter.collectBackupData(includePasswords, includeSecrets)

            // Create metadata
            val metadata = createBackupMetadata(backupData)

            // Build single-JSON v3 backup
            val root = JSONObject().apply {
                put("v", BACKUP_VERSION)
                put("metadata", JSONObject().apply {
                    put("version", metadata.version)
                    put("createdAt", metadata.createdAt)
                    put("appVersion", metadata.appVersion)
                    put("deviceModel", metadata.deviceModel)
                    put("androidVersion", metadata.androidVersion)
                    put("itemCounts", JSONObject(metadata.itemCounts))
                })
                val dataObj = JSONObject()
                backupData.forEach { (k, v) -> dataObj.put(k, v) }
                put("data", dataObj)
            }
            val plainJson = root.toString()

            val bytes: ByteArray = if (encryptBackup && password != null) {
                encryptor.encrypt(plainJson.toByteArray(Charsets.UTF_8), password)
            } else {
                plainJson.toByteArray(Charsets.UTF_8)
            }

            context.contentResolver.openOutputStream(outputUri)?.use { it.write(bytes) }

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

            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                val allBytes = inputStream.readBytes()
                // Detect format: ZIP magic is PK (0x50 0x4B).
                if (allBytes.size >= 2 && allBytes[0] == 0x50.toByte() && allBytes[1] == 0x4B.toByte()) {
                    // Legacy ZIP backup
                    ZipInputStream(allBytes.inputStream()).use { zipIn ->
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
                } else {
                    // v3 single-JSON format (optionally AES-GCM encrypted)
                    val plainBytes: ByteArray = if (password != null) {
                        try {
                            encryptor.decrypt(allBytes, password)
                        } catch (_: Exception) {
                            // Not encrypted or wrong password — treat as raw JSON
                            allBytes
                        }
                    } else {
                        allBytes
                    }
                    val root = JSONObject(String(plainBytes, Charsets.UTF_8))
                    val metaObj = root.getJSONObject("metadata")
                    val itemCountsObj = metaObj.getJSONObject("itemCounts")
                    val itemCounts = mutableMapOf<String, Int>()
                    itemCountsObj.keys().forEach { key -> itemCounts[key] = itemCountsObj.getInt(key) }
                    metadata = BackupMetadata(
                        version = metaObj.getInt("version"),
                        createdAt = metaObj.getLong("createdAt"),
                        appVersion = metaObj.getString("appVersion"),
                        deviceModel = metaObj.getString("deviceModel"),
                        androidVersion = metaObj.getInt("androidVersion"),
                        itemCounts = itemCounts
                    )
                    val dataObj = root.getJSONObject("data")
                    dataObj.keys().forEach { key -> backupData[key] = dataObj.getString(key) }
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

        // Generic counter: every entity file the exporter writes uses
        // either the v2 wrapper (`{"v":2,"items":[...]}`) or the legacy
        // v1 `{"<plural>":[...]}` shape. Preferences are an object, not a
        // list — skip those.
        backupData.forEach { (filename, data) ->
            if (filename == "preferences.json") return@forEach
            val key = filename.removeSuffix(".json")
            val count = try {
                val obj = JSONObject(data)
                when {
                    obj.has("items") -> obj.getJSONArray("items").length()
                    obj.has(key) -> obj.getJSONArray(key).length()
                    else -> 0
                }
            } catch (_: Exception) { 0 }
            itemCounts[key] = count
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
     * Decrypt a legacy ZIP entry value (Base64-wrapped AES-GCM or bare Base64).
     * Only used by the legacy ZIP restore path.
     */
    private fun decryptData(data: String, password: String): String {
        val raw = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        return try {
            val plain = encryptor.decrypt(raw, password)
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            // Legacy fallthrough — old backups were Base64-only; this
            // path keeps them restorable.
            Logger.w("BackupManager",
                "decrypt failed (${e.message}); falling back to legacy Base64-only format")
            String(raw, Charsets.UTF_8)
        }
    }

    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
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
        return "tabssh_backup_$timestamp.tabssh"
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