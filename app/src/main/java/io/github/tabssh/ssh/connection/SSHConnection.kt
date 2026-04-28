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

    // Last decision returned from the HostKeyRepository.check() callback path.
    // UserInfo.promptYesNo() consults this so it never fires a second dialog
    // for the same host (Issues #33 / #34).
    private var lastHostKeyDecision: HostKeyAction? = null

    // Cached resolved identity (loaded on connect)
    private var resolvedIdentity: io.github.tabssh.storage.database.entities.Identity? = null

    // Cached password for UserInfo callbacks (set during setupAuthentication)
    private var cachedPassword: String? = null

    // Cached passphrase for encrypted SSH keys
    private var cachedPassphrase: String? = null

    val id: String = profile.id
    val displayName: String = profile.getDisplayName()

    init {
        Logger.d("SSHConnection", "Created connection for ${profile.getDisplayName()}")
    }
    
    /**
     * Connect to the SSH server
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Trust the JSch session over our state field — after a remote-side
        // EOF the JSch session tears down without our DISCONNECTED transition
        // firing, leaving _connectionState stuck at CONNECTED. If the session
        // is actually dead, fall through and reconnect.
        val sessionAlive = session?.isConnected == true
        if (_connectionState.value.isActive() && sessionAlive) {
            Logger.w("SSHConnection", "Connection already active: ${_connectionState.value}")
            return@withContext true
        }
        if (!sessionAlive && _connectionState.value.isActive()) {
            Logger.i("SSHConnection", "State was ${_connectionState.value} but JSch session is dead — reconnecting")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                _errorMessage.value = null
                notifyListeners { onConnecting(id) }

                // Resolve linked identity if set (for effective username/credentials)
                val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
                Logger.d("SSHConnection", "Profile identityId: ${profile.identityId}")
                resolvedIdentity = if (profile.identityId != null) {
                    try {
                        val identity = app?.database?.identityDao()?.getIdentityById(profile.identityId!!)
                        if (identity != null) {
                            Logger.i("SSHConnection", "Using identity '${identity.name}' (keyId=${identity.keyId}, authType=${identity.authType})")
                        } else {
                            Logger.w("SSHConnection", "Identity not found in DB for id: ${profile.identityId}")
                        }
                        identity
                    } catch (e: Exception) {
                        Logger.w("SSHConnection", "Error loading linked identity", e)
                        null
                    }
                } else {
                    Logger.d("SSHConnection", "No identity linked to this connection")
                    null
                }

                // Use identity username if available, otherwise profile username
                val effectiveUsername = resolvedIdentity?.username ?: profile.username

                Logger.i("SSHConnection", "STEP 1: Starting connection to ${profile.host}:${profile.port} as $effectiveUsername")

                // Execute port knock sequence if enabled
                Logger.d("SSHConnection", "STEP 2: Checking port knock configuration")
                executePortKnockIfEnabled()

                // Create JSch session with host key verification
                Logger.d("SSHConnection", "STEP 3: Creating JSch session")
                val jsch = JSch()

                // Set custom host key repository for database-backed verification
                Logger.d("SSHConnection", "STEP 4: Setting up host key verifier")
                jsch.hostKeyRepository = hostKeyVerifier

                // Configure host key changed callback
                hostKeyVerifier.setHostKeyChangedCallback { info ->
                    Logger.w("SSHConnection", "⚠️ Host key CHANGED verification triggered for ${info.hostname}")
                    val action = hostKeyChangedCallback?.invoke(info) ?: HostKeyAction.REJECT_CONNECTION
                    lastHostKeyDecision = action
                    action
                }

                // Configure new host key callback
                hostKeyVerifier.setNewHostKeyCallback { info ->
                    Logger.i("SSHConnection", "🆕 New host key verification triggered for ${info.hostname}")
                    val action = newHostKeyCallback?.invoke(info) ?: HostKeyAction.REJECT_CONNECTION
                    lastHostKeyDecision = action
                    action
                }

                // Setup jump host if configured
                Logger.d("SSHConnection", "STEP 5: Checking jump host configuration")
                val jumpHostPort = setupJumpHost(jsch)

                // Create main session - connect through jump host if configured
                Logger.d("SSHConnection", "STEP 6: Creating SSH session")
                val newSession = if (jumpHostPort != null) {
                    Logger.i("SSHConnection", "Connecting to target through jump host on localhost:$jumpHostPort as $effectiveUsername")
                    jsch.getSession(effectiveUsername, "localhost", jumpHostPort)
                } else {
                    Logger.i("SSHConnection", "Direct connection to ${profile.host}:${profile.port} as $effectiveUsername")
                    jsch.getSession(effectiveUsername, profile.host, profile.port)
                }

                // Setup HTTP/SOCKS proxy if configured
                Logger.d("SSHConnection", "STEP 7: Checking proxy configuration")
                setupHttpSocksProxy(newSession)

                // Configure session
                Logger.d("SSHConnection", "STEP 8: Configuring SSH session")
                configureSession(newSession)

                // Set timeout
                newSession.timeout = profile.connectTimeout * 1000
                Logger.d("SSHConnection", "Connection timeout set to ${profile.connectTimeout} seconds")

                // Wave 1.2 — env vars are applied at channel open (JSch's
                // setEnv lives on Channel, not Session). See openShellChannel().

                // Setup UserInfo for host key verification prompts
                Logger.d("SSHConnection", "STEP 8.5: Setting up UserInfo for host key prompts")
                setupUserInfo(newSession)

                // Setup authentication BEFORE connecting
                Logger.i("SSHConnection", "STEP 9: Setting up authentication")
                _connectionState.value = ConnectionState.AUTHENTICATING
                notifyListeners { onAuthenticating(id) }

                setupAuthentication(jsch, newSession)

                // Connect (this performs both connection AND authentication in one step).
                //
                // Issue #40: the very first connect() to a fresh host occasionally
                // fails with `java.io.IOException: End of IO Stream Read` thrown
                // from inside JSch's Session.connect(). The SSH server log shows
                // the password was accepted; the failure is on the JSch side
                // post-auth, before the session is fully marshalled. A second
                // attempt always succeeds because the host key is now in our
                // store so the kex phase finishes without the user dialog.
                //
                // Until the underlying race is rooted out, retry once silently
                // (after a short back-off) and only surface the error if the
                // second try also fails. Keeps the UX sane on first connect.
                Logger.i("SSHConnection", "STEP 10: Calling session.connect()")
                val activeSession = connectWithSilentRetry(jsch, newSession, jumpHostPort, effectiveUsername)
                session = activeSession

                Logger.i("SSHConnection", "Successfully connected and authenticated to ${profile.host}")
                
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                notifyListeners { onConnected(id) }
                
                Logger.i("SSHConnection", "Connection complete to ${profile.host}")
                
            } catch (e: Exception) {
                Logger.e("SSHConnection", "Connection failed at some step", e)
                handleConnectionError(e)
                return@launch
            }
        }
        
        connectJob?.join()
        return@withContext _connectionState.value == ConnectionState.CONNECTED
    }

    /**
     * Wrap [Session.connect] with a single silent retry on transient
     * post-auth IO errors (Issue #40). Returns the connected Session.
     *
     * The first attempt's `firstSession` is fully configured by the caller
     * (`configureSession`, `setupAuthentication`, `setupUserInfo`, etc.). The
     * retry creates a fresh session with the same configuration since JSch
     * sessions are not reusable after a failed `connect()`.
     */
    private suspend fun connectWithSilentRetry(
        jsch: JSch,
        firstSession: Session,
        jumpHostPort: Int?,
        effectiveUsername: String
    ): Session {
        try {
            firstSession.connect()
            return firstSession
        } catch (e: java.io.IOException) {
            val msg = e.message.orEmpty()
            val isTransient = msg.contains("End of IO Stream Read", ignoreCase = true) ||
                              msg.contains("connection is closed by foreign host", ignoreCase = true)
            if (!isTransient) throw e
            Logger.w(
                "SSHConnection",
                "session.connect() hit transient IO error '$msg' — retrying once silently"
            )
        }

        // Brief back-off so any half-open server-side state can settle.
        try { Thread.sleep(500) } catch (_: InterruptedException) {}

        val retrySession = if (jumpHostPort != null) {
            jsch.getSession(effectiveUsername, "localhost", jumpHostPort)
        } else {
            jsch.getSession(effectiveUsername, profile.host, profile.port)
        }
        setupHttpSocksProxy(retrySession)
        configureSession(retrySession)
        retrySession.timeout = profile.connectTimeout * 1000
        setupUserInfo(retrySession)
        setupAuthentication(jsch, retrySession)
        retrySession.connect()
        Logger.i("SSHConnection", "Silent retry succeeded for ${profile.host}")
        return retrySession
    }

    /**
     * Wave 1.2 — apply per-host env vars from profile.envVars (multi-line
     * "KEY=value") to a channel. Comment lines beginning with `#` and blank
     * lines are skipped. Quoted values are unquoted. Invalid lines are
     * logged and skipped — never crash the connect.
     *
     * JSch puts `setEnv` on Channel (not Session). The server must list
     * each KEY in `AcceptEnv` in sshd_config or it's silently dropped.
     */
    private fun applyEnvVarsTo(channel: com.jcraft.jsch.Channel) {
        val raw = profile.envVars?.takeIf { it.isNotBlank() } ?: return
        var applied = 0
        raw.lineSequence().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val eq = trimmed.indexOf('=')
            if (eq <= 0) {
                Logger.w("SSHConnection", "envVars line ${idx + 1}: skipping malformed '$trimmed'")
                return@forEachIndexed
            }
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1)
            }
            try {
                // JSch ChannelShell.setEnv signature accepts String/String
                val m = channel.javaClass.getMethod("setEnv", String::class.java, String::class.java)
                m.invoke(channel, key, value)
                applied++
            } catch (e: Exception) {
                Logger.w("SSHConnection", "envVars setEnv($key) failed: ${e.message}")
            }
        }
        if (applied > 0) {
            Logger.i("SSHConnection", "Applied $applied env var(s) to ${profile.host}")
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
            override fun getPassword(): String? {
                Logger.d("SSHConnection", "UserInfo.getPassword() called, returning cached password: ${cachedPassword != null}")
                return cachedPassword
            }

            override fun promptYesNo(message: String): Boolean {
                Logger.i("SSHConnection", "UserInfo.promptYesNo called: $message")

                // This is called by JSch for host key verification when StrictHostKeyChecking="ask"
                // Our HostKeyRepository.check() should handle this, but JSch may still call this
                // for certain edge cases

                // Check if this is a host key prompt
                if (message.contains("authenticity", ignoreCase = true) ||
                    message.contains("fingerprint", ignoreCase = true) ||
                    message.contains("RSA key", ignoreCase = true) ||
                    message.contains("ECDSA key", ignoreCase = true) ||
                    message.contains("ED25519", ignoreCase = true)) {

                    // Issues #33 / #34: HostKeyRepository.check() already showed
                    // the proper dialog (with the real key type and SHA-256
                    // fingerprint) and captured the user's decision in
                    // lastHostKeyDecision. JSch then asks us to confirm via
                    // promptYesNo for any check() result of NOT_INCLUDED. If we
                    // also fired a callback here we would show a SECOND dialog
                    // — and worse, that one stuffs JSch's raw "authenticity..."
                    // text into the fingerprint field with `keyType = unknown`,
                    // which is exactly what the user reported as #34.
                    //
                    // Instead, silently honour the decision check() already
                    // captured. If check() never ran (e.g. some unusual JSch
                    // path), default to REJECT for safety.
                    val decision = lastHostKeyDecision
                    Logger.i(
                        "SSHConnection",
                        "Host key prompt suppressed — using check() decision: $decision"
                    )
                    return decision == HostKeyAction.ACCEPT_NEW_KEY ||
                           decision == HostKeyAction.ACCEPT_ONCE
                }

                // For other prompts, accept if they seem like continuation prompts
                val shouldAccept = message.contains("continue connecting", ignoreCase = true)
                Logger.d("SSHConnection", "Non-host-key prompt, accepting: $shouldAccept")
                return shouldAccept
            }

            override fun getPassphrase(): String? {
                // Return passphrase for encrypted SSH keys if we have one stored
                Logger.d("SSHConnection", "UserInfo.getPassphrase() called, hasPassphrase=${cachedPassphrase != null}")
                return cachedPassphrase
            }

            override fun promptPassphrase(message: String): Boolean {
                Logger.d("SSHConnection", "UserInfo.promptPassphrase called: $message, hasPassphrase=${cachedPassphrase != null}")
                return cachedPassphrase != null
            }

            override fun promptPassword(message: String): Boolean {
                Logger.d("SSHConnection", "UserInfo.promptPassword called: $message, hasPassword=${cachedPassword != null}")
                // Return true if we have a password to provide
                return cachedPassword != null
            }

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
     * Authentication priority:
     * 1. If identity is explicitly linked (identityId set) → use identity's credentials
     * 2. Else if identity exists matching username → use that identity (SSH config import convenience)
     * 3. Else if connection has its own credentials → use those
     * 4. Else let SSH negotiate (publickey agent, keyboard-interactive, etc.)
     */
    private suspend fun setupAuthentication(jsch: JSch, session: Session) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication

        // Use already-resolved identity from connect()
        val linkedIdentity = resolvedIdentity
        Logger.d("SSHConnection", "setupAuthentication: linkedIdentity=${linkedIdentity?.name}, identity.keyId=${linkedIdentity?.keyId}, profile.keyId=${profile.keyId}")

        // Gather available credentials — identity overrides connection-level values
        val effectiveKeyId: String? = linkedIdentity?.keyId ?: profile.keyId
        Logger.d("SSHConnection", "setupAuthentication: effectiveKeyId=$effectiveKeyId")

        // Identity password: try SecurePasswordManager first, fall back to legacy plaintext in DB
        val identityPassword: String? = if (linkedIdentity != null) {
            app?.securePasswordManager?.retrievePassword("identity_${linkedIdentity.id}")
                ?: linkedIdentity.password  // legacy plaintext fallback
        } else null
        val effectivePassword: String? = identityPassword ?: getPasswordForAuthentication()

        // ALWAYS cache password first so it's available for fallback if key auth fails
        if (effectivePassword != null) {
            cachedPassword = effectivePassword
            session.setPassword(effectivePassword)
            Logger.d("SSHConnection", "Auth: Password cached for fallback (length=${effectivePassword.length})")
        }

        // Priority 1: SSH key (if available and retrievable)
        if (effectiveKeyId != null) {
            Logger.i("SSHConnection", "Auth: Attempting SSH key authentication with keyId=$effectiveKeyId")

            // Try to retrieve passphrase if key is encrypted (non-blocking)
            cachedPassphrase = app?.securePasswordManager?.retrievePassword("key_passphrase_$effectiveKeyId")
            if (cachedPassphrase != null) {
                Logger.d("SSHConnection", "Auth: Retrieved passphrase for encrypted key")
            }

            val jschBytes = getJSchBytes(effectiveKeyId)
            if (jschBytes != null) {
                try {
                    Logger.d("SSHConnection", "Auth: JSch bytes retrieved, size=${jschBytes.size} bytes")

                    // Wave 2.2 — if an OpenSSH user certificate is attached to this
                    // key, use the byte-array variant of addIdentity so we can pass
                    // the cert as the public-key portion. Server validates against
                    // the CA-signed cert instead of the bare key.
                    val storedKeyForCert = app?.database?.keyDao()?.getKeyById(effectiveKeyId)
                    val cert = storedKeyForCert?.certificate?.takeIf { it.isNotBlank() }
                    if (cert != null) {
                        jsch.addIdentity(
                            "tabssh-$effectiveKeyId",
                            jschBytes,
                            cert.toByteArray(Charsets.US_ASCII),
                            cachedPassphrase?.toByteArray()
                        )
                        Logger.i("SSHConnection", "Auth: SSH key + certificate added to JSch (keyId=$effectiveKeyId, cert=${cert.length} bytes)")
                        return@withContext
                    }

                    // No cert — preserve existing temp-file path (byte-array variant has Linux quirks).
                    val tempKeyFile = java.io.File(context.cacheDir, "temp_key_$effectiveKeyId")
                    try {
                        tempKeyFile.writeBytes(jschBytes)
                        Logger.d("SSHConnection", "Auth: Wrote key to temp file: ${tempKeyFile.absolutePath}")

                        jsch.addIdentity(tempKeyFile.absolutePath, cachedPassphrase?.toByteArray())
                        Logger.i("SSHConnection", "Auth: SSH key added to JSch from file (keyId=$effectiveKeyId)")
                        return@withContext
                    } finally {
                        tempKeyFile.delete()
                        Logger.d("SSHConnection", "Auth: Temp key file deleted")
                    }
                } catch (e: Exception) {
                    Logger.e("SSHConnection", "Auth: Failed to add SSH key to JSch", e)
                    // Fall through to password auth
                }
            } else {
                Logger.w("SSHConnection", "Auth: JSch bytes null for keyId=$effectiveKeyId - key may not be stored properly")
            }
            Logger.w("SSHConnection", "Auth: SSH key failed, falling back to password")
        } else {
            Logger.d("SSHConnection", "Auth: No SSH key ID configured")
        }

        // Priority 2: Password (if stored)
        if (effectivePassword != null) {
            cachedPassword = effectivePassword  // Cache for UserInfo callbacks
            session.setPassword(effectivePassword)
            Logger.i("SSHConnection", "Auth: password set on session")
            return@withContext
        }

        // Priority 3: Keyboard-interactive fallback
        Logger.i("SSHConnection", "Auth: no credentials found, using keyboard-interactive")
        // JSch will negotiate; host-key UserInfo is already set on the session — don't replace it here
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
            
            Logger.d("SSHConnection", "No stored password found for ${profile.id}")
            null
            
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
     * Retrieve JSch-native bytes for a keyId.
     * 1. Try the dedicated jsch_bytes_ store (set at import time for all formats).
     * 2. Fallback: reconstruct from stored PKCS#8 DER + convert via KeyStorage.toJSchKeyBytes().
     *    This handles keys imported before the jsch_bytes store was introduced.
     */
    private suspend fun getJSchBytes(keyId: String): ByteArray? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
        if (app == null) {
            Logger.e("SSHConnection", "getJSchBytes: Application context is null")
            return@withContext null
        }

        Logger.d("SSHConnection", "getJSchBytes: Looking up key $keyId")

        // Preferred path — JSch-native bytes stored at import time
        val stored = app.keyStorage.retrieveJSchBytes(keyId)
        if (stored != null) {
            Logger.d("SSHConnection", "getJSchBytes: Found cached JSch bytes (${stored.size} bytes)")
            return@withContext stored
        }

        Logger.d("SSHConnection", "getJSchBytes: No cached JSch bytes, attempting fallback conversion")

        // Fallback for legacy stored keys — convert PKCS#8 DER on-the-fly
        return@withContext try {
            val privateKey = app.keyStorage.retrievePrivateKey(keyId)
            if (privateKey == null) {
                Logger.e("SSHConnection", "getJSchBytes: Private key not found for $keyId")
                return@withContext null
            }
            Logger.d("SSHConnection", "getJSchBytes: Retrieved private key (algorithm=${privateKey.algorithm})")

            val storedKey = app.database.keyDao().getKeyById(keyId)
            if (storedKey == null) {
                Logger.e("SSHConnection", "getJSchBytes: StoredKey metadata not found for $keyId")
                return@withContext null
            }
            Logger.d("SSHConnection", "getJSchBytes: Key metadata - type=${storedKey.keyType}, name=${storedKey.name}")

            val keyType = io.github.tabssh.crypto.keys.KeyType.valueOf(storedKey.keyType)
            // Public key is derived from the private key for the fallback
            Logger.d("SSHConnection", "getJSchBytes: Deriving public key from private key")
            val publicKey = app.keyStorage.getPublicKeyFromPrivate(privateKey)
            Logger.d("SSHConnection", "getJSchBytes: Public key derived successfully")

            val jschBytes = app.keyStorage.toJSchKeyBytes(privateKey, publicKey, keyType)
            Logger.d("SSHConnection", "getJSchBytes: Converted to JSch format (${jschBytes.size} bytes)")

            // Cache for next time
            app.keyStorage.storeJSchBytes(keyId, jschBytes)
            Logger.d("SSHConnection", "getJSchBytes: Cached JSch bytes for future use")

            jschBytes
        } catch (e: Exception) {
            Logger.e("SSHConnection", "getJSchBytes: Failed to build JSch bytes for $keyId", e)
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
            // Wave 1.2: per-host env vars before channel.connect()
            applyEnvVarsTo(channel)
            // Wave 1.5: SSH agent forwarding (per-host opt-in)
            if (profile.agentForwarding) {
                try {
                    channel.setAgentForwarding(true)
                    Logger.i("SSHConnection", "Enabled SSH agent forwarding for ${profile.host}")
                } catch (e: Exception) {
                    Logger.w("SSHConnection", "setAgentForwarding failed: ${e.message}")
                }
            }
            // Wave 2.X — real X11 channel forwarding via JSch.
            // We DO NOT implement an X server in-app; we route the X11 channel
            // to whatever's listening on localhost:6000 (XServer-XSDL is a
            // good Android option). With this enabled, sshd on the remote
            // sets DISPLAY=localhost:N.0 and JSch tunnels it back.
            if (profile.x11Forwarding) {
                try {
                    currentSession.setX11Host("localhost")
                    currentSession.setX11Port(6000)
                    channel.setXForwarding(true)
                    Logger.i("SSHConnection", "Enabled X11 forwarding for ${profile.host} (target: localhost:6000)")
                } catch (e: Exception) {
                    Logger.w("SSHConnection", "X11 forwarding setup failed: ${e.message}")
                }
            }
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
    /**
     * Push a new PTY size to the remote shell. Called whenever the local
     * terminal view resizes (rotation, IME show/hide, font-size change).
     * Without this, the remote sees the initial 80×24 forever and lines
     * wrap at column 80 even when the local view is ~55 columns wide —
     * which is what "terminal reports 80 cols but actual is closer to 55"
     * looked like in the wild.
     */
    fun resizePty(cols: Int, rows: Int) {
        try {
            shellChannel?.setPtySize(cols, rows, 0, 0)
            Logger.d("SSHConnection", "Pushed PTY size to remote: ${cols}x${rows}")
        } catch (e: Exception) {
            Logger.w("SSHConnection", "setPtySize failed: ${e.message}")
        }
    }

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