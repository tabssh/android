package io.github.tabssh.ui.activities

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import io.github.tabssh.ui.utils.DockerText
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import java.io.StringReader

/**
 * Compose stack editor: paste-first new-stack flow —
 * name it, paste the YAML, validate, and push to
 * {composeBase}/{name}/compose.yaml plus an optional .env. Edit mode loads
 * both files back from the host and adds the compose lifecycle menu.
 *
 * External-file mode (TODO.AI.md § D, [EXTRA_EXTERNAL_CONFIG_FILE]) edits a
 * compose project discovered via `docker compose ls` that has no Room row:
 * the compose file loads and saves at its original absolute path, no
 * directory is created, `.env` is hidden (its location is unknown for an
 * arbitrary config file), and lifecycle actions use the `*ByProject`
 * transport calls. Saving does not import the stack into Room.
 *
 * FLAG_SECURE because .env content is typically secrets.
 */
class ComposeEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
        const val EXTRA_STACK_ID = "compose_stack_id"
        const val EXTRA_EXTERNAL_CONFIG_FILE = "compose_external_config_file"
        const val EXTRA_EXTERNAL_NAME = "compose_external_name"
        private val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var buttonPaste: MaterialButton
    private lateinit var editCompose: TextInputEditText
    private lateinit var layoutEnv: TextInputLayout
    private lateinit var editEnv: TextInputEditText
    private lateinit var progressBar: ProgressBar
    private lateinit var buttonCancel: MaterialButton
    private lateinit var buttonSave: MaterialButton

    private var hostId: Long = 0
    private var stackId: Long = 0
    private var stack: ComposeStack? = null
    private var session: DockerSessionManager.DockerSession? = null

    /** Absolute remote path to an untracked project's compose file, or null when tracked/new. */
    private var externalConfigFile: String? = null
    private var externalName: String? = null
    private val isExternal: Boolean get() = externalConfigFile != null

    /** Guards the compose lifecycle menu — repeated taps must not stack up/down runs. */
    private var actionRunning = false

    /** False once the activity can no longer host a dialog or touch its views. */
    private fun isAlive(): Boolean = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pasted compose files and .env content routinely hold secrets.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_compose_editor)

        app = application as TabSSHApplication
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        stackId = intent.getLongExtra(EXTRA_STACK_ID, 0)
        externalConfigFile = intent.getStringExtra(EXTRA_EXTERNAL_CONFIG_FILE)
        externalName = intent.getStringExtra(EXTRA_EXTERNAL_NAME)

        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        buttonPaste = findViewById(R.id.button_paste)
        editCompose = findViewById(R.id.edit_compose)
        layoutEnv = findViewById(R.id.layout_env)
        editEnv = findViewById(R.id.edit_env)
        progressBar = findViewById(R.id.progress_bar)
        buttonCancel = findViewById(R.id.button_cancel)
        buttonSave = findViewById(R.id.button_save)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(
            if (stackId == 0L && !isExternal) R.string.docker_stack_new_title
            else R.string.docker_stack_edit_title
        )
        toolbar.setNavigationOnClickListener { finish() }

        if (isExternal) {
            // No sibling .env path is known for an arbitrary discovered
            // config file, and the project's own name is not renameable here.
            layoutEnv.visibility = View.GONE
            // The project name comes from `docker compose ls` — sanitize before display.
            editName.setText(DockerText.display(externalName))
            editName.isEnabled = false
        }

        buttonPaste.setOnClickListener { pasteFromClipboard() }
        buttonCancel.setOnClickListener { finish() }
        buttonSave.setOnClickListener { save() }

        acquireSession()
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = DockerSessionManager.acquire(app, hostId)
            // acquire() suspends — the activity may be gone by the time it returns.
            if (!isAlive()) return@launch
            when (result) {
                is DockerResult.Success -> {
                    session = result.value
                    when {
                        isExternal -> loadExternalStack()
                        stackId != 0L -> loadStack()
                        else -> progressBar.visibility = View.GONE
                    }
                }
                else -> {
                    progressBar.visibility = View.GONE
                    DockerErrorPresenter.present(this@ComposeEditorActivity, result)
                }
            }
        }
    }

    /** Edit mode: load the Room row plus compose.yaml and .env from the host. */
    private fun loadStack() {
        val current = session ?: return
        lifecycleScope.launch {
            val loaded = app.database.composeStackDao().getById(stackId)
            if (!isAlive()) return@launch
            if (loaded == null) {
                finish()
                return@launch
            }
            stack = loaded
            editName.setText(loaded.name)
            // The name keys the remote directory — immutable once created.
            editName.isEnabled = false
            val compose = current.transport.readRemoteFile("${loaded.remotePath}/compose.yaml")
            if (!isAlive()) return@launch
            if (compose !is DockerResult.Success) {
                progressBar.visibility = View.GONE
                DockerErrorPresenter.present(this@ComposeEditorActivity, compose)
                finish()
                return@launch
            }
            editCompose.setText(compose.value)
            // .env is legitimately optional — a missing/unreadable file just
            // means the stack has none; only compose.yaml failure is fatal.
            val env = current.transport.readRemoteFile("${loaded.remotePath}/.env")
            if (!isAlive()) return@launch
            env.valueOrNull()?.let { editEnv.setText(it) }
            progressBar.visibility = View.GONE
            invalidateOptionsMenu()
        }
    }

    /** External-file mode: load the discovered project's compose file directly. */
    private fun loadExternalStack() {
        val current = session ?: return
        val path = externalConfigFile ?: return
        lifecycleScope.launch {
            val compose = current.transport.readRemoteFile(path)
            if (!isAlive()) return@launch
            if (compose !is DockerResult.Success) {
                progressBar.visibility = View.GONE
                DockerErrorPresenter.present(this@ComposeEditorActivity, compose)
                finish()
                return@launch
            }
            editCompose.setText(compose.value)
            progressBar.visibility = View.GONE
            invalidateOptionsMenu()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) {
            editCompose.setText(text)
        }
    }

    /** Compose text must parse as YAML with a mapping at the root. */
    private fun validateYaml(text: String): Boolean {
        if (text.isBlank()) {
            Toast.makeText(this, R.string.docker_stack_empty_compose, Toast.LENGTH_SHORT).show()
            return false
        }
        val node = try {
            Yaml().compose(StringReader(text))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.docker_stack_error_yaml, Toast.LENGTH_SHORT).show()
            return false
        }
        if (node !is MappingNode) {
            Toast.makeText(this, R.string.docker_stack_error_yaml_mapping, Toast.LENGTH_SHORT)
                .show()
            return false
        }
        return true
    }

    private fun save() {
        val current = session ?: return
        val name = editName.text?.toString()?.trim().orEmpty()
        if (!isExternal && stackId == 0L && !NAME_REGEX.matches(name)) {
            Toast.makeText(this, R.string.docker_stack_error_name, Toast.LENGTH_SHORT).show()
            return
        }
        val compose = editCompose.text?.toString().orEmpty()
        if (!validateYaml(compose)) return

        if (isExternal) {
            saveExternal(current, compose)
            return
        }

        val env = editEnv.text?.toString().orEmpty()
        progressBar.visibility = View.VISIBLE
        buttonSave.isEnabled = false
        lifecycleScope.launch {
            val dao = app.database.composeStackDao()
            val existing = stack
            // Resolve $USER etc. on the remote shell so the stored path is stable.
            val remotePath = existing?.remotePath ?: run {
                val base = current.host.composeBasePath.trimEnd('/')
                current.transport.expandRemotePath("$base/$name").valueOrNull()
                    ?: "$base/$name"
            }
            val written = current.transport.writeRemoteFile("$remotePath/compose.yaml", compose)
            if (!isAlive()) return@launch
            if (written !is DockerResult.Success) {
                progressBar.visibility = View.GONE
                buttonSave.isEnabled = true
                DockerErrorPresenter.present(this@ComposeEditorActivity, written)
                return@launch
            }
            if (env.isNotBlank()) {
                val envWritten = current.transport.writeRemoteFile("$remotePath/.env", env)
                if (!isAlive()) return@launch
                if (envWritten !is DockerResult.Success) {
                    progressBar.visibility = View.GONE
                    buttonSave.isEnabled = true
                    DockerErrorPresenter.present(this@ComposeEditorActivity, envWritten)
                    return@launch
                }
            }
            if (existing == null) {
                stackId = dao.insert(
                    ComposeStack(
                        dockerHostId = hostId,
                        name = name,
                        remotePath = remotePath,
                        modifiedAt = System.currentTimeMillis()
                    )
                )
                stack = dao.getById(stackId)
            } else {
                dao.update(existing.copy(updatedAt = System.currentTimeMillis(), modifiedAt = System.currentTimeMillis()))
            }
            if (!isAlive()) return@launch
            progressBar.visibility = View.GONE
            buttonSave.isEnabled = true
            Toast.makeText(
                this@ComposeEditorActivity,
                getString(R.string.docker_stack_saved, remotePath), Toast.LENGTH_SHORT
            ).show()
            supportActionBar?.setTitle(R.string.docker_stack_edit_title)
            editName.isEnabled = false
            invalidateOptionsMenu()
        }
    }

    /**
     * External-file mode: write back to the same absolute path — never
     * create a directory or a Room row, and never move the file into the
     * tracked compose-dir layout.
     */
    private fun saveExternal(current: DockerSessionManager.DockerSession, compose: String) {
        val path = externalConfigFile ?: return
        progressBar.visibility = View.VISIBLE
        buttonSave.isEnabled = false
        lifecycleScope.launch {
            val written = current.transport.writeRemoteFile(path, compose)
            if (!isAlive()) return@launch
            progressBar.visibility = View.GONE
            buttonSave.isEnabled = true
            if (written !is DockerResult.Success) {
                DockerErrorPresenter.present(this@ComposeEditorActivity, written)
                return@launch
            }
            Toast.makeText(
                this@ComposeEditorActivity,
                getString(R.string.docker_stack_saved, path), Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Lifecycle actions only make sense once the stack exists remotely.
        if (stackId != 0L || isExternal) {
            menuInflater.inflate(R.menu.menu_compose_editor, menu)
            // Deleting a Room row makes no sense for an untracked project.
            menu.findItem(R.id.action_delete)?.isVisible = !isExternal
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        val current = session ?: return super.onOptionsItemSelected(item)
        if (item.itemId == R.id.action_logs) {
            openLogs()
            return true
        }
        if (isExternal) {
            val name = externalName ?: return super.onOptionsItemSelected(item)
            val file = externalConfigFile ?: return super.onOptionsItemSelected(item)
            return when (item.itemId) {
                R.id.action_up -> runComposeAction { current.transport.composeUpByProject(name, file) }
                R.id.action_down -> runComposeAction { current.transport.composeDownByProject(name, file) }
                R.id.action_pull -> runComposeAction { current.transport.composePullByProject(name, file) }
                R.id.action_restart -> runComposeAction {
                    current.transport.composeRestartByProject(name, file)
                }
                R.id.action_services -> runComposeAction {
                    current.transport.composePsByProject(name, file)
                }
                else -> super.onOptionsItemSelected(item)
            }
        }
        val path = stack?.remotePath ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.action_up -> runComposeAction { current.transport.composeUp(path) }
            R.id.action_down -> runComposeAction { current.transport.composeDown(path) }
            R.id.action_pull -> runComposeAction { current.transport.composePull(path) }
            R.id.action_restart -> runComposeAction { current.transport.composeRestart(path) }
            R.id.action_services -> runComposeAction { current.transport.composePs(path) }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openLogs() {
        val name = stack?.name ?: externalName ?: return
        val intent = Intent(this, StackLogsActivity::class.java)
        intent.putExtra(StackLogsActivity.EXTRA_HOST_ID, hostId)
        intent.putExtra(StackLogsActivity.EXTRA_STACK_NAME, name)
        val trackedPath = stack?.remotePath
        if (trackedPath != null) {
            intent.putExtra(StackLogsActivity.EXTRA_STACK_DIR, trackedPath)
        } else {
            intent.putExtra(StackLogsActivity.EXTRA_CONFIG_FILE, externalConfigFile)
            intent.putExtra(StackLogsActivity.EXTRA_EXTERNAL_NAME, externalName)
        }
        startActivity(intent)
    }

    private fun runComposeAction(action: suspend () -> DockerResult<String>): Boolean {
        val name = stack?.name ?: externalName ?: return true
        // compose up/down/restart are not idempotent under concurrency — one
        // in-flight action at a time, however fast the menu is tapped.
        if (actionRunning) return true
        actionRunning = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = action()
                if (!isAlive()) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is DockerResult.Success -> DockerInspectDialog.show(
                        this@ComposeEditorActivity,
                        getString(
                            R.string.docker_stack_action_output_title,
                            DockerText.display(name)
                        ),
                        DockerText.block(result.value).ifBlank {
                            getString(R.string.docker_action_success)
                        }
                    )
                    else -> DockerErrorPresenter.present(this@ComposeEditorActivity, result)
                }
            } finally {
                actionRunning = false
            }
        }
        return true
    }

    private fun confirmDelete() {
        // Sanitized so a crafted name cannot bidi-reorder the confirmation text
        // into naming a different stack than the one that will be deleted.
        val name = DockerText.display(stack?.name ?: return)
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setMessage(getString(R.string.docker_stack_delete_message, name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteStack() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Best-effort compose down, then remove the Room row (remote files kept). */
    private fun deleteStack() {
        val current = session ?: return
        val existing = stack ?: return
        // A second confirm tap must not re-run compose down and re-delete.
        if (actionRunning) return
        actionRunning = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            current.transport.composeDown(existing.remotePath)
            app.database.composeStackDao().delete(existing)
            io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                applicationContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.COMPOSE_STACK,
                io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(existing))
            finish()
        }
    }
}
