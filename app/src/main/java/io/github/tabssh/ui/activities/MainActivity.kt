package io.github.tabssh.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.backup.BackupManager
import io.github.tabssh.databinding.ActivityMainBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.ui.adapters.GroupedConnectionAdapter
import io.github.tabssh.ui.models.ConnectionListItem
import io.github.tabssh.ui.utils.ConnectionListBuilder
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main activity showing connection list and quick connect
 * Updated with full database integration and RecyclerView
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val REQUEST_CODE_IMPORT_BACKUP = 1002
        private const val REQUEST_CODE_EXPORT_BACKUP = 1003
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: TabSSHApplication
    private lateinit var groupedConnectionAdapter: GroupedConnectionAdapter
    private lateinit var frequentlyUsedAdapter: ConnectionAdapter
    private lateinit var ungroupedAdapter: ConnectionAdapter
    private lateinit var backupManager: BackupManager

    private val connections = mutableListOf<ConnectionProfile>()
    private val allConnections = mutableListOf<ConnectionProfile>()
    private val allGroups = mutableListOf<ConnectionGroup>()
    private val frequentlyUsedConnections = mutableListOf<ConnectionProfile>()
    private val ungroupedConnections = mutableListOf<ConnectionProfile>()
    private val displayItems = mutableListOf<ConnectionListItem>()
    private val groupExpansionState = mutableMapOf<String, Boolean>()
    private var ungroupedExpanded: Boolean = true
    private var currentSearchQuery: String = ""
    private var groupingEnabled: Boolean = true

    // Activity result launchers for import/export
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBackupFromUri(it) }
    }

    private val importSSHConfigLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSSHConfigFromUri(it) }
    }

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { exportBackupToUri(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Logger.d("MainActivity", "onCreate")

        app = application as TabSSHApplication
        backupManager = BackupManager(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFrequentlyUsedRecyclerView()
        setupQuickConnect()
        setupFAB()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Load data from database
        loadGroups()
        loadConnections()
        loadFrequentlyUsedConnections()

        Logger.i("MainActivity", "MainActivity created successfully")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Logger.i("MainActivity", "Notification permission granted")
                } else {
                    Logger.w("MainActivity", "Notification permission denied")
                    showToast("Notifications disabled. You won't see connection status updates.")
                }
            }
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "TabSSH"
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Setup SearchView
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView

        searchView?.apply {
            queryHint = "Search connections..."
            maxWidth = Integer.MAX_VALUE // Full width when expanded

            // Set up query listener
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    // Search when user presses search button
                    query?.let {
                        currentSearchQuery = it
                        filterConnections(it)
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    // Real-time search as user types
                    currentSearchQuery = newText ?: ""
                    filterConnections(currentSearchQuery)
                    return true
                }
            })

            // Restore search query if it exists
            if (currentSearchQuery.isNotBlank()) {
                searchItem.expandActionView()
                setQuery(currentSearchQuery, false)
            }

            // Clear search when SearchView is collapsed
            setOnCloseListener {
                currentSearchQuery = ""
                filterConnections("")
                false
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_create_group -> {
                val intent = Intent(this, GroupManagementActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_manage_snippets -> {
                val intent = Intent(this, SnippetManagerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_cluster_commands -> {
                val intent = Intent(this, ClusterCommandActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_proxmox -> {
                val intent = Intent(this, ProxmoxManagerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_xcpng -> {
                val intent = Intent(this, XCPngManagerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_vmware -> {
                val intent = Intent(this, VMwareManagerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_manage_identities -> {
                val intent = Intent(this, IdentityManagementActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_manage_keys -> {
                val intent = Intent(this, KeyManagementActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_import_connections -> {
                importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                true
            }
            R.id.action_import_ssh_config -> {
                importSSHConfigLauncher.launch(arrayOf("text/plain", "*/*"))
                true
            }
            R.id.action_export_connections -> {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "tabssh_backup_$timestamp.zip"
                exportBackupLauncher.launch(filename)
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About TabSSH")
            .setMessage("TabSSH - Advanced SSH Client for Android\n\nVersion: $versionName\n\nFeatures:\n• Multi-tab SSH sessions\n• SSH key authentication\n• SFTP file transfer\n• Port forwarding\n• Custom themes\n• Session persistence\n\nOpen source and privacy-focused.")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun setupRecyclerView() {
        // Setup Groups RecyclerView (with expand/collapse)
        groupedConnectionAdapter = GroupedConnectionAdapter(
            items = displayItems,
            onConnectionClick = { profile -> connectToProfile(profile) },
            onConnectionLongClick = { profile -> showConnectionMenu(profile) },
            onConnectionEdit = { profile -> editConnection(profile) },
            onConnectionDelete = { profile -> deleteConnection(profile) },
            onGroupClick = { groupHeader -> toggleGroupExpansion(groupHeader) },
            onGroupLongClick = { groupHeader -> showGroupMenu(groupHeader) }
        )

        binding.recyclerGroups.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = groupedConnectionAdapter
        }
        
        // Setup Ungrouped RecyclerView
        ungroupedAdapter = ConnectionAdapter(
            connections = ungroupedConnections,
            onConnectionClick = { profile -> connectToProfile(profile) },
            onConnectionLongClick = { profile -> showConnectionMenu(profile) },
            onConnectionEdit = { profile -> editConnection(profile) },
            onConnectionDelete = { profile -> deleteConnection(profile) }
        )

        binding.recyclerConnections.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ungroupedAdapter
        }
    }

    private fun setupFrequentlyUsedRecyclerView() {
        frequentlyUsedAdapter = ConnectionAdapter(
            connections = frequentlyUsedConnections,
            onConnectionClick = { profile -> connectToProfile(profile) },
            onConnectionLongClick = { profile -> showConnectionMenu(profile) },
            onConnectionEdit = { profile -> editConnection(profile) },
            onConnectionDelete = { profile -> deleteConnection(profile) }
        )
        
        binding.recyclerFrequentlyUsed.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = frequentlyUsedAdapter
        }
    }
    
    private fun setupQuickConnect() {
        binding.btnQuickConnect.setOnClickListener {
            performQuickConnect()
        }
        
        // Auto-fill username from preferences
        val defaultUsername = app.preferencesManager.getDefaultUsername()
        if (defaultUsername.isNotBlank()) {
            binding.editQuickUsername.setText(defaultUsername)
        }
    }
    
    private fun setupFAB() {
        binding.fabAddConnection.setOnClickListener {
            val intent = ConnectionEditActivity.createIntent(this)
            startActivity(intent)
        }
        
        binding.btnAddConnection.setOnClickListener {
            val intent = ConnectionEditActivity.createIntent(this)
            startActivity(intent)
        }
    }
    
    private fun loadConnections() {
        lifecycleScope.launch {
            try {
                // Load all connections
                app.database.connectionDao().getAllConnections().collect { connectionList ->
                    allConnections.clear()
                    allConnections.addAll(connectionList)

                    // Load top 5 frequently used
                    val frequentList = app.database.connectionDao().getFrequentlyUsedConnections(limit = 5)
                    frequentlyUsedConnections.clear()
                    frequentlyUsedConnections.addAll(frequentList)

                    // Rebuild display list
                    rebuildDisplayList()

                    Logger.d("MainActivity", "Loaded ${connectionList.size} connections, ${frequentList.size} frequent")
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to load connections", e)
                showToast("Failed to load connections")
            }
        }
        
        // Load ungrouped connections separately
        lifecycleScope.launch {
            try {
                app.database.connectionDao().getUngroupedConnections().collect { ungroupedList ->
                    ungroupedConnections.clear()
                    ungroupedConnections.addAll(ungroupedList)
                    
                    // Update ungrouped adapter
                    runOnUiThread {
                        ungroupedAdapter.notifyDataSetChanged()
                        updateSectionVisibility()
                    }
                    
                    Logger.d("MainActivity", "Loaded ${ungroupedList.size} ungrouped connections")
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to load ungrouped connections", e)
            }
        }
    }

    private fun loadGroups() {
        lifecycleScope.launch {
            try {
                app.database.connectionGroupDao().getAllGroups().collect { groupList ->
                    allGroups.clear()
                    allGroups.addAll(groupList)

                    // Rebuild display list
                    rebuildDisplayList()

                    Logger.d("MainActivity", "Loaded ${groupList.size} groups")
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to load groups", e)
                showToast("Failed to load groups")
            }
        }
    }

    /**
     * Rebuild the display list with groups and connections
     */
    private fun rebuildDisplayList() {
        // Filter connections if search is active
        val filteredConnections = if (currentSearchQuery.isBlank()) {
            allConnections
        } else {
            allConnections.filter { connection ->
                connection.name.contains(currentSearchQuery, ignoreCase = true) ||
                connection.host.contains(currentSearchQuery, ignoreCase = true) ||
                connection.username.contains(currentSearchQuery, ignoreCase = true) ||
                connection.getDisplayName().contains(currentSearchQuery, ignoreCase = true)
            }
        }

        // Apply sort
        val sortedConnections = applySortToList(filteredConnections)
        
        // Filter to ONLY grouped connections (for groups section)
        val groupedOnly = sortedConnections.filter { it.groupId != null }

        // Build display list for GROUPS section (no ungrouped here!)
        val items = if (currentSearchQuery.isBlank() && groupingEnabled) {
            // Grouped view - ONLY show grouped connections
            ConnectionListBuilder.buildGroupedList(
                groups = allGroups,
                connections = groupedOnly,  // Changed: only grouped connections
                groupExpansionState = groupExpansionState,
                showUngrouped = false,  // Changed: don't show ungrouped in groups section
                ungroupedExpanded = false
            )
        } else {
            // Flat view (for search results or when grouping disabled)
            ConnectionListBuilder.buildFlatList(sortedConnections)
        }

        displayItems.clear()
        displayItems.addAll(items)
        
        // Update frequently used adapter
        frequentlyUsedAdapter.notifyDataSetChanged()

        runOnUiThread {
            groupedConnectionAdapter.notifyDataSetChanged()
            updateSectionVisibility()

            Logger.d("MainActivity", "Rebuilt: ${frequentlyUsedConnections.size} frequent, ${displayItems.size} grouped items, ${ungroupedConnections.size} ungrouped")
        }
    }

    /**
     * Filter connections based on search query (legacy method for compatibility)
     */
    private fun filterConnections(query: String) {
        currentSearchQuery = query
        rebuildDisplayList()
    }
    
    /**
     * Update visibility of the 3 sections based on data
     */
    private fun updateSectionVisibility() {
        // Show frequently used section only if there are frequent connections
        binding.cardFrequentlyUsed.visibility = if (frequentlyUsedConnections.isNotEmpty()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Show groups section only if there are groups
        binding.cardGroups.visibility = if (allGroups.isNotEmpty()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Update ungrouped section visibility (RecyclerView and empty state)
        if (ungroupedConnections.isEmpty()) {
            binding.recyclerConnections.visibility = android.view.View.GONE
            binding.textEmptyConnections.visibility = android.view.View.VISIBLE
        } else {
            binding.recyclerConnections.visibility = android.view.View.VISIBLE
            binding.textEmptyConnections.visibility = android.view.View.GONE
        }
    }

    /**
     * Show sort options dialog
     */
    private fun showSortDialog() {
        val sortOptions = arrayOf(
            "Name (A-Z)",
            "Name (Z-A)",
            "Host (A-Z)",
            "Host (Z-A)",
            "Most Used",
            "Least Used",
            "Recently Connected",
            "Oldest Connected"
        )

        val currentSort = app.preferencesManager.getString("connection_sort", "name_asc")
        val currentIndex = when (currentSort) {
            "name_asc" -> 0
            "name_desc" -> 1
            "host_asc" -> 2
            "host_desc" -> 3
            "most_used" -> 4
            "least_used" -> 5
            "recent" -> 6
            "oldest" -> 7
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Sort Connections")
            .setSingleChoiceItems(sortOptions, currentIndex) { dialog, which ->
                val sortType = when (which) {
                    0 -> "name_asc"
                    1 -> "name_desc"
                    2 -> "host_asc"
                    3 -> "host_desc"
                    4 -> "most_used"
                    5 -> "least_used"
                    6 -> "recent"
                    7 -> "oldest"
                    else -> "name_asc"
                }

                // Save preference
                app.preferencesManager.setString("connection_sort", sortType)

                // Re-apply filter (which will apply the new sort)
                filterConnections(currentSearchQuery)

                Logger.d("MainActivity", "Sort changed to: $sortType")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Apply the current sort preference to a list of connections
     */
    private fun applySortToList(list: List<ConnectionProfile>): List<ConnectionProfile> {
        val sortType = app.preferencesManager.getString("connection_sort", "name_asc")

        return when (sortType) {
            "name_asc" -> list.sortedBy { it.name.lowercase() }
            "name_desc" -> list.sortedByDescending { it.name.lowercase() }
            "host_asc" -> list.sortedBy { it.host.lowercase() }
            "host_desc" -> list.sortedByDescending { it.host.lowercase() }
            "most_used" -> list.sortedByDescending { it.connectionCount }
            "least_used" -> list.sortedBy { it.connectionCount }
            "recent" -> list.sortedByDescending { it.lastConnected }
            "oldest" -> list.sortedBy { it.lastConnected }
            else -> list.sortedBy { it.name.lowercase() }
        }
    }

    private fun loadFrequentlyUsedConnections() {
        lifecycleScope.launch {
            try {
                val frequentConnections = app.database.connectionDao().getFrequentlyUsedConnections(5)
                
                frequentlyUsedConnections.clear()
                frequentlyUsedConnections.addAll(frequentConnections)
                
                runOnUiThread {
                    frequentlyUsedAdapter.notifyDataSetChanged()
                    updateFrequentlyUsedVisibility()
                }
                
                Logger.d("MainActivity", "Loaded ${frequentConnections.size} frequently used connections")
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to load frequently used connections", e)
            }
        }
    }

    private fun updateFrequentlyUsedVisibility() {
        if (frequentlyUsedConnections.isEmpty()) {
            binding.cardFrequentlyUsed.visibility = android.view.View.GONE
        } else {
            binding.cardFrequentlyUsed.visibility = android.view.View.VISIBLE
        }
    }
    
    private fun updateEmptyState() {
        if (connections.isEmpty()) {
            binding.recyclerConnections.visibility = android.view.View.GONE
            binding.textEmptyConnections.visibility = android.view.View.VISIBLE
        } else {
            binding.recyclerConnections.visibility = android.view.View.VISIBLE
            binding.textEmptyConnections.visibility = android.view.View.GONE
        }
    }
    
    private fun performQuickConnect() {
        val host = binding.editQuickHost.text.toString().trim()
        val portText = binding.editQuickPort.text.toString().trim()
        val username = binding.editQuickUsername.text.toString().trim()
        
        // Validate input
        if (host.isBlank()) {
            binding.editQuickHost.error = "Host is required"
            return
        }
        
        if (username.isBlank()) {
            binding.editQuickUsername.error = "Username is required"
            return
        }
        
        val port = portText.toIntOrNull() ?: 22
        if (port < 1 || port > 65535) {
            binding.editQuickPort.error = "Invalid port"
            return
        }
        
        // Create temporary connection profile
        val quickProfile = ConnectionProfile(
            name = "Quick Connect",
            host = host,
            port = port,
            username = username,
            authType = "PASSWORD" // Default to password auth for quick connect
        )
        
        connectToProfile(quickProfile)
    }
    
    private fun connectToProfile(profile: ConnectionProfile) {
        lifecycleScope.launch {
            try {
                Logger.d("MainActivity", "Connecting to ${profile.getDisplayName()}")

                // Update connection count and last connected timestamp (only if profile exists in DB)
                try {
                    app.database.connectionDao().updateLastConnected(profile.id)
                } catch (e: Exception) {
                    Logger.d("MainActivity", "Profile not in database (temporary quick connect)")
                }

                // Check if we need authentication (password/key)
                val authType = profile.getAuthTypeEnum()
                val needsPassword = when (authType) {
                    io.github.tabssh.ssh.auth.AuthType.PASSWORD,
                    io.github.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE -> {
                        // Check if password is stored
                        val storedPassword = app.securePasswordManager.retrievePassword(profile.id)
                        storedPassword == null
                    }
                    io.github.tabssh.ssh.auth.AuthType.PUBLIC_KEY -> {
                        profile.keyId == null
                    }
                    else -> false
                }

                if (needsPassword) {
                    // Show authentication dialog or edit connection
                    showAuthenticationDialog(profile)
                } else {
                    // Connect directly - start background service for persistent connections
                    io.github.tabssh.services.SSHConnectionService.startService(this@MainActivity)

                    val intent = TabTerminalActivity.createIntent(this@MainActivity, profile, autoConnect = true)
                    startActivity(intent)
                }

            } catch (e: Exception) {
                Logger.e("MainActivity", "Error initiating connection", e)
                showToast("Connection error: ${e.message}")
            }
        }
    }
    
    private fun showAuthenticationDialog(profile: ConnectionProfile) {
        val authType = profile.getAuthTypeEnum()

        when (authType) {
            io.github.tabssh.ssh.auth.AuthType.PASSWORD,
            io.github.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE -> {
                showPasswordPromptDialog(profile)
            }
            io.github.tabssh.ssh.auth.AuthType.PUBLIC_KEY -> {
                showToast("⚠️ No SSH key selected. Please edit connection and select a key.")
                val intent = ConnectionEditActivity.createIntent(this, profile.id)
                startActivity(intent)
            }
            else -> {
                showToast("⚠️ Please configure authentication for this connection.")
                val intent = ConnectionEditActivity.createIntent(this, profile.id)
                startActivity(intent)
            }
        }
    }

    private fun showPasswordPromptDialog(profile: ConnectionProfile) {
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Enter password for ${profile.username}@${profile.host}"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val savePasswordCheckbox = android.widget.CheckBox(this).apply {
            text = "💾 Save password securely"
            isChecked = false
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(passwordInput)
            addView(savePasswordCheckbox)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔐 Authentication Required")
            .setMessage("Enter password to connect to:\n${profile.name}")
            .setView(layout)
            .setPositiveButton("🚀 Connect") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isEmpty()) {
                    showToast("Password is required")
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        // Save password if requested
                        if (savePasswordCheckbox.isChecked) {
                            app.securePasswordManager.storePassword(profile.id, password)
                            showToast("✅ Password saved securely")
                        }

                        // Start SSH connection service
                        io.github.tabssh.services.SSHConnectionService.startService(this@MainActivity)

                        // Open terminal with the password
                        // Password is retrieved from SecurePasswordManager in TabTerminalActivity
                        // No need to pass via Intent (security best practice)
                        val intent = TabTerminalActivity.createIntent(
                            this@MainActivity,
                            profile,
                            autoConnect = true
                        )
                        startActivity(intent)

                    } catch (e: Exception) {
                        Logger.e("MainActivity", "Failed to save password", e)
                        showToast("❌ Error: ${e.message}")
                    }
                }
            }
            .setNegativeButton("❌ Cancel", null)
            .setNeutralButton("✏️ Edit Connection") { _, _ ->
                val intent = ConnectionEditActivity.createIntent(this, profile.id)
                startActivity(intent)
            }
            .show()
    }
    
    private fun showConnectionMenu(profile: ConnectionProfile) {
        val items = arrayOf("Connect", "Edit", "Move to Group", "Duplicate", "Delete")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(profile.getDisplayName())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> connectToProfile(profile)
                    1 -> editConnection(profile)
                    2 -> showMoveToGroupDialog(profile)
                    3 -> duplicateConnection(profile)
                    4 -> deleteConnection(profile)
                }
            }
            .show()
    }

    /**
     * Show dialog to move connection to a group
     */
    private fun showMoveToGroupDialog(profile: ConnectionProfile) {
        val groupNames = mutableListOf("(Ungrouped)", "+ Create New Group")
        groupNames.addAll(1, allGroups.map { it.name })

        val currentGroupIndex = if (profile.groupId == null) {
            0
        } else {
            allGroups.indexOfFirst { it.id == profile.groupId }.let { if (it >= 0) it + 2 else 0 }
        }

        AlertDialog.Builder(this)
            .setTitle("Move to Group")
            .setSingleChoiceItems(groupNames.toTypedArray(), currentGroupIndex) { dialog, which ->
                when (which) {
                    0 -> {
                        // Move to ungrouped
                        moveConnectionToGroup(profile, null)
                        dialog.dismiss()
                    }
                    1 -> {
                        // Create new group
                        dialog.dismiss()
                        showCreateGroupDialog(profile)
                    }
                    else -> {
                        // Move to existing group
                        val group = allGroups[which - 2]
                        moveConnectionToGroup(profile, group.id)
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show dialog to create a new group
     */
    private fun showCreateGroupDialog(connectionToMove: ConnectionProfile? = null) {
        val input = android.widget.EditText(this).apply {
            hint = "Group name"
        }

        AlertDialog.Builder(this)
            .setTitle("Create Group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val groupName = input.text.toString().trim()
                if (groupName.isNotBlank()) {
                    lifecycleScope.launch {
                        val newGroup = ConnectionGroup(
                            name = groupName,
                            sortOrder = allGroups.size,
                            createdAt = System.currentTimeMillis(),
                            modifiedAt = System.currentTimeMillis()
                        )
                        app.database.connectionGroupDao().insertGroup(newGroup)

                        // Move connection to new group if specified
                        connectionToMove?.let {
                            moveConnectionToGroup(it, newGroup.id)
                        }

                        Logger.d("MainActivity", "Created new group: $groupName")
                        showToast("Group created: $groupName")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Move connection to a group (or remove from group if groupId is null)
     */
    private fun moveConnectionToGroup(profile: ConnectionProfile, groupId: String?) {
        lifecycleScope.launch {
            val updatedProfile = profile.copy(
                groupId = groupId,
                modifiedAt = System.currentTimeMillis()
            )
            app.database.connectionDao().updateConnection(updatedProfile)

            val groupName = if (groupId == null) {
                "Ungrouped"
            } else {
                allGroups.find { it.id == groupId }?.name ?: "Unknown"
            }

            Logger.d("MainActivity", "Moved ${profile.name} to: $groupName")
            showToast("Moved to: $groupName")
        }
    }
    
    private fun editConnection(profile: ConnectionProfile) {
        val intent = ConnectionEditActivity.createIntent(this, profile.id)
        startActivity(intent)
    }
    
    private fun duplicateConnection(profile: ConnectionProfile) {
        lifecycleScope.launch {
            try {
                val duplicated = profile.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "${profile.name} (Copy)",
                    createdAt = System.currentTimeMillis()
                )
                
                app.database.connectionDao().insertConnection(duplicated)
                showToast("Connection duplicated")
                
                Logger.i("MainActivity", "Duplicated connection: ${profile.name}")
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to duplicate connection", e)
                showToast("Failed to duplicate connection")
            }
        }
    }
    
    private fun deleteConnection(profile: ConnectionProfile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Connection")
            .setMessage("Delete '${profile.name}'?\n\nThis will also remove any stored passwords for this connection.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.connectionDao().deleteConnection(profile)
                        app.securePasswordManager.clearPassword(profile.id)
                        
                        showToast("Connection deleted")
                        Logger.i("MainActivity", "Deleted connection: ${profile.name}")
                        
                    } catch (e: Exception) {
                        Logger.e("MainActivity", "Failed to delete connection", e)
                        showToast("Failed to delete connection")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Import backup from URI
     */
    private fun importBackupFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                Logger.i("MainActivity", "Importing backup from: $uri")

                val result = backupManager.restoreBackup(uri)

                if (result.success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Successful")
                        .setMessage("Restored ${result.restoredItems.values.sum()} items:\n" +
                                result.restoredItems.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                        .setPositiveButton("OK") { _, _ ->
                            // Refresh connection list
                            loadConnections()
                            loadFrequentlyUsedConnections()
                        }
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Failed")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Import failed", e)
                showToast("Import failed: ${e.message}")
            }
        }
    }

    private fun importSSHConfigFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val configContent = contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: throw Exception("Failed to read SSH config file")

                val parser = io.github.tabssh.ssh.config.SSHConfigParser()
                val profiles = parser.parseConfig(configContent)

                if (profiles.isEmpty()) {
                    showToast("No valid host configurations found in SSH config")
                    return@launch
                }

                // Insert all parsed profiles into database
                var importedCount = 0
                profiles.forEach { profile ->
                    try {
                        app.database.connectionDao().insertConnection(profile)
                        importedCount++
                    } catch (e: Exception) {
                        Logger.w("MainActivity", "Skipped duplicate or invalid host: ${profile.name}", e)
                    }
                }

                // Refresh the connection list
                loadConnections()

                // Show success dialog
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("SSH Config Imported")
                    .setMessage("Successfully imported $importedCount connection(s) from SSH config file.")
                    .setPositiveButton("OK", null)
                    .show()

                Logger.i("MainActivity", "Imported $importedCount connections from SSH config")

            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to import SSH config", e)
                showToast("Failed to import SSH config: ${e.message}")
            }
        }
    }

    /**
     * Export backup to URI
     */
    private fun exportBackupToUri(uri: Uri) {
        // Show export options dialog
        val options = arrayOf("Include passwords (encrypted)", "No passwords")
        var includePasswords = false

        AlertDialog.Builder(this)
            .setTitle("Export Options")
            .setSingleChoiceItems(options, 1) { _, which ->
                includePasswords = (which == 0)
            }
            .setPositiveButton("Export") { _, _ ->
                if (includePasswords) {
                    // Ask for encryption password
                    showBackupPasswordDialog(uri)
                } else {
                    performExport(uri, false, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show password dialog for backup encryption
     */
    private fun showBackupPasswordDialog(uri: Uri) {
        val passwordInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Encryption password"
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(passwordInput)
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("🔐 Encrypt Backup")
            .setMessage("Enter a strong password to encrypt your backup.\nYou'll need this password to restore.")
            .setView(layout)
            .setPositiveButton("Encrypt & Export") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.length < 8) {
                    showToast("Password must be at least 8 characters")
                    return@setPositiveButton
                }
                performExport(uri, true, password)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Perform actual export
     */
    private fun performExport(uri: Uri, includePasswords: Boolean, password: String?) {
        lifecycleScope.launch {
            try {
                Logger.i("MainActivity", "Exporting backup to: $uri")

                val result = backupManager.createBackup(
                    outputUri = uri,
                    includePasswords = includePasswords,
                    encryptBackup = includePasswords,
                    password = password
                )

                if (result.success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Export Successful")
                        .setMessage("Backup created successfully!\n\n" +
                                "Items exported:\n" +
                                result.metadata?.itemCounts?.entries?.joinToString("\n") { "${it.key}: ${it.value}" })
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Export Failed")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Export failed", e)
                showToast("Export failed: ${e.message}")
            }
        }
    }

    /**
     * Toggle group expansion state
     */
    private fun toggleGroupExpansion(groupHeader: ConnectionListItem.GroupHeader) {
        val newState = !groupHeader.isExpanded
        groupExpansionState[groupHeader.group.id] = newState

        // Update database
        lifecycleScope.launch {
            app.database.connectionGroupDao().updateGroupCollapsedState(groupHeader.group.id, !newState)
        }

        // Rebuild display
        rebuildDisplayList()

        Logger.d("MainActivity", "Toggled group ${groupHeader.group.name}: ${if (newState) "expanded" else "collapsed"}")
    }

    /**
     * Show group management menu
     */
    private fun showGroupMenu(groupHeader: ConnectionListItem.GroupHeader) {
        val options = arrayOf("Rename Group", "Delete Group")

        AlertDialog.Builder(this)
            .setTitle(groupHeader.group.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameGroupDialog(groupHeader.group)
                    1 -> showDeleteGroupDialog(groupHeader.group)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show rename group dialog
     */
    private fun showRenameGroupDialog(group: ConnectionGroup) {
        val input = android.widget.EditText(this).apply {
            setText(group.name)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Group")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch {
                        val updatedGroup = group.copy(
                            name = newName,
                            modifiedAt = System.currentTimeMillis()
                        )
                        app.database.connectionGroupDao().updateGroup(updatedGroup)
                        Logger.d("MainActivity", "Renamed group to: $newName")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show delete group dialog
     */
    private fun showDeleteGroupDialog(group: ConnectionGroup) {
        lifecycleScope.launch {
            val connectionCount = app.database.connectionGroupDao().getConnectionCountInGroup(group.id)

            val message = if (connectionCount > 0) {
                "This group contains $connectionCount connection(s). Deleting the group will move them to Ungrouped."
            } else {
                "Delete this group?"
            }

            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete ${group.name}?")
                    .setMessage(message)
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            app.database.connectionGroupDao().deleteGroup(group)
                            Logger.d("MainActivity", "Deleted group: ${group.name}")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.d("MainActivity", "onResume")

        // Refresh data in case it was modified
        loadGroups()
        loadConnections()
        loadFrequentlyUsedConnections()
    }
    
    override fun onPause() {
        super.onPause()
        Logger.d("MainActivity", "onPause")
    }
}