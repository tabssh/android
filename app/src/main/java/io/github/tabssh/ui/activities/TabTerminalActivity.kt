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
    private lateinit var keyboardLayoutManager: io.github.tabssh.ui.keyboard.KeyboardLayoutManager
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
    
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabManager.getTabCount() > 0) {
                    // Ask for confirmation before closing all tabs
                    showConfirmCloseDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
        // Setup button click listeners
        binding.btnKeyboard.setOnClickListener {
            toggleKeyboard()
            hideBottomActionBar()
        }
        
        binding.btnSnippets.setOnClickListener {
            showSnippetsDialog()
            hideBottomActionBar()
        }
        
        binding.btnFiles.setOnClickListener {
            openFileManager()
            hideBottomActionBar()
        }
        
        binding.btnPaste.setOnClickListener {
            pasteFromClipboard()
            hideBottomActionBar()
        }
        
        binding.btnMenu.setOnClickListener {
            showTerminalMenu()
            hideBottomActionBar()
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

            // Wait for user response (60-second timeout for safety)
            try {
                val responded = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                if (!responded) {
                    Logger.w("TabTerminalActivity", "Host key dialog timed out - rejecting")
                }
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

            // Wait for user response (60-second timeout for safety)
            try {
                val responded = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                if (!responded) {
                    Logger.w("TabTerminalActivity", "Host key changed dialog timed out - rejecting")
                }
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
            copyTerminalText()
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
                // Load font size from preferences
                val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)
                setFontSize(fontSize)

                // Set up URL detection handler
                val urlDetectionEnabled = app.preferencesManager.getBoolean("detect_urls", true)
                if (urlDetectionEnabled) {
                    // URL detection callback
                    onUrlDetected = { url ->
                        showUrlDialog(url)
                    }
                    
                    // Context menu callback for long press on text
                    onContextMenuRequested = { x, y ->
                        showTextContextMenu(x, y)
                    }
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
    private fun showTextContextMenu(x: Float, y: Float) {
        val items = arrayOf("Copy", "Paste", "Select All", "Share Session", "Cancel")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Terminal Actions")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> copyTerminalText() // Copy
                    1 -> pasteFromClipboard() // Paste
                    2 -> selectAllText() // Select All
                    3 -> shareSession() // Share
                    4 -> dialog.dismiss() // Cancel
                }
            }
            .show()
    }
    
    /**
     * Copy visible terminal text to clipboard
     */
    private fun copyTerminalText() {
        val terminal = getActiveTerminalView()
        val text = "" // TODO: Implement getVisibleText() method on TerminalView
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Terminal", text)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(this, "Terminal text copied", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Select all terminal text (placeholder for future implementation)
     */
    private fun selectAllText() {
        Toast.makeText(this, "✓ Terminal text copied", Toast.LENGTH_SHORT).show()
        // Simple placeholder - full implementation would need terminal buffer access
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
                // Try embedded JSON first (works for quick-connect / unsaved profiles)
                val profile: ConnectionProfile? = if (connectionProfileJson != null) {
                    try {
                        com.google.gson.Gson().fromJson(connectionProfileJson, ConnectionProfile::class.java)
                    } catch (e: Exception) {
                        Logger.w("TabTerminalActivity", "Failed to decode profile JSON, falling back to DB", e)
                        null
                    }
                } else null
                    ?: withContext(Dispatchers.IO) {
                        app.database.connectionDao().getConnectionById(connectionProfileId)
                    }

                if (profile != null) {
                    Logger.d("TabTerminalActivity", "Found profile: ${profile.name}")
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
            
            // Create SSH connection
            Logger.d("TabTerminalActivity", "Creating SSH connection via SSHSessionManager")
            val sshConnection = app.sshSessionManager.connectToServer(profile)
            
            if (sshConnection != null) {
                Logger.i("TabTerminalActivity", "✅ SSH connection established, creating tab")
                
                // Create new tab with the connection
                val tab = tabManager.createTab(profile)

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
                    
                    // Connect the tab's terminal to SSH streams
                    Logger.i("TabTerminalActivity", "🔌 Connecting terminal to SSH streams...")
                    val connected = tab.connect(sshConnection)
                    if (connected) {
                        Logger.i("TabTerminalActivity", "✅✅✅ TERMINAL CONNECTED SUCCESSFULLY to ${profile.getDisplayName()}")
                        showToast("Connected to ${profile.getDisplayName()}")
                        
                        // Show connection success notification
                        io.github.tabssh.utils.NotificationHelper.showConnectionSuccess(
                            this,
                            profile.getDisplayName(),
                            profile.username
                        )
                    } else {
                        Logger.e("TabTerminalActivity", "❌❌❌ Failed to connect terminal to SSH for ${profile.getDisplayName()}")
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
                    Logger.e("TabTerminalActivity", "❌ Failed to create tab for ${profile.getDisplayName()}")
                    showError("Failed to create terminal tab", "Error")
                }
            } else {
                // Connection failed - try to get detailed error from last connection attempt
                Logger.e("TabTerminalActivity", "❌❌❌ SSH connection returned NULL for ${profile.getDisplayName()}")
                
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

        // Get font size preference
        val fontSize = app.preferencesManager.getInt("terminal_font_size", 14)

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
                    }
                    else -> {}
                }
                break
            }
        }
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
            else -> super.onOptionsItemSelected(item)
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
        val terminalView = getActiveTerminalView()
        if (terminalView != null) {
            val inputMethodManager = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            // Make sure terminal has focus first
            terminalView.requestFocus()
            // Use toggleSoftInput which reliably toggles the keyboard state
            inputMethodManager.toggleSoftInput(
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                0
            )
            Logger.d("TabTerminalActivity", "Toggled keyboard for terminal")
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
     * Show dialog to fill in snippet variables
     */
    private fun showVariablesDialog(snippet: io.github.tabssh.storage.database.entities.Snippet) {
        val variables = snippet.getVariables()
        val values = mutableMapOf<String, String>()
        val inputs = mutableListOf<android.widget.EditText>()

        // Create linear layout with inputs for each variable
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        variables.forEach { varName ->
            val label = android.widget.TextView(this).apply {
                text = varName
                textSize = 14f
            }
            val input = android.widget.EditText(this).apply {
                hint = "Enter value for $varName"
            }
            inputs.add(input)

            layout.addView(label)
            layout.addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Fill Variables")
            .setView(layout)
            .setPositiveButton("Insert") { _, _ ->
                // Collect values
                variables.forEachIndexed { index, varName ->
                    values[varName] = inputs[index].text.toString()
                }

                // Apply variables and insert
                val command = snippet.applyVariables(values)
                getActiveTerminalView()?.sendText(command)

                // Increment usage count
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
    
    private fun showConfirmCloseDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Close All Connections")
            .setMessage("This will close all SSH connections. Continue?")
            .setPositiveButton("Close All") { _, _ ->
                disconnectAllTabs()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupCustomKeyboard() {
        keyboardLayoutManager = io.github.tabssh.ui.keyboard.KeyboardLayoutManager(this, app.preferencesManager)
        
        val layout = keyboardLayoutManager.getLayout()
        binding.customKeyboard.setLayout(layout)
        
        binding.customKeyboard.setOnKeyClickListener { key ->
            handleCustomKeyPress(key)
        }
        
        binding.customKeyboard.setOnToggleClickListener {
            toggleCustomKeyboard()
        }
        
        Logger.d("TabTerminalActivity", "Custom keyboard initialized")
    }
    
    private fun handleCustomKeyPress(key: io.github.tabssh.ui.keyboard.KeyboardKey) {
        val terminal = getActiveTerminalView()
        
        when (key.id) {
            "PASTE" -> pasteFromClipboard()
            "TOGGLE" -> toggleCustomKeyboard()
            else -> {
                if (key.keySequence.isNotEmpty()) {
                    terminal?.sendText(key.keySequence)
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
        binding.customKeyboard.visibility = android.view.View.GONE
    }

    private fun showCustomKeyboardBar() {
        customKeyboardVisible = true
        binding.customKeyboard.visibility = android.view.View.VISIBLE
    }
}
