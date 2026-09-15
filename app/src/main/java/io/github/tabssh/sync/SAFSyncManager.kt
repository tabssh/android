package io.github.tabssh.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Storage Access Framework (SAF) based sync manager
 *
 * Allows users to sync to ANY cloud storage provider they have installed:
 * - Google Drive app
 * - Dropbox
 * - OneDrive
 * - Nextcloud
 * - Local storage
 * - Any other DocumentsProvider
 *
 * No OAuth configuration required - uses Android's built-in file picker.
 */
class SAFSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SAFSyncManager"
        private const val PREF_NAME = "saf_sync_prefs"
        private const val KEY_SYNC_URI = "sync_file_uri"
        private const val KEY_SYNC_PASSWORD_SET = "sync_password_set"
        private const val SYNC_FILE_NAME = "tabssh_sync.dat"
        private const val SYNC_FILE_MIME = "application/octet-stream"
        private const val SYNC_PASSWORD_KEY = "sync_encryption_password"
        // Local journal of the encrypted payload currently being written to the
        // SAF document. A single-document SAF grant has no parent access, so a
        // sibling temp-then-rename is impossible; instead the payload is kept
        // here until the remote write completes, and a truncated remote file is
        // rewritten from it on the next download.
        private const val PENDING_UPLOAD_FILE = "sync_upload_pending.dat"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val encryptor = SyncEncryptor()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // Lazy-load password from secure storage
    private val app: io.github.tabssh.TabSSHApplication?
        get() = context.applicationContext as? io.github.tabssh.TabSSHApplication

    // In-memory fallback when secure storage unavailable
    private var inMemoryPassword: String? = null

    // Last error message for UI display
    var lastError: String? = null
        private set

    /**
     * Set the sync encryption password (stored in secure storage)
     */
    suspend fun setSyncPassword(password: String): Boolean {
        Logger.d(TAG, "Attempting to store sync password")

        // Try secure storage first
        try {
            val secureManager = app?.securePasswordManager
            if (secureManager != null) {
                val success = secureManager.storePassword(
                    SYNC_PASSWORD_KEY,
                    password,
                    io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                )
                if (success) {
                    prefs.edit().putBoolean(KEY_SYNC_PASSWORD_SET, true).apply()
                    // Also keep in memory as backup
                    inMemoryPassword = password
                    Logger.i(TAG, "Sync password stored securely")
                    return true
                } else {
                    Logger.w(TAG, "SecurePasswordManager.storePassword returned false")
                }
            } else {
                Logger.w(TAG, "SecurePasswordManager not available")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to store sync password in secure storage", e)
        }

        // Fallback to in-memory only. The persisted "password set" flag stays
        // OFF: setting it made isConfigured() report true after process death
        // while the password itself was gone, so background syncs ran with no
        // key, failed to decrypt, and looked like a corrupt remote file.
        Logger.w(TAG, "Using in-memory password storage (less secure)")
        inMemoryPassword = password
        prefs.edit().putBoolean(KEY_SYNC_PASSWORD_SET, false).apply()
        return true
    }

    /**
     * True when the password only exists in memory for this process. The UI
     * uses it to tell the user their sync password must be re-entered after a
     * restart instead of letting sync silently fail later.
     */
    fun isPasswordMemoryOnly(): Boolean =
        inMemoryPassword != null && !prefs.getBoolean(KEY_SYNC_PASSWORD_SET, false)

    /**
     * Public accessor for the sync password so the three-way merge coordinator
     * can encrypt/decrypt the at-rest base snapshot with the same key as the
     * sync file (§9.6). Returns null when no password is configured.
     */
    suspend fun getEncryptionPassword(): String? = getSyncPassword()

    /**
     * Get the sync password from secure storage
     */
    private suspend fun getSyncPassword(): String? {
        // Try in-memory first (fastest)
        inMemoryPassword?.let {
            Logger.d(TAG, "Retrieved sync password from memory")
            return it
        }

        // Try secure storage
        try {
            val secureManager = app?.securePasswordManager
            if (secureManager != null) {
                val password = secureManager.retrievePassword(SYNC_PASSWORD_KEY)
                if (password != null) {
                    // Cache for next time
                    inMemoryPassword = password
                    Logger.d(TAG, "Retrieved sync password from secure storage")
                    return password
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to retrieve sync password from secure storage", e)
        }

        Logger.w(TAG, "Sync password not found in any storage")
        return null
    }

    /**
     * Check if sync password is set
     */
    fun hasPassword(): Boolean {
        // Check in-memory first
        if (inMemoryPassword != null) return true

        // Check flag (indicates password was previously set)
        return prefs.getBoolean(KEY_SYNC_PASSWORD_SET, false)
    }

    /**
     * Get intent to create a new sync file
     */
    fun getCreateFileIntent(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = SYNC_FILE_MIME
            putExtra(Intent.EXTRA_TITLE, SYNC_FILE_NAME)
        }
    }

    /**
     * Get intent to open an existing sync file
     */
    fun getOpenFileIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Allow any file type since cloud providers may change mime
            type = "*/*"
        }
    }

    /**
     * Save the sync file URI and take persistable permission
     */
    fun saveSyncUri(uri: Uri) {
        // Take persistable permission so we can access the file later
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            Logger.d(TAG, "Took persistable permission for: $uri")
        } catch (e: Exception) {
            Logger.w(TAG, "Could not take persistable permission", e)
        }

        prefs.edit().putString(KEY_SYNC_URI, uri.toString()).apply()
        Logger.i(TAG, "Saved sync URI: $uri")
    }

    /**
     * Get the saved sync file URI
     */
    fun getSyncUri(): Uri? {
        val uriString = prefs.getString(KEY_SYNC_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    /**
     * Check if sync is configured (has URI and password)
     */
    fun isConfigured(): Boolean {
        return getSyncUri() != null && hasPassword()
    }

    /**
     * Get sync location display name
     */
    fun getSyncLocationName(): String? {
        val uri = getSyncUri() ?: return null
        return try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            val name = docFile?.name ?: uri.lastPathSegment
            // Try to get provider name from URI
            val provider = when {
                uri.authority?.contains("google") == true -> "Google Drive"
                uri.authority?.contains("dropbox") == true -> "Dropbox"
                uri.authority?.contains("onedrive") == true -> "OneDrive"
                uri.authority?.contains("nextcloud") == true -> "Nextcloud"
                uri.authority?.contains("downloads") == true -> "Downloads"
                uri.authority?.contains("externalstorage") == true -> "Local Storage"
                else -> uri.authority ?: "Unknown"
            }
            "$name ($provider)"
        } catch (e: Exception) {
            Logger.w(TAG, "Could not get location name", e)
            uri.lastPathSegment
        }
    }

    /**
     * Upload data to sync file
     */
    suspend fun upload(payload: SyncDataPackage): Boolean = withContext(Dispatchers.IO) {
        lastError = null

        val uri = getSyncUri()
        if (uri == null) {
            val msg = "No sync location configured"
            lastError = msg
            Logger.e(TAG, msg)
            return@withContext false
        }

        val password = getSyncPassword()
        if (password == null) {
            val msg = "Sync password not found - please re-enter your password in Settings"
            lastError = msg
            Logger.e(TAG, msg)
            return@withContext false
        }

        try {
            // Serialize payload to JSON
            Logger.d(TAG, "upload: starting JSON serialize")
            val jsonData = json.encodeToString(payload)
            Logger.d(TAG, "upload: serialized JSON, ${jsonData.length} chars")

            // Compress with GZIP
            Logger.d(TAG, "upload: starting GZIP compression")
            val compressed = compress(jsonData.toByteArray(Charsets.UTF_8))
            Logger.d(TAG, "upload: compressed to ${compressed.size} bytes")

            // Encrypt
            Logger.d(TAG, "upload: starting AES-GCM encrypt (Argon2id key derivation)")
            val encrypted = encryptor.encrypt(compressed, password)
            Logger.d(TAG, "upload: encrypted to ${encrypted.size} bytes")

            // Journal the payload locally first: "wt" truncates the remote file
            // before writing, so process death mid-write would otherwise leave
            // truncated ciphertext with no way back (see PENDING_UPLOAD_FILE).
            try {
                writePendingUpload(encrypted)
            } catch (pe: Exception) {
                Logger.w(TAG, "Could not journal pending upload — continuing without safety net", pe)
            }

            // Write to URI
            Logger.d(TAG, "upload: opening output stream for $uri")
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(encrypted)
                output.flush()
            } ?: throw Exception("Could not open output stream")

            // Remote write completed — the journal is no longer needed
            pendingUploadFile().delete()

            Logger.i(TAG, "Successfully uploaded ${encrypted.size} bytes to sync file")

            // Update last sync time
            prefs.edit().putLong("sync_last_time", System.currentTimeMillis()).apply()

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "Upload failed: ${e.message}"
            Logger.e(TAG, "Upload failed", e)
            false
        }
    }

    /**
     * Download data from the sync file.
     *
     * The outcome is deliberately three-valued. A single nullable return used
     * to collapse "no remote data yet" together with "the remote file exists
     * but could not be read" (IO hiccup, wrong password after reinstall,
     * corrupt payload). Callers read null as "nothing to merge", uploaded the
     * local state over the peer's file and reported success, permanently
     * destroying whatever only lived in that file. [SyncDownload.Failed] must
     * never be followed by an upload.
     */
    suspend fun download(): SyncDownload = withContext(Dispatchers.IO) {
        lastError = null

        val uri = getSyncUri()
        if (uri == null) {
            val msg = "No sync location configured"
            lastError = msg
            Logger.e(TAG, msg)
            return@withContext SyncDownload.Failed(msg)
        }

        val password = getSyncPassword()
        if (password == null) {
            val msg = "Sync password not found - please re-enter your password in Settings"
            lastError = msg
            Logger.e(TAG, msg)
            return@withContext SyncDownload.Failed(msg)
        }

        try {
            // Read from URI
            val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: throw Exception("Could not open input stream")

            Logger.d(TAG, "Read ${encrypted.size} bytes from sync file")

            // Decrypt, recovering from the local pending journal when the remote
            // file was left truncated (or zeroed) by an interrupted upload.
            val compressed = if (encrypted.isEmpty()) {
                recoverFromPendingUpload(uri, password) ?: run {
                    // A freshly created sync file really is empty — this is the
                    // one case where seeding it from local state is correct.
                    Logger.d(TAG, "Sync file is empty - treating as first sync")
                    return@withContext SyncDownload.Empty
                }
            } else {
                try {
                    encryptor.decrypt(encrypted, password)
                } catch (e: Exception) {
                    recoverFromPendingUpload(uri, password) ?: run {
                        val msg = "Decryption failed - wrong password or corrupted data"
                        lastError = msg
                        Logger.e(TAG, "Decryption failed", e)
                        return@withContext SyncDownload.Failed(msg)
                    }
                }
            }
            Logger.d(TAG, "Decrypted to ${compressed.size} bytes")

            // Decompress
            val jsonData = decompress(compressed).toString(Charsets.UTF_8)
            Logger.d(TAG, "Decompressed to ${jsonData.length} chars")

            // Deserialize
            val payload = json.decodeFromString<SyncDataPackage>(jsonData)
            Logger.i(TAG, "Successfully downloaded sync data")

            // Update last sync time
            prefs.edit().putLong("sync_last_time", System.currentTimeMillis()).apply()

            SyncDownload.Data(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Distinguish "the file is not there at all" (nothing can be lost
            // by seeding it) from "it is there and we could not read it".
            val absent = try {
                DocumentFile.fromSingleUri(context, uri)?.exists() != true
            } catch (probe: Exception) {
                Logger.w(TAG, "Could not probe sync file existence", probe)
                false
            }
            if (absent) {
                Logger.d(TAG, "Sync file does not exist yet - treating as first sync")
                return@withContext SyncDownload.Empty
            }
            val msg = "Download failed: ${e.message}"
            lastError = msg
            Logger.e(TAG, "Download failed", e)
            SyncDownload.Failed(msg)
        }
    }

    private fun pendingUploadFile(): java.io.File {
        return java.io.File(context.filesDir, PENDING_UPLOAD_FILE)
    }

    /**
     * Atomically journal the encrypted payload about to be written to SAF.
     * filesDir is same-filesystem, so temp-then-rename is atomic here even
     * though it cannot be done on the SAF document itself.
     */
    private fun writePendingUpload(encrypted: ByteArray) {
        val tmp = java.io.File(context.filesDir, "$PENDING_UPLOAD_FILE.tmp")
        tmp.writeBytes(encrypted)
        val pending = pendingUploadFile()
        if (!tmp.renameTo(pending)) {
            pending.delete()
            if (!tmp.renameTo(pending)) {
                throw java.io.IOException("Could not move pending journal into place")
            }
        }
    }

    /**
     * Recovery for an upload that died mid-write: if the journaled payload
     * decrypts with the current password, rewrite the remote file from it and
     * return the decrypted (still compressed) bytes. Returns null when there
     * is no journal or it cannot be decrypted/rewritten — the journal is only
     * ever our own ciphertext, so a wrong password fails here too and a peer's
     * healthy file is never clobbered (this runs only after its decrypt failed).
     */
    private fun recoverFromPendingUpload(uri: Uri, password: String): ByteArray? {
        val pending = pendingUploadFile()
        if (!pending.exists()) return null
        return try {
            val bytes = pending.readBytes()
            val compressed = encryptor.decrypt(bytes, password)
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return null
            Logger.i(TAG, "Recovered truncated sync file from local pending journal")
            pending.delete()
            compressed
        } catch (e: Exception) {
            Logger.w(TAG, "Pending-journal recovery failed", e)
            null
        }
    }

    /**
     * Verify that a password can decrypt the given URI without storing the password.
     * Returns true if the file is empty (nothing to verify yet) or if decryption succeeds.
     * Returns false if decryption fails (wrong password or corrupted data).
     */
    suspend fun verifyPassword(uri: Uri, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext false
            if (bytes.isEmpty()) return@withContext true
            encryptor.decrypt(bytes, password)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Password verification failed", e)
            false
        }
    }

    /**
     * Check if sync file exists and is readable
     */
    suspend fun checkSyncFile(): SyncFileStatus = withContext(Dispatchers.IO) {
        val uri = getSyncUri() ?: return@withContext SyncFileStatus.NOT_CONFIGURED

        try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            if (docFile == null || !docFile.exists()) {
                return@withContext SyncFileStatus.FILE_NOT_FOUND
            }
            if (!docFile.canRead()) {
                return@withContext SyncFileStatus.NO_PERMISSION
            }
            if (!docFile.canWrite()) {
                return@withContext SyncFileStatus.READ_ONLY
            }
            SyncFileStatus.OK
        } catch (e: SecurityException) {
            Logger.w(TAG, "Permission denied for sync file", e)
            SyncFileStatus.NO_PERMISSION
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking sync file", e)
            SyncFileStatus.ERROR
        }
    }

    /**
     * Clear sync configuration. Suspending because clearing the
     * Keystore-backed password is itself a suspend call — we don't want
     * to wrap that in `runBlocking` from a UI thread (it can stall on
     * hardware-backed Keystore latency and trigger an ANR).
     */
    suspend fun clearConfiguration() {
        // Release persistable permission
        getSyncUri()?.let { uri ->
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                Logger.w(TAG, "Could not release permission", e)
            }
        }

        // Clear password from secure storage
        try {
            app?.securePasswordManager?.clearPassword(SYNC_PASSWORD_KEY)
        } catch (e: Exception) {
            Logger.w(TAG, "Could not clear sync password from secure storage", e)
        }

        // Clear in-memory password
        inMemoryPassword = null

        prefs.edit()
            .remove(KEY_SYNC_URI)
            .remove(KEY_SYNC_PASSWORD_SET)
            .remove("sync_last_time")
            .apply()

        lastError = null
        Logger.i(TAG, "Sync configuration cleared")
    }

    /**
     * Get last sync time
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong("sync_last_time", 0L)
    }

    private fun compress(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }
}

/**
 * Outcome of reading the remote sync file.
 *
 * [Empty] means the remote genuinely holds nothing yet, so seeding it from
 * local state is safe. [Failed] means the remote may hold a peer's data that
 * simply could not be read — the sync must abort without uploading.
 */
sealed class SyncDownload {
    data class Data(val payload: SyncDataPackage) : SyncDownload()
    object Empty : SyncDownload()
    data class Failed(val message: String) : SyncDownload()
}

/**
 * Sync file status
 */
enum class SyncFileStatus {
    OK,
    NOT_CONFIGURED,
    FILE_NOT_FOUND,
    NO_PERMISSION,
    READ_ONLY,
    ERROR
}
