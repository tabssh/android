package io.github.tabssh.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.protocols.tor.TorNativeClient
import io.github.tabssh.storage.database.entities.NetworkRoute
import io.github.tabssh.storage.database.entities.NetworkRouteType
import io.github.tabssh.storage.database.entities.StoredKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Add / edit screen for a single reusable [NetworkRoute] (proxy or SSH jump
 * host). The visible endpoint fields adapt to the selected [NetworkRouteType]
 * and the two presets (Orbot, built-in Tor).
 *
 * Secrets never live on a route (PART 6): there is no password field. A jump
 * host authenticates with either a saved SSH key or the target's own password
 * supplied at connect time.
 */
class NetworkRouteEditActivity : TabSSHActivity() {

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    private val app: TabSSHApplication
        get() = tabSSHApp

    private lateinit var editName: TextInputEditText
    private lateinit var spinnerType: MaterialAutoCompleteTextView

    private lateinit var chipOrbot: Chip
    private lateinit var chipTor: Chip
    private lateinit var textTorDesc: View

    private lateinit var layoutHost: TextInputLayout
    private lateinit var editHost: TextInputEditText
    private lateinit var layoutPort: TextInputLayout
    private lateinit var editPort: TextInputEditText
    private lateinit var layoutUsername: TextInputLayout
    private lateinit var editUsername: TextInputEditText
    private lateinit var layoutJumpAuth: View
    private lateinit var spinnerAuthType: MaterialAutoCompleteTextView
    private lateinit var layoutKey: TextInputLayout
    private lateinit var spinnerKey: MaterialAutoCompleteTextView

    private lateinit var switchEnabled: MaterialSwitch

    // Ordered to match the type dropdown.
    private val types = listOf(
        NetworkRouteType.PROXY_HTTP,
        NetworkRouteType.PROXY_SOCKS4,
        NetworkRouteType.PROXY_SOCKS5,
        NetworkRouteType.JUMP_HOST
    )
    private var selectedType: NetworkRouteType = NetworkRouteType.PROXY_SOCKS5
    private var builtInTor: Boolean = false
    private var jumpAuthIsKey: Boolean = false

    private var availableKeys: List<StoredKey> = emptyList()
    private var selectedKeyId: String? = null

