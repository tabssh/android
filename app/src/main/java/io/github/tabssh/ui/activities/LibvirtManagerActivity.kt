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
import io.github.tabssh.hypervisor.libvirt.LibvirtApiClient
import io.github.tabssh.hypervisor.libvirt.LibvirtException
import io.github.tabssh.hypervisor.libvirt.LibvirtVm
import io.github.tabssh.hypervisor.vnc.VncStreamHolder
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of libvirt domains on a QEMU/KVM hypervisor and lets the
 * user open a VNC console for running domains or perform power actions
 * (start / shutdown / reboot / hard-reset).
 *
 * Receives [EXTRA_HYPERVISOR_ID] (Long) in its launch intent.
 */
class LibvirtManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LibvirtManagerActivity"

        /** Long — ID of the HypervisorProfile to connect to. */
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private var apiClient: LibvirtApiClient? = null
    private val vms = mutableListOf<LibvirtVm>()
    private lateinit var adapter: VmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_libvirt_manager)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "QEMU/libvirt Domains"

        adapter = VmAdapter(vms) { vm -> onVmClicked(vm) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refresh() }

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
        super.onDestroy()
        apiClient?.disconnect()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectAndRefresh(hypervisorId: Long) {
        lifecycleScope.launch {
            showProgress("Connecting to hypervisor…")
            val profile = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getById(hypervisorId)
            }
            if (profile == null) {
                showError("Hypervisor profile not found (id=$hypervisorId)")
                return@launch
            }
            val client = LibvirtApiClient(this@LibvirtManagerActivity, profile)
            try {
                withContext(Dispatchers.IO) { client.connect() }
                apiClient = client
                loadDomains(client)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect to libvirt host", e)
                showError("SSH connection failed: ${e.message}")
            }
        }
    }

    private fun refresh() {
        val client = apiClient
        if (client == null) {
            Toast.makeText(this, "Not connected — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { loadDomains(client) }
    }

    private suspend fun loadDomains(client: LibvirtApiClient) {
        showProgress("Loading domains…")
        try {
            val domains = withContext(Dispatchers.IO) { client.listDomains() }
            vms.clear()
            vms.addAll(domains)
            adapter.notifyDataSetChanged()
            hideProgress()
            if (domains.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = "No domains found"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to list domains", e)
            showError("Could not list domains: ${e.message}")
        }
    }

    // ── VM interaction ────────────────────────────────────────────────────────

    private fun onVmClicked(vm: LibvirtVm) {
        val client = apiClient ?: run {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            vm.state == "running" -> showRunningActions(vm, client)
            vm.state == "shut off" || vm.state == "paused" -> showStoppedActions(vm, client)
            else -> showRunningActions(vm, client)
        }
    }

    /** Action dialog for a running domain. */
    private fun showRunningActions(vm: LibvirtVm, client: LibvirtApiClient) {
        val actions = arrayOf("🖥  Open Console", "🔄  Reboot", "⏹  Shutdown", "⚡  Hard Reset")
        AlertDialog.Builder(this)
            .setTitle(vm.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openConsole(vm, client)
                    1 -> powerAction(vm, client, "reboot")
                    2 -> powerAction(vm, client, "shutdown")
                    3 -> confirmHardReset(vm, client)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Action dialog for a shut-off / paused domain. */
    private fun showStoppedActions(vm: LibvirtVm, client: LibvirtApiClient) {
        val actions = arrayOf("▶  Start")
        AlertDialog.Builder(this)
            .setTitle(vm.name)
            .setItems(actions) { _, which ->
                if (which == 0) powerAction(vm, client, "start")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Extra confirmation before hard reset (destructive). */
    private fun confirmHardReset(vm: LibvirtVm, client: LibvirtApiClient) {
        AlertDialog.Builder(this)
            .setTitle("Hard Reset ${vm.name}?")
            .setMessage("This is equivalent to pulling the power cord. Any unsaved data will be lost.")
            .setPositiveButton("Reset") { _, _ -> powerAction(vm, client, "reset") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun powerAction(vm: LibvirtVm, client: LibvirtApiClient, action: String) {
        lifecycleScope.launch {
            showProgress("${action.replaceFirstChar { it.uppercase() }}ing ${vm.name}…")
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "start"    -> client.startDomain(vm.name)
                        "shutdown" -> client.shutdownDomain(vm.name)
                        "reboot"   -> client.rebootDomain(vm.name)
                        "reset"    -> client.resetDomain(vm.name)
                    }
                }
                Toast.makeText(
                    this@LibvirtManagerActivity,
                    "${vm.name}: $action sent",
                    Toast.LENGTH_SHORT
                ).show()
                loadDomains(client)
            } catch (e: LibvirtException) {
                hideProgress()
                Logger.e(TAG, "$action failed for ${vm.name}", e)
                showDomainError("$action failed", e.message ?: "virsh error")
            } catch (e: Exception) {
                hideProgress()
                Logger.e(TAG, "$action failed for ${vm.name}", e)
                showError("$action failed: ${e.message}")
            }
        }
    }

    private fun openConsole(vm: LibvirtVm, client: LibvirtApiClient) {
        lifecycleScope.launch {
            showProgress("Opening VNC console for ${vm.name}…")
            try {
                val (ins, out) = withContext(Dispatchers.IO) { client.openVncChannel(vm.name) }
                VncStreamHolder.set(ins, out, socket = null)
                val intent = Intent(this@LibvirtManagerActivity, VMConsoleActivity::class.java).apply {
                    putExtra(VMConsoleActivity.EXTRA_DIRECT_VNC, true)
                    putExtra(VMConsoleActivity.EXTRA_VM_NAME, vm.name)
                }
                hideProgress()
                startActivity(intent)
            } catch (e: LibvirtException) {
                Logger.e(TAG, "VNC channel error for ${vm.name}", e)
                hideProgress()
                showDomainError("VNC Not Available", e.message ?: "VNC error")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open VNC channel for ${vm.name}", e)
                hideProgress()
                showError("Could not open VNC console: ${e.message}")
            }
        }
    }

    private fun showDomainError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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
        private val items: List<LibvirtVm>,
        private val onClick: (LibvirtVm) -> Unit
    ) : RecyclerView.Adapter<VmAdapter.VmViewHolder>() {

        inner class VmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val vmName: TextView = itemView.findViewById(R.id.vm_name)
            val vmState: TextView = itemView.findViewById(R.id.vm_state)
            val vmId: TextView = itemView.findViewById(R.id.vm_id)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VmViewHolder {
            val view = layoutInflater.inflate(R.layout.item_libvirt_vm, parent, false)
            return VmViewHolder(view)
        }

        override fun onBindViewHolder(holder: VmViewHolder, position: Int) {
            val vm = items[position]
            holder.vmName.text = vm.name
            holder.vmState.text = stateLabel(vm.state)
            holder.vmId.text = if (vm.id >= 0) "ID: ${vm.id}" else "ID: —"
            holder.vmState.setTextColor(stateColor(vm.state))
            holder.itemView.setOnClickListener { onClick(vm) }
        }

        override fun getItemCount(): Int = items.size

        private fun stateLabel(state: String): String = when (state) {
            "running"  -> "● running"
            "shut off" -> "○ shut off"
            "paused"   -> "⏸ paused"
            else       -> state
        }

        private fun stateColor(state: String): Int = when (state) {
            "running"  -> 0xFF4CAF50.toInt() // green
            "paused"   -> 0xFFFF9800.toInt() // orange
            else       -> 0xFF9E9E9E.toInt() // grey
        }
    }
}
