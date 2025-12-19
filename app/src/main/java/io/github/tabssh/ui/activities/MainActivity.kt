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
import io.github.tabssh.ui.adapters.ConnectionAdapter
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
    private lateinit var connectionAdapter: ConnectionAdapter
    private lateinit var frequentlyUsedAdapter: ConnectionAdapter
    private lateinit var backupManager: BackupManager

    private val connections = mutableListOf<ConnectionProfile>()
    private val frequentlyUsedConnections = mutableListOf<ConnectionProfile>()

    // Activity result launchers for import/export
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBackupFromUri(it) }
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

        // Load connections from database
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
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
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
        connectionAdapter = ConnectionAdapter(
            connections = connections,
            onConnectionClick = { profile -> connectToProfile(profile) },
            onConnectionLongClick = { profile -> showConnectionMenu(profile) },
            onConnectionEdit = { profile -> editConnection(profile) },
            onConnectionDelete = { profile -> deleteConnection(profile) }
        )
        
        binding.recyclerConnections.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = connectionAdapter
            visibility = android.view.View.VISIBLE
        }
        
        // Hide empty message when RecyclerView is shown
        binding.textEmptyConnections.visibility = android.view.View.GONE
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
                app.database.connectionDao().getAllConnections().collect { connectionList ->
                    connections.clear()
                    connections.addAll(connectionList)
                    
                    runOnUiThread {
                        connectionAdapter.notifyDataSetChanged()
                        updateEmptyState()
                    }
                    
                    Logger.d("MainActivity", "Loaded ${connectionList.size} connections")
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Failed to load connections", e)
                showToast("Failed to load connections")
            }
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
                        val intent = TabTerminalActivity.createIntent(
                            this@MainActivity,
                            profile,
                            autoConnect = true
                        )
                        // TODO: Pass password to terminal activity securely
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
        val items = arrayOf("Connect", "Edit", "Duplicate", "Delete")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(profile.getDisplayName())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> connectToProfile(profile)
                    1 -> editConnection(profile)
                    2 -> duplicateConnection(profile)
                    3 -> deleteConnection(profile)
                }
            }
            .show()
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
                performExport(uri, includePasswords)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Perform actual export
     */
    private fun performExport(uri: Uri, includePasswords: Boolean) {
        lifecycleScope.launch {
            try {
                Logger.i("MainActivity", "Exporting backup to: $uri")

                val result = backupManager.createBackup(
                    outputUri = uri,
                    includePasswords = includePasswords,
                    encryptBackup = includePasswords,
                    password = if (includePasswords) null else null // TODO: Get password from user
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

    override fun onResume() {
        super.onResume()
        Logger.d("MainActivity", "onResume")
        
        // Refresh connections in case they were modified
        loadConnections()
        loadFrequentlyUsedConnections()
    }
    
    override fun onPause() {
        super.onPause()
        Logger.d("MainActivity", "onPause")
    }
}