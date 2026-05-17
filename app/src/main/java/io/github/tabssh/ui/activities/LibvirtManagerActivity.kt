package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
 * user open a VNC console for running domains.
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
        if (vm.state != "running") {
            Toast.makeText(this, "VM is not running", Toast.LENGTH_SHORT).show()
            return
        }
        val client = apiClient ?: run {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
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
                showVncError(e.message ?: "VNC error")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open VNC channel for ${vm.name}", e)
                hideProgress()
                showError("Could not open VNC console: ${e.message}")
            }
        }
    }

    private fun showVncError(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("VNC Not Available")
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
            holder.vmState.text = vm.state
            holder.vmId.text = if (vm.id >= 0) "ID: ${vm.id}" else "ID: —"
            holder.vmState.setTextColor(
                if (vm.state == "running") getColor(android.R.color.holo_green_dark)
                else getColor(android.R.color.darker_gray)
            )
            holder.itemView.setOnClickListener { onClick(vm) }
        }

        override fun getItemCount(): Int = items.size
    }
}
