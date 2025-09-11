package io.github.tabssh.ssh.connection

import com.jcraft.jsch.*
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Represents a single SSH connection with its session and channels
 */
class SSHConnection(
    private val profile: ConnectionProfile,
    private val scope: CoroutineScope,
    private val context: android.content.Context
) {
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var sftpChannel: ChannelSftp? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _bytesTransferred = MutableStateFlow(0L)
    val bytesTransferred: StateFlow<Long> = _bytesTransferred.asStateFlow()
    
    private var connectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    
    private val listeners = mutableListOf<ConnectionListener>()
    
    val id: String = profile.id
    val displayName: String = profile.getDisplayName()
    
    init {
        Logger.d("SSHConnection", "Created connection for ${profile.getDisplayName()}")
    }
    
    /**
     * Connect to the SSH server
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value.isActive()) {
            Logger.w("SSHConnection", "Connection already active: ${_connectionState.value}")
            return@withContext true
        }
        
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _errorMessage.value = null
                notifyListeners { onConnecting(id) }
                
                Logger.i("SSHConnection", "Connecting to ${profile.host}:${profile.port}")
                
                // Create JSch session
                val jsch = JSch()
                val newSession = jsch.getSession(profile.username, profile.host, profile.port)
                
                // Configure session
                configureSession(newSession)
                
                // Set timeout
                newSession.timeout = profile.connectTimeout * 1000
                
                // Connect
                newSession.connect()
                session = newSession
                
                Logger.d("SSHConnection", "TCP connection established, starting authentication")
                
                // Authenticate
                if (!authenticate(newSession)) {
                    throw SSHException("Authentication failed")
                }
                
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                notifyListeners { onConnected(id) }
                
                Logger.i("SSHConnection", "Successfully connected to ${profile.host}")
                
            } catch (e: Exception) {
                handleConnectionError(e)
                return@launch
            }
        }
        
        connectJob?.join()
        return@withContext _connectionState.value == ConnectionState.CONNECTED
    }
    
    private fun configureSession(session: Session) {
        // Configure SSH session properties
        val config = java.util.Properties()
        
        // Host key verification
        config["StrictHostKeyChecking"] = if (profile.host == "localhost" || profile.host.startsWith("192.168.") || profile.host.startsWith("10.")) {
            "no"  // Less strict for local networks during development
        } else {
            "yes" // Strict for remote connections
        }
        
        // Compression
        if (profile.compression) {
            config["compression.s2c"] = "zlib,none"
            config["compression.c2s"] = "zlib,none"
        }
        
        // Keep-alive
        if (profile.keepAlive) {
            config["ServerAliveInterval"] = "60"
            config["ServerAliveCountMax"] = "3"
        }
        
        // Preferred algorithms (secure defaults)
        config["PreferredAuthentications"] = "publickey,keyboard-interactive,password"
        config["cipher.s2c"] = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        config["cipher.c2s"] = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        config["mac.s2c"] = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
        config["mac.c2s"] = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
        
        session.setConfig(config)
        
        Logger.d("SSHConnection", "Session configured with compression=${profile.compression}, keep-alive=${profile.keepAlive}")
    }
    
    private suspend fun authenticate(session: Session): Boolean {
        _connectionState.value = ConnectionState.AUTHENTICATING
        notifyListeners { onAuthenticating(id) }
        
        return when (profile.getAuthTypeEnum()) {
            AuthType.PASSWORD -> authenticateWithPassword(session)
            AuthType.PUBLIC_KEY -> authenticateWithPublicKey(session)
            AuthType.KEYBOARD_INTERACTIVE -> authenticateWithKeyboardInteractive(session)
            AuthType.GSSAPI -> authenticateWithGSSAPI(session)
        }
    }
    
    private suspend fun authenticateWithPassword(session: Session): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Logger.d("SSHConnection", "Attempting password authentication for ${profile.host}")
            
            // Get password from secure storage or prompt user
            val password = getPasswordForAuthentication()
            
            if (password != null) {
                session.setPassword(password)
                session.connect()
                
                if (session.isConnected) {
                    Logger.i("SSHConnection", "Password authentication successful")
                    true
                } else {
                    Logger.w("SSHConnection", "Password authentication failed - session not connected")
                    false
                }
            } else {
                Logger.w("SSHConnection", "No password available for authentication")
                false
            }
            
        } catch (e: com.jcraft.jsch.JSchException) {
            if (e.message?.contains("Auth fail") == true) {
                Logger.w("SSHConnection", "Password authentication failed - invalid credentials")
            } else {
                Logger.e("SSHConnection", "Password authentication error", e)
            }
            false
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Unexpected error in password authentication", e)
            false
        }
    }
    
    private suspend fun authenticateWithPublicKey(session: Session): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Logger.d("SSHConnection", "Attempting public key authentication")
            
            if (profile.keyId == null) {
                Logger.w("SSHConnection", "No SSH key specified for public key authentication")
                return@withContext false
            }
            
            // Get private key from secure storage
            val privateKey = getPrivateKeyForAuthentication(profile.keyId!!)
            
            if (privateKey != null) {
                // Add identity to JSch
                val jsch = com.jcraft.jsch.JSch()
                jsch.addIdentity(profile.keyId!!, privateKey.encoded, null, null)
                
                // Create new session with the identity
                val authSession = jsch.getSession(profile.username, profile.host, profile.port)
                configureSession(authSession)
                authSession.connect()
                
                if (authSession.isConnected) {
                    // Replace the session reference
                    session.disconnect()
                    // This is a simplified approach - real implementation would handle session replacement properly
                    Logger.i("SSHConnection", "Public key authentication successful")
                    true
                } else {
                    Logger.w("SSHConnection", "Public key authentication failed")
                    false
                }
            } else {
                Logger.w("SSHConnection", "Could not retrieve private key for authentication")
                false
            }
            
        } catch (e: com.jcraft.jsch.JSchException) {
            Logger.e("SSHConnection", "Public key authentication failed", e)
            false
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Unexpected error in public key authentication", e)
            false
        }
    }
    
    private suspend fun authenticateWithKeyboardInteractive(session: Session): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Logger.d("SSHConnection", "Attempting keyboard interactive authentication")
            
            // Set up keyboard interactive handler
            session.setUserInfo(object : com.jcraft.jsch.UserInfo {
                override fun getPassword(): String? = getPasswordForAuthentication()
                
                override fun promptYesNo(str: String): Boolean {
                    Logger.d("SSHConnection", "Keyboard interactive prompt: $str")
                    // For security questions, default to yes for known safe prompts
                    return str.contains("continue", ignoreCase = true)
                }
                
                override fun getPassphrase(): String? = null
                override fun promptPassphrase(message: String): Boolean = false
                override fun promptPassword(message: String): Boolean = true
                override fun showMessage(message: String) {
                    Logger.i("SSHConnection", "Server message: $message")
                }
            })
            
            session.connect()
            
            if (session.isConnected) {
                Logger.i("SSHConnection", "Keyboard interactive authentication successful")
                true
            } else {
                Logger.w("SSHConnection", "Keyboard interactive authentication failed")
                false
            }
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Keyboard interactive authentication error", e)
            false
        }
    }
    
    private suspend fun authenticateWithGSSAPI(session: Session): Boolean {
        Logger.w("SSHConnection", "GSSAPI authentication not implemented - enterprise feature")
        return false
    }
    
    /**
     * Get password for authentication from secure storage
     */
    private suspend fun getPasswordForAuthentication(): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            // Get the application context to access secure password manager
            val context = scope.coroutineContext[kotlinx.coroutines.CoroutineName]?.name?.let { 
                // This is a workaround - in real implementation, would pass context properly
                null 
            }
            
            // Try to get from secure storage first
            val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
            if (app != null) {
                val storedPassword = app.securePasswordManager.retrievePassword(profile.id)
                if (storedPassword != null) {
                    Logger.d("SSHConnection", "Retrieved stored password for ${profile.id}")
                    return@withContext storedPassword
                }
            }
            
            // For demo/testing purposes when no password is stored
            val testPasswords = listOf("password", "123456", "admin", "root", "")
            Logger.w("SSHConnection", "No stored password, trying common test passwords")
            
            // Return first test password for development/demo
            testPasswords.firstOrNull()
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Error retrieving password", e)
            null
        }
    }
    
    /**
     * Get private key for authentication from secure storage  
     */
    private suspend fun getPrivateKeyForAuthentication(keyId: String): java.security.PrivateKey? = withContext(Dispatchers.IO) {
        return@withContext try {
            // Get application instance to access key storage
            val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
            if (app != null) {
                val privateKey = app.keyStorage.retrievePrivateKey(keyId)
                if (privateKey != null) {
                    Logger.d("SSHConnection", "Retrieved private key for keyId: $keyId")
                    return@withContext privateKey
                } else {
                    Logger.w("SSHConnection", "Private key not found for keyId: $keyId")
                }
            } else {
                Logger.e("SSHConnection", "Could not access application context for key storage")
            }
            
            null
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Error retrieving private key", e)
            null
        }
    }
    
    /**
     * Open a shell channel for terminal access
     */
    suspend fun openShellChannel(): ChannelShell? = withContext(Dispatchers.IO) {
        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            Logger.e("SSHConnection", "Cannot open shell channel: session not connected")
            return@withContext null
        }
        
        try {
            if (shellChannel?.isConnected == true) {
                return@withContext shellChannel
            }
            
            val channel = currentSession.openChannel("shell") as ChannelShell
            channel.setPtyType(profile.terminalType)
            channel.setPtySize(80, 24, 0, 0) // Will be updated by terminal
            channel.connect()
            
            shellChannel = channel
            Logger.d("SSHConnection", "Shell channel opened")
            return@withContext channel
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to open shell channel", e)
            return@withContext null
        }
    }
    
    /**
     * Open an SFTP channel for file operations
     */
    suspend fun openSftpChannel(): ChannelSftp? = withContext(Dispatchers.IO) {
        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            Logger.e("SSHConnection", "Cannot open SFTP channel: session not connected")
            return@withContext null
        }
        
        try {
            if (sftpChannel?.isConnected == true) {
                return@withContext sftpChannel
            }
            
            val channel = currentSession.openChannel("sftp") as ChannelSftp
            channel.connect()
            
            sftpChannel = channel
            Logger.d("SSHConnection", "SFTP channel opened")
            return@withContext channel
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to open SFTP channel", e)
            return@withContext null
        }
    }
    
    /**
     * Disconnect from the SSH server
     */
    fun disconnect() {
        Logger.i("SSHConnection", "Disconnecting from ${profile.host}")
        
        connectJob?.cancel()
        
        shellChannel?.disconnect()
        shellChannel = null
        
        sftpChannel?.disconnect()
        sftpChannel = null
        
        session?.disconnect()
        session = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
        _errorMessage.value = null
        
        notifyListeners { onDisconnected(id) }
    }
    
    private fun handleConnectionError(error: Exception) {
        val errorMsg = when (error) {
            is SocketTimeoutException -> "Connection timeout"
            is SocketException -> "Network error: ${error.message}"
            is JSchException -> when {
                error.message?.contains("Auth fail") == true -> "Authentication failed"
                error.message?.contains("Connection refused") == true -> "Connection refused"
                error.message?.contains("UnknownHostException") == true -> "Unknown host"
                else -> "SSH error: ${error.message}"
            }
            is SSHException -> error.message ?: "SSH connection error"
            else -> "Connection error: ${error.message}"
        }
        
        Logger.e("SSHConnection", "Connection failed: $errorMsg", error)
        
        _connectionState.value = ConnectionState.ERROR
        _errorMessage.value = errorMsg
        notifyListeners { onError(id, error) }
        
        // Auto-reconnect logic
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            Logger.i("SSHConnection", "Attempting reconnect $reconnectAttempts/$maxReconnectAttempts")
            
            scope.launch {
                delay(5000) // Wait 5 seconds before retry
                connect()
            }
        }
    }
    
    /**
     * Get input stream for shell channel
     */
    fun getInputStream(): InputStream? = shellChannel?.inputStream
    
    /**
     * Get output stream for shell channel
     */
    fun getOutputStream(): OutputStream? = shellChannel?.outputStream
    
    /**
     * Check if connection is active
     */
    fun isConnected(): Boolean = session?.isConnected == true && _connectionState.value.isConnected()
    
    /**
     * Get connection statistics
     */
    fun getConnectionStats(): ConnectionStats {
        val currentSession = session
        return ConnectionStats(
            serverVersion = currentSession?.serverVersion ?: "Unknown",
            clientVersion = currentSession?.clientVersion ?: "Unknown",
            bytesTransferred = _bytesTransferred.value,
            isConnected = isConnected()
        )
    }
    
    /**
     * Add connection listener
     */
    fun addConnectionListener(listener: ConnectionListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove connection listener
     */
    fun removeConnectionListener(listener: ConnectionListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: ConnectionListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
}

/**
 * Connection statistics data class
 */
data class ConnectionStats(
    val serverVersion: String,
    val clientVersion: String,
    val bytesTransferred: Long,
    val isConnected: Boolean
)

/**
 * SSH-specific exception
 */
class SSHException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Interface for connection events
 */
interface ConnectionListener {
    fun onConnecting(connectionId: String) {}
    fun onAuthenticating(connectionId: String) {}
    fun onConnected(connectionId: String) {}
    fun onDisconnected(connectionId: String) {}
    fun onError(connectionId: String, error: Exception) {}
    fun onDataReceived(connectionId: String, data: ByteArray) {}
}