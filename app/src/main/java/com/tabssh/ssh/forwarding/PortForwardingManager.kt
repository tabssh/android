package com.tabssh.ssh.forwarding

import com.jcraft.jsch.Session
import com.tabssh.ssh.connection.SSHConnection
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages SSH port forwarding (Local, Remote, Dynamic/SOCKS)
 * Provides tunneling capabilities for secure access to remote services
 */
class PortForwardingManager(private val sshConnection: SSHConnection) {
    
    private val forwardingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Active tunnels mapped by tunnel ID
    private val activeTunnels = ConcurrentHashMap<String, Tunnel>()
    
    private val _tunnelStates = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    val tunnelStates: StateFlow<Map<String, TunnelState>> = _tunnelStates.asStateFlow()
    
    private val listeners = mutableListOf<PortForwardingListener>()
    
    init {
        Logger.d("PortForwardingManager", "Created port forwarding manager for connection ${sshConnection.id}")
    }
    
    /**
     * Create a local port forward (SSH -L option)
     * Routes traffic from local port to remote host:port through SSH connection
     */
    suspend fun createLocalForward(
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
        autoStart: Boolean = true
    ): Tunnel = withContext(Dispatchers.IO) {
        
        val tunnelId = generateTunnelId()
        val tunnel = Tunnel(
            id = tunnelId,
            type = TunnelType.LOCAL_FORWARD,
            localHost = "localhost",
            localPort = localPort,
            remoteHost = remoteHost,
            remotePort = remotePort,
            autoStart = autoStart,
            manager = this@PortForwardingManager
        )
        
        activeTunnels[tunnelId] = tunnel
        updateTunnelStates()
        
        if (autoStart) {
            startTunnel(tunnelId)
        }
        
        Logger.i("PortForwardingManager", "Created local forward: localhost:$localPort -> $remoteHost:$remotePort")
        notifyListeners { onTunnelCreated(tunnel) }
        
        return@withContext tunnel
    }
    
    /**
     * Create a remote port forward (SSH -R option)  
     * Routes traffic from remote port back to local host:port
     */
    suspend fun createRemoteForward(
        remotePort: Int,
        localHost: String,
        localPort: Int,
        autoStart: Boolean = true
    ): Tunnel = withContext(Dispatchers.IO) {
        
        val tunnelId = generateTunnelId()
        val tunnel = Tunnel(
            id = tunnelId,
            type = TunnelType.REMOTE_FORWARD,
            localHost = localHost,
            localPort = localPort,
            remoteHost = "0.0.0.0", // Listen on all interfaces
            remotePort = remotePort,
            autoStart = autoStart,
            manager = this@PortForwardingManager
        )
        
        activeTunnels[tunnelId] = tunnel
        updateTunnelStates()
        
        if (autoStart) {
            startTunnel(tunnelId)
        }
        
        Logger.i("PortForwardingManager", "Created remote forward: remote:$remotePort -> $localHost:$localPort")
        notifyListeners { onTunnelCreated(tunnel) }
        
        return@withContext tunnel
    }
    
    /**
     * Create a dynamic port forward (SSH -D option)
     * Creates a SOCKS proxy on the local port
     */
    suspend fun createDynamicForward(
        localPort: Int,
        autoStart: Boolean = true
    ): Tunnel = withContext(Dispatchers.IO) {
        
        val tunnelId = generateTunnelId()
        val tunnel = Tunnel(
            id = tunnelId,
            type = TunnelType.DYNAMIC_FORWARD,
            localHost = "localhost",
            localPort = localPort,
            remoteHost = "", // Not applicable for SOCKS
            remotePort = 0,  // Not applicable for SOCKS
            autoStart = autoStart,
            manager = this@PortForwardingManager
        )
        
        activeTunnels[tunnelId] = tunnel
        updateTunnelStates()
        
        if (autoStart) {
            startTunnel(tunnelId)
        }
        
        Logger.i("PortForwardingManager", "Created dynamic forward (SOCKS): localhost:$localPort")
        notifyListeners { onTunnelCreated(tunnel) }
        
        return@withContext tunnel
    }
    
