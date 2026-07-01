package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.console.ConsoleEventListener
import io.github.tabssh.hypervisor.console.HypervisorConsoleManager
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.vnc.VncDirectConnector
import io.github.tabssh.hypervisor.vnc.VncStreamHolder
import io.github.tabssh.hypervisor.vnc.console.VncConsoleChannel
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
import io.github.tabssh.ui.views.TerminalView
import io.github.tabssh.ui.views.VncView
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket

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
        // Per-host TLS verification toggle (matches HypervisorProfile.verifySsl).
        // Previously this activity hardcoded `false` for every API client +
        // WebSocket it constructed, silently bypassing whatever the user
        // had set in HypervisorEditActivity. The console flow now honours
        // the per-host setting.
        const val EXTRA_VERIFY_SSL = "verify_ssl"
        // Phase 1 cert pinning — captured leaf SHA-256 from previous
        // connect, threaded through so the console TLS handshake
        // enforces the pin (or captures TOFU on first console connect).
        const val EXTRA_PINNED_CERT_SHA256 = "pinned_cert_sha256"

        // Hypervisor type values
        const val TYPE_PROXMOX = "proxmox"
        const val TYPE_XCPNG = "xcpng"
        const val TYPE_XEN_ORCHESTRA = "xen_orchestra"
        const val TYPE_VMWARE = "vmware"

        // Direct VNC connection extras
        /** Boolean — when true, read streams from VncStreamHolder (libvirt console). */
        const val EXTRA_DIRECT_VNC = "direct_vnc"
        /** String — VncHost UUID; when set, connect directly to that host. */
        const val EXTRA_VNC_HOST_ID = "vnc_host_id"
        /**
         * Boolean — when true, [RfbClient.canRequestResize] is set to false at session start.
         * Set in the activity result when the server closed the connection after a resize
         * rejection so the caller can relaunch without triggering another resize.
         */
        const val EXTRA_DISABLE_RESIZE = "disable_resize"

        // Ad-hoc direct VNC extras — connect without a DB entry.
        // Useful for one-shot connections and debug sessions.
        /** String — hostname or IP to connect to directly (no DB lookup). */
        const val EXTRA_VNC_ADHOC_HOST = "vnc_adhoc_host"
        /** Int — port number; defaults to 5900. */
        const val EXTRA_VNC_ADHOC_PORT = "vnc_adhoc_port"
        /** String — VNC password; may be null for unauthenticated servers. */
        const val EXTRA_VNC_ADHOC_PASSWORD = "vnc_adhoc_password"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var terminalView: TerminalView
    private lateinit var vncView: VncView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var consoleContent: LinearLayout

    // VNC toolbar button references — non-null while in graphical mode
    private var vncCtrlBtn: MaterialButton? = null
    private var vncAltBtn:  MaterialButton? = null
    private var vncWinBtn:  MaterialButton? = null

    private var consoleManager: HypervisorConsoleManager? = null
    private var termuxBridge: TermuxBridge? = null
    private var isConnected = false
    /** True when the active console is graphical (VNC); false for text (serial). */
    private var isGraphicalMode = false
    /** Socket to close when a direct VNC host connection ends. */
    private var directVncSocket: Socket? = null
    /** Job returned by [connectToConsole]'s lifecycleScope.launch; cancelled in [onStop]. */
    private var connectionJob: Job? = null
    /**
     * Set to true in [onStop] when we were connected (or in graphical mode) at the
     * time the activity was backgrounded.  [onResume] consults this flag to decide
     * whether an automatic reconnect is needed.
     */
    private var shouldReconnectOnResume = false
    /**
     * Active [RfbClient] for all VNC paths (Proxmox via manager, libvirt direct,
     * VncHost direct).  Set in [switchToGraphical]; cleared in [onDestroy].
     *
     * Kept here so [onStop]/[onDestroy] can call [RfbClient.stop] — setting
     * [running]=false — BEFORE the underlying pipe is torn down.  Without the
     * stop(), the reader thread sees IOException "Pipe closed" while running=true
     * and logs a spurious E/ error on every user-initiated disconnect.
     */
    private var activeRfbClient: io.github.tabssh.hypervisor.console.rfb.RfbClient? = null
    /**
     * Active VNC console channel (keyboard bridge to RfbClient).
     * Non-null whenever [isGraphicalMode] is true — all VNC paths now
     * use console mode (VncConsoleChannel) rather than raw VncView input.
     */
    private var vncConsoleChannel: VncConsoleChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vm_console)

        app = application as TabSSHApplication

        // Find views
        terminalView = findViewById(R.id.terminal_view)
        vncView = findViewById(R.id.vnc_view)
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        consoleContent = findViewById(R.id.console_content)

        // Pan the console content upward as the soft keyboard animates in/out
        // so the session stays visible above the IME (adjustNothing keeps the
        // window from resizing, which would otherwise send spurious EDS requests
        // to the VNC server on every keyboard show/hide).
        setupKeyboardPan()

        // Modern back-press handling — Activity.onBackPressed() is
        // deprecated, the OnBackPressedDispatcher API is the supported
        // path (and supports predictive-back gestures on Android 14+).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmDisconnectThenFinish()
            }
        })

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
        // TermuxBridge construction triggers first-time DEX verification of
        // kotlinx.coroutines Mutex/Semaphore classes — on the emulator this
        // takes ~5 s on the main thread and trips the ANR watchdog.  For VNC
        // connections termuxBridge is not used at all (graphical path), so
        // blocking onCreate is pure waste.  Construct on IO; all UI wiring
        // happens back on Main after the bridge is ready.
        val app = application as io.github.tabssh.TabSSHApplication
        val cursorStyle = app.preferencesManager.getCursorStyleInt()
        lifecycleScope.launch {
            val bridge = withContext(Dispatchers.IO) {
                TermuxBridge(
                    columns = 80,
                    rows = 24,
                    transcriptRows = 2000,
                    cursorStyle = cursorStyle
                ).also { it.initialize() }
            }

            // UI wiring — back on Main.
            termuxBridge = bridge

            bridge.addListener(object : TermuxBridgeListener {
                override fun onConnected() {
                    Logger.d(TAG, "Terminal connected")
                }

                override fun onDisconnected() {
                    Logger.d(TAG, "Terminal disconnected")
                    runOnUiThread { showStatus("Disconnected") }
                }

                override fun onScreenChanged() {
                    runOnUiThread { terminalView.invalidate() }
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
                    runOnUiThread { terminalView.invalidate() }
                }

                override fun onCursorStateChanged(visible: Boolean) {
                    runOnUiThread { terminalView.invalidate() }
                }

                override fun onCopyToClipboard(text: String) {
                    // Handle clipboard
                }

                override fun onPasteFromClipboard() {
                    // Handle paste
                }

                override fun onError(e: Exception) {
                    Logger.e(TAG, "Terminal error", e)
                    runOnUiThread { showError("Terminal error: ${e.message}") }
                }
            })

            terminalView.attachTerminalEmulator(bridge)

            // Wire long-press → context menu (was missing — VM console had
            // no way to copy/paste/send Ctrl keys; only the SSH terminal did).
            // x,y forwarded so the "Select text…" item can anchor selection
            // mode at the long-press point.
            terminalView.onContextMenuRequested = { x, y ->
                showVmConsoleContextMenu(x, y)
            }
        }
    }

    private fun showVmConsoleContextMenu(x: Float, y: Float) {
        val items = arrayOf(
            "Copy screen",
            "Select text…",
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
                    0 -> copyScreenToClipboard()
                    1 -> beginSelection(x, y)
                    2 -> pasteFromClipboard()
                    // In VNC mode route via VncConsoleChannel → X11 keysyms.
                    // In text/serial mode send the raw byte over the WebSocket.
                    3 -> if (isGraphicalMode) vncConsoleChannel?.sendCtrlChar('c')
                         else sendBytes(byteArrayOf(0x03))             // ETX
                    4 -> if (isGraphicalMode) vncConsoleChannel?.sendCtrlChar('d')
                         else sendBytes(byteArrayOf(0x04))             // EOT
                    5 -> if (isGraphicalMode) vncConsoleChannel?.sendCtrlChar('z')
                         else sendBytes(byteArrayOf(0x1A))             // SUB
                    6 -> if (isGraphicalMode) vncConsoleChannel?.sendKey(RfbConstants.KEY_ESCAPE)
                         else sendBytes(byteArrayOf(0x1B))             // ESC
                    7 -> if (isGraphicalMode) vncConsoleChannel?.sendKey(RfbConstants.KEY_TAB)
                         else sendBytes(byteArrayOf(0x09))             // TAB
                    8 -> if (isGraphicalMode) vncConsoleChannel?.sendKey(RfbConstants.KEY_RETURN)
                         else sendBytes(byteArrayOf(0x0D))             // CR
                    9 -> showSendTextDialog()
                    10 -> {
                        // toggleSoftInput(SHOW_FORCED, …) is deprecated.
                        // Use the WindowInsetsController IME toggle which
                        // works against the active focus and supports the
                        // predictive-back gesture path.
                        val view = window.decorView
                        val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()
                        val visible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                            ?.isVisible(imeType) == true
                        val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
                        if (visible) ic.hide(imeType) else ic.show(imeType)
                    }
                    11 -> confirmDisconnectThenFinish()
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
        if (isGraphicalMode) vncConsoleChannel?.sendText(text)
        else sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Drag-to-select range copy for the VM console (issue #73).
     * Mirrors the SSH-terminal flow: enter selection mode at the
     * long-press point, then start a floating ActionMode with a Copy
     * item. Single shared `selectionActionMode` so we can finish() it
     * from any code path.
     */
    private var selectionActionMode: android.view.ActionMode? = null

    private fun beginSelection(x: Float, y: Float) {
        terminalView.enterSelectionMode(x, y)
        startTerminalSelectionActionMode()
    }

    private fun startTerminalSelectionActionMode() {
        selectionActionMode?.finish()
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                mode.title = "Select"
                menu.add(0, 1, 0, "Copy")
                    .setIcon(android.R.drawable.ic_menu_set_as)
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId == 1) {
                    val text = terminalView.getSelectedText()
                    if (text.isNullOrEmpty()) {
                        Toast.makeText(this@VMConsoleActivity, "Nothing selected", Toast.LENGTH_SHORT).show()
                    } else {
                        io.github.tabssh.utils.ClipboardHelper.copy(this@VMConsoleActivity, "VM Console selection", text, sensitive = false)
                        Toast.makeText(this@VMConsoleActivity, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
                    }
                    mode.finish()
                    return true
                }
                return false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                terminalView.exitSelectionMode()
                if (selectionActionMode === mode) selectionActionMode = null
            }
        }
        selectionActionMode = if (android.os.Build.VERSION.SDK_INT >= 23) {
            terminalView.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        } else {
            terminalView.startActionMode(callback)
        }
    }

    /**
     * Copy the visible VM-console screen + recent scrollback to the
     * system clipboard. VMConsoleActivity previously had no copy path
     * at all (only Paste / Send keys / Disconnect in its long-press
     * menu), so users couldn't pull boot-time output / serial-console
     * messages off the device.
     */
    private fun copyScreenToClipboard() {
        val bridge = termuxBridge
        if (bridge == null) {
            Toast.makeText(this, "Console not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val screen = try { bridge.getScreenContent() } catch (e: Exception) { "" }
        val scrollback = try { bridge.getScrollbackContent() } catch (e: Exception) { "" }
        val text = if (scrollback.isNotEmpty()) "$scrollback\n$screen" else screen
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing on screen to copy", Toast.LENGTH_SHORT).show()
            return
        }
        io.github.tabssh.utils.ClipboardHelper.copy(this, "VM Console", text, sensitive = false)
        Toast.makeText(this, "Console screen copied", Toast.LENGTH_SHORT).show()
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
                if (text.isNotEmpty()) {
                    if (isGraphicalMode) vncConsoleChannel?.sendText(text)
                    else sendBytes(text.toByteArray(Charsets.UTF_8))
                }
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
        val vmName = intent.getStringExtra(EXTRA_VM_NAME) ?: "VM Console"
        showProgress("Connecting to $vmName…")

        connectionJob = lifecycleScope.launch {
            try {
                // ── Direct VNC paths take priority over hypervisor connections ──

                // Path 0: Ad-hoc direct VNC — host/port/password provided in the
                // intent without a DB entry.  Useful for one-shot connections and
                // debug sessions launched directly via `adb shell am start`.
                val adhocHost = intent.getStringExtra(EXTRA_VNC_ADHOC_HOST)
                if (adhocHost != null) {
                    connectVncAdhoc(
                        host     = adhocHost,
                        port     = intent.getIntExtra(EXTRA_VNC_ADHOC_PORT, 5900),
                        password = intent.getStringExtra(EXTRA_VNC_ADHOC_PASSWORD),
                        vmName   = vmName
                    )
                    return@launch
                }

                // Path 1: Libvirt console — streams pre-stored in VncStreamHolder.
                if (intent.getBooleanExtra(EXTRA_DIRECT_VNC, false)) {
                    connectFromStreamHolder(vmName)
                    return@launch
                }

                // Path 2: Direct VncHost connection by ID.
                val vncHostId = intent.getStringExtra(EXTRA_VNC_HOST_ID)
                if (vncHostId != null) {
                    connectVncHost(vncHostId)
                    return@launch
                }

                // ── Standard hypervisor path ──────────────────────────────────
                val hypervisorType = intent.getStringExtra(EXTRA_HYPERVISOR_TYPE)
                val vmId = intent.getStringExtra(EXTRA_VM_ID)

                if (hypervisorType == null || vmId == null) {
                    showError("Missing hypervisor or VM information")
                    return@launch
                }

                consoleManager = HypervisorConsoleManager()

                val connection = when (hypervisorType) {
                    TYPE_PROXMOX -> connectProxmox(vmId, vmName)
                    TYPE_XCPNG -> connectXCPng(vmId, vmName)
                    TYPE_XEN_ORCHESTRA -> connectXenOrchestra(vmId, vmName)
                    TYPE_VMWARE -> {
                        // VMware serial/VNC console via RFB is not yet implemented.
                        // VMwareManagerActivity opens a TabTerminalActivity (SSH) path
                        // for VM access. This branch should not be reachable in normal
                        // flow; surface a clear message in case it is.
                        showError("VMware console is not supported in this build. Connect via SSH from the VMware VM list instead.")
                        null
                    }
                    else -> {
                        showError("Unsupported hypervisor type: $hypervisorType")
                        null
                    }
                }

                when (connection) {
                    is HypervisorConsoleManager.ConsoleConnection.Text -> {
                        termuxBridge?.let { bridge ->
                            consoleManager?.wireToTerminal(connection, bridge)
                            bridge.onResizeCallback = { cols, rows ->
                                consoleManager?.getWebSocketClient()?.sendResize(cols, rows)
                            }
                        }
                        isConnected = true
                        isGraphicalMode = false
                        hideProgress()
                        refreshFloatingControls()
                        Logger.i(TAG, "Text console connected for $vmName")
                    }
                    is HypervisorConsoleManager.ConsoleConnection.Graphical -> {
                        switchToGraphical(connection)
                    }
                    null -> Unit // error already surfaced via listener
                }
                // Do NOT show a generic "Failed to connect" here. Every code path
                // that returns null from connectProxmox / connectXCPng /
                // connectXenOrchestra has already called listener?.onError() or
                // showError() with a specific message. Adding a second error here
                // caused a double-dialog race: the specific message (queued via
                // runOnUiThread from the IO thread) arrived *after* the generic one
                // (posted directly on main), so users saw "Failed to connect" first,
                // dismissed it ("shows ok"), and then the real "serial interface"
                // error appeared — making it look like the error wasn't being caught.
            } catch (e: Exception) {
                Logger.e(TAG, "Console connection error", e)
                val vmLabel = intent.getStringExtra(EXTRA_VM_NAME) ?: "VM Console"
                showError("Connection failed: vm $vmLabel: ${e.message}")
            }
        }
    }

    /**
     * Path 0 — Ad-hoc direct VNC: connect to [host]:[port] with optional [password]
     * without requiring a DB entry.  The TCP connection and RFB handshake are
     * identical to Path 2; this path just skips the DB lookup.
     */
    private suspend fun connectVncAdhoc(host: String, port: Int, password: String?, vmName: String) {
        val hasVncAuth = password != null
        Logger.i(TAG, "Ad-hoc VNC connect → $host:$port (auth=${if (hasVncAuth) "set" else "none"})")
        val socket = withContext(Dispatchers.IO) {
            java.net.Socket(host, port)
        }
        directVncSocket = socket
        val rfbClient = RfbClient(
            inputStream  = socket.getInputStream(),
            outputStream = socket.getOutputStream(),
            vncPassword  = password,
            consoleMode  = true
        )
        val connection = HypervisorConsoleManager.ConsoleConnection.Graphical(
            vmName          = vmName,
            hypervisorType  = HypervisorConsoleManager.HypervisorType.PROXMOX,
            rfbClient       = rfbClient
        )
        withContext(Dispatchers.Main) { switchToGraphical(connection) }
    }

    /**
     * Path 1 — Libvirt direct VNC: retrieve pre-built streams from [VncStreamHolder]
     * and hand them to [RfbClient].
     */
    private fun connectFromStreamHolder(vmName: String) {
        val streams = VncStreamHolder.take()
        if (streams == null) {
            showError("VNC stream not available — please retry from the VM list")
            return
        }
        val (ins, out, socket) = streams
        directVncSocket = socket
        val rfbClient = RfbClient(
            inputStream = ins,
            outputStream = out,
            vncPassword = null,
            consoleMode = true
        )
        // Honour the resize-suppression flag set by LibvirtManagerActivity when
        // relaunching after the server closed the connection due to a resize rejection.
        if (intent.getBooleanExtra(EXTRA_DISABLE_RESIZE, false)) {
            rfbClient.canRequestResize = false
        }
        val connection = HypervisorConsoleManager.ConsoleConnection.Graphical(
            vmName = vmName,
            hypervisorType = HypervisorConsoleManager.HypervisorType.LIBVIRT,
            rfbClient = rfbClient
        )
        switchToGraphical(connection)
    }

    /**
     * Path 2 — Direct VncHost: load the host from DB, resolve credentials,
     * establish a TCP connection, and hand off to [switchToGraphical].
     *
     * @param disableResize When true, [RfbClient.canRequestResize] is set to false before
     *   starting the session. Used for reconnects after a server closes the connection
     *   in response to a resize request it cannot service.
     */
    private suspend fun connectVncHost(vncHostId: String, disableResize: Boolean = false) {
        val app = application as TabSSHApplication
        val host = withContext(Dispatchers.IO) {
            app.database.vncHostDao().getById(vncHostId)
        }
        if (host == null) {
            showError("VNC host not found (id=$vncHostId)")
            return
        }
        val (password, username) = withContext(Dispatchers.IO) {
            val identityId = host.identityId
            // Per-host password override always takes priority.
            val hostPw = try {
                app.securePasswordManager.retrievePassword("vnc_host_$vncHostId")
            } catch (e: Exception) {
                Logger.w(TAG, "Could not retrieve VNC host password: ${e.message}")
                null
            }
            if (hostPw != null) {
                // Host override present: use it; username still comes from identity if one is linked.
                val identityUsername = if (identityId != null) {
                    app.database.vncIdentityDao().getById(identityId)?.username
                } else null
                Pair(hostPw, identityUsername)
            } else if (identityId != null) {
                val identity = app.database.vncIdentityDao().getById(identityId)
                val pw = try {
                    app.securePasswordManager.retrievePassword("vnc_identity_$identityId")
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not retrieve VNC identity password: ${e.message}")
                    null
                }
                Pair(pw, identity?.username)
            } else {
                Pair(null, null)
            }
        }
        val (rfbClient, socket) = withContext(Dispatchers.IO) {
            VncDirectConnector.connect(host, password, username, this@VMConsoleActivity)
        }
        if (disableResize) rfbClient.canRequestResize = false
        directVncSocket = socket
        val connection = HypervisorConsoleManager.ConsoleConnection.Graphical(
            vmName = host.name,
            hypervisorType = HypervisorConsoleManager.HypervisorType.PROXMOX,
            rfbClient = rfbClient
        )
        withContext(Dispatchers.IO) {
            app.database.vncHostDao().updateLastConnected(vncHostId, System.currentTimeMillis())
        }
        switchToGraphical(connection)
    }

    private suspend fun connectProxmox(vmId: String, vmName: String): HypervisorConsoleManager.ConsoleConnection? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: run {
            Logger.e(TAG, "connectProxmox: missing EXTRA_HOST in intent")
            showError("Missing host — cannot connect to Proxmox console")
            return null
        }
        val port = intent.getIntExtra(EXTRA_PORT, 8006)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: run {
            Logger.e(TAG, "connectProxmox: missing EXTRA_USERNAME in intent")
            showError("Missing username — cannot connect to Proxmox console")
            return null
        }
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: run {
            Logger.e(TAG, "connectProxmox: missing EXTRA_PASSWORD in intent")
            showError("Missing password — cannot connect to Proxmox console")
            return null
        }
        val realm = intent.getStringExtra(EXTRA_REALM) ?: "pam"
        val node = intent.getStringExtra(EXTRA_VM_NODE) ?: run {
            Logger.e(TAG, "connectProxmox: missing EXTRA_VM_NODE in intent")
            showError("Missing VM node — cannot connect to Proxmox console")
            return null
        }
        val vmType = intent.getStringExtra(EXTRA_VM_TYPE) ?: "qemu"
        val verifySsl = intent.getBooleanExtra(EXTRA_VERIFY_SSL, false)
        val pinnedSha = intent.getStringExtra(EXTRA_PINNED_CERT_SHA256)?.takeIf { it.isNotBlank() }

        return withContext(Dispatchers.IO) {
            val client = ProxmoxApiClient(host, port, username, password, realm, verifySsl, pinnedSha)
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
                verifySsl = verifySsl,
                pinnedCertSha256 = pinnedSha,
                displayHost = host,
                displayPort = port,
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
        val verifySsl = intent.getBooleanExtra(EXTRA_VERIFY_SSL, false)
        val pinnedSha = intent.getStringExtra(EXTRA_PINNED_CERT_SHA256)?.takeIf { it.isNotBlank() }

        return withContext(Dispatchers.IO) {
            val client = XCPngApiClient(host, port, username, password, verifySsl, pinnedSha)
            if (!client.authenticate()) {
                withContext(Dispatchers.Main) {
                    showError("Authentication failed")
                }
                return@withContext null
            }

            // Get VM reference if not provided
            val ref = vmRef ?: client.getVMRefByUUID(vmId) ?: run {
                Logger.e(TAG, "connectXCPng: could not resolve VM ref for vmId=$vmId")
                withContext(Dispatchers.Main) {
                    showError("Could not find VM reference on XCP-ng host")
                }
                return@withContext null
            }

            consoleManager?.connectXCPngConsole(
                client = client,
                vmRef = ref,
                vmName = vmName,
                verifySsl = verifySsl,
                pinnedCertSha256 = pinnedSha,
                displayHost = host,
                displayPort = port,
                listener = createConsoleListener()
            )
        }
    }

    private suspend fun connectXenOrchestra(vmId: String, vmName: String): HypervisorConsoleManager.ConsoleConnection? {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return null
        val port = intent.getIntExtra(EXTRA_PORT, 443)
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return null
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return null
        val verifySsl = intent.getBooleanExtra(EXTRA_VERIFY_SSL, false)
        val pinnedSha = intent.getStringExtra(EXTRA_PINNED_CERT_SHA256)?.takeIf { it.isNotBlank() }

        return withContext(Dispatchers.IO) {
            val client = XenOrchestraApiClient(host, port, username, password, verifySsl, pinnedSha)
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
                verifySsl = verifySsl,
                pinnedCertSha256 = pinnedSha,
                displayHost = host,
                displayPort = port,
                listener = createConsoleListener()
            )
        }
    }

    /**
     * Switch the activity to VNC console mode.
     *
     * All VNC paths use console mode: the framebuffer is rendered through
     * [VncView] (pixel display), but keyboard input is routed through a
     * [VncConsoleChannel] so both the custom keyboard bar and the Android
     * system keyboard work identically to the SSH terminal.  The keyboard
     * bar stays visible; mouse pointer events are not forwarded.
     *
     * Called both from [connectToConsole] (direct VNC connection) and from
     * [createConsoleListener]'s [onSwitchToGraphical] (Proxmox fallback).
     */
    private fun switchToGraphical(connection: HypervisorConsoleManager.ConsoleConnection.Graphical) {
        Logger.i(TAG, "Switching to VNC console mode for ${connection.vmName}")
        isGraphicalMode = true
        isConnected = true

        val rfb = connection.rfbClient
        activeRfbClient = rfb

        // Build the keyboard bridge.  Initial size is NOT set via setInitialSize()
        // because character-grid math (cols * fontW = 640 px) resets the server
        // framebuffer to blank before any pixels arrive.  Instead, the VncView
        // reports its pixel dimensions via onViewSizeReady and we forward those
        // directly to sendSetDesktopSize() — see below.
        val channel = VncConsoleChannel(rfb)
        vncConsoleChannel = channel

        // Wire VncView for framebuffer rendering only — no pointer or key
        // events from VncView itself; those go through VncConsoleChannel.
        val rfbListener = vncView.asRfbListener()
        rfb.listener = channel.wrapListener(object : io.github.tabssh.hypervisor.console.rfb.RfbListener by rfbListener {
            override fun onConnected(width: Int, height: Int, name: String,
                                     framebuffer: IntArray) {
                rfbListener.onConnected(width, height, name, framebuffer)
                runOnUiThread {
                    terminalView.visibility = View.GONE
                    vncView.visibility = View.VISIBLE
                    // Force a redraw now that the view is visible — the bitmap may
                    // already contain decoded framebuffer pixels that arrived while
                    // the view was GONE (postInvalidate on a GONE view is a no-op).
                    vncView.postInvalidate()
                    // Request focus so hardware keys and the custom keyboard bar
                    // route input to the VNC view.  The Android soft keyboard is
                    // NOT auto-shown here — the user can tap the framebuffer to
                    // toggle it when needed.
                    vncView.requestFocus()
                    // restartInput tells the IME to rebind its InputConnection to
                    // vncView.  Without it the IME keeps the stale connection to
                    // the previous focused view (typically the terminal), so soft-
                    // keyboard commitText() events never reach VncView.onTextInput
                    // and typed characters are silently discarded.
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .restartInput(vncView)
                    hideProgress()
                    refreshFloatingControls()
                }
            }
            override fun onError(message: String) {
                rfbListener.onError(message)
                runOnUiThread { showError(message) }
            }
            override fun onDisconnected(reason: String) {
                rfbListener.onDisconnected(reason)
                runOnUiThread {
                    isConnected = false
                    // Reset graphical mode so a subsequent reconnect to a
                    // text/serial console isn't routed through the now-null
                    // vncConsoleChannel (custom keyboard taps would otherwise
                    // be silently dropped).
                    isGraphicalMode = false
                    // Restore the keyboard bar; hide the VNC toolbar.
                    clearArmedModifierButtons()
                    vncConsoleChannel?.clearArmedModifiers()
                    findViewById<View>(R.id.vnc_toolbar)?.visibility = View.GONE
                    findViewById<View>(R.id.multi_row_keyboard).visibility = View.VISIBLE
                    vncConsoleChannel?.close()
                    vncConsoleChannel = null
                    activeRfbClient = null
                    // Clear VncView callbacks — they capture the now-closed
                    // VncConsoleChannel.  If left set, a touch or key event on
                    // the still-visible framebuffer after disconnect will call
                    // channel.sendPointerEvent() → executor.execute() on a
                    // Terminated executor → RejectedExecutionException crash
                    // (observed: 34 s after disconnect on SM-X230, API 36).
                    vncView.onPointerEvent = null
                    vncView.onKeyEvent = null
                    vncView.onTextInput = null
                    vncView.onBackspace = null
                    vncView.onViewSizeReady = null

                    // The server closed immediately after rejecting our resize request.
                    // Automatically reconnect with resize suppressed so the user keeps
                    // a working session at the server's native resolution.
                    //
                    // Two paths share this recovery:
                    //   • Direct VncHost (Path 2): reconnect via connectVncHost with disableResize=true.
                    //   • Hypervisor VNC (Proxmox vncproxy): re-issue the VNC ticket and
                    //     reconnect via HypervisorConsoleManager.reconnectGraphicalWithoutResize.
                    if (reason == "Server closed after resize rejection" && !isFinishing && !isDestroyed) {
                        val vncHostId = intent.getStringExtra(EXTRA_VNC_HOST_ID)
                        val mgr = consoleManager
                        when {
                            vncHostId != null -> {
                                showProgress("Reconnecting without resize…")
                                lifecycleScope.launch {
                                    try {
                                        connectVncHost(vncHostId, disableResize = true)
                                    } catch (e: Exception) {
                                        Logger.e(TAG, "Reconnect after resize rejection failed", e)
                                        showStatus("Disconnected: server does not support resize")
                                        refreshFloatingControls()
                                    }
                                }
                            }
                            mgr != null -> {
                                showProgress("Reconnecting without resize…")
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        mgr.reconnectGraphicalWithoutResize(createConsoleListener())
                                    } catch (e: Exception) {
                                        Logger.e(TAG, "Hypervisor reconnect after resize rejection failed", e)
                                        withContext(Dispatchers.Main) {
                                            showStatus("Disconnected: server does not support resize")
                                            refreshFloatingControls()
                                        }
                                    }
                                }
                            }
                            else -> {
                                // Libvirt path (Path 1): streams are one-shot from
                                // VncStreamHolder — we cannot reconnect here. Signal the
                                // caller (LibvirtManagerActivity) to re-open a fresh VNC
                                // channel with resize suppressed. The activity result
                                // carries EXTRA_DISABLE_RESIZE=true so the caller knows
                                // to set rfbClient.canRequestResize=false on the next
                                // session.
                                setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_DISABLE_RESIZE, true))
                                finish()
                            }
                        }
                        return@runOnUiThread
                    }

                    showStatus("Disconnected: $reason")
                    refreshFloatingControls()
                }
            }
        })

        // Wire pointer events: finger taps and drags are forwarded to the VM.
        vncView.onPointerEvent = { x, y, mask -> channel.sendPointerEvent(x, y, mask) }

        // Wire VncView hardware-key fallback: handles cases where the activity's
        // dispatchKeyEvent did not consume the event (ACTION_UP, unrecognised keys).
        vncView.onKeyEvent = { keysym, down -> channel.sendRawKeyEvent(keysym, down) }

        // Wire Android soft-keyboard input: IME commitText() → keysym sequence.
        // Without this, every character typed on the Android soft keyboard is
        // silently dropped because View.onCreateInputConnection() is a no-op.
        vncView.onTextInput = { text -> channel.sendText(text) }

        // Wire soft-keyboard backspace: IME deleteSurroundingText() / KEYCODE_DEL
        // key events sent through the InputConnection route here.
        vncView.onBackspace = { channel.sendKey(RfbConstants.KEY_BACK_SPACE) }

        // Wire resize: when VncView is measured (or re-measured on rotation),
        // send the pixel dimensions to the server as SetDesktopSize.
        // Using the view's actual pixel dimensions — not a character-grid
        // calculation — prevents the server from resizing to a blank 640×384
        // framebuffer before any pixels arrive.
        vncView.onViewSizeReady = { w, h -> channel.resizeToPixels(w, h) }
        // If the view is already measured (e.g. reconnect after rotation), fire now.
        if (vncView.width > 0 && vncView.height > 0) {
            channel.resizeToPixels(vncView.width, vncView.height)
        }

        rfb.start()

        runOnUiThread {
            // Hide the text-console keyboard bar; show the VNC-specific toolbar.
            findViewById<View>(R.id.multi_row_keyboard).visibility = View.GONE
            setupVncToolbar(channel)
            hideProgress()
            refreshFloatingControls()
        }
    }

    private fun createConsoleListener() = object : ConsoleEventListener {
        override fun onConnected(vmName: String) {
            runOnUiThread { hideProgress() }
        }

        override fun onDisconnected(reason: String) {
            runOnUiThread {
                showStatus("Disconnected: $reason")
                isConnected = false
                refreshFloatingControls()
            }
        }

        override fun onError(message: String) {
            runOnUiThread { showError(message) }
        }

        override fun onSerialConsoleUnavailable() {
            // Serial console unavailability is an internal implementation detail —
            // HypervisorConsoleManager already logs it at DEBUG and retries with
            // vncproxy transparently. Only surface an error if VNC also fails
            // (that path calls onError() above). No dialog needed here.
        }

        override fun onSwitchToGraphical(
            connection: HypervisorConsoleManager.ConsoleConnection.Graphical
        ) {
            runOnUiThread { switchToGraphical(connection) }
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
        if (isGraphicalMode) {
            vncView.visibility = View.VISIBLE
            terminalView.visibility = View.GONE
        } else {
            terminalView.visibility = View.VISIBLE
            vncView.visibility = View.GONE
        }
    }

    private fun showStatus(message: String) {
        statusText.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun showError(message: String) {
        // Defense-in-depth: an async listener (WebSocket reader thread)
        // can fire onError after the user already tapped Disconnect — by
        // the time the runOnUiThread lambda runs, the activity may be
        // finishing and our window token is dead. Showing an AlertDialog
        // then throws BadTokenException and crashes the process.
        // ConsoleWebSocketClient.disconnect() now suppresses post-close
        // failures, but there are still hypervisor-side error paths that
        // can race with finish(); guard here too.
        if (isFinishing || isDestroyed) {
            Logger.w(TAG, "showError suppressed (activity finishing): $message")
            return
        }
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Error: $message"
        // Issue #167 — surface a copyable error dialog in addition to the
        // inline status. Toasts vanish; users that want to file a bug
        // need a way to capture the exact message.
        io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(this, "VM Console Error", message)
    }

    /**
     * Pan [consoleContent] upward as the IME animates in/out so the session
     * remains fully visible above the soft keyboard.
     *
     * The window uses `adjustNothing` so Android never resizes the view — which
     * would otherwise send a spurious EDS (Extended Desktop Size) request to the
     * VNC server on every keyboard show/hide.  We compensate by translating the
     * content ourselves, frame-synced with the keyboard animation via
     * [WindowInsetsAnimationCompat].
     *
     * Navigation-bar insets are subtracted because they are already consumed by
     * the root view's `fitsSystemWindows=true` padding; only the extra height
     * added by the IME needs to be compensated.
     */
    private fun setupKeyboardPan() {
        ViewCompat.setWindowInsetsAnimationCallback(
            window.decorView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyImeShift(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    // Snap to the settled position in case floating-point
                    // accumulation left a sub-pixel gap.
                    val insets = ViewCompat.getRootWindowInsets(window.decorView) ?: return
                    applyImeShift(insets)
                }
            }
        )
    }

    private fun applyImeShift(insets: WindowInsetsCompat) {
        val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        consoleContent.translationY = -(imeBottom - navBottom).coerceAtLeast(0).toFloat()
    }

    /**
     * Route system-keyboard input to [VncConsoleChannel] when in VNC console mode.
     * This intercepts key events from the Android IME and hardware keyboard before
     * the default dispatch chain so they reach the RFB layer as X11 keysyms rather
     * than being consumed by the focused view.
     *
     * Both ACTION_DOWN and ACTION_UP are consumed in VNC mode:
     *  - ACTION_DOWN  → [VncConsoleChannel.sendKeyEvent] sends the complete down+up
     *    keysym pair to the RFB server, then returns true so the event is consumed.
     *  - ACTION_UP    → [VncConsoleChannel.sendKeyEvent] returns false (it only acts
     *    on ACTION_DOWN).  Without an explicit consume here, Android's default dispatch
     *    would reach [VncView.onKeyUp], which maps the key code to a keysym and invokes
     *    [VncConsoleChannel.sendRawKeyEvent] with down=false — producing a second KeyUp
     *    for every key that has an explicit keysym mapping (Enter, arrows, F-keys, …).
     *    Consuming ACTION_UP prevents that duplicate.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val channel = vncConsoleChannel
        if (isGraphicalMode && channel != null) {
            if (channel.sendKeyEvent(event)) return true
            if (event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSupportNavigateUp(): Boolean {
        confirmDisconnectThenFinish()
        return true
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
        // Wire the click listener immediately so any tap that arrives before
        // the deferred layout runs is still routed correctly.
        keyboard.setOnKeyClickListener { key ->
            if (isGraphicalMode) {
                // VNC console mode: route through VncConsoleChannel which
                // translates sequences to X11 keysyms (same path as SSH
                // uses ANSI sequences, but here they become RFB KeyEvents).
                vncConsoleChannel?.sendSequence(key.keySequence)
            } else {
                // Text console: send the byte sequence verbatim.
                sendBytes(key.keySequence.toByteArray(Charsets.UTF_8))
            }
        }
        // Defer MaterialButton construction to after the initial layout pass.
        // Creating ~27 buttons synchronously in onCreate() blocks the main
        // thread for several seconds on a cold theme cache (emulator / first
        // launch on device), which crosses the ANR threshold.  Deferring here
        // ensures the activity becomes interactive before any button work runs.
        keyboard.post {
            try {
                val rowCount = app.preferencesManager.getKeyboardRowCount()
                keyboard.setRowCount(rowCount)

                // Auto-migrate default layout when built-in layout version advances
                // and the user has not explicitly customised their layout.
                val storedVersion = app.preferencesManager.getKeyboardLayoutVersion()
                val isCustomized  = app.preferencesManager.isKeyboardLayoutCustomized()
                if (storedVersion < io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION && !isCustomized) {
                    Logger.i(TAG, "Default layout updated (v$storedVersion → v${io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION}); clearing saved layout")
                    app.preferencesManager.setKeyboardLayoutJson(null)
                    app.preferencesManager.setKeyboardLayoutVersion(io.github.tabssh.ui.keyboard.MultiRowKeyboardView.CURRENT_DEFAULT_LAYOUT_VERSION)
                }

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
        }
    }

    /**
     * Wire the VNC floating toolbar buttons to [channel].
     * Called from [switchToGraphical] on the main thread.
     */
    private fun setupVncToolbar(channel: VncConsoleChannel) {
        val toolbar = findViewById<View>(R.id.vnc_toolbar) ?: return
        toolbar.visibility = View.VISIBLE

        vncCtrlBtn = toolbar.findViewById(R.id.vnc_btn_ctrl)
        vncAltBtn  = toolbar.findViewById(R.id.vnc_btn_alt)
        vncWinBtn  = toolbar.findViewById(R.id.vnc_btn_win)

        // When armed mods are consumed by a key, un-highlight the modifier buttons.
        channel.onArmedModsConsumed = { runOnUiThread { refreshModifierButtons(channel) } }

        // Keyboard toggle
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_keyboard)?.setOnClickListener {
            val view = window.decorView
            val imeType = androidx.core.view.WindowInsetsCompat.Type.ime()
            val visible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(imeType) == true
            val ic = androidx.core.view.WindowCompat.getInsetsController(window, view)
            if (visible) ic.hide(imeType) else {
                vncView.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .restartInput(vncView)
                ic.show(imeType)
            }
        }

        // Sticky modifiers — toggle arm state, update button highlight.
        vncCtrlBtn?.setOnClickListener {
            channel.armModifier(VncConsoleChannel.VncModifier.CTRL)
            refreshModifierButtons(channel)
        }
        vncAltBtn?.setOnClickListener {
            channel.armModifier(VncConsoleChannel.VncModifier.ALT)
            refreshModifierButtons(channel)
        }
        vncWinBtn?.setOnClickListener {
            channel.armModifier(VncConsoleChannel.VncModifier.WIN)
            refreshModifierButtons(channel)
        }

        // Immediate key sends
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_esc)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_ESCAPE)
        }
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_tab)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_TAB)
        }

        // F-key picker
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_fkeys)?.setOnClickListener {
            showFKeyPicker(channel)
        }

        // Arrow keys
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_arrow_left)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_LEFT)
        }
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_arrow_up)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_UP)
        }
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_arrow_down)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_DOWN)
        }
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_arrow_right)?.setOnClickListener {
            channel.sendKey(RfbConstants.KEY_RIGHT)
        }

        // More overflow menu
        toolbar.findViewById<MaterialButton>(R.id.vnc_btn_more)?.setOnClickListener { anchor ->
            showVncMoreMenu(anchor, channel)
        }
    }

    private fun refreshModifierButtons(channel: VncConsoleChannel) {
        vncCtrlBtn?.isChecked = channel.isModifierArmed(VncConsoleChannel.VncModifier.CTRL)
        vncAltBtn?.isChecked  = channel.isModifierArmed(VncConsoleChannel.VncModifier.ALT)
        vncWinBtn?.isChecked  = channel.isModifierArmed(VncConsoleChannel.VncModifier.WIN)
    }

    private fun clearArmedModifierButtons() {
        vncCtrlBtn?.isChecked = false
        vncAltBtn?.isChecked  = false
        vncWinBtn?.isChecked  = false
        vncCtrlBtn = null
        vncAltBtn  = null
        vncWinBtn  = null
    }

    private fun showFKeyPicker(channel: VncConsoleChannel) {
        val keys = arrayOf("F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12")
        val keysyms = longArrayOf(
            RfbConstants.KEY_F1,
            RfbConstants.KEY_F2,
            RfbConstants.KEY_F3,
            RfbConstants.KEY_F4,
            RfbConstants.KEY_F5,
            RfbConstants.KEY_F6,
            RfbConstants.KEY_F7,
            RfbConstants.KEY_F8,
            RfbConstants.KEY_F9,
            RfbConstants.KEY_F10,
            RfbConstants.KEY_F11,
            RfbConstants.KEY_F12,
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Function keys")
            .setItems(keys) { _, i -> channel.sendKey(keysyms[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVncMoreMenu(anchor: View, channel: VncConsoleChannel) {
        val items = arrayOf(
            "Send Ctrl+Alt+Del",
            "Send text…",
            "Copy screen",
            "Zoom to fit",
            "Zoom 1:1",
            "Disconnect"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("VNC options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // Ctrl+Alt+Del: arm Ctrl+Alt, then send Delete key.
                        val ch = vncConsoleChannel ?: return@setItems
                        ch.armModifier(VncConsoleChannel.VncModifier.CTRL)
                        ch.armModifier(VncConsoleChannel.VncModifier.ALT)
                        ch.sendKey(RfbConstants.KEY_DELETE)
                        runOnUiThread { refreshModifierButtons(ch) }
                    }
                    1 -> showSendTextDialog()
                    2 -> copyScreenToClipboard()
                    3 -> vncView.resetZoom()
                    4 -> vncView.zoomActual()
                    5 -> confirmDisconnectThenFinish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onResume() {
        super.onResume()
        // Auto-reconnect when returning from background if onStop() tore down
        // an active session.  shouldReconnectOnResume is set only in onStop(),
        // so this is a no-op on the very first onResume() after onCreate().
        if (shouldReconnectOnResume && !isConnected) {
            shouldReconnectOnResume = false
            // TermuxBridge was cleaned up and nulled in onStop(); rebuild it so
            // the serial-console path (text mode) works again after reconnect.
            if (termuxBridge == null) setupTerminal()
            connectToConsole()
        }
    }

    override fun onStop() {
        super.onStop()
        // Cancel any in-flight connection coroutine so it cannot call
        // switchToGraphical() after we've already nulled activeRfbClient and
        // vncConsoleChannel below.  lifecycleScope coroutines survive onStop()
        // (they only cancel at DESTROYED), so an explicit cancel is needed.
        connectionJob?.cancel()
        connectionJob = null

        // Remember that the user was actively connected so onResume() can
        // re-establish the session automatically when the activity comes back.
        shouldReconnectOnResume = isConnected || isGraphicalMode

        // Disconnect the console WebSocket/RFB loop when the activity is
        // backgrounded. The tight RFB polling loop writes framebuffer data to
        // a pipe; if the pipe buffer fills (no consumer reading while hidden),
        // blocking writes stall the I/O thread pool and make the app
        // unresponsive. Disconnecting here is safe — onResume() reconnects
        // automatically when the user returns to the activity.
        //
        // Stop the RFB client FIRST so running=false before any pipe is torn
        // down — prevents spurious E/ "Pipe closed" on user-initiated close.
        activeRfbClient?.stop()
        activeRfbClient = null
        clearArmedModifierButtons()
        vncConsoleChannel?.close()
        vncConsoleChannel = null
        // Clear VncView callbacks — they capture the now-closed VncConsoleChannel.
        // Must be cleared here as well as in onDisconnected so they don't survive
        // an activity background → foreground cycle.
        vncView.onPointerEvent = null
        vncView.onKeyEvent = null
        vncView.onTextInput = null
        vncView.onBackspace = null
        vncView.onViewSizeReady = null
        isGraphicalMode = false
        isConnected = false
        consoleManager?.disconnect()
        termuxBridge?.cleanup()
        // Null out TermuxBridge so onResume() can detect it needs reinitialisation
        // (setupTerminal() recreates it from scratch).
        termuxBridge = null
        try { directVncSocket?.close() } catch (_: Exception) {}
        directVncSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionJob?.cancel()
        connectionJob = null
        activeRfbClient?.stop()
        activeRfbClient = null
        clearArmedModifierButtons()
        vncConsoleChannel?.close()
        vncConsoleChannel = null
        consoleManager?.disconnect()
        termuxBridge?.cleanup()
        termuxBridge = null
        vncView.recycle()
        try { directVncSocket?.close() } catch (_: Exception) {}
    }

}
