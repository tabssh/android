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
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.crypto.keys.GenerateResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Activity for creating and editing SSH connection profiles
 */
class ConnectionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_IS_EDIT_MODE = "is_edit_mode"
        private const val REQUEST_CODE_IMPORT_KEY = 1001

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
    private var selectedKeyIndex: Int = -1
    private var selectedGroupId: String? = null
    private var selectedGroupName: String = "No Group"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as TabSSHApplication
        isEditMode = intent.getBooleanExtra(EXTRA_IS_EDIT_MODE, false)
        
        setupToolbar()
        setupAuthTypeSpinner()
        setupKeySpinner()
        setupProxyTypeSpinner()
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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.connection_edit_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_set_group -> {
                showGroupSelectionDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupAuthTypeSpinner() {
        val authTypes = AuthType.getAvailableTypes()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            authTypes.map { it.displayName }
        )

        binding.spinnerAuthType.setAdapter(adapter)

        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ ->
            val selectedAuthType = authTypes[position]
            updateAuthTypeUI(selectedAuthType)
        }

        // Set default selection
        if (authTypes.isNotEmpty()) {
            binding.spinnerAuthType.setText(authTypes[0].displayName, false)
            updateAuthTypeUI(authTypes[0])
        }
    }
    
    private fun setupKeySpinner() {
        lifecycleScope.launch {
            try {
                availableKeys = app.keyStorage.listStoredKeys()

                val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_list_item_1,
                    keyNames
                )

                binding.spinnerSshKey.setAdapter(adapter)

                binding.spinnerSshKey.setOnItemClickListener { _, _, position, _ ->
                    selectedKeyIndex = position
                }

            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load SSH keys", e)
                showToast("Failed to load SSH keys")
            }
        }
    }
    
    private fun setupProxyTypeSpinner() {
        val proxyTypes = listOf("None", "HTTP", "SOCKS4", "SOCKS5", "SSH Jump Host")
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            proxyTypes
        )

        binding.spinnerProxyType.setAdapter(adapter)

        binding.spinnerProxyType.setOnItemClickListener { _, _, position, _ ->
            val selectedProxyType = proxyTypes[position]
            updateProxyTypeUI(selectedProxyType)
        }

        // Set default to "None"
        binding.spinnerProxyType.setText(proxyTypes[0], false)
        updateProxyTypeUI(proxyTypes[0])

        // Setup proxy auth type spinner
        val authTypes = AuthType.getAvailableTypes()
        val authAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            authTypes.map { it.displayName }
        )

        binding.spinnerProxyAuthType.setAdapter(authAdapter)

        binding.spinnerProxyAuthType.setOnItemClickListener { _, _, position, _ ->
            val selectedAuthType = authTypes[position]
            updateProxyAuthTypeUI(selectedAuthType)
        }

        // Setup proxy SSH key spinner (reuse available keys)
        lifecycleScope.launch {
            val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
            val keyAdapter = ArrayAdapter(
                this@ConnectionEditActivity,
                android.R.layout.simple_list_item_1,
                keyNames
            )

            binding.spinnerProxySshKey.setAdapter(keyAdapter)
        }
    }

    private fun updateProxyTypeUI(proxyType: String) {
        when (proxyType) {
            "None" -> {
                binding.layoutProxyConfig.visibility = android.view.View.GONE
                binding.layoutJumpHostAuth.visibility = android.view.View.GONE
            }
            "SSH Jump Host" -> {
                binding.layoutProxyConfig.visibility = android.view.View.VISIBLE
                binding.layoutJumpHostAuth.visibility = android.view.View.VISIBLE
                binding.editProxyPort.setText("22")
            }
            "HTTP" -> {
                binding.layoutProxyConfig.visibility = android.view.View.VISIBLE
                binding.layoutJumpHostAuth.visibility = android.view.View.GONE
                binding.editProxyPort.setText("8080")
            }
            "SOCKS4", "SOCKS5" -> {
                binding.layoutProxyConfig.visibility = android.view.View.VISIBLE
                binding.layoutJumpHostAuth.visibility = android.view.View.GONE
                binding.editProxyPort.setText("1080")
            }
        }
    }

    private fun updateProxyAuthTypeUI(authType: AuthType) {
        when (authType) {
            AuthType.PUBLIC_KEY -> {
                binding.layoutProxyKey.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.layoutProxyKey.visibility = android.view.View.GONE
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
            showKeyManagementDialog()
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
            binding.spinnerAuthType.setText(authTypes[authTypeIndex].displayName, false)
            updateAuthTypeUI(authTypes[authTypeIndex])
        }
        
        // Set SSH key if applicable
        if (authType == AuthType.PUBLIC_KEY && profile.keyId != null) {
            val keyIndex = availableKeys.indexOfFirst { it.keyId == profile.keyId }
            if (keyIndex >= 0) {
                selectedKeyIndex = keyIndex + 1 // +1 for "Select SSH Key..." item
                val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                if (selectedKeyIndex < keyNames.size) {
                    binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                }
            }
        }
        
        // Advanced settings
        binding.editTerminalType.setText(profile.terminalType)
        binding.switchCompression.isChecked = profile.compression
        binding.switchKeepAlive.isChecked = profile.keepAlive

        // Group
        selectedGroupId = profile.groupId
        selectedGroupName = profile.groupId ?: "No Group"
        if (selectedGroupId != null) {
            supportActionBar?.subtitle = selectedGroupName
        }

        // Proxy/Jump Host settings
        val proxyTypes = listOf("None", "HTTP", "SOCKS4", "SOCKS5", "SSH Jump Host")
        val proxyType = profile.proxyType ?: "None"
        val proxyTypeDisplay = when (proxyType) {
            "SSH" -> "SSH Jump Host"
            else -> if (proxyTypes.contains(proxyType)) proxyType else "None"
        }
        binding.spinnerProxyType.setText(proxyTypeDisplay, false)
        updateProxyTypeUI(proxyTypeDisplay)

        if (proxyType != null && proxyType != "None") {
            profile.proxyHost?.let { binding.editProxyHost.setText(it) }
            profile.proxyPort?.let { binding.editProxyPort.setText(it.toString()) }

            // SSH Jump Host specific fields
            if (proxyType == "SSH") {
                profile.proxyUsername?.let { binding.editProxyUsername.setText(it) }

                // Set proxy auth type
                if (profile.proxyAuthType != null) {
                    val proxyAuthType = try {
                        AuthType.valueOf(profile.proxyAuthType)
                    } catch (e: IllegalArgumentException) {
                        AuthType.PASSWORD
                    }
                    val authTypes = AuthType.getAvailableTypes()
                    val proxyAuthTypeIndex = authTypes.indexOf(proxyAuthType)
                    if (proxyAuthTypeIndex >= 0) {
                        binding.spinnerProxyAuthType.setText(authTypes[proxyAuthTypeIndex].displayName, false)
                        updateProxyAuthTypeUI(authTypes[proxyAuthTypeIndex])
                    }

                    // Set proxy SSH key if applicable
                    if (proxyAuthType == AuthType.PUBLIC_KEY && profile.proxyKeyId != null) {
                        val keyIndex = availableKeys.indexOfFirst { it.keyId == profile.proxyKeyId }
                        if (keyIndex >= 0) {
                            val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                            if (keyIndex + 1 < keyNames.size) {
                                binding.spinnerProxySshKey.setText(keyNames[keyIndex + 1], false)
                            }
                        }
                    }
                }
            }
        }
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
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.BIOMETRIC
                        } else {
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
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
            if (selectedKeyIndex > 0 && selectedKeyIndex - 1 < availableKeys.size) {
                availableKeys[selectedKeyIndex - 1].keyId
            } else null
        } else null
        
        val terminalType = binding.editTerminalType.text.toString().takeIf { it.isNotBlank() } ?: "xterm-256color"
        val compression = binding.switchCompression.isChecked
        val keepAlive = binding.switchKeepAlive.isChecked

        // Proxy/Jump Host settings
        val proxyTypeDisplay = binding.spinnerProxyType.text.toString()
        val proxyType = when (proxyTypeDisplay) {
            "SSH Jump Host" -> "SSH"
            "None" -> null
            else -> proxyTypeDisplay
        }

        val proxyHost = if (proxyType != null) {
            binding.editProxyHost.text.toString().takeIf { it.isNotBlank() }
        } else null

        val proxyPort = if (proxyType != null) {
            binding.editProxyPort.text.toString().toIntOrNull()
        } else null

        val proxyUsername = if (proxyType == "SSH") {
            binding.editProxyUsername.text.toString().takeIf { it.isNotBlank() }
        } else null

        val proxyAuthType = if (proxyType == "SSH") {
            val authTypes = AuthType.getAvailableTypes()
            val selectedText = binding.spinnerProxyAuthType.text.toString()
            authTypes.find { it.displayName == selectedText }?.name
        } else null

        val proxyKeyId = if (proxyType == "SSH" && proxyAuthType == AuthType.PUBLIC_KEY.name) {
            val selectedProxyKeyText = binding.spinnerProxySshKey.text.toString()
            if (selectedProxyKeyText != "Select SSH Key...") {
                availableKeys.find { it.getDisplayName() == selectedProxyKeyText }?.keyId
            } else null
        } else null

        return existingProfile?.copy(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType.name,
            keyId = keyId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive,
            groupId = selectedGroupId,
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyAuthType = proxyAuthType,
            proxyKeyId = proxyKeyId
        ) ?: ConnectionProfile(
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType.name,
            keyId = keyId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive,
            groupId = selectedGroupId,
            proxyType = proxyType,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyAuthType = proxyAuthType,
            proxyKeyId = proxyKeyId
        )
    }
    
    private fun getSelectedAuthType(): AuthType {
        val authTypes = AuthType.getAvailableTypes()
        val selectedText = binding.spinnerAuthType.text.toString()
        val selectedAuthType = authTypes.find { it.displayName == selectedText }
        return selectedAuthType ?: AuthType.PASSWORD
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
        return if (selectedKeyIndex <= 0) {
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

    private fun showKeyManagementDialog() {
        val options = arrayOf(
            "Import SSH Key (File)",
            "Paste SSH Key",
            "Generate New Key Pair",
            "Browse Existing Keys"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("SSH Key Management")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importKeyFromFile()
                    1 -> pasteKey()
                    2 -> generateNewKey()
                    3 -> browseExistingKeys()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT_KEY)
        } catch (e: Exception) {
            showToast("File picker not available")
        }
    }

    private fun pasteKey() {
        val editText = android.widget.EditText(this).apply {
            hint = "Paste your private key here (PEM format)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            maxLines = 20
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Paste SSH Private Key")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val keyContent = editText.text.toString().trim()
                if (keyContent.isNotEmpty()) {
                    importKeyFromContent(keyContent, "pasted_key")
                } else {
                    showToast("Key content is empty")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateNewKey() {
        showKeyGenerationDialog()
    }

    private fun showKeyGenerationDialog() {
        val keyTypes = arrayOf(
            "RSA 2048-bit (Compatible)",
            "RSA 4096-bit (Secure)",
            "ECDSA P-256 (Modern)",
            "ECDSA P-384 (High Security)",
            "Ed25519 (Recommended)"
        )

        var selectedType = 4 // Default to Ed25519
        val keyTypeMapping = mapOf(
            0 to Pair(KeyType.RSA, 2048),
            1 to Pair(KeyType.RSA, 4096),
            2 to Pair(KeyType.ECDSA, 256),
            3 to Pair(KeyType.ECDSA, 384),
            4 to Pair(KeyType.ED25519, 256)
        )

        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_single_choice, null)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Generate SSH Key Pair")
            .setMessage("Choose the key type to generate. Ed25519 is recommended for best security and performance.")
            .setSingleChoiceItems(keyTypes, selectedType) { _, which ->
                selectedType = which
            }
            .setPositiveButton("Generate") { _, _ ->
                val (keyType, keySize) = keyTypeMapping[selectedType] ?: Pair(KeyType.ED25519, 256)
                showKeyNamingDialog(keyType, keySize)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeyNamingDialog(keyType: KeyType, keySize: Int) {
        val editText = android.widget.EditText(this).apply {
            hint = "Enter key name (e.g., 'My Server Key')"
            val keyName = when (keyType) {
                KeyType.RSA -> "RSA ${keySize}-bit"
                KeyType.ECDSA -> "ECDSA P-${keySize}"
                KeyType.ED25519 -> "Ed25519"
                KeyType.DSA -> "DSA ${keySize}-bit"
            }
            setText("Generated $keyName Key")
            selectAll()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Name Your Key")
            .setMessage("Give your new SSH key a descriptive name.")
            .setView(editText)
            .setPositiveButton("Generate Key") { _, _ ->
                val keyName = editText.text.toString().trim()
                if (keyName.isNotEmpty()) {
                    generateKeyPair(keyType, keySize, keyName)
                } else {
                    showToast("Key name is required")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateKeyPair(keyType: KeyType, keySize: Int, keyName: String) {
        // Show progress dialog
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Generating SSH Key")
            .setMessage("Creating ${keyType.name} ${keySize}-bit key pair...\nThis may take a moment.")
            .setCancelable(false)
            .create()
        
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val keyStorage = app.keyStorage
                val result = keyStorage.generateKeyPair(keyType, keySize, keyName)
                
                runOnUiThread {
                    progressDialog.dismiss()
                    
                    when (result) {
                        is GenerateResult.Success -> {
                            showKeyGenerationSuccess(keyName, result.fingerprint)
                            // Refresh the key list and auto-select the new key
                            loadAvailableKeys()
                        }
                        is GenerateResult.Error -> {
                            showKeyGenerationError(result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    showKeyGenerationError("Failed to generate key: ${e.message}")
                }
            }
        }
    }

    private fun loadAvailableKeys() {
        setupKeySpinner()
    }

    private fun showKeyGenerationSuccess(message: String, fingerprint: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✅ Key Generated Successfully")
            .setMessage("$message\n\nFingerprint:\n$fingerprint\n\nThe key has been securely stored and is ready to use.")
            .setPositiveButton("OK") { _, _ ->
                // Auto-select the newly generated key if it's available
                loadAvailableKeys()
            }
            .show()
        
        showToast("🔑 SSH key generated successfully!")
    }

    private fun showKeyGenerationError(errorMessage: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❌ Key Generation Failed")
            .setMessage("Failed to generate SSH key:\n\n$errorMessage\n\nPlease try again or contact support if the problem persists.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Try Again") { _, _ ->
                generateNewKey()
            }
            .show()
    }


    private fun browseExistingKeys() {
        if (availableKeys.isEmpty()) {
            showToast("No SSH keys stored yet. Import or generate a key first.")
            return
        }

        val keyNames = availableKeys.map { it.getDisplayName() }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select SSH Key")
            .setItems(keyNames) { _, which ->
                selectedKeyIndex = which + 1 // +1 for "Select SSH Key..." offset
                val selectedKey = availableKeys[which]
                binding.spinnerSshKey.setText(selectedKey.getDisplayName(), false)
                showToast("Selected: ${selectedKey.getDisplayName()}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importKeyFromContent(keyContent: String, filename: String) {
        lifecycleScope.launch {
            try {
                // Show progress
                showToast("Importing SSH key...")
                
                // Check if key is encrypted (needs passphrase)
                val needsPassphrase = keyContent.contains("ENCRYPTED") || 
                                     keyContent.contains("Proc-Type: 4,ENCRYPTED")
                
                if (needsPassphrase) {
                    // Show passphrase dialog
                    showKeyPassphraseDialog(keyContent, filename)
                } else {
                    // Import without passphrase
                    performKeyImport(keyContent, filename, null)
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to import key", e)
                showToast("❌ Import failed: ${e.message}")
            }
        }
    }
    
    private fun showKeyPassphraseDialog(keyContent: String, filename: String) {
        val passphraseInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Key passphrase"
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(passphraseInput)
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("🔐 Encrypted SSH Key")
            .setMessage("This key is encrypted. Enter passphrase to decrypt.")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val passphrase = passphraseInput.text.toString()
                if (passphrase.isEmpty()) {
                    showToast("Passphrase is required")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    performKeyImport(keyContent, filename, passphrase)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private suspend fun performKeyImport(keyContent: String, filename: String, passphrase: String?) {
        try {
            // Import key using enhanced KeyStorage
            val result = app.keyStorage.importKeyFromText(
                keyContent = keyContent,
                passphrase = passphrase,
                keyName = extractKeyNameFromFilename(filename)
            )
            
            when (result) {
                is io.github.tabssh.crypto.keys.ImportResult.Success -> {
                    Logger.i("ConnectionEditActivity", "Key imported successfully: ${result.keyId}")
                    showToast("✅ SSH key imported successfully!")
                    
                    // Refresh key list
                    setupKeySpinner()
                    
                    // Auto-select the imported key
                    val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                    if (importedKeyIndex >= 0) {
                        selectedKeyIndex = importedKeyIndex + 1 // +1 for "Select SSH Key..." offset
                        val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                        if (selectedKeyIndex < keyNames.size) {
                            binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                        }
                    }
                }
                is io.github.tabssh.crypto.keys.ImportResult.Error -> {
                    Logger.e("ConnectionEditActivity", "Key import failed: ${result.message}")
                    showKeyImportErrorDialog(result.message)
                }
            }
            
        } catch (e: Exception) {
            Logger.e("ConnectionEditActivity", "Key import failed", e)
            showToast("❌ Key import failed: ${e.message}")
        }
    }
    
    private fun extractKeyNameFromFilename(filename: String): String {
        // Extract a meaningful name from the filename
        val name = filename
            .substringBeforeLast(".") // Remove extension
            .replace("_", " ")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { word -> 
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        
        return if (name.isBlank()) "Imported Key" else name
    }
    
    private fun showPassphraseDialog(keyContent: String, filename: String) {
        val editText = android.widget.EditText(this).apply {
            hint = "Enter passphrase for encrypted key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Encrypted Key")
            .setMessage("This SSH key is encrypted and requires a passphrase.")
            .setView(editText)
            .setPositiveButton("Import") { _, _ ->
                val passphrase = editText.text.toString()
                if (passphrase.isNotEmpty()) {
                    importKeyWithPassphrase(keyContent, filename, passphrase)
                } else {
                    showToast("Passphrase is required for encrypted keys")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun importKeyWithPassphrase(keyContent: String, filename: String, passphrase: String) {
        lifecycleScope.launch {
            try {
                showToast("Importing encrypted SSH key...")
                
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = passphrase,
                    keyName = extractKeyNameFromFilename(filename)
                )
                
                when (result) {
                    is io.github.tabssh.crypto.keys.ImportResult.Success -> {
                        Logger.i("ConnectionEditActivity", "Encrypted key imported successfully: ${result.keyId}")
                        showToast("✅ Encrypted SSH key imported successfully!")
                        
                        // Refresh key list and auto-select
                        setupKeySpinner()
                        val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                        if (importedKeyIndex >= 0) {
                            selectedKeyIndex = importedKeyIndex + 1
                            val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                            if (selectedKeyIndex < keyNames.size) {
                                binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                            }
                        }
                    }
                    is io.github.tabssh.crypto.keys.ImportResult.Error -> {
                        Logger.e("ConnectionEditActivity", "Encrypted key import failed: ${result.message}")
                        showKeyImportErrorDialog(result.message)
                    }
                }
                
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Encrypted key import failed", e)
                showToast("❌ Encrypted key import failed: ${e.message}")
            }
        }
    }
    
    private fun showKeyImportErrorDialog(errorMessage: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Key Import Failed")
            .setMessage(errorMessage)
            .setPositiveButton("OK", null)
            .setNeutralButton("Help") { _, _ ->
                showKeyImportHelpDialog()
            }
            .show()
    }
    
    private fun showKeyImportHelpDialog() {
        val helpText = """
            SSH Key Import Help:
            
            Supported Formats:
            • PKCS#8 (recommended)
            • Traditional RSA/DSA/ECDSA
            • OpenSSH format (with conversion)
            
            Common Solutions:
            
            1. Convert OpenSSH to PKCS#8:
               ssh-keygen -p -m pkcs8 -f your_key
            
            2. Convert PuTTY (.ppk) to OpenSSH:
               puttygen key.ppk -O private-openssh -o key
            
            3. Generate compatible key:
               ssh-keygen -t ed25519 -m pkcs8 -f new_key
            
            4. For encrypted keys, ensure correct passphrase
            
            Need help? Visit our documentation.
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("SSH Key Import Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_IMPORT_KEY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val keyContent = inputStream.bufferedReader().readText()
                        val filename = uri.lastPathSegment ?: "imported_key"
                        importKeyFromContent(keyContent, filename)
                    }
                } catch (e: Exception) {
                    Logger.e("ConnectionEditActivity", "Failed to read key file", e)
                    showToast("Failed to read key file: ${e.message}")
                }
            }
        }
    }

    private fun showGroupSelectionDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Group name (e.g., Work Servers, Home Lab)"
            setText(selectedGroupName)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Connection Group")
            .setMessage("Organize your connections into groups.\n\nLeave empty for 'No Group'.")
            .setView(editText)
            .setPositiveButton("Set") { _, _ ->
                val groupName = editText.text.toString().trim()
                if (groupName.isEmpty()) {
                    selectedGroupId = null
                    selectedGroupName = "No Group"
                    showToast("Group cleared")
                } else {
                    selectedGroupId = groupName // For simplicity, use name as ID
                    selectedGroupName = groupName
                    showToast("Group set to: $groupName")
                }
                supportActionBar?.subtitle = selectedGroupName
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                selectedGroupId = null
                selectedGroupName = "No Group"
                supportActionBar?.subtitle = null
                showToast("Group cleared")
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}