    /**
     * Start a tunnel by ID
     */
    suspend fun startTunnel(tunnelId: String): Boolean = withContext(Dispatchers.IO) {
        val tunnel = activeTunnels[tunnelId] ?: return@withContext false
        
        if (tunnel.state == TunnelState.ACTIVE) {
            Logger.d("PortForwardingManager", "Tunnel already active: $tunnelId")
            return@withContext true
        }
        
        return@withContext try {
            tunnel.updateState(TunnelState.CONNECTING)
            
            val session = getSSHSession() ?: throw Exception("SSH session not available")
            
            val result = when (tunnel.type) {
                TunnelType.LOCAL_FORWARD -> {
                    val assignedPort = session.setPortForwardingL(
                        tunnel.localPort,
                        tunnel.remoteHost,
                        tunnel.remotePort
                    )
                    tunnel.actualLocalPort = assignedPort
                    assignedPort > 0
                }
                TunnelType.REMOTE_FORWARD -> {
                    session.setPortForwardingR(
                        tunnel.remotePort,
                        tunnel.localHost,
                        tunnel.localPort
                    )
                    true
                }
                TunnelType.DYNAMIC_FORWARD -> {
                    session.setPortForwardingL(tunnel.localPort.toString())
                    tunnel.actualLocalPort = tunnel.localPort
                    true
                }
            }
            
            if (result) {
                tunnel.updateState(TunnelState.ACTIVE)
                tunnel.startTime = System.currentTimeMillis()
                
                Logger.i("PortForwardingManager", "Started tunnel: ${tunnel.getDescription()}")
                notifyListeners { onTunnelStarted(tunnel) }
                true
            } else {
                tunnel.updateState(TunnelState.ERROR)
                tunnel.lastError = "Failed to establish port forward"
                
                Logger.e("PortForwardingManager", "Failed to start tunnel: $tunnelId")
                notifyListeners { onTunnelError(tunnel, "Failed to start tunnel") }
                false
            }
            
        } catch (e: Exception) {
            tunnel.updateState(TunnelState.ERROR)
            tunnel.lastError = e.message ?: "Unknown error"
            
            Logger.e("PortForwardingManager", "Error starting tunnel $tunnelId", e)
            notifyListeners { onTunnelError(tunnel, e.message ?: "Unknown error") }
            false
        }
    }
    
    /**
     * Stop a tunnel by ID
     */
    suspend fun stopTunnel(tunnelId: String): Boolean = withContext(Dispatchers.IO) {
        val tunnel = activeTunnels[tunnelId] ?: return@withContext false
        
        if (tunnel.state != TunnelState.ACTIVE) {
            Logger.d("PortForwardingManager", "Tunnel not active: $tunnelId")
            return@withContext true
        }
        
        return@withContext try {
            val session = getSSHSession() ?: throw Exception("SSH session not available")
            
            when (tunnel.type) {
                TunnelType.LOCAL_FORWARD -> {
                    session.delPortForwardingL(tunnel.actualLocalPort ?: tunnel.localPort)
                }
                TunnelType.REMOTE_FORWARD -> {
                    session.delPortForwardingR(tunnel.remotePort)
                }
                TunnelType.DYNAMIC_FORWARD -> {
                    session.delPortForwardingL(tunnel.actualLocalPort ?: tunnel.localPort)
                }
            }
            
            tunnel.updateState(TunnelState.STOPPED)
            tunnel.stopTime = System.currentTimeMillis()
            
            Logger.i("PortForwardingManager", "Stopped tunnel: ${tunnel.getDescription()}")
            notifyListeners { onTunnelStopped(tunnel) }
            true
            
        } catch (e: Exception) {
            tunnel.updateState(TunnelState.ERROR)
            tunnel.lastError = e.message ?: "Unknown error"
            
            Logger.e("PortForwardingManager", "Error stopping tunnel $tunnelId", e)
            notifyListeners { onTunnelError(tunnel, e.message ?: "Unknown error") }
            false
        }
    }
    
