package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.runconfig.RunConfig
import io.github.tabssh.docker.runconfig.RunConfigException
import io.github.tabssh.docker.runconfig.RunConfigParser
import io.github.tabssh.docker.runconfig.RunConfigTranslator
import io.github.tabssh.docker.runconfig.RunConfigWriter
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.storage.database.entities.SingleContainerConfig
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import kotlinx.coroutines.launch

/**
 * Single-container run.yml editor (PLAN.AI.md step 26): a structured form
 * over the RunConfig fields with an advanced raw-YAML toggle. Save writes
 * {runConfigBase}/{name}/run.yml on the host plus the Room row; Run
 * translates the config to docker run argv and executes it over the
 * transport's exec runner. FLAG_SECURE because env values are secrets.
 */
class SingleContainerConfigEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
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
    private var session: DockerSessionManager.DockerSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Environment values in run configs routinely hold secrets.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_run_config_editor)

        app = application as TabSSHApplication
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        configId = intent.getLongExtra(EXTRA_CONFIG_ID, 0)

        bindViews()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(
            if (configId == 0L) R.string.docker_run_new_title else R.string.docker_run_edit_title
        )
        toolbar.setNavigationOnClickListener { finish() }

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
            when (val result = DockerSessionManager.acquire(app, hostId)) {
                is DockerResult.Success -> {
                    session = result.value
                    if (configId != 0L) {
                        loadConfig()
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
                else -> {
                    progressBar.visibility = View.GONE
                    DockerErrorPresenter.present(
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
            if (text != null) {
                editRaw.setText(text)
                try {
                    fillForm(RunConfigParser.parse(text))
                } catch (e: RunConfigException) {
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
                        this, getString(R.string.docker_run_parse_error, e.message),
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
            Toast.makeText(this, R.string.docker_run_error_image, Toast.LENGTH_SHORT).show()
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
                    this, getString(R.string.docker_run_parse_error, e.message),
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
            Toast.makeText(this, R.string.docker_run_error_name, Toast.LENGTH_SHORT).show()
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
            if (written !is DockerResult.Success) {
                progressBar.visibility = View.GONE
                buttonSave.isEnabled = true
                DockerErrorPresenter.present(
                    this@SingleContainerConfigEditorActivity, written
                )
                return@launch
            }
            if (existing == null) {
                configId = dao.insert(
                    SingleContainerConfig(
                        dockerHostId = hostId, name = name, remotePath = remotePath
                    )
                )
                config = dao.getById(configId)
            } else {
                dao.update(existing.copy(updatedAt = System.currentTimeMillis()))
            }
            progressBar.visibility = View.GONE
            buttonSave.isEnabled = true
            Toast.makeText(
                this@SingleContainerConfigEditorActivity,
                getString(R.string.docker_run_saved, remotePath), Toast.LENGTH_SHORT
            ).show()
            supportActionBar?.setTitle(R.string.docker_run_edit_title)
            editName.isEnabled = false
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
                this, getString(R.string.docker_run_parse_error, e.message), Toast.LENGTH_LONG
            ).show()
            return
        }
        progressBar.visibility = View.VISIBLE
        buttonRun.isEnabled = false
        lifecycleScope.launch {
            val docker = current.host.dockerCliPath ?: "docker"
            // Every argv token is shell-quoted before remote interpolation.
            val command = docker + " " + RunConfigTranslator.toRunArgv(parsed)
                .joinToString(" ") { SshExecRunner.shQuote(it) }
            val result = current.runner.run(command, timeoutMs = 120000)
            progressBar.visibility = View.GONE
            buttonRun.isEnabled = true
            if (result.isSuccess) {
                Toast.makeText(
                    this@SingleContainerConfigEditorActivity,
                    getString(
                        R.string.docker_run_started, result.stdout.trim().take(12)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@SingleContainerConfigEditorActivity,
                    result.stderr.trim().ifEmpty { result.stdout.trim() },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
