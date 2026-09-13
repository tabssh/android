package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Build
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
import io.github.tabssh.utils.LocalFileSource
import io.github.tabssh.utils.StorageAccessHelper
import io.github.tabssh.utils.PathBreadcrumbSegment
import io.github.tabssh.utils.splitPathBreadcrumb
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import java.util.UUID

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

        // Matches PanesGridView.NARROW_WIDTH_DP — the codebase's existing
        // runtime-width-check threshold for switching to a single-pane layout.
        private const val NARROW_WIDTH_DP = 600

        fun createIntent(context: Context, connectionId: String): Intent {
            return Intent(context, SFTPActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_ID, connectionId)
            }
        }
    }
    
    // A drilled-into detail screen for one connection, not a main-tab
    // destination — matches every other Edit/detail activity (VncHostEdit,
    // ConnectionEdit, …). Without this override the base class's DRAWER
    // default applies, so the toolbar icon opened the nav drawer instead of
    // exiting the screen, with no other visible way to leave and close the
    // still-open SFTP connection.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

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

    // Storage Access Framework local browser. currentLocalSaf != null means
    // the local browser is showing a granted SAF tree instead of
    // currentLocalPath — java.io.File listing is unreliable under scoped
    // storage on Android 11+/API 30+, so this is the fix for "local files
    // not showing" once the user grants a folder via the "Browse folder…"
    // storage-picker option.
    private var localSafRoot: DocumentFile? = null
    private var currentLocalSaf: DocumentFile? = null

    private val openLocalSafTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) grantLocalSafRoot(uri)
        }

    // File-manager-class MANAGE_EXTERNAL_STORAGE grant (AI.md PART 2/5).
    // The legacy (API ≤ 28) runtime-permission grant delivers its result
    // through this launcher; the API ≥ 30 Settings flow has no such result
    // contract, so [awaitingFullAccessSettingsResult] plus onResume() covers
    // that path instead. SAF (openLocalSafTreeLauncher above) remains the
    // fallback for declined grants and the permanent API 29 case.
    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                currentLocalSaf = null
                loadLocalFiles()
            } else {
                Toast.makeText(this, R.string.storage_helper_denied_message, Toast.LENGTH_LONG).show()
                loadLocalFiles()
            }
        }

    private var awaitingFullAccessSettingsResult = false

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
    private val localFiles = mutableListOf<LocalFileSource>()
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
        setupResponsivePaneLayout()
        restoreLocalSafRoot()

        // Load initial directories
        loadLocalFiles()
        loadRemoteDirectory(currentRemotePath)
        
        Logger.i("SFTPActivity", "SFTP activity created")
    }

    /**
     * Re-checks [StorageAccessHelper.hasFullAccess] after a return from the
     * API ≥ 30 "manage all files" Settings screen — that flow has no
     * [androidx.activity.result.ActivityResultLauncher] result contract, so
     * this is the only place the grant/denial is observable.
     */
    override fun onResume() {
        super.onResume()
        if (awaitingFullAccessSettingsResult) {
            awaitingFullAccessSettingsResult = false
            if (StorageAccessHelper.hasFullAccess(this)) {
                currentLocalSaf = null
            } else {
                Toast.makeText(this, R.string.storage_helper_denied_message, Toast.LENGTH_LONG).show()
            }
            loadLocalFiles()
        }
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
        renderLocalPlainBreadcrumb(currentLocalPath)
        renderRemoteBreadcrumb(currentRemotePath)

        binding.btnLocalUp.setOnClickListener {
            navigateLocalUp()
        }
        
        binding.btnRemoteUp.setOnClickListener {
            navigateRemoteUp()
        }

        // Lets the user grant a SAF folder directly from the "no folder
        // selected" empty state, instead of having to find the overflow
        // menu's "Choose local storage" entry first.
        binding.emptyLocal.setOnClickListener {
            if (currentLocalSaf == null) showChooseLocalStorageDialog()
        }
    }

    /**
     * Matches [io.github.tabssh.ui.views.PanesGridView]'s existing runtime
     * width check rather than introducing the codebase's first
     * resource-qualifier layout split — no `layout-sw600dp` folder exists
     * anywhere in this project.
     */
    private fun isNarrowScreen(): Boolean {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return widthDp < NARROW_WIDTH_DP
    }

    /**
     * On narrow screens, shows a Local/Remote [TabLayout] and only one pane
     * at a time; on wide screens (tablet/landscape), both panes stay side by
     * side exactly as before and the tab strip is hidden.
     */
    private fun setupResponsivePaneLayout() {
        val narrow = isNarrowScreen()
        binding.localRemoteTabs.visibility = if (narrow) View.VISIBLE else View.GONE
        binding.paneDivider.visibility = if (narrow) View.GONE else View.VISIBLE

        if (!narrow) {
            binding.paneLocal.visibility = View.VISIBLE
            binding.paneRemote.visibility = View.VISIBLE
            return
        }

        applyNarrowPaneSelection(binding.localRemoteTabs.selectedTabPosition)
        binding.localRemoteTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                applyNarrowPaneSelection(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun applyNarrowPaneSelection(tabPosition: Int) {
        binding.paneLocal.visibility = if (tabPosition == 0) View.VISIBLE else View.GONE
        binding.paneRemote.visibility = if (tabPosition == 0) View.GONE else View.VISIBLE
    }

    /**
     * Resolves a theme attribute (e.g. `?attr/colorPrimary`) to its current
     * color at runtime, so breadcrumb chips can highlight the current
     * segment without hardcoding a color.
     */
    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /**
     * Inflates one [R.layout.item_breadcrumb_segment] chip per entry in
     * [segments] into [container], with a non-clickable "/" separator chip
     * between each pair. The last segment (the current directory) is
     * highlighted with `?attr/colorPrimary` and not clickable; every earlier
     * segment is `?attr/colorOnSurfaceVariant` and navigates to its [Pair.second]
     * path when tapped.
     */
    private fun renderBreadcrumb(container: LinearLayout, segments: List<Pair<String, () -> Unit>>) {
        container.removeAllViews()
        val inflater = layoutInflater
        val currentColor = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        val ancestorColor = resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        segments.forEachIndexed { index, (label, onClick) ->
            if (index > 0) {
                val separator = inflater.inflate(R.layout.item_breadcrumb_segment, container, false) as TextView
                separator.text = "/"
                separator.setTextColor(ancestorColor)
                container.addView(separator)
            }
            val chip = inflater.inflate(R.layout.item_breadcrumb_segment, container, false) as TextView
            chip.text = label
            val isCurrent = index == segments.lastIndex
            chip.setTextColor(if (isCurrent) currentColor else ancestorColor)
            if (!isCurrent) {
                chip.setOnClickListener { onClick() }
            } else {
                chip.setOnClickListener(null)
                chip.isClickable = false
            }
            container.addView(chip)
        }
    }

    private fun renderLocalPlainBreadcrumb(path: String) {
        val segments = splitPathBreadcrumb(path).map { segment: PathBreadcrumbSegment ->
            segment.label to { loadLocalDirectoryPlain(segment.path) }
        }
        renderBreadcrumb(binding.includeLocalBreadcrumb.breadcrumbSegments, segments)
    }

    private fun renderRemoteBreadcrumb(path: String) {
        val segments = splitPathBreadcrumb(path).map { segment: PathBreadcrumbSegment ->
            segment.label to { loadRemoteDirectory(segment.path) }
        }
        renderBreadcrumb(binding.includeRemoteBreadcrumb.breadcrumbSegments, segments)
    }

    /**
     * Walks `DocumentFile.parentFile` from [dir] up to (and including)
     * [localSafRoot], since a SAF tree has no filesystem path string to
     * split the way [splitPathBreadcrumb] does — only a live parent chain.
     * Returns root-first order for left-to-right rendering.
     */
    private fun buildSafTrail(dir: DocumentFile): List<DocumentFile> {
        val root = localSafRoot
        val trail = mutableListOf(dir)
        var current = dir
        while (root == null || current.uri != root.uri) {
            val parent = current.parentFile ?: break
            trail.add(parent)
            current = parent
        }
        return trail.asReversed()
    }

    private fun renderLocalSafBreadcrumb(dir: DocumentFile) {
        val trail = buildSafTrail(dir)
        val segments = trail.map { doc ->
            (doc.name ?: doc.uri.lastPathSegment ?: "/") to { loadLocalDirectorySaf(doc) }
        }
        renderBreadcrumb(binding.includeLocalBreadcrumb.breadcrumbSegments, segments)
    }

    /**
     * Restores a previously granted local-browser SAF tree (if any) so the
     * user doesn't have to re-pick "Browse folder…" every time this
     * activity is created. Silently falls back to the legacy File-path
     * browser if the permission grant is gone (revoked, uninstalled app
     * that owned the tree, etc.).
     *
     * If [StorageAccessHelper.hasFullAccess] is now granted (a user who
     * picked a SAF folder before upgrading to the full-access grant, or on a
     * device that already had it granted from another app-manager route),
     * the persisted SAF tree is dropped: it's a strictly narrower view than
     * the full device-rooted browser full access unlocks, so there's no
     * reason to keep defaulting to it once the better path is available.
     */
    private fun restoreLocalSafRoot() {
        val saved = tabSSHApp.preferencesManager.getSftpLocalSafTreeUri() ?: return
        if (StorageAccessHelper.hasFullAccess(this)) {
            tabSSHApp.preferencesManager.clearSftpLocalSafTreeUri()
            return
        }
        val doc = DocumentFile.fromTreeUri(this, Uri.parse(saved))
        if (doc != null && doc.canRead()) {
            localSafRoot = doc
            currentLocalSaf = doc
        } else {
            tabSSHApp.preferencesManager.clearSftpLocalSafTreeUri()
        }
    }

    /** Called once the user grants a tree via [openLocalSafTreeLauncher]. */
    private fun grantLocalSafRoot(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        tabSSHApp.preferencesManager.setSftpLocalSafTreeUri(uri.toString())
        val doc = DocumentFile.fromTreeUri(this, uri) ?: return
        localSafRoot = doc
        currentLocalSaf = doc
        loadLocalFiles()
    }

    /**
     * Dispatches to the SAF-tree browser or the fast plain-path browser.
     *
     * Order matters (AI.md PART 2/5 file-manager-class exception): an
     * explicitly-chosen SAF root always wins (the user asked for that
     * folder specifically); otherwise the fast [java.io.File] path is used
     * whenever [StorageAccessHelper.hasFullAccess] is granted; API 29 has no
     * full-filesystem-access API at all and permanently falls back to SAF;
     * everything else (API ≤ 28 without the legacy permission yet, or
     * API ≥ 30 without the MANAGE_EXTERNAL_STORAGE grant yet) requests full
     * access lazily, in context, right here.
     */
    private fun loadLocalFiles() {
        val saf = currentLocalSaf
        when {
            saf != null -> loadLocalDirectorySaf(saf)
            StorageAccessHelper.hasFullAccess(this) -> loadLocalDirectoryPlain(currentLocalPath)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> showLocalStorageNotGranted()
            else -> requestFullAccessThenLoad()
        }
    }

    /**
     * Shows the rationale dialog for [StorageAccessHelper], then leaves the
     * local pane in the same "not granted" empty state as the SAF-only path
     * below until the grant result comes back — either synchronously via
     * [legacyStoragePermissionLauncher] (API ≤ 28) or via [onResume] after a
     * Settings round trip (API ≥ 30, [awaitingFullAccessSettingsResult]).
     */
    private fun requestFullAccessThenLoad() {
        StorageAccessHelper.requestFullAccessIfNeeded(
            activity = this,
            legacyPermissionLauncher = legacyStoragePermissionLauncher,
            onAlreadyGranted = { loadLocalDirectoryPlain(currentLocalPath) },
            onSettingsLaunched = { awaitingFullAccessSettingsResult = true }
        )
        if (!StorageAccessHelper.hasFullAccess(this)) {
            showLocalStorageNotGranted()
        }
    }

    /** Shown when no SAF folder has been granted and full access isn't available yet. */
    private fun showLocalStorageNotGranted() {
        localFileAdapter.replaceAllWithDiff(
            items = localFiles,
            newItems = emptyList(),
            areItemsTheSame = { a, b -> a.id == b.id }
        )
        binding.textEmptyLocal.text = getString(R.string.sftp_local_no_access)
        binding.emptyLocal.visibility = View.VISIBLE
    }

    private fun loadLocalDirectoryPlain(path: String) {
        lifecycleScope.launch {
            try {
                val directory = File(path)
                if (directory.exists() && directory.isDirectory) {
                    val files = withContext(Dispatchers.IO) {
                        directory.listFiles()?.toList() ?: emptyList()
                    }

                    val sorted = files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                        .map { LocalFileSource.Plain(it) }

                    runOnUiThread {
                        localFileAdapter.replaceAllWithDiff(
                            items = localFiles,
                            newItems = sorted,
                            areItemsTheSame = { a, b -> a.id == b.id }
                        )
                        currentLocalPath = path
                        renderLocalPlainBreadcrumb(path)
                        binding.textEmptyLocal.text = getString(R.string.sftp_browser_empty_folder)
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

    private fun loadLocalDirectorySaf(dir: DocumentFile) {
        lifecycleScope.launch {
            try {
                val children = withContext(Dispatchers.IO) { dir.listFiles().toList() }
                val sorted = children.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name ?: "" })
                    .map { LocalFileSource.Saf(it) }

                runOnUiThread {
                    localFileAdapter.replaceAllWithDiff(
                        items = localFiles,
                        newItems = sorted,
                        areItemsTheSame = { a, b -> a.id == b.id }
                    )
                    currentLocalSaf = dir
                    renderLocalSafBreadcrumb(dir)
                    binding.textEmptyLocal.text = getString(R.string.sftp_browser_empty_folder)
                    binding.emptyLocal.visibility =
                        if (localFiles.isEmpty()) View.VISIBLE else View.GONE
                }

                Logger.d("SFTPActivity", "Loaded SAF local directory: ${dir.uri} (${children.size} items)")
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to load SAF local directory: ${dir.uri}", e)
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
                    currentRemotePath = path
                    renderRemoteBreadcrumb(path)
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
                showError(getString(R.string.sftp_error_load_remote_fmt, e.message.orEmpty()))
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
    
    private fun handleLocalFileClick(file: LocalFileSource) {
        if (file.isDirectory) {
            when (file) {
                is LocalFileSource.Plain -> loadLocalDirectoryPlain(file.file.absolutePath)
                is LocalFileSource.Saf -> loadLocalDirectorySaf(file.doc)
            }
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
    
    private fun selectLocalFile(file: LocalFileSource) {
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
    
    /**
     * SFTPManager/SCPClient's transfer APIs take a plain java.io.File —
     * rewriting them to be DocumentFile-aware is out of scope here. For a
     * SAF-mode entry, materialize it into a real temp file under [cacheDir]
     * first and upload that instead; the caller is responsible for calling
     * [cleanupMaterialized] once the upload completes.
     */
    private suspend fun materializeForUpload(entry: LocalFileSource): File = withContext(Dispatchers.IO) {
        when (entry) {
            is LocalFileSource.Plain -> entry.file
            is LocalFileSource.Saf -> {
                val tempRoot = File(cacheDir, "saf-upload/${UUID.randomUUID()}")
                tempRoot.mkdirs()
                val dest = File(tempRoot, entry.name)
                copySafToLocal(entry.doc, dest)
                dest
            }
        }
    }

    private fun copySafToLocal(doc: DocumentFile, dest: File) {
        if (doc.isDirectory) {
            dest.mkdirs()
            doc.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                copySafToLocal(child, File(dest, childName))
            }
        } else {
            contentResolver.openInputStream(doc.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** Deletes the temp materialized copy for a [LocalFileSource.Saf] entry; no-op for [LocalFileSource.Plain]. */
    private fun cleanupMaterialized(entry: LocalFileSource, materialized: File) {
        if (entry is LocalFileSource.Saf) {
            materialized.parentFile?.deleteRecursively()
        }
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
                    for (entry in selectedFiles) {
                        val remotePath = currentRemotePath + "/" + entry.name
                        val materialized = materializeForUpload(entry)
                        try {
                            if (entry.isDirectory) {
                                sftpManager.uploadDirectory(localDir = materialized, remoteDir = remotePath)
                            } else {
                                sftpManager.uploadFile(localFile = materialized, remotePath = remotePath)
                            }
                        } finally {
                            cleanupMaterialized(entry, materialized)
                        }
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
                for (entry in selected) {
                    val remote = "$currentRemotePath/${entry.name}"
                    val materialized = materializeForUpload(entry)
                    try {
                        val ok = if (entry.isDirectory) {
                            client.uploadDirectory(materialized, remote, null)
                        } else {
                            client.uploadFile(materialized, remote, null)
                        }
                        if (ok) okCount++ else failCount++
                    } finally {
                        cleanupMaterialized(entry, materialized)
                    }
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
                    val saf = currentLocalSaf
                    for (file in selectedFiles) {
                        if (saf != null) {
                            val temp = File(cacheDir, "saf-download/${UUID.randomUUID()}/${file.name}")
                            temp.parentFile?.mkdirs()
                            if (file.isDirectory) {
                                sftpManager.downloadDirectory(remotePath = file.path, localDir = temp)
                                copyLocalDirectoryIntoSaf(temp, saf, file.name)
                            } else {
                                sftpManager.downloadFile(remotePath = file.path, localFile = temp)
                                copyLocalFileIntoSaf(temp, saf, file.name)
                            }
                            temp.parentFile?.deleteRecursively()
                        } else {
                            val localFile = File(currentLocalPath, file.name)
                            if (file.isDirectory) {
                                sftpManager.downloadDirectory(remotePath = file.path, localDir = localFile)
                            } else {
                                sftpManager.downloadFile(remotePath = file.path, localFile = localFile)
                            }
                        }
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
                loadLocalFiles()
            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Download failed", e)
                showError(getString(R.string.sftp_toast_download_failed_fmt, e.message.orEmpty()))
            }
        }
    }

    /** Copies a downloaded temp file into a granted SAF tree, overwriting an existing entry of the same name. */
    private fun copyLocalFileIntoSaf(src: File, parentDoc: DocumentFile, name: String) {
        val extension = name.substringAfterLast('.', "")
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val target = parentDoc.findFile(name) ?: parentDoc.createFile(mime, name) ?: return
        contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            src.inputStream().use { input -> input.copyTo(output) }
        }
    }

    /**
     * Recursively copies a downloaded temp directory tree into a granted
     * SAF tree, mirroring [copySafToLocal]'s upload-side reverse. Creates
     * (or reuses) the named child directory under [parentDoc], then walks
     * [src]'s children, dispatching each to [copyLocalDirectoryIntoSaf]
     * (subdirectories) or [copyLocalFileIntoSaf] (files).
     */
    private fun copyLocalDirectoryIntoSaf(src: File, parentDoc: DocumentFile, name: String) {
        val targetDir = parentDoc.findFile(name)?.takeIf { it.isDirectory }
            ?: parentDoc.createDirectory(name) ?: return
        src.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                copyLocalDirectoryIntoSaf(child, targetDir, child.name)
            } else {
                copyLocalFileIntoSaf(child, targetDir, child.name)
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
        val saf = currentLocalSaf
        if (saf != null) {
            val root = localSafRoot
            if (root != null && saf.uri == root.uri) return
            val parent = saf.parentFile
            if (parent != null) loadLocalDirectorySaf(parent)
            return
        }
        val parent = File(currentLocalPath).parentFile
        if (parent != null && parent.canRead()) {
            loadLocalDirectoryPlain(parent.absolutePath)
        }
    }
    
    private fun navigateRemoteUp() {
        if (currentRemotePath != "/") {
            val parent = File(currentRemotePath).parent ?: "/"
            loadRemoteDirectory(parent)
        }
    }
    
    private fun refreshDirectories() {
        loadLocalFiles()
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

    private fun showLocalFileMenu(file: LocalFileSource) {
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
    
    private fun uploadFile(entry: LocalFileSource) {
        if (!::sftpManager.isInitialized) return

        lifecycleScope.launch {
            try {
                val remotePath = "$currentRemotePath/${entry.name}"
                val entryName = entry.name
                val materialized = materializeForUpload(entry)

                val transferTask = withContext(Dispatchers.IO) {
                    val listener = object : TransferListener {
                        override fun onProgress(transfer: TransferTask, bytesTransferred: Long, totalBytes: Long) {
                            runOnUiThread {
                                updateTransferProgress(transfer)

                                // Update notification with progress
                                io.github.tabssh.utils.NotificationHelper.showFileTransferProgress(
                                    this@SFTPActivity,
                                    transfer.id.hashCode(),
                                    entryName,
                                    bytesTransferred,
                                    totalBytes,
                                    isUpload = true
                                )
                            }
                        }

                        override fun onCompleted(transfer: TransferTask, result: io.github.tabssh.sftp.TransferResult) {
                            cleanupMaterialized(entry, materialized)
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
                                            entryName,
                                            isUpload = true
                                        )
                                    }
                                    is io.github.tabssh.sftp.TransferResult.Error -> {
                                        io.github.tabssh.utils.NotificationHelper.showConnectionError(
                                            this@SFTPActivity,
                                            entryName,
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

                    if (materialized.isDirectory) {
                        sftpManager.uploadDirectory(
                            localDir = materialized,
                            remoteDir = remotePath,
                            listener = listener
                        )
                    } else {
                        sftpManager.uploadFile(
                            localFile = materialized,
                            remotePath = remotePath,
                            listener = listener
                        )
                    }
                }

                activeTransfers.add(transferTask)
                transferAdapter.notifyItemInserted(activeTransfers.size - 1)
                refreshTransferCardVisibility()

                Logger.i("SFTPActivity", "Started upload: $entryName")

            } catch (e: Exception) {
                Logger.e("SFTPActivity", "Failed to start upload", e)
                showError(getString(R.string.sftp_upload_failed_fmt, e.message.orEmpty()))
            }
        }
    }
    
    private fun downloadFile(remoteFile: RemoteFileInfo) {
        lifecycleScope.launch {
            try {
                val saf = currentLocalSaf
                val localFile = if (saf != null) {
                    File(cacheDir, "saf-download/${UUID.randomUUID()}/${remoteFile.name}").also {
                        it.parentFile?.mkdirs()
                    }
                } else {
                    File(currentLocalPath, remoteFile.name)
                }

                val transferTask = withContext(Dispatchers.IO) {
                    val listener = object : TransferListener {
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
                                if (saf != null && result is io.github.tabssh.sftp.TransferResult.Success) {
                                    if (remoteFile.isDirectory) {
                                        copyLocalDirectoryIntoSaf(localFile, saf, remoteFile.name)
                                    } else {
                                        copyLocalFileIntoSaf(localFile, saf, remoteFile.name)
                                    }
                                    localFile.parentFile?.deleteRecursively()
                                }
                                runOnUiThread {
                                    handleTransferCompleted(transfer, result)
                                    // Refresh local files
                                    loadLocalFiles()

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

                    if (remoteFile.isDirectory) {
                        sftpManager.downloadDirectory(
                            remotePath = remoteFile.path,
                            localDir = localFile,
                            listener = listener
                        )
                    } else {
                        sftpManager.downloadFile(
                            remotePath = remoteFile.path,
                            localFile = localFile,
                            listener = listener
                        )
                    }
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
    
    private fun deleteLocalFile(file: LocalFileSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sftp_delete_title_fmt, file.name))
            .setMessage(deleteConfirmMessage(file.isDirectory))
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = when (file) {
                    is LocalFileSource.Plain -> file.file.delete()
                    is LocalFileSource.Saf -> file.doc.delete()
                }
                if (deleted) {
                    showToast(getString(R.string.sftp_deleted_fmt, file.name))
                    // Refresh
                    loadLocalFiles()
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
    
    private fun shareFile(file: LocalFileSource) {
        try {
            val shareUri = when (file) {
                is LocalFileSource.Plain -> androidx.core.content.FileProvider.getUriForFile(
                    this@SFTPActivity,
                    "${packageName}.fileprovider",
                    file.file
                )
                is LocalFileSource.Saf -> file.doc.uri
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
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
            R.id.action_choose_local_storage -> {
                showChooseLocalStorageDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Lets the user switch the local file browser's root between internal
     * storage, any inserted SD card, and an arbitrary folder granted via the
     * Storage Access Framework.
     *
     * The plain-path entries (internal storage, SD card) require
     * [StorageAccessHelper.hasFullAccess] to be granted: without it, scoped
     * storage makes `java.io.File.listFiles()` silently return nothing for
     * paths outside this app's own sandbox on API 30+, and there is no
     * legacy permission granted yet below API 30 either. Offering an entry
     * that always renders empty is worse than not offering it, so those
     * entries are only shown once full access is actually granted; otherwise
     * only the SAF "Browse folder…" option — which actually lists files
     * correctly without that permission — is shown.
     */
    private fun showChooseLocalStorageDialog() {
        val roots = if (StorageAccessHelper.hasFullAccess(this)) externalStorageRoots() else emptyList()
        val labels = (roots.map { it.first } + getString(R.string.sftp_storage_browse_folder)).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sftp_choose_storage_title)
            .setItems(labels) { _, which ->
                if (which == roots.size) {
                    // Android's own document-tree picker (DocumentsUI, or the
                    // OEM equivalent) hard-refuses granting the top-level
                    // "Internal storage"/"This device" root or the "Download"
                    // folder — "Can't use this folder. Please choose another
                    // folder." with no way for this app to detect or bypass
                    // it (uri callback just looks like a plain cancel). Warn
                    // up front so the user navigates into a real subfolder
                    // instead of retrying the same blocked root repeatedly.
                    Toast.makeText(this, R.string.sftp_saf_picker_hint, Toast.LENGTH_LONG).show()
                    openLocalSafTreeLauncher.launch(null)
                } else {
                    currentLocalSaf = null
                    currentLocalPath = roots[which].second
                    loadLocalFiles()
                }
            }
            .show()
    }

    /**
     * Enumerates this device's external storage volumes (internal "primary"
     * storage plus any inserted SD card) as (label, absolute root path)
     * pairs. Derived from [Context.getExternalFilesDirs] — index 0 is always
     * the primary volume — whose app-private directory always ends in
     * "/Android/data/<package>/files"; stripping that fixed suffix recovers
     * the volume root without depending on StorageVolume.getDirectory(),
     * which only exists on API 30+ while this app's minSdk is 24.
     */
    private fun externalStorageRoots(): List<Pair<String, String>> {
        val suffix = "/Android/data/$packageName/files"
        return getExternalFilesDirs(null).filterNotNull().mapIndexedNotNull { index, dir ->
            val path = dir.absolutePath
            if (!path.endsWith(suffix)) return@mapIndexedNotNull null
            val root = path.removeSuffix(suffix)
            val label = if (index == 0) {
                getString(R.string.sftp_storage_internal)
            } else {
                getString(R.string.sftp_storage_sd_card_fmt, File(root).name)
            }
            label to root
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
