package io.github.tabssh.hypervisor.console

import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.xcpng.XenOrchestraApiClient
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
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
     * Connect to Proxmox VM console via termproxy
     * Works even without VM network access (serial console)
     */
    suspend fun connectProxmoxConsole(
        client: ProxmoxApiClient,
        node: String,
        vmid: Int,
        vmName: String,
        type: String = "qemu", // "qemu" or "lxc"
        listener: ConsoleEventListener? = null
    ): ConsoleConnection? = withContext(Dispatchers.IO) {
        consoleListener = listener

        try {
            Logger.i(TAG, "Connecting to Proxmox console: $vmName (vmid=$vmid)")

            // Get termproxy ticket and WebSocket URL
            val termProxy = client.getTermProxy(node, vmid, type)
            if (termProxy == null) {
                Logger.e(TAG, "Failed to get termproxy ticket")
                listener?.onError("Failed to get console access ticket")
                return@withContext null
            }

            Logger.d(TAG, "Got termproxy ticket, connecting to WebSocket")

            // Create WebSocket client
            webSocketClient = ConsoleWebSocketClient(verifySsl = false)

            // Build WebSocket URL
            // Proxmox termproxy WebSocket: wss://host:port/api2/json/nodes/{node}/{type}/{vmid}/vncwebsocket?port={port}&vncticket={ticket}
            val wsUrl = termProxy.websocketUrl

            // Connect with auth headers
            val headers = mapOf(
                "Cookie" to "PVEAuthCookie=${termProxy.ticket}"
            )

            val client = webSocketClient ?: run {
                Logger.e(TAG, "WebSocket client not initialized")
                listener?.onError("WebSocket client initialization failed")
                return@withContext null
            }
            val connected = client.connect(wsUrl, headers, object : ConsoleConnectionListener {
                override fun onConnected() {
                    Logger.i(TAG, "Proxmox console connected")
                    listener?.onConnected(vmName)
                }

                override fun onDisconnected(reason: String) {
                    Logger.i(TAG, "Proxmox console disconnected: $reason")
                    listener?.onDisconnected(reason)
                }

                override fun onError(error: Throwable) {
                    Logger.e(TAG, "Proxmox console error", error)
                    listener?.onError(error.message ?: "Unknown error")
                }
            })

            if (!connected) {
                Logger.e(TAG, "Failed to connect WebSocket")
                listener?.onError("Failed to establish WebSocket connection")
                return@withContext null
            }

            // Wait a moment for connection to establish
            Thread.sleep(500)

            val inputStream = client.getInputStream()
            val outputStream = client.getOutputStream()

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

            // Create WebSocket client
            webSocketClient = ConsoleWebSocketClient(verifySsl = false)
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
                    Logger.e(TAG, "XCP-ng console error", error)
                    listener?.onError(error.message ?: "Unknown error")
                }
            })

            if (!connected) {
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

            // Create WebSocket client
            webSocketClient = ConsoleWebSocketClient(verifySsl = false)
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
                    Logger.e(TAG, "Xen Orchestra console error", error)
                    listener?.onError(error.message ?: "Unknown error")
                }
            })

            if (!connected) {
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
