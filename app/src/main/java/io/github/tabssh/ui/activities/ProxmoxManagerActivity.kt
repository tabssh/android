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
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
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
            vms.clear()
            vms.addAll(vmsWithIPs)
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
                        "stop"   -> client.shutdownVM(vm.node, vm.vmid, vm.type)
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

    private fun openConsole(vm: ProxmoxApiClient.ProxmoxVM) {
        val profile = currentProfile ?: return
        lifecycleScope.launch {
            val creds = HypervisorPasswordStore.resolveCredentials(this@ProxmoxManagerActivity, profile)
            val intent = Intent(this@ProxmoxManagerActivity, VMConsoleActivity::class.java).apply {
                putExtra(VMConsoleActivity.EXTRA_HYPERVISOR_TYPE, VMConsoleActivity.TYPE_PROXMOX)
                putExtra(VMConsoleActivity.EXTRA_VM_ID, vm.vmid.toString())
                putExtra(VMConsoleActivity.EXTRA_VM_NAME, vm.name)
                putExtra(VMConsoleActivity.EXTRA_VM_NODE, vm.node)
                putExtra(VMConsoleActivity.EXTRA_VM_TYPE, vm.type)
                putExtra(VMConsoleActivity.EXTRA_HOST, profile.host)
                putExtra(VMConsoleActivity.EXTRA_PORT, profile.port)
                putExtra(VMConsoleActivity.EXTRA_USERNAME, creds.username)
                putExtra(VMConsoleActivity.EXTRA_PASSWORD, creds.password)
                putExtra(VMConsoleActivity.EXTRA_REALM, creds.realm ?: "pam")
                putExtra(VMConsoleActivity.EXTRA_VERIFY_SSL, profile.verifySsl)
                putExtra(VMConsoleActivity.EXTRA_PINNED_CERT_SHA256, profile.pinnedCertSha256)
            }
            startActivity(intent)
            Logger.i(TAG, "Launching console for ${vm.name} (vmid=${vm.vmid})")
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
}
