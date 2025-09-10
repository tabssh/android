package com.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tabssh.R
import com.tabssh.TabSSHApplication
import com.tabssh.databinding.ActivityMainBinding
import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.ui.adapters.ConnectionAdapter
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Main activity showing connection list and quick connect
 * Updated with full database integration and RecyclerView
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var app: TabSSHApplication
    private lateinit var connectionAdapter: ConnectionAdapter
    
    private val connections = mutableListOf<ConnectionProfile>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.d("MainActivity", "onCreate")
        
        app = application as TabSSHApplication
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupRecyclerView()
        setupQuickConnect()
        setupFAB()
        
        // Load connections from database
        loadConnections()
        
        Logger.i("MainActivity", "MainActivity created successfully")
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
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
                
                // Check if we need authentication (password/key)
                val authType = profile.getAuthTypeEnum()
                val needsPassword = when (authType) {
                    com.tabssh.ssh.auth.AuthType.PASSWORD, 
                    com.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE -> {
                        // Check if password is stored
                        val storedPassword = app.securePasswordManager.retrievePassword(profile.id)
                        storedPassword == null
                    }
                    com.tabssh.ssh.auth.AuthType.PUBLIC_KEY -> {
                        profile.keyId == null
                    }
                    else -> false
                }
                
                if (needsPassword) {
                    // Show authentication dialog or edit connection
                    showAuthenticationDialog(profile)
                } else {
                    // Connect directly
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
        // For now, redirect to connection edit for authentication setup
        val intent = ConnectionEditActivity.createIntent(this, profile.id)
        startActivity(intent)
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
    
    override fun onResume() {
        super.onResume()
        Logger.d("MainActivity", "onResume")
        
        // Refresh connections in case they were modified
        loadConnections()
    }
    
    override fun onPause() {
        super.onPause()
        Logger.d("MainActivity", "onPause")
    }
}