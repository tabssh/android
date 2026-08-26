package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.ContainerHostPasswordStore
import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.SshExecRunner
import io.github.tabssh.containers.transport.TransportCapabilityDetector
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerEngineLabels
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Add/edit one container host. Mirrors the hypervisor editor: optional name,
 * an engine dropdown in the same position as the hypervisor type selector,
 * then either a saved SSH connection or a manually entered endpoint. Auth is
 * SSH only — password, SSH key, or a saved identity.
 *
 * The engine settings below it — socket path, the two remote base-path
 * overrides, optional CLI path — are all optional; a blank socket path stores
 * blank and means "probe this engine's default locations". "Test transport"
 * runs the full three-tier TransportCapabilityDetector against the current
 * form values and presents the result (permission-denied failures get the
 * engine-group remediation dialog via ContainerErrorPresenter).
 */
class ContainerHostEditActivity : TabSSHActivity() {

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
        private const val TAG = "ContainerHostEditActivity"

        /**
         * A host address must be a bare hostname/IP — no whitespace, no control
         * characters, and no scheme/path separators that would silently change
         * which endpoint the SSH layer dials.
         */
        internal fun isValidHostAddress(value: String): Boolean =
            value.isNotEmpty() && value.length <= 255 &&
                value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F } &&
                !value.contains('/') && !value.contains('\\') && !value.contains('@')

        /**
         * Remote paths are interpolated into shell commands (shell-quoted, so
         * this is defense in depth) and into stored config: require absolute,
         * single-line, control-character-free values.
         */
        internal fun isValidRemotePath(value: String): Boolean =
            value.startsWith("/") && value.length <= 4096 &&
                value.none { it.code < 0x20 || it.code == 0x7F }

        /**
         * The socket field is an OVERRIDE, not a required value: blank means
         * "probe this engine's default locations"
         * (`ContainerHost.socketCandidates()`), so blank is valid and is stored
         * blank. A typed value is accepted in the same three shapes
         * `ContainerHost.usesNetworkEndpoint()` recognises — an absolute unix
         * path, `tcp://host:port`, or `ssh://user@host` — because requiring a
         * unix path here would reject endpoints the transport supports.
         */
        internal fun isValidSocketEndpoint(value: String): Boolean {
            if (value.isEmpty()) return true
            if (value.length > 4096) return false
            // The value reaches a remote shell command and the relay dialer;
            // whitespace and controls have no legitimate use in either shape.
            if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
                return false
            }
            return when {
                // A tcp endpoint without a port has no default worth guessing.
                value.startsWith(TCP_SCHEME) ->
                    isValidAuthority(value.removePrefix(TCP_SCHEME), requirePort = true)
                // ssh:// falls back to port 22, so the port stays optional.
                value.startsWith(SSH_SCHEME) ->
                    isValidAuthority(value.removePrefix(SSH_SCHEME), requirePort = false)
                else -> value.startsWith("/")
            }
        }

        private const val TCP_SCHEME = "tcp://"
        private const val SSH_SCHEME = "ssh://"

        /**
         * `[user@]host[:port]` with no path component. The host is only checked
         * for presence and shape here — resolution is the transport's job.
         */
        private fun isValidAuthority(authority: String, requirePort: Boolean): Boolean {
            if (authority.isEmpty() || authority.contains('/')) return false
            val at = authority.lastIndexOf('@')
            if (at == 0) return false
            val hostPort = if (at > 0) authority.substring(at + 1) else authority
            // A bracketed IPv6 literal owns every colon inside the brackets, so
            // the port separator is only the one that follows the closing "]".
            val searchFrom = if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 2) return false
                close
            } else {
                0
            }
            val colon = hostPort.indexOf(':', searchFrom)
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) hostPort.substring(colon + 1) else null
            if (host.isEmpty()) return false
            if (port == null) return !requirePort
            val portNumber = port.toIntOrNull() ?: return false
            return portNumber in 1..65535
        }
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var spinnerEngine: Spinner
    private lateinit var radioMode: RadioGroup
    private lateinit var sectionSaved: View
    private lateinit var sectionCustom: View
    private lateinit var spinnerConnection: Spinner
    private lateinit var layoutCustomHost: TextInputLayout
    private lateinit var editCustomHost: TextInputEditText
    private lateinit var layoutCustomPort: TextInputLayout
    private lateinit var editCustomPort: TextInputEditText
    private lateinit var layoutCustomUsername: TextInputLayout
    private lateinit var editCustomUsername: TextInputEditText
    private lateinit var spinnerAuthType: Spinner
    private lateinit var layoutCustomPassword: TextInputLayout
    private lateinit var editCustomPassword: TextInputEditText
    private lateinit var rowCustomKey: View
    private lateinit var spinnerCustomKey: Spinner
    private lateinit var rowCustomIdentity: View
    private lateinit var spinnerCustomIdentity: Spinner
    private lateinit var layoutSocketPath: TextInputLayout
    private lateinit var editSocketPath: TextInputEditText
    private lateinit var layoutComposeBase: TextInputLayout
    private lateinit var editComposeBase: TextInputEditText
    private lateinit var layoutRunBase: TextInputLayout
    private lateinit var editRunBase: TextInputEditText
    private lateinit var layoutCliPath: TextInputLayout
    private lateinit var editCliPath: TextInputEditText
    private lateinit var switchUpdateCheck: SwitchMaterial
    private lateinit var layoutUpdateInterval: TextInputLayout
    private lateinit var editUpdateInterval: TextInputEditText
    private lateinit var editNotes: TextInputEditText
    private lateinit var buttonTestTransport: MaterialButton
    private lateinit var progressTest: ProgressBar
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonCancel: MaterialButton

    private var connections: List<ConnectionProfile> = emptyList()
    private var keys: List<StoredKey> = emptyList()
    private var identities: List<Identity> = emptyList()
    private var existingHost: ContainerHost? = null

    /** Guards the save path — a double tap would otherwise insert the host twice. */
    private var saving = false

    /** False once the activity can no longer host a dialog or touch its views. */
    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    private val hostId: Long? by lazy {
        intent.getLongExtra(EXTRA_HOST_ID, -1L).takeIf { it != -1L }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container_host_edit)

        app = tabSSHApp

        setupViews()
        setupToolbar()
        setupClickListeners()
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
        editCustomHost.addTextChangedListener(watcher)
        editCustomPort.addTextChangedListener(watcher)
        editCustomUsername.addTextChangedListener(watcher)
        editCustomPassword.addTextChangedListener(watcher)
        editSocketPath.addTextChangedListener(watcher)
        editComposeBase.addTextChangedListener(watcher)
        editRunBase.addTextChangedListener(watcher)
        editCliPath.addTextChangedListener(watcher)
        editUpdateInterval.addTextChangedListener(watcher)
        editNotes.addTextChangedListener(watcher)
        radioMode.setOnCheckedChangeListener { _, checkedId ->
            hasUnsavedChanges = true
            val custom = checkedId == R.id.radio_mode_custom
            sectionSaved.visibility = if (custom) View.GONE else View.VISIBLE
            sectionCustom.visibility = if (custom) View.VISIBLE else View.GONE
        }
        switchUpdateCheck.setOnCheckedChangeListener { _, checked ->
            hasUnsavedChanges = true
            layoutUpdateInterval.visibility = if (checked) View.VISIBLE else View.GONE
        }
        spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                hasUnsavedChanges = true
                applyEngineHints(engineValues.getOrNull(pos) ?: ContainerEngine.DEFAULT)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        spinnerConnection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { hasUnsavedChanges = true }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        spinnerAuthType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                hasUnsavedChanges = true
                val auth = authTypeValues.getOrNull(pos) ?: "password"
                layoutCustomPassword.visibility =
                    if (auth == "password") View.VISIBLE else View.GONE
                rowCustomKey.visibility = if (auth == "key") View.VISIBLE else View.GONE
                rowCustomIdentity.visibility =
                    if (auth == "identity") View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        spinnerCustomKey.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { hasUnsavedChanges = true }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        spinnerCustomIdentity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { hasUnsavedChanges = true }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        enableUnsavedChangesGuard()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        spinnerEngine = findViewById(R.id.spinner_engine)
        radioMode = findViewById(R.id.radio_mode)
        sectionSaved = findViewById(R.id.section_saved)
        sectionCustom = findViewById(R.id.section_custom)
        spinnerConnection = findViewById(R.id.spinner_connection)
        layoutCustomHost = findViewById(R.id.layout_custom_host)
        editCustomHost = findViewById(R.id.edit_custom_host)
        layoutCustomPort = findViewById(R.id.layout_custom_port)
        editCustomPort = findViewById(R.id.edit_custom_port)
        layoutCustomUsername = findViewById(R.id.layout_custom_username)
        editCustomUsername = findViewById(R.id.edit_custom_username)
        spinnerAuthType = findViewById(R.id.spinner_auth_type)
        layoutCustomPassword = findViewById(R.id.layout_custom_password)
        editCustomPassword = findViewById(R.id.edit_custom_password)
        rowCustomKey = findViewById(R.id.row_custom_key)
        spinnerCustomKey = findViewById(R.id.spinner_custom_key)
        rowCustomIdentity = findViewById(R.id.row_custom_identity)
        spinnerCustomIdentity = findViewById(R.id.spinner_custom_identity)
        layoutSocketPath = findViewById(R.id.layout_socket_path)
        editSocketPath = findViewById(R.id.edit_socket_path)
        layoutComposeBase = findViewById(R.id.layout_compose_base)
        editComposeBase = findViewById(R.id.edit_compose_base)
        layoutRunBase = findViewById(R.id.layout_run_base)
        editRunBase = findViewById(R.id.edit_run_base)
        layoutCliPath = findViewById(R.id.layout_cli_path)
        editCliPath = findViewById(R.id.edit_cli_path)
        switchUpdateCheck = findViewById(R.id.switch_update_check)
        layoutUpdateInterval = findViewById(R.id.layout_update_interval)
        editUpdateInterval = findViewById(R.id.edit_update_interval)
        // switchUpdateCheck's checked-change listener (visibility toggle plus
        // the dirty-flag flip) is installed in setupUnsavedChangesGuard().
        editNotes = findViewById(R.id.edit_notes)
        buttonTestTransport = findViewById(R.id.button_test_transport)
        progressTest = findViewById(R.id.progress_test)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (hostId != null) R.string.activity_label_edit_container_host
            else R.string.container_host_edit_title_add
        )
    }

    /** "password", "key", or "identity" for the auth-type spinner position. */
    private val authTypeValues = listOf("password", "key", "identity")

    /**
     * Engines in dropdown order. [ContainerEngine] declares them in exactly the
     * order the UI shows — Docker first and preselected, then Incus, Podman,
     * LXC/LXD — so the enum order is the single source of truth here.
     */
    private val engineValues = ContainerEngine.entries.toList()

    /** The engine the form currently describes. */
    private fun selectedEngine(): ContainerEngine =
        engineValues.getOrNull(spinnerEngine.selectedItemPosition) ?: ContainerEngine.DEFAULT

    /**
     * Point the optional socket and CLI fields at the selected engine's own
     * defaults. Only the placeholder and helper text change — an empty field
     * stays empty, because blank is what stores "auto-detect for this engine".
     */
    private fun applyEngineHints(engine: ContainerEngine) {
        val defaultSocket = engine.defaultSocketPaths.first()
        layoutSocketPath.placeholderText = defaultSocket
        layoutSocketPath.helperText =
            getString(R.string.container_host_socket_auto_helper, defaultSocket)
        layoutCliPath.placeholderText = engine.cliBinary
        layoutCliPath.helperText =
            getString(R.string.container_host_cli_auto_helper, engine.cliBinary)
    }

    private fun setupClickListeners() {
        buttonCancel.setOnClickListener { confirmDiscardIfNeeded { finish() } }
        buttonSave.setOnClickListener { saveHost() }
        buttonTestTransport.setOnClickListener { testTransport() }
        // radioMode's checked-change listener (and spinnerEngine/spinnerAuthType's
        // item-selected listeners) are installed in setupUnsavedChangesGuard()
        // instead, merged with the dirty-flag flip — a RadioGroup/Spinner only
        // keeps one listener, so setting it here would be silently overwritten.
    }

    private fun isCustomMode(): Boolean =
        radioMode.checkedRadioButtonId == R.id.radio_mode_custom

    /** Clears every field-level Material error before re-validating the form. */
    private fun clearFieldErrors() {
        layoutCustomHost.error = null
        layoutCustomUsername.error = null
        layoutCustomPort.error = null
        layoutSocketPath.error = null
        layoutComposeBase.error = null
        layoutRunBase.error = null
        layoutCliPath.error = null
    }

    private fun loadData() {
        lifecycleScope.launch {
            val (lists, host) = withContext(Dispatchers.IO) {
                val conns = app.database.connectionDao().getAllConnectionsList()
                val keyList = app.database.keyDao().getAllKeysList()
                val idList = app.database.identityDao().getAllIdentitiesList()
                val host = hostId?.let { app.database.containerHostDao().getById(it) }
                Triple(conns, keyList, idList) to host
            }
            connections = lists.first
            keys = lists.second
            identities = lists.third
            existingHost = host

            spinnerConnection.adapter = ArrayAdapter(
                this@ContainerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                connections.map { it.name }
            )
            spinnerEngine.adapter = ArrayAdapter(
                this@ContainerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                engineValues.map { getString(ContainerEngineLabels.engineName(it)) }
            )
            spinnerAuthType.adapter = ArrayAdapter(
                this@ContainerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.password_hint),
                    getString(R.string.route_auth_key),
                    getString(R.string.container_host_identity_desc)
                )
            )
            spinnerCustomKey.adapter = ArrayAdapter(
                this@ContainerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                keys.map { it.name }
            )
            spinnerCustomIdentity.adapter = ArrayAdapter(
                this@ContainerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                identities.map { it.name }
            )

            // Entity defaults populate the form for a new host so the user
            // sees (and can override) the /srv/$USER/... base paths. The socket
            // field is deliberately left as the entity has it — blank on a new
            // host — so the engine's own defaults are what gets probed.
            val defaults = host ?: ContainerHost(name = "")
            editSocketPath.setText(defaults.socketPath)
            editComposeBase.setText(defaults.composeBasePath)
            editRunBase.setText(defaults.runConfigBasePath)
            editCustomPort.setText((defaults.customPort ?: 22).toString())

            // Selecting the engine fires the spinner listener, which is what
            // puts this engine's default socket and binary in the field hints.
            val engineIndex = engineValues.indexOf(defaults.engineType())
            spinnerEngine.setSelection(if (engineIndex >= 0) engineIndex else 0)
            applyEngineHints(defaults.engineType())

            if (host == null) {
                // No DB record to populate — the defaults set above are all
                // there is, so the form starts clean.
                hasUnsavedChanges = false
                return@launch
            }

            editName.setText(host.name)
            editCliPath.setText(host.engineCliPath ?: "")
            switchUpdateCheck.isChecked = host.updateCheckEnabled
            layoutUpdateInterval.visibility =
                if (host.updateCheckEnabled) View.VISIBLE else View.GONE
            editUpdateInterval.setText(host.updateCheckIntervalHours?.toString() ?: "")
            editNotes.setText(host.notes ?: "")
            if (host.usesCustomEndpoint()) {
                radioMode.check(R.id.radio_mode_custom)
                editCustomHost.setText(host.customHost)
                editCustomUsername.setText(host.customUsername ?: "")
                val authIdx = authTypeValues.indexOf(host.customAuthType ?: "password")
                if (authIdx >= 0) spinnerAuthType.setSelection(authIdx)
                // A stored password is never echoed back — blank means "keep".
                layoutCustomPassword.helperText =
                    getString(R.string.container_host_password_keep_helper)
                val keyIdx = keys.indexOfFirst { it.keyId == host.customKeyId }
                if (keyIdx >= 0) spinnerCustomKey.setSelection(keyIdx)
                val identIdx = identities.indexOfFirst { it.id == host.customIdentityId }
                if (identIdx >= 0) spinnerCustomIdentity.setSelection(identIdx)
            } else {
                val idx = connections.indexOfFirst { it.id == host.linkedConnectionId }
                if (idx >= 0) spinnerConnection.setSelection(idx)
            }

            // Every field/spinner set above flips the dirty-flag listeners
            // installed in setupUnsavedChangesGuard() — DB-driven population
            // is not a user edit, so clear the flag once population is done.
            hasUnsavedChanges = false
        }
    }

    /**
     * The ContainerHost the current form describes, or null when invalid.
     * The name is optional — it defaults to the linked connection's name
     * (saved mode) or the endpoint hostname (custom mode).
     */
    private fun hostFromForm(): ContainerHost? {
        clearFieldErrors()
        val typedName = editName.text?.toString()?.trim().orEmpty()
        val entityDefaults = ContainerHost(name = "")
        val base = existingHost ?: entityDefaults

        val endpoint: ContainerHost = if (isCustomMode()) {
            val hostAddr = editCustomHost.text?.toString()?.trim().orEmpty()
            if (!isValidHostAddress(hostAddr)) {
                layoutCustomHost.error = getString(R.string.container_host_error_custom_host)
                return null
            }
            val username = editCustomUsername.text?.toString()?.trim().orEmpty()
            // A username with whitespace or controls would break the SSH auth
            // request; reject it here rather than failing opaquely on connect.
            if (username.isEmpty() ||
                username.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
            ) {
                layoutCustomUsername.error = getString(R.string.container_host_error_custom_username)
                return null
            }
            // Out-of-range ports are rejected, never silently clamped — a typo
            // like "655350" must not silently become port 65535.
            val portText = editCustomPort.text?.toString()?.trim().orEmpty()
            val port = portText.toIntOrNull()
            if (portText.isNotEmpty() && (port == null || port !in 1..65535)) {
                layoutCustomPort.error = getString(R.string.error_invalid_port)
                return null
            }
            val auth = authTypeValues.getOrNull(spinnerAuthType.selectedItemPosition)
                ?: "password"
            val key = if (auth == "key") {
                keys.getOrNull(spinnerCustomKey.selectedItemPosition) ?: run {
                    showError(getString(R.string.container_host_error_no_keys))
                    return null
                }
            } else null
            val identity = if (auth == "identity") {
                identities.getOrNull(spinnerCustomIdentity.selectedItemPosition) ?: run {
                    showError(getString(R.string.container_host_error_no_identities))
                    return null
                }
            } else null
            base.copy(
                name = typedName.ifEmpty { hostAddr },
                linkedConnectionId = null,
                customHost = hostAddr,
                customPort = port ?: 22,
                customUsername = username,
                customAuthType = auth,
                customKeyId = key?.keyId,
                customIdentityId = identity?.id
            )
        } else {
            val connection = connections.getOrNull(spinnerConnection.selectedItemPosition)
            if (connection == null) {
                showError(getString(R.string.container_host_error_connection))
                return null
            }
            base.copy(
                name = typedName.ifEmpty { connection.name },
                linkedConnectionId = connection.id,
                customHost = null,
                customPort = null,
                customUsername = null,
                customAuthType = null,
                customKeyId = null,
                customIdentityId = null
            )
        }

        // Blank is the normal case and is stored blank — it is what tells
        // socketCandidates() to probe the engine's own default locations.
        val socketPath = editSocketPath.text?.toString()?.trim().orEmpty()
        if (!isValidSocketEndpoint(socketPath)) {
            layoutSocketPath.error = getString(R.string.container_host_error_socket)
            return null
        }
        val composeBase = editComposeBase.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entityDefaults.composeBasePath
        val runBase = editRunBase.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entityDefaults.runConfigBasePath
        // Both are interpolated into remote shell commands and into stored
        // config — reject anything that is not a plain absolute path.
        if (!isValidRemotePath(composeBase)) {
            layoutComposeBase.error = getString(R.string.container_host_error_path)
            return null
        }
        if (!isValidRemotePath(runBase)) {
            layoutRunBase.error = getString(R.string.container_host_error_path)
            return null
        }
        val cliPath = editCliPath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // The CLI path may be a bare command name ("docker"), so only reject
        // whitespace and control characters rather than requiring an absolute path.
        if (cliPath != null &&
            cliPath.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
        ) {
            layoutCliPath.error = getString(R.string.container_host_error_path)
            return null
        }

        return endpoint.copy(
            engine = selectedEngine().id,
            socketPath = socketPath,
            composeBasePath = composeBase,
            runConfigBasePath = runBase,
            engineCliPath = cliPath,
            updateCheckEnabled = switchUpdateCheck.isChecked,
            updateCheckIntervalHours = editUpdateInterval.text?.toString()?.trim()
                ?.toIntOrNull()?.takeIf { it > 0 },
            notes = editNotes.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            modifiedAt = System.currentTimeMillis()
        )
    }

    private fun saveHost() {
        // Without this the second tap inserts a duplicate row: the DAO insert
        // lives behind a suspension point, so isEnabled alone is not enough.
        if (saving) return
        val host = hostFromForm() ?: return
        val password = editCustomPassword.text?.toString().orEmpty()
        saving = true
        buttonSave.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val savedId = if (existingHost != null) {
                        app.database.containerHostDao().update(host)
                        host.id
                    } else {
                        app.database.containerHostDao().insert(host)
                    }
                    if (host.customAuthType == "password" && password.isNotEmpty()) {
                        ContainerHostPasswordStore.store(
                            this@ContainerHostEditActivity, savedId, password
                        )
                    } else if (host.customAuthType != "password") {
                        // Mode or auth switched away from password — drop the
                        // stored secret so no orphaned Keystore entry remains.
                        ContainerHostPasswordStore.clear(this@ContainerHostEditActivity, savedId)
                    }
                    // A custom endpoint's ephemeral profile is cached by the
                    // session managers — drop any stale session so the next
                    // acquire uses the edited endpoint.
                    ContainerSessionManager.release(app, savedId)
                }
                if (!isAlive()) return@launch
                Toast.makeText(
                    this@ContainerHostEditActivity,
                    getString(R.string.container_host_saved),
                    Toast.LENGTH_SHORT
                ).show()
                hasUnsavedChanges = false
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save container host", e)
                if (!isAlive()) return@launch
                showError(
                    ContainerText.display(e.message).ifEmpty {
                        getString(R.string.container_error_title)
                    }
                )
            } finally {
                saving = false
                if (isAlive()) buttonSave.isEnabled = true
            }
        }
    }

    /**
     * Run three-tier detection against the CURRENT form values (the host does
     * not have to be saved first). The detected transport is closed right
     * after the probe — screens acquire their own via ContainerSessionManager.
     */
    private fun testTransport() {
        val host = hostFromForm() ?: return
        val password = editCustomPassword.text?.toString().orEmpty()

        buttonTestTransport.isEnabled = false
        progressTest.visibility = View.VISIBLE
        Toast.makeText(this, R.string.container_testing_transport, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    probeTransport(host, password)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connectForMonitoring / Keystore access throw on a dead network
                // or a locked keystore — report instead of crashing the editor.
                Logger.e(TAG, "Transport probe failed", e)
                ContainerResult.Error(
                    ContainerText.display(e.message).ifEmpty {
                        getString(R.string.container_error_title)
                    }
                )
            }
            if (!isAlive()) return@launch
            buttonTestTransport.isEnabled = true
            progressTest.visibility = View.GONE
            when (result) {
                is ContainerResult.Success -> MaterialAlertDialogBuilder(this@ContainerHostEditActivity)
                    .setTitle(R.string.container_transport_result_title)
                    .setMessage(getString(R.string.container_transport_ok, result.value.mode))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                else -> ContainerErrorPresenter.present(this@ContainerHostEditActivity, result)
            }
        }
    }

    /**
     * The blocking half of [testTransport]: resolve a profile, connect, run the
     * three-tier detection, and always drop a temporary Keystore alias stored
     * for a not-yet-saved host — even when the probe throws.
     */
    private suspend fun probeTransport(
        host: ContainerHost,
        password: String
    ): ContainerResult<TransportCapabilityDetector.DetectedTransport> {
        var storedTemporaryPassword = false
        try {
            val profile = if (host.usesCustomEndpoint()) {
                // The probe needs the typed password in the Keystore (the
                // SSH layer reads it by profile id). For a not-yet-saved
                // host (id 0) the temporary alias is cleared right after.
                if (host.customAuthType == "password" && password.isNotEmpty()) {
                    ContainerHostPasswordStore.store(
                        this@ContainerHostEditActivity, host.id, password
                    )
                    storedTemporaryPassword = existingHost == null
                }
                ContainerSessionManager.resolveCustomProfile(app, host)
            } else {
                connections.getOrNull(spinnerConnection.selectedItemPosition)
            }
            if (profile == null) {
                return ContainerResult.Error(getString(R.string.container_host_error_connection))
            }
            // connectForMonitoring: a connection test is plumbing, not a user
            // session — it must not show up as an active SSH session.
            val ssh = app.sshSessionManager.getConnection(profile.id)
                ?.takeIf { it.isConnected() }
                ?: app.sshSessionManager.connectForMonitoring(profile)
            if (ssh == null) {
                return ContainerResult.TransportUnavailable(
                    getString(R.string.container_msg_ssh_unavailable),
                    profile.name
                )
            }
            val runner = SshExecRunner { ssh.jschSession() }
            val detector = TransportCapabilityDetector(app.database.containerHostDao())
            val probe = detector.detect(host, runner, force = true)
            // The probe transport is single-use — close its relay.
            probe.valueOrNull()?.transport?.close()
            return probe
        } finally {
            // A throw between store() and here would otherwise leave the typed
            // password in the Keystore under an unsaved host's id.
            if (storedTemporaryPassword) {
                ContainerHostPasswordStore.clear(this@ContainerHostEditActivity, host.id)
            }
        }
    }
}