    private var editingId: String? = null
    private var existing: NetworkRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_route_edit)

        editingId = intent.getStringExtra(EXTRA_ROUTE_ID)

        bindViews()
        setupToolbar()
        setupTypeSpinner()
        setupPresetChips()
        setupAuthTypeSpinner()
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
        editHost.addTextChangedListener(watcher)
        editPort.addTextChangedListener(watcher)
        editUsername.addTextChangedListener(watcher)
        switchEnabled.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }

        enableUnsavedChangesGuard()
    }

    private fun bindViews() {
        editName = findViewById(R.id.edit_name)
        spinnerType = findViewById(R.id.spinner_type)

        chipOrbot = findViewById(R.id.chip_preset_orbot)
        chipTor = findViewById(R.id.chip_preset_tor)
        textTorDesc = findViewById(R.id.text_preset_tor_desc)

        layoutHost = findViewById(R.id.layout_host)
        editHost = findViewById(R.id.edit_host)
        layoutPort = findViewById(R.id.layout_port)
        editPort = findViewById(R.id.edit_port)
        layoutUsername = findViewById(R.id.layout_username)
        editUsername = findViewById(R.id.edit_username)
        layoutJumpAuth = findViewById(R.id.layout_jump_auth)
        spinnerAuthType = findViewById(R.id.spinner_auth_type)
        layoutKey = findViewById(R.id.layout_key)
        spinnerKey = findViewById(R.id.spinner_key)

        switchEnabled = findViewById(R.id.switch_enabled)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = getString(
                if (editingId != null) R.string.route_edit_title_edit
                else R.string.route_edit_title_new
            )
        }
    }

    private fun setupTypeSpinner() {
        val labels = types.map { getString(typeLabelRes(it)) }
        spinnerType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        spinnerType.setOnItemClickListener { _, _, position, _ ->
            // A manual type choice always means "not the built-in Tor preset".
            hasUnsavedChanges = true
            builtInTor = false
            applyType(types[position])
        }
    }

    private fun typeLabelRes(type: NetworkRouteType): Int = when (type) {
        NetworkRouteType.PROXY_HTTP -> R.string.route_type_proxy_http
        NetworkRouteType.PROXY_SOCKS4 -> R.string.route_type_proxy_socks4
        NetworkRouteType.PROXY_SOCKS5 -> R.string.route_type_proxy_socks5
        NetworkRouteType.JUMP_HOST -> R.string.route_type_jump_host
    }

    /** Reflect [type] in the dropdown and show the fields that type uses. */
    private fun applyType(type: NetworkRouteType) {
        selectedType = type
        spinnerType.setText(getString(typeLabelRes(type)), false)
        if (type == NetworkRouteType.JUMP_HOST && editPort.text.isNullOrBlank()) {
            editPort.setText("22")
        }
        updateEndpointVisibility()
    }

    private fun setupPresetChips() {
        chipOrbot.setOnClickListener {
            hasUnsavedChanges = true
            builtInTor = false
            applyType(NetworkRouteType.PROXY_SOCKS5)
            editHost.setText("127.0.0.1")
            editPort.setText(NetworkRoute.ORBOT_SOCKS_PORT.toString())
        }
        chipTor.setOnClickListener {
            hasUnsavedChanges = true
            builtInTor = true
            applyType(NetworkRouteType.PROXY_SOCKS5)
        }
    }

    private fun setupAuthTypeSpinner() {
        val labels = listOf(
            getString(R.string.password_hint),
            getString(R.string.route_auth_key)
        )
        spinnerAuthType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        spinnerAuthType.setText(labels[0], false)
        spinnerAuthType.setOnItemClickListener { _, _, position, _ ->
            hasUnsavedChanges = true
            jumpAuthIsKey = position == 1
            updateKeyVisibility()
        }
    }

    private fun setupKeySpinner() {
        val labels = listOf(getString(R.string.route_key_select_placeholder)) +
            availableKeys.map { it.getDisplayName() }
        spinnerKey.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        spinnerKey.setOnItemClickListener { _, _, position, _ ->
            hasUnsavedChanges = true
            selectedKeyId = if (position == 0) null else availableKeys[position - 1].keyId
            spinnerKey.setText(labels[position], false)
        }
    }

    private fun updateEndpointVisibility() {
        val isJump = selectedType == NetworkRouteType.JUMP_HOST
        // Built-in Tor binds its own loopback SOCKS listener, so there is nothing
        // for the user to enter.
        val showEndpoint = !builtInTor
        layoutHost.visibility = if (showEndpoint) View.VISIBLE else View.GONE
        layoutPort.visibility = if (showEndpoint) View.VISIBLE else View.GONE
        layoutUsername.visibility = if (showEndpoint) View.VISIBLE else View.GONE
        // Username is required for a jump host, optional for a proxy.
        layoutUsername.helperText =
            if (isJump) null else getString(R.string.route_username_optional_helper)
        layoutJumpAuth.visibility = if (isJump && showEndpoint) View.VISIBLE else View.GONE
        textTorDesc.visibility = if (builtInTor) View.VISIBLE else View.GONE
        updateKeyVisibility()
        updatePresetVisibility()
    }

    /**
     * Both bundled presets (Orbot, built-in Tor) only apply a SOCKS5 proxy
     * configuration — showing them for HTTP/SOCKS4/jump-host types invited
     * taps that silently discarded the user's selected route type. Show them
     * only when the current type is the one they actually configure.
     */
    private fun updatePresetVisibility() {
        val applicable = selectedType == NetworkRouteType.PROXY_SOCKS5
        // The built-in Tor preset additionally only makes sense when a
        // bundled tor binary is actually present on this device/ABI.
        val torAvailable = applicable && TorNativeClient.isAvailable(this)
        chipOrbot.visibility = if (applicable) View.VISIBLE else View.GONE
        chipTor.visibility = if (torAvailable) View.VISIBLE else View.GONE
    }

    private fun updateKeyVisibility() {
        val show = selectedType == NetworkRouteType.JUMP_HOST && !builtInTor && jumpAuthIsKey
        layoutKey.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setupButtons() {
        findViewById<View>(R.id.btn_cancel).setOnClickListener { confirmDiscardIfNeeded { finish() } }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun loadData() {
        lifecycleScope.launch {
            availableKeys = withContext(Dispatchers.IO) { app.keyStorage.listStoredKeys() }
            setupKeySpinner()

            val id = editingId
            val loaded = if (id != null) {
                withContext(Dispatchers.IO) { app.database.networkRouteDao().getById(id) }
            } else {
                null
            }
            existing = loaded
            if (loaded != null) populate(loaded) else applyType(NetworkRouteType.PROXY_SOCKS5)
            // Field/spinner changes above flip the dirty-flag listeners
            // installed in setupUnsavedChangesGuard() — this is DB-driven
            // (or default) population, not a user edit, so clear the flag.
            hasUnsavedChanges = false
        }
    }

    private fun populate(route: NetworkRoute) {
        editName.setText(route.name)
        builtInTor = route.builtInTor
        editHost.setText(route.host.orEmpty())
        if (route.port > 0) editPort.setText(route.port.toString())
        editUsername.setText(route.username.orEmpty())

        jumpAuthIsKey = route.authType.equals("KEY", ignoreCase = true)
        spinnerAuthType.setText(
            getString(if (jumpAuthIsKey) R.string.route_auth_key else R.string.password_hint),
            false
        )
        selectedKeyId = route.keyId
        route.keyId?.let { keyId ->
            val index = availableKeys.indexOfFirst { it.keyId == keyId }
            if (index >= 0) {
                spinnerKey.setText(availableKeys[index].getDisplayName(), false)
            }
        }

        switchEnabled.isChecked = route.enabled
        applyType(route.routeType)
    }

    private fun save() {
        clearErrors()

        val name = editName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            editName.error = getString(R.string.route_error_name)
            editName.requestFocus()
            return
        }

        val isJump = selectedType == NetworkRouteType.JUMP_HOST

        var host: String? = null
        var port = 0
        var username: String? = null
        var authType: String? = null
        var keyId: String? = null

        if (builtInTor) {
            // Loopback SOCKS5 into the bundled tor process; port resolved at connect.
            host = "127.0.0.1"
            port = 0
        } else {
            host = editHost.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                layoutHost.error = getString(R.string.route_error_host)
                editHost.requestFocus()
                return
            }
            val portValue = editPort.text?.toString()?.trim()?.toIntOrNull()
            if (portValue == null || portValue !in 1..65535) {
                layoutPort.error = getString(R.string.route_error_port_range)
                editPort.requestFocus()
                return
            }
            port = portValue

            username = editUsername.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (isJump) {
                if (username == null) {
                    layoutUsername.error = getString(R.string.route_error_username)
                    editUsername.requestFocus()
                    return
                }
                if (jumpAuthIsKey) {
                    authType = "KEY"
                    if (selectedKeyId == null) {
                        layoutKey.error = getString(R.string.route_error_key)
                        return
                    }
                    keyId = selectedKeyId
                } else {
                    authType = "PASSWORD"
                }
            }
        }

        val base = existing ?: NetworkRoute()
        val result = base.copy(
            name = name,
            type = selectedType.name,
            host = host,
            port = port,
            username = username,
            authType = authType,
            keyId = keyId,
            builtInTor = builtInTor,
            enabled = switchEnabled.isChecked,
            modifiedAt = System.currentTimeMillis()
        )
        persist(result)
    }

    private fun persist(route: NetworkRoute) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (existing != null) {
                    app.database.networkRouteDao().update(route)
                } else {
                    app.database.networkRouteDao().insert(route)
                }
            }
            hasUnsavedChanges = false
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_ROUTE_ID, route.id))
            finish()
        }
    }

    private fun clearErrors() {
        editName.error = null
        layoutHost.error = null
        layoutPort.error = null
        layoutUsername.error = null
        layoutKey.error = null
    }

    companion object {
        const val EXTRA_ROUTE_ID = "route_id"
        const val RESULT_ROUTE_ID = "result_route_id"
    }
}
