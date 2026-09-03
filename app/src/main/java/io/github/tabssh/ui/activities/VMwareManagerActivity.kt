package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import io.github.tabssh.utils.ThrowableMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Displays the list of VMs on a single VMware ESXi/vCenter host. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 *
 * "Open Console" creates or updates a [ConnectionProfile] for the VM's IP and
 * launches [TabTerminalActivity] — VMware's web-based VMRC is not supported
 * on Android; SSH to the guest is the practical alternative.
 */
class VMwareManagerActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "VMwareManager"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"

        /** Upper bound on a server-supplied name rendered in a row, dialog or log line. */
        private const val MAX_NAME_LENGTH = 128

        /** Upper bound on an error detail surfaced in a dialog or toast. */
        private const val MAX_DETAIL_LENGTH = 300

        /**
         * Strip the characters a hostile vCenter could put in a VM/snapshot name
         * to forge log lines or spoof dialog text — C0/C1 controls plus the bidi
         * override and isolate ranges — then bound the length.
         */
        internal fun safeName(raw: String?): String {
            val cleaned = raw.orEmpty().filterNot { ch ->
                val code = ch.code
                code < 0x20 || code in 0x7F..0x9F ||
                    code in 0x202A..0x202E || code in 0x2066..0x2069
            }.trim().take(MAX_NAME_LENGTH)
            return cleaned.ifBlank { "(unnamed)" }
        }

        /**
         * Make an exception or API-supplied detail safe to display: same control
         * character strip as [safeName], bounded so a multi-kilobyte vSphere
         * fault body cannot fill the screen.
         */
        internal fun safeDetail(detail: String?): String {
            val cleaned = detail.orEmpty().filterNot { ch ->
                val code = ch.code
                code < 0x20 || code in 0x7F..0x9F ||
                    code in 0x202A..0x202E || code in 0x2066..0x2069
            }.trim().take(MAX_DETAIL_LENGTH)
            return cleaned.ifBlank { "unknown error" }
        }

        /**
         * True when [address] is a plain IPv4/IPv6 literal or bare hostname.
         *
         * The address arrives from VMware Tools inside the guest, so it is
         * attacker-controlled on a compromised VM, and it is written straight
         * into a saved [ConnectionProfile.host]. Anything carrying a scheme,
         * userinfo, port, path or whitespace is rejected rather than stored.
         */
        internal fun isValidGuestAddress(address: String): Boolean {
            if (address.isBlank() || address.length > 255) return false
            if (address.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return false
            if (address.contains("://") || address.contains('/') || address.contains('@') ||
                address.contains('?') || address.contains('#') || address.contains('\\')
            ) {
                return false
            }
            // Unbracketed IPv6 literal — colons are legal here and nowhere else.
            if (address.contains(':')) {
                return address.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
            }
            return address.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        }
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

    /** Retained for [showError]'s tap-to-retry — re-runs the connect that failed. */
    private var currentHypervisorId: Long = -1L

    /**
     * Single-flight latch for the VM actions. A second tap while a power op or a
     * console open is still in flight used to fire the whole path again —
     * duplicate power commands, or two VNC tabs each owning their own socket.
     */
    private var actionInFlight = false

    /**
     * The snapshot dialog is the only dialog here that outlives an async load, so
     * it is the one that can still be showing when the activity is torn down —
     * leaking its window. Tracked so [onDestroy] can dismiss it.
     */
    private var snapshotDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vmware_manager)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.hypervisor_type_vmware)

        adapter = VmAdapter(vms)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refreshVMs() }

        val hypervisorId = intent.getLongExtra(EXTRA_HYPERVISOR_ID, -1L)
        currentHypervisorId = hypervisorId
        if (hypervisorId == -1L) {
            showError(getString(R.string.hypervisor_error_no_id))
            return
        }
        connectAndRefresh(hypervisorId)
    }

    override fun onDestroy() {
        // Cancel any in-flight HTTP calls so OkHttp does not retain Activity
        // references through callbacks past onDestroy.
        // Only the exception type is logged — an OkHttp failure message can carry
        // the request URL, which for vSphere includes the session identifier.
        try {
            currentClient?.cancelAll()
        } catch (e: Exception) {
            Logger.w(TAG, "cancelAll failed: ${e.javaClass.simpleName}")
        }
        snapshotDialog?.dismiss()
        snapshotDialog = null
        super.onDestroy()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectAndRefresh(hypervisorId: Long) {
        lifecycleScope.launch {
            showProgress(getString(R.string.status_connecting))
            val profile = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getById(hypervisorId)
            }
            if (profile == null) {
                showError(getString(R.string.hypervisor_error_not_found_fmt, hypervisorId))
                return@launch
            }
            // The awaited DB hop can land after the user has left the screen.
            if (isFinishing || isDestroyed) return@launch
            currentProfile = profile
            supportActionBar?.title = safeName(profile.name)

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
                    showError(getString(R.string.vmware_error_auth_failed))
                    return@launch
                }
                val capturedSha = client.getCapturedCertSha256()
                HypervisorPasswordStore.persistCapturedPinIfAny(
                    this@VMwareManagerActivity, profile, capturedSha
                )
                if (!capturedSha.isNullOrBlank()) currentProfile = profile.copy(pinnedCertSha256 = capturedSha)
                val serverType = if (client.isVCenter()) "vCenter" else "ESXi"
                Logger.i(TAG, "Connected to ${safeName(profile.name)} ($serverType)")
                app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                currentClient = client
                loadVMs(client)
            } catch (e: CancellationException) {
                // Activity scope cancelled — not a connection failure.
                throw e
            } catch (e: Exception) {
                // e.message can be a raw vSphere fault body, which echoes the
                // request — including the session cookie on some deployments;
                // ThrowableMapper keeps that out of the UI while still logging it.
                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Connect failed")
                showError(getString(R.string.vmware_connect_failed_fmt, safeName(profile.name), mapped.message))
            }
        }
    }

    private fun refreshVMs() {
        val client = currentClient ?: run {
            Toast.makeText(this, getString(R.string.hypervisor_not_connected_wait), Toast.LENGTH_SHORT).show()
            return
        }
        if (actionInFlight) return
        actionInFlight = true
        lifecycleScope.launch {
            try {
                loadVMs(client)
            } finally {
                actionInFlight = false
            }
        }
    }

    private suspend fun loadVMs(client: VMwareApiClient) {
        showProgress(getString(R.string.vmware_loading_vms))
        try {
            val vmList = client.getAllVMs()
            // The awaited API hop can land after the user has left the screen.
            if (isFinishing || isDestroyed) return
            adapter.replaceAllWithDiff(
                items = vms,
                newItems = vmList,
                areItemsTheSame = { a, b -> a.vm == b.vm }
            )
            hideProgress()
            if (vms.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.vmware_no_vms_found)
                resetStatusTextStyle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "loadVMs failed")
            showError(getString(R.string.vmware_error_load_vms_fmt, mapped.message))
        }
    }

    // ── VM actions ────────────────────────────────────────────────────────────

    private fun confirmStop(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vmware_stop_title_fmt, safeName(vm.name)))
            .setMessage(getString(R.string.vmware_stop_message))
            .setPositiveButton(getString(R.string.vmware_shutdown_guest_button)) { _, _ -> vmAction(vm, client, "shutdown") }
            .setNeutralButton(getString(R.string.vmware_power_off_button)) { _, _ -> vmAction(vm, client, "stop") }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmHardReset(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.libvirt_hard_reset_title_fmt, safeName(vm.name)))
            .setMessage(getString(R.string.libvirt_hard_reset_message))
            .setPositiveButton(getString(R.string.libvirt_reset_button)) { _, _ -> vmAction(vm, client, "reset") }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun vmAction(vm: VMwareApiClient.VMwareVM, client: VMwareApiClient, action: String) {
        // A second tap while the first request is still in flight used to send the
        // power command twice — a start/stop race on the hypervisor.
        if (actionInFlight) return
        actionInFlight = true
        val vmName = safeName(vm.name)
        lifecycleScope.launch {
            // Human-readable progress label per action verb
            val progressLabel = when (action) {
                "start"    -> getString(R.string.vmware_action_starting)
                "stop"     -> getString(R.string.vm_state_stopping)
                "shutdown" -> getString(R.string.vmware_action_shutting_down)
                "reboot"   -> getString(R.string.vm_state_restarting)
                "reset"    -> getString(R.string.vmware_action_resetting)
                else       -> action.replaceFirstChar { it.uppercase() }
            }
            showProgress("$progressLabel $vmName…")
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
                // The awaited API hop can land after the user has left the screen.
                if (isFinishing || isDestroyed) return@launch
                if (ok) {
                    Toast.makeText(this@VMwareManagerActivity, getString(R.string.vmware_action_sent_fmt, vmName, action), Toast.LENGTH_SHORT).show()
                    delay(2000)
                    loadVMs(client)
                } else {
                    hideProgress()
                    showError(getString(R.string.xcpng_action_failed_for_fmt, action, vmName))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "$action failed for $vmName")
                showError(getString(R.string.proxmox_power_action_error_fmt, action, mapped.message))
            } finally {
                actionInFlight = false
            }
        }
    }

    private fun openSshConsole(vm: VMwareApiClient.VMwareVM) {
        val ip = vm.ipAddress ?: run {
            Toast.makeText(this, getString(R.string.vmware_ip_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        // The address is reported by VMware Tools running inside the guest, so on
        // a compromised VM it is attacker-controlled — and it is written straight
        // into a saved connection's host field.
        if (!isValidGuestAddress(ip)) {
            Toast.makeText(this, getString(R.string.vmware_ip_unusable), Toast.LENGTH_LONG).show()
            return
        }
        if (actionInFlight) return
        actionInFlight = true
        val vmName = safeName(vm.name)
        lifecycleScope.launch {
            try {
                val connectionName = "VMware: $vmName"
                val vmHostsGroupId = withContext(Dispatchers.IO) {
                    SystemGroupHelper.getOrCreateSystemGroupId(
                        app.database, "vm_hosts", getString(R.string.vmware_group_name_vm_hosts), "vm"
                    )
                }
                // Deterministic per-VM profile id (server row id + managed
                // object id), so renaming the profile in Hosts never breaks
                // the mapping — getByName remains only as a one-time legacy
                // fallback for rows created before the deterministic id.
                val profileId = "vmware-vm:${currentHypervisorId}:${vm.vm}"
                val existing = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(profileId)
                        ?: app.database.connectionDao().getByName(connectionName)
                }
                // The legacy lookup key is derived from a hypervisor-supplied VM
                // name, so a VM renamed to collide with a user's own saved
                // profile would otherwise repoint that profile's host at the
                // VM's address. Only legacy profiles this screen itself created
                // (vm_hosts group) may be rewritten; id-matched rows are ours
                // by construction, wherever the user has moved them.
                if (existing != null && existing.id != profileId && existing.groupId != vmHostsGroupId) {
                    showError(getString(R.string.vmware_connection_name_exists_fmt, connectionName))
                    return@launch
                }
                val connection = if (existing == null) {
                    val created = ConnectionProfile(
                        id = profileId,
                        name = connectionName,
                        host = ip,
                        port = 22,
                        username = "root",
                        authType = io.github.tabssh.ssh.auth.AuthType.PASSWORD.name,
                        groupId = vmHostsGroupId,
                        createdAt = System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis()
                    )
                    // ConnectionProfile.id is a client-generated UUID, so the row id
                    // insertConnection returns carries no information we need.
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().insertConnection(created)
                    }
                    created
                } else {
                    val updated = existing.copy(host = ip, modifiedAt = System.currentTimeMillis())
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().updateConnection(updated)
                    }
                    updated
                }
                // The awaited DB hops can land after the user has left the screen.
                if (isFinishing || isDestroyed) return@launch
                val intent = TabTerminalActivity.createIntent(this@VMwareManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                Logger.i(TAG, "Launching SSH to $ip for $vmName")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Failed to open SSH terminal for $vmName")
                showError(getString(R.string.vmware_open_terminal_failed_fmt, mapped.message))
            } finally {
                actionInFlight = false
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
            Toast.makeText(this, getString(R.string.hypervisor_not_connected_wait), Toast.LENGTH_SHORT).show()
            return
        }
        // Without this latch a double tap opened two sockets to the ESXi host and
        // two VNC tabs, only one of which the user could ever close.
        if (actionInFlight) return
        actionInFlight = true
        val vmName = safeName(vm.name)
        lifecycleScope.launch {
            showProgress(getString(R.string.libvirt_opening_console_fmt, vmName))
            // Held outside the try so every failure path below — including a
            // cancellation between the connect and the tab taking ownership —
            // can still close the connected socket.
            var socket: java.net.Socket? = null
            try {
                val info = withContext(Dispatchers.IO) {
                    val consoleInfo = client.getVncConsoleInfo(vm.vm)
                    val s = java.net.Socket()
                    try {
                        s.soTimeout = 30_000
                        s.connect(java.net.InetSocketAddress(consoleInfo.host, consoleInfo.port), 15_000)
                    } catch (e: Throwable) {
                        // Close the fd on connect failure so we never leak the socket
                        try { s.close() } catch (_: Exception) {}
                        throw e
                    }
                    socket = s
                    consoleInfo
                }
                val live = socket ?: return@launch
                val rfbClient = io.github.tabssh.hypervisor.console.rfb.RfbClient(
                    inputStream = live.getInputStream(),
                    outputStream = live.getOutputStream(),
                    vncPassword = info.password,
                    rawSocket = live,
                    consoleMode = true
                )
                // Ephemeral hypervisor consoles never auto-relaunch on resize rejection
                rfbClient.canRequestResize = false
                if (isFinishing || isDestroyed) {
                    try { rfbClient.stop() } catch (_: Exception) {}
                    socket = null
                    return@launch
                }
                val tab = app.tabManager.createVncTab(vncHost = null, ephemeralDisplayName = vmName)
                hideProgress()
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.javaClass.simpleName}")
                    }
                    socket = null
                    Toast.makeText(this@VMwareManagerActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                // The tab owns the socket from here; clear the local handle so the
                // finally block does not close a console the user is now using.
                socket = null
                startActivity(
                    Intent(this@VMwareManagerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Failed to open VNC console for $vmName")
                showError(getString(R.string.vmware_open_vnc_failed_fmt, vmName, mapped.message))
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                actionInFlight = false
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(message: String) {
        runOnUiThread {
            // runOnUiThread posts; the post can run after the window is gone.
            if (isFinishing || isDestroyed) return@runOnUiThread
            progressBar.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = message
            resetStatusTextStyle()
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progressBar.visibility = View.GONE
            statusText.visibility = View.GONE
            resetStatusTextStyle()
        }
    }

    /**
     * Renders a load/connect failure distinct from the loading/empty text
     * above — error-colored copy plus a tap-to-retry affordance — instead of
     * reusing the same plain text with no visual or interactive difference.
     */
    private fun showError(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            progressBar.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.hypervisor_error_prefix_fmt, message)
            statusText.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.error)
            )
            statusText.setOnClickListener {
                if (currentHypervisorId != -1L) connectAndRefresh(currentHypervisorId)
            }
        }
    }

    /** Restores [statusText] to its neutral loading/empty appearance. */
    private fun resetStatusTextStyle() {
        statusText.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)
        )
        statusText.setOnClickListener(null)
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

            // name, powerState and ipAddress are all hypervisor/guest supplied; a
            // crafted value could otherwise inject bidi overrides or control
            // characters into the row and spoof which VM the buttons act on.
            holder.name.text = safeName(vm.name)
            holder.state.text = stateLabel(vm.powerState)
            holder.state.setTextColor(stateColor(vm.powerState))
            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(vm.powerState))
            holder.info.text = getString(R.string.vmware_cpu_ram_info_fmt, vm.cpuCount, vm.memoryMB)
            if (!vm.ipAddress.isNullOrBlank()) {
                holder.ip.text = getString(R.string.vmware_ip_label_fmt, safeName(vm.ipAddress))
                holder.ip.visibility = View.VISIBLE
            } else {
                holder.ip.visibility = View.GONE
            }

            holder.btnStart.text = getString(R.string.vmware_power_on_button)
            holder.btnStop.text = getString(R.string.container_action_stop)
            holder.btnReboot.text = getString(R.string.vmware_restart_guest_button)
            holder.btnReset.text = getString(R.string.xcpng_action_hard_reset)

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
            "POWERED_ON"  -> getString(R.string.vm_state_running)
            "POWERED_OFF" -> getString(R.string.vm_state_stopped)
            "SUSPENDED"   -> getString(R.string.vm_state_paused)
            else          -> safeName(state).split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }

    // ── Snapshot management dialog ────────────────────────────────────────────

    /**
     * Show snapshot management dialog for a VM
     */
    private fun showSnapshotDialog(vm: VMwareApiClient.VMwareVM) {
        val client = currentClient ?: return
        // Re-entered from the create/delete coroutines, which can resume after
        // the activity is gone — showing a dialog then throws BadTokenException.
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val dialogProgressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val snapshotRecyclerView = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)

        snapshotRecyclerView.layoutManager = LinearLayoutManager(this)
        vmNameText.text = getString(R.string.hypervisor_vm_label_fmt, safeName(vm.name))

        // Replace any dialog left over from a previous open so only one is tracked.
        snapshotDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        snapshotDialog = dialog

        // Load snapshots
        lifecycleScope.launch {
            try {
                dialogProgressBar.visibility = View.VISIBLE
                val snapshots = client.listSnapshots(vm.vm)
                // The awaited API hop can land after the dialog's activity is gone.
                if (isFinishing || isDestroyed) return@launch
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load snapshots", e)
                if (isFinishing || isDestroyed) return@launch
                dialogProgressBar.visibility = View.GONE
                emptyStateText.text = getString(R.string.hypervisor_error_loading_snapshots)
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
            hint = getString(R.string.xcpng_snapshot_name_hint),
            initial = getString(R.string.vmware_default_snapshot_name_fmt, System.currentTimeMillis()),
            monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hypervisor_create_snapshot_title))
            .setMessage(getString(R.string.hypervisor_snapshot_name_prompt_fmt, safeName(vm.name)))
            .setView(form.root)
            .setPositiveButton(getString(R.string.container_create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    showError(getString(R.string.vmware_snapshot_name_empty))
                    return@setPositiveButton
                }
                // The name is sent to vCenter and echoed back into the snapshot
                // list; control characters there would forge the dialog's rows.
                if (name != safeName(name)) {
                    showError(getString(R.string.vmware_snapshot_name_control_chars))
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val success = currentClient?.createSnapshot(vm.vm, name) ?: false
                        if (isFinishing || isDestroyed) return@launch
                        if (success) {
                            Toast.makeText(this@VMwareManagerActivity, getString(R.string.hypervisor_snapshot_created), Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            // Reopen to show the refreshed snapshot list
                            showSnapshotDialog(vm)
                        } else {
                            showError(getString(R.string.xcpng_error_create_snapshot))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Snapshot creation error")
                        showError(getString(R.string.hypervisor_error_prefix_fmt, mapped.message))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: VMwareApiClient.VMwareVM, snapshot: VMwareApiClient.VMwareSnapshot, action: String, parentDialog: AlertDialog) {
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_revert_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_revert_snapshot_message_fmt, safeName(vm.name), safeName(snapshot.name)))
                    .setPositiveButton(getString(R.string.hypervisor_revert_button)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentClient?.revertSnapshot(snapshot.snapshot) ?: false
                                if (isFinishing || isDestroyed) return@launch
                                if (success) {
                                    Toast.makeText(this@VMwareManagerActivity, getString(R.string.hypervisor_vm_reverted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    showError(getString(R.string.xcpng_error_revert))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Revert error")
                                showError(getString(R.string.hypervisor_error_prefix_fmt, mapped.message))
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            "delete" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_delete_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_delete_snapshot_message_fmt, safeName(snapshot.name)))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentClient?.deleteSnapshot(snapshot.snapshot) ?: false
                                if (isFinishing || isDestroyed) return@launch
                                if (success) {
                                    Toast.makeText(this@VMwareManagerActivity, getString(R.string.hypervisor_snapshot_deleted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    // Reopen to show the refreshed snapshot list
                                    showSnapshotDialog(vm)
                                } else {
                                    showError(getString(R.string.xcpng_error_delete_snapshot))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val mapped = ThrowableMapper.map(this@VMwareManagerActivity, TAG, e, "Delete error")
                                showError(getString(R.string.hypervisor_error_prefix_fmt, mapped.message))
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
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

            // snapshot.name is supplied by vCenter.
            holder.name.text = safeName(snapshot.name)
            holder.time.text = getString(R.string.vmware_snapshot_created_time_fmt, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.createTime)))

            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }

        override fun getItemCount() = snapshots.size
    }
}
