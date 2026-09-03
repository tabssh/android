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
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.hypervisor.libvirt.LibvirtApiClient
import io.github.tabssh.hypervisor.libvirt.LibvirtException
import io.github.tabssh.hypervisor.libvirt.LibvirtVm
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.config.BulkImportParser
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import io.github.tabssh.utils.ThrowableMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

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
class LibvirtManagerActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "LibvirtManagerActivity"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"

        /** C0/C1 controls plus bidi overrides a hostile or broken hypervisor could embed in a name or message. */
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}\\u0080-\\u009F\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]+")

        /**
         * Reduce a hypervisor- or exception-supplied string to something safe to
         * put in a log line, a Toast or a dialog: control characters collapsed to
         * a space and the result length-capped, so unbounded virsh output cannot
         * forge log lines or flood the UI.
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
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private var apiClient: LibvirtApiClient? = null

    /**
     * The snapshot list dialog outlives a configuration change or a back-press
     * unless it is dismissed explicitly, leaking its window. Tracked so
     * [onDestroy] can dismiss it.
     */
    private var snapshotDialog: AlertDialog? = null
    private var hypervisorProfile: HypervisorProfile? = null
    private val vms = mutableListOf<LibvirtVm>()
    private lateinit var adapter: VmAdapter

    // Retained for showError's tap-to-retry — re-runs the connect that failed.
    private var currentHypervisorId: Long = -1L

    // Single-flight latch for the power buttons: a second tap while a virsh
    // command is still running would fire a duplicate start/stop/reboot/reset.
    private var powerActionInFlight = false

    // Single-flight latch for the console button: two taps would open two VNC
    // channels and leak the one whose tab never gets created.
    private var consoleInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_libvirt_manager)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.libvirt_manager_title)

        adapter = VmAdapter(vms)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refresh() }

        val hypervisorId = intent.getLongExtra(EXTRA_HYPERVISOR_ID, -1L)
        currentHypervisorId = hypervisorId
        if (hypervisorId == -1L) {
            showError(getString(R.string.hypervisor_error_no_id))
            return
        }
        connectAndRefresh(hypervisorId)
    }

    override fun onDestroy() {
        // JSch tears the socket down on the calling thread, so hand the teardown
        // to a worker: lifecycleScope is already cancelled by the time we get here.
        val client = apiClient
        apiClient = null
        if (client != null) {
            Thread({ client.disconnect() }, "libvirt-disconnect").start()
        }
        snapshotDialog?.dismiss()
        snapshotDialog = null
        super.onDestroy()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectAndRefresh(hypervisorId: Long) {
        lifecycleScope.launch {
            showProgress(getString(R.string.libvirt_connecting))
            val profile = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getById(hypervisorId)
            }
            if (profile == null) {
                showError(getString(R.string.hypervisor_error_not_found_fmt, hypervisorId))
                return@launch
            }
            hypervisorProfile = profile
            supportActionBar?.title = profile.name

            val client = LibvirtApiClient(this@LibvirtManagerActivity, profile)
            try {
                withContext(Dispatchers.IO) { client.connect() }
                withContext(Dispatchers.IO) {
                    app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                }
                apiClient = client
                loadDomains(client)
            } catch (e: CancellationException) {
                // The activity is going away: close the half-built session rather
                // than leaking it, then let the cancellation propagate.
                Thread({ client.disconnect() }, "libvirt-disconnect").start()
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to connect to libvirt host", e)
                val msg = safeText(e.message)
                // "No credentials found" → Keystore entry is gone; offer shortcut to settings.
                if (msg.startsWith("No credentials found")) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        progressBar.visibility = View.GONE
                        MaterialAlertDialogBuilder(this@LibvirtManagerActivity)
                            .setTitle(getString(R.string.libvirt_credentials_missing_title))
                            .setMessage(getString(R.string.libvirt_credentials_missing_message_fmt, msg))
                            .setPositiveButton(getString(R.string.libvirt_open_settings)) { _, _ ->
                                startActivity(
                                    Intent(this@LibvirtManagerActivity, HypervisorEditActivity::class.java)
                                        .putExtra("hypervisor_id", hypervisorId)
                                )
                            }
                            .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish() }
                            .show()
                    }
                } else {
                    val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Failed to connect to libvirt host")
                    showError(getString(R.string.libvirt_ssh_connect_failed_fmt, mapped.message))
                }
            }
        }
    }

    private fun refresh() {
        val client = apiClient ?: run {
            Toast.makeText(this, getString(R.string.hypervisor_not_connected_wait), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { loadDomains(client) }
    }

    private suspend fun loadDomains(client: LibvirtApiClient) {
        showProgress(getString(R.string.libvirt_loading_domains))
        try {
            val domains = withContext(Dispatchers.IO) { client.listDomains() }
            adapter.replaceAllWithDiff(
                items = vms,
                newItems = domains,
                areItemsTheSame = { a, b -> a.name == b.name }
            )
            hideProgress()
            if (domains.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.libvirt_no_domains)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Failed to list domains")
            showError(getString(R.string.libvirt_error_list_domains_fmt, mapped.message))
        }
    }

    // ── VM actions ────────────────────────────────────────────────────────────

    private fun confirmHardReset(vm: LibvirtVm, client: LibvirtApiClient) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.libvirt_hard_reset_title_fmt, safeText(vm.name, 64)))
            .setMessage(getString(R.string.libvirt_hard_reset_message))
            .setPositiveButton(getString(R.string.libvirt_reset_button)) { _, _ -> powerAction(vm, client, "reset") }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun powerAction(vm: LibvirtVm, client: LibvirtApiClient, action: String) {
        // Double-tap guard: the power buttons stay enabled while the virsh call is
        // in flight, so a second tap used to issue a duplicate power operation.
        if (powerActionInFlight) return
        powerActionInFlight = true
        val vmLabel = safeText(vm.name, 64)
        // Progress copy and the past-tense confirmation toast both key off the
        // action verb — resolved once here so neither needs an English-only
        // string concatenation ("${action}ing") that would break translations.
        val (progressFmtRes, actionLabelRes) = when (action) {
            "start"  -> R.string.libvirt_power_action_start_progress_fmt to R.string.container_action_start
            "stop"   -> R.string.libvirt_power_action_stop_progress_fmt to R.string.container_action_stop
            "reboot" -> R.string.libvirt_power_action_reboot_progress_fmt to R.string.hypervisor_vm_action_reboot
            else     -> R.string.libvirt_power_action_reset_progress_fmt to R.string.libvirt_reset_button
        }
        lifecycleScope.launch {
            showProgress(getString(progressFmtRes, vmLabel))
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        "start"  -> client.startDomain(vm.name)
                        // The button is labelled "Shutdown", so it must issue the
                        // graceful `virsh shutdown`; `virsh destroy` cuts power and
                        // belongs behind the confirmed hard-reset path only.
                        "stop"   -> client.shutdownDomain(vm.name)
                        "reboot" -> client.rebootDomain(vm.name)
                        "reset"  -> client.resetDomain(vm.name)
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    val sentMessage = getString(
                        R.string.libvirt_power_action_sent_fmt, vmLabel, getString(actionLabelRes)
                    )
                    Toast.makeText(this@LibvirtManagerActivity, sentMessage, Toast.LENGTH_SHORT).show()
                }
                loadDomains(client)
            } catch (e: LibvirtException) {
                hideProgress()
                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "$action failed")
                showDomainError("$action failed", mapped.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hideProgress()
                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "$action failed for $vmLabel")
                showError("$action failed: ${mapped.message}")
            } finally {
                powerActionInFlight = false
            }
        }
    }

    /**
     * Open a VNC console for [vm]. If the domain has no VNC display configured
     * (LibvirtException "VNC not configured"), auto-detect the VM's IP via virsh
     * and offer SSH as a fallback.
     *
     * VNC-tab-swipe integration step 6c: creates an ephemeral [io.github.tabssh.ui.tabs.VncTab]
     * directly on the shared, application-scoped `TabManager` (same pattern as
     * [VncHostsActivity.openVncConsole]) rather than opening a dedicated
     * per-console activity. The [io.github.tabssh.hypervisor.console.rfb.RfbClient]
     * is constructed here from the libvirt-provided streams but never started —
     * `TerminalPagerAdapter`'s `VncViewHolder` drives the handshake once the page renders.
     *
     * Resize handling: a dedicated per-console activity could afford to auto-retry
     * the connection with resize disabled when the server closed the socket after
     * rejecting a SetDesktopSize request, because the retry only relaunched that
     * one console. [TabTerminalActivity] is instead a shared, persistent multi-tab
     * shell that may have unrelated tabs open when a resize rejection happens on
     * this one, so relaunching it would disrupt those unrelated tabs. Libvirt/QEMU
     * VNC displays are also the primary case that rejects resize at all, so this
     * path defaults `canRequestResize = false` unconditionally for ephemeral
     * libvirt consoles — trading away resize support on the rare display that
     * *would* honour it for a connection that never hits the rejection-disconnect
     * loop at all.
     */
    private fun openConsole(vm: LibvirtVm, client: LibvirtApiClient) {
        if (consoleInFlight) return
        consoleInFlight = true
        val vmLabel = safeText(vm.name, 64)
        lifecycleScope.launch {
            showProgress(getString(R.string.libvirt_opening_console_fmt, vmLabel))
            try {
                // SPICE first: richest protocol when the domain exposes it and
                // the native library shipped; any miss falls through silently
                // to the VNC path (Logger.i inside getSpiceDisplay).
                val spiceDisplay = withContext(Dispatchers.IO) {
                    try {
                        client.getSpiceDisplay(vm.name)
                    } catch (e: CancellationException) {
                        // A cancelled scope must not be reported as "no SPICE"
                        // and fall through to the VNC probe on a dead scope.
                        throw e
                    } catch (e: Exception) {
                        Logger.i(TAG, "SPICE probe failed for $vmLabel: ${safeText(e.message)} — VNC fallback")
                        null
                    }
                }
                if (spiceDisplay != null) {
                    openSpiceTab(vm, client, spiceDisplay)
                    return@launch
                }
                val vncChannel = withContext(Dispatchers.IO) { client.openVncChannel(vm.name) }
                val rfbClient = io.github.tabssh.hypervisor.console.rfb.RfbClient(
                    inputStream = vncChannel.input,
                    outputStream = vncChannel.output,
                    // Domains with <graphics type='vnc' passwd='…'/> fail VNC-Auth
                    // without this; null stays null for unauthenticated displays.
                    vncPassword = vncChannel.password,
                    consoleMode = true
                )
                rfbClient.canRequestResize = false
                val tab = app.tabManager.createVncTab(vncHost = null, ephemeralDisplayName = vm.name)
                hideProgress()
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${safeText(e.message)}")
                    }
                    vncChannel.close()
                    Toast.makeText(this@LibvirtManagerActivity, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                // Stopping the RfbClient closes only the streams it was handed —
                // the JSch channel behind them stays connected until told
                // otherwise, leaking a channel and its pump threads per tab.
                tab.onCleanup = { vncChannel.close() }
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                startActivity(
                    Intent(this@LibvirtManagerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: LibvirtException) {
                Logger.w(TAG, "VNC unavailable for $vmLabel: ${safeText(e.message)}")
                hideProgress()
                if (e.message?.contains("VNC not configured") == true) {
                    // VNC not configured on this VM — try SSH instead.
                    offerSshFallback(vm, client)
                } else {
                    val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "VNC unavailable for $vmLabel")
                    showDomainError(getString(R.string.libvirt_console_error_title), mapped.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Failed to open VNC channel for $vmLabel")
                hideProgress()
                showError(getString(R.string.libvirt_error_open_console_fmt, mapped.message))
            } finally {
                consoleInFlight = false
            }
        }
    }

    /**
     * Host a SPICE session for [vm] in a [io.github.tabssh.ui.tabs.ConsoleTab] —
     * the tab kind the SPICE architecture renders (`TerminalPagerAdapter`'s
     * `bindSpice`); [io.github.tabssh.ui.tabs.VncTab] is RFB-only. The
     * [io.github.tabssh.hypervisor.spice.SpiceClient] is constructed un-started:
     * the ConsoleViewHolder attaches the SpiceView listener and performs the
     * single start() once the page renders, same as the Proxmox spiceproxy flow.
     * The SSH local port forward is removed via the tab's cleanup hook.
     */
    private fun openSpiceTab(vm: LibvirtVm, client: LibvirtApiClient, display: LibvirtApiClient.SpiceDisplay) {
        val hvProfile = hypervisorProfile ?: run {
            client.stopSpiceForward(display.localPort)
            showError(getString(R.string.hypervisor_profile_not_loaded))
            return
        }
        val connectParams = io.github.tabssh.ui.tabs.ConsoleConnectParams(
            type = io.github.tabssh.ui.tabs.HypervisorConsoleType.LIBVIRT,
            host = hvProfile.host,
            port = hvProfile.port,
            username = hvProfile.username,
            // No API password is stored on the tab: the SPICE ticket travels in
            // SpiceConnectionParams and libvirt consoles have no reconnect path.
            password = "",
            verifySsl = true,
            pinnedCertSha256 = null,
            vmId = vm.name,
            vmName = vm.name
        )
        val tab = app.tabManager.createConsoleTab(connectParams)
        hideProgress()
        if (tab == null) {
            client.stopSpiceForward(display.localPort)
            Toast.makeText(this, getString(R.string.virt_viewer_max_tabs), Toast.LENGTH_SHORT).show()
            return
        }
        val spiceClient = io.github.tabssh.hypervisor.spice.SpiceClient(display.params)
        tab.markSpice(spiceClient)
        tab.onCleanup = { client.stopSpiceForward(display.localPort) }
        tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
        startActivity(
            Intent(this, TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
            }
        )
        Logger.i(TAG, "Opened SPICE console tab for ${safeText(vm.name, 64)} via 127.0.0.1:${display.localPort}")
    }

    /**
     * Directly SSHes to a running VM by fetching its IP via virsh first,
     * then launching [TabTerminalActivity] through the hypervisor as a jump host.
     */
    private fun directSshToVm(vm: LibvirtVm, client: LibvirtApiClient) {
        lifecycleScope.launch {
            val ip = detectVmIp(vm, client)
            launchSshToVm(vm, ip)
        }
    }

    /**
     * Ask virsh for the domain's address. The value is hypervisor-supplied, so it
     * is validated before it can reach a stored profile or the SSH transport; a
     * probe failure is not fatal and simply yields null.
     */
    private suspend fun detectVmIp(vm: LibvirtVm, client: LibvirtApiClient): String? =
        withContext(Dispatchers.IO) {
            val raw = try {
                client.getVmIpAddress(vm.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d(TAG, "IP probe failed for ${safeText(vm.name, 64)}: ${safeText(e.message)}")
                null
            }
            raw?.trim()?.takeIf { BulkImportParser.isValidHostValue(it) }
        }

    /**
     * Called when VNC is not available. Attempts to auto-detect the VM's IP via
     * `virsh domifaddr`, then presents a "Connect via SSH?" dialog pre-filled
     * with that IP. The SSH profile is persisted so repeated taps don't re-ask.
     */
    private fun offerSshFallback(vm: LibvirtVm, client: LibvirtApiClient) {
        lifecycleScope.launch {
            val detectedIp = detectVmIp(vm, client)

            val messageLines = mutableListOf<String>()
            messageLines += getString(R.string.libvirt_no_vnc_display)
            if (detectedIp != null) {
                messageLines += getString(R.string.libvirt_detected_ip_fmt, detectedIp)
            }
            messageLines += ""
            messageLines += getString(R.string.libvirt_ssh_fallback_tunnel_line)
            messageLines += getString(
                R.string.libvirt_ssh_fallback_jump_line_fmt,
                hypervisorProfile?.host ?: getString(R.string.libvirt_fallback_hypervisor_label)
            )

            if (isFinishing || isDestroyed) return@launch
            MaterialAlertDialogBuilder(this@LibvirtManagerActivity)
                .setTitle(getString(R.string.libvirt_no_console_title))
                .setMessage(messageLines.joinToString("\n"))
                .setPositiveButton(getString(R.string.libvirt_ssh_connect_button)) { _, _ ->
                    launchSshToVm(vm, detectedIp)
                }
                .setNegativeButton(getString(R.string.cancel), null)
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
            showError(getString(R.string.hypervisor_profile_not_loaded))
            return
        }
        val vmLabel = safeText(vm.name, 64)
        lifecycleScope.launch {
            try {
                val connectionName = "libvirt: $vmLabel"
                // Deterministic per-VM profile id (server row id + libvirt
                // domain name — the stable domain identifier virsh exposes),
                // so renaming the profile in Hosts never breaks the mapping —
                // getByName remains only as a one-time legacy fallback for
                // rows created before the deterministic id existed.
                val profileId = "libvirt-vm:${currentHypervisorId}:${vm.name}"
                var existing = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getConnectionById(profileId)
                        ?: app.database.connectionDao().getByName(connectionName)
                }
                val host = ip ?: existing?.host ?: ""

                // Jump-host auth mirrors the hypervisor profile's own auth method.
                val proxyAuthType = if (hvProfile.sshIdentityId != null)
                    AuthType.PUBLIC_KEY.name else AuthType.PASSWORD.name
                val proxyKeyId = hvProfile.sshIdentityId

                val isNewProfile = existing == null
                if (existing == null) {
                    // Leave username/authType blank so the user fills them in via
                    // ConnectionEditActivity. Connecting with guessed credentials
                    // (e.g. root/password) always fails; it's better to stop and
                    // let the user configure the right auth method for their VM.
                    val vmHostsGroupId = withContext(Dispatchers.IO) {
                        SystemGroupHelper.getOrCreateSystemGroupId(
                            app.database, "vm_hosts", getString(R.string.vmware_group_name_vm_hosts), "vm"
                        )
                    }
                    existing = ConnectionProfile(
                        id = profileId,
                        name = connectionName,
                        host = host,
                        port = 22,
                        username = "",
                        authType = AuthType.PASSWORD.name,
                        // Route through the hypervisor as an SSH jump host so the VM's
                        // internal bridge IP is reachable from Android.
                        proxyType = "SSH",
                        proxyHost = hvProfile.host,
                        proxyPort = hvProfile.port,
                        proxyUsername = hvProfile.username,
                        proxyAuthType = proxyAuthType,
                        proxyKeyId = proxyKeyId,
                        groupId = vmHostsGroupId,
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

                if (isNewProfile) {
                    // New profile: open editor so the user can set the correct
                    // username and auth method before connecting. Auto-connecting
                    // with default credentials always fails.
                    val intent = ConnectionEditActivity.createIntent(
                        this@LibvirtManagerActivity, connectionId = existing.id
                    )
                    startActivity(intent)
                    Logger.i(TAG, "Opened SSH editor for new VM profile $vmLabel — user must set credentials")
                } else {
                    val intent = TabTerminalActivity.createIntent(
                        this@LibvirtManagerActivity, existing, autoConnect = host.isNotBlank()
                    )
                    startActivity(intent)
                    Logger.i(TAG, "Launched SSH fallback for $vmLabel via jump:${hvProfile.host}:${hvProfile.port}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "SSH fallback launch failed for $vmLabel")
                showError(getString(R.string.hypervisor_ssh_open_failed_fmt, mapped.message))
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(message: String) {
        runOnUiThread {
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

    private fun showDomainError(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    // ── Snapshot management ───────────────────────────────────────────────────

    /**
     * Show the snapshot management dialog for [vm]: lists existing snapshots
     * with revert/delete actions and offers creating a new one.
     */
    private fun showSnapshotDialog(vm: LibvirtVm, client: LibvirtApiClient) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_vm_snapshots, null)
        val vmNameText = dialogView.findViewById<TextView>(R.id.vm_name_text)
        val dialogProgress = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
        val emptyStateText = dialogView.findViewById<TextView>(R.id.empty_state_text)
        val snapshotRecycler = dialogView.findViewById<RecyclerView>(R.id.snapshot_recycler_view)
        val createButton = dialogView.findViewById<Button>(R.id.create_snapshot_button)
        val closeButton = dialogView.findViewById<Button>(R.id.close_button)

        snapshotRecycler.layoutManager = LinearLayoutManager(this)
        val vmLabel = safeText(vm.name, 64)
        vmNameText.text = getString(R.string.hypervisor_vm_label_fmt, vmLabel)

        snapshotDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        snapshotDialog = dialog

        // Load snapshots
        lifecycleScope.launch {
            try {
                dialogProgress.visibility = View.VISIBLE
                val snapshots = withContext(Dispatchers.IO) { client.listSnapshots(vm.name) }
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
                Logger.e(TAG, "Failed to load snapshots for $vmLabel", e)
                dialogProgress.visibility = View.GONE
                emptyStateText.text = getString(R.string.hypervisor_error_loading_snapshots)
                emptyStateText.visibility = View.VISIBLE
            }
        }

        createButton.setOnClickListener { showCreateSnapshotDialog(vm, client, dialog) }
        closeButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * Prompt for a snapshot name and create the snapshot. virsh snapshot names
     * must not contain whitespace (the client rejects them too), so the input
     * is validated before the command is sent.
     */
    private fun showCreateSnapshotDialog(vm: LibvirtVm, client: LibvirtApiClient, parentDialog: AlertDialog) {
        val form = DialogFields.form(this)
        val input = DialogFields.addText(
            form,
            hint = getString(R.string.xcpng_snapshot_name_hint),
            initial = "snapshot-${System.currentTimeMillis()}",
            monospace = true
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hypervisor_create_snapshot_title))
            .setMessage(getString(R.string.hypervisor_snapshot_name_prompt_fmt, safeText(vm.name, 64)))
            .setView(form.root)
            .setPositiveButton(getString(R.string.container_create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.hypervisor_snapshot_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.any { it.isWhitespace() }) {
                    Toast.makeText(this, getString(R.string.libvirt_snapshot_name_no_spaces), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.createSnapshot(vm.name, name) }
                        Toast.makeText(this@LibvirtManagerActivity, getString(R.string.hypervisor_snapshot_created), Toast.LENGTH_SHORT).show()
                        parentDialog.dismiss()
                        // Refresh
                        showSnapshotDialog(vm, client)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Snapshot creation error")
                        showDomainError(getString(R.string.status_error), getString(R.string.libvirt_error_create_snapshot_fmt, mapped.message))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Handle snapshot actions (revert, delete), each behind a confirmation dialog.
     */
    private fun handleSnapshotAction(
        vm: LibvirtVm,
        client: LibvirtApiClient,
        snapshot: LibvirtApiClient.LibvirtSnapshot,
        action: String,
        parentDialog: AlertDialog
    ) {
        // Both the domain name and the snapshot name come from virsh output, so
        // clamp them before they are interpolated into a dialog body or a log line.
        val vmLabel = safeText(vm.name, 64)
        val snapLabel = safeText(snapshot.name, 64)
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.hypervisor_revert_snapshot_title))
                    .setMessage(getString(R.string.hypervisor_revert_snapshot_message_fmt, vmLabel, snapLabel))
                    .setPositiveButton(getString(R.string.hypervisor_revert_button)) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { client.revertSnapshot(vm.name, snapshot.name) }
                                Toast.makeText(this@LibvirtManagerActivity, getString(R.string.hypervisor_vm_reverted), Toast.LENGTH_SHORT).show()
                                parentDialog.dismiss()
                                // Reverting can change the domain's power state — refresh the list
                                loadDomains(client)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Revert error for $vmLabel")
                                showDomainError(getString(R.string.status_error), getString(R.string.libvirt_error_revert_fmt, mapped.message))
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
                                withContext(Dispatchers.IO) { client.deleteSnapshot(vm.name, snapshot.name) }
                                Toast.makeText(this@LibvirtManagerActivity, getString(R.string.hypervisor_snapshot_deleted), Toast.LENGTH_SHORT).show()
                                parentDialog.dismiss()
                                // Refresh
                                showSnapshotDialog(vm, client)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val mapped = ThrowableMapper.map(this@LibvirtManagerActivity, TAG, e, "Snapshot delete error for $vmLabel")
                                showDomainError(getString(R.string.status_error), getString(R.string.libvirt_error_delete_snapshot_fmt, mapped.message))
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
    }

    /**
     * Snapshot adapter for the snapshot dialog's RecyclerView.
     */
    private inner class SnapshotAdapter(
        private val snapshots: List<LibvirtApiClient.LibvirtSnapshot>,
        private val onAction: (LibvirtApiClient.LibvirtSnapshot, String) -> Unit
    ) : RecyclerView.Adapter<SnapshotAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.snapshot_name)
            val time: TextView = view.findViewById(R.id.snapshot_time)
            val revertButton: Button = view.findViewById(R.id.revert_button)
            val deleteButton: Button = view.findViewById(R.id.delete_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_snapshot, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = snapshots.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val snapshot = snapshots[position]

            holder.name.text = safeText(snapshot.name, 64)
            holder.time.text = getString(R.string.libvirt_snapshot_created_fmt, safeText(snapshot.creationTime, 40), safeText(snapshot.state, 24))

            holder.revertButton.setOnClickListener { onAction(snapshot, "revert") }
            holder.deleteButton.setOnClickListener { onAction(snapshot, "delete") }
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class VmAdapter(
        private val items: List<LibvirtVm>
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

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vm = items[position]
            val client = apiClient ?: return

            holder.name.text = safeText(vm.name, 64)
            holder.state.text = stateLabel(vm.state)
            holder.state.setTextColor(stateColor(vm.state))
            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(vm.state))
            holder.info.text = if (vm.id >= 0) getString(R.string.libvirt_vm_id_fmt, vm.id) else getString(R.string.libvirt_vm_id_unknown)
            holder.ip.visibility = View.GONE

            holder.btnStop.text = getString(R.string.libvirt_shutdown_button)

            // Button visibility by domain state
            when (vm.state) {
                "running" -> {
                    holder.btnConsole.visibility = View.VISIBLE
                    holder.btnSsh.visibility = View.VISIBLE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.VISIBLE
                    holder.btnReset.visibility = View.VISIBLE
                }
                "shut off" -> {
                    holder.btnConsole.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.GONE
                    holder.btnReboot.visibility = View.GONE
                    holder.btnReset.visibility = View.GONE
                }
                "paused" -> {
                    holder.btnConsole.visibility = View.GONE
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.VISIBLE
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

            // Long-press opens the snapshot management dialog for this domain
            holder.itemView.setOnLongClickListener { showSnapshotDialog(vm, client); true }

            holder.btnConsole.setOnClickListener { openConsole(vm, client) }
            holder.btnSsh.setOnClickListener { directSshToVm(vm, client) }
            holder.btnStart.setOnClickListener { powerAction(vm, client, "start") }
            holder.btnStop.setOnClickListener { powerAction(vm, client, "stop") }
            holder.btnReboot.setOnClickListener { powerAction(vm, client, "reboot") }
            holder.btnReset.setOnClickListener { confirmHardReset(vm, client) }
        }

        private fun stateColor(state: String): Int = when (state) {
            "running"    -> androidx.core.content.ContextCompat.getColor(this@LibvirtManagerActivity, R.color.status_success)
            "shut off"   -> androidx.core.content.ContextCompat.getColor(this@LibvirtManagerActivity, R.color.status_error)
            "paused"     -> androidx.core.content.ContextCompat.getColor(this@LibvirtManagerActivity, R.color.status_warning)
            "restarting" -> androidx.core.content.ContextCompat.getColor(this@LibvirtManagerActivity, R.color.status_warning)
            else         -> androidx.core.content.ContextCompat.getColor(this@LibvirtManagerActivity, R.color.status_neutral)
        }

        private fun stateLabel(state: String): String = when (state) {
            "running"    -> getString(R.string.vm_state_running)
            "shut off"   -> getString(R.string.vm_state_stopped)
            "paused"     -> getString(R.string.vm_state_paused)
            "restarting" -> getString(R.string.vm_state_restarting)
            else         -> safeText(state, 24).replaceFirstChar { it.uppercase() }
        }
    }
}
