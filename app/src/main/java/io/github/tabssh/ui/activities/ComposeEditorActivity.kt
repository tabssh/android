package io.github.tabssh.ui.activities

import android.content.ClipboardManager
import android.content.Context
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
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import java.io.StringReader

/**
 * Compose stack editor (PLAN.AI.md step 25): paste-first new-stack flow —
 * name it, paste the YAML, validate, and push to
 * {composeBase}/{name}/compose.yaml plus an optional .env. Edit mode loads
 * both files back from the host and adds the compose lifecycle menu.
 * FLAG_SECURE because .env content is typically secrets.
 */
class ComposeEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
        const val EXTRA_STACK_ID = "compose_stack_id"
        private val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var buttonPaste: MaterialButton
    private lateinit var editCompose: TextInputEditText
    private lateinit var editEnv: TextInputEditText
    private lateinit var progressBar: ProgressBar
    private lateinit var buttonCancel: MaterialButton
    private lateinit var buttonSave: MaterialButton

    private var hostId: Long = 0
    private var stackId: Long = 0
    private var stack: ComposeStack? = null
    private var session: DockerSessionManager.DockerSession? = null

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

        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        buttonPaste = findViewById(R.id.button_paste)
        editCompose = findViewById(R.id.edit_compose)
        editEnv = findViewById(R.id.edit_env)
        progressBar = findViewById(R.id.progress_bar)
        buttonCancel = findViewById(R.id.button_cancel)
        buttonSave = findViewById(R.id.button_save)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(
            if (stackId == 0L) R.string.docker_stack_new_title else R.string.docker_stack_edit_title
        )
        toolbar.setNavigationOnClickListener { finish() }

        buttonPaste.setOnClickListener { pasteFromClipboard() }
        buttonCancel.setOnClickListener { finish() }
        buttonSave.setOnClickListener { save() }

        acquireSession()
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val result = DockerSessionManager.acquire(app, hostId)) {
                is DockerResult.Success -> {
                    session = result.value
                    if (stackId != 0L) {
                        loadStack()
                    } else {
                        progressBar.visibility = View.GONE
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
            if (loaded == null) {
                finish()
                return@launch
            }
            stack = loaded
            editName.setText(loaded.name)
            // The name keys the remote directory — immutable once created.
            editName.isEnabled = false
            val compose = current.transport.readRemoteFile("${loaded.remotePath}/compose.yaml")
            compose.valueOrNull()?.let { editCompose.setText(it) }
            val env = current.transport.readRemoteFile("${loaded.remotePath}/.env")
            env.valueOrNull()?.let { editEnv.setText(it) }
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
        } catch (e: Exception) {
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
        if (stackId == 0L && !NAME_REGEX.matches(name)) {
            Toast.makeText(this, R.string.docker_stack_error_name, Toast.LENGTH_SHORT).show()
            return
        }
        val compose = editCompose.text?.toString().orEmpty()
        if (!validateYaml(compose)) return
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
            if (written !is DockerResult.Success) {
                progressBar.visibility = View.GONE
                buttonSave.isEnabled = true
                DockerErrorPresenter.present(this@ComposeEditorActivity, written)
                return@launch
            }
            if (env.isNotBlank()) {
                val envWritten = current.transport.writeRemoteFile("$remotePath/.env", env)
                if (envWritten !is DockerResult.Success) {
                    progressBar.visibility = View.GONE
                    buttonSave.isEnabled = true
                    DockerErrorPresenter.present(this@ComposeEditorActivity, envWritten)
                    return@launch
                }
            }
            if (existing == null) {
                stackId = dao.insert(
                    ComposeStack(dockerHostId = hostId, name = name, remotePath = remotePath)
                )
                stack = dao.getById(stackId)
            } else {
                dao.update(existing.copy(updatedAt = System.currentTimeMillis()))
            }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Lifecycle actions only make sense once the stack exists remotely.
        if (stackId != 0L) {
            menuInflater.inflate(R.menu.menu_compose_editor, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val current = session ?: return super.onOptionsItemSelected(item)
        val path = stack?.remotePath ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
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

    private fun runComposeAction(action: suspend () -> DockerResult<String>): Boolean {
        val name = stack?.name ?: return true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = action()
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> DockerInspectDialog.show(
                    this@ComposeEditorActivity,
                    getString(R.string.docker_stack_action_output_title, name),
                    result.value.ifBlank { getString(R.string.docker_action_success) }
                )
                else -> DockerErrorPresenter.present(this@ComposeEditorActivity, result)
            }
        }
        return true
    }

    private fun confirmDelete() {
        val name = stack?.name ?: return
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
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            current.transport.composeDown(existing.remotePath)
            app.database.composeStackDao().delete(existing)
            finish()
        }
    }
}
