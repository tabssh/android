package io.github.tabssh.hypervisor.console

import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Unified hypervisor console manager.
 * Handles console connections for all supported hypervisors:
 * - Proxmox VE (via termproxy WebSocket)
 * - XCP-ng/XenServer (via XenAPI console)
 * - Xen Orchestra (via REST API + WebSocket)
 * - VMware (via VMRC or Web Console)
 *
 * The console provides a TEXT STREAM just like SSH, which can be
 * wired directly to TermuxBridge for terminal display.
 */
class HypervisorConsoleManager {
    companion object {
        private const val TAG = "HypervisorConsole"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocketClient: ConsoleWebSocketClient? = null
    private var termuxBridge: TermuxBridge? = null
    private var consoleListener: ConsoleEventListener? = null

    /**
     * The active [RfbClient] when a graphical console is running, or null for
     * text consoles.  Stored so [disconnect] can call [RfbClient.stop] before
     * tearing down the WebSocket pipe — without the stop(), the reader thread
     * sees [java.io.IOException] "Pipe closed" while [running] is still true
     * and logs a spurious error.
     */
    private var activeRfbClient: RfbClient? = null

    // Stored for VNC fallback when termproxy WebSocket reports a serial error
    // after a successful API call (the API-level fallback is in connectProxmoxConsole
    // phase 1; this covers the rare case where the API succeeds but the WS frame fails).
    private var proxmoxVncFallbackClient: ProxmoxApiClient? = null
    private var proxmoxVncFallbackNode: String = ""
    private var proxmoxVncFallbackVmid: Int = 0
    private var proxmoxVncFallbackVmName: String = ""
    private var proxmoxVncFallbackType: String = "qemu"
    private var proxmoxVncFallbackVerifySsl: Boolean = false
    private var proxmoxVncFallbackPinnedCert: String? = null
    private var proxmoxVncFallbackDisplayHost: String = ""
    private var proxmoxVncFallbackDisplayPort: Int = 0

    /**
     * Console connection result — sealed so callers must handle both variants.
     *
     * [Text] carries a raw byte stream wired to TermuxBridge (serial/text consoles).
     * [Graphical] carries a fully-constructed [RfbClient] for VNC/RFB consoles;
     * the caller should attach a [io.github.tabssh.hypervisor.console.rfb.RfbListener]
     * and call [RfbClient.start].
     */
    sealed class ConsoleConnection {
        abstract val vmName: String
        abstract val hypervisorType: HypervisorType

        data class Text(
            override val vmName: String,
            override val hypervisorType: HypervisorType,
            val inputStream: InputStream,
            val outputStream: OutputStream
        ) : ConsoleConnection()

        data class Graphical(
            override val vmName: String,
            override val hypervisorType: HypervisorType,
            val rfbClient: RfbClient
        ) : ConsoleConnection()
    }

    enum class HypervisorType {
        PROXMOX,
        XCPNG,
        XEN_ORCHESTRA,
        VMWARE,
        LIBVIRT
    }

    /**
     * Connect to Proxmox VM console.
     *
     * Tries termproxy first (text-based serial console). If the VM has no
     * serial interface configured, Proxmox returns an error containing
     * "serial"; in that case we automatically fall back to vncproxy (raw
     * RFB over WebSocket), which works for every running VM regardless of
     * hardware configuration. The caller sees a connected console either way.
     *
     * Auth differences between the two paths:
     *  - termproxy: PVEAuthCookie in HTTP upgrade header PLUS
     *    `<userid>:<ticket>\n` as the first WebSocket frame
     *  - vncproxy: PVEAuthCookie in HTTP upgrade header only;
     *    the vncticket is already encoded in the WebSocket URL
     */
    suspend fun connectProxmoxConsole(
        client: ProxmoxApiClient,
        node: String,
        vmid: Int,
        vmName: String,
        type: String = "qemu", // "qemu" or "lxc"
        verifySsl: Boolean = false,
        pinnedCertSha256: String? = null,
        displayHost: String = "",
        displayPort: Int = 0,
        listener: ConsoleEventListener? = null
    ): ConsoleConnection? = withContext(Dispatchers.IO) {
        consoleListener = listener

        // Phase 1: obtain a console ticket. Try termproxy; fall back to
        // vncproxy when the VM has no serial interface.
        Logger.i(TAG, "Connecting to Proxmox console: $vmName (vmid=$vmid)")
        // Kotlin doesn't allow val re-assignment across try/catch branches, so
        // wrap the fallback logic in a single expression that produces the pair.
        val (ticket, protocol) = try {
            val t = client.getTermProxy(node, vmid, type)
            Logger.d(TAG, "Got termproxy ticket for $vmName")
            Pair(t, ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_TERM)
        } catch (termEx: Exception) {
            val msg = termEx.message ?: ""
            val isSerialError = msg.contains("serial", ignoreCase = true) ||
                msg.contains("unable to find", ignoreCase = true)
            if (!isSerialError) {
                // Unexpected failure (auth, network, …) — surface it directly.
                Logger.e(TAG, "termproxy failed (non-serial): $msg")
                listener?.onError(msg.ifBlank { "Connection failed" })
                return@withContext null
            }
            Logger.i(TAG, "termproxy unavailable for $vmName ($msg) — falling back to vncproxy")
            val vnc = client.getVNCProxy(node, vmid, type)
            if (vnc == null) {
                Logger.e(TAG, "vncproxy fallback also failed for $vmName (vmid=$vmid)")
                listener?.onError(
                    "This VM has no serial console and the VNC fallback also failed.\n\n" +
                    "To enable serial console: open the VM in Proxmox → Hardware → " +
                    "Add → Serial Port → set to 'socket', then restart the VM."
                )
                return@withContext null
            }
            Logger.d(TAG, "Got vncproxy ticket for $vmName (fallback)")
            Pair(vnc, ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_VNC)
        }

        // Store params for the WebSocket-level VNC fallback (in case Proxmox
        // accepts the termproxy ticket but then sends a serial-error frame).
        proxmoxVncFallbackClient = client
        proxmoxVncFallbackNode = node
        proxmoxVncFallbackVmid = vmid
        proxmoxVncFallbackVmName = vmName
        proxmoxVncFallbackType = type
        proxmoxVncFallbackVerifySsl = verifySsl
        proxmoxVncFallbackPinnedCert = pinnedCertSha256
        proxmoxVncFallbackDisplayHost = displayHost
        proxmoxVncFallbackDisplayPort = displayPort

        // Phase 2: open the WebSocket console connection.
        try {
            // `verifySsl` + `pinnedCertSha256` thread through from the
            // per-host setting; previously hardcoded `false` silently
            // bypassed both.
            webSocketClient = ConsoleWebSocketClient(
                verifySsl = verifySsl,
                protocol = protocol,
                pinnedCertSha256 = pinnedCertSha256,
                displayHost = displayHost,
                displayPort = displayPort
            )
            val wsClient = webSocketClient ?: run {
                Logger.e(TAG, "WebSocket client not initialized")
                listener?.onError("WebSocket client initialization failed")
                return@withContext null
            }

            // PVEAuthCookie is needed for the HTTP-upgrade leg on both paths.
            val headers = mapOf("Cookie" to "PVEAuthCookie=${ticket.authCookie}")

            // termproxy also requires a `<userid>:<ticket>\n` first-frame
            // handshake. vncproxy authenticates via the vncticket URL param
            // already encoded in websocketUrl — no first frame.
            if (protocol == ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_TERM) {
                wsClient.setProxmoxAuthFrame(ticket.userid, ticket.termproxyTicket)
            }

            val connected = wsClient.connect(ticket.websocketUrl, headers, object : ConsoleConnectionListener {
                override fun onConnected() {
                    Logger.i(TAG, "Proxmox console connected (${protocol.name})")
                    // Send initial size only for termproxy; VNC resize requires
                    // a full RFB DesktopSize negotiation that we don't implement.
                    if (protocol == ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_TERM) {
                        wsClient.sendResize(80, 24)
                    }
                    listener?.onConnected(vmName)
                }

                override fun onDisconnected(reason: String) {
                    Logger.i(TAG, "Proxmox console disconnected: $reason")
                    listener?.onDisconnected(reason)
                }

                override fun onError(error: Throwable) {
                    Logger.e(TAG, "Proxmox console WebSocket error: ${error.message}")
                    // error.message is null for some low-level socket failures
                    // (e.g. java.net.SocketException with no detail message).
                    // Fall back through cause.message then class name so the
                    // dialog always shows something actionable.
                    val msg = error.message?.takeIf { it.isNotBlank() }
                        ?: error.cause?.message?.takeIf { it.isNotBlank() }
                        ?: "Connection failed (${error.javaClass.simpleName})"
                    listener?.onError(msg)
                }

                override fun onSerialConsoleUnavailable() {
                    Logger.i(TAG, "Proxmox serial console unavailable via WebSocket frame — retrying with vncproxy")
                    // Disconnect the termproxy WebSocket and retry with vncproxy.
                    // This is the second fallback path; the first (API-level exception)
                    // is already handled in Phase 1 above.
                    wsClient.disconnect()
                    scope.launch {
                        retryProxmoxWithVnc(listener)
                    }
                }
            })

            if (!connected) {
                Logger.e(TAG, "Failed to connect WebSocket")
                listener?.onError("Failed to establish WebSocket connection")
                return@withContext null
            }

            // Brief pause for the WS handshake + auth frame exchange.
            delay(500)

            val inputStream = wsClient.getInputStream()
            val outputStream = wsClient.getOutputStream()

            if (inputStream == null || outputStream == null) {
                Logger.e(TAG, "Failed to get streams")
                listener?.onError("Failed to establish console streams")
                return@withContext null
            }

            if (protocol == ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_VNC) {
                // Graphical console: hand the streams to an RfbClient.
                // The caller wires an RfbListener and calls rfbClient.start().
                // ticket.termproxyTicket is the vncproxy ticket — Proxmox requires
                // it as the VNC Auth password during the RFB handshake (security type 2).
                val rfbClient = RfbClient(inputStream, outputStream,
                    vncPassword = ticket.termproxyTicket,
                    consoleMode = true)
                activeRfbClient = rfbClient
                ConsoleConnection.Graphical(
                    vmName = vmName,
                    hypervisorType = HypervisorType.PROXMOX,
                    rfbClient = rfbClient
                )
            } else {
                ConsoleConnection.Text(
                    inputStream = inputStream,
                    outputStream = outputStream,
                    vmName = vmName,
                    hypervisorType = HypervisorType.PROXMOX
                )
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to connect Proxmox console", e)
            listener?.onError(e.message ?: "Connection failed")
            null
        }
    }

    /**
     * Connect to XCP-ng/XenServer VM console
     */
    suspend fun connectXCPngConsole(
        client: XCPngApiClient,
        vmRef: String,
        vmName: String,
        verifySsl: Boolean = false,
        pinnedCertSha256: String? = null,
        displayHost: String = "",
        displayPort: Int = 0,
        listener: ConsoleEventListener? = null
    ): ConsoleConnection? = withContext(Dispatchers.IO) {
        consoleListener = listener

        try {
            Logger.i(TAG, "Connecting to XCP-ng console: $vmName")

            // Get console location from XenAPI
            val consoleUrl = client.getConsoleUrl(vmRef)
            if (consoleUrl == null) {
                Logger.e(TAG, "Failed to get console URL")
                listener?.onError("Failed to get console URL from XCP-ng")
                return@withContext null
            }

            Logger.d(TAG, "Got console URL: $consoleUrl")

            // Create WebSocket client with XCP-ng protocol (raw bytes).
            // `verifySsl` + `pinnedCertSha256` from the caller; previously
            // both hardcoded `false`/null.
            webSocketClient = ConsoleWebSocketClient(
                verifySsl = verifySsl,
                protocol = ConsoleWebSocketClient.ConsoleProtocol.XCPNG,
                pinnedCertSha256 = pinnedCertSha256,
                displayHost = displayHost,
                displayPort = displayPort
            )
            val xcpClient = webSocketClient ?: run {
                Logger.e(TAG, "WebSocket client not initialized")
                listener?.onError("WebSocket client initialization failed")
                return@withContext null
            }

            // Connect
            val connected = xcpClient.connect(consoleUrl, emptyMap(), object : ConsoleConnectionListener {
                override fun onConnected() {
                    Logger.i(TAG, "XCP-ng console connected")
                    listener?.onConnected(vmName)
                }

                override fun onDisconnected(reason: String) {
                    Logger.i(TAG, "XCP-ng console disconnected: $reason")
                    listener?.onDisconnected(reason)
                }

                override fun onError(error: Throwable) {
                    Logger.e(TAG, "XCP-ng console WebSocket error: ${error.message}")
                    listener?.onError(error.message ?: "Unknown error")
                }
            })

            if (!connected) {
                Logger.e(TAG, "XCP-ng WebSocket connect returned false for $vmName")
                listener?.onError("Failed to establish WebSocket connection")
                return@withContext null
            }

            Thread.sleep(500)

            val inputStream = xcpClient.getInputStream()
            val outputStream = xcpClient.getOutputStream()

            if (inputStream == null || outputStream == null) {
                listener?.onError("Failed to establish console streams")
                return@withContext null
            }

            ConsoleConnection.Text(
                inputStream = inputStream,
                outputStream = outputStream,
                vmName = vmName,
                hypervisorType = HypervisorType.XCPNG
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to connect XCP-ng console", e)
            listener?.onError(e.message ?: "Connection failed")
            null
        }
    }

    /**
     * Connect to Xen Orchestra VM console
     */
    suspend fun connectXenOrchestraConsole(
        client: XenOrchestraApiClient,
        vmId: String,
        vmName: String,
        verifySsl: Boolean = false,
        pinnedCertSha256: String? = null,
        displayHost: String = "",
        displayPort: Int = 0,
        listener: ConsoleEventListener? = null
    ): ConsoleConnection? = withContext(Dispatchers.IO) {
        consoleListener = listener

        try {
            Logger.i(TAG, "Connecting to Xen Orchestra console: $vmName")

            // Get console WebSocket URL from XO
            val consoleUrl = client.getConsoleWebSocketUrl(vmId)
            if (consoleUrl == null) {
                Logger.e(TAG, "Failed to get XO console URL")
                listener?.onError("Failed to get console URL from Xen Orchestra")
                return@withContext null
            }

            Logger.d(TAG, "Got XO console URL: $consoleUrl")

            // Create WebSocket client with XO protocol (raw bytes).
            // `verifySsl` + `pinnedCertSha256` from the caller; previously
            // both hardcoded `false`/null.
            webSocketClient = ConsoleWebSocketClient(
                verifySsl = verifySsl,
                protocol = ConsoleWebSocketClient.ConsoleProtocol.XO,
                pinnedCertSha256 = pinnedCertSha256,
                displayHost = displayHost,
                displayPort = displayPort
            )
            val xoClient = webSocketClient ?: run {
                Logger.e(TAG, "WebSocket client not initialized")
                listener?.onError("WebSocket client initialization failed")
                return@withContext null
            }

            // Connect with auth token
            val headers = mapOf(
                "Authorization" to "Bearer ${client.getAuthToken()}"
            )

            val connected = xoClient.connect(consoleUrl, headers, object : ConsoleConnectionListener {
                override fun onConnected() {
                    Logger.i(TAG, "Xen Orchestra console connected")
                    listener?.onConnected(vmName)
                }

                override fun onDisconnected(reason: String) {
                    Logger.i(TAG, "Xen Orchestra console disconnected: $reason")
                    listener?.onDisconnected(reason)
                }

                override fun onError(error: Throwable) {
                    Logger.e(TAG, "Xen Orchestra console WebSocket error: ${error.message}")
                    listener?.onError(error.message ?: "Unknown error")
                }
            })

            if (!connected) {
                Logger.e(TAG, "Xen Orchestra WebSocket connect returned false for $vmName")
                listener?.onError("Failed to establish WebSocket connection")
                return@withContext null
            }

            Thread.sleep(500)

            val inputStream = xoClient.getInputStream()
            val outputStream = xoClient.getOutputStream()

            if (inputStream == null || outputStream == null) {
                listener?.onError("Failed to establish console streams")
                return@withContext null
            }

            ConsoleConnection.Text(
                inputStream = inputStream,
                outputStream = outputStream,
                vmName = vmName,
                hypervisorType = HypervisorType.XEN_ORCHESTRA
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to connect XO console", e)
            listener?.onError(e.message ?: "Connection failed")
            null
        }
    }

    /**
     * VNC fallback after a WebSocket-level serial error.
     *
     * Called from the IO scope when Proxmox termproxy accepted the connection
     * but then sent "unable to find serial interface" as a data frame. We get
     * a vncproxy ticket and re-wire the terminal bridge to the new WebSocket.
     */
    private suspend fun retryProxmoxWithVnc(listener: ConsoleEventListener?) {
        val client = proxmoxVncFallbackClient ?: run {
            Logger.e(TAG, "VNC fallback: no stored Proxmox client — cannot retry")
            listener?.onError("Serial console unavailable and VNC fallback state was lost. Please reconnect.")
            return
        }
        val node = proxmoxVncFallbackNode
        val vmid = proxmoxVncFallbackVmid
        val vmName = proxmoxVncFallbackVmName
        val type = proxmoxVncFallbackType
        val verifySsl = proxmoxVncFallbackVerifySsl
        val pinnedCert = proxmoxVncFallbackPinnedCert
        val displayHost = proxmoxVncFallbackDisplayHost
        val displayPort = proxmoxVncFallbackDisplayPort

        Logger.i(TAG, "VNC fallback: requesting vncproxy ticket for $vmName")
        val vnc = try {
            client.getVNCProxy(node, vmid, type)
        } catch (e: Exception) {
            Logger.e(TAG, "VNC fallback: getVNCProxy threw", e)
            null
        }
        if (vnc == null) {
            Logger.e(TAG, "VNC fallback: vncproxy returned null for $vmName")
            listener?.onError(
                "This VM has no serial console and the VNC fallback also failed.\n\n" +
                "To enable serial console: open the VM in Proxmox → Hardware → " +
                "Add → Serial Port → set to 'socket', then restart the VM."
            )
            return
        }

        Logger.d(TAG, "VNC fallback: got vncproxy ticket for $vmName — connecting")
        val vncClient = ConsoleWebSocketClient(
            verifySsl = verifySsl,
            protocol = ConsoleWebSocketClient.ConsoleProtocol.PROXMOX_VNC,
            pinnedCertSha256 = pinnedCert,
            displayHost = displayHost,
            displayPort = displayPort
        )
        webSocketClient = vncClient

        val headers = mapOf("Cookie" to "PVEAuthCookie=${vnc.authCookie}")
        val connected = vncClient.connect(vnc.websocketUrl, headers, object : ConsoleConnectionListener {
            override fun onConnected() {
                Logger.i(TAG, "VNC fallback connected for $vmName")
                val input = vncClient.getInputStream() ?: run {
                    listener?.onError("VNC fallback: could not get streams")
                    return
                }
                val output = vncClient.getOutputStream() ?: run {
                    listener?.onError("VNC fallback: could not get streams")
                    return
                }
                // vnc.termproxyTicket is the vncproxy ticket — used as the
                // VNC Auth password in the RFB handshake (security type 2).
                // consoleMode=true: exclusive access, console encodings, resize support.
                val rfbClient = RfbClient(input, output,
                    vncPassword = vnc.termproxyTicket,
                    consoleMode = true)
                activeRfbClient = rfbClient
                val graphical = ConsoleConnection.Graphical(
                    vmName = vmName,
                    hypervisorType = HypervisorType.PROXMOX,
                    rfbClient = rfbClient
                )
                // Notify the UI to show the serial-unavailable banner before
                // switching to VNC console mode.
                listener?.onSerialConsoleUnavailable()
                listener?.onConnected(vmName)
                listener?.onSwitchToGraphical(graphical)
            }

            override fun onDisconnected(reason: String) {
                Logger.i(TAG, "VNC fallback disconnected: $reason")
                listener?.onDisconnected(reason)
            }

            override fun onError(error: Throwable) {
                Logger.e(TAG, "VNC fallback WebSocket error: ${error.message}")
                listener?.onError(error.message ?: "VNC connection failed")
            }
        })

        if (!connected) {
            Logger.e(TAG, "VNC fallback: WebSocket connect returned false for $vmName")
            listener?.onError("VNC fallback failed to connect for $vmName")
        }
    }

    /**
     * Wire a text console connection to TermuxBridge for terminal display.
     * Only valid for [ConsoleConnection.Text]; graphical consoles use [RfbClient] directly.
     */
    fun wireToTerminal(connection: ConsoleConnection.Text, bridge: TermuxBridge) {
        termuxBridge = bridge
        bridge.connect(connection.inputStream, connection.outputStream)
        Logger.i(TAG, "Console wired to terminal for ${connection.vmName}")
    }

    /**
     * Get WebSocket client for sending control messages (resize, etc.)
     */
    fun getWebSocketClient(): ConsoleWebSocketClient? = webSocketClient

    /**
     * Disconnect console
     */
    fun disconnect() {
        Logger.i(TAG, "Disconnecting console")
        scope.coroutineContext[Job]?.cancel()
        // Stop the RFB reader thread BEFORE closing the WebSocket pipe.
        // Without this, the reader sees IOException "Pipe closed" while
        // running=true and logs a spurious E/ error on every user-initiated close.
        activeRfbClient?.stop()
        activeRfbClient = null
        termuxBridge?.disconnect()
        webSocketClient?.disconnect()
        termuxBridge = null
        webSocketClient = null
        proxmoxVncFallbackClient = null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() == true
}

/**
 * Listener for console events fired by [HypervisorConsoleManager].
 */
interface ConsoleEventListener {
    fun onConnected(vmName: String)
    fun onDisconnected(reason: String)
    fun onError(message: String)

    /**
     * Called immediately before falling back to VNC console mode because the VM
     * has no serial device configured.  The UI should show a dismissible banner:
     *
     *   "Serial console unavailable — VM has no serial device.
     *    Add serial0: socket in Proxmox → VM Hardware, then reboot the VM.
     *    Falling back to VNC console mode."
     *
     * Default no-op so callers that never encounter this path need not change.
     */
    fun onSerialConsoleUnavailable() {}

    /**
     * Called when a text console falls back to a graphical (VNC/RFB) console
     * mid-session (e.g. Proxmox termproxy reports no serial interface after
     * the WebSocket is already open).
     *
     * The caller should attach a [io.github.tabssh.hypervisor.vnc.console.VncConsoleChannel]
     * to the RfbClient for keyboard input, keep the custom keyboard bar visible,
     * and call [HypervisorConsoleManager.ConsoleConnection.Graphical.rfbClient]`.start()`.
     *
     * Default no-op so existing callers that never use VNC don't need to change.
     */
    fun onSwitchToGraphical(connection: HypervisorConsoleManager.ConsoleConnection.Graphical) {}
}
