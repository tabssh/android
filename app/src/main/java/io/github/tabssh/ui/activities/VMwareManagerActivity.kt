package io.github.tabssh.ui.activities

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
import io.github.tabssh.hypervisor.vmware.VMwareApiClient
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
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
            vms.clear()
            vms.addAll(vmList)
            adapter.notifyDataSetChanged()
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
            showProgress("${action.replaceFirstChar { it.uppercase() }}ing ${vm.name}…")
            try {
                val ok = withContext(Dispatchers.IO) {
                    when (action) {
                        "start"  -> client.startVM(vm.vm)
                        "stop"   -> client.stopVM(vm.vm)
                        "reboot" -> client.resetVM(vm.vm)
                        "reset"  -> client.resetVM(vm.vm)
                        else     -> false
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
                Logger.e(TAG, "Failed to open SSH console for ${vm.name}", e)
                showError("Failed to open console: ${e.message}", "Error")
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
            holder.btnStop.text = "Power Off"

            // Button visibility by power state
            when (vm.powerState.uppercase()) {
                "POWERED_ON" -> {
                    holder.btnConsole.visibility = View.GONE
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

            holder.btnSsh.setOnClickListener { openSshConsole(vm) }
            holder.btnStart.setOnClickListener { vmAction(vm, client, "start") }
            holder.btnStop.setOnClickListener { vmAction(vm, client, "stop") }
            holder.btnReboot.setOnClickListener { vmAction(vm, client, "reboot") }
            holder.btnReset.setOnClickListener { confirmHardReset(vm, client) }
        }

        private fun stateColor(state: String): Int = when (state.uppercase()) {
            "POWERED_ON"  -> 0xFF4CAF50.toInt()
            "POWERED_OFF" -> 0xFFF44336.toInt()
            "SUSPENDED"   -> 0xFFFF9800.toInt()
            else          -> 0xFF9E9E9E.toInt()
        }

        private fun stateLabel(state: String): String = when (state.uppercase()) {
            "POWERED_ON"  -> "Running"
            "POWERED_OFF" -> "Stopped"
            "SUSPENDED"   -> "Paused"
            else          -> state.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }
}
