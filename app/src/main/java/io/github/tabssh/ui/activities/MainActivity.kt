package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
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
import io.github.tabssh.BuildConfig
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.ui.adapters.MainPagerAdapter
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val bulkImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { bulkImportFromUri(it) }
    }

    // Wave 6.1 — SSH config export (SAF SaveDocument)
    private val exportSshConfigLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { exportSshConfigToUri(it) }
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
        // Toolbar title reflects the active tab (mobile-first — "TabSSH"
        // alone next to a tab strip is redundant, the user already knows
        // which app they're in). Re-set in the OnPageChangeCallback.
        supportActionBar?.title = "Hosts"

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

        // Wave 4.c — Tablet sidebar mode. On sw>=720dp the drawer locks open
        // as a permanent sidebar; the hamburger is hidden and the user can't
        // swipe it shut. Phones unchanged (off-canvas overlay).
        if (resources.getBoolean(R.bool.is_tablet)) {
            applySidebarMode(toggle)
        }
        // Wave 4.b — Foldable book mode. On a foldable that's HALF_OPENED with
        // a vertical hinge (book posture), unfold gives us "tablet-shaped"
        // real estate even though sw720dp may still be false on the inner
        // display. Lock the drawer open while in that state; restore overlay
        // mode when device is folded again.
        observeFoldingFeature(toggle)

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

        // `general_startup_behavior` pref → land on a specific tab on cold
        // start. "last_tab" is treated like "connections" until we add
        // persistent last-tab tracking; logging both for now.
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
        val startup = prefs.getString("general_startup_behavior", "connections")
        val initialTabIndex = when (startup) {
            "frequent"    -> 0  // Frequent tab in MainPagerAdapter
            "last_tab"    -> prefs.getInt("ui_last_main_tab_index", 1).coerceIn(0, 4)
            else          -> 1  // Connections tab (default)
        }
        viewPager.setCurrentItem(initialTabIndex, /* smoothScroll = */ false)
        supportActionBar?.title = pagerAdapter.getTabTitle(initialTabIndex)
        Logger.d("MainActivity", "Startup behavior: $startup → tab $initialTabIndex")

        // Persist whichever tab is showing so "last_tab" startup mode has
        // something to read on next launch.
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                prefs.edit().putInt("ui_last_main_tab_index", position).apply()
                // Keep the toolbar title in sync with the active tab. The
                // tab labels already read sensible-phone-friendly names
                // (Frequent, Hosts, Identities, Stats, VMs).
                supportActionBar?.title = pagerAdapter.getTabTitle(position)
            }
        })

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
                // Show FAB only on Connections tab (position 1)
                // Identities tab (position 2) has its own FAB in the fragment
                fab.visibility = if (position == 1) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        })

        // Set initial FAB visibility based on the CURRENT tab (the
        // OnPageChangeCallback above only fires on subsequent changes;
        // setCurrentItem during cold-start runs BEFORE the callback is
        // registered, so without this the FAB stayed hidden until the
        // user manually swiped away and back to the Hosts tab — even
        // though the empty-state UI literally says "Tap the + button to
        // add your first SSH server").
        fab.visibility = if (viewPager.currentItem == 1) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

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

        // Show startup error dialog if any component failed to initialize
        checkStartupErrors()

        Logger.i("MainActivity", "MainActivity created successfully")
    }
    
    /**
     * If any component failed to initialize, show a dialog so the user can see
     * the error without needing ADB or a log viewer.
     */
    private fun checkStartupErrors() {
        val prefs = getSharedPreferences(io.github.tabssh.TabSSHApplication.STARTUP_PREFS, MODE_PRIVATE)
        val error = prefs.getString(io.github.tabssh.TabSSHApplication.KEY_STARTUP_ERROR, null)
        if (!error.isNullOrBlank()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠️ Startup Warning")
                .setMessage("Some components failed to initialize. The app may have reduced functionality.\n\n$error")
                .setPositiveButton("Copy & Dismiss") { _, _ ->
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("TabSSH Error", error))
                    prefs.edit().remove(io.github.tabssh.TabSSHApplication.KEY_STARTUP_ERROR).apply()
                }
                .setNegativeButton("Dismiss") { _, _ ->
                    prefs.edit().remove(io.github.tabssh.TabSSHApplication.KEY_STARTUP_ERROR).apply()
                }
                .setCancelable(false)
                .show()
        }
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

    // Toolbar menu removed - using drawer navigation only

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Main actions
            R.id.nav_quick_connect -> {
                showQuickConnectDialog()
            }
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
            R.id.nav_import_ssh_config -> {
                importSSHConfig()
            }
            R.id.nav_bulk_import -> {
                bulkImportLauncher.launch(arrayOf("*/*"))
            }
            R.id.nav_export_ssh_config -> {
                exportSshConfigLauncher.launch("ssh_config_${System.currentTimeMillis() / 1000}.txt")
            }
            R.id.nav_import_connections -> {
                importConnectionsLauncher.launch(arrayOf("application/zip", "application/json"))
            }
            R.id.nav_export_connections -> {
                exportConnectionsLauncher.launch("tabssh_connections_${System.currentTimeMillis()}.zip")
            }
            
            // Settings & Help
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.nav_theme_editor -> {
                startActivity(ThemeEditorActivity.createIntent(this))
            }
            R.id.nav_connection_history -> {
                startActivity(Intent(this, ConnectionHistoryActivity::class.java))
            }
            R.id.nav_whats_new -> {
                startActivity(Intent(this, WhatsNewActivity::class.java))
            }
            R.id.nav_multi_dashboard -> {
                startActivity(Intent(this, MultiHostDashboardActivity::class.java))
            }
            R.id.nav_cloud_accounts -> {
                startActivity(Intent(this, CloudAccountsActivity::class.java))
            }
            R.id.nav_copy_app_log -> {
                copyAppLog()
            }
            R.id.nav_copy_debug_logs -> {
                copyDebugLogs()
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
     * Copy debug logs to clipboard and offer to share
     */
    private fun copyDebugLogs() {
        // Probe the actual file — substring-sniffing the rendered text
        // (what the old version did) was unreliable and copied placeholder
        // strings into the clipboard while toasting "success". Now we
        // route on file state directly.
        val file = Logger.getLogFile()
        val haveRealLogs = file != null && file.exists() && file.length() > 0
        if (!haveRealLogs) {
            // Show a hint that depends on which build the user is on:
            //   debug build  → debug logging is supposed to be on, but no
            //                  events captured yet — "do something first"
            //   release build → debug logging is off; tell them where to
            //                  enable it (Settings → Logging) rather than
            //                  silently flipping it from this menu (the
            //                  user wants this controlled from Settings).
            val msg = if (BuildConfig.DEBUG_MODE) {
                "Debug logging is enabled (this is a development build) but " +
                "no events have been captured yet.\n\nUse the app a bit " +
                "(open a connection, navigate around) and try Copy Debug " +
                "Logs again."
            } else {
                "Debug logging is OFF in this release build.\n\n" +
                "Enable it in Settings → Logging → \"Enable Debug Logging\", " +
                "reproduce the issue, then come back here to copy the logs."
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No Debug Logs Yet")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .also { b ->
                    if (!BuildConfig.DEBUG_MODE) {
                        b.setNeutralButton("Open Settings") { _, _ ->
                            startActivity(Intent(this, SettingsActivity::class.java))
                        }
                    }
                }
                .show()
            return
        }

        val logs = Logger.getAllLogs()
        // Copy to clipboard
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("TabSSH Debug Logs", logs))

        // Show dialog with options
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Debug Logs Copied")
            .setMessage("${logs.length} characters copied to clipboard.\n\nYou can paste this into a message or share it.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TabSSH Debug Logs")
                    putExtra(Intent.EXTRA_TEXT, logs)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Debug Logs"))
            }
            .setNegativeButton("Clear Logs") { _, _ ->
                Logger.clearLogs()
                android.widget.Toast.makeText(this, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Copy sanitized app log (safe for public sharing)
     */
    private fun copyAppLog() {
        // Probe the file directly — `getAppLog()` returns a "No logs
        // recorded yet" placeholder when there's nothing, but checking
        // file existence + size is more reliable than substring matching
        // (which previously also missed the placeholder for the debug-log
        // sibling and copied junk).
        val file = Logger.getAppLogFile()
        val haveRealLogs = file != null && file.exists() && file.length() > 0
        if (!haveRealLogs) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Application Log")
                .setMessage("No logs recorded yet.\n\nUse the app normally, and logs will be captured automatically.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val logs = Logger.getAppLog()
        // Copy to clipboard
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("TabSSH App Log", logs))

        // Show dialog with options
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("App Log Copied")
            .setMessage("${logs.length} characters copied to clipboard.\n\n⚠️ This log is SAFE TO SHARE PUBLICLY.\nAll sensitive info (IPs, hostnames, usernames) has been anonymized.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TabSSH App Log")
                    putExtra(Intent.EXTRA_TEXT, logs)
                }
                startActivity(Intent.createChooser(shareIntent, "Share App Log"))
            }
            .setNegativeButton("Clear") { _, _ ->
                Logger.clearAppLog()
                android.widget.Toast.makeText(this, "App log cleared", android.widget.Toast.LENGTH_SHORT).show()
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
        // First try without password
        lifecycleScope.launch {
            try {
                val result = backupManager.restoreBackup(uri, password = null, overwriteExisting = false)

                if (result.success) {
                    showImportSuccessDialog(result)
                    Logger.i("MainActivity", "Imported backup successfully")
                } else {
                    throw Exception("Import failed")
                }

            } catch (e: Exception) {
                // If it failed, might need password - ask user
                if (e.message?.contains("encrypted", ignoreCase = true) == true ||
                    e.message?.contains("password", ignoreCase = true) == true ||
                    e.message?.contains("decrypt", ignoreCase = true) == true) {
                    showImportPasswordDialog(uri)
                } else {
                    Logger.e("MainActivity", "Failed to import backup", e)
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Import Failed")
                        .setMessage("Failed to import backup:\n${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Show password dialog for encrypted backup import
     */
    private fun showImportPasswordDialog(uri: android.net.Uri) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Enter backup password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Encrypted Backup")
            .setMessage("This backup is encrypted. Enter the password to decrypt it.")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isNotBlank()) {
                    importBackupWithPassword(uri, password)
                } else {
                    android.widget.Toast.makeText(this, "Password required", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Import backup with password
     */
    private fun importBackupWithPassword(uri: android.net.Uri, password: String) {
        lifecycleScope.launch {
            try {
                val result = backupManager.restoreBackup(uri, password, overwriteExisting = false)

                if (result.success) {
                    showImportSuccessDialog(result)
                    Logger.i("MainActivity", "Imported encrypted backup successfully")
                } else {
                    throw Exception("Import failed")
                }

            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to import backup with password", e)
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Import Failed")
                    .setMessage("Failed to import backup:\n${e.message}\n\nThe password may be incorrect.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Show import success dialog
     */
    private fun showImportSuccessDialog(result: io.github.tabssh.backup.BackupManager.RestoreResult) {
        val message = buildString {
            append("Import successful!\n\n")
            append("Imported:\n")
            result.restoredItems.forEach { (type, count) ->
                if (count > 0) {
                    append("  • $count $type\n")
                }
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
            .setTitle("Backup Imported")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    /**
     * Import SSH config file
     */
    private fun importSSHConfig() {
        importSSHConfigLauncher.launch(arrayOf("*/*"))
    }

    /**
     * Wave 6.1 — Export current connections to OpenSSH config text. Writes to
     * the SAF-picked URI; passwords are NEVER written (they live in Keystore).
     */
    private fun exportSshConfigToUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val connections = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getAllConnectionsList()
                }
                val groups = withContext(Dispatchers.IO) {
                    try { app.database.connectionGroupDao().getAllGroups().first() } catch (_: Exception) { emptyList() }
                }
                val text = io.github.tabssh.ssh.config.SSHConfigExporter.export(connections, groups)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("Could not open output stream")
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Exported ${connections.size} connections",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Logger.e("MainActivity", "SSH config export failed", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Wave 1.6 — Bulk import dispatcher (CSV / JSON / PuTTY .reg / Terraform .tf).
     *
     * Parses with [io.github.tabssh.ssh.config.BulkImportParser], shows a
     * preview/confirm dialog, then routes through [importSSHConfigProfiles]
     * for the actual DB insert (so groups get created the same way).
     */
    private fun bulkImportFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: throw Exception("Could not open file")

                val result = io.github.tabssh.ssh.config.BulkImportParser.parse(text)

                if (result.hosts.isEmpty()) {
                    val msg = buildString {
                        append("No connections detected (${result.format.name}).")
                        if (result.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(result.warnings.joinToString("\n"))
                        }
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Bulk Import")
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }
                showBulkImportPreviewDialog(result)
            } catch (e: Exception) {
                Logger.e("MainActivity", "Bulk import failed", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Bulk import failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showBulkImportPreviewDialog(result: io.github.tabssh.ssh.config.BulkImportParser.ParseResult) {
        val sample = result.hosts.take(20).joinToString("\n") { p ->
            val auth = p.authType?.let { " · $it" }.orEmpty()
            val grp = p.groupName?.let { " [${'$'}it]" }.orEmpty()
            "• ${p.name} → ${p.username ?: "?"}@${p.host}:${p.port}$auth$grp"
        }
        val more = if (result.hosts.size > 20) "\n… and ${result.hosts.size - 20} more" else ""
        val warn = if (result.warnings.isNotEmpty()) {
            "\n\nWarnings:\n" + result.warnings.take(8).joinToString("\n") { "  - $it" }
        } else ""

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bulk Import: ${result.format.name}")
            .setMessage("Found ${result.hosts.size} connection(s).\n\n$sample$more$warn")
            .setPositiveButton("Import") { _, _ ->
                val profiles = result.hosts.map { it.toConnectionProfile() }
                importSSHConfigProfiles(profiles)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

                // Step 3: Update profiles with actual group IDs and insert.
                // Wave 6.4 — dedup on (host, port, username); skip rows that
                // already exist instead of creating duplicates on re-import.
                val existing = app.database.connectionDao().getAllConnectionsList()
                val existingTriples = existing.map { Triple(it.host, it.port, it.username) }.toHashSet()
                var connectionsImported = 0
                var connectionsSkipped = 0
                profiles.forEach { profile ->
                    val updatedProfile = if (profile.groupId != null && groupNameToId.containsKey(profile.groupId)) {
                        profile.copy(groupId = groupNameToId[profile.groupId])
                    } else {
                        profile
                    }
                    val triple = Triple(updatedProfile.host, updatedProfile.port, updatedProfile.username)
                    if (existingTriples.contains(triple)) {
                        connectionsSkipped++
                    } else {
                        app.database.connectionDao().insertConnection(updatedProfile)
                        existingTriples.add(triple) // catch in-batch duplicates too
                        connectionsImported++
                    }
                }

                val message = buildString {
                    append("✓ Imported $connectionsImported connection(s)")
                    if (connectionsSkipped > 0) {
                        append("\n• Skipped $connectionsSkipped already-existing host(s)")
                    }
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
        // Show options dialog for export
        showExportOptionsDialog(uri)
    }

    /**
     * Show export options dialog
     */
    private fun showExportOptionsDialog(uri: android.net.Uri) {
        val options = arrayOf(
            "Export without encryption",
            "Export with password protection"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> performExport(uri, includePasswords = false, password = null)
                    1 -> showExportPasswordDialog(uri)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show password dialog for encrypted export
     */
    private fun showExportPasswordDialog(uri: android.net.Uri) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Enter password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val confirmInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "Confirm password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val includePasswordsCheckbox = android.widget.CheckBox(this).apply {
            text = "Include saved passwords (encrypted)"
            isChecked = false
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
            addView(confirmInput.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            })
            addView(includePasswordsCheckbox.apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Encrypt Backup")
            .setMessage("Enter a password to encrypt your backup.")
            .setView(layout)
            .setPositiveButton("Export") { _, _ ->
                val password = passwordInput.text.toString()
                val confirm = confirmInput.text.toString()
                val includePasswords = includePasswordsCheckbox.isChecked

                when {
                    password.isBlank() -> {
                        android.widget.Toast.makeText(this, "Password required", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    password != confirm -> {
                        android.widget.Toast.makeText(this, "Passwords do not match", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    password.length < 4 -> {
                        android.widget.Toast.makeText(this, "Password too short (minimum 4 characters)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        performExport(uri, includePasswords, password)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Perform the actual export
     */
    private fun performExport(uri: android.net.Uri, includePasswords: Boolean, password: String?) {
        lifecycleScope.launch {
            try {
                val result = backupManager.createBackup(
                    outputUri = uri,
                    includePasswords = includePasswords,
                    encryptBackup = password != null,
                    password = password
                )

                if (result.success) {
                    val message = buildString {
                        append("Backup exported successfully!")
                        if (password != null) {
                            append("\n\n🔐 Encrypted with password")
                        }
                        result.metadata?.itemCounts?.let { items ->
                            if (items.isNotEmpty()) {
                                append("\n\nExported:\n")
                                items.forEach { (type, count) ->
                                    if (count > 0) {
                                        append("  • $count $type\n")
                                    }
                                }
                            }
                        }
                    }

                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Export Complete")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()

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
        // Wave 3.2 — PIN gate. Triggered once per process launch and after
        // every onPause where the activity actually went to background.
        maybePromptPinLock()
        // Fragments will handle their own data refreshing
    }

    private var pinUnlocked = false

    /** Wave 4.b — toggle a sidebar-locked-open mode (used by tablet + foldable). */
    private fun applySidebarMode(toggle: ActionBarDrawerToggle) {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
        drawerLayout.openDrawer(GravityCompat.START)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toggle.isDrawerIndicatorEnabled = false
    }

    /** Wave 4.b — restore phone overlay mode (used when folding back). */
    private fun applyPhoneOverlayMode(toggle: ActionBarDrawerToggle) {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        drawerLayout.closeDrawer(GravityCompat.START)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toggle.isDrawerIndicatorEnabled = true
    }

    /** Wave 4.b — observe FoldingFeature; flip drawer mode on hinge state changes. */
    private fun observeFoldingFeature(toggle: ActionBarDrawerToggle) {
        // Skip when we're already locked open from is_tablet.
        if (resources.getBoolean(R.bool.is_tablet)) return
        val tracker = androidx.window.layout.WindowInfoTracker.getOrCreate(this)
        lifecycleScope.launch {
            try {
                tracker.windowLayoutInfo(this@MainActivity).collect { info ->
                    val fold = info.displayFeatures
                        .filterIsInstance<androidx.window.layout.FoldingFeature>()
                        .firstOrNull()
                    val bookMode = fold != null &&
                        fold.state == androidx.window.layout.FoldingFeature.State.HALF_OPENED &&
                        fold.orientation == androidx.window.layout.FoldingFeature.Orientation.VERTICAL
                    if (bookMode) applySidebarMode(toggle) else applyPhoneOverlayMode(toggle)
                }
            } catch (e: Exception) {
                Logger.w("MainActivity", "FoldingFeature observation failed: ${e.message}")
            }
        }
    }

    private fun maybePromptPinLock() {
        if (pinUnlocked) return
        val enabled = app.preferencesManager.getBoolean(PinLockActivity.PREF_PIN_ENABLED, false)
        val hash = app.preferencesManager.getString(PinLockActivity.PREF_PIN_HASH, "")
        if (!enabled || hash.isBlank()) return
        startActivityForResult(PinLockActivity.verifyIntent(this), PIN_VERIFY_REQ)
    }

    @Deprecated("startActivityForResult required for one-shot lock screen pre-AndroidX result API in this codebase")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PIN_VERIFY_REQ) {
            if (resultCode == RESULT_OK) {
                pinUnlocked = true
            } else {
                finishAffinity() // user couldn't / wouldn't unlock
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Re-lock when we're sent to background.
        if (isFinishing.not()) pinUnlocked = false
    }

    companion object {
        private const val PIN_VERIFY_REQ = 0xA10C
    }
    
    /**
     * Show quick connect dialog for fast SSH connections.
     * If user types only a hostname (no @), resolves username from
     * Settings > Connection > Default Username, falling back to "root".
     */
    private fun showQuickConnectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_quick_connect, null)
        val hostInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_host)
        val hostLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_host)
        val portInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_port)
        val passwordInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_password)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quick Connect")
            .setView(view)
            .setPositiveButton("Connect", null) // set below to prevent auto-dismiss on error
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 22
                val password = passwordInput.text.toString()

                if (raw.isEmpty()) {
                    hostLayout.error = "Enter a hostname"
                    return@setOnClickListener
                }
                hostLayout.error = null

                val (username, hostname) = resolveQuickConnectUser(raw)
                dialog.dismiss()
                quickConnect(username, hostname, port, password.takeIf { it.isNotEmpty() })
            }
        }

        dialog.show()
        hostInput.requestFocus()
    }

    /**
     * Splits "user@host" or resolves username for plain "host".
     * Priority: explicit user@ > Settings default username > "root"
     */
    private fun resolveQuickConnectUser(input: String): Pair<String, String> {
        return if (input.contains("@")) {
            val atIdx = input.indexOf("@")
            input.substring(0, atIdx) to input.substring(atIdx + 1)
        } else {
            val prefs = io.github.tabssh.storage.preferences.PreferenceManager(this)
            val defaultUser = prefs.getDefaultUsername().trim()
            val user = if (defaultUser.isNotEmpty()) defaultUser else "root"
            user to input
        }
    }

    /**
     * Quick connect to SSH server without saving profile
     */
    private fun quickConnect(username: String, hostname: String, port: Int, password: String? = null) {
        val quickProfile = ConnectionProfile(
            id = "quick_${System.currentTimeMillis()}",
            name = "Quick: $username@$hostname",
            host = hostname,
            port = port,
            username = username,
            authType = if (password != null) AuthType.PASSWORD.name else AuthType.KEYBOARD_INTERACTIVE.name,
            keyId = null,
            groupId = null
        )

        // Store password for this session only — cleared when app restarts
        if (password != null) {
            lifecycleScope.launch {
                app.securePasswordManager.storePassword(
                    quickProfile.id, password,
                    io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.SESSION_ONLY
                )
            }
        }

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
