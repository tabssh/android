package io.github.tabssh.sync.executor

import android.content.Context
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import io.github.tabssh.sync.models.RemoteSyncFile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV synchronization executor for degoogled devices
 * Works with any WebDAV-compatible server (Nextcloud, ownCloud, generic WebDAV)
 */
class WebDAVSyncExecutor(
    private val context: Context,
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val syncFolder: String = "/TabSSH"
) {
    companion object {
        private const val TAG = "WebDAVSyncExecutor"
        private const val SYNC_FILE_PREFIX = "tabssh-sync-"
        private const val SYNC_FILE_EXTENSION = ".enc"
    }

    private val sardine: Sardine by lazy {
        OkHttpSardine().apply {
            setCredentials(username, password)
        }
    }

    /**
     * Initialize WebDAV connection and create sync folder if needed
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Initializing WebDAV connection to: $serverUrl")

            // Ensure sync folder exists
            val folderUrl = normalizeUrl("$serverUrl$syncFolder")
            if (!sardine.exists(folderUrl)) {
                Logger.d(TAG, "Creating sync folder: $folderUrl")
                sardine.createDirectory(folderUrl)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize WebDAV", e)
            Result.failure(e)
        }
    }

    /**
     * Upload sync file to WebDAV server
     */
    suspend fun uploadSyncFile(
        deviceId: String,
        encryptedData: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "$SYNC_FILE_PREFIX$deviceId$SYNC_FILE_EXTENSION"
            val fileUrl = normalizeUrl("$serverUrl$syncFolder/$fileName")

            Logger.d(TAG, "Uploading sync file: $fileName (${encryptedData.size} bytes)")

            sardine.put(fileUrl, encryptedData, "application/octet-stream")

            Logger.i(TAG, "Successfully uploaded sync file: $fileName")
            Result.success(fileUrl)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to upload sync file", e)
            Result.failure(e)
        }
    }

    /**
     * Download sync file from WebDAV server
     */
    suspend fun downloadSyncFile(deviceId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val fileName = "$SYNC_FILE_PREFIX$deviceId$SYNC_FILE_EXTENSION"
            val fileUrl = normalizeUrl("$serverUrl$syncFolder/$fileName")

            Logger.d(TAG, "Downloading sync file: $fileName")

            if (!sardine.exists(fileUrl)) {
                return@withContext Result.failure(Exception("Sync file not found: $fileName"))
            }

            val inputStream = sardine.get(fileUrl)
            val data = inputStream.readBytes()
            inputStream.close()

            Logger.i(TAG, "Successfully downloaded sync file: $fileName (${data.size} bytes)")
            Result.success(data)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download sync file", e)
            Result.failure(e)
        }
    }

    /**
     * List all sync files from all devices
     */
    suspend fun listSyncFiles(): Result<List<RemoteSyncFile>> = withContext(Dispatchers.IO) {
        try {
            val folderUrl = normalizeUrl("$serverUrl$syncFolder")
            Logger.d(TAG, "Listing sync files from: $folderUrl")

            if (!sardine.exists(folderUrl)) {
                Logger.w(TAG, "Sync folder does not exist yet")
                return@withContext Result.success(emptyList())
            }

            val resources = sardine.list(folderUrl)
            val syncFiles = resources
                .filter { !it.isDirectory && it.name.startsWith(SYNC_FILE_PREFIX) && it.name.endsWith(SYNC_FILE_EXTENSION) }
                .map { resource ->
                    val deviceId = resource.name
                        .removePrefix(SYNC_FILE_PREFIX)
                        .removeSuffix(SYNC_FILE_EXTENSION)

                    RemoteSyncFile(
                        fileId = resource.path ?: resource.name,
                        fileName = resource.name,
                        deviceId = deviceId,
                        modifiedTime = resource.modified?.time ?: 0L,
                        size = resource.contentLength ?: 0L
                    )
                }
                .sortedByDescending { it.modifiedTime }

            Logger.i(TAG, "Found ${syncFiles.size} sync files")
            Result.success(syncFiles)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to list sync files", e)
            Result.failure(e)
        }
    }

    /**
     * Delete sync file for specific device
     */
    suspend fun deleteSyncFile(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileName = "$SYNC_FILE_PREFIX$deviceId$SYNC_FILE_EXTENSION"
            val fileUrl = normalizeUrl("$serverUrl$syncFolder/$fileName")

            Logger.d(TAG, "Deleting sync file: $fileName")

            if (sardine.exists(fileUrl)) {
                sardine.delete(fileUrl)
                Logger.i(TAG, "Successfully deleted sync file: $fileName")
            } else {
                Logger.w(TAG, "Sync file not found for deletion: $fileName")
            }

            Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete sync file", e)
            Result.failure(e)
        }
    }

    /**
     * Test WebDAV connection
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "Testing WebDAV connection...")

            // Try to list root directory
            val exists = sardine.exists(normalizeUrl(serverUrl))

            if (exists) {
                Logger.i(TAG, "WebDAV connection successful")
                Result.success(true)
            } else {
                Logger.w(TAG, "WebDAV server exists but may not be accessible")
                Result.success(false)
            }

        } catch (e: Exception) {
            Logger.e(TAG, "WebDAV connection test failed", e)
            Result.failure(e)
        }
    }

    /**
     * Get sync file metadata without downloading
     */
    suspend fun getSyncFileMetadata(deviceId: String): Result<RemoteSyncFile?> = withContext(Dispatchers.IO) {
        try {
            val fileName = "$SYNC_FILE_PREFIX$deviceId$SYNC_FILE_EXTENSION"
            val fileUrl = normalizeUrl("$serverUrl$syncFolder/$fileName")

            Logger.d(TAG, "Getting metadata for: $fileName")

            if (!sardine.exists(fileUrl)) {
                return@withContext Result.success(null)
            }

            val resources = sardine.list(fileUrl, 0)
            val resource = resources.firstOrNull()

            if (resource != null) {
                val syncFile = RemoteSyncFile(
                    fileId = resource.path ?: resource.name,
                    fileName = resource.name,
                    deviceId = deviceId,
                    modifiedTime = resource.modified?.time ?: 0L,
                    size = resource.contentLength ?: 0L
                )
                Result.success(syncFile)
            } else {
                Result.success(null)
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get sync file metadata", e)
            Result.failure(e)
        }
    }

    /**
     * Download sync file from another device
     */
    suspend fun downloadDeviceSyncFile(deviceId: String): Result<ByteArray> {
        return downloadSyncFile(deviceId)
    }

    /**
     * Check if sync is available (server reachable)
     */
    suspend fun isSyncAvailable(): Boolean {
        return testConnection().getOrDefault(false)
    }

    private fun normalizeUrl(url: String): String {
        // Ensure URL doesn't have duplicate slashes
        return url.replace(Regex("(?<!:)//+"), "/")
    }
}

/**
 * WebDAV configuration data class
 */
data class WebDAVConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val syncFolder: String = "/TabSSH",
    val enabled: Boolean = false
)
