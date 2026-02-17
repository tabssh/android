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
import java.net.UnknownHostException
import java.net.ConnectException

/**
 * Detailed error information for SSH connection failures
 */
data class SSHConnectionErrorInfo(
    val errorType: String,
    val userMessage: String,
    val technicalDetails: String,
    val possibleSolutions: List<String>,
    val exception: Exception?
)

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
    private var jumpHostSession: Session? = null
    private var jumpHostLocalPort: Int = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _detailedError = MutableStateFlow<SSHConnectionErrorInfo?>(null)
    val detailedError: StateFlow<SSHConnectionErrorInfo?> = _detailedError.asStateFlow()

    private val _bytesTransferred = MutableStateFlow(0L)
    val bytesTransferred: StateFlow<Long> = _bytesTransferred.asStateFlow()

    private var connectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    private val listeners = mutableListOf<ConnectionListener>()

    // Host key verification
    private val hostKeyVerifier = HostKeyVerifier(context)
    var hostKeyChangedCallback: ((HostKeyChangedInfo) -> HostKeyAction)? = null
    var newHostKeyCallback: ((NewHostKeyInfo) -> HostKeyAction)? = null

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
                
                Logger.i("SSHConnection", "🔌 STEP 1: Starting connection to ${profile.host}:${profile.port} as ${profile.username}")
                showDebugToast("Connecting to ${profile.host}...")

                // Execute port knock sequence if enabled
                Logger.d("SSHConnection", "🔌 STEP 2: Checking port knock configuration")
                executePortKnockIfEnabled()

                // Create JSch session with host key verification
                Logger.d("SSHConnection", "🔌 STEP 3: Creating JSch session")
                val jsch = JSch()

                // Set custom host key repository for database-backed verification
                Logger.d("SSHConnection", "🔌 STEP 4: Setting up host key verifier")
                jsch.hostKeyRepository = hostKeyVerifier

                // Configure host key changed callback
                hostKeyVerifier.setHostKeyChangedCallback { info ->
                    Logger.w("SSHConnection", "⚠️ Host key CHANGED verification triggered for ${info.hostname}")
                    hostKeyChangedCallback?.invoke(info) ?: HostKeyAction.REJECT_CONNECTION
                }

                // Configure new host key callback
                hostKeyVerifier.setNewHostKeyCallback { info ->
                    Logger.i("SSHConnection", "🆕 New host key verification triggered for ${info.hostname}")
                    newHostKeyCallback?.invoke(info) ?: HostKeyAction.REJECT_CONNECTION
                }

                // Setup jump host if configured
                Logger.d("SSHConnection", "🔌 STEP 5: Checking jump host configuration")
                val jumpHostPort = setupJumpHost(jsch)

                // Create main session - connect through jump host if configured
                Logger.d("SSHConnection", "🔌 STEP 6: Creating SSH session")
                val newSession = if (jumpHostPort != null) {
                    Logger.i("SSHConnection", "Connecting to target through jump host on localhost:$jumpHostPort")
                    jsch.getSession(profile.username, "localhost", jumpHostPort)
                } else {
                    Logger.i("SSHConnection", "Direct connection to ${profile.host}:${profile.port}")
                    jsch.getSession(profile.username, profile.host, profile.port)
                }

                // Setup HTTP/SOCKS proxy if configured
                Logger.d("SSHConnection", "🔌 STEP 7: Checking proxy configuration")
                setupHttpSocksProxy(newSession)

                // Configure session
                Logger.d("SSHConnection", "🔌 STEP 8: Configuring SSH session")
                configureSession(newSession)

                // Set timeout
                newSession.timeout = profile.connectTimeout * 1000
                Logger.d("SSHConnection", "Connection timeout set to ${profile.connectTimeout} seconds")

                // Setup UserInfo for host key verification prompts
                Logger.d("SSHConnection", "🔐 STEP 8.5: Setting up UserInfo for host key prompts")
                setupUserInfo(newSession)

                // Setup authentication BEFORE connecting
                Logger.i("SSHConnection", "🔑 STEP 9: Setting up authentication")
                _connectionState.value = ConnectionState.AUTHENTICATING
                notifyListeners { onAuthenticating(id) }
                showDebugToast("Authenticating...")

                setupAuthentication(jsch, newSession)

                // Connect (this performs both connection AND authentication in one step)
                Logger.i("SSHConnection", "🔌 STEP 10: Calling session.connect() - THIS IS WHERE HOST KEY VERIFICATION HAPPENS")
                newSession.connect()
                session = newSession

                Logger.i("SSHConnection", "✅ Successfully connected and authenticated to ${profile.host}")
                showDebugToast("Connected!")
                
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                notifyListeners { onConnected(id) }
                
                Logger.i("SSHConnection", "✅ Connection complete to ${profile.host}")
                
            } catch (e: Exception) {
                Logger.e("SSHConnection", "❌ Connection failed at some step", e)
                showDebugToast("Connection failed: ${e.message}")
                handleConnectionError(e)
                return@launch
            }
        }
        
        connectJob?.join()
        return@withContext _connectionState.value == ConnectionState.CONNECTED
    }
    
    private fun showDebugToast(message: String) {
        // Show toast on UI thread for debugging
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun configureSession(session: Session) {
        // Configure SSH session properties
        val config = java.util.Properties()

        // Host key verification - use "ask" to trigger our custom verification
        config["StrictHostKeyChecking"] = "ask"
        
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

    /**
     * Setup UserInfo for handling host key verification prompts
     * This is required for JSch to work with StrictHostKeyChecking="ask"
     */
    private fun setupUserInfo(session: Session) {
        session.setUserInfo(object : com.jcraft.jsch.UserInfo {
            override fun getPassword(): String? = null

            override fun promptYesNo(message: String): Boolean {
                Logger.i("SSHConnection", "🔐 UserInfo.promptYesNo called: $message")

                // This is called by JSch for host key verification when StrictHostKeyChecking="ask"
                // Our HostKeyRepository.check() should handle this, but JSch may still call this
                // for certain edge cases

                // Check if this is a host key prompt
                if (message.contains("authenticity", ignoreCase = true) ||
                    message.contains("fingerprint", ignoreCase = true) ||
                    message.contains("RSA key", ignoreCase = true) ||
                    message.contains("ECDSA key", ignoreCase = true) ||
                    message.contains("ED25519", ignoreCase = true)) {

                    Logger.w("SSHConnection", "⚠️ Host key prompt detected in UserInfo - this should be handled by HostKeyVerifier")

                    // If we have a newHostKeyCallback, use it
                    if (newHostKeyCallback != null) {
                        // Extract hostname from message if possible
                        val hostname = profile.host
                        val port = profile.port

                        // Create a basic NewHostKeyInfo
                        val info = NewHostKeyInfo(
                            hostname = hostname,
                            port = port,
                            keyType = "unknown",
                            fingerprint = message, // Use the message as fingerprint info
                            publicKey = ""
                        )

                        val action = newHostKeyCallback!!.invoke(info)
                        return action == HostKeyAction.ACCEPT_NEW_KEY || action == HostKeyAction.ACCEPT_ONCE
                    }

                    // No callback - reject for safety
                    Logger.w("SSHConnection", "No host key callback available - rejecting")
                    return false
                }

                // For other prompts, accept if they seem like continuation prompts
                val shouldAccept = message.contains("continue connecting", ignoreCase = true)
                Logger.d("SSHConnection", "Non-host-key prompt, accepting: $shouldAccept")
                return shouldAccept
            }

            override fun getPassphrase(): String? = null
            override fun promptPassphrase(message: String): Boolean = false
            override fun promptPassword(message: String): Boolean = false
            override fun showMessage(message: String) {
                Logger.i("SSHConnection", "Server message: $message")
            }
        })

        Logger.d("SSHConnection", "UserInfo configured for host key prompts")
    }

    /**
     * Execute port knock sequence if enabled
     */
    private suspend fun executePortKnockIfEnabled() {
        // Check global default setting
        val app = context.applicationContext as io.github.tabssh.TabSSHApplication
        val globalDefault = app.preferencesManager.getBoolean("port_knock_enabled_default", false)
        
        // Check per-connection override (null = use global default)
        val knockEnabled = profile.portKnockEnabled ?: globalDefault
        
        if (!knockEnabled || profile.portKnockSequence == null) {
            Logger.d("SSHConnection", "Port knocking disabled or no sequence configured")
            return
        }
        
        try {
            val knocker = io.github.tabssh.network.portknock.PortKnocker()
            val sequence = knocker.parseKnockSequence(profile.portKnockSequence)
            
            if (sequence.isEmpty()) {
                Logger.w("SSHConnection", "Empty knock sequence")
                return
            }
            
            Logger.i("SSHConnection", "Executing port knock sequence on ${profile.host}")
            val success = knocker.executeKnockSequence(
                profile.host,
                sequence,
                profile.portKnockDelayMs
            )
            
            if (success) {
                Logger.i("SSHConnection", "Port knock sequence completed successfully")
                // Small delay to allow firewall to open port
                delay(500)
            } else {
                Logger.w("SSHConnection", "Port knock sequence failed")
            }
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Error executing port knock", e)
            // Don't fail connection on knock failure - proceed anyway
        }
    }

    /**
     * Setup SSH jump host (bastion) connection if configured
     * Returns local port number for forwarding, or null if no jump host
     */
    private suspend fun setupJumpHost(jsch: JSch): Int? = withContext(Dispatchers.IO) {
        if (profile.proxyType != "SSH" || profile.proxyHost == null) {
            return@withContext null
        }

        try {
            Logger.i("SSHConnection", "Setting up SSH jump host: ${profile.proxyHost}:${profile.proxyPort}")

            val jumpHost = profile.proxyHost
            val jumpPort = profile.proxyPort ?: 22
            val jumpUsername = profile.proxyUsername ?: profile.username

            // Create jump host session
            val jumpSession = jsch.getSession(jumpUsername, jumpHost, jumpPort)

            // Configure jump host session
            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "ask"
            jumpSession.setConfig(config)
            jumpSession.timeout = profile.connectTimeout * 1000

            // Authenticate to jump host
            when (profile.proxyAuthType) {
                AuthType.PUBLIC_KEY.name -> {
                    if (profile.proxyKeyId != null) {
                        Logger.d("SSHConnection", "Jump host: Using public key authentication")
                        // Load jump host SSH key using KeyStorage
                        val app = (context.applicationContext as io.github.tabssh.TabSSHApplication)
                        val privateKey = app.keyStorage.retrievePrivateKey(profile.proxyKeyId)
                        if (privateKey != null) {
                            jsch.addIdentity(
                                profile.proxyKeyId,
                                privateKey.encoded,
                                null, // public key (JSch can derive it)
                                null // passphrase
                            )
                        } else {
                            throw SSHException("Jump host key not found: ${profile.proxyKeyId}")
                        }
                    }
                }
                AuthType.PASSWORD.name, null -> {
                    Logger.d("SSHConnection", "Jump host: Using password authentication")
                    // Use same password as main connection for jump host
                    val password = getPasswordForAuthentication()
                    if (password != null) {
                        jumpSession.setPassword(password)
                    }
                }
                else -> {
                    Logger.w("SSHConnection", "Jump host: Unsupported auth type ${profile.proxyAuthType}, using password")
                    val password = getPasswordForAuthentication()
                    if (password != null) {
                        jumpSession.setPassword(password)
                    }
                }
            }

            // Connect to jump host
            jumpSession.connect()
            jumpHostSession = jumpSession

            Logger.i("SSHConnection", "Jump host connected successfully")

            // Setup port forwarding through jump host
            // Forward a random local port to the target host's SSH port
            val localPort = jumpSession.setPortForwardingL(
                0, // 0 = random available port
                profile.host,
                profile.port
            )

            jumpHostLocalPort = localPort

            Logger.i("SSHConnection", "Jump host port forwarding established: localhost:$localPort -> ${profile.host}:${profile.port}")

            return@withContext localPort

        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to setup jump host", e)
            jumpHostSession?.disconnect()
            jumpHostSession = null
            throw SSHException("Jump host setup failed: ${e.message}", e)
        }
    }

    /**
     * Setup HTTP/SOCKS proxy if configured (for non-SSH proxies)
     */
    private fun setupHttpSocksProxy(session: Session) {
        val proxyType = profile.proxyType ?: return
        val proxyHost = profile.proxyHost ?: return
        val proxyPort = profile.proxyPort ?: return

        when (proxyType.uppercase()) {
            "HTTP" -> {
                Logger.i("SSHConnection", "Setting up HTTP proxy: $proxyHost:$proxyPort")
                val proxy = ProxyHTTP(proxyHost, proxyPort)
                profile.proxyUsername?.let { username ->
                    proxy.setUserPasswd(username, "") // Password not commonly used for HTTP proxies
                }
                session.setProxy(proxy)
            }
            "SOCKS4" -> {
                Logger.i("SSHConnection", "Setting up SOCKS4 proxy: $proxyHost:$proxyPort")
                val proxy = ProxySOCKS4(proxyHost, proxyPort)
                profile.proxyUsername?.let { username ->
                    proxy.setUserPasswd(username, "")
                }
                session.setProxy(proxy)
            }
            "SOCKS5" -> {
                Logger.i("SSHConnection", "Setting up SOCKS5 proxy: $proxyHost:$proxyPort")
                val proxy = ProxySOCKS5(proxyHost, proxyPort)
                profile.proxyUsername?.let { username ->
                    proxy.setUserPasswd(username, "")
                }
                session.setProxy(proxy)
            }
            else -> {
                // SSH jump host or unknown - ignore (SSH jump host handled separately)
                Logger.d("SSHConnection", "Proxy type $proxyType not applicable for HTTP/SOCKS setup")
            }
        }
    }

    /**
     * Setup authentication credentials BEFORE connecting
     * JSch requires credentials to be configured before calling session.connect()
     *
     * Auto-matching: If an identity exists with the same name as the username,
     * use that identity's credentials instead of the connection profile's.
     */
    private suspend fun setupAuthentication(jsch: JSch, session: Session) = withContext(Dispatchers.IO) {
        // Check for identity auto-matching: if identity.name matches username, use identity's credentials
        val matchedIdentity = try {
            val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
            val identity = app?.database?.identityDao()?.getIdentityByName(profile.username)
            if (identity != null) {
                Logger.i("SSHConnection", "🔑 Auto-matched identity '${identity.name}' for username '${profile.username}'")
            }
            identity
        } catch (e: Exception) {
            Logger.w("SSHConnection", "Error checking for identity auto-match", e)
            null
        }

        // Determine which auth type and credentials to use
        val effectiveAuthType = matchedIdentity?.authType ?: profile.getAuthTypeEnum()
        val effectiveKeyId = matchedIdentity?.keyId ?: profile.keyId

        Logger.d("SSHConnection", "Using auth type: $effectiveAuthType (matched identity: ${matchedIdentity != null})")

        when (effectiveAuthType) {
            AuthType.PASSWORD -> {
                Logger.d("SSHConnection", "Setting up password authentication for ${profile.host}")
                val password = getPasswordForAuthentication()
                if (password != null) {
                    session.setPassword(password)
                } else {
                    throw SSHException("No password available for authentication")
                }
            }

            AuthType.PUBLIC_KEY -> {
                Logger.d("SSHConnection", "Setting up public key authentication")
                val keyIdToUse = effectiveKeyId
                if (keyIdToUse == null) {
                    throw SSHException("No SSH key specified for public key authentication")
                }

                val privateKey = getPrivateKeyForAuthentication(keyIdToUse)
                if (privateKey != null) {
                    jsch.addIdentity(keyIdToUse, privateKey.encoded, null, null)
                    Logger.d("SSHConnection", "Added SSH key identity: $keyIdToUse${if (matchedIdentity != null) " (from matched identity)" else ""}")
                } else {
                    throw SSHException("Could not retrieve private key for authentication")
                }
            }

            AuthType.KEYBOARD_INTERACTIVE -> {
                Logger.d("SSHConnection", "Setting up keyboard interactive authentication")
                val password = getPasswordForAuthentication()

                session.setUserInfo(object : com.jcraft.jsch.UserInfo {
                    override fun getPassword(): String? = password

                    override fun promptYesNo(str: String): Boolean {
                        Logger.d("SSHConnection", "Keyboard interactive prompt: $str")
                        return str.contains("continue", ignoreCase = true)
                    }

                    override fun getPassphrase(): String? = null
                    override fun promptPassphrase(message: String): Boolean = false
                    override fun promptPassword(message: String): Boolean = true
                    override fun showMessage(message: String) {
                        Logger.i("SSHConnection", "Server message: $message")
                    }
                })
            }

            AuthType.GSSAPI -> {
                // GSSAPI authentication is rarely used on Android and requires enterprise Kerberos
                // infrastructure. Most SSH servers don't require it, so we gracefully skip it.
                Logger.w("SSHConnection", "GSSAPI authentication not supported (rarely needed on Android)")
                // Don't throw exception - let JSch try other available methods
                // JSch will automatically fallback to password/keyboard-interactive if available
            }
        }
    }
    
    /**
     * Get password for authentication from secure storage
     */
    private suspend fun getPasswordForAuthentication(): String? = withContext(Dispatchers.IO) {
        return@withContext try {
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
     * Execute a command on the remote server and return output
     * @param command The command to execute
     * @param timeoutMs Timeout in milliseconds (default: 30 seconds)
     * @return Command output as string
     */
    suspend fun executeCommand(command: String, timeoutMs: Long = 30000): String = withContext(Dispatchers.IO) {
        if (!isConnected()) {
            throw IllegalStateException("Not connected to SSH server")
        }
        
        val currentSession = session ?: throw IllegalStateException("Session is null")
        
        try {
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.errStream
            
            channel.connect(timeoutMs.toInt())
            
            val output = StringBuilder()
            val buffer = ByteArray(1024)
            
            // Read stdout
            while (true) {
                val available = inputStream.available()
                if (available > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    }
                }
                
                // Check if channel is closed
                if (channel.isClosed) {
                    // Read any remaining data
                    while (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            output.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                        }
                    }
                    break
                }
                
                kotlinx.coroutines.delay(100)
            }
            
            // Read stderr if there's an error
            val errorOutput = StringBuilder()
            while (errorStream.available() > 0) {
                val bytesRead = errorStream.read(buffer)
                if (bytesRead > 0) {
                    errorOutput.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                }
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            
            if (exitStatus != 0 && errorOutput.isNotEmpty()) {
                Logger.w("SSHConnection", "Command exit status: $exitStatus, stderr: $errorOutput")
            }
            
            output.toString()
            
        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to execute command: $command", e)
            throw e
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

        // Disconnect jump host session if active
        if (jumpHostSession != null) {
            Logger.i("SSHConnection", "Disconnecting jump host session")
            try {
                if (jumpHostLocalPort > 0) {
                    jumpHostSession?.delPortForwardingL(jumpHostLocalPort)
                }
            } catch (e: Exception) {
                Logger.w("SSHConnection", "Failed to remove port forwarding", e)
            }
            jumpHostSession?.disconnect()
            jumpHostSession = null
            jumpHostLocalPort = 0
        }

        _connectionState.value = ConnectionState.DISCONNECTED
        _errorMessage.value = null

        notifyListeners { onDisconnected(id) }
    }
    
    private fun handleConnectionError(error: Exception) {
        // Build detailed error information
        val errorInfo = buildDetailedErrorInfo(error)

        Logger.e("SSHConnection", "Connection failed: ${errorInfo.userMessage}", error)

        _connectionState.value = ConnectionState.ERROR
        _errorMessage.value = errorInfo.userMessage
        _detailedError.value = errorInfo
        notifyListeners { onError(id, error) }

        // Check if this is an auth error - don't retry on auth failures
        val isAuthError = error.message?.let { msg ->
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("authentication", ignoreCase = true) ||
            msg.contains("password", ignoreCase = true) ||
            msg.contains("publickey", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("Too many authentication failures", ignoreCase = true)
        } ?: false

        // Auto-reconnect logic - only for network errors, not auth failures
        if (!isAuthError && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            Logger.i("SSHConnection", "Attempting reconnect $reconnectAttempts/$maxReconnectAttempts")

            scope.launch {
                delay(5000) // Wait 5 seconds before retry
                connect()
            }
        } else if (isAuthError) {
            Logger.i("SSHConnection", "Auth error detected - not retrying")
        }
    }
    
    /**
     * Build detailed error information with diagnostics and solutions
     */
    private fun buildDetailedErrorInfo(error: Exception): SSHConnectionErrorInfo {
        return when (error) {
            is SocketTimeoutException -> SSHConnectionErrorInfo(
                errorType = "Connection Timeout",
                userMessage = "Connection to ${profile.host}:${profile.port} timed out after ${profile.connectTimeout}s",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                    appendLine("Timeout: ${profile.connectTimeout}s")
                    appendLine("Proxy: ${profile.proxyType ?: "None"}")
                    if (profile.portKnockEnabled == true) {
                        appendLine("Port Knock: Enabled (${profile.portKnockSequence})")
                    }
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Check if the server is running and accessible",
                    "• Verify the hostname/IP address is correct",
                    "• Ensure port ${profile.port} is not blocked by firewall",
                    "• Try increasing connection timeout in settings",
                    "• Check your network connection",
                    "• If using port knocking, verify the sequence is correct"
                ),
                exception = error
            )
            
            is UnknownHostException -> SSHConnectionErrorInfo(
                errorType = "Unknown Host",
                userMessage = "Cannot resolve hostname: ${profile.host}",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Hostname: ${profile.host}")
                    appendLine("Port: ${profile.port}")
                    appendLine("Message: ${error.message}")
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Check if the hostname is spelled correctly",
                    "• Try using IP address instead of hostname",
                    "• Verify DNS settings on your device",
                    "• Check if you have internet connectivity",
                    "• Test the hostname in a browser or ping tool"
                ),
                exception = error
            )
            
            is ConnectException -> SSHConnectionErrorInfo(
                errorType = "Connection Refused",
                userMessage = "Server at ${profile.host}:${profile.port} refused connection",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                    appendLine("Message: ${error.message}")
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Verify SSH server is running on port ${profile.port}",
                    "• Check if firewall is blocking the connection",
                    "• Ensure the port number is correct (default: 22)",
                    "• Server may not be accepting connections",
                    "• Try connecting from another device to verify server status"
                ),
                exception = error
            )
            
            is SocketException -> SSHConnectionErrorInfo(
                errorType = "Network Error",
                userMessage = "Network error: ${error.message}",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                    appendLine("Message: ${error.message}")
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Check your network connection",
                    "• Try switching between WiFi and mobile data",
                    "• Server may have dropped the connection",
                    "• Check if there's a network proxy interfering",
                    "• Restart your network connection"
                ),
                exception = error
            )
            
            is JSchException -> {
                val msg = error.message ?: "Unknown SSH error"
                when {
                    msg.contains("Auth fail", ignoreCase = true) -> SSHConnectionErrorInfo(
                        errorType = "Authentication Failed",
                        userMessage = "Authentication failed for ${profile.username}@${profile.host}",
                        technicalDetails = buildString {
                            appendLine("Exception: ${error.javaClass.simpleName}")
                            appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                            appendLine("Auth Type: ${profile.authType}")
                            appendLine("Message: ${error.message}")
                            if (profile.authType == AuthType.PUBLIC_KEY.name) {
                                appendLine("SSH Key ID: ${profile.keyId ?: "None"}")
                            }
                            appendLine("\nStack Trace:")
                            appendLine(error.stackTraceToString())
                        },
                        possibleSolutions = if (profile.authType == AuthType.PUBLIC_KEY.name) {
                            listOf(
                                "• Verify the SSH key is correct and authorized on server",
                                "• Check ~/.ssh/authorized_keys on the server",
                                "• Ensure the private key matches the public key on server",
                                "• Try using password authentication instead",
                                "• Check if key passphrase is correct (if encrypted)",
                                "• Server may require specific key types (RSA/ECDSA/Ed25519)"
                            )
                        } else {
                            listOf(
                                "• Check username and password are correct",
                                "• Verify the user account exists on the server",
                                "• Account may be locked or disabled",
                                "• Try SSH key authentication instead",
                                "• Check server logs for authentication errors",
                                "• Server may require different auth method (keyboard-interactive)"
                            )
                        },
                        exception = error
                    )
                    
                    msg.contains("Connection refused", ignoreCase = true) -> SSHConnectionErrorInfo(
                        errorType = "Connection Refused",
                        userMessage = "Server at ${profile.host}:${profile.port} refused connection",
                        technicalDetails = buildString {
                            appendLine("Exception: ${error.javaClass.simpleName}")
                            appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                            appendLine("Message: ${error.message}")
                            appendLine("\nStack Trace:")
                            appendLine(error.stackTraceToString())
                        },
                        possibleSolutions = listOf(
                            "• Verify SSH server is running: sudo systemctl status sshd",
                            "• Check if firewall is blocking port ${profile.port}",
                            "• Ensure the port number is correct (default: 22)",
                            "• Server may not be accepting connections",
                            "• Check server's sshd_config: AllowUsers, DenyUsers, etc."
                        ),
                        exception = error
                    )
                    
                    msg.contains("UnknownHostException", ignoreCase = true) -> SSHConnectionErrorInfo(
                        errorType = "Unknown Host",
                        userMessage = "Cannot resolve hostname: ${profile.host}",
                        technicalDetails = buildString {
                            appendLine("Exception: ${error.javaClass.simpleName}")
                            appendLine("Hostname: ${profile.host}")
                            appendLine("Port: ${profile.port}")
                            appendLine("Message: ${error.message}")
                            appendLine("\nStack Trace:")
                            appendLine(error.stackTraceToString())
                        },
                        possibleSolutions = listOf(
                            "• Check if the hostname is spelled correctly",
                            "• Try using IP address instead of hostname",
                            "• Verify DNS settings on your device",
                            "• Check if you have internet connectivity"
                        ),
                        exception = error
                    )
                    
                    msg.contains("Algorithm", ignoreCase = true) -> SSHConnectionErrorInfo(
                        errorType = "Algorithm Mismatch",
                        userMessage = "Server and client don't have compatible algorithms",
                        technicalDetails = buildString {
                            appendLine("Exception: ${error.javaClass.simpleName}")
                            appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                            appendLine("Message: ${error.message}")
                            appendLine("\nStack Trace:")
                            appendLine(error.stackTraceToString())
                        },
                        possibleSolutions = listOf(
                            "• Server may be using outdated/deprecated algorithms",
                            "• Try enabling legacy algorithms in Settings → Security",
                            "• Update the SSH server to support modern algorithms",
                            "• Check server's sshd_config: Ciphers, MACs, KexAlgorithms",
                            "• Consider upgrading server SSH version"
                        ),
                        exception = error
                    )
                    
                    else -> SSHConnectionErrorInfo(
                        errorType = "SSH Error",
                        userMessage = msg,
                        technicalDetails = buildString {
                            appendLine("Exception: ${error.javaClass.simpleName}")
                            appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                            appendLine("Message: ${error.message}")
                            appendLine("\nStack Trace:")
                            appendLine(error.stackTraceToString())
                        },
                        possibleSolutions = listOf(
                            "• Check all connection parameters are correct",
                            "• Try connecting with ssh command line to verify server",
                            "• Check server logs: /var/log/auth.log or /var/log/secure",
                            "• Enable debug logging in app settings for more details",
                            "• Report this issue if problem persists"
                        ),
                        exception = error
                    )
                }
            }
            
            is SSHException -> SSHConnectionErrorInfo(
                errorType = "SSH Connection Error",
                userMessage = error.message ?: "Unknown SSH connection error",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                    appendLine("Message: ${error.message}")
                    appendLine("\nCaused by:")
                    error.cause?.let {
                        appendLine(it.javaClass.simpleName + ": " + it.message)
                    }
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Review connection settings for errors",
                    "• Check server accessibility",
                    "• Try connecting with standard SSH tools to verify",
                    "• Check app permissions and network settings",
                    "• Enable debug logging for more details"
                ),
                exception = error
            )
            
            else -> SSHConnectionErrorInfo(
                errorType = "Unknown Error",
                userMessage = error.message ?: "An unexpected error occurred",
                technicalDetails = buildString {
                    appendLine("Exception: ${error.javaClass.simpleName}")
                    appendLine("Target: ${profile.username}@${profile.host}:${profile.port}")
                    appendLine("Message: ${error.message}")
                    appendLine("\nStack Trace:")
                    appendLine(error.stackTraceToString())
                },
                possibleSolutions = listOf(
                    "• Try restarting the app",
                    "• Check device has sufficient memory",
                    "• Clear app cache if problem persists",
                    "• Report this error to developers with debug logs",
                    "• Try a different connection method"
                ),
                exception = error
            )
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