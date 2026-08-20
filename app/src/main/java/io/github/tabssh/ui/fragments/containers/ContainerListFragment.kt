package io.github.tabssh.ui.fragments.containers

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.containers.ComposeMembership
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.EngineCapability
import io.github.tabssh.containers.transport.ContainerAction
import io.github.tabssh.containers.transport.ContainerSummary
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.activities.ContainerDetailActivity
import io.github.tabssh.ui.activities.SingleContainerConfigEditorActivity
import io.github.tabssh.ui.adapters.ContainerListAdapter
import io.github.tabssh.ui.dialogs.AutoUpdatePolicyDialog
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerExecLauncher
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Containers destination — the primary tab. Rows show status dot, name,
 * image, ports, pending-update badge, and an always-visible action strip:
 * state-aware start/stop, one-tap logs, one-tap exec terminal, and a
 * state-aware overflow sheet (long-press is a shortcut to the same sheet).
 * List-load failures render inline with a retry button; FAB creates a
 * new single-container run config.
 */
class ContainerListFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var textEmptyHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ContainerListAdapter

    // Reentrancy guard: the action strip and the sheet rows stay tappable while
    // a lifecycle call is in flight, and double-tapping start/stop/kill/remove
    // fires the operation twice against the daemon.
    private var actionInFlight = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_container_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler_list)
        emptyState = view.findViewById(R.id.empty_state)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmptyHint = view.findViewById(R.id.text_empty_hint)
        textEmpty.setText(R.string.container_containers_empty)
        textEmptyHint.setText(R.string.container_containers_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container)
        fabAction.contentDescription = getString(R.string.container_new_container_config_desc)

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        adapter = ContainerListAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { container ->
            openContainerDetail(container, ContainerDetailActivity.TAB_INSPECT)
        }
        adapter.setOnPrimaryActionListener { container ->
            runAction(container, primaryActionFor(container))
        }
        adapter.setOnLogsClickListener { container ->
            openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
        }
        adapter.setOnTerminalClickListener { container ->
            enterTerminal(container)
        }
        adapter.setOnMoreClickListener { container ->
            showContainerSheet(container)
        }

        fabAction.setOnClickListener {
            val intent = Intent(requireContext(), SingleContainerConfigEditorActivity::class.java)
            intent.putExtra(SingleContainerConfigEditorActivity.EXTRA_HOST_ID, manager.hostId)
            startActivity(intent)
        }

        observePendingUpdates()

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    /** Pending-update badges from policy rows with pendingUpdateDigest set. */
    private fun observePendingUpdates() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.containerAutoUpdatePolicyDao()
                .getPoliciesForHost(manager.hostId)
                .collect { policies ->
                    if (!isAdded) return@collect
                    adapter.updatePendingNames(
                        policies.filter { it.pendingUpdateDigest != null }
                            .map { it.containerNameOrStackName }
                            .toSet()
                    )
                }
        }
    }

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        val engine = session.host.engineType()
        startLoad {
            // The project list is only needed to recognise stack members, so
            // it is fetched only on engines that have compose at all, and
            // concurrently with the container listing.
            val loaded = coroutineScope {
                val containers = async { session.transport.listContainers(all = true) }
                val projects = async {
                    if (engine.supports(EngineCapability.COMPOSE_STACKS)) {
                        knownProjects(session)
                    } else {
                        emptySet()
                    }
                }
                containers.await() to projects.await()
            }
            if (!isAdded) return@startLoad
            val (result, projects) = loaded
            progressBar.visibility = View.GONE
            when (result) {
                is ContainerResult.Success -> showContainers(result.value, projects)
                else -> showLoadError(result)
            }
        }
    }

    /**
     * Compose stack members live on the Stacks tab, so they are filtered out
     * here — the dashboard still counts them (IDEA.md § Container host
     * management). Membership detection is [ComposeMembership].
     */
    private fun showContainers(all: List<ContainerSummary>, projects: Set<String>) {
        val standalone = ComposeMembership.standaloneOnly(all, projects) { container ->
            ComposeMembership.ContainerIdentity(
                name = container.names.firstOrNull().orEmpty(),
                labels = container.labels
            )
        }
        adapter.updateList(standalone)
        val empty = standalone.isEmpty()
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        // An empty list with containers on the host means every one of them
        // belongs to a stack — say so instead of claiming the host is idle.
        textEmpty.setText(
            if (empty && all.isNotEmpty()) {
                R.string.container_containers_all_in_stacks
            } else {
                R.string.container_containers_empty
            }
        )
        textEmptyHint.setText(
            if (empty && all.isNotEmpty()) {
                R.string.container_containers_all_in_stacks_hint
            } else {
                R.string.container_containers_empty_hint
            }
        )
    }

    /** Stack projects on this host: the app's tracked stacks plus discovered ones. */
    private suspend fun knownProjects(
        session: ContainerSessionManager.ContainerSession
    ): Set<String> {
        val tracked = app.database.composeStackDao()
            .getStacksForHostList(session.host.id)
            .map { it.name }
        val discovered = session.transport.composeLs().valueOrNull().orEmpty().map { it.name }
        return (tracked + discovered).toSet()
    }

    /** Inline error with retry instead of a dialog over a blank list. */
    private fun showLoadError(result: ContainerResult<*>) {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        textError.text = ContainerErrorPresenter.messageFor(requireContext(), result)
    }

    private fun openContainerDetail(container: ContainerSummary, tab: Int) {
        if (!isAdded) return
        val intent = Intent(requireContext(), ContainerDetailActivity::class.java)
        intent.putExtra(ContainerDetailActivity.EXTRA_HOST_ID, manager.hostId)
        intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, container.id)
        intent.putExtra(
            ContainerDetailActivity.EXTRA_CONTAINER_NAME,
            container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        )
        intent.putExtra(ContainerDetailActivity.EXTRA_INITIAL_TAB, tab)
        startActivity(intent)
    }

    /** Stop when running, unpause when paused, start otherwise. */
    private fun primaryActionFor(container: ContainerSummary): ContainerAction {
        return when (container.state) {
            "running" -> ContainerAction.STOP
            "paused" -> ContainerAction.UNPAUSE
            else -> ContainerAction.START
        }
    }

    /**
     * State-aware overflow sheet: only actions valid for the container's
     * current state, ordered by frequency, destructive kill/remove last.
     */
    private fun showContainerSheet(container: ContainerSummary) {
        if (!isAdded) return
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
                actions += ContainerActionSheet.Action(R.drawable.ic_pause, getString(R.string.container_action_pause)) {
                    runAction(container, ContainerAction.PAUSE)
                }
            }
            "paused" -> {
                actions += ContainerActionSheet.Action(R.drawable.ic_play, getString(R.string.container_action_unpause)) {
                    runAction(container, ContainerAction.UNPAUSE)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_logs, getString(R.string.container_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
                actions += ContainerActionSheet.Action(R.drawable.ic_stop, getString(R.string.container_action_stop)) {
                    runAction(container, ContainerAction.STOP)
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
        actions += ContainerActionSheet.Action(R.drawable.ic_download, getString(R.string.container_action_auto_update)) {
            AutoUpdatePolicyDialog.show(requireContext(), app, manager.hostId, name)
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_info, getString(R.string.container_action_details)) {
            openContainerDetail(container, ContainerDetailActivity.TAB_INSPECT)
        }
        if (container.state == "running" || container.state == "paused") {
            actions += ContainerActionSheet.Action(
                R.drawable.ic_flash, getString(R.string.container_action_kill), destructive = true
            ) {
                confirmKill(container, name)
            }
        }
        actions += ContainerActionSheet.Action(
            R.drawable.ic_clear, getString(R.string.container_action_remove), destructive = true
        ) {
            confirmRemove(container, name)
        }
        ContainerActionSheet.show(requireContext(), name, container.image, actions)
    }

    /** One-tap docker exec into the container, opening a terminal tab. */
    private fun enterTerminal(container: ContainerSummary) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        progressBar.visibility = View.VISIBLE
        Toast.makeText(requireContext(), R.string.container_terminal_probing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val intent = ContainerExecLauncher.buildExecIntent(
                    requireContext(), current, manager.hostId, container.id, name
                )
                if (!isAdded) return@launch
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // buildExecIntent runs a shell probe over the session runner,
                // which throws outright when the transport has gone away —
                // unlike the ContainerResult-returning transport calls.
                if (!isAdded) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.container_error_detail_fmt, ContainerText.display(e.message)),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                actionInFlight = false
                if (isAdded) progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Kill sends SIGKILL — unsaved in-container state is lost, so it is gated
     * behind the same confirmation as remove rather than firing on one tap.
     */
    private fun confirmKill(container: ContainerSummary, name: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(ContainerText.display(name))
            .setMessage(
                getString(R.string.container_kill_container_message, ContainerText.display(name))
            )
            .setPositiveButton(R.string.container_action_kill) { _, _ ->
                runAction(container, ContainerAction.KILL)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(container: ContainerSummary, name: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setMessage(getString(R.string.container_remove_container_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                removeContainer(container)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeContainer(container: ContainerSummary) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.removeContainer(container.id, force = true)
                if (!isAdded) return@launch
                when (result) {
                    is ContainerResult.Success -> onSessionReady(current)
                    else -> ContainerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
                if (isAdded) progressBar.visibility = View.GONE
            }
        }
    }

    private fun runAction(container: ContainerSummary, action: ContainerAction) {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = current.transport.containerAction(container.id, action)
                if (!isAdded) return@launch
                when (result) {
                    is ContainerResult.Success -> {
                        Toast.makeText(
                            requireContext(), R.string.container_action_success, Toast.LENGTH_SHORT
                        ).show()
                        onSessionReady(current)
                    }
                    else -> ContainerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                actionInFlight = false
                if (isAdded) progressBar.visibility = View.GONE
            }
        }
    }
}