    /**
     * Remove a tunnel
     */
    suspend fun removeTunnel(tunnelId: String): Boolean {
        val tunnel = activeTunnels[tunnelId] ?: return false
        
        // Stop the tunnel if active
        if (tunnel.state == TunnelState.ACTIVE) {
            stopTunnel(tunnelId)
        }
        
        activeTunnels.remove(tunnelId)
        updateTunnelStates()
        
        Logger.i("PortForwardingManager", "Removed tunnel: $tunnelId")
        notifyListeners { onTunnelRemoved(tunnel) }
        
        return true
    }
    
    /**
     * Get all active tunnels
     */
    fun getActiveTunnels(): List<Tunnel> = activeTunnels.values.toList()
    
    /**
     * Get tunnel by ID
     */
    fun getTunnel(tunnelId: String): Tunnel? = activeTunnels[tunnelId]
    
    /**
     * Stop all tunnels
     */
    suspend fun stopAllTunnels() {
        Logger.d("PortForwardingManager", "Stopping all tunnels (${activeTunnels.size} active)")
        
        activeTunnels.values.forEach { tunnel ->
            if (tunnel.state == TunnelState.ACTIVE) {
                stopTunnel(tunnel.id)
            }
        }
        
        Logger.i("PortForwardingManager", "All tunnels stopped")
        notifyListeners { onAllTunnelsStopped() }
    }
    
    /**
     * Get port forwarding statistics
     */
    fun getStatistics(): PortForwardingStatistics {
        val totalTunnels = activeTunnels.size
        val activeTunnelCount = activeTunnels.values.count { it.state == TunnelState.ACTIVE }
        val localForwards = activeTunnels.values.count { it.type == TunnelType.LOCAL_FORWARD }
        val remoteForwards = activeTunnels.values.count { it.type == TunnelType.REMOTE_FORWARD }
        val dynamicForwards = activeTunnels.values.count { it.type == TunnelType.DYNAMIC_FORWARD }
        val totalBytesTransferred = activeTunnels.values.sumOf { it.bytesTransferred }
        
        return PortForwardingStatistics(
            totalTunnels = totalTunnels,
            activeTunnels = activeTunnelCount,
            localForwards = localForwards,
            remoteForwards = remoteForwards,
            dynamicForwards = dynamicForwards,
            totalBytesTransferred = totalBytesTransferred
        )
    }
    
    /**
     * Check if a local port is available
     */
    suspend fun isPortAvailable(port: Int): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            java.net.ServerSocket(port).use { 
                true // Port is available
            }
        } catch (e: Exception) {
            false // Port is in use
        }
    }
    
    /**
     * Get next available port starting from a given port
     */
    suspend fun getNextAvailablePort(startPort: Int): Int = withContext(Dispatchers.IO) {
        for (port in startPort..65535) {
            if (isPortAvailable(port)) {
                return@withContext port
            }
        }
        return@withContext -1 // No available ports found
    }
    
    private fun getSSHSession(): Session? {
        // Get the JSch Session from the SSH connection
        // This uses Java reflection to access the internal session since
        // SSHConnection doesn't expose it directly in the public API
        return try {
            val sessionField = sshConnection.javaClass.getDeclaredField("session")
            sessionField.isAccessible = true
            sessionField.get(sshConnection) as? Session
        } catch (e: Exception) {
            Logger.e("PortForwardingManager", "Failed to get SSH session for port forwarding", e)
            null
        }
    }
    
    private fun generateTunnelId(): String = java.util.UUID.randomUUID().toString()
    
    private fun updateTunnelStates() {
        val states = activeTunnels.mapValues { (_, tunnel) -> tunnel.state }
        _tunnelStates.value = states
    }
    
    // Listener management
    
    fun addListener(listener: PortForwardingListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: PortForwardingListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: PortForwardingListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup all tunnels and resources
     */
    fun cleanup() {
        Logger.d("PortForwardingManager", "Cleaning up port forwarding manager")
        
        forwardingScope.launch {
            stopAllTunnels()
        }
        
        forwardingScope.cancel()
        activeTunnels.clear()
        listeners.clear()
    }
}

/**
 * Represents a single SSH tunnel
 */
