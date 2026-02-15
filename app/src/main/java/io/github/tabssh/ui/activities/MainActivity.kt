package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.ui.adapters.MainPagerAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Main activity with 5-tab JuiceSSH-inspired layout
 * Tabs: Frequent | Connections | Identities | Performance | Hypervisors
 */
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var app: TabSSHApplication
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var backupManager: io.github.tabssh.backup.BackupManager
    
    // Import/Export launchers
    private val importConnectionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importBackupFromUri(it) }
    }
    
    private val exportConnectionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { exportBackupToUri(it) }
    }

    private val importSSHConfigLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importSSHConfigFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d("MainActivity", "onCreate - New 5-tab layout")

        app = application as TabSSHApplication
        backupManager = io.github.tabssh.backup.BackupManager(this)

        // Setup toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "TabSSH"

        // Setup drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Setup ViewPager2 + TabLayout
        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)
        fab = findViewById(R.id.fab_add)

        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = pagerAdapter.getTabTitle(position)
        }.attach()

        // FAB action
        fab.setOnClickListener {
            val currentTab = viewPager.currentItem
            when (currentTab) {
                0 -> {
                    // Frequent tab - no add action (read-only)
                }
                1 -> {
                    // Connections tab - add new connection
                    startActivity(Intent(this, ConnectionEditActivity::class.java))
                }
                2 -> {
                    // Identities tab - navigate to key management
                    startActivity(Intent(this, KeyManagementActivity::class.java))
                }
                3 -> {
                    // Performance tab - placeholder
                }
                4 -> {
                    // Hypervisors tab - placeholder
                }
            }
        }

        // Update FAB visibility based on current tab
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Show FAB only on Connections and Identities tabs
                fab.visibility = if (position == 1 || position == 2) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        })

        // Set initial FAB visibility
        fab.visibility = android.view.View.VISIBLE

        // Handle back press for drawer
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Request notification permission for Android 13+
        requestNotificationPermissionIfNeeded()

        Logger.i("MainActivity", "MainActivity created successfully")
    }
    
    /**
     * Request notification permission for Android 13+ (API 33+)
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
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
        
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.i("MainActivity", "Notification permission granted")
            } else {
                Logger.w("MainActivity", "Notification permission denied")
                android.widget.Toast.makeText(
                    this,
                    "⚠️ Notifications disabled - you won't receive connection alerts",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_quick_connect -> {
                showQuickConnectDialog()
                true
            }
            R.id.action_search -> true // Handled by SearchView
            R.id.action_sort -> {
                android.widget.Toast.makeText(this, "Sort options - Switch to Connections tab", android.widget.Toast.LENGTH_SHORT).show()
                viewPager.currentItem = 1
                true
            }
            R.id.action_view_logs -> {
                showLogsDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_manage_keys -> {
                startActivity(Intent(this, KeyManagementActivity::class.java))
                true
            }
            R.id.action_manage_identities -> {
                startActivity(Intent(this, IdentityManagementActivity::class.java))
                true
            }
            R.id.action_manage_snippets -> {
                startActivity(Intent(this, SnippetManagerActivity::class.java))
                true
            }
            R.id.action_create_group -> {
                startActivity(Intent(this, GroupManagementActivity::class.java))
                true
            }
            R.id.action_cluster_commands -> {
                startActivity(Intent(this, ClusterCommandActivity::class.java))
                true
            }
            R.id.action_import_connections -> {
                importConnectionsLauncher.launch(arrayOf("application/zip", "application/json"))
                true
            }
            R.id.action_export_connections -> {
                exportConnectionsLauncher.launch("tabssh_connections_${System.currentTimeMillis()}.zip")
                true
            }
            R.id.action_import_ssh_config -> {
                importSSHConfig()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_proxmox -> {
                startActivity(Intent(this, ProxmoxManagerActivity::class.java))
                true
            }
            R.id.action_xcpng -> {
                startActivity(Intent(this, XCPngManagerActivity::class.java))
                true
            }
            R.id.action_vmware -> {
                startActivity(Intent(this, VMwareManagerActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Main actions
            R.id.nav_connections -> {
                viewPager.currentItem = 1 // Switch to Connections tab
            }
            R.id.nav_identities -> {
                viewPager.currentItem = 2 // Switch to Identities tab
            }
            R.id.nav_manage_keys -> {
                startActivity(Intent(this, KeyManagementActivity::class.java))
            }
            R.id.nav_snippets -> {
                startActivity(Intent(this, SnippetManagerActivity::class.java))
            }
            R.id.nav_port_forwarding -> {
                startActivity(Intent(this, PortForwardingActivity::class.java))
            }
            
            // Hypervisors
            R.id.nav_hypervisors -> {
                viewPager.currentItem = 4 // Switch to Hypervisors tab
            }
            R.id.nav_proxmox -> {
                openHypervisorManagerByType(HypervisorType.PROXMOX)
            }
            R.id.nav_xcpng -> {
                openHypervisorManagerByType(HypervisorType.XCPNG)
            }
            R.id.nav_vmware -> {
                openHypervisorManagerByType(HypervisorType.VMWARE)
            }
            
            // Tools
            R.id.nav_manage_groups -> {
                startActivity(Intent(this, GroupManagementActivity::class.java))
            }
            R.id.nav_cluster_commands -> {
                startActivity(Intent(this, ClusterCommandActivity::class.java))
            }
            R.id.nav_performance -> {
                viewPager.currentItem = 3 // Switch to Performance tab
            }
            
            // Import/Export
            R.id.nav_import_connections -> {
                importConnectionsLauncher.launch(arrayOf("application/zip", "application/json"))
            }
            R.id.nav_export_connections -> {
                exportConnectionsLauncher.launch("tabssh_connections_${System.currentTimeMillis()}.zip")
            }
            R.id.nav_import_ssh_config -> {
                importSSHConfig()
            }
            
            // Settings & Help
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.nav_help -> {
                showHelpDialog()
            }
            R.id.nav_about -> {
                showAboutDialog()
            }
        }
        
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
    
    /**
     * Show help dialog
     */
    private fun showHelpDialog() {
        val helpText = """
        TabSSH - Modern SSH Client for Android
        
        Getting Started:
        • Tap (+) to add a new connection
        • Long-press a connection for more options
        • Swipe left/right to navigate between tabs
        
        Features:
        • Browser-style SSH tabs
        • SSH key management
        • Port forwarding
        • SFTP file transfer
        • Performance monitoring
        • Hypervisor management
        
        For more help, visit:
        https://github.com/tabssh/android
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .setNeutralButton("Visit Website") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tabssh/android"))
                startActivity(intent)
            }
            .show()
    }
    
    /**
     * Show about dialog
     */
    private fun showAboutDialog() {
        val aboutText = """
        TabSSH
        Version 1.0.0 (Build 1)
        
        A modern, open-source SSH client for Android with browser-style tabs, Material Design 3, and comprehensive security features.
        
        © 2024-2026 TabSSH Project
        Licensed under MIT License
        
        Built with:
        • Kotlin 2.0.21
        • JSch (SSH library)
        • Material Design Components
        • MPAndroidChart
        
        Credits:
        • Development: TabSSH Team
        • Icon Design: Material Icons
        • Community Contributors
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About TabSSH")
            .setMessage(aboutText)
            .setPositiveButton("OK", null)
            .setNeutralButton("GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tabssh/android"))
                startActivity(intent)
            }
            .setNegativeButton("License") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/tabssh/android/blob/main/LICENSE.md"))
                startActivity(intent)
            }
            .show()
    }

    /**
     * Open hypervisor manager by type
     * Queries database for hypervisors of the given type and opens the appropriate manager
     */
    private fun openHypervisorManagerByType(type: HypervisorType) {
        lifecycleScope.launch {
            try {
                val hypervisors = app.database.hypervisorDao().getByType(type)

                when {
                    hypervisors.isEmpty() -> {
                        // No hypervisors of this type, show helpful message
                        val typeName = when (type) {
                            HypervisorType.PROXMOX -> "Proxmox"
                            HypervisorType.XCPNG -> "XCP-ng"
                            HypervisorType.VMWARE -> "VMware"
                        }
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "No $typeName hosts configured. Go to Hypervisors tab to add one.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // Switch to Hypervisors tab
                        viewPager.currentItem = 4
                    }
                    hypervisors.size == 1 -> {
                        // Only one hypervisor, open it directly
                        openHypervisorManager(hypervisors[0])
                    }
                    else -> {
                        // Multiple hypervisors, show selection dialog
                        showHypervisorSelectionDialog(hypervisors)
                    }
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to get hypervisors by type", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Failed to load hypervisors: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Open the appropriate hypervisor manager activity
     */
    private fun openHypervisorManager(hypervisor: HypervisorProfile) {
        val intent = when (hypervisor.type) {
            HypervisorType.PROXMOX -> Intent(this, ProxmoxManagerActivity::class.java)
            HypervisorType.XCPNG -> Intent(this, XCPngManagerActivity::class.java)
            HypervisorType.VMWARE -> Intent(this, VMwareManagerActivity::class.java)
        }
        intent.putExtra("hypervisor_id", hypervisor.id)
        startActivity(intent)
    }

    /**
     * Show dialog to select from multiple hypervisors
     */
    private fun showHypervisorSelectionDialog(hypervisors: List<HypervisorProfile>) {
        val names = hypervisors.map { "${it.name} (${it.host})" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Hypervisor")
            .setItems(names) { _, which ->
                openHypervisorManager(hypervisors[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Import connections backup from URI
     */
    private fun importBackupFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                // Ask for password (optional)
                val password = null // TODO: Add password dialog
                
                val result = backupManager.restoreBackup(uri, password, overwriteExisting = false)
                
                if (result.success) {
                    // Show success dialog
                    val message = "Import successful!\n\nImported: ${result.restoredItems["connections"] ?: 0} connections"
                    
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Backup Imported")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                        
                    Logger.i("MainActivity", "Imported backup successfully")
                } else {
                    throw Exception("Import failed")
                }
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to import backup", e)
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import Failed")
                    .setMessage("Failed to import backup:\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    /**
     * Import SSH config file
     */
    private fun importSSHConfig() {
        importSSHConfigLauncher.launch(arrayOf("*/*"))
    }
    
    /**
     * Import SSH config from URI
     */
    private fun importSSHConfigFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not open file")
                
                // Read content
                val configContent = inputStream.bufferedReader().use { it.readText() }
                
                // Parse config
                val parser = io.github.tabssh.ssh.config.SSHConfigParser()
                val profiles = parser.parseConfig(configContent)
                
                // Show import dialog
                showSSHConfigImportDialog(profiles)
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to import SSH config", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Failed to import SSH config: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Show SSH config import dialog with results
     */
    private fun showSSHConfigImportDialog(profiles: List<io.github.tabssh.storage.database.entities.ConnectionProfile>) {
        // Collect unique groups (groupId contains group name from parser)
        val groups = profiles.mapNotNull { it.groupId }.filter { it.isNotBlank() }.toSet()

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import SSH Config")
            .setMessage(buildString {
                append("Found ${profiles.size} host(s)")
                if (groups.isNotEmpty()) {
                    append(" in ${groups.size} group(s)")
                }
                append("\n\n")

                // Show groups
                if (groups.isNotEmpty()) {
                    append("Groups to create:\n")
                    groups.sorted().forEach { group ->
                        val count = profiles.count { it.groupId == group }
                        append("  [$group] ($count hosts)\n")
                    }
                    append("\n")
                }

                // Show hosts by group
                if (profiles.isNotEmpty()) {
                    append("Hosts:\n")
                    // Group by groupId and show
                    val grouped = profiles.groupBy { it.groupId ?: "Ungrouped" }
                    var shown = 0
                    grouped.forEach { (group, groupProfiles) ->
                        if (shown < 15) {
                            append("[$group]\n")
                            groupProfiles.take(5).forEach { profile ->
                                append("  • ${profile.name}\n")
                                shown++
                            }
                            if (groupProfiles.size > 5) {
                                append("    ... and ${groupProfiles.size - 5} more\n")
                            }
                        }
                    }
                    if (profiles.size > 15) {
                        append("\n... and more hosts\n")
                    }
                }
            })
            .setPositiveButton("Import") { _, _ ->
                importSSHConfigProfiles(profiles)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }
    
    /**
     * Import profiles to database
     */
    private fun importSSHConfigProfiles(profiles: List<io.github.tabssh.storage.database.entities.ConnectionProfile>) {
        lifecycleScope.launch {
            try {
                val groupDao = app.database.connectionGroupDao()

                // Step 1: Collect unique group names from profiles (groupId contains group name from parser)
                val groupNames = profiles.mapNotNull { it.groupId }.filter { it.isNotBlank() }.toSet()
                Logger.d("MainActivity", "Found ${groupNames.size} unique groups: $groupNames")

                // Step 2: Create groups that don't exist and build name-to-ID map
                val groupNameToId = mutableMapOf<String, String>()
                var groupsCreated = 0

                for (groupName in groupNames) {
                    // Check if group already exists
                    val existingGroup = groupDao.getGroupByName(groupName)
                    if (existingGroup != null) {
                        groupNameToId[groupName] = existingGroup.id
                        Logger.d("MainActivity", "Group '$groupName' already exists with ID: ${existingGroup.id}")
                    } else {
                        // Create new group
                        val newGroup = io.github.tabssh.storage.database.entities.ConnectionGroup(
                            name = groupName,
                            icon = "folder",
                            sortOrder = groupsCreated
                        )
                        groupDao.insertGroup(newGroup)
                        groupNameToId[groupName] = newGroup.id
                        groupsCreated++
                        Logger.d("MainActivity", "Created new group '$groupName' with ID: ${newGroup.id}")
                    }
                }

                // Step 3: Update profiles with actual group IDs and insert
                var connectionsImported = 0
                profiles.forEach { profile ->
                    // Replace group name with group ID
                    val updatedProfile = if (profile.groupId != null && groupNameToId.containsKey(profile.groupId)) {
                        profile.copy(groupId = groupNameToId[profile.groupId])
                    } else {
                        profile
                    }
                    app.database.connectionDao().insertConnection(updatedProfile)
                    connectionsImported++
                }

                // Show success with details
                val message = buildString {
                    append("✓ Imported $connectionsImported connection(s)")
                    if (groupsCreated > 0) {
                        append("\n✓ Created $groupsCreated new group(s)")
                    }
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()

                // Refresh connections list
                viewPager.currentItem = 1 // Switch to Connections tab

            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to save imported connections", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Failed to save connections: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Export connections backup to URI
     */
    private fun exportBackupToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                // Ask for password (optional)
                val password = null // TODO: Add password dialog
                
                val result = backupManager.createBackup(
                    outputUri = uri,
                    includePasswords = false,
                    encryptBackup = password != null,
                    password = password
                )
                
                if (result.success) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Backup exported successfully",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    
                    Logger.i("MainActivity", "Exported backup successfully")
                } else {
                    throw Exception("Export failed")
                }
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to export backup", e)
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export Failed")
                    .setMessage("Failed to export backup:\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fragments will handle their own data refreshing
    }
    
    /**
     * Show quick connect dialog for fast SSH connections
     */
    private fun showQuickConnectDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_2, null).apply {
            // Create custom layout programmatically
            val layout = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 32, 64, 32)
            }
            
            val usernameEdit = com.google.android.material.textfield.TextInputEditText(this@MainActivity).apply {
                hint = "user@hostname"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            val usernameLayout = com.google.android.material.textfield.TextInputLayout(this@MainActivity).apply {
                addView(usernameEdit)
            }
            
            val portEdit = com.google.android.material.textfield.TextInputEditText(this@MainActivity).apply {
                hint = "Port (default: 22)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("22")
            }
            val portLayout = com.google.android.material.textfield.TextInputLayout(this@MainActivity).apply {
                addView(portEdit)
            }
            
            layout.addView(usernameLayout)
            layout.addView(portLayout)
            
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Quick Connect")
                .setMessage("Connect without saving")
                .setView(layout)
                .setPositiveButton("Connect") { _, _ ->
                    val input = usernameEdit.text.toString().trim()
                    val port = portEdit.text.toString().toIntOrNull() ?: 22
                    
                    if (input.contains("@")) {
                        val parts = input.split("@")
                        val username = parts[0]
                        val hostname = parts[1]
                        
                        quickConnect(username, hostname, port)
                    } else {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Format: user@hostname",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    /**
     * Quick connect to SSH server without saving profile
     */
    private fun quickConnect(username: String, hostname: String, port: Int) {
        val quickProfile = ConnectionProfile(
            id = "quick_${System.currentTimeMillis()}",
            name = "Quick: $username@$hostname",
            host = hostname,
            port = port,
            username = username,
            authType = "PASSWORD", // Will prompt for password
            keyId = null,
            groupId = null
        )
        
        // Launch terminal activity
        val intent = TabTerminalActivity.createIntent(this, quickProfile, autoConnect = true)
        startActivity(intent)
        
        Logger.i("MainActivity", "Quick connecting to $username@$hostname:$port")
    }
    
    private fun showLogsDialog() {
        try {
            val logEntries = Logger.getRecentLogs()
            if (logEntries.isEmpty()) {
                io.github.tabssh.ui.utils.DialogUtils.showSuccessDialog(
                    this,
                    "Application Logs",
                    "No logs available. Logs are generated during app usage."
                )
            } else {
                // Convert LogEntry list to formatted string
                val logs = logEntries.joinToString("\n") { entry ->
                    "${entry.timestamp} [${entry.level}] ${entry.tag}: ${entry.message}"
                }
                
                io.github.tabssh.ui.utils.DialogUtils.showCopyableDialog(
                    this,
                    "Application Logs (Last 500 entries)",
                    logs
                )
            }
        } catch (e: Exception) {
            io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
                this,
                "Error Loading Logs",
                "Failed to load logs: ${e.message}"
            )
        }
    }
}
