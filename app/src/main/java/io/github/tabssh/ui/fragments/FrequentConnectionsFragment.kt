package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        app = requireActivity().application as TabSSHApplication

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
                adapter.notifyDataSetChanged()
            }
        }

        Logger.d("FrequentConnectionsFragment", "Fragment created")
    }

    private fun setupRecyclerView() {
        // Frequent connects is a quick-launch surface; no long-press menu.
        adapter = ConnectionAdapter(
            onConnectionClick = { connection: ConnectionProfile ->
                openConnection(connection)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
    
    private fun openConnection(connection: ConnectionProfile) {
        Logger.d("FrequentConnectionsFragment", "Opening connection: ${connection.name}")
        // Prompt to reattach if a tab already exists for this profile —
        // ConnectionLauncher handles the dialog + the no-existing-tab fast path.
        io.github.tabssh.ui.utils.ConnectionLauncher.launch(requireContext(), connection)
    }
    
    private fun loadFrequentConnections() {
        lifecycleScope.launch {
            try {
                // Get top 10 connections by hybrid score
                val connections = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getFrequentlyUsedConnections(10)
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
