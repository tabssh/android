package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.hypervisor.console.ConsoleEventListener
import io.github.tabssh.hypervisor.console.HypervisorConsoleManager
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.ui.tabs.ConsoleConnectParams
import io.github.tabssh.ui.tabs.HypervisorConsoleType
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of VMs on a single Proxmox hypervisor. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 */
class ProxmoxManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProxmoxManager"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private val vms = mutableListOf<ProxmoxApiClient.ProxmoxVM>()
    private var currentClient: ProxmoxApiClient? = null
    private var currentProfile: HypervisorProfile? = null
    private lateinit var adapter: VmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxmox_manager)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Proxmox"

        adapter = VmAdapter(vms)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refreshVMs() }

        val hypervisorId = intent.getLongExtra(EXTRA_HYPERVISOR_ID, -1L)
        if (hypervisorId == -1L) {
            showError("No hypervisor ID provided")
            return
        }
        connectAndRefresh(hypervisorId)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // Cancel any in-flight HTTP calls so OkHttp does not retain Activity
        // references through callbacks past onDestroy.
        try { currentClient?.cancelAll() } catch (e: Exception) { Logger.w(TAG, "cancelAll: ${e.message}") }
        super.onDestroy()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectAndRefresh(hypervisorId: Long) {
        lifecycleScope.launch {
            showProgress("Connecting…")
            val profile = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getById(hypervisorId)
            }
            if (profile == null) {
                showError("Hypervisor profile not found (id=$hypervisorId)")
                return@launch
            }
            currentProfile = profile
            supportActionBar?.title = profile.name

            try {
                val creds = HypervisorPasswordStore.resolveCredentials(this@ProxmoxManagerActivity, profile)
                val client = ProxmoxApiClient(
                    host = profile.host,
                    port = profile.port,
                    username = creds.username,
                    password = creds.password,
                    realm = creds.realm ?: "pam",
                    verifySsl = profile.verifySsl,
                    pinnedCertSha256 = profile.pinnedCertSha256
                )
                val ok = client.authenticate()
                if (!ok) {
                    showError("Authentication failed — check credentials")
                    return@launch
                }
                val capturedSha = client.getCapturedCertSha256()
                HypervisorPasswordStore.persistCapturedPinIfAny(
                    this@ProxmoxManagerActivity, profile, capturedSha
                )
                if (!capturedSha.isNullOrBlank()) currentProfile = profile.copy(pinnedCertSha256 = capturedSha)
                app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                currentClient = client
                loadVMs(client)
            } catch (e: Exception) {
                Logger.e(TAG, "Connect failed", e)
                showError("Connection failed: proxmox ${profile.name}: ${e.message}")
            }
        }
    }

    private fun refreshVMs() {
        val client = currentClient ?: run {
            Toast.makeText(this, "Not connected — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { loadVMs(client) }
    }

    private suspend fun loadVMs(client: ProxmoxApiClient) {
        showProgress("Loading VMs…")
        try {
            val vmList = client.getAllVMs() ?: emptyList()
            // Fetch IPs for running VMs concurrently — guest-agent queries can
            // take up to ~4 s each; serial would stall the list for N×4 s.
            val vmsWithIPs = coroutineScope {
                vmList.map { vm ->
                    async {
                        if (vm.status == "running") {
                            val ip = try {
                                client.getVMIPAddress(vm.node, vm.vmid, vm.type)
                            } catch (e: Exception) {
                                Logger.d(TAG, "No IP for ${vm.vmid}: ${e.message}")
                                null
                            }
                            vm.copy(ipAddress = ip)
                        } else vm
                    }
                }.awaitAll()
            }
            adapter.replaceAllWithDiff(
                items = vms,
                newItems = vmsWithIPs,
                areItemsTheSame = { a, b -> a.vmid == b.vmid && a.node == b.node }
            )
            hideProgress()
            if (vms.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = "No VMs found"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "loadVMs failed", e)
            showError("Could not load VMs: ${e.message}")
        }
    }

    // ── VM actions ────────────────────────────────────────────────────────────

    private fun confirmHardReset(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient) {
        AlertDialog.Builder(this)
            .setTitle("Hard Reset ${vm.name}?")
            .setMessage("This is equivalent to pulling the power cord. Any unsaved data will be lost.")
            .setPositiveButton("Reset") { _, _ -> powerAction(vm, client, "reset") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun powerAction(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient, action: String) {
        lifecycleScope.launch {
            showProgress("${action.replaceFirstChar { it.uppercase() }}ing ${vm.name}…")
            try {
                val ok = withContext(Dispatchers.IO) {
                    when (action) {
                        "start"  -> client.startVM(vm.node, vm.vmid, vm.type)
                        "stop"   -> client.stopVM(vm.node, vm.vmid, vm.type)
                        "reboot" -> client.rebootVM(vm.node, vm.vmid, vm.type)
                        "reset"  -> client.resetVM(vm.node, vm.vmid, vm.type)
                        else     -> false
                    }
                }
                if (ok) {
                    Toast.makeText(this@ProxmoxManagerActivity, "${vm.name}: $action sent", Toast.LENGTH_SHORT).show()
                    delay(2000)
                    loadVMs(client)
                } else {
                    hideProgress()
                    showError("$action failed for ${vm.name}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "$action failed for ${vm.name}", e)
                showError("$action failed: ${e.message}")
            }
        }
    }

    /**
     * VNC-tab-swipe integration step 6e: opens the VM's serial/graphical
     * console as a [io.github.tabssh.ui.tabs.Tab.Console] inside
     * [TabTerminalActivity] instead of launching the standalone
     * `VMConsoleActivity`. Mirrors [LibvirtManagerActivity.openConsole]'s
     * precedent of creating the tab only *after* the connection succeeds —
     * `TabManager` has no by-id/by-reference tab-removal API, so a
     * connect-failure never needs to leave behind (or clean up) a
     * half-created tab.
     */
    private fun openConsole(vm: ProxmoxApiClient.ProxmoxVM) {
        val profile = currentProfile ?: return
        val client = currentClient ?: return
        lifecycleScope.launch {
            showProgress("Connecting to ${vm.name}…")
            val creds = HypervisorPasswordStore.resolveCredentials(this@ProxmoxManagerActivity, profile)
            val manager = HypervisorConsoleManager()

            // Captured after the tab is created below — onSwitchToGraphical
            // can only fire once the initial connect (which runs first, on
            // this same coroutine) has already returned, so the tab always
            // exists by the time this listener needs it.
            var consoleTab: io.github.tabssh.ui.tabs.ConsoleTab? = null
            val listener = object : ConsoleEventListener {
                override fun onConnected(vmName: String) = Unit
                override fun onDisconnected(reason: String) {
                    runOnUiThread { consoleTab?.setConnectionState(ConnectionState.DISCONNECTED) }
                }
                override fun onError(message: String) {
                    Logger.w(TAG, "Console error for ${vm.name}: $message")
                }
                override fun onStrategyAttempt(strategyName: String) {
                    // One spinner; its text updates as the chain falls through (PLAN 14)
                    runOnUiThread {
                        showProgress("Connecting to ${vm.name} (${HypervisorConsoleManager.strategyLabel(strategyName)})…")
                    }
                }
                override fun onSwitchToGraphical(connection: HypervisorConsoleManager.ConsoleConnection.Graphical) {
                    runOnUiThread { consoleTab?.markGraphical(connection.rfbClient) }
                }
            }

            try {
                val connection = manager.connectProxmoxConsole(
                    client = client,
                    node = vm.node,
                    vmid = vm.vmid,
                    vmName = vm.name,
                    type = vm.type,
                    verifySsl = profile.verifySsl,
                    pinnedCertSha256 = profile.pinnedCertSha256,
                    displayHost = profile.host,
                    displayPort = profile.port,
                    listener = listener
                )
                if (connection == null) {
                    // Do NOT show a second generic error here — every code
                    // path that returns null has already surfaced a specific
                    // message via listener.onError() (see VMConsoleActivity's
                    // connectToConsole() for the double-dialog race this
                    // avoids).
                    hideProgress()
                    return@launch
                }

                val tab = app.tabManager.createConsoleTab(
                    ConsoleConnectParams(
                        type = HypervisorConsoleType.PROXMOX,
                        host = profile.host,
                        port = profile.port,
                        username = creds.username,
                        password = creds.password,
                        verifySsl = profile.verifySsl,
                        pinnedCertSha256 = profile.pinnedCertSha256,
                        vmId = vm.vmid.toString(),
                        vmName = vm.name,
                        vmNode = vm.node,
                        vmType = vm.type,
                        realm = creds.realm ?: "pam"
                    )
                )
                if (tab == null) {
                    manager.disconnect()
                    hideProgress()
                    Toast.makeText(this@ProxmoxManagerActivity, "Maximum tabs reached", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                consoleTab = tab
                tab.consoleManager = manager

                when (connection) {
                    is HypervisorConsoleManager.ConsoleConnection.Text -> {
                        val cursorStyle = app.preferencesManager.getCursorStyleInt()
                        val bridge = withContext(Dispatchers.IO) {
                            TermuxBridge(columns = 80, rows = 24, transcriptRows = 2000, cursorStyle = cursorStyle)
                                .also { it.initialize() }
                        }
                        manager.wireToTerminal(connection, bridge)
                        bridge.onResizeCallback = { cols, rows -> manager.getWebSocketClient()?.sendResize(cols, rows) }
                        tab.termuxBridge = bridge
                        tab.setConnectionState(ConnectionState.CONNECTED)
                    }
                    is HypervisorConsoleManager.ConsoleConnection.Graphical -> {
                        tab.markGraphical(connection.rfbClient)
                        tab.setConnectionState(ConnectionState.CONNECTED)
                    }
                    is HypervisorConsoleManager.ConsoleConnection.Spice -> {
                        // Construct un-started; ConsoleViewHolder attaches the
                        // SpiceView listener and performs the single start().
                        val spiceClient = io.github.tabssh.hypervisor.spice.SpiceClient(connection.spiceParams)
                        tab.markSpice(spiceClient)
                        tab.setConnectionState(ConnectionState.CONNECTED)
                    }
                }

                hideProgress()
                startActivity(
                    Intent(this@ProxmoxManagerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
                Logger.i(TAG, "Opened console tab for ${vm.name} (vmid=${vm.vmid})")
            } catch (e: Exception) {
                Logger.e(TAG, "Console connection error for ${vm.name}", e)
                hideProgress()
                showError("Connection failed: vm ${vm.name}: ${e.message}")
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(message: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = message
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            progressBar.visibility = View.GONE
            statusText.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            statusText.text = "Error: $message"
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class VmAdapter(
        private val items: List<ProxmoxApiClient.ProxmoxVM>
    ) : RecyclerView.Adapter<VmAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val state: TextView = view.findViewById(R.id.vm_state)
            val info: TextView = view.findViewById(R.id.vm_info)
            val ip: TextView = view.findViewById(R.id.vm_ip)
            val statusDot: View = view.findViewById(R.id.view_status_dot)
            val rowConnect: android.widget.LinearLayout = view.findViewById(R.id.row_connect)
            val rowMain: android.widget.LinearLayout = view.findViewById(R.id.row_main)
            val rowSecondary: android.widget.LinearLayout = view.findViewById(R.id.row_secondary)
            val btnConsole: MaterialButton = view.findViewById(R.id.btn_console)
            val btnSsh: MaterialButton = view.findViewById(R.id.btn_ssh)
            val btnStart: MaterialButton = view.findViewById(R.id.btn_start)
            val btnStop: MaterialButton = view.findViewById(R.id.btn_stop)
            val btnReboot: MaterialButton = view.findViewById(R.id.btn_reboot)
            val btnReset: MaterialButton = view.findViewById(R.id.btn_reset)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_hypervisor_vm, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vm = items[position]
            val client = currentClient ?: return

            holder.name.text = "${vm.name} (${vm.vmid})"
            holder.state.text = stateLabel(vm.status)
            holder.state.setTextColor(stateColor(vm.status))
            holder.info.text = "Node: ${vm.node}  ·  VMID: ${vm.vmid}  ·  CPU: ${(vm.cpu * 100).toInt()}%  ·  RAM: ${vm.mem / 1024 / 1024}MB"
            if (vm.ipAddress != null) {
                holder.ip.text = "IP: ${vm.ipAddress}"
                holder.ip.visibility = View.VISIBLE
            } else {
                holder.ip.visibility = View.GONE
            }

            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(vm.status))

            // Button visibility by state
            when (vm.status.lowercase()) {
                "running" -> {
                    holder.btnConsole.visibility = View.VISIBLE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.VISIBLE
                    holder.btnReset.visibility = View.VISIBLE
                }
                "stopped" -> {
                    holder.btnConsole.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.GONE
                    holder.btnReboot.visibility = View.GONE
                    holder.btnReset.visibility = View.GONE
                }
                else -> {
                    holder.btnConsole.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.GONE
                    holder.btnReset.visibility = View.GONE
                }
            }

            // Show rows based on which buttons are active
            holder.rowConnect.visibility = if (holder.btnConsole.visibility == View.VISIBLE || holder.btnSsh.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowMain.visibility = if (holder.btnStart.visibility == View.VISIBLE || holder.btnStop.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowSecondary.visibility = if (holder.btnReboot.visibility == View.VISIBLE || holder.btnReset.visibility == View.VISIBLE) View.VISIBLE else View.GONE

            // Long-press opens the snapshot management dialog
            holder.itemView.setOnLongClickListener {
                showSnapshotDialog(vm, client)
                true
            }

            holder.btnConsole.setOnClickListener { openConsole(vm) }
            holder.btnStart.setOnClickListener { powerAction(vm, client, "start") }
            holder.btnStop.setOnClickListener { powerAction(vm, client, "stop") }
            holder.btnReboot.setOnClickListener { powerAction(vm, client, "reboot") }
            holder.btnReset.setOnClickListener { confirmHardReset(vm, client) }
        }

        private fun stateColor(state: String): Int = when (state.lowercase()) {
            "running"    -> 0xFF4CAF50.toInt()
            "stopped"    -> 0xFFF44336.toInt()
            "paused"     -> 0xFFFF9800.toInt()
            "restarting" -> 0xFFFF5722.toInt()
            else         -> 0xFF9E9E9E.toInt()
        }

        private fun stateLabel(state: String): String = when (state.lowercase()) {
            "running"    -> "Running"
            "stopped"    -> "Stopped"
            "paused"     -> "Paused"
            "restarting" -> "Restarting"
            else         -> state.replaceFirstChar { it.uppercase() }
        }
    }

    // ── Snapshot management ───────────────────────────────────────────────────

    /**
     * Show snapshot management dialog for a VM. Opened by long-press on a VM
     * row; mirrors [XCPngManagerActivity]'s snapshot dialog pattern.
     */
    private fun showSnapshotDialog(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val dialogProgress = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val snapshotRecycler = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<android.widget.Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<android.widget.Button>(R.id.close_button)

        snapshotRecycler.layoutManager = LinearLayoutManager(this)
        vmNameText.text = "VM: ${vm.name}"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Load snapshots
        lifecycleScope.launch {
            try {
                dialogProgress.visibility = View.VISIBLE
                val snapshots = client.listSnapshots(vm.node, vm.vmid, vm.type)
                dialogProgress.visibility = View.GONE

                if (snapshots.isEmpty()) {
                    emptyStateText.visibility = View.VISIBLE
                    snapshotRecycler.visibility = View.GONE
                } else {
                    emptyStateText.visibility = View.GONE
                    snapshotRecycler.visibility = View.VISIBLE
                    snapshotRecycler.adapter = SnapshotAdapter(snapshots) { snapshot, action ->
                        handleSnapshotAction(vm, client, snapshot, action, dialog)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load snapshots: ${e.message}", e)
                dialogProgress.visibility = View.GONE
                emptyStateText.text = "Error loading snapshots"
                emptyStateText.visibility = View.VISIBLE
            }
        }

        createButton.setOnClickListener {
            showCreateSnapshotDialog(vm, client, dialog)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Show create snapshot dialog
     */
    private fun showCreateSnapshotDialog(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient, parentDialog: AlertDialog) {
        val input = android.widget.EditText(this)
        input.hint = "Snapshot name"
        // Proxmox snapshot names must be config IDs (letter first, no spaces) — default differs from XCPng's
        input.setText("snap${System.currentTimeMillis()}")

        AlertDialog.Builder(this)
            .setTitle("Create Snapshot")
            .setMessage("Enter a name for the snapshot of ${vm.name}")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        val success = client.createSnapshot(vm.node, vm.vmid, vm.type, name)
                        if (success) {
                            Toast.makeText(this@ProxmoxManagerActivity, "Snapshot created", Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            // Refresh
                            showSnapshotDialog(vm, client)
                        } else {
                            Toast.makeText(this@ProxmoxManagerActivity, "Failed to create snapshot", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Snapshot creation error: ${e.message}", e)
                        Toast.makeText(this@ProxmoxManagerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient, snapshot: ProxmoxApiClient.ProxmoxSnapshot, action: String, parentDialog: AlertDialog) {
        when (action) {
            "revert" -> {
                AlertDialog.Builder(this)
                    .setTitle("Revert to Snapshot")
                    .setMessage("Are you sure you want to revert ${vm.name} to snapshot '${snapshot.name}'?\n\nThis will restore the VM to its state when the snapshot was taken.")
                    .setPositiveButton("Revert") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = client.rollbackSnapshot(vm.node, vm.vmid, vm.type, snapshot.name)
                                if (success) {
                                    Toast.makeText(this@ProxmoxManagerActivity, "VM reverted to snapshot", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    Toast.makeText(this@ProxmoxManagerActivity, "Failed to revert", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Revert error: ${e.message}", e)
                                Toast.makeText(this@ProxmoxManagerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            "delete" -> {
                AlertDialog.Builder(this)
                    .setTitle("Delete Snapshot")
                    .setMessage("Are you sure you want to delete snapshot '${snapshot.name}'?\n\nThis action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = client.deleteSnapshot(vm.node, vm.vmid, vm.type, snapshot.name)
                                if (success) {
                                    Toast.makeText(this@ProxmoxManagerActivity, "Snapshot deleted", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    // Refresh
                                    showSnapshotDialog(vm, client)
                                } else {
                                    Toast.makeText(this@ProxmoxManagerActivity, "Failed to delete snapshot", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Delete error: ${e.message}", e)
                                Toast.makeText(this@ProxmoxManagerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /**
     * Snapshot adapter for RecyclerView
     */
    private inner class SnapshotAdapter(
        private val snapshots: List<ProxmoxApiClient.ProxmoxSnapshot>,
        private val onAction: (ProxmoxApiClient.ProxmoxSnapshot, String) -> Unit
    ) : RecyclerView.Adapter<SnapshotAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.snapshot_name)
            val time: TextView = view.findViewById(R.id.snapshot_time)
            val revertButton: android.widget.Button = view.findViewById(R.id.revert_button)
            val deleteButton: android.widget.Button = view.findViewById(R.id.delete_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_snapshot, parent, false)
            return VH(v)
        }

        override fun getItemCount() = snapshots.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val snapshot = snapshots[position]

            holder.name.text = snapshot.name
            // snaptime is epoch seconds; null while the snapshot is still being created
            holder.time.text = if (snapshot.snaptime != null) {
                "Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.snaptime * 1000))}"
            } else {
                snapshot.description ?: "Creating…"
            }

            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }
    }
}
