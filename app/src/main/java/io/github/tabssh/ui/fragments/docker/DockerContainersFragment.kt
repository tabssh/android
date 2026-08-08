package io.github.tabssh.ui.fragments.docker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import kotlinx.coroutines.launch

/**
 * Containers destination (PLAN.AI.md step 22): status dot, name, image,
 * ports, and pending-update badge from the ContainerAutoUpdatePolicy Flow.
 * Tap opens ContainerDetailActivity; long-press offers quick lifecycle
 * actions and the auto-update policy dialog; FAB starts a new run-config.
 */
class DockerContainersFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
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
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.docker_containers_empty)
        fabAction.contentDescription = getString(R.string.docker_new_container_config_desc)

        adapter = DockerContainerAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { container ->
            openContainerDetail(container)
        }
        adapter.setOnItemLongClickListener { container ->
            showContainerMenu(container)
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
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun openContainerDetail(container: DockerContainerSummary) {
        if (!isAdded) return
        val intent = Intent(requireContext(), ContainerDetailActivity::class.java)
        intent.putExtra(ContainerDetailActivity.EXTRA_HOST_ID, manager.hostId)
        intent.putExtra(ContainerDetailActivity.EXTRA_CONTAINER_ID, container.id)
        intent.putExtra(
            ContainerDetailActivity.EXTRA_CONTAINER_NAME,
            container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        )
        startActivity(intent)
    }

    private fun showContainerMenu(container: DockerContainerSummary) {
        if (!isAdded) return
        val name = container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12)
        val actions = listOf(
            getString(R.string.docker_action_start) to ContainerAction.START,
            getString(R.string.docker_action_stop) to ContainerAction.STOP,
            getString(R.string.docker_action_restart) to ContainerAction.RESTART,
            getString(R.string.docker_action_pause) to ContainerAction.PAUSE,
            getString(R.string.docker_action_unpause) to ContainerAction.UNPAUSE,
            getString(R.string.docker_action_kill) to ContainerAction.KILL
        )
        val options = actions.map { it.first } + getString(R.string.docker_action_auto_update)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                if (which < actions.size) {
                    runAction(container, actions[which].second)
                } else {
                    AutoUpdatePolicyDialog.show(requireContext(), app, manager.hostId, name)
                }
            }
            .show()
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
