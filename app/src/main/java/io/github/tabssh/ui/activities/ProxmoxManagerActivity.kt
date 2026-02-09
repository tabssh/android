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
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import kotlinx.coroutines.launch

class ProxmoxManagerActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var serverSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var addServerButton: Button
    private lateinit var vmRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val vms = mutableListOf<ProxmoxApiClient.ProxmoxVM>()
    private var currentClient: ProxmoxApiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxmox_manager)
        
        app = application as TabSSHApplication
        
        setupToolbar()
        setupViews()
        loadHypervisors()
        
        Logger.d("ProxmoxManager", "Activity created")
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Proxmox Manager"
    }

    private fun setupViews() {
        serverSpinner = findViewById(R.id.server_spinner)
        refreshButton = findViewById(R.id.refresh_button)
        addServerButton = findViewById(R.id.add_server_button)
        vmRecyclerView = findViewById(R.id.vm_recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        
        vmRecyclerView.layoutManager = LinearLayoutManager(this)
        
        refreshButton.setOnClickListener { refreshVMs() }
        addServerButton.setOnClickListener { showAddServerDialog() }
        
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
            app.database.hypervisorDao().getByType(HypervisorType.PROXMOX).let { servers ->
                hypervisors.clear()
                hypervisors.addAll(servers)
                
                if (hypervisors.isEmpty()) {
                    statusText.text = "No Proxmox servers configured"
                    statusText.visibility = View.VISIBLE
                } else {
                    statusText.visibility = View.GONE
                    val adapter = ArrayAdapter(
                        this@ProxmoxManagerActivity,
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
                
                currentClient = ProxmoxApiClient(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    password = profile.password,
                    realm = profile.realm ?: "pam",
                    verifySsl = profile.verifySsl
                )
                
                val authenticated = currentClient?.authenticate() ?: false
                
                if (authenticated) {
                    statusText.text = "Connected to ${profile.name}"
                    app.database.hypervisorDao().updateLastConnected(profile.id, System.currentTimeMillis())
                    refreshVMs()
                } else {
                    statusText.text = "Authentication failed"
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ProxmoxManagerActivity, "Failed to authenticate", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "Connection failed", e)
                statusText.text = "Connection error: ${e.message}"
                progressBar.visibility = View.GONE
                Toast.makeText(this@ProxmoxManagerActivity, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshVMs() {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Loading VMs..."
                
                val vmList = currentClient?.getAllVMs() ?: emptyList()
                
                // Fetch IP addresses for running VMs (in parallel)
                val vmsWithIPs = vmList.map { vm ->
                    if (vm.status == "running") {
                        // Try to get IP address
                        val ip = try {
                            currentClient?.getVMIPAddress(vm.node, vm.vmid, vm.type)
                        } catch (e: Exception) {
                            Logger.d("ProxmoxManager", "Could not get IP for VM ${vm.vmid}: ${e.message}")
                            null
                        }
                        vm.copy(ipAddress = ip)
                    } else {
                        vm
                    }
                }
                
                vms.clear()
                vms.addAll(vmsWithIPs)
                
                vmRecyclerView.adapter = VMAdapter(vms) { vm, action ->
                    handleVMAction(vm, action)
                }
                
                statusText.text = "Found ${vms.size} VMs"
                progressBar.visibility = View.GONE
                
                Logger.d("ProxmoxManager", "Loaded ${vms.size} VMs")
                
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "Failed to load VMs", e)
                statusText.text = "Error loading VMs"
                progressBar.visibility = View.GONE
                Toast.makeText(this@ProxmoxManagerActivity, "Failed to load VMs", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleVMAction(vm: ProxmoxApiClient.ProxmoxVM, action: String) {
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
                
                val success = when (action) {
                    "start" -> currentClient?.startVM(vm.node, vm.vmid, vm.type) ?: false
                    "stop" -> currentClient?.stopVM(vm.node, vm.vmid, vm.type) ?: false
                    "shutdown" -> currentClient?.shutdownVM(vm.node, vm.vmid, vm.type) ?: false
                    "reboot" -> currentClient?.rebootVM(vm.node, vm.vmid, vm.type) ?: false
                    else -> false
                }
                
                if (success) {
                    Toast.makeText(this@ProxmoxManagerActivity, "VM $action successful", Toast.LENGTH_SHORT).show()
                    // Refresh after a delay
                    kotlinx.coroutines.delay(2000)
                    refreshVMs()
                } else {
                    Toast.makeText(this@ProxmoxManagerActivity, "VM $action failed", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "VM action failed", e)
                Toast.makeText(this@ProxmoxManagerActivity, "Action failed: ${e.message}", Toast.LENGTH_SHORT).show()
                progressBar.visibility = View.GONE
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
        
        portInput.setText("8006")
        realmInput.setText("pam")
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Proxmox Server")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val profile = HypervisorProfile(
                    name = nameInput.text.toString(),
                    type = HypervisorType.PROXMOX,
                    host = hostInput.text.toString(),
                    port = portInput.text.toString().toIntOrNull() ?: 8006,
                    username = usernameInput.text.toString(),
                    password = passwordInput.text.toString(),
                    realm = realmInput.text.toString(),
                    verifySsl = false
                )
                
                lifecycleScope.launch {
                    app.database.hypervisorDao().insert(profile)
                    loadHypervisors()
                    Toast.makeText(this@ProxmoxManagerActivity, "Server added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVMConsole(vm: ProxmoxApiClient.ProxmoxVM) {
        if (vm.ipAddress == null) {
            Toast.makeText(this, "VM IP address not available", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // Get or create connection profile for VM
                val connectionName = "${vm.name}-console"
                var connection = app.database.connectionDao().getAllConnections()
                    .let { flow -> 
                        var result: io.github.tabssh.storage.database.entities.ConnectionProfile? = null
                        flow.collect { connections ->
                            result = connections.firstOrNull { it.name == connectionName }
                        }
                        result
                    }

                if (connection == null) {
                    // Create new connection profile
                    connection = io.github.tabssh.storage.database.entities.ConnectionProfile(
                        id = java.util.UUID.randomUUID().toString(),
                        name = connectionName,
                        host = vm.ipAddress!!,
                        port = 22,
                        username = "root",
                        authType = io.github.tabssh.ssh.auth.AuthType.PASSWORD.name,
                        createdAt = System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis()
                    )
                    app.database.connectionDao().insertConnection(connection)
                    Logger.i("ProxmoxManager", "Created connection profile for VM: ${vm.name}")
                } else {
                    // Update existing connection with latest IP
                    connection = connection.copy(
                        host = vm.ipAddress!!,
                        modifiedAt = System.currentTimeMillis()
                    )
                    app.database.connectionDao().updateConnection(connection)
                    Logger.i("ProxmoxManager", "Updated connection profile for VM: ${vm.name}")
                }

                // Launch terminal activity
                val intent = TabTerminalActivity.createIntent(this@ProxmoxManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                
                Toast.makeText(this@ProxmoxManagerActivity, "Opening console for ${vm.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "Failed to open VM console", e)
                Toast.makeText(this@ProxmoxManagerActivity, "Failed to open console: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // VM Adapter
    
    private class VMAdapter(
        private val vms: List<ProxmoxApiClient.ProxmoxVM>,
        private val onAction: (ProxmoxApiClient.ProxmoxVM, String) -> Unit
    ) : RecyclerView.Adapter<VMAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val status: TextView = view.findViewById(R.id.vm_status)
            val info: TextView = view.findViewById(R.id.vm_info)
            val ipAddress: TextView = view.findViewById(R.id.vm_ip_address)
            val consoleButton: Button = view.findViewById(R.id.console_button)
            val startButton: Button = view.findViewById(R.id.start_button)
            val stopButton: Button = view.findViewById(R.id.stop_button)
            val rebootButton: Button = view.findViewById(R.id.reboot_button)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_proxmox_vm, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val vm = vms[position]
            
            holder.name.text = "${vm.name} (${vm.vmid})"
            holder.status.text = vm.status.uppercase()
            holder.info.text = "Node: ${vm.node} | CPU: ${(vm.cpu * 100).toInt()}% | RAM: ${vm.mem / 1024 / 1024}MB"
            
            // Show IP address if available
            if (vm.ipAddress != null) {
                holder.ipAddress.visibility = android.view.View.VISIBLE
                holder.ipAddress.text = "IP: ${vm.ipAddress}"
            } else {
                holder.ipAddress.visibility = android.view.View.GONE
            }
            
            // Set status color
            holder.status.setTextColor(
                when (vm.status) {
                    "running" -> 0xFF4CAF50.toInt()
                    "stopped" -> 0xFFF44336.toInt()
                    else -> 0xFFFF9800.toInt()
                }
            )
            
            // Enable/disable buttons based on status
            holder.consoleButton.isEnabled = vm.status == "running" && vm.ipAddress != null
            holder.startButton.isEnabled = vm.status != "running"
            holder.stopButton.isEnabled = vm.status == "running"
            holder.rebootButton.isEnabled = vm.status == "running"
            
            holder.consoleButton.setOnClickListener { onAction(vm, "console") }
            holder.startButton.setOnClickListener { onAction(vm, "start") }
            holder.stopButton.setOnClickListener { onAction(vm, "stop") }
            holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
        }

        override fun getItemCount() = vms.size
    }
}
