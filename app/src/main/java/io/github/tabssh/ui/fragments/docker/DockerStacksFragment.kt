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
import io.github.tabssh.docker.transport.ComposeLsEntry
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.ui.activities.ComposeEditorActivity
import io.github.tabssh.ui.activities.StackLogsActivity
import io.github.tabssh.ui.adapters.ComposeStackAdapter
import io.github.tabssh.ui.adapters.StackListItem
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.dialogs.DockerInspectDialog
import kotlinx.coroutines.launch

/**
 * Compose stacks destination (PLAN.AI.md step 25): Room-backed stack list,
 * merged with projects discovered via `docker compose ls` that have no Room
 * row (TODO.AI.md § D). FAB opens the paste-first editor, tap edits,
 * long-press runs compose lifecycle actions with their command output shown
 * in a dialog.
 */
class DockerStacksFragment : DockerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ComposeStackAdapter

    private var trackedStacks: List<ComposeStack> = emptyList()
    private var externalEntries: List<ComposeLsEntry> = emptyList()

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

        adapter.setOnItemClickListener { item -> openEditor(item) }
        adapter.setOnItemLongClickListener { item -> showStackMenu(item) }

        fabAction.setOnClickListener { openEditor(null) }

        observeStacks()

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    /** Room is the source of truth for tracked stacks. */
    private fun observeStacks() {
        viewLifecycleOwner.lifecycleScope.launch {
            app.database.composeStackDao().getStacksForHost(manager.hostId).collect { stacks ->
                if (!isAdded) return@collect
                trackedStacks = stacks
                renderList()
            }
        }
    }

    override fun onSessionReady(session: DockerSessionManager.DockerSession) {
        refreshStatuses(session)
        discoverExternalStacks(session)
    }

    /** `docker compose ls` — projects on the host with no Room row yet. */
    private fun discoverExternalStacks(session: DockerSessionManager.DockerSession) {
        viewLifecycleOwner.lifecycleScope.launch {
            // TransportUnavailable (no compose on the host) just means "no
            // external stacks to show" — not a failure worth surfacing here.
            val result = session.transport.composeLs()
            if (!isAdded) return@launch
            externalEntries = result.valueOrNull().orEmpty()
            renderList()
        }
    }

    /** Merge tracked + discovered stacks, excluding externals already tracked by name. */
    private fun renderList() {
        val trackedNames = trackedStacks.map { it.name }.toSet()
        val items = trackedStacks.map { StackListItem.Tracked(it) } +
            externalEntries.filter { it.name !in trackedNames }.map { StackListItem.External(it) }
        adapter.updateList(items)
        val empty = items.isEmpty()
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** Refresh each tracked stack's per-service status snapshot via compose ps. */
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

    private fun openEditor(item: StackListItem?) {
        if (!isAdded) return
        val intent = Intent(requireContext(), ComposeEditorActivity::class.java)
        intent.putExtra(ComposeEditorActivity.EXTRA_HOST_ID, manager.hostId)
        when (item) {
            is StackListItem.Tracked -> intent.putExtra(
                ComposeEditorActivity.EXTRA_STACK_ID, item.stack.id
            )
            is StackListItem.External -> {
                intent.putExtra(
                    ComposeEditorActivity.EXTRA_EXTERNAL_CONFIG_FILE, item.entry.primaryConfigFile
                )
                intent.putExtra(ComposeEditorActivity.EXTRA_EXTERNAL_NAME, item.entry.name)
            }
            null -> {}
        }
        startActivity(intent)
    }

    private fun showStackMenu(item: StackListItem) {
        if (!isAdded) return
        val options = buildList {
            add(getString(R.string.docker_stack_action_up))
            add(getString(R.string.docker_stack_action_down))
            add(getString(R.string.docker_stack_action_pull))
            add(getString(R.string.docker_stack_action_restart))
            add(getString(R.string.docker_stack_services))
            add(getString(R.string.docker_action_logs))
            // Deleting a Room row makes no sense for a stack that has none.
            if (item is StackListItem.Tracked) add(getString(R.string.delete))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, which ->
                val current = session ?: return@setItems
                when (which) {
                    0 -> runComposeAction(item) {
                        composeAction(
                            current, item,
                            { t, d -> t.composeUp(d) },
                            { t, n, f -> t.composeUpByProject(n, f) }
                        )
                    }
                    1 -> runComposeAction(item) {
                        composeAction(
                            current, item,
                            { t, d -> t.composeDown(d) },
                            { t, n, f -> t.composeDownByProject(n, f) }
                        )
                    }
                    2 -> runComposeAction(item) {
                        composeAction(
                            current, item,
                            { t, d -> t.composePull(d) },
                            { t, n, f -> t.composePullByProject(n, f) }
                        )
                    }
                    3 -> runComposeAction(item) {
                        composeAction(
                            current, item,
                            { t, d -> t.composeRestart(d) },
                            { t, n, f -> t.composeRestartByProject(n, f) }
                        )
                    }
                    4 -> runComposeAction(item) {
                        composeAction(
                            current, item,
                            { t, d -> t.composePs(d) },
                            { t, n, f -> t.composePsByProject(n, f) }
                        )
                    }
                    5 -> openLogs(item)
                    6 -> if (item is StackListItem.Tracked) confirmDelete(item.stack)
                }
            }
            .show()
    }

    /**
     * Dispatch a compose verb to the tracked-directory transport call for a
     * Room-tracked stack, or the matching *ByProject call for a discovered
     * (external) stack — mirrors the split in [openLogs] and the editor.
     */
    private suspend fun composeAction(
        session: DockerSessionManager.DockerSession,
        item: StackListItem,
        trackedCall: suspend (
            transport: io.github.tabssh.docker.transport.DockerTransport,
            stackDir: String
        ) -> DockerResult<String>,
        externalCall: suspend (
            transport: io.github.tabssh.docker.transport.DockerTransport,
            name: String,
            configFile: String
        ) -> DockerResult<String>
    ): DockerResult<String> = when (item) {
        is StackListItem.Tracked -> trackedCall(session.transport, item.stack.remotePath)
        is StackListItem.External -> externalCall(
            session.transport, item.entry.name, item.entry.primaryConfigFile
        )
    }

    private fun runComposeAction(
        item: StackListItem,
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
                        getString(R.string.docker_stack_action_output_title, item.name),
                        result.value.ifBlank { getString(R.string.docker_action_success) }
                    )
                    session?.let {
                        refreshStatuses(it)
                        discoverExternalStacks(it)
                    }
                }
                else -> DockerErrorPresenter.present(requireContext(), result)
            }
        }
    }

    private fun openLogs(item: StackListItem) {
        if (!isAdded) return
        val intent = Intent(requireContext(), StackLogsActivity::class.java)
        intent.putExtra(StackLogsActivity.EXTRA_HOST_ID, manager.hostId)
        intent.putExtra(StackLogsActivity.EXTRA_STACK_NAME, item.name)
        when (item) {
            is StackListItem.Tracked ->
                intent.putExtra(StackLogsActivity.EXTRA_STACK_DIR, item.stack.remotePath)
            is StackListItem.External -> {
                intent.putExtra(StackLogsActivity.EXTRA_CONFIG_FILE, item.entry.primaryConfigFile)
                intent.putExtra(StackLogsActivity.EXTRA_EXTERNAL_NAME, item.entry.name)
            }
        }
        startActivity(intent)
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
            io.github.tabssh.sync.tombstone.TombstoneRecorder.record(
                app.applicationContext, io.github.tabssh.sync.tombstone.TombstoneRecorder.COMPOSE_STACK,
                io.github.tabssh.sync.tombstone.TombstoneRecorder.naturalKey(stack))
            if (!isAdded) return@launch
            progressBar.visibility = View.GONE
        }
    }
}
