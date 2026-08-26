package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import io.github.tabssh.hypervisor.console.ConsoleEventListener
import io.github.tabssh.hypervisor.console.HypervisorConsoleManager
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.ui.tabs.ConsoleConnectParams
import io.github.tabssh.ui.tabs.HypervisorConsoleType
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Displays the list of VMs on a single Proxmox hypervisor. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 */
class ProxmoxManagerActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "ProxmoxManager"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"

        /** Upper bound on a server-supplied name rendered in a row, dialog or log line. */
        private const val MAX_NAME_LENGTH = 128

        /** Upper bound on an error detail surfaced in a dialog or toast. */
        private const val MAX_DETAIL_LENGTH = 300

        /**
         * Strip the characters a hostile Proxmox node could put in a VM/node name
         * to forge log lines or spoof dialog text — C0/C1 controls plus the
         * bidi override and isolate ranges — then bound the length.
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
         * character strip as [safeName], bounded so a multi-kilobyte error body
         * cannot fill the screen.
         */
        internal fun safeDetail(detail: String?): String {
            val cleaned = detail.orEmpty().filterNot { ch ->
                val code = ch.code
                code < 0x20 || code in 0x7F..0x9F ||
                    code in 0x202A..0x202E || code in 0x2066..0x2069
            }.trim().take(MAX_DETAIL_LENGTH)
            return cleaned.ifBlank { "unknown error" }
        }
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

    /** Retained for [showError]'s tap-to-retry — re-runs the connect that failed. */
    private var currentHypervisorId: Long = -1L

    /**
     * Single-flight latch for the VM actions. A second tap while a power op or a
     * console connect is still in flight used to fire the whole path again —
     * duplicate power commands, or two console tabs each owning their own
     * [HypervisorConsoleManager] and socket.
     */
    private var actionInFlight = false

    /**
     * The snapshot dialog is the only dialog here that outlives an async load, so
     * it is the one that can still be showing when the activity is torn down —
     * leaking its window. Tracked so [onDestroy] can dismiss it.
     */
    private var snapshotDialog: AlertDialog? = null

    // Console managers whose ConsoleEventListener is an anonymous object
    // holding this Activity (runOnUiThread, progress dialog). The tab — and
    // therefore the manager — outlives this screen, so onDestroy() must
    // detach the listener or the destroyed Activity is retained for the
    // life of the console connection.
    private val spawnedConsoleManagers = mutableListOf<HypervisorConsoleManager>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxmox_manager)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.hypervisor_type_proxmox)

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
        // the request URL, which for Proxmox includes the auth ticket.
        try {
            currentClient?.cancelAll()
        } catch (e: Exception) {
            Logger.w(TAG, "cancelAll failed: ${e.javaClass.simpleName}")
        }
        snapshotDialog?.dismiss()
        snapshotDialog = null
        // Listeners are anonymous objects holding this Activity; the console
        // connections (owned by their tabs) outlive this screen.
        spawnedConsoleManagers.forEach { it.detachListener() }
        spawnedConsoleManagers.clear()
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
                    showError(getString(R.string.proxmox_connect_auth_failed))
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
            } catch (e: CancellationException) {
                // Activity scope cancelled — not a connection failure.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Connect failed", e)
                // e.message can be a raw server error body; Proxmox echoes the
                // request path (which carries the auth ticket) on some errors.
                showError(getString(R.string.proxmox_connect_failed_fmt, safeName(profile.name), safeDetail(e.message)))
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

    private suspend fun loadVMs(client: ProxmoxApiClient) {
        showProgress(getString(R.string.vmware_loading_vms))
        try {
            val vmList = client.getAllVMs()
            // Fetch IPs for running VMs concurrently — guest-agent queries can
            // take up to ~4 s each; serial would stall the list for N×4 s.
            val vmsWithIPs = coroutineScope {
                vmList.map { vm ->
                    async {
                        if (vm.status == "running") {
                            val ip = try {
                                client.getVMIPAddress(vm.node, vm.vmid, vm.type)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.d(TAG, "No IP for ${vm.vmid}: ${e.javaClass.simpleName}")
                                null
                            }
                            vm.copy(ipAddress = ip)
                        } else vm
                    }
                }.awaitAll()
            }
            // The awaited API hop can land after the user has left the screen.
            if (isFinishing || isDestroyed) return
            adapter.replaceAllWithDiff(
                items = vms,
                newItems = vmsWithIPs,
                areItemsTheSame = { a, b -> a.vmid == b.vmid && a.node == b.node }
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
            Logger.e(TAG, "loadVMs failed", e)
            showError(getString(R.string.proxmox_could_not_load_vms_fmt, safeDetail(e.message)))
        }
    }

    // ── VM actions ────────────────────────────────────────────────────────────

    /**
     * Proxmox `stop` is a hard power-off (qm stop), not an ACPI shutdown — it is
     * as destructive as `reset` and was the only power action reaching the API
     * without a confirmation step.
     */
    private fun confirmStop(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.proxmox_stop_vm_title_fmt, safeName(vm.name)))
            .setMessage(getString(R.string.proxmox_stop_vm_message))
            .setPositiveButton(getString(R.string.container_action_stop)) { _, _ -> powerAction(vm, client, "stop") }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmHardReset(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.libvirt_hard_reset_title_fmt, safeName(vm.name)))
            .setMessage(getString(R.string.libvirt_hard_reset_message))
            .setPositiveButton(getString(R.string.libvirt_reset_button)) { _, _ -> powerAction(vm, client, "reset") }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun powerAction(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient, action: String) {
        // A second tap while the first request is still in flight used to send the
        // power command twice — a start/stop race on the hypervisor.
        if (actionInFlight) return
        actionInFlight = true
        val vmName = safeName(vm.name)
        lifecycleScope.launch {
            showProgress(getString(R.string.proxmox_power_action_progress_fmt, action.replaceFirstChar { it.uppercase() }, vmName))
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
                // The awaited API hop can land after the user has left the screen.
                if (isFinishing || isDestroyed) return@launch
                if (ok) {
                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.vmware_action_sent_fmt, vmName, action), Toast.LENGTH_SHORT).show()
                    delay(2000)
                    loadVMs(client)
                } else {
                    hideProgress()
                    showError(getString(R.string.xcpng_action_failed_for_fmt, action, vmName))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "$action failed for $vmName", e)
                showError(getString(R.string.proxmox_power_action_error_fmt, action, safeDetail(e.message)))
            } finally {
                actionInFlight = false
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
        // Without this latch a double tap ran the connect twice and left two
        // console tabs, each holding its own manager, socket and terminal bridge.
        if (actionInFlight) return
        actionInFlight = true
        val vmName = safeName(vm.name)
        lifecycleScope.launch {
            showProgress(getString(R.string.xcpng_connecting_to_vm_fmt, vmName))
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
                    // message originates from the hypervisor — sanitize before it
                    // reaches the log, or a crafted VM name forges log lines.
                    Logger.w(TAG, "Console error for $vmName: ${safeDetail(message)}")
                }
                override fun onStrategyAttempt(strategyName: String) {
                    // One spinner; its text updates as the chain falls through (PLAN 14)
                    runOnUiThread {
                        showProgress(getString(R.string.xcpng_connecting_strategy_fmt, vmName, HypervisorConsoleManager.strategyLabel(strategyName)))
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
                // The connect can complete after the user has left the screen;
                // dropping it here without disconnect() would leak the socket.
                if (isFinishing || isDestroyed) {
                    manager.disconnect()
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
                        // Becomes the tab title — sanitized so a crafted VM name
                        // cannot spoof or reorder the tab strip.
                        vmName = vmName,
                        vmNode = vm.node,
                        vmType = vm.type,
                        realm = creds.realm ?: "pam"
                    )
                )
                if (tab == null) {
                    manager.disconnect()
                    hideProgress()
                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                consoleTab = tab
                tab.consoleManager = manager
                spawnedConsoleManagers.add(manager)

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
                Logger.i(TAG, "Opened console tab for $vmName (vmid=${vm.vmid})")
            } catch (e: CancellationException) {
                // Cancellation is not a connection failure — swallowing it here
                // left the progress spinner and an error toast behind when the
                // activity scope was cancelled mid-connect.
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Console connection error for $vmName", e)
                hideProgress()
                showError(getString(R.string.xcpng_console_connect_failed_fmt, vmName, safeDetail(e.message)))
            } finally {
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

            // name, node and ipAddress are all hypervisor-supplied; a crafted
            // value could otherwise inject bidi overrides or control characters
            // into the row and spoof which VM the buttons act on.
            holder.name.text = getString(R.string.proxmox_vm_name_id_fmt, safeName(vm.name), vm.vmid)
            holder.state.text = stateLabel(vm.status)
            holder.state.setTextColor(stateColor(vm.status))
            holder.info.text = getString(
                R.string.proxmox_vm_info_fmt,
                safeName(vm.node),
                vm.vmid,
                (vm.cpu * 100).toInt(),
                Format.size(this@ProxmoxManagerActivity, vm.mem)
            )
            if (vm.ipAddress != null) {
                holder.ip.text = getString(R.string.proxmox_vm_ip_fmt, safeName(vm.ipAddress))
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
            holder.btnStop.setOnClickListener { confirmStop(vm, client) }
            holder.btnReboot.setOnClickListener { powerAction(vm, client, "reboot") }
            holder.btnReset.setOnClickListener { confirmHardReset(vm, client) }
        }

        private fun stateColor(state: String): Int = when (state.lowercase()) {
            "running"    -> androidx.core.content.ContextCompat.getColor(this@ProxmoxManagerActivity, R.color.status_success)
            "stopped"    -> androidx.core.content.ContextCompat.getColor(this@ProxmoxManagerActivity, R.color.status_error)
            "paused"     -> androidx.core.content.ContextCompat.getColor(this@ProxmoxManagerActivity, R.color.status_warning)
            "restarting" -> androidx.core.content.ContextCompat.getColor(this@ProxmoxManagerActivity, R.color.status_warning)
            else         -> androidx.core.content.ContextCompat.getColor(this@ProxmoxManagerActivity, R.color.status_neutral)
        }

        private fun stateLabel(state: String): String = when (state.lowercase()) {
            "running"    -> getString(R.string.vm_state_running)
            "stopped"    -> getString(R.string.vm_state_stopped)
            "paused"     -> getString(R.string.vm_state_paused)
            "restarting" -> getString(R.string.vm_state_restarting)
            else         -> state.replaceFirstChar { it.uppercase() }
        }
    }

    // ── Snapshot management ───────────────────────────────────────────────────

    /**
     * Show snapshot management dialog for a VM. Opened by long-press on a VM
     * row; mirrors [XCPngManagerActivity]'s snapshot dialog pattern.
     */
    private fun showSnapshotDialog(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient) {
        // Re-entered from the create/delete coroutines, which can resume after
        // the activity is gone — showing a dialog then throws BadTokenException.
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val dialogProgress = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val snapshotRecycler = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<android.widget.Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<android.widget.Button>(R.id.close_button)

        snapshotRecycler.layoutManager = LinearLayoutManager(this)
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
                dialogProgress.visibility = View.VISIBLE
                val snapshots = client.listSnapshots(vm.node, vm.vmid, vm.type)
                // The awaited API hop can land after the dialog's activity is gone.
                if (isFinishing || isDestroyed) return@launch
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load snapshots", e)
                if (isFinishing || isDestroyed) return@launch
                dialogProgress.visibility = View.GONE
                emptyStateText.text = getString(R.string.hypervisor_error_loading_snapshots)
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
        val form = DialogFields.form(this)
        // Proxmox snapshot names must be config IDs (letter first, no spaces) — default differs from XCPng's
        val input = DialogFields.addText(
            form,
            hint = getString(R.string.xcpng_snapshot_name_hint),
            initial = "snap${System.currentTimeMillis()}",
            monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hypervisor_create_snapshot_title))
            .setMessage(getString(R.string.hypervisor_snapshot_name_prompt_fmt, safeName(vm.name)))
            .setView(form.root)
            .setPositiveButton(getString(R.string.container_create)) { _, _ ->
                val name = input.text.toString().trim()
                // Proxmox rejects anything that is not a config ID; catching it here
                // gives a usable message instead of a generic "Failed to create snapshot".
                if (!Regex("^[A-Za-z][A-Za-z0-9_-]*$").matches(name)) {
                    Toast.makeText(
                        this@ProxmoxManagerActivity,
                        getString(R.string.proxmox_snapshot_name_invalid),
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val success = client.createSnapshot(vm.node, vm.vmid, vm.type, name)
                        if (isFinishing || isDestroyed) return@launch
                        if (success) {
                            Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.hypervisor_snapshot_created), Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            // Refresh
                            showSnapshotDialog(vm, client)
                        } else {
                            Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.xcpng_error_create_snapshot), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Snapshot creation error", e)
                        if (isFinishing || isDestroyed) return@launch
                        Toast.makeText(
                            this@ProxmoxManagerActivity,
                            getString(R.string.hypervisor_error_prefix_fmt, safeDetail(e.message)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: ProxmoxApiClient.ProxmoxVM, client: ProxmoxApiClient, snapshot: ProxmoxApiClient.ProxmoxSnapshot, action: String, parentDialog: AlertDialog) {
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_revert_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_revert_snapshot_message_fmt, safeName(vm.name), safeName(snapshot.name)))
                    .setPositiveButton(getString(R.string.hypervisor_revert_button)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = client.rollbackSnapshot(vm.node, vm.vmid, vm.type, snapshot.name)
                                if (isFinishing || isDestroyed) return@launch
                                if (success) {
                                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.hypervisor_vm_reverted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.xcpng_error_revert), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Revert error", e)
                                if (isFinishing || isDestroyed) return@launch
                                Toast.makeText(
                                    this@ProxmoxManagerActivity,
                                    getString(R.string.hypervisor_error_prefix_fmt, safeDetail(e.message)),
                                    Toast.LENGTH_LONG
                                ).show()
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
                                val success = client.deleteSnapshot(vm.node, vm.vmid, vm.type, snapshot.name)
                                if (isFinishing || isDestroyed) return@launch
                                if (success) {
                                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.hypervisor_snapshot_deleted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    // Refresh
                                    showSnapshotDialog(vm, client)
                                } else {
                                    Toast.makeText(this@ProxmoxManagerActivity, getString(R.string.xcpng_error_delete_snapshot), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Delete error", e)
                                if (isFinishing || isDestroyed) return@launch
                                Toast.makeText(
                                    this@ProxmoxManagerActivity,
                                    getString(R.string.hypervisor_error_prefix_fmt, safeDetail(e.message)),
                                    Toast.LENGTH_LONG
                                ).show()
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

            // Both name and description come from the hypervisor.
            holder.name.text = safeName(snapshot.name)
            // snaptime is epoch seconds; null while the snapshot is still being created
            holder.time.text = if (snapshot.snaptime != null) {
                getString(
                    R.string.vmware_snapshot_created_time_fmt,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.snaptime * 1000))
                )
            } else {
                snapshot.description?.let { safeName(it) } ?: getString(R.string.proxmox_snapshot_creating)
            }

            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }
    }
}
