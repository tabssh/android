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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Main backup and restore manager.
 *
 * Current wire format ([BACKUP_VERSION]): a ZIP archive whose first entry is
 * `manifest.json` (format version, app version, timestamp, content list),
 * followed by one JSON entry per data domain. Credentials live in a separate
 * `secrets.json` entry that only exists when the user supplies a backup
 * password — its bytes are then encrypted with [SyncEncryptor] (Argon2id +
 * AES-256-GCM). A backup created without a password contains no credentials
 * at all.
 *
 * The previous single-JSON format ([LEGACY_BACKUP_VERSION]), plain or
 * whole-file encrypted, remains fully readable on restore.
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
    // Credential encryption reuses the sync subsystem's SyncEncryptor
    // (AES-256-GCM + Argon2id key derivation, see SyncEncryptor.kt) so the
    // backup format never grows its own crypto.
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

    /** File-level container a backup archive was written in, detected by magic bytes. */
    enum class BackupFileFormat { ZIP, LEGACY_ENCRYPTED, LEGACY_JSON, UNKNOWN }

    companion object {
        /**
         * Current backup wire version: a ZIP archive whose first entry is
         * [MANIFEST_ENTRY], followed by one JSON entry per data domain and an
         * optional SyncEncryptor-encrypted secrets entry.
         */
        const val BACKUP_VERSION = 4

        /**
         * The previous single-JSON `.tabssh` format. Still fully readable on
         * restore; never written anymore.
         */
        const val LEGACY_BACKUP_VERSION = 3

        /** Manifest entry name inside the ZIP archive — always written first. */
        const val MANIFEST_ENTRY = "manifest.json"

        /** Magic prefix written by [SyncEncryptor] on encrypted bytes. */
        private const val ENCRYPTED_MAGIC = "TABSSH_SYNC_V3"

        private const val TAG = "BackupManager"

        /** True when [data] starts with the [SyncEncryptor] magic header. */
        internal fun isSyncEncrypted(data: ByteArray): Boolean =
            data.size >= ENCRYPTED_MAGIC.length &&
                String(data, 0, ENCRYPTED_MAGIC.length, Charsets.ISO_8859_1) == ENCRYPTED_MAGIC

        /** Detect the container format of a backup file from its leading bytes. */
        internal fun sniffFormat(head: ByteArray): BackupFileFormat = when {
            // ZIP local-file-header magic "PK\x03\x04" — the current archive format.
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2].toInt() == 3 && head[3].toInt() == 4 -> BackupFileFormat.ZIP
            isSyncEncrypted(head) -> BackupFileFormat.LEGACY_ENCRYPTED
            // Legacy plaintext archives are a single JSON object.
            head.isNotEmpty() && head[0] == '{'.code.toByte() -> BackupFileFormat.LEGACY_JSON
            else -> BackupFileFormat.UNKNOWN
        }

        /** Serialise [metadata] plus the archive's entry list into the manifest JSON. */
        internal fun buildManifest(
            metadata: BackupMetadata,
            contents: List<String>,
            secretsIncluded: Boolean
        ): String = JSONObject().apply {
            put("version", metadata.version)
            put("createdAt", metadata.createdAt)
            put("appVersion", metadata.appVersion)
            put("deviceModel", metadata.deviceModel)
            put("androidVersion", metadata.androidVersion)
            put("itemCounts", JSONObject(metadata.itemCounts))
            put("contents", JSONArray(contents))
            put("secretsIncluded", secretsIncluded)
        }.toString()

        /** Parse a manifest written by [buildManifest]. Throws on a malformed manifest. */
        internal fun parseManifest(manifestJson: String): BackupMetadata {
            val obj = JSONObject(manifestJson)
            val itemCounts = mutableMapOf<String, Int>()
            obj.optJSONObject("itemCounts")?.let { counts ->
                counts.keys().forEach { key -> itemCounts[key] = counts.getInt(key) }
            }
            return BackupMetadata(
                version = obj.getInt("version"),
                createdAt = obj.getLong("createdAt"),
                appVersion = obj.getString("appVersion"),
                deviceModel = obj.getString("deviceModel"),
                androidVersion = obj.getInt("androidVersion"),
                itemCounts = itemCounts
            )
        }

        /** Write the archive: manifest first, then every entry in [entries] order. */
        internal fun writeBackupZip(
            out: OutputStream,
            manifestJson: String,
            entries: Map<String, ByteArray>
        ) {
            val zip = ZipOutputStream(out)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // finish() writes the central directory; flush() pushes it to the
            // underlying stream so the caller's close() has nothing left to lose.
            zip.finish()
            zip.flush()
        }

        /** Read every entry of the archive. Returns manifest JSON (or null) and the rest. */
        internal fun readBackupZip(input: InputStream): Pair<String?, Map<String, ByteArray>> {
            var manifest: String? = null
            val entries = linkedMapOf<String, ByteArray>()
            val zip = ZipInputStream(input)
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    if (entry.name == MANIFEST_ENTRY) manifest = String(bytes, Charsets.UTF_8)
                    else entries[entry.name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            return manifest to entries
        }

        /** Read only the manifest entry, stopping as soon as it has been seen. */
        internal fun readManifestOnly(input: InputStream): String? {
            val zip = ZipInputStream(input)
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) return String(zip.readBytes(), Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
            return null
        }
    }

    /**
     * Create a full backup as a [BACKUP_VERSION] ZIP archive.
     *
     * @param password when non-null, credentials are included as a separate
     *   secrets entry encrypted with this password ([SyncEncryptor], Argon2id +
     *   AES-256-GCM). When null, the archive contains **no credentials at
     *   all** — an unencrypted backup never carries a secret in any form.
     */
    suspend fun createBackup(
        outputUri: Uri,
        password: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            Logger.i(TAG, "Creating backup...")

            // A blank password would silently derive a trivially guessable key,
            // so refuse it outright before any bytes are collected or written.
            if (password != null && password.isBlank()) {
                return@withContext BackupResult(
                    success = false,
                    message = "Backup password must not be blank"
                )
            }

            // Secrets are gathered only when they can be encrypted (AI.md PART 6:
            // exported secrets are always password-protected, never plaintext).
            val includeSecrets = password != null
            val backupData = exporter.collectBackupData(includeSecrets)
            val secretsJson = backupData[BackupExporter.FILE_SECRETS]
            val domainData = backupData - BackupExporter.FILE_SECRETS

            val metadata = createBackupMetadata(domainData)

            val entries = linkedMapOf<String, ByteArray>()
            domainData.forEach { (name, json) -> entries[name] = json.toByteArray(Charsets.UTF_8) }
            // Binding the password here rather than asserting it non-null keeps
            // the "secrets are only ever written encrypted" invariant visible in
            // the code instead of resting on includeSecrets' data flow.
            if (password != null && secretsJson != null) {
                entries[BackupExporter.FILE_SECRETS] =
                    encryptor.encrypt(secretsJson.toByteArray(Charsets.UTF_8), password)
            }
            val manifestJson = buildManifest(metadata, entries.keys.toList(), includeSecrets)

            // "wt" guarantees truncation of an existing document — plain "w" does
            // not on all providers — and a null stream is a hard failure, not a
            // silent zero-byte "success".
            val output = context.contentResolver.openOutputStream(outputUri, "wt")
                ?: return@withContext BackupResult(
                    success = false,
                    message = "Unable to open the backup destination for writing"
                )
            output.use { writeBackupZip(it, manifestJson, entries) }

            // Read the head back so a provider that dropped the write cannot be
            // reported as a successful backup.
            val verified = context.contentResolver.openInputStream(outputUri)?.use { input ->
                val head = ByteArray(4)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                read == head.size && sniffFormat(head) == BackupFileFormat.ZIP
            } ?: false
            if (!verified) {
                return@withContext BackupResult(
                    success = false,
                    message = "Backup write could not be verified — the file may be incomplete"
                )
            }

            Logger.i(TAG, "Backup created successfully")
            return@withContext BackupResult(
                success = true,
                message = "Backup created successfully",
                metadata = metadata,
                filePath = outputUri.path
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create backup", e)
            return@withContext BackupResult(
                success = false,
                message = "Failed to create backup: ${e.message}"
            )
        }
    }

    /**
     * Validate a backup file by reading only its manifest — no data entry is
     * parsed and no password is needed. Legacy single-JSON archives have no
     * separate manifest, so their embedded metadata is read instead.
     */
    suspend fun validateBackup(inputUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val allBytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                ?: return@withContext BackupResult(false, "Unable to open the backup file")
            when (sniffFormat(allBytes)) {
                BackupFileFormat.ZIP -> {
                    val manifestJson = readManifestOnly(ByteArrayInputStream(allBytes))
                        ?: return@withContext BackupResult(false, "Invalid backup: missing $MANIFEST_ENTRY")
                    val metadata = parseManifest(manifestJson)
                    unsupportedVersionMessage(metadata.version)?.let {
                        return@withContext BackupResult(false, it)
                    }
                    BackupResult(true, "Backup is valid", metadata)
                }
                BackupFileFormat.LEGACY_ENCRYPTED ->
                    // Whole-file encryption hides even the metadata; the archive is
                    // plausible but cannot be inspected without the password.
                    BackupResult(true, "Encrypted legacy backup — password required to inspect")
                BackupFileFormat.LEGACY_JSON -> {
                    val metadata = parseLegacyRoot(allBytes).first
                    unsupportedVersionMessage(metadata.version)?.let {
                        return@withContext BackupResult(false, it)
                    }
                    BackupResult(true, "Backup is valid", metadata)
                }
                BackupFileFormat.UNKNOWN ->
                    BackupResult(false, "Not a TabSSH backup file")
            }
        } catch (e: Exception) {
            BackupResult(false, "Invalid backup: ${e.message}")
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
            Logger.i(TAG, "Starting restore...")

            val allBytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                ?: return@withContext RestoreResult(
                    success = false,
                    message = "Unable to open the backup file"
                )

            val backupData = mutableMapOf<String, String>()
            val metadata: BackupMetadata

            when (sniffFormat(allBytes)) {
                BackupFileFormat.ZIP -> {
                    val (manifestJson, entries) = readBackupZip(ByteArrayInputStream(allBytes))
                    if (manifestJson == null) {
                        return@withContext RestoreResult(
                            success = false,
                            message = "Invalid backup: missing $MANIFEST_ENTRY"
                        )
                    }
                    // Manifest-first: the format version is checked before any
                    // data entry is even looked at.
                    metadata = parseManifest(manifestJson)
                    unsupportedVersionMessage(metadata.version)?.let {
                        return@withContext RestoreResult(success = false, message = it)
                    }
                    for ((name, bytes) in entries) {
                        if (name == BackupExporter.FILE_SECRETS && isSyncEncrypted(bytes)) {
                            if (password == null) {
                                return@withContext RestoreResult(
                                    success = false,
                                    message = "This backup is encrypted — enter your backup password to restore"
                                )
                            }
                            val plain = try {
                                encryptor.decrypt(bytes, password)
                            } catch (e: Exception) {
                                return@withContext RestoreResult(
                                    success = false,
                                    message = "Incorrect backup password",
                                    errors = listOf(e.message ?: "Decryption failed")
                                )
                            }
                            backupData[name] = String(plain, Charsets.UTF_8)
                        } else {
                            backupData[name] = String(bytes, Charsets.UTF_8)
                        }
                    }
                }
                BackupFileFormat.LEGACY_ENCRYPTED -> {
                    if (password == null) {
                        return@withContext RestoreResult(
                            success = false,
                            message = "This backup is encrypted — enter your backup password to restore"
                        )
                    }
                    val plainBytes = try {
                        encryptor.decrypt(allBytes, password)
                    } catch (e: Exception) {
                        return@withContext RestoreResult(
                            success = false,
                            message = "Incorrect backup password",
                            errors = listOf(e.message ?: "Decryption failed")
                        )
                    }
                    val (meta, data) = parseLegacyRoot(plainBytes)
                    metadata = meta
                    backupData.putAll(data)
                }
                BackupFileFormat.LEGACY_JSON -> {
                    val (meta, data) = parseLegacyRoot(allBytes)
                    metadata = meta
                    backupData.putAll(data)
                }
                BackupFileFormat.UNKNOWN -> {
                    return@withContext RestoreResult(
                        success = false,
                        message = "Not a TabSSH backup file"
                    )
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
            val outcome = importer.restoreBackupData(backupData, overwriteExisting, replaceMode)

            // Per-item failures must never be reported as an unqualified success.
            val message = if (outcome.errors.isEmpty()) {
                "Restore completed successfully"
            } else {
                "Restore completed with ${outcome.errors.size} failed item(s)"
            }
            Logger.i(TAG, message)
            return@withContext RestoreResult(
                success = true,
                message = message,
                restoredItems = outcome.restoredItems,
                errors = outcome.errors
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to restore backup", e)
            return@withContext RestoreResult(
                success = false,
                message = "Failed to restore backup: ${e.message}",
                errors = listOf(e.message ?: "Unknown error")
            )
        }
    }

    /** Error message for a version this build cannot read, or null when supported. */
    private fun unsupportedVersionMessage(version: Int): String? =
        if (version != BACKUP_VERSION && version != LEGACY_BACKUP_VERSION) {
            "Unsupported backup format: version $version " +
                "(this build reads versions $LEGACY_BACKUP_VERSION and $BACKUP_VERSION)"
        } else {
            null
        }

    /** Parse the legacy single-JSON root into its metadata and name→JSON data map. */
    private fun parseLegacyRoot(plainBytes: ByteArray): Pair<BackupMetadata, Map<String, String>> {
        val root = JSONObject(String(plainBytes, Charsets.UTF_8))
        val metaObj = root.getJSONObject("metadata")
        val itemCountsObj = metaObj.getJSONObject("itemCounts")
        val itemCounts = mutableMapOf<String, Int>()
        itemCountsObj.keys().forEach { key -> itemCounts[key] = itemCountsObj.getInt(key) }
        val metadata = BackupMetadata(
            version = metaObj.getInt("version"),
            createdAt = metaObj.getLong("createdAt"),
            appVersion = metaObj.getString("appVersion"),
            deviceModel = metaObj.getString("deviceModel"),
            androidVersion = metaObj.getInt("androidVersion"),
            itemCounts = itemCounts
        )
        val data = mutableMapOf<String, String>()
        val dataObj = root.getJSONObject("data")
        dataObj.keys().forEach { key -> data[key] = dataObj.getString(key) }
        return metadata to data
    }

    /**
     * Create backup metadata
     */
    private fun createBackupMetadata(backupData: Map<String, String>): BackupMetadata {
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
}
