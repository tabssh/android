package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.Identity
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.adapters.ConnectionAdapter
import io.github.tabssh.ui.adapters.GroupedConnectionAdapter
import io.github.tabssh.ui.models.ConnectionListItem
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fragment showing all connections with search and sort
 */
class ConnectionsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchView: SearchView
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLayout: View
    private lateinit var adapter: ConnectionAdapter
    private var groupedAdapter: GroupedConnectionAdapter? = null
    
    private var allConnections = listOf<ConnectionProfile>()
    private var allGroups = listOf<ConnectionGroup>()
    private var allIdentities = listOf<Identity>()
    private var currentSearchQuery = ""
    private var currentSortOption = SortOption.NAME_ASC
    private var useGroupedView = true // Default to grouped view

    // Multi-select mode
    private var isSelectionMode = false
    private val selectedConnections = mutableSetOf<String>() // Connection IDs
    
    enum class SortOption(val displayName: String) {
        NAME_ASC("Name (A-Z)"),
        NAME_DESC("Name (Z-A)"),
        HOST_ASC("Host (A-Z)"),
        HOST_DESC("Host (Z-A)"),
        MOST_USED("Most Used"),
        LEAST_USED("Least Used"),
        RECENTLY_CONNECTED("Recently Connected"),
        OLDEST_CONNECTED("Oldest Connected")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_connections, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as TabSSHApplication
        
        toolbar = view.findViewById(R.id.toolbar_connections)
        searchView = view.findViewById(R.id.search_view)
        recyclerView = view.findViewById(R.id.recycler_connections)
        emptyLayout = view.findViewById(R.id.layout_empty_connections)
        
        setupToolbar()
        setupSearchView()
        setupRecyclerView()
        loadSortPreference()
        loadAllConnections()
        
        Logger.d("ConnectionsFragment", "Fragment created")
    }
    
    private fun setupToolbar() {
        toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_connections, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_sort -> {
                        showSortDialog()
                        true
                    }
                    R.id.action_bulk_edit -> {
                        showBulkEditOptions()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }
    
    private fun showSortDialog() {
        val options = SortOption.values().map { it.displayName }.toTypedArray()
        val currentIndex = currentSortOption.ordinal
        
        AlertDialog.Builder(requireContext())
            .setTitle("Sort Connections")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSortOption = SortOption.values()[which]
                saveSortPreference()
                applySortAndFilter()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun saveSortPreference() {
        requireContext().getSharedPreferences("TabSSH", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("connections_sort", currentSortOption.name)
            .apply()
    }
    
    private fun loadSortPreference() {
        val prefName = requireContext().getSharedPreferences("TabSSH", android.content.Context.MODE_PRIVATE)
            .getString("connections_sort", SortOption.NAME_ASC.name)
        currentSortOption = SortOption.entries.find { it.name == prefName } ?: SortOption.NAME_ASC
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                filterConnections(currentSearchQuery)
                return true
            }
        })
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
        Logger.d("ConnectionsFragment", "Opening connection: ${connection.name}")
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
                Logger.d("ConnectionsFragment", "Connection duplicated: ${duplicate.name}")
            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Failed to duplicate connection", e)
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
                        Logger.d("ConnectionsFragment", "Connection deleted: ${connection.name}")
                    } catch (e: Exception) {
                        Logger.e("ConnectionsFragment", "Failed to delete connection", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupMenu(groupHeader: ConnectionListItem.GroupHeader) {
        val items = arrayOf("Rename Group", "Delete Group", "Collapse All Groups")

        AlertDialog.Builder(requireContext())
            .setTitle(groupHeader.group.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameGroup(groupHeader.group.name)
                    1 -> deleteGroup(groupHeader.group.name)
                    2 -> collapseAllGroups()
                }
            }
            .show()
    }

    private fun renameGroup(oldName: String) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(oldName)
            hint = "Group name"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Group")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank() && newName != oldName) {
                    lifecycleScope.launch {
                        try {
                            // Update all connections in this group
                            val connections = app.database.connectionDao().getAllConnectionsList()
                            // Note: Groups are stored by ID, not name - this is a simplified implementation
                            connections.filter { it.groupId == oldName }.forEach { conn ->
                                val updated = conn.copy(groupId = newName)
                                app.database.connectionDao().updateConnection(updated)
                            }
                            Toast.makeText(requireContext(), "Group renamed to '$newName'", Toast.LENGTH_SHORT).show()
                            loadAllConnections()
                        } catch (e: Exception) {
                            Logger.e("ConnectionsFragment", "Failed to rename group", e)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup(groupName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Group")
            .setMessage("Remove group '$groupName' from all connections?\n\nConnections will not be deleted, just ungrouped.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        // Clear group from all connections
                        val connections = app.database.connectionDao().getAllConnectionsList()
                        connections.filter { it.groupId == groupName }.forEach { conn ->
                            val updated = conn.copy(groupId = null)
                            app.database.connectionDao().updateConnection(updated)
                        }
                        Toast.makeText(requireContext(), "Group deleted", Toast.LENGTH_SHORT).show()
                        loadAllConnections()
                    } catch (e: Exception) {
                        Logger.e("ConnectionsFragment", "Failed to delete group", e)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun collapseAllGroups() {
        groupedAdapter?.collapseAll()
        Toast.makeText(requireContext(), "All groups collapsed", Toast.LENGTH_SHORT).show()
    }

    /**
     * Show bulk edit options dialog
     */
    private fun showBulkEditOptions() {
        val options = arrayOf(
            "Edit All Connections",
            "Edit Connections in Group...",
            "Select Multiple to Edit",
            "Select Multiple to Delete"  // Wave 6.2
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Bulk Edit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBulkEditDialog(allConnections)
                    1 -> showGroupSelectionForBulkEdit()
                    2 -> enterSelectionMode()
                    3 -> enterSelectionMode(deleteMode = true)  // Wave 6.2
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show group selection dialog for bulk editing
     */
    private fun showGroupSelectionForBulkEdit() {
        if (allGroups.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No groups available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val groupNames = allGroups.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Group to Edit")
            .setItems(groupNames) { _, which ->
                val selectedGroup = allGroups[which]
                val groupConnections = allConnections.filter { it.groupId == selectedGroup.id }
                if (groupConnections.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "No connections in this group", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showBulkEditDialog(groupConnections)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Enter multi-select mode
     */
    private fun enterSelectionMode(deleteMode: Boolean = false) {
        isSelectionMode = true
        selectedConnections.clear()
        toolbar.title = if (deleteMode) "Select to Delete" else "Select Connections"
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { exitSelectionMode() }

        // Show action button for bulk edit
        toolbar.menu.findItem(R.id.action_bulk_edit)?.isVisible = false
        toolbar.menu.findItem(R.id.action_sort)?.isVisible = false

        val hint = if (deleteMode)
            "Tap connections to select, long-press to delete selected"
        else
            "Tap connections to select, long-press to edit selected"
        android.widget.Toast.makeText(requireContext(), hint, android.widget.Toast.LENGTH_LONG).show()

        // Update adapter click behavior — long-press triggers the chosen action.
        adapter.setOnItemLongClickListener { _ ->
            if (selectedConnections.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "Select at least one connection", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            val selectedList = allConnections.filter { selectedConnections.contains(it.id) }
            if (deleteMode) {
                confirmAndBulkDelete(selectedList)
            } else {
                showBulkEditDialog(selectedList)
            }
            true
        }
    }

    /**
     * Wave 6.2 — Bulk delete with confirmation. Uses connectionDao.deleteConnection
     * one-by-one so the existing audit / cascade behaviour fires per row.
     */
    private fun confirmAndBulkDelete(selected: List<ConnectionProfile>) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${selected.size} connection(s)?")
            .setMessage("This cannot be undone. Stored passwords for these connections will also be cleared.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    var deleted = 0
                    val app = requireActivity().application as io.github.tabssh.TabSSHApplication
                    for (c in selected) {
                        try {
                            app.database.connectionDao().deleteConnection(c)
                            try { app.securePasswordManager.clearPassword(c.id) } catch (_: Exception) {}
                            deleted++
                        } catch (e: Exception) {
                            Logger.e("ConnectionsFragment", "Bulk delete failed for ${c.name}", e)
                        }
                    }
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Deleted $deleted connection(s)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Exit multi-select mode
     */
    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedConnections.clear()
        toolbar.title = "Connections"
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)

        // Restore menu
        toolbar.menu.findItem(R.id.action_bulk_edit)?.isVisible = true
        toolbar.menu.findItem(R.id.action_sort)?.isVisible = true

        // Restore adapter click behavior
        setupRecyclerView()
        applySortAndFilter()
    }

    /**
     * Show bulk edit dialog with checkboxes for each field
     */
    private fun showBulkEditDialog(connections: List<ConnectionProfile>) {
        if (connections.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "No connections to edit", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bulk_edit, null)

        // Checkboxes for enabling each field change
        val checkUsername = dialogView.findViewById<android.widget.CheckBox>(R.id.check_username)
        val checkPort = dialogView.findViewById<android.widget.CheckBox>(R.id.check_port)
        val checkGroup = dialogView.findViewById<android.widget.CheckBox>(R.id.check_group)
        val checkIdentity = dialogView.findViewById<android.widget.CheckBox>(R.id.check_identity)
        val checkTimeout = dialogView.findViewById<android.widget.CheckBox>(R.id.check_timeout)
        val checkKeepalive = dialogView.findViewById<android.widget.CheckBox>(R.id.check_keepalive)
        val checkCompression = dialogView.findViewById<android.widget.CheckBox>(R.id.check_compression)

        // Field inputs
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPort = dialogView.findViewById<TextInputEditText>(R.id.edit_port)
        val dropdownGroup = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_group)
        val dropdownIdentity = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_identity)
        val editTimeout = dialogView.findViewById<TextInputEditText>(R.id.edit_timeout)
        val switchKeepalive = dialogView.findViewById<SwitchMaterial>(R.id.switch_keepalive)
        val switchCompression = dialogView.findViewById<SwitchMaterial>(R.id.switch_compression)
        val textSelectedCount = dialogView.findViewById<TextView>(R.id.text_selected_count)

        textSelectedCount.text = "${connections.size} connections selected"

        // Auto-check checkbox when user edits a field
        editUsername.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) checkUsername.isChecked = true }
        editPort.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) checkPort.isChecked = true }
        editTimeout.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) checkTimeout.isChecked = true }
        dropdownGroup.setOnItemClickListener { _, _, _, _ -> checkGroup.isChecked = true }
        dropdownIdentity.setOnItemClickListener { _, _, _, _ -> checkIdentity.isChecked = true }
        switchKeepalive.setOnCheckedChangeListener { _, _ -> checkKeepalive.isChecked = true }
        switchCompression.setOnCheckedChangeListener { _, _ -> checkCompression.isChecked = true }

        // Setup group dropdown
        val groupOptions = mutableListOf("(None - Remove from group)")
        groupOptions.addAll(allGroups.map { it.name })
        val groupAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, groupOptions)
        dropdownGroup.setAdapter(groupAdapter)

        // Setup identity dropdown
        val identityOptions = mutableListOf("(None - Remove identity)")
        lifecycleScope.launch {
            try {
                app.database.identityDao().getAllIdentities().collect { identities ->
                    allIdentities = identities
                    identityOptions.addAll(identities.map { it.name })
                    val identityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, identityOptions)
                    dropdownIdentity.setAdapter(identityAdapter)
                }
            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Failed to load identities", e)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Bulk Edit ${connections.size} Connections")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                applyBulkEdit(
                    connections = connections,
                    applyUsername = checkUsername.isChecked,
                    newUsername = editUsername.text?.toString() ?: "",
                    applyPort = checkPort.isChecked,
                    newPort = editPort.text?.toString()?.toIntOrNull() ?: 22,
                    applyGroup = checkGroup.isChecked,
                    newGroupSelection = dropdownGroup.text?.toString(),
                    applyIdentity = checkIdentity.isChecked,
                    newIdentitySelection = dropdownIdentity.text?.toString(),
                    applyTimeout = checkTimeout.isChecked,
                    newTimeout = editTimeout.text?.toString()?.toIntOrNull() ?: 15,
                    applyKeepalive = checkKeepalive.isChecked,
                    newKeepalive = switchKeepalive.isChecked,
                    applyCompression = checkCompression.isChecked,
                    newCompression = switchCompression.isChecked
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Apply bulk edits to connections - only applies checked fields
     */
    private fun applyBulkEdit(
        connections: List<ConnectionProfile>,
        applyUsername: Boolean,
        newUsername: String,
        applyPort: Boolean,
        newPort: Int,
        applyGroup: Boolean,
        newGroupSelection: String?,
        applyIdentity: Boolean,
        newIdentitySelection: String?,
        applyTimeout: Boolean,
        newTimeout: Int,
        applyKeepalive: Boolean,
        newKeepalive: Boolean,
        applyCompression: Boolean,
        newCompression: Boolean
    ) {
        lifecycleScope.launch {
            try {
                var updatedCount = 0
                val changes = mutableListOf<String>()

                // Build list of what will change
                if (applyUsername) changes.add("username")
                if (applyPort) changes.add("port")
                if (applyGroup) changes.add("group")
                if (applyIdentity) changes.add("identity")
                if (applyTimeout) changes.add("timeout")
                if (applyKeepalive) changes.add("keepalive")
                if (applyCompression) changes.add("compression")

                if (changes.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "No changes selected", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Logger.d("ConnectionsFragment", "Bulk edit: applying ${changes.joinToString(", ")} to ${connections.size} connections")

                for (connection in connections) {
                    var updatedConnection = connection

                    // Apply username if checked
                    if (applyUsername) {
                        updatedConnection = updatedConnection.copy(username = newUsername)
                    }

                    // Apply port if checked
                    if (applyPort) {
                        updatedConnection = updatedConnection.copy(port = newPort)
                    }

                    // Apply group if checked
                    if (applyGroup) {
                        val newGroupId = when {
                            newGroupSelection.isNullOrEmpty() -> null
                            newGroupSelection.startsWith("(None") -> null
                            else -> allGroups.find { it.name == newGroupSelection }?.id
                        }
                        updatedConnection = updatedConnection.copy(groupId = newGroupId)
                    }

                    // Apply identity if checked
                    if (applyIdentity) {
                        val newIdentityId = when {
                            newIdentitySelection.isNullOrEmpty() -> null
                            newIdentitySelection.startsWith("(None") -> null
                            else -> allIdentities.find { it.name == newIdentitySelection }?.id
                        }
                        updatedConnection = updatedConnection.copy(identityId = newIdentityId)
                    }

                    // Apply timeout if checked
                    if (applyTimeout) {
                        updatedConnection = updatedConnection.copy(connectTimeout = newTimeout)
                    }

                    // Apply keepalive if checked
                    if (applyKeepalive) {
                        updatedConnection = updatedConnection.copy(keepAlive = newKeepalive)
                    }

                    // Apply compression if checked
                    if (applyCompression) {
                        updatedConnection = updatedConnection.copy(compression = newCompression)
                    }

                    app.database.connectionDao().updateConnection(updatedConnection)
                    updatedCount++
                }

                Logger.d("ConnectionsFragment", "Bulk edit completed: $updatedCount connections updated (${changes.joinToString(", ")})")
                android.widget.Toast.makeText(requireContext(), "Updated $updatedCount connections: ${changes.joinToString(", ")}", android.widget.Toast.LENGTH_SHORT).show()

                // Exit selection mode if active
                if (isSelectionMode) {
                    exitSelectionMode()
                }

            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Bulk edit failed", e)
                android.widget.Toast.makeText(requireContext(), "Bulk edit failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadAllConnections() {
        lifecycleScope.launch {
            try {
                // Use combine() to merge both Flows - fixes nested collect() blocking issue
                // Both connections AND groups will trigger UI updates when changed
                combine(
                    app.database.connectionDao().getAllConnections(),
                    app.database.connectionGroupDao().getAllGroups()
                ) { connections, groups ->
                    Pair(connections, groups)
                }.collect { (connections, groups) ->
                    allConnections = connections
                    allGroups = groups

                    if (useGroupedView) {
                        applyGroupedView()
                    } else {
                        applySortAndFilter()
                    }

                    Logger.d("ConnectionsFragment", "Loaded ${connections.size} connections, ${groups.size} groups")
                }
            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Failed to load connections", e)
            }
        }
    }
    
    private fun applyGroupedView() {
        // Build ConnectionListItem list
        val items = mutableListOf<ConnectionListItem>()
        
        // Add grouped connections
        for (group in allGroups.sortedBy { it.sortOrder }) {
            val groupConnections = allConnections.filter { it.groupId == group.id }
            if (groupConnections.isNotEmpty()) {
                // Add group header
                items.add(ConnectionListItem.GroupHeader(
                    group = group,
                    connectionCount = groupConnections.size,
                    isExpanded = !group.isCollapsed
                ))
                
                // Add connections if group is expanded
                if (!group.isCollapsed) {
                    groupConnections.forEach { connection ->
                        items.add(ConnectionListItem.Connection(
                            profile = connection,
                            isInGroup = true,
                            indentLevel = 1
                        ))
                    }
                }
            }
        }
        
        // Add ungrouped connections (null groupId OR groupId that doesn't exist in allGroups)
        // This ensures imported connections with non-existent groupId still appear
        val existingGroupIds = allGroups.map { it.id }.toSet()
        val ungroupedConnections = allConnections.filter {
            it.groupId == null || it.groupId !in existingGroupIds
        }
        if (ungroupedConnections.isNotEmpty()) {
            items.add(ConnectionListItem.UngroupedHeader(
                connectionCount = ungroupedConnections.size,
                isExpanded = true
            ))
            ungroupedConnections.forEach { connection ->
                items.add(ConnectionListItem.Connection(
                    profile = connection,
                    isInGroup = false,
                    indentLevel = 0
                ))
            }
        }
        
        // Update adapter
        if (groupedAdapter == null) {
            groupedAdapter = GroupedConnectionAdapter(
                items = items.toMutableList(),
                onConnectionClick = { connection -> openConnection(connection) },
                onConnectionLongClick = { connection -> showConnectionMenu(connection); },
                onConnectionEdit = { connection -> editConnection(connection) },
                onConnectionDelete = { connection -> deleteConnection(connection) },
                onGroupClick = { groupHeader -> toggleGroupExpanded(groupHeader) },
                onGroupLongClick = { groupHeader -> showGroupMenu(groupHeader) }
            )
            recyclerView.adapter = groupedAdapter
        } else {
            // Update existing adapter
            groupedAdapter!!.items.clear()
            groupedAdapter!!.items.addAll(items)
            groupedAdapter!!.notifyDataSetChanged()
        }
        
        // Update empty state
        if (items.isEmpty()) {
            emptyLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }
    
    private fun toggleGroupExpanded(groupHeader: ConnectionListItem.GroupHeader) {
        lifecycleScope.launch {
            try {
                // If currently expanded (isExpanded=true), user wants to collapse → newCollapsed=true
                // If currently collapsed (isExpanded=false), user wants to expand → newCollapsed=false
                val newCollapsed = groupHeader.isExpanded
                app.database.connectionGroupDao().updateGroupCollapsedState(groupHeader.group.id, newCollapsed)
                Logger.d("ConnectionsFragment", "Toggled group '${groupHeader.group.name}' collapsed=$newCollapsed")
                // Reload will happen via Flow
            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Failed to toggle group", e)
            }
        }
    }
    
    private fun applySortAndFilter() {
        filterConnections(currentSearchQuery)
    }

    private fun filterConnections(query: String) {
        val filtered = if (query.isEmpty()) {
            allConnections
        } else {
            allConnections.filter { connection ->
                connection.name.contains(query, ignoreCase = true) ||
                connection.host.contains(query, ignoreCase = true) ||
                connection.username.contains(query, ignoreCase = true)
            }
        }
        
        // Apply sort
        val sorted = applySortToList(filtered)
        
        if (sorted.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyLayout.visibility = View.GONE
            adapter.submitList(sorted)
        }
    }
    
    private fun applySortToList(connections: List<ConnectionProfile>): List<ConnectionProfile> {
        return when (currentSortOption) {
            SortOption.NAME_ASC -> connections.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> connections.sortedByDescending { it.name.lowercase() }
            SortOption.HOST_ASC -> connections.sortedBy { it.host.lowercase() }
            SortOption.HOST_DESC -> connections.sortedByDescending { it.host.lowercase() }
            SortOption.MOST_USED -> connections.sortedByDescending { it.connectionCount }
            SortOption.LEAST_USED -> connections.sortedBy { it.connectionCount }
            SortOption.RECENTLY_CONNECTED -> connections.sortedByDescending { it.lastConnected }
            SortOption.OLDEST_CONNECTED -> connections.sortedBy { if (it.lastConnected > 0) it.lastConnected else Long.MAX_VALUE }
        }
    }

    companion object {
        fun newInstance() = ConnectionsFragment()
    }
}
