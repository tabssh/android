package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.runconfig.RunConfig
import io.github.tabssh.containers.runconfig.RunConfigException
import io.github.tabssh.containers.runconfig.RunConfigParser
import io.github.tabssh.containers.runconfig.RunConfigTranslator
import io.github.tabssh.containers.runconfig.RunConfigWriter
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.SshExecRunner
import io.github.tabssh.storage.database.entities.SingleContainerConfig
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.tabssh.utils.tabSSHApp

/**
 * Single-container run.yml editor: a structured form
 * over the RunConfig fields with an advanced raw-YAML toggle. Save writes
 * {runConfigBase}/{name}/run.yml on the host plus the Room row; Run
 * translates the config to docker run argv and executes it over the
 * transport's exec runner. FLAG_SECURE because env values are secrets.
 */
class SingleContainerConfigEditorActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
        const val EXTRA_CONFIG_ID = "run_config_id"
        private val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var containerForm: LinearLayout
    private lateinit var editImage: TextInputEditText
    private lateinit var editContainerName: TextInputEditText
    private lateinit var editRestart: TextInputEditText
    private lateinit var editPorts: TextInputEditText
    private lateinit var editVolumes: TextInputEditText
    private lateinit var editEnv: TextInputEditText
    private lateinit var editNetwork: TextInputEditText
    private lateinit var editCommand: TextInputEditText
    private lateinit var switchAdvanced: SwitchMaterial
    private lateinit var layoutRaw: TextInputLayout
    private lateinit var editRaw: TextInputEditText
    private lateinit var buttonRun: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var buttonCancel: MaterialButton
    private lateinit var buttonSave: MaterialButton

    private var hostId: Long = 0
    private var configId: Long = 0
    private var config: SingleContainerConfig? = null
    private var session: ContainerSessionManager.ContainerSession? = null

    /** Guards the log lookup — repeated menu taps must not stack `docker ps` runs. */
    private var lookingUpLogs = false

    /** False once the activity can no longer host a dialog or touch its views. */
    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Environment values in run configs routinely hold secrets.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_run_config_editor)

        app = tabSSHApp
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        configId = intent.getLongExtra(EXTRA_CONFIG_ID, 0)

        bindViews()
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (configId == 0L) R.string.container_run_new_title else R.string.container_run_edit_title
        )

        switchAdvanced.setOnCheckedChangeListener { _, checked -> toggleAdvanced(checked) }
        buttonRun.setOnClickListener { runContainer() }
        buttonCancel.setOnClickListener { finish() }
        buttonSave.setOnClickListener { save() }

        acquireSession()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        containerForm = findViewById(R.id.container_form)
        editImage = findViewById(R.id.edit_image)
        editContainerName = findViewById(R.id.edit_container_name)
        editRestart = findViewById(R.id.edit_restart)
        editPorts = findViewById(R.id.edit_ports)
        editVolumes = findViewById(R.id.edit_volumes)
        editEnv = findViewById(R.id.edit_env)
        editNetwork = findViewById(R.id.edit_network)
        editCommand = findViewById(R.id.edit_command)
        switchAdvanced = findViewById(R.id.switch_advanced)
        layoutRaw = findViewById(R.id.layout_raw)
        editRaw = findViewById(R.id.edit_raw)
        buttonRun = findViewById(R.id.button_run)
        progressBar = findViewById(R.id.progress_bar)
        buttonCancel = findViewById(R.id.button_cancel)
        buttonSave = findViewById(R.id.button_save)
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ContainerSessionManager.acquire(app, hostId)
            // acquire() suspends — the activity may be gone by the time it returns.
            if (!isAlive()) return@launch
            when (result) {
                is ContainerResult.Success -> {
                    session = result.value
                    if (configId != 0L) {
                        loadConfig()
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
                else -> {
                    progressBar.visibility = View.GONE
                    ContainerErrorPresenter.present(
                        this@SingleContainerConfigEditorActivity, result
                    )
                }
            }
        }
    }

    /** Edit mode: load the Room row plus run.yml from the host into the form. */
    private fun loadConfig() {
        val current = session ?: return
        lifecycleScope.launch {
            val loaded = app.database.singleContainerConfigDao().getById(configId)
            // The DAO lookup suspends — the activity may already be gone.
            if (!isAlive()) return@launch
            if (loaded == null) {
                finish()
                return@launch
            }
            config = loaded
            editName.setText(loaded.name)
            // The name keys the remote directory — immutable once created.
            editName.isEnabled = false
            val text = current.transport
                .readRemoteFile("${loaded.remotePath}/run.yml").valueOrNull()
            // The remote read suspends — bail out if the screen went away.
            if (!isAlive()) return@launch
            if (text != null) {
                editRaw.setText(text)
                try {
                    fillForm(RunConfigParser.parse(text))
                } catch (_: RunConfigException) {
                    // Unparseable remote file — fall back to the raw editor.
                    switchAdvanced.isChecked = true
                }
            }
            progressBar.visibility = View.GONE
        }
    }

    private fun fillForm(parsed: RunConfig) {
        editImage.setText(parsed.image)
        editContainerName.setText(parsed.name.orEmpty())
        editRestart.setText(parsed.restart.orEmpty())
        editPorts.setText(parsed.ports.joinToString("\n"))
        editVolumes.setText(parsed.volumes.joinToString("\n"))
        editEnv.setText(parsed.env.entries.joinToString("\n") { "${it.key}=${it.value}" })
        editNetwork.setText(parsed.network.orEmpty())
        editCommand.setText(parsed.command.joinToString(" "))
    }

    /** Advanced mode edits run.yml directly; the form is regenerated on exit. */
    private fun toggleAdvanced(advanced: Boolean) {
        if (advanced) {
            val built = buildConfigFromForm()
            if (built != null) {
                editRaw.setText(RunConfigWriter.write(built))
            }
            containerForm.visibility = View.GONE
            layoutRaw.visibility = View.VISIBLE
        } else {
            val text = editRaw.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                try {
                    fillForm(RunConfigParser.parse(text))
                } catch (e: RunConfigException) {
                    Toast.makeText(
                        this, getString(R.string.container_run_parse_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                    switchAdvanced.isChecked = true
                    return
                }
            }
            containerForm.visibility = View.VISIBLE
            layoutRaw.visibility = View.GONE
        }
    }

    private fun linesOf(edit: TextInputEditText): List<String> =
        edit.text?.toString().orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Build a RunConfig from the form; null (with a toast) when invalid. */
    private fun buildConfigFromForm(): RunConfig? {
        val image = editImage.text?.toString()?.trim().orEmpty()
        if (image.isEmpty()) {
            Toast.makeText(this, R.string.container_run_error_image, Toast.LENGTH_SHORT).show()
            return null
        }
        val env = linkedMapOf<String, String>()
        for (line in linesOf(editEnv)) {
            val key = line.substringBefore('=').trim()
            if (key.isNotEmpty()) {
                env[key] = line.substringAfter('=', "").trim()
            }
        }
        return RunConfig(
            image = image,
            name = editContainerName.text?.toString()?.trim()?.ifEmpty { null },
            restart = editRestart.text?.toString()?.trim()?.ifEmpty { null },
            ports = linesOf(editPorts),
            volumes = linesOf(editVolumes),
            env = env,
            network = editNetwork.text?.toString()?.trim()?.ifEmpty { null },
            command = editCommand.text?.toString()?.trim().orEmpty()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
        )
    }

    /** Current editor state as run.yml text; null when invalid. */
    private fun currentYaml(): String? {
        return if (switchAdvanced.isChecked) {
            val text = editRaw.text?.toString().orEmpty()
            try {
                RunConfigParser.parse(text)
                text
            } catch (e: RunConfigException) {
                Toast.makeText(
                    this, getString(R.string.container_run_parse_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
                null
            }
        } else {
            buildConfigFromForm()?.let { RunConfigWriter.write(it) }
        }
    }

    private fun save() {
        val current = session ?: return
        val name = editName.text?.toString()?.trim().orEmpty()
        if (configId == 0L && !NAME_REGEX.matches(name)) {
            Toast.makeText(this, R.string.container_run_error_name, Toast.LENGTH_SHORT).show()
            return
        }
        val yaml = currentYaml() ?: return

        progressBar.visibility = View.VISIBLE
        buttonSave.isEnabled = false
        lifecycleScope.launch {
            val dao = app.database.singleContainerConfigDao()
            val existing = config
            // Resolve $USER etc. on the remote shell so the stored path is stable.
            val remotePath = existing?.remotePath ?: run {
                val base = current.host.runConfigBasePath.trimEnd('/')
                current.transport.expandRemotePath("$base/$name").valueOrNull()
                    ?: "$base/$name"
            }
            val written = current.transport.writeRemoteFile("$remotePath/run.yml", yaml)
            // The remote write suspends — nothing below may touch dead views.
            if (!isAlive()) return@launch
            if (written !is ContainerResult.Success) {
                progressBar.visibility = View.GONE
                buttonSave.isEnabled = true
                ContainerErrorPresenter.present(
                    this@SingleContainerConfigEditorActivity, written
                )
                return@launch
            }
            if (existing == null) {
                configId = dao.insert(
                    SingleContainerConfig(
                        containerHostId = hostId, name = name, remotePath = remotePath,
                        modifiedAt = System.currentTimeMillis()
                    )
                )
                config = dao.getById(configId)
            } else {
                dao.update(existing.copy(updatedAt = System.currentTimeMillis(), modifiedAt = System.currentTimeMillis()))
            }
            // The DAO writes suspend as well — re-check before the toast.
            if (!isAlive()) return@launch
            progressBar.visibility = View.GONE
            buttonSave.isEnabled = true
            Toast.makeText(
                this@SingleContainerConfigEditorActivity,
                getString(R.string.container_run_saved, remotePath), Toast.LENGTH_SHORT
            ).show()
            supportActionBar?.setTitle(R.string.container_run_edit_title)
            editName.isEnabled = false
            invalidateOptionsMenu()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Logs only makes sense once the config has a remote directory to
        // resolve a container name/id against.
        if (configId != 0L) {
            menuInflater.inflate(R.menu.menu_run_config_editor, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logs -> {
                openLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Resolve the run config's container (its explicit `name:` field, falling
     * back to the config's own directory-keying name since Docker
     * auto-generates a name when the run.yml has none) via `docker ps -a
     * --filter name=^<name>$` and open the existing container log flow for
     * it. Toasts a not-found error when no matching container exists.
     */
    private fun openLogs() {
        val current = session ?: return
        val savedConfig = config ?: return
        // The form field mirrors RunConfig.name (populated by fillForm on
        // load); an empty run.yml name means Docker auto-generated one, so
        // fall back to the config's own directory-keying name.
        val containerName = editContainerName.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: savedConfig.name
        // Repeated menu taps must not stack `docker ps` runs on one session.
        if (lookingUpLogs) return
        lookingUpLogs = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val docker = current.host.cliBinary()
                val filter = SshExecRunner.shQuote("name=^$containerName$")
                // run() throws TransportUnavailableException on a dead session.
                val result = current.runner.run(
                    "$docker ps -a --filter $filter --format '{{.ID}}'"
                )
                if (!isAlive()) return@launch
                progressBar.visibility = View.GONE
                val containerId = result.stdout.trim().lineSequence()
                    .firstOrNull { it.isNotBlank() }
                if (containerId.isNullOrBlank()) {
                    Toast.makeText(
                        this@SingleContainerConfigEditorActivity,
                        R.string.container_run_container_not_found, Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val intent = Intent(
                    this@SingleContainerConfigEditorActivity,
                    ContainerDetailActivity::class.java
                )
                intent.putExtra(ContainerDetailActivity.EXTRA_HOST_ID, hostId)
                intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, containerId)
                intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_NAME, containerName)
                intent.putExtra(
                    ContainerDetailActivity.EXTRA_INITIAL_TAB,
                    ContainerDetailActivity.TAB_LOGS
                )
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive()) return@launch
                progressBar.visibility = View.GONE
                ContainerErrorPresenter.present(
                    this@SingleContainerConfigEditorActivity,
                    ContainerResult.Error(
                        ContainerText.display(e.message).ifEmpty {
                            getString(R.string.container_error_title)
                        }
                    )
                )
            } finally {
                lookingUpLogs = false
            }
        }
    }

    /** Translate the current config to docker run argv and execute it. */
    private fun runContainer() {
        val current = session ?: return
        val yaml = currentYaml() ?: return
        val parsed = try {
            RunConfigParser.parse(yaml)
        } catch (e: RunConfigException) {
            Toast.makeText(
                this, getString(R.string.container_run_parse_error, e.message), Toast.LENGTH_LONG
            ).show()
            return
        }
        progressBar.visibility = View.VISIBLE
        buttonRun.isEnabled = false
        lifecycleScope.launch {
            try {
                val docker = current.host.cliBinary()
                // Every argv token is shell-quoted before remote interpolation.
                val command = docker + " " + RunConfigTranslator.toRunArgv(parsed)
                    .joinToString(" ") { SshExecRunner.shQuote(it) }
                // run() throws TransportUnavailableException on a dead session.
                val result = current.runner.run(command, timeoutMs = 120000)
                if (!isAlive()) return@launch
                if (result.isSuccess) {
                    Toast.makeText(
                        this@SingleContainerConfigEditorActivity,
                        getString(
                            R.string.container_run_started,
                            ContainerText.display(result.stdout.trim().take(12))
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // stderr/stdout are remote-controlled — sanitize before display.
                    Toast.makeText(
                        this@SingleContainerConfigEditorActivity,
                        ContainerText.display(
                            result.stderr.trim().ifEmpty { result.stdout.trim() }
                        ).ifEmpty { getString(R.string.container_error_title) },
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive()) return@launch
                ContainerErrorPresenter.present(
                    this@SingleContainerConfigEditorActivity,
                    ContainerResult.Error(
                        ContainerText.display(e.message).ifEmpty {
                            getString(R.string.container_error_title)
                        }
                    )
                )
            } finally {
                // A throw would otherwise leave Run permanently disabled.
                if (isAlive()) {
                    progressBar.visibility = View.GONE
                    buttonRun.isEnabled = true
                }
            }
        }
    }
}
