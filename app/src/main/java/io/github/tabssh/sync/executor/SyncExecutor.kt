package io.github.tabssh.sync.executor

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import io.github.tabssh.sync.auth.DriveAuthenticationManager
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.RemoteSyncFile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Executes sync file operations with Google Drive
 */
class SyncExecutor(
    private val authManager: DriveAuthenticationManager,
    private val metadataManager: SyncMetadataManager
) {

    companion object {
        private const val TAG = "SyncExecutor"
        private const val SYNC_FOLDER_SPACE = "appDataFolder"
        private const val SYNC_FILE_MIME_TYPE = "application/octet-stream"
        private const val FILE_NAME_PREFIX = "tabssh-sync-"
        private const val FILE_NAME_SUFFIX = ".enc"
    }

    /**
     * Upload sync file to Google Drive
     */
    suspend fun uploadSyncFile(data: ByteArray, deviceId: String): String = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()
            val fileName = generateFileName(deviceId)

            val existingFileId = findSyncFile(deviceId)

            val fileMetadata = File().apply {
                name = fileName
                parents = listOf(SYNC_FOLDER_SPACE)
                mimeType = SYNC_FILE_MIME_TYPE
            }

            val mediaContent = ByteArrayContent(SYNC_FILE_MIME_TYPE, data)

            val file = if (existingFileId != null) {
                Logger.d(TAG, "Updating existing sync file: $fileName (ID: $existingFileId)")
                driveService.files()
                    .update(existingFileId, fileMetadata, mediaContent)
                    .execute()
            } else {
                Logger.d(TAG, "Creating new sync file: $fileName")
                driveService.files()
                    .create(fileMetadata, mediaContent)
                    .setFields("id, name, modifiedTime, size")
                    .execute()
            }

            Logger.d(TAG, "Sync file uploaded successfully: ${file.id} (${data.size} bytes)")
            file.id
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to upload sync file", e)
            throw SyncExecutorException("Upload failed: ${e.message}", e)
        }
    }

    /**
     * Download sync file from Google Drive
     */
    suspend fun downloadSyncFile(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()

            Logger.d(TAG, "Downloading sync file: $fileId")

            val outputStream = ByteArrayOutputStream()
            driveService.files()
                .get(fileId)
                .executeMediaAndDownloadTo(outputStream)

            val data = outputStream.toByteArray()
            Logger.d(TAG, "Sync file downloaded successfully: ${data.size} bytes")
            data
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download sync file", e)
            throw SyncExecutorException("Download failed: ${e.message}", e)
        }
    }

    /**
     * Download sync file by device ID
     */
    suspend fun downloadSyncFileByDevice(deviceId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val fileId = findSyncFile(deviceId)
            if (fileId != null) {
                downloadSyncFile(fileId)
            } else {
                Logger.d(TAG, "No sync file found for device: $deviceId")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download sync file for device: $deviceId", e)
            null
        }
    }

    /**
     * List all sync files
     */
    suspend fun listDeviceSyncFiles(): List<RemoteSyncFile> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()

            val query = "name contains '$FILE_NAME_PREFIX' and trashed = false"

            Logger.d(TAG, "Listing sync files with query: $query")

            val result: FileList = driveService.files()
                .list()
                .setSpaces(SYNC_FOLDER_SPACE)
                .setQ(query)
                .setFields("files(id, name, modifiedTime, size)")
                .execute()

            val syncFiles = result.files?.mapNotNull { file ->
                try {
                    val deviceId = extractDeviceIdFromFileName(file.name)
                    RemoteSyncFile(
                        fileId = file.id,
                        fileName = file.name,
                        deviceId = deviceId,
                        modifiedTime = (file.modifiedTime?.value ?: 0L) as Long,
                        size = (file.size ?: 0L).toLong()
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to parse sync file: ${file.name}", e)
                    null
                }
            } ?: emptyList()

            Logger.d(TAG, "Found ${syncFiles.size} sync files")
            syncFiles
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to list sync files", e)
            throw SyncExecutorException("List failed: ${e.message}", e)
        }
    }

    /**
     * Download all sync files from other devices
     */
    suspend fun downloadAllDeviceFiles(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        try {
            val currentDeviceId = metadataManager.getDeviceId()
            val allFiles = listDeviceSyncFiles()

            val otherDeviceFiles = allFiles.filter { it.deviceId != currentDeviceId }

            Logger.d(TAG, "Downloading ${otherDeviceFiles.size} files from other devices")

            val downloadedFiles = mutableMapOf<String, ByteArray>()

            for (file in otherDeviceFiles) {
                try {
                    val data = downloadSyncFile(file.fileId)
                    downloadedFiles[file.deviceId] = data
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to download file from device: ${file.deviceId}", e)
                }
            }

            Logger.d(TAG, "Downloaded ${downloadedFiles.size} sync files successfully")
            downloadedFiles
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download all device files", e)
            throw SyncExecutorException("Download all failed: ${e.message}", e)
        }
    }

    /**
     * Delete sync file
     */
    suspend fun deleteSyncFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()

            Logger.d(TAG, "Deleting sync file: $fileId")

            driveService.files().delete(fileId).execute()

            Logger.d(TAG, "Sync file deleted successfully")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete sync file", e)
            false
        }
    }

    /**
     * Delete old sync files
     */
    suspend fun deleteOldSyncFiles(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        try {
            val allFiles = listDeviceSyncFiles()
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)

            val oldFiles = allFiles.filter { it.modifiedTime < cutoffTime }

            Logger.d(TAG, "Deleting ${oldFiles.size} sync files older than $retentionDays days")

            var deletedCount = 0
            for (file in oldFiles) {
                if (deleteSyncFile(file.fileId)) {
                    deletedCount++
                }
            }

            Logger.d(TAG, "Deleted $deletedCount old sync files")
            deletedCount
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete old sync files", e)
            0
        }
    }

    /**
     * Find sync file ID for specific device
     */
    private suspend fun findSyncFile(deviceId: String): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = generateFileName(deviceId)
            val driveService = getDriveService()

            val query = "name = '$fileName' and trashed = false"

            val result: FileList = driveService.files()
                .list()
                .setSpaces(SYNC_FOLDER_SPACE)
                .setQ(query)
                .setFields("files(id)")
                .execute()

            val fileId = result.files?.firstOrNull()?.id

            if (fileId != null) {
                Logger.d(TAG, "Found sync file for device $deviceId: $fileId")
            } else {
                Logger.d(TAG, "No sync file found for device: $deviceId")
            }

            fileId
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to find sync file", e)
            null
        }
    }

    /**
     * Get Drive service with error handling
     */
    private fun getDriveService(): Drive {
        return authManager.getDriveService()
            ?: throw SyncExecutorException("Not authenticated - Drive service unavailable")
    }

    /**
     * Generate sync file name from device ID
     */
    private fun generateFileName(deviceId: String): String {
        return "$FILE_NAME_PREFIX$deviceId$FILE_NAME_SUFFIX"
    }

    /**
     * Extract device ID from file name
     */
    private fun extractDeviceIdFromFileName(fileName: String): String {
        return fileName
            .removePrefix(FILE_NAME_PREFIX)
            .removeSuffix(FILE_NAME_SUFFIX)
    }

    /**
     * Check if sync file exists for device
     */
    suspend fun syncFileExists(deviceId: String): Boolean {
        return findSyncFile(deviceId) != null
    }

    /**
     * Get sync file info
     */
    suspend fun getSyncFileInfo(fileId: String): RemoteSyncFile? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService()

            val file = driveService.files()
                .get(fileId)
                .setFields("id, name, modifiedTime, size")
                .execute()

            val deviceId = extractDeviceIdFromFileName(file.name)

            RemoteSyncFile(
                fileId = file.id,
                fileName = file.name,
                deviceId = deviceId,
                modifiedTime = (file.modifiedTime?.value ?: 0L) as Long,
                size = (file.size ?: 0L).toLong()
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to get sync file info", e)
            null
        }
    }
}

/**
 * Exception for sync executor errors
 */
class SyncExecutorException(message: String, cause: Throwable? = null) : Exception(message, cause)
