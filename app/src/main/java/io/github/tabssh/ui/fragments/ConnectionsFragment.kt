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
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    // Issue #165 + #175 — "Active Sessions" strip above the connection
    // list, deferred behind a ViewStub. Lateinit on the stub itself;
    // the inflated views are populated only after first non-empty tabs.
    private lateinit var activeSessionsStub: android.view.ViewStub
    private var activeSessionsContainer: View? = null
    private var activeSessionsRecycler: RecyclerView? = null
    private var activeSessionAdapter: io.github.tabssh.ui.adapters.ActiveSessionAdapter? = null
    private val activeTabTitleObservers = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val activeTabStateObservers = mutableMapOf<String, kotlinx.coroutines.Job>()
    
    private var allConnections = listOf<ConnectionProfile>()
    private var allGroups = listOf<ConnectionGroup>()
    private var allIdentities = listOf<Identity>()
    private var currentSearchQuery = ""
    private var currentSortOption = SortOption.NAME_ASC
    private var currentGroupSortOption = GroupSortOption.NAME_ASC
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

    enum class GroupSortOption(val displayName: String) {
        NAME_ASC("Name (A-Z)"),
        NAME_DESC("Name (Z-A)"),
        CUSTOM("Custom (drag order)")
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
        activeSessionsStub = view.findViewById(R.id.stub_active_sessions)

        setupToolbar()
        setupSearchView()
        setupRecyclerView()
        setupActiveSessionsStrip()
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
        AlertDialog.Builder(requireContext())
            .setTitle("Sort")
            .setItems(arrayOf("Sort connections", "Sort groups")) { _, which ->
                when (which) {
                    0 -> showConnectionSortDialog()
                    1 -> showGroupSortDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConnectionSortDialog() {
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

    private fun showGroupSortDialog() {
        val options = GroupSortOption.values().map { it.displayName }.toTypedArray()
        val currentIndex = currentGroupSortOption.ordinal

        AlertDialog.Builder(requireContext())
            .setTitle("Sort Groups")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentGroupSortOption = GroupSortOption.values()[which]
                saveGroupSortPreference()
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

    private fun saveGroupSortPreference() {
        requireContext().getSharedPreferences("TabSSH", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("groups_sort", currentGroupSortOption.name)
            .apply()
    }

    private fun loadSortPreference() {
        val prefs = requireContext().getSharedPreferences("TabSSH", android.content.Context.MODE_PRIVATE)
        val connectionsPref = prefs.getString("connections_sort", SortOption.NAME_ASC.name)
        currentSortOption = SortOption.entries.find { it.name == connectionsPref } ?: SortOption.NAME_ASC
        val groupsPref = prefs.getString("groups_sort", GroupSortOption.NAME_ASC.name)
        currentGroupSortOption = GroupSortOption.entries.find { it.name == groupsPref } ?: GroupSortOption.NAME_ASC
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

    /**
     * Issue #165 — wire the "Active Sessions" strip. Subscribes to
     * `app.tabManager.tabsFlow` and to each tab's per-instance `title`
     * + `connectionState` flows so the strip updates when a remote sets
     * an OSC 0/2 title or a tab transitions state. Disambiguates same-
     * default-title tabs (multiple tabs to one host with no OSC title)
     * by appending `(#N)`.
     */
    private fun setupActiveSessionsStrip() {
        // ViewStub-deferred — the strip's RecyclerView/header/container
        // are NOT inflated yet. Just collect tabsFlow; the first non-empty
        // emission triggers ensureActiveSessionsInflated().
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tabManager.tabsFlow.collect { tabs -> rebindActiveSessions(tabs) }
            }
        }
    }

    /**
     * Issue #175 — first-call inflates the ViewStub, builds the adapter +
     * LayoutManager. No-op on subsequent calls. Keeps cold-start cost at
     * zero when the user has no running tabs (the common case).
     */
    private fun ensureActiveSessionsInflated() {
        if (activeSessionsContainer != null) return
        val inflated = activeSessionsStub.inflate()
        activeSessionsContainer = inflated
        activeSessionsRecycler = inflated.findViewById(R.id.recycler_active_sessions)
        val recycler = activeSessionsRecycler!!
        val adapter = io.github.tabssh.ui.adapters.ActiveSessionAdapter { tabId ->
            val intent = android.content.Intent(requireContext(), TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tabId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
        )
        recycler.adapter = adapter
        activeSessionAdapter = adapter
    }

    private fun rebindActiveSessions(tabs: List<io.github.tabssh.ui.tabs.SSHTab>) {
        // Cancel observers for tabs that disappeared.
        val live = tabs.map { it.tabId }.toSet()
        (activeTabTitleObservers.keys - live).forEach { id ->
            activeTabTitleObservers.remove(id)?.cancel()
            activeTabStateObservers.remove(id)?.cancel()
        }
        // Subscribe to per-tab title + state for any new tab.
        tabs.forEach { tab ->
            if (tab.tabId !in activeTabTitleObservers) {
                activeTabTitleObservers[tab.tabId] = viewLifecycleOwner.lifecycleScope.launch {
                    tab.title.collect { renderActiveSessionRows() }
                }
                activeTabStateObservers[tab.tabId] = viewLifecycleOwner.lifecycleScope.launch {
                    tab.connectionState.collect { renderActiveSessionRows() }
                }
            }
        }
        renderActiveSessionRows()
    }

    private fun renderActiveSessionRows() {
        val tabs = app.tabManager.getAllTabs()
        if (tabs.isEmpty()) {
            // Don't inflate the stub if we never had to. If it was already
            // inflated (tabs existed earlier), just hide the container.
            activeSessionsContainer?.visibility = View.GONE
            activeSessionAdapter?.submit(emptyList())
            return
        }
        ensureActiveSessionsInflated()
        activeSessionsContainer?.visibility = View.VISIBLE

        // Build display strings as {user}@{host}:{title}. The title source
        // priority is: terminal-set OSC title → tab's default → cwd-style
        // suffix. SSHTab.title can carry a transient state prefix (⏳, ⏸,
        // 🔐, ❌); strip those for the strip — the colour dot already
        // conveys state, so duplicating it as a glyph is noisy.
        val rawDisplays = tabs.map { tab ->
            val user = tab.profile.username
            val host = tab.profile.host
            val cleanTitle = tab.title.value
                .removePrefix("⏳ ")
                .removePrefix("⏸ ")
                .removePrefix("🔐 ")
                .removePrefix("❌ ")
                .trim()
            val userHost = if (user.isNotBlank() && host.isNotBlank()) "$user@$host" else host
            // If the shell already set a title that starts with "user@host",
            // don't repeat the prefix — show the OSC title verbatim.
            val display = when {
                cleanTitle.isBlank() -> userHost
                cleanTitle == userHost -> userHost
                cleanTitle.startsWith("$userHost:") -> cleanTitle
                cleanTitle.startsWith(userHost) -> cleanTitle
                else -> "$userHost:$cleanTitle"
            }
            tab to display
        }

        // Disambiguate exact-duplicate displays (same user@host with no
        // OSC title) by appending (#N) — N is the 1-based running index.
        val occurrences = rawDisplays.groupingBy { it.second }.eachCount()
        val seen = mutableMapOf<String, Int>()
        val rows = rawDisplays.map { (tab, display) ->
            val total = occurrences[display] ?: 1
            val label = if (total > 1) {
                val n = (seen[display] ?: 0) + 1
                seen[display] = n
                "$display (#$n)"
            } else {
                display
            }
            io.github.tabssh.ui.adapters.ActiveSessionAdapter.Row(
                tabId = tab.tabId,
                title = label,
                state = tab.connectionState.value
            )
        }
        activeSessionAdapter?.submit(rows)
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

        // Re-bind rows whenever the SSH session manager's connection-state
        // map changes — adapter reads `isConnectionActive(id)` at bind
        // time, and without this the active dot never updates.
        viewLifecycleOwner.lifecycleScope.launch {
            app.sshSessionManager.connectionStates.collect {
                adapter.notifyDataSetChanged()
                groupedAdapter?.notifyDataSetChanged()
            }
        }
    }
    
    private fun openConnection(connection: ConnectionProfile) {
        Logger.d("ConnectionsFragment", "Opening connection: ${connection.name}")
        // Prompt to reattach if a tab already exists for this profile —
        // ConnectionLauncher handles the dialog + the no-existing-tab fast path.
        io.github.tabssh.ui.utils.ConnectionLauncher.launch(requireContext(), connection)
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
                    0 -> renameGroup(groupHeader.group)
                    1 -> deleteGroup(groupHeader.group)
                    2 -> collapseAllGroups()
                }
            }
            .show()
    }

    private fun renameGroup(group: io.github.tabssh.storage.database.entities.ConnectionGroup) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(group.name)
            hint = "Group name"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Group")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank() && newName != group.name) {
                    lifecycleScope.launch {
                        try {
                            app.database.connectionGroupDao().updateGroup(
                                group.copy(
                                    name = newName,
                                    modifiedAt = System.currentTimeMillis()
                                )
                            )
                            Toast.makeText(requireContext(), "Group renamed to '$newName'", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Logger.e("ConnectionsFragment", "Failed to rename group", e)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup(group: io.github.tabssh.storage.database.entities.ConnectionGroup) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Group")
            .setMessage("Remove group '${group.name}'?\n\nConnections will not be deleted, just ungrouped.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.withTransaction {
                            // Ungroup all connections that belong to this group
                            val connections = app.database.connectionDao().getAllConnectionsList()
                            connections.filter { it.groupId == group.id }.forEach { conn ->
                                app.database.connectionDao().updateConnection(conn.copy(groupId = null))
                            }
                            // Delete the group row
                            app.database.connectionGroupDao().deleteGroup(group)
                        }
                        Toast.makeText(requireContext(), "Group '${group.name}' deleted", Toast.LENGTH_SHORT).show()
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
     * Bulk-edit dialog (rewrite, 2026-05-02).
     *
     * Layout (`dialog_bulk_edit.xml`) is now four MaterialCardView
     * sections — Connection / Behavior / Terminal / Advanced — each with
     * full-width inputs. The per-field "Apply" switch is gone; apply is
     * driven by value-presence:
     *   • Text fields: empty → ignored. Non-empty → applied.
     *   • Dropdowns: blank text → ignored. "(Clear …)" item → write null.
     *     Any other selection → applied.
     *   • Tri-state booleans (`include_bulk_edit_tristate.xml`):
     *     "Don't change" (default) / "Off" / "On".
     *
     * The header shows a live "N changes will apply to M connections"
     * count and disables Apply when N == 0. Reset zeroes every input
     * back to its default ignored state.
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

        // Forward-declared so all the listeners we wire below close over
        // the final body. Assigned just before show().
        var refreshApplySummary: () -> Unit = {}

        // ── Inputs ──
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPort = dialogView.findViewById<TextInputEditText>(R.id.edit_port)
        val dropdownGroup = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_group)
        val dropdownIdentity = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_identity)
        val editTimeout = dialogView.findViewById<TextInputEditText>(R.id.edit_timeout)
        val dropdownTerminalType = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_terminal_type)
        val dropdownColorTag = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdown_color_tag)
        val editPostConnect = dialogView.findViewById<TextInputEditText>(R.id.edit_post_connect)

        val textInputs = listOf(editUsername, editPort, editTimeout, editPostConnect)
        val dropdowns = listOf(dropdownGroup, dropdownIdentity, dropdownTerminalType, dropdownColorTag)

        // Wire each input so the live summary updates on every change.
        textInputs.forEach { input ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { refreshApplySummary() }
            })
        }
        // AutoCompleteTextView reports text-watcher changes too, but only
        // for keystrokes — programmatic setText(_, false) doesn't notify.
        // The dropdown itemClickListener fires on selection, so we hook
        // both for symmetry.
        dropdowns.forEach { dd ->
            dd.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { refreshApplySummary() }
            })
            dd.setOnItemClickListener { _, _, _, _ -> refreshApplySummary() }
        }

        // ── Tri-state bool rows ──
        // Each row: set its label + icon, default to "Don't change",
        // notify summary on every selection change.
        data class TriRow(val getState: () -> TriState)

        fun wireTriState(rowId: Int, label: String, iconRes: Int): TriRow {
            val rowView = dialogView.findViewById<View>(rowId)
            rowView.findViewById<TextView>(R.id.tri_label).text = label
            rowView.findViewById<android.widget.ImageView>(R.id.tri_icon)
                .setImageResource(iconRes)
            val triGroup = rowView.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tri_group)
            triGroup.check(R.id.tri_unchanged)
            triGroup.addOnButtonCheckedListener { _, _, _ -> refreshApplySummary() }
            return TriRow {
                when (triGroup.checkedButtonId) {
                    R.id.tri_off -> TriState.OFF
                    R.id.tri_on -> TriState.ON
                    else -> TriState.UNCHANGED
                }
            }
        }

        val compression = wireTriState(R.id.row_compression, "Compression", R.drawable.ic_file_archive)
        val agentFwd = wireTriState(R.id.row_agent_fwd, "Agent forwarding", R.drawable.ic_forward)
        val x11 = wireTriState(R.id.row_x11, "X11 forwarding", R.drawable.ic_interface)
        val mosh = wireTriState(R.id.row_mosh, "Mosh", R.drawable.ic_flash)

        // ── Dropdown options ──
        val terminalTypeOptions = arrayOf("xterm-256color", "xterm", "vt100", "vt220", "screen-256color", "tmux-256color")
        dropdownTerminalType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, terminalTypeOptions))

        // colorTag indices map 1:1 to entry order — index 0 = "(none)" = no tag.
        val colorTagOptions = arrayOf("(none)", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink")
        dropdownColorTag.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, colorTagOptions))

        val groupOptions = mutableListOf("(Clear group assignment)")
        groupOptions.addAll(allGroups.map { it.name })
        dropdownGroup.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, groupOptions))

        // Identity dropdown — populated async from Room flow.
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

        // ── Apply summary (live) ──
        // Counts each field that would change. A change counts when:
        //  - text input is non-blank (text fields with parseable ints
        //    additionally need to parse — guard inside),
        //  - dropdown text is non-blank,
        //  - tri-state is not UNCHANGED.
        var dialogRef: AlertDialog? = null
        refreshApplySummary = {
            var n = 0
            if (!editUsername.text.isNullOrBlank()) n++
            if (editPort.text?.toString()?.toIntOrNull() != null) n++
            if (!dropdownGroup.text.isNullOrBlank()) n++
            if (!dropdownIdentity.text.isNullOrBlank()) n++
            if (editTimeout.text?.toString()?.toIntOrNull() != null) n++
            if (!dropdownTerminalType.text.isNullOrBlank()) n++
            if (!dropdownColorTag.text.isNullOrBlank()) n++
            if (!editPostConnect.text.isNullOrBlank()) n++
            if (compression.getState() != TriState.UNCHANGED) n++
            if (agentFwd.getState() != TriState.UNCHANGED) n++
            if (x11.getState() != TriState.UNCHANGED) n++
            if (mosh.getState() != TriState.UNCHANGED) n++

            textApplySummary.text = if (n == 0) {
                "No changes yet — fill in any field below to enqueue it"
            } else {
                "$n field${if (n == 1) "" else "s"} will change on ${connections.size} connection${if (connections.size == 1) "" else "s"}"
            }
            dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                btn.isEnabled = n > 0
                btn.text = if (n == 0) "Apply" else "Apply ($n)"
            }
        }

        // ── Reset all ──
        buttonResetAll.setOnClickListener {
            editUsername.text = null
            editPort.text = null
            editTimeout.text = null
            editPostConnect.text = null
            dropdownGroup.setText("", false)
            dropdownIdentity.setText("", false)
            dropdownTerminalType.setText("", false)
            dropdownColorTag.setText("", false)
            listOf(R.id.row_compression, R.id.row_agent_fwd, R.id.row_x11, R.id.row_mosh)
                .forEach { id ->
                    dialogView.findViewById<View>(id)
                        .findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tri_group)
                        .check(R.id.tri_unchanged)
                }
            refreshApplySummary()
        }

        dialogRef = AlertDialog.Builder(ctx)
            .setTitle("Bulk edit")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                applyBulkEdit(
                    connections = connections,
                    newUsername = editUsername.text?.toString()?.takeIf { it.isNotBlank() },
                    newPort = editPort.text?.toString()?.toIntOrNull(),
                    newGroupSelection = dropdownGroup.text?.toString()?.takeIf { it.isNotBlank() },
                    newIdentitySelection = dropdownIdentity.text?.toString()?.takeIf { it.isNotBlank() },
                    newTimeout = editTimeout.text?.toString()?.toIntOrNull(),
                    compression = compression.getState(),
                    newTerminalType = dropdownTerminalType.text?.toString()?.takeIf { it.isNotBlank() },
                    newColorTagSelection = dropdownColorTag.text?.toString()?.takeIf { it.isNotBlank() },
                    colorTagOptions = colorTagOptions,
                    x11 = x11.getState(),
                    mosh = mosh.getState(),
                    agentFwd = agentFwd.getState(),
                    newPostConnect = editPostConnect.text?.toString()?.takeIf { it.isNotBlank() }
                )
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialogRef.show()
        refreshApplySummary()
    }

    /** Tri-state for bulk-edit boolean fields. */
    private enum class TriState { UNCHANGED, OFF, ON }

    /**
     * Apply bulk edits — value-presence semantics. Each text/dropdown
     * argument is null when the user left that field empty, in which
     * case the connection's existing value is left alone. Tri-state
     * booleans are explicit: UNCHANGED skips, OFF/ON sets.
     *
     * For dropdowns, a "(Clear …)" selection from the user is forwarded
     * here as a non-null string starting with "(Clear" — we resolve that
     * to writing null on the entity (explicit clear).
     */
    private fun applyBulkEdit(
        connections: List<ConnectionProfile>,
        newUsername: String?,
        newPort: Int?,
        newGroupSelection: String?,
        newIdentitySelection: String?,
        newTimeout: Int?,
        compression: TriState,
        newTerminalType: String?,
        newColorTagSelection: String?,
        colorTagOptions: Array<String>,
        x11: TriState,
        mosh: TriState,
        agentFwd: TriState,
        newPostConnect: String?
    ) {
        lifecycleScope.launch {
            try {
                val changes = mutableListOf<String>()

                if (newUsername != null) changes.add("username")
                if (newPort != null) changes.add("port")
                if (newGroupSelection != null) changes.add("group")
                if (newIdentitySelection != null) changes.add("identity")
                if (newTimeout != null) changes.add("timeout")
                if (compression != TriState.UNCHANGED) changes.add("compression")
                if (newTerminalType != null) changes.add("terminal type")
                if (newColorTagSelection != null) changes.add("color tag")
                if (x11 != TriState.UNCHANGED) changes.add("X11 forwarding")
                if (mosh != TriState.UNCHANGED) changes.add("Mosh")
                if (agentFwd != TriState.UNCHANGED) changes.add("agent forwarding")
                if (newPostConnect != null) changes.add("post-connect script")

                if (changes.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), "No changes selected", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Resolve dropdown name → entity ID up front so we don't get
                // stuck silently nullifying when the dialog's identity/group
                // flow hasn't emitted yet (the previous bug). Read directly
                // from the DB instead of trusting the in-memory caches.
                val resolvedGroupId: String? = if (newGroupSelection != null) {
                    if (newGroupSelection.startsWith("(Clear")) {
                        null
                    } else {
                        val match = app.database.connectionGroupDao().getAllGroups().first()
                            .find { it.name == newGroupSelection }
                        if (match == null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Bulk edit aborted: group '$newGroupSelection' not found",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            Logger.w("ConnectionsFragment", "Bulk edit: group '$newGroupSelection' did not resolve to a row")
                            return@launch
                        }
                        match.id
                    }
                } else null

                val resolvedIdentityId: String? = if (newIdentitySelection != null) {
                    if (newIdentitySelection.startsWith("(Clear")) {
                        null
                    } else {
                        val match = app.database.identityDao().getAllIdentitiesList()
                            .find { it.name == newIdentitySelection }
                        if (match == null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "Bulk edit aborted: identity '$newIdentitySelection' not found",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            Logger.w("ConnectionsFragment", "Bulk edit: identity '$newIdentitySelection' did not resolve to a row")
                            return@launch
                        }
                        match.id
                    }
                } else null

                Logger.d("ConnectionsFragment", "Bulk edit: applying ${changes.joinToString(", ")} to ${connections.size} connections")

                // One transaction so Room emits the Flow once at the end
                // instead of per-row (was producing 30+ redundant
                // `Loaded 37 connections` log lines on a 37-row bulk apply).
                var updatedCount = 0
                app.database.withTransaction {
                    for (connection in connections) {
                        var updated = connection

                        if (newUsername != null) {
                            updated = updated.copy(username = newUsername)
                        }
                        if (newPort != null) {
                            updated = updated.copy(port = newPort)
                        }
                        if (newGroupSelection != null) {
                            updated = updated.copy(groupId = resolvedGroupId)
                        }
                        if (newIdentitySelection != null) {
                            updated = updated.copy(identityId = resolvedIdentityId)
                        }
                        if (newTimeout != null) {
                            updated = updated.copy(connectTimeout = newTimeout)
                        }
                        if (compression != TriState.UNCHANGED) {
                            updated = updated.copy(compression = compression == TriState.ON)
                        }
                        if (newTerminalType != null) {
                            updated = updated.copy(terminalType = newTerminalType)
                        }
                        if (newColorTagSelection != null) {
                            // colorTagOptions index maps 1:1 to ConnectionProfile.colorTag.
                            // Index 0 = "(none)" which is correctly stored as 0 (no tag).
                            val idx = colorTagOptions.indexOf(newColorTagSelection).coerceAtLeast(0)
                            updated = updated.copy(colorTag = idx)
                        }
                        if (x11 != TriState.UNCHANGED) {
                            updated = updated.copy(x11Forwarding = x11 == TriState.ON)
                        }
                        if (mosh != TriState.UNCHANGED) {
                            updated = updated.copy(useMosh = mosh == TriState.ON)
                        }
                        if (agentFwd != TriState.UNCHANGED) {
                            updated = updated.copy(agentForwarding = agentFwd == TriState.ON)
                        }
                        if (newPostConnect != null) {
                            updated = updated.copy(postConnectScript = newPostConnect)
                        }

                        app.database.connectionDao().updateConnection(updated)
                        updatedCount++
                    }
                }

                Logger.d("ConnectionsFragment", "Bulk edit completed: $updatedCount connections updated (${changes.joinToString(", ")})")
                android.widget.Toast.makeText(requireContext(), "Updated $updatedCount connections: ${changes.joinToString(", ")}", android.widget.Toast.LENGTH_SHORT).show()

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
        // Issue #158 — gate collection behind repeatOnLifecycle(STARTED) so the
        // Flow setup doesn't run synchronously during the first layout pass.
        // viewPager2 attaches the fragment view → onViewCreated runs → the old
        // bare lifecycleScope.launch executed combine().collect on Main.immediate
        // before the activity finished its initial traversal, contributing to a
        // multi-second main-thread freeze on cold start.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
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
    }
    
    private fun applyGroupedView() {
        // Build ConnectionListItem list
        val items = mutableListOf<ConnectionListItem>()
        
        // Add grouped connections
        val sortedGroups = when (currentGroupSortOption) {
            GroupSortOption.NAME_ASC -> allGroups.sortedBy { it.name.lowercase() }
            GroupSortOption.NAME_DESC -> allGroups.sortedByDescending { it.name.lowercase() }
            GroupSortOption.CUSTOM -> allGroups.sortedWith(
                compareBy({ it.sortOrder }, { it.name.lowercase() })
            )
        }
        for (group in sortedGroups) {
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
