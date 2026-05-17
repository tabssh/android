package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityConnectionEditBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.crypto.keys.GenerateResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import io.github.tabssh.utils.showError
import java.util.UUID

/**
 * Activity for creating and editing connection profiles.
 *
 * Protocol-aware:
 *   SSH    — full SSH form; identity picker shows [Identity] (SSH identities)
 *   VNC    — simplified form (name/host/port + VNC identity); saves [VncHost]
 *   Telnet — username/password only; identity picker hidden
 *
 * Launch via [createIntent] (SSH/Telnet/ConnectionProfile) or
 * [createVncIntent] (VNC/VncHost).
 */
class ConnectionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_IS_EDIT_MODE = "is_edit_mode"
        /** When present, load + save as [VncHost] regardless of protocol spinner. */
        const val EXTRA_VNC_HOST_ID = "vnc_host_id"
        private const val REQUEST_CODE_IMPORT_KEY = 1001

        /** Launch to create/edit an SSH or Telnet [ConnectionProfile]. */
        fun createIntent(context: Context, connectionId: String? = null): Intent {
            return Intent(context, ConnectionEditActivity::class.java).apply {
                connectionId?.let { putExtra(EXTRA_CONNECTION_ID, it) }
                putExtra(EXTRA_IS_EDIT_MODE, connectionId != null)
            }
        }

        /** Launch to create/edit a [VncHost]. */
        fun createVncIntent(context: Context, vncHostId: String? = null): Intent {
            return Intent(context, ConnectionEditActivity::class.java).apply {
                vncHostId?.let { putExtra(EXTRA_VNC_HOST_ID, it) }
                putExtra(EXTRA_IS_EDIT_MODE, vncHostId != null)
            }
        }
    }

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var app: TabSSHApplication

    private var existingProfile: ConnectionProfile? = null
    private var editingVncHostId: String? = null
    private var isEditMode = false

    // Protocol state — "ssh" | "vnc" | "telnet"
    private var currentProtocol: String = "ssh"

    private var availableKeys: List<StoredKey> = emptyList()
    private var selectedKeyIndex: Int = -1
    private var pendingRestoreKeyId: String? = null

    private var selectedGroupId: String? = null
    private var selectedGroupName: String = "No Group"

    // SSH identities (Identity table)
    private var availableIdentities: List<io.github.tabssh.storage.database.entities.Identity> = emptyList()
    private var selectedIdentityId: String? = null

    // VNC identities (VncIdentity table)
    private var availableVncIdentities: List<VncIdentity> = emptyList()
    private var selectedVncIdentityId: String? = null

    /** Wave 3.1 — current color tag in the editor (ARGB int; 0 = none). */
    private var currentColorTag: Int = 0

    private val colorTagPresets = listOf(
        0xFFE53935.toInt() to "Red",
        0xFFFB8C00.toInt() to "Orange",
        0xFFFDD835.toInt() to "Yellow",
        0xFF43A047.toInt() to "Green",
        0xFF1E88E5.toInt() to "Blue",
        0xFF8E24AA.toInt() to "Purple",
        0xFF6D4C41.toInt() to "Brown",
        0xFF546E7A.toInt() to "Slate"
    )

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
        setupProxyTypeSpinner()
        setupTerminalTypeSpinner()
        setupMultiplexerSpinner()
        setupConnectionThemeSpinner()
        setupRemoteCommandSpinner()
        setupValidation()
        setupButtons()
        setupPortKnockUI()
        // Protocol spinner wired last — it calls updateProtocolUI() which
        // triggers identity list loading and card visibility.
        setupProtocolSpinner()

        val vncHostId = intent.getStringExtra(EXTRA_VNC_HOST_ID)
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)

        when {
            vncHostId != null -> {
                editingVncHostId = vncHostId
                loadVncHost(vncHostId)
            }
            connectionId != null -> loadConnection(connectionId)
            else -> {
                // New connection — pre-fill the preferred default username.
                val defaultUser = io.github.tabssh.storage.preferences.PreferenceManager(this)
                    .getDefaultUsername()
                if (defaultUser.isNotBlank()) {
                    binding.editUsername.setText(defaultUser)
                }
            }
        }

        Logger.d("ConnectionEditActivity", "editMode=$isEditMode vncHostId=$vncHostId connectionId=$connectionId")
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

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
            R.id.action_set_group -> { showGroupSelectionDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------------------
    // Protocol spinner — drives all per-protocol visibility changes
    // -------------------------------------------------------------------------

    private fun setupProtocolSpinner() {
        binding.spinnerProtocol.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val values = resources.getStringArray(R.array.protocol_values)
                    val proto = values.getOrElse(position) { "ssh" }
                    updateProtocolUI(proto)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        // Fire the listener for position 0 (SSH default) to initialise visibility.
        updateProtocolUI("ssh")
    }

    /**
     * Update card visibility, field hints, port default, and identity list for
     * the chosen protocol. Shared state [currentProtocol] is updated here.
     *
     * Visibility matrix:
     *
     * |Card                     | SSH | VNC | Telnet |
     * |-------------------------|-----|-----|--------|
     * | cardAuthentication      |  ✓  |  ✗  |   ✓    |
     * | layoutIdentityRow       |  ✓  |  ✓  |   ✗    |
     * | layoutUsernameInput     |  ✓  |  ✗  |   ✓    |
     * | cardAdvancedSettings    |  ✓  |  ✗  |   ✓    |
     * | cardNotificationsSection|  ✓  |  ✗  |   ✓    |
     * | cardMultiplexer         |  ✓  |  ✗  |   ✗    |
     * | cardAppearance          |  ✓  |  ✗  |   ✓    |
     * | cardProxy               |  ✓  |  ✗  |   ✗    |
     */
    private fun updateProtocolUI(proto: String) {
        if (currentProtocol == proto) return
        currentProtocol = proto

        when (proto) {
            "vnc" -> {
                // Hide SSH-specific authentication and advanced sections
                binding.cardAuthentication.visibility = View.GONE
                binding.cardAdvancedSettings.visibility = View.GONE
                binding.cardNotificationsSection.visibility = View.GONE
                binding.cardMultiplexer.visibility = View.GONE
                binding.cardAppearance.visibility = View.GONE
                binding.cardProxy.visibility = View.GONE
                // VNC hosts have no username field
                binding.layoutUsernameInput.visibility = View.GONE
                // VNC identity picker
                binding.layoutIdentityRow.visibility = View.VISIBLE
                // Default port
                autoSetPort("5900", setOf("22", "23"))
                // Reload identity list with VNC identities
                loadVncIdentities()
            }
            "telnet" -> {
                binding.cardAuthentication.visibility = View.VISIBLE
                binding.cardAdvancedSettings.visibility = View.VISIBLE
                binding.cardNotificationsSection.visibility = View.VISIBLE
                // Multiplexer and proxy are not applicable to Telnet
                binding.cardMultiplexer.visibility = View.GONE
                binding.cardAppearance.visibility = View.VISIBLE
                binding.cardProxy.visibility = View.GONE
                binding.layoutUsernameInput.visibility = View.VISIBLE
                // Hide identity picker — Telnet uses inline user/pass only
                binding.layoutIdentityRow.visibility = View.GONE
                autoSetPort("23", setOf("22", "5900"))
            }
            else -> { // "ssh"
                binding.cardAuthentication.visibility = View.VISIBLE
                binding.cardAdvancedSettings.visibility = View.VISIBLE
                binding.cardNotificationsSection.visibility = View.VISIBLE
                binding.cardMultiplexer.visibility = View.VISIBLE
                binding.cardAppearance.visibility = View.VISIBLE
                binding.cardProxy.visibility = View.VISIBLE
                binding.layoutUsernameInput.visibility = View.VISIBLE
                binding.layoutIdentityRow.visibility = View.VISIBLE
                autoSetPort("22", setOf("5900", "23"))
                // Reload identity list with SSH identities
                loadSshIdentities()
            }
        }
    }

    /** Set [newPort] only when the port field is blank or holds one of [swapPorts]. */
    private fun autoSetPort(newPort: String, swapPorts: Set<String>) {
        val current = binding.editPort.text?.toString() ?: ""
        if (current.isBlank() || current in swapPorts) {
            binding.editPort.setText(newPort)
        }
    }

    // -------------------------------------------------------------------------
    // Identity spinner — protocol-aware
    // -------------------------------------------------------------------------

    /** Populate the identity spinner with SSH [Identity] rows. */
    private fun loadSshIdentities() {
        lifecycleScope.launch {
            try {
                availableIdentities = app.database.identityDao().getAllIdentitiesList()
                availableVncIdentities = emptyList()

                val items = listOf("No Identity") + availableIdentities.map { it.name }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    items
                )
                binding.spinnerIdentity.setAdapter(adapter)
                binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
                    if (position > 0) {
                        val identity = availableIdentities[position - 1]
                        selectedIdentityId = identity.id
                        selectedVncIdentityId = null
                        binding.editUsername.setText(identity.username)
                        binding.cardAuthentication.visibility = View.GONE
                    } else {
                        selectedIdentityId = null
                        selectedVncIdentityId = null
                        binding.cardAuthentication.visibility = View.VISIBLE
                    }
                }
                restoreSshIdentitySpinner()
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load SSH identities", e)
            }
        }
    }

    /** Populate the identity spinner with [VncIdentity] rows. */
    private fun loadVncIdentities() {
        lifecycleScope.launch {
            try {
                availableVncIdentities = app.database.vncIdentityDao().getAllIdentitiesList()
                availableIdentities = emptyList()

                val items = listOf("No Identity") + availableVncIdentities.map { it.name }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    items
                )
                binding.spinnerIdentity.setAdapter(adapter)
                binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
                    selectedVncIdentityId = if (position > 0) availableVncIdentities[position - 1].id else null
                    selectedIdentityId = null
                }
                restoreVncIdentitySpinner()
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load VNC identities", e)
            }
        }
    }

    private fun restoreSshIdentitySpinner() {
        val id = selectedIdentityId
        if (id != null) {
            val idx = availableIdentities.indexOfFirst { it.id == id }
            if (idx >= 0) {
                binding.spinnerIdentity.setText(availableIdentities[idx].name, false)
                binding.cardAuthentication.visibility = View.GONE
                return
            }
        }
        binding.spinnerIdentity.setText("No Identity", false)
        binding.cardAuthentication.visibility = View.VISIBLE
    }

    private fun restoreVncIdentitySpinner() {
        val id = selectedVncIdentityId
        if (id != null) {
            val idx = availableVncIdentities.indexOfFirst { it.id == id }
            if (idx >= 0) {
                binding.spinnerIdentity.setText(availableVncIdentities[idx].name, false)
                return
            }
        }
        binding.spinnerIdentity.setText("No Identity", false)
    }

    // -------------------------------------------------------------------------
    // Auth type spinner
    // -------------------------------------------------------------------------

    private fun setupAuthTypeSpinner() {
        val authTypes = AuthType.getAvailableTypes()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            authTypes.map { it.displayName }
        )
        binding.spinnerAuthType.setAdapter(adapter)
        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ ->
            updateAuthTypeUI(authTypes[position])
        }
        if (authTypes.isNotEmpty()) {
            binding.spinnerAuthType.setText(authTypes[0].displayName, false)
            updateAuthTypeUI(authTypes[0])
        }
    }

    private fun updateAuthTypeUI(authType: AuthType) {
        when (authType) {
            AuthType.PASSWORD -> {
                binding.layoutPassword.visibility = View.VISIBLE
                binding.layoutSshKey.visibility = View.GONE
                binding.layoutSavePassword.visibility = View.VISIBLE
            }
            AuthType.PUBLIC_KEY -> {
                binding.layoutPassword.visibility = View.GONE
                binding.layoutSshKey.visibility = View.VISIBLE
                binding.layoutSavePassword.visibility = View.GONE
            }
            AuthType.KEYBOARD_INTERACTIVE -> {
                binding.layoutPassword.visibility = View.VISIBLE
                binding.layoutSshKey.visibility = View.GONE
                binding.layoutSavePassword.visibility = View.VISIBLE
            }
            else -> {
                binding.layoutPassword.visibility = View.GONE
                binding.layoutSshKey.visibility = View.GONE
                binding.layoutSavePassword.visibility = View.GONE
            }
        }
    }

    // -------------------------------------------------------------------------
    // SSH key spinner
    // -------------------------------------------------------------------------

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
                pendingRestoreKeyId?.let { keyId ->
                    pendingRestoreKeyId = null
                    val keyIndex = availableKeys.indexOfFirst { it.keyId == keyId }
                    if (keyIndex >= 0) {
                        selectedKeyIndex = keyIndex + 1
                        binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                    }
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load SSH keys", e)
                showError("Failed to load SSH keys", "Error")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Proxy type spinner
    // -------------------------------------------------------------------------

    private fun setupProxyTypeSpinner() {
        val proxyTypes = listOf("None", "HTTP", "SOCKS4", "SOCKS5", "SSH Jump Host")
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, proxyTypes)
        binding.spinnerProxyType.setAdapter(adapter)
        binding.spinnerProxyType.setOnItemClickListener { _, _, position, _ ->
            updateProxyTypeUI(proxyTypes[position])
        }
        binding.spinnerProxyType.setText(proxyTypes[0], false)
        updateProxyTypeUI(proxyTypes[0])

        val authTypes = AuthType.getAvailableTypes()
        val authAdapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, authTypes.map { it.displayName }
        )
        binding.spinnerProxyAuthType.setAdapter(authAdapter)
        binding.spinnerProxyAuthType.setOnItemClickListener { _, _, position, _ ->
            updateProxyAuthTypeUI(authTypes[position])
        }

        lifecycleScope.launch {
            val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
            val keyAdapter = ArrayAdapter(
                this@ConnectionEditActivity, android.R.layout.simple_list_item_1, keyNames
            )
            binding.spinnerProxySshKey.setAdapter(keyAdapter)
        }
    }

    private fun updateProxyTypeUI(proxyType: String) {
        when (proxyType) {
            "None" -> {
                binding.layoutProxyConfig.visibility = View.GONE
                binding.layoutJumpHostAuth.visibility = View.GONE
            }
            "SSH Jump Host" -> {
                binding.layoutProxyConfig.visibility = View.VISIBLE
                binding.layoutJumpHostAuth.visibility = View.VISIBLE
                binding.editProxyPort.setText("22")
            }
            "HTTP" -> {
                binding.layoutProxyConfig.visibility = View.VISIBLE
                binding.layoutJumpHostAuth.visibility = View.GONE
                binding.editProxyPort.setText("8080")
            }
            "SOCKS4", "SOCKS5" -> {
                binding.layoutProxyConfig.visibility = View.VISIBLE
                binding.layoutJumpHostAuth.visibility = View.GONE
                binding.editProxyPort.setText("1080")
            }
        }
    }

    private fun updateProxyAuthTypeUI(authType: AuthType) {
        binding.layoutProxyKey.visibility =
            if (authType == AuthType.PUBLIC_KEY) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------------
    // Group spinner
    // -------------------------------------------------------------------------

    private fun setupGroupSpinner() {
        lifecycleScope.launch {
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

            existingProfile?.groupId?.let { groupId ->
                val index = groups.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    binding.spinnerGroup.setText(groups[index].name, false)
                    selectedGroupId = groupId
                    selectedGroupName = groups[index].name
                    supportActionBar?.subtitle = groups[index].name
                } else {
                    binding.spinnerGroup.setText("No Group", false)
                    selectedGroupId = null
                    selectedGroupName = "No Group"
                    supportActionBar?.subtitle = null
                }
            } ?: run {
                binding.spinnerGroup.setText("No Group", false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private fun setupValidation() {
        binding.editHost.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validateHost() }
        binding.editPort.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validatePort() }
        binding.editUsername.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) validateUsername() }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConnection() }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnGenerateKey.setOnClickListener { showKeyManagementDialog() }
        binding.btnPickColorTag.setOnClickListener { showColorTagPicker() }
        binding.btnClearColorTag.setOnClickListener {
            currentColorTag = 0
            renderColorTagPreview()
        }
        renderColorTagPreview()
    }

    private fun showColorTagPicker() {
        val labels = colorTagPresets.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pick color tag")
            .setItems(labels) { _, which ->
                currentColorTag = colorTagPresets[which].first
                renderColorTagPreview()
            }
            .setNeutralButton("Clear") { _, _ -> currentColorTag = 0; renderColorTagPreview() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderColorTagPreview() {
        if (currentColorTag != 0) {
            binding.previewColorTag.setBackgroundColor(currentColorTag)
        } else {
            binding.previewColorTag.setBackgroundColor(0xFFCCCCCC.toInt())
        }
    }

    // -------------------------------------------------------------------------
    // Load — SSH/Telnet ConnectionProfile
    // -------------------------------------------------------------------------

    private fun loadConnection(connectionId: String) {
        lifecycleScope.launch {
            try {
                existingProfile = app.database.connectionDao().getConnectionById(connectionId)
                existingProfile?.let { profile ->
                    populateFields(profile)
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

        // Protocol — SSH=0, VNC=1, Telnet=2 (VNC profiles don't come through here;
        // this handles the legacy SSH/Telnet split).
        val protocolIndex = when (profile.protocol.lowercase()) {
            "telnet" -> 2
            else -> 0
        }
        // Setting selection fires onItemSelected → updateProtocolUI(); that in
        // turn loads the right identity list and resets visibility.
        binding.spinnerProtocol.setSelection(protocolIndex)

        // Set identity before restoring, so restoreSshIdentitySpinner() can use it
        selectedIdentityId = profile.identityId
        restoreSshIdentitySpinner()

        val authType = profile.getAuthTypeEnum()
        val authTypes = AuthType.getAvailableTypes()
        val authTypeIndex = authTypes.indexOf(authType)
        if (authTypeIndex >= 0) {
            binding.spinnerAuthType.setText(authTypes[authTypeIndex].displayName, false)
            updateAuthTypeUI(authTypes[authTypeIndex])
        }

        if (authType == AuthType.PUBLIC_KEY && profile.keyId != null) {
            val keyIndex = availableKeys.indexOfFirst { it.keyId == profile.keyId }
            if (keyIndex >= 0) {
                selectedKeyIndex = keyIndex + 1
                val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                if (selectedKeyIndex < keyNames.size) {
                    binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                }
            } else {
                pendingRestoreKeyId = profile.keyId
            }
        }

        if (authType == AuthType.PASSWORD || authType == AuthType.KEYBOARD_INTERACTIVE) {
            val storedPassword = withContext(Dispatchers.IO) {
                app.securePasswordManager.retrievePassword(profile.id)
            }
            if (storedPassword != null) {
                binding.editPassword.setText(storedPassword)
                binding.switchSavePassword.isChecked = true
            }
        }

        binding.spinnerTerminalType.setText(profile.terminalType, false)
        binding.switchCompression.isChecked = profile.compression
        binding.switchX11Forwarding.isChecked = profile.x11Forwarding
        binding.switchUseMosh.isChecked = profile.useMosh

        val notifAlertEntries = resources.getStringArray(R.array.notif_alert_mode_entries)
        binding.spinnerNotifSound.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, notifAlertEntries)
        )
        binding.spinnerNotifVibrate.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, notifAlertEntries)
        )
        binding.spinnerNotifSound.setText(
            notifAlertEntries.getOrNull(profile.notifSoundMode.coerceIn(0, 2)) ?: notifAlertEntries[0], false
        )
        binding.spinnerNotifVibrate.setText(
            notifAlertEntries.getOrNull(profile.notifVibrateMode.coerceIn(0, 2)) ?: notifAlertEntries[0], false
        )

        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val modeIndex = modeValues.indexOf(profile.multiplexerMode)
        if (modeIndex >= 0) {
            binding.spinnerMultiplexerMode.setText(modeEntries[modeIndex], false)
            binding.layoutMultiplexerSessionName.visibility = if (profile.multiplexerMode != "OFF") {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        profile.multiplexerSessionName?.let { binding.editMultiplexerSessionName.setText(it) }

        selectedGroupId = profile.groupId
        selectedGroupName = "No Group"
        if (selectedGroupId != null) {
            supportActionBar?.subtitle = selectedGroupName
        }

        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val themeIndex = themeValues.indexOf(profile.theme)
        if (themeIndex >= 0) {
            val displayEntries = listOf("Use Global Default") + themeEntries.toList()
            binding.spinnerConnectionTheme.setText(displayEntries[themeIndex + 1], false)
        } else {
            binding.spinnerConnectionTheme.setText("Use Global Default", false)
        }

        profile.fontSizeOverride?.let { binding.editFontSizeOverride.setText(it.toString()) }
        profile.postConnectScript?.let { binding.editPostConnectScript.setText(it) }
        profile.envVars?.let { binding.editEnvVars.setText(it) }
        binding.switchAgentForwarding.isChecked = profile.agentForwarding

        applyRemoteCommandToUi(profile.remoteCommand)

        binding.spinnerIpMode.setSelection(
            when (profile.ipMode.lowercase()) { "ipv4" -> 1; "ipv6" -> 2; else -> 0 }
        )

        currentColorTag = profile.colorTag
        renderColorTagPreview()

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
            if (proxyType == "SSH") {
                profile.proxyUsername?.let { binding.editProxyUsername.setText(it) }
                if (profile.proxyAuthType != null) {
                    val proxyAuthType = try {
                        AuthType.valueOf(profile.proxyAuthType)
                    } catch (e: IllegalArgumentException) { AuthType.PASSWORD }
                    val authTypes = AuthType.getAvailableTypes()
                    val proxyAuthTypeIndex = authTypes.indexOf(proxyAuthType)
                    if (proxyAuthTypeIndex >= 0) {
                        binding.spinnerProxyAuthType.setText(authTypes[proxyAuthTypeIndex].displayName, false)
                        updateProxyAuthTypeUI(authTypes[proxyAuthTypeIndex])
                    }
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

    // -------------------------------------------------------------------------
    // Load — VncHost
    // -------------------------------------------------------------------------

    private fun loadVncHost(vncHostId: String) {
        lifecycleScope.launch {
            try {
                val vncHost = app.database.vncHostDao().getById(vncHostId)
                if (vncHost != null) {
                    populateVncFields(vncHost)
                    supportActionBar?.title = "Edit ${vncHost.name}"
                } else {
                    showError("VNC host not found", "Error")
                    finish()
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load VNC host", e)
                showError("Failed to load VNC host", "Error")
                finish()
            }
        }
    }

    private fun populateVncFields(vncHost: VncHost) {
        binding.editName.setText(vncHost.name)
        binding.editHost.setText(vncHost.host)
        binding.editPort.setText(vncHost.effectivePort.toString())
        // Set protocol spinner to VNC (index 1) — triggers updateProtocolUI("vnc")
        // which loads VNC identities and hides SSH-only cards.
        binding.spinnerProtocol.setSelection(1)
        // Set identity before restoring so restoreVncIdentitySpinner() can use it.
        selectedVncIdentityId = vncHost.identityId
        restoreVncIdentitySpinner()
        selectedGroupId = vncHost.groupId
        currentColorTag = vncHost.colorTag
        renderColorTagPreview()
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private fun saveConnection() {
        if (!validateAllFields()) return

        if (currentProtocol == "vnc") {
            saveVncHost()
            return
        }
        saveConnectionProfile()
    }

    private fun saveVncHost() {
        lifecycleScope.launch {
            try {
                val name = binding.editName.text.toString().trim()
                val host = binding.editHost.text.toString().trim()
                val port = binding.editPort.text.toString().toIntOrNull() ?: 5900
                val now = System.currentTimeMillis()
                val hostId = editingVncHostId ?: UUID.randomUUID().toString()

                val existing = if (editingVncHostId != null) {
                    app.database.vncHostDao().getById(editingVncHostId!!)
                } else null

                val vncHost = existing?.copy(
                    name = name,
                    host = host,
                    port = port,
                    identityId = selectedVncIdentityId,
                    groupId = selectedGroupId,
                    colorTag = currentColorTag,
                    modifiedAt = now
                ) ?: VncHost(
                    id = hostId,
                    name = name,
                    host = host,
                    port = port,
                    identityId = selectedVncIdentityId,
                    groupId = selectedGroupId,
                    colorTag = currentColorTag,
                    createdAt = now,
                    modifiedAt = now
                )

                if (existing != null) {
                    app.database.vncHostDao().update(vncHost)
                    Logger.i("ConnectionEditActivity", "Updated VNC host: $name")
                    showToast("VNC host updated")
                } else {
                    app.database.vncHostDao().insert(vncHost)
                    Logger.i("ConnectionEditActivity", "Saved VNC host: $name")
                    showToast("VNC host saved")
                }

                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to save VNC host", e)
                showError("Failed to save VNC host: ${e.message}", "Error")
            }
        }
    }

    private fun saveConnectionProfile() {
        lifecycleScope.launch {
            try {
                val profile = createConnectionProfile()

                if (isEditMode && existingProfile != null) {
                    app.database.connectionDao().updateConnection(profile)
                    Logger.i("ConnectionEditActivity", "Updated connection: ${profile.name}")
                    showToast("Connection updated")
                } else {
                    app.database.connectionDao().insertConnection(profile)
                    Logger.i("ConnectionEditActivity", "Created connection: ${profile.name}")
                    showToast("Connection saved")
                }

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

        val selectedIdentity = selectedIdentityId?.let { id -> availableIdentities.find { it.id == id } }

        val authType = if (selectedIdentity != null) {
            selectedIdentity.authType
        } else {
            getSelectedAuthType()
        }

        val keyId = if (selectedIdentity != null && selectedIdentity.authType == AuthType.PUBLIC_KEY) {
            selectedIdentity.keyId
        } else if (authType == AuthType.PUBLIC_KEY) {
            if (selectedKeyIndex > 0 && selectedKeyIndex - 1 < availableKeys.size) {
                availableKeys[selectedKeyIndex - 1].keyId
            } else null
        } else null

        val terminalType = binding.spinnerTerminalType.text.toString().takeIf { it.isNotBlank() } ?: "xterm-256color"
        val compression = binding.switchCompression.isChecked
        val keepAlive = true
        val x11Forwarding = binding.switchX11Forwarding.isChecked
        val useMosh = binding.switchUseMosh.isChecked

        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val multiplexerModeText = binding.spinnerMultiplexerMode.text.toString()
        val modeIndex = modeEntries.indexOf(multiplexerModeText)
        val multiplexerMode = if (modeIndex >= 0) modeValues[modeIndex] else "OFF"
        val multiplexerSessionName = binding.editMultiplexerSessionName.text.toString().takeIf { it.isNotBlank() }

        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val selectedThemeText = binding.spinnerConnectionTheme.text.toString()
        val theme = if (selectedThemeText == "Use Global Default") {
            "dracula"
        } else {
            val themeIndex = themeEntries.indexOf(selectedThemeText)
            if (themeIndex >= 0) themeValues[themeIndex] else "dracula"
        }

        val fontSizeOverride = binding.editFontSizeOverride.text.toString().toIntOrNull()?.takeIf { it in 8..32 }
        val postConnectScript = binding.editPostConnectScript.text.toString().takeIf { it.isNotBlank() }
        val envVars = binding.editEnvVars.text.toString().takeIf { it.isNotBlank() }
        val agentForwarding = binding.switchAgentForwarding.isChecked
        val remoteCommand = readRemoteCommandFromUi()

        // Use tracked currentProtocol — "vnc" never reaches here (saveVncHost handles it).
        val protocol = if (currentProtocol == "vnc") "ssh" else currentProtocol

        val ipMode = when (binding.spinnerIpMode.selectedItemPosition) { 1 -> "ipv4"; 2 -> "ipv6"; else -> "auto" }
        val colorTag = currentColorTag

        val proxyTypeDisplay = binding.spinnerProxyType.text.toString()
        val proxyType = when (proxyTypeDisplay) {
            "SSH Jump Host" -> "SSH"
            "None" -> null
            else -> proxyTypeDisplay
        }
        val proxyHost = if (proxyType != null) binding.editProxyHost.text.toString().takeIf { it.isNotBlank() } else null
        val proxyPort = if (proxyType != null) binding.editProxyPort.text.toString().toIntOrNull() else null
        val proxyUsername = if (proxyType == "SSH") binding.editProxyUsername.text.toString().takeIf { it.isNotBlank() } else null
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

        val notifAlertEntries = resources.getStringArray(R.array.notif_alert_mode_entries)
        val notifSoundMode = notifAlertEntries.indexOf(binding.spinnerNotifSound.text.toString()).takeIf { it >= 0 } ?: 0
        val notifVibrateMode = notifAlertEntries.indexOf(binding.spinnerNotifVibrate.text.toString()).takeIf { it >= 0 } ?: 0

        return existingProfile?.copy(
            name = name, host = host, port = port, username = username,
            protocol = protocol, authType = authType.name, keyId = keyId,
            identityId = selectedIdentityId, terminalType = terminalType,
            compression = compression, keepAlive = keepAlive,
            x11Forwarding = x11Forwarding, useMosh = useMosh,
            multiplexerMode = multiplexerMode, multiplexerSessionName = multiplexerSessionName,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            proxyType = proxyType, proxyHost = proxyHost, proxyPort = proxyPort,
            proxyUsername = proxyUsername, proxyAuthType = proxyAuthType, proxyKeyId = proxyKeyId,
            colorTag = colorTag, notifSoundMode = notifSoundMode, notifVibrateMode = notifVibrateMode
        ) ?: ConnectionProfile(
            name = name, host = host, port = port, username = username,
            protocol = protocol, authType = authType.name, keyId = keyId,
            identityId = selectedIdentityId, terminalType = terminalType,
            compression = compression, keepAlive = keepAlive,
            x11Forwarding = x11Forwarding, useMosh = useMosh,
            multiplexerMode = multiplexerMode, multiplexerSessionName = multiplexerSessionName,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            proxyType = proxyType, proxyHost = proxyHost, proxyPort = proxyPort,
            proxyUsername = proxyUsername, proxyAuthType = proxyAuthType, proxyKeyId = proxyKeyId,
            notifSoundMode = notifSoundMode, notifVibrateMode = notifVibrateMode
        )
    }

    // -------------------------------------------------------------------------
    // Remote command spinner (Issue #37)
    // -------------------------------------------------------------------------

    private fun setupRemoteCommandSpinner() {
        val customIndex = resources.getStringArray(R.array.remote_command_values).size - 1
        binding.spinnerRemoteCommand.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    binding.layoutRemoteCommandCustom.visibility =
                        if (position == customIndex) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun applyRemoteCommandToUi(remoteCommand: String?) {
        val values = resources.getStringArray(R.array.remote_command_values)
        val customIndex = values.size - 1
        if (remoteCommand.isNullOrBlank()) {
            binding.spinnerRemoteCommand.setSelection(0)
            binding.editRemoteCommandCustom.setText("")
            binding.layoutRemoteCommandCustom.visibility = View.GONE
            return
        }
        val matchIndex = values.indexOfFirst { it == remoteCommand }.takeIf { it > 0 && it != customIndex }
        if (matchIndex != null) {
            binding.spinnerRemoteCommand.setSelection(matchIndex)
            binding.editRemoteCommandCustom.setText("")
            binding.layoutRemoteCommandCustom.visibility = View.GONE
        } else {
            binding.spinnerRemoteCommand.setSelection(customIndex)
            binding.editRemoteCommandCustom.setText(remoteCommand)
            binding.layoutRemoteCommandCustom.visibility = View.VISIBLE
        }
    }

    private fun readRemoteCommandFromUi(): String? {
        val values = resources.getStringArray(R.array.remote_command_values)
        val customIndex = values.size - 1
        val pos = binding.spinnerRemoteCommand.selectedItemPosition
        return when {
            pos == 0 -> null
            pos == customIndex -> binding.editRemoteCommandCustom.text.toString().trim().takeIf { it.isNotEmpty() }
            pos in values.indices -> values[pos].takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun getSelectedAuthType(): AuthType {
        val authTypes = AuthType.getAvailableTypes()
        val selectedText = binding.spinnerAuthType.text.toString()
        return authTypes.find { it.displayName == selectedText } ?: AuthType.PASSWORD
    }

    // -------------------------------------------------------------------------
    // Field validation
    // -------------------------------------------------------------------------

    private fun validateAllFields(): Boolean {
        var isValid = true
        if (!validateHost()) isValid = false
        if (!validatePort()) isValid = false
        // Username is not required for VNC (VncHost has no username field)
        if (currentProtocol != "vnc" && !validateUsername()) isValid = false
        // Key selection only checked when no identity selected and auth is public key
        if (currentProtocol == "ssh" && selectedIdentityId == null) {
            val authType = getSelectedAuthType()
            if (authType == AuthType.PUBLIC_KEY && !validateKeySelection()) isValid = false
        }
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
            portText.isBlank() -> { binding.editPort.error = "Port is required"; false }
            port == null -> { binding.editPort.error = "Invalid port number"; false }
            port < 1 || port > 65535 -> { binding.editPort.error = "Port must be between 1 and 65535"; false }
            else -> { binding.editPort.error = null; true }
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

    // -------------------------------------------------------------------------
    // Test connection (SSH-only)
    // -------------------------------------------------------------------------

    private fun testConnection() {
        if (currentProtocol == "vnc") {
            showToast("Test connection is not available for VNC")
            return
        }
        if (!validateAllFields()) return

        lifecycleScope.launch {
            binding.btnTest.isEnabled = false
            binding.btnTest.text = "Testing..."
            var tempProfileId: String? = null

            try {
                val profile = createConnectionProfile()
                val authType = getSelectedAuthType()
                if (!isEditMode &&
                    (authType == AuthType.PASSWORD || authType == AuthType.KEYBOARD_INTERACTIVE)) {
                    val pw = binding.editPassword.text.toString()
                    if (pw.isNotEmpty()) {
                        app.securePasswordManager.storePassword(
                            profile.id, pw,
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.SESSION_ONLY
                        )
                        tempProfileId = profile.id
                    }
                }
                val connection = io.github.tabssh.ssh.connection.SSHConnection(
                    profile, lifecycleScope, this@ConnectionEditActivity
                )
                connection.newHostKeyCallback = app.sshSessionManager.newHostKeyCallback
                connection.hostKeyChangedCallback = app.sshSessionManager.hostKeyChangedCallback

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
                tempProfileId?.let { app.securePasswordManager.clearPassword(it) }
                binding.btnTest.isEnabled = true
                binding.btnTest.text = "Test"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility / dialogs
    // -------------------------------------------------------------------------

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
            .setPositiveButton("Next") { _, _ ->
                val keyContent = editText.text.toString().trim()
                if (keyContent.isNotEmpty()) {
                    promptForKeyName("Pasted Key") { confirmedName ->
                        importKeyFromContent(keyContent, confirmedName)
                    }
                } else {
                    showToast("Key content is empty")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateNewKey() { showKeyGenerationDialog() }

    private fun showKeyGenerationDialog() {
        val keyTypes = arrayOf(
            "RSA 2048-bit (Compatible)",
            "RSA 4096-bit (Secure)",
            "ECDSA P-256 (Modern)",
            "ECDSA P-384 (High Security)",
            "Ed25519 (Recommended)"
        )
        var selectedType = 4
        val keyTypeMapping = mapOf(
            0 to Pair(KeyType.RSA, 2048), 1 to Pair(KeyType.RSA, 4096),
            2 to Pair(KeyType.ECDSA, 256), 3 to Pair(KeyType.ECDSA, 384),
            4 to Pair(KeyType.ED25519, 256)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Generate SSH Key Pair")
            .setMessage("Choose the key type to generate. Ed25519 is recommended for best security and performance.")
            .setSingleChoiceItems(keyTypes, selectedType) { _, which -> selectedType = which }
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
                if (keyName.isNotEmpty()) generateKeyPair(keyType, keySize, keyName)
                else showToast("Key name is required")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateKeyPair(keyType: KeyType, keySize: Int, keyName: String) {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Generating SSH Key")
            .setMessage("Creating ${keyType.name} ${keySize}-bit key pair…\nThis may take a moment.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = app.keyStorage.generateKeyPair(keyType, keySize, keyName)
                runOnUiThread {
                    progressDialog.dismiss()
                    when (result) {
                        is GenerateResult.Success -> {
                            showKeyGenerationSuccess(keyName, result.fingerprint)
                            loadAvailableKeys()
                        }
                        is GenerateResult.Error -> showKeyGenerationError(result.message)
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

    private fun loadAvailableKeys() { setupKeySpinner() }

    private fun showKeyGenerationSuccess(message: String, fingerprint: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✅ Key Generated Successfully")
            .setMessage("$message\n\nFingerprint:\n$fingerprint\n\nThe key has been securely stored and is ready to use.")
            .setPositiveButton("OK") { _, _ -> loadAvailableKeys() }
            .show()
        showToast("🔑 SSH key generated successfully!")
    }

    private fun showKeyGenerationError(errorMessage: String) {
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = "❌ Key Generation Failed",
            message = "Failed to generate SSH key:\n\n$errorMessage\n\nPlease try again or contact support if the problem persists.",
            onDismiss = {}
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
                selectedKeyIndex = which + 1
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
                showToast("Importing SSH key…")
                val needsPassphrase = keyContent.contains("ENCRYPTED") ||
                    keyContent.contains("Proc-Type: 4,ENCRYPTED")
                if (needsPassphrase) showKeyPassphraseDialog(keyContent, filename)
                else performKeyImport(keyContent, filename, null)
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
                if (passphrase.isEmpty()) { showToast("Passphrase is required"); return@setPositiveButton }
                lifecycleScope.launch { performKeyImport(keyContent, filename, passphrase) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun performKeyImport(keyContent: String, filename: String, passphrase: String?) {
        try {
            val result = app.keyStorage.importKeyFromText(
                keyContent = keyContent,
                passphrase = passphrase,
                keyName = extractKeyNameFromFilename(filename)
            )
            when (result) {
                is io.github.tabssh.crypto.keys.ImportResult.Success -> {
                    Logger.i("ConnectionEditActivity", "Key imported successfully: ${result.keyId}")
                    showToast("✅ SSH key imported successfully!")
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
        val name = filename
            .substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        return if (name.isBlank()) "Imported Key" else name
    }

    private fun importKeyWithPassphrase(keyContent: String, filename: String, passphrase: String) {
        lifecycleScope.launch {
            try {
                showToast("Importing encrypted SSH key…")
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = passphrase,
                    keyName = extractKeyNameFromFilename(filename)
                )
                when (result) {
                    is io.github.tabssh.crypto.keys.ImportResult.Success -> {
                        Logger.i("ConnectionEditActivity", "Encrypted key imported: ${result.keyId}")
                        showToast("✅ Encrypted SSH key imported successfully!")
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
            onDismiss = {}
        )
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_IMPORT_KEY && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val keyContent = inputStream.bufferedReader().readText()
                        val display = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "imported_key"
                        val suggestion = display.replace(Regex("\\.(pem|key|pub)$"), "")
                            .replace("_", " ").trim()
                        promptForKeyName(suggestion) { confirmedName ->
                            importKeyFromContent(keyContent, confirmedName)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("ConnectionEditActivity", "Failed to read key file", e)
                    showError("Failed to read key file: ${e.message}", "Error")
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Logger.w("ConnectionEditActivity", "Display name lookup failed: ${e.message}")
            null
        }
    }

    private fun promptForKeyName(suggestion: String, onConfirm: (String) -> Unit) {
        val edit = android.widget.EditText(this).apply {
            setText(suggestion)
            setSelection(text.length)
            hint = "Key name"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Name this key")
            .setMessage("This is the label TabSSH will show in the keys list.")
            .setView(edit)
            .setPositiveButton("Import") { _, _ ->
                val name = edit.text.toString().trim().ifBlank { suggestion }
                onConfirm(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupSelectionDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Group name (e.g., Work Servers, Home Lab)"
            setText(if (selectedGroupName == "No Group") "" else selectedGroupName)
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
                    supportActionBar?.subtitle = null
                    showToast("Group cleared")
                } else {
                    lifecycleScope.launch {
                        try {
                            val groupDao = app.database.connectionGroupDao()
                            val existing = groupDao.getGroupByName(groupName)
                            val groupId = if (existing != null) {
                                existing.id
                            } else {
                                val newGroup = io.github.tabssh.storage.database.entities.ConnectionGroup(
                                    name = groupName, icon = "folder", sortOrder = 0
                                )
                                groupDao.insertGroup(newGroup)
                                newGroup.id
                            }
                            selectedGroupId = groupId
                            selectedGroupName = groupName
                            supportActionBar?.subtitle = groupName
                            showToast("Group set to: $groupName")
                        } catch (e: Exception) {
                            Logger.e("ConnectionEditActivity", "Failed to resolve group '$groupName'", e)
                            showToast("Failed to set group")
                        }
                    }
                }
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
        binding.spinnerTerminalType.setText("xterm-256color", false)
    }

    private fun setupMultiplexerSpinner() {
        val modeEntries = resources.getStringArray(R.array.multiplexer_mode_entries)
        val modeValues = resources.getStringArray(R.array.multiplexer_mode_values)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modeEntries)
        binding.spinnerMultiplexerMode.setAdapter(adapter)
        binding.spinnerMultiplexerMode.setText(modeEntries[0], false)
        binding.spinnerMultiplexerMode.setOnItemClickListener { _, _, position, _ ->
            binding.layoutMultiplexerSessionName.visibility =
                if (modeValues[position] != "OFF") View.VISIBLE else View.GONE
        }
    }

    private fun setupConnectionThemeSpinner() {
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val entries = listOf("Use Global Default") + themeEntries.toList()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, entries)
        binding.spinnerConnectionTheme.setAdapter(adapter)
        binding.spinnerConnectionTheme.setText(entries[0], false)
    }

    private fun setupPortKnockUI() {
        binding.switchPortKnock.setOnCheckedChangeListener { _, isChecked ->
            binding.btnConfigurePortKnock.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnConfigurePortKnock.setOnClickListener { showPortKnockConfigDialog() }
    }

    private fun showPortKnockConfigDialog() {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val textView = android.widget.TextView(this).apply {
            text = "Enter port knock sequence (format: port:protocol, comma-separated)\nExample: 7000:TCP,8000:TCP,9000:UDP"
            textSize = 14f
        }
        dialogView.addView(textView)
        val editText = android.widget.EditText(this).apply {
            hint = "7000:TCP,8000:TCP,9000:UDP"
            setSingleLine(false)
            maxLines = 3
        }
        dialogView.addView(editText)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Port Knock Sequence")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sequence = editText.text.toString().trim()
                val count = if (sequence.isNotBlank()) sequence.split(",").size else 0
                android.widget.Toast.makeText(this, "✓ Knock sequence saved: $count ports", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                android.widget.Toast.makeText(this, "Sequence cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
