package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.oci.OciApiClient
import io.github.tabssh.hypervisor.oci.OciInstance
import io.github.tabssh.hypervisor.oci.OciInstanceAction
import io.github.tabssh.hypervisor.oci.OciKeyMaterial
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager UI for OCI Compute instances. Mirrors `ProxmoxManagerActivity`
 * — server spinner over the list, refresh button, status text, then a
 * RecyclerView of instances with start/stop/softstop/reset/softreset
 * buttons.
 *
 * Differences from Proxmox:
 *   - No console (deferred — needs the bastion-over-SSH flow OCI uses).
 *   - Auth happens per-request via the HTTP signature in `OciApiClient`,
 *     not via an upfront `authenticate()` call. We still issue a
 *     `validateCredentials()` ping so a bad config surfaces a clear
 *     error before listing instances.
 *   - The "host" column on the row carries the region; the API client
 *     resolves the per-service hostname itself.
 *
 * No add/edit dialog here — OCI hosts are created via
 * `OciOnboardingActivity` (Path A importer). This activity is read +
 * action only.
 */
class OciManagerActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var serverSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val instances = mutableListOf<OciInstance>()
    private var currentClient: OciApiClient? = null
    private var currentProfile: HypervisorProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oci_manager)

        app = application as TabSSHApplication

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "OCI Manager"

        serverSpinner = findViewById(R.id.server_spinner)
        refreshButton = findViewById(R.id.refresh_button)
        recyclerView = findViewById(R.id.vm_recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)

        recyclerView.layoutManager = LinearLayoutManager(this)
        refreshButton.setOnClickListener { refreshInstances() }

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in 0 until hypervisors.size) connectToHypervisor(hypervisors[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadHypervisors()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun loadHypervisors() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                app.database.hypervisorDao().getByType(HypervisorType.OCI)
            }
            hypervisors.clear()
            hypervisors.addAll(rows)

            if (hypervisors.isEmpty()) {
                statusText.text = "No OCI tenancies configured"
                statusText.visibility = View.VISIBLE
                return@launch
            }
            statusText.visibility = View.GONE
            val adapter = ArrayAdapter(
                this@OciManagerActivity,
                android.R.layout.simple_spinner_item,
                hypervisors.map { "${it.name} (${it.ociRegion ?: "?"})" }
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            serverSpinner.adapter = adapter
        }
    }

    /**
     * Build an `OciApiClient` for the chosen profile. The PEM private
     * key (and optional passphrase) live in the Keystore under
     * `oci_private_key_${id}` / `oci_passphrase_${id}` — see Phase 4
     * onboarding.
     */
    private fun connectToHypervisor(profile: HypervisorProfile) {
        currentProfile = profile
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Loading credentials for ${profile.name}…"
                statusText.visibility = View.VISIBLE

                val pm = app.securePasswordManager
                val pem = withContext(Dispatchers.IO) { pm.retrievePassword("oci_private_key_${profile.id}") }
                if (pem.isNullOrBlank()) {
                    statusText.text = "Private key not found in Keystore — re-run onboarding"
                    progressBar.visibility = View.GONE
                    return@launch
                }
                val passphrase = withContext(Dispatchers.IO) {
                    pm.retrievePassword("oci_passphrase_${profile.id}")?.takeIf { it.isNotEmpty() }
                }
                val tenancy = profile.ociTenancyOcid
                val user = profile.ociUserOcid
                val region = profile.ociRegion
                val fingerprint = profile.ociFingerprint
                if (tenancy.isNullOrBlank() || user.isNullOrBlank() ||
                    region.isNullOrBlank() || fingerprint.isNullOrBlank()
                ) {
                    statusText.text = "Profile is missing OCI fields — re-run onboarding"
                    progressBar.visibility = View.GONE
                    return@launch
                }

                val km = withContext(Dispatchers.Default) {
                    OciKeyMaterial.fromPem(pem, passphrase?.toCharArray())
                }
                currentClient = OciApiClient(
                    tenancyOcid = tenancy,
                    userOcid = user,
                    fingerprint = fingerprint,
                    region = region,
                    keyMaterial = km,
                    verifySsl = profile.verifySsl,
                    pinnedCertSha256 = profile.pinnedCertSha256
                )

                statusText.text = "Validating with OCI…"
                val ok = currentClient?.validateCredentials() ?: false
                if (!ok) {
                    statusText.text = "OCI rejected the credentials"
                    progressBar.visibility = View.GONE
                    showError("Authentication failed", "Error")
                    return@launch
                }

                app.database.hypervisorDao()
                    .updateLastConnected(profile.id, System.currentTimeMillis())

                // Persist any cert pin captured during validateCredentials() so
                // subsequent API calls in this session (and future sessions) skip
                // the TOFU dialog.
                val capturedPin = currentClient?.getCapturedCertSha256()
                if (capturedPin != null) {
                    withContext(Dispatchers.IO) {
                        app.database.hypervisorDao().updatePinnedCertSha256(profile.id, capturedPin)
                    }
                    currentProfile = currentProfile?.copy(pinnedCertSha256 = capturedPin)
                }

                statusText.text = "Connected to ${profile.name}"
                refreshInstances()
            } catch (e: Exception) {
                Logger.e("OciManager", "Connection failed", e)
                statusText.text = "Connection error: ${e.message}"
                progressBar.visibility = View.GONE
                showError("Connection failed: ${e.message}", "Error")
            }
        }
    }

    private fun refreshInstances() {
        val client = currentClient ?: return
        val profile = currentProfile ?: return
        val compartment = profile.ociCompartmentOcid?.takeIf { it.isNotBlank() }
            ?: profile.ociTenancyOcid
            ?: return
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Loading instances…"

                val raw = client.listInstances(compartment)

                // Walk VNICs for IPs in parallel (cheap — one extra HTTP per
                // running instance). Don't block on stopped instances since
                // they have no live VNIC.
                val withIps = raw.map { inst ->
                    if (inst.lifecycleState.equals("RUNNING", ignoreCase = true)) {
                        val (pub, priv) = try {
                            client.getInstancePublicIp(inst.id, compartment)
                        } catch (e: Exception) {
                            Logger.d("OciManager", "VNIC walk failed for ${inst.id}: ${e.message}")
                            null to null
                        }
                        inst.copy(publicIp = pub, privateIp = priv)
                    } else inst
                }

                instances.clear()
                instances.addAll(withIps)
                recyclerView.adapter = InstanceAdapter(
                    instances,
                    onAction = { inst, action -> handleAction(inst, action) },
                    onSshConnect = { inst -> handleSshConnect(inst) }
                )
                statusText.text = "Found ${instances.size} instance${if (instances.size == 1) "" else "s"}"
                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                Logger.e("OciManager", "Failed to load instances", e)
                statusText.text = "Error loading instances"
                progressBar.visibility = View.GONE
                showError("Failed to load instances: ${e.message}", "Error")
            }
        }
    }

    private fun handleAction(inst: OciInstance, action: OciInstanceAction) {
        val client = currentClient ?: return
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                val ok = client.instanceAction(inst.id, action)
                if (ok) {
                    Toast.makeText(
                        this@OciManagerActivity,
                        "${action.wireValue} sent to ${inst.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Give OCI a couple of seconds to flip lifecycleState.
                    kotlinx.coroutines.delay(2000)
                    refreshInstances()
                } else {
                    showError("${action.wireValue} failed for ${inst.displayName}", "Error")
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                Logger.e("OciManager", "Action ${action.wireValue} failed", e)
                showError("Action failed: ${e.message}", "Error")
                progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * SSH Connect — prompted with a username dialog (defaults to "opc",
     * the standard OCI Oracle Linux user; Ubuntu images use "ubuntu").
     * Creates a temporary ConnectionProfile from the instance's public IP
     * and opens TabTerminalActivity, mirroring the quick-connect flow.
     */
    private fun handleSshConnect(inst: OciInstance) {
        val publicIp = inst.publicIp
        if (publicIp.isNullOrBlank()) {
            Toast.makeText(this, "Instance has no public IP address", Toast.LENGTH_SHORT).show()
            return
        }

        val usernameInput = EditText(this).apply {
            hint = "SSH username"
            setText("opc")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to ${inst.displayName}")
            .setMessage("SSH to $publicIp")
            .setView(usernameInput)
            .setPositiveButton("Connect") { _, _ ->
                val username = usernameInput.text.toString().trim().ifBlank { "opc" }
                val profile = ConnectionProfile(
                    name = "OCI: ${inst.displayName}",
                    host = publicIp,
                    port = 22,
                    username = username
                )
                Logger.i("OciManager", "Launching SSH to $username@$publicIp for ${inst.displayName}")
                val intent = TabTerminalActivity.createIntent(this, profile, autoConnect = true)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Adapter -----------------------------------------------

    private class InstanceAdapter(
        private val items: List<OciInstance>,
        private val onAction: (OciInstance, OciInstanceAction) -> Unit,
        private val onSshConnect: (OciInstance) -> Unit
    ) : RecyclerView.Adapter<InstanceAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.instance_name)
            val state: TextView = view.findViewById(R.id.instance_state)
            val info: TextView = view.findViewById(R.id.instance_info)
            val ip: TextView = view.findViewById(R.id.instance_ip)
            val start: Button = view.findViewById(R.id.start_button)
            val stop: Button = view.findViewById(R.id.stop_button)
            val softStop: Button = view.findViewById(R.id.softstop_button)
            val reset: Button = view.findViewById(R.id.reset_button)
            val softReset: Button = view.findViewById(R.id.softreset_button)
            val sshConnect: Button = view.findViewById(R.id.ssh_connect_button)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_oci_instance, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val inst = items[position]
            holder.name.text = inst.displayName
            holder.state.text = inst.lifecycleState
            holder.info.text = "${inst.shape} · ${inst.availabilityDomain}"

            val ipParts = mutableListOf<String>()
            inst.publicIp?.let { ipParts += "Public: $it" }
            inst.privateIp?.let { ipParts += "Private: $it" }
            if (ipParts.isNotEmpty()) {
                holder.ip.visibility = View.VISIBLE
                holder.ip.text = ipParts.joinToString(" · ")
            } else holder.ip.visibility = View.GONE

            holder.state.setTextColor(
                when (inst.lifecycleState.uppercase()) {
                    "RUNNING" -> 0xFF4CAF50.toInt()
                    "STOPPED", "TERMINATED" -> 0xFFF44336.toInt()
                    else -> 0xFFFF9800.toInt()
                }
            )

            // Show/hide buttons by state — same UX rule as ProxmoxAdapter.
            when (inst.lifecycleState.uppercase()) {
                "RUNNING" -> {
                    holder.start.visibility = View.GONE
                    holder.stop.visibility = View.VISIBLE
                    holder.softStop.visibility = View.VISIBLE
                    holder.reset.visibility = View.VISIBLE
                    holder.softReset.visibility = View.VISIBLE
                }
                "STOPPED", "TERMINATED" -> {
                    holder.start.visibility = View.VISIBLE
                    holder.stop.visibility = View.GONE
                    holder.softStop.visibility = View.GONE
                    holder.reset.visibility = View.GONE
                    holder.softReset.visibility = View.GONE
                }
                else -> {
                    holder.start.visibility = View.VISIBLE
                    holder.stop.visibility = View.VISIBLE
                    holder.softStop.visibility = View.GONE
                    holder.reset.visibility = View.GONE
                    holder.softReset.visibility = View.GONE
                }
            }

            holder.start.setOnClickListener { onAction(inst, OciInstanceAction.START) }
            holder.stop.setOnClickListener { onAction(inst, OciInstanceAction.STOP) }
            holder.softStop.setOnClickListener { onAction(inst, OciInstanceAction.SOFTSTOP) }
            holder.reset.setOnClickListener { onAction(inst, OciInstanceAction.RESET) }
            holder.softReset.setOnClickListener { onAction(inst, OciInstanceAction.SOFTRESET) }

            // SSH Connect — only meaningful for RUNNING instances that have
            // a resolved public IP. Hidden when the instance is stopped or
            // the VNIC walk didn't find a public address.
            val isRunning = inst.lifecycleState.equals("RUNNING", ignoreCase = true)
            if (isRunning && !inst.publicIp.isNullOrBlank()) {
                holder.sshConnect.visibility = View.VISIBLE
                holder.sshConnect.setOnClickListener { onSshConnect(inst) }
            } else {
                holder.sshConnect.visibility = View.GONE
            }
        }
    }
}
