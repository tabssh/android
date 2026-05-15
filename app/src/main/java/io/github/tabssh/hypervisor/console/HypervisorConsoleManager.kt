package io.github.tabssh.hypervisor.console

import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private var webSocketClient: ConsoleWebSocketClient? = null
    private var termuxBridge: TermuxBridge? = null
    private var consoleListener: ConsoleEventListener? = null

    /**
     * Console connection result
     */
    data class ConsoleConnection(
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val vmName: String,
        val hypervisorType: HypervisorType
    )

    enum class HypervisorType {
        PROXMOX,
        XCPNG,
        XEN_ORCHESTRA,
        VMWARE
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
                    listener?.onError(error.message ?: "Unknown error")
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

            ConsoleConnection(
                inputStream = inputStream,
                outputStream = outputStream,
                vmName = vmName,
                hypervisorType = HypervisorType.PROXMOX
            )
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

            ConsoleConnection(
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

            ConsoleConnection(
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
     * Wire console connection to TermuxBridge for terminal display
     */
    fun wireToTerminal(connection: ConsoleConnection, bridge: TermuxBridge) {
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
        termuxBridge?.disconnect()
        webSocketClient?.disconnect()
        termuxBridge = null
        webSocketClient = null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = webSocketClient?.isConnected() == true
}

/**
 * Listener for console events
 */
interface ConsoleEventListener {
    fun onConnected(vmName: String)
    fun onDisconnected(reason: String)
    fun onError(message: String)
}
