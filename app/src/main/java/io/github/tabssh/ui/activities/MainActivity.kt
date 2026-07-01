package io.github.tabssh.ui.activities

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import io.github.tabssh.hypervisor.vnc.VncDirectConnector
import io.github.tabssh.hypervisor.vnc.VncStreamHolder
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.ui.adapters.MainPagerAdapter
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d("MainActivity", "onCreate - New 5-tab layout")

        app = application as TabSSHApplication

        // Sweep any per-host SSH notifications that were orphaned by a prior
        // force-stop or OOM kill (onDestroy never ran → notifications survived
        // their normal 20-min safety-net timeout but are now stale).
        // The service sweeps on its own onCreate, but it only starts when the
        // user initiates a new connection — opening MainActivity is the
        // earliest reliable opportunity to clear leftover entries.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.activeNotifications
                .filter { it.id in 10_000..99_999 }
                .forEach { nm.cancel(it.id) }
        }

        // Setup drawer — hamburger is now inline with the tab bar (no separate title row)
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        findViewById<android.widget.ImageButton>(R.id.btn_nav_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

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
        // Explicit start_tab extra overrides the pref — used when another
        // activity (e.g. the command palette) navigates here to a specific tab.
        val explicitTab = intent.getIntExtra("start_tab", -1).takeIf { it in 0..4 }
        val initialTabIndex = explicitTab ?: when (startup) {
            "frequent"    -> 0  // Frequent tab in MainPagerAdapter
            "last_tab"    -> prefs.getInt("ui_last_main_tab_index", 1).coerceIn(0, 4)
            else          -> 1  // Connections tab (default)
        }
        viewPager.setCurrentItem(initialTabIndex, /* smoothScroll = */ false)
        Logger.d("MainActivity", "Startup behavior: $startup → tab $initialTabIndex")

        // Persist whichever tab is showing so "last_tab" startup mode has
        // something to read on next launch.
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                prefs.edit().putInt("ui_last_main_tab_index", position).apply()
            }
        })

        // FAB — only active on the Hosts tab (tab 1); all other tabs manage
        // their own in-content add actions or are read-only.
        fab.setOnClickListener {
            if (viewPager.currentItem == 1) {
                startActivity(Intent(this, ConnectionEditActivity::class.java))
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

        // Handle back press for drawer + optional exit-confirmation prompt
        // controlled by the user-visible `confirm_exit` preference
        // (preferences_general.xml). When the toggle is on, the back press
        // that would exit MainActivity is intercepted and an AlertDialog
        // asks the user to confirm before finishing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                val confirmExit = app.preferencesManager.getBoolean("confirm_exit", false)
                if (confirmExit) {
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setMessage("Exit TabSSH?")
                        .setPositiveButton("Exit") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
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
            // Quick
            R.id.nav_quick_connect -> showQuickConnectDialog()

            // Manage
            R.id.nav_snippets -> startActivity(Intent(this, SnippetManagerActivity::class.java))
            R.id.nav_manage_groups -> startActivity(Intent(this, GroupManagementActivity::class.java))

            // Connect
            R.id.nav_port_forwarding -> startActivity(Intent(this, PortForwardingActivity::class.java))
            R.id.nav_cluster_commands -> startActivity(Intent(this, ClusterCommandActivity::class.java))

            // Insights
            R.id.nav_multi_dashboard -> startActivity(Intent(this, MultiHostDashboardActivity::class.java))
            R.id.nav_connection_history -> startActivity(Intent(this, ConnectionHistoryActivity::class.java))

            // Accounts
            R.id.nav_vnc_hosts -> startActivity(Intent(this, VncHostsActivity::class.java))

            // Settings
            R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))

            // Diagnostics
            R.id.nav_copy_app_log -> copyAppLog()
            R.id.nav_copy_debug_logs -> copyDebugLogs()
            R.id.nav_whats_new -> startActivity(Intent(this, WhatsNewActivity::class.java))
            R.id.nav_help -> showHelpDialog()
            R.id.nav_about -> showAboutDialog()
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
        // Pull real version/build metadata from BuildConfig so devel/daily/beta
        // builds are distinguishable — the About dialog previously showed a
        // hard-coded "1.0.0 (Build 1)" which made every build look identical
        // and left users unsure whether an update had actually landed.
        val versionName = io.github.tabssh.BuildConfig.VERSION_NAME
        val versionCode = io.github.tabssh.BuildConfig.VERSION_CODE
        val commit = io.github.tabssh.BuildConfig.GIT_COMMIT_ID ?: "unknown"
        val flavor = io.github.tabssh.BuildConfig.BUILD_TYPE
        val aboutText = """
        TabSSH
        Version $versionName (Build $versionCode)
        Commit: $commit ($flavor)

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

        // Logger.getAllLogs() reads multiple files synchronously — must run on IO,
        // not on Main, to avoid blocking the main thread (ANR).
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) { Logger.getAllLogs() }
            offerLogShareOrCopy(
                title = "Debug Logs",
                clipLabel = "TabSSH Debug Logs",
                shareSubject = "TabSSH Debug Logs",
                logs = logs,
                logType = "debug",
                onClear = {
                    Logger.clearLogs()
                    android.widget.Toast.makeText(this@MainActivity, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /**
     * Show a log and offer Copy/Open Issue/Clear.
     *
     * "Open Issue" uploads the sanitized log to the configured paste service
     * and opens the GitHub new-issue page pre-filled with the paste URL —
     * no API token required.  This is more actionable than a raw Share sheet
     * because it lands directly in the bug tracker with device context already
     * attached.
     *
     * Clipboard write is still attempted for convenience but is wrapped in
     * try/catch because OEM clipboard services throw TransactionTooLargeException
     * on large payloads; when that happens we skip the copy and keep the
     * Open Issue button as the primary action.
     *
     * @param logType  "debug" or "app" — passed to [io.github.tabssh.ui.dialogs.ReportIssueDialog]
     *                 to label the paste correctly (both log types are pre-sanitized at write time).
     */
    private fun offerLogShareOrCopy(
        title: String,
        clipLabel: String,
        shareSubject: String,
        logs: String,
        logType: String,
        onClear: () -> Unit
    ) {
        val openIssueAction: () -> Unit = {
            io.github.tabssh.ui.dialogs.ReportIssueDialog
                .create(logs, logType)
                .show(supportFragmentManager, "report_issue")
        }

        // Empirical safe cap. Real binder limit is ~1MB across all parcels
        // in the call; clipboard service adds metadata, so 256 KB leaves
        // plenty of headroom even on cranky OEM builds.
        val clipboardCapBytes = 256 * 1024
        val logsBytes = logs.toByteArray(Charsets.UTF_8).size
        val tooBigForClipboard = logsBytes > clipboardCapBytes

        if (tooBigForClipboard) {
            // Don't even attempt clipboard — large strings have crashed
            // here before (TransactionTooLargeException) and there's no
            // sane way to recover mid-binder-call.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("$title — too large to copy")
                .setMessage(
                    "Log is ${logsBytes / 1024} KB — too large for the clipboard. " +
                    "Use \"Paste / Issue\" to upload it to a paste service — you can " +
                    "copy the URL or open a pre-filled GitHub bug report."
                )
                .setPositiveButton("Paste / Issue") { _, _ -> openIssueAction() }
                .setNeutralButton("Clear") { _, _ -> onClear() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val copied = try {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, logs))
            true
        } catch (e: Throwable) {
            // RemoteException / TransactionTooLargeException / OEM weirdness.
            Logger.e("MainActivity", "Clipboard write failed for $title (${logsBytes} bytes)", e)
            false
        }

        val msg = if (copied) {
            "${logs.length} characters copied to clipboard.\n\nTap \"Paste / Issue\" to upload to a paste service — copy the URL or open a GitHub issue."
        } else {
            "Clipboard write failed — log may be too large. Use \"Paste / Issue\" to upload it directly."
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (copied) "$title Copied" else title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Paste / Issue") { _, _ -> openIssueAction() }
            .setNegativeButton("Clear") { _, _ -> onClear() }
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
        offerLogShareOrCopy(
            title = "App Log",
            clipLabel = "TabSSH App Log",
            shareSubject = "TabSSH App Log",
            logs = logs,
            logType = "app",
            onClear = {
                Logger.clearAppLog()
                android.widget.Toast.makeText(this, "App log cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
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
                            HypervisorType.OCI -> "OCI"
                            HypervisorType.LIBVIRT -> "QEMU/libvirt"
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
        if (hypervisor.type == HypervisorType.OCI) {
            Toast.makeText(this, "OCI is now managed under Cloud Accounts", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = when (hypervisor.type) {
            HypervisorType.PROXMOX -> Intent(this, ProxmoxManagerActivity::class.java)
            HypervisorType.XCPNG -> Intent(this, XCPngManagerActivity::class.java)
            HypervisorType.VMWARE -> Intent(this, VMwareManagerActivity::class.java)
            HypervisorType.LIBVIRT -> Intent(this, LibvirtManagerActivity::class.java)
            else -> return
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

    override fun onResume() {
        super.onResume()
        // Fragments will handle their own data refreshing
    }

    /** Handle re-delivery of start_tab intent when activity is brought to front. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra("start_tab", -1).takeIf { it in 0..4 }
        if (tab != null) viewPager.setCurrentItem(tab, /* smoothScroll = */ true)
    }


    // Modern result API — replaces startActivityForResult/onActivityResult.
    override fun onPause() {
        super.onPause()
    }

    /**
     * Show quick connect dialog for fast SSH connections.
     * If user types only a hostname (no @), resolves username from
     * Settings > Connection > Default Username, falling back to "root".
     */
    private fun showQuickConnectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_quick_connect, null)
        val chipGroupProtocol = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chip_group_protocol)
        val chipSsh = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_ssh)
        val chipVnc = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_vnc)
        val hostInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_host)
        val hostLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_host)
        val portInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_port)
        val passwordInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_password)
        val passwordLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_password)
        val switchSave = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_save_connection)
        val layoutSaveName = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layout_save_name)
        val inputSaveName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_save_name)

        // Update hints when protocol selection changes.
        chipGroupProtocol.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chip_vnc)) {
                hostLayout.hint = "hostname or IP"
                portInput.setText("5900")
                passwordLayout.hint = "VNC password (optional)"
            } else {
                hostLayout.hint = "hostname or user@hostname"
                portInput.setText("22")
                passwordLayout.hint = "Password (leave blank to use SSH key)"
            }
        }

        // Toggle: "Save this connection" reveals/hides the name field.
        switchSave.setOnCheckedChangeListener { _, checked ->
            layoutSaveName.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quick Connect")
            .setView(view)
            .setPositiveButton("Connect", null) // set below to prevent auto-dismiss on error
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = hostInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (raw.isEmpty()) {
                    hostLayout.error = "Enter a hostname"
                    return@setOnClickListener
                }
                hostLayout.error = null

                val savePermanent = switchSave.isChecked
                val saveName = inputSaveName.text?.toString()?.trim()

                if (chipVnc.isChecked) {
                    val port = portInput.text.toString().toIntOrNull() ?: 5900
                    dialog.dismiss()
                    if (savePermanent) {
                        lifecycleScope.launch {
                            val now = System.currentTimeMillis()
                            val vncHost = VncHost(
                                id = java.util.UUID.randomUUID().toString(),
                                name = saveName?.takeIf { it.isNotBlank() } ?: "$raw:$port",
                                host = raw,
                                port = port,
                                createdAt = now,
                                modifiedAt = now
                            )
                            try {
                                app.database.vncHostDao().insert(vncHost)
                                if (password.isNotEmpty()) {
                                    app.securePasswordManager.storePassword(
                                        "vnc_host_${vncHost.id}", password,
                                        io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                                    )
                                }
                                val intent = android.content.Intent(this@MainActivity, VMConsoleActivity::class.java).apply {
                                    putExtra(VMConsoleActivity.EXTRA_VNC_HOST_ID, vncHost.id)
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Logger.e("MainActivity", "Failed to save VNC host", e)
                                Toast.makeText(this@MainActivity, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        lifecycleScope.launch {
                            try {
                                val now = System.currentTimeMillis()
                                val transientHost = VncHost(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = "$raw:$port",
                                    host = raw,
                                    port = port,
                                    createdAt = now,
                                    modifiedAt = now
                                )
                                val (rfbClient, socket) = VncDirectConnector.connect(
                                    transientHost,
                                    password.takeIf { it.isNotEmpty() },
                                    context = this@MainActivity
                                )
                                VncStreamHolder.set(socket.inputStream, socket.outputStream, socket)
                                val intent = android.content.Intent(this@MainActivity, VMConsoleActivity::class.java).apply {
                                    putExtra(VMConsoleActivity.EXTRA_DIRECT_VNC, true)
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Logger.e("MainActivity", "VNC connect failed", e)
                                Toast.makeText(this@MainActivity, "Connection failed: vnc $raw:$port: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    val port = portInput.text.toString().toIntOrNull() ?: 22
                    val (username, hostname) = resolveQuickConnectUser(raw)
                    dialog.dismiss()

                    if (savePermanent) {
                        saveAndConnect(
                            name = saveName?.takeIf { it.isNotBlank() } ?: "$username@$hostname",
                            username = username,
                            hostname = hostname,
                            port = port,
                            password = password.takeIf { it.isNotEmpty() }
                        )
                    } else {
                        quickConnect(username, hostname, port, password.takeIf { it.isNotEmpty() })
                    }
                }
            }
        }

        dialog.show()
        hostInput.requestFocus()
    }

    /**
     * Persist a Quick-Connect dialog's host/port/user/password as a
     * permanent ConnectionProfile, then launch a connect on it. The
     * password (if any) is stored at PERSISTENT level via
     * SecurePasswordManager so the user doesn't get reprompted on
     * future connects.
     */
    private fun saveAndConnect(
        name: String,
        username: String,
        hostname: String,
        port: Int,
        password: String?
    ) {
        lifecycleScope.launch {
            val profile = ConnectionProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                host = hostname,
                port = port,
                username = username,
                authType = if (password != null) AuthType.PASSWORD.name else AuthType.KEYBOARD_INTERACTIVE.name,
                keyId = null,
                groupId = null
            )
            try {
                app.database.connectionDao().insertConnection(profile)
                if (password != null) {
                    app.securePasswordManager.storePassword(
                        profile.id, password,
                        io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                }
                Logger.i("MainActivity", "Saved + connecting to $username@$hostname:$port (id=${profile.id})")
                Toast.makeText(this@MainActivity, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                val intent = TabTerminalActivity.createIntent(this@MainActivity, profile, autoConnect = true)
                startActivity(intent)
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to save connection", e)
                Toast.makeText(this@MainActivity, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
            val defaultUser = app.preferencesManager.getDefaultUsername().trim()
            val user = if (defaultUser.isNotEmpty()) defaultUser else "root"
            user to input
        }
    }

    /**
     * Quick connect to SSH server without saving profile
     */
    private fun quickConnect(username: String, hostname: String, port: Int, password: String? = null) {
        val quickProfile = ConnectionProfile(
            id = java.util.UUID.randomUUID().toString(),
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
