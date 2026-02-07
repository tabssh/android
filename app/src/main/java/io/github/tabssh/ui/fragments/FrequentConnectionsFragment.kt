package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

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
        
        Logger.d("FrequentConnectionsFragment", "Fragment created")
    }

    private fun setupRecyclerView() {
        adapter = ConnectionAdapter(
            onConnectionClick = { connection: ConnectionProfile ->
                openConnection(connection)
            }
        )
        
        // Long click for context menu
        adapter.setOnItemLongClickListener { connection ->
            showConnectionMenu(connection)
            true
        }
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
    
    private fun openConnection(connection: ConnectionProfile) {
        Logger.d("FrequentConnectionsFragment", "Opening connection: ${connection.name}")
        val intent = TabTerminalActivity.createIntent(requireContext(), connection, autoConnect = true)
        startActivity(intent)
    }
    
    private fun showConnectionMenu(connection: ConnectionProfile) {
        val items = arrayOf("Open", "Edit", "Duplicate", "Delete")
        
        AlertDialog.Builder(requireContext())
            .setTitle(connection.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openConnection(connection)
                    1 -> editConnection(connection)
                    2 -> duplicateConnection(connection)
                    3 -> deleteConnection(connection)
                }
            }
            .show()
    }
    
    private fun editConnection(connection: ConnectionProfile) {
        val intent = Intent(requireContext(), ConnectionEditActivity::class.java).apply {
            putExtra(ConnectionEditActivity.EXTRA_CONNECTION_ID, connection.id)
        }
        startActivity(intent)
    }
    
    private fun duplicateConnection(connection: ConnectionProfile) {
        lifecycleScope.launch {
            try {
                val duplicate = connection.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "${connection.name} (Copy)",
                    connectionCount = 0,
                    lastConnected = 0
                )
                app.database.connectionDao().insertConnection(duplicate)
                Logger.d("FrequentConnectionsFragment", "Connection duplicated: ${duplicate.name}")
            } catch (e: Exception) {
                Logger.e("FrequentConnectionsFragment", "Failed to duplicate connection", e)
            }
        }
    }
    
    private fun deleteConnection(connection: ConnectionProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Connection")
            .setMessage("Are you sure you want to delete '${connection.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.connectionDao().deleteConnection(connection)
                        Logger.d("FrequentConnectionsFragment", "Connection deleted: ${connection.name}")
                    } catch (e: Exception) {
                        Logger.e("FrequentConnectionsFragment", "Failed to delete connection", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadFrequentConnections() {
        lifecycleScope.launch {
            try {
                // Get top 10 connections by hybrid score
                val connections = app.database.connectionDao().getFrequentlyUsedConnections(10)
                
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
