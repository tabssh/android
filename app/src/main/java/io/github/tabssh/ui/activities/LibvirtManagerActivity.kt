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
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.hypervisor.libvirt.LibvirtApiClient
import io.github.tabssh.hypervisor.libvirt.LibvirtException
import io.github.tabssh.hypervisor.libvirt.LibvirtVm
import io.github.tabssh.hypervisor.vnc.VncStreamHolder
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of libvirt domains on a QEMU/KVM hypervisor and lets the
 * user open a VNC console for running domains or perform power actions
 * (start / shutdown / reboot / hard-reset).
 *
 * When a VM has no VNC display configured, the activity offers SSH to the
 * guest as a fallback — useful for headless or console-only VMs.
 *
 * Receives [EXTRA_HYPERVISOR_ID] (Long) in its launch intent.
 */
class LibvirtManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LibvirtManagerActivity"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private var apiClient: LibvirtApiClient? = null
    private var hypervisorProfile: HypervisorProfile? = null
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
        supportActionBar?.title = "QEMU/libvirt"

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
            hypervisorProfile = profile
            supportActionBar?.title = profile.name

            val client = LibvirtApiClient(this@LibvirtManagerActivity, profile)
            try {
                withContext(Dispatchers.IO) { client.connect() }
                apiClient = client
                loadDomains(client)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect to libvirt host", e)
                val msg = e.message ?: "Unknown error"
                // "No credentials found" → Keystore entry is gone; offer shortcut to settings.
                if (msg.startsWith("No credentials found")) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        AlertDialog.Builder(this@LibvirtManagerActivity)
                            .setTitle("Credentials Missing")
                            .setMessage("$msg\n\nOpen Settings to re-enter the password?")
                            .setPositiveButton("Open Settings") { _, _ ->
                                startActivity(
                                    Intent(this@LibvirtManagerActivity, HypervisorEditActivity::class.java)
                                        .putExtra("hypervisor_id", hypervisorId)
                                )
                            }
                            .setNegativeButton("Cancel") { _, _ -> finish() }
                            .show()
                    }
                } else {
                    showError("SSH connection failed: $msg")
                }
            }
        }
    }

    private fun refresh() {
        val client = apiClient ?: run {
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
            vm.state == "running"                       -> showRunningActions(vm, client)
            vm.state == "shut off" || vm.state == "paused" -> showStoppedActions(vm, client)
            else                                        -> showRunningActions(vm, client)
        }
    }

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

    private fun showStoppedActions(vm: LibvirtVm, client: LibvirtApiClient) {
        AlertDialog.Builder(this)
            .setTitle(vm.name)
            .setItems(arrayOf("▶  Start")) { _, _ -> powerAction(vm, client, "start") }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                Toast.makeText(this@LibvirtManagerActivity, "${vm.name}: $action sent", Toast.LENGTH_SHORT).show()
                loadDomains(client)
            } catch (e: LibvirtException) {
                hideProgress()
                showDomainError("$action failed", e.message ?: "virsh error")
            } catch (e: Exception) {
                hideProgress()
                Logger.e(TAG, "$action failed for ${vm.name}", e)
                showError("$action failed: ${e.message}")
            }
        }
    }

    /**
     * Open a VNC console for [vm]. If the domain has no VNC display configured
     * (LibvirtException "VNC not configured"), auto-detect the VM's IP via virsh
     * and offer SSH as a fallback.
     */
    private fun openConsole(vm: LibvirtVm, client: LibvirtApiClient) {
        lifecycleScope.launch {
            showProgress("Opening console for ${vm.name}…")
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
                Logger.w(TAG, "VNC unavailable for ${vm.name}: ${e.message}")
                hideProgress()
                if (e.message?.contains("VNC not configured") == true) {
                    // VNC not configured on this VM — try SSH instead.
                    offerSshFallback(vm, client)
                } else {
                    showDomainError("Console Error", e.message ?: "VNC error")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open VNC channel for ${vm.name}", e)
                hideProgress()
                showError("Could not open console: ${e.message}")
            }
        }
    }

    /**
     * Called when VNC is not available. Attempts to auto-detect the VM's IP via
     * `virsh domifaddr`, then presents a "Connect via SSH?" dialog pre-filled
     * with that IP. The SSH profile is persisted so repeated taps don't re-ask.
     */
    private fun offerSshFallback(vm: LibvirtVm, client: LibvirtApiClient) {
        lifecycleScope.launch {
            val detectedIp = withContext(Dispatchers.IO) {
                try { client.getVmIpAddress(vm.name) } catch (e: Exception) { null }
            }

            val messageLines = mutableListOf<String>()
            messageLines += "This VM has no VNC display configured."
            if (detectedIp != null) {
                messageLines += "Detected IP: $detectedIp"
            }
            messageLines += ""
            messageLines += "Connect via SSH? The connection will tunnel through"
            messageLines += "${hypervisorProfile?.host ?: "the hypervisor"} as a jump host."

            AlertDialog.Builder(this@LibvirtManagerActivity)
                .setTitle("No Console Available")
                .setMessage(messageLines.joinToString("\n"))
                .setPositiveButton("SSH Connect") { _, _ ->
                    launchSshToVm(vm, detectedIp)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Creates or updates a [ConnectionProfile] for the VM and launches
     * [TabTerminalActivity].
     *
     * VMs discovered via libvirt live on the hypervisor's private bridge network
     * (typically 192.168.122.x) and are not directly reachable from Android.
     * The hypervisor is wired as an SSH ProxyJump (jump host) so the connection
     * tunnels Android → hypervisor → VM.
     *
     * Jump-host auth:
     *  - Key-based hypervisor: `proxyAuthType = PUBLIC_KEY`, `proxyKeyId` set
     *  - Password-based hypervisor: hypervisor password cached in [SecurePasswordManager]
     *    for [ConnectionProfile.id] (SESSION_ONLY) so [SSHConnection.setupJumpHost]
     *    retrieves it via [SSHConnection.getPasswordForAuthentication].
     */
    private fun launchSshToVm(vm: LibvirtVm, ip: String?) {
        val hvProfile = hypervisorProfile ?: run {
            showError("Hypervisor profile not loaded")
            return
        }
        lifecycleScope.launch {
            try {
                val connectionName = "libvirt: ${vm.name}"
                var existing = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getByName(connectionName)
                }
                val host = ip ?: existing?.host ?: ""

                // Jump-host auth mirrors the hypervisor profile's own auth method.
                val proxyAuthType = if (hvProfile.sshIdentityId != null)
                    AuthType.PUBLIC_KEY.name else AuthType.PASSWORD.name
                val proxyKeyId = hvProfile.sshIdentityId

                if (existing == null) {
                    existing = ConnectionProfile(
                        name = connectionName,
                        host = host,
                        port = 22,
                        username = "root",
                        authType = AuthType.PASSWORD.name,
                        // Route through the hypervisor as an SSH jump host so the VM's
                        // internal bridge IP is reachable from Android.
                        proxyType = "SSH",
                        proxyHost = hvProfile.host,
                        proxyPort = hvProfile.port,
                        proxyUsername = hvProfile.username,
                        proxyAuthType = proxyAuthType,
                        proxyKeyId = proxyKeyId,
                        createdAt = System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().insertConnection(existing)
                    }
                } else {
                    // Always refresh proxy fields in case the hypervisor was reconfigured.
                    val updated = existing.copy(
                        host = ip ?: existing.host,
                        proxyType = "SSH",
                        proxyHost = hvProfile.host,
                        proxyPort = hvProfile.port,
                        proxyUsername = hvProfile.username,
                        proxyAuthType = proxyAuthType,
                        proxyKeyId = proxyKeyId,
                        modifiedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().updateConnection(updated)
                    }
                    existing = updated
                }

                // For password-based hypervisors, cache the hypervisor password in
                // SecurePasswordManager keyed by the VM connection's UUID so that
                // SSHConnection.setupJumpHost() can retrieve it. SESSION_ONLY: never
                // persisted — cleared when the app process exits.
                if (proxyKeyId == null) {
                    val hvPassword = withContext(Dispatchers.IO) {
                        HypervisorPasswordStore.retrieve(this@LibvirtManagerActivity, hvProfile)
                    }
                    if (hvPassword.isNotBlank()) {
                        app.securePasswordManager.storePassword(
                            existing.id,
                            hvPassword,
                            SecurePasswordManager.StorageLevel.SESSION_ONLY
                        )
                    }
                }

                val intent = TabTerminalActivity.createIntent(
                    this@LibvirtManagerActivity, existing, autoConnect = host.isNotBlank()
                )
                startActivity(intent)
                Logger.i(TAG, "Launched SSH fallback for ${vm.name} → $host via jump:${hvProfile.host}:${hvProfile.port}")
            } catch (e: Exception) {
                Logger.e(TAG, "SSH fallback launch failed for ${vm.name}", e)
                showError("Failed to open SSH: ${e.message}")
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

    private fun showDomainError(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class VmAdapter(
        private val items: List<LibvirtVm>,
        private val onClick: (LibvirtVm) -> Unit
    ) : RecyclerView.Adapter<VmAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val state: TextView = view.findViewById(R.id.vm_state)
            val info: TextView = view.findViewById(R.id.vm_info)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_hypervisor_vm, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vm = items[position]
            holder.name.text = vm.name
            holder.state.text = stateLabel(vm.state)
            holder.state.setTextColor(stateColor(vm.state))
            holder.info.text = if (vm.id >= 0) "ID: ${vm.id}" else "ID: —"
            holder.itemView.setOnClickListener { onClick(vm) }
        }

        private fun stateLabel(state: String): String = when (state) {
            "running"  -> "● running"
            "shut off" -> "○ shut off"
            "paused"   -> "⏸ paused"
            else       -> state
        }

        private fun stateColor(state: String): Int = when (state) {
            "running"  -> 0xFF4CAF50.toInt()
            "paused"   -> 0xFFFF9800.toInt()
            else       -> 0xFF9E9E9E.toInt()
        }
    }
}
