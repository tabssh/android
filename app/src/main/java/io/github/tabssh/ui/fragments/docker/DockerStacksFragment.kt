package io.github.tabssh.ui.fragments.docker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.ui.activities.ComposeEditorActivity
import io.github.tabssh.ui.adapters.ComposeStackAdapter
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import kotlinx.coroutines.launch

/**
 * Compose stacks destination (PLAN.AI.md step 25): Room-backed stack list,
 * FAB opens the paste-first editor, tap edits, long-press runs compose
 * lifecycle actions with their command output shown in a dialog.
 */
class DockerStacksFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ComposeStackAdapter

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

        textEmpty.setText(R.string.docker_stacks_empty)
        fabAction.contentDescription = getString(R.string.docker_stack_new_title)

        adapter = ComposeStackAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { stack -> openEditor(stack) }
        adapter.setOnItemLongClickListener { stack -> showStackMenu(stack) }

        fabAction.setOnClickListener { openEditor(null) }

        observeStacks()

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    /** Room is the source of truth for the stack list. */
    private fun observeStacks() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.composeStackDao().getStacksForHost(manager.hostId).collect { stacks ->
                if (!isAdded) return@collect
                adapter.updateList(stacks)
                val empty = stacks.isEmpty()
                recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        refreshStatuses(session)
    }

    /** Refresh each stack's per-service status snapshot via compose ps. */
    private fun refreshStatuses(session: DockerSessionManager.DockerSession) {
        viewLifecycleOwner.lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val dao = app.database.composeStackDao()
            for (stack in dao.getStacksForHostList(manager.hostId)) {
                val output = session.transport.composePs(stack.remotePath).valueOrNull()?.trim()
                if (output != null) {
                    val services = output.lines().drop(1).count { it.isNotBlank() }
                    dao.updateLastKnownStatus(
                        stack.id,
                        resources.getQuantityString(
                            R.plurals.docker_stack_running_services, services, services
                        ),
                        System.currentTimeMillis()
                    )
                }
            }
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
        }
    }

    private fun openEditor(stack: ComposeStack?) {
        if (!isAdded) return
        val intent = Intent(requireContext(), ComposeEditorActivity::class.java)
        intent.putExtra(ComposeEditorActivity.EXTRA_HOST_ID, manager.hostId)
        if (stack != null) {
            intent.putExtra(ComposeEditorActivity.EXTRA_STACK_ID, stack.id)
        }
        startActivity(intent)
    }

    private fun showStackMenu(stack: ComposeStack) {
        if (!isAdded) return
        val options = arrayOf(
            getString(R.string.docker_stack_action_up),
            getString(R.string.docker_stack_action_down),
            getString(R.string.docker_stack_action_pull),
            getString(R.string.docker_stack_action_restart),
            getString(R.string.docker_stack_services),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(stack.name)
            .setItems(options) { _, which ->
                val current = session ?: return@setItems
                when (which) {
                    0 -> runComposeAction(stack) { current.transport.composeUp(stack.remotePath) }
                    1 -> runComposeAction(stack) { current.transport.composeDown(stack.remotePath) }
                    2 -> runComposeAction(stack) { current.transport.composePull(stack.remotePath) }
                    3 -> runComposeAction(stack) {
                        current.transport.composeRestart(stack.remotePath)
                    }
                    4 -> runComposeAction(stack) { current.transport.composePs(stack.remotePath) }
                    5 -> confirmDelete(stack)
                }
            }
            .show()
    }

    private fun runComposeAction(
        stack: ComposeStack,
        action: suspend () -> DockerResult<String>
    ) {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = action()
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    DockerInspectDialog.show(
                        requireContext(),
                        getString(R.string.docker_stack_action_output_title, stack.name),
                        result.value.ifBlank { getString(R.string.docker_action_success) }
                    )
                    session?.let { refreshStatuses(it) }
                }
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun confirmDelete(stack: ComposeStack) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(stack.name)
            .setMessage(getString(R.string.docker_stack_delete_message, stack.name))
            .setPositiveButton(R.string.delete) { _, _ -> deleteStack(stack) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Best-effort compose down, then remove the Room row (remote files kept). */
    private fun deleteStack(stack: ComposeStack) {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            session?.transport?.composeDown(stack.remotePath)
            app.database.composeStackDao().delete(stack)
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
        }
    }
}
