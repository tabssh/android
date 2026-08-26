package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ForwardType
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.PortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Add / edit screen for a single saved [PortForward] rule.
 *
 * The visible forward fields depend on the selected [ForwardType], and the
 * SSH endpoint is entered either as a saved connection or a manual host + key.
 */
class PortForwardEditActivity : TabSSHActivity() {

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    private val app: TabSSHApplication
        get() = tabSSHApp

    // General
    private lateinit var editName: TextInputEditText
    private lateinit var spinnerType: MaterialAutoCompleteTextView
    private lateinit var textTypeDesc: View

    // Endpoint
    private lateinit var chipSaved: com.google.android.material.chip.Chip
    private lateinit var chipManual: com.google.android.material.chip.Chip
    private lateinit var layoutConnection: TextInputLayout
    private lateinit var spinnerConnection: MaterialAutoCompleteTextView
    private lateinit var layoutManual: View
    private lateinit var layoutSshHost: TextInputLayout
    private lateinit var editSshHost: TextInputEditText
    private lateinit var layoutSshPort: TextInputLayout
    private lateinit var editSshPort: TextInputEditText
    private lateinit var layoutSshUsername: TextInputLayout
    private lateinit var editSshUsername: TextInputEditText
    private lateinit var spinnerIdentity: MaterialAutoCompleteTextView

    // Forward params
    private lateinit var layoutHostIp: TextInputLayout
    private lateinit var editHostIp: TextInputEditText
    private lateinit var layoutRemotePort: TextInputLayout
    private lateinit var editRemotePort: TextInputEditText
    private lateinit var layoutLocalPort: TextInputLayout
    private lateinit var editLocalPort: TextInputEditText

    // Options
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchAutoStart: MaterialSwitch

    // Data
    private val types = listOf(ForwardType.LOCAL, ForwardType.REMOTE, ForwardType.DYNAMIC)
    private var selectedType: ForwardType = ForwardType.LOCAL
    private var connections: List<ConnectionProfile> = emptyList()
    private var identities: List<Identity> = emptyList()
    private var selectedConnectionId: String? = null
    private var selectedIdentityId: String? = null

