package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.launch

/**
 * Fragment for displaying and managing SSH connections
 */
class ConnectionListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConnectionAdapter
    private lateinit var viewModel: ConnectionListViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_connection_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView(view)
        setupViewModel()
        observeConnections()
    }

    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById(R.id.recycler_connections)
        adapter = ConnectionAdapter { connection ->
            onConnectionSelected(connection)
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[ConnectionListViewModel::class.java]
    }

    private fun observeConnections() {
        viewModel.connections.observe(viewLifecycleOwner) { connections ->
            adapter.submitList(connections)
        }
    }

    private fun onConnectionSelected(connection: ConnectionProfile) {
        // Handle connection selection
        // This would typically navigate to terminal activity or start connection
    }

    companion object {
        fun newInstance(): ConnectionListFragment {
            return ConnectionListFragment()
        }
    }
}

/**
 * ViewModel for connection list management
 * Loads and manages SSH connection profiles from database
 */
class ConnectionListViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    private val database = io.github.tabssh.storage.database.TabSSHDatabase.getDatabase(application)
    private val connectionDao = database.connectionDao()

    private val _connections = androidx.lifecycle.MutableLiveData<List<ConnectionProfile>>()
    val connections: androidx.lifecycle.LiveData<List<ConnectionProfile>> = _connections

    private val _isLoading = androidx.lifecycle.MutableLiveData(false)
    val isLoading: androidx.lifecycle.LiveData<Boolean> = _isLoading

    init {
        loadConnections()
    }

    private fun loadConnections() {
        _isLoading.value = true

        // Load connections from database using coroutine
        viewModelScope.launch {
            try {
                // Observe connections from database
                connectionDao.getAllConnections().collect { connectionList ->
                    _connections.postValue(connectionList.sortedByDescending { it.lastConnected })
                    _isLoading.postValue(false)
                }
            } catch (e: Exception) {
                io.github.tabssh.utils.logging.Logger.e("ConnectionListViewModel", "Failed to load connections", e)
                _connections.postValue(emptyList())
                _isLoading.postValue(false)
            }
        }
    }

    fun refreshConnections() {
        loadConnections()
    }

    fun deleteConnection(connection: ConnectionProfile) {
        viewModelScope.launch {
            try {
                connectionDao.deleteConnection(connection)
                io.github.tabssh.utils.logging.Logger.i("ConnectionListViewModel", "Deleted connection: ${connection.name}")
            } catch (e: Exception) {
                io.github.tabssh.utils.logging.Logger.e("ConnectionListViewModel", "Failed to delete connection", e)
            }
        }
    }
}