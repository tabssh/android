package io.github.tabssh.ui.fragments.containers

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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerSnapshotSummary
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.ui.adapters.ContainerSnapshotAdapter
import io.github.tabssh.ui.dialogs.ContainerActionSheet
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerNames
import io.github.tabssh.ui.utils.ContainerText
import kotlinx.coroutines.launch

/**
 * Snapshots destination (Incus and LXC/LXD): every instance's snapshots in one
 * list, FAB takes a new one, tap (or long-press) opens the action sheet with
 * restore first and destructive delete last. Load failures render inline with
 * retry, the same as every other list destination.
 */
class ContainerSnapshotsFragment : ContainerPageFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var errorState: LinearLayout
    private lateinit var textError: TextView
    private lateinit var textEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabAction: FloatingActionButton
    private lateinit var adapter: ContainerSnapshotAdapter
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

        textEmpty.setText(R.string.container_snapshots_empty)
        view.findViewById<TextView>(R.id.text_empty_hint).setText(R.string.container_snapshots_empty_hint)
        view.findViewById<ImageView>(R.id.image_empty).setImageResource(R.drawable.ic_container_snapshot)
        fabAction.contentDescription = getString(R.string.container_create_snapshot_title)

        adapter = ContainerSnapshotAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Tap opens the sheet rather than acting: both snapshot actions
        // (restore, delete) are destructive, so neither is a safe one-touch.
        adapter.setOnItemClickListener { snapshot -> showSnapshotMenu(snapshot) }
        adapter.setOnMoreClickListener { snapshot -> showSnapshotMenu(snapshot) }
        adapter.setOnItemLongClickListener { snapshot -> showSnapshotMenu(snapshot) }

        fabAction.setOnClickListener { showCreateDialog() }

        view.findViewById<MaterialButton>(R.id.button_retry).setOnClickListener {
            session?.let { onSessionReady(it) }
        }

        // Base class wires sessionFlow/refreshFlow into onSessionReady.
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onSessionReady(session: ContainerSessionManager.ContainerSession) {
        progressBar.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        startLoad {
            // Snapshots belong to an instance, so the host-wide list is the
            // union over every instance — including stopped ones, which keep
            // their snapshots.
            val instances = session.transport.listContainers(all = true)
            if (!isAdded) return@startLoad
            if (instances !is ContainerResult.Success) {
                progressBar.visibility = View.GONE
                showLoadError(instances)
                return@startLoad
            }
            val snapshots = mutableListOf<ContainerSnapshotSummary>()
            var failure: ContainerResult<*>? = null
            for (instance in instances.value) {
                // Same identifier the rest of the container UI addresses an
                // instance by: the engine name when it has one, else the id.
                val ref = instance.names.firstOrNull()?.removePrefix("/")
                    ?: instance.id.take(12)
                when (val result = session.transport.listSnapshots(ref)) {
                    is ContainerResult.Success -> snapshots += result.value
                    // One unreadable instance must not blank the snapshots of
                    // every readable one; it is reported only if none succeed.
                    else -> if (failure == null) failure = result
                }
            }
            if (!isAdded) return@startLoad
            progressBar.visibility = View.GONE
            val unreadable = failure
            if (snapshots.isEmpty() && unreadable != null) {
                showLoadError(unreadable)
                return@startLoad
            }
            adapter.updateList(snapshots)
            val empty = snapshots.isEmpty()
            recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    private fun showLoadError(result: ContainerResult<*>) {
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        textError.text = ContainerErrorPresenter.messageFor(requireContext(), result)
    }

    private fun showCreateDialog() {
        if (!isAdded) return
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_create_snapshot, null)
        val editInstance = view.findViewById<TextInputEditText>(R.id.edit_instance)
        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        val checkStateful = view.findViewById<MaterialCheckBox>(R.id.check_stateful)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.container_create_snapshot_title)
            .setView(view)
            .setPositiveButton(R.string.container_create) { _, _ ->
                val instance = editInstance.text?.toString()?.trim().orEmpty()
                val name = editName.text?.toString()?.trim().orEmpty()
                // Validate before the value reaches the transport: a name with
                // whitespace or a leading dash would be parsed as CLI flags.
                if (instance.isEmpty()) {
                    Toast.makeText(
                        requireContext(), R.string.container_error_instance_required, Toast.LENGTH_SHORT
                    ).show()
                } else if (name.isEmpty()) {
                    Toast.makeText(
                        requireContext(), R.string.container_error_name_required, Toast.LENGTH_SHORT
                    ).show()
                } else if (!ContainerNames.isValidResourceName(instance) ||
                    !ContainerNames.isValidResourceName(name)
                ) {
                    Toast.makeText(
                        requireContext(), R.string.container_error_name_format, Toast.LENGTH_LONG
                    ).show()
                } else {
                    createSnapshot(instance, name, checkStateful.isChecked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createSnapshot(instance: String, name: String, stateful: Boolean) {
        runAction { it.createSnapshot(instance, name, stateful) }
    }

    private fun showSnapshotMenu(snapshot: ContainerSnapshotSummary) {
        if (!isAdded) return
        ContainerActionSheet.show(
            requireContext(), snapshot.name, snapshot.instance,
            listOf(
                ContainerActionSheet.Action(
                    R.drawable.ic_container_snapshot,
                    getString(R.string.container_action_restore),
                    destructive = true
                ) {
                    confirmRestore(snapshot)
                },
                ContainerActionSheet.Action(
                    R.drawable.ic_clear, getString(R.string.container_action_remove), destructive = true
                ) {
                    confirmRemove(snapshot)
                }
            )
        )
    }

    private fun confirmRestore(snapshot: ContainerSnapshotSummary) {
        if (!isAdded) return
        // A bidi-override in a daemon-supplied name could make the
        // confirmation read as a different snapshot than the one being used.
        val safeName = ContainerText.display(snapshot.name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.container_restore_snapshot_title, safeName))
            .setMessage(R.string.container_restore_snapshot_message)
            .setPositiveButton(R.string.container_action_restore) { _, _ ->
                runAction { it.restoreSnapshot(snapshot.instance, snapshot.name) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(snapshot: ContainerSnapshotSummary) {
        if (!isAdded) return
        val safeName = ContainerText.display(snapshot.name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(safeName)
            .setMessage(getString(R.string.container_remove_snapshot_message, safeName))
            .setPositiveButton(R.string.delete) { _, _ ->
                runAction { it.removeSnapshot(snapshot.instance, snapshot.name) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Run one snapshot mutation, then reload the list from the host. */
    private fun runAction(block: suspend (ContainerTransport) -> ContainerResult<Unit>) {
        val current = session ?: return
        // Snapshot mutations are not idempotent from the user's point of view —
        // a repeat tap surfaces a spurious "already exists" error dialog.
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = block(current.transport)
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
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
            }
        }
    }
}
