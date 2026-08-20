package io.github.tabssh.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.backup.export.BackupExporter
import io.github.tabssh.backup.import.BackupImporter
import io.github.tabssh.backup.validation.BackupValidator
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
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

/**
 * Main backup and restore manager.
 *
 * Handles a full application backup — every entity, every preference and every
 * Keystore-backed credential. There is exactly one wire format
 * ([BACKUP_VERSION]); an archive that does not match it is rejected with an
 * "unsupported backup format" error rather than parsed best-effort.
 *
 * Two output modes, both containing the same data:
 *  - **Encrypted** (default): AES-256-GCM keyed from a user password.
 *  - **Plaintext**: readable by anyone holding the file. Requires
 *    `plaintextSecretsConfirmed` from a type-to-confirm dialog.
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
    // Building either manager opens the AndroidKeyStore provider, which throws
    // outright where that provider does not exist. Catching it here is what
    // makes the "skip the secrets section" promise above true — otherwise the
    // whole export fails instead of the secrets section being left empty.
    private val securePasswordManager: SecurePasswordManager?
        get() = try {
            app?.securePasswordManager
        } catch (e: Exception) {
            Logger.w(TAG, "Keystore unavailable, backup will omit stored secrets: ${e.message}")
            null
        }
    private val keyStorage: KeyStorage?
        get() = try {
            app?.keyStorage
        } catch (e: Exception) {
            Logger.w(TAG, "Keystore unavailable, backup will omit key material: ${e.message}")
            null
        }
    private val exporter by lazy {
        BackupExporter(context, database, preferenceManager,
            securePasswordManager, keyStorage)
    }
    private val importer by lazy {
        BackupImporter(context, database, preferenceManager,
            securePasswordManager, keyStorage)
    }
    private val validator = BackupValidator()
    // P0 fix: real password-based encryption for backups. Reuses the
    // sync subsystem's SyncEncryptor (AES-256-GCM + Argon2id key derivation,
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
        /**
         * The one and only backup wire version. There is exactly one current
         * format: a single-JSON `.tabssh` file whose entity files each carry
         * `{"v":BACKUP_VERSION,"items":[...]}`. Archives written in any other
         * version are rejected outright — no legacy read path exists.
         */
        const val BACKUP_VERSION = 3

        /** Magic prefix written by [SyncEncryptor] on an encrypted archive. */
        private const val ENCRYPTED_MAGIC = "TABSSH_SYNC_V3"

        private const val TAG = "BackupManager"
    }

    /**
     * Create a full backup.
     *
     * A backup always contains absolutely everything, credentials included —
     * encryption is a file-level option that never changes the contents.
     *
     * @param encryptBackup true writes an AES-256-GCM archive keyed from
     *   [password]; [password] is then mandatory.
     * @param plaintextSecretsConfirmed required when [encryptBackup] is false.
     *   An unencrypted archive contains every SSH key passphrase and every
     *   connection/container-host/registry/VNC password in readable form, so the caller
     *   must have obtained an explicit type-to-confirm acknowledgement from the
     *   user first. Without it the backup is refused before any bytes are
     *   written to [outputUri].
     */
    suspend fun createBackup(
        outputUri: Uri,
        encryptBackup: Boolean = true,
        password: String? = null,
        plaintextSecretsConfirmed: Boolean = false
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            Logger.i("BackupManager", "Creating backup...")

            // Encryption requested but no usable password: fail loudly. The old
            // code fell through to the plaintext branch, writing an unencrypted
            // backup of every credential while the encryptBackup=true UI promised
            // otherwise — a silent downgrade that must never happen.
            if (encryptBackup && password.isNullOrBlank()) {
                Logger.e("BackupManager", "Encrypted backup requested without a password")
                return@withContext BackupResult(
                    success = false,
                    message = "Encrypted backup requires a password"
                )
            }

            // Refuse before opening the output stream so an unconfirmed
            // plaintext export cannot even create a partial file on disk.
            if (!encryptBackup && !plaintextSecretsConfirmed) {
                Logger.e("BackupManager", "Unencrypted backup requested without explicit confirmation")
                return@withContext BackupResult(
                    success = false,
                    message = "Unencrypted backup requires explicit confirmation " +
                        "because it exposes every stored credential in readable form"
                )
            }

            // A backup always contains absolutely everything, including all
            // credentials. Encryption is a file-level option the user controls;
            // it never changes what the backup contains. This guarantees a
            // restore reproduces the exact app state at capture time.
            val backupData = exporter.collectBackupData()

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
     * Restore from backup.
     *
     * @param overwriteExisting merge-mode granularity: when true, a row already
     *   present locally is overwritten by the backup's copy; when false it is
     *   left untouched. Ignored when [replaceMode] is true.
     * @param replaceMode true snapshot restore: every entity table present in
     *   the backup is cleared before the backup's rows are inserted, and
     *   preferences are restored in full — the device ends up an exact copy
     *   of the backup for everything the backup contains. Defaults to false
     *   (merge) for safety.
     */
    suspend fun restoreBackup(
        inputUri: Uri,
        password: String? = null,
        overwriteExisting: Boolean = false,
        replaceMode: Boolean = false
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            Logger.i("BackupManager", "Starting restore...")

            val backupData = mutableMapOf<String, String>()
            var metadata: BackupMetadata? = null

            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                val allBytes = inputStream.readBytes()
                // Single-JSON format, optionally AES-GCM encrypted. Detect the
                // SyncEncryptor magic header so we can surface a clear
                // "need password" error instead of a raw JSONException.
                val isEncrypted = allBytes.size >= ENCRYPTED_MAGIC.length &&
                    String(allBytes, 0, ENCRYPTED_MAGIC.length, Charsets.ISO_8859_1) == ENCRYPTED_MAGIC
                if (isEncrypted && password == null) {
                    return@withContext RestoreResult(
                        success = false,
                        message = "This backup is encrypted — enter your backup password to restore"
                    )
                }
                val plainBytes: ByteArray = if (isEncrypted && password != null) {
                    try {
                        encryptor.decrypt(allBytes, password)
                    } catch (e: Exception) {
                        return@withContext RestoreResult(
                            success = false,
                            message = "Incorrect backup password",
                            errors = listOf(e.message ?: "Decryption failed")
                        )
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
            val restoredItems = importer.restoreBackupData(backupData, overwriteExisting, replaceMode)

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

        // Generic counter: every entity file the exporter writes uses the
        // single current wrapper (`{"v":BACKUP_VERSION,"items":[...]}`).
        // Preferences are an object, not a list — skip those.
        backupData.forEach { (filename, data) ->
            if (filename == "preferences.json") return@forEach
            val key = filename.removeSuffix(".json")
            val count = try {
                JSONObject(data).optJSONArray("items")?.length() ?: 0
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
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})"
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
