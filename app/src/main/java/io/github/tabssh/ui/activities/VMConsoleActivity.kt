package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.console.ConsoleEventListener
import io.github.tabssh.hypervisor.console.HypervisorConsoleManager
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity for displaying VM serial console.
 * Uses WebSocket connection to hypervisor for text-based console access.
 *
 * Works even without VM network access (serial console via hypervisor API).
 *
 * Supports:
 * - Proxmox VE (termproxy)
 * - XCP-ng/XenServer (XenAPI console)
 * - Xen Orchestra (REST API + WebSocket)
 */
class VMConsoleActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VMConsoleActivity"

        // Intent extras
        const val EXTRA_HYPERVISOR_TYPE = "hypervisor_type"
        const val EXTRA_VM_ID = "vm_id"
        const val EXTRA_VM_NAME = "vm_name"
        const val EXTRA_VM_NODE = "vm_node" // Proxmox node name
        const val EXTRA_VM_TYPE = "vm_type" // "qemu" or "lxc" for Proxmox
        const val EXTRA_VM_REF = "vm_ref" // XCP-ng VM reference

        // Hypervisor profile extras
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_REALM = "realm" // Proxmox realm
        const val EXTRA_IS_XEN_ORCHESTRA = "is_xen_orchestra"

        // Hypervisor type values
        const val TYPE_PROXMOX = "proxmox"
        const val TYPE_XCPNG = "xcpng"
        const val TYPE_XEN_ORCHESTRA = "xen_orchestra"
        const val TYPE_VMWARE = "vmware"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var terminalView: TerminalView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var consoleManager: HypervisorConsoleManager? = null
    private var termuxBridge: TermuxBridge? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vm_console)

        app = application as TabSSHApplication

        // Find views
        terminalView = findViewById(R.id.terminal_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)

        // Setup toolbar
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "VM Console"
        supportActionBar?.apply {
            title = vmName
            subtitle = "Serial Console"
            setDisplayHomeAsUpEnabled(true)
        }

        // Initialize terminal
        setupTerminal()

        // Connect to console
        connectToConsole()
    }

    private fun setupTerminal() {
        // Create TermuxBridge for terminal emulation
        termuxBridge = TermuxBridge(
            columns = 80,
            rows = 24,
            transcriptRows = 2000
        )
        termuxBridge?.initialize()

        // Set up terminal listener
        termuxBridge?.addListener(object : TermuxBridgeListener {
            override fun onConnected() {
                Logger.d(TAG, "Terminal connected")
            }

            override fun onDisconnected() {
                Logger.d(TAG, "Terminal disconnected")
                runOnUiThread {
                    showStatus("Disconnected")
                }
            }

            override fun onScreenChanged() {
                runOnUiThread {
                    terminalView.invalidate()
                }
            }

            override fun onTitleChanged(title: String) {
                runOnUiThread {
                    if (title.isNotBlank()) {
                        supportActionBar?.title = title
                    }
                }
            }

            override fun onBell() {
                // Could vibrate or play sound
            }

            override fun onColorsChanged() {
                runOnUiThread {
                    terminalView.invalidate()
                }
            }

            override fun onCursorStateChanged(visible: Boolean) {
                runOnUiThread {
                    terminalView.invalidate()
                }
            }

            override fun onCopyToClipboard(text: String) {
                // Handle clipboard
            }

            override fun onPasteFromClipboard() {
                // Handle paste
            }

            override fun onError(e: Exception) {
                Logger.e(TAG, "Terminal error", e)
                runOnUiThread {
                    showError("Terminal error: ${e.message}")
                }
            }
        })

        // Attach to terminal view
        termuxBridge?.let {
            terminalView.attachTerminalEmulator(it)
        }
    }

    private fun connectToConsole() {
        val hypervisorType = intent.getStringExtra(EXTRA_HYPERVISOR_TYPE)
        val vmId = intent.getStringExtra(EXTRA_VM_ID)
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "Unknown VM"

        if (hypervisorType == null || vmId == null) {
            showError("Missing hypervisor or VM information")
            return
        }

        showProgress("Connecting to $vmName...")

        lifecycleScope.launch {
            try {
                consoleManager = HypervisorConsoleManager()

                val connection = when (hypervisorType) {
                    TYPE_PROXMOX -> connectProxmox(vmId, vmName)
                    TYPE_XCPNG -> connectXCPng(vmId, vmName)
                    TYPE_XEN_ORCHESTRA -> connectXenOrchestra(vmId, vmName)
                    else -> {
                        showError("Unsupported hypervisor type: $hypervisorType")
                        null
                    }
                }

                if (connection != null) {
                    // Wire console to terminal
                    termuxBridge?.let { bridge ->
                        consoleManager?.wireToTerminal(connection, bridge)
                        isConnected = true
                        hideProgress()
                        Logger.i(TAG, "Console connected for $vmName")
                    }
                } else {
                    showError("Failed to connect to console")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Console connection error", e)
                showError("Connection failed: ${e.message}")
            }
        }
    }

    private suspend fun connectProxmox(vmId: String, vmName: String): HypervisorConsoleManager.ConsoleConnection? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, 8006)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return null
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return null
        val realm = intent.getStringExtra(EXTRA_REALM) ?: "pam"
        val node = intent.getStringExtra(EXTRA_VM_NODE) ?: return null
        val vmType = intent.getStringExtra(EXTRA_VM_TYPE) ?: "qemu"

        return withContext(Dispatchers.IO) {
            val client = ProxmoxApiClient(host, port, username, password, realm, false)
            if (!client.authenticate()) {
                withContext(Dispatchers.Main) {
                    showError("Authentication failed")
                }
                return@withContext null
            }

            consoleManager?.connectProxmoxConsole(
                client = client,
                node = node,
                vmid = vmId.toIntOrNull() ?: return@withContext null,
                vmName = vmName,
                type = vmType,
                listener = createConsoleListener()
            )
        }
    }

    private suspend fun connectXCPng(vmId: String, vmName: String): HypervisorConsoleManager.ConsoleConnection? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, 443)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return null
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return null
        val vmRef = intent.getStringExtra(EXTRA_VM_REF)

        return withContext(Dispatchers.IO) {
            val client = XCPngApiClient(host, port, username, password, false)
            if (!client.authenticate()) {
                withContext(Dispatchers.Main) {
                    showError("Authentication failed")
                }
                return@withContext null
            }

            // Get VM reference if not provided
            val ref = vmRef ?: client.getVMRefByUUID(vmId) ?: return@withContext null

            consoleManager?.connectXCPngConsole(
                client = client,
                vmRef = ref,
                vmName = vmName,
                listener = createConsoleListener()
            )
        }
    }

    private suspend fun connectXenOrchestra(vmId: String, vmName: String): HypervisorConsoleManager.ConsoleConnection? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, 443)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return null
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return null

        return withContext(Dispatchers.IO) {
            val client = XenOrchestraApiClient(host, port, username, password, false)
            if (!client.authenticate()) {
                withContext(Dispatchers.Main) {
                    showError("Authentication failed")
                }
                return@withContext null
            }

            consoleManager?.connectXenOrchestraConsole(
                client = client,
                vmId = vmId,
                vmName = vmName,
                listener = createConsoleListener()
            )
        }
    }

    private fun createConsoleListener() = object : ConsoleEventListener {
        override fun onConnected(vmName: String) {
            runOnUiThread {
                hideProgress()
                Toast.makeText(this@VMConsoleActivity, "Connected to $vmName", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDisconnected(reason: String) {
            runOnUiThread {
                showStatus("Disconnected: $reason")
                isConnected = false
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                showError(message)
            }
        }
    }

    private fun showProgress(message: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        terminalView.visibility = View.GONE
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
    }

    private fun showStatus(message: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Error: $message"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        consoleManager?.disconnect()
        termuxBridge?.cleanup()
    }
}
