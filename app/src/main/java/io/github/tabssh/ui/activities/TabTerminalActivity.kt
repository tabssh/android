package io.github.tabssh.ui.activities
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.*
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ui.adapters.TerminalPagerAdapter
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityTabTerminalBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.ui.tabs.SSHTab
import io.github.tabssh.ui.tabs.TabManager
import io.github.tabssh.ui.tabs.TabManagerListener
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch
import io.github.tabssh.utils.showError
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.themes.definitions.BuiltInThemes

/**
 * Main terminal activity with tabbed SSH sessions
 * This is the core innovation of TabSSH - browser-style SSH tabs
 */
class TabTerminalActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_PROFILE_ID = "connection_profile_id"
        const val EXTRA_CONNECTION_PROFILE = "connection_profile"
        const val EXTRA_AUTO_CONNECT = "auto_connect"

        fun createIntent(context: Context, profile: ConnectionProfile, autoConnect: Boolean = true): Intent {
            return Intent(context, TabTerminalActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_PROFILE_ID, profile.id)
                // Also embed the full profile as JSON so unsaved (quick-connect) profiles work
                putExtra(EXTRA_CONNECTION_PROFILE,
                    com.google.gson.Gson().toJson(profile))
                putExtra(EXTRA_AUTO_CONNECT, autoConnect)
            }
        }
    }
    
    private lateinit var binding: ActivityTabTerminalBinding
    private lateinit var app: TabSSHApplication
    private lateinit var tabManager: TabManager

    // UI components
    private var terminalView: TerminalView? = null
    private var viewPager: ViewPager2? = null
    private var pagerAdapter: TerminalPagerAdapter? = null
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var swipeEnabled: Boolean = true
    
    // Performance overlay
    private var performanceOverlay: io.github.tabssh.ui.views.PerformanceOverlayView? = null
    private var performanceUpdateJob: Job? = null
    
    // Custom keyboard
    private var customKeyboardVisible: Boolean = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d("TabTerminalActivity", "onCreate")
        
        app = application as TabSSHApplication
        binding = ActivityTabTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupTabManager()
        setupTabLayout()
        setupTerminalView()
        setupTerminalGestures()  // NEW: Edge tap gestures for menu/toolbar
        setupFunctionKeys()
        setupCustomKeyboard()
        setupPerformanceOverlay()
        setupBackPressHandler()
        setupMenuFab()
        setupBottomActionBar()  // NEW: Bottom toolbar setup
        setupHostKeyVerification()  // Setup host key verification dialogs

        // Handle intent
        handleIntent(intent)
        
        Logger.i("TabTerminalActivity", "Terminal activity created")
    }
    
    // Track if keyboard is visible for back button handling
    private var isKeyboardVisible = false

    private fun setupBackPressHandler() {
        // Listen for keyboard visibility changes
        val rootView = window.decorView.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - rect.bottom
            isKeyboardVisible = keypadHeight > screenHeight * 0.15
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Logger.i("TabTerminalActivity", "BACK pressed — handler invoked")
                handleBackToMainActivity()
            }
        })
    }

    /**
     * Robust BACK handling: hide IME if shown, else go to MainActivity
     * (sessions stay alive in SSHSessionManager + foreground service).
     * Issue: relying on `finish()` alone left users stranded when the
     * activity was launched from outside the normal task stack (widget,
     * notification, etc.) — they ended up at the home screen instead of
     * MainActivity. Explicit Intent ensures MainActivity always shows.
     */
    private fun handleBackToMainActivity() {
        val terminalView = getActiveTerminalView()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        val imeShown = terminalView?.let {
            androidx.core.view.ViewCompat.getRootWindowInsets(it)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        } ?: false

        if (imeShown && terminalView != null) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            Logger.d("TabTerminalActivity", "BACK: hid IME, staying in terminal")
            return
        }

        Logger.i(
            "TabTerminalActivity",
            "BACK: launching MainActivity (${tabManager.getTabCount()} sessions stay active in SSHSessionManager)"
        )
        // Launch MainActivity explicitly so the user reliably ends up there
        // regardless of what's in the back stack (widget launch, notification
        // launch, deep-link, etc. all clear the stack differently).
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Hardware/gesture BACK fallback for devices/configurations where the
     * OnBackPressedDispatcher misses the event (e.g. predictive back gesture
     * in a fullscreen activity, or third-party launchers). Eat KEYCODE_BACK
     * here ourselves and route through the same handler.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Logger.i("TabTerminalActivity", "onBackPressed() called directly — routing")
        handleBackToMainActivity()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { view ->
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    /**
     * Show dialog when back button is pressed with active connections
     * Options: Stay, Go Home (keep connections), Close All
     */
    private fun showBackOptionsDialog() {
        val activeCount = tabManager.getTabCount()
        val connectedCount = tabManager.getAllTabs().count { it.isConnected() }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Active Sessions: $activeCount ($connectedCount connected)")
            .setItems(arrayOf(
                "📱 Go to Home (keep connections running)",
                "❌ Close all connections and exit",
                "↩️ Stay in terminal"
            )) { _, which ->
                when (which) {
                    0 -> {
                        // Go to home without closing connections
                        Logger.i("TabTerminalActivity", "User chose to go home, keeping $activeCount connections")
                        moveTaskToBack(true)
                    }
                    1 -> {
                        // Close all and exit
                        Logger.i("TabTerminalActivity", "User chose to close all $activeCount connections")
                        disconnectAllTabs()
                        finish()
                    }
                    // 2 = Stay, just dismiss dialog (do nothing)
                }
            }
            .show()
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.terminal_activity_title)
        }
    }
    
    private fun setupTabManager() {
        tabManager = TabManager(maxTabs = 10)
        
        // Set up tab manager listener
        tabManager.addListener(object : TabManagerListener {
            override fun onTabCreated(tab: SSHTab) {
                Handler(Looper.getMainLooper()).post {
                    addTabToUI(tab)
                    Logger.d("TabTerminalActivity", "Tab created: ${tab.profile.getDisplayName()}")
                }
            }

            override fun onTabClosed(tab: SSHTab, index: Int) {
                Handler(Looper.getMainLooper()).post {
                    removeTabFromUI(index)

                    // Close activity if no tabs remain
                    if (tabManager.getTabCount() == 0) {
                        finish()
                    }
                }
            }

            override fun onActiveTabChanged(index: Int) {
                Handler(Looper.getMainLooper()).post {
                    switchToTab(index)
                }
            }

            override fun onTabConnectionStateChanged(tab: SSHTab, state: ConnectionState) {
                Handler(Looper.getMainLooper()).post {
                    updateTabIcon(tab, state)
                }
            }
        })
    }
    
    private fun setupMenuFab() {
        binding.fabMenu.setOnClickListener {
            showTerminalMenu()
        }
    }
    
    /**
     * Setup bottom action bar with slide-up functionality
     */
    private fun setupBottomActionBar() {
        // Wave 3.7 — `show_bottom_nav` preference (default true) keeps the bar
        // visible at all times. If false we fall back to the edge-tap toggle
        // (legacy behaviour) where the bar appears for 5s then hides.
        val persistent = app.preferencesManager.getBoolean("show_bottom_nav", true)
        binding.bottomActionBar.visibility = if (persistent) View.VISIBLE else View.GONE

        binding.btnKeyboard.setOnClickListener {
            toggleKeyboard()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnSnippets.setOnClickListener {
            showSnippetsDialog()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnFiles.setOnClickListener {
            openFileManager()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnPaste.setOnClickListener {
            pasteFromClipboard()
            if (!persistent) hideBottomActionBar()
        }
        binding.btnMenu.setOnClickListener {
            showTerminalMenu()
            if (!persistent) hideBottomActionBar()
        }
    }

    /**
     * Setup host key verification callbacks for SSH connections
     */
    private fun setupHostKeyVerification() {
        // Setup callback for new (unknown) host keys
        app.sshSessionManager.newHostKeyCallback = { info ->
            Logger.i("TabTerminalActivity", "New host key callback invoked for ${info.hostname}")

            var userAction: io.github.tabssh.ssh.connection.HostKeyAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
            val latch = java.util.concurrent.CountDownLatch(1)

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    Logger.w("TabTerminalActivity", "Activity is finishing/destroyed - rejecting new host key")
                    latch.countDown()
                    return@runOnUiThread
                }
                try {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("New Host Key")
                        .setMessage(info.getDisplayMessage())
                        .setPositiveButton("Accept & Save") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept Once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                        .setNegativeButton("Reject") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                        .setCancelable(false)
                        .setOnDismissListener { latch.countDown() }
                        .show()
                } catch (e: Exception) {
                    Logger.e("TabTerminalActivity", "Failed to show new host key dialog", e)
                    latch.countDown()
                }
            }

            // Wait for the user to actually decide. Issue #32: the previous
            // 60s timeout silently rejected the connection while a careful
            // user was still verifying a 64-char SHA-256 fingerprint. The
            // dialog is non-cancellable and its dismiss listener counts the
            // latch down on activity destruction, so blocking is safe.
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Logger.e("TabTerminalActivity", "Interrupted waiting for host key response", e)
            }

            userAction
        }

        // Setup callback for changed host keys (MITM warning)
        app.sshSessionManager.hostKeyChangedCallback = { info ->
            Logger.w("TabTerminalActivity", "Host key CHANGED callback invoked for ${info.hostname}")

            var userAction: io.github.tabssh.ssh.connection.HostKeyAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
            val latch = java.util.concurrent.CountDownLatch(1)

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    Logger.w("TabTerminalActivity", "Activity is finishing/destroyed - rejecting changed host key")
                    latch.countDown()
                    return@runOnUiThread
                }
                try {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("WARNING: Host Key Changed!")
                        .setMessage(info.getDisplayMessage())
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton("Accept New Key") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_NEW_KEY
                            latch.countDown()
                        }
                        .setNeutralButton("Accept Once") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.ACCEPT_ONCE
                            latch.countDown()
                        }
                        .setNegativeButton("Reject (Recommended)") { _, _ ->
                            userAction = io.github.tabssh.ssh.connection.HostKeyAction.REJECT_CONNECTION
                            latch.countDown()
                        }
                        .setCancelable(false)
                        .setOnDismissListener { latch.countDown() }
                        .show()
                } catch (e: Exception) {
                    Logger.e("TabTerminalActivity", "Failed to show changed host key dialog", e)
                    latch.countDown()
                }
            }

            // No timeout — see Issue #32. The dismiss listener releases the
            // latch on activity destruction, so blocking is safe.
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Logger.e("TabTerminalActivity", "Interrupted waiting for host key response", e)
            }

            userAction
        }

        Logger.i("TabTerminalActivity", "Host key verification callbacks set up")
    }

    /**
     * Setup edge tap gestures for showing UI elements
     */
    private fun setupTerminalGestures() {
        // Get the root view
        val rootView = binding.root
        
        rootView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val width = rootView.width
                val height = rootView.height
                
                // Tap on left edge (first 10%) shows menu FAB temporarily
                if (x < width * 0.1f && y < height * 0.5f) {
                    showMenuFabTemporarily()
                    return@setOnTouchListener true
                }
                
                // Tap on bottom edge (last 10%) shows bottom action bar
                if (y > height * 0.9f) {
                    toggleBottomActionBar()
                    return@setOnTouchListener true
                }
            }
            false
        }
    }
    
    private fun showMenuFabTemporarily() {
        binding.fabMenu.visibility = View.VISIBLE
        
        // Auto-hide after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            binding.fabMenu.visibility = View.GONE
        }, 3000)
    }
    
    private fun toggleBottomActionBar() {
        if (binding.bottomActionBar.visibility == View.VISIBLE) {
            hideBottomActionBar()
        } else {
            showBottomActionBar()
        }
    }
    
    private fun showBottomActionBar() {
        binding.bottomActionBar.visibility = View.VISIBLE
        
        // Auto-hide after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            hideBottomActionBar()
        }, 5000)
    }
    
    private fun hideBottomActionBar() {
        binding.bottomActionBar.visibility = View.GONE
    }
    
    private fun showTerminalMenu() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_terminal_menu, null)
        
        // Tab list
        val tabsRecyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabs_recycler_view)
        tabsRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val tabs = tabManager.getAllTabs()
        val tabAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class TabViewHolder(val button: com.google.android.material.button.MaterialButton) : 
                androidx.recyclerview.widget.RecyclerView.ViewHolder(button)
            
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val button = com.google.android.material.button.MaterialButton(parent.context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextAlignment(android.view.View.TEXT_ALIGNMENT_VIEW_START)
                }
                return TabViewHolder(button)
            }
            
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val tab = tabs[position]
                val viewHolder = holder as TabViewHolder
                viewHolder.button.text = tab.profile.getDisplayName()
                viewHolder.button.setOnClickListener {
                    tabManager.setActiveTab(position)
                    switchToTab(position)
                    bottomSheet.dismiss()
                }
            }
            
            override fun getItemCount() = tabs.size
        }
        tabsRecyclerView.adapter = tabAdapter
        
        // Menu buttons
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_new_tab)?.setOnClickListener {
            // Quick connect
            bottomSheet.dismiss()
            showConnectionSelector()
        }
        
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close_tab)?.setOnClickListener {
            bottomSheet.dismiss()
            closeCurrentTab()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_disconnect_all)?.setOnClickListener {
            bottomSheet.dismiss()
            disconnectAllTabs()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_copy)?.setOnClickListener {
            bottomSheet.dismiss()
            copyTerminalScreen()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_paste)?.setOnClickListener {
            bottomSheet.dismiss()
            pasteFromClipboard()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_port_forwarding)?.setOnClickListener {
            bottomSheet.dismiss()
            openPortForwarding()
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_settings)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        bottomSheet.setContentView(view)
        bottomSheet.show()
    }
    
    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val position = tab.position
                tabManager.setActiveTab(position)
                switchToTab(position)
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }
    
    private fun setupTerminalView() {
        // Check if swipe is enabled
        swipeEnabled = app.preferencesManager.getBoolean("swipe_between_tabs", true)

        if (swipeEnabled) {
            // Use ViewPager2 for swipeable tabs
            viewPager = binding.viewPager
            binding.viewPager.visibility = View.VISIBLE
            binding.terminalView.visibility = View.GONE

            // ViewPager2 will be set up when tabs are created
            Logger.d("TabTerminalActivity", "Swipe between tabs enabled")
        } else {
            // Use single TerminalView (classic mode)
            terminalView = binding.terminalView
            binding.viewPager.visibility = View.GONE
            binding.terminalView.visibility = View.VISIBLE

            // Set up terminal view
            binding.terminalView.apply {
                // Load font from preferences
                val fontValue = app.preferencesManager.getString("terminal_font", "monospace")
                setFont(fontValue)

                // Load font size from preferences
                val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)
                setFontSize(fontSize)

                // URL detection is opt-in via preference; the long-press
                // context menu is ALWAYS available so users have a discoverable
                // way to copy/paste/select/send-text/change-font-size, matching
                // JuiceSSH's terminal long-press menu.
                val urlDetectionEnabled = app.preferencesManager.getBoolean("detect_urls", true)
                if (urlDetectionEnabled) {
                    onUrlDetected = { url ->
                        showUrlDialog(url)
                    }
                }
                onContextMenuRequested = { x, y ->
                    showTextContextMenu(x, y)
                }
                
                // Set up custom gesture support
                val gesturesEnabled = app.preferencesManager.getBoolean("enable_custom_gestures", false)
                if (gesturesEnabled) {
                    val multiplexerTypeStr = app.preferencesManager.getString("gesture_multiplexer_type", "tmux")
                    val multiplexerType = when (multiplexerTypeStr) {
                        "tmux" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
                        "screen" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.SCREEN
                        "zellij" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.ZELLIJ
                        else -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
                    }
                    
                    val customPrefix = app.preferencesManager.getString("multiplexer_custom_prefix", "")
                    enableGestureSupport(multiplexerType, customPrefix)
                    
                    // Set up command callback
                    onCommandSent = { command ->
                        // Send command to active terminal
                        tabManager.getActiveTab()?.let { tab ->
                            tab.terminal.sendText(String(command, Charsets.UTF_8))
                            android.widget.Toast.makeText(
                                this@TabTerminalActivity,
                                "Gesture command sent",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                // Terminal view will be connected to active tab's terminal
            }

            Logger.d("TabTerminalActivity", "Single terminal view mode (swipe disabled)")
        }

        // Apply current theme to terminal
        applyCurrentTheme()
    }

    /**
     * Apply the current terminal theme to all terminal views
     */
    private fun applyCurrentTheme() {
        val themeId = app.preferencesManager.getTheme()
        val theme = app.themeManager.getThemeById(themeId) ?: BuiltInThemes.dracula()

        Logger.d("TabTerminalActivity", "Applying theme: ${theme.name}")

        // Apply to main terminal view (non-swipe mode)
        binding.terminalView.applyTheme(theme)

        // Apply to all terminal views in ViewPager (swipe mode)
        pagerAdapter?.setTheme(theme)
    }

    /**
     * Show dialog for detected URL with options to open or copy
     */
    private fun showUrlDialog(url: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("URL Detected")
            .setMessage(url)
            .setPositiveButton("Open") { _, _ ->
                openUrl(url)
            }
            .setNeutralButton("Copy") { _, _ ->
                copyUrlToClipboard(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Open URL in browser
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
            Logger.d("TabTerminalActivity", "Opening URL: $url")
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Failed to open URL: $url", e)
            showError("Failed to open URL", "Error")
        }
    }

    /**
     * Copy URL to clipboard
     */
    private fun copyUrlToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
        Logger.d("TabTerminalActivity", "Copied URL to clipboard: $url")
    }
    
    /**
     * Show comprehensive SSH connection error dialog
     */
    private fun showSSHConnectionErrorDialog(
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        errorInfo: io.github.tabssh.ssh.connection.SSHConnectionErrorInfo
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ssh_connection_error, null)
        
        // Populate connection details
        dialogView.findViewById<TextView>(R.id.text_connection_name)?.text = 
            "Connection: ${profile.name ?: profile.getDisplayName()}"
        dialogView.findViewById<TextView>(R.id.text_connection_host)?.text = 
            "Host: ${profile.host}:${profile.port}"
        dialogView.findViewById<TextView>(R.id.text_connection_username)?.text = 
            "Username: ${profile.username}"
        dialogView.findViewById<TextView>(R.id.text_auth_type)?.text = 
            "Auth: ${profile.authType}"
        
        // Set error message
        dialogView.findViewById<TextView>(R.id.text_error_message)?.text = errorInfo.userMessage
        
        // Set technical details
        val technicalDetails = dialogView.findViewById<TextView>(R.id.text_technical_details)
        technicalDetails?.text = errorInfo.technicalDetails
        
        // Set solutions
        val solutionsText = errorInfo.possibleSolutions.joinToString("\n")
        dialogView.findViewById<TextView>(R.id.text_solutions)?.text = solutionsText
        
        // Toggle technical details visibility
        val showTechnicalButton = dialogView.findViewById<TextView>(R.id.text_show_technical)
        showTechnicalButton?.setOnClickListener {
            if (technicalDetails?.visibility == View.GONE) {
                technicalDetails.visibility = View.VISIBLE
                showTechnicalButton.text = "▼ Hide Technical Details"
            } else {
                technicalDetails?.visibility = View.GONE
                showTechnicalButton.text = "▶ Show Technical Details"
            }
        }
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("SSH Connection Failed: ${errorInfo.errorType}")
            .setView(dialogView)
            .create()
        
        // Copy Error button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_copy_error)
            ?.setOnClickListener {
                val fullError = buildString {
                    appendLine("=== SSH Connection Error ===")
                    appendLine()
                    appendLine("Connection: ${profile.name ?: profile.getDisplayName()}")
                    appendLine("Host: ${profile.host}:${profile.port}")
                    appendLine("Username: ${profile.username}")
                    appendLine("Auth: ${profile.authType}")
                    appendLine()
                    appendLine("Error Type: ${errorInfo.errorType}")
                    appendLine()
                    appendLine("Message:")
                    appendLine(errorInfo.userMessage)
                    appendLine()
                    appendLine("Technical Details:")
                    appendLine(errorInfo.technicalDetails)
                    appendLine()
                    appendLine("Possible Solutions:")
                    errorInfo.possibleSolutions.forEach {
                        appendLine(it)
                    }
                }
                
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SSH Error", fullError)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        
        // Edit Connection button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_edit_connection)
            ?.setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this, io.github.tabssh.ui.activities.ConnectionEditActivity::class.java).apply {
                    putExtra(io.github.tabssh.ui.activities.ConnectionEditActivity.EXTRA_CONNECTION_ID, profile.id)
                }
                startActivity(intent)
                finish() // Close TabTerminalActivity
            }
        
        // Retry button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_retry)
            ?.setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    connectToProfile(profile)
                }
            }
        
        // Close button
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_close)
            ?.setOnClickListener {
                dialog.dismiss()
                finish() // Close TabTerminalActivity
            }
        
        dialog.show()
    }
    
    /**
     * Show text context menu when user long-presses on non-URL text
     */
    /**
     * JuiceSSH-style long-press context menu for the terminal area.
     * Items roughly mirror what JuiceSSH/Termius offer: copy the visible
     * screen, paste, send arbitrary text, font size adjustment, share
     * connection info, close the current tab.
     */
    private fun showTextContextMenu(x: Float, y: Float) {
        val items = arrayOf(
            "Paste",
            "Copy screen",
            "Find in scrollback…",
            "Send text…",
            "Send Ctrl+C",
            "Send Ctrl+D",
            "Send Ctrl+Z",
            "Send Esc",
            "Snippets…",
            "Font size…",
            "Toggle keyboard",
            "Toggle keyboard layout (rows)",
            "Share connection info",
            "Close this tab"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Terminal")
            .setItems(items) { _, which ->
                when (which) {
                    0  -> pasteFromClipboard()
                    1  -> copyTerminalScreen()
                    2  -> showFindDialog()
                    3  -> showSendTextDialog()
                    4  -> sendBytesToActiveTab(byteArrayOf(0x03))   // ^C
                    5  -> sendBytesToActiveTab(byteArrayOf(0x04))   // ^D
                    6  -> sendBytesToActiveTab(byteArrayOf(0x1A))   // ^Z
                    7  -> sendBytesToActiveTab(byteArrayOf(0x1B))   // ESC
                    8  -> showSnippetsPickerForActiveTab()
                    9  -> showFontSizeDialog()
                    10 -> toggleKeyboard()
                    11 -> toggleCustomKeyboard()
                    12 -> shareSession()
                    13 -> closeActiveTabConfirmed()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendBytesToActiveTab(bytes: ByteArray) {
        try {
            tabManager.getActiveTab()?.connection?.getOutputStream()?.let { os ->
                os.write(bytes); os.flush()
            }
        } catch (e: Exception) {
            Logger.w("TabTerminalActivity", "sendBytesToActiveTab failed: ${e.message}")
        }
    }

    private fun showSnippetsPickerForActiveTab() {
        // Defer to the existing Snippets activity if present, else inform.
        try {
            val cls = Class.forName("io.github.tabssh.ui.activities.SnippetManagerActivity")
            startActivity(android.content.Intent(this, cls))
        } catch (e: ClassNotFoundException) {
            Toast.makeText(this, "Snippets unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wave 1.3 — Find-in-scrollback. Searches across the visible screen
     * AND the transcript buffer. Shows matching lines with line numbers
     * relative to the bottom (1 = newest). User can copy a selected match.
     * (In-terminal highlighting + scroll-to-position is a future polish.)
     */
    private fun showFindDialog() {
        val tab = tabManager.getActiveTab() ?: run {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Search text"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Find in scrollback")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text?.toString().orEmpty()
                if (query.isNotEmpty()) showFindResults(tab, query)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFindResults(tab: SSHTab, query: String) {
        val haystack = buildString {
            append(tab.getScrollbackContent())
            if (isNotEmpty()) append('\n')
            append(tab.getTerminalContent())
        }
        if (haystack.isBlank()) {
            Toast.makeText(this, "Nothing to search yet", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = haystack.split('\n')
        val matches = lines.mapIndexedNotNull { idx, line ->
            if (line.contains(query, ignoreCase = true)) {
                // Number from the bottom: last line = 1, line above = 2, etc.
                val fromBottom = lines.size - idx
                "$fromBottom: ${line.trim().take(120)}"
            } else null
        }
        if (matches.isEmpty()) {
            Toast.makeText(this, "No matches for \"$query\"", Toast.LENGTH_SHORT).show()
            return
        }
        val items = matches.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${matches.size} match${if (matches.size == 1) "" else "es"}")
            .setItems(items) { _, which ->
                // Tap a match to copy it.
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Match", items[which].substringAfter(": "))
                )
                Toast.makeText(this, "Match copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Copy the visible terminal screen + recent scrollback to clipboard.
     */
    private fun copyTerminalScreen() {
        val tab = tabManager.getActiveTab()
        if (tab == null) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }
        val visible = try { tab.getTerminalContent() } catch (e: Exception) { "" }
        if (visible.isBlank()) {
            Toast.makeText(this, "Nothing on screen to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("Terminal", visible)
        )
        Toast.makeText(this, "Terminal screen copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Send arbitrary text to the active terminal — useful for inserting
     * passwords/snippets that don't fit the snippets manager.
     */
    private fun showSendTextDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Text to send"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 2
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Send text")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    getActiveTerminalView()?.sendText(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Inline +/- font-size adjuster reused from the volume-key path.
     */
    private fun showFontSizeDialog() {
        val current = getActiveTerminalView()?.getFontSize() ?: 14
        val items = arrayOf("Smaller (-2 sp)", "Larger (+2 sp)", "Reset to 14 sp")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Font size — current: ${current} sp")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> adjustFontSize(-2)
                    1 -> adjustFontSize(2)
                    2 -> {
                        getActiveTerminalView()?.setFontSize(14)
                        app.preferencesManager.setInt("terminal_font_size", 14)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Close the currently-visible tab (with a confirm step so a stray
     * long-press doesn't lose work).
     */
    private fun closeActiveTabConfirmed() {
        val tab = tabManager.getActiveTab() ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Close tab")
            .setMessage("Close ${tab.profile.getDisplayName()}?")
            .setPositiveButton("Close") { _, _ ->
                val idx = tabManager.getAllTabs().indexOfFirst { it.tabId == tab.tabId }
                if (idx >= 0) tabManager.closeTab(idx)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Share current session info
     */
    private fun shareSession() {
        val currentTab = tabManager.getActiveTab()
        val profile = currentTab?.profile
        
        val shareText = buildString {
            append("TabSSH Session\n\n")
            profile?.let { p ->
                append("Host: ${p.host}\n")
                append("Port: ${p.port}\n")
                append("User: ${p.username}\n")
            }
        }
        
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        
        startActivity(Intent.createChooser(sendIntent, "Share session info"))
    }
    
    private fun setupPerformanceOverlay() {
        // Check if performance overlay is enabled in settings
        val showOverlay = app.preferencesManager.getBoolean("show_performance_overlay", false)
        
        if (showOverlay) {
            // Create overlay view
            performanceOverlay = io.github.tabssh.ui.views.PerformanceOverlayView(this)
            
            // Add to root view
            binding.root.addView(performanceOverlay)
            
            // Start updating metrics
            performanceUpdateJob = lifecycleScope.launch {
                app.performanceManager.performanceMetrics.collect { metrics ->
                    withContext(Dispatchers.Main) {
                        performanceOverlay?.updateMetrics(metrics)
                    }
                }
            }
            
            Logger.d("TabTerminalActivity", "Performance overlay enabled")
        }
    }
    
    private fun setupFunctionKeys() {
        // Set up function key buttons
        binding.btnCtrl.setOnClickListener { sendKey("ctrl") }
        binding.btnAlt.setOnClickListener { sendKey("alt") }
        binding.btnEsc.setOnClickListener { sendKey("esc") }
        binding.btnTab.setOnClickListener { sendKey("tab") }
        binding.btnArrowUp.setOnClickListener { sendKey("up") }
        binding.btnArrowDown.setOnClickListener { sendKey("down") }
        
        // Bottom action bar
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnSnippets.setOnClickListener { showSnippetsDialog() }
        binding.btnFiles.setOnClickListener { openFileManager() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Check for widget connection intent
        val widgetConnectionId = intent.getStringExtra("connection_id")
        if (widgetConnectionId != null) {
            Logger.d("TabTerminalActivity", "Handling widget connection: $widgetConnectionId")
            lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(widgetConnectionId)
                }
                if (profile != null) {
                    connectToProfile(profile)
                } else {
                    Logger.w("TabTerminalActivity", "Widget connection profile not found: $widgetConnectionId")
                    Toast.makeText(this@TabTerminalActivity, "Connection not found", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // Handle normal connection intent
        val connectionProfileId = intent.getStringExtra(EXTRA_CONNECTION_PROFILE_ID)
        val connectionProfileJson = intent.getStringExtra(EXTRA_CONNECTION_PROFILE)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, true)

        Logger.d("TabTerminalActivity", "Intent extras: profileId=$connectionProfileId, autoConnect=$autoConnect")

        if (connectionProfileId != null) {
            lifecycleScope.launch {
                // Always fetch from DB first to get latest changes (including identity)
                // Only fall back to embedded JSON for quick-connect (unsaved) profiles
                var profile: ConnectionProfile? = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(connectionProfileId)
                }

                // If not in DB, try embedded JSON (for quick-connect)
                if (profile == null && connectionProfileJson != null) {
                    try {
                        profile = com.google.gson.Gson().fromJson(connectionProfileJson, ConnectionProfile::class.java)
                        Logger.d("TabTerminalActivity", "Using embedded profile (quick-connect)")
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Failed to decode profile JSON", e)
                    }
                }

                if (profile != null) {
                    Logger.d("TabTerminalActivity", "Found profile: ${profile.name}, identityId: ${profile.identityId}")
                    if (autoConnect) {
                        connectToProfile(profile)
                    }
                } else {
                    Logger.e("TabTerminalActivity", "Profile not found for ID: $connectionProfileId")
                    Toast.makeText(this@TabTerminalActivity, "Connection profile not found", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Logger.w("TabTerminalActivity", "No connection profile ID in intent")
        }
    }
    
    private suspend fun connectToProfile(profile: ConnectionProfile) {
        try {
            Logger.i("TabTerminalActivity", "🚀 Starting connection to ${profile.getDisplayName()}")
            runOnUiThread {
                android.widget.Toast.makeText(this, "Connecting to ${profile.name}...", android.widget.Toast.LENGTH_SHORT).show()
            }

            // Resolve linked identity if set (for effective credentials)
            val linkedIdentity = if (profile.identityId != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        app.database.identityDao().getIdentityById(profile.identityId!!)?.also {
                            Logger.i("TabTerminalActivity", "Using identity '${it.name}' for connection")
                        }
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Error loading linked identity", e)
                        null
                    }
                }
            } else null

            // Use effective credentials: identity overrides profile
            val effectiveUsername = linkedIdentity?.username ?: profile.username
            val effectiveAuthType = linkedIdentity?.authType ?: AuthType.fromString(profile.authType)
            val effectiveKeyId = linkedIdentity?.keyId ?: profile.keyId

            // Check authentication requirements and prompt for password if needed
            val hasStoredPassword = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Check identity password first, then profile password
                val identityPw = linkedIdentity?.let {
                    app.securePasswordManager.retrievePassword("identity_${it.id}") ?: it.password
                }
                identityPw != null || app.securePasswordManager.retrievePassword(profile.id) != null
            }

            // Check if SSH key is available when PUBLIC_KEY auth is configured
            val keyAvailable = if (effectiveKeyId != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val keyExists = app.database.keyDao().getKeyById(effectiveKeyId) != null
                    val jschBytes = app.keyStorage.retrieveJSchBytes(effectiveKeyId)
                    val privateKey = if (jschBytes == null) {
                        app.keyStorage.retrievePrivateKey(effectiveKeyId)
                    } else null
                    keyExists && (jschBytes != null || privateKey != null)
                }
            } else false

            // Determine if we need to prompt for password:
            // 1. KEYBOARD_INTERACTIVE with no password and no key
            // 2. PUBLIC_KEY auth but key is not available (fallback to password)
            // 3. PASSWORD auth with no stored password
            val needPasswordPrompt = when {
                effectiveAuthType == AuthType.KEYBOARD_INTERACTIVE && !hasStoredPassword && !keyAvailable -> true
                effectiveAuthType == AuthType.PUBLIC_KEY && !keyAvailable && !hasStoredPassword -> {
                    Logger.w("TabTerminalActivity", "SSH key not available (keyId=$effectiveKeyId), falling back to password")
                    true
                }
                effectiveAuthType == AuthType.PASSWORD && !hasStoredPassword -> true
                else -> false
            }

            if (needPasswordPrompt) {
                val promptMessage = if (effectiveAuthType == AuthType.PUBLIC_KEY && !keyAvailable) {
                    "SSH key not found. Enter password for $effectiveUsername@${profile.host}"
                } else {
                    "Password required for $effectiveUsername@${profile.host}"
                }
                val enteredPassword = promptForPassword(promptMessage)
                if (enteredPassword == null) {
                    Logger.i("TabTerminalActivity", "User cancelled password prompt - closing activity")
                    Toast.makeText(this, "Connection cancelled", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Store password for the profile (SSHConnection will look it up)
                    app.securePasswordManager.storePassword(
                        profile.id, enteredPassword, SecurePasswordManager.StorageLevel.SESSION_ONLY
                    )
                }
            }

            // Wave 2.3 — Telnet branch (no auth, no JSch).
            if (profile.protocol.equals("telnet", ignoreCase = true)) {
                connectTelnetProfile(profile)
                return
            }

            // Create SSH connection
            Logger.d("TabTerminalActivity", "Creating SSH connection via SSHSessionManager")
            val sshConnection = app.sshSessionManager.connectToServer(profile)

            if (sshConnection != null) {
                Logger.i("TabTerminalActivity", "SSH connection established, creating tab")
                
                // Create new tab with the connection (using user's preferred cursor style)
                val cursorStyle = app.preferencesManager.getCursorStyleInt()
                val tab = tabManager.createTab(profile, cursorStyle)

                if (tab != null) {
                    Logger.d("TabTerminalActivity", "Tab created successfully: ${tab.tabId}")

                    // Auto-start recording if enabled
                    if (app.preferencesManager.getBoolean("auto_record_sessions", false)) {
                        Logger.d("TabTerminalActivity", "Starting session recording")
                        tab.sessionRecorder = io.github.tabssh.terminal.recording.SessionRecorder(
                            this,
                            profile.getDisplayName()
                        )
                        tab.sessionRecorder?.startRecording()
                    }

                    // CRITICAL: Wait for UI to be set up before connecting
                    // The onTabCreated listener posts addTabToUI() to main thread,
                    // we need to wait for that to complete so TerminalView is attached
                    kotlinx.coroutines.delay(200) // Give time for UI to attach terminal view

                    // Connect the tab's terminal to SSH streams
                    Logger.i("TabTerminalActivity", "🔌 Connecting terminal to SSH streams...")
                    val connected = tab.connect(sshConnection)
                    if (connected) {
                        Logger.i("TabTerminalActivity", "TERMINAL CONNECTED SUCCESSFULLY to ${profile.getDisplayName()}")

                        // Wave 9.2 — auto-mosh path. When `useMosh` is true on the
                        // profile AND we have a bundled native mosh-client, run
                        // mosh-server over the just-opened SSH session, then swap
                        // the tab's I/O from SSH to mosh-client. The SSH stays open
                        // briefly so the bootstrap completes; mosh-server detaches
                        // and continues listening on UDP independently.
                        if (profile.useMosh && io.github.tabssh.protocols.mosh.MoshNativeClient.resolveBinary(this) != null) {
                            val handoff = io.github.tabssh.protocols.mosh.MoshHandoff.bootstrap(
                                sshConnection, profile.username, profile.host
                            )
                            if (handoff is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Success) {
                                tab.disconnect()
                                val moshOk = tab.connectMosh(
                                    this, handoff.info.host, handoff.info.port, handoff.info.keyBase64
                                )
                                if (moshOk) {
                                    Logger.i("TabTerminalActivity", "✅ MOSH attached for ${profile.getDisplayName()}")
                                    showToast("Mosh: ${profile.getDisplayName()}")
                                } else {
                                    Logger.w("TabTerminalActivity", "Mosh attach failed; falling back to SSH")
                                    tab.connect(sshConnection)  // restore SSH
                                    showToast("Mosh failed — using SSH")
                                }
                            } else {
                                Logger.w("TabTerminalActivity", "Mosh bootstrap failed: ${(handoff as? io.github.tabssh.protocols.mosh.MoshHandoff.Result.Error)?.message}")
                                showToast("Mosh bootstrap failed — using SSH")
                            }
                        } else {
                            showToast("Connected to ${profile.getDisplayName()}")
                        }

                        // Update connection statistics (count + timestamp)
                        try {
                            app.database.connectionDao().updateLastConnected(profile.id)
                            Logger.d("TabTerminalActivity", "Updated connection count for ${profile.getDisplayName()}")
                        } catch (e: Exception) {
                            Logger.e("TabTerminalActivity", "Failed to update connection stats", e)
                        }

                        // Show connection success notification
                        io.github.tabssh.utils.NotificationHelper.showConnectionSuccess(
                            this,
                            profile.getDisplayName(),
                            profile.username,
                            profile.id
                        )
                    } else {
                        Logger.e("TabTerminalActivity", "Failed to connect terminal to SSH for ${profile.getDisplayName()}")
                        showError("Failed to connect terminal", "Error")
                        
                        // Check for detailed error info
                        val errorInfo = sshConnection.detailedError.value
                        if (errorInfo != null) {
                            Logger.e("TabTerminalActivity", "Error details: ${errorInfo.userMessage}")
                            runOnUiThread {
                                showSSHConnectionErrorDialog(profile, errorInfo)
                            }
                            
                            // Show error notification
                            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                                this,
                                profile.getDisplayName(),
                                errorInfo.userMessage
                            )
                        }
                    }
                } else {
                    Logger.e("TabTerminalActivity", "Failed to create tab for ${profile.getDisplayName()}")
                    showError("Failed to create terminal tab", "Error")
                }
            } else {
                // Connection failed - try to get detailed error from last connection attempt
                Logger.e("TabTerminalActivity", "SSH connection returned NULL for ${profile.getDisplayName()}")

                // Get the connection that failed (it may still exist even though connect() returned null)
                val failedConnection = app.sshSessionManager.getConnection(profile.id)
                val errorInfo = failedConnection?.detailedError?.value

                if (errorInfo != null) {
                    runOnUiThread {
                        showSSHConnectionErrorDialog(profile, errorInfo)
                    }

                    // Show error notification
                    io.github.tabssh.utils.NotificationHelper.showConnectionError(
                        this,
                        profile.getDisplayName(),
                        errorInfo.userMessage
                    )
                } else {
                    // Fallback to simple toast if no detailed error available
                    showError("Connection failed: ${profile.getDisplayName()}", "Error")

                    // Show generic error notification
                    io.github.tabssh.utils.NotificationHelper.showConnectionError(
                        this,
                        profile.getDisplayName(),
                        "Connection failed"
                    )

                // Close activity after connection failure
                Logger.i("TabTerminalActivity", "Closing activity due to connection failure")
                finish()
                }
            }
            
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Error connecting to ${profile.getDisplayName()}", e)
            
            // Try to create a detailed error info from the exception
            val errorInfo = io.github.tabssh.ssh.connection.SSHConnectionErrorInfo(
                errorType = "Connection Error",
                userMessage = e.message ?: "Unknown error occurred",
                technicalDetails = buildString {
                    appendLine("Exception: ${e.javaClass.simpleName}")
                    appendLine("Message: ${e.message}")
                    appendLine("\nStack Trace:")
                    appendLine(e.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Try restarting the app",
                    "• Check connection settings",
                    "• Verify network connectivity",
                    "• Check app logs for more details"
                ),
                exception = e
            )
            
            runOnUiThread {
                showSSHConnectionErrorDialog(profile, errorInfo)
            }
            
            // Show error notification
            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                this,
                profile.getDisplayName(),
                errorInfo.userMessage
            )
        }
    }
    
    /**
     * Wave 2.3 — Telnet connect path (no JSch, no auth).
     */
    private suspend fun connectTelnetProfile(profile: ConnectionProfile) {
        Logger.i("TabTerminalActivity", "Telnet connect to ${profile.getDisplayName()}")
        val telnet = io.github.tabssh.ssh.connection.TelnetConnection(profile.host, profile.port.takeIf { it > 0 } ?: 23)

        val cursorStyle = app.preferencesManager.getCursorStyleInt()
        val tab = tabManager.createTab(profile, cursorStyle)
        if (tab == null) {
            Logger.e("TabTerminalActivity", "Failed to create tab for ${profile.getDisplayName()}")
            showError("Failed to create terminal tab", "Error")
            finish()
            return
        }
        kotlinx.coroutines.delay(200) // wait for UI attach

        val ok = tab.connect(telnet)
        if (ok) {
            showToast("Connected (telnet) to ${profile.getDisplayName()}")
            try {
                app.database.connectionDao().updateLastConnected(profile.id)
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to update telnet connection stats", e)
            }
            io.github.tabssh.utils.NotificationHelper.showConnectionSuccess(
                this, profile.getDisplayName(), profile.username, profile.id
            )
        } else {
            showError("Telnet connection to ${profile.host}:${profile.port} failed", "Connection Error")
            io.github.tabssh.utils.NotificationHelper.showConnectionError(
                this, profile.getDisplayName(), "Telnet connect failed"
            )
            finish()
        }
    }

    private fun addTabToUI(tab: SSHTab) {
        if (swipeEnabled) {
            // Rebuild ViewPager2 adapter with updated tabs
            updateViewPagerAdapter()
            Logger.d("TabTerminalActivity", "Added tab to ViewPager2: ${tab.profile.getDisplayName()}")
        } else {
            // Classic mode: add tab to TabLayout only
            val tabLayout = binding.tabLayout
            val newTab = tabLayout.newTab()

            newTab.text = tab.getShortTitle()
            newTab.tag = tab.tabId

            tabLayout.addTab(newTab)

            // Select the new tab
            newTab.select()

            // Attach the terminal to the view
            terminalView?.attachTerminalEmulator(tab.terminal)

            Logger.d("TabTerminalActivity", "Added tab to UI: ${tab.profile.getDisplayName()}")
        }
    }

    /**
     * Update ViewPager2 adapter with current tabs
     */
    private fun updateViewPagerAdapter() {
        val allTabs = tabManager.getAllTabs()

        // Get font preferences
        val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)
        val fontValue = app.preferencesManager.getString("terminal_font", "monospace")

        // Create URL detection callback if enabled
        val urlDetectionCallback = if (app.preferencesManager.getBoolean("detect_urls", true)) {
            { url: String -> showUrlDialog(url) }
        } else {
            null
        }
        
        // Get gesture settings
        val gesturesEnabled = app.preferencesManager.getBoolean("enable_custom_gestures", false)
        val multiplexerTypeStr = app.preferencesManager.getString("gesture_multiplexer_type", "tmux")
        val multiplexerType = when (multiplexerTypeStr) {
            "tmux" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
            "screen" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.SCREEN
            "zellij" -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.ZELLIJ
            else -> io.github.tabssh.terminal.gestures.GestureCommandMapper.MultiplexerType.TMUX
        }
        val customPrefix = app.preferencesManager.getString("multiplexer_custom_prefix", "")
        
        // Create command send callback for gestures
        val commandCallback: ((ByteArray) -> Unit)? = if (gesturesEnabled) {
            { command ->
                tabManager.getActiveTab()?.let { tab ->
                    tab.terminal.sendText(String(command, Charsets.UTF_8))
                    android.widget.Toast.makeText(
                        this,
                        "Gesture command sent",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            null
        }

        if (pagerAdapter == null) {
            // First time setup
            pagerAdapter = TerminalPagerAdapter(
                allTabs,
                fontSize,
                fontValue,
                urlDetectionCallback,
                gesturesEnabled,
                multiplexerType,
                customPrefix,
                commandCallback
            )
            viewPager?.adapter = pagerAdapter

            // Setup TabLayoutMediator to sync TabLayout with ViewPager2
            tabLayoutMediator?.detach()
            tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = allTabs.getOrNull(position)?.getShortTitle() ?: "Tab ${position + 1}"
                tab.tag = allTabs.getOrNull(position)?.tabId
            }
            tabLayoutMediator?.attach()

            // Register page change callback
            viewPager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    tabManager.switchToTab(position)
                    Logger.d("TabTerminalActivity", "Swiped to tab $position")
                }
            })
        } else {
            // Recreate adapter with new tabs list
            val currentPosition = viewPager?.currentItem ?: 0
            pagerAdapter = TerminalPagerAdapter(
                allTabs,
                fontSize,
                fontValue,
                urlDetectionCallback,
                gesturesEnabled,
                multiplexerType,
                customPrefix,
                commandCallback
            )
            viewPager?.adapter = pagerAdapter

            // Restore position (or select last tab if adding)
            val newPosition = if (currentPosition >= allTabs.size) allTabs.size - 1 else currentPosition
            viewPager?.setCurrentItem(newPosition, false)

            // Re-attach mediator
            tabLayoutMediator?.detach()
            tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = allTabs.getOrNull(position)?.getShortTitle() ?: "Tab ${position + 1}"
                tab.tag = allTabs.getOrNull(position)?.tabId
            }
            tabLayoutMediator?.attach()
        }
    }
    
    private fun removeTabFromUI(index: Int) {
        if (swipeEnabled) {
            // Rebuild ViewPager2 adapter
            updateViewPagerAdapter()
        } else {
            // Classic mode: remove from TabLayout
            val tabLayout = binding.tabLayout
            if (index < tabLayout.tabCount) {
                tabLayout.removeTabAt(index)
            }
        }
    }

    private fun switchToTab(index: Int) {
        if (swipeEnabled) {
            // Swipe mode: update ViewPager2 position
            viewPager?.setCurrentItem(index, true)
        } else {
            // Classic mode: attach terminal to single view
            val tab = tabManager.getTab(index)
            if (tab != null) {
                // Connect terminal view to the tab's terminal emulator
                val terminal = tab.terminal
                terminalView?.attachTerminalEmulator(terminal)

                // Deactivate previous tab
                tabManager.getActiveTab()?.deactivate()

                // Activate new tab
                tab.activate()

                // Update toolbar title
                supportActionBar?.title = tab.getDisplayTitle()

                Logger.d("TabTerminalActivity", "Switched to tab: ${tab.profile.getDisplayName()}")
            }
        }
    }
    
    private fun updateTabIcon(tab: SSHTab, state: ConnectionState) {
        val tabLayout = binding.tabLayout
        
        // Find the tab by ID
        for (i in 0 until tabLayout.tabCount) {
            val tabLayoutTab = tabLayout.getTabAt(i)
            if (tabLayoutTab?.tag == tab.tabId) {
                // Update tab appearance based on connection state
                when (state) {
                    ConnectionState.CONNECTED -> {
                        // Green indicator or checkmark
                        tabLayoutTab.setIcon(R.drawable.ic_connected)
                    }
                    ConnectionState.CONNECTING -> {
                        // Orange/yellow indicator
                        tabLayoutTab.setIcon(R.drawable.ic_connecting)
                    }
                    ConnectionState.ERROR -> {
                        // Red error indicator
                        tabLayoutTab.setIcon(R.drawable.ic_error)
                    }
                    ConnectionState.DISCONNECTED -> {
                        // Gray or no icon
                        tabLayoutTab.icon = null

                        // Wave 1.1 — instead of auto-closing the tab after 2s,
                        // show a Reconnect / Close dialog so the user can resume
                        // the session in one tap. Common case: SSH timeout, server
                        // restart, brief network blip — auto-close was destroying
                        // the user's tab + scrollback unnecessarily.
                        Logger.i("TabTerminalActivity", "Tab ${tab.tabId} disconnected — offering reconnect")
                        runOnUiThread {
                            showReconnectDialog(tab)
                        }
                    }
                    else -> {}
                }
                break
            }
        }
    }
    
    /**
     * Wave 1.1 — when a tab's SSH session ends (server-side exit, timeout,
     * network blip), keep the tab and show a Reconnect / Close dialog
     * instead of auto-destroying it. The user's scrollback stays visible
     * behind the dialog so they can read the last output before deciding.
     */
    private fun showReconnectDialog(tab: SSHTab) {
        if (isFinishing || isDestroyed) return

        val tabId = tab.tabId
        val profile = tab.profile
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection closed")
            .setMessage("${profile.getDisplayName()} disconnected.\nReconnect or close the tab?")
            .setCancelable(false)
            .setPositiveButton("Reconnect") { _, _ ->
                Logger.i("TabTerminalActivity", "User chose RECONNECT for tab $tabId")
                // Close the dead tab object then start a fresh connect to the
                // same profile — keeps the slot in the tab strip.
                val idx = tabManager.getAllTabs().indexOfFirst { it.tabId == tabId }
                if (idx >= 0) tabManager.closeTab(idx)
                lifecycleScope.launch { connectToProfile(profile) }
            }
            .setNegativeButton("Close tab") { _, _ ->
                Logger.i("TabTerminalActivity", "User chose CLOSE for tab $tabId")
                val idx = tabManager.getAllTabs().indexOfFirst { it.tabId == tabId }
                if (idx >= 0) {
                    tabManager.closeTab(idx)
                    if (tabManager.getTabCount() == 0) finish()
                }
            }
            .show()
    }

    // Toolbar options menu removed - using bottom sheet menu instead
    // override fun onCreateOptionsMenu(menu: Menu): Boolean {
    //     menuInflater.inflate(R.menu.terminal_menu, menu)
    //     return true
    // }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.action_new_tab -> {
                // Open connection selector for new tab
                showConnectionSelector()
                true
            }
            R.id.action_close_tab -> {
                closeCurrentTab()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_disconnect_all -> {
                disconnectAllTabs()
                true
            }
            R.id.action_toggle_recording -> {
                toggleRecording(item)
                true
            }
            R.id.action_port_forwarding -> {
                openPortForwarding()
                true
            }
            R.id.action_view_transcripts -> {
                startActivity(Intent(this, TranscriptViewerActivity::class.java))
                true
            }
            R.id.action_command_palette -> { showCommandPalette(); true }
            R.id.action_quick_switcher -> { showQuickSwitcher(); true }
            R.id.action_broadcast_input -> { showBroadcastTargetsDialog(); true }
            R.id.action_save_workspace -> { showSaveWorkspaceDialog(); true }
            R.id.action_open_workspace -> { showOpenWorkspaceDialog(); true }
            R.id.action_history_palette -> { showHistoryPalette(); true }
            R.id.action_split_bottom -> { showSplitConnectionPicker(); true }
            R.id.action_unsplit -> { closeSplitPane(); true }
            R.id.action_mosh_handoff -> { showMoshHandoff(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * Wave 2.10 — Per-tab cache of remote shell history. Lazy-fetched on
     * first Ctrl+R for that tab so we don't pay the round-trip until the
     * user actually wants the palette.
     */
    private val historyCache = mutableMapOf<String, List<String>>()

    private fun showHistoryPalette() {
        val active = tabManager.getActiveTab()
        if (active == null) {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        val ssh = active.connection
        if (ssh == null) {
            Toast.makeText(this, "Not an SSH tab — history needs a live SSH session", Toast.LENGTH_SHORT).show()
            return
        }
        val cached = historyCache[active.tabId]
        if (cached != null) {
            showHistoryDialog(cached)
            return
        }
        Toast.makeText(this, "Fetching history…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val hist = io.github.tabssh.ssh.HistoryFetcher(ssh).fetch()
            historyCache[active.tabId] = hist
            runOnUiThread {
                if (hist.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No history found (or files unreadable)", Toast.LENGTH_LONG).show()
                } else {
                    showHistoryDialog(hist)
                }
            }
        }
    }

    private fun showHistoryDialog(history: List<String>) {
        val items = history.map { line ->
            io.github.tabssh.ui.views.PaletteDialog.Item(
                title = line,
                subtitle = null
            ) { getActiveTerminalView()?.sendText(line) }
        }
        io.github.tabssh.ui.views.PaletteDialog.show(this, "Remote history (${history.size})", items)
    }

    /**
     * Wave 2.7 — Broadcast input across tabs.
     *
     * The active tab keeps typing as normal; in addition, every keystroke is
     * fanned out to the SSH outputStream of each selected target tab.
     * Implementation: we mutate `termuxBridge.broadcastTargets` on the ACTIVE
     * tab to the list of (peer-tab outputStreams). When the user switches
     * tabs we don't currently re-thread the targets — they stay attached to
     * whichever tab was active when the dialog committed. That's the simple
     * intended semantic: "I'm typing here, mirror to those".
     */
    private val broadcastTargetIds = mutableSetOf<String>()

    private fun showBroadcastTargetsDialog() {
        val tabs = tabManager.getAllTabs()
        if (tabs.size < 2) {
            Toast.makeText(this, "Open at least 2 tabs first", Toast.LENGTH_SHORT).show()
            return
        }
        val active = tabManager.getActiveTab()
        val others = tabs.filter { it.tabId != active?.tabId }
        val labels = others.map { it.profile.getDisplayName() }.toTypedArray()
        val checked = BooleanArray(others.size) { i -> broadcastTargetIds.contains(others[i].tabId) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Broadcast input from current tab")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val tabId = others[which].tabId
                if (isChecked) broadcastTargetIds.add(tabId) else broadcastTargetIds.remove(tabId)
            }
            .setPositiveButton("Apply") { _, _ -> applyBroadcastTargets() }
            .setNeutralButton("Stop broadcasting") { _, _ ->
                broadcastTargetIds.clear()
                applyBroadcastTargets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBroadcastTargets() {
        val active = tabManager.getActiveTab() ?: return
        val targetStreams = tabManager.getAllTabs()
            .filter { broadcastTargetIds.contains(it.tabId) && it.tabId != active.tabId }
            .mapNotNull { it.termuxBridge.peerOutputStream() }
        active.termuxBridge.broadcastTargets = targetStreams
        // Clear targets on every other tab so we don't accidentally double-broadcast.
        tabManager.getAllTabs().filter { it.tabId != active.tabId }
            .forEach { it.termuxBridge.broadcastTargets = emptyList() }
        if (targetStreams.isEmpty()) {
            Toast.makeText(this, "Broadcast off", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Broadcasting to ${targetStreams.size} tab(s)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wave 2.5 — Save the currently open tabs (their connection IDs, in tab
     * order) as a named workspace. Reopening the workspace later will fan
     * out connectToProfile to each one in sequence with a small inter-open
     * delay so we don't slam every host at once.
     */
    private fun showSaveWorkspaceDialog() {
        val tabs = tabManager.getAllTabs()
        if (tabs.isEmpty()) {
            Toast.makeText(this, "No open tabs", Toast.LENGTH_SHORT).show()
            return
        }
        val edit = android.widget.EditText(this).apply {
            hint = "Workspace name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save workspace (${tabs.size} tab${if (tabs.size == 1) "" else "s"})")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val name = edit.text.toString().trim().ifBlank { "Workspace ${System.currentTimeMillis() / 1000}" }
                val ids = tabs.map { it.profile.id }
                val json = org.json.JSONArray(ids).toString()
                lifecycleScope.launch {
                    try {
                        app.database.workspaceDao().upsert(
                            io.github.tabssh.storage.database.entities.Workspace(
                                name = name,
                                connectionIdsJson = json
                            )
                        )
                        runOnUiThread {
                            Toast.makeText(this@TabTerminalActivity, "Saved workspace '$name'", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Save workspace failed", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOpenWorkspaceDialog() {
        lifecycleScope.launch {
            val all = try {
                app.database.workspaceDao().getAll()
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Load workspaces failed", e)
                emptyList()
            }
            runOnUiThread {
                if (all.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No workspaces saved", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = all.map { ws ->
                    val n = try { org.json.JSONArray(ws.connectionIdsJson).length() } catch (_: Exception) { 0 }
                    "${ws.name} ($n tab${if (n == 1) "" else "s"})"
                }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                    .setTitle("Open workspace")
                    .setItems(labels) { _, which -> openWorkspace(all[which]) }
                    .setNeutralButton("Delete…") { _, _ -> showDeleteWorkspaceDialog(all) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun openWorkspace(ws: io.github.tabssh.storage.database.entities.Workspace) {
        val ids: List<String> = try {
            val arr = org.json.JSONArray(ws.connectionIdsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Workspace ${ws.id} has malformed connection list", e)
            emptyList()
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Workspace is empty", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            var opened = 0
            var skipped = 0
            for (id in ids) {
                val profile = try { app.database.connectionDao().getConnectionById(id) } catch (_: Exception) { null }
                if (profile == null) { skipped++; continue }
                connectToProfile(profile)
                opened++
                kotlinx.coroutines.delay(400) // gentle stagger
            }
            runOnUiThread {
                Toast.makeText(
                    this@TabTerminalActivity,
                    "Opened $opened${if (skipped > 0) " (skipped $skipped missing)" else ""}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDeleteWorkspaceDialog(all: List<io.github.tabssh.storage.database.entities.Workspace>) {
        val labels = all.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete workspace")
            .setItems(labels) { _, which ->
                val ws = all[which]
                lifecycleScope.launch {
                    try {
                        app.database.workspaceDao().delete(ws)
                        runOnUiThread {
                            Toast.makeText(this@TabTerminalActivity, "Deleted '${ws.name}'", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Logger.e("TabTerminalActivity", "Delete workspace failed", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Wave 2.8 — Minimal split view. One bottom pane per tab; the pane is its
     * own SSHTab (NOT in tabManager) anchored to the activity. Tap a pane to
     * focus; getActiveTerminalView() routes keystrokes accordingly. Closing
     * the pane disconnects its SSH session and hides the layout slot.
     *
     * What this is NOT: nested splits, horizontal split, multi-pane grids,
     * pane resize. The use case is "tail logs in the bottom while typing
     * commands in the top" on a phone — anything richer is a tablet
     * problem and not in scope yet.
     */
    private var splitTab: SSHTab? = null
    private var bottomTerminalView: TerminalView? = null
    private var bottomPaneFocused: Boolean = false

    private fun showSplitConnectionPicker() {
        if (splitTab != null) {
            Toast.makeText(this, "Already split — close the bottom pane first", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val recent = try {
                app.database.connectionDao().getFrequentlyUsedConnections(20)
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Recent fetch failed for split picker", e)
                emptyList()
            }
            runOnUiThread {
                if (recent.isEmpty()) {
                    Toast.makeText(this@TabTerminalActivity, "No saved connections", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = recent.map { it.getDisplayName() }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                    .setTitle("Split — open in bottom pane")
                    .setItems(labels) { _, which -> openSplitWithProfile(recent[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun openSplitWithProfile(profile: ConnectionProfile) {
        val pane = findViewById<android.widget.FrameLayout>(R.id.split_bottom_pane)
        val term = findViewById<TerminalView>(R.id.split_bottom_terminal)
        bottomTerminalView = term
        pane.visibility = View.VISIBLE
        // Tap-to-focus indicator: simple border swap.
        pane.setOnClickListener { setBottomPaneFocused(true) }
        term.setOnClickListener { setBottomPaneFocused(true) }
        // Tap on top FrameLayout (parent of viewPager / classic terminalView) to refocus top.
        findViewById<View>(R.id.view_pager)?.setOnClickListener { setBottomPaneFocused(false) }
        terminalView?.setOnClickListener { setBottomPaneFocused(false) }

        lifecycleScope.launch {
            val ssh = if (profile.protocol.equals("telnet", ignoreCase = true)) null
                else app.sshSessionManager.connectToServer(profile)
            // Telnet branch (separate path)
            if (profile.protocol.equals("telnet", ignoreCase = true)) {
                val telnet = io.github.tabssh.ssh.connection.TelnetConnection(profile.host, profile.port.takeIf { it > 0 } ?: 23)
                val newTab = SSHTab(profile, io.github.tabssh.terminal.TermuxBridge())
                term.attachTerminalEmulator(newTab.termuxBridge)
                kotlinx.coroutines.delay(150)
                if (newTab.connect(telnet)) {
                    splitTab = newTab
                    runOnUiThread { Toast.makeText(this@TabTerminalActivity, "Split (telnet) ready", Toast.LENGTH_SHORT).show() }
                } else {
                    runOnUiThread {
                        pane.visibility = View.GONE
                        Toast.makeText(this@TabTerminalActivity, "Split telnet failed", Toast.LENGTH_LONG).show()
                    }
                }
                return@launch
            }
            if (ssh == null) {
                runOnUiThread {
                    pane.visibility = View.GONE
                    Toast.makeText(this@TabTerminalActivity, "Split SSH connect failed", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val newTab = SSHTab(profile, io.github.tabssh.terminal.TermuxBridge())
            term.attachTerminalEmulator(newTab.termuxBridge)
            kotlinx.coroutines.delay(150)
            if (newTab.connect(ssh)) {
                splitTab = newTab
                runOnUiThread {
                    Toast.makeText(this@TabTerminalActivity, "Split: ${profile.getDisplayName()}", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    pane.visibility = View.GONE
                    Toast.makeText(this@TabTerminalActivity, "Split SSH wire failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun closeSplitPane() {
        val tab = splitTab
        if (tab == null) {
            Toast.makeText(this, "No split pane", Toast.LENGTH_SHORT).show()
            return
        }
        try { tab.disconnect() } catch (e: Exception) { Logger.w("TabTerminalActivity", "Split tab disconnect: ${e.message}") }
        splitTab = null
        bottomTerminalView = null
        bottomPaneFocused = false
        findViewById<View>(R.id.split_bottom_pane).visibility = View.GONE
        Toast.makeText(this, "Split pane closed", Toast.LENGTH_SHORT).show()
    }

    private fun setBottomPaneFocused(focus: Boolean) {
        if (focus && splitTab == null) return
        bottomPaneFocused = focus
        // No theme-aware tinting yet — just announce so user notices.
        if (focus) {
            Toast.makeText(this, "Bottom pane focused", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wave 2.X — Mosh handoff. Runs `mosh-server new` over the active SSH
     * exec channel, parses MOSH CONNECT, and shows the user a copy-able
     * `mosh -p PORT user@host` command. NOT real Mosh — see
     * [io.github.tabssh.protocols.mosh.MoshHandoff] for the rationale.
     */
    private fun showMoshHandoff() {
        val active = tabManager.getActiveTab()
        if (active == null) {
            Toast.makeText(this, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }
        val ssh = active.connection
        if (ssh == null) {
            Toast.makeText(this, "Mosh handoff needs an active SSH session", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Bootstrapping mosh-server…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = io.github.tabssh.protocols.mosh.MoshHandoff.bootstrap(
                ssh,
                username = active.profile.username,
                host = active.profile.host
            )
            runOnUiThread {
                when (res) {
                    is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Success -> {
                        val info = res.info
                        val cmd = info.toClientCommand()
                        val termuxLauncher = io.github.tabssh.protocols.mosh.TermuxMoshLauncher
                        val termuxStatus = termuxLauncher.status(this@TabTerminalActivity)
                        val builder = androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                            .setTitle("Mosh handoff ready")
                        when (termuxStatus) {
                            is io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.Ready,
                            is io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.Unknown -> {
                                builder.setMessage(
                                    "mosh-server is listening on UDP :${info.port}.\n\n" +
                                    "Termux is installed — TabSSH can hand off to it directly. " +
                                    "Tap **Open in Termux** to start the Mosh session there. " +
                                    "Closing your SSH tab does NOT kill mosh-server.\n\n" +
                                    "If Termux refuses, ensure `allow-external-apps=true` is set " +
                                    "in `${io.github.tabssh.protocols.mosh.TermuxMoshLauncher.TERMUX_PROPS_HINT}` " +
                                    "and that you've granted the RUN_COMMAND permission."
                                )
                                .setPositiveButton("Open in Termux") { _, _ ->
                                    val ok = termuxLauncher.launch(
                                        this@TabTerminalActivity,
                                        info.host, info.port, info.keyBase64, info.username
                                    )
                                    if (!ok) {
                                        Toast.makeText(this@TabTerminalActivity,
                                            "Termux refused — check RUN_COMMAND permission + allow-external-apps", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .setNeutralButton("Copy command") { _, _ ->
                                    val cb = getSystemService(android.content.ClipboardManager::class.java)
                                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("mosh handoff", cmd))
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                            }
                            io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.MoshNotInstalled -> {
                                builder.setMessage(
                                    "mosh-server is listening on UDP :${info.port}.\n\n" +
                                    "Termux is installed but `mosh-client` isn't available. Open " +
                                    "Termux and run:\n  pkg install mosh\n\nThen come back and " +
                                    "tap Mosh handoff again.\n\nMeanwhile, copy this command:\n$cmd"
                                )
                                .setPositiveButton("Copy command") { _, _ ->
                                    val cb = getSystemService(android.content.ClipboardManager::class.java)
                                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("mosh handoff", cmd))
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                            }
                            io.github.tabssh.protocols.mosh.TermuxMoshLauncher.Status.TermuxMissing -> {
                                builder.setMessage(
                                    "mosh-server is listening on UDP :${info.port}.\n\n" +
                                    "Install Termux + mosh to attach. F-Droid is the recommended " +
                                    "source. Without it, you'll need to run this command on any " +
                                    "Mosh-capable client:\n\n$cmd"
                                )
                                .setPositiveButton("Install Termux") { _, _ ->
                                    termuxLauncher.openTermuxListing(this@TabTerminalActivity)
                                }
                                .setNeutralButton("Copy command") { _, _ ->
                                    val cb = getSystemService(android.content.ClipboardManager::class.java)
                                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("mosh handoff", cmd))
                                    Toast.makeText(this@TabTerminalActivity, "Copied", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                            }
                        }
                        builder.show()
                    }
                    is io.github.tabssh.protocols.mosh.MoshHandoff.Result.Error -> {
                        showError(res.message, "Mosh handoff failed")
                    }
                }
            }
        }
    }

    private fun openPortForwarding() {
        val activeTab = tabManager.getActiveTab()
        if (activeTab == null) {
            Toast.makeText(this, "No active connection", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, PortForwardingActivity::class.java)
        intent.putExtra("connection_id", activeTab.profile.id)
        startActivity(intent)
    }
    
    private fun toggleRecording(menuItem: MenuItem) {
        val activeTab = tabManager.getActiveTab() ?: return
        
        if (activeTab.sessionRecorder?.isRecording() == true) {
            // Stop recording
            activeTab.sessionRecorder?.stopRecording()
            menuItem.title = "Start Recording"
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Start recording
            if (activeTab.sessionRecorder == null) {
                activeTab.sessionRecorder = io.github.tabssh.terminal.recording.SessionRecorder(
                    this,
                    activeTab.profile.getDisplayName()
                )
            }
            activeTab.sessionRecorder?.startRecording()
            menuItem.title = "Stop Recording"
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Handle volume keys for font size control (if enabled)
        val volumeKeysEnabled = app.preferencesManager.getBoolean("volume_keys_font_size", true)
        if (volumeKeysEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    adjustFontSize(+2)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    adjustFontSize(-2)
                    return true
                }
            }
        }

        // Handle keyboard shortcuts
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_T -> {
                    showConnectionSelector()
                    return true
                }
                KeyEvent.KEYCODE_W -> {
                    closeCurrentTab()
                    return true
                }
                KeyEvent.KEYCODE_TAB -> {
                    if (event.isShiftPressed) {
                        tabManager.switchToPreviousTab()
                    } else {
                        tabManager.switchToNextTab()
                    }
                    return true
                }
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                    val tabNumber = keyCode - KeyEvent.KEYCODE_0
                    tabManager.switchToTabNumber(tabNumber)
                    return true
                }
                // Wave 2.6 — palette / switcher
                KeyEvent.KEYCODE_K -> { showCommandPalette(); return true }
                KeyEvent.KEYCODE_J -> { showQuickSwitcher(); return true }
                // Wave 2.10 — remote history palette
                KeyEvent.KEYCODE_R -> { showHistoryPalette(); return true }
            }
        }

        // Let terminal view handle other keys
        val activeTerminal = getActiveTerminalView()
        return activeTerminal?.onKeyDown(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }

    /**
     * Get the currently active terminal view (works in both classic and swipe modes)
     */
    private fun getActiveTerminalView(): TerminalView? {
        // Wave 2.8 — split takes precedence: if user has tapped the bottom pane
        // we route input there, regardless of which top tab is selected.
        if (bottomPaneFocused && bottomTerminalView != null) return bottomTerminalView
        return if (swipeEnabled) {
            // Get the currently visible page in ViewPager2
            val currentItem = viewPager?.currentItem ?: return null
            val holder = (viewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                ?.findViewHolderForAdapterPosition(currentItem) as? TerminalPagerAdapter.TerminalViewHolder
            holder?.terminalView
        } else {
            terminalView
        }
    }

    /**
     * Adjust terminal font size by delta
     */
    /**
     * Wave 2.6 — Command palette (Ctrl+K). Lists every navigable destination
     * + tab/connection actions; fuzzy-filterable from the search box.
     */
    private fun showCommandPalette() {
        val items = mutableListOf<io.github.tabssh.ui.views.PaletteDialog.Item>()
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Settings", "Open settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Theme Editor", "Create or edit a custom terminal theme") {
            startActivity(ThemeEditorActivity.createIntent(this))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("SSH Keys", "Manage SSH private keys & certificates") {
            startActivity(Intent(this, KeyManagementActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Snippets", "Reusable command snippets") {
            startActivity(Intent(this, SnippetManagerActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Port Forwarding", "Local / Remote / SOCKS tunnels") {
            startActivity(Intent(this, PortForwardingActivity::class.java))
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Find in scrollback", "Search current tab's history") {
            showFindDialog()
        }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Close current tab", null) { closeCurrentTab() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Increase font size", "Ctrl+= (or Volume Up)") { adjustFontSize(+2) }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Decrease font size", "Ctrl+- (or Volume Down)") { adjustFontSize(-2) }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Toggle keyboard", null) { toggleKeyboard() }
        items += io.github.tabssh.ui.views.PaletteDialog.Item("Paste from clipboard", null) { pasteFromClipboard() }
        io.github.tabssh.ui.views.PaletteDialog.show(this, "Command Palette", items)
    }

    /**
     * Wave 2.6 — Quick switcher (Ctrl+J). Lists open tabs first, then recent
     * connections — pick one to switch / open.
     */
    private fun showQuickSwitcher() {
        val items = mutableListOf<io.github.tabssh.ui.views.PaletteDialog.Item>()
        // Open tabs
        tabManager.getAllTabs().forEachIndexed { index, tab ->
            items += io.github.tabssh.ui.views.PaletteDialog.Item(
                "Tab ${index + 1}: ${tab.profile.getDisplayName()}",
                "Open · ${tab.connectionState.value}"
            ) { tabManager.switchToTabNumber(index + 1) }
        }
        // Recent connections (top 20 most-used)
        lifecycleScope.launch {
            try {
                val recent = app.database.connectionDao().getFrequentlyUsedConnections(20)
                runOnUiThread {
                    recent.forEach { profile ->
                        items += io.github.tabssh.ui.views.PaletteDialog.Item(
                            profile.getDisplayName(),
                            "Connect · ${profile.username}@${profile.host}:${profile.port}"
                        ) {
                            lifecycleScope.launch { connectToProfile(profile) }
                        }
                    }
                    io.github.tabssh.ui.views.PaletteDialog.show(this@TabTerminalActivity, "Quick Switcher", items)
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load recent connections for switcher", e)
                runOnUiThread {
                    io.github.tabssh.ui.views.PaletteDialog.show(this@TabTerminalActivity, "Quick Switcher", items)
                }
            }
        }
    }

    private fun adjustFontSize(delta: Int) {
        val view = getActiveTerminalView()
        view?.let {
            val currentSize = it.getFontSize()
            val newSize = (currentSize + delta).coerceIn(8, 32)
            it.setFontSize(newSize)

            // Save to preferences
            app.preferencesManager.setInt("terminal_font_size", newSize)

            // Show toast with current size
            Toast.makeText(this, "Font Size: ${newSize}sp", Toast.LENGTH_SHORT).show()

            Logger.d("TabTerminalActivity", "Font size adjusted: $currentSize → $newSize")
        }
    }
    
    private fun sendKey(key: String) {
        val terminal = getActiveTerminalView()
        when (key) {
            "ctrl" -> {
                // Toggle ctrl mode or show ctrl key menu
                showToast("Ctrl key pressed")
            }
            "alt" -> {
                showToast("Alt key pressed")
            }
            "esc" -> {
                terminal?.sendKeySequence("\u001B")
            }
            "tab" -> {
                terminal?.sendKeySequence("\t")
            }
            "up" -> {
                terminal?.sendKeySequence("\u001B[A")
            }
            "down" -> {
                terminal?.sendKeySequence("\u001B[B")
            }
        }
    }

    private fun toggleKeyboard() {
        val terminalView = getActiveTerminalView() ?: return
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager

        // Always grab focus first so the IME has a target.
        terminalView.requestFocus()

        // toggleSoftInput(SHOW_IMPLICIT) silently no-ops on the first tap when
        // the framework hasn't seen us as the active input client yet (Issue
        // #39). Use WindowInsetsCompat to read actual IME visibility and
        // drive show/hide explicitly.
        val rootInsets = androidx.core.view.ViewCompat
            .getRootWindowInsets(terminalView)
        val imeVisible = rootInsets?.isVisible(
            androidx.core.view.WindowInsetsCompat.Type.ime()
        ) == true

        if (imeVisible) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
            Logger.d("TabTerminalActivity", "IME hidden")
        } else {
            imm.showSoftInput(
                terminalView,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
            )
            Logger.d("TabTerminalActivity", "IME shown")
        }
    }

    private fun openFileManager() {
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null && activeTab.isConnected()) {
            // Open SFTP file manager
            val intent = Intent(this, SFTPActivity::class.java).apply {
                putExtra(SFTPActivity.EXTRA_CONNECTION_ID, activeTab.profile.id)
            }
            startActivity(intent)
        } else {
            showToast("Connect to a server first")
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
            getActiveTerminalView()?.sendText(text.toString())
        }
    }
    
    private fun showConnectionSelector() {
        // This would show a dialog or start ConnectionEditActivity
        val intent = Intent(this, ConnectionEditActivity::class.java)
        startActivity(intent)
    }

    /**
     * Show snippets dialog for quick command access
     */
    private fun showSnippetsDialog() {
        lifecycleScope.launch {
            try {
                val snippets = app.database.snippetDao().getFrequentlyUsedSnippets(20)

                if (snippets.isEmpty()) {
                    // Show create snippet dialog if no snippets exist
                    showToast("No snippets yet. Create one first!")
                    showCreateSnippetDialog()
                    return@launch
                }

                // Build snippet list
                val snippetNames = snippets.map { snippet ->
                    if (snippet.hasVariables()) {
                        "${snippet.name} ${snippet.category}"
                    } else {
                        "${snippet.name} - ${snippet.category}"
                    }
                }.toTypedArray()

                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                        .setTitle("Insert Snippet")
                        .setItems(snippetNames) { _, which ->
                            val snippet = snippets[which]
                            insertSnippet(snippet)
                        }
                        .setNeutralButton("Manage Snippets") { _, _ ->
                            showManageSnippetsMenu()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load snippets", e)
                showError("Failed to load snippets", "Error")
            }
        }
    }

    /**
     * Insert a snippet into the active terminal
     */
    private fun insertSnippet(snippet: io.github.tabssh.storage.database.entities.Snippet) {
        lifecycleScope.launch {
            try {
                // Check if snippet has variables
                if (snippet.hasVariables()) {
                    // Show dialog to fill in variables
                    showVariablesDialog(snippet)
                } else {
                    // Insert directly
                    val terminal = getActiveTerminalView()
                    terminal?.sendText(snippet.command)

                    // Increment usage count
                    app.database.snippetDao().incrementUsageCount(snippet.id)

                    Logger.d("TabTerminalActivity", "Inserted snippet: ${snippet.name}")
                }
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to insert snippet", e)
                showError("Failed to insert snippet", "Error")
            }
        }
    }

    /**
     * Wave 2.1 — Show dialog to fill snippet variables. Honours `{?name:default|hint}`
     * declared defaults and hints, and recalls the last value used for each
     * variable name (per snippet) from SharedPreferences so users don't retype
     * the same hostnames / paths over and over.
     */
    private fun showVariablesDialog(snippet: io.github.tabssh.storage.database.entities.Snippet) {
        val specs = snippet.getVariableSpecs()
        if (specs.isEmpty()) {
            getActiveTerminalView()?.sendText(snippet.command)
            return
        }
        val recallPrefs = getSharedPreferences("snippet_var_recall", android.content.Context.MODE_PRIVATE)
        val inputs = mutableListOf<android.widget.EditText>()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        specs.forEach { spec ->
            val label = android.widget.TextView(this).apply {
                text = spec.name
                textSize = 14f
            }
            val input = android.widget.EditText(this).apply {
                hint = spec.hint ?: "Enter value for ${spec.name}"
                // Pre-fill: last-used > declared default > blank
                val recall = recallPrefs.getString("${snippet.id}/${spec.name}", null)
                val initial = recall ?: spec.default
                if (!initial.isNullOrEmpty()) {
                    setText(initial)
                    setSelection(text.length)
                }
            }
            inputs.add(input)
            layout.addView(label)
            layout.addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Fill Variables")
            .setView(layout)
            .setPositiveButton("Insert") { _, _ ->
                val values = mutableMapOf<String, String>()
                val recallEdits = recallPrefs.edit()
                specs.forEachIndexed { i, spec ->
                    val v = inputs[i].text.toString()
                    values[spec.name] = v
                    if (v.isNotBlank()) recallEdits.putString("${snippet.id}/${spec.name}", v)
                }
                recallEdits.apply()

                getActiveTerminalView()?.sendText(snippet.applyVariables(values))
                lifecycleScope.launch {
                    app.database.snippetDao().incrementUsageCount(snippet.id)
                }
                Logger.d("TabTerminalActivity", "Inserted snippet with variables: ${snippet.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show manage snippets menu
     */
    private fun showManageSnippetsMenu() {
        val options = arrayOf("Create New Snippet", "View All Snippets", "Search Snippets")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Manage Snippets")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateSnippetDialog()
                    1 -> showAllSnippetsDialog()
                    2 -> showSearchSnippetsDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show dialog to create a new snippet
     */
    private fun showCreateSnippetDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        val inputName = android.widget.EditText(this).apply {
            hint = "Snippet name"
        }
        val inputCommand = android.widget.EditText(this).apply {
            hint = "Command (use {variable} for placeholders)"
        }
        val inputCategory = android.widget.EditText(this).apply {
            hint = "Category (optional)"
            setText("General")
        }

        layout.addView(inputName)
        layout.addView(inputCommand)
        layout.addView(inputCategory)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create Snippet")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = inputName.text.toString().trim()
                val command = inputCommand.text.toString().trim()
                val category = inputCategory.text.toString().trim().ifBlank { "General" }

                if (name.isNotBlank() && command.isNotBlank()) {
                    lifecycleScope.launch {
                        val snippet = io.github.tabssh.storage.database.entities.Snippet(
                            name = name,
                            command = command,
                            category = category
                        )
                        app.database.snippetDao().insertSnippet(snippet)
                        showToast("Snippet created: $name")
                        Logger.d("TabTerminalActivity", "Created snippet: $name")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show all snippets dialog
     */
    private fun showAllSnippetsDialog() {
        lifecycleScope.launch {
            val allSnippets = app.database.snippetDao().getFrequentlyUsedSnippets(100)

            if (allSnippets.isEmpty()) {
                showToast("No snippets yet")
                return@launch
            }

            val snippetNames = allSnippets.map { "${it.name} - ${it.category}" }.toTypedArray()

            runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                    .setTitle("All Snippets")
                    .setItems(snippetNames) { _, which ->
                        insertSnippet(allSnippets[which])
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    /**
     * Show search snippets dialog
     */
    private fun showSearchSnippetsDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Search snippets..."
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Search Snippets")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) {
                    searchAndShowSnippets(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Search and show matching snippets
     */
    private fun searchAndShowSnippets(query: String) {
        lifecycleScope.launch {
            app.database.snippetDao().searchSnippets(query).collect { results ->
                if (results.isEmpty()) {
                    showToast("No matching snippets")
                    return@collect
                }

                val snippetNames = results.map { "${it.name} - ${it.category}" }.toTypedArray()

                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@TabTerminalActivity)
                        .setTitle("Search Results")
                        .setItems(snippetNames) { _, which ->
                            insertSnippet(results[which])
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }
        }
    }

    private fun closeCurrentTab() {
        CoroutineScope(Dispatchers.Main).launch {
            val activeIndex = tabManager.getActiveTabIndex()
            if (activeIndex >= 0) {
                tabManager.closeTab(activeIndex)
            }
        }
    }
    
    private fun disconnectAllTabs() {
        CoroutineScope(Dispatchers.Main).launch {
            tabManager.closeAllTabs()
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show a password prompt dialog and suspend until the user responds.
     * Returns the entered password, or null if the user cancelled.
     */
    private suspend fun promptForPassword(message: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val editText = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Password"
            }
            val padding = (16 * resources.displayMetrics.density).toInt()
            val container = android.widget.FrameLayout(this).apply {
                setPadding(padding, 0, padding, 0)
                addView(editText)
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Authentication Required")
                .setMessage(message)
                .setView(container)
                .setPositiveButton("Connect") { _, _ ->
                    if (cont.isActive) cont.resume(editText.text.toString()) {}
                }
                .setNegativeButton("Cancel") { _, _ ->
                    if (cont.isActive) cont.resume(null) {}
                }
                .setCancelable(false)
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    
    override fun onResume() {
        super.onResume()

        // Restore active tab if needed
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null) {
            if (!swipeEnabled) {
                // In classic mode, reconnect terminal view to active tab
                val terminal = activeTab.terminal
                terminalView?.initialize(terminal.getRows(), terminal.getCols())
            }
            activeTab.activate()
            supportActionBar?.title = activeTab.getDisplayTitle()
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // Save session state
        CoroutineScope(Dispatchers.Main).launch {
            tabManager.saveTabState()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel performance overlay updates
        performanceUpdateJob?.cancel()
        
        Logger.d("TabTerminalActivity", "Terminal activity destroyed")
        tabManager.cleanup()
    }
    
    /**
     * Confirm dialog for menu-based "close all" action
     */
    private fun showConfirmCloseDialog() {
        val activeCount = tabManager.getTabCount()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Close All Connections")
            .setMessage("This will close all $activeCount SSH connections. Continue?")
            .setPositiveButton("Close All") { _, _ ->
                Logger.i("TabTerminalActivity", "Closing all $activeCount connections")
                disconnectAllTabs()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupCustomKeyboard() {
        // Load keyboard row count from preferences (default 3, max 5)
        val rowCount = app.preferencesManager.getKeyboardRowCount()
        binding.multiRowKeyboard.setRowCount(rowCount)

        // Load custom layout from preferences if available
        val layoutJson = app.preferencesManager.getKeyboardLayoutJson()
        Logger.d("TabTerminalActivity", "Custom keyboard layout JSON length=${layoutJson?.length ?: 0}, rowCount=$rowCount")
        if (layoutJson != null) {
            try {
                val savedLayout = io.github.tabssh.ui.keyboard.KeyboardLayoutManager.parseLayoutJson(layoutJson)
                binding.multiRowKeyboard.setLayout(savedLayout)
                Logger.i("TabTerminalActivity", "Loaded custom keyboard layout: ${savedLayout.size} rows, ${savedLayout.sumOf { it.size }} keys")
            } catch (e: Exception) {
                Logger.e("TabTerminalActivity", "Failed to load keyboard layout, using defaults", e)
                binding.multiRowKeyboard.resetToDefault()
            }
        } else {
            Logger.d("TabTerminalActivity", "No saved layout, using default keyboard")
            binding.multiRowKeyboard.resetToDefault()
        }

        binding.multiRowKeyboard.setOnKeyClickListener { key ->
            handleCustomKeyPress(key)
        }

        binding.multiRowKeyboard.setOnToggleClickListener {
            toggleCustomKeyboard()
        }

        // Bridge bar modifier state into the terminal so IME letters honour
        // sticky CTL/ALT (Issue #37). Also wire the inverse — when the
        // terminal consumes a one-shot modifier, the bar UI must reset.
        binding.multiRowKeyboard.setOnModifierChangedListener { modifier ->
            val tv = getActiveTerminalView() ?: return@setOnModifierChangedListener
            tv.setPendingModifier(modifier)
            tv.onModifierConsumed = {
                binding.multiRowKeyboard.clearModifier()
            }
        }

        Logger.d("TabTerminalActivity", "Multi-row keyboard initialized with $rowCount rows")
    }

    private fun handleCustomKeyPress(key: io.github.tabssh.ui.keyboard.KeyboardKey) {
        Logger.d("TabTerminalActivity", "Custom key pressed: ${key.label} id=${key.id} sequence=${key.keySequence.map { it.code }}")
        val terminal = getActiveTerminalView()

        when (key.id) {
            "PASTE" -> {
                Logger.d("TabTerminalActivity", "Paste action")
                pasteFromClipboard()
            }
            "TOGGLE" -> {
                Logger.d("TabTerminalActivity", "Toggle keyboard action")
                toggleCustomKeyboard()
            }
            else -> {
                if (key.keySequence.isNotEmpty()) {
                    // If the bar has CTL/ALT latched and the key is a single
                    // ASCII character (typical for symbol/letter keys), apply
                    // the modifier here so chords like CTL+/ also work from
                    // the bar even without the IME path.
                    val seq = key.keySequence
                    val applied = if (seq.length == 1 &&
                        terminal != null &&
                        terminal.sendCharWithPendingModifier(seq[0])
                    ) {
                        true
                    } else {
                        false
                    }
                    if (!applied) {
                        terminal?.sendText(seq)
                    }
                } else {
                    Logger.w("TabTerminalActivity", "Key ${key.label} has empty sequence")
                }
            }
        }
    }
    
    private fun toggleCustomKeyboard() {
        // Toggle the system soft keyboard when user taps keyboard icon
        // This lets users show/hide the main keyboard while keeping custom bar visible
        toggleKeyboard()
    }

    private fun hideCustomKeyboardBar() {
        customKeyboardVisible = false
        binding.multiRowKeyboard.visibility = android.view.View.GONE
    }

    private fun showCustomKeyboardBar() {
        customKeyboardVisible = true
        binding.multiRowKeyboard.visibility = android.view.View.VISIBLE
    }
}
