package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityConnectionEditBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Activity for creating and editing SSH connection profiles
 */
class ConnectionEditActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_IS_EDIT_MODE = "is_edit_mode"
        
        fun createIntent(context: Context, connectionId: String? = null): Intent {
            return Intent(context, ConnectionEditActivity::class.java).apply {
                connectionId?.let { putExtra(EXTRA_CONNECTION_ID, it) }
                putExtra(EXTRA_IS_EDIT_MODE, connectionId != null)
            }
        }
    }
    
    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var app: TabSSHApplication
    
    private var existingProfile: ConnectionProfile? = null
    private var isEditMode = false
    private var availableKeys: List<StoredKey> = emptyList()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as TabSSHApplication
        isEditMode = intent.getBooleanExtra(EXTRA_IS_EDIT_MODE, false)
        
        setupToolbar()
        setupAuthTypeSpinner()
        setupKeySpinner()
        setupValidation()
        setupButtons()
        
        // Load existing connection if editing
        intent.getStringExtra(EXTRA_CONNECTION_ID)?.let { connectionId ->
            loadConnection(connectionId)
        }
        
        Logger.d("ConnectionEditActivity", "Connection edit activity created, editMode: $isEditMode")
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = if (isEditMode) "Edit Connection" else "New Connection"
        }
    }
    
    private fun setupAuthTypeSpinner() {
        val authTypes = AuthType.getAvailableTypes()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            authTypes.map { it.displayName }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        
        binding.spinnerAuthType.adapter = adapter
        
        binding.spinnerAuthType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedAuthType = authTypes[position]
                updateAuthTypeUI(selectedAuthType)
            }
            
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }
    
    private fun setupKeySpinner() {
        lifecycleScope.launch {
            try {
                availableKeys = app.keyStorage.listStoredKeys()
                
                val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_spinner_item,
                    keyNames
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                
                binding.spinnerSshKey.adapter = adapter
                
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load SSH keys", e)
                showToast("Failed to load SSH keys")
            }
        }
    }
    
    private fun updateAuthTypeUI(authType: AuthType) {
        when (authType) {
            AuthType.PASSWORD -> {
                binding.layoutPassword.visibility = android.view.View.VISIBLE
                binding.layoutSshKey.visibility = android.view.View.GONE
                binding.layoutSavePassword.visibility = android.view.View.VISIBLE
            }
            AuthType.PUBLIC_KEY -> {
                binding.layoutPassword.visibility = android.view.View.GONE
                binding.layoutSshKey.visibility = android.view.View.VISIBLE
                binding.layoutSavePassword.visibility = android.view.View.GONE
            }
            AuthType.KEYBOARD_INTERACTIVE -> {
                binding.layoutPassword.visibility = android.view.View.VISIBLE
                binding.layoutSshKey.visibility = android.view.View.GONE
                binding.layoutSavePassword.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.layoutPassword.visibility = android.view.View.GONE
                binding.layoutSshKey.visibility = android.view.View.GONE
                binding.layoutSavePassword.visibility = android.view.View.GONE
            }
        }
    }
    
    private fun setupValidation() {
        // Real-time validation could be added here
        binding.editHost.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateHost()
            }
        }
        
        binding.editPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validatePort()
            }
        }
        
        binding.editUsername.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateUsername()
            }
        }
    }
    
    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveConnection()
        }
        
        binding.btnCancel.setOnClickListener {
            finish()
        }
        
        binding.btnTest.setOnClickListener {
            testConnection()
        }
        
        binding.btnGenerateKey.setOnClickListener {
            startActivity(Intent(this, KeyManagementActivity::class.java))
        }
    }
    
    private fun loadConnection(connectionId: String) {
        lifecycleScope.launch {
            try {
                existingProfile = app.database.connectionDao().getConnectionById(connectionId)
                existingProfile?.let { profile ->
                    populateFields(profile)
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load connection", e)
                showToast("Failed to load connection")
                finish()
            }
        }
    }
    
    private fun populateFields(profile: ConnectionProfile) {
        binding.editName.setText(profile.name)
        binding.editHost.setText(profile.host)
        binding.editPort.setText(profile.port.toString())
        binding.editUsername.setText(profile.username)
        
        // Set auth type
        val authType = profile.getAuthTypeEnum()
        val authTypes = AuthType.getAvailableTypes()
        val authTypeIndex = authTypes.indexOf(authType)
        if (authTypeIndex >= 0) {
            binding.spinnerAuthType.setSelection(authTypeIndex)
        }
        
        // Set SSH key if applicable
        if (authType == AuthType.PUBLIC_KEY && profile.keyId != null) {
            val keyIndex = availableKeys.indexOfFirst { it.keyId == profile.keyId }
            if (keyIndex >= 0) {
                binding.spinnerSshKey.setSelection(keyIndex + 1) // +1 for "Select SSH Key..." item
            }
        }
        
        // Advanced settings
        binding.editTerminalType.setText(profile.terminalType)
        binding.switchCompression.isChecked = profile.compression
        binding.switchKeepAlive.isChecked = profile.keepAlive
    }
    
    private fun saveConnection() {
        if (!validateAllFields()) {
            return
        }
        
        lifecycleScope.launch {
            try {
                val profile = createConnectionProfile()
                
                if (isEditMode && existingProfile != null) {
                    // Update existing connection
                    app.database.connectionDao().updateConnection(profile)
                    Logger.i("ConnectionEditActivity", "Updated connection: ${profile.name}")
                    showToast("Connection updated")
                } else {
                    // Create new connection
                    app.database.connectionDao().insertConnection(profile)
                    Logger.i("ConnectionEditActivity", "Created connection: ${profile.name}")
                    showToast("Connection saved")
                }
                
                // Save password if requested
                val authType = getSelectedAuthType()
                if (authType == AuthType.PASSWORD || authType == AuthType.KEYBOARD_INTERACTIVE) {
                    val password = binding.editPassword.text.toString()
                    val savePassword = binding.switchSavePassword.isChecked
                    
                    if (password.isNotEmpty() && savePassword) {
                        val storageLevel = if (app.securePasswordManager.requiresEnhancedSecurity(profile.host)) {
                            com.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.BIOMETRIC
                        } else {
                            com.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                        }
                        
                        app.securePasswordManager.storePassword(profile.id, password, storageLevel)
                    }
                }
                
                setResult(RESULT_OK)
                finish()
                
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to save connection", e)
                showToast("Failed to save connection: ${e.message}")
            }
        }
    }
    
    private fun createConnectionProfile(): ConnectionProfile {
        val name = binding.editName.text.toString().trim()
        val host = binding.editHost.text.toString().trim()
        val port = binding.editPort.text.toString().toIntOrNull() ?: 22
        val username = binding.editUsername.text.toString().trim()
        val authType = getSelectedAuthType()
        
        val keyId = if (authType == AuthType.PUBLIC_KEY) {
            val selectedKeyIndex = binding.spinnerSshKey.selectedItemPosition
            if (selectedKeyIndex > 0 && selectedKeyIndex - 1 < availableKeys.size) {
                availableKeys[selectedKeyIndex - 1].keyId
            } else null
        } else null
        
        val terminalType = binding.editTerminalType.text.toString().takeIf { it.isNotBlank() } ?: "xterm-256color"
        val compression = binding.switchCompression.isChecked
        val keepAlive = binding.switchKeepAlive.isChecked
        
        return existingProfile?.copy(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType.name,
            keyId = keyId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive
        ) ?: ConnectionProfile(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType.name,
            keyId = keyId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive
        )
    }
    
    private fun getSelectedAuthType(): AuthType {
        val authTypes = AuthType.getAvailableTypes()
        val selectedIndex = binding.spinnerAuthType.selectedItemPosition
        return if (selectedIndex >= 0 && selectedIndex < authTypes.size) {
            authTypes[selectedIndex]
        } else {
            AuthType.PASSWORD
        }
    }
    
    private fun validateAllFields(): Boolean {
        var isValid = true
        
        if (!validateHost()) isValid = false
        if (!validatePort()) isValid = false
        if (!validateUsername()) isValid = false
        
        val authType = getSelectedAuthType()
        if (authType == AuthType.PUBLIC_KEY && !validateKeySelection()) isValid = false
        
        return isValid
    }
    
    private fun validateHost(): Boolean {
        val host = binding.editHost.text.toString().trim()
        
        return if (host.isBlank()) {
            binding.editHost.error = "Host is required"
            false
        } else {
            binding.editHost.error = null
            true
        }
    }
    
    private fun validatePort(): Boolean {
        val portText = binding.editPort.text.toString().trim()
        val port = portText.toIntOrNull()
        
        return when {
            portText.isBlank() -> {
                binding.editPort.error = "Port is required"
                false
            }
            port == null -> {
                binding.editPort.error = "Invalid port number"
                false
            }
            port < 1 || port > 65535 -> {
                binding.editPort.error = "Port must be between 1 and 65535"
                false
            }
            else -> {
                binding.editPort.error = null
                true
            }
        }
    }
    
    private fun validateUsername(): Boolean {
        val username = binding.editUsername.text.toString().trim()
        
        return if (username.isBlank()) {
            binding.editUsername.error = "Username is required"
            false
        } else {
            binding.editUsername.error = null
            true
        }
    }
    
    private fun validateKeySelection(): Boolean {
        val selectedIndex = binding.spinnerSshKey.selectedItemPosition
        
        return if (selectedIndex <= 0) {
            showToast("Please select an SSH key for public key authentication")
            false
        } else {
            true
        }
    }
    
    private fun testConnection() {
        if (!validateAllFields()) {
            return
        }
        
        lifecycleScope.launch {
            try {
                binding.btnTest.isEnabled = false
                binding.btnTest.text = "Testing..."
                
                val profile = createConnectionProfile()
                val connection = app.sshSessionManager.connectToServer(profile)
                
                if (connection != null) {
                    showToast("✅ Connection test successful!")
                    connection.disconnect() // Close test connection
                } else {
                    showToast("❌ Connection test failed")
                }
                
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Connection test failed", e)
                showToast("Connection test error: ${e.message}")
            } finally {
                binding.btnTest.isEnabled = true
                binding.btnTest.text = "Test Connection"
            }
        }
    }
    
    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}