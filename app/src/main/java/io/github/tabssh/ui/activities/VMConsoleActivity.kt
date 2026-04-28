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

        // Mobile-first: no action bar / toolbar. The VM name shows briefly
        // in the loading overlay's status text. Disconnect / Reconnect
        // controls live as floating overlay buttons.
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "VM Console"
        statusText.text = "Connecting to $vmName…"
        setupFloatingControls()
        setupCustomKeyboard()

        // Initialize terminal
        setupTerminal()

        // Connect to console
        connectToConsole()
    }

    private fun setupTerminal() {
        // Create TermuxBridge for terminal emulation with user's preferred cursor style
        val app = application as io.github.tabssh.TabSSHApplication
        val cursorStyle = app.preferencesManager.getCursorStyleInt()
        termuxBridge = TermuxBridge(
            columns = 80,
            rows = 24,
            transcriptRows = 2000,
            cursorStyle = cursorStyle
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
                // No-op: there's no action bar to update in mobile-first
                // mode. The escape-sequence title (set by remote shell) is
                // still useful info, but we choose to drop it rather than
                // claw back vertical space for a custom title strip.
                Logger.d(TAG, "Remote terminal title changed: $title (ignored)")
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

        // Wire long-press → context menu (was missing — VM console had
        // no way to copy/paste/send Ctrl keys; only the SSH terminal did).
        terminalView.onContextMenuRequested = { _, _ ->
            showVmConsoleContextMenu()
        }
    }

    private fun showVmConsoleContextMenu() {
        val items = arrayOf(
            "Paste",
            "Send Ctrl+C",
            "Send Ctrl+D",
            "Send Ctrl+Z",
            "Send Esc",
            "Send Tab",
            "Send Enter",
            "Send text…",
            "Toggle keyboard",
            "Disconnect"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("VM Console")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pasteFromClipboard()
                    1 -> sendBytes(byteArrayOf(0x03))                  // ETX
                    2 -> sendBytes(byteArrayOf(0x04))                  // EOT
                    3 -> sendBytes(byteArrayOf(0x1A))                  // SUB
                    4 -> sendBytes(byteArrayOf(0x1B))                  // ESC
                    5 -> sendBytes(byteArrayOf(0x09))                  // TAB
                    6 -> sendBytes(byteArrayOf(0x0D))                  // CR
                    7 -> showSendTextDialog()
                    8 -> {
                        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as android.view.inputmethod.InputMethodManager
                        imm.toggleSoftInput(
                            android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
                    }
                    9 -> confirmDisconnectThenFinish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    private fun showSendTextDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Text to send"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Send text")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) sendBytes(text.toByteArray(Charsets.UTF_8))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendBytes(bytes: ByteArray) {
        try {
            termuxBridge?.write(bytes)
        } catch (e: Exception) {
            Logger.w(TAG, "sendBytes failed: ${e.message}")
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

                        // Setup resize callback for VM console (Proxmox termproxy needs resize messages)
                        bridge.onResizeCallback = { cols, rows ->
                            consoleManager?.getWebSocketClient()?.sendResize(cols, rows)
                        }

                        isConnected = true
                        hideProgress()
                        refreshFloatingControls()  // refresh Disconnect/Reconnect visibility
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
                refreshFloatingControls()  // surfaces the Reconnect menu item
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
        confirmDisconnectThenFinish()
        return true
    }

    override fun onBackPressed() {
        confirmDisconnectThenFinish()
    }

    /**
     * VM consoles are WebSocket-backed — there's no `exit` to type and
     * no SSH layer to politely tear down, so the user needs an obvious
     * way out. Toolbar overflow → "Disconnect", up-arrow, hardware back,
     * and the long-press context menu's "Disconnect" all route here.
     *
     * If the console is still connected, confirm before tearing down so
     * a stray edge-swipe doesn't kill an active session.
     */
    private fun confirmDisconnectThenFinish() {
        if (!isConnected) {
            finish(); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Disconnect VM console?")
            .setMessage("This closes the WebSocket session. The VM keeps running on the hypervisor.")
            .setPositiveButton("Disconnect") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    /**
     * Wire the multi-row keyboard at the bottom of the VM console screen.
     * Same component the SSH terminal uses (so Esc / Tab / arrows / Ctrl
     * work), but events go to the WebSocket-backed bridge instead of an
     * SSH stream. Loads the user's saved layout from PreferenceManager;
     * falls back to default keys if none is configured.
     */
    private fun setupCustomKeyboard() {
        val keyboard = findViewById<io.github.tabssh.ui.keyboard.MultiRowKeyboardView>(
            R.id.multi_row_keyboard
        )
        try {
            val rowCount = app.preferencesManager.getKeyboardRowCount()
            keyboard.setRowCount(rowCount)
            val layoutJson = app.preferencesManager.getKeyboardLayoutJson()
            if (layoutJson != null) {
                val saved = io.github.tabssh.ui.keyboard.KeyboardLayoutManager
                    .parseLayoutJson(layoutJson)
                keyboard.setLayout(saved)
            } else {
                keyboard.resetToDefault()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Custom keyboard layout load failed; defaults: ${e.message}")
            keyboard.resetToDefault()
        }
        keyboard.setOnKeyClickListener { key ->
            // KeyboardKey.keySequence already encodes ESC, arrows, F-keys,
            // etc. Send the bytes verbatim through the same write path as
            // the long-press menu items.
            sendBytes(key.keySequence.toByteArray(Charsets.UTF_8))
        }
    }

    private fun setupFloatingControls() {
        findViewById<android.widget.ImageButton>(R.id.btn_disconnect).setOnClickListener {
            confirmDisconnectThenFinish()
        }
        findViewById<android.widget.Button>(R.id.btn_reconnect).setOnClickListener {
            Logger.i(TAG, "Manual reconnect requested")
            connectToConsole()
        }
        refreshFloatingControls()
    }

    /** Show Disconnect when connected, Reconnect when not. */
    private fun refreshFloatingControls() {
        runOnUiThread {
            findViewById<android.widget.ImageButton>(R.id.btn_disconnect)?.visibility =
                if (isConnected) android.view.View.VISIBLE else android.view.View.GONE
            findViewById<android.widget.Button>(R.id.btn_reconnect)?.visibility =
                if (isConnected) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        consoleManager?.disconnect()
        termuxBridge?.cleanup()
    }

}
