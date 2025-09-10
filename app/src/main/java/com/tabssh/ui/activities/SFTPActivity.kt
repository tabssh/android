package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivitySftpBinding
import io.github.tabssh.sftp.RemoteFileInfo
import io.github.tabssh.sftp.SFTPManager
import io.github.tabssh.sftp.TransferTask
import io.github.tabssh.sftp.TransferListener
import io.github.tabssh.ui.adapters.FileAdapter
import io.github.tabssh.ui.adapters.TransferAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import java.io.File

/**
 * SFTP file browser activity with dual-pane interface
 * Provides comprehensive file management capabilities
 */
class SFTPActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        
        fun createIntent(context: Context, connectionId: String): Intent {
            return Intent(context, SFTPActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_ID, connectionId)
            }
        }
    }
    
    private lateinit var binding: ActivitySftpBinding
    private lateinit var app: TabSSHApplication
    private lateinit var sftpManager: SFTPManager
    
    // File adapters
    private lateinit var localFileAdapter: FileAdapter
    private lateinit var remoteFileAdapter: FileAdapter
    private lateinit var transferAdapter: TransferAdapter
    
    // Current directories
    private var currentLocalPath = "/storage/emulated/0" // Default to external storage
    private var currentRemotePath = "/"
    
    // File lists
    private val localFiles = mutableListOf<File>()
    private val remoteFiles = mutableListOf<RemoteFileInfo>()
    private val activeTransfers = mutableListOf<TransferTask>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySftpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as TabSSHApplication
        
        setupToolbar()
        setupSFTPManager()
        setupFileAdapters()
        setupTransferAdapter()
        setupButtons()
        setupPathNavigation()
        
        // Load initial directories
        loadLocalDirectory(currentLocalPath)
        loadRemoteDirectory(currentRemotePath)
        
        Logger.i("SFTPActivity", "SFTP activity created")
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.file_browser_title)
        }
    }
    
    private fun setupSFTPManager() {
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        if (connectionId == null) {
            Logger.e("SFTPActivity", "No connection ID provided")
            finish()
            return
        }
        
        lifecycleScope.launch {
            try {
                val connection = app.sshSessionManager.getConnection(connectionId)
                if (connection == null) {
                    Logger.e("SFTPActivity", "Connection not found: $connectionId")
                    finish()
                    return@launch
                }
                
                sftpManager = SFTPManager(connection)
                val connected = sftpManager.connect()
                
                if (connected) {
                    Logger.i("SFTPActivity", "SFTP connected successfully")
                    loadRemoteDirectory(currentRemotePath)
                } else {
                    Logger.e("SFTPActivity", "Failed to connect SFTP")
                    showToast("Failed to connect SFTP")
                    finish()
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error setting up SFTP", e)
                showToast("SFTP setup error: ${e.message}")
                finish()
            }
        }
    }
    
    private fun setupFileAdapters() {
        // Local file adapter
        localFileAdapter = FileAdapter(
            files = localFiles,
            isRemote = false,
            onFileClick = { file -> handleLocalFileClick(file) },
            onFileLongClick = { file -> showLocalFileMenu(file) }
        )
        
        binding.recyclerLocalFiles.apply {
            layoutManager = LinearLayoutManager(this@SFTPActivity)
            adapter = localFileAdapter
        }
        
        // Remote file adapter
        remoteFileAdapter = FileAdapter(
            remoteFiles = remoteFiles,
            isRemote = true,
            onRemoteFileClick = { file -> handleRemoteFileClick(file) },
            onRemoteFileLongClick = { file -> showRemoteFileMenu(file) }
        )
        
        binding.recyclerRemoteFiles.apply {
            layoutManager = LinearLayoutManager(this@SFTPActivity)
            adapter = remoteFileAdapter
        }
    }
    
    private fun setupTransferAdapter() {
        transferAdapter = TransferAdapter(
            transfers = activeTransfers,
            onTransferCancel = { transfer -> cancelTransfer(transfer) },
            onTransferPause = { transfer -> pauseTransfer(transfer) },
            onTransferResume = { transfer -> resumeTransfer(transfer) }
        )
        
        binding.recyclerTransfers.apply {
            layoutManager = LinearLayoutManager(this@SFTPActivity)
            adapter = transferAdapter
        }
    }
    
    private fun setupButtons() {
        binding.btnUpload.setOnClickListener {
            uploadSelectedFiles()
        }
        
        binding.btnDownload.setOnClickListener {
            downloadSelectedFiles()
        }
        
        binding.btnNewFolder.setOnClickListener {
            showCreateFolderDialog()
        }
        
        binding.btnRefresh.setOnClickListener {
            refreshDirectories()
        }
    }
    
    private fun setupPathNavigation() {
        binding.textLocalPath.text = currentLocalPath
        binding.textRemotePath.text = currentRemotePath
        
        binding.btnLocalUp.setOnClickListener {
            navigateLocalUp()
        }
        
        binding.btnRemoteUp.setOnClickListener {
            navigateRemoteUp()
        }
    }
    
    private fun loadLocalDirectory(path: String) {
        lifecycleScope.launch {
            try {
                val directory = File(path)
                if (directory.exists() && directory.isDirectory) {
                    val files = directory.listFiles()?.toList() ?: emptyList()
                    
                    localFiles.clear()
                    localFiles.addAll(files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }))
                    
                    runOnUiThread {
                        localFileAdapter.notifyDataSetChanged()
                        binding.textLocalPath.text = path
                        currentLocalPath = path
                    }
                    
                    Logger.d("SFTPActivity", "Loaded local directory: $path (${files.size} items)")
                }
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to load local directory: $path", e)
                showToast("Failed to load local directory")
            }
        }
    }
    
    private fun loadRemoteDirectory(path: String) {
        if (!::sftpManager.isInitialized) return
        
        lifecycleScope.launch {
            try {
                val files = sftpManager.listRemoteFiles(path)
                
                remoteFiles.clear()
                remoteFiles.addAll(files)
                
                runOnUiThread {
                    remoteFileAdapter.notifyDataSetChanged()
                    binding.textRemotePath.text = path
                    currentRemotePath = path
                }
                
                Logger.d("SFTPActivity", "Loaded remote directory: $path (${files.size} items)")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to load remote directory: $path", e)
                showToast("Failed to load remote directory")
            }
        }
    }
    
    private fun handleLocalFileClick(file: File) {
        if (file.isDirectory) {
            loadLocalDirectory(file.absolutePath)
        } else {
            // Select file for upload
            selectLocalFile(file)
        }
    }
    
    private fun handleRemoteFileClick(file: RemoteFileInfo) {
        if (file.isDirectory) {
            loadRemoteDirectory(file.path)
        } else {
            // Select file for download
            selectRemoteFile(file)
        }
    }
    
    private fun selectLocalFile(file: File) {
        // Highlight selected file and enable upload button
        binding.btnUpload.isEnabled = true
        binding.btnUpload.text = "Upload ${file.name}"
        
        Logger.d("SFTPActivity", "Selected local file: ${file.name}")
    }
    
    private fun selectRemoteFile(file: RemoteFileInfo) {
        // Highlight selected file and enable download button  
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = "Download ${file.name}"
        
        Logger.d("SFTPActivity", "Selected remote file: ${file.name}")
    }
    
    private fun uploadSelectedFiles() {
        // This would upload selected local files to current remote directory
        // For now, show placeholder message
        showToast("Upload functionality - select files to upload")
    }
    
    private fun downloadSelectedFiles() {
        // This would download selected remote files to current local directory
        // For now, show placeholder message
        showToast("Download functionality - select files to download")
    }
    
    private fun showCreateFolderDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val input = android.widget.EditText(this)
        input.hint = "Folder name"
        
        builder.setTitle("Create Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    createRemoteFolder(folderName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun createRemoteFolder(folderName: String) {
        if (!::sftpManager.isInitialized) return
        
        lifecycleScope.launch {
            try {
                val newPath = "$currentRemotePath/$folderName"
                val created = sftpManager.createRemoteDirectory(newPath)
                
                if (created) {
                    showToast("Folder created: $folderName")
                    loadRemoteDirectory(currentRemotePath) // Refresh
                } else {
                    showToast("Failed to create folder")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error creating folder", e)
                showToast("Error creating folder: ${e.message}")
            }
        }
    }
    
    private fun navigateLocalUp() {
        val parent = File(currentLocalPath).parentFile
        if (parent != null && parent.canRead()) {
            loadLocalDirectory(parent.absolutePath)
        }
    }
    
    private fun navigateRemoteUp() {
        if (currentRemotePath != "/") {
            val parent = File(currentRemotePath).parent ?: "/"
            loadRemoteDirectory(parent)
        }
    }
    
    private fun refreshDirectories() {
        loadLocalDirectory(currentLocalPath)
        loadRemoteDirectory(currentRemotePath)
        
        // Refresh transfers
        if (::sftpManager.isInitialized) {
            activeTransfers.clear()
            activeTransfers.addAll(sftpManager.getActiveTransfers())
            transferAdapter.notifyDataSetChanged()
        }
    }
    
    private fun showLocalFileMenu(file: File) {
        val items = if (file.isDirectory) {
            arrayOf("Open", "Upload Folder", "Delete")
        } else {
            arrayOf("Upload", "Open", "Share", "Delete")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Open" -> handleLocalFileClick(file)
                    "Upload", "Upload Folder" -> uploadFile(file)
                    "Share" -> shareFile(file)
                    "Delete" -> deleteLocalFile(file)
                }
            }
            .show()
    }
    
    private fun showRemoteFileMenu(file: RemoteFileInfo) {
        val items = if (file.isDirectory) {
            arrayOf("Open", "Download Folder", "Rename", "Delete")
        } else {
            arrayOf("Download", "Rename", "Delete", "Properties")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Open" -> handleRemoteFileClick(file)
                    "Download", "Download Folder" -> downloadFile(file)
                    "Rename" -> renameRemoteFile(file)
                    "Delete" -> deleteRemoteFile(file)
                    "Properties" -> showFileProperties(file)
                }
            }
            .show()
    }
    
    private fun uploadFile(localFile: File) {
        if (!::sftpManager.isInitialized) return
        
        lifecycleScope.launch {
            try {
                val remotePath = "$currentRemotePath/${localFile.name}"
                
                val transferTask = sftpManager.uploadFile(
                    localFile = localFile,
                    remotePath = remotePath,
                    listener = object : TransferListener {
                        override fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {
                            runOnUiThread {
                                updateTransferProgress(transfer)
                            }
                        }
                        
                        override fun onCompleted(transfer: TransferTask, result: com.tabssh.sftp.TransferResult) {
                            runOnUiThread {
                                handleTransferCompleted(transfer, result)
                                loadRemoteDirectory(currentRemotePath) // Refresh remote files
                            }
                        }
                    }
                )
                
                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                
                Logger.i("SFTPActivity", "Started upload: ${localFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start upload", e)
                showToast("Upload failed: ${e.message}")
            }
        }
    }
    
    private fun downloadFile(remoteFile: RemoteFileInfo) {
        lifecycleScope.launch {
            try {
                val localFile = File(currentLocalPath, remoteFile.name)
                
                val transferTask = sftpManager.downloadFile(
                    remotePath = remoteFile.path,
                    localFile = localFile,
                    listener = object : TransferListener {
                        override fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {
                            runOnUiThread {
                                updateTransferProgress(transfer)
                            }
                        }
                        
                        override fun onCompleted(transfer: TransferTask, result: com.tabssh.sftp.TransferResult) {
                            runOnUiThread {
                                handleTransferCompleted(transfer, result)
                                loadLocalDirectory(currentLocalPath) // Refresh local files
                            }
                        }
                    }
                )
                
                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                
                Logger.i("SFTPActivity", "Started download: ${remoteFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start download", e)
                showToast("Download failed: ${e.message}")
            }
        }
    }
    
    private fun updateTransferProgress(transfer: TransferTask) {
        val index = activeTransfers.indexOfFirst { it.id == transfer.id }
        if (index >= 0) {
            transferAdapter.notifyItemChanged(index)
        }
    }
    
    private fun handleTransferCompleted(transfer: TransferTask, result: com.tabssh.sftp.TransferResult) {
        when (result) {
            is com.tabssh.sftp.TransferResult.Success -> {
                showToast("Transfer completed: ${transfer.getDisplayName()}")
            }
            is com.tabssh.sftp.TransferResult.Error -> {
                showToast("Transfer failed: ${result.message}")
            }
            is com.tabssh.sftp.TransferResult.Cancelled -> {
                showToast("Transfer cancelled")
            }
        }
        
        // Remove completed transfer from list
        val index = activeTransfers.indexOfFirst { it.id == transfer.id }
        if (index >= 0) {
            activeTransfers.removeAt(index)
            transferAdapter.notifyItemRemoved(index)
        }
    }
    
    private fun cancelTransfer(transfer: TransferTask) {
        transfer.cancel()
        sftpManager.cancelTransfer(transfer.id)
    }
    
    private fun pauseTransfer(transfer: TransferTask) {
        transfer.pause()
    }
    
    private fun resumeTransfer(transfer: TransferTask) {
        transfer.resume()
    }
    
    private fun renameRemoteFile(file: RemoteFileInfo) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val input = android.widget.EditText(this).apply {
            setText(file.name)
            selectAll()
        }
        
        builder.setTitle("Rename ${file.name}")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    performRemoteRename(file, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performRemoteRename(file: RemoteFileInfo, newName: String) {
        lifecycleScope.launch {
            try {
                val newPath = "${File(file.path).parent}/$newName"
                val success = sftpManager.renameRemoteFile(file.path, newPath)
                
                if (success) {
                    showToast("Renamed to $newName")
                    loadRemoteDirectory(currentRemotePath) // Refresh
                } else {
                    showToast("Failed to rename file")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error renaming file", e)
                showToast("Rename error: ${e.message}")
            }
        }
    }
    
    private fun deleteRemoteFile(file: RemoteFileInfo) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}")
            .setMessage("Are you sure you want to delete this ${if (file.isDirectory) "folder" else "file"}?")
            .setPositiveButton("Delete") { _, _ ->
                performRemoteDelete(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performRemoteDelete(file: RemoteFileInfo) {
        lifecycleScope.launch {
            try {
                val success = sftpManager.deleteRemoteFile(file.path, file.isDirectory)
                
                if (success) {
                    showToast("Deleted ${file.name}")
                    loadRemoteDirectory(currentRemotePath) // Refresh
                } else {
                    showToast("Failed to delete ${file.name}")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error deleting file", e)
                showToast("Delete error: ${e.message}")
            }
        }
    }
    
    private fun deleteLocalFile(file: File) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}")
            .setMessage("Are you sure you want to delete this ${if (file.isDirectory) "folder" else "file"}?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    showToast("Deleted ${file.name}")
                    loadLocalDirectory(currentLocalPath) // Refresh
                } else {
                    showToast("Failed to delete ${file.name}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun shareFile(file: File) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                    this@SFTPActivity,
                    "${packageName}.fileprovider",
                    file
                ))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
            
        } catch (e: Exception) {
            Logger.e("SFTPActivity", "Error sharing file", e)
            showToast("Failed to share file")
        }
    }
    
    private fun showFileProperties(file: RemoteFileInfo) {
        val message = buildString {
            appendLine("Name: ${file.name}")
            appendLine("Size: ${file.getFormattedSize()}")
            appendLine("Type: ${file.getFileType()}")
            appendLine("Permissions: ${file.permissions}")
            appendLine("Modified: ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.modifiedTime))}")
            if (file.isSymlink) {
                appendLine("Symbolic link: Yes")
            }
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Properties")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sftp_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_select_all_local -> {
                // Select all local files
                true
            }
            R.id.action_select_all_remote -> {
                // Select all remote files
                true
            }
            R.id.action_clear_transfers -> {
                clearCompletedTransfers()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun clearCompletedTransfers() {
        val completedCount = activeTransfers.count { 
            it.isCompleted() || it.hasError() || it.isCancelled()
        }
        
        activeTransfers.removeAll { 
            it.isCompleted() || it.hasError() || it.isCancelled()
        }
        
        transferAdapter.notifyDataSetChanged()
        showToast("Cleared $completedCount completed transfers")
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        if (::sftpManager.isInitialized) {
            sftpManager.cleanup()
        }
        
        Logger.d("SFTPActivity", "SFTP activity destroyed")
    }
}