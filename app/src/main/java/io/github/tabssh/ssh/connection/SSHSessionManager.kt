package io.github.tabssh.ssh.connection

import android.content.Context
import io.github.tabssh.services.SSHConnectionService
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple SSH connections and sessions
 */
class SSHSessionManager(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Active connections mapped by connection profile ID
    private val activeConnections = ConcurrentHashMap<String, SSHConnection>()
    
    // Connection pool for reuse
    private val connectionPool = ConcurrentHashMap<String, SSHConnection>()
    
    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()
    
    private val listeners = mutableListOf<SessionManagerListener>()

    private var isInitialized = false

    // Host key verification callbacks
    var hostKeyChangedCallback: ((HostKeyChangedInfo) -> HostKeyAction)? = null
    var newHostKeyCallback: ((NewHostKeyInfo) -> HostKeyAction)? = null
    
    fun initialize() {
        if (isInitialized) return
        
        Logger.d("SSHSessionManager", "Initializing SSH session manager")
        
        // Set up JSch logger
        com.jcraft.jsch.JSch.setLogger(object : com.jcraft.jsch.Logger {
            override fun isEnabled(level: Int): Boolean = Logger.isDebugMode()
            
            override fun log(level: Int, message: String?) {
                when (level) {
                    com.jcraft.jsch.Logger.DEBUG -> Logger.d("JSch", message ?: "")
                    com.jcraft.jsch.Logger.INFO -> Logger.i("JSch", message ?: "")
                    com.jcraft.jsch.Logger.WARN -> Logger.w("JSch", message ?: "")
                    com.jcraft.jsch.Logger.ERROR -> Logger.e("JSch", message ?: "")
                    com.jcraft.jsch.Logger.FATAL -> Logger.e("JSch", "FATAL: ${message ?: ""}")
                }
            }
        })
        
        isInitialized = true
        Logger.i("SSHSessionManager", "SSH session manager initialized")
    }
    
    /**
     * Create a new SSH connection
     */
    suspend fun createConnection(profile: ConnectionProfile): SSHConnection {
        Logger.d("SSHSessionManager", "Creating connection for ${profile.getDisplayName()}")
        
        // Check if connection already exists in pool
        connectionPool[profile.id]?.let { existingConnection ->
            if (existingConnection.connectionState.value == ConnectionState.CONNECTED) {
                Logger.d("SSHSessionManager", "Reusing existing connection for ${profile.id}")
                activeConnections[profile.id] = existingConnection
                updateConnectionStates()
                return existingConnection
            } else {
                // Remove stale connection from pool
                connectionPool.remove(profile.id)
            }
        }
        
        // Create new connection
        val connection = SSHConnection(profile, scope, context)

        // Set host key verification callbacks
        connection.hostKeyChangedCallback = hostKeyChangedCallback
        connection.newHostKeyCallback = newHostKeyCallback

        // Add connection listener to track state changes
        connection.addConnectionListener(object : ConnectionListener {
            override fun onConnecting(connectionId: String) {
                updateConnectionStates()
                notifyListeners { onConnectionStateChanged(connectionId, ConnectionState.CONNECTING) }
            }
            
            override fun onConnected(connectionId: String) {
                updateConnectionStates()
                notifyListeners { onConnectionStateChanged(connectionId, ConnectionState.CONNECTED) }
            }
            
            override fun onDisconnected(connectionId: String) {
                activeConnections.remove(connectionId)
                updateConnectionStates()
                notifyListeners { onConnectionStateChanged(connectionId, ConnectionState.DISCONNECTED) }
            }
            
            override fun onError(connectionId: String, error: Exception) {
                updateConnectionStates()
                notifyListeners { onConnectionError(connectionId, error) }
            }
        })
        
        // Store in active connections and pool
        activeConnections[profile.id] = connection
        connectionPool[profile.id] = connection
        
        updateConnectionStates()
        
        Logger.i("SSHSessionManager", "Created new connection for ${profile.getDisplayName()}")
        return connection
    }
    
    /**
     * Connect to a server using the profile
     */
    suspend fun connectToServer(profile: ConnectionProfile): SSHConnection? {
        return try {
            val connection = createConnection(profile)
            val success = connection.connect()
            
            if (success) {
                Logger.i("SSHSessionManager", "Successfully connected to ${profile.getDisplayName()}")
                // Start foreground service to maintain persistent notification
                SSHConnectionService.startService(context)
                notifyListeners { onConnectionEstablished(profile.id) }
                connection
            } else {
                Logger.w("SSHSessionManager", "Failed to connect to ${profile.getDisplayName()}")
                null
            }
        } catch (e: Exception) {
            Logger.e("SSHSessionManager", "Error connecting to ${profile.getDisplayName()}", e)
            notifyListeners { onConnectionFailed(profile.id, e) }
            null
        }
    }
    
    /**
     * Get an active connection by profile ID
     */
    fun getConnection(profileId: String): SSHConnection? {
        return activeConnections[profileId]
    }
    
    /**
     * Get all active connections
     */
    fun getActiveConnections(): List<SSHConnection> {
        return activeConnections.values.toList()
    }
    
    /**
     * Close a specific connection
     */
    fun closeConnection(profileId: String) {
        Logger.d("SSHSessionManager", "Closing connection: $profileId")
        
        activeConnections[profileId]?.let { connection ->
            connection.disconnect()
            activeConnections.remove(profileId)
            connectionPool.remove(profileId) // Also remove from pool
            updateConnectionStates()
            
            Logger.i("SSHSessionManager", "Closed connection: $profileId")
            notifyListeners { onConnectionClosed(profileId) }
        }
    }
    
    /**
     * Close all active connections
     */
    fun closeAllConnections() {
        Logger.d("SSHSessionManager", "Closing all connections (${activeConnections.size} active)")
        
        activeConnections.values.forEach { connection ->
            try {
                connection.disconnect()
            } catch (e: Exception) {
                Logger.e("SSHSessionManager", "Error disconnecting connection ${connection.id}", e)
            }
        }
        
        activeConnections.clear()
        connectionPool.clear()
        updateConnectionStates()
        
        Logger.i("SSHSessionManager", "All connections closed")
        notifyListeners { onAllConnectionsClosed() }
    }
    
    /**
     * Get connection statistics
     */
    fun getConnectionStatistics(): SessionManagerStats {
        val connected = activeConnections.values.count { it.isConnected() }
        val total = activeConnections.size
        val poolSize = connectionPool.size
        
        return SessionManagerStats(
            totalConnections = total,
            connectedConnections = connected,
            poolSize = poolSize
        )
    }
    
    /**
     * Cleanup inactive connections
     */
    suspend fun performMaintenance() {
        Logger.d("SSHSessionManager", "Performing connection maintenance")
        
        val toRemove = mutableListOf<String>()
        
        activeConnections.forEach { (id, connection) ->
            if (!connection.isConnected() && connection.connectionState.value == ConnectionState.DISCONNECTED) {
                toRemove.add(id)
            }
        }
        
        toRemove.forEach { id ->
            activeConnections.remove(id)
            connectionPool.remove(id)
        }
        
        if (toRemove.isNotEmpty()) {
            updateConnectionStates()
            Logger.i("SSHSessionManager", "Cleaned up ${toRemove.size} inactive connections")
        }
    }
    
    /**
     * Check if a connection is active for a profile
     */
    fun isConnectionActive(profileId: String): Boolean {
        return activeConnections[profileId]?.isConnected() == true
    }
    
    /**
     * Get connection state for a profile
     */
    fun getConnectionState(profileId: String): ConnectionState {
        return activeConnections[profileId]?.connectionState?.value ?: ConnectionState.DISCONNECTED
    }
    
    private fun updateConnectionStates() {
        val states = activeConnections.mapValues { (_, connection) ->
            connection.connectionState.value
        }
        _connectionStates.value = states
    }
    
    /**
     * Add session manager listener
     */
    fun addListener(listener: SessionManagerListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove session manager listener
     */
    fun removeListener(listener: SessionManagerListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: SessionManagerListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("SSHSessionManager", "Cleaning up SSH session manager")
        closeAllConnections()
        scope.cancel()
    }
}

/**
 * Session manager statistics
 */
data class SessionManagerStats(
    val totalConnections: Int,
    val connectedConnections: Int,
    val poolSize: Int
)

/**
 * Interface for session manager events
 */
interface SessionManagerListener {
    fun onConnectionEstablished(profileId: String) {}
    fun onConnectionFailed(profileId: String, error: Exception) {}
    fun onConnectionClosed(profileId: String) {}
    fun onConnectionStateChanged(profileId: String, state: ConnectionState) {}
    fun onConnectionError(profileId: String, error: Exception) {}
    fun onAllConnectionsClosed() {}
}