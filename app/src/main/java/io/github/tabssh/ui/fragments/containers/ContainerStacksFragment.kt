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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ComposeLsEntry
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.ui.activities.ComposeEditorActivity
import io.github.tabssh.ui.activities.StackLogsActivity
import io.github.tabssh.ui.adapters.ComposeStackAdapter
import io.github.tabssh.ui.adapters.StackListItem
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.dialogs.ContainerInspectDialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Compose stacks destination: Room-backed stack list, merged with projects
 * discovered via `docker compose ls` that have no Room row. FAB opens the
 * paste-first editor, tap edits, and the row's overflow button (or a
 * long-press) opens the compose action sheet with command output shown in a
 * dialog and the destructive delete last.
 */
class ContainerStacksFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ComposeStackAdapter

    private var trackedStacks: List<ComposeStack> = emptyList()
    private var externalEntries: List<ComposeLsEntry> = emptyList()

    // Reentrancy guard: compose up/down/restart are long-running and the sheet
    // rows stay tappable, so a double tap would issue the command twice.
    private var composeActionInFlight = false

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
        textEmpty = view.findViewById(R.id.text_empty)
        progressBar = view.findViewById(R.id.progress_bar)
        fabAction = view.findViewById(R.id.fab_action)

        textEmpty.setText(R.string.container_stacks_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.container_stacks_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container_stack)
        fabAction.contentDescription = getString(R.string.container_stack_new_title)

        adapter = ComposeStackAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { item -> openEditor(item) }
        adapter.setOnItemLongClickListener { item -> showStackMenu(item) }
        adapter.setOnMoreClickListener { item -> showStackMenu(item) }

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

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        // refreshStatuses and discoverExternalStacks run concurrently, but
        // both must be cancelled together when a fresher session/refresh
        // tick supersedes this one — otherwise a stale composePs or
        // composeLs result can land after the newer one and overwrite it.
        startLoad {
            coroutineScope {
                launch { refreshStatuses(session) }
                launch { discoverExternalStacks(session) }
            }
        }
    }

    /** `docker compose ls` — projects on the host with no Room row yet. */
    private suspend fun discoverExternalStacks(session: ContainerSessionManager.ContainerSession) {
        // TransportUnavailable (no compose on the host) just means "no
        // external stacks to show" — not a failure worth surfacing here.
        val result = session.transport.composeLs()
        if (!isAdded) return
        externalEntries = result.valueOrNull().orEmpty()
        renderList()
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
    private suspend fun refreshStatuses(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        val dao = app.database.composeStackDao()
        // Resources are resolved through the application context: the loop
        // suspends on every compose ps, and Fragment.getResources() throws
        // IllegalStateException once the fragment has detached mid-loop.
        val res = app.resources
        for (stack in dao.getStacksForHostList(manager.hostId)) {
            val output = session.transport.composePs(stack.remotePath).valueOrNull()?.trim()
            if (output != null) {
                val services = output.lines().drop(1).count { it.isNotBlank() }
                dao.updateLastKnownStatus(
                    stack.id,
                    res.getQuantityString(
                        R.plurals.container_stack_running_services, services, services
                    ),
                    System.currentTimeMillis()
                )
            }
        }
        if (!isAdded) return
        progressBar.visibility = View.GONE
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

    /**
     * Action sheet ordered by frequency — read-only logs/services first,
     * lifecycle verbs next, service-stopping down and destructive delete last.
     */
    private fun showStackMenu(item: StackListItem) {
        if (!isAdded) return
        val current = session ?: return
        val actions = mutableListOf<ContainerActionSheet.Action>()
        actions += ContainerActionSheet.Action(R.drawable.ic_logs, getString(R.string.container_action_logs)) {
            openLogs(item)
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_info, getString(R.string.container_stack_services)) {
            runComposeAction(item) {
                composeAction(
                    current, item,
                    { t, d -> t.composePs(d) },
                    { t, n, f -> t.composePsByProject(n, f) }
                )
            }
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_arrow_up, getString(R.string.container_stack_action_up)) {
            runComposeAction(item) {
                composeAction(
                    current, item,
                    { t, d -> t.composeUp(d) },
                    { t, n, f -> t.composeUpByProject(n, f) }
                )
            }
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_refresh, getString(R.string.container_stack_action_restart)) {
            runComposeAction(item) {
                composeAction(
                    current, item,
                    { t, d -> t.composeRestart(d) },
                    { t, n, f -> t.composeRestartByProject(n, f) }
                )
            }
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_download, getString(R.string.container_pull_action)) {
            runComposeAction(item) {
                composeAction(
                    current, item,
                    { t, d -> t.composePull(d) },
                    { t, n, f -> t.composePullByProject(n, f) }
                )
            }
        }
        actions += ContainerActionSheet.Action(R.drawable.ic_arrow_down, getString(R.string.container_stack_action_down)) {
            runComposeAction(item) {
                composeAction(
                    current, item,
                    { t, d -> t.composeDown(d) },
                    { t, n, f -> t.composeDownByProject(n, f) }
                )
            }
        }
        // Deleting a Room row makes no sense for a stack that has none.
        if (item is StackListItem.Tracked) {
            actions += ContainerActionSheet.Action(
                R.drawable.ic_clear, getString(R.string.delete), destructive = true
            ) {
                confirmDelete(item.stack)
            }
        }
        val subtitle = when (item) {
            is StackListItem.Tracked -> item.stack.remotePath
            is StackListItem.External -> item.entry.primaryConfigFile
        }
        ContainerActionSheet.show(requireContext(), item.name, subtitle, actions)
    }

    /**
     * Dispatch a compose verb to the tracked-directory transport call for a
     * Room-tracked stack, or the matching *ByProject call for a discovered
     * (external) stack — mirrors the split in [openLogs] and the editor.
     */
    private suspend fun composeAction(
        session: ContainerSessionManager.ContainerSession,
        item: StackListItem,
        trackedCall: suspend (
            transport: io.github.tabssh.containers.transport.ContainerTransport,
            stackDir: String
        ) -> ContainerResult<String>,
        externalCall: suspend (
            transport: io.github.tabssh.containers.transport.ContainerTransport,
            name: String,
            configFile: String
        ) -> ContainerResult<String>
    ): ContainerResult<String> = when (item) {
        is StackListItem.Tracked -> trackedCall(session.transport, item.stack.remotePath)
        is StackListItem.External -> externalCall(
            session.transport, item.entry.name, item.entry.primaryConfigFile
        )
    }

    private fun runComposeAction(
        item: StackListItem,
        action: suspend () -> ContainerResult<String>
    ) {
        if (composeActionInFlight) return
        composeActionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = action()
                if (!isAdded) return@launch
                when (result) {
                    is ContainerResult.Success -> {
                        ContainerInspectDialog.show(
                            requireContext(),
                            getString(R.string.container_stack_action_output_title, item.name),
                            result.value.ifBlank { getString(R.string.container_action_success) }
                        )
                        session?.let {
                            refreshStatuses(it)
                            discoverExternalStacks(it)
                        }
                    }
                    else -> ContainerErrorPresenter.present(requireContext(), result)
                }
            } finally {
                composeActionInFlight = false
                if (isAdded) progressBar.visibility = View.GONE
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
            .setMessage(getString(R.string.container_stack_delete_message, stack.name))
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
