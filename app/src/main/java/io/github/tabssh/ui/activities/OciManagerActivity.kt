package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of OCI Compute instances for a single tenancy. Launched by
 * [HypervisorsFragment] / [MainActivity] with [EXTRA_HYPERVISOR_ID] set.
 *
 * Auth happens per-request via the HTTP signature in [OciApiClient]; a
 * [validateCredentials] ping surfaces configuration errors up-front.
 * There is no separate "Authenticate" call unlike Proxmox/VMware.
 */
class OciManagerActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oci_manager)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        btnRefresh = findViewById(R.id.btn_refresh)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        recyclerView = findViewById(R.id.vm_recycler_view)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "OCI"

        adapter = InstanceAdapter(instances)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnRefresh.setOnClickListener { refreshInstances() }

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
            showProgress("Loading credentials…")
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
                // Resolve OCI fields: prefer linked account, fall back to profile columns.
                val accountId = profile.accountId
                val account = if (accountId != null) {
                    withContext(Dispatchers.IO) {
                        try { app.database.hypervisorAccountDao().getById(accountId) }
                        catch (e: Exception) {
                            Logger.w(TAG, "account load failed: ${e.message}"); null
                        }
                    }
                } else null

                val tenancy     = account?.ociTenancyOcid  ?: profile.ociTenancyOcid
                val user        = account?.ociUserOcid      ?: profile.ociUserOcid
                val region      = account?.ociRegion        ?: profile.ociRegion
                val fingerprint = account?.ociFingerprint   ?: profile.ociFingerprint

                val pem = withContext(Dispatchers.IO) {
                    if (accountId != null) {
                        HypervisorPasswordStore.retrieveOciAccountKey(applicationContext, accountId, profile.id)
                    } else {
                        app.securePasswordManager.retrievePassword("oci_private_key_${profile.id}")
                    }
                }
                if (pem.isNullOrBlank()) {
                    showError("Private key not found — edit this OCI identity to add one")
                    return@launch
                }
                val passphrase = withContext(Dispatchers.IO) {
                    if (accountId != null) {
                        HypervisorPasswordStore.retrieveOciAccountPassphrase(applicationContext, accountId, profile.id)
                            ?.takeIf { it.isNotEmpty() }
                    } else {
                        app.securePasswordManager.retrievePassword("oci_passphrase_${profile.id}")
                            ?.takeIf { it.isNotEmpty() }
                    }
                }
                if (tenancy.isNullOrBlank() || user.isNullOrBlank() ||
                    region.isNullOrBlank() || fingerprint.isNullOrBlank()
                ) {
                    showError("Profile is missing OCI fields — re-run onboarding")
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

                showProgress("Validating with OCI…")
                val ok = client.validateCredentials()
                if (!ok) {
                    showError("OCI rejected the credentials")
                    return@launch
                }

                app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())

                val capturedPin = client.getCapturedCertSha256()
                if (capturedPin != null) {
                    withContext(Dispatchers.IO) {
                        app.database.hypervisorDao().updatePinnedCertSha256(profile.id, capturedPin)
                    }
                    currentProfile = profile.copy(pinnedCertSha256 = capturedPin)
                }

                currentClient = client
                refreshInstances()
            } catch (e: Exception) {
                Logger.e(TAG, "Connect failed", e)
                showError("Connection failed: oci ${profile.name}: ${e.message}")
            }
        }
    }

    private fun refreshInstances() {
        val client = currentClient ?: run {
            Toast.makeText(this, "Not connected — please wait", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = currentProfile ?: return
        val compartment = profile.ociCompartmentOcid?.takeIf { it.isNotBlank() }
            ?: profile.ociTenancyOcid
            ?: run {
                showError("Compartment OCID not configured")
                return
            }
        lifecycleScope.launch { loadInstances(client, compartment) }
    }

    private suspend fun loadInstances(client: OciApiClient, compartment: String) {
        showProgress("Loading instances…")
        try {
            val raw = client.listInstances(compartment)
            // Walk VNICs for IPs on running instances (one extra HTTP call per instance).
            val withIps = raw.map { inst ->
                if (inst.lifecycleState.equals("RUNNING", ignoreCase = true)) {
                    val (pub, priv) = try {
                        client.getInstancePublicIp(inst.id, compartment)
                    } catch (e: Exception) {
                        Logger.d(TAG, "VNIC walk failed for ${inst.id}: ${e.message}")
                        null to null
                    }
                    inst.copy(publicIp = pub, privateIp = priv)
                } else inst
            }
            instances.clear()
            instances.addAll(withIps)
            adapter.notifyDataSetChanged()
            hideProgress()
            if (instances.isEmpty()) {
                statusText.visibility = View.VISIBLE
                statusText.text = "No instances found"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "loadInstances failed", e)
            showError("Could not load instances: ${e.message}")
        }
    }

    // ── Instance actions ──────────────────────────────────────────────────────

    private fun instanceAction(inst: OciInstance, client: OciApiClient, action: OciInstanceAction) {
        lifecycleScope.launch {
            showProgress("${action.wireValue} → ${inst.displayName}…")
            try {
                val ok = client.instanceAction(inst.id, action)
                if (ok) {
                    Toast.makeText(
                        this@OciManagerActivity,
                        "${action.wireValue} sent to ${inst.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    delay(2000)
                    val profile = currentProfile ?: return@launch
                    val compartment = profile.ociCompartmentOcid?.takeIf { it.isNotBlank() }
                        ?: profile.ociTenancyOcid ?: return@launch
                    loadInstances(client, compartment)
                } else {
                    hideProgress()
                    showError("${action.wireValue} failed for ${inst.displayName}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Action ${action.wireValue} failed", e)
                showError("Action failed: ${e.message}")
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
        val publicIp = inst.publicIp
        if (publicIp.isNullOrBlank()) {
            Toast.makeText(this, "Instance has no public IP address", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                app.database.connectionDao().getByOciInstanceId(inst.id)
            }
            val storedKeys = withContext(Dispatchers.IO) {
                app.database.keyDao().getAllKeysList()
            }
            showSshConfigDialog(inst, publicIp, existing, storedKeys)
        }
    }

    private fun showSshConfigDialog(
        inst: OciInstance,
        publicIp: String,
        existing: ConnectionProfile?,
        storedKeys: List<StoredKey>
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_oci_ssh_config, null)

        val instanceLabel = view.findViewById<TextView>(R.id.oci_ssh_instance_label)
        val usernameField = view.findViewById<TextInputEditText>(R.id.oci_ssh_username)
        val portField = view.findViewById<TextInputEditText>(R.id.oci_ssh_port)
        val authSpinner = view.findViewById<Spinner>(R.id.oci_ssh_auth_method)
        val keyLabel = view.findViewById<TextView>(R.id.oci_ssh_key_label)
        val keySpinner = view.findViewById<Spinner>(R.id.oci_ssh_key_spinner)
        val noKeysHint = view.findViewById<TextView>(R.id.oci_ssh_no_keys_hint)

        instanceLabel.text = "SSH to $publicIp"
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

        AlertDialog.Builder(this)
            .setTitle("SSH: ${inst.displayName}")
            .setView(view)
            .setPositiveButton("Connect") { _, _ ->
                val username = usernameField.text.toString().trim().ifBlank { "opc" }
                val port = portField.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 22
                val authType = authOptions[authSpinner.selectedItemPosition]
                val keyId = if (authType == AuthType.PUBLIC_KEY && storedKeys.isNotEmpty()) {
                    storedKeys[keySpinner.selectedItemPosition].keyId
                } else null

                val profile = (existing ?: ConnectionProfile(
                    name = "OCI: ${inst.displayName}",
                    host = publicIp,
                    username = username,
                    ociInstanceId = inst.id
                )).copy(
                    name = "OCI: ${inst.displayName}",
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
                    Logger.i(TAG, "Launching SSH to $username@$publicIp (auth=${authType.name}, key=$keyId) for ${inst.displayName}")
                    val intent = TabTerminalActivity.createIntent(this@OciManagerActivity, profile, autoConnect = true)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
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

    private inner class InstanceAdapter(
        private val items: List<OciInstance>
    ) : RecyclerView.Adapter<InstanceAdapter.VH>() {

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
            val inst = items[position]
            val client = currentClient ?: return

            holder.name.text = inst.displayName
            holder.state.text = stateLabel(inst.lifecycleState)
            holder.state.setTextColor(stateColor(inst.lifecycleState))
            holder.info.text = "${inst.shape}  ·  ${inst.availabilityDomain}"

            val ipParts = mutableListOf<String>()
            inst.publicIp?.let { ipParts += "Public: $it" }
            inst.privateIp?.let { ipParts += "Private: $it" }
            if (ipParts.isNotEmpty()) {
                holder.ip.text = ipParts.joinToString("  ·  ")
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

            holder.btnSsh.setOnClickListener { handleSshConnect(inst) }
            holder.btnStart.setOnClickListener { instanceAction(inst, client, OciInstanceAction.START) }
            holder.btnStop.setOnClickListener { instanceAction(inst, client, OciInstanceAction.SOFTSTOP) }
            holder.btnReboot.setOnClickListener { instanceAction(inst, client, OciInstanceAction.SOFTRESET) }
        }

        private fun stateColor(state: String): Int = when (state.uppercase()) {
            "RUNNING"              -> 0xFF4CAF50.toInt()
            "STOPPED"              -> 0xFFF44336.toInt()
            "STARTING", "REBOOTING" -> 0xFFFF5722.toInt()
            "STOPPING"             -> 0xFFFF5722.toInt()
            else                   -> 0xFF9E9E9E.toInt()
        }

        private fun stateLabel(state: String): String = when (state.uppercase()) {
            "RUNNING"   -> "Running"
            "STOPPED"   -> "Stopped"
            "STARTING"  -> "Restarting"
            "STOPPING"  -> "Stopping"
            "REBOOTING" -> "Restarting"
            else        -> state.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
    }
}
