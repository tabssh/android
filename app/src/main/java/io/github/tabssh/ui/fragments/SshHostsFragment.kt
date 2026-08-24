package io.github.tabssh.ui.fragments

import io.github.tabssh.sync.tombstone.TombstoneRecorder
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
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.ui.tabs.connectionDisplayName
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts tab's SSH sub-tab — grouped list, sort, bulk edit, and multi-select
 * for every [ConnectionProfile] with `protocol == "ssh"` (Mosh folds in via
 * the existing `moshMode` field on the same protocol; no separate filter).
 * Search lives on the outer [ConnectionsFragment], not here.
 */
class SshHostsFragment : Fragment() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
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
    // Default to grouped view
    private var useGroupedView = true

    // Multi-select mode
    private var isSelectionMode = false
    // Connection IDs
    private val selectedConnections = mutableSetOf<String>()

    enum class SortOption(@StringRes val displayNameRes: Int) {
        NAME_ASC(R.string.connections_sort_name_asc),
        NAME_DESC(R.string.connections_sort_name_desc),
        HOST_ASC(R.string.connections_sort_host_asc),
        HOST_DESC(R.string.connections_sort_host_desc),
        MOST_USED(R.string.connections_sort_most_used),
        LEAST_USED(R.string.connections_sort_least_used),
        RECENTLY_CONNECTED(R.string.connections_sort_recently_connected),
        OLDEST_CONNECTED(R.string.connections_sort_oldest_connected)
    }

    enum class GroupSortOption(@StringRes val displayNameRes: Int) {
        NAME_ASC(R.string.connections_group_sort_name_asc),
        NAME_DESC(R.string.connections_group_sort_name_desc),
        CUSTOM(R.string.connections_group_sort_custom)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ssh_hosts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        app = requireActivity().application as TabSSHApplication
        
        toolbar = view.findViewById(R.id.toolbar_connections)
        recyclerView = view.findViewById(R.id.recycler_connections)
        emptyLayout = view.findViewById(R.id.layout_empty_connections)
        activeSessionsStub = view.findViewById(R.id.stub_active_sessions)

        setupToolbar()
        setupRecyclerView()
        setupActiveSessionsStrip()
        loadSortPreference()
        loadAllConnections()
        
        Logger.d("SshHostsFragment", "Fragment created")
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_sort_dialog_title)
            .setItems(
                arrayOf(
                    getString(R.string.connections_sort_scope_connections),
                    getString(R.string.connections_sort_scope_groups)
                )
            ) { _, which ->
                when (which) {
                    0 -> showConnectionSortDialog()
                    1 -> showGroupSortDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConnectionSortDialog() {
        val options = SortOption.entries.map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = currentSortOption.ordinal

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_sort_connections_title)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSortOption = SortOption.values()[which]
                saveSortPreference()
                applySortAndFilter()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGroupSortDialog() {
        val options = GroupSortOption.entries.map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = currentGroupSortOption.ordinal

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_sort_groups_title)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentGroupSortOption = GroupSortOption.values()[which]
                saveGroupSortPreference()
                applySortAndFilter()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
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


    /**
     * Issue #165 — wire the "Active Sessions" strip. Subscribes to
     * `app.tabManager.allTabsFlow` (every kind of tab — SSH, VNC, and
     * hypervisor console) and to each tab's per-instance `title` +
     * `connectionState` flows so the strip updates when a remote sets an
     * OSC 0/2 title or a tab transitions state. Disambiguates same-
     * default-title tabs (multiple tabs to one host with no OSC title)
     * by appending `(#N)`.
     */
    private fun setupActiveSessionsStrip() {
        // ViewStub-deferred — the strip's RecyclerView/header/container
        // are NOT inflated yet. Just collect allTabsFlow; the first non-empty
        // emission triggers ensureActiveSessionsInflated().
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tabManager.allTabsFlow.collect { tabs -> rebindActiveSessions(tabs) }
            }
        }
    }

    /** Per-instance title flow for any tab kind — chip labels update live. */
    private fun Tab.titleFlow() = when (this) {
        is Tab.Ssh -> sshTab.title
        is Tab.Vnc -> vncTab.title
        is Tab.Console -> consoleTab.title
        is Tab.Panes -> panesTab.title
    }

    /** Per-instance connection-state flow for any tab kind — drives the dot. */
    private fun Tab.stateFlow() = when (this) {
        is Tab.Ssh -> sshTab.connectionState
        is Tab.Vnc -> vncTab.connectionState
        is Tab.Console -> consoleTab.connectionState
        // A Panes tab has no single connection state — its own panes each
        // track theirs. Surface CONNECTED whenever at least one pane is
        // connected so the strip doesn't hide/misrepresent an active group;
        // DISCONNECTED only once every pane has dropped.
        is Tab.Panes -> kotlinx.coroutines.flow.MutableStateFlow(
            if (panesTab.currentEntries().any {
                    it.sshTab?.connectionState?.value == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED
                }
            ) io.github.tabssh.ssh.connection.ConnectionState.CONNECTED
            else io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED
        )
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
        inflated.findViewById<View>(R.id.text_active_sessions_see_all)
            .setOnClickListener { showAllActiveSessionsDialog() }
    }

    /**
     * "See all" expansion — the strip is a horizontal preview that clips
     * once there are more active sessions than fit on screen. This dialog
     * lists every active session (SSH/VNC/Console/Panes) vertically,
     * unclipped, reusing the same label logic as the strip.
     */
    private fun showAllActiveSessionsDialog() {
        val tabs = app.tabManager.getAllTabsSealed().filter { tab ->
            tab.stateFlow().value != io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED &&
                (tab as? Tab.Ssh)?.sshTab?.profile?.id?.startsWith("docker-exec:") != true
        }
        if (tabs.isEmpty()) return

        val labels = disambiguatedActiveSessionLabels(tabs)
        val rows = tabs.map { tab ->
            io.github.tabssh.ui.adapters.AllActiveSessionsAdapter.Row(
                tabId = tab.tabId,
                title = labels.getValue(tab.tabId),
                subtitle = when (tab) {
                    is Tab.Ssh -> getString(R.string.auth_tab_ssh)
                    is Tab.Vnc -> getString(R.string.auth_tab_vnc)
                    is Tab.Console -> getString(R.string.connections_session_type_console)
                    is Tab.Panes -> getString(R.string.main_tab_panes)
                },
                state = tab.stateFlow().value
            )
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_active_sessions_list, null, false)
        val dialogRecycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.recycler_all_active_sessions
        )
        dialogRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        var dialogRef: androidx.appcompat.app.AlertDialog? = null
        val dialogAdapter = io.github.tabssh.ui.adapters.AllActiveSessionsAdapter { tabId ->
            dialogRef?.dismiss()
            val intent = android.content.Intent(requireContext(), TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tabId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
        dialogRecycler.adapter = dialogAdapter
        dialogAdapter.submit(rows)

        dialogRef = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_active_sessions_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Shared label logic for the Active Sessions strip and its "See all"
     * dialog. SSH: profile name else user@host (never the OSC terminal
     * title). VNC/Console/Panes: the tab's own display title. Identical
     * labels are disambiguated with a stable `(#N)` suffix.
     */
    private fun disambiguatedActiveSessionLabels(tabs: List<Tab>): Map<String, String> {
        val rawDisplays = tabs.map { tab -> tab to tab.connectionDisplayName() }
        val occurrences = rawDisplays.groupingBy { it.second }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return rawDisplays.associate { (tab, display) ->
            val total = occurrences[display] ?: 1
            val label = if (total > 1) {
                val n = (seen[display] ?: 0) + 1
                seen[display] = n
                getString(R.string.connections_session_duplicate_fmt, display, n)
            } else {
                display
            }
            tab.tabId to label
        }
    }

    private fun rebindActiveSessions(tabs: List<Tab>) {
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
                    tab.titleFlow().collect { renderActiveSessionRows() }
                }
                activeTabStateObservers[tab.tabId] = viewLifecycleOwner.lifecycleScope.launch {
                    tab.stateFlow().collect { renderActiveSessionRows() }
                }
            }
        }
        renderActiveSessionRows()
    }

    private fun renderActiveSessionRows() {
        // Only show tabs that are actively connecting or connected.
        // DISCONNECTED tabs are dead slots — showing them after a notification
        // disconnect misleads the user into thinking there is still a live session.
        // Docker exec tabs (profile id "docker-exec:…") are excluded: Docker is
        // a separate domain like hypervisors, not part of the SSH connection
        // list, so its container shells don't belong in the sessions strip.
        val tabs = app.tabManager.getAllTabsSealed().filter { tab ->
            tab.stateFlow().value != io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED &&
                (tab as? Tab.Ssh)?.sshTab?.profile?.id?.startsWith("docker-exec:") != true
        }
        if (tabs.isEmpty()) {
            // Don't inflate the stub if we never had to. If it was already
            // inflated (tabs existed earlier), just hide the container.
            activeSessionsContainer?.visibility = View.GONE
            activeSessionAdapter?.submit(emptyList())
            return
        }
        ensureActiveSessionsInflated()
        activeSessionsContainer?.visibility = View.VISIBLE

        // Build chip labels for the active sessions strip. SSH: profile name
        // else user@host, never the OSC terminal title (see
        // disambiguatedActiveSessionLabels doc). VNC/Console/Panes: the
        // tab's own display title.
        val labels = disambiguatedActiveSessionLabels(tabs)
        val rows = tabs.map { tab ->
            io.github.tabssh.ui.adapters.ActiveSessionAdapter.Row(
                tabId = tab.tabId,
                title = labels.getValue(tab.tabId),
                state = tab.stateFlow().value
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
                // Skip-DiffUtil rationale: list contents are unchanged — this
                // is a forced rebind so rows can re-read the external
                // `isConnectionActive(id)` flag they show as a status dot.
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                groupedAdapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
            }
        }
    }
    
    private fun openConnection(connection: ConnectionProfile) {
        Logger.d("SshHostsFragment", "Opening connection: ${connection.name}")
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
        val items = arrayOf(
            getString(R.string.connections_menu_connect),
            getString(R.string.connections_menu_browse_files),
            getString(R.string.edit),
            getString(R.string.connections_menu_duplicate),
            getString(R.string.delete)
        )

        MaterialAlertDialogBuilder(requireContext())
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
        Logger.d("SshHostsFragment", "Opening SFTP browser for ${connection.name}")
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
                    name = getString(R.string.connections_duplicate_name_fmt, connection.name),
                    connectionCount = 0,
                    lastConnected = 0
                )
                app.database.connectionDao().insertConnection(duplicate)
                Logger.d("SshHostsFragment", "Connection duplicated: ${duplicate.name}")
            } catch (e: Exception) {
                Logger.e("SshHostsFragment", "Failed to duplicate connection", e)
            }
        }
    }
    
    private fun deleteConnection(connection: ConnectionProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_delete_title)
            .setMessage(getString(R.string.connections_delete_message_fmt, connection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.connectionDao().deleteConnection(connection)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.CONNECTION, connection.id)
                            // Clean up orphan soft-FK references left by this connection.
                            app.database.monitorSlotDao().deleteByConnectionId(connection.id)
                            app.database.hypervisorDao().clearLinkedConnectionId(connection.id)
                            // Keep the Panes registry and any saved pane-group membership accurate.
                            io.github.tabssh.storage.registry.ConnectableHostRegistry
                                .removeConnectionProfile(app.database, connection.id)
                            // clearPassword is suspend + IO-dispatched (KeyStore HAL round-trip).
                            try { app.securePasswordManager.clearPassword(connection.id) } catch (_: Exception) {}
                        }
                        Logger.d("SshHostsFragment", "Connection deleted: ${connection.name}")
                    } catch (e: Exception) {
                        Logger.e("SshHostsFragment", "Failed to delete connection", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGroupMenu(groupHeader: ConnectionListItem.GroupHeader) {
        val items = arrayOf(
            getString(R.string.connections_group_menu_bulk_edit),
            getString(R.string.connections_group_menu_rename),
            getString(R.string.connections_group_menu_delete),
            getString(R.string.connections_group_menu_collapse_all)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(groupHeader.group.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val groupConnections = allConnections.filter { it.groupId == groupHeader.group.id }
                        if (groupConnections.isEmpty()) {
                            Toast.makeText(requireContext(), R.string.connections_group_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            showBulkEditDialog(groupConnections)
                        }
                    }
                    1 -> renameGroup(groupHeader.group)
                    2 -> deleteGroup(groupHeader.group)
                    3 -> collapseAllGroups()
                }
            }
            .show()
    }

    private fun renameGroup(group: io.github.tabssh.storage.database.entities.ConnectionGroup) {
        val form = io.github.tabssh.ui.dialogs.DialogFields.form(requireContext())
        val editText = io.github.tabssh.ui.dialogs.DialogFields.addText(
            form, hint = getString(R.string.group_rename_hint), initial = group.name
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_group_rename_title)
            .setView(form.root)
            .setPositiveButton(R.string.connections_group_rename_confirm) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank() && newName != group.name) {
                    lifecycleScope.launch {
                        try {
                            // Check for duplicate group name before renaming
                            val existing = app.database.connectionGroupDao().getGroupByName(newName)
                            if (existing != null && existing.id != group.id) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.connections_group_name_taken_fmt, newName),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            app.database.connectionGroupDao().updateGroup(
                                group.copy(
                                    name = newName,
                                    modifiedAt = System.currentTimeMillis()
                                )
                            )
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.connections_group_renamed_fmt, newName),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Logger.e("SshHostsFragment", "Failed to rename group", e)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteGroup(group: io.github.tabssh.storage.database.entities.ConnectionGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_group_delete_title)
            .setMessage(getString(R.string.connections_group_delete_message_fmt, group.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.database.withTransaction {
                            // Ungroup all connections that belong to this group
                            val connections = app.database.connectionDao().getAllConnectionsList()
                            connections.filter { it.groupId == group.id }.forEach { conn ->
                                app.database.connectionDao().updateConnection(conn.copy(groupId = null))
                            }
                            // Nullify group_id on any VNC hosts assigned to this group
                            app.database.vncHostDao().nullifyGroupId(group.id)
                            // Nullify group_id on any telnet hosts assigned to this group
                            app.database.telnetHostDao().nullifyGroupId(group.id)
                            // Delete the group row
                            app.database.connectionGroupDao().deleteGroup(group)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.GROUP, group.id)
                        }
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.connections_group_deleted_fmt, group.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Logger.e("SshHostsFragment", "Failed to delete group", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun collapseAllGroups() {
        groupedAdapter?.collapseAll()
        Toast.makeText(requireContext(), R.string.connections_groups_collapsed, Toast.LENGTH_SHORT).show()
    }

    /**
     * Show bulk edit options dialog. Three scopes — all, by group, by
     * multi-select. Bulk DELETE has its own entry point (long-press
     * → "Select Multiple to Delete") so it doesn't pollute this menu.
     */
    private fun showBulkEditOptions() {
        val options = arrayOf(
            getString(R.string.connections_bulk_scope_all_fmt, allConnections.size),
            getString(R.string.connections_bulk_scope_group),
            getString(R.string.connections_bulk_scope_pick_edit),
            getString(R.string.connections_bulk_scope_pick_delete)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_bulk_edit_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBulkEditDialog(allConnections)
                    1 -> showGroupSelectionForBulkEdit()
                    2 -> enterSelectionMode()
                    3 -> enterSelectionMode(deleteMode = true)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Show group selection dialog for bulk editing
     */
    private fun showGroupSelectionForBulkEdit() {
        if (allGroups.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.connections_no_groups, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val groupNames = allGroups.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_select_group_title)
            .setItems(groupNames) { _, which ->
                val selectedGroup = allGroups[which]
                val groupConnections = allConnections.filter { it.groupId == selectedGroup.id }
                if (groupConnections.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(), R.string.connections_group_empty, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showBulkEditDialog(groupConnections)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Enter multi-select mode
     */
    private fun enterSelectionMode(deleteMode: Boolean = false) {
        isSelectionMode = true
        selectedConnections.clear()
        toolbar.setTitle(
            if (deleteMode) R.string.connections_selection_title_delete
            else R.string.connections_selection_title_edit
        )
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener { exitSelectionMode() }

        // Show action button for bulk edit
        toolbar.menu.findItem(R.id.action_bulk_edit)?.isVisible = false
        toolbar.menu.findItem(R.id.action_sort)?.isVisible = false

        val hint = if (deleteMode) {
            R.string.connections_selection_hint_delete
        } else {
            R.string.connections_selection_hint_edit
        }
        android.widget.Toast.makeText(requireContext(), hint, android.widget.Toast.LENGTH_LONG).show()

        // Update adapter click behavior — long-press triggers the chosen action.
        adapter.setOnItemLongClickListener { _ ->
            if (selectedConnections.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), R.string.connections_selection_none, android.widget.Toast.LENGTH_SHORT).show()
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                resources.getQuantityString(
                    R.plurals.connections_bulk_delete_title, selected.size, selected.size
                )
            )
            .setMessage(R.string.connections_bulk_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    var deleted = 0
                    val app = requireActivity().application as io.github.tabssh.TabSSHApplication
                    for (c in selected) {
                        try {
                            withContext(Dispatchers.IO) {
                                app.database.connectionDao().deleteConnection(c)
                                // H6 — record the deletion so it propagates and is not resurrected.
                                TombstoneRecorder.record(app, TombstoneRecorder.CONNECTION, c.id)
                                // Clean up orphan soft-FK references left by this connection.
                                app.database.monitorSlotDao().deleteByConnectionId(c.id)
                                app.database.hypervisorDao().clearLinkedConnectionId(c.id)
                                // Keep the Panes registry and any saved pane-group membership accurate.
                                io.github.tabssh.storage.registry.ConnectableHostRegistry
                                    .removeConnectionProfile(app.database, c.id)
                                // clearPassword is suspend + IO-dispatched (KeyStore HAL round-trip).
                                // Without IO dispatch, N deletions in a loop = N KeyStore round-trips on Main → ANR.
                                try { app.securePasswordManager.clearPassword(c.id) } catch (_: Exception) {}
                            }
                            deleted++
                        } catch (e: Exception) {
                            Logger.e("SshHostsFragment", "Bulk delete failed for ${c.name}", e)
                        }
                    }
                    android.widget.Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(
                            R.plurals.connections_bulk_deleted, deleted, deleted
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Exit multi-select mode
     */
    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedConnections.clear()
        // Hosts tab has no toolbar title (the "Hosts" tab label above already
        // names the screen) — clear back to that, not the old "Connections" text.
        toolbar.title = ""
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
            Toast.makeText(requireContext(), R.string.connections_bulk_no_connections, Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_bulk_edit, null)

        // ── Header ──
        val textSelectedCount = dialogView.findViewById<TextView>(R.id.text_selected_count)
        val textApplySummary = dialogView.findViewById<TextView>(R.id.text_apply_summary)
        val buttonResetAll = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_reset_all)
        textSelectedCount.text = resources.getQuantityString(
            R.plurals.connections_bulk_selected_count, connections.size, connections.size
        )

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

        fun wireTriState(rowId: Int, @StringRes labelRes: Int, iconRes: Int): TriRow {
            val rowView = dialogView.findViewById<View>(rowId)
            rowView.findViewById<TextView>(R.id.tri_label).setText(labelRes)
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

        val compression = wireTriState(R.id.row_compression, R.string.connections_bulk_tri_compression, R.drawable.ic_file_archive)
        val agentFwd = wireTriState(R.id.row_agent_fwd, R.string.connections_bulk_tri_agent_fwd, R.drawable.ic_forward)
        val x11 = wireTriState(R.id.row_x11, R.string.connections_bulk_tri_x11, R.drawable.ic_interface)
        val mosh = wireTriState(R.id.row_mosh, R.string.connections_bulk_tri_mosh, R.drawable.ic_flash)

        // ── Dropdown options ──
        val terminalTypeOptions = resources.getStringArray(R.array.connections_terminal_type_options)
        dropdownTerminalType.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, terminalTypeOptions))

        // colorTag indices map 1:1 to entry order — index 0 is the "no tag" entry.
        val colorTagOptions = resources.getStringArray(R.array.connections_color_tag_options)
        dropdownColorTag.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, colorTagOptions))

        val groupOptions = mutableListOf(getString(R.string.connections_bulk_clear_group))
        groupOptions.addAll(allGroups.filter { it.groupType.isEmpty() }.map { it.name })
        dropdownGroup.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, groupOptions))

        // Identity dropdown — one-shot fetch (not a continuous collect).
        // A continuous Flow collect would overwrite the adapter on every
        // identity-table emission while the dialog is open, potentially
        // resetting the user's in-progress selection. viewLifecycleOwner
        // scope still cancels the in-flight first() if the view is destroyed.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val identities = app.database.identityDao().getAllIdentities().first()
                allIdentities = identities
                val opts = mutableListOf(getString(R.string.connections_bulk_clear_identity))
                    .also { it.addAll(identities.map { i -> i.name }) }
                dropdownIdentity.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, opts))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("SshHostsFragment", "Failed to load identities", e)
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
                getString(R.string.connections_bulk_summary_none)
            } else {
                getString(
                    R.string.connections_bulk_summary_fmt,
                    resources.getQuantityString(R.plurals.connections_bulk_summary_fields, n, n),
                    resources.getQuantityString(
                        R.plurals.connections_bulk_summary_connections,
                        connections.size,
                        connections.size
                    )
                )
            }
            dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                btn.isEnabled = n > 0
                btn.text = if (n == 0) {
                    getString(R.string.connections_bulk_apply)
                } else {
                    getString(R.string.connections_bulk_apply_count_fmt, n)
                }
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

        dialogRef = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.connections_bulk_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.connections_bulk_apply) { _, _ ->
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
            .setNegativeButton(R.string.cancel, null)
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
     * For dropdowns, the localized "clear" entry is forwarded here as a
     * non-null string equal to that entry's own string resource — we
     * resolve that to writing null on the entity (explicit clear).
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
                // Two parallel lists: localized names for the toast the user
                // reads, stable English keys for the log lines.
                val changes = mutableListOf<String>()
                val changeKeys = mutableListOf<String>()
                fun addChange(@StringRes labelRes: Int, key: String) {
                    changes.add(getString(labelRes))
                    changeKeys.add(key)
                }

                if (newUsername != null) addChange(R.string.connections_field_username, "username")
                if (newPort != null) addChange(R.string.connections_field_port, "port")
                if (newGroupSelection != null) addChange(R.string.connections_field_group, "group")
                if (newIdentitySelection != null) addChange(R.string.connections_field_identity, "identity")
                if (newTimeout != null) addChange(R.string.connections_field_timeout, "timeout")
                if (compression != TriState.UNCHANGED) addChange(R.string.connections_field_compression, "compression")
                if (newTerminalType != null) addChange(R.string.connections_field_terminal_type, "terminalType")
                if (newColorTagSelection != null) addChange(R.string.connections_field_color_tag, "colorTag")
                if (x11 != TriState.UNCHANGED) addChange(R.string.connections_field_x11, "x11Forwarding")
                if (mosh != TriState.UNCHANGED) addChange(R.string.connections_field_mosh, "moshMode")
                if (agentFwd != TriState.UNCHANGED) addChange(R.string.connections_field_agent_forwarding, "agentForwarding")
                if (newPostConnect != null) addChange(R.string.connections_field_post_connect, "postConnectScript")

                if (changes.isEmpty()) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        R.string.connections_bulk_no_changes,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // Resolve dropdown name → entity ID up front so we don't get
                // stuck silently nullifying when the dialog's identity/group
                // flow hasn't emitted yet (the previous bug). Read directly
                // from the DB instead of trusting the in-memory caches.
                val resolvedGroupId: String? = if (newGroupSelection != null) {
                    if (newGroupSelection == getString(R.string.connections_bulk_clear_group)) {
                        null
                    } else {
                        val match = app.database.connectionGroupDao().getAllGroups().first()
                            .find { it.name == newGroupSelection }
                        if (match == null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(R.string.connections_bulk_group_missing_fmt, newGroupSelection),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            Logger.w("SshHostsFragment", "Bulk edit: group '$newGroupSelection' did not resolve to a row")
                            return@launch
                        }
                        match.id
                    }
                } else null

                val resolvedIdentityId: String? = if (newIdentitySelection != null) {
                    if (newIdentitySelection == getString(R.string.connections_bulk_clear_identity)) {
                        null
                    } else {
                        val match = app.database.identityDao().getAllIdentitiesList()
                            .find { it.name == newIdentitySelection }
                        if (match == null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(R.string.connections_bulk_identity_missing_fmt, newIdentitySelection),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            Logger.w("SshHostsFragment", "Bulk edit: identity '$newIdentitySelection' did not resolve to a row")
                            return@launch
                        }
                        match.id
                    }
                } else null

                Logger.d("SshHostsFragment", "Bulk edit: applying ${changeKeys.joinToString(", ")} to ${connections.size} connections")

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
                            // Index 0 is the "no tag" entry, correctly stored as 0.
                            val idx = colorTagOptions.indexOf(newColorTagSelection).coerceAtLeast(0)
                            updated = updated.copy(colorTag = idx)
                        }
                        if (x11 != TriState.UNCHANGED) {
                            updated = updated.copy(x11Forwarding = x11 == TriState.ON)
                        }
                        if (mosh != TriState.UNCHANGED) {
                            updated = updated.copy(moshMode = if (mosh == TriState.ON) "on" else "off")
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

                Logger.d("SshHostsFragment", "Bulk edit completed: $updatedCount connections updated (${changeKeys.joinToString(", ")})")
                android.widget.Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(
                        R.plurals.connections_bulk_updated,
                        updatedCount,
                        updatedCount,
                        changes.joinToString(getString(R.string.connections_list_separator))
                    ),
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                if (isSelectionMode) {
                    exitSelectionMode()
                }

            } catch (e: Exception) {
                Logger.e("SshHostsFragment", "Bulk edit failed", e)
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.connections_bulk_failed_fmt, e.message.orEmpty()),
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
                        allConnections = connections.filter { it.protocol == "ssh" }
                        allGroups = groups

                        if (useGroupedView) {
                            applyGroupedView()
                        } else {
                            applySortAndFilter()
                        }

                        Logger.d("SshHostsFragment", "Loaded ${connections.size} connections, ${groups.size} groups")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("SshHostsFragment", "Failed to load connections", e)
                }
            }
        }
    }

    private fun applyGroupedView() {
        // Build ConnectionListItem list
        val items = mutableListOf<ConnectionListItem>()

        // Add grouped connections — user groups first, VM Hosts groups at the end,
        // cloud groups hidden entirely (their connections don't appear in this view).
        val userGroups = allGroups
            .filter { it.groupType != "cloud" && it.groupType != "vm_hosts" }
            .let { list ->
                when (currentGroupSortOption) {
                    GroupSortOption.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                    GroupSortOption.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
                    GroupSortOption.CUSTOM -> list.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
                }
            }
        val vmGroups = allGroups.filter { it.groupType == "vm_hosts" }
            .sortedBy { it.name.lowercase() }
        val sortedGroups = userGroups + vmGroups
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

        // Add ungrouped items (null groupId OR groupId that doesn't exist in allGroups).
        // Exclude connections whose groupId belongs to a cloud system group — they are not
        // shown in the Hosts view at all.
        val existingGroupIds = allGroups.map { it.id }.toSet()
        val cloudGroupIds = allGroups.filter { it.groupType == "cloud" }.map { it.id }.toSet()
        val ungroupedConnections = allConnections.filter {
            (it.groupId == null || it.groupId !in existingGroupIds) && it.groupId !in cloudGroupIds
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
                onGroupClick = { groupHeader -> toggleGroupExpanded(groupHeader) },
                onGroupLongClick = { groupHeader -> showGroupMenu(groupHeader) }
            )
            recyclerView.adapter = groupedAdapter
        } else {
            // Update existing adapter
            groupedAdapter!!.replaceAllWithDiff(
                items = groupedAdapter!!.items,
                newItems = items,
                areItemsTheSame = { a, b ->
                    when {
                        a is io.github.tabssh.ui.models.ConnectionListItem.GroupHeader &&
                            b is io.github.tabssh.ui.models.ConnectionListItem.GroupHeader ->
                            a.group.id == b.group.id
                        a is io.github.tabssh.ui.models.ConnectionListItem.Connection &&
                            b is io.github.tabssh.ui.models.ConnectionListItem.Connection ->
                            a.profile.id == b.profile.id
                        a is io.github.tabssh.ui.models.ConnectionListItem.UngroupedHeader &&
                            b is io.github.tabssh.ui.models.ConnectionListItem.UngroupedHeader -> true
                        else -> false
                    }
                }
            )
            // filterConnections() switches recyclerView.adapter to the flat
            // `adapter` while a search is active; if that happened, this call
            // (returning to grouped view after the query is cleared, or on a
            // Flow re-collect after a tab switch) must switch it back — the
            // "if (groupedAdapter == null)" branch above only does this once,
            // on first creation, otherwise recyclerView stays bound to the flat
            // adapter's last (possibly empty, search-filtered) list forever.
            if (recyclerView.adapter !== groupedAdapter) {
                recyclerView.adapter = groupedAdapter
            }
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
                Logger.d("SshHostsFragment", "Toggled group '${groupHeader.group.name}' collapsed=$newCollapsed")
                // Reload will happen via Flow
            } catch (e: Exception) {
                Logger.e("SshHostsFragment", "Failed to toggle group", e)
            }
        }
    }
    
    private fun applySortAndFilter() {
        filterConnections(currentSearchQuery)
    }

    private fun filterConnections(query: String) {
        // When grouped view is active and the query is cleared, delegate back
        // to applyGroupedView() so the RecyclerView shows the grouped adapter again.
        if (useGroupedView && query.isEmpty()) {
            applyGroupedView()
            return
        }

        // Search mode: switch RecyclerView to the flat adapter so results are visible.
        // applyGroupedView() sets recyclerView.adapter = groupedAdapter; we must undo
        // that when the user starts typing, otherwise submitList() goes to the wrong adapter.
        if (recyclerView.adapter !== adapter) {
            recyclerView.adapter = adapter
        }

        val filtered = allConnections.filter { connection ->
            query.isEmpty() ||
            connection.name.contains(query, ignoreCase = true) ||
            connection.host.contains(query, ignoreCase = true) ||
            connection.username.contains(query, ignoreCase = true)
        }

        // Apply sort
        val sorted = applySortToList(filtered)

        if (sorted.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyLayout.visibility = View.GONE
            // Provide group name map so search results show a group badge.
            // allGroups is already observed in the same scope; no extra DB call needed.
            adapter.updateGroupNames(allGroups.associate { it.id to it.name })
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
        fun newInstance() = SshHostsFragment()
    }
}
