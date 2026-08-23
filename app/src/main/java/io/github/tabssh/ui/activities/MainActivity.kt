package io.github.tabssh.ui.activities

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.vnc.VncDirectConnector
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.ui.adapters.MainPagerAdapter
import io.github.tabssh.ui.adapters.MainTab
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main activity with 5-tab JuiceSSH-inspired layout
 * Tabs: Frequent | Hosts | Stats | Infra | Auth
 */
class MainActivity : TabSSHActivity() {

    private lateinit var app: TabSSHApplication
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

        // The drawer itself comes from TabSSHActivity's shared scaffold; the
        // home screen only supplies its own inline hamburger, which sits in
        // the tab row instead of a separate title bar.
        findViewById<android.widget.ImageButton>(R.id.btn_nav_menu).setOnClickListener {
            openNavigationDrawer()
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
            // Frequent tab in MainPagerAdapter
            "frequent"    -> MainTab.FREQUENT
            "last_tab"    -> prefs.getInt("ui_last_main_tab_index", MainTab.HOSTS).coerceIn(0, 4)
            // Connections tab (default)
            else          -> MainTab.HOSTS
        }
        // Optional deep-link into Auth's sub-tabs (e.g. Configure OCI -> VMs,
        // unresolved-keys Snackbar / Ctrl+K "SSH Keys" -> Keys). Written into
        // AuthFragment's own persisted sub-tab index before the pager moves,
        // so AuthFragment.onViewCreated picks it up via the normal read path.
        intent.getIntExtra("start_sub_tab", -1).takeIf { it >= 0 }?.let { subTab ->
            prefs.edit().putInt(io.github.tabssh.ui.fragments.AuthFragment.PREF_LAST_SUB_TAB, subTab).apply()
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
            if (viewPager.currentItem == MainTab.HOSTS) {
                startActivity(Intent(this, ConnectionEditActivity::class.java))
            }
        }

