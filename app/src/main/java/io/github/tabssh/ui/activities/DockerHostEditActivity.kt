package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.docker.transport.TransportCapabilityDetector
import io.github.tabssh.ssh.forwarding.PortForwardingManager
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add/edit one Docker host (PLAN.AI.md step 20): name, linked SSH connection,
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
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var spinnerConnection: Spinner
    private lateinit var editSocketPath: TextInputEditText
    private lateinit var editComposeBase: TextInputEditText
    private lateinit var editRunBase: TextInputEditText
    private lateinit var editCliPath: TextInputEditText
    private lateinit var editNotes: TextInputEditText
    private lateinit var buttonTestTransport: MaterialButton
    private lateinit var progressTest: ProgressBar
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonCancel: MaterialButton

    private var connections: List<ConnectionProfile> = emptyList()
    private var existingHost: DockerHost? = null
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
        spinnerConnection = findViewById(R.id.spinner_connection)
        editSocketPath = findViewById(R.id.edit_socket_path)
        editComposeBase = findViewById(R.id.edit_compose_base)
        editRunBase = findViewById(R.id.edit_run_base)
        editCliPath = findViewById(R.id.edit_cli_path)
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

    private fun setupClickListeners() {
        buttonCancel.setOnClickListener { finish() }
        buttonSave.setOnClickListener { saveHost() }
        buttonTestTransport.setOnClickListener { testTransport() }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val (list, host) = withContext(Dispatchers.IO) {
                val list = app.database.connectionDao().getAllConnectionsList()
                val host = hostId?.let { app.database.dockerHostDao().getById(it) }
                list to host
            }
            connections = list
            existingHost = host

            spinnerConnection.adapter = ArrayAdapter(
                this@DockerHostEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                connections.map { it.name }
            )

            // Entity defaults populate the form for a new host so the user
            // sees (and can override) the /srv/$USER/... base paths.
            val defaults = host ?: DockerHost(name = "")
            editSocketPath.setText(defaults.socketPath)
            editComposeBase.setText(defaults.composeBasePath)
            editRunBase.setText(defaults.runConfigBasePath)
            host ?: return@launch

            editName.setText(host.name)
            editCliPath.setText(host.dockerCliPath ?: "")
            editNotes.setText(host.notes ?: "")
            val idx = connections.indexOfFirst { it.id == host.linkedConnectionId }
            if (idx >= 0) spinnerConnection.setSelection(idx)
        }
    }

    /** The DockerHost the current form describes, or null when invalid. */
    private fun hostFromForm(): DockerHost? {
        val name = editName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            showError(getString(R.string.docker_host_error_name))
            return null
        }
        val connection = connections.getOrNull(spinnerConnection.selectedItemPosition)
        if (connection == null) {
            showError(getString(R.string.docker_host_error_connection))
            return null
        }
        val base = existingHost ?: DockerHost(name = name)
        return base.copy(
            name = name,
            linkedConnectionId = connection.id,
            socketPath = editSocketPath.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: DockerHost(name = name).socketPath,
            composeBasePath = editComposeBase.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: DockerHost(name = name).composeBasePath,
            runConfigBasePath = editRunBase.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: DockerHost(name = name).runConfigBasePath,
            dockerCliPath = editCliPath.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            notes = editNotes.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun saveHost() {
        val host = hostFromForm() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (existingHost != null) {
                        app.database.dockerHostDao().update(host)
                    } else {
                        app.database.dockerHostDao().insert(host)
                    }
                }
                Toast.makeText(
                    this@DockerHostEditActivity,
                    getString(R.string.docker_host_saved),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save docker host", e)
                showError(e.message ?: getString(R.string.docker_error_title))
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
        val connection = connections.getOrNull(spinnerConnection.selectedItemPosition) ?: return

        buttonTestTransport.isEnabled = false
        progressTest.visibility = View.VISIBLE
        Toast.makeText(this, R.string.docker_testing_transport, Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val ssh = app.sshSessionManager.getConnection(connection.id)
                    ?.takeIf { it.isConnected() }
                    ?: app.sshSessionManager.connectToServer(connection)
                if (ssh == null) {
                    DockerResult.TransportUnavailable(
                        getString(R.string.docker_msg_ssh_unavailable),
                        connection.name
                    )
                } else {
                    val runner = SshExecRunner { ssh.jschSession() }
                    val detector = TransportCapabilityDetector(app.database.dockerHostDao())
                    val detected = detector.detect(
                        host, runner, PortForwardingManager(ssh), force = true
                    )
                    // The probe transport is single-use — close its relay.
                    detected.valueOrNull()?.transport?.close()
                    detected
                }
            }
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
}
