package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
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
import io.github.tabssh.utils.showError

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
                    showError("Failed to connect SFTP", "Error")
                    finish()
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error setting up SFTP", e)
                showError("SFTP setup error: ${e.message}", "Error")
                finish()
            }
        }
    }
    
    private fun setupFileAdapters() {
        // Local file adapter
        localFileAdapter = FileAdapter()
        localFileAdapter.setLocalFiles(
            files = localFiles,
            onFileClick = { file -> handleLocalFileClick(file) },
            onFileLongClick = { file -> showLocalFileMenu(file) }
        )

        binding.recyclerLocalFiles.apply {
            layoutManager = LinearLayoutManager(this@SFTPActivity)
            adapter = localFileAdapter
        }

        // Remote file adapter
        remoteFileAdapter = FileAdapter()
        remoteFileAdapter.setRemoteFiles(
            files = remoteFiles,
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

        // Wave 1.9 — long-press the Upload button to switch to SCP mode.
        // SCP is the fallback for legacy / minimal servers without an
        // SFTP subsystem. SFTP remains the default; users opt into SCP
        // explicitly per upload.
        binding.btnUpload.setOnLongClickListener {
            askScpModeAndUpload()
            true
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
                showError("Failed to load local directory", "Error")
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
                showError("Failed to load remote directory", "Error")
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
        // Multi-file upload: Get selected files from FileAdapter
        val selectedFiles = localFileAdapter?.getSelectedFiles() ?: emptyList()
        
        if (selectedFiles.isEmpty()) {
            showToast("No files selected. Long-press files to select them.")
            return
        }
        
        lifecycleScope.launch {
            try {
                var successCount = 0
                for (file in selectedFiles) {
                    if (file.isDirectory) continue
                    
                    // Use correct API: uploadFile(localFile, remotePath)
                    sftpManager?.uploadFile(
                        localFile = file,
                        remotePath = currentRemotePath + "/" + file.name
                    )
                    successCount++
                }
                showToast("✅ Uploaded $successCount file(s)")
                localFileAdapter?.clearSelection()
                loadRemoteDirectory(currentRemotePath)
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Upload failed", e)
                showError("❌ Upload failed: ${e.message}", "Error")
            }
        }
    }

    /**
     * Wave 1.9 — SCP fallback upload. Long-pressing the Upload button
     * routes selected files through SCPClient instead of SFTP. Useful for
     * ancient / minimal servers without an SFTP subsystem (network gear,
     * stripped-down embedded systems).
     */
    private fun askScpModeAndUpload() {
        val selected = localFileAdapter?.getSelectedFiles() ?: emptyList()
        if (selected.isEmpty()) {
            showToast("No files selected. Long-press files to select them.")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Upload via SCP")
            .setMessage("SCP is the legacy fallback for servers without SFTP. " +
                "Default Upload uses SFTP (recommended). Continue with SCP?")
            .setPositiveButton("SCP") { _, _ -> uploadSelectedFilesViaScp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadSelectedFilesViaScp() {
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return
        val ssh = app.sshSessionManager.getConnection(connectionId) ?: run {
            showError("Connection not active", "Error")
            return
        }
        val client = io.github.tabssh.sftp.SCPClient(ssh)
        val selected = localFileAdapter?.getSelectedFiles() ?: return
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            for (file in selected) {
                if (file.isDirectory) continue
                val remote = "$currentRemotePath/${file.name}"
                if (client.uploadFile(file, remote, null)) ok++ else fail++
            }
            runOnUiThread {
                showToast("SCP: $ok ok, $fail failed")
                localFileAdapter?.clearSelection()
                loadRemoteDirectory(currentRemotePath)
            }
        }
    }

    private fun downloadSelectedFiles() {
        // Multi-file download: Get selected files from FileAdapter
        val selectedFiles = remoteFileAdapter?.getSelectedRemoteFiles() ?: emptyList()
        
        if (selectedFiles.isEmpty()) {
            showToast("No files selected. Long-press files to select them.")
            return
        }
        
        lifecycleScope.launch {
            try {
                var successCount = 0
                for (file in selectedFiles) {
                    if (file.isDirectory) continue
                    
                    val localFile = File(currentLocalPath, file.name)
                    // Use correct API: downloadFile(remotePath, localFile)
                    sftpManager?.downloadFile(
                        remotePath = file.path,
                        localFile = localFile
                    )
                    successCount++
                }
                showToast("✅ Downloaded $successCount file(s)")
                remoteFileAdapter?.clearSelection()
                loadLocalDirectory(currentLocalPath)
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Download failed", e)
                showError("❌ Download failed: ${e.message}", "Error")
            }
        }
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
                    showError("Failed to create folder", "Error")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error creating folder", e)
                showError("Error creating folder: ${e.message}", "Error")
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
                if (which < 0 || which >= items.size) return@setItems
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
        // Wave 1.7 + 1.8 — added "Edit" (text-ish files) and "Permissions"
        // (chmod) to the per-file long-press menu.
        val items = if (file.isDirectory) {
            arrayOf("Open", "Download Folder", "Rename", "Permissions…", "Delete")
        } else {
            arrayOf("Open / Edit", "Download", "Rename", "Permissions…", "Properties", "Delete")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                if (which < 0 || which >= items.size) return@setItems
                when (items[which]) {
                    "Open" -> handleRemoteFileClick(file)
                    "Open / Edit" -> openOrEditRemoteFile(file)
                    "Download", "Download Folder" -> downloadFile(file)
                    "Rename" -> renameRemoteFile(file)
                    "Permissions…" -> showPermissionsDialog(file)
                    "Delete" -> deleteRemoteFile(file)
                    "Properties" -> showFileProperties(file)
                }
            }
            .show()
    }

    /**
     * Wave 1.8 — chmod dialog. rwx checkboxes for user/group/other plus a
     * live numeric (octal) display. Apply via SFTPManager.changeRemotePermissions().
     */
    private fun showPermissionsDialog(file: RemoteFileInfo) {
        val current = file.permissions
        // file.permissions might be a string like "rwxr-xr--" or "0644" — try octal first
        val initialMode = parseInitialMode(current, file.isDirectory)

        val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        // Build the dialog programmatically to avoid a new layout file.
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val mode = intArrayOf(initialMode)

        fun makeRow(label: String, shift: Int): android.widget.LinearLayout {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(android.widget.TextView(this).apply {
                text = label
                width = 220
            })
            for ((bit, name) in listOf(4 to "r", 2 to "w", 1 to "x")) {
                val cb = android.widget.CheckBox(this).apply {
                    text = name
                    isChecked = (mode[0] shr shift) and bit != 0
                    setOnCheckedChangeListener { _, isChecked ->
                        mode[0] = if (isChecked) mode[0] or (bit shl shift)
                                  else mode[0] and (bit shl shift).inv()
                        updatePermissionsLabel(container, mode[0])
                    }
                }
                row.addView(cb)
            }
            return row
        }

        container.addView(makeRow("Owner", 6))
        container.addView(makeRow("Group", 3))
        container.addView(makeRow("Other", 0))
        container.addView(android.widget.TextView(this).apply {
            id = android.R.id.text1
            text = "Mode: ${octalString(mode[0])}"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permissions — ${file.name}")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                lifecycleScope.launch {
                    val ok = sftpManager.changeRemotePermissions(file.path, mode[0])
                    runOnUiThread {
                        Toast.makeText(
                            this@SFTPActivity,
                            if (ok) "Permissions set to ${octalString(mode[0])}"
                            else "chmod failed",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadRemoteDirectory(currentRemotePath)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePermissionsLabel(container: android.view.ViewGroup, mode: Int) {
        container.findViewById<android.widget.TextView>(android.R.id.text1)?.text =
            "Mode: ${octalString(mode)}"
    }

    private fun octalString(mode: Int): String =
        String.format("%04o (%s)", mode, modeToRwx(mode))

    private fun modeToRwx(mode: Int): String {
        val sb = StringBuilder()
        for (shift in intArrayOf(6, 3, 0)) {
            sb.append(if ((mode shr shift) and 4 != 0) 'r' else '-')
            sb.append(if ((mode shr shift) and 2 != 0) 'w' else '-')
            sb.append(if ((mode shr shift) and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }

    private fun parseInitialMode(perms: String?, isDirectory: Boolean): Int {
        // Kotlin has no octal literal; use String.toInt(8) for clarity.
        val defaultDir = "755".toInt(8)   // 0o755
        val defaultFile = "644".toInt(8)  // 0o644
        if (perms.isNullOrBlank()) return if (isDirectory) defaultDir else defaultFile
        perms.trim().toIntOrNull(8)?.let { return it }
        val s = if (perms.length == 10) perms.substring(1) else perms
        if (s.length != 9) return if (isDirectory) defaultDir else defaultFile
        var mode = 0
        for (i in 0 until 3) {
            val base = i * 3
            if (s[base]     == 'r') mode = mode or (4 shl ((2 - i) * 3))
            if (s[base + 1] == 'w') mode = mode or (2 shl ((2 - i) * 3))
            if (s[base + 2] == 'x') mode = mode or (1 shl ((2 - i) * 3))
        }
        return mode
    }

    /**
     * Wave 1.7 — Open / edit a remote text file. Downloads to cache,
     * launches a simple text editor activity that writes back via SFTP.
     * For now: only files under 1 MiB. Binary detection skipped — opening
     * a binary file shows a warning but still proceeds (read-only).
     */
    private fun openOrEditRemoteFile(file: RemoteFileInfo) {
        if (file.size > 1_048_576) {
            Toast.makeText(this, "File too large (>1 MiB) — download instead", Toast.LENGTH_LONG).show()
            return
        }
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return
        val editorIntent = android.content.Intent(this, RemoteFileEditorActivity::class.java).apply {
            putExtra(RemoteFileEditorActivity.EXTRA_CONNECTION_ID, connectionId)
            putExtra(RemoteFileEditorActivity.EXTRA_REMOTE_PATH, file.path)
            putExtra(RemoteFileEditorActivity.EXTRA_FILE_NAME, file.name)
        }
        startActivity(editorIntent)
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
                                
                                // Update notification with progress
                                io.github.tabssh.utils.NotificationHelper.showFileTransferProgress(
                                    this@SFTPActivity,
                                    transfer.id.hashCode(),
                                    localFile.name,
                                    bytesTransferred,
                                    totalBytes,
                                    isUpload = true
                                )
                            }
                        }
                        
                        override fun onCompleted(transfer: TransferTask, result: io.github.tabssh.sftp.TransferResult) {
                            runOnUiThread {
                                handleTransferCompleted(transfer, result)
                                loadRemoteDirectory(currentRemotePath) // Refresh remote files
                                
                                // Show completion notification
                                when (result) {
                                    is io.github.tabssh.sftp.TransferResult.Success -> {
                                        io.github.tabssh.utils.NotificationHelper.showFileTransferComplete(
                                            this@SFTPActivity,
                                            transfer.id.hashCode(),
                                            localFile.name,
                                            isUpload = true
                                        )
                                    }
                                    is io.github.tabssh.sftp.TransferResult.Error -> {
                                        io.github.tabssh.utils.NotificationHelper.showConnectionError(
                                            this@SFTPActivity,
                                            localFile.name,
                                            "Upload failed: ${result.message}"
                                        )
                                    }
                                    is io.github.tabssh.sftp.TransferResult.Cancelled -> {
                                        // Cancel notification silently
                                        io.github.tabssh.utils.NotificationHelper.cancelNotification(
                                            this@SFTPActivity,
                                            transfer.id.hashCode()
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
                
                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                
                Logger.i("SFTPActivity", "Started upload: ${localFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start upload", e)
                showError("Upload failed: ${e.message}", "Error")
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
                                
                                // Update notification with progress
                                io.github.tabssh.utils.NotificationHelper.showFileTransferProgress(
                                    this@SFTPActivity,
                                    transfer.id.hashCode(),
                                    remoteFile.name,
                                    bytesTransferred,
                                    totalBytes,
                                    isUpload = false
                                )
                            }
                        }
                        
                        override fun onCompleted(transfer: TransferTask, result: io.github.tabssh.sftp.TransferResult) {
                            runOnUiThread {
                                handleTransferCompleted(transfer, result)
                                loadLocalDirectory(currentLocalPath) // Refresh local files
                                
                                // Show completion notification
                                when (result) {
                                    is io.github.tabssh.sftp.TransferResult.Success -> {
                                        io.github.tabssh.utils.NotificationHelper.showFileTransferComplete(
                                            this@SFTPActivity,
                                            transfer.id.hashCode(),
                                            remoteFile.name,
                                            isUpload = false
                                        )
                                    }
                                    is io.github.tabssh.sftp.TransferResult.Error -> {
                                        io.github.tabssh.utils.NotificationHelper.showConnectionError(
                                            this@SFTPActivity,
                                            remoteFile.name,
                                            "Download failed: ${result.message}"
                                        )
                                    }
                                    is io.github.tabssh.sftp.TransferResult.Cancelled -> {
                                        // Cancel notification silently
                                        io.github.tabssh.utils.NotificationHelper.cancelNotification(
                                            this@SFTPActivity,
                                            transfer.id.hashCode()
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
                
                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                
                Logger.i("SFTPActivity", "Started download: ${remoteFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start download", e)
                showError("Download failed: ${e.message}", "Error")
            }
        }
    }
    
    private fun updateTransferProgress(transfer: TransferTask) {
        val index = activeTransfers.indexOfFirst { it.id == transfer.id }
        if (index >= 0) {
            transferAdapter.notifyItemChanged(index)
        }
    }
    
    private fun handleTransferCompleted(transfer: TransferTask, result: io.github.tabssh.sftp.TransferResult) {
        when (result) {
            is io.github.tabssh.sftp.TransferResult.Success -> {
                showToast("Transfer completed: ${transfer.getDisplayName()}")
            }
            is io.github.tabssh.sftp.TransferResult.Error -> {
                showError("Transfer failed: ${result.message}", "Error")
            }
            is io.github.tabssh.sftp.TransferResult.Cancelled -> {
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
                    showError("Failed to rename file", "Error")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error renaming file", e)
                showError("Rename error: ${e.message}", "Error")
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
                    showError("Failed to delete ${file.name}", "Error")
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error deleting file", e)
                showError("Delete error: ${e.message}", "Error")
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
                    showError("Failed to delete ${file.name}", "Error")
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
            showError("Failed to share file", "Error")
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
