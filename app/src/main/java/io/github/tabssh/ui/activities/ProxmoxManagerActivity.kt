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
import io.github.tabssh.utils.showError

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
                    showError("Failed to authenticate", "Error")
                }
                
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "Connection failed", e)
                statusText.text = "Connection error: ${e.message}"
                progressBar.visibility = View.GONE
                showError("Connection failed", "Error")
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
                showError("Failed to load VMs", "Error")
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
                    "reset" -> currentClient?.resetVM(vm.node, vm.vmid, vm.type) ?: false
                    else -> false
                }
                
                if (success) {
                    Toast.makeText(this@ProxmoxManagerActivity, "VM $action successful", Toast.LENGTH_SHORT).show()
                    // Refresh after a delay
                    kotlinx.coroutines.delay(2000)
                    refreshVMs()
                } else {
                    showError("VM $action failed", "Error")
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: Exception) {
                Logger.e("ProxmoxManager", "VM action failed", e)
                showError("Action failed: ${e.message}", "Error")
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
        // Get current hypervisor profile for credentials
        val position = serverSpinner.selectedItemPosition
        if (position < 0 || position >= hypervisors.size) {
            Toast.makeText(this, "No hypervisor selected", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = hypervisors[position]

        // Launch VMConsoleActivity for serial console (works without VM network)
        val intent = android.content.Intent(this, VMConsoleActivity::class.java).apply {
            putExtra(VMConsoleActivity.EXTRA_HYPERVISOR_TYPE, VMConsoleActivity.TYPE_PROXMOX)
            putExtra(VMConsoleActivity.EXTRA_VM_ID, vm.vmid.toString())
            putExtra(VMConsoleActivity.EXTRA_VM_NAME, vm.name)
            putExtra(VMConsoleActivity.EXTRA_VM_NODE, vm.node)
            putExtra(VMConsoleActivity.EXTRA_VM_TYPE, vm.type)
            putExtra(VMConsoleActivity.EXTRA_HOST, profile.host)
            putExtra(VMConsoleActivity.EXTRA_PORT, profile.port)
            putExtra(VMConsoleActivity.EXTRA_USERNAME, profile.username)
            putExtra(VMConsoleActivity.EXTRA_PASSWORD, profile.password)
            putExtra(VMConsoleActivity.EXTRA_REALM, profile.realm ?: "pam")
        }
        startActivity(intent)

        Toast.makeText(this, "Opening serial console for ${vm.name}", Toast.LENGTH_SHORT).show()
        Logger.i("ProxmoxManager", "Launching serial console for VM: ${vm.name} (vmid=${vm.vmid})")
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
            val resetButton: Button = view.findViewById(R.id.reset_button)
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
            
            // Show/hide buttons based on VM status (better UX than disabled)
            when (vm.status.lowercase()) {
                "running" -> {
                    // VM is running - show power controls, hide start
                    holder.consoleButton.visibility = android.view.View.VISIBLE
                    holder.startButton.visibility = android.view.View.GONE
                    holder.stopButton.visibility = android.view.View.VISIBLE
                    holder.rebootButton.visibility = android.view.View.VISIBLE
                    holder.resetButton.visibility = android.view.View.VISIBLE
                }
                "stopped" -> {
                    // VM is stopped - only show start
                    holder.consoleButton.visibility = android.view.View.GONE
                    holder.startButton.visibility = android.view.View.VISIBLE
                    holder.stopButton.visibility = android.view.View.GONE
                    holder.rebootButton.visibility = android.view.View.GONE
                    holder.resetButton.visibility = android.view.View.GONE
                }
                else -> {
                    // Paused, suspended, etc - show start/stop only
                    holder.consoleButton.visibility = android.view.View.GONE
                    holder.startButton.visibility = android.view.View.VISIBLE
                    holder.stopButton.visibility = android.view.View.VISIBLE
                    holder.rebootButton.visibility = android.view.View.GONE
                    holder.resetButton.visibility = android.view.View.GONE
                }
            }

            holder.consoleButton.setOnClickListener { onAction(vm, "console") }
            holder.startButton.setOnClickListener { onAction(vm, "start") }
            holder.stopButton.setOnClickListener { onAction(vm, "stop") }
            holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
            holder.resetButton.setOnClickListener { onAction(vm, "reset") }
        }

        override fun getItemCount() = vms.size
    }
}
