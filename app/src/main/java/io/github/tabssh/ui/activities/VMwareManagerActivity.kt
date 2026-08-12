package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.hypervisor.vmware.VMwareApiClient
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of VMs on a single VMware ESXi/vCenter host. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 *
 * "Open Console" creates or updates a [ConnectionProfile] for the VM's IP and
 * launches [TabTerminalActivity] — VMware's web-based VMRC is not supported
 * on Android; SSH to the guest is the practical alternative.
 */
class VMwareManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VMwareManager"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private val vms = mutableListOf<VMwareApiClient.VMwareVM>()
    private var currentClient: VMwareApiClient? = null
    private var currentProfile: HypervisorProfile? = null
    private lateinit var adapter: VmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vmware_manager)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "VMware"

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
                val creds = HypervisorPasswordStore.resolveCredentials(this@VMwareManagerActivity, profile)
                val client = VMwareApiClient(
                    host = profile.host,
                    username = creds.username,
                    password = creds.password,
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
                    this@VMwareManagerActivity, profile, capturedSha
                )
                if (!capturedSha.isNullOrBlank()) currentProfile = profile.copy(pinnedCertSha256 = capturedSha)
                val serverType = if (client.isVCenter()) "vCenter" else "ESXi"
                Logger.i(TAG, "Connected to ${profile.name} ($serverType)")
                app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                currentClient = client
                loadVMs(client)
            } catch (e: Exception) {
                Logger.e(TAG, "Connect failed", e)
                showError("Connection failed: vmware ${profile.name}: ${e.message}")
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

    private suspend fun loadVMs(client: VMwareApiClient) {
        showProgress("Loading VMs…")
        try {
            val vmList = client.getAllVMs() ?: emptyList()
            adapter.replaceAllWithDiff(
                items = vms,
                newItems = vmList,
                areItemsTheSame = { a, b -> a.vm == b.vm }
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

    private fun confirmStop(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient) {
        AlertDialog.Builder(this)
            .setTitle("Stop ${vm.name}?")
            .setMessage("Shutdown Guest asks the guest OS to shut down cleanly (requires VMware Tools). Power Off forcibly cuts power — any unsaved data will be lost.")
            .setPositiveButton("Shutdown Guest") { _, _ -> vmAction(vm, client, "shutdown") }
            .setNeutralButton("Power Off") { _, _ -> vmAction(vm, client, "stop") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmHardReset(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient) {
        AlertDialog.Builder(this)
            .setTitle("Hard Reset ${vm.name}?")
            .setMessage("This is equivalent to pulling the power cord. Any unsaved data will be lost.")
            .setPositiveButton("Reset") { _, _ -> vmAction(vm, client, "reset") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun vmAction(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient, action: String) {
        lifecycleScope.launch {
            // Human-readable progress label per action verb
            val progressLabel = when (action) {
                "start"    -> "Starting"
                "stop"     -> "Stopping"
                "shutdown" -> "Shutting down"
                "reboot"   -> "Restarting"
                "reset"    -> "Resetting"
                else       -> action.replaceFirstChar { it.uppercase() }
            }
            showProgress("$progressLabel ${vm.name}…")
            try {
                val ok = withContext(Dispatchers.IO) {
                    when (action) {
                        "start"    -> client.startVM(vm.vm)
                        "stop"     -> client.stopVM(vm.vm)
                        "shutdown" -> { client.shutdownVM(vm.vm); true }
                        "reboot"   -> { client.rebootGuest(vm.vm); true }
                        "reset"    -> client.resetVM(vm.vm)
                        else       -> false
                    }
                }
                if (ok) {
                    Toast.makeText(this@VMwareManagerActivity, "${vm.name}: $action sent", Toast.LENGTH_SHORT).show()
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

    private fun openSshConsole(vm: VMwareApiClient.VMwareVM) {
        val ip = vm.ipAddress ?: run {
            Toast.makeText(this, "VM IP address not available", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val connectionName = "VMware: ${vm.name}"
                var connection = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getByName(connectionName)
                }
                if (connection == null) {
                    val vmHostsGroupId = withContext(Dispatchers.IO) {
                        SystemGroupHelper.getOrCreateSystemGroupId(
                            app.database, "vm_hosts", "VM Hosts", "vm"
                        )
                    }
                    connection = ConnectionProfile(
                        name = connectionName,
                        host = ip,
                        port = 22,
                        username = "root",
                        authType = io.github.tabssh.ssh.auth.AuthType.PASSWORD.name,
                        groupId = vmHostsGroupId,
                        createdAt = System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().insertConnection(connection)
                    }
                } else {
                    connection = connection.copy(host = ip, modifiedAt = System.currentTimeMillis())
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().updateConnection(connection)
                    }
                }
                val intent = TabTerminalActivity.createIntent(this@VMwareManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                Logger.i(TAG, "Launching SSH to $ip for ${vm.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open SSH terminal for ${vm.name}", e)
                showError("Failed to open terminal: ${e.message}", "Error")
            }
        }
    }

    /**
     * Open a graphical VNC console for the VM via RemoteDisplay.vnc.* in the vmx.
     * Reads host/port/password over the vim25 SOAP endpoint, opens a direct TCP
     * socket to the ESXi host, and hands the stream to an ephemeral VNC tab —
     * mirrors [LibvirtManagerActivity]'s openConsole flow.
     */
    private fun openVncConsole(vm: VMwareApiClient.VMwareVM) {
        val client = currentClient ?: run {
            Toast.makeText(this, "Not connected — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            showProgress("Opening console for ${vm.name}…")
            try {
                val (info, socket) = withContext(Dispatchers.IO) {
                    val info = client.getVncConsoleInfo(vm.vm)
                    val socket = java.net.Socket()
                    try {
                        socket.soTimeout = 30_000
                        socket.connect(java.net.InetSocketAddress(info.host, info.port), 15_000)
                    } catch (e: Throwable) {
                        // Close the fd on connect failure so we never leak the socket
                        try { socket.close() } catch (ignored: Exception) {}
                        throw e
                    }
                    Pair(info, socket)
                }
                val rfbClient = io.github.tabssh.hypervisor.console.rfb.RfbClient(
                    inputStream = socket.getInputStream(),
                    outputStream = socket.getOutputStream(),
                    vncPassword = info.password,
                    rawSocket = socket,
                    consoleMode = true
                )
                // Ephemeral hypervisor consoles never auto-relaunch on resize rejection
                rfbClient.canRequestResize = false
                val tab = app.tabManager.createVncTab(vncHost = null, ephemeralDisplayName = vm.name)
                hideProgress()
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    Toast.makeText(this@VMwareManagerActivity, "Maximum tabs reached", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                startActivity(
                    Intent(this@VMwareManagerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open VNC console for ${vm.name}", e)
                showError(e.message ?: "Failed to open VNC console for ${vm.name}")
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
        private val items: List<VMwareApiClient.VMwareVM>
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

            holder.name.text = vm.name
            holder.state.text = stateLabel(vm.powerState)
            holder.state.setTextColor(stateColor(vm.powerState))
            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(vm.powerState))
            holder.info.text = "CPUs: ${vm.cpuCount}  ·  RAM: ${vm.memoryMB}MB"
            if (!vm.ipAddress.isNullOrBlank()) {
                holder.ip.text = "IP: ${vm.ipAddress}"
                holder.ip.visibility = View.VISIBLE
            } else {
                holder.ip.visibility = View.GONE
            }

            holder.btnStart.text = "Power On"
            holder.btnStop.text = "Stop"
            holder.btnReboot.text = "Restart Guest"
            holder.btnReset.text = "Hard Reset"

            // Button visibility by power state
            when (vm.powerState.uppercase()) {
                "POWERED_ON" -> {
                    holder.btnConsole.visibility = View.VISIBLE
                    holder.btnSsh.visibility = if (!vm.ipAddress.isNullOrBlank()) View.VISIBLE else View.GONE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.VISIBLE
                    holder.btnReset.visibility = View.VISIBLE
                }
                "POWERED_OFF" -> {
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

            holder.rowConnect.visibility = if (holder.btnConsole.visibility == View.VISIBLE || holder.btnSsh.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowMain.visibility = if (holder.btnStart.visibility == View.VISIBLE || holder.btnStop.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowSecondary.visibility = if (holder.btnReboot.visibility == View.VISIBLE || holder.btnReset.visibility == View.VISIBLE) View.VISIBLE else View.GONE

            // Long-press opens the snapshot management dialog
            holder.itemView.setOnLongClickListener { showSnapshotDialog(vm); true }

            holder.btnConsole.setOnClickListener { openVncConsole(vm) }
            holder.btnSsh.setOnClickListener { openSshConsole(vm) }
            holder.btnStart.setOnClickListener { vmAction(vm, client, "start") }
            holder.btnStop.setOnClickListener { confirmStop(vm, client) }
            holder.btnReboot.setOnClickListener { vmAction(vm, client, "reboot") }
            holder.btnReset.setOnClickListener { confirmHardReset(vm, client) }
        }

        private fun stateColor(state: String): Int = when (state.uppercase()) {
            "POWERED_ON"  -> androidx.core.content.ContextCompat.getColor(this@VMwareManagerActivity, R.color.status_success)
            "POWERED_OFF" -> androidx.core.content.ContextCompat.getColor(this@VMwareManagerActivity, R.color.status_error)
            "SUSPENDED"   -> androidx.core.content.ContextCompat.getColor(this@VMwareManagerActivity, R.color.status_warning)
            else          -> androidx.core.content.ContextCompat.getColor(this@VMwareManagerActivity, R.color.status_neutral)
        }

        private fun stateLabel(state: String): String = when (state.uppercase()) {
            "POWERED_ON"  -> "Running"
            "POWERED_OFF" -> "Stopped"
            "SUSPENDED"   -> "Paused"
            else          -> state.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    // ── Snapshot management dialog ────────────────────────────────────────────

    /**
     * Show snapshot management dialog for a VM
     */
    private fun showSnapshotDialog(vm: VMwareApiClient.VMwareVM) {
        val client = currentClient ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val dialogProgressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val snapshotRecyclerView = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)

        snapshotRecyclerView.layoutManager = LinearLayoutManager(this)
        vmNameText.text = "VM: ${vm.name}"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        // Load snapshots
        lifecycleScope.launch {
            try {
                dialogProgressBar.visibility = View.VISIBLE
                val snapshots = client.listSnapshots(vm.vm)
                dialogProgressBar.visibility = View.GONE

                if (snapshots.isEmpty()) {
                    emptyStateText.visibility = View.VISIBLE
                    snapshotRecyclerView.visibility = View.GONE
                } else {
                    emptyStateText.visibility = View.GONE
                    snapshotRecyclerView.visibility = View.VISIBLE
                    snapshotRecyclerView.adapter = SnapshotAdapter(snapshots) { snapshot, action ->
                        handleSnapshotAction(vm, snapshot, action, dialog)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load snapshots: ${e.message}", e)
                dialogProgressBar.visibility = View.GONE
                emptyStateText.text = "Error loading snapshots"
                emptyStateText.visibility = View.VISIBLE
            }
        }

        createButton.setOnClickListener {
            showCreateSnapshotDialog(vm, dialog)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Show create snapshot dialog
     */
    private fun showCreateSnapshotDialog(vm: VMwareApiClient.VMwareVM, parentDialog: AlertDialog) {
        val form = DialogFields.form(this)
        val input = DialogFields.addText(
            form,
            hint = getString(R.string.vmware_snapshot_name_hint),
            initial = "Snapshot ${System.currentTimeMillis()}",
            monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Create Snapshot")
            .setMessage("Enter a name for the snapshot of ${vm.name}")
            .setView(form.root)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    showError("Enter a name for the snapshot")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val success = currentClient?.createSnapshot(vm.vm, name) ?: false
                        if (success) {
                            Toast.makeText(this@VMwareManagerActivity, "Snapshot created", Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            // Reopen to show the refreshed snapshot list
                            showSnapshotDialog(vm)
                        } else {
                            showError("Failed to create snapshot")
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Snapshot creation error: ${e.message}", e)
                        showError("Error: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: VMwareApiClient.VMwareVM, snapshot: VMwareApiClient.VMwareSnapshot, action: String, parentDialog: AlertDialog) {
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Revert to Snapshot")
                    .setMessage("Are you sure you want to revert ${vm.name} to snapshot '${snapshot.name}'?\n\nThis will restore the VM to its state when the snapshot was taken.")
                    .setPositiveButton("Revert") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentClient?.revertSnapshot(snapshot.snapshot) ?: false
                                if (success) {
                                    Toast.makeText(this@VMwareManagerActivity, "VM reverted to snapshot", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    showError("Failed to revert")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Revert error: ${e.message}", e)
                                showError("Error: ${e.message}")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            "delete" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Snapshot")
                    .setMessage("Are you sure you want to delete snapshot '${snapshot.name}'?\n\nThis action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentClient?.deleteSnapshot(snapshot.snapshot) ?: false
                                if (success) {
                                    Toast.makeText(this@VMwareManagerActivity, "Snapshot deleted", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    // Reopen to show the refreshed snapshot list
                                    showSnapshotDialog(vm)
                                } else {
                                    showError("Failed to delete snapshot")
                                }
                            } catch (e: Exception) {
                                Logger.e(TAG, "Delete error: ${e.message}", e)
                                showError("Error: ${e.message}")
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
        private val snapshots: List<VMwareApiClient.VMwareSnapshot>,
        private val onAction: (VMwareApiClient.VMwareSnapshot, String) -> Unit
    ) : RecyclerView.Adapter<SnapshotAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.snapshot_name)
            val time: TextView = view.findViewById(R.id.snapshot_time)
            val revertButton: Button = view.findViewById(R.id.revert_button)
            val deleteButton: Button = view.findViewById(R.id.delete_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_snapshot, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val snapshot = snapshots[position]

            holder.name.text = snapshot.name
            holder.time.text = "Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.createTime))}"

            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }

        override fun getItemCount() = snapshots.size
    }
}