        // Update FAB visibility based on current tab
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Show FAB only on Hosts tab — Auth's sub-tabs each have
                // their own in-content add action, and Infra is read-only.
                fab.visibility = if (position == MainTab.HOSTS) {
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
        fab.visibility = if (viewPager.currentItem == MainTab.HOSTS) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        // Optional exit-confirmation prompt controlled by the user-visible
        // `confirm_exit` preference. Closing an open drawer is handled by
        // TabSSHActivity, whose callback is registered later and therefore
        // runs first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val confirmExit = app.preferencesManager.getBoolean("confirm_exit", false)
                if (confirmExit) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.exit_confirm_message)
                        .setPositiveButton(R.string.action_exit) { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // A drawer action picked on another screen routes here (MainActivity
        // is singleTop) because its UI lives on the home screen.
        handleNavAction(intent)

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
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.main_startup_warning_title)
                .setMessage(getString(R.string.main_startup_warning_message, error))
                .setPositiveButton(R.string.main_startup_warning_copy_dismiss) { _, _ ->
                    io.github.tabssh.utils.ClipboardHelper.copy(this@MainActivity, "TabSSH Error", error, sensitive = false)
                    prefs.edit().remove(io.github.tabssh.TabSSHApplication.KEY_STARTUP_ERROR).apply()
                }
                .setNegativeButton(R.string.main_startup_warning_dismiss) { _, _ ->
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
                    getString(R.string.main_notifications_disabled),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Quick Connect is the one drawer entry whose UI lives here: it needs the
    // tab manager and the home screen's connection list. Every other entry is
    // handled identically for all screens by TabSSHActivity.
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.nav_quick_connect) {
            closeNavigationDrawer()
            showQuickConnectDialog()
            return true
        }
        return super.onNavigationItemSelected(item)
    }

    /**
     * Performs a drawer action that another screen delegated to the home
     * screen by launching it with [EXTRA_NAV_ACTION].
     */
    private fun handleNavAction(source: Intent?) {
        val action = source?.getStringExtra(EXTRA_NAV_ACTION) ?: return
        source.removeExtra(EXTRA_NAV_ACTION)
        if (action == EXTRA_QUICK_CONNECT) {
            showQuickConnectDialog()
        }
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
                            HypervisorType.PROXMOX -> getString(R.string.hypervisor_type_proxmox)
                            HypervisorType.XCPNG -> getString(R.string.hypervisor_type_xcpng)
                            HypervisorType.VMWARE -> getString(R.string.hypervisor_type_vmware)
                            HypervisorType.OCI -> getString(R.string.oci_manager_title)
                            HypervisorType.LIBVIRT -> getString(R.string.hypervisor_type_libvirt)
                        }
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            getString(R.string.main_no_hosts_configured, typeName),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // Switch to Infra tab (Hypervisors sub-tab)
                        viewPager.currentItem = MainTab.INFRA
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
                    getString(R.string.main_load_hypervisors_failed, e.message),
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
            Toast.makeText(this, getString(R.string.main_oci_managed_under_cloud_accounts), Toast.LENGTH_SHORT).show()
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
        val names = hypervisors.map { getString(R.string.main_hypervisor_name_host, it.name, it.host) }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.main_select_hypervisor_title)
            .setItems(names) { _, which ->
                openHypervisorManager(hypervisors[which])
            }
            .setNegativeButton(R.string.cancel, null)
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
        intent.getIntExtra("start_sub_tab", -1).takeIf { it >= 0 }?.let { subTab ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt(io.github.tabssh.ui.fragments.AuthFragment.PREF_LAST_SUB_TAB, subTab).apply()
        }
        if (tab != null) viewPager.setCurrentItem(tab, /* smoothScroll = */ true)
        handleNavAction(intent)
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
                hostLayout.hint = getString(R.string.main_quick_connect_hint_host_vnc)
                portInput.setText("5900")
                passwordLayout.hint = getString(R.string.main_quick_connect_hint_password_vnc)
            } else {
                hostLayout.hint = getString(R.string.main_quick_connect_hint_host_ssh)
                portInput.setText("22")
                passwordLayout.hint = getString(R.string.main_quick_connect_hint_password_ssh)
            }
        }

        // Toggle: "Save this connection" reveals/hides the name field.
        switchSave.setOnCheckedChangeListener { _, checked ->
            layoutSaveName.visibility = if (checked) View.VISIBLE else View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_connect_title)
            .setView(view)
            // set below to prevent auto-dismiss on error
            .setPositiveButton(R.string.connect_button, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = hostInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (raw.isEmpty()) {
                    hostLayout.error = getString(R.string.main_quick_connect_error_enter_hostname)
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
                                val (rfbClient, _) = withContext(Dispatchers.IO) {
                                    VncDirectConnector.connect(vncHost, password.takeIf { it.isNotEmpty() }, context = this@MainActivity)
                                }
                                val tab = app.tabManager.createVncTab(vncHost)
                                if (tab == null) {
                                    try { rfbClient.stop() } catch (e: Exception) {
                                        Logger.d("MainActivity", "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                                    }
                                    Toast.makeText(this@MainActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                tab.rfbClient = rfbClient
                                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                                startActivity(
                                    android.content.Intent(this@MainActivity, TabTerminalActivity::class.java).apply {
                                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                                    }
                                )
                            } catch (e: Exception) {
                                Logger.e("MainActivity", "Failed to save VNC host", e)
                                Toast.makeText(this@MainActivity, getString(R.string.main_quick_connect_save_failed, e.message), Toast.LENGTH_LONG).show()
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
                                val (rfbClient, _) = withContext(Dispatchers.IO) {
                                    VncDirectConnector.connect(
                                        transientHost,
                                        password.takeIf { it.isNotEmpty() },
                                        context = this@MainActivity
                                    )
                                }
                                val tab = app.tabManager.createVncTab(null, ephemeralDisplayName = transientHost.name)
                                if (tab == null) {
                                    try { rfbClient.stop() } catch (e: Exception) {
                                        Logger.d("MainActivity", "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                                    }
                                    Toast.makeText(this@MainActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                tab.rfbClient = rfbClient
                                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                                startActivity(
                                    android.content.Intent(this@MainActivity, TabTerminalActivity::class.java).apply {
                                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                                    }
                                )
                            } catch (e: Exception) {
                                Logger.e("MainActivity", "VNC connect failed", e)
                                Toast.makeText(this@MainActivity, getString(R.string.main_quick_connect_vnc_connection_failed, raw, port, e.message), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@MainActivity, getString(R.string.main_quick_connect_saved, name), Toast.LENGTH_SHORT).show()
                val intent = TabTerminalActivity.createIntent(this@MainActivity, profile, autoConnect = true)
                startActivity(intent)
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to save connection", e)
                Toast.makeText(this@MainActivity, getString(R.string.main_quick_connect_save_failed, e.message), Toast.LENGTH_LONG).show()
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
            name = getString(R.string.main_quick_connect_profile_name, username, hostname),
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
}
