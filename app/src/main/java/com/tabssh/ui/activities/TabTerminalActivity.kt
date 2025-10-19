package com.tabssh.ui.activities
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import com.tabssh.ssh.connection.SSHConnection
import com.tabssh.ui.views.TerminalView
import com.tabssh.R
import com.tabssh.TabSSHApplication
import com.tabssh.databinding.ActivityTabTerminalBinding
import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.ssh.connection.ConnectionState
import com.tabssh.ui.tabs.SSHTab
import com.tabssh.ui.tabs.TabManager
import com.tabssh.ui.tabs.TabManagerListener
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Main terminal activity with tabbed SSH sessions
 * This is the core innovation of TabSSH - browser-style SSH tabs
 */
class TabTerminalActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_PROFILE = "connection_profile"
        const val EXTRA_AUTO_CONNECT = "auto_connect"
        
        fun createIntent(context: Context, profile: ConnectionProfile, autoConnect: Boolean = true): Intent {
            return Intent(context, TabTerminalActivity::class.java).apply {
                putExtra(EXTRA_CONNECTION_PROFILE, profile.id)
                putExtra(EXTRA_AUTO_CONNECT, autoConnect)
            }
        }
    }
    
    private lateinit var binding: ActivityTabTerminalBinding
    private lateinit var app: TabSSHApplication
    private lateinit var tabManager: TabManager
    
    // UI components
    private var terminalView: TerminalView? = null
    
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
        setupFunctionKeys()
        
        // Handle intent
        handleIntent(intent)
        
        Logger.i("TabTerminalActivity", "Terminal activity created")
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
        terminalView = binding.terminalView
        
        // Set up terminal view
        binding.terminalView.apply {
            // Terminal view will be connected to active tab's terminal
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
        binding.btnFiles.setOnClickListener { openFileManager() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        val connectionProfileId = intent.getStringExtra(EXTRA_CONNECTION_PROFILE)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, true)
        
        if (connectionProfileId != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val profile = app.database.connectionDao().getConnectionById(connectionProfileId)
                if (profile != null && autoConnect) {
                    connectToProfile(profile)
                }
            }
        }
    }
    
    private suspend fun connectToProfile(profile: ConnectionProfile) {
        try {
            Logger.d("TabTerminalActivity", "Connecting to ${profile.getDisplayName()}")
            
            // Create SSH connection
            val sshConnection = app.sshSessionManager.connectToServer(profile)
            
            if (sshConnection != null) {
                // Create new tab with the connection
                val tab = tabManager.createTab(profile)
                
                if (tab != null) {
                    Logger.i("TabTerminalActivity", "Connected to ${profile.getDisplayName()}")
                    showToast("Connected to ${profile.getDisplayName()}")
                } else {
                    Logger.e("TabTerminalActivity", "Failed to create tab for ${profile.getDisplayName()}")
                    showToast("Failed to create terminal tab")
                }
            } else {
                Logger.e("TabTerminalActivity", "Failed to connect to ${profile.getDisplayName()}")
                showToast("Connection failed: ${profile.getDisplayName()}")
            }
            
        } catch (e: Exception) {
            Logger.e("TabTerminalActivity", "Error connecting to ${profile.getDisplayName()}", e)
            showToast("Connection error: ${e.message}")
        }
    }
    
    private fun addTabToUI(tab: SSHTab) {
        val tabLayout = binding.tabLayout
        val newTab = tabLayout.newTab()
        
        newTab.text = tab.getShortTitle()
        newTab.tag = tab.tabId
        
        tabLayout.addTab(newTab)
        
        // Select the new tab
        newTab.select()
        
        Logger.d("TabTerminalActivity", "Added tab to UI: ${tab.profile.getDisplayName()}")
    }
    
    private fun removeTabFromUI(index: Int) {
        val tabLayout = binding.tabLayout
        if (index < tabLayout.tabCount) {
            tabLayout.removeTabAt(index)
        }
    }
    
    private fun switchToTab(index: Int) {
        val tab = tabManager.getTab(index)
        if (tab != null) {
            // Connect terminal view to the tab's terminal
            val terminal = tab.terminal
            terminalView?.initialize(terminal.getRows(), terminal.getCols())

            // Deactivate previous tab
            tabManager.getActiveTab()?.deactivate()

            // Activate new tab
            tab.activate()

            // Update toolbar title
            supportActionBar?.title = tab.getDisplayTitle()

            Logger.d("TabTerminalActivity", "Switched to tab: ${tab.profile.getDisplayName()}")
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
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
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
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
        return terminalView?.onKeyDown(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }
    
    private fun sendKey(key: String) {
        when (key) {
            "ctrl" -> {
                // Toggle ctrl mode or show ctrl key menu
                showToast("Ctrl key pressed")
            }
            "alt" -> {
                showToast("Alt key pressed")
            }
            "esc" -> {
                terminalView?.sendKeySequence("\u001B")
            }
            "tab" -> {
                terminalView?.sendKeySequence("\t")
            }
            "up" -> {
                terminalView?.sendKeySequence("\u001B[A")
            }
            "down" -> {
                terminalView?.sendKeySequence("\u001B[B")
            }
        }
    }
    
    private fun toggleKeyboard() {
        terminalView?.sendText("")
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
            terminalView?.sendText(text.toString())
        }
    }
    
    private fun showConnectionSelector() {
        // This would show a dialog or start ConnectionEditActivity
        val intent = Intent(this, ConnectionEditActivity::class.java)
        startActivity(intent)
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
            // Reconnect terminal view to active tab
            val terminal = activeTab.terminal
            terminalView?.initialize(terminal.getRows(), terminal.getCols())
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
        
        Logger.d("TabTerminalActivity", "Terminal activity destroyed")
        tabManager.cleanup()
    }
    
    override fun onBackPressed() {
        if (tabManager.getTabCount() > 0) {
            // Ask for confirmation before closing all tabs
            showConfirmCloseDialog()
        } else {
            super.onBackPressed()
        }
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
}