    private var editingId: String? = null
    private var existing: PortForward? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forward_edit)

        editingId = intent.getStringExtra(EXTRA_FORWARD_ID)

        bindViews()
        setupToolbar()
        setupTypeSpinner()
        setupEndpointChips()
        setupOptionSwitches()
        setupButtons()
        setupUnsavedChangesGuard()

        loadData()
    }

    /**
     * Wires every primary form field to flip [hasUnsavedChanges] and opts
     * this screen into the shared discard-confirmation guard.
     */
    private fun setupUnsavedChangesGuard() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { hasUnsavedChanges = true }
        }
        editName.addTextChangedListener(watcher)
        editSshHost.addTextChangedListener(watcher)
        editSshPort.addTextChangedListener(watcher)
        editSshUsername.addTextChangedListener(watcher)
        editHostIp.addTextChangedListener(watcher)
        editRemotePort.addTextChangedListener(watcher)
        editLocalPort.addTextChangedListener(watcher)

        enableUnsavedChangesGuard()
    }

    private fun bindViews() {
        editName = findViewById(R.id.edit_name)
        spinnerType = findViewById(R.id.spinner_type)
        textTypeDesc = findViewById(R.id.text_type_desc)

        chipSaved = findViewById(R.id.chip_saved)
        chipManual = findViewById(R.id.chip_manual)
        layoutConnection = findViewById(R.id.layout_connection)
        spinnerConnection = findViewById(R.id.spinner_connection)
        layoutManual = findViewById(R.id.layout_manual)
        layoutSshHost = findViewById(R.id.layout_ssh_host)
        editSshHost = findViewById(R.id.edit_ssh_host)
        layoutSshPort = findViewById(R.id.layout_ssh_port)
        editSshPort = findViewById(R.id.edit_ssh_port)
        layoutSshUsername = findViewById(R.id.layout_ssh_username)
        editSshUsername = findViewById(R.id.edit_ssh_username)
        spinnerIdentity = findViewById(R.id.spinner_identity)

        layoutHostIp = findViewById(R.id.layout_host_ip)
        editHostIp = findViewById(R.id.edit_host_ip)
        layoutRemotePort = findViewById(R.id.layout_remote_port)
        editRemotePort = findViewById(R.id.edit_remote_port)
        layoutLocalPort = findViewById(R.id.layout_local_port)
        editLocalPort = findViewById(R.id.edit_local_port)

        switchEnabled = findViewById(R.id.switch_enabled)
        switchAutoStart = findViewById(R.id.switch_autostart)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = getString(
                if (editingId != null) R.string.port_forward_edit_title_edit
                else R.string.port_forward_edit_title_new
            )
        }
    }

    private fun setupTypeSpinner() {
        val labels = types.map { typeLabel(it) }
        spinnerType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        spinnerType.setOnItemClickListener { _, _, position, _ ->
            hasUnsavedChanges = true
            applyType(types[position])
        }
    }

    private fun typeLabel(type: ForwardType): String = when (type) {
        ForwardType.LOCAL -> getString(R.string.port_forward_type_local)
        ForwardType.REMOTE -> getString(R.string.port_forward_type_remote)
        ForwardType.DYNAMIC -> getString(R.string.port_forward_type_dynamic)
    }

    /**
     * Apply the selected type: reflect it in the dropdown, its description, and
     * show only the forward fields (with the right labels) that type uses.
     */
    private fun applyType(type: ForwardType) {
        selectedType = type
        spinnerType.setText(typeLabel(type), false)
        (textTypeDesc as android.widget.TextView).setText(
            when (type) {
                ForwardType.LOCAL -> R.string.port_forward_type_local_desc
                ForwardType.REMOTE -> R.string.port_forward_type_remote_desc
                ForwardType.DYNAMIC -> R.string.port_forward_type_dynamic_desc
            }
        )
        when (type) {
            ForwardType.LOCAL -> {
                layoutHostIp.visibility = View.VISIBLE
                layoutHostIp.hint = getString(R.string.port_forward_host_ip_hint)
                layoutHostIp.helperText = getString(R.string.port_forward_host_ip_helper)
                layoutRemotePort.visibility = View.VISIBLE
                layoutRemotePort.hint = getString(R.string.port_forward_remote_port_hint)
                layoutRemotePort.helperText = null
                layoutLocalPort.visibility = View.VISIBLE
                layoutLocalPort.hint = getString(R.string.port_forward_local_port_hint)
                layoutLocalPort.helperText =
                    getString(R.string.port_forward_local_port_optional_helper)
            }
            ForwardType.REMOTE -> {
                layoutHostIp.visibility = View.VISIBLE
                layoutHostIp.hint = getString(R.string.port_forward_local_target_host_hint)
                layoutHostIp.helperText =
                    getString(R.string.port_forward_local_target_host_helper)
                layoutLocalPort.visibility = View.VISIBLE
                layoutLocalPort.hint = getString(R.string.port_forward_local_target_port_hint)
                layoutLocalPort.helperText = null
                layoutRemotePort.visibility = View.VISIBLE
                layoutRemotePort.hint = getString(R.string.port_forward_server_port_hint)
                layoutRemotePort.helperText = getString(R.string.port_forward_server_port_helper)
            }
            ForwardType.DYNAMIC -> {
                layoutHostIp.visibility = View.GONE
                layoutRemotePort.visibility = View.GONE
                layoutLocalPort.visibility = View.VISIBLE
                layoutLocalPort.hint = getString(R.string.port_forward_socks_port_hint)
                layoutLocalPort.helperText = getString(R.string.port_forward_socks_port_helper)
            }
        }
    }

    private fun setupEndpointChips() {
        chipSaved.setOnClickListener { hasUnsavedChanges = true; applyEndpointMode(useSaved = true) }
        chipManual.setOnClickListener { hasUnsavedChanges = true; applyEndpointMode(useSaved = false) }
    }

    private fun applyEndpointMode(useSaved: Boolean) {
        chipSaved.isChecked = useSaved
        chipManual.isChecked = !useSaved
        layoutConnection.visibility = if (useSaved) View.VISIBLE else View.GONE
        layoutManual.visibility = if (useSaved) View.GONE else View.VISIBLE
    }

    /**
     * Auto-start only ever runs while enabled, so its switch is greyed (and
     * cleared) whenever the master Enabled switch is off.
     */
    private fun setupOptionSwitches() {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            hasUnsavedChanges = true
            switchAutoStart.isEnabled = isChecked
            if (!isChecked) switchAutoStart.isChecked = false
        }
        switchAutoStart.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }
        switchAutoStart.isEnabled = switchEnabled.isChecked
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btn_cancel).setOnClickListener { confirmDiscardIfNeeded { finish() } }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val loadedConnections = withContext(Dispatchers.IO) {
                app.database.connectionDao().getAllConnectionsList()
            }
            val loadedIdentities = withContext(Dispatchers.IO) {
                app.database.identityDao().getAllIdentitiesList()
            }
            connections = loadedConnections
            identities = loadedIdentities

            setupConnectionSpinner()
            setupIdentitySpinner()

            val id = editingId
            val loaded = if (id != null) {
                withContext(Dispatchers.IO) { app.database.portForwardDao().getById(id) }
            } else {
                null
            }
            existing = loaded
            if (loaded != null) {
                populate(loaded)
            } else {
                applyType(ForwardType.LOCAL)
                applyEndpointMode(useSaved = connections.isNotEmpty())
                prefillFromIntent()
            }
            // Field/spinner changes above flip the dirty-flag listeners
            // installed in setupUnsavedChangesGuard() — this is DB-driven
            // (or default) population, not a user edit, so clear the flag.
            hasUnsavedChanges = false
        }
    }

    private fun setupConnectionSpinner() {
        val labels = connections.map { it.getDisplayName() }
        spinnerConnection.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        spinnerConnection.setOnItemClickListener { _, _, position, _ ->
            hasUnsavedChanges = true
            selectedConnectionId = connections[position].id
        }
    }

    private fun setupIdentitySpinner() {
        val labels = mutableListOf(getString(R.string.port_forward_identity_none))
        labels.addAll(identities.map { it.name })
        spinnerIdentity.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        spinnerIdentity.setText(labels.first(), false)
        spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
            hasUnsavedChanges = true
            selectedIdentityId = if (position == 0) null else identities[position - 1].id
        }
    }

    private fun prefillFromIntent() {
        val prefill = intent.getStringExtra(EXTRA_PREFILL_CONNECTION_ID) ?: return
        val match = connections.firstOrNull { it.id == prefill } ?: return
        applyEndpointMode(useSaved = true)
        selectedConnectionId = match.id
        spinnerConnection.setText(match.getDisplayName(), false)
    }

    private fun populate(pf: PortForward) {
        editName.setText(pf.name)
        applyType(pf.forwardType)

        if (pf.usesSavedConnection) {
            applyEndpointMode(useSaved = true)
            selectedConnectionId = pf.connectionId
            connections.firstOrNull { it.id == pf.connectionId }?.let {
                spinnerConnection.setText(it.getDisplayName(), false)
            }
        } else {
            applyEndpointMode(useSaved = false)
            editSshHost.setText(pf.sshHost.orEmpty())
            editSshPort.setText(pf.sshPort.toString())
            editSshUsername.setText(pf.sshUsername.orEmpty())
            selectedIdentityId = pf.identityId
            val identity = identities.firstOrNull { it.id == pf.identityId }
            spinnerIdentity.setText(
                identity?.name ?: getString(R.string.port_forward_identity_none), false
            )
        }

        editHostIp.setText(pf.hostIp)
        if (pf.remotePort > 0) editRemotePort.setText(pf.remotePort.toString())
        if (pf.localPort > 0) editLocalPort.setText(pf.localPort.toString())

        switchEnabled.isChecked = pf.enabled
        switchAutoStart.isEnabled = pf.enabled
        switchAutoStart.isChecked = pf.autoStart && pf.enabled
    }

    /**
     * Validate per type + endpoint mode, then insert or update and finish.
     * Returns early (showing an inline field error) on the first problem.
     */
    private fun save() {
        clearErrors()

        val name = editName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            editName.error = getString(R.string.port_forward_error_name)
            editName.requestFocus()
            return
        }

        val useSaved = chipSaved.isChecked
        var connectionId: String? = null
        var sshHost: String? = null
        var sshPort = 22
        var sshUsername: String? = null
        var identityId: String? = null

        if (useSaved) {
            connectionId = selectedConnectionId
            if (connectionId == null) {
                layoutConnection.error = getString(R.string.port_forward_error_connection)
                return
            }
        } else {
            sshHost = editSshHost.text?.toString()?.trim().orEmpty()
            if (sshHost.isEmpty()) {
                layoutSshHost.error = getString(R.string.port_forward_error_ssh_host)
                return
            }
            val portValue = editSshPort.text?.toString()?.trim()?.toIntOrNull()
            if (portValue == null || portValue !in 1..65535) {
                layoutSshPort.error = getString(R.string.route_error_port_range)
                return
            }
            sshPort = portValue
            sshUsername = editSshUsername.text?.toString()?.trim().orEmpty()
            if (sshUsername.isEmpty()) {
                layoutSshUsername.error = getString(R.string.port_forward_error_ssh_username)
                return
            }
            identityId = selectedIdentityId
        }

        var hostIp = "localhost"
        var remotePort = 0
        var localPort = 0

        when (selectedType) {
            ForwardType.LOCAL -> {
                hostIp = editHostIp.text?.toString()?.trim().orEmpty()
                if (hostIp.isEmpty()) {
                    layoutHostIp.error = getString(R.string.port_forward_error_host_ip)
                    return
                }
                remotePort = validPort(layoutRemotePort) ?: return
                // Blank local port mirrors the remote port (entity handles it).
                localPort = editLocalPort.text?.toString()?.trim()?.toIntOrNull() ?: 0
                if (localPort != 0 && localPort !in 1..65535) {
                    layoutLocalPort.error = getString(R.string.route_error_port_range)
                    return
                }
            }
            ForwardType.REMOTE -> {
                hostIp = editHostIp.text?.toString()?.trim().orEmpty()
                if (hostIp.isEmpty()) {
                    layoutHostIp.error = getString(R.string.port_forward_error_host_ip)
                    return
                }
                localPort = validPort(layoutLocalPort) ?: return
                remotePort = validPort(layoutRemotePort) ?: return
            }
            ForwardType.DYNAMIC -> {
                localPort = validPort(layoutLocalPort) ?: return
            }
        }

        val enabled = switchEnabled.isChecked
        // "Only ever autostart if enabled" — never persist auto-start on a
        // disabled rule.
        val autoStart = enabled && switchAutoStart.isChecked

        val base = existing ?: PortForward()
        val result = base.copy(
            name = name,
            type = selectedType.name,
            connectionId = connectionId,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUsername = sshUsername,
            identityId = identityId,
            hostIp = hostIp,
            remotePort = remotePort,
            localPort = localPort,
            enabled = enabled,
            autoStart = autoStart,
            modifiedAt = System.currentTimeMillis()
        )

        persist(result)
    }

    private fun validPort(layout: TextInputLayout): Int? {
        val value = layout.editText?.text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value !in 1..65535) {
            layout.error = getString(R.string.route_error_port_range)
            return null
        }
        return value
    }

    private fun persist(pf: PortForward) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (existing != null) {
                    app.database.portForwardDao().update(pf)
                } else {
                    app.database.portForwardDao().insert(pf)
                }
            }
            hasUnsavedChanges = false
            finish()
        }
    }

    private fun clearErrors() {
        layoutConnection.error = null
        layoutSshHost.error = null
        layoutSshPort.error = null
        layoutSshUsername.error = null
        layoutHostIp.error = null
        layoutRemotePort.error = null
        layoutLocalPort.error = null
        editName.error = null
    }

    companion object {
        const val EXTRA_FORWARD_ID = "forward_id"
        const val EXTRA_PREFILL_CONNECTION_ID = "prefill_connection_id"
    }
}
