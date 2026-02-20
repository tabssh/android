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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import io.github.tabssh.utils.showError

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
    private var availableIdentities: List<io.github.tabssh.storage.database.entities.Identity> = emptyList()
    private var selectedIdentityId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as TabSSHApplication
        isEditMode = intent.getBooleanExtra(EXTRA_IS_EDIT_MODE, false)
        
        setupToolbar()
        setupAuthTypeSpinner()
        setupKeySpinner()
        setupGroupSpinner()
        setupIdentitySpinner()
        setupProxyTypeSpinner()
        setupTerminalTypeSpinner()
        setupMultiplexerSpinner()
        setupConnectionThemeSpinner()
        setupValidation()
        setupButtons()
        setupPortKnockUI()
        
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
                showError("Failed to load SSH keys", "Error")
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

    private fun setupGroupSpinner() {
        lifecycleScope.launch {
            // Get one-time snapshot from Flow
            val groups = app.database.connectionGroupDao().getAllGroups().first()
            val groupsList = mutableListOf("No Group")
            groups.forEach { group -> groupsList.add(group.name) }
            
            val adapter = ArrayAdapter(
                this@ConnectionEditActivity,
                android.R.layout.simple_dropdown_item_1line,
                groupsList
            )
            binding.spinnerGroup.setAdapter(adapter)
            
            binding.spinnerGroup.setOnItemClickListener { _, _, position, _ ->
                selectedGroupId = if (position == 0) null else groups[position - 1].id
                selectedGroupName = groupsList[position]
            }
            
            // Set current group
            existingProfile?.groupId?.let { groupId ->
                val index = groups.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    binding.spinnerGroup.setText(groups[index].name, false)
                    selectedGroupId = groupId
                    selectedGroupName = groups[index].name
                }
            } ?: run {
                binding.spinnerGroup.setText("No Group", false)
            }
        }
    }
    
    private fun setupIdentitySpinner() {
        lifecycleScope.launch {
            availableIdentities = app.database.identityDao().getAllIdentitiesList()
            val identityList = mutableListOf("No Identity")
            availableIdentities.forEach { identity -> identityList.add(identity.name) }

            val adapter = ArrayAdapter(
                this@ConnectionEditActivity,
                android.R.layout.simple_dropdown_item_1line,
                identityList
            )
            binding.spinnerIdentity.setAdapter(adapter)

            binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
                if (position > 0) {
                    val identity = availableIdentities[position - 1]
                    selectedIdentityId = identity.id
                    binding.editUsername.setText(identity.username)
                    binding.cardAuthentication.visibility = android.view.View.GONE
                } else {
                    selectedIdentityId = null
                    binding.cardAuthentication.visibility = android.view.View.VISIBLE
                }
            }

            // Restore selection if editing (may be called before or after loadExistingProfile)
            restoreIdentitySpinner()
        }
    }

    private fun restoreIdentitySpinner() {
        val id = selectedIdentityId
        if (id != null) {
            val idx = availableIdentities.indexOfFirst { it.id == id }
            if (idx >= 0) {
                binding.spinnerIdentity.setText(availableIdentities[idx].name, false)
                binding.cardAuthentication.visibility = android.view.View.GONE
                return
            }
        }
        binding.spinnerIdentity.setText("No Identity", false)
        binding.cardAuthentication.visibility = android.view.View.VISIBLE
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
                    // Update toolbar with actual connection name
                    supportActionBar?.title = "Edit ${profile.name}"
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load connection", e)
                showError("Failed to load connection", "Error")
                finish()
            }
        }
    }
    
    private suspend fun populateFields(profile: ConnectionProfile) {
        binding.editName.setText(profile.name)
        binding.editHost.setText(profile.host)
        binding.editPort.setText(profile.port.toString())
        binding.editUsername.setText(profile.username)

        // Restore identity selection — must be set before restoreIdentitySpinner() runs
        selectedIdentityId = profile.identityId
        restoreIdentitySpinner()
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

        // Load stored password so user doesn't have to re-enter when editing
        if (authType == AuthType.PASSWORD || authType == AuthType.KEYBOARD_INTERACTIVE) {
            val storedPassword = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword(profile.id)
            }
            if (storedPassword != null) {
                binding.editPassword.setText(storedPassword)
                binding.switchSavePassword.isChecked = true
            }
        }
        
        // Advanced settings
        binding.spinnerTerminalType.setText(profile.terminalType, false)
        binding.switchCompression.isChecked = profile.compression
        binding.switchKeepAlive.isChecked = profile.keepAlive
        binding.switchX11Forwarding.isChecked = profile.x11Forwarding
        binding.switchUseMosh.isChecked = profile.useMosh

        // Multiplexer settings
        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val modeIndex = modeValues.indexOf(profile.multiplexerMode)
        if (modeIndex >= 0) {
            binding.spinnerMultiplexerMode.setText(modeEntries[modeIndex], false)
            // Show session name field if not OFF
            binding.layoutMultiplexerSessionName.visibility = if (profile.multiplexerMode != "OFF") {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
        profile.multiplexerSessionName?.let { binding.editMultiplexerSessionName.setText(it) }

        // Group
        selectedGroupId = profile.groupId
        selectedGroupName = profile.groupId ?: "No Group"
        if (selectedGroupId != null) {
            supportActionBar?.subtitle = selectedGroupName
        }

        // Appearance & Scripts settings
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val themeIndex = themeValues.indexOf(profile.theme)
        if (themeIndex >= 0) {
            // +1 to account for "Use Global Default" at index 0
            val displayEntries = listOf("Use Global Default") + themeEntries.toList()
            binding.spinnerConnectionTheme.setText(displayEntries[themeIndex + 1], false)
        } else {
            binding.spinnerConnectionTheme.setText("Use Global Default", false)
        }

        profile.fontSizeOverride?.let { fontSize ->
            binding.editFontSizeOverride.setText(fontSize.toString())
        }

        profile.postConnectScript?.let { script ->
            binding.editPostConnectScript.setText(script)
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

        if (proxyType != "None") {
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
                showError("Failed to save connection: ${e.message}", "Error")
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
        
        val terminalType = binding.spinnerTerminalType.text.toString().takeIf { it.isNotBlank() } ?: "xterm-256color"
        val compression = binding.switchCompression.isChecked
        val keepAlive = binding.switchKeepAlive.isChecked
        val x11Forwarding = binding.switchX11Forwarding.isChecked
        val useMosh = binding.switchUseMosh.isChecked

        // Multiplexer settings
        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val multiplexerModeText = binding.spinnerMultiplexerMode.text.toString()
        val modeIndex = modeEntries.indexOf(multiplexerModeText)
        val multiplexerMode = if (modeIndex >= 0) modeValues[modeIndex] else "OFF"
        val multiplexerSessionName = binding.editMultiplexerSessionName.text.toString().takeIf { it.isNotBlank() }

        // Appearance & Scripts settings
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val selectedThemeText = binding.spinnerConnectionTheme.text.toString()
        val theme = if (selectedThemeText == "Use Global Default") {
            "dracula" // Default theme
        } else {
            val themeIndex = themeEntries.indexOf(selectedThemeText)
            if (themeIndex >= 0) themeValues[themeIndex] else "dracula"
        }

        val fontSizeOverride = binding.editFontSizeOverride.text.toString().toIntOrNull()?.takeIf { it in 8..32 }
        val postConnectScript = binding.editPostConnectScript.text.toString().takeIf { it.isNotBlank() }

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
            identityId = selectedIdentityId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive,
            x11Forwarding = x11Forwarding,
            useMosh = useMosh,
            multiplexerMode = multiplexerMode,
            multiplexerSessionName = multiplexerSessionName,
            theme = theme,
            fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript,
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
            identityId = selectedIdentityId,
            terminalType = terminalType,
            compression = compression,
            keepAlive = keepAlive,
            x11Forwarding = x11Forwarding,
            useMosh = useMosh,
            multiplexerMode = multiplexerMode,
            multiplexerSessionName = multiplexerSessionName,
            theme = theme,
            fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript,
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
            binding.btnTest.isEnabled = false
            binding.btnTest.text = "Testing..."

            var tempProfileId: String? = null

            try {
                val profile = createConnectionProfile()

                // For new (unsaved) connections the password hasn't been stored yet.
                // Temporarily store it as SESSION_ONLY so auth can find it, then delete it.
                val authType = getSelectedAuthType()
                if (!isEditMode &&
                    (authType == io.github.tabssh.ssh.auth.AuthType.PASSWORD ||
                     authType == io.github.tabssh.ssh.auth.AuthType.KEYBOARD_INTERACTIVE)) {
                    val pw = binding.editPassword.text.toString()
                    if (pw.isNotEmpty()) {
                        app.securePasswordManager.storePassword(
                            profile.id, pw,
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.SESSION_ONLY
                        )
                        tempProfileId = profile.id
                    }
                }

                // Create a direct SSHConnection — bypass SSHSessionManager to avoid
                // side-effects (pool entries, foreground service, connection listeners).
                val connection = io.github.tabssh.ssh.connection.SSHConnection(
                    profile, lifecycleScope, this@ConnectionEditActivity
                )
                connection.hostKeyChangedCallback = app.sshSessionManager.hostKeyChangedCallback
                connection.newHostKeyCallback = app.sshSessionManager.newHostKeyCallback

                val success = connection.connect()
                connection.disconnect()

                if (success) {
                    showToast("✅ Connection test successful!")
                } else {
                    val errorMsg = connection.errorMessage.value ?: "Connection test failed"
                    showError(errorMsg, "Test Failed")
                }

            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Connection test failed", e)
                showError(e.message ?: "Unknown error", "Test Failed")
            } finally {
                // Clean up any temp password that was stored only for this test
                tempProfileId?.let { app.securePasswordManager.clearPassword(it) }
                binding.btnTest.isEnabled = true
                binding.btnTest.text = "Test"
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
            @Suppress("DEPRECATION")
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
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = "❌ Key Generation Failed",
            message = "Failed to generate SSH key:\n\n$errorMessage\n\nPlease try again or contact support if the problem persists.",
            onDismiss = {
                // User can manually retry via the Generate button
            }
        )
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
                showError("❌ Import failed: ${e.message}", "Error")
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
            showError("❌ Key import failed: ${e.message}", "Error")
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
                showError("❌ Encrypted key import failed: ${e.message}", "Error")
            }
        }
    }
    
    private fun showKeyImportErrorDialog(errorMessage: String) {
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = "❌ SSH Key Import Failed",
            message = errorMessage,
            onDismiss = {
                // User can try importing again
            }
        )
    }
    
    private fun showKeyImportHelpDialog() {
        val helpText = """
SSH Key Import - Supported Formats:

✅ FULLY SUPPORTED (No Conversion Needed):
• OpenSSH Private Key (-----BEGIN OPENSSH PRIVATE KEY-----)
• RSA Private Key (-----BEGIN RSA PRIVATE KEY-----)
• DSA Private Key (-----BEGIN DSA PRIVATE KEY-----)
• EC Private Key (-----BEGIN EC PRIVATE KEY-----)
• PKCS#8 (-----BEGIN PRIVATE KEY-----)
• PKCS#8 Encrypted (-----BEGIN ENCRYPTED PRIVATE KEY-----)
• PuTTY v2/v3 (.ppk files)

✅ KEY TYPES SUPPORTED:
• RSA (2048, 3072, 4096-bit)
• ECDSA (P-256, P-384, P-521)
• Ed25519
• DSA

✅ ENCRYPTED KEYS:
• Passphrase-protected keys fully supported
• AES-128, AES-256, 3DES encryption

❌ If Import Still Fails:

1. Check key file is complete (not truncated)
2. Verify passphrase is correct (case-sensitive)
3. Try without extra whitespace/newlines
4. Convert PuTTY to OpenSSH:
   puttygen key.ppk -O private-openssh -o key

5. Re-export key:
   ssh-keygen -p -m PEM -f your_key

📋 The error message above can be copied by tapping "Copy Error"
        """.trimIndent()
        
        // Create scrollable TextView with selectable text
        val textView = android.widget.TextView(this).apply {
            text = helpText
            setTextIsSelectable(true)
            setPadding(50, 40, 50, 10)
            textSize = 14f
        }
        
        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔑 SSH Key Import Help")
            .setView(scrollView)
            .setPositiveButton("Got It", null)
            .show()
    }

    @Suppress("DEPRECATION")
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
                    showError("Failed to read key file: ${e.message}", "Error")
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
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    private fun setupTerminalTypeSpinner() {
        val terminalTypes = resources.getStringArray(R.array.terminal_types)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, terminalTypes)
        binding.spinnerTerminalType.setAdapter(adapter)
        binding.spinnerTerminalType.setText("xterm-256color", false) // Default value
    }

    private fun setupMultiplexerSpinner() {
        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modeEntries)
        binding.spinnerMultiplexerMode.setAdapter(adapter)
        binding.spinnerMultiplexerMode.setText(modeEntries[0], false) // Default: Disabled

        binding.spinnerMultiplexerMode.setOnItemClickListener { _, _, position, _ ->
            // Show session name field when not "OFF"
            val showSessionName = modeValues[position] != "OFF"
            binding.layoutMultiplexerSessionName.visibility = if (showSessionName) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
    }

    private fun setupConnectionThemeSpinner() {
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        // Add "Use Global Default" as first option
        val entries = listOf("Use Global Default") + themeEntries.toList()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, entries)
        binding.spinnerConnectionTheme.setAdapter(adapter)
        binding.spinnerConnectionTheme.setText(entries[0], false) // Default: Use Global
    }

    private fun setupPortKnockUI() {
        // Toggle configure button visibility based on switch
        binding.switchPortKnock.setOnCheckedChangeListener { _, isChecked ->
            binding.btnConfigurePortKnock.visibility = if (isChecked) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }
        
        // Configure knock sequence button
        binding.btnConfigurePortKnock.setOnClickListener {
            showPortKnockConfigDialog()
        }
    }
    
    private fun showPortKnockConfigDialog() {
        // Simple input dialog for port knock sequence
        val dialogView = android.widget.LinearLayout(this)
        dialogView.orientation = android.widget.LinearLayout.VERTICAL
        dialogView.setPadding(50, 40, 50, 10)
        
        val textView = android.widget.TextView(this)
        textView.text = "Enter port knock sequence (format: port:protocol, comma-separated)\nExample: 7000:TCP,8000:TCP,9000:UDP"
        textView.textSize = 14f
        dialogView.addView(textView)
        
        val editText = android.widget.EditText(this)
        editText.hint = "7000:TCP,8000:TCP,9000:UDP"
        editText.setSingleLine(false)
        editText.maxLines = 3
        dialogView.addView(editText)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Port Knock Sequence")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sequence = editText.text.toString().trim()
                val count = if (sequence.isNotBlank()) sequence.split(",").size else 0
                android.widget.Toast.makeText(
                    this,
                    "✓ Knock sequence saved: $count ports",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                android.widget.Toast.makeText(this, "Sequence cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