class Tunnel(
    val id: String,
    val type: TunnelType,
    val localHost: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val autoStart: Boolean,
    private val manager: PortForwardingManager
) {
    private val _state = MutableStateFlow(TunnelState.STOPPED)
    val state: TunnelState get() = _state.value
    
    var actualLocalPort: Int? = null // Actual port assigned (may differ if port 0 was requested)
    var startTime: Long = 0
    var stopTime: Long = 0
    var bytesTransferred: Long = 0
    var activeConnections: Int = 0
    var lastActivity: Long = 0
    var lastError: String? = null
    
    /**
     * Update tunnel state
     */
    fun updateState(newState: TunnelState) {
        _state.value = newState
        Logger.d("Tunnel", "Tunnel $id state changed to $newState")
    }
    
    /**
     * Get human-readable description
     */
    fun getDescription(): String {
        return when (type) {
            TunnelType.LOCAL_FORWARD -> "Local: $localHost:$localPort → $remoteHost:$remotePort"
            TunnelType.REMOTE_FORWARD -> "Remote: $remoteHost:$remotePort → $localHost:$localPort"
            TunnelType.DYNAMIC_FORWARD -> "SOCKS: $localHost:$localPort"
        }
    }
    
    /**
     * Get short description for UI
     */
    fun getShortDescription(): String {
        return when (type) {
            TunnelType.LOCAL_FORWARD -> "$localPort → $remoteHost:$remotePort"
            TunnelType.REMOTE_FORWARD -> "Remote:$remotePort → $localPort"
            TunnelType.DYNAMIC_FORWARD -> "SOCKS:$localPort"
        }
    }
    
    /**
     * Get tunnel uptime in milliseconds
     */
    fun getUptime(): Long {
        return if (startTime > 0 && state == TunnelState.ACTIVE) {
            System.currentTimeMillis() - startTime
        } else if (stopTime > 0 && startTime > 0) {
            stopTime - startTime
        } else {
            0
        }
    }
    
    /**
     * Get formatted uptime string
     */
    fun getUptimeString(): String {
        val uptime = getUptime()
        val seconds = uptime / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
    
    /**
     * Get bytes transferred formatted
     */
    fun getFormattedBytesTransferred(): String {
        return when {
            bytesTransferred < 1024 -> "$bytesTransferred B"
            bytesTransferred < 1024 * 1024 -> String.format("%.1f KB", bytesTransferred / 1024.0)
            bytesTransferred < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytesTransferred / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytesTransferred / (1024.0 * 1024 * 1024))
        }
    }
    
    /**
     * Check if tunnel is active
     */
    fun isActive(): Boolean = state == TunnelState.ACTIVE
    
    /**
     * Check if tunnel can be started
     */
    fun canStart(): Boolean = state == TunnelState.STOPPED || state == TunnelState.ERROR
    
    /**
     * Check if tunnel can be stopped
     */
    fun canStop(): Boolean = state == TunnelState.ACTIVE || state == TunnelState.CONNECTING
}

/**
 * Types of SSH port forwarding
 */
enum class TunnelType(val displayName: String, val description: String) {
    LOCAL_FORWARD(
        "Local Forward",
        "Forward local port to remote host through SSH tunnel"
    ),
    REMOTE_FORWARD(
        "Remote Forward", 
        "Forward remote port back to local host"
    ),
    DYNAMIC_FORWARD(
        "Dynamic Forward",
        "Create SOCKS proxy for routing traffic through SSH"
    )
}

/**
 * Tunnel states
 */
enum class TunnelState(val displayName: String) {
    STOPPED("Stopped"),
    CONNECTING("Connecting"),
    ACTIVE("Active"),
    ERROR("Error")
}

/**
 * Port forwarding statistics
 */
data class PortForwardingStatistics(
    val totalTunnels: Int,
    val activeTunnels: Int,
    val localForwards: Int,
    val remoteForwards: Int,
    val dynamicForwards: Int,
    val totalBytesTransferred: Long
)

/**
 * Port forwarding event listener
 */
interface PortForwardingListener {
    fun onTunnelCreated(tunnel: Tunnel) {}
    fun onTunnelStarted(tunnel: Tunnel) {}
    fun onTunnelStopped(tunnel: Tunnel) {}
    fun onTunnelError(tunnel: Tunnel, error: String) {}
    fun onTunnelRemoved(tunnel: Tunnel) {}
    fun onAllTunnelsStopped() {}
}