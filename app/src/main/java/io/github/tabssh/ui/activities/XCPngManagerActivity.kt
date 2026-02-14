package io.github.tabssh.ui.activities
import io.github.tabssh.utils.logging.Logger

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import kotlinx.coroutines.launch
import io.github.tabssh.utils.showError

class XCPngManagerActivity : AppCompatActivity() {
    
    private lateinit var app: TabSSHApplication
    
    // UI Components
    private lateinit var serverSpinner: Spinner
    private lateinit var vmRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var addServerButton: Button
    private lateinit var refreshButton: Button
    private lateinit var backupJobsButton: Button
    private lateinit var liveIndicator: View
    private lateinit var liveText: TextView
    
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val vms = mutableListOf<XCPngApiClient.XenVM>()
    private var currentClient: XCPngApiClient? = null
    private var currentXoClient: XenOrchestraApiClient? = null
    private var isXenOrchestra: Boolean = false

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
        supportActionBar?.title = "XCP-ng Manager"
    }

    private fun setupViews() {
        serverSpinner = findViewById(R.id.server_spinner)
        refreshButton = findViewById(R.id.refresh_button)
        addServerButton = findViewById(R.id.add_server_button)
        backupJobsButton = findViewById(R.id.backup_jobs_button)
        vmRecyclerView = findViewById(R.id.vm_recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        liveIndicator = findViewById(R.id.live_indicator)
        liveText = findViewById(R.id.live_text)
        
        vmRecyclerView.layoutManager = LinearLayoutManager(this)
        
        refreshButton.setOnClickListener { refreshVMs() }
        addServerButton.setOnClickListener { showAddServerDialog() }
        backupJobsButton.setOnClickListener { showBackupJobsDialog() }
        
        // Hide live indicator and backup button initially
        liveIndicator.visibility = View.GONE
        liveText.visibility = View.GONE
        backupJobsButton.visibility = View.GONE
        
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
            app.database.hypervisorDao().getByType(HypervisorType.XCPNG).let { servers ->
                hypervisors.clear()
                hypervisors.addAll(servers)
                
                if (hypervisors.isEmpty()) {
                    statusText.text = "No XCP-ng servers configured"
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
            }
        }
    }

    private fun connectToHypervisor(profile: HypervisorProfile) {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Connecting to ${profile.name}..."
                statusText.visibility = View.VISIBLE
                
                isXenOrchestra = profile.isXenOrchestra
                
                Logger.d("XCPngManager", "Connecting to ${profile.host}:${profile.port} as ${profile.username} (XO=${profile.isXenOrchestra})")
                
                val authenticated = if (profile.isXenOrchestra) {
                    // Use Xen Orchestra REST API
                    currentClient = null
                    currentXoClient = XenOrchestraApiClient(
                        host = profile.host,
                        port = profile.port,
                        email = profile.username,
                        password = profile.password,
                        verifySsl = profile.verifySsl
                    )
                    currentXoClient?.authenticate() ?: false
                } else {
                    // Use XCP-ng XML-RPC API (direct)
                    currentXoClient = null
                    currentClient = XCPngApiClient(
                        host = profile.host,
                        port = profile.port,
                        username = profile.username,
                        password = profile.password,
                        verifySsl = profile.verifySsl
                    )
                    currentClient?.authenticate() ?: false
                }
                
                if (authenticated) {
                    val modeText = if (profile.isXenOrchestra) "Xen Orchestra" else "XCP-ng Direct"
                    statusText.text = "Connected to ${profile.name} ($modeText)"
                    app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                    
                    // Show backup jobs button for XO
                    if (profile.isXenOrchestra) {
                        backupJobsButton.visibility = View.VISIBLE
                    } else {
                        backupJobsButton.visibility = View.GONE
                    }
                    
                    refreshVMs()
                    
                    // Connect WebSocket for Xen Orchestra (real-time updates)
                    if (profile.isXenOrchestra) {
                        setupWebSocket()
                    }
                } else {
                    statusText.text = "Authentication failed - check credentials and network"
                    val apiType = if (profile.isXenOrchestra) "Xen Orchestra REST API" else "XCP-ng XML-RPC API"
                    showError("Failed to authenticate with $apiType. Check:\n• Username/password\n• Host/port (${profile.host}:${profile.port})\n• Network connectivity\n• SSL certificate (verify SSL: ${profile.verifySsl})", "Error")
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: java.net.UnknownHostException) {
                Logger.e("XCPngManager", "Unknown host: ${profile.host}", e)
                statusText.text = "Error: Host not found (${profile.host})"
                showError("Cannot resolve hostname: ${profile.host}", "Error")
                progressBar.visibility = View.GONE
            } catch (e: java.net.ConnectException) {
                Logger.e("XCPngManager", "Connection refused", e)
                statusText.text = "Error: Connection refused"
                val apiType = if (profile.isXenOrchestra) "Xen Orchestra" else "XCP-ng"
                Toast.makeText(this@XCPngManagerActivity, "Connection refused. Check:\n• Port ${profile.port} is correct\n• $apiType API is accessible\n• Firewall allows connection", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                Logger.e("XCPngManager", "SSL handshake failed", e)
                statusText.text = "Error: SSL certificate issue"
                showError("SSL certificate verification failed. Try disabling 'Verify SSL' in hypervisor settings.", "Error")
                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                Logger.e("XCPngManager", "Connection failed", e)
                statusText.text = "Connection error: ${e.message}"
                showError("Error: ${e.message}", "Error")
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun refreshVMs() {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Loading VMs..."
                
                val vmList = if (isXenOrchestra) {
                    // Get VMs from Xen Orchestra REST API
                    currentXoClient?.listVMs()?.map { convertXoVMToXenVM(it) } ?: emptyList()
                } else {
                    // Get VMs from XCP-ng XML-RPC API
                    currentClient?.getAllVMs() ?: emptyList()
                }
                
                vms.clear()
                vms.addAll(vmList)
                
                vmRecyclerView.adapter = VMAdapter(vms) { vm, action ->
                    handleVMAction(vm, action)
                }
                
                statusText.text = "Found ${vms.size} VMs"
                progressBar.visibility = View.GONE
                
            } catch (e: Exception) {
                Logger.e("XCPngManager", "Failed to load VMs", e)
                statusText.text = "Error loading VMs"
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
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                
                when (action) {
                    "console" -> {
                        openVMConsole(vm)
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
                    Toast.makeText(this@XCPngManagerActivity, "VM $action successful", Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.delay(2000)
                    refreshVMs()
                } else {
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: Exception) {
                Logger.e("XCPngManager", "VM action failed", e)
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun openVMConsole(vm: XCPngApiClient.XenVM) {
        // Get current hypervisor profile for credentials
        val position = serverSpinner.selectedItemPosition
        if (position < 0 || position >= hypervisors.size) {
            Toast.makeText(this, "No hypervisor selected", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = hypervisors[position]

        // Determine hypervisor type (Xen Orchestra or direct XCP-ng)
        val hypervisorType = if (isXenOrchestra) {
            VMConsoleActivity.TYPE_XEN_ORCHESTRA
        } else {
            VMConsoleActivity.TYPE_XCPNG
        }

        // Launch VMConsoleActivity for serial console (works without VM network)
        val intent = android.content.Intent(this, VMConsoleActivity::class.java).apply {
            putExtra(VMConsoleActivity.EXTRA_HYPERVISOR_TYPE, hypervisorType)
            putExtra(VMConsoleActivity.EXTRA_VM_ID, vm.uuid)
            putExtra(VMConsoleActivity.EXTRA_VM_NAME, vm.name)
            putExtra(VMConsoleActivity.EXTRA_VM_REF, vm.uuid) // XCP-ng uses uuid as reference
            putExtra(VMConsoleActivity.EXTRA_HOST, profile.host)
            putExtra(VMConsoleActivity.EXTRA_PORT, profile.port)
            putExtra(VMConsoleActivity.EXTRA_USERNAME, profile.username)
            putExtra(VMConsoleActivity.EXTRA_PASSWORD, profile.password)
            putExtra(VMConsoleActivity.EXTRA_IS_XEN_ORCHESTRA, isXenOrchestra)
        }
        startActivity(intent)

        val consoleType = if (isXenOrchestra) "Xen Orchestra" else "XCP-ng"
        Toast.makeText(this, "Opening serial console for ${vm.name}", Toast.LENGTH_SHORT).show()
        Logger.i("XCPngManager", "Launching $consoleType serial console for VM: ${vm.name} (uuid=${vm.uuid})")
    }

    private fun showAddServerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_hypervisor, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.name_input)
        val hostInput = dialogView.findViewById<EditText>(R.id.host_input)
        val portInput = dialogView.findViewById<EditText>(R.id.port_input)
        val usernameInput = dialogView.findViewById<EditText>(R.id.username_input)
        val passwordInput = dialogView.findViewById<EditText>(R.id.password_input)
        val realmInput = dialogView.findViewById<EditText>(R.id.realm_input)
        val xenOrchestraSwitch = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.xen_orchestra_switch)
        val xenOrchestraHint = dialogView.findViewById<TextView>(R.id.xen_orchestra_hint)
        
        portInput.setText("443")
        realmInput.visibility = View.GONE // XCP-ng doesn't use realm
        
        // Show Xen Orchestra toggle for XCP-ng
        xenOrchestraSwitch.visibility = View.VISIBLE
        xenOrchestraHint.visibility = View.VISIBLE
        
        // Update username hint based on toggle
        xenOrchestraSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                usernameInput.hint = "Email (for Xen Orchestra)"
            } else {
                usernameInput.hint = "Username"
            }
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Add XCP-ng / Xen Orchestra Server")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val profile = HypervisorProfile(
                    name = nameInput.text.toString(),
                    type = HypervisorType.XCPNG,
                    host = hostInput.text.toString(),
                    port = portInput.text.toString().toIntOrNull() ?: 443,
                    username = usernameInput.text.toString(),
                    password = passwordInput.text.toString(),
                    verifySsl = false,
                    isXenOrchestra = xenOrchestraSwitch.isChecked
                )
                
                lifecycleScope.launch {
                    app.database.hypervisorDao().insert(profile)
                    loadHypervisors()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // VM Adapter (reusing item_proxmox_vm layout)
    
    private inner class VMAdapter(
        private val vms: List<XCPngApiClient.XenVM>,
        private val onAction: (XCPngApiClient.XenVM, String) -> Unit
    ) : RecyclerView.Adapter<VMAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val status: TextView = view.findViewById(R.id.vm_status)
            val info: TextView = view.findViewById(R.id.vm_info)
            val consoleButton: Button = view.findViewById(R.id.console_button)
            val startButton: Button = view.findViewById(R.id.start_button)
            val stopButton: Button = view.findViewById(R.id.stop_button)
            val rebootButton: Button = view.findViewById(R.id.reboot_button)
            val resetButton: Button = view.findViewById(R.id.reset_button)
            val snapshotsButton: Button = view.findViewById(R.id.snapshots_button)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_proxmox_vm, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val vm = vms[position]

            holder.name.text = vm.name
            holder.status.text = vm.powerState.uppercase()
            holder.info.text = "CPUs: ${vm.vcpus} | RAM: ${vm.memory / 1024 / 1024}MB"

            holder.status.setTextColor(
                when (vm.powerState.lowercase()) {
                    "running" -> 0xFF4CAF50.toInt()
                    "halted" -> 0xFFF44336.toInt()
                    else -> 0xFFFF9800.toInt()
                }
            )

            // Show/hide buttons based on VM status (better UX than disabled)
            when (vm.powerState.lowercase()) {
                "running" -> {
                    // VM is running - show power controls, hide start
                    holder.consoleButton.visibility = View.VISIBLE
                    holder.startButton.visibility = View.GONE
                    holder.stopButton.visibility = View.VISIBLE
                    holder.rebootButton.visibility = View.VISIBLE
                    holder.resetButton.visibility = View.VISIBLE
                }
                "halted", "stopped" -> {
                    // VM is stopped - only show start
                    holder.consoleButton.visibility = View.GONE
                    holder.startButton.visibility = View.VISIBLE
                    holder.stopButton.visibility = View.GONE
                    holder.rebootButton.visibility = View.GONE
                    holder.resetButton.visibility = View.GONE
                }
                else -> {
                    // Paused, suspended, etc - show start/stop only
                    holder.consoleButton.visibility = View.GONE
                    holder.startButton.visibility = View.VISIBLE
                    holder.stopButton.visibility = View.VISIBLE
                    holder.rebootButton.visibility = View.GONE
                    holder.resetButton.visibility = View.GONE
                }
            }

            // Show snapshots button only for XO connections
            if (isXenOrchestra) {
                holder.snapshotsButton.visibility = View.VISIBLE
                holder.snapshotsButton.setOnClickListener {
                    showSnapshotDialog(vm)
                }
            } else {
                holder.snapshotsButton.visibility = View.GONE
            }

            holder.consoleButton.setOnClickListener { onAction(vm, "console") }
            holder.startButton.setOnClickListener { onAction(vm, "start") }
            holder.stopButton.setOnClickListener { onAction(vm, "stop") }
            holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
            holder.resetButton.setOnClickListener { onAction(vm, "reset") }
        }

        override fun getItemCount() = vms.size
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
        vmNameText.text = "VM: ${vm.name}"
        
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
            } catch (e: Exception) {
                Logger.e("XCPngManager", "Failed to load snapshots: ${e.message}", e)
                progressBar.visibility = View.GONE
                emptyStateText.text = "Error loading snapshots"
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
        val input = EditText(this)
        input.hint = "Snapshot name"
        input.setText("Snapshot ${System.currentTimeMillis()}")
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Create Snapshot")
            .setMessage("Enter a name for the snapshot of ${vm.name}")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                lifecycleScope.launch {
                    try {
                        val success = currentXoClient?.createSnapshot(vm.uuid, name) ?: false
                        if (success) {
                            Toast.makeText(this@XCPngManagerActivity, "Snapshot created", Toast.LENGTH_SHORT).show()
                            parentDialog.dismiss()
                            showSnapshotDialog(vm) // Refresh
                        } else {
                            showError("Failed to create snapshot", "Error")
                        }
                    } catch (e: Exception) {
                        Logger.e("XCPngManager", "Snapshot creation error: ${e.message}", e)
                        showError("Error: ${e.message}", "Error")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Handle snapshot actions (revert, delete)
     */
    private fun handleSnapshotAction(vm: XCPngApiClient.XenVM, snapshot: XenOrchestraApiClient.XoSnapshot, action: String, parentDialog: androidx.appcompat.app.AlertDialog) {
        when (action) {
            "revert" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Revert to Snapshot")
                    .setMessage("Are you sure you want to revert ${vm.name} to snapshot '${snapshot.name_label}'?\n\nThis will restore the VM to its state when the snapshot was taken.")
                    .setPositiveButton("Revert") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.revertSnapshot(vm.uuid, snapshot.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, "VM reverted to snapshot", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                } else {
                                    showError("Failed to revert", "Error")
                                }
                            } catch (e: Exception) {
                                Logger.e("XCPngManager", "Revert error: ${e.message}", e)
                                showError("Error: ${e.message}", "Error")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            "delete" -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Snapshot")
                    .setMessage("Are you sure you want to delete snapshot '${snapshot.name_label}'?\n\nThis action cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.deleteSnapshot(vm.uuid, snapshot.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, "Snapshot deleted", Toast.LENGTH_SHORT).show()
                                    parentDialog.dismiss()
                                    showSnapshotDialog(vm) // Refresh
                                } else {
                                    showError("Failed to delete snapshot", "Error")
                                }
                            } catch (e: Exception) {
                                Logger.e("XCPngManager", "Delete error: ${e.message}", e)
                                showError("Error: ${e.message}", "Error")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
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
            
            holder.name.text = snapshot.name_label
            holder.time.text = "Created: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(snapshot.snapshot_time))}"
            
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
                } catch (e: Exception) {
                    Logger.e("XCPngManager", "Failed to load backup jobs: ${e.message}", e)
                    progressBar.visibility = View.GONE
                    emptyStateText.text = "Error loading backup jobs"
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
                    .setTitle("Trigger Backup")
                    .setMessage("Are you sure you want to trigger backup job '${job.name}' now?\n\nThis will start a manual backup run.")
                    .setPositiveButton("Trigger") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val success = currentXoClient?.triggerBackup(job.id) ?: false
                                if (success) {
                                    Toast.makeText(this@XCPngManagerActivity, "Backup triggered", Toast.LENGTH_SHORT).show()
                                } else {
                                    showError("Failed to trigger backup", "Error")
                                }
                            } catch (e: Exception) {
                                Logger.e("XCPngManager", "Trigger backup error: ${e.message}", e)
                                showError("Error: ${e.message}", "Error")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
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
            
            holder.name.text = job.name
            holder.mode.text = "Mode: ${job.mode}"
            holder.status.text = if (job.enabled) "● Enabled" else "○ Disabled"
            holder.status.setTextColor(if (job.enabled) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
            holder.schedule.text = "Schedule: ${job.schedule ?: "Manual"}"
            holder.vms.text = "VMs: ${job.vms?.size ?: 0}"
            
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
        jobNameText.text = "Backup Job: ${job.name}"
        
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
                } catch (e: Exception) {
                    Logger.e("XCPngManager", "Failed to load backup runs: ${e.message}", e)
                    progressBar.visibility = View.GONE
                    emptyStateText.text = "Error loading backup runs"
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
                    holder.status.text = "● Success"
                    holder.status.setTextColor(0xFF4CAF50.toInt())
                }
                "failure", "error" -> {
                    holder.status.text = "● Failed"
                    holder.status.setTextColor(0xFFF44336.toInt())
                }
                "running", "in_progress" -> {
                    holder.status.text = "● Running"
                    holder.status.setTextColor(0xFF2196F3.toInt())
                }
                else -> {
                    holder.status.text = "○ ${run.status}"
                    holder.status.setTextColor(0xFF9E9E9E.toInt())
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
                holder.duration.text = "Duration: ${minutes}m ${seconds}s"
            } else {
                holder.duration.text = "Duration: In progress..."
            }
            
            // Result
            holder.result.text = "Result: ${run.result ?: "No details available"}"
        }
        
        override fun getItemCount() = runs.size
    }
    
    // ======================== WebSocket Real-Time Updates ========================
    
    /**
     * Setup WebSocket connection for real-time VM updates
     */
    private fun setupWebSocket() {
        val xoClient = currentXoClient ?: return
        
        Logger.d("XCPngManager", "Setting up WebSocket for real-time updates")
        
        xoClient.connectWebSocket(object : XenOrchestraApiClient.EventListener {
            override fun onVMStateChanged(vmId: String, newState: String) {
                runOnUiThread {
                    Logger.d("XCPngManager", "VM $vmId state changed to $newState")
                    
                    // Update VM in list
                    val vmIndex = vms.indexOfFirst { it.uuid == vmId }
                    if (vmIndex >= 0) {
                        vms[vmIndex] = vms[vmIndex].copy(powerState = newState)
                        vmRecyclerView.adapter?.notifyItemChanged(vmIndex)
                    }
                    
                    Toast.makeText(this@XCPngManagerActivity, 
                        "VM state changed: $newState", 
                        Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onVMCreated(vmId: String) {
                runOnUiThread {
                    Logger.d("XCPngManager", "VM created: $vmId")
                    Toast.makeText(this@XCPngManagerActivity, 
                        "New VM created", 
                        Toast.LENGTH_SHORT).show()
                    refreshVMs()
                }
            }
            
            override fun onVMDeleted(vmId: String) {
                runOnUiThread {
                    Logger.d("XCPngManager", "VM deleted: $vmId")
                    
                    // Remove VM from list
                    val vmIndex = vms.indexOfFirst { it.uuid == vmId }
                    if (vmIndex >= 0) {
                        vms.removeAt(vmIndex)
                        vmRecyclerView.adapter?.notifyItemRemoved(vmIndex)
                    }
                    
                    Toast.makeText(this@XCPngManagerActivity, 
                        "VM deleted", 
                        Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onSnapshotCreated(vmId: String, snapshotId: String) {
                runOnUiThread {
                    Logger.d("XCPngManager", "Snapshot created for VM $vmId: $snapshotId")
                    Toast.makeText(this@XCPngManagerActivity, 
                        "Snapshot created", 
                        Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onSnapshotDeleted(vmId: String, snapshotId: String) {
                runOnUiThread {
                    Logger.d("XCPngManager", "Snapshot deleted for VM $vmId: $snapshotId")
                    Toast.makeText(this@XCPngManagerActivity, 
                        "Snapshot deleted", 
                        Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onBackupCompleted(jobId: String, success: Boolean) {
                runOnUiThread {
                    Logger.d("XCPngManager", "Backup completed: jobId=$jobId, success=$success")
                    val message = if (success) "Backup completed successfully" else "Backup failed"
                    Toast.makeText(this@XCPngManagerActivity, message, Toast.LENGTH_LONG).show()
                }
            }
            
            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    Logger.d("XCPngManager", "WebSocket connection state: $connected")
                    
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
                runOnUiThread {
                    Logger.e("XCPngManager", "WebSocket error: $error")
                    // Don't show toast for errors - keep UI clean
                }
            }
        })
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Disconnect WebSocket when activity is destroyed
        currentXoClient?.disconnectWebSocket()
    }
}
