package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.utils.FrequencyScore
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Fragment showing top 10 most frequently used connections
 * Uses hybrid scoring algorithm: connectionCount × recencyBoost
 */
class FrequentConnectionsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLayout: View
    private lateinit var adapter: ConnectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_frequent_connections, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        app = tabSSHApp

        recyclerView = view.findViewById(R.id.recycler_frequent)
        emptyLayout = view.findViewById(R.id.layout_empty_frequent)

        setupRecyclerView()
        loadFrequentConnections()

        // Re-bind rows whenever the SSH session manager's connection-state
        // map changes — the adapter reads `isConnectionActive(id)` at bind
        // time, but the DB Flow only ticks on lastConnected/count updates
        // (not on state transitions), so without this the active dot
        // never flipped to green or back to grey.
        viewLifecycleOwner.lifecycleScope.launch {
            app.sshSessionManager.connectionStates.collect {
                // Skip-DiffUtil rationale: the backing list of connections has
                // not changed — only the external `isConnectionActive(id)`
                // signal that each row reads at bind time. A range-change is
                // the minimal correct way to ask the RecyclerView to rebind
                // visible rows.
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }

        Logger.d("FrequentConnectionsFragment", "Fragment created")
    }

    private fun setupRecyclerView() {
        adapter = ConnectionAdapter(
            onConnectionClick = { connection: ConnectionProfile ->
                openConnection(connection)
            }
        )

        // Long click for context menu — same mechanism as Hosts, but a
        // reduced menu (no Duplicate/Delete): Frequent is a quick-launch
        // surface, not where the user manages the connection record itself.
        adapter.setOnItemLongClickListener { connection ->
            showConnectionMenu(connection)
            true
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun openConnection(connection: ConnectionProfile) {
        Logger.d("FrequentConnectionsFragment", "Opening connection: ${connection.name}")
        // Prompt to reattach if a tab already exists for this profile —
        // ConnectionLauncher handles the dialog + the no-existing-tab fast path.
        io.github.tabssh.ui.utils.ConnectionLauncher.launch(requireContext(), connection)
    }

    private fun showConnectionMenu(connection: ConnectionProfile) {
        // Order: Connect → Browse Files → Edit. No Duplicate/Delete here —
        // those are record-management actions that belong on Hosts, where
        // the full connection list (not just the top-10 shortlist) lives.
        val items = arrayOf(
            getString(R.string.connect_button),
            getString(R.string.connections_menu_browse_files),
            getString(R.string.edit)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(connection.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openConnection(connection)
                    1 -> openSftpBrowser(connection)
                    2 -> editConnection(connection)
                }
            }
            .show()
    }

    private fun openSftpBrowser(connection: ConnectionProfile) {
        Logger.d("FrequentConnectionsFragment", "Opening SFTP browser for ${connection.name}")
        startActivity(io.github.tabssh.ui.activities.SFTPActivity.createIntent(
            requireContext(), connection.id
        ))
    }

    private fun editConnection(connection: ConnectionProfile) {
        val intent = Intent(requireContext(), ConnectionEditActivity::class.java).apply {
            putExtra(ConnectionEditActivity.EXTRA_CONNECTION_ID, connection.id)
        }
        startActivity(intent)
    }
    
    private fun loadFrequentConnections() {
        // Bound to view lifecycle: touches recyclerView/adapter after IO,
        // which would NPE/leak if the view was destroyed while the fragment
        // instance survives (viewpager off-screen, nav back-stack).
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get top 10 connections by hybrid score (count x recency decay)
                val connections = withContext(Dispatchers.IO) {
                    val candidates = app.database.connectionDao().getFrequentlyUsedConnectionCandidates()
                    FrequencyScore.rank(candidates, 10)
                }

                if (connections.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyLayout.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyLayout.visibility = View.GONE
                    adapter.submitList(connections)
                }
                
                Logger.d("FrequentConnectionsFragment", "Loaded ${connections.size} frequent connections")
                
            } catch (e: Exception) {
                Logger.e("FrequentConnectionsFragment", "Failed to load frequent connections", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload on resume to reflect any changes
        loadFrequentConnections()
    }

    companion object {
        fun newInstance() = FrequentConnectionsFragment()
    }
}
