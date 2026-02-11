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
import io.github.tabssh.hypervisor.vmware.VMwareApiClient
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import kotlinx.coroutines.launch
import io.github.tabssh.utils.showError

class VMwareManagerActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var serverSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var addServerButton: Button
    private lateinit var vmRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val vms = mutableListOf<VMwareApiClient.VMwareVM>()
    private var currentClient: VMwareApiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxmox_manager)
        
        app = application as TabSSHApplication
        
        setupToolbar()
        setupViews()
        loadHypervisors()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "VMware Manager"
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
            app.database.hypervisorDao().getByType(HypervisorType.VMWARE).let { servers ->
                hypervisors.clear()
                hypervisors.addAll(servers)
                
                if (hypervisors.isEmpty()) {
                    statusText.text = "No VMware servers configured"
                    statusText.visibility = View.VISIBLE
                } else {
                    statusText.visibility = View.GONE
                    val adapter = ArrayAdapter(
                        this@VMwareManagerActivity,
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
                
                currentClient = VMwareApiClient(
                    host = profile.host,
                    username = profile.username,
                    password = profile.password,
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
                }
                
            } catch (e: Exception) {
                Logger.e("VMwareManager", "Connection failed", e)
                statusText.text = "Connection error"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun refreshVMs() {
        lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Loading VMs..."
                
                val vmList = currentClient?.getAllVMs() ?: emptyList()
                vms.clear()
                vms.addAll(vmList)
                
                vmRecyclerView.adapter = VMAdapter(vms) { vm, action ->
                    handleVMAction(vm, action)
                }
                
                statusText.text = "Found ${vms.size} VMs"
                progressBar.visibility = View.GONE
                
            } catch (e: Exception) {
                Logger.e("VMwareManager", "Failed to load VMs", e)
                statusText.text = "Error loading VMs"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun handleVMAction(vm: VMwareApiClient.VMwareVM, action: String) {
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
                    "start" -> currentClient?.startVM(vm.vm) ?: false
                    "stop" -> currentClient?.stopVM(vm.vm) ?: false
                    "reboot" -> currentClient?.resetVM(vm.vm) ?: false
                    else -> false
                }
                
                if (success) {
                    Toast.makeText(this@VMwareManagerActivity, "VM $action successful", Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.delay(2000)
                    refreshVMs()
                } else {
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: Exception) {
                Logger.e("VMwareManager", "VM action failed", e)
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun openVMConsole(vm: VMwareApiClient.VMwareVM) {
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
                    Logger.i("VMwareManager", "Created connection profile for VM: ${vm.name}")
                } else {
                    // Update existing connection with latest IP
                    connection = connection.copy(
                        host = vm.ipAddress!!,
                        modifiedAt = System.currentTimeMillis()
                    )
                    app.database.connectionDao().updateConnection(connection)
                    Logger.i("VMwareManager", "Updated connection profile for VM: ${vm.name}")
                }

                // Launch terminal activity
                val intent = TabTerminalActivity.createIntent(this@VMwareManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                
                Toast.makeText(this@VMwareManagerActivity, "Opening console for ${vm.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("VMwareManager", "Failed to open VM console", e)
                showError("Failed to open console: ${e.message}", "Error")
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
        
        portInput.visibility = View.GONE // VMware uses default HTTPS port
        realmInput.visibility = View.GONE
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Add VMware Server")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val profile = HypervisorProfile(
                    name = nameInput.text.toString(),
                    type = HypervisorType.VMWARE,
                    host = hostInput.text.toString(),
                    port = 443,
                    username = usernameInput.text.toString(),
                    password = passwordInput.text.toString(),
                    verifySsl = false
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

    private class VMAdapter(
        private val vms: List<VMwareApiClient.VMwareVM>,
        private val onAction: (VMwareApiClient.VMwareVM, String) -> Unit
    ) : RecyclerView.Adapter<VMAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.vm_name)
            val status: TextView = view.findViewById(R.id.vm_status)
            val info: TextView = view.findViewById(R.id.vm_info)
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
            
            holder.name.text = vm.name
            holder.status.text = vm.powerState.uppercase()
            holder.info.text = "CPUs: ${vm.cpuCount} | RAM: ${vm.memoryMB}MB"
            
            holder.status.setTextColor(
                when (vm.powerState.uppercase()) {
                    "POWERED_ON" -> 0xFF4CAF50.toInt()
                    "POWERED_OFF" -> 0xFFF44336.toInt()
                    else -> 0xFFFF9800.toInt()
                }
            )
            
            holder.startButton.isEnabled = vm.powerState.uppercase() != "POWERED_ON"
            holder.stopButton.isEnabled = vm.powerState.uppercase() == "POWERED_ON"
            holder.rebootButton.isEnabled = vm.powerState.uppercase() == "POWERED_ON"
            
            holder.startButton.setOnClickListener { onAction(vm, "start") }
            holder.stopButton.setOnClickListener { onAction(vm, "stop") }
            holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
        }

        override fun getItemCount() = vms.size
    }
}
