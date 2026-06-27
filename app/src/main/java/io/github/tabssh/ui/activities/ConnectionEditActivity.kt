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
import io.github.tabssh.crypto.storage.SecurePasswordManager
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

    /**
     * A preset entry in the mosh server command dropdown.
     * `command` is the actual string passed to mosh-server; `description` is
     * the human-readable label shown in the dropdown. For the "Custom…" sentinel
     * both fields are empty — the UI shows extra text inputs instead.
     */
    data class MoshPreset(val description: String, val command: String) {
        val isCustom: Boolean get() = description == "Custom…"
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_IS_EDIT_MODE = "is_edit_mode"
        /** When present, load + save as [VncHost] regardless of protocol spinner. */
        const val EXTRA_VNC_HOST_ID = "vnc_host_id"
        private const val REQUEST_CODE_IMPORT_KEY = 1001

        val MOSH_PRESETS = listOf(
            MoshPreset("Default", "mosh-server new -l LANG=en_US.UTF-8"),
            MoshPreset("Port range 60001–60050", "mosh-server new -l LANG=en_US.UTF-8 -p 60001:60050"),
            MoshPreset("Single port 61000", "mosh-server new -l LANG=en_US.UTF-8 -p 61000"),
            MoshPreset("IPv4 only", "mosh-server new -l LANG=en_US.UTF-8 -4"),
            MoshPreset("IPv6 only", "mosh-server new -l LANG=en_US.UTF-8 -6"),
            MoshPreset("Full locale", "mosh-server new -l LANG=en_US.UTF-8 -l LC_ALL=en_US.UTF-8"),
            MoshPreset("Custom path (/usr/local/bin)", "/usr/local/bin/mosh-server new -l LANG=en_US.UTF-8"),
            MoshPreset("Custom…", ""),
        )

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

    // Protocol state — "ssh" | "vnc" | "telnet".
    // Initialized to a sentinel so the first updateProtocolUI() call always runs
    // its branch (loadSshIdentities / autoSetPort) instead of short-circuiting
    // because currentProtocol already matched the new value.
    private var currentProtocol: String = ""

    // UX-08: set to true while populateFields/populateVncFields are running so the
    // async Spinner.setSelection() → onItemSelected → updateProtocolUI() callback
    // does not reset selectedIdentityId that populateFields just restored.
    private var isPopulatingFields = false

    private var availableKeys: List<StoredKey> = emptyList()
    private var selectedKeyIndex: Int = -1
    private var pendingRestoreKeyId: String? = null
    private var pendingRestoreProxyKeyId: String? = null

    private var selectedGroupId: String? = null
    private var selectedGroupName: String = "No Group"

    // SSH identities (Identity table)
    private var availableIdentities: List<io.github.tabssh.storage.database.entities.Identity> = emptyList()
    private var selectedIdentityId: String? = null

    // VNC identities (VncIdentity table)
    private var availableVncIdentities: List<VncIdentity> = emptyList()
    private var selectedVncIdentityId: String? = null

    // Cached per-host VNC password loaded by populateVncFields(); restored into
    // editVncPassword by restoreVncIdentitySpinner() when the spinner falls back
    // to "No Identity" so the user does not lose their previously saved password.
    private var loadedVncPassword: String? = null

    /** Wave 3.1 — current color tag in the editor (ARGB int; 0 = none). */
    private var currentColorTag: Int = 0

    // Port-knock state held in memory while the dialog is open; written to the
    // profile on save. Format: "port:PROTO,port:PROTO,…" (e.g. "7000:TCP,8000:UDP").
    private var pendingKnockSequence: String? = null

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
        // Wire spinner adapters + listeners synchronously with a placeholder list
        // BEFORE any async load can race the user (UX-01, rule 5). The async
        // loaders below replace the adapter contents once the DB load returns.
        bootstrapIdentitySpinner()
        bootstrapKeySpinner()
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
        setupMoshCommandDropdown()
        // Protocol spinner wired last — it calls updateProtocolUI() which
        // triggers identity list loading and card visibility.
        setupProtocolSpinner()
        setupUnsavedChangesGuard()

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
                val defaultUser = app.preferencesManager.getDefaultUsername()
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
                    // UX-08: clear the population guard so subsequent user-initiated
                    // protocol switches are not mistakenly treated as programmatic ones.
                    isPopulatingFields = false
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
        val previousProtocol = currentProtocol
        currentProtocol = proto

        // Reset identity / key selections when switching protocols so a previously
        // chosen SSH identity does not leak into a Telnet save (bug-06). The new
        // protocol's loader will repopulate the spinner and restore state if any.
        // UX-08: skip reset during populateFields/populateVncFields — those functions
        // restore the correct IDs immediately after setSelection(); resetting here
        // (from the async Spinner callback) would clobber their work.
        if (previousProtocol.isNotEmpty() && !isPopulatingFields) {
            selectedIdentityId = null
            selectedVncIdentityId = null
            selectedKeyIndex = -1
        }

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
                // Per-host password visible when no identity selected (default state)
                binding.layoutVncPassword.visibility = View.VISIBLE
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
                // Hide identity picker and VNC password — Telnet uses inline user/pass only
                binding.layoutIdentityRow.visibility = View.GONE
                binding.layoutVncPassword.visibility = View.GONE
                binding.editVncPassword.text?.clear()
                autoSetPort("23", setOf("22", "5900"))
            }
            else -> { // "ssh"
                // bug-22: ensure auth card is visible on protocol switch back to SSH;
                // selectedIdentityId was just reset above, so user must enter creds.
                if (selectedIdentityId == null) {
                    binding.cardAuthentication.visibility = View.VISIBLE
                }
                binding.cardAdvancedSettings.visibility = View.VISIBLE
                binding.cardNotificationsSection.visibility = View.VISIBLE
                binding.cardMultiplexer.visibility = View.VISIBLE
                binding.cardAppearance.visibility = View.VISIBLE
                binding.cardProxy.visibility = View.VISIBLE
                binding.layoutUsernameInput.visibility = View.VISIBLE
                binding.layoutIdentityRow.visibility = View.VISIBLE
                binding.layoutVncPassword.visibility = View.GONE
                binding.editVncPassword.text?.clear()
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

    /**
     * Wire the identity spinner with a synchronous placeholder adapter and
     * click listener so taps work immediately, before the DB-backed
     * [loadSshIdentities] / [loadVncIdentities] coroutines resume on the
     * main thread (UX-01).
     */
    private fun bootstrapIdentitySpinner() {
        val placeholder = listOf("No Identity")
        binding.spinnerIdentity.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, placeholder)
        )
        // UX-10: AutoCompleteTextView.setText() does not fire onItemClickListener.
        // When code calls setText() programmatically (e.g. restoreSshIdentitySpinner),
        // selectedIdentityId is already set correctly and the listener is not needed.
        // But if the user manually types into the field without picking from the dropdown
        // the listener also won't fire, leaving selectedIdentityId stale. Re-sync on
        // focus loss so the displayed text always matches the saved ID.
        binding.spinnerIdentity.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val typed = binding.spinnerIdentity.text.toString()
                if (currentProtocol == "vnc") {
                    val match = availableVncIdentities.firstOrNull { it.name == typed }
                    selectedVncIdentityId = match?.id
                    selectedIdentityId = null
                } else {
                    val match = availableIdentities.firstOrNull { it.name == typed }
                    selectedIdentityId = match?.id
                    selectedVncIdentityId = null
                }
            }
        }
        binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
            // Resolve current identity list (SSH or VNC) at click time so we
            // always operate on the latest loaded data.
            val items = listOf("No Identity") + when (currentProtocol) {
                "vnc" -> availableVncIdentities.map { it.name }
                else  -> availableIdentities.map { it.name }
            }
            if (position >= items.size) return@setOnItemClickListener
            binding.spinnerIdentity.setText(items[position], false)
            if (currentProtocol == "vnc") {
                selectedVncIdentityId = if (position > 0) availableVncIdentities[position - 1].id else null
                selectedIdentityId = null
                if (selectedVncIdentityId == null) {
                    binding.layoutVncPassword.visibility = View.VISIBLE
                } else {
                    binding.layoutVncPassword.visibility = View.GONE
                    binding.editVncPassword.text?.clear()
                }
            } else {
                if (position > 0) {
                    val identity = availableIdentities[position - 1]
                    selectedIdentityId = identity.id
                    selectedVncIdentityId = null
                    binding.editUsername.setText(identity.username)
                    // PUBLIC_KEY identities carry their own keyId — hide auth card.
                    // PASSWORD/KEYBOARD_INTERACTIVE identities still require the
                    // user to enter a per-connection password, so keep auth visible.
                    if (identity.authType == AuthType.PUBLIC_KEY) {
                        binding.cardAuthentication.visibility = View.GONE
                    } else {
                        binding.cardAuthentication.visibility = View.VISIBLE
                        val authTypes = AuthType.getAvailableTypes()
                        val idx = authTypes.indexOf(identity.authType)
                        if (idx >= 0) {
                            binding.spinnerAuthType.setText(authTypes[idx].displayName, false)
                            updateAuthTypeUI(identity.authType)
                        }
                    }
                } else {
                    selectedIdentityId = null
                    selectedVncIdentityId = null
                    binding.cardAuthentication.visibility = View.VISIBLE
                    val authTypes = AuthType.getAvailableTypes()
                    val pwIdx = authTypes.indexOf(AuthType.PASSWORD)
                    if (pwIdx >= 0) {
                        binding.spinnerAuthType.setText(authTypes[pwIdx].displayName, false)
                        updateAuthTypeUI(AuthType.PASSWORD)
                    }
                }
            }
            hasUnsavedChanges = true
        }
    }

    /**
     * Wire the SSH key + proxy SSH key spinners with placeholder adapters and
     * click listeners synchronously so taps are responsive before the async
     * [setupKeySpinner] coroutine completes (UX-01).
     */
    private fun bootstrapKeySpinner() {
        val placeholder = listOf("Select SSH Key...")
        binding.spinnerSshKey.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, placeholder)
        )
        binding.spinnerSshKey.setOnItemClickListener { _, _, position, _ ->
            val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
            if (position < keyNames.size) {
                selectedKeyIndex = position
                binding.spinnerSshKey.setText(keyNames[position], false)
            }
        }
        binding.spinnerProxySshKey.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, placeholder)
        )
        // Proxy key click listener — reads availableKeys at click time so it
        // continues to work after setupKeySpinner() swaps the adapter. Position 0
        // is the "Select SSH Key..." sentinel and clears any prior selection.
        binding.spinnerProxySshKey.setOnItemClickListener { _, _, position, _ ->
            val names = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
            if (position >= names.size) return@setOnItemClickListener
            if (position == 0) {
                binding.spinnerProxySshKey.setText("", false)
                pendingRestoreProxyKeyId = null
            } else {
                binding.spinnerProxySshKey.setText(names[position], false)
            }
            hasUnsavedChanges = true
        }
    }

    /** Populate the identity spinner with SSH [Identity] rows. */
    private fun loadSshIdentities() {
        lifecycleScope.launch {
            try {
                availableIdentities = withContext(Dispatchers.IO) {
                    app.database.identityDao().getAllIdentitiesList()
                }
                availableVncIdentities = emptyList()

                val items = listOf("No Identity") + availableIdentities.map { it.name }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    items
                )
                // Listener was set synchronously by bootstrapIdentitySpinner() and reads
                // availableIdentities at click time — do not re-set it here with a stale
                // captured `items` list that would break if identities change mid-session.
                binding.spinnerIdentity.setAdapter(adapter)
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
                availableVncIdentities = withContext(Dispatchers.IO) {
                    app.database.vncIdentityDao().getAllIdentitiesList()
                }
                availableIdentities = emptyList()

                val items = listOf("No Identity") + availableVncIdentities.map { it.name }
                val adapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    items
                )
                // Listener was set synchronously by bootstrapIdentitySpinner() and reads
                // availableVncIdentities at click time — do not re-set it here with a stale
                // captured `items` list that would break if identities change mid-session.
                binding.spinnerIdentity.setAdapter(adapter)
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
                val identity = availableIdentities[idx]
                binding.spinnerIdentity.setText(identity.name, false)
                // Only PUBLIC_KEY identities self-contain credentials; password-based
                // identities still need a per-connection password from the user.
                binding.cardAuthentication.visibility =
                    if (identity.authType == AuthType.PUBLIC_KEY) View.GONE else View.VISIBLE
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
                binding.layoutVncPassword.visibility = View.GONE
                return
            }
        }
        binding.spinnerIdentity.setText("No Identity", false)
        // No identity found — ensure the per-host password field is visible so the
        // user can enter a VNC password without needing an identity record.
        binding.layoutVncPassword.visibility = View.VISIBLE
        // Restore any previously loaded per-host password (bug-04) so falling back
        // to "No Identity" does not silently wipe the saved credential.
        loadedVncPassword?.let { pw ->
            if (binding.editVncPassword.text.isNullOrEmpty()) {
                binding.editVncPassword.setText(pw)
            }
        }
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
        // If the user types directly into the field instead of picking from the dropdown,
        // onItemClickListener never fires and the field layout (password vs key) stays stale.
        // Re-sync on focus loss so the visible fields always match the text.
        binding.spinnerAuthType.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateAuthTypeUI(getSelectedAuthType())
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
                    android.R.layout.simple_dropdown_item_1line,
                    keyNames
                )
                // Listener was set synchronously by bootstrapKeySpinner() and reads
                // availableKeys at click time — do not override it here with a stale
                // captured `keyNames` list.
                binding.spinnerSshKey.setAdapter(adapter)
                pendingRestoreKeyId?.let { keyId ->
                    pendingRestoreKeyId = null
                    val keyIndex = availableKeys.indexOfFirst { it.keyId == keyId }
                    if (keyIndex >= 0) {
                        selectedKeyIndex = keyIndex + 1
                        binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                    }
                }

                // Rebuild proxy SSH key spinner now that availableKeys is populated,
                // and restore any pending proxy key selection set by populateFields()
                // before this coroutine completed.
                val proxyKeyAdapter = ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    keyNames
                )
                binding.spinnerProxySshKey.setAdapter(proxyKeyAdapter)
                pendingRestoreProxyKeyId?.let { proxyKeyId ->
                    pendingRestoreProxyKeyId = null
                    val proxyKeyIndex = availableKeys.indexOfFirst { it.keyId == proxyKeyId }
                    if (proxyKeyIndex >= 0) {
                        binding.spinnerProxySshKey.setText(keyNames[proxyKeyIndex + 1], false)
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
        // Re-sync proxy auth UI when the user types directly instead of picking
        // from the dropdown (bug-08) — mirrors the main spinnerAuthType handler.
        binding.spinnerProxyAuthType.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateProxyAuthTypeUI(getSelectedProxyAuthType())
        }

        // Proxy SSH key spinner adapter is built inside setupKeySpinner() once
        // availableKeys is loaded; click listener is set in bootstrapKeySpinner()
        // and reads availableKeys at click time so it survives adapter swaps.
    }

    private fun updateProxyTypeUI(proxyType: String) {
        when (proxyType) {
            "None" -> {
                binding.layoutProxyConfig.visibility = View.GONE
                binding.layoutJumpHostAuth.visibility = View.GONE
                // Clear stale proxy data so it does not get persisted on save (bug-09).
                binding.editProxyHost.text?.clear()
                binding.editProxyPort.text?.clear()
                binding.editProxyUsername.text?.clear()
            }
            "SSH Jump Host" -> {
                binding.layoutProxyConfig.visibility = View.VISIBLE
                binding.layoutJumpHostAuth.visibility = View.VISIBLE
                binding.editProxyPort.setText("22")
                // Re-sync the proxy key picker visibility from the current proxy auth
                // type selection. Without this, toggling proxy type away then back to
                // SSH Jump Host leaves layoutProxyKey hidden even when PUBLIC_KEY is shown.
                val authTypes = AuthType.getAvailableTypes()
                val selectedText = binding.spinnerProxyAuthType.text.toString()
                val currentProxyAuth = authTypes.find { it.displayName == selectedText } ?: AuthType.PASSWORD
                updateProxyAuthTypeUI(currentProxyAuth)
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
            val groups = withContext(Dispatchers.IO) {
                app.database.connectionGroupDao().getAllGroups().first()
            }.filter { it.groupType.isEmpty() } // only user groups in the spinner
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
                // Reflect the choice in the action-bar subtitle (bug-23): clear it
                // when "No Group" is picked; otherwise show the group name.
                supportActionBar?.subtitle = if (position == 0) null else groupsList[position]
                hasUnsavedChanges = true
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

    // Set to true the first time the user types in or toggles any of the
    // primary form fields after the activity has finished populating. Used by
    // [setupUnsavedChangesGuard] to decide whether the back button needs to
    // confirm a discard. Reset to false at the end of [populateFields] and
    // after a successful save.
    private var hasUnsavedChanges: Boolean = false

    private fun setupUnsavedChangesGuard() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { hasUnsavedChanges = true }
        }
        binding.editName.addTextChangedListener(watcher)
        binding.editHost.addTextChangedListener(watcher)
        binding.editPort.addTextChangedListener(watcher)
        binding.editUsername.addTextChangedListener(watcher)
        binding.editPassword.addTextChangedListener(watcher)
        binding.editConnectTimeout.addTextChangedListener(watcher)
        binding.editReadTimeout.addTextChangedListener(watcher)
        binding.editServerAliveInterval.addTextChangedListener(watcher)
        binding.editPortKnockDelay.addTextChangedListener(watcher)
        // bug-15: track changes on proxy / mosh / remote-command custom fields
        // so the unsaved-changes guard fires for every editable field on the form.
        binding.editProxyHost.addTextChangedListener(watcher)
        binding.editProxyPort.addTextChangedListener(watcher)
        binding.editProxyUsername.addTextChangedListener(watcher)
        binding.editMoshCustomCommand.addTextChangedListener(watcher)
        binding.editMoshCustomDesc.addTextChangedListener(watcher)
        binding.editRemoteCommandCustom.addTextChangedListener(watcher)
        binding.spinnerEncoding.addTextChangedListener(watcher)
        binding.switchKeepAlive.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!hasUnsavedChanges) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                androidx.appcompat.app.AlertDialog.Builder(this@ConnectionEditActivity)
                    .setTitle("Discard changes?")
                    .setMessage("You have unsaved changes. Leave without saving?")
                    .setPositiveButton("Discard") { _, _ ->
                        isEnabled = false
                        finish()
                    }
                    .setNegativeButton("Keep editing", null)
                    .show()
            }
        })
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConnection() }
        binding.btnCancel.setOnClickListener {
            if (!hasUnsavedChanges) {
                finish()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Discard changes?")
                    .setMessage("You have unsaved changes. Leave without saving?")
                    .setPositiveButton("Discard") { _, _ -> finish() }
                    .setNegativeButton("Keep editing", null)
                    .show()
            }
        }
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
                existingProfile = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(connectionId)
                }
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
        // turn loads the right identity list and resets visibility. Guard with
        // isPopulatingFields so the async callback skips the identity reset
        // that would clobber the selectedIdentityId we restore below (UX-08).
        isPopulatingFields = true
        binding.spinnerProtocol.setSelection(protocolIndex)

        // Set identity before restoring, so restoreSshIdentitySpinner() can use it.
        selectedIdentityId = profile.identityId
        // Synchronously fetch SSH identities BEFORE calling restore — the prior
        // version ran restore immediately while loadSshIdentities() was still
        // pending on Dispatchers.IO, causing the spinner to silently show
        // "No Identity" for every edited connection.
        availableIdentities = withContext(Dispatchers.IO) {
            app.database.identityDao().getAllIdentitiesList()
        }
        // Rebuild the adapter on the main thread so the visible text below
        // matches the selection state.
        run {
            val items = listOf("No Identity") + availableIdentities.map { it.name }
            val adapter = ArrayAdapter(
                this@ConnectionEditActivity,
                android.R.layout.simple_dropdown_item_1line,
                items
            )
            binding.spinnerIdentity.setAdapter(adapter)
        }
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
        binding.switchKeepAlive.isChecked = profile.keepAlive
        binding.spinnerEncoding.setText(profile.encoding.ifBlank { "UTF-8" }, false)
        binding.editConnectTimeout.setText(profile.connectTimeout.toString())
        binding.editReadTimeout.setText(profile.readTimeout.toString())
        binding.editServerAliveInterval.setText((profile.serverAliveInterval ?: 0).toString())
        binding.editPortKnockDelay.setText(profile.portKnockDelayMs.toString())
        binding.switchX11Forwarding.isChecked = profile.x11Forwarding
        binding.spinnerMoshMode.setText(moshModeLabel(profile.moshMode), false)
        restoreMoshCommandDropdown(profile)

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
        val resolvedGroup = selectedGroupId?.let { gid ->
            withContext(Dispatchers.IO) { app.database.connectionGroupDao().getGroupById(gid) }
        }
        if (resolvedGroup != null) {
            selectedGroupName = resolvedGroup.name
            binding.spinnerGroup.setText(resolvedGroup.name, false)
            supportActionBar?.subtitle = resolvedGroup.name
        } else {
            selectedGroupId = null
            selectedGroupName = "No Group"
            binding.spinnerGroup.setText("No Group", false)
            supportActionBar?.subtitle = null
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
                        } else {
                            // availableKeys not yet loaded — defer to setupKeySpinner() completion
                            pendingRestoreProxyKeyId = profile.proxyKeyId
                        }
                    }
                }
            }
        }

        // Port knock — restore persisted sequence into local state and switch.
        pendingKnockSequence = profile.portKnockSequence
        binding.switchPortKnock.isChecked = profile.portKnockEnabled == true
        binding.btnConfigurePortKnock.visibility =
            if (profile.portKnockEnabled == true) View.VISIBLE else View.GONE

        // populateFields fires TextWatchers as it fills the form — clear the
        // dirty flag now so the back-press guard does not nag the user for
        // changes they didn't actually make.
        hasUnsavedChanges = false
    }

    // -------------------------------------------------------------------------
    // Load — VncHost
    // -------------------------------------------------------------------------

    private fun loadVncHost(vncHostId: String) {
        lifecycleScope.launch {
            try {
                val vncHost = withContext(Dispatchers.IO) {
                    app.database.vncHostDao().getById(vncHostId)
                }
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

    private suspend fun populateVncFields(vncHost: VncHost) {
        binding.editName.setText(vncHost.name)
        binding.editHost.setText(vncHost.host)
        binding.editPort.setText(vncHost.effectivePort.toString())
        // Set protocol spinner to VNC (index 1) — triggers updateProtocolUI("vnc")
        // which loads VNC identities and hides SSH-only cards. Guard flag prevents
        // the async callback from resetting selectedVncIdentityId (UX-08).
        isPopulatingFields = true
        binding.spinnerProtocol.setSelection(1)
        // Set identity before restoring so restoreVncIdentitySpinner() can use it.
        selectedVncIdentityId = vncHost.identityId
        // Synchronously fetch VNC identities before calling restore — same approach
        // as populateFields() for SSH. Without this, restoreVncIdentitySpinner() runs
        // while the async loadVncIdentities() coroutine is still in-flight and
        // availableVncIdentities is empty, causing the spinner to always show
        // "No Identity" and layoutVncPassword to stay hidden even when no identity exists.
        availableVncIdentities = withContext(Dispatchers.IO) {
            app.database.vncIdentityDao().getAllIdentitiesList()
        }
        run {
            val items = listOf("No Identity") + availableVncIdentities.map { it.name }
            binding.spinnerIdentity.setAdapter(
                ArrayAdapter(this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line, items)
            )
        }
        restoreVncIdentitySpinner()
        selectedGroupId = vncHost.groupId
        // bug-14: nested lifecycleScope.launch inside a suspend fun creates fire-and-forget
        // coroutines that race with hasUnsavedChanges = false at the end of each branch.
        // Use inline withContext(IO) so the whole function is sequentially ordered.
        val resolvedGroup = selectedGroupId?.let { gid ->
            withContext(Dispatchers.IO) { app.database.connectionGroupDao().getGroupById(gid) }
        }
        if (resolvedGroup != null) {
            selectedGroupName = resolvedGroup.name
            binding.spinnerGroup.setText(resolvedGroup.name, false)
            supportActionBar?.subtitle = resolvedGroup.name
        } else {
            selectedGroupId = null
            selectedGroupName = "No Group"
            binding.spinnerGroup.setText("No Group", false)
            supportActionBar?.subtitle = null
        }
        currentColorTag = vncHost.colorTag
        renderColorTagPreview()
        // Pre-populate per-host password when no identity is linked
        if (vncHost.identityId == null) {
            val stored = try {
                withContext(Dispatchers.IO) {
                    app.securePasswordManager.retrievePassword("vnc_host_${vncHost.id}")
                }
            } catch (e: Exception) {
                Logger.w("ConnectionEditActivity", "No stored VNC host password: ${e.message}")
                null
            }
            if (!stored.isNullOrEmpty()) {
                binding.editVncPassword.setText(stored)
                // Cache for restoreVncIdentitySpinner() fallback (bug-04).
                loadedVncPassword = stored
            }
        } else {
            binding.layoutVncPassword.visibility = View.GONE
        }
        // After all fields are populated, clear the dirty flag so the back-press
        // guard does not trigger on TextWatcher fires from setText (UX-13).
        hasUnsavedChanges = false
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
                // Defensive sync: if the identity spinner shows "No Identity" but
                // selectedVncIdentityId is still non-null (e.g. async restore beat the
                // synchronous fetch path), clear it so the DB column matches the UI.
                if (binding.spinnerIdentity.text.toString() == "No Identity") {
                    selectedVncIdentityId = null
                }
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

                // Persist or clear the per-host VNC password when no identity is linked
                if (selectedVncIdentityId == null) {
                    val vncPw = binding.editVncPassword.text.toString()
                    if (vncPw.isNotEmpty()) {
                        app.securePasswordManager.storePassword(
                            "vnc_host_$hostId",
                            vncPw,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    } else {
                        // User blanked the field — remove any previously stored password
                        try { app.securePasswordManager.clearPassword("vnc_host_$hostId") }
                        catch (_: Exception) { /* nothing stored, ignore */ }
                    }
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
                val excludeId = existingProfile?.id ?: ""
                val duplicate = withContext(Dispatchers.IO) {
                    app.database.connectionDao().findDuplicate(profile.host, profile.port, profile.username, excludeId)
                }
                if (duplicate != null && duplicate.id != existingProfile?.id) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ConnectionEditActivity)
                        .setTitle("Duplicate connection")
                        .setMessage("A connection to ${profile.username}@${profile.host}:${profile.port} already exists (\"${duplicate.name}\"). What would you like to do?")
                        .setPositiveButton("Save as New") { _, _ ->
                            // bug-16: profile.id still holds the existing ID when in edit mode;
                            // forceInsert bypasses the isEditMode branch in doSave().
                            lifecycleScope.launch {
                                doSave(profile.copy(id = java.util.UUID.randomUUID().toString()), forceInsert = true)
                            }
                        }
                        .setNeutralButton("Update Existing") { _, _ ->
                            lifecycleScope.launch { doSave(profile.copy(id = duplicate.id)) }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@launch
                }
                doSave(profile)
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to save connection", e)
                showError("Failed to save connection: ${e.message}", "Error")
            }
        }
    }

    private suspend fun doSave(profile: ConnectionProfile, forceInsert: Boolean = false) {
        try {
            if (isEditMode && existingProfile != null && !forceInsert) {
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
                } else if (!savePassword) {
                    // User explicitly unchecked "Save Password" — revoke any previously
                    // stored credential so it is not silently reused on the next connect.
                    try { app.securePasswordManager.clearPassword(profile.id) } catch (_: Exception) {}
                }
            }
            // bug-21: switching to PUBLIC_KEY leaves a stale password in SecurePasswordManager
            // that could be picked up by a future auth attempt as a fallback.
            if (authType == AuthType.PUBLIC_KEY) {
                try { app.securePasswordManager.clearPassword(profile.id) } catch (_: Exception) {}
            }

            // Save succeeded — clear the dirty flag so back/cancel don't
            // prompt for discard on the way out.
            hasUnsavedChanges = false
            setResult(RESULT_OK)
            finish()
        } catch (e: Exception) {
            Logger.e("ConnectionEditActivity", "Failed to save connection", e)
            showError("Failed to save connection: ${e.message}", "Error")
        }
    }

    private fun createConnectionProfile(): ConnectionProfile {
        val name = binding.editName.text.toString().trim()
        val host = binding.editHost.text.toString().trim()
        val port = binding.editPort.text.toString().toIntOrNull() ?: 22
        val username = binding.editUsername.text.toString().trim()

        // Defensive sync: if the displayed name doesn't match any available identity
        // (e.g. the click listener didn't fire for a programmatic setText), clear the
        // stale ID so the DB column matches what the UI shows (bug-18).
        val spinnerIdentityText = binding.spinnerIdentity.text.toString()
        if (availableIdentities.none { it.name == spinnerIdentityText }) {
            selectedIdentityId = null
        }

        val selectedIdentity = selectedIdentityId?.let { id -> availableIdentities.find { it.id == id } }

        val authType = if (selectedIdentity != null) {
            selectedIdentity.authType
        } else {
            getSelectedAuthType()
        }

        // Defensive sync: if the user typed directly into the SSH key AutoCompleteTextView
        // instead of picking from the dropdown, onItemClickListener never fired and
        // selectedKeyIndex is still -1. Try to resolve by matching the displayed text.
        if (selectedKeyIndex <= 0) {
            val typedText = binding.spinnerSshKey.text.toString()
            val match = availableKeys.indexOfFirst { it.getDisplayName() == typedText }
            if (match >= 0) selectedKeyIndex = match + 1
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
        val keepAlive = binding.switchKeepAlive.isChecked
        val encoding = binding.spinnerEncoding.text.toString().takeIf { it.isNotBlank() } ?: "UTF-8"
        val connectTimeout = binding.editConnectTimeout.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 15
        val readTimeout = binding.editReadTimeout.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 30
        val serverAliveInterval = binding.editServerAliveInterval.text.toString().toIntOrNull()?.coerceIn(0, 3600)?.let { if (it == 0) null else it }
        val x11Forwarding = binding.switchX11Forwarding.isChecked
        val moshMode = readMoshModeFromUi()
        val moshCommandOverride = readMoshCommandFromUi()
        // Persist the user-supplied description for Custom… mosh commands so it
        // round-trips into the editor on next open (bug-10).
        val moshDescOverride = readMoshDescFromUi()

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

        val knockEnabled = binding.switchPortKnock.isChecked
        val knockSequence = if (knockEnabled) pendingKnockSequence else null
        // bug-17: always read the delay field so a user-entered value is preserved even
        // when knock is currently disabled (the switch can be toggled back on later).
        val portKnockDelayMs = binding.editPortKnockDelay.text.toString().toIntOrNull()?.coerceIn(0, 10_000) ?: 100

        // Merge moshServerCommand (and optional desc) into the advancedSettings
        // JSON blob so it survives copy() without needing a new DB column.
        val mergedAdvancedSettings = mergeAdvancedSettings(
            existing = existingProfile?.advancedSettings,
            moshCommand = moshCommandOverride,
            moshDescription = moshDescOverride
        )

        return existingProfile?.copy(
            name = name, host = host, port = port, username = username,
            protocol = protocol, authType = authType.name, keyId = keyId,
            identityId = selectedIdentityId, terminalType = terminalType,
            compression = compression, keepAlive = keepAlive,
            x11Forwarding = x11Forwarding, moshMode = moshMode,
            multiplexerMode = multiplexerMode, multiplexerSessionName = multiplexerSessionName,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            proxyType = proxyType, proxyHost = proxyHost, proxyPort = proxyPort,
            proxyUsername = proxyUsername, proxyAuthType = proxyAuthType, proxyKeyId = proxyKeyId,
            colorTag = colorTag, notifSoundMode = notifSoundMode, notifVibrateMode = notifVibrateMode,
            portKnockEnabled = knockEnabled, portKnockSequence = knockSequence, portKnockDelayMs = portKnockDelayMs,
            encoding = encoding, connectTimeout = connectTimeout, readTimeout = readTimeout, serverAliveInterval = serverAliveInterval,
            advancedSettings = mergedAdvancedSettings
        ) ?: ConnectionProfile(
            name = name, host = host, port = port, username = username,
            protocol = protocol, authType = authType.name, keyId = keyId,
            identityId = selectedIdentityId, terminalType = terminalType,
            compression = compression, keepAlive = keepAlive,
            x11Forwarding = x11Forwarding, moshMode = moshMode,
            multiplexerMode = multiplexerMode, multiplexerSessionName = multiplexerSessionName,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            proxyType = proxyType, proxyHost = proxyHost, proxyPort = proxyPort,
            proxyUsername = proxyUsername, proxyAuthType = proxyAuthType, proxyKeyId = proxyKeyId,
            notifSoundMode = notifSoundMode, notifVibrateMode = notifVibrateMode,
            portKnockEnabled = knockEnabled, portKnockSequence = knockSequence, portKnockDelayMs = portKnockDelayMs,
            encoding = encoding, connectTimeout = connectTimeout, readTimeout = readTimeout, serverAliveInterval = serverAliveInterval,
            advancedSettings = mergedAdvancedSettings
        )
    }

    /**
     * Merge [moshCommand] into [existing] advancedSettings JSON.
     * Preserves all other keys already in the blob (IdentityFile, cloudSource, etc.).
     * Removes the key if [moshCommand] is null (user chose Default — no override needed).
     */
    private fun mergeAdvancedSettings(existing: String?, moshCommand: String?, moshDescription: String? = null): String? {
        val json = try {
            existing?.takeIf { it.isNotBlank() }?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
        } catch (_: Exception) { org.json.JSONObject() }

        if (moshCommand.isNullOrBlank()) {
            json.remove("moshServerCommand")
            json.remove("moshServerDescription")
        } else {
            json.put("moshServerCommand", moshCommand)
            if (moshDescription.isNullOrBlank()) {
                json.remove("moshServerDescription")
            } else {
                json.put("moshServerDescription", moshDescription)
            }
        }

        val result = json.toString()
        return if (result == "{}") null else result
    }

    /**
     * Read the user-supplied description that pairs with a Custom… mosh command.
     * Returns null when not in Custom mode or when the field is blank.
     */
    private fun readMoshDescFromUi(): String? {
        val selectedLabel = binding.spinnerMoshCommand.text.toString()
        val preset = MOSH_PRESETS.firstOrNull { it.description == selectedLabel }
        if (preset != null && !preset.isCustom) return null
        return binding.editMoshCustomDesc.text.toString().trim().takeIf { it.isNotBlank() }
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

    private fun getSelectedProxyAuthType(): AuthType {
        val authTypes = AuthType.getAvailableTypes()
        val selectedText = binding.spinnerProxyAuthType.text.toString()
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
        } else if (currentProtocol == "ssh" && selectedIdentityId != null) {
            // Identity is selected — if it claims PUBLIC_KEY auth but has no keyId,
            // the save would silently produce an unusable connection (bug-05).
            val identity = availableIdentities.find { it.id == selectedIdentityId }
            if (identity != null && identity.authType == AuthType.PUBLIC_KEY && identity.keyId.isNullOrBlank()) {
                showToast("Selected identity has no SSH key — pick a different identity")
                isValid = false
            }
        }
        // bug-24: port knock enabled but sequence is empty → connecting would send no
        // knock packets and the server's firewall would block the SSH connection.
        if (binding.switchPortKnock.isChecked && pendingKnockSequence.isNullOrBlank()) {
            showToast("Port knock is enabled but no sequence is configured — tap \"Configure Knock\"")
            isValid = false
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
            // UX-04: inline error on the SSH key picker EditText so the user sees
            // the problem in context, not just a toast. layout_ssh_key is a plain
            // LinearLayout (no .error API); MaterialAutoCompleteTextView inherits
            // from EditText and shows the error icon via its TextInputLayout wrapper.
            binding.spinnerSshKey.error = "Select an SSH key for public-key authentication"
            showToast("Please select an SSH key for public key authentication")
            false
        } else {
            binding.spinnerSshKey.error = null
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
            // Hoisted so the finally block can always tear down a half-
            // established session — connection.connect() can throw before
            // returning (network error, host-key callback rejection,
            // auth failure) and the previous version skipped disconnect()
            // on the exception path, leaking the JSch Session.
            var connection: io.github.tabssh.ssh.connection.SSHConnection? = null

            try {
                val profile = createConnectionProfile()
                val authType = getSelectedAuthType()
                // bug-19: in edit mode the profile may already have a persisted password that
                // differs from what is currently shown in the form. Always use the form value
                // for the test by storing it under a temporary ID and passing a profile copy
                // with that ID to SSHConnection — the original stored credential is untouched.
                val testProfile = if (authType == AuthType.PASSWORD || authType == AuthType.KEYBOARD_INTERACTIVE) {
                    val pw = binding.editPassword.text.toString()
                    if (pw.isNotEmpty()) {
                        val testId = "test_${java.util.UUID.randomUUID()}"
                        app.securePasswordManager.storePassword(
                            testId, pw,
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.SESSION_ONLY
                        )
                        tempProfileId = testId
                        profile.copy(id = testId)
                    } else profile
                } else profile
                connection = io.github.tabssh.ssh.connection.SSHConnection(
                    testProfile, lifecycleScope, this@ConnectionEditActivity
                )
                connection.newHostKeyCallback = app.sshSessionManager.newHostKeyCallback
                connection.hostKeyChangedCallback = app.sshSessionManager.hostKeyChangedCallback

                val success = connection.connect()

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
                try { connection?.disconnect() } catch (e: Exception) {
                    Logger.w("ConnectionEditActivity", "Test-connect disconnect failed", e)
                }
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
                    // bug-20: setupKeySpinner() is a fire-and-forget coroutine; calling it
                    // and then reading availableKeys immediately is a race. Load keys inline
                    // with withContext(IO) so availableKeys is up-to-date before auto-select.
                    availableKeys = withContext(Dispatchers.IO) { app.keyStorage.listStoredKeys() }
                    val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                    val adapter = ArrayAdapter(this@ConnectionEditActivity, android.R.layout.simple_dropdown_item_1line, keyNames)
                    binding.spinnerSshKey.setAdapter(adapter)
                    binding.spinnerProxySshKey.setAdapter(adapter)
                    val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                    if (importedKeyIndex >= 0) {
                        selectedKeyIndex = importedKeyIndex + 1
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
                        // bug-20: load keys inline so availableKeys is up-to-date before auto-select.
                        availableKeys = withContext(Dispatchers.IO) { app.keyStorage.listStoredKeys() }
                        val keyNames = listOf("Select SSH Key...") + availableKeys.map { it.getDisplayName() }
                        val adapter = ArrayAdapter(this@ConnectionEditActivity, android.R.layout.simple_dropdown_item_1line, keyNames)
                        binding.spinnerSshKey.setAdapter(adapter)
                        binding.spinnerProxySshKey.setAdapter(adapter)
                        val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                        if (importedKeyIndex >= 0) {
                            selectedKeyIndex = importedKeyIndex + 1
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
                            val existing = withContext(Dispatchers.IO) {
                                groupDao.getGroupByName(groupName)
                            }
                            val groupId = if (existing != null) {
                                existing.id
                            } else {
                                val newGroup = io.github.tabssh.storage.database.entities.ConnectionGroup(
                                    name = groupName, icon = "folder", sortOrder = 0
                                )
                                withContext(Dispatchers.IO) {
                                    groupDao.insertGroup(newGroup)
                                }
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

        val encodings = arrayOf("UTF-8", "ISO-8859-1", "US-ASCII", "UTF-16")
        val encodingAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, encodings)
        binding.spinnerEncoding.setAdapter(encodingAdapter)
        binding.spinnerEncoding.setText("UTF-8", false)
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
        // Re-sync session-name visibility when the user types directly instead of
        // picking from the dropdown (bug-13).
        binding.spinnerMultiplexerMode.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val typed = binding.spinnerMultiplexerMode.text.toString()
                val idx = modeEntries.indexOf(typed)
                val value = if (idx >= 0) modeValues[idx] else "OFF"
                binding.layoutMultiplexerSessionName.visibility =
                    if (value != "OFF") View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupConnectionThemeSpinner() {
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val entries = listOf("Use Global Default") + themeEntries.toList()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, entries)
        binding.spinnerConnectionTheme.setAdapter(adapter)
        binding.spinnerConnectionTheme.setText(entries[0], false)
    }

    // -------------------------------------------------------------------------
    // Mosh command dropdown
    // -------------------------------------------------------------------------

    /**
     * Populate the mosh command spinner with [MOSH_PRESETS] and wire visibility
     * to the "Use Mosh" toggle. When "Custom…" is chosen, show the plain command
     * and optional description fields.
     */
    private fun setupMoshCommandDropdown() {
        val labels = MOSH_PRESETS.map { it.description }
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line, labels
        )
        binding.spinnerMoshCommand.setAdapter(adapter)

        // Mode spinner: Off / Auto / On — drives command panel visibility.
        val moshModeLabels = arrayOf("Off", "Auto (default)", "On")
        binding.spinnerMoshMode.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, moshModeLabels)
        )
        binding.spinnerMoshMode.setText("Auto (default)", false)
        binding.spinnerMoshMode.setOnItemClickListener { _, _, position, _ ->
            val isActive = position > 0  // Auto or On
            binding.layoutMoshCommand.visibility = if (isActive) View.VISIBLE else View.GONE
            if (!isActive) {
                binding.layoutMoshCustomCommand.visibility = View.GONE
                binding.layoutMoshCustomDesc.visibility = View.GONE
            }
        }

        // Show custom inputs when "Custom…" is selected.
        binding.spinnerMoshCommand.setOnItemClickListener { _, _, position, _ ->
            val isCustom = MOSH_PRESETS.getOrNull(position)?.isCustom == true
            binding.layoutMoshCustomCommand.visibility = if (isCustom) View.VISIBLE else View.GONE
            binding.layoutMoshCustomDesc.visibility   = if (isCustom) View.VISIBLE else View.GONE
        }

        // Default selection: first preset (Default).
        binding.spinnerMoshCommand.setText(labels.first(), false)
    }

    /**
     * Populate the mosh dropdown from the saved [advancedSettings] JSON.
     * Matches against [MOSH_PRESETS] by command string; falls back to the
     * Custom option if no match is found.
     */
    private fun restoreMoshCommandDropdown(profile: ConnectionProfile) {
        binding.layoutMoshCommand.visibility =
            if (profile.moshMode != "off") View.VISIBLE else View.GONE

        val savedCmd = try {
            profile.advancedSettings?.let { org.json.JSONObject(it).optString("moshServerCommand") }
                .takeIf { !it.isNullOrBlank() }
        } catch (_: Exception) { null }

        if (savedCmd == null) {
            binding.spinnerMoshCommand.setText(MOSH_PRESETS.first().description, false)
            binding.layoutMoshCustomCommand.visibility = View.GONE
            binding.layoutMoshCustomDesc.visibility = View.GONE
            return
        }

        val match = MOSH_PRESETS.firstOrNull { !it.isCustom && it.command == savedCmd }
        if (match != null) {
            binding.spinnerMoshCommand.setText(match.description, false)
            binding.layoutMoshCustomCommand.visibility = View.GONE
            binding.layoutMoshCustomDesc.visibility = View.GONE
        } else {
            // Custom command not in the preset list.
            binding.spinnerMoshCommand.setText("Custom…", false)
            binding.layoutMoshCustomCommand.visibility = View.VISIBLE
            binding.layoutMoshCustomDesc.visibility = View.VISIBLE
            binding.editMoshCustomCommand.setText(savedCmd)
            // Restore the saved custom description (bug-10) so the round-trip is complete.
            val savedDesc = try {
                profile.advancedSettings?.let { org.json.JSONObject(it).optString("moshServerDescription") }
                    .takeIf { !it.isNullOrBlank() }
            } catch (_: Exception) { null }
            savedDesc?.let { binding.editMoshCustomDesc.setText(it) }
        }
    }

    /** Map a moshMode value to the display label used in the spinner. */
    private fun moshModeLabel(mode: String): String = when (mode) {
        "off" -> "Off"
        "on"  -> "On"
        else  -> "Auto (default)"
    }

    /** Read the current mosh mode ("off"/"auto"/"on") from the spinner. */
    private fun readMoshModeFromUi(): String = when (binding.spinnerMoshMode.text.toString()) {
        "Off" -> "off"
        "On"  -> "on"
        else  -> "auto"
    }

    /**
     * Read the effective mosh command from the UI.
     * Returns null if the Default preset is selected (no override stored).
     * NOTE: do NOT short-circuit when mode is "off" — the command must be
     * preserved in advancedSettings so it round-trips when the user re-enables
     * mosh later (bug-26: early return caused mergeAdvancedSettings to remove
     * the key from the JSON blob whenever mode was saved as "off").
     */
    private fun readMoshCommandFromUi(): String? {
        val selectedLabel = binding.spinnerMoshCommand.text.toString()
        val preset = MOSH_PRESETS.firstOrNull { it.description == selectedLabel }
        return when {
            preset == null || preset.isCustom -> {
                binding.editMoshCustomCommand.text.toString().trim().takeIf { it.isNotBlank() }
            }
            preset.command == MOSH_PRESETS.first().command -> null // Default — no override
            else -> preset.command
        }
    }

    private fun setupPortKnockUI() {
        binding.switchPortKnock.setOnCheckedChangeListener { _, isChecked ->
            binding.btnConfigurePortKnock.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.layoutPortKnockDelay.visibility = if (isChecked) View.VISIBLE else View.GONE
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
        // Pre-fill with any previously entered sequence.
        pendingKnockSequence?.let { editText.setText(it) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Port Knock Sequence")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sequence = editText.text.toString().trim()
                pendingKnockSequence = sequence.ifBlank { null }
                val count = if (sequence.isNotBlank()) sequence.split(",").size else 0
                android.widget.Toast.makeText(this, "✓ Knock sequence saved: $count ports", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                pendingKnockSequence = null
                android.widget.Toast.makeText(this, "Sequence cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
