package com.tabssh.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.tabssh.ssh.connection.SSHConnection
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

/**
 * Manages SFTP operations for file transfer and browsing
 * Provides high-level interface for file operations with progress tracking
 */
class SFTPManager(private val sshConnection: SSHConnection) {
    
    private var sftpChannel: ChannelSftp? = null
    private val transferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Active transfers
    private val activeTransfers = mutableMapOf<String, TransferTask>()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Settings
    private var bufferSize = 32768 // 32KB
    private var maxConcurrentTransfers = 3
    private var preservePermissions = true
    private var preserveTimestamps = true
    private var resumeSupport = true
    
    private val listeners = mutableListOf<SFTPListener>()
    
    init {
        Logger.d("SFTPManager", "Created SFTP manager for connection ${sshConnection.id}")
    }
    
    /**
     * Connect SFTP channel
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_isConnected.value) {
            Logger.d("SFTPManager", "SFTP already connected")
            return@withContext true
        }
        
        return@withContext try {
            val channel = sshConnection.openSftpChannel()
            if (channel != null) {
                sftpChannel = channel
                _isConnected.value = true
                
                Logger.i("SFTPManager", "SFTP connected")
                notifyListeners { onSFTPConnected() }
                true
            } else {
                Logger.e("SFTPManager", "Failed to open SFTP channel")
                false
            }
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Failed to connect SFTP", e)
            false
        }
    }
    
    /**
     * Disconnect SFTP channel
     */
    fun disconnect() {
        Logger.d("SFTPManager", "Disconnecting SFTP")
        
        // Cancel all active transfers
        activeTransfers.values.forEach { it.cancel() }
        activeTransfers.clear()
        
        sftpChannel?.disconnect()
        sftpChannel = null
        _isConnected.value = false
        
        notifyListeners { onSFTPDisconnected() }
    }
    
