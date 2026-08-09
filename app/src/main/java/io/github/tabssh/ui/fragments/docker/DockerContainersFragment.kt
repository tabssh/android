package io.github.tabssh.ui.fragments.docker

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
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.ContainerAction
import io.github.tabssh.docker.transport.DockerContainerSummary
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.ui.activities.ContainerDetailActivity
import io.github.tabssh.ui.activities.SingleContainerConfigEditorActivity
import io.github.tabssh.ui.adapters.DockerContainerAdapter
import io.github.tabssh.ui.dialogs.AutoUpdatePolicyDialog
import io.github.tabssh.ui.dialogs.DockerActionSheet
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.utils.DockerExecLauncher
import kotlinx.coroutines.launch

/**
 * Containers destination — the primary tab. Rows show status dot, name,
 * image, ports, pending-update badge, and an always-visible action strip:
 * state-aware start/stop, one-tap logs, one-tap exec terminal, and a
 * state-aware overflow sheet (long-press is a shortcut to the same sheet).
 * List-load failures render inline with a retry button; FAB creates a
 * new single-container run config.
 */
class DockerContainersFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: DockerContainerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_docker_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recyclerView = view.findViewById(R.id.recycler_list)
        emptyState = view.findViewById(R.id.empty_state)
        errorState = view.findViewById(R.id.error_state)
        textError = view.findViewById(R.id.text_error)
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.docker_containers_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.docker_containers_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_docker_container)
        fabAction.contentDescription = getString(R.string.docker_new_container_config_desc)

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        adapter = DockerContainerAdapter()
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

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = session.transport.listContainers(all = true)
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    adapter.updateList(result.value)
                    val empty = result.value.isEmpty()
                    recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
                else -> showLoadError(result)
            }
        }
    }

    /** Inline error with retry instead of a dialog over a blank list. */
    private fun showLoadError(result: DockerResult<*>) {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        textError.text = DockerErrorPresenter.messageFor(requireContext(), result)
    }

    private fun openContainerDetail(container: DockerContainerSummary, tab: Int) {
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
    private fun primaryActionFor(container: DockerContainerSummary): ContainerAction {
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
    private fun showContainerSheet(container: DockerContainerSummary) {
        if (!isAdded) return
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        val actions = mutableListOf<DockerActionSheet.Action>()
        when (container.state) {
            "running" -> {
                actions += DockerActionSheet.Action(R.drawable.ic_logs, getString(R.string.docker_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_terminal, getString(R.string.docker_action_terminal)) {
                    enterTerminal(container)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_refresh, getString(R.string.docker_action_restart)) {
                    runAction(container, ContainerAction.RESTART)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_stop, getString(R.string.docker_action_stop)) {
                    runAction(container, ContainerAction.STOP)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_pause, getString(R.string.docker_action_pause)) {
                    runAction(container, ContainerAction.PAUSE)
                }
            }
            "paused" -> {
                actions += DockerActionSheet.Action(R.drawable.ic_play, getString(R.string.docker_action_unpause)) {
                    runAction(container, ContainerAction.UNPAUSE)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_logs, getString(R.string.docker_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_stop, getString(R.string.docker_action_stop)) {
                    runAction(container, ContainerAction.STOP)
                }
            }
            else -> {
                actions += DockerActionSheet.Action(R.drawable.ic_play, getString(R.string.docker_action_start)) {
                    runAction(container, ContainerAction.START)
                }
                actions += DockerActionSheet.Action(R.drawable.ic_logs, getString(R.string.docker_action_logs)) {
                    openContainerDetail(container, ContainerDetailActivity.TAB_LOGS)
                }
            }
        }
        actions += DockerActionSheet.Action(R.drawable.ic_download, getString(R.string.docker_action_auto_update)) {
            AutoUpdatePolicyDialog.show(requireContext(), app, manager.hostId, name)
        }
        actions += DockerActionSheet.Action(R.drawable.ic_info, getString(R.string.docker_action_details)) {
            openContainerDetail(container, ContainerDetailActivity.TAB_INSPECT)
        }
        if (container.state == "running" || container.state == "paused") {
            actions += DockerActionSheet.Action(
                R.drawable.ic_flash, getString(R.string.docker_action_kill), destructive = true
            ) {
                runAction(container, ContainerAction.KILL)
            }
        }
        actions += DockerActionSheet.Action(
            R.drawable.ic_clear, getString(R.string.docker_action_remove), destructive = true
        ) {
            confirmRemove(container, name)
        }
        DockerActionSheet.show(requireContext(), name, container.image, actions)
    }

    /** One-tap docker exec into the container, opening a terminal tab. */
    private fun enterTerminal(container: DockerContainerSummary) {
        val current = session ?: return
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        progressBar.visibility = View.VISIBLE
        Toast.makeText(requireContext(), R.string.docker_terminal_probing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val intent = DockerExecLauncher.buildExecIntent(
                requireContext(), current, manager.hostId, container.id, name
            )
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            startActivity(intent)
        }
    }

    private fun confirmRemove(container: DockerContainerSummary, name: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setMessage(getString(R.string.docker_remove_container_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                removeContainer(container)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeContainer(container: DockerContainerSummary) {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.removeContainer(container.id, force = true)
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> onSessionReady(current)
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun runAction(container: DockerContainerSummary, action: ContainerAction) {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = current.transport.containerAction(container.id, action)
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    Toast.makeText(
                        requireContext(), R.string.docker_action_success, Toast.LENGTH_SHORT
                    ).show()
                    onSessionReady(current)
                }
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }
}
