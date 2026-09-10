package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ComposeMembership
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerAction
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerSummary
import io.github.tabssh.ui.adapters.ContainerListAdapter
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerExecLauncher
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.utils.tabSSHApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Compose stack detail screen: the stack's own member containers, presented
 * with the same per-row status/logs/terminal/lifecycle affordances as the
 * Containers tab ([io.github.tabssh.ui.fragments.containers
 * .ContainerListFragment]) — the "no way to enter containers that are part
 * of a stack" gap. Membership is decided by [ComposeMembership] against this
 * stack's own name, so it agrees with the Containers tab's exclusion list.
 * Reached by tapping a stack row; editing the compose file moved to the
 * toolbar's explicit Edit action so it no longer shares the tap target.
 */
class StackDetailActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
        const val EXTRA_STACK_NAME = "compose_stack_name"
        const val EXTRA_STACK_ID = "compose_stack_id"
        const val EXTRA_STACK_DIR = "compose_stack_dir"
        const val EXTRA_CONFIG_FILE = "compose_config_file"
        const val EXTRA_EXTERNAL_NAME = "compose_external_name"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ContainerListAdapter

    private var hostId: Long = 0
    private var stackName: String = ""
    private var stackId: Long = 0
    private var stackDir: String? = null
    private var configFile: String? = null
    private var externalName: String? = null
    private var session: ContainerSessionManager.ContainerSession? = null

    // Same reentrancy guard as ContainerListFragment/ContainerDetailActivity —
    // one lifecycle/exec call at a time against a given container.
    private var actionInFlight = false

    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stack_detail)

        app = tabSSHApp
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        stackName = intent.getStringExtra(EXTRA_STACK_NAME).orEmpty()
        stackId = intent.getLongExtra(EXTRA_STACK_ID, 0)
        stackDir = intent.getStringExtra(EXTRA_STACK_DIR)
        configFile = intent.getStringExtra(EXTRA_CONFIG_FILE)
        externalName = intent.getStringExtra(EXTRA_EXTERNAL_NAME)

        toolbar = findViewById(R.id.app_bar)
        recyclerView = findViewById(R.id.recycler_list)
        emptyState = findViewById(R.id.empty_state)
        progressBar = findViewById(R.id.progress_bar)

        setSupportActionBar(toolbar)
        // Stack name comes from Room or `docker compose ls` (external) — sanitize
        // before it becomes the toolbar title.
        supportActionBar?.title = ContainerText.display(stackName)

        adapter = ContainerListAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { container ->
            openContainerDetail(container, ContainerDetailActivity.TAB_INSPECT)
        }
        adapter.setOnPrimaryActionListener { container -> runAction(container, primaryActionFor(container)) }
        adapter.setOnLogsClickListener { container ->
            openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
        }
        adapter.setOnTerminalClickListener { container -> enterTerminal(container) }
        adapter.setOnMoreClickListener { container -> showContainerSheet(container) }

        acquireSession()
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ContainerSessionManager.acquire(app, hostId)
            if (!isAlive) return@launch
            when (result) {
                is ContainerResult.Success -> {
                    session = result.value
                    loadContainers()
                }
                else -> {
                    progressBar.visibility = View.GONE
                    ContainerErrorPresenter.present(this@StackDetailActivity, result)
                }
            }
        }
    }

    private fun loadContainers() {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = current.transport.listContainers(all = true)
            if (!isAlive) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is ContainerResult.Success -> showContainers(result.value)
                else -> ContainerErrorPresenter.present(this@StackDetailActivity, result)
            }
        }
    }

    /** Only this stack's own members — same [ComposeMembership] rule the Containers tab excludes by. */
    private fun showContainers(all: List<ContainerSummary>) {
        val knownProjects = setOf(stackName)
        val members = all.filter { container ->
            val name = container.names.firstOrNull().orEmpty()
            ComposeMembership.projectOf(name, container.labels, knownProjects) == stackName
        }
        adapter.updateList(members)
        val empty = members.isEmpty()
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun openContainerDetail(container: ContainerSummary, tab: Int) {
        if (!isAlive) return
        val intent = Intent(this, ContainerDetailActivity::class.java)
        intent.putExtra(ContainerDetailActivity.EXTRA_HOST_ID, hostId)
        intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, container.id)
        intent.putExtra(
            ContainerDetailActivity.EXTRA_CONTAINER_NAME,
            container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        )
        intent.putExtra(ContainerDetailActivity.EXTRA_INITIAL_TAB, tab)
        startActivity(intent)
    }

    /** Stop when running, unpause when paused, start otherwise — mirrors ContainerListFragment. */
    private fun primaryActionFor(container: ContainerSummary): ContainerAction = when (container.state) {
        "running" -> ContainerAction.STOP
        "paused" -> ContainerAction.UNPAUSE
        else -> ContainerAction.START
    }

    private fun showContainerSheet(container: ContainerSummary) {
        if (!isAlive) return
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        val actions = mutableListOf<ContainerActionSheet.Action>()
        when (container.state) {
            "running" -> {
                actions += ContainerActionSheet.Action(R.drawable.ic_logs, getString(R.string.container_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_terminal, getString(R.string.container_action_terminal)) {
                    enterTerminal(container)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_refresh, getString(R.string.container_action_restart)) {
                    runAction(container, ContainerAction.RESTART)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_stop, getString(R.string.container_action_stop)) {
                    runAction(container, ContainerAction.STOP)
                }
            }
            "paused" -> {
                actions += ContainerActionSheet.Action(R.drawable.ic_play, getString(R.string.container_action_unpause)) {
                    runAction(container, ContainerAction.UNPAUSE)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_logs, getString(R.string.container_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
            }
            else -> {
                actions += ContainerActionSheet.Action(R.drawable.ic_play, getString(R.string.container_action_start)) {
                    runAction(container, ContainerAction.START)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_logs, getString(R.string.container_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
            }
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_info, getString(R.string.container_action_details)) {
            openContainerDetail(container, ContainerDetailActivity.TAB_INSPECT)
        }
        ContainerActionSheet.show(this, name, container.image, actions)
    }

    private fun enterTerminal(container: ContainerSummary) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, R.string.container_terminal_probing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val intent = ContainerExecLauncher.buildExecIntent(
                    this@StackDetailActivity, current, hostId, container.id, name
                )
                if (!isAlive) return@launch
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive) return@launch
                Toast.makeText(
                    this@StackDetailActivity,
                    getString(R.string.container_error_detail_fmt, ContainerText.display(e.message)),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                actionInFlight = false
                if (isAlive) progressBar.visibility = View.GONE
            }
        }
    }

    private fun runAction(container: ContainerSummary, action: ContainerAction): Boolean {
        val current = session ?: return true
        if (actionInFlight) return true
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = current.transport.containerAction(container.id, action)
                if (!isAlive) return@launch
                when (result) {
                    is ContainerResult.Success -> {
                        Toast.makeText(
                            this@StackDetailActivity, R.string.container_action_success, Toast.LENGTH_SHORT
                        ).show()
                        loadContainers()
                    }
                    else -> ContainerErrorPresenter.present(this@StackDetailActivity, result)
                }
            } finally {
                actionInFlight = false
                if (isAlive) progressBar.visibility = View.GONE
            }
        }
        return true
    }

    private fun openEditor() {
        val intent = Intent(this, ComposeEditorActivity::class.java)
        intent.putExtra(ComposeEditorActivity.EXTRA_HOST_ID, hostId)
        val dir = stackDir
        val name = externalName
        val file = configFile
        if (dir != null) {
            intent.putExtra(ComposeEditorActivity.EXTRA_STACK_ID, stackId)
        } else if (name != null && file != null) {
            intent.putExtra(ComposeEditorActivity.EXTRA_EXTERNAL_CONFIG_FILE, file)
            intent.putExtra(ComposeEditorActivity.EXTRA_EXTERNAL_NAME, name)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_stack_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadContainers()
                true
            }
            R.id.action_edit -> {
                openEditor()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