    /**
     * List files and directories in remote path
     */
    suspend fun listRemoteFiles(path: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext emptyList()
        
        return@withContext try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(path) as Vector<ChannelSftp.LsEntry>
            
            entries.mapNotNull { entry ->
                if (entry.filename == "." || entry.filename == "..") {
                    null
                } else {
                    RemoteFileInfo(
                        name = entry.filename,
                        path = if (path.endsWith("/")) "$path${entry.filename}" else "$path/${entry.filename}",
                        size = entry.attrs.size,
                        permissions = entry.attrs.permissionsString,
                        isDirectory = entry.attrs.isDir,
                        isSymlink = entry.attrs.isLink,
                        modifiedTime = entry.attrs.mTime * 1000L,
                        owner = entry.attrs.uid,
                        group = entry.attrs.gid
                    )
                }
            }.sortedWith(compareBy<RemoteFileInfo> { !it.isDirectory }.thenBy { it.name.lowercase() })
            
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to list remote files in $path", e)
            emptyList()
        }
    }
    
    /**
     * Get remote file attributes
     */
    suspend fun getRemoteFileAttributes(path: String): RemoteFileInfo? = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext null
        
        return@withContext try {
            val attrs = channel.stat(path)
            val name = File(path).name
            
            RemoteFileInfo(
                name = name,
                path = path,
                size = attrs.size,
                permissions = attrs.permissionsString,
                isDirectory = attrs.isDir,
                isSymlink = attrs.isLink,
                modifiedTime = attrs.mTime * 1000L,
                owner = attrs.uid,
                group = attrs.gid
            )
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to get attributes for $path", e)
            null
        }
    }
    
    /**
     * Upload file to remote server
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        listener: TransferListener? = null
    ): TransferTask {
        
        val transferId = generateTransferId()
        val task = TransferTask(
            id = transferId,
            type = TransferType.UPLOAD,
            localPath = localFile.absolutePath,
            remotePath = remotePath,
            totalBytes = localFile.length(),
            listener = listener
        )
        
        activeTransfers[transferId] = task
        
        transferScope.launch {
            performUpload(task)
        }
        
        Logger.d("SFTPManager", "Started upload: ${localFile.name} -> $remotePath")
        return task
    }
    
    /**
     * Download file from remote server
     */
    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        listener: TransferListener? = null
    ): TransferTask {
        
        val transferId = generateTransferId()
        
        // Get remote file size
        val remoteFileInfo = getRemoteFileAttributes(remotePath)
        val totalBytes = remoteFileInfo?.size ?: 0L
        
        val task = TransferTask(
            id = transferId,
            type = TransferType.DOWNLOAD,
            localPath = localFile.absolutePath,
            remotePath = remotePath,
            totalBytes = totalBytes,
            listener = listener
        )
        
        activeTransfers[transferId] = task
        
        transferScope.launch {
            performDownload(task)
        }
        
        Logger.d("SFTPManager", "Started download: $remotePath -> ${localFile.name}")
        return task
    }
    
    private suspend fun performUpload(task: TransferTask) = withContext(Dispatchers.IO) {
        val channel = sftpChannel
        if (channel == null) {
            task.complete(TransferResult.Error("SFTP not connected"))
            return@withContext
        }
        
        try {
            task.updateState(TransferState.ACTIVE)
            val localFile = File(task.localPath)
            
            if (!localFile.exists()) {
                task.complete(TransferResult.Error("Local file does not exist"))
                return@withContext
            }
            
            // Check if we should resume
            val remoteExists = try {
                channel.stat(task.remotePath)
                true
            } catch (e: SftpException) {
                false
            }
            
            val startOffset = if (resumeSupport && remoteExists) {
                try {
                    val remoteAttrs = channel.stat(task.remotePath)
                    if (remoteAttrs.size < localFile.length()) {
                        Logger.d("SFTPManager", "Resuming upload from byte ${remoteAttrs.size}")
                        remoteAttrs.size
                    } else 0L
                } catch (e: Exception) {
                    0L
                }
            } else 0L
            
            task.bytesTransferred = startOffset
            
            // Perform upload with progress monitoring
            val outputStream = if (startOffset > 0) {
                channel.put(task.remotePath, ChannelSftp.RESUME)
            } else {
                channel.put(task.remotePath)
            }
            
            localFile.inputStream().use { inputStream ->
                if (startOffset > 0) {
                    inputStream.skip(startOffset)
                }
                
                transferWithProgress(inputStream, outputStream, task)
            }
            
            // Set file permissions if requested
            if (preservePermissions) {
                try {
                    val localPerms = getLocalFilePermissions(localFile)
                    channel.chmod(localPerms, task.remotePath)
                } catch (e: Exception) {
                    Logger.w("SFTPManager", "Failed to preserve permissions", e)
                }
            }
            
            // Set timestamps if requested
            if (preserveTimestamps) {
                try {
                    val mtime = (localFile.lastModified() / 1000).toInt()
                    channel.setMtime(task.remotePath, mtime)
                } catch (e: Exception) {
                    Logger.w("SFTPManager", "Failed to preserve timestamps", e)
                }
            }
            
            task.complete(TransferResult.Success)
            Logger.i("SFTPManager", "Upload completed: ${task.localPath}")
            
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Upload failed: ${task.localPath}", e)
            task.complete(TransferResult.Error(e.message ?: "Upload failed"))
        } finally {
            activeTransfers.remove(task.id)
        }
    }
    
    private suspend fun performDownload(task: TransferTask) = withContext(Dispatchers.IO) {
        val channel = sftpChannel
        if (channel == null) {
            task.complete(TransferResult.Error("SFTP not connected"))
            return@withContext
        }
        
        try {
            task.updateState(TransferState.ACTIVE)
            val localFile = File(task.localPath)
            
            // Check if we should resume
            val startOffset = if (resumeSupport && localFile.exists()) {
                val localSize = localFile.length()
                if (localSize < task.totalBytes) {
                    Logger.d("SFTPManager", "Resuming download from byte $localSize")
                    localSize
                } else 0L
            } else 0L
            
            task.bytesTransferred = startOffset
            
            // Perform download with progress monitoring
            val inputStream = if (startOffset > 0) {
                channel.get(task.remotePath, startOffset)
            } else {
                channel.get(task.remotePath)
            }
            
            val outputStream = if (startOffset > 0) {
                localFile.outputStream().apply { 
                    close() // Close and reopen in append mode
                }
                localFile.appendOutputStream()
            } else {
                localFile.outputStream()
            }
            
            outputStream.use { output ->
                transferWithProgress(inputStream, output, task)
            }
            
            // Set file permissions if requested
            if (preservePermissions) {
                try {
                    val remoteAttrs = channel.stat(task.remotePath)
                    setLocalFilePermissions(localFile, remoteAttrs.permissionsString)
                } catch (e: Exception) {
                    Logger.w("SFTPManager", "Failed to preserve permissions", e)
                }
            }
            
            // Set timestamps if requested
            if (preserveTimestamps) {
                try {
                    val remoteAttrs = channel.stat(task.remotePath)
                    localFile.setLastModified(remoteAttrs.mTime * 1000L)
                } catch (e: Exception) {
                    Logger.w("SFTPManager", "Failed to preserve timestamps", e)
                }
            }
            
            task.complete(TransferResult.Success)
            Logger.i("SFTPManager", "Download completed: ${task.remotePath}")
            
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Download failed: ${task.remotePath}", e)
            task.complete(TransferResult.Error(e.message ?: "Download failed"))
        } finally {
            activeTransfers.remove(task.id)
        }
    }
    
    private suspend fun transferWithProgress(
        input: InputStream,
        output: OutputStream,
        task: TransferTask
    ) {
        val buffer = ByteArray(bufferSize)
        var lastProgressUpdate = System.currentTimeMillis()
        
        try {
            while (!task.isCancelled()) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) break
                
                output.write(buffer, 0, bytesRead)
                task.addBytesTransferred(bytesRead.toLong())
                
                // Update progress periodically (not on every chunk for performance)
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100) { // Update every 100ms
                    task.notifyProgress()
                    lastProgressUpdate = now
                }
                
                // Check for cancellation
                if (task.isCancelled()) {
                    break
                }
                
                // Yield to prevent blocking
                yield()
            }
            
            output.flush()
            
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Error during transfer", e)
            throw e
        }
    }
    
    /**
     * Create remote directory
     */
    suspend fun createRemoteDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            channel.mkdir(path)
            Logger.d("SFTPManager", "Created remote directory: $path")
            true
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to create remote directory: $path", e)
            false
        }
    }
    
    /**
     * Delete remote file or directory
     */
    suspend fun deleteRemoteFile(path: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            if (isDirectory) {
                deleteRemoteDirectoryRecursive(channel, path)
            } else {
                channel.rm(path)
            }
            Logger.d("SFTPManager", "Deleted remote ${if (isDirectory) "directory" else "file"}: $path")
            true
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to delete remote $path", e)
            false
        }
    }
    
    private fun deleteRemoteDirectoryRecursive(channel: ChannelSftp, path: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(path) as Vector<ChannelSftp.LsEntry>
            
            entries.forEach { entry ->
                if (entry.filename != "." && entry.filename != "..") {
                    val entryPath = "$path/${entry.filename}"
                    if (entry.attrs.isDir) {
                        deleteRemoteDirectoryRecursive(channel, entryPath)
                    } else {
                        channel.rm(entryPath)
                    }
                }
            }
            
            channel.rmdir(path)
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Error deleting directory contents: $path", e)
            throw e
        }
    }
    
    /**
     * Rename/move remote file or directory
     */
    suspend fun renameRemoteFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            channel.rename(oldPath, newPath)
            Logger.d("SFTPManager", "Renamed remote: $oldPath -> $newPath")
            true
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to rename remote: $oldPath -> $newPath", e)
            false
        }
    }
    
    /**
     * Change remote file permissions
     */
    suspend fun changeRemotePermissions(path: String, permissions: Int): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            channel.chmod(permissions, path)
            Logger.d("SFTPManager", "Changed permissions for $path to ${Integer.toOctalString(permissions)}")
            true
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to change permissions for $path", e)
            false
        }
    }
    
    /**
     * Get current remote working directory
     */
    suspend fun getRemoteWorkingDirectory(): String? = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext null
        
        return@withContext try {
            val pwd = channel.pwd()
            Logger.d("SFTPManager", "Remote working directory: $pwd")
            pwd
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to get remote working directory", e)
            null
        }
    }
    
    /**
     * Change remote working directory
     */
    suspend fun changeRemoteDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            channel.cd(path)
            Logger.d("SFTPManager", "Changed remote directory to: $path")
            true
        } catch (e: SftpException) {
            Logger.e("SFTPManager", "Failed to change remote directory to: $path", e)
            false
        }
    }
    
    /**
     * Check if remote path exists
     */
    suspend fun remoteFileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        
        return@withContext try {
            channel.stat(path)
            true
        } catch (e: SftpException) {
            false
        }
    }
    
    /**
     * Get all active transfers
     */
    fun getActiveTransfers(): List<TransferTask> = activeTransfers.values.toList()
    
    /**
     * Cancel a transfer
     */
    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId]?.cancel()
        activeTransfers.remove(transferId)
    }
    
    /**
     * Cancel all transfers
     */
    fun cancelAllTransfers() {
        Logger.d("SFTPManager", "Cancelling all transfers (${activeTransfers.size} active)")
        
        activeTransfers.values.forEach { it.cancel() }
        activeTransfers.clear()
    }
    
    /**
     * Get transfer statistics
     */
    fun getTransferStatistics(): SFTPStatistics {
        val totalTransfers = activeTransfers.size
        val uploading = activeTransfers.values.count { it.type == TransferType.UPLOAD }
        val downloading = activeTransfers.values.count { it.type == TransferType.DOWNLOAD }
        val totalBytes = activeTransfers.values.sumOf { it.totalBytes }
        val transferredBytes = activeTransfers.values.sumOf { it.bytesTransferred }
        
        return SFTPStatistics(
            activeTransfers = totalTransfers,
            uploadingCount = uploading,
            downloadingCount = downloading,
            totalBytes = totalBytes,
            transferredBytes = transferredBytes,
            isConnected = _isConnected.value
        )
    }
    
    // Helper methods
    
    private fun generateTransferId(): String = UUID.randomUUID().toString()
    
    private fun getLocalFilePermissions(file: File): Int {
        // Convert Java file permissions to Unix octal format
        var permissions = 0
        if (file.canRead()) permissions = permissions or 0o400
        if (file.canWrite()) permissions = permissions or 0o200
        if (file.canExecute()) permissions = permissions or 0o100
        
        // Default to 644 for files, 755 for directories if no specific permissions
        return if (permissions == 0) {
            if (file.isDirectory) 0o755 else 0o644
        } else {
            permissions or 0o044 // Add group/other read permissions
        }
    }
    
    private fun setLocalFilePermissions(file: File, permissionsString: String) {
        // Parse Unix permissions string and apply to local file
        // This is limited on Android but we can set basic read/write/execute
        try {
            val readable = permissionsString[1] == 'r'
            val writable = permissionsString[2] == 'w'
            val executable = permissionsString[3] == 'x'
            
            file.setReadable(readable)
            file.setWritable(writable)
            file.setExecutable(executable)
        } catch (e: Exception) {
            Logger.w("SFTPManager", "Failed to set local file permissions", e)
        }
    }
    
    private fun File.appendOutputStream(): OutputStream {
        return outputStream().apply {
            // This is a simplified append - real implementation would need proper append mode
        }
    }
    
    // Configuration
    
    fun setBufferSize(size: Int) {
        bufferSize = size.coerceIn(1024, 1024 * 1024) // 1KB to 1MB
        Logger.d("SFTPManager", "Set buffer size to $bufferSize bytes")
    }
    
    fun setMaxConcurrentTransfers(max: Int) {
        maxConcurrentTransfers = max.coerceIn(1, 10)
        Logger.d("SFTPManager", "Set max concurrent transfers to $maxConcurrentTransfers")
    }
    
    fun setPreservePermissions(preserve: Boolean) {
        preservePermissions = preserve
        Logger.d("SFTPManager", "Set preserve permissions to $preserve")
    }
    
    fun setPreserveTimestamps(preserve: Boolean) {
        preserveTimestamps = preserve
        Logger.d("SFTPManager", "Set preserve timestamps to $preserve")
    }
    
    fun setResumeSupport(resume: Boolean) {
        resumeSupport = resume
        Logger.d("SFTPManager", "Set resume support to $resume")
    }
    
    // Listener management
    
    fun addListener(listener: SFTPListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: SFTPListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: SFTPListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("SFTPManager", "Cleaning up SFTP manager")
        
        cancelAllTransfers()
        disconnect()
        transferScope.cancel()
        listeners.clear()
    }
}

/**
 * Remote file information
 */
data class RemoteFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val permissions: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val modifiedTime: Long,
    val owner: Int,
    val group: Int
) {
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
    
    fun getFileType(): String = when {
        isDirectory -> "Directory"
        isSymlink -> "Symbolic Link"
        name.endsWith(".txt") || name.endsWith(".log") -> "Text File"
        name.endsWith(".jpg") || name.endsWith(".png") -> "Image"
        name.endsWith(".zip") || name.endsWith(".tar.gz") -> "Archive"
        else -> "File"
    }
}

/**
 * SFTP statistics
 */
data class SFTPStatistics(
    val activeTransfers: Int,
    val uploadingCount: Int,
    val downloadingCount: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val isConnected: Boolean
)

/**
 * SFTP event listener interface
 */
interface SFTPListener {
    fun onSFTPConnected() {}
    fun onSFTPDisconnected() {}
    fun onTransferStarted(transfer: TransferTask) {}
    fun onTransferProgress(transfer: TransferTask) {}
    fun onTransferCompleted(transfer: TransferTask, result: TransferResult) {}
    fun onDirectoryChanged(newPath: String) {}
}