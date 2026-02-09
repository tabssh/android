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
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import kotlinx.coroutines.launch

class XCPngManagerActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var serverSpinner: Spinner
    private lateinit var refreshButton: Button
    private lateinit var addServerButton: Button
    private lateinit var vmRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    
    private val hypervisors = mutableListOf<HypervisorProfile>()
    private val vms = mutableListOf<XCPngApiClient.XenVM>()
    private var currentClient: XCPngApiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxmox_manager) // Reuse Proxmox layout
        
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
                
                Logger.d("XCPngManager", "Connecting to ${profile.host}:${profile.port} as ${profile.username}")
                
                currentClient = XCPngApiClient(
                    host = profile.host,
                    port = profile.port,
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
                    statusText.text = "Authentication failed - check credentials and network"
                    Toast.makeText(this@XCPngManagerActivity, 
                        "Failed to authenticate. Check:\n• Username/password\n• Host/port (${profile.host}:${profile.port})\n• Network connectivity\n• SSL certificate (verify SSL: ${profile.verifySsl})", 
                        Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                }
                
            } catch (e: java.net.UnknownHostException) {
                Logger.e("XCPngManager", "Unknown host: ${profile.host}", e)
                statusText.text = "Error: Host not found (${profile.host})"
                Toast.makeText(this@XCPngManagerActivity, "Cannot resolve hostname: ${profile.host}", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            } catch (e: java.net.ConnectException) {
                Logger.e("XCPngManager", "Connection refused", e)
                statusText.text = "Error: Connection refused"
                Toast.makeText(this@XCPngManagerActivity, "Connection refused. Check:\n• Port ${profile.port} is correct\n• XCP-ng API is accessible\n• Firewall allows connection", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                Logger.e("XCPngManager", "SSL handshake failed", e)
                statusText.text = "Error: SSL certificate issue"
                Toast.makeText(this@XCPngManagerActivity, "SSL certificate verification failed. Try disabling 'Verify SSL' in hypervisor settings.", Toast.LENGTH_LONG).show()
                progressBar.visibility = View.GONE
            } catch (e: Exception) {
                Logger.e("XCPngManager", "Connection failed", e)
                statusText.text = "Connection error: ${e.message}"
                Toast.makeText(this@XCPngManagerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
                Logger.e("XCPngManager", "Failed to load VMs", e)
                statusText.text = "Error loading VMs"
                progressBar.visibility = View.GONE
            }
        }
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
                
                val success = when (action) {
                    "start" -> currentClient?.startVM(vm.uuid) ?: false
                    "stop" -> currentClient?.hardShutdownVM(vm.uuid) ?: false
                    "shutdown" -> currentClient?.shutdownVM(vm.uuid) ?: false
                    "reboot" -> currentClient?.rebootVM(vm.uuid) ?: false
                    else -> false
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
                    Logger.i("XCPngManager", "Created connection profile for VM: ${vm.name}")
                } else {
                    // Update existing connection with latest IP
                    connection = connection.copy(
                        host = vm.ipAddress!!,
                        modifiedAt = System.currentTimeMillis()
                    )
                    app.database.connectionDao().updateConnection(connection)
                    Logger.i("XCPngManager", "Updated connection profile for VM: ${vm.name}")
                }

                // Launch terminal activity
                val intent = TabTerminalActivity.createIntent(this@XCPngManagerActivity, connection, autoConnect = false)
                startActivity(intent)
                
                Toast.makeText(this@XCPngManagerActivity, "Opening console for ${vm.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("XCPngManager", "Failed to open VM console", e)
                Toast.makeText(this@XCPngManagerActivity, "Failed to open console: ${e.message}", Toast.LENGTH_SHORT).show()
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
        
        portInput.setText("443")
        realmInput.visibility = View.GONE // XCP-ng doesn't use realm
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Add XCP-ng Server")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val profile = HypervisorProfile(
                    name = nameInput.text.toString(),
                    type = HypervisorType.XCPNG,
                    host = hostInput.text.toString(),
                    port = portInput.text.toString().toIntOrNull() ?: 443,
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

    // VM Adapter (reusing item_proxmox_vm layout)
    
    private class VMAdapter(
        private val vms: List<XCPngApiClient.XenVM>,
        private val onAction: (XCPngApiClient.XenVM, String) -> Unit
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
            holder.info.text = "CPUs: ${vm.vcpus} | RAM: ${vm.memory / 1024 / 1024}MB"
            
            holder.status.setTextColor(
                when (vm.powerState.lowercase()) {
                    "running" -> 0xFF4CAF50.toInt()
                    "halted" -> 0xFFF44336.toInt()
                    else -> 0xFFFF9800.toInt()
                }
            )
            
            holder.startButton.isEnabled = vm.powerState.lowercase() != "running"
            holder.stopButton.isEnabled = vm.powerState.lowercase() == "running"
            holder.rebootButton.isEnabled = vm.powerState.lowercase() == "running"
            
            holder.startButton.setOnClickListener { onAction(vm, "start") }
            holder.stopButton.setOnClickListener { onAction(vm, "stop") }
            holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
        }

        override fun getItemCount() = vms.size
    }
}
