package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.vmware.VMwareApiClient
import kotlinx.coroutines.launch
import io.github.tabssh.utils.showError

class HypervisorEditActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var spinnerType: Spinner
    private lateinit var editHost: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var editRealm: TextInputEditText
    private lateinit var layoutRealm: LinearLayout
    private lateinit var switchVerifySsl: SwitchMaterial
    private lateinit var editNotes: TextInputEditText
    private lateinit var buttonTestConnection: MaterialButton
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonCancel: MaterialButton
    
    private var hypervisorId: Long? = null
    private var editingHypervisor: HypervisorProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hypervisor_edit)
        
        app = application as TabSSHApplication
        
        setupViews()
        setupToolbar()
        setupSpinner()
        setupClickListeners()
        
        hypervisorId = intent.getLongExtra("hypervisor_id", -1).takeIf { it != -1L }
        hypervisorId?.let { loadHypervisor(it) }
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        spinnerType = findViewById(R.id.spinner_type)
        editHost = findViewById(R.id.edit_host)
        editPort = findViewById(R.id.edit_port)
        editUsername = findViewById(R.id.edit_username)
        editPassword = findViewById(R.id.edit_password)
        editRealm = findViewById(R.id.edit_realm)
        layoutRealm = findViewById(R.id.layout_realm)
        switchVerifySsl = findViewById(R.id.switch_verify_ssl)
        editNotes = findViewById(R.id.edit_notes)
        buttonTestConnection = findViewById(R.id.button_test)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (hypervisorId != null) "Edit Hypervisor" else "Add Hypervisor"
    }

    private fun setupSpinner() {
        val types = arrayOf("Proxmox", "XCP-ng", "VMware")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter
        
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateUIForType(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Set default ports
        updateUIForType(0)
    }

    private fun setupClickListeners() {
        buttonTestConnection.setOnClickListener {
            testConnection()
        }
        
        buttonSave.setOnClickListener {
            saveHypervisor()
        }
        
        buttonCancel.setOnClickListener {
            finish()
        }
    }

    private fun updateUIForType(typePosition: Int) {
        when (typePosition) {
            0 -> { // Proxmox
                if (editPort.text.toString().isEmpty()) editPort.setText("8006")
                layoutRealm.visibility = View.VISIBLE
                if (editRealm.text.toString().isEmpty()) editRealm.setText("pam")
            }
            1 -> { // XCP-ng
                if (editPort.text.toString().isEmpty()) editPort.setText("443")
                layoutRealm.visibility = View.GONE
            }
            2 -> { // VMware
                if (editPort.text.toString().isEmpty()) editPort.setText("443")
                layoutRealm.visibility = View.GONE
            }
        }
    }

    private fun loadHypervisor(id: Long) {
        lifecycleScope.launch {
            try {
                val hypervisor = app.database.hypervisorDao().getById(id)
                if (hypervisor != null) {
                    editingHypervisor = hypervisor
                    
                    editName.setText(hypervisor.name)
                    spinnerType.setSelection(hypervisor.type.ordinal)
                    editHost.setText(hypervisor.host)
                    editPort.setText(hypervisor.port.toString())
                    editUsername.setText(hypervisor.username)
                    editPassword.setText(hypervisor.password)
                    editRealm.setText(hypervisor.realm ?: "pam")
                    switchVerifySsl.isChecked = hypervisor.verifySsl
                    editNotes.setText(hypervisor.notes ?: "")
                }
            } catch (e: Exception) {
                showError("Failed to load hypervisor", "Error")
                finish()
            }
        }
    }

    private fun testConnection() {
        if (!validateFields()) return
        
        buttonTestConnection.isEnabled = false
        buttonTestConnection.text = "Testing..."
        
        lifecycleScope.launch {
            try {
                val type = HypervisorType.values()[spinnerType.selectedItemPosition]
                val host = editHost.text.toString()
                val port = editPort.text.toString().toInt()
                val username = editUsername.text.toString()
                val password = editPassword.text.toString()
                val realm = editRealm.text.toString()
                val verifySsl = switchVerifySsl.isChecked
                
                val success = when (type) {
                    HypervisorType.PROXMOX -> {
                        val client = ProxmoxApiClient(host, port, username, password, realm, verifySsl)
                        client.authenticate()
                    }
                    HypervisorType.XCPNG -> {
                        // XCP-ng uses XML-RPC API
                        val client = XCPngApiClient(host, port, username, password, verifySsl)
                        client.authenticate()
                    }
                    HypervisorType.VMWARE -> {
                        // VMware uses REST API
                        val client = VMwareApiClient(host, username, password, verifySsl)
                        client.authenticate()
                    }
                }
                
                if (success) {
                    Toast.makeText(this@HypervisorEditActivity, "✓ Connection successful", Toast.LENGTH_LONG).show()
                } else {
                    showError("✗ Connection failed", "Error")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}", "Error")
            } finally {
                buttonTestConnection.isEnabled = true
                buttonTestConnection.text = "Test Connection"
            }
        }
    }

    private fun saveHypervisor() {
        if (!validateFields()) return
        
        lifecycleScope.launch {
            try {
                val type = HypervisorType.values()[spinnerType.selectedItemPosition]
                val hypervisor = HypervisorProfile(
                    id = hypervisorId ?: 0,
                    name = editName.text.toString(),
                    type = type,
                    host = editHost.text.toString(),
                    port = editPort.text.toString().toInt(),
                    username = editUsername.text.toString(),
                    password = editPassword.text.toString(),
                    realm = if (type == HypervisorType.PROXMOX) editRealm.text.toString() else null,
                    verifySsl = switchVerifySsl.isChecked,
                    notes = editNotes.text.toString().takeIf { it.isNotBlank() },
                    lastConnected = editingHypervisor?.lastConnected ?: 0,
                    createdAt = editingHypervisor?.createdAt ?: System.currentTimeMillis()
                )
                
                if (hypervisorId != null) {
                    app.database.hypervisorDao().update(hypervisor)
                    Toast.makeText(this@HypervisorEditActivity, "Updated ${hypervisor.name}", Toast.LENGTH_SHORT).show()
                } else {
                    app.database.hypervisorDao().insert(hypervisor)
                    Toast.makeText(this@HypervisorEditActivity, "Added ${hypervisor.name}", Toast.LENGTH_SHORT).show()
                }
                
                finish()
            } catch (e: Exception) {
                showError("Failed to save: ${e.message}", "Error")
            }
        }
    }

    private fun validateFields(): Boolean {
        if (editName.text.toString().isBlank()) {
            editName.error = "Name is required"
            return false
        }
        if (editHost.text.toString().isBlank()) {
            editHost.error = "Host is required"
            return false
        }
        if (editPort.text.toString().isBlank()) {
            editPort.error = "Port is required"
            return false
        }
        if (editUsername.text.toString().isBlank()) {
            editUsername.error = "Username is required"
            return false
        }
        if (editPassword.text.toString().isBlank()) {
            editPassword.error = "Password is required"
            return false
        }
        
        val port = editPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            editPort.error = "Invalid port"
            return false
        }
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
