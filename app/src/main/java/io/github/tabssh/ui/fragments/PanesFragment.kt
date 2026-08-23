package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.PaneGroup
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.adapters.PaneGroupAdapter
import io.github.tabssh.ui.dialogs.PaneGroupEditDialog
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Panes tab — saved groups of up to 6 terminal connections tiled into one slot. */
class PanesFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var paneGroupAdapter: PaneGroupAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_panes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = requireActivity().application as TabSSHApplication

        setupPaneGroupsSection(view)
        observeData()
    }

    private fun setupPaneGroupsSection(view: View) {
        paneGroupAdapter = PaneGroupAdapter(
            onLaunch = { group -> launchPaneGroup(group) },
            onEdit = { group -> showPaneGroupDialog(group) },
            onDelete = { group -> confirmDeletePaneGroup(group) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_pane_groups)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = paneGroupAdapter
        updatePaneGroupsEmptyState(view, 0)

        view.findViewById<MaterialButton>(R.id.btn_add_pane_group).setOnClickListener {
            showPaneGroupDialog(null)
        }
    }

    private fun updatePaneGroupsEmptyState(view: View, count: Int) {
        val empty = count == 0
        view.findViewById<View>(R.id.recycler_pane_groups).visibility = if (empty) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.text_pane_groups_empty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun launchPaneGroup(group: PaneGroup) {
        val intent = Intent(requireContext(), TabTerminalActivity::class.java)
            .putExtra(TabTerminalActivity.EXTRA_PANE_GROUP_ID, group.id)
        startActivity(intent)
    }

    private fun showPaneGroupDialog(existing: PaneGroup?) {
        PaneGroupEditDialog.show(requireContext(), app, viewLifecycleOwner.lifecycleScope, existing) {}
    }

    private fun confirmDeletePaneGroup(group: PaneGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pane_group_delete_title))
            .setMessage(getString(R.string.pane_group_delete_message_fmt, group.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.database.paneGroupDao().deleteById(group.id)
                        TombstoneRecorder.record(app, TombstoneRecorder.PANE_GROUP, group.id)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.database.paneGroupDao().getAll().collect { list ->
                        paneGroupAdapter.submit(list)
                        view?.let { updatePaneGroupsEmptyState(it, list.size) }
                        Logger.d(TAG, "Loaded ${list.size} pane groups")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PanesFragment"
        fun newInstance() = PanesFragment()
    }
}
