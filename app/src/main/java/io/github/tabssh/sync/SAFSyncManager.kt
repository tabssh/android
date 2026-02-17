package io.github.tabssh.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.utils.logging.Logger
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
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val encryptor = SyncEncryptor()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private var syncPassword: String? = null

    /**
     * Set the sync encryption password
     */
    fun setSyncPassword(password: String) {
        syncPassword = password
        prefs.edit().putBoolean(KEY_SYNC_PASSWORD_SET, true).apply()
        Logger.d(TAG, "Sync password set")
    }

    /**
     * Check if sync password is set
     */
    fun hasPassword(): Boolean {
        return prefs.getBoolean(KEY_SYNC_PASSWORD_SET, false) && syncPassword != null
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
            type = "*/*"  // Allow any file type since cloud providers may change mime
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
        val uri = getSyncUri()
        if (uri == null) {
            Logger.e(TAG, "No sync URI configured")
            return@withContext false
        }

        val password = syncPassword
        if (password == null) {
            Logger.e(TAG, "No sync password set")
            return@withContext false
        }

        try {
            // Serialize payload to JSON
            val jsonData = json.encodeToString(payload)
            Logger.d(TAG, "Serialized payload: ${jsonData.length} chars")

            // Compress with GZIP
            val compressed = compress(jsonData.toByteArray(Charsets.UTF_8))
            Logger.d(TAG, "Compressed to ${compressed.size} bytes")

            // Encrypt
            val encrypted = encryptor.encrypt(compressed, password)
            Logger.d(TAG, "Encrypted to ${encrypted.size} bytes")

            // Write to URI
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(encrypted)
                output.flush()
            } ?: throw Exception("Could not open output stream")

            Logger.i(TAG, "Successfully uploaded ${encrypted.size} bytes to sync file")

            // Update last sync time
            prefs.edit().putLong("sync_last_time", System.currentTimeMillis()).apply()

            true
        } catch (e: Exception) {
            Logger.e(TAG, "Upload failed", e)
            false
        }
    }

    /**
     * Download data from sync file
     */
    suspend fun download(): SyncDataPackage? = withContext(Dispatchers.IO) {
        val uri = getSyncUri()
        if (uri == null) {
            Logger.e(TAG, "No sync URI configured")
            return@withContext null
        }

        val password = syncPassword
        if (password == null) {
            Logger.e(TAG, "No sync password set")
            return@withContext null
        }

        try {
            // Read from URI
            val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: throw Exception("Could not open input stream")

            Logger.d(TAG, "Read ${encrypted.size} bytes from sync file")

            if (encrypted.isEmpty()) {
                Logger.w(TAG, "Sync file is empty")
                return@withContext null
            }

            // Decrypt
            val compressed = encryptor.decrypt(encrypted, password)
            Logger.d(TAG, "Decrypted to ${compressed.size} bytes")

            // Decompress
            val jsonData = decompress(compressed).toString(Charsets.UTF_8)
            Logger.d(TAG, "Decompressed to ${jsonData.length} chars")

            // Deserialize
            val payload = json.decodeFromString<SyncDataPackage>(jsonData)
            Logger.i(TAG, "Successfully downloaded sync data")

            // Update last sync time
            prefs.edit().putLong("sync_last_time", System.currentTimeMillis()).apply()

            payload
        } catch (e: Exception) {
            Logger.e(TAG, "Download failed", e)
            null
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
        } catch (e: Exception) {
            Logger.e(TAG, "Error checking sync file", e)
            SyncFileStatus.ERROR
        }
    }

    /**
     * Clear sync configuration
     */
    fun clearConfiguration() {
        // Release persistable permission
        getSyncUri()?.let { uri ->
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                Logger.w(TAG, "Could not release permission", e)
            }
        }

        prefs.edit()
            .remove(KEY_SYNC_URI)
            .remove(KEY_SYNC_PASSWORD_SET)
            .remove("sync_last_time")
            .apply()

        syncPassword = null
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
