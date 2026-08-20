package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.DockerHostPasswordStore
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.docker.transport.TransportCapabilityDetector
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.utils.DockerText
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add/edit one Docker host: name, linked SSH connection,
 * socket path, the two remote base-path overrides (defaults from the entity),
 * optional CLI path and notes — plus "Test transport", which runs the full
 * three-tier TransportCapabilityDetector against the current form values and
 * presents the result (permission-denied failures get the docker-group
 * remediation dialog via DockerErrorPresenter).
 */
class DockerHostEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
        private const val TAG = "DockerHostEditActivity"

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
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var radioMode: RadioGroup
    private lateinit var sectionSaved: View
    private lateinit var sectionCustom: View
    private lateinit var spinnerConnection: Spinner
    private lateinit var editCustomHost: TextInputEditText
    private lateinit var editCustomPort: TextInputEditText
    private lateinit var editCustomUsername: TextInputEditText
    private lateinit var spinnerAuthType: Spinner
    private lateinit var layoutCustomPassword: TextInputLayout
    private lateinit var editCustomPassword: TextInputEditText
    private lateinit var rowCustomKey: View
    private lateinit var spinnerCustomKey: Spinner
    private lateinit var rowCustomIdentity: View
    private lateinit var spinnerCustomIdentity: Spinner
    private lateinit var editSocketPath: TextInputEditText
    private lateinit var editComposeBase: TextInputEditText
    private lateinit var editRunBase: TextInputEditText
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
    private var existingHost: DockerHost? = null

    /** Guards the save path — a double tap would otherwise insert the host twice. */
    private var saving = false

    /** False once the activity can no longer host a dialog or touch its views. */
    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    private val hostId: Long? by lazy {
        intent.getLongExtra(EXTRA_HOST_ID, -1L).takeIf { it != -1L }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_docker_host_edit)

        app = application as TabSSHApplication

        setupViews()
        setupToolbar()
        setupClickListeners()
        loadData()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        radioMode = findViewById(R.id.radio_mode)
        sectionSaved = findViewById(R.id.section_saved)
        sectionCustom = findViewById(R.id.section_custom)
        spinnerConnection = findViewById(R.id.spinner_connection)
        editCustomHost = findViewById(R.id.edit_custom_host)
        editCustomPort = findViewById(R.id.edit_custom_port)
        editCustomUsername = findViewById(R.id.edit_custom_username)
        spinnerAuthType = findViewById(R.id.spinner_auth_type)
        layoutCustomPassword = findViewById(R.id.layout_custom_password)
        editCustomPassword = findViewById(R.id.edit_custom_password)
        rowCustomKey = findViewById(R.id.row_custom_key)
        spinnerCustomKey = findViewById(R.id.spinner_custom_key)
        rowCustomIdentity = findViewById(R.id.row_custom_identity)
        spinnerCustomIdentity = findViewById(R.id.spinner_custom_identity)
        editSocketPath = findViewById(R.id.edit_socket_path)
        editComposeBase = findViewById(R.id.edit_compose_base)
        editRunBase = findViewById(R.id.edit_run_base)
        editCliPath = findViewById(R.id.edit_cli_path)
        switchUpdateCheck = findViewById(R.id.switch_update_check)
        layoutUpdateInterval = findViewById(R.id.layout_update_interval)
        editUpdateInterval = findViewById(R.id.edit_update_interval)
        switchUpdateCheck.setOnCheckedChangeListener { _, checked ->
            layoutUpdateInterval.visibility = if (checked) View.VISIBLE else View.GONE
        }
        editNotes = findViewById(R.id.edit_notes)
        buttonTestTransport = findViewById(R.id.button_test_transport)
        progressTest = findViewById(R.id.progress_test)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(
            if (hostId != null) R.string.docker_host_edit_title_edit
            else R.string.docker_host_edit_title_add
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** "password", "key", or "identity" for the auth-type spinner position. */
    private val authTypeValues = listOf("password", "key", "identity")

    private fun setupClickListeners() {
        buttonCancel.setOnClickListener { finish() }
        buttonSave.setOnClickListener { saveHost() }
        buttonTestTransport.setOnClickListener { testTransport() }
        radioMode.setOnCheckedChangeListener { _, checkedId ->
            val custom = checkedId == R.id.radio_mode_custom
            sectionSaved.visibility = if (custom) View.GONE else View.VISIBLE
            sectionCustom.visibility = if (custom) View.VISIBLE else View.GONE
        }
        spinnerAuthType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val auth = authTypeValues.getOrNull(pos) ?: "password"
                layoutCustomPassword.visibility =
                    if (auth == "password") View.VISIBLE else View.GONE
                rowCustomKey.visibility = if (auth == "key") View.VISIBLE else View.GONE
                rowCustomIdentity.visibility =
                    if (auth == "identity") View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun isCustomMode(): Boolean =
        radioMode.checkedRadioButtonId == R.id.radio_mode_custom

    private fun loadData() {
        lifecycleScope.launch {
            val (lists, host) = withContext(Dispatchers.IO) {
                val conns = app.database.connectionDao().getAllConnectionsList()
                val keyList = app.database.keyDao().getAllKeysList()
                val idList = app.database.identityDao().getAllIdentitiesList()
                val host = hostId?.let { app.database.dockerHostDao().getById(it) }
                Triple(conns, keyList, idList) to host
            }
            connections = lists.first
            keys = lists.second
            identities = lists.third
            existingHost = host

            spinnerConnection.adapter = ArrayAdapter(
                this@DockerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                connections.map { it.name }
            )
            spinnerAuthType.adapter = ArrayAdapter(
                this@DockerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.docker_auth_password),
                    getString(R.string.docker_auth_key),
                    getString(R.string.docker_auth_identity)
                )
            )
            spinnerCustomKey.adapter = ArrayAdapter(
                this@DockerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                keys.map { it.name }
            )
            spinnerCustomIdentity.adapter = ArrayAdapter(
                this@DockerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                identities.map { it.name }
            )

            // Entity defaults populate the form for a new host so the user
            // sees (and can override) the /srv/$USER/... base paths.
            val defaults = host ?: DockerHost(name = "")
            editSocketPath.setText(defaults.socketPath)
            editComposeBase.setText(defaults.composeBasePath)
            editRunBase.setText(defaults.runConfigBasePath)
            editCustomPort.setText((defaults.customPort ?: 22).toString())
            host ?: return@launch

            editName.setText(host.name)
            editCliPath.setText(host.dockerCliPath ?: "")
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
                    getString(R.string.docker_host_password_keep_helper)
                val keyIdx = keys.indexOfFirst { it.keyId == host.customKeyId }
                if (keyIdx >= 0) spinnerCustomKey.setSelection(keyIdx)
                val identIdx = identities.indexOfFirst { it.id == host.customIdentityId }
                if (identIdx >= 0) spinnerCustomIdentity.setSelection(identIdx)
            } else {
                val idx = connections.indexOfFirst { it.id == host.linkedConnectionId }
                if (idx >= 0) spinnerConnection.setSelection(idx)
            }
        }
    }

    /**
     * The DockerHost the current form describes, or null when invalid.
     * The name is optional — it defaults to the linked connection's name
     * (saved mode) or the endpoint hostname (custom mode).
     */
    private fun hostFromForm(): DockerHost? {
        val typedName = editName.text?.toString()?.trim().orEmpty()
        val entityDefaults = DockerHost(name = "")
        val base = existingHost ?: entityDefaults

        val endpoint: DockerHost = if (isCustomMode()) {
            val hostAddr = editCustomHost.text?.toString()?.trim().orEmpty()
            if (!isValidHostAddress(hostAddr)) {
                showError(getString(R.string.docker_host_error_custom_host))
                return null
            }
            val username = editCustomUsername.text?.toString()?.trim().orEmpty()
            // A username with whitespace or controls would break the SSH auth
            // request; reject it here rather than failing opaquely on connect.
            if (username.isEmpty() ||
                username.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
            ) {
                showError(getString(R.string.docker_host_error_custom_username))
                return null
            }
            val auth = authTypeValues.getOrNull(spinnerAuthType.selectedItemPosition)
                ?: "password"
            val key = if (auth == "key") {
                keys.getOrNull(spinnerCustomKey.selectedItemPosition) ?: run {
                    showError(getString(R.string.docker_host_error_no_keys))
                    return null
                }
            } else null
            val identity = if (auth == "identity") {
                identities.getOrNull(spinnerCustomIdentity.selectedItemPosition) ?: run {
                    showError(getString(R.string.docker_host_error_no_identities))
                    return null
                }
            } else null
            base.copy(
                name = typedName.ifEmpty { hostAddr },
                linkedConnectionId = null,
                customHost = hostAddr,
                customPort = editCustomPort.text?.toString()?.trim()?.toIntOrNull()
                    ?.coerceIn(1, 65535) ?: 22,
                customUsername = username,
                customAuthType = auth,
                customKeyId = key?.keyId,
                customIdentityId = identity?.id
            )
        } else {
            val connection = connections.getOrNull(spinnerConnection.selectedItemPosition)
            if (connection == null) {
                showError(getString(R.string.docker_host_error_connection))
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

        val socketPath = editSocketPath.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entityDefaults.socketPath
        val composeBase = editComposeBase.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entityDefaults.composeBasePath
        val runBase = editRunBase.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entityDefaults.runConfigBasePath
        // These three are interpolated into remote shell commands and into
        // stored config — reject anything that is not a plain absolute path.
        if (!isValidRemotePath(socketPath) || !isValidRemotePath(composeBase) ||
            !isValidRemotePath(runBase)
        ) {
            showError(getString(R.string.docker_host_error_path))
            return null
        }
        val cliPath = editCliPath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // The CLI path may be a bare command name ("docker"), so only reject
        // whitespace and control characters rather than requiring an absolute path.
        if (cliPath != null &&
            cliPath.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
        ) {
            showError(getString(R.string.docker_host_error_path))
            return null
        }

        return endpoint.copy(
            socketPath = socketPath,
            composeBasePath = composeBase,
            runConfigBasePath = runBase,
            dockerCliPath = cliPath,
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
                        app.database.dockerHostDao().update(host)
                        host.id
                    } else {
                        app.database.dockerHostDao().insert(host)
                    }
                    if (host.customAuthType == "password" && password.isNotEmpty()) {
                        DockerHostPasswordStore.store(
                            this@DockerHostEditActivity, savedId, password
                        )
                    } else if (host.customAuthType != "password") {
                        // Mode or auth switched away from password — drop the
                        // stored secret so no orphaned Keystore entry remains.
                        DockerHostPasswordStore.clear(this@DockerHostEditActivity, savedId)
                    }
                    // A custom endpoint's ephemeral profile is cached by the
                    // session managers — drop any stale session so the next
                    // acquire uses the edited endpoint.
                    DockerSessionManager.release(app, savedId)
                }
                if (!isAlive()) return@launch
                Toast.makeText(
                    this@DockerHostEditActivity,
                    getString(R.string.docker_host_saved),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save docker host", e)
                if (!isAlive()) return@launch
                showError(
                    DockerText.display(e.message).ifEmpty {
                        getString(R.string.docker_error_title)
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
     * after the probe — screens acquire their own via DockerSessionManager.
     */
    private fun testTransport() {
        val host = hostFromForm() ?: return
        val password = editCustomPassword.text?.toString().orEmpty()

        buttonTestTransport.isEnabled = false
        progressTest.visibility = View.VISIBLE
        Toast.makeText(this, R.string.docker_testing_transport, Toast.LENGTH_SHORT).show()

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
                DockerResult.Error(
                    DockerText.display(e.message).ifEmpty {
                        getString(R.string.docker_error_title)
                    }
                )
            }
            if (!isAlive()) return@launch
            buttonTestTransport.isEnabled = true
            progressTest.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> MaterialAlertDialogBuilder(this@DockerHostEditActivity)
                    .setTitle(R.string.docker_transport_result_title)
                    .setMessage(getString(R.string.docker_transport_ok, result.value.mode))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                else -> DockerErrorPresenter.present(this@DockerHostEditActivity, result)
            }
        }
    }

    /**
     * The blocking half of [testTransport]: resolve a profile, connect, run the
     * three-tier detection, and always drop a temporary Keystore alias stored
     * for a not-yet-saved host — even when the probe throws.
     */
    private suspend fun probeTransport(
        host: DockerHost,
        password: String
    ): DockerResult<TransportCapabilityDetector.DetectedTransport> {
        var storedTemporaryPassword = false
        try {
            val profile = if (host.usesCustomEndpoint()) {
                // The probe needs the typed password in the Keystore (the
                // SSH layer reads it by profile id). For a not-yet-saved
                // host (id 0) the temporary alias is cleared right after.
                if (host.customAuthType == "password" && password.isNotEmpty()) {
                    DockerHostPasswordStore.store(
                        this@DockerHostEditActivity, host.id, password
                    )
                    storedTemporaryPassword = existingHost == null
                }
                DockerSessionManager.resolveCustomProfile(app, host)
            } else {
                connections.getOrNull(spinnerConnection.selectedItemPosition)
            }
            if (profile == null) {
                return DockerResult.Error(getString(R.string.docker_host_error_connection))
            }
            // connectForMonitoring: a connection test is plumbing, not a user
            // session — it must not show up as an active SSH session.
            val ssh = app.sshSessionManager.getConnection(profile.id)
                ?.takeIf { it.isConnected() }
                ?: app.sshSessionManager.connectForMonitoring(profile)
            if (ssh == null) {
                return DockerResult.TransportUnavailable(
                    getString(R.string.docker_msg_ssh_unavailable),
                    profile.name
                )
            }
            val runner = SshExecRunner { ssh.jschSession() }
            val detector = TransportCapabilityDetector(app.database.dockerHostDao())
            val probe = detector.detect(host, runner, force = true)
            // The probe transport is single-use — close its relay.
            probe.valueOrNull()?.transport?.close()
            return probe
        } finally {
            // A throw between store() and here would otherwise leave the typed
            // password in the Keystore under an unsaved host's id.
            if (storedTemporaryPassword) {
                DockerHostPasswordStore.clear(this@DockerHostEditActivity, host.id)
            }
        }
    }
}
