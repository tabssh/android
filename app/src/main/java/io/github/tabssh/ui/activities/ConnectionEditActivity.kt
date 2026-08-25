package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityConnectionEditBinding
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.TelnetHost
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.ThrowableMapper
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.crypto.keys.KeyType
import io.github.tabssh.crypto.keys.GenerateResult
import io.github.tabssh.crypto.keys.toUserMessage
import io.github.tabssh.crypto.storage.SecurePasswordManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import io.github.tabssh.utils.showError
import io.github.tabssh.utils.announceAccessibility
import java.util.UUID
import io.github.tabssh.utils.tabSSHApp

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
class ConnectionEditActivity : TabSSHActivity() {

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
        /** New-connection only — pre-selects the protocol spinner ("ssh"/"vnc"/"telnet"). */
        const val EXTRA_DEFAULT_PROTOCOL = "default_protocol"
        // Bundle key for the dirty flag saved/restored across rotation.
        private const val KEY_HAS_UNSAVED_CHANGES = "has_unsaved_changes"

        // Fixed positions in the network route spinner. Saved routes start at
        // index 2; the final index is always the "+ Add new route…" action.
        private const val ROUTE_POS_DIRECT = 0
        private const val ROUTE_POS_GLOBAL = 1
        private const val ROUTE_POS_ROUTES_START = 2

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

