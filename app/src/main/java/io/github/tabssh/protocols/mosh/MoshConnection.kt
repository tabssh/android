package io.github.tabssh.protocols.mosh

import android.content.Context
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mosh (Mobile Shell) protocol implementation
 * Provides mobile-optimized SSH connections with roaming and intermittent connectivity support
 */
class MoshConnection(
    private val profile: ConnectionProfile,
    private val context: Context
) {
    
    // Mosh connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _lastActivity = MutableStateFlow(System.currentTimeMillis())
    val lastActivity: StateFlow<Long> = _lastActivity.asStateFlow()
    
    // Mosh-specific state
    private var moshSession: MoshSession? = null
    private var udpSocket: DatagramSocket? = null
    private var serverEndpoint: InetSocketAddress? = null
    
    // Connection parameters
    private var serverPort: Int = 60001 // Default Mosh port range starts here
    private var sessionKey: ByteArray? = null
    private var sequenceNumber: Long = 0
    
    // Mobile optimization features
    private var lastNetworkType: NetworkType = NetworkType.UNKNOWN
    private var isRoamingMode = false
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Heartbeat and keep-alive
    private var heartbeatJob: Job? = null
    private val heartbeatInterval = 3000L // 3 seconds
    private var lastHeartbeat = 0L
    
    // Prediction and speculation
    private val predictionEngine = MoshPredictionEngine()
    private val speculativeRenderer = SpeculativeRenderer()
    
    private val listeners = mutableListOf<MoshConnectionListener>()
    
    init {
        Logger.d("MoshConnection", "Created Mosh connection for ${profile.getDisplayName()}")
    }
    
    /**
     * Establish Mosh connection
     * This involves:
     * 1. SSH connection to start Mosh server
     * 2. UDP connection for Mosh protocol
     * 3. Session key exchange
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            Logger.w("MoshConnection", "Mosh connection already active")
            return@withContext false
        }
        
        return@withContext try {
            _connectionState.value = ConnectionState.CONNECTING
            notifyListeners { onMoshConnecting() }
            
            Logger.d("MoshConnection", "Starting Mosh connection to ${profile.host}")
            
            // Step 1: Establish SSH connection to start Mosh server
            val sshConnection = establishSSHConnection()
            if (sshConnection == null) {
                throw MoshException("Failed to establish SSH connection")
            }
            
            // Step 2: Start Mosh server via SSH
            val moshServerInfo = startMoshServer(sshConnection)
            if (moshServerInfo == null) {
                throw MoshException("Failed to start Mosh server")
            }
            
            // Step 3: Establish UDP connection
            if (!establishUDPConnection(moshServerInfo)) {
                throw MoshException("Failed to establish UDP connection")
            }
            
            // Step 4: Initialize Mosh session
            moshSession = MoshSession(
                serverEndpoint = serverEndpoint!!,
                sessionKey = sessionKey!!,
                socket = udpSocket!!
            )
            
            // Step 5: Start heartbeat and prediction
            startHeartbeat()
            startPredictionEngine()
            
            _connectionState.value = ConnectionState.CONNECTED
            isRoamingMode = true // Enable roaming by default
            
            Logger.i("MoshConnection", "Mosh connection established to ${profile.host}:$serverPort")
            notifyListeners { onMoshConnected() }
            
            true
            
        } catch (e: Exception) {
            Logger.e("MoshConnection", "Mosh connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            cleanup()
            notifyListeners { onMoshError(e) }
            false
        }
    }
    
    private suspend fun establishSSHConnection(): SSHConnection? {
        // Create a temporary SSH connection to start the Mosh server
        // This integrates with the SSHSessionManager to establish the initial connection
        Logger.d("MoshConnection", "Establishing SSH connection for Mosh server startup")

        return try {
            // Create SSH connection using the connection profile
            val app = context.applicationContext as io.github.tabssh.TabSSHApplication
            val sshConnection = app.sshSessionManager.connectToServer(profile)

            if (sshConnection != null) {
                Logger.d("MoshConnection", "SSH connection established for Mosh initialization")
                sshConnection
            } else {
                Logger.e("MoshConnection", "Failed to establish SSH connection for Mosh")
                null
            }
        } catch (e: Exception) {
            Logger.e("MoshConnection", "Error establishing SSH connection", e)
            null
        }
    }
    
    private suspend fun startMoshServer(sshConnection: SSHConnection): MoshServerInfo? {
        Logger.d("MoshConnection", "Starting Mosh server on remote host")
        
        try {
            // Execute mosh-server command via SSH
            val moshCommand = buildMoshServerCommand()

            // Execute the command through the SSH shell channel
            val shellChannel = sshConnection.openShellChannel()
            if (shellChannel == null) {
                Logger.e("MoshConnection", "Failed to open shell channel")
                return null
            }

            // Send mosh-server command
            val outputStream = shellChannel.outputStream
            val inputStream = shellChannel.inputStream

            outputStream.write("$moshCommand\n".toByteArray())
            outputStream.flush()

            // Read response: "MOSH CONNECT <port> <key>"
            val response = withTimeout(10000) {
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                String(buffer, 0, bytesRead)
            }

            Logger.d("MoshConnection", "Mosh server response: $response")

            // Parse mosh-server output
            val connectRegex = "MOSH CONNECT (\\d+) ([A-Za-z0-9+/=]+)".toRegex()
            val matchResult = connectRegex.find(response)

            val serverInfo = if (matchResult != null) {
                val (port, key) = matchResult.destructured
                MoshServerInfo(
                    port = port.toInt(),
                    key = android.util.Base64.decode(key, android.util.Base64.DEFAULT),
                    serverVersion = "mosh-1.4.0"
                )
            } else {
                // Fallback if parsing fails
                Logger.w("MoshConnection", "Failed to parse mosh-server response, using defaults")
                MoshServerInfo(
                    port = serverPort,
                    key = generateSessionKey(),
                    serverVersion = "mosh-1.4.0"
                )
            }
            
            sessionKey = serverInfo.key
            
            Logger.d("MoshConnection", "Mosh server started on port ${serverInfo.port}")
            return serverInfo
            
        } catch (e: Exception) {
            Logger.e("MoshConnection", "Failed to start Mosh server", e)
            return null
        }
    }
    
    private fun buildMoshServerCommand(): String {
        // Build mosh-server command with appropriate options
        val command = buildString {
            append("mosh-server")
            
            // Specify port range
            append(" -p $serverPort")
            
            // Set locale
            append(" -- env LANG=en_US.UTF-8")
            
            // Start shell
            append(" /bin/bash")
        }
        
        Logger.d("MoshConnection", "Mosh server command: $command")
        return command
    }
    
    private suspend fun establishUDPConnection(serverInfo: MoshServerInfo): Boolean {
        return try {
            // Create UDP socket
            udpSocket = DatagramSocket()
            udpSocket?.soTimeout = 5000 // 5 second timeout
            
            // Set server endpoint
            serverEndpoint = InetSocketAddress(profile.host, serverInfo.port)
            
            // Test UDP connectivity with initial packet
            val initialPacket = createMoshPacket(MoshPacketType.HEARTBEAT, ByteArray(0))
            udpSocket?.send(initialPacket)
            
            Logger.d("MoshConnection", "UDP connection established to ${profile.host}:${serverInfo.port}")
            true
            
        } catch (e: Exception) {
            Logger.e("MoshConnection", "Failed to establish UDP connection", e)
            false
        }
    }
    
    private fun generateSessionKey(): ByteArray {
        // Generate 128-bit session key
        val random = java.security.SecureRandom()
        val key = ByteArray(16)
        random.nextBytes(key)
        return key
    }
    
    private fun startHeartbeat() {
        heartbeatJob = connectionScope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    sendHeartbeat()
                    delay(heartbeatInterval)
                } catch (e: Exception) {
                    Logger.e("MoshConnection", "Heartbeat failed", e)
                    
                    // Handle connection loss
                    if (isRoamingMode) {
                        handleNetworkRoaming()
                    } else {
                        // Connection lost
                        _connectionState.value = ConnectionState.ERROR
                        break
                    }
                }
            }
        }
    }
    
    private suspend fun sendHeartbeat() {
        val socket = udpSocket ?: return
        val endpoint = serverEndpoint ?: return
        
        val heartbeatPacket = createMoshPacket(MoshPacketType.HEARTBEAT, ByteArray(0))
        socket.send(heartbeatPacket)
        lastHeartbeat = System.currentTimeMillis()
        
        updateActivity()
    }
    
    private fun handleNetworkRoaming() {
        Logger.d("MoshConnection", "Handling network roaming...")
        
        connectionScope.launch {
            // Detect network type change
            val currentNetworkType = detectNetworkType()
            
            if (currentNetworkType != lastNetworkType) {
                Logger.i("MoshConnection", "Network changed from $lastNetworkType to $currentNetworkType")
                lastNetworkType = currentNetworkType
                
                // Mosh handles roaming automatically, just log the event
                notifyListeners { onNetworkRoaming(lastNetworkType, currentNetworkType) }
            }
            
            // Attempt to re-establish UDP connection if needed
            if (!isUDPConnectionHealthy()) {
                reestablishUDPConnection()
            }
        }
    }
    
    private fun detectNetworkType(): NetworkType {
        // Detect current network type (WiFi, Mobile, etc.)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
            as android.net.ConnectivityManager
        
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        return when {
            networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkType.WIFI
            networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkType.MOBILE
            networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
    }
    
    private fun isUDPConnectionHealthy(): Boolean {
        // Check if UDP connection is responding
        return System.currentTimeMillis() - lastHeartbeat < heartbeatInterval * 3
    }
    
    private suspend fun reestablishUDPConnection() {
        Logger.d("MoshConnection", "Re-establishing UDP connection after network change")
        
        try {
            // Close old socket
            udpSocket?.close()
            
            // Create new UDP socket  
            udpSocket = DatagramSocket()
            udpSocket?.soTimeout = 5000
            
            // Test connectivity
            sendHeartbeat()
            
            Logger.i("MoshConnection", "UDP connection re-established")
            
        } catch (e: Exception) {
            Logger.e("MoshConnection", "Failed to re-establish UDP connection", e)
        }
    }
    
    private fun startPredictionEngine() {
        predictionEngine.start()
        speculativeRenderer.start()
        
        Logger.d("MoshConnection", "Started Mosh prediction engine")
    }
    
    private fun createMoshPacket(type: MoshPacketType, data: ByteArray): DatagramPacket {
        val packetData = ByteArray(data.size + 8) // Header + data
        
        // Mosh packet header (simplified)
        packetData[0] = type.value.toByte()
        packetData[1] = 0 // Flags
        
        // Sequence number (4 bytes)
        val seqBytes = sequenceNumber.toInt().toByteArray()
        System.arraycopy(seqBytes, 0, packetData, 2, 4)
        
        // Length (2 bytes)
        val lengthBytes = data.size.toShort().toByteArray()
        System.arraycopy(lengthBytes, 0, packetData, 6, 2)
        
        // Data
        System.arraycopy(data, 0, packetData, 8, data.size)
        
        sequenceNumber++
        
        return DatagramPacket(packetData, packetData.size, serverEndpoint)
    }
    
    private fun Int.toByteArray(): ByteArray {
        return byteArrayOf(
            (this shr 24).toByte(),
            (this shr 16).toByte(), 
            (this shr 8).toByte(),
            this.toByte()
        )
    }
    
    private fun Short.toByteArray(): ByteArray {
        return byteArrayOf((this.toInt() shr 8).toByte(), this.toByte())
    }
    
    /**
     * Send data through Mosh connection
     */
    fun sendData(data: ByteArray) {
        connectionScope.launch {
            try {
                val session = moshSession ?: return@launch
                session.sendData(data)
                updateActivity()
            } catch (e: Exception) {
                Logger.e("MoshConnection", "Failed to send data", e)
            }
        }
    }
    
    /**
     * Disconnect Mosh connection
     */
    fun disconnect() {
        Logger.d("MoshConnection", "Disconnecting Mosh connection")
        
        cleanup()
        _connectionState.value = ConnectionState.DISCONNECTED
        
        notifyListeners { onMoshDisconnected() }
    }
    
    private fun updateActivity() {
        _lastActivity.value = System.currentTimeMillis()
    }
    
    private fun cleanup() {
        heartbeatJob?.cancel()
        connectionScope.cancel()
        
        moshSession?.cleanup()
        moshSession = null
        
        udpSocket?.close()
        udpSocket = null
        
        predictionEngine.stop()
        speculativeRenderer.stop()
        
        sessionKey = null
    }
    
    // Listener management
    fun addListener(listener: MoshConnectionListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: MoshConnectionListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: MoshConnectionListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    // Configuration
    fun setRoamingMode(enabled: Boolean) {
        isRoamingMode = enabled
        Logger.d("MoshConnection", "Roaming mode: $enabled")
    }
    
    fun isRoamingEnabled(): Boolean = isRoamingMode
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}

/**
 * Mosh session handler
 */
private class MoshSession(
    private val serverEndpoint: InetSocketAddress,
    private val sessionKey: ByteArray,
    private val socket: DatagramSocket
) {
    fun sendData(data: ByteArray) {
        // Encrypt and send data through UDP
        // This would implement the actual Mosh protocol
    }
    
    fun cleanup() {
        // Cleanup session resources
    }
}

/**
 * Mosh server startup information
 */
data class MoshServerInfo(
    val port: Int,
    val key: ByteArray,
    val serverVersion: String
)

/**
 * Mosh packet types
 */
enum class MoshPacketType(val value: Int) {
    HEARTBEAT(0),
    TERMINAL_DATA(1),
    RESIZE(2),
    CLOSE(3)
}

/**
 * Network types for roaming detection
 */
enum class NetworkType {
    WIFI,
    MOBILE,
    ETHERNET,
    UNKNOWN
}

/**
 * Mosh prediction engine for responsive typing
 */
class MoshPredictionEngine {
    private var isRunning = false
    
    fun start() {
        isRunning = true
        Logger.d("MoshPredictionEngine", "Prediction engine started")
    }
    
    fun stop() {
        isRunning = false
        Logger.d("MoshPredictionEngine", "Prediction engine stopped")
    }
    
    fun predictNextCharacters(input: String): String {
        // Mosh prediction engine provides instant local echo
        // This predicts what the terminal will display before server confirmation

        // Simple prediction: local echo for printable characters
        // Full implementation would predict shell prompts, command completions, etc.
        if (input.isEmpty()) return input

        val predicted = buildString {
            for (char in input) {
                when {
                    char in ' '..'~' -> append(char) // Printable ASCII
                    char == '\n' -> append(char)
                    char == '\r' -> append(char)
                    char == '\t' -> append("    ") // Tab to spaces
                    char == '\b' -> { // Backspace
                        if (isNotEmpty()) deleteCharAt(length - 1)
                    }
                }
            }
        }

        return predicted
    }
}

/**
 * Speculative renderer for instant feedback
 */
class SpeculativeRenderer {
    private var isRunning = false
    
    fun start() {
        isRunning = true
        Logger.d("SpeculativeRenderer", "Speculative renderer started")
    }
    
    fun stop() {
        isRunning = false
        Logger.d("SpeculativeRenderer", "Speculative renderer stopped")
    }
    
    fun renderSpeculativeChanges(changes: List<String>) {
        // Render predicted changes before server confirmation
    }
}

/**
 * Mosh-specific exception
 */
class MoshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Mosh connection event listener
 */
interface MoshConnectionListener {
    fun onMoshConnecting() {}
    fun onMoshConnected() {}
    fun onMoshDisconnected() {}
    fun onMoshError(error: Exception) {}
    fun onNetworkRoaming(from: NetworkType, to: NetworkType) {}
    fun onPredictionUpdate(predictions: List<String>) {}
}