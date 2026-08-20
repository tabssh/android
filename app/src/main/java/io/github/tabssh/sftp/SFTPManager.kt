package io.github.tabssh.sftp
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages SFTP operations for file transfer and browsing
 * Provides high-level interface for file operations with progress tracking
 */
class SFTPManager(private val sshConnection: SSHConnection) {
    
    private var sftpChannel: ChannelSftp? = null
    private val transferScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Serializes access to the shared [sftpChannel] used by metadata operations
    // (list/stat/mkdir/rm/rename/chmod/cd/pwd/exists). JSch's ChannelSftp is not
    // thread-safe, so two concurrent metadata calls on the same channel corrupt
    // each other. Long-running transfers do NOT use this channel — each opens its
    // own dedicated channel via SSHConnection.openDedicatedSftpChannel().
    private val channelMutex = Mutex()

    // Active transfers. ConcurrentHashMap because entries are added on the caller
    // thread and removed from transfer coroutines running on Dispatchers.IO.
    private val activeTransfers = ConcurrentHashMap<String, TransferTask>()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Settings
    // 32KB
    private var bufferSize = 32768
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
     * Advance [stream] by exactly [count] bytes, or throw.
     *
     * `InputStream.skip` is best-effort — on JSch's network-backed `get()` stream
     * it routinely returns short. A short skip on a resumed transfer appends from
     * the wrong offset, producing a byte-gap the caller then reports as Success.
     */
    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() returned 0: fall back to reading, and treat EOF as fatal
            // rather than silently resuming from the wrong position.
            if (stream.read() < 0) {
                throw IOException("Cannot resume: stream ended $remaining bytes before offset $count")
            }
            remaining--
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
        
        // Clear the field first so any withChannel() that has not yet taken the
        // mutex falls straight through to its default, then tear the channel down
        // behind channelMutex. disconnect() is not suspend, so it previously
        // destroyed the channel unguarded — potentially in the middle of an
        // in-flight ls()/stat() holding the very lock that exists because
        // JSch's ChannelSftp is not thread-safe.
        val closing = sftpChannel
        sftpChannel = null
        _isConnected.value = false
        if (closing != null) {
            transferScope.launch {
                channelMutex.withLock {
                    try {
                        closing.disconnect()
                    } catch (e: Exception) {
                        Logger.w("SFTPManager", "Failed to disconnect SFTP channel: ${e.message}")
                    }
                }
            }
        }

        notifyListeners { onSFTPDisconnected() }
    }
    
    /**
     * List files and directories in remote path
     */
    suspend fun listRemoteFiles(path: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        withChannel(emptyList()) { channel ->
            try {
                @Suppress("UNCHECKED_CAST")
                val entries = channel.ls(path) as Vector<ChannelSftp.LsEntry>

                entries.mapNotNull { entry ->
                    // Skip "." / ".." and drop any server-supplied name that is
                    // not a plain path component. A hostile server returning a
                    // filename with "/" or ".." could otherwise steer a later
                    // download outside the chosen local directory.
                    if (!isSafeRemoteName(entry.filename)) {
                        if (entry.filename != "." && entry.filename != "..") {
                            Logger.w("SFTPManager", "Skipping remote entry with unsafe name in $path")
                        }
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
                            owner = entry.attrs.uId,
                            group = entry.attrs.gId
                        )
                    }
                }.sortedWith(compareBy<RemoteFileInfo> { !it.isDirectory }.thenBy { it.name.lowercase() })

            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to list remote files in $path", e)
                emptyList()
            }
        }
    }
    
    /**
     * Get remote file attributes
     */
    suspend fun getRemoteFileAttributes(path: String): RemoteFileInfo? = withContext(Dispatchers.IO) {
        withChannel(null) { channel ->
            try {
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
                    owner = attrs.uId,
                    group = attrs.gId
                )
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to get attributes for $path", e)
                null
            }
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
        // Each transfer runs on its own dedicated ChannelSftp. JSch channels are
        // not thread-safe, and a long-running transfer must not block or corrupt
        // the shared cached metadata channel used by listRemoteFiles/stat/etc.
        val channel = sshConnection.openDedicatedSftpChannel()
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

            task.setBytesTransferred(startOffset)

            // Perform upload with progress monitoring
            // Both streams wrapped in .use{} so that an exception in transferWithProgress
            // (or in inputStream creation) does not leak the SFTP put stream.
            val outputStream = if (startOffset > 0) {
                channel.put(task.remotePath, ChannelSftp.RESUME)
            } else {
                channel.put(task.remotePath)
            }
            outputStream.use { out ->
                localFile.inputStream().use { inputStream ->
                    if (startOffset > 0) {
                        skipFully(inputStream, startOffset)
                    }
                    transferWithProgress(inputStream, out, task)
                }
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
            
            // transferWithProgress exits its copy loop on cancellation, so a
            // cancelled transfer would otherwise fall through and report Success
            // over a partial file. Surface the cancellation as Cancelled instead.
            if (task.isCancelled()) {
                task.complete(TransferResult.Cancelled)
                Logger.i("SFTPManager", "Upload cancelled: ${task.localPath}")
                return@withContext
            }

            task.complete(TransferResult.Success)
            Logger.i("SFTPManager", "Upload completed: ${task.localPath}")

            // Audit logging — best-effort, never break the SFTP success path.
            try {
                val app = sshConnection.context.applicationContext as? io.github.tabssh.TabSSHApplication
                app?.auditLogManager?.logSftpUpload(
                    sshConnection.profile,
                    sshConnection.id,
                    task.remotePath,
                    localFile.length()
                )
            } catch (e: Exception) {
                Logger.w("SFTPManager", "Audit log (sftpUpload) failed: ${e.message}")
            }

        } catch (e: CancellationException) {
            // Scope cancellation is not a transfer error — reporting it as one
            // hid a cancelled transfer behind a bogus TransferResult.Error.
            task.complete(TransferResult.Cancelled)
            throw e
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Upload failed: ${task.localPath}", e)
            task.complete(TransferResult.Error(e.message ?: "Upload failed"))
        } finally {
            // The dedicated channel is owned by this transfer — always release it.
            try {
                channel.disconnect()
            } catch (e: Exception) {
                Logger.w("SFTPManager", "Failed to disconnect transfer channel", e)
            }
            activeTransfers.remove(task.id)
        }
    }
    
    private suspend fun performDownload(task: TransferTask) = withContext(Dispatchers.IO) {
        // Dedicated per-transfer channel — see performUpload for the rationale.
        val channel = sshConnection.openDedicatedSftpChannel()
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

            task.setBytesTransferred(startOffset)

            // Perform download with progress monitoring
            // inputStream wrapped in .use{} so it is closed if outputStream
            // construction or transferWithProgress throws.
            channel.get(task.remotePath).use { inputStream ->
                // Skip bytes if resuming
                if (startOffset > 0) {
                    skipFully(inputStream, startOffset)
                }

                // Resume-download fix: previously this branch called
                // `localFile.outputStream().close()` (which truncates the
                // partial file to zero bytes) and then `appendOutputStream()`
                // which was a stub that *also* truncated, so resuming a
                // download discarded the bytes-on-disk and silently produced
                // a tail-only file. Use FileOutputStream(file, append=true)
                // so the post-skip bytes are appended after the already-
                // downloaded prefix.
                val outputStream = if (startOffset > 0) {
                    FileOutputStream(localFile, true)
                } else {
                    FileOutputStream(localFile, false)
                }

                outputStream.use { output ->
                    transferWithProgress(inputStream, output, task)
                }
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
            
            // A cancelled download leaves a partial file on disk — report
            // Cancelled rather than falling through to a false Success.
            if (task.isCancelled()) {
                task.complete(TransferResult.Cancelled)
                Logger.i("SFTPManager", "Download cancelled: ${task.remotePath}")
                return@withContext
            }

            task.complete(TransferResult.Success)
            Logger.i("SFTPManager", "Download completed: ${task.remotePath}")

            // Audit logging — best-effort, never break the SFTP success path.
            try {
                val app = sshConnection.context.applicationContext as? io.github.tabssh.TabSSHApplication
                app?.auditLogManager?.logSftpDownload(
                    sshConnection.profile,
                    sshConnection.id,
                    task.remotePath,
                    localFile.length()
                )
            } catch (e: Exception) {
                Logger.w("SFTPManager", "Audit log (sftpDownload) failed: ${e.message}")
            }

        } catch (e: CancellationException) {
            // Same as the upload path: cancellation must propagate, not be
            // reported to the UI as a failed transfer.
            task.complete(TransferResult.Cancelled)
            throw e
        } catch (e: Exception) {
            Logger.e("SFTPManager", "Download failed: ${task.remotePath}", e)
            task.complete(TransferResult.Error(e.message ?: "Download failed"))
        } finally {
            // The dedicated channel is owned by this transfer — always release it.
            try {
                channel.disconnect()
            } catch (e: Exception) {
                Logger.w("SFTPManager", "Failed to disconnect transfer channel", e)
            }
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
                // Update every 100ms
                if (now - lastProgressUpdate > 100) {
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
        withChannel(false) { channel ->
            try {
                channel.mkdir(path)
                Logger.d("SFTPManager", "Created remote directory: $path")
                true
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to create remote directory: $path", e)
                false
            }
        }
    }
    
    /**
     * Delete remote file or directory
     */
    suspend fun deleteRemoteFile(path: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        withChannel(false) { channel ->
            try {
                if (isDirectory) {
                    deleteRemoteDirectoryRecursive(channel, path)
                } else {
                    channel.rm(path)
                }
                Logger.d("SFTPManager", "Deleted remote ${if (isDirectory) "directory" else "file"}: $path")
                // Audit logging — best-effort, never break the SFTP success path.
                try {
                    val app = sshConnection.context.applicationContext as? io.github.tabssh.TabSSHApplication
                    app?.auditLogManager?.logSftpDelete(sshConnection.profile, sshConnection.id, path)
                } catch (e: Exception) {
                    Logger.w("SFTPManager", "Audit log (sftpDelete) failed: ${e.message}")
                }
                true
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to delete remote $path", e)
                false
            }
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
        withChannel(false) { channel ->
            try {
                channel.rename(oldPath, newPath)
                Logger.d("SFTPManager", "Renamed remote: $oldPath -> $newPath")
                true
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to rename remote: $oldPath -> $newPath", e)
                false
            }
        }
    }
    
    /**
     * Change remote file permissions
     */
    suspend fun changeRemotePermissions(path: String, permissions: Int): Boolean = withContext(Dispatchers.IO) {
        withChannel(false) { channel ->
            try {
                channel.chmod(permissions, path)
                Logger.d("SFTPManager", "Changed permissions for $path to ${Integer.toOctalString(permissions)}")
                true
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to change permissions for $path", e)
                false
            }
        }
    }
    
    /**
     * Get current remote working directory
     */
    suspend fun getRemoteWorkingDirectory(): String? = withContext(Dispatchers.IO) {
        withChannel(null) { channel ->
            try {
                val pwd = channel.pwd()
                Logger.d("SFTPManager", "Remote working directory: $pwd")
                pwd
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to get remote working directory", e)
                null
            }
        }
    }
    
    /**
     * Change remote working directory
     */
    suspend fun changeRemoteDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        withChannel(false) { channel ->
            try {
                channel.cd(path)
                Logger.d("SFTPManager", "Changed remote directory to: $path")
                true
            } catch (e: SftpException) {
                Logger.e("SFTPManager", "Failed to change remote directory to: $path", e)
                false
            }
        }
    }
    
    /**
     * Check if remote path exists
     */
    suspend fun remoteFileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        withChannel(false) { channel ->
            try {
                channel.stat(path)
                true
            } catch (e: SftpException) {
                false
            }
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
    
    // Helper methods
    
    private fun generateTransferId(): String = UUID.randomUUID().toString()

    /**
     * Run [block] against the shared SFTP channel under [channelMutex], so
     * metadata operations never touch JSch's non-thread-safe channel
     * concurrently. Returns [default] when the channel is not open.
     */
    private suspend fun <T> withChannel(default: T, block: suspend (ChannelSftp) -> T): T =
        channelMutex.withLock {
            val channel = sftpChannel ?: return@withLock default
            block(channel)
        }

    /**
     * True if [name] is a single, safe path component. A malicious or compromised
     * server can return directory entries whose filename contains a path
     * separator or "..", which would let a later download escape the chosen local
     * directory (path traversal). Reject anything that is not a plain component.
     */
    private fun isSafeRemoteName(name: String): Boolean =
        name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        name.none { it.code < 0x20 }
    
    private fun getLocalFilePermissions(file: File): Int {
        // Convert Java file permissions to Unix octal format
        var permissions = 0
        // 0400 in octal
        if (file.canRead()) permissions = permissions or 0x100
        // 0200 in octal
        if (file.canWrite()) permissions = permissions or 0x80
        // 0100 in octal
        if (file.canExecute()) permissions = permissions or 0x40

        // Default to 644 for files, 755 for directories if no specific permissions
        return if (permissions == 0) {
            // 0755 and 0644 in hexadecimal
            if (file.isDirectory) 0x1ed else 0x1a4
        } else {
            // Add group/other read permissions (044 in octal)
            permissions or 0x24
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
    
    // Configuration
    
    fun setBufferSize(size: Int) {
        // 1KB to 1MB
        bufferSize = size.coerceIn(1024, 1024 * 1024)
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