        /** Launch to create a new [TelnetHost] with the Telnet protocol pre-selected. */
        fun createTelnetIntent(context: Context, telnetHostId: String? = null): Intent {
            return Intent(context, ConnectionEditActivity::class.java).apply {
                telnetHostId?.let { putExtra(EXTRA_CONNECTION_ID, it) }
                putExtra(EXTRA_IS_EDIT_MODE, telnetHostId != null)
                if (telnetHostId == null) putExtra(EXTRA_DEFAULT_PROTOCOL, "telnet")
            }
        }
    }

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var app: TabSSHApplication

    // Captured from onCreate's savedInstanceState and applied once the
    // async DB load finishes populating fields from the stored record
    // — applying it any earlier would just be overwritten by
    // the freshly loaded record. Null on a fresh launch, or once consumed.
    private var pendingFormState: Bundle? = null

    private var existingProfile: ConnectionProfile? = null
    private var editingVncHostId: String? = null
    // Set when loadConnection() falls back to telnet_hosts (post-MIGRATION_24_25,
    // telnet rows are no longer ConnectionProfile rows).
    private var editingTelnetHostId: String? = null
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

    // Network route selection for this connection.
    //   null                -> inherit the global default route
    //   NetworkRoute.DIRECT -> force a direct connection (no proxy/jump)
    //   any other value     -> the referenced NetworkRoute id
    private var availableRoutes: List<NetworkRoute> = emptyList()
    private var selectedRouteId: String? = null
    private var routesLoaded: Boolean = false

    // Launches the route editor for the "+ Add new route…" option and selects
    // the freshly created route when the editor returns successfully.
    private val routeEditorLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val newId = result.data?.getStringExtra(
                    NetworkRouteEditActivity.RESULT_ROUTE_ID
                )
                loadRoutesIntoSpinner(forceSelectRouteId = newId)
            } else {
                renderRouteSelection()
            }
        }

    // Launches the file picker for "import key from file" and reads the
    // selected document back into the key-name prompt on success.
    private val importKeyLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
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
                        val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to read key file")
                        showError(getString(R.string.conn_edit_read_key_file_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
                    }
                }
            }
        }

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

        app = tabSSHApp
        isEditMode = intent.getBooleanExtra(EXTRA_IS_EDIT_MODE, false)
        pendingFormState = savedInstanceState

        setupToolbar()
        // Wire spinner adapters + listeners synchronously with a placeholder list
        // BEFORE any async load can race the user (UX-01, rule 5). The async
        // loaders below replace the adapter contents once the DB load returns.
        bootstrapIdentitySpinner()
        bootstrapKeySpinner()
        setupAuthTypeSpinner()
        setupKeySpinner()
        setupGroupSpinner()
        setupRouteSpinner()
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
                // Hosts tab's Telnet sub-tab launches "Add" with this extra so the
                // form opens already on the Telnet protocol instead of SSH.
                val defaultProtocol = intent.getStringExtra(EXTRA_DEFAULT_PROTOCOL)
                if (defaultProtocol != null) {
                    val values = resources.getStringArray(R.array.protocol_values)
                    val index = values.indexOf(defaultProtocol)
                    if (index >= 0) binding.spinnerProtocol.setSelection(index)
                }
                // New-connection path has no async DB load to hang the restore
                // off of (unlike loadConnection/loadVncHost's populate* calls),
                // so apply any rotation-carried form state right here.
                finishPopulatingFields()
            }
        }

        Logger.d("ConnectionEditActivity", "editMode=$isEditMode vncHostId=$vncHostId connectionId=$connectionId")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveFormState(outState, binding.root)
        outState.putBoolean(KEY_HAS_UNSAVED_CHANGES, hasUnsavedChanges)
    }

    /**
     * Clears the dirty flag set by [setupUnsavedChangesGuard]'s watchers
     * firing during programmatic population, then applies any rotation-
     * carried [pendingFormState] on top so in-progress edits
     * win over the freshly loaded record.
     */
    private fun finishPopulatingFields() {
        hasUnsavedChanges = false
        pendingFormState?.let { saved ->
            restoreFormState(saved, binding.root)
            hasUnsavedChanges = saved.getBoolean(KEY_HAS_UNSAVED_CHANGES, false)
        }
        pendingFormState = null
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

    private fun setupToolbar() {
        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(
            if (isEditMode) R.string.connection_edit_title_edit else R.string.connection_edit_title_new
        )
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
                // TelnetHost only stores shared metadata (name/host/port/username/
                // group/color/notes) plus a Keystore-bound password — advanced SSH
                // settings, notifications, appearance, multiplexer and proxy have no
                // backing column and would silently be dropped on save, so hide them.
                binding.cardAuthentication.visibility = View.VISIBLE
                binding.cardAdvancedSettings.visibility = View.GONE
                binding.cardNotificationsSection.visibility = View.GONE
                binding.cardMultiplexer.visibility = View.GONE
                binding.cardAppearance.visibility = View.GONE
                binding.cardProxy.visibility = View.GONE
                binding.layoutUsernameInput.visibility = View.VISIBLE
                // Hide identity picker and VNC password — Telnet uses inline user/pass only
                binding.layoutIdentityRow.visibility = View.GONE
                binding.layoutVncPassword.visibility = View.GONE
                binding.editVncPassword.text?.clear()
                autoSetPort("23", setOf("22", "5900"))
            }
            // "ssh"
            else -> {
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
        val placeholder = listOf(getString(R.string.conn_edit_no_identity))
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
            val items = listOf(getString(R.string.conn_edit_no_identity)) + when (currentProtocol) {
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
        val placeholder = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder))
        binding.spinnerSshKey.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, placeholder)
        )
        binding.spinnerSshKey.setOnItemClickListener { _, _, position, _ ->
            val keyNames = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder)) + availableKeys.map { it.getDisplayName() }
            if (position < keyNames.size) {
                selectedKeyIndex = position
                binding.spinnerSshKey.setText(keyNames[position], false)
            }
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

                val items = listOf(getString(R.string.conn_edit_no_identity)) + availableIdentities.map { it.name }
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

                val items = listOf(getString(R.string.conn_edit_no_identity)) + availableVncIdentities.map { it.name }
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
        binding.spinnerIdentity.setText(getString(R.string.conn_edit_no_identity), false)
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
        binding.spinnerIdentity.setText(getString(R.string.conn_edit_no_identity), false)
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
        }
    }

    // -------------------------------------------------------------------------
    // SSH key spinner
    // -------------------------------------------------------------------------

    private fun setupKeySpinner() {
        lifecycleScope.launch {
            try {
                availableKeys = app.keyStorage.listStoredKeys()
                val keyNames = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder)) + availableKeys.map { it.getDisplayName() }
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
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load SSH keys", e)
                showError(getString(R.string.conn_edit_load_ssh_keys_failed), getString(R.string.status_error))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Network route picker
    //
    // A connection either inherits the global default route, forces a direct
    // connection, or references a reusable NetworkRoute (proxy / jump host)
    // managed on the Routing & Forwarding hub. The editor stores only the
    // chosen route id on the profile — proxy details live on the route.
    // -------------------------------------------------------------------------

    private fun setupRouteSpinner() {
        binding.spinnerRoute.setOnItemClickListener { _, _, position, _ ->
            val labels = currentRouteLabels()
            when (position) {
                ROUTE_POS_DIRECT -> {
                    selectedRouteId = NetworkRoute.DIRECT
                    hasUnsavedChanges = true
                }
                ROUTE_POS_GLOBAL -> {
                    selectedRouteId = null
                    hasUnsavedChanges = true
                }
                labels.size - 1 -> {
                    // "+ Add new route…" — open the editor and re-select on return.
                    routeEditorLauncher.launch(
                        Intent(this, NetworkRouteEditActivity::class.java)
                    )
                    // Restore the label so the transient "+ Add…" text is not left
                    // showing if the user cancels.
                    renderRouteSelection()
                    return@setOnItemClickListener
                }
                else -> {
                    availableRoutes.getOrNull(position - ROUTE_POS_ROUTES_START)?.let {
                        selectedRouteId = it.id
                        hasUnsavedChanges = true
                    }
                }
            }
            renderRouteSelection()
        }
        loadRoutesIntoSpinner()
    }

    /**
     * Build the ordered spinner labels: Direct, Global default, each saved
     * route by name, then the "add new" action as the final item.
     */
    private fun currentRouteLabels(): List<String> {
        val labels = mutableListOf(
            getString(R.string.conn_route_direct),
            getString(R.string.conn_route_global_default)
        )
        labels += availableRoutes.map { it.name.ifBlank { it.getSummary() } }
        labels += getString(R.string.conn_route_add_new)
        return labels
    }

    /**
     * Load saved routes off the main thread, refresh the adapter, and re-render
     * the current selection. [forceSelectRouteId] overrides the selection (used
     * after adding a new route from the picker).
     */
    private fun loadRoutesIntoSpinner(forceSelectRouteId: String? = null) {
        lifecycleScope.launch {
            availableRoutes = withContext(Dispatchers.IO) {
                app.database.networkRouteDao().getAllList()
            }
            routesLoaded = true
            if (forceSelectRouteId != null) {
                selectedRouteId = forceSelectRouteId
                hasUnsavedChanges = true
            }
            binding.spinnerRoute.setAdapter(
                ArrayAdapter(
                    this@ConnectionEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    currentRouteLabels()
                )
            )
            renderRouteSelection()
        }
    }

    /** Sync the visible spinner text with [selectedRouteId]. */
    private fun renderRouteSelection() {
        val labels = currentRouteLabels()
        val text = when (val id = selectedRouteId) {
            NetworkRoute.DIRECT -> labels[ROUTE_POS_DIRECT]
            null -> labels[ROUTE_POS_GLOBAL]
            else -> {
                val idx = availableRoutes.indexOfFirst { it.id == id }
                when {
                    idx >= 0 -> labels[idx + ROUTE_POS_ROUTES_START]
                    // Referenced route was deleted — fall back to the global default.
                    routesLoaded -> {
                        selectedRouteId = null
                        labels[ROUTE_POS_GLOBAL]
                    }
                    // Routes not loaded yet — keep the id, show default placeholder.
                    else -> labels[ROUTE_POS_GLOBAL]
                }
            }
        }
        binding.spinnerRoute.setText(text, false)
    }

    // -------------------------------------------------------------------------
    // Group spinner
    // -------------------------------------------------------------------------

    private fun setupGroupSpinner() {
        lifecycleScope.launch {
            // Only user groups in the spinner
            val groups = withContext(Dispatchers.IO) {
                app.database.connectionGroupDao().getAllGroups().first()
            }.filter { it.groupType.isEmpty() }
            val groupsList = mutableListOf(getString(R.string.conn_edit_no_group))
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
                    binding.spinnerGroup.setText(getString(R.string.conn_edit_no_group), false)
                    selectedGroupId = null
                    selectedGroupName = getString(R.string.conn_edit_no_group)
                    supportActionBar?.subtitle = null
                }
            } ?: run {
                binding.spinnerGroup.setText(getString(R.string.conn_edit_no_group), false)
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

    // hasUnsavedChanges itself lives on TabSSHActivity — this
    // screen only wires which fields flip it and calls
    // enableUnsavedChangesGuard() below. Reset to false at the end of
    // populateFields/populateVncFields/populateTelnetFields and after a
    // successful save.

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
        // bug-15: track changes on mosh / remote-command custom fields so the
        // unsaved-changes guard fires for every editable field on the form.
        binding.editMoshCustomCommand.addTextChangedListener(watcher)
        binding.editMoshCustomDesc.addTextChangedListener(watcher)
        binding.editRemoteCommandCustom.addTextChangedListener(watcher)
        binding.spinnerEncoding.addTextChangedListener(watcher)
        binding.switchKeepAlive.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }

        enableUnsavedChangesGuard()
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConnection() }
        binding.btnCancel.setOnClickListener { confirmDiscardIfNeeded { finish() } }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_pick_color_tag_title)
            .setItems(labels) { _, which ->
                currentColorTag = colorTagPresets[which].first
                renderColorTagPreview()
            }
            .setNeutralButton(R.string.action_clear) { _, _ -> currentColorTag = 0; renderColorTagPreview() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderColorTagPreview() {
        if (currentColorTag != 0) {
            binding.previewColorTag.setBackgroundColor(currentColorTag)
        } else {
            binding.previewColorTag.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.gray_400))
        }
    }

    // -------------------------------------------------------------------------
    // Load — SSH/Telnet ConnectionProfile
    // -------------------------------------------------------------------------

    private fun loadConnection(connectionId: String) {
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(connectionId)
                }
                if (profile != null) {
                    existingProfile = profile
                    populateFields(profile)
                    supportActionBar?.title = getString(R.string.conn_edit_title_edit_named, profile.name)
                    return@launch
                }
                // Post-MIGRATION_24_25 telnet rows are no longer ConnectionProfile
                // rows — callers (ConnectionsFragment, FrequentConnectionsFragment,
                // TabTerminalActivity, LibvirtManagerActivity) pass a bare id without
                // knowing its protocol, so fall back to telnet_hosts before failing.
                val telnetHost = withContext(Dispatchers.IO) {
                    app.database.telnetHostDao().getById(connectionId)
                }
                if (telnetHost != null) {
                    editingTelnetHostId = telnetHost.id
                    populateTelnetFields(telnetHost)
                    supportActionBar?.title = getString(R.string.conn_edit_title_edit_named, telnetHost.name)
                } else {
                    showError(getString(R.string.conn_edit_load_connection_failed), getString(R.string.status_error))
                    finish()
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load connection", e)
                showError(getString(R.string.conn_edit_load_connection_failed), getString(R.string.status_error))
                finish()
            }
        }
    }

    private suspend fun populateTelnetFields(telnetHost: TelnetHost) {
        binding.editName.setText(telnetHost.name)
        binding.editHost.setText(telnetHost.host)
        binding.editPort.setText(telnetHost.port.toString())
        binding.editUsername.setText(telnetHost.username)
        // Set protocol spinner to Telnet (index 2) — triggers onItemSelected ->
        // updateProtocolUI("telnet"). Guard flag mirrors populateVncFields (UX-08).
        isPopulatingFields = true
        binding.spinnerProtocol.setSelection(2)
        selectedGroupId = telnetHost.groupId
        val resolvedGroup = selectedGroupId?.let { gid ->
            withContext(Dispatchers.IO) { app.database.connectionGroupDao().getGroupById(gid) }
        }
        if (resolvedGroup != null) {
            selectedGroupName = resolvedGroup.name
            binding.spinnerGroup.setText(resolvedGroup.name, false)
            supportActionBar?.subtitle = resolvedGroup.name
        } else {
            selectedGroupId = null
            selectedGroupName = getString(R.string.conn_edit_no_group)
            binding.spinnerGroup.setText(getString(R.string.conn_edit_no_group), false)
            supportActionBar?.subtitle = null
        }
        currentColorTag = telnetHost.colorTag
        renderColorTagPreview()
        // Telnet is always password auth (TelnetHost has no authType column) —
        // pre-populate the shared password field the same way SSH does.
        if (telnetHost.savePassword) {
            val stored = try {
                withContext(Dispatchers.IO) {
                    app.securePasswordManager.retrievePassword(telnetHost.id)
                }
            } catch (e: Exception) {
                Logger.w("ConnectionEditActivity", "No stored telnet password: ${e.message}")
                null
            }
            if (!stored.isNullOrEmpty()) {
                binding.editPassword.setText(stored)
                binding.switchSavePassword.isChecked = true
            }
        }
        // After all fields are populated, clear the dirty flag so the back-press
        // guard does not trigger on TextWatcher fires from setText (UX-13), then
        // apply any rotation-carried edits on top.
        finishPopulatingFields()
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
            val items = listOf(getString(R.string.conn_edit_no_identity)) + availableIdentities.map { it.name }
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
                val keyNames = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder)) + availableKeys.map { it.getDisplayName() }
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

        // NULL override = auto-detect, shown as the "AUTO" sentinel entry.
        val ovEntries = resources.getStringArray(R.array.multiplexer_override_entries)
        val ovValues = resources.getStringArray(R.array.multiplexer_override_values)
        val ovIndex = ovValues.indexOf(profile.multiplexerOverride ?: "AUTO")
        binding.spinnerMultiplexerOverride.setText(
            ovEntries.getOrNull(ovIndex) ?: ovEntries[0], false
        )

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
            selectedGroupName = getString(R.string.conn_edit_no_group)
            binding.spinnerGroup.setText(getString(R.string.conn_edit_no_group), false)
            supportActionBar?.subtitle = null
        }

        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val themeIndex = themeValues.indexOf(profile.theme)
        if (themeIndex >= 0) {
            val displayEntries = listOf(getString(R.string.conn_edit_use_global_default)) + themeEntries.toList()
            binding.spinnerConnectionTheme.setText(displayEntries[themeIndex + 1], false)
        } else {
            binding.spinnerConnectionTheme.setText(getString(R.string.conn_edit_use_global_default), false)
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

        // Network route — restore the saved selection. renderRouteSelection()
        // resolves it against the route list, which may still be loading; the
        // route loader re-renders once it completes.
        selectedRouteId = profile.routeId
        renderRouteSelection()

        // Port knock — restore persisted sequence into local state and switch.
        pendingKnockSequence = profile.portKnockSequence
        binding.switchPortKnock.isChecked = profile.portKnockEnabled == true
        binding.btnConfigurePortKnock.visibility =
            if (profile.portKnockEnabled == true) View.VISIBLE else View.GONE

        // populateFields fires TextWatchers as it fills the form — clear the
        // dirty flag now so the back-press guard does not nag the user for
        // changes they didn't actually make, then apply any rotation-carried
        // edits on top.
        finishPopulatingFields()
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
                    supportActionBar?.title = getString(R.string.conn_edit_title_edit_named, vncHost.name)
                } else {
                    showError(getString(R.string.conn_edit_vnc_host_not_found), getString(R.string.status_error))
                    finish()
                }
            } catch (e: Exception) {
                Logger.e("ConnectionEditActivity", "Failed to load VNC host", e)
                showError(getString(R.string.conn_edit_load_vnc_host_failed), getString(R.string.status_error))
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
            val items = listOf(getString(R.string.conn_edit_no_identity)) + availableVncIdentities.map { it.name }
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
            selectedGroupName = getString(R.string.conn_edit_no_group)
            binding.spinnerGroup.setText(getString(R.string.conn_edit_no_group), false)
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
        // guard does not trigger on TextWatcher fires from setText (UX-13), then
        // apply any rotation-carried edits on top.
        finishPopulatingFields()
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
        if (currentProtocol == "telnet") {
            saveTelnetHost()
            return
        }
        saveConnectionProfile()
    }

    private fun saveTelnetHost() {
        lifecycleScope.launch {
            try {
                val name = binding.editName.text.toString().trim()
                val host = binding.editHost.text.toString().trim()
                val port = binding.editPort.text.toString().toIntOrNull() ?: 23
                val username = binding.editUsername.text.toString().trim()
                val password = binding.editPassword.text.toString()
                val savePasswordChecked = binding.switchSavePassword.isChecked && password.isNotEmpty()
                val now = System.currentTimeMillis()
                val hostId = editingTelnetHostId ?: UUID.randomUUID().toString()

                val existing = if (editingTelnetHostId != null) {
                    app.database.telnetHostDao().getById(editingTelnetHostId!!)
                } else null

                val telnetHost = existing?.copy(
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    savePassword = savePasswordChecked,
                    groupId = selectedGroupId,
                    colorTag = currentColorTag,
                    modifiedAt = now
                ) ?: TelnetHost(
                    id = hostId,
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    savePassword = savePasswordChecked,
                    groupId = selectedGroupId,
                    colorTag = currentColorTag,
                    createdAt = now,
                    modifiedAt = now
                )

                if (existing != null) {
                    app.database.telnetHostDao().update(telnetHost)
                    Logger.i("ConnectionEditActivity", "Updated telnet host: $name")
                    showToast(getString(R.string.conn_edit_connection_updated))
                } else {
                    app.database.telnetHostDao().insert(telnetHost)
                    Logger.i("ConnectionEditActivity", "Saved telnet host: $name")
                    showToast(getString(R.string.conn_edit_connection_saved))
                }

                // Persist or clear the Keystore-bound password, keyed by the bare
                // host id — same convention ConnectionProfile uses (doSave()).
                if (savePasswordChecked) {
                    val storageLevel = if (app.securePasswordManager.requiresEnhancedSecurity(host)) {
                        SecurePasswordManager.StorageLevel.BIOMETRIC
                    } else {
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    }
                    app.securePasswordManager.storePassword(hostId, password, storageLevel)
                } else {
                    try { app.securePasswordManager.clearPassword(hostId) } catch (_: Exception) {}
                }

                hasUnsavedChanges = false
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to save telnet host")
                showError(getString(R.string.conn_edit_save_connection_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
            }
        }
    }

    private fun saveVncHost() {
        lifecycleScope.launch {
            try {
                // Defensive sync: if the identity spinner shows "No Identity" but
                // selectedVncIdentityId is still non-null (e.g. async restore beat the
                // synchronous fetch path), clear it so the DB column matches the UI.
                if (binding.spinnerIdentity.text.toString() == getString(R.string.conn_edit_no_identity)) {
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
                    showToast(getString(R.string.conn_edit_vnc_host_updated))
                } else {
                    app.database.vncHostDao().insert(vncHost)
                    Logger.i("ConnectionEditActivity", "Saved VNC host: $name")
                    showToast(getString(R.string.conn_edit_vnc_host_saved))
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
                val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to save VNC host")
                showError(getString(R.string.conn_edit_save_vnc_host_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
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
                    MaterialAlertDialogBuilder(this@ConnectionEditActivity)
                        .setTitle(R.string.conn_edit_duplicate_title)
                        .setMessage(getString(R.string.conn_edit_duplicate_message, profile.username, profile.host, profile.port, duplicate.name))
                        .setPositiveButton(R.string.conn_edit_save_as_new) { _, _ ->
                            // bug-16: profile.id still holds the existing ID when in edit mode;
                            // forceInsert bypasses the isEditMode branch in doSave().
                            lifecycleScope.launch {
                                doSave(profile.copy(id = java.util.UUID.randomUUID().toString()), forceInsert = true)
                            }
                        }
                        .setNeutralButton(R.string.conn_edit_update_existing) { _, _ ->
                            lifecycleScope.launch { doSave(profile.copy(id = duplicate.id)) }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return@launch
                }
                doSave(profile)
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to save connection")
                showError(getString(R.string.conn_edit_save_connection_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
            }
        }
    }

    private suspend fun doSave(profile: ConnectionProfile, forceInsert: Boolean = false) {
        try {
            if (isEditMode && existingProfile != null && !forceInsert) {
                app.database.connectionDao().updateConnection(profile)
                Logger.i("ConnectionEditActivity", "Updated connection: ${profile.name}")
                showToast(getString(R.string.conn_edit_connection_updated))
            } else {
                app.database.connectionDao().insertConnection(profile)
                Logger.i("ConnectionEditActivity", "Created connection: ${profile.name}")
                showToast(getString(R.string.conn_edit_connection_saved))
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
            val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to save connection")
            showError(getString(R.string.conn_edit_save_connection_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
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

        // "AUTO" sentinel (or anything unrecognised) round-trips to a NULL
        // override so live detection stays in charge.
        val ovEntries = resources.getStringArray(R.array.multiplexer_override_entries)
        val ovValues = resources.getStringArray(R.array.multiplexer_override_values)
        val ovIndex = ovEntries.indexOf(binding.spinnerMultiplexerOverride.text.toString())
        val multiplexerOverride = ovValues.getOrNull(ovIndex)?.takeIf { it != "AUTO" }

        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val themeValues = resources.getStringArray(R.array.terminal_theme_values)
        val selectedThemeText = binding.spinnerConnectionTheme.text.toString()
        val theme = if (selectedThemeText == getString(R.string.conn_edit_use_global_default)) {
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

        // Routing is now driven by routeId (see the network route picker). Legacy
        // proxy_* columns are explicitly cleared so a migrated connection never
        // double-applies both a saved route and a stale inline proxy config.
        val routeId = selectedRouteId

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
            multiplexerOverride = multiplexerOverride,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            proxyType = null, proxyHost = null, proxyPort = null,
            proxyUsername = null, proxyAuthType = null, proxyKeyId = null,
            routeId = routeId,
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
            multiplexerOverride = multiplexerOverride,
            theme = theme, fontSizeOverride = fontSizeOverride,
            postConnectScript = postConnectScript, envVars = envVars,
            agentForwarding = agentForwarding, remoteCommand = remoteCommand,
            ipMode = ipMode, groupId = selectedGroupId,
            routeId = routeId,
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
                showToast(getString(R.string.conn_edit_identity_no_ssh_key))
                isValid = false
            }
        }
        // bug-24: port knock enabled but sequence is empty → connecting would send no
        // knock packets and the server's firewall would block the SSH connection.
        if (binding.switchPortKnock.isChecked && pendingKnockSequence.isNullOrBlank()) {
            showToast(getString(R.string.conn_edit_port_knock_not_configured))
            isValid = false
        }
        return isValid
    }

    private fun validateHost(): Boolean {
        val host = binding.editHost.text.toString().trim()
        return if (host.isBlank()) {
            binding.layoutHost.error = getString(R.string.conn_edit_host_required)
            false
        } else {
            binding.layoutHost.error = null
            true
        }
    }

    private fun validatePort(): Boolean {
        val portText = binding.editPort.text.toString().trim()
        val port = portText.toIntOrNull()
        return when {
            portText.isBlank() -> { binding.layoutPort.error = getString(R.string.conn_edit_port_required); false }
            port == null -> { binding.layoutPort.error = getString(R.string.conn_edit_port_invalid); false }
            port < 1 || port > 65535 -> { binding.layoutPort.error = getString(R.string.conn_edit_port_out_of_range); false }
            else -> { binding.layoutPort.error = null; true }
        }
    }

    private fun validateUsername(): Boolean {
        val username = binding.editUsername.text.toString().trim()
        return if (username.isBlank()) {
            binding.layoutUsernameInput.error = getString(R.string.conn_edit_username_required)
            false
        } else {
            binding.layoutUsernameInput.error = null
            true
        }
    }

    private fun validateKeySelection(): Boolean {
        return if (selectedKeyIndex <= 0) {
            // UX-04: inline error on the SSH key picker EditText so the user sees
            // the problem in context, not just a toast. layout_ssh_key is a plain
            // LinearLayout (no .error API); MaterialAutoCompleteTextView inherits
            // from EditText and shows the error icon via its TextInputLayout wrapper.
            binding.spinnerSshKey.error = getString(R.string.conn_edit_select_key_for_pubkey_auth)
            showToast(getString(R.string.conn_edit_please_select_ssh_key))
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
            showToast(getString(R.string.conn_edit_test_not_available_vnc))
            return
        }
        if (!validateAllFields()) return

        lifecycleScope.launch {
            binding.btnTest.isEnabled = false
            binding.btnTest.text = getString(R.string.conn_edit_testing_ellipsis)
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
                    showToast(getString(R.string.conn_edit_test_successful))
                } else {
                    val errorMsg = connection.errorMessage.value ?: getString(R.string.conn_edit_test_failed_generic)
                    showError(errorMsg, getString(R.string.conn_edit_test_failed_title))
                }
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Connection test failed")
                showError(mapped.message, getString(R.string.conn_edit_test_failed_title), copyText = mapped.technicalDetail)
            } finally {
                try { connection?.disconnect() } catch (e: Exception) {
                    Logger.w("ConnectionEditActivity", "Test-connect disconnect failed", e)
                }
                tempProfileId?.let { app.securePasswordManager.clearPassword(it) }
                binding.btnTest.isEnabled = true
                binding.btnTest.text = getString(R.string.test_button)
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
            getString(R.string.conn_edit_key_option_import_file),
            getString(R.string.conn_edit_key_option_paste),
            getString(R.string.conn_edit_key_option_generate),
            getString(R.string.conn_edit_key_option_browse)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_key_management_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importKeyFromFile()
                    1 -> pasteKey()
                    2 -> generateNewKey()
                    3 -> browseExistingKeys()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importKeyFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            importKeyLauncher.launch(intent)
        } catch (e: Exception) {
            showToast(getString(R.string.conn_edit_file_picker_unavailable))
        }
    }

    private fun pasteKey() {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val editText = io.github.tabssh.ui.dialogs.DialogFields.addMultiline(
            form, hint = getString(R.string.paste_ssh_key_hint),
            minLines = 10, maxLines = 20, monospace = true
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_paste_key_title)
            .setView(form.root)
            .setPositiveButton(R.string.next) { _, _ ->
                val keyContent = editText.text.toString().trim()
                if (keyContent.isNotEmpty()) {
                    promptForKeyName(getString(R.string.conn_edit_pasted_key_default_name)) { confirmedName ->
                        importKeyFromContent(keyContent, confirmedName)
                    }
                } else {
                    showToast(getString(R.string.conn_edit_key_content_empty))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateNewKey() { showKeyGenerationDialog() }

    private fun showKeyGenerationDialog() {
        val keyTypes = arrayOf(
            getString(R.string.conn_edit_key_type_rsa_2048),
            getString(R.string.conn_edit_key_type_rsa_4096),
            getString(R.string.conn_edit_key_type_ecdsa_256),
            getString(R.string.conn_edit_key_type_ecdsa_384),
            getString(R.string.conn_edit_key_type_ed25519)
        )
        var selectedType = 4
        val keyTypeMapping = mapOf(
            0 to Pair(KeyType.RSA, 2048), 1 to Pair(KeyType.RSA, 4096),
            2 to Pair(KeyType.ECDSA, 256), 3 to Pair(KeyType.ECDSA, 384),
            4 to Pair(KeyType.ED25519, 256)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_generate_key_title)
            .setMessage(R.string.conn_edit_generate_key_message)
            .setSingleChoiceItems(keyTypes, selectedType) { _, which -> selectedType = which }
            .setPositiveButton(R.string.conn_edit_generate) { _, _ ->
                val (keyType, keySize) = keyTypeMapping[selectedType] ?: Pair(KeyType.ED25519, 256)
                showKeyNamingDialog(keyType, keySize)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showKeyNamingDialog(keyType: KeyType, keySize: Int) {
        val keyName = when (keyType) {
            KeyType.RSA -> getString(R.string.conn_edit_key_label_rsa, keySize)
            KeyType.ECDSA -> getString(R.string.conn_edit_key_label_ecdsa, keySize)
            KeyType.ED25519 -> getString(R.string.conn_edit_key_label_ed25519)
            KeyType.DSA -> getString(R.string.conn_edit_key_label_dsa, keySize)
        }
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val editText = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.key_generate_name_hint),
            initial = getString(R.string.conn_edit_generated_key_default_name, keyName)
        )
        editText.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_name_key_title)
            .setMessage(R.string.conn_edit_name_key_message)
            .setView(form.root)
            .setPositiveButton(R.string.conn_edit_generate_key_button) { _, _ ->
                val keyName = editText.text.toString().trim()
                if (keyName.isNotEmpty()) generateKeyPair(keyType, keySize, keyName)
                else showToast(getString(R.string.conn_edit_key_name_required))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateKeyPair(keyType: KeyType, keySize: Int, keyName: String) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_generating_key_title)
            .setMessage(getString(R.string.conn_edit_generating_key_message, keyType.name, keySize))
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
                val mapped = ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Key generation failed")
                runOnUiThread {
                    progressDialog.dismiss()
                    showKeyGenerationError(getString(R.string.conn_edit_generate_key_failed, mapped.message))
                }
            }
        }
    }

    private fun loadAvailableKeys() { setupKeySpinner() }

    private fun showKeyGenerationSuccess(message: String, fingerprint: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_key_generated_title)
            .setMessage(getString(R.string.conn_edit_key_generated_message, message, fingerprint))
            .setPositiveButton(R.string.ok) { _, _ -> loadAvailableKeys() }
            .show()
        showToast(getString(R.string.conn_edit_key_generated_toast))
    }

    private fun showKeyGenerationError(errorMessage: String) {
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = getString(R.string.conn_edit_key_generation_failed_title),
            message = getString(R.string.conn_edit_key_generation_failed_message, errorMessage),
            onDismiss = {}
        )
    }

    private fun browseExistingKeys() {
        if (availableKeys.isEmpty()) {
            showToast(getString(R.string.conn_edit_no_keys_stored))
            return
        }
        val keyNames = availableKeys.map { it.getDisplayName() }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_key)
            .setItems(keyNames) { _, which ->
                selectedKeyIndex = which + 1
                val selectedKey = availableKeys[which]
                binding.spinnerSshKey.setText(selectedKey.getDisplayName(), false)
                showToast(getString(R.string.conn_edit_selected_key, selectedKey.getDisplayName()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importKeyFromContent(keyContent: String, filename: String) {
        lifecycleScope.launch {
            try {
                showToast(getString(R.string.conn_edit_importing_ssh_key))
                val needsPassphrase = keyContent.contains("ENCRYPTED") ||
                    keyContent.contains("Proc-Type: 4,ENCRYPTED")
                if (needsPassphrase) showKeyPassphraseDialog(keyContent, filename)
                else performKeyImport(keyContent, filename, null)
            } catch (e: Exception) {
                val mapped = io.github.tabssh.utils.ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Failed to import key")
                showError(getString(R.string.conn_edit_import_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
            }
        }
    }

    private fun showKeyPassphraseDialog(keyContent: String, filename: String) {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val passphraseInput = io.github.tabssh.ui.dialogs.DialogFields.addSecret(
            form, hint = getString(R.string.key_passphrase_hint)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_encrypted_key_title)
            .setMessage(R.string.conn_edit_encrypted_key_message)
            .setView(form.root)
            .setPositiveButton(R.string.menu_import) { _, _ ->
                val passphrase = passphraseInput.text.toString()
                if (passphrase.isEmpty()) { showToast(getString(R.string.conn_edit_passphrase_required)); return@setPositiveButton }
                lifecycleScope.launch { performKeyImport(keyContent, filename, passphrase) }
            }
            .setNegativeButton(R.string.cancel, null)
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
                    showToast(getString(R.string.conn_edit_key_imported_toast))
                    // Item 20: explicit announcement — key import is async
                    // and finishes off-screen (a file picker or background
                    // decode), so TalkBack needs a spoken outcome.
                    announceAccessibility(getString(R.string.conn_edit_key_imported_toast))
                    // bug-20: setupKeySpinner() is a fire-and-forget coroutine; calling it
                    // and then reading availableKeys immediately is a race. Load keys inline
                    // with withContext(IO) so availableKeys is up-to-date before auto-select.
                    availableKeys = withContext(Dispatchers.IO) { app.keyStorage.listStoredKeys() }
                    val keyNames = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder)) + availableKeys.map { it.getDisplayName() }
                    val adapter = ArrayAdapter(this@ConnectionEditActivity, android.R.layout.simple_dropdown_item_1line, keyNames)
                    binding.spinnerSshKey.setAdapter(adapter)
                    val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                    if (importedKeyIndex >= 0) {
                        selectedKeyIndex = importedKeyIndex + 1
                        if (selectedKeyIndex < keyNames.size) {
                            binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                        }
                    }
                }
                is io.github.tabssh.crypto.keys.ImportResult.Error -> {
                    Logger.e("ConnectionEditActivity", "Key import failed: ${result.errorType} (${result.technicalDetail})")
                    showKeyImportErrorDialog(result.errorType, result.technicalDetail)
                }
            }
        } catch (e: Exception) {
            val mapped = io.github.tabssh.utils.ThrowableMapper.map(this, "ConnectionEditActivity", e, "Key import failed")
            showError(getString(R.string.conn_edit_key_import_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
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
        return if (name.isBlank()) getString(R.string.conn_edit_imported_key_default_name) else name
    }

    private fun importKeyWithPassphrase(keyContent: String, filename: String, passphrase: String) {
        lifecycleScope.launch {
            try {
                showToast(getString(R.string.conn_edit_importing_encrypted_key))
                val result = app.keyStorage.importKeyFromText(
                    keyContent = keyContent,
                    passphrase = passphrase,
                    keyName = extractKeyNameFromFilename(filename)
                )
                when (result) {
                    is io.github.tabssh.crypto.keys.ImportResult.Success -> {
                        Logger.i("ConnectionEditActivity", "Encrypted key imported: ${result.keyId}")
                        showToast(getString(R.string.conn_edit_encrypted_key_imported_toast))
                        announceAccessibility(getString(R.string.conn_edit_encrypted_key_imported_toast))
                        // bug-20: load keys inline so availableKeys is up-to-date before auto-select.
                        availableKeys = withContext(Dispatchers.IO) { app.keyStorage.listStoredKeys() }
                        val keyNames = listOf(getString(R.string.conn_edit_select_ssh_key_placeholder)) + availableKeys.map { it.getDisplayName() }
                        val adapter = ArrayAdapter(this@ConnectionEditActivity, android.R.layout.simple_dropdown_item_1line, keyNames)
                        binding.spinnerSshKey.setAdapter(adapter)
                        val importedKeyIndex = availableKeys.indexOfFirst { it.keyId == result.keyId }
                        if (importedKeyIndex >= 0) {
                            selectedKeyIndex = importedKeyIndex + 1
                            if (selectedKeyIndex < keyNames.size) {
                                binding.spinnerSshKey.setText(keyNames[selectedKeyIndex], false)
                            }
                        }
                    }
                    is io.github.tabssh.crypto.keys.ImportResult.Error -> {
                        Logger.e("ConnectionEditActivity", "Encrypted key import failed: ${result.errorType} (${result.technicalDetail})")
                        showKeyImportErrorDialog(result.errorType, result.technicalDetail)
                    }
                }
            } catch (e: Exception) {
                val mapped = io.github.tabssh.utils.ThrowableMapper.map(this@ConnectionEditActivity, "ConnectionEditActivity", e, "Encrypted key import failed")
                showError(getString(R.string.conn_edit_encrypted_key_import_failed, mapped.message), getString(R.string.status_error), copyText = mapped.technicalDetail)
            }
        }
    }

    private fun showKeyImportErrorDialog(errorType: io.github.tabssh.crypto.keys.KeyImportErrorType, technicalDetail: String?) {
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
            context = this,
            title = getString(R.string.conn_edit_ssh_key_import_failed_title),
            message = errorType.toUserMessage(this),
            copyText = technicalDetail,
            onDismiss = {}
        )
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
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val edit = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.key_import_name_hint), initial = suggestion
        )
        edit.setSelection(edit.text?.length ?: 0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_name_this_key_title)
            .setMessage(R.string.conn_edit_name_this_key_message)
            .setView(form.root)
            .setPositiveButton(R.string.menu_import) { _, _ ->
                val name = edit.text.toString().trim().ifBlank { suggestion }
                onConfirm(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGroupSelectionDialog() {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val editText = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.group_name_hint),
            initial = if (selectedGroupName == getString(R.string.conn_edit_no_group)) "" else selectedGroupName
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_set_group_title)
            .setMessage(R.string.conn_edit_set_group_message)
            .setView(form.root)
            .setPositiveButton(R.string.conn_edit_set) { _, _ ->
                val groupName = editText.text.toString().trim()
                if (groupName.isEmpty()) {
                    selectedGroupId = null
                    selectedGroupName = getString(R.string.conn_edit_no_group)
                    supportActionBar?.subtitle = null
                    showToast(getString(R.string.conn_edit_group_cleared))
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
                            showToast(getString(R.string.conn_edit_group_set_to, groupName))
                        } catch (e: Exception) {
                            Logger.e("ConnectionEditActivity", "Failed to resolve group '$groupName'", e)
                            showToast(getString(R.string.conn_edit_group_set_failed))
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.action_clear) { _, _ ->
                selectedGroupId = null
                selectedGroupName = getString(R.string.conn_edit_no_group)
                supportActionBar?.subtitle = null
                showToast(getString(R.string.conn_edit_group_cleared))
            }
            .show()
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

        val ovEntries = resources.getStringArray(R.array.multiplexer_override_entries)
        val ovAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ovEntries)
        binding.spinnerMultiplexerOverride.setAdapter(ovAdapter)
        binding.spinnerMultiplexerOverride.setText(ovEntries[0], false)
    }

    private fun setupConnectionThemeSpinner() {
        val themeEntries = resources.getStringArray(R.array.terminal_theme_entries)
        val entries = listOf(getString(R.string.conn_edit_use_global_default)) + themeEntries.toList()
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
        val moshModeLabels = arrayOf(
            getString(R.string.conn_edit_mosh_mode_off),
            getString(R.string.conn_edit_mosh_mode_auto),
            getString(R.string.conn_edit_mosh_mode_on)
        )
        binding.spinnerMoshMode.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, moshModeLabels)
        )
        binding.spinnerMoshMode.setText(getString(R.string.conn_edit_mosh_mode_auto), false)
        binding.spinnerMoshMode.setOnItemClickListener { _, _, position, _ ->
            // Auto or On
            val isActive = position > 0
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
            binding.spinnerMoshCommand.setText(getString(R.string.conn_edit_mosh_command_custom), false)
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
            // Default — no override
            preset.command == MOSH_PRESETS.first().command -> null
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
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(this)
        val textView = android.widget.TextView(this).apply {
            text = "Enter port knock sequence (format: port:protocol, comma-separated)\nExample: 7000:TCP,8000:TCP,9000:UDP"
            textSize = 14f
        }
        form.column.addView(textView, 0)
        // Pre-fill with any previously entered sequence.
        val editText = io.github.tabssh.ui.dialogs.DialogFields.addMultiline(
            form, hint = getString(R.string.port_knock_sequence_hint),
            initial = pendingKnockSequence, minLines = 1, maxLines = 3, monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.conn_edit_port_knock_title)
            .setView(form.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val sequence = editText.text.toString().trim()
                pendingKnockSequence = sequence.ifBlank { null }
                val count = if (sequence.isNotBlank()) sequence.split(",").size else 0
                android.widget.Toast.makeText(this, getString(R.string.conn_edit_knock_sequence_saved, count), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.action_clear) { _, _ ->
                pendingKnockSequence = null
                android.widget.Toast.makeText(this, getString(R.string.conn_edit_knock_sequence_cleared), android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
