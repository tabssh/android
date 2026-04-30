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
        // Order: Connect → Browse Files → Edit → Duplicate → Delete.
        // "Browse Files" sits between Connect and Edit per UX feedback —
        // it's a top-level action a user reaches for as often as Connect,
        // not a buried option. Renamed "Open" to "Connect" to match how
        // the rest of the app talks about starting an SSH session.
        val items = arrayOf("Connect", "Browse Files (SFTP)", "Edit", "Duplicate", "Delete")

        AlertDialog.Builder(requireContext())
            .setTitle(connection.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openConnection(connection)
                    1 -> openSftpBrowser(connection)
                    2 -> editConnection(connection)
                    3 -> duplicateConnection(connection)
                    4 -> deleteConnection(connection)
                }
            }
            .show()
    }

    private fun openSftpBrowser(connection: ConnectionProfile) {
        Logger.d("ConnectionsFragment", "Opening SFTP browser for ${connection.name}")
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
     * Show bulk edit options dialog. Three scopes — all, by group, by
     * multi-select. Bulk DELETE has its own entry point (long-press
     * → "Select Multiple to Delete") so it doesn't pollute this menu.
     */
    private fun showBulkEditOptions() {
        val options = arrayOf(
            "Edit all (${allConnections.size}) connections",
            "Edit connections in a group…",
            "Pick connections to edit…",
            "Pick connections to delete…"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Bulk edit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBulkEditDialog(allConnections)
                    1 -> showGroupSelectionForBulkEdit()
                    2 -> enterSelectionMode()
                    3 -> enterSelectionMode(deleteMode = true)
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
    /**
     * Polished bulk-edit dialog (rewrite, 2026-04-29).
     *
     * The layout is `dialog_bulk_edit.xml`; tri-state bool rows include
     * `include_bulk_edit_tristate.xml`. Visual rules:
     *   • Each field row dims to alpha=0.45 when its "Apply" switch is OFF
     *     and brightens to 1.0 when ON. The user can flip the switch
     *     manually or just edit the field — a TextWatcher / dropdown
     *     listener auto-flips it ON.
     *   • Bool rows (Keep-Alive, Compression, Agent fwd, X11, Mosh) use a
     *     three-button MaterialButtonToggleGroup — "—" / "Off" / "On".
     *     "—" is the default and means "leave each connection's value
     *     unchanged"; this replaces the old checkbox+switch tri-state
     *     dance, which was easy to misread.
     *   • The positive button text is rebuilt live as the user makes
     *     changes: "Apply N changes to M connections". Disabled when
     *     N == 0 so misclicks can't run a no-op bulk write.
     *   • Reset button clears every field row back to "no change".
     */
    private fun showBulkEditDialog(connections: List<ConnectionProfile>) {
        if (connections.isEmpty()) {
            Toast.makeText(requireContext(), "No connections to edit", Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_bulk_edit, null)

        // ── Header ──
        val textSelectedCount = dialogView.findViewById<TextView>(R.id.text_selected_count)
        val textApplySummary = dialogView.findViewById<TextView>(R.id.text_apply_summary)
        val buttonResetAll = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_reset_all)
        textSelectedCount.text = "${connections.size} connection${if (connections.size == 1) "" else "s"} selected"

        // ── Switch + row pairs (text/dropdown fields) ──
        // Each pair: the row to fade, the switch to listen on.
        data class FieldRow(
            val row: View,
            val switch: com.google.android.material.materialswitch.MaterialSwitch
        )

        data class TriRow(
            val view: View,
            val getState: () -> TriState
        )

        // Forward-declared lambda — the real implementation is assigned
        // after the field/tri-row collections are populated. Callbacks
        // wired during `bind()` / `wireTriState()` capture this var by
        // reference, so they see the final body even though it's defined
        // later in the function body.
        var refreshApplySummary: () -> Unit = {}

        val fieldRows = mutableListOf<FieldRow>()
        val triRows = mutableListOf<TriRow>()

        fun bind(rowId: Int, switchId: Int): FieldRow {
            val row = dialogView.findViewById<View>(rowId)
            val sw = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(switchId)
            sw.setOnCheckedChangeListener { _, checked ->
                row.alpha = if (checked) 1f else 0.45f
                refreshApplySummary()
            }
            return FieldRow(row, sw).also { fieldRows.add(it) }
        }

        val username = bind(R.id.row_username, R.id.check_username)
        val port = bind(R.id.row_port, R.id.check_port)
        val group = bind(R.id.row_group, R.id.check_group)
        val identity = bind(R.id.row_identity, R.id.check_identity)
        val timeout = bind(R.id.row_timeout, R.id.check_timeout)
        val terminalType = bind(R.id.row_terminal_type, R.id.check_terminal_type)
        val colorTag = bind(R.id.row_color_tag, R.id.check_color_tag)
        val postConnect = bind(R.id.row_post_connect, R.id.check_post_connect)

        // ── Tri-state bool rows ──
        // wireTriState() takes the included row, sets the icon/label,
        // and exposes a getter that returns the current selection
        // (UNCHANGED / OFF / ON) for the apply step.
        fun wireTriState(rowId: Int, label: String, iconRes: Int): TriRow {
            val rowView = dialogView.findViewById<View>(rowId)
            rowView.findViewById<TextView>(R.id.tri_label).text = label
            rowView.findViewById<android.widget.ImageView>(R.id.tri_icon)
                .setImageResource(iconRes)
            val triGroup = rowView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tri_group)
            triGroup.check(R.id.tri_unchanged)
            triGroup.addOnButtonCheckedListener { _, _, _ -> refreshApplySummary() }
            return TriRow(rowView) {
                when (triGroup.checkedButtonId) {
                    R.id.tri_off -> TriState.OFF
                    R.id.tri_on -> TriState.ON
                    else -> TriState.UNCHANGED
                }
            }.also { triRows.add(it) }
        }

        val keepalive = wireTriState(R.id.row_keepalive, "Keep-alive", R.drawable.ic_refresh)
        val compression = wireTriState(R.id.row_compression, "Compression", R.drawable.ic_file_archive)
        val agentFwd = wireTriState(R.id.row_agent_fwd, "Agent forwarding", R.drawable.ic_forward)
        val x11 = wireTriState(R.id.row_x11, "X11 forwarding", R.drawable.ic_interface)
        val mosh = wireTriState(R.id.row_mosh, "Mosh", R.drawable.ic_flash)

        // ── Inputs ──
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPort = dialogView.findViewById<TextInputEditText>(R.id.edit_port)
        val dropdownGroup = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_group)
        val dropdownIdentity = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_identity)
        val editTimeout = dialogView.findViewById<TextInputEditText>(R.id.edit_timeout)
        val dropdownTerminalType = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_terminal_type)
        val dropdownColorTag = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_color_tag)
        val editPostConnect = dialogView.findViewById<TextInputEditText>(R.id.edit_post_connect)

        // Auto-flip "Apply" switch ON when the user actually changes a value.
        // Using afterTextChanged (not focus) so just tabbing through fields
        // doesn't enable everything by accident.
        fun autoEnableOnEdit(view: TextInputEditText, sw: com.google.android.material.materialswitch.MaterialSwitch) {
            view.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!sw.isChecked && view.hasFocus()) sw.isChecked = true
                }
            })
        }
        autoEnableOnEdit(editUsername, username.switch)
        autoEnableOnEdit(editPort, port.switch)
        autoEnableOnEdit(editTimeout, timeout.switch)
        autoEnableOnEdit(editPostConnect, postConnect.switch)
        dropdownGroup.setOnItemClickListener { _, _, _, _ -> group.switch.isChecked = true }
        dropdownIdentity.setOnItemClickListener { _, _, _, _ -> identity.switch.isChecked = true }
        dropdownTerminalType.setOnItemClickListener { _, _, _, _ -> terminalType.switch.isChecked = true }
        dropdownColorTag.setOnItemClickListener { _, _, _, _ -> colorTag.switch.isChecked = true }

        // Terminal type dropdown
        val terminalTypeOptions = arrayOf("xterm-256color", "xterm", "vt100", "vt220", "screen-256color", "tmux-256color")
        dropdownTerminalType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, terminalTypeOptions))

        // Color tag dropdown — labels map 1:1 to ConnectionProfile.colorTag indices.
        val colorTagOptions = arrayOf("(none)", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink")
        dropdownColorTag.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, colorTagOptions))

        // Group dropdown
        val groupOptions = mutableListOf("(Clear group assignment)")
        groupOptions.addAll(allGroups.map { it.name })
        dropdownGroup.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, groupOptions))

        // Identity dropdown — populated async
        val identityOptions = mutableListOf("(Clear identity)")
        lifecycleScope.launch {
            try {
                app.database.identityDao().getAllIdentities().collect { identities ->
                    allIdentities = identities
                    val opts = mutableListOf("(Clear identity)").also { it.addAll(identities.map { i -> i.name }) }
                    dropdownIdentity.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, opts))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("ConnectionsFragment", "Failed to load identities", e)
            }
        }

        // ── Live "Apply N changes to M connections" updates ──
        // The dialog reference can't be set until builder.create() runs,
        // so we close over a nullable holder that's assigned right before
        // show().
        var dialogRef: AlertDialog? = null
        refreshApplySummary = {
            val triCount = triRows.count { it.getState() != TriState.UNCHANGED }
            val switchCount = fieldRows.count { it.switch.isChecked }
            val total = triCount + switchCount
            textApplySummary.text = if (total == 0) {
                "No changes selected — flip a switch or pick a value"
            } else {
                "$total field${if (total == 1) "" else "s"} will change on ${connections.size} connection${if (connections.size == 1) "" else "s"}"
            }
            dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                btn.isEnabled = total > 0
                btn.text = if (total == 0) "Apply" else "Apply ($total)"
            }
        }

        // ── Reset all ──
        buttonResetAll.setOnClickListener {
            listOf(username, port, group, identity, timeout, terminalType, colorTag, postConnect)
                .forEach { it.switch.isChecked = false }
            listOf(R.id.row_keepalive, R.id.row_compression, R.id.row_agent_fwd, R.id.row_x11, R.id.row_mosh)
                .forEach { id ->
                    dialogView.findViewById<View>(id)
                        .findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tri_group)
                        .check(R.id.tri_unchanged)
                }
            // Clear the values too — Reset means "wipe my picks".
            editUsername.text = null
            editPort.text = null
            editTimeout.text = null
            editPostConnect.text = null
            dropdownGroup.setText("", false)
            dropdownIdentity.setText("", false)
            dropdownTerminalType.setText("", false)
            dropdownColorTag.setText("", false)
            refreshApplySummary()
        }

        dialogRef = AlertDialog.Builder(ctx)
            .setTitle("Bulk edit")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                applyBulkEdit(
                    connections = connections,
                    applyUsername = username.switch.isChecked,
                    newUsername = editUsername.text?.toString() ?: "",
                    applyPort = port.switch.isChecked,
                    newPort = editPort.text?.toString()?.toIntOrNull() ?: 22,
                    applyGroup = group.switch.isChecked,
                    newGroupSelection = dropdownGroup.text?.toString(),
                    applyIdentity = identity.switch.isChecked,
                    newIdentitySelection = dropdownIdentity.text?.toString(),
                    applyTimeout = timeout.switch.isChecked,
                    newTimeout = editTimeout.text?.toString()?.toIntOrNull() ?: 15,
                    keepalive = keepalive.getState(),
                    compression = compression.getState(),
                    applyTerminalType = terminalType.switch.isChecked,
                    newTerminalType = dropdownTerminalType.text?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "xterm-256color",
                    applyColorTag = colorTag.switch.isChecked,
                    newColorTag = colorTagOptions.indexOf(dropdownColorTag.text?.toString())
                        .coerceAtLeast(0),
                    x11 = x11.getState(),
                    mosh = mosh.getState(),
                    agentFwd = agentFwd.getState(),
                    applyPostConnect = postConnect.switch.isChecked,
                    newPostConnect = editPostConnect.text?.toString()
                )
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialogRef.show()
        // Initial state: all rows OFF, summary disabled.
        refreshApplySummary()
    }

    /** Tri-state for bulk-edit boolean fields. */
    private enum class TriState { UNCHANGED, OFF, ON }

    /**
     * Apply bulk edits to connections — only writes fields the user
     * actually opted into. Bool fields use TriState: UNCHANGED leaves
     * each connection's value alone, OFF/ON sets it explicitly.
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
        keepalive: TriState,
        compression: TriState,
        applyTerminalType: Boolean,
        newTerminalType: String,
        applyColorTag: Boolean,
        newColorTag: Int,
        x11: TriState,
        mosh: TriState,
        agentFwd: TriState,
        applyPostConnect: Boolean,
        newPostConnect: String?
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
                if (keepalive != TriState.UNCHANGED) changes.add("keepalive")
                if (compression != TriState.UNCHANGED) changes.add("compression")
                if (applyTerminalType) changes.add("terminal type")
                if (applyColorTag) changes.add("color tag")
                if (x11 != TriState.UNCHANGED) changes.add("X11 forwarding")
                if (mosh != TriState.UNCHANGED) changes.add("Mosh")
                if (agentFwd != TriState.UNCHANGED) changes.add("agent forwarding")
                if (applyPostConnect) changes.add("post-connect script")

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
                            newGroupSelection.startsWith("(Clear") -> null
                            else -> allGroups.find { it.name == newGroupSelection }?.id
                        }
                        updatedConnection = updatedConnection.copy(groupId = newGroupId)
                    }

                    // Apply identity if checked
                    if (applyIdentity) {
                        val newIdentityId = when {
                            newIdentitySelection.isNullOrEmpty() -> null
                            newIdentitySelection.startsWith("(Clear") -> null
                            else -> allIdentities.find { it.name == newIdentitySelection }?.id
                        }
                        updatedConnection = updatedConnection.copy(identityId = newIdentityId)
                    }

                    // Apply timeout if checked
                    if (applyTimeout) {
                        updatedConnection = updatedConnection.copy(connectTimeout = newTimeout)
                    }

                    if (keepalive != TriState.UNCHANGED) {
                        updatedConnection = updatedConnection.copy(keepAlive = keepalive == TriState.ON)
                    }
                    if (compression != TriState.UNCHANGED) {
                        updatedConnection = updatedConnection.copy(compression = compression == TriState.ON)
                    }

                    if (applyTerminalType) {
                        updatedConnection = updatedConnection.copy(terminalType = newTerminalType)
                    }
                    if (applyColorTag) {
                        updatedConnection = updatedConnection.copy(colorTag = newColorTag)
                    }
                    if (x11 != TriState.UNCHANGED) {
                        updatedConnection = updatedConnection.copy(x11Forwarding = x11 == TriState.ON)
                    }
                    if (mosh != TriState.UNCHANGED) {
                        updatedConnection = updatedConnection.copy(useMosh = mosh == TriState.ON)
                    }
                    if (agentFwd != TriState.UNCHANGED) {
                        updatedConnection = updatedConnection.copy(agentForwarding = agentFwd == TriState.ON)
                    }
                    if (applyPostConnect) {
                        // null/blank → clear; otherwise set verbatim.
                        val script = newPostConnect?.takeIf { it.isNotBlank() }
                        updatedConnection = updatedConnection.copy(postConnectScript = script)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
