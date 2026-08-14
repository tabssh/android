package io.github.tabssh.ui.activities
import io.github.tabssh.utils.logging.Logger

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.ui.tabs.HypervisorConsoleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.config.BulkImportParser
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.replaceAllWithDiff
import io.github.tabssh.utils.showError
import kotlinx.coroutines.CancellationException

class XCPngManagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XCPngManager"

        /** C0/C1 controls plus bidi overrides a hostile or broken API could embed in a name/message. */
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u0080-\\u009F\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]+")

        /**
         * Reduce a server- or exception-supplied string to something safe to put in a
         * log line, a Toast or a dialog: control characters collapsed to a space and
         * the result length-capped, so a hostile XAPI/XO response cannot forge log
         * lines or flood the UI with an unbounded body.
         */
        internal fun safeText(value: String?, max: Int = 160): String {
            val cleaned = value.orEmpty().replace(CONTROL_CHARS, " ").trim()
            return when {
                cleaned.isEmpty() -> "unknown"
                cleaned.length <= max -> cleaned
                else -> cleaned.take(max) + "…"
            }
        }
    }

    private lateinit var app: TabSSHApplication

    // UI Components
    private lateinit var serverSpinner: Spinner
    private lateinit var vmRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var addServerButton: Button
    private lateinit var refreshButton: Button
    private lateinit var backupJobsButton: Button
    private lateinit var infrastructureButton: Button
    private lateinit var liveIndicator: View
    private lateinit var liveText: TextView
    
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val vms = mutableListOf<XCPngApiClient.XenVM>()
    private var currentClient: XCPngApiClient? = null
    private var currentXoClient: XenOrchestraApiClient? = null

    // Console managers whose ConsoleEventListener is an anonymous object
    // holding this Activity (runOnUiThread, progress views). The tab — and
    // therefore the manager — outlives this screen, so onDestroy() must
    // detach the listener or the destroyed Activity is retained for the
    // life of the console connection.
    private val spawnedConsoleManagers =
        mutableListOf<io.github.tabssh.hypervisor.console.HypervisorConsoleManager>()
    private var isXenOrchestra: Boolean = false
    private lateinit var vmAdapter: VMAdapter

    // Single-flight latch for the VM power/console buttons: a second tap while a
    // request is still in flight would fire a duplicate start/stop/reset.
    private var vmActionInFlight = false

    // Single-flight latch for the spinner-driven connect, so a spinner that
    // re-fires while a connect is running cannot leave two clients half-built.
    private var connectInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xcpng_manager) // Use XCP-ng specific layout
        
        app = application as TabSSHApplication
        
        setupToolbar()
        setupViews()
        loadHypervisors()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.xcpng_manager_title)
    }

    private fun setupViews() {
        serverSpinner = findViewById(R.id.server_spinner)
        refreshButton = findViewById(R.id.refresh_button)
        addServerButton = findViewById(R.id.add_server_button)
        backupJobsButton = findViewById(R.id.backup_jobs_button)
        infrastructureButton = findViewById(R.id.infrastructure_button)
        vmRecyclerView = findViewById(R.id.vm_recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        liveIndicator = findViewById(R.id.live_indicator)
        liveText = findViewById(R.id.live_text)
        
        vmRecyclerView.layoutManager = LinearLayoutManager(this)
        // One adapter for the life of the activity: rebuilding it on every
        // refresh dropped scroll position and raced the WebSocket's
        // notifyItemChanged/notifyItemRemoved against a freshly swapped adapter.
        vmAdapter = VMAdapter(vms) { vm, action -> handleVMAction(vm, action) }
        vmRecyclerView.adapter = vmAdapter

        refreshButton.setOnClickListener { refreshVMs() }
        addServerButton.setOnClickListener { showAddServerDialog() }
        backupJobsButton.setOnClickListener { showBackupJobsDialog() }
        infrastructureButton.setOnClickListener { showInfrastructureDialog() }
        
        // Hide live indicator and buttons initially
        liveIndicator.visibility = View.GONE
        liveText.visibility = View.GONE
        backupJobsButton.visibility = View.GONE
        infrastructureButton.visibility = View.GONE
        
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < hypervisors.size) {
                    connectToHypervisor(hypervisors[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadHypervisors() {
        lifecycleScope.launch {
            try {
                val servers = app.database.hypervisorDao().getByType(HypervisorType.XCPNG)
                hypervisors.clear()
                hypervisors.addAll(servers)

                if (hypervisors.isEmpty()) {
                    statusText.text = getString(R.string.xcpng_no_servers)
                    statusText.visibility = View.VISIBLE
                } else {
                    statusText.visibility = View.GONE
                    val adapter = ArrayAdapter(
                        this@XCPngManagerActivity,
                        android.R.layout.simple_spinner_item,
                        hypervisors.map { it.name }
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    serverSpinner.adapter = adapter
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load XCP-ng hypervisor list", e)
                statusText.text = getString(R.string.xcpng_error_load_servers)
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private fun connectToHypervisor(profile: HypervisorProfile) {
        if (connectInFlight) return
        connectInFlight = true
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.xcpng_connecting_fmt, profile.name)
                statusText.visibility = View.VISIBLE

                // The account name identifies the operator; Logger only redacts the
                // "username=" / "user@host" forms, so mask it explicitly here.
                Logger.d(
                    TAG,
                    "Connecting to ${profile.host}:${profile.port} " +
                        "as ${if (profile.username.isEmpty()) "<none>" else "xxxxx"}"
                )

                // Auto-detect: Try Xen Orchestra first, then XCP-ng direct
                val (authenticated, detectedXO) = autoDetectAndConnect(profile)
                isXenOrchestra = detectedXO

                if (authenticated) {
                    // Persist the auto-detected API type when it differs from the stored override.
                    val detectedOverride = if (detectedXO) "centralized" else "direct"
                    if (profile.apiTypeOverride != detectedOverride) {
                        Logger.i(TAG, "Auto-detected API type: ${if (detectedXO) "Xen Orchestra" else "XCP-ng Direct"}")
                        app.database.hypervisorDao().updateApiTypeOverride(profile.id, detectedOverride)
                    }

                    val modeText = if (detectedXO) getString(R.string.xcpng_mode_xen_orchestra) else getString(R.string.xcpng_mode_direct)
                    statusText.text = getString(R.string.xcpng_connected_fmt, profile.name, modeText)
                    app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())

                    // Show XO-specific buttons
                    if (detectedXO) {
                        backupJobsButton.visibility = View.VISIBLE
                        infrastructureButton.visibility = View.VISIBLE
                    } else {
                        backupJobsButton.visibility = View.GONE
                        infrastructureButton.visibility = View.GONE
                    }

                    refreshVMs()

                    // Connect WebSocket for Xen Orchestra (real-time updates)
                    if (detectedXO) {
                        setupWebSocket()
                    }
                } else {
                    statusText.text = getString(R.string.xcpng_auth_failed)
                    showError(getString(R.string.xcpng_auth_failed_details_fmt, profile.host, profile.port, profile.verifySsl.toString()), getString(R.string.xcpng_connection_error_title))
                    progressBar.visibility = View.GONE
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                Logger.e(TAG, "Unknown host: ${profile.host}", e)
                statusText.text = getString(R.string.xcpng_error_host_not_found_fmt, profile.host)
                showError(getString(R.string.xcpng_cannot_resolve_fmt, profile.host), getString(R.string.hypervisor_error_title))
                progressBar.visibility = View.GONE
            } catch (e: java.net.ConnectException) {
                Logger.e(TAG, "Connection refused", e)
                statusText.text = getString(R.string.xcpng_error_connection_refused)
                val apiType = if (profile.apiTypeOverride == "centralized") getString(R.string.xcpng_mode_xen_orchestra) else getString(R.string.xcpng_label_xcpng)
                Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_connection_refused_details_fmt, profile.port, apiType), Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                Logger.e(TAG, "SSL handshake failed", e)
                statusText.text = getString(R.string.xcpng_error_ssl)
                showError(getString(R.string.xcpng_ssl_error_message), getString(R.string.hypervisor_error_title))
                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                Logger.e(TAG, "Connection failed", e)
                // The exception text can carry a whole server error body; clamp it
                // before it reaches the status line or the dialog.
                val reason = safeText(e.message)
                statusText.text = getString(R.string.xcpng_connection_error_fmt, reason)
                showError(getString(R.string.xcpng_connect_failed_fmt, profile.name, reason), getString(R.string.hypervisor_error_title))
                progressBar.visibility = View.GONE
            } finally {
                connectInFlight = false
            }
        }
    }

    /**
     * Auto-detect whether the server is Xen Orchestra or XCP-ng direct.
     * Respects profile.apiTypeOverride: "auto", "direct", "centralized"
     * Returns Pair(authenticated, isXenOrchestra)
     */
    private suspend fun autoDetectAndConnect(profile: HypervisorProfile): Pair<Boolean, Boolean> {
        // Check for manual override
        val override = profile.apiTypeOverride

        // If user forced centralized (Xen Orchestra only)
        if (override == "centralized") {
            Logger.d(TAG, "User override: Force Xen Orchestra")
            return tryXenOrchestra(profile)?.let { Pair(it, true) } ?: Pair(false, false)
        }

        // If user forced direct (XCP-ng only)
        if (override == "direct") {
            Logger.d(TAG, "User override: Force XCP-ng direct")
            return tryXCPngDirect(profile)?.let { Pair(it, false) } ?: Pair(false, false)
        }

        // Auto-detect: Try XO first, fallback to XCP-ng
        Logger.d(TAG, "Auto-detecting API type...")

        // Try Xen Orchestra REST API first
        tryXenOrchestra(profile)?.let { success ->
            if (success) return Pair(true, true)
        }

        // Fallback to XCP-ng XML-RPC
        tryXCPngDirect(profile)?.let { success ->
            if (success) return Pair(true, false)
        }

        // Both failed
        Logger.w(TAG, "All API attempts failed")
        return Pair(false, false)
    }

    private suspend fun tryXenOrchestra(profile: HypervisorProfile): Boolean? {
        return try {
            Logger.d(TAG, "Trying Xen Orchestra REST API...")
            statusText.text = getString(R.string.xcpng_trying_xo)

            val creds = io.github.tabssh.crypto.storage.HypervisorPasswordStore
                .resolveCredentials(this, profile)
            currentClient = null
            currentXoClient = XenOrchestraApiClient(
                host = profile.host,
                port = profile.port,
                email = creds.username,
                password = creds.password,
                verifySsl = profile.verifySsl,
                pinnedCertSha256 = profile.pinnedCertSha256
            )

            if (currentXoClient?.authenticate() == true) {
                Logger.i(TAG, "Xen Orchestra REST API authentication successful")
                val capturedSha = currentXoClient?.getCapturedCertSha256()
                io.github.tabssh.crypto.storage.HypervisorPasswordStore
                    .persistCapturedPinIfAny(this@XCPngManagerActivity, profile, capturedSha)
                if (!capturedSha.isNullOrBlank()) {
                    val idx = hypervisors.indexOfFirst { it.id == profile.id }
                    if (idx >= 0) hypervisors[idx] = hypervisors[idx].copy(pinnedCertSha256 = capturedSha)
                }
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d(TAG, "Xen Orchestra API failed: ${safeText(e.message)}")
            null
        }
    }

    private suspend fun tryXCPngDirect(profile: HypervisorProfile): Boolean? {
        return try {
            Logger.d(TAG, "Trying XCP-ng XML-RPC API...")
            statusText.text = getString(R.string.xcpng_trying_direct)

            val creds = io.github.tabssh.crypto.storage.HypervisorPasswordStore
                .resolveCredentials(this, profile)
            currentXoClient = null
            currentClient = XCPngApiClient(
                host = profile.host,
                port = profile.port,
                username = creds.username,
                password = creds.password,
                verifySsl = profile.verifySsl,
                pinnedCertSha256 = profile.pinnedCertSha256
            )

            if (currentClient?.authenticate() == true) {
                Logger.i(TAG, "XCP-ng XML-RPC API authentication successful")
                val capturedSha = currentClient?.getCapturedCertSha256()
                io.github.tabssh.crypto.storage.HypervisorPasswordStore
                    .persistCapturedPinIfAny(this@XCPngManagerActivity, profile, capturedSha)
                if (!capturedSha.isNullOrBlank()) {
                    val idx = hypervisors.indexOfFirst { it.id == profile.id }
                    if (idx >= 0) hypervisors[idx] = hypervisors[idx].copy(pinnedCertSha256 = capturedSha)
                }
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d(TAG, "XCP-ng API failed: ${safeText(e.message)}")
            null
        }
    }

    private fun refreshVMs() {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = getString(R.string.xcpng_loading_vms)

                val vmList = if (isXenOrchestra) {
                    // Get VMs from Xen Orchestra REST API
                    currentXoClient?.listVMs()?.map { convertXoVMToXenVM(it) } ?: emptyList()
                } else {
                    // Get VMs from XCP-ng XML-RPC API
                    currentClient?.getAllVMs() ?: emptyList()
                }

                vmAdapter.replaceAllWithDiff(
                    items = vms,
                    newItems = vmList,
                    areItemsTheSame = { a, b -> a.uuid == b.uuid }
                )

                statusText.text = getString(R.string.xcpng_found_vms_fmt, vms.size)
                progressBar.visibility = View.GONE

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load VMs", e)
                statusText.text = getString(R.string.xcpng_error_loading_vms)
                progressBar.visibility = View.GONE
            }
        }
    }
    
    /**
     * Convert XenOrchestra XoVM to XCP-ng XenVM format
     */
    private fun convertXoVMToXenVM(xoVM: XenOrchestraApiClient.XoVM): XCPngApiClient.XenVM {
        return XCPngApiClient.XenVM(
            uuid = xoVM.uuid,
            name = xoVM.name_label,
            powerState = xoVM.power_state,
            memory = xoVM.memory,
            vcpus = xoVM.vcpus,
            isTemplate = xoVM.type == "VM-template",
            ipAddress = xoVM.mainIpAddress
        )
    }

    private fun handleVMAction(vm: XCPngApiClient.XenVM, action: String) {
        // The VM label comes from the hypervisor; clamp it before it is
        // interpolated into a confirmation title or body.
        val vmLabel = safeText(vm.name, 64)
        // Confirm before destructive hard-power operations
        if (action == "stop" || action == "reset") {
            val label = if (action == "stop") getString(R.string.xcpng_action_force_stop) else getString(R.string.xcpng_action_hard_reset)
            val msg = if (action == "stop")
                getString(R.string.xcpng_force_stop_message_fmt, vmLabel)
            else
                getString(R.string.xcpng_hard_reset_message_fmt, vmLabel)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.xcpng_confirm_action_title_fmt, label, vmLabel))
                .setMessage(msg)
                .setPositiveButton(label) { _, _ -> doVMAction(vm, action) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
            return
        }
        doVMAction(vm, action)
    }

    private fun doVMAction(vm: XCPngApiClient.XenVM, action: String) {
        // Double-tap guard: the power buttons stay enabled while the request is
        // in flight, so a second tap used to issue a duplicate power operation.
        if (vmActionInFlight) return
        vmActionInFlight = true
        val vmLabel = safeText(vm.name, 64)
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE

                when (action) {
                    "console" -> {
                        openVMConsole(vm)
                        progressBar.visibility = View.GONE
                        return@launch
                    }
                    "ssh" -> {
                        openSshToVm(vm)
                        progressBar.visibility = View.GONE
                        return@launch
                    }
                }
                
                val success = if (isXenOrchestra) {
                    // Use Xen Orchestra API
                    when (action) {
                        "start" -> currentXoClient?.startVM(vm.uuid) ?: false
                        "stop" -> currentXoClient?.stopVM(vm.uuid, force = true) ?: false
                        "shutdown" -> currentXoClient?.stopVM(vm.uuid, force = false) ?: false
                        "reboot" -> currentXoClient?.rebootVM(vm.uuid) ?: false
                        "reset" -> currentXoClient?.resetVM(vm.uuid) ?: false
                        else -> false
                    }
                } else {
                    // Use XCP-ng XML-RPC API
                    when (action) {
                        "start" -> currentClient?.startVM(vm.uuid) ?: false
                        "stop" -> currentClient?.hardShutdownVM(vm.uuid) ?: false
                        "shutdown" -> currentClient?.shutdownVM(vm.uuid) ?: false
                        "reboot" -> currentClient?.rebootVM(vm.uuid) ?: false
                        "reset" -> currentClient?.hardRebootVM(vm.uuid) ?: false
                        else -> false
                    }
                }
                
                if (success) {
                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_vm_action_successful_fmt, action), Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.delay(2000)
                    refreshVMs()
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_action_failed_for_fmt, action, vmLabel), Toast.LENGTH_SHORT).show()
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "VM action failed", e)
                progressBar.visibility = View.GONE
                Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_action_failed_fmt, action, safeText(e.message)), Toast.LENGTH_SHORT).show()
            } finally {
                vmActionInFlight = false
            }
        }
    }

    /**
     * VNC-tab-swipe integration step 6e: opens the VM's serial/graphical
     * console as a [io.github.tabssh.ui.tabs.Tab.Console] inside
     * [TabTerminalActivity] instead of launching the standalone
     * `VMConsoleActivity`. Same discipline as
     * [ProxmoxManagerActivity.openConsole]: the tab is created only *after*
     * the connect succeeds, mirroring [LibvirtManagerActivity.openConsole]'s
     * precedent, since `TabManager` has no by-id/by-reference tab-removal
     * API to clean up a half-created tab on connect failure.
     */
    private fun openVMConsole(vm: XCPngApiClient.XenVM) {
        // Get current hypervisor profile for credentials
        val position = serverSpinner.selectedItemPosition
        if (position < 0 || position >= hypervisors.size) {
            Toast.makeText(this, getString(R.string.xcpng_no_hypervisor_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val profile = hypervisors[position]
        val consoleType = if (isXenOrchestra) HypervisorConsoleType.XEN_ORCHESTRA else HypervisorConsoleType.XCPNG
        val vmLabel = safeText(vm.name, 64)

        // Resolve the password through the Keystore-backed store before
        // connecting. Same-process values are local — the security concern
        // was the on-disk DB column.
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = getString(R.string.xcpng_connecting_to_vm_fmt, vmLabel)
            statusText.visibility = View.VISIBLE

            val creds = io.github.tabssh.crypto.storage.HypervisorPasswordStore
                .resolveCredentials(this@XCPngManagerActivity, profile)
            val manager = io.github.tabssh.hypervisor.console.HypervisorConsoleManager()

            // Captured after the tab is created below — onSwitchToGraphical
            // can only fire once the initial connect (which runs first, on
            // this same coroutine) has already returned, so the tab always
            // exists by the time this listener needs it.
            var consoleTab: io.github.tabssh.ui.tabs.ConsoleTab? = null
            // The console outlives this activity, so every callback body must
            // re-check that the activity is still alive before touching a view.
            val listener = object : io.github.tabssh.hypervisor.console.ConsoleEventListener {
                override fun onConnected(vmName: String) = Unit
                override fun onDisconnected(reason: String) {
                    runOnUiThread {
                        consoleTab?.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED)
                    }
                }
                override fun onError(message: String) {
                    Logger.w(TAG, "Console error for $vmLabel: ${safeText(message)}")
                }
                override fun onStrategyAttempt(strategyName: String) {
                    // One spinner; its text updates as the chain falls through (PLAN 14)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        statusText.text = getString(
                            R.string.xcpng_connecting_strategy_fmt,
                            vmLabel,
                            io.github.tabssh.hypervisor.console.HypervisorConsoleManager.strategyLabel(strategyName)
                        )
                    }
                }
                override fun onSwitchToGraphical(
                    connection: io.github.tabssh.hypervisor.console.HypervisorConsoleManager.ConsoleConnection.Graphical
                ) {
                    runOnUiThread { consoleTab?.markGraphical(connection.rfbClient) }
                }
            }

            try {
                val connection = if (isXenOrchestra) {
                    val xoClient = currentXoClient
                    if (xoClient == null) {
                        progressBar.visibility = View.GONE
                        showError(getString(R.string.xcpng_not_connected_xo))
                        return@launch
                    }
                    manager.connectXenOrchestraConsole(
                        client = xoClient,
                        vmId = vm.uuid,
                        vmName = vm.name,
                        verifySsl = profile.verifySsl,
                        pinnedCertSha256 = profile.pinnedCertSha256,
                        displayHost = profile.host,
                        displayPort = profile.port,
                        listener = listener
                    )
                } else {
                    val xcpClient = currentClient
                    if (xcpClient == null) {
                        progressBar.visibility = View.GONE
                        showError(getString(R.string.xcpng_not_connected_direct))
                        return@launch
                    }
                    manager.connectXCPngConsole(
                        client = xcpClient,
                        vmRef = vm.uuid,
                        vmName = vm.name,
                        verifySsl = profile.verifySsl,
                        pinnedCertSha256 = profile.pinnedCertSha256,
                        displayHost = profile.host,
                        displayPort = profile.port,
                        listener = listener
                    )
                }
                if (connection == null) {
                    // Do NOT show a second generic error here — every code
                    // path that returns null has already surfaced a specific
                    // message via listener.onError() (see VMConsoleActivity's
                    // connectToConsole() for the double-dialog race this
                    // avoids).
                    progressBar.visibility = View.GONE
                    statusText.visibility = View.GONE
                    return@launch
                }

                val tab = app.tabManager.createConsoleTab(
                    io.github.tabssh.ui.tabs.ConsoleConnectParams(
                        type = consoleType,
                        host = profile.host,
                        port = profile.port,
                        username = creds.username,
                        password = creds.password,
                        verifySsl = profile.verifySsl,
                        pinnedCertSha256 = profile.pinnedCertSha256,
                        vmId = vm.uuid,
                        vmName = vm.name,
                        vmRef = vm.uuid
                    )
                )
                if (tab == null) {
                    manager.disconnect()
                    progressBar.visibility = View.GONE
                    statusText.visibility = View.GONE
                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                consoleTab = tab
                tab.consoleManager = manager
                spawnedConsoleManagers.add(manager)

                when (connection) {
                    is io.github.tabssh.hypervisor.console.HypervisorConsoleManager.ConsoleConnection.Text -> {
                        val cursorStyle = app.preferencesManager.getCursorStyleInt()
                        val bridge = withContext(Dispatchers.IO) {
                            io.github.tabssh.terminal.TermuxBridge(
                                columns = 80, rows = 24, transcriptRows = 2000, cursorStyle = cursorStyle
                            ).also { it.initialize() }
                        }
                        manager.wireToTerminal(connection, bridge)
                        bridge.onResizeCallback = { cols, rows -> manager.getWebSocketClient()?.sendResize(cols, rows) }
                        tab.termuxBridge = bridge
                        tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                    }
                    is io.github.tabssh.hypervisor.console.HypervisorConsoleManager.ConsoleConnection.Graphical -> {
                        tab.markGraphical(connection.rfbClient)
                        tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                    }
                    is io.github.tabssh.hypervisor.console.HypervisorConsoleManager.ConsoleConnection.Spice -> {
                        // XCP-ng / Xen Orchestra consoles never resolve to SPICE
                        // (XAPI only exposes rfb/vt100); branch kept for when-exhaustiveness.
                        Logger.w(TAG, "Unexpected SPICE console connection for $vmLabel")
                        manager.disconnect()
                        progressBar.visibility = View.GONE
                        statusText.visibility = View.GONE
                        return@launch
                    }
                }

                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                startActivity(
                    android.content.Intent(this@XCPngManagerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
                val consoleLabel = if (isXenOrchestra) "Xen Orchestra" else "XCP-ng"
                Logger.i(TAG, "Opened $consoleLabel console tab for VM: $vmLabel (uuid=${vm.uuid})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Console connection error for $vmLabel", e)
                progressBar.visibility = View.GONE
                showError(getString(R.string.xcpng_console_connect_failed_fmt, vmLabel, safeText(e.message)))
            }
        }
    }

    /**
     * Opens an SSH terminal to a running XCP-ng / XO VM using its stored IP address.
     * Creates a ConnectionProfile in the "VM Hosts" group if one does not yet exist.
     */
    private fun openSshToVm(vm: XCPngApiClient.XenVM) {
        val vmLabel = safeText(vm.name, 64)
        val ip = vm.ipAddress
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.xcpng_no_ip_fmt, vmLabel), Toast.LENGTH_SHORT).show()
            return
        }
        // The address is reported by the guest agent, so it is server-controlled:
        // reject anything that is not a plain host/IP before it is persisted.
        if (!BulkImportParser.isValidHostValue(ip)) {
            Logger.w(TAG, "Rejecting malformed guest address for $vmLabel")
            Toast.makeText(this, getString(R.string.xcpng_invalid_ip_fmt, vmLabel), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val connectionName = "XCP-ng: $vmLabel"
                var connection = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getByName(connectionName)
                }
                if (connection == null) {
                    val groupId = withContext(Dispatchers.IO) {
                        SystemGroupHelper.getOrCreateSystemGroupId(
                            app.database, "vm_hosts", "VM Hosts", "vm"
                        )
                    }
                    connection = ConnectionProfile(
                        name = connectionName,
                        host = ip,
                        port = 22,
                        username = "root",
                        authType = AuthType.PASSWORD.name,
                        groupId = groupId,
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
                val intent = TabTerminalActivity.createIntent(this@XCPngManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                Logger.i(TAG, "Launching SSH for $vmLabel")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to open SSH for $vmLabel", e)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@XCPngManagerActivity,
                        getString(R.string.hypervisor_ssh_open_failed_fmt, safeText(e.message)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showAddServerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_hypervisor, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.name_input)
        val hostInput = dialogView.findViewById<EditText>(R.id.host_input)
        val portInput = dialogView.findViewById<EditText>(R.id.port_input)
        val usernameInput = dialogView.findViewById<EditText>(R.id.username_input)
        val passwordInput = dialogView.findViewById<EditText>(R.id.password_input)
        val realmInput = dialogView.findViewById<EditText>(R.id.realm_input)
        val apiTypeLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.api_type_layout)
        val apiTypeDropdown = dialogView.findViewById<AutoCompleteTextView>(R.id.api_type_dropdown)
        val apiTypeHint = dialogView.findViewById<TextView>(R.id.api_type_hint)

        portInput.setText("443")
        realmInput.visibility = View.GONE // XCP-ng doesn't use realm

        // Show API type dropdown for XCP-ng
        apiTypeLayout?.visibility = View.VISIBLE
        apiTypeHint?.visibility = View.VISIBLE

        // Setup dropdown with XCP-ng specific options
        val apiTypes = resources.getStringArray(R.array.api_type_entries)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, apiTypes)
        apiTypeDropdown?.setAdapter(adapter)
        apiTypeDropdown?.setText(apiTypes[0], false) // Default to auto-detect
        
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xcpng_add_server_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.xcpng_add_button)) { _, _ ->
                // Get API type override from dropdown
                val apiTypeEntries = resources.getStringArray(R.array.api_type_entries)
                val apiTypeValues = resources.getStringArray(R.array.api_type_values)
                val selectedApiType = apiTypeDropdown?.text?.toString() ?: apiTypeEntries[0]
                val apiTypeIndex = apiTypeEntries.indexOf(selectedApiType)
                val apiTypeOverride = if (apiTypeIndex >= 0) apiTypeValues[apiTypeIndex] else "auto"

                val plaintextPassword = passwordInput.text.toString()
                val serverName = nameInput.text.toString().trim()
                val serverHost = hostInput.text.toString().trim()
                val rawPort = portInput.text.toString().trim().toIntOrNull()
                if (serverName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.xcpng_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!BulkImportParser.isValidHostValue(serverHost)) {
                    Toast.makeText(this, getString(R.string.xcpng_invalid_host), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (rawPort == null || rawPort !in 1..65535) {
                    Toast.makeText(this, getString(R.string.xcpng_invalid_port), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val profile = HypervisorProfile(
                    name = serverName,
                    type = HypervisorType.XCPNG,
                    host = serverHost,
                    port = rawPort,
                    username = usernameInput.text.toString().trim(),
                    password = "",   // never persist plaintext — routed to Keystore below
                    verifySsl = false,
                    apiTypeOverride = apiTypeOverride
                )

                lifecycleScope.launch {
                    try {
                        val newId = app.database.hypervisorDao().insert(profile)
                        // A Keystore failure must not leave a profile whose password
                        // silently does not exist, so surface it instead of swallowing.
                        val stored = io.github.tabssh.crypto.storage.HypervisorPasswordStore.store(
                            this@XCPngManagerActivity, newId, plaintextPassword
                        )
                        if (!stored) {
                            Logger.w(TAG, "Credential store failed for new profile id=$newId")
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(
                                    this@XCPngManagerActivity,
                                    getString(R.string.xcpng_password_store_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        loadHypervisors()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to save XCP-ng server", e)
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this@XCPngManagerActivity,
                                getString(R.string.xcpng_save_server_failed_fmt, safeText(e.message)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // VM Adapter (using shared item_hypervisor_vm layout)

    private inner class VMAdapter(
        private val vms: List<XCPngApiClient.XenVM>,
        private val onAction: (XCPngApiClient.XenVM, String) -> Unit
    ) : RecyclerView.Adapter<VMAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val state: TextView = view.findViewById(R.id.vm_state)
            val info: TextView = view.findViewById(R.id.vm_info)
            val ip: TextView = view.findViewById(R.id.vm_ip)
            val statusDot: View = view.findViewById(R.id.view_status_dot)
            val rowConnect: android.widget.LinearLayout = view.findViewById(R.id.row_connect)
            val rowMain: android.widget.LinearLayout = view.findViewById(R.id.row_main)
            val rowSecondary: android.widget.LinearLayout = view.findViewById(R.id.row_secondary)
            val btnVnc: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_console)
            val btnSsh: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_ssh)
            val btnStart: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_start)
            val btnStop: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_stop)
            val btnReboot: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_reboot)
            val btnReset: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btn_reset)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hypervisor_vm, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val vm = vms[position]

            holder.name.text = safeText(vm.name, 64)
            holder.state.text = stateLabel(vm.powerState)
            holder.info.text = getString(R.string.xcpng_vm_info_fmt, vm.vcpus, vm.memory / 1024 / 1024)
            holder.ip.visibility = View.GONE

            holder.state.setTextColor(stateColor(vm.powerState))
            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(vm.powerState))

            // Show/hide buttons based on VM status
            when (vm.powerState.lowercase()) {
                "running" -> {
                    // XCP-ng supports both VNC console and SSH — show both
                    holder.btnVnc.visibility = View.VISIBLE
                    holder.btnSsh.visibility = View.VISIBLE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.VISIBLE
                    holder.btnReset.visibility = View.VISIBLE
                }
                "halted", "stopped" -> {
                    holder.btnVnc.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.GONE
                    holder.btnReboot.visibility = View.GONE
                    holder.btnReset.visibility = View.GONE
                }
                else -> {
                    // Paused, suspended, transitional — show start/stop only
                    holder.btnVnc.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.GONE
                    holder.btnReset.visibility = View.GONE
                }
            }

            holder.rowConnect.visibility = if (holder.btnVnc.visibility == View.VISIBLE || holder.btnSsh.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowMain.visibility = if (holder.btnStart.visibility == View.VISIBLE || holder.btnStop.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowSecondary.visibility = if (holder.btnReboot.visibility == View.VISIBLE || holder.btnReset.visibility == View.VISIBLE) View.VISIBLE else View.GONE

            // Long-press opens snapshot dialog for Xen Orchestra connections
            holder.itemView.setOnLongClickListener {
                if (isXenOrchestra) { showSnapshotDialog(vm); true } else false
            }

            holder.btnVnc.setOnClickListener { onAction(vm, "console") }
            holder.btnSsh.setOnClickListener { onAction(vm, "ssh") }
            holder.btnStart.setOnClickListener { onAction(vm, "start") }
            holder.btnStop.setOnClickListener { onAction(vm, "stop") }
            holder.btnReboot.setOnClickListener { onAction(vm, "reboot") }
            holder.btnReset.setOnClickListener { onAction(vm, "reset") }
        }

        override fun getItemCount() = vms.size

        private fun stateColor(state: String): Int = when (state.lowercase()) {
            "running" -> androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_success)
            "halted", "stopped" -> androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_error)
            else -> androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_warning)
        }

        private fun stateLabel(state: String): String = when (state.lowercase()) {
            "running" -> getString(R.string.vm_state_running)
            "halted", "stopped" -> getString(R.string.vm_state_stopped)
            "paused" -> getString(R.string.vm_state_paused)
            "suspended" -> getString(R.string.vm_state_suspended)
            else -> state.replaceFirstChar { it.uppercase() }
        }
    }
    
    // ======================== Snapshot Management Dialog ========================
    
    /**
     * Show snapshot management dialog for a VM
     */
    private fun showSnapshotDialog(vm: XCPngApiClient.XenVM) {
        val xoClient = currentXoClient ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        val vmLabel = safeText(vm.name, 64)
        vmNameText.text = getString(R.string.hypervisor_vm_label_fmt, vmLabel)
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        // Load snapshots
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                val snapshots = xoClient.listSnapshots(vm.uuid) ?: emptyList()
                progressBar.visibility = View.GONE
                
                if (snapshots.isEmpty()) {
                    emptyStateText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyStateText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = SnapshotAdapter(snapshots) { snapshot, action ->
                        handleSnapshotAction(vm, snapshot, action, dialog)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load snapshots", e)
                progressBar.visibility = View.GONE
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
    private fun showCreateSnapshotDialog(vm: XCPngApiClient.XenVM, parentDialog: androidx.appcompat.app.AlertDialog) {
        val form = DialogFields.form(this)
        val input = DialogFields.addText(
            form,
            hint = getString(R.string.xcpng_snapshot_name_hint),
            initial = "Snapshot ${System.currentTimeMillis()}",
            monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hypervisor_create_snapshot_title))
            .setMessage(getString(R.string.hypervisor_snapshot_name_prompt_fmt, safeText(vm.name, 64)))
            .setView(form.root)
            .setPositiveButton(getString(R.string.docker_create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.xcpng_snapshot_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val success = currentXoClient?.createSnapshot(vm.uuid, name) ?: false
                        if (success) {
                            Toast.makeText(this@XCPngManagerActivity, getString(R.string.hypervisor_snapshot_created), Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            showSnapshotDialog(vm) // Refresh
                        } else {
                            showError(getString(R.string.xcpng_error_create_snapshot), getString(R.string.hypervisor_error_title))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Snapshot creation error", e)
                        showError(getString(R.string.hypervisor_error_prefix_fmt, safeText(e.message)), getString(R.string.hypervisor_error_title))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: XCPngApiClient.XenVM, snapshot: XenOrchestraApiClient.XoSnapshot, action: String, parentDialog: androidx.appcompat.app.AlertDialog) {
        // Both the VM name and the snapshot label are hypervisor-supplied, so clamp
        // them before they are interpolated into a dialog body or a log line.
        val vmLabel = safeText(vm.name, 64)
        val snapLabel = safeText(snapshot.name_label, 64)
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_revert_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_revert_snapshot_message_fmt, vmLabel, snapLabel))
                    .setPositiveButton(getString(R.string.hypervisor_revert_button)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.revertSnapshot(vm.uuid, snapshot.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.hypervisor_vm_reverted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    showError(getString(R.string.xcpng_error_revert), getString(R.string.hypervisor_error_title))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Revert error", e)
                                showError(getString(R.string.hypervisor_error_prefix_fmt, safeText(e.message)), getString(R.string.hypervisor_error_title))
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            "delete" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_delete_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_delete_snapshot_message_fmt, snapLabel))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.deleteSnapshot(vm.uuid, snapshot.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.hypervisor_snapshot_deleted), Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    showSnapshotDialog(vm) // Refresh
                                } else {
                                    showError(getString(R.string.xcpng_error_delete_snapshot), getString(R.string.hypervisor_error_title))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Snapshot delete error", e)
                                showError(getString(R.string.hypervisor_error_prefix_fmt, safeText(e.message)), getString(R.string.hypervisor_error_title))
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
        private val snapshots: List<XenOrchestraApiClient.XoSnapshot>,
        private val onAction: (XenOrchestraApiClient.XoSnapshot, String) -> Unit
    ) : RecyclerView.Adapter<SnapshotAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.snapshot_name)
            val time: TextView = view.findViewById(R.id.snapshot_time)
            val revertButton: Button = view.findViewById(R.id.revert_button)
            val deleteButton: Button = view.findViewById(R.id.delete_button)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_snapshot, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val snapshot = snapshots[position]
            
            holder.name.text = safeText(snapshot.name_label, 64)
            holder.time.text = getString(
                R.string.xcpng_snapshot_created_fmt,
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.snapshot_time))
            )
            
            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }
        
        override fun getItemCount() = snapshots.size
    }
    
    // ======================== Backup Job Management Dialog ========================
    
    /**
     * Show backup jobs management dialog
     */
    private fun showBackupJobsDialog() {
        val xoClient = currentXoClient ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_jobs, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.backup_recycler_view)
        val refreshButton = dialogView.findViewById<Button>(R.id.refresh_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        fun loadBackupJobs() {
            lifecycleScope.launch {
                try {
                    progressBar.visibility = View.VISIBLE
                    val jobs = xoClient.listBackupJobs() ?: emptyList()
                    progressBar.visibility = View.GONE
                    
                    if (jobs.isEmpty()) {
                        emptyStateText.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyStateText.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.adapter = BackupJobAdapter(jobs) { job, action ->
                            handleBackupJobAction(job, action, dialog)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load backup jobs", e)
                    progressBar.visibility = View.GONE
                    emptyStateText.text = getString(R.string.xcpng_error_loading_backup_jobs)
                    emptyStateText.visibility = View.VISIBLE
                }
            }
        }
        
        loadBackupJobs()
        
        refreshButton.setOnClickListener {
            loadBackupJobs()
        }
        
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * Handle backup job actions (trigger, view runs)
     */
    private fun handleBackupJobAction(job: XenOrchestraApiClient.XoBackupJob, action: String, parentDialog: androidx.appcompat.app.AlertDialog) {
        when (action) {
            "trigger" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.xcpng_trigger_backup_title))
                    .setMessage(getString(R.string.xcpng_trigger_backup_message_fmt, safeText(job.name, 64)))
                    .setPositiveButton(getString(R.string.xcpng_trigger_button)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.triggerBackup(job.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_backup_triggered), Toast.LENGTH_SHORT).show()
                                } else {
                                    showError(getString(R.string.xcpng_error_trigger_backup), getString(R.string.hypervisor_error_title))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e(TAG, "Trigger backup error", e)
                                showError(getString(R.string.hypervisor_error_prefix_fmt, safeText(e.message)), getString(R.string.hypervisor_error_title))
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            "view_runs" -> {
                showBackupRunsDialog(job)
            }
        }
    }
    
    /**
     * Backup job adapter for RecyclerView
     */
    private inner class BackupJobAdapter(
        private val jobs: List<XenOrchestraApiClient.XoBackupJob>,
        private val onAction: (XenOrchestraApiClient.XoBackupJob, String) -> Unit
    ) : RecyclerView.Adapter<BackupJobAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.job_name)
            val mode: TextView = view.findViewById(R.id.job_mode)
            val status: TextView = view.findViewById(R.id.job_status)
            val schedule: TextView = view.findViewById(R.id.job_schedule)
            val vms: TextView = view.findViewById(R.id.job_vms)
            val triggerButton: Button = view.findViewById(R.id.trigger_button)
            val viewRunsButton: Button = view.findViewById(R.id.view_runs_button)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_backup_job, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val job = jobs[position]
            
            holder.name.text = safeText(job.name, 64)
            holder.mode.text = getString(R.string.xcpng_job_mode_fmt, safeText(job.mode, 32))
            holder.status.text = if (job.enabled) getString(R.string.xcpng_job_enabled) else getString(R.string.xcpng_job_disabled)
            holder.status.setTextColor(if (job.enabled) androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_success) else androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_warning))
            holder.schedule.text = getString(R.string.xcpng_job_schedule_fmt, job.schedule?.let { safeText(it, 48) } ?: getString(R.string.xcpng_schedule_manual))
            holder.vms.text = getString(R.string.xcpng_job_vms_fmt, job.vms?.size ?: 0)
            
            holder.triggerButton.setOnClickListener { onAction(job, "trigger") }
            holder.viewRunsButton.setOnClickListener { onAction(job, "view_runs") }
        }
        
        override fun getItemCount() = jobs.size
    }
    
    // ======================== Backup Runs Dialog ========================
    
    /**
     * Show backup runs dialog for a specific backup job
     */
    private fun showBackupRunsDialog(job: XenOrchestraApiClient.XoBackupJob) {
        val xoClient = currentXoClient ?: return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_runs, null)
        val jobNameText = dialogView.findViewById<TextView>(R.id.job_name_text)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.backup_runs_recycler_view)
        val refreshButton = dialogView.findViewById<Button>(R.id.refresh_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        jobNameText.text = getString(R.string.xcpng_backup_job_label_fmt, safeText(job.name, 64))
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        fun loadBackupRuns() {
            lifecycleScope.launch {
                try {
                    progressBar.visibility = View.VISIBLE
                    val runs = xoClient.getBackupRuns(job.id) ?: emptyList()
                    progressBar.visibility = View.GONE
                    
                    if (runs.isEmpty()) {
                        emptyStateText.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyStateText.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.adapter = BackupRunAdapter(runs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load backup runs", e)
                    progressBar.visibility = View.GONE
                    emptyStateText.text = getString(R.string.xcpng_error_loading_backup_runs)
                    emptyStateText.visibility = View.VISIBLE
                }
            }
        }
        
        loadBackupRuns()
        
        refreshButton.setOnClickListener {
            loadBackupRuns()
        }
        
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * Backup run adapter for RecyclerView
     */
    private inner class BackupRunAdapter(
        private val runs: List<XenOrchestraApiClient.XoBackupRun>
    ) : RecyclerView.Adapter<BackupRunAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val status: TextView = view.findViewById(R.id.run_status)
            val date: TextView = view.findViewById(R.id.run_date)
            val duration: TextView = view.findViewById(R.id.run_duration)
            val result: TextView = view.findViewById(R.id.run_result)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_backup_run, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val run = runs[position]
            
            // Status with color
            when (run.status.lowercase()) {
                "success" -> {
                    holder.status.text = getString(R.string.xcpng_run_status_success)
                    holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_success))
                }
                "failure", "error" -> {
                    holder.status.text = getString(R.string.xcpng_run_status_failed)
                    holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_error))
                }
                "running", "in_progress" -> {
                    holder.status.text = getString(R.string.xcpng_run_status_running)
                    holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_info))
                }
                else -> {
                    holder.status.text = getString(R.string.xcpng_run_status_other_fmt, safeText(run.status, 32))
                    holder.status.setTextColor(androidx.core.content.ContextCompat.getColor(this@XCPngManagerActivity, R.color.status_neutral))
                }
            }
            
            // Date
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            holder.date.text = dateFormat.format(java.util.Date(run.start))
            
            // Duration
            if (run.end != null && run.end > 0) {
                val durationMs = run.end - run.start
                val durationSec = durationMs / 1000
                val minutes = durationSec / 60
                val seconds = durationSec % 60
                holder.duration.text = getString(R.string.xcpng_duration_fmt, minutes, seconds)
            } else {
                holder.duration.text = getString(R.string.xcpng_duration_in_progress)
            }

            // Result
            // The result string is an unbounded server-side message, so cap it before display.
            holder.result.text = getString(R.string.xcpng_result_fmt, run.result?.let { safeText(it, 200) } ?: getString(R.string.xcpng_no_details))
        }
        
        override fun getItemCount() = runs.size
    }
    
    // ======================== WebSocket Real-Time Updates ========================
    
    /**
     * Setup WebSocket connection for real-time VM updates
     */
    private fun setupWebSocket() {
        val xoClient = currentXoClient ?: return
        
        Logger.d(TAG, "Setting up WebSocket for real-time updates")

        // The socket delivers events on its own thread and can outlive this screen,
        // so every callback hops to the main thread and bails out once the activity
        // is finishing or destroyed before touching a view.
        xoClient.connectWebSocket(object : XenOrchestraApiClient.EventListener {
            override fun onVMStateChanged(vmId: String, newState: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val state = safeText(newState, 32)
                    Logger.d(TAG, "VM state changed to $state")

                    // Update VM in list
                    val vmIndex = vms.indexOfFirst { it.uuid == vmId }
                    if (vmIndex >= 0) {
                        vms[vmIndex] = vms[vmIndex].copy(powerState = newState)
                        vmAdapter.notifyItemChanged(vmIndex)
                    }

                    Toast.makeText(this@XCPngManagerActivity,
                        getString(R.string.xcpng_vm_state_changed_fmt, state),
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onVMCreated(vmId: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "VM created")
                    Toast.makeText(this@XCPngManagerActivity,
                        getString(R.string.xcpng_vm_created),
                        Toast.LENGTH_SHORT).show()
                    refreshVMs()
                }
            }

            override fun onVMDeleted(vmId: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "VM deleted")

                    // Remove VM from list
                    val vmIndex = vms.indexOfFirst { it.uuid == vmId }
                    if (vmIndex >= 0) {
                        vms.removeAt(vmIndex)
                        vmAdapter.notifyItemRemoved(vmIndex)
                    }

                    Toast.makeText(this@XCPngManagerActivity,
                        getString(R.string.xcpng_vm_deleted),
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSnapshotCreated(vmId: String, snapshotId: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "Snapshot created")
                    Toast.makeText(this@XCPngManagerActivity,
                        getString(R.string.hypervisor_snapshot_created),
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSnapshotDeleted(vmId: String, snapshotId: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "Snapshot deleted")
                    Toast.makeText(this@XCPngManagerActivity,
                        getString(R.string.hypervisor_snapshot_deleted),
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onBackupCompleted(jobId: String, success: Boolean) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "Backup completed: success=$success")
                    val message = if (success) getString(R.string.xcpng_backup_completed_success) else getString(R.string.xcpng_backup_failed)
                    Toast.makeText(this@XCPngManagerActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Logger.d(TAG, "WebSocket connection state: $connected")

                    // Show/hide live indicator
                    if (connected) {
                        liveIndicator.visibility = View.VISIBLE
                        liveText.visibility = View.VISIBLE
                        
                        // Subscribe to all VMs for updates
                        xoClient.subscribeToAllVMs()
                    } else {
                        liveIndicator.visibility = View.GONE
                        liveText.visibility = View.GONE
                    }
                }
            }
            
            override fun onError(error: String) {
                // Errors are logged only, so no activity-alive check or UI hop is needed.
                Logger.e(TAG, "WebSocket error: ${safeText(error)}")
            }
        })
    }
    
    private fun showInfrastructureDialog() {
        lifecycleScope.launch {
            try {
                val xoClient = currentXoClient
                if (xoClient == null) {
                    Toast.makeText(this@XCPngManagerActivity, getString(R.string.xcpng_not_connected_xo), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Fetch pools and hosts
                val pools = xoClient.listPools()
                val hosts = xoClient.listHosts()

                // Build infrastructure tree
                val message = buildString {
                    appendLine(getString(R.string.xcpng_infra_header))
                    appendLine()
                    appendLine(getString(R.string.xcpng_infra_pools_fmt, pools.size))
                    pools.forEach { pool ->
                        appendLine(getString(R.string.xcpng_infra_pool_name_fmt, safeText(pool.name_label, 64)))
                        appendLine(getString(R.string.xcpng_infra_pool_uuid_fmt, safeText(pool.uuid, 64)))
                    }
                    appendLine()
                    appendLine(getString(R.string.xcpng_infra_hosts_fmt, hosts.size))
                    hosts.forEach { host ->
                        val status = if (host.enabled) getString(R.string.xcpng_infra_host_status_enabled) else getString(R.string.xcpng_infra_host_status_disabled)
                        appendLine(getString(R.string.xcpng_infra_host_name_fmt, safeText(host.name_label, 64), status))
                        appendLine(getString(R.string.xcpng_infra_host_hostname_fmt, safeText(host.hostname, 64)))
                        val memGB = host.memory_total / (1024 * 1024 * 1024)
                        val memFreeGB = host.memory_free / (1024 * 1024 * 1024)
                        appendLine(getString(R.string.xcpng_infra_host_memory_fmt, memFreeGB, memGB))
                    }
                }

                if (isFinishing || isDestroyed) return@launch
                MaterialAlertDialogBuilder(this@XCPngManagerActivity)
                    .setTitle(getString(R.string.xcpng_infra_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load infrastructure", e)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@XCPngManagerActivity,
                        getString(R.string.hypervisor_error_prefix_fmt, safeText(e.message)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        // Cancel any in-flight HTTP calls before tearing down the WebSocket so
        // OkHttp does not retain Activity references through callbacks past onDestroy.
        try { currentXoClient?.cancelAll() } catch (e: Exception) { Logger.w(TAG, "xo cancelAll: ${safeText(e.message)}") }
        try { currentClient?.cancelAll() } catch (e: Exception) { Logger.w(TAG, "xcp cancelAll: ${safeText(e.message)}") }
        // Disconnect WebSocket when activity is destroyed
        currentXoClient?.disconnectWebSocket()
        // Drop the client references so a queued callback cannot resurrect a
        // connection through this destroyed activity.
        currentXoClient = null
        currentClient = null
        // Listeners are anonymous objects holding this Activity; the console
        // connections (owned by their tabs) outlive this screen.
        spawnedConsoleManagers.forEach { it.detachListener() }
        spawnedConsoleManagers.clear()
        super.onDestroy()
    }
}
