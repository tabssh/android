package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.hypervisor.oci.OciApiClient
import io.github.tabssh.hypervisor.oci.OciInstance
import io.github.tabssh.hypervisor.oci.OciInstanceAction
import io.github.tabssh.hypervisor.oci.OciKeyMaterial
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.SystemGroupHelper
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.replaceAllWithDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * Displays the list of OCI Compute instances for a single tenancy. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 *
 * Auth happens per-request via the HTTP signature in [OciApiClient]; a
 * [validateCredentials] ping surfaces configuration errors up-front.
 * There is no separate "Authenticate" call unlike Proxmox/VMware.
 */
class OciManagerActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "OciManager"
        const val EXTRA_HYPERVISOR_ID = "hypervisor_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: Toolbar
    private lateinit var btnRefresh: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private val instances = mutableListOf<OciInstance>()
    private var currentClient: OciApiClient? = null
    private var currentProfile: HypervisorProfile? = null
    private lateinit var adapter: InstanceAdapter

    // Retained for showError's tap-to-retry — re-runs the connect that failed.
    private var currentHypervisorId: Long = -1L

    // The refresh button and every per-row action button stay tappable while
    // their HTTP call is in flight; without these a double tap issues the
    // instance action twice against the same OCID.
    private var refreshing = false
    private var actionInFlight = false

    /** False once the activity is tearing down — dialogs must not be shown then. */
    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oci_manager)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.oci_manager_title)

        adapter = InstanceAdapter(instances)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refreshInstances() }

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
        try { currentClient?.cancelAll() } catch (e: Exception) { Logger.w(TAG, "cancelAll: ${e.message}") }
        super.onDestroy()
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectAndRefresh(hypervisorId: Long) {
        lifecycleScope.launch {
            showProgress(getString(R.string.oci_loading_credentials))
            val profile = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getById(hypervisorId)
            }
            if (profile == null) {
                showError(getString(R.string.hypervisor_error_not_found_fmt, hypervisorId))
                return@launch
            }
            currentProfile = profile
            supportActionBar?.title = profile.name

            try {
                // Resolve OCI fields: prefer linked account, fall back to profile columns.
                val accountId = profile.accountId
                val account = if (accountId != null) {
                    withContext(Dispatchers.IO) {
                        try { app.database.hypervisorAccountDao().getById(accountId) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) {
                            Logger.w(TAG, "account load failed: ${e.message}"); null
                        }
                    }
                } else null

                val tenancy     = account?.ociTenancyOcid  ?: profile.ociTenancyOcid
                val user        = account?.ociUserOcid      ?: profile.ociUserOcid
                val region      = account?.ociRegion        ?: profile.ociRegion
                val fingerprint = account?.ociFingerprint   ?: profile.ociFingerprint

                // OCI API keys are account-scoped only. A profile with no
                // account link has no key to authenticate with — the user
                // must attach a VM credential in the profile's edit screen.
                if (accountId == null) {
                    showError(getString(R.string.oci_error_no_private_key))
                    return@launch
                }
                val pem = withContext(Dispatchers.IO) {
                    HypervisorPasswordStore.retrieveOciAccountKey(applicationContext, accountId)
                }
                if (pem.isNullOrBlank()) {
                    showError(getString(R.string.oci_error_no_private_key))
                    return@launch
                }
                val passphrase = withContext(Dispatchers.IO) {
                    HypervisorPasswordStore.retrieveOciAccountPassphrase(applicationContext, accountId)
                        ?.takeIf { it.isNotEmpty() }
                }
                if (tenancy.isNullOrBlank() || user.isNullOrBlank() ||
                    region.isNullOrBlank() || fingerprint.isNullOrBlank()
                ) {
                    showError(getString(R.string.oci_error_missing_fields))
                    return@launch
                }

                val km = withContext(Dispatchers.Default) {
                    OciKeyMaterial.fromPem(pem, passphrase?.toCharArray())
                }
                val client = OciApiClient(
                    tenancyOcid = tenancy,
                    userOcid = user,
                    fingerprint = fingerprint,
                    region = region,
                    keyMaterial = km,
                    verifySsl = profile.verifySsl,
                    pinnedCertSha256 = profile.pinnedCertSha256
                )

                showProgress(getString(R.string.oci_validating))
                val ok = client.validateCredentials()
                if (!ok) {
                    showError(getString(R.string.oci_error_rejected))
                    return@launch
                }

                app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())

                // Persist the identity-endpoint TLS pin captured during validateCredentials().
                // The iaas-endpoint pin is persisted separately in loadInstances() after the
                // first listInstances() call completes — OCI uses two distinct hostnames and
                // two separate TLS leaf certs.
                persistCapturedPins(client)

                currentClient = client
                refreshInstances()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Connect failed", e)
                // e.message can carry an OCI-supplied error body — sanitize it
                // before it reaches the status line.
                showError(
                    getString(R.string.oci_connect_failed_fmt, profile.name, ContainerText.display(e.message))
                )
            }
        }
    }

    private fun refreshInstances() {
        val client = currentClient ?: run {
            Toast.makeText(this, getString(R.string.hypervisor_not_connected_wait), Toast.LENGTH_SHORT).show()
            return
        }
        if (refreshing) return
        val profile = currentProfile ?: return
        val compartment = profile.ociCompartmentOcid?.takeIf { it.isNotBlank() }
            ?: profile.ociTenancyOcid
            ?: run {
                showError(getString(R.string.oci_error_no_compartment))
                return
            }
        refreshing = true
        lifecycleScope.launch {
            try {
                loadInstances(client, compartment)
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun loadInstances(client: OciApiClient, compartment: String) {
        showProgress(getString(R.string.oci_loading_instances))
        try {
            val raw = client.listInstances(compartment)
            // Walk VNICs for IPs on running instances (one extra HTTP call per instance).
            val withIps = raw.map { inst ->
                if (inst.lifecycleState.equals("RUNNING", ignoreCase = true)) {
                    val (pub, priv) = try {
                        client.getInstancePublicIp(inst.id, compartment)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.d(TAG, "VNIC walk failed for ${inst.id}: ${e.message}")
                        null to null
                    }
                    inst.copy(publicIp = pub, privateIp = priv)
                } else inst
            }
            if (!isAlive) return
            adapter.replaceAllWithDiff(
                items = instances,
                newItems = withIps,
                areItemsTheSame = { a, b -> a.id == b.id }
            )
            hideProgress()
            if (instances.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.oci_no_instances)
            }
            // Persist the iaas-endpoint TLS pin that was captured during listInstances().
            // OCI uses a separate leaf cert for iaas.* vs identity.*; without this call
            // the user would see a TOFU prompt every time the instance list loads.
            persistCapturedPins(client)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "loadInstances failed", e)
            showError(getString(R.string.oci_error_load_instances_fmt, ContainerText.display(e.message)))
        }
    }

    /**
     * Persist any TLS cert SHAs captured since the client was built.
     * getCapturedCertSha256() returns the full semicolon-delimited set of
     * pinned SHAs (existing + newly captured) so incremental calls are safe
     * and idempotent — only writes to DB when the value has actually changed.
     */
    private suspend fun persistCapturedPins(client: OciApiClient) {
        val profile = currentProfile ?: return
        val combined = client.getCapturedCertSha256() ?: return
        HypervisorPasswordStore.persistCapturedPinIfAny(this, profile, combined)
        if (!combined.equals(profile.pinnedCertSha256, ignoreCase = true)) {
            currentProfile = profile.copy(pinnedCertSha256 = combined)
        }
    }

    // ── Instance actions ──────────────────────────────────────────────────────

    /**
     * Confirm before any action that interrupts a running instance. START is
     * additive and runs straight through; STOP/SOFTSTOP/RESET/SOFTRESET take
     * the workload down and are gated behind an explicit prompt.
     */
    private fun confirmInstanceAction(
        inst: OciInstance,
        client: OciApiClient,
        action: OciInstanceAction
    ) {
        if (action == OciInstanceAction.START) {
            instanceAction(inst, client, action)
            return
        }
        val name = ContainerText.display(inst.displayName)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.oci_confirm_action_title_fmt, action.wireValue))
            .setMessage(getString(R.string.oci_confirm_action_message_fmt, action.wireValue, name))
            .setPositiveButton(action.wireValue) { _, _ ->
                instanceAction(inst, client, action)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun instanceAction(inst: OciInstance, client: OciApiClient, action: OciInstanceAction) {
        if (actionInFlight) return
        actionInFlight = true
        // displayName is tenancy-supplied and is echoed into the status line
        // and a toast — sanitize before it reaches either.
        val name = ContainerText.display(inst.displayName)
        lifecycleScope.launch {
            showProgress(getString(R.string.oci_action_progress_fmt, action.wireValue, name))
            try {
                val ok = client.instanceAction(inst.id, action)
                if (!isAlive) return@launch
                if (ok) {
                    Toast.makeText(
                        this@OciManagerActivity,
                        getString(R.string.oci_action_sent_fmt, action.wireValue, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    delay(2000)
                    val profile = currentProfile ?: return@launch
                    val compartment = profile.ociCompartmentOcid?.takeIf { it.isNotBlank() }
                        ?: profile.ociTenancyOcid ?: return@launch
                    loadInstances(client, compartment)
                } else {
                    hideProgress()
                    showError(getString(R.string.xcpng_action_failed_for_fmt, action.wireValue, name))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Action ${action.wireValue} failed", e)
                showError(getString(R.string.oci_error_action_failed_fmt, ContainerText.display(e.message)))
            } finally {
                actionInFlight = false
            }
        }
    }

    /**
     * SSH Connect — shows a persistent configuration dialog pre-filled with any
     * previously saved SSH settings for this OCI instance. Supports password and
     * key-based auth. The connection profile is saved so settings persist across
     * taps on the same instance.
     */
    private fun handleSshConnect(inst: OciInstance) {
        val publicIp = inst.publicIp?.trim()
        if (publicIp.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.oci_error_no_public_ip), Toast.LENGTH_SHORT).show()
            return
        }
        // The address comes back from the VNIC API and becomes the host of a
        // saved connection profile — reject anything that is not a bare
        // host literal before it reaches the SSH layer.
        if (!isValidHostLiteral(publicIp)) {
            Toast.makeText(this, getString(R.string.oci_error_unusable_address), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                app.database.connectionDao().getByOciInstanceId(inst.id)
            }
            val storedKeys = withContext(Dispatchers.IO) {
                app.database.keyDao().getAllKeysList()
            }
            if (!isAlive) return@launch
            showSshConfigDialog(inst, publicIp, existing, storedKeys)
        }
    }

    /**
     * True when [host] is a plausible IPv4/IPv6/hostname literal: no spaces,
     * no control characters, no scheme or path separators, and within the
     * DNS name length limit.
     */
    private fun isValidHostLiteral(host: String): Boolean {
        if (host.isEmpty() || host.length > 255) return false
        return host.all { ch ->
            ch.isLetterOrDigit() || ch == '.' || ch == ':' || ch == '-' || ch == '_'
        }
    }

    private fun showSshConfigDialog(
        inst: OciInstance,
        publicIp: String,
        existing: ConnectionProfile?,
        storedKeys: List<StoredKey>
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_oci_ssh_config, null)
        // Tenancy-supplied name — rendered in the dialog title and stored as
        // the profile name, so sanitize it once here.
        val instanceName = ContainerText.display(inst.displayName)

        val instanceLabel = view.findViewById<TextView>(R.id.oci_ssh_instance_label)
        val usernameField = view.findViewById<TextInputEditText>(R.id.oci_ssh_username)
        val portField = view.findViewById<TextInputEditText>(R.id.oci_ssh_port)
        val authSpinner = view.findViewById<Spinner>(R.id.oci_ssh_auth_method)
        val keyLabel = view.findViewById<TextView>(R.id.oci_ssh_key_label)
        val keySpinner = view.findViewById<Spinner>(R.id.oci_ssh_key_spinner)
        val noKeysHint = view.findViewById<TextView>(R.id.oci_ssh_no_keys_hint)

        instanceLabel.text = getString(R.string.oci_ssh_to_fmt, publicIp)
        usernameField.setText(existing?.username ?: "opc")
        portField.setText((existing?.port ?: 22).toString())

        val authOptions = listOf(AuthType.PASSWORD, AuthType.PUBLIC_KEY)
        val authAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            authOptions.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        authSpinner.adapter = authAdapter

        val savedAuth = existing?.getAuthTypeEnum() ?: AuthType.PASSWORD
        authSpinner.setSelection(authOptions.indexOf(savedAuth).coerceAtLeast(0))

        val keyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            storedKeys.map { "${it.name} (${it.keyType})" }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        keySpinner.adapter = keyAdapter

        val savedKeyIndex = existing?.keyId
            ?.let { id -> storedKeys.indexOfFirst { it.keyId == id } }
            ?.takeIf { it >= 0 } ?: 0
        if (storedKeys.isNotEmpty()) keySpinner.setSelection(savedKeyIndex)

        fun updateKeyVisibility(authType: AuthType) {
            val needsKey = authType == AuthType.PUBLIC_KEY
            keyLabel.visibility = if (needsKey) View.VISIBLE else View.GONE
            if (needsKey && storedKeys.isEmpty()) {
                keySpinner.visibility = View.GONE
                noKeysHint.visibility = View.VISIBLE
            } else {
                keySpinner.visibility = if (needsKey) View.VISIBLE else View.GONE
                noKeysHint.visibility = View.GONE
            }
        }
        updateKeyVisibility(savedAuth)

        authSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateKeyVisibility(authOptions[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.oci_ssh_dialog_title_fmt, instanceName))
            .setView(view)
            .setPositiveButton(getString(R.string.connect_button)) { _, _ ->
                val username = usernameField.text.toString().trim().ifBlank { "opc" }
                val port = portField.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 22
                val authType = authOptions[authSpinner.selectedItemPosition]
                val keyId = if (authType == AuthType.PUBLIC_KEY && storedKeys.isNotEmpty()) {
                    storedKeys[keySpinner.selectedItemPosition].keyId
                } else null

                val profile = (existing ?: ConnectionProfile(
                    name = "OCI: $instanceName",
                    host = publicIp,
                    username = username,
                    ociInstanceId = inst.id
                )).copy(
                    name = "OCI: $instanceName",
                    host = publicIp,
                    port = port,
                    username = username,
                    authType = authType.name,
                    keyId = keyId,
                    ociInstanceId = inst.id,
                    modifiedAt = System.currentTimeMillis()
                )

                lifecycleScope.launch {
                    val cloudGroupId = withContext(Dispatchers.IO) {
                        SystemGroupHelper.getOrCreateSystemGroupId(
                            app.database, "cloud", "Cloud Instances", "cloud"
                        )
                    }
                    withContext(Dispatchers.IO) {
                        app.database.connectionDao().insertConnection(
                            profile.copy(groupId = cloudGroupId)
                        )
                    }
                    Logger.i(TAG, "Launching SSH to $username@$publicIp (auth=${authType.name}, key=$keyId) for $instanceName")
                    if (!isAlive) return@launch
                    val intent = TabTerminalActivity.createIntent(this@OciManagerActivity, profile, autoConnect = true)
                    startActivity(intent)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(message: String) {
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = message
            resetStatusTextStyle()
        }
    }

    private fun hideProgress() {
        runOnUiThread {
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

    private inner class InstanceAdapter(
        private val items: List<OciInstance>
    ) : RecyclerView.Adapter<InstanceAdapter.VH>() {

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
            val inst = items[position]
            val client = currentClient ?: return

            // Display name, shape, AD, IPs and lifecycle state all come straight
            // from the tenancy API — sanitize before they reach a row widget.
            holder.name.text = ContainerText.display(inst.displayName)
            holder.state.text = ContainerText.display(stateLabel(inst.lifecycleState))
            holder.state.setTextColor(stateColor(inst.lifecycleState))
            holder.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor(inst.lifecycleState))
            holder.info.text = ContainerText.display(getString(R.string.oci_instance_info_fmt, inst.shape, inst.availabilityDomain))

            val ipParts = mutableListOf<String>()
            inst.publicIp?.let { ipParts += getString(R.string.oci_ip_public_fmt, ContainerText.display(it)) }
            inst.privateIp?.let { ipParts += getString(R.string.oci_ip_private_fmt, ContainerText.display(it)) }
            if (ipParts.isNotEmpty()) {
                holder.ip.text = ipParts.joinToString(getString(R.string.oci_list_separator))
                holder.ip.visibility = View.VISIBLE
            } else {
                holder.ip.visibility = View.GONE
            }

            // OCI has no reset action — btn_reset always gone
            holder.btnConsole.visibility = View.GONE
            holder.btnReset.visibility = View.GONE

            when (inst.lifecycleState.uppercase()) {
                "RUNNING" -> {
                    val hasIp = !inst.publicIp.isNullOrBlank()
                    holder.btnSsh.visibility = if (hasIp) View.VISIBLE else View.GONE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.VISIBLE
                    holder.btnReboot.visibility = View.VISIBLE
                }
                "STOPPED" -> {
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.VISIBLE
                    holder.btnStop.visibility = View.GONE
                    holder.btnReboot.visibility = View.GONE
                }
                else -> {
                    // Transitional state — operation in progress; hide all
                    holder.btnSsh.visibility = View.GONE
                    holder.btnStart.visibility = View.GONE
                    holder.btnStop.visibility = View.GONE
                    holder.btnReboot.visibility = View.GONE
                }
            }

            holder.rowConnect.visibility = if (holder.btnConsole.visibility == View.VISIBLE || holder.btnSsh.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowMain.visibility = if (holder.btnStart.visibility == View.VISIBLE || holder.btnStop.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            holder.rowSecondary.visibility = if (holder.btnReboot.visibility == View.VISIBLE || holder.btnReset.visibility == View.VISIBLE) View.VISIBLE else View.GONE

            holder.btnSsh.setOnClickListener { handleSshConnect(inst) }
            holder.btnStart.setOnClickListener { confirmInstanceAction(inst, client, OciInstanceAction.START) }
            holder.btnStop.setOnClickListener { confirmInstanceAction(inst, client, OciInstanceAction.SOFTSTOP) }
            holder.btnReboot.setOnClickListener { confirmInstanceAction(inst, client, OciInstanceAction.SOFTRESET) }
        }

        private fun stateColor(state: String): Int = when (state.uppercase()) {
            "RUNNING"              -> androidx.core.content.ContextCompat.getColor(this@OciManagerActivity, R.color.status_success)
            "STOPPED"              -> androidx.core.content.ContextCompat.getColor(this@OciManagerActivity, R.color.status_error)
            "STARTING", "REBOOTING" -> androidx.core.content.ContextCompat.getColor(this@OciManagerActivity, R.color.status_warning)
            "STOPPING"             -> androidx.core.content.ContextCompat.getColor(this@OciManagerActivity, R.color.status_warning)
            else                   -> androidx.core.content.ContextCompat.getColor(this@OciManagerActivity, R.color.status_neutral)
        }

        private fun stateLabel(state: String): String = when (state.uppercase()) {
            "RUNNING"   -> getString(R.string.vm_state_running)
            "STOPPED"   -> getString(R.string.vm_state_stopped)
            "STARTING"  -> getString(R.string.vm_state_restarting)
            "STOPPING"  -> getString(R.string.vm_state_stopping)
            "REBOOTING" -> getString(R.string.vm_state_restarting)
            else        -> state.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }
}
