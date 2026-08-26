package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivitySftpBinding
import io.github.tabssh.sftp.RemoteFileInfo
import io.github.tabssh.sftp.SFTPManager
import io.github.tabssh.sftp.TransferTask
import io.github.tabssh.sftp.TransferListener
import io.github.tabssh.ui.adapters.FileAdapter
import io.github.tabssh.ui.adapters.typeLabel
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.ui.adapters.TransferAdapter
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.tabssh.utils.showError
import io.github.tabssh.utils.announceAccessibility
import io.github.tabssh.utils.tabSSHApp

/**
 * SFTP file browser activity with dual-pane interface
 * Provides comprehensive file management capabilities
 */
class SFTPActivity : TabSSHActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"

        /**
         * Optional starting remote directory — set when a file:// terminal
         * link's "Open in SFTP" action navigates straight to that path
         * instead of the default "/".
         */
        const val EXTRA_INITIAL_REMOTE_PATH = "initial_remote_path"

        // Ceiling for the in-app text editor. Anything larger is a download,
        // not an edit — the editor materialises the whole file in memory.
        private const val MAX_INLINE_EDIT_BYTES = 1_048_576L

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
    // Default to external storage
    private var currentLocalPath = "/storage/emulated/0"
    private var currentRemotePath = "/"

    /**
     * Wave 8.5 — multi-connection SFTP tabs. Each [SftpTab] holds the
     * SFTPManager + last remembered remote path for one connection. Tap a
     * chip in the strip to swap which manager [sftpManager] points at. The
     * 16+ existing references stay valid because we just reseat the field.
     */
    private data class SftpTab(
        val connectionId: String,
        val displayName: String,
        val sftpManager: SFTPManager,
        var rememberedRemotePath: String = "/"
    )

    private val sftpTabs = mutableListOf<SftpTab>()
    private var activeSftpTabIndex: Int = -1
    
    // File lists
    private val localFiles = mutableListOf<File>()
    private val remoteFiles = mutableListOf<RemoteFileInfo>()
    private val activeTransfers = mutableListOf<TransferTask>()

    // file:// "Open" round trip (download → external viewer/editor → upload
    // back on change). Constructed here, before onCreate finishes, so it can
    // observe this activity's lifecycle for the post-edit resume check.
    private val remoteFileOpener = io.github.tabssh.sftp.RemoteFileOpener(this) {
        tabSSHApp.preferencesManager.getFileOpenSizeLimitMb()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySftpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = tabSSHApp

        intent.getStringExtra(EXTRA_INITIAL_REMOTE_PATH)?.let { initialPath ->
            currentRemotePath = initialPath
        }

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
        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.apply {
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
                // Reuse an already-open terminal session when there is one.
                // Opened from the connections list the host is usually NOT
                // connected yet — the old code treated that as a fatal
                // "Connection not found" and closed the browser immediately.
                val active = withContext(Dispatchers.IO) {
                    app.sshSessionManager.getConnection(connectionId)
                }
                val connection = active ?: run {
                    val profile = withContext(Dispatchers.IO) {
                        app.database.connectionDao().getConnectionById(connectionId)
                    }
                    if (profile == null) {
                        Logger.e("SFTPActivity", "Connection profile not found: $connectionId")
                        showError(getString(R.string.sftp_error_connection_gone))
                        finish()
                        return@launch
                    }
                    Logger.event("SFTPActivity", "Opening SSH session for SFTP: ${profile.getDisplayName()}")
                    withContext(Dispatchers.IO) {
                        app.sshSessionManager.connectToServer(profile)
                    } ?: run {
                        Logger.e("SFTPActivity", "SSH connect failed for SFTP: ${profile.getDisplayName()}")
                        showError(getString(R.string.sftp_error_connect_failed_fmt, profile.getDisplayName()))
                        finish()
                        return@launch
                    }
                }

                sftpManager = SFTPManager(connection)
                val connected = withContext(Dispatchers.IO) { sftpManager.connect() }

                if (connected) {
                    Logger.i("SFTPActivity", "SFTP connected successfully")
                    // Wave 8.5 — register this initial connection as the first tab.
                    val displayName = try {
                        withContext(Dispatchers.IO) {
                            app.database.connectionDao().getConnectionById(connectionId)?.getDisplayName()
                        } ?: connectionId.take(8)
                    } catch (_: Exception) { connectionId.take(8) }
                    sftpTabs.add(SftpTab(connectionId, displayName, sftpManager, currentRemotePath))
                    activeSftpTabIndex = 0
                    rebuildSftpTabsStrip()
                    loadRemoteDirectory(currentRemotePath)
                } else {
                    Logger.e("SFTPActivity", "Failed to connect SFTP")
                    showError(getString(R.string.sftp_error_connect_sftp_failed))
                    finish()
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error setting up SFTP", e)
                showError(getString(R.string.sftp_error_setup_fmt, e.message.orEmpty()))
                finish()
            }
        }
    }

    /**
     * Wave 8.5 — Render the chip strip from [sftpTabs]. Includes a "+" chip
     * at the end to add another connection. Tap a chip to swap [sftpManager]
     * and reload the remote pane.
     */
    private fun rebuildSftpTabsStrip() {
        val strip = findViewById<com.google.android.material.chip.ChipGroup>(R.id.sftp_tabs_strip)
        strip.removeAllViews()
        sftpTabs.forEachIndexed { index, tab ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = tab.displayName
                isCheckable = true
                isChecked = (index == activeSftpTabIndex)
                isCloseIconVisible = sftpTabs.size > 1
                setOnClickListener { switchToSftpTab(index) }
                setOnCloseIconClickListener { closeSftpTab(index) }
            }
            strip.addView(chip)
        }
        val addChip = com.google.android.material.chip.Chip(this).apply {
            setText(R.string.sftp_tab_add_chip)
            isCheckable = false
            setOnClickListener { showAddSftpTabPicker() }
        }
        strip.addView(addChip)
    }

    private fun switchToSftpTab(index: Int) {
        if (index == activeSftpTabIndex) return
        if (index !in sftpTabs.indices) return
        // Save current path on the outgoing tab.
        if (activeSftpTabIndex in sftpTabs.indices) {
            sftpTabs[activeSftpTabIndex].rememberedRemotePath = currentRemotePath
        }
        activeSftpTabIndex = index
        val tab = sftpTabs[index]
        sftpManager = tab.sftpManager
        currentRemotePath = tab.rememberedRemotePath
        rebuildSftpTabsStrip()
        loadRemoteDirectory(currentRemotePath)
    }

    private fun closeSftpTab(index: Int) {
        if (sftpTabs.size <= 1) return
        val tab = sftpTabs[index]
        try { tab.sftpManager.disconnect() } catch (e: Exception) { Logger.w("SFTPActivity", "tab disconnect: ${e.message}") }
        sftpTabs.removeAt(index)
        if (activeSftpTabIndex >= sftpTabs.size) activeSftpTabIndex = sftpTabs.size - 1
        // Switch to the new active tab.
        val tgt = sftpTabs[activeSftpTabIndex]
        sftpManager = tgt.sftpManager
        currentRemotePath = tgt.rememberedRemotePath
        rebuildSftpTabsStrip()
        loadRemoteDirectory(currentRemotePath)
    }

    private fun showAddSftpTabPicker() {
        lifecycleScope.launch {
            // Any saved connection is offerable — [openNewSftpTab] dials one that
            // has no live session instead of refusing it.
            val candidates = try {
                withContext(Dispatchers.IO) { app.database.connectionDao().getRecentConnections(50) }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (candidates.isEmpty()) {
                    showError(
                        getString(R.string.sftp_no_saved_connections_message),
                        getString(R.string.dashboard_no_saved_connections)
                    )
                    return@runOnUiThread
                }
                val labels = candidates.map { it.getDisplayName() }.toTypedArray()
                MaterialAlertDialogBuilder(this@SFTPActivity)
                    .setTitle(R.string.sftp_add_tab_title)
                    .setItems(labels) { _, which -> openNewSftpTab(candidates[which]) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openNewSftpTab(profile: io.github.tabssh.storage.database.entities.ConnectionProfile) {
        // Already a tab for this connection? Just switch.
        val existingIdx = sftpTabs.indexOfFirst { it.connectionId == profile.id }
        if (existingIdx >= 0) {
            switchToSftpTab(existingIdx)
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { app.sshSessionManager.getConnection(profile.id) }
            val conn = existing ?: withContext(Dispatchers.IO) {
                app.sshSessionManager.connectToServer(profile)
            }
            if (conn == null) {
                runOnUiThread {
                    showError(getString(R.string.sftp_error_connect_failed_fmt, profile.getDisplayName()))
                }
                return@launch
            }
            val mgr = SFTPManager(conn)
            val ok = withContext(Dispatchers.IO) { mgr.connect() }
            runOnUiThread {
                if (!ok) {
                    showError(getString(R.string.sftp_error_open_tab_failed_fmt, profile.getDisplayName()))
                    return@runOnUiThread
                }
                if (activeSftpTabIndex in sftpTabs.indices) {
                    sftpTabs[activeSftpTabIndex].rememberedRemotePath = currentRemotePath
                }
                sftpTabs.add(SftpTab(profile.id, profile.getDisplayName(), mgr))
                activeSftpTabIndex = sftpTabs.size - 1
                sftpManager = mgr
                currentRemotePath = "/"
                rebuildSftpTabsStrip()
                loadRemoteDirectory(currentRemotePath)
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
                    val files = withContext(Dispatchers.IO) {
                        directory.listFiles()?.toList() ?: emptyList()
                    }

                    val sorted = files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })

                    runOnUiThread {
                        localFileAdapter.replaceAllWithDiff(
                            items = localFiles,
                            newItems = sorted,
                            areItemsTheSame = { a, b -> a.absolutePath == b.absolutePath }
                        )
                        binding.textLocalPath.text = path
                        currentLocalPath = path
                        binding.emptyLocal.visibility =
                            if (localFiles.isEmpty()) View.VISIBLE else View.GONE
                    }

                    Logger.d("SFTPActivity", "Loaded local directory: $path (${files.size} items)")
                }
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to load local directory: $path", e)
                showError(getString(R.string.sftp_error_load_local))
            }
        }
    }
    
    private fun loadRemoteDirectory(path: String) {
        if (!::sftpManager.isInitialized) return

        binding.loadingRemote.visibility = View.VISIBLE
        binding.emptyRemote.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) { sftpManager.listRemoteFiles(path) }

                runOnUiThread {
                    remoteFileAdapter.replaceAllWithDiff(
                        items = remoteFiles,
                        newItems = files,
                        areItemsTheSame = { a, b -> a.name == b.name }
                    )
                    binding.textRemotePath.text = path
                    currentRemotePath = path
                    binding.loadingRemote.visibility = View.GONE
                    binding.emptyRemote.visibility =
                        if (remoteFiles.isEmpty()) View.VISIBLE else View.GONE
                }

                Logger.d("SFTPActivity", "Loaded remote directory: $path (${files.size} items)")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to load remote directory: $path", e)
                runOnUiThread {
                    binding.loadingRemote.visibility = View.GONE
                }
                showError(getString(R.string.sftp_error_load_remote))
            }
        }
    }

    /**
     * Hide the transfer-progress card when nothing is queued, surface
     * it when at least one transfer is in flight. The card was always
     * visible at 200dp before, eating the bottom of the screen even on
     * a fresh open with zero activity.
     */
    private fun refreshTransferCardVisibility() {
        binding.transferCard.visibility =
            if (activeTransfers.isEmpty()) View.GONE else View.VISIBLE
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
        binding.btnUpload.text = getString(R.string.sftp_btn_upload_file_fmt, file.name)
        
        Logger.d("SFTPActivity", "Selected local file: ${file.name}")
    }
    
    private fun selectRemoteFile(file: RemoteFileInfo) {
        // Highlight selected file and enable download button  
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = getString(R.string.sftp_btn_download_file_fmt, file.name)
        
        Logger.d("SFTPActivity", "Selected remote file: ${file.name}")
    }
    
    private fun uploadSelectedFiles() {
        if (!::sftpManager.isInitialized) {
            showToast(getString(R.string.sftp_not_connected))
            return
        }
        val selectedFiles = localFileAdapter.getSelectedFiles()

        if (selectedFiles.isEmpty()) {
            showToast(getString(R.string.sftp_no_files_selected))
            return
        }

        lifecycleScope.launch {
            try {
                val successCount = withContext(Dispatchers.IO) {
                    var count = 0
                    for (file in selectedFiles) {
                        if (file.isDirectory) continue
                        sftpManager.uploadFile(
                            localFile = file,
                            remotePath = currentRemotePath + "/" + file.name
                        )
                        count++
                    }
                    count
                }
                showToast(
                    resources.getQuantityString(
                        R.plurals.sftp_uploaded_count,
                        successCount,
                        Format.count(successCount)
                    )
                )
                localFileAdapter.clearSelection()
                loadRemoteDirectory(currentRemotePath)
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Upload failed", e)
                showError(getString(R.string.sftp_toast_upload_failed_fmt, e.message.orEmpty()))
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
        val selected = if (::localFileAdapter.isInitialized) localFileAdapter.getSelectedFiles() else emptyList()
        if (selected.isEmpty()) {
            showToast(getString(R.string.sftp_no_files_selected))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sftp_scp_title)
            .setMessage(R.string.sftp_scp_message)
            .setPositiveButton(R.string.sftp_scp_confirm) { _, _ -> uploadSelectedFilesViaScp() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uploadSelectedFilesViaScp() {
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return
        val ssh = app.sshSessionManager.getConnection(connectionId) ?: run {
            showError(getString(R.string.sftp_error_connection_inactive))
            return
        }
        val client = io.github.tabssh.sftp.SCPClient(ssh)
        val selected = localFileAdapter.getSelectedFiles()
        if (selected.isEmpty()) return
        lifecycleScope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) {
                var okCount = 0
                var failCount = 0
                for (file in selected) {
                    if (file.isDirectory) continue
                    val remote = "$currentRemotePath/${file.name}"
                    if (client.uploadFile(file, remote, null)) okCount++ else failCount++
                }
                okCount to failCount
            }
            runOnUiThread {
                showToast(getString(R.string.sftp_scp_result_fmt, Format.count(ok), Format.count(fail)))
                localFileAdapter.clearSelection()
                loadRemoteDirectory(currentRemotePath)
            }
        }
    }

    private fun downloadSelectedFiles() {
        if (!::sftpManager.isInitialized) {
            showToast(getString(R.string.sftp_not_connected))
            return
        }
        val selectedFiles = remoteFileAdapter.getSelectedRemoteFiles()

        if (selectedFiles.isEmpty()) {
            showToast(getString(R.string.sftp_no_files_selected))
            return
        }

        lifecycleScope.launch {
            try {
                val successCount = withContext(Dispatchers.IO) {
                    var count = 0
                    for (file in selectedFiles) {
                        if (file.isDirectory) continue
                        val localFile = File(currentLocalPath, file.name)
                        sftpManager.downloadFile(
                            remotePath = file.path,
                            localFile = localFile
                        )
                        count++
                    }
                    count
                }
                showToast(
                    resources.getQuantityString(
                        R.plurals.sftp_downloaded_count,
                        successCount,
                        Format.count(successCount)
                    )
                )
                remoteFileAdapter.clearSelection()
                loadLocalDirectory(currentLocalPath)
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Download failed", e)
                showError(getString(R.string.sftp_toast_download_failed_fmt, e.message.orEmpty()))
            }
        }
    }
    
    private fun showCreateFolderDialog() {
        val builder = MaterialAlertDialogBuilder(this)
        val form = DialogFields.form(this)
        val input = DialogFields.addText(form, getString(R.string.sftp_create_folder_hint))

        builder.setTitle(R.string.sftp_create_folder_title)
            .setView(form.root)
            .setPositiveButton(R.string.sftp_create_folder_confirm) { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    createRemoteFolder(folderName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createRemoteFolder(folderName: String) {
        if (!::sftpManager.isInitialized) return
        
        lifecycleScope.launch {
            try {
                val newPath = "$currentRemotePath/$folderName"
                val created = withContext(Dispatchers.IO) { sftpManager.createRemoteDirectory(newPath) }
                
                if (created) {
                    showToast(getString(R.string.sftp_folder_created_fmt, folderName))
                    // Refresh
                    loadRemoteDirectory(currentRemotePath)
                } else {
                    showError(getString(R.string.sftp_error_create_folder))
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error creating folder", e)
                showError(getString(R.string.sftp_error_create_folder_fmt, e.message.orEmpty()))
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
            transferAdapter.replaceAllWithDiff(
                items = activeTransfers,
                newItems = sftpManager.getActiveTransfers(),
                areItemsTheSame = { a, b -> a === b }
            )
        }
    }
    
    /**
     * Shows a long-press menu built from label-resource / action pairs and
     * dispatches on the tapped position, so the visible labels can be
     * translated without breaking which action runs.
     */
    private fun showFileActionMenu(title: String, entries: List<Pair<Int, () -> Unit>>) {
        val labels = entries.map { getString(it.first) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, which -> entries.getOrNull(which)?.second?.invoke() }
            .show()
    }

    private fun showLocalFileMenu(file: File) {
        val entries: List<Pair<Int, () -> Unit>> = if (file.isDirectory) {
            listOf(
                R.string.sftp_menu_open to { handleLocalFileClick(file) },
                R.string.sftp_menu_upload_folder to { uploadFile(file) },
                R.string.delete to { deleteLocalFile(file) }
            )
        } else {
            listOf(
                R.string.upload_file to { uploadFile(file) },
                R.string.sftp_menu_open to { handleLocalFileClick(file) },
                R.string.share to { shareFile(file) },
                R.string.delete to { deleteLocalFile(file) }
            )
        }
        showFileActionMenu(file.name, entries)
    }

    private fun showRemoteFileMenu(file: RemoteFileInfo) {
        // Wave 1.7 + 1.8 — added "Edit" (text-ish files) and "Permissions"
        // (chmod) to the per-file long-press menu.
        val entries: List<Pair<Int, () -> Unit>> = if (file.isDirectory) {
            listOf(
                R.string.sftp_menu_open to { handleRemoteFileClick(file) },
                R.string.sftp_menu_download_folder to { downloadFile(file) },
                R.string.rename_file to { renameRemoteFile(file) },
                R.string.sftp_menu_permissions to { showPermissionsDialog(file) },
                R.string.delete to { deleteRemoteFile(file) }
            )
        } else {
            // "Open" downloads and hands the file to an external app (the
            // file:// round trip — RemoteFileOpener). "Open / Edit" is the
            // separate in-app text editor for small (<1 MiB) text files.
            listOf(
                R.string.sftp_menu_open to { openRemoteFileExternally(file) },
                R.string.sftp_menu_open_edit to { openOrEditRemoteFile(file) },
                R.string.download_file to { downloadFile(file) },
                R.string.rename_file to { renameRemoteFile(file) },
                R.string.sftp_menu_permissions to { showPermissionsDialog(file) },
                R.string.file_properties to { showFileProperties(file) },
                R.string.delete to { deleteRemoteFile(file) }
            )
        }
        showFileActionMenu(file.name, entries)
    }

    /**
     * file:// "Open" round trip for a remote file selected in the SFTP
     * browser — download to cacheDir/file-links/, then hand off to whatever
     * app the device resolves for its MIME type (see RemoteFileOpener).
     */
    private fun openRemoteFileExternally(file: RemoteFileInfo) {
        if (!::sftpManager.isInitialized) {
            showToast(getString(R.string.sftp_not_connected))
            return
        }
        remoteFileOpener.open(sftpManager, file.path, file.name)
    }

    /**
     * Wave 1.8 — chmod dialog. rwx checkboxes for user/group/other plus a
     * live numeric (octal) display. Apply via SFTPManager.changeRemotePermissions().
     */
    private fun showPermissionsDialog(file: RemoteFileInfo) {
        val current = file.permissions
        // file.permissions might be a string like "rwxr-xr--" or "0644" — try octal first
        val initialMode = parseInitialMode(current, file.isDirectory)

        // Build the dialog programmatically to avoid a new layout file.
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val mode = intArrayOf(initialMode)

        fun makeRow(labelRes: Int, shift: Int): android.widget.LinearLayout {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(android.widget.TextView(this).apply {
                setText(labelRes)
                width = 220
            })
            val flags = listOf(
                4 to R.string.sftp_perm_flag_read,
                2 to R.string.sftp_perm_flag_write,
                1 to R.string.sftp_perm_flag_execute
            )
            for ((bit, flagRes) in flags) {
                val cb = android.widget.CheckBox(this).apply {
                    setText(flagRes)
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

        container.addView(makeRow(R.string.sftp_perm_owner, 6))
        container.addView(makeRow(R.string.sftp_perm_group, 3))
        container.addView(makeRow(R.string.sftp_perm_other, 0))
        container.addView(android.widget.TextView(this).apply {
            id = android.R.id.text1
            text = getString(R.string.sftp_perm_mode_fmt, octalString(mode[0]))
            textSize = 16f
            setPadding(0, 24, 0, 0)
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sftp_permissions_title_fmt, file.name))
            .setView(container)
            .setPositiveButton(R.string.terminal_apply) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { sftpManager.changeRemotePermissions(file.path, mode[0]) }
                    runOnUiThread {
                        Toast.makeText(
                            this@SFTPActivity,
                            if (ok) {
                                getString(R.string.sftp_permissions_set_fmt, octalString(mode[0]))
                            } else {
                                getString(R.string.sftp_permissions_failed)
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                        loadRemoteDirectory(currentRemotePath)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updatePermissionsLabel(container: android.view.ViewGroup, mode: Int) {
        container.findViewById<android.widget.TextView>(android.R.id.text1)?.text =
            getString(R.string.sftp_perm_mode_fmt, octalString(mode))
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
        // 0o755
        val defaultDir = "755".toInt(8)
        // 0o644
        val defaultFile = "644".toInt(8)
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
        if (file.size > MAX_INLINE_EDIT_BYTES) {
            Toast.makeText(
                this,
                getString(
                    R.string.sftp_error_file_too_large_fmt,
                    Format.size(this, MAX_INLINE_EDIT_BYTES)
                ),
                Toast.LENGTH_LONG
            ).show()
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

                val transferTask = withContext(Dispatchers.IO) {
                    sftpManager.uploadFile(
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
                                    // Refresh remote files
                                    loadRemoteDirectory(currentRemotePath)

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
                                                getString(R.string.sftp_upload_failed_fmt, result.message)
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
                }

                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                refreshTransferCardVisibility()

                Logger.i("SFTPActivity", "Started upload: ${localFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start upload", e)
                showError(getString(R.string.sftp_upload_failed_fmt, e.message.orEmpty()))
            }
        }
    }
    
    private fun downloadFile(remoteFile: RemoteFileInfo) {
        lifecycleScope.launch {
            try {
                val localFile = File(currentLocalPath, remoteFile.name)

                val transferTask = withContext(Dispatchers.IO) {
                    sftpManager.downloadFile(
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
                                    // Refresh local files
                                    loadLocalDirectory(currentLocalPath)

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
                                                getString(R.string.sftp_download_failed_fmt, result.message)
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
                }

                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                refreshTransferCardVisibility()

                Logger.i("SFTPActivity", "Started download: ${remoteFile.name}")
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start download", e)
                showError(getString(R.string.sftp_download_failed_fmt, e.message.orEmpty()))
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
        // A Toast is not reliably spoken by TalkBack (custom Toast
        // views and rapid successive transfers can drop it), so a transfer
        // outcome gets an explicit announcement alongside the existing
        // Toast/dialog feedback.
        when (result) {
            is io.github.tabssh.sftp.TransferResult.Success -> {
                val message = getString(R.string.sftp_transfer_completed_fmt, transfer.getDisplayName(this@SFTPActivity))
                showToast(message)
                announceAccessibility(message)
            }
            is io.github.tabssh.sftp.TransferResult.Error -> {
                showError(getString(R.string.sftp_transfer_failed_fmt, result.message))
                announceAccessibility(getString(R.string.sftp_transfer_failed_fmt, result.message))
            }
            is io.github.tabssh.sftp.TransferResult.Cancelled -> {
                showToast(getString(R.string.sftp_transfer_cancelled))
                announceAccessibility(getString(R.string.sftp_transfer_cancelled))
            }
        }
        
        // Remove completed transfer from list
        val index = activeTransfers.indexOfFirst { it.id == transfer.id }
        if (index >= 0) {
            activeTransfers.removeAt(index)
            transferAdapter.notifyItemRemoved(index)
            refreshTransferCardVisibility()
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
        val builder = MaterialAlertDialogBuilder(this)
        val form = DialogFields.form(this)
        val input = DialogFields.addText(
            form, getString(R.string.container_rename_hint), initial = file.name
        )
        input.selectAll()

        builder.setTitle(getString(R.string.sftp_rename_title_fmt, file.name))
            .setView(form.root)
            .setPositiveButton(R.string.rename_file) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    performRemoteRename(file, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun performRemoteRename(file: RemoteFileInfo, newName: String) {
        lifecycleScope.launch {
            try {
                val newPath = "${File(file.path).parent}/$newName"
                val success = withContext(Dispatchers.IO) { sftpManager.renameRemoteFile(file.path, newPath) }
                
                if (success) {
                    showToast(getString(R.string.sftp_renamed_fmt, newName))
                    // Refresh
                    loadRemoteDirectory(currentRemotePath)
                } else {
                    showError(getString(R.string.sftp_error_rename))
                }
                
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error renaming file", e)
                showError(getString(R.string.sftp_error_rename_fmt, e.message.orEmpty()))
            }
        }
    }
    
    private fun deleteRemoteFile(file: RemoteFileInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sftp_delete_title_fmt, file.name))
            .setMessage(deleteConfirmMessage(file.isDirectory))
            .setPositiveButton(R.string.delete) { _, _ ->
                performRemoteDelete(file)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun performRemoteDelete(file: RemoteFileInfo) {
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) { sftpManager.deleteRemoteFile(file.path, file.isDirectory) }
                
                if (success) {
                    showToast(getString(R.string.sftp_deleted_fmt, file.name))
                    // Refresh
                    loadRemoteDirectory(currentRemotePath)
                } else {
                    showError(getString(R.string.sftp_error_delete_fmt, file.name))
                }

            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Error deleting file", e)
                showError(getString(R.string.sftp_error_delete_generic_fmt, e.message.orEmpty()))
            }
        }
    }
    
    private fun deleteLocalFile(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sftp_delete_title_fmt, file.name))
            .setMessage(deleteConfirmMessage(file.isDirectory))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (file.delete()) {
                    showToast(getString(R.string.sftp_deleted_fmt, file.name))
                    // Refresh
                    loadLocalDirectory(currentLocalPath)
                } else {
                    showError(getString(R.string.sftp_error_delete_fmt, file.name))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Folder and file wordings are separate resources so translations can inflect both. */
    private fun deleteConfirmMessage(isDirectory: Boolean): String = getString(
        if (isDirectory) R.string.sftp_delete_message_folder else R.string.sftp_delete_message_file
    )
    
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
            
            startActivity(
                Intent.createChooser(intent, getString(R.string.sftp_share_chooser_fmt, file.name))
            )

        } catch (e: Exception) {
            Logger.e("SFTPActivity", "Error sharing file", e)
            showError(getString(R.string.sftp_error_share))
        }
    }
    
    private fun showFileProperties(file: RemoteFileInfo) {
        val modified = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(file.modifiedTime))
        val message = buildString {
            appendLine(getString(R.string.sftp_props_name_fmt, file.name))
            appendLine(getString(R.string.sftp_props_size_fmt, Format.size(this@SFTPActivity, file.size)))
            appendLine(getString(R.string.sftp_props_type_fmt, file.typeLabel(this@SFTPActivity)))
            appendLine(getString(R.string.sftp_props_permissions_fmt, file.permissions))
            appendLine(getString(R.string.sftp_props_modified_fmt, modified))
            if (file.isSymlink) {
                appendLine(getString(R.string.sftp_props_symlink))
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_properties)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.sftp_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all_local -> {
                localFileAdapter.selectAllLocal()
                val count = localFileAdapter.getSelectedFiles().size
                showToast(
                    if (count > 0) {
                        resources.getQuantityString(
                            R.plurals.sftp_selected_local_count, count, Format.count(count)
                        )
                    } else {
                        getString(R.string.sftp_no_files_to_select)
                    }
                )
                true
            }
            R.id.action_select_all_remote -> {
                remoteFileAdapter.selectAllRemote()
                val count = remoteFileAdapter.getSelectedRemoteFiles().size
                showToast(
                    if (count > 0) {
                        resources.getQuantityString(
                            R.plurals.sftp_selected_remote_count, count, Format.count(count)
                        )
                    } else {
                        getString(R.string.sftp_no_files_to_select)
                    }
                )
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
        // Keep the removed entries so a Snackbar can put them back — an
        // outright confirm dialog would interrupt a routine cleanup tap,
        // undo is the lighter-weight safety net for a non-destructive-to-data
        // action (nothing on disk changes, only this list's contents).
        val removed = activeTransfers.filter {
            it.isCompleted() || it.hasError() || it.isCancelled()
        }
        if (removed.isEmpty()) {
            return
        }
        val removedPositions = removed.map { activeTransfers.indexOf(it) to it }

        // Build the filtered list explicitly so DiffUtil can compute the
        // exact removals instead of a wholesale invalidate.
        val remaining = activeTransfers.filterNot {
            it.isCompleted() || it.hasError() || it.isCancelled()
        }
        transferAdapter.replaceAllWithDiff(
            items = activeTransfers,
            newItems = remaining,
            areItemsTheSame = { a, b -> a === b }
        )
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            resources.getQuantityString(
                R.plurals.sftp_cleared_transfers,
                removed.size,
                Format.count(removed.size)
            ),
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction(R.string.sftp_clear_transfers_undo) {
            val restored = activeTransfers.toMutableList()
            for ((position, transfer) in removedPositions.sortedBy { it.first }) {
                val insertAt = position.coerceIn(0, restored.size)
                restored.add(insertAt, transfer)
            }
            transferAdapter.replaceAllWithDiff(
                items = activeTransfers,
                newItems = restored,
                areItemsTheSame = { a, b -> a === b }
            )
        }.show()
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()

        // Wave 8.5 — disconnect every tab's manager (the active one is the
        // same instance as `sftpManager`, so guard against double-cleanup).
        sftpTabs.forEach { tab ->
            try { tab.sftpManager.cleanup() } catch (e: Exception) {
                Logger.w("SFTPActivity", "tab cleanup: ${e.message}")
            }
        }
        sftpTabs.clear()

        Logger.d("SFTPActivity", "SFTP activity destroyed")
    }
}
