package io.github.tabssh.ssh.connection

import com.jcraft.jsch.*
import io.github.tabssh.network.NetworkAwareReconnector
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.forwarding.X11NoServerException
import io.github.tabssh.ssh.forwarding.X11Proxy
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val profile: ConnectionProfile,
    private val scope: CoroutineScope,
    internal val context: android.content.Context
) {
    /**
     * True when this connection was created by [SSHSessionManager.connectForMonitoring]
     * rather than [SSHSessionManager.connectToServer].
     *
     * Monitoring connections are invisible to the session notification layer:
     * [SSHConnectionService] skips [renderHostNotification] for any profile ID
     * whose live [SSHConnection] has this flag set.  The flag is cleared to false
     * when [connectToServer] promotes an existing monitoring connection to a full
     * terminal session so the notification appears at the right moment.
     */
    @Volatile var isMonitoringOnly: Boolean = false

    /**
     * Last terminal title parsed by Termux from the OSC 0/1/2 escape
     * sequences (the value most shells set on every prompt — e.g.
     * `user@host:cwd`). Set by [SSHTab] from its TermuxBridgeListener.
     * Read by the foreground service when rebuilding per-host
     * notification text.
     */
    @Volatile var terminalTitle: String? = null

    /**
     * Optional callback fired when host-level metadata that the
     * notification depends on (terminal title, …) changes after
     * connect. Wired by [SSHSessionManager] so the service-side
     * listener can rebuild the per-host notification without a
     * dedicated event type.
     */
    var metadataChangedCallback: (() -> Unit)? = null

    /**
     * Notify metadata-change listeners. Cheap, idempotent — the service
     * just re-renders the per-host notification using the latest
     * `terminalTitle`.
     */
    fun notifyMetadataChanged() {
        try { metadataChangedCallback?.invoke() } catch (_: Exception) {}
    }
    private var session: Session? = null
    // Issue #37 — May hold either a `ChannelShell` (the default — login
    // shell) or a `ChannelExec` (when `profile.remoteCommand` is set, for
    // hosts like shell.sourceforge.net that need an explicit `create`).
    // We'd love to type this as `ChannelSession` (which is the JSch parent
    // of both) but that class is **package-private** in JSch — referenced
    // from outside the JSch package it fails to resolve. So we use the
    // public `Channel` base type and dispatch on the concrete subclass at
    // call sites that need session-only methods (see `resizeActiveChannelPty`).
    private var shellChannel: Channel? = null
    // Last observed shell channel exit-status (0 = clean `exit`, -1 = no
    // exit-status message, e.g. abrupt drop). Captured at channel close
    // because `shellChannel` is nulled before the DISCONNECTED state
    // observer reads it — without this snapshot the reconnect-prompt
    // gate always saw -1 and prompted even on a clean `exit`.
    @Volatile private var lastShellExitStatus: Int = -1
    /** Set by [markIntentionalClose] to suppress the reconnect dialog. */
    @Volatile private var intentionalClose: Boolean = false
    // Issue #163 — track every channel we've opened on this Session so that
    // (a) multiple tabs can each hold their own ChannelShell against one
    // Session (real multiplexing — opening the same profile twice no longer
    // returns the same stream pair), and (b) `disconnect()` can tear them
    // all down together. The legacy `shellChannel` field above is now the
    // most-recently-opened channel; legacy callers that don't know about
    // multi-tab (e.g. SFTP path, TabTerminalActivity's getOutputStream
    // shortcut) get the active tab's stream as long as the active tab was
    // the last to call openShellChannel — which is true in practice because
    // the user always activates the tab they just opened.
    private val openChannels: MutableSet<Channel> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    private var sftpChannel: ChannelSftp? = null
    private var jumpHostSession: Session? = null
    private var jumpHostLocalPort: Int = 0
    // X11 forwarding proxy — non-null only while x11Forwarding is active
    private var x11Proxy: X11Proxy? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _detailedError = MutableStateFlow<SSHConnectionErrorInfo?>(null)
    val detailedError: StateFlow<SSHConnectionErrorInfo?> = _detailedError.asStateFlow()

    private val _bytesTransferred = MutableStateFlow(0L)
    val bytesTransferred: StateFlow<Long> = _bytesTransferred.asStateFlow()

    // Non-fatal advisory messages (e.g. X11 server not found). Observers can
    // show a Snackbar or Toast without treating the event as a connection failure.
    private val _warnings = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val warnings: SharedFlow<String> = _warnings.asSharedFlow()

    private var connectJob: Job? = null

    // Auto-reconnect should only fire after a session has been *established*
    // and then dropped — not on initial-connect failures (host unreachable,
    // wrong port, …). Without this gate the reconnect loop runs in the
    // background after the activity has bailed.
    private var hadSuccessfulConnect = false
    private var sessionStartMs: Long = 0   // wall-clock ms at last successful connect

    // Network-aware reconnector — pauses when the device is offline, wakes
    // immediately when the link returns, falls back to a 5-minute poll.
    // null until the first successful connect (same guard as hadSuccessfulConnect).
    private var reconnector: NetworkAwareReconnector? = null

    // CopyOnWriteArrayList: addConnectionListener may be called from UI
    // while notifyListeners iterates from the SSH connect/disconnect
    // coroutines (Dispatchers.IO). A plain ArrayList would risk
    // ConcurrentModificationException and lost updates.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ConnectionListener>()

    // Host key verification
    private val hostKeyVerifier = HostKeyVerifier(context)
    var hostKeyChangedCallback: ((HostKeyChangedInfo) -> HostKeyAction)? = null
    var newHostKeyCallback: ((NewHostKeyInfo) -> HostKeyAction)? = null

    // Last decision returned from the HostKeyRepository.check() callback path.
    // UserInfo.promptYesNo() consults this so it never fires a second dialog
    // for the same host (Issues #33 / #34).
    // @Volatile: written from the connect coroutine on Dispatchers.IO,
    // read from JSch's UserInfo callbacks that fire on JSch's internal
    // worker thread.
    @Volatile
    private var lastHostKeyDecision: HostKeyAction? = null

    // Cached resolved identity (loaded on connect).
    // @Volatile: written from the connect coroutine on Dispatchers.IO,
    // read from JSch UserInfo callbacks and from disconnect() which can
    // be called from either Main or IO.
    @Volatile
    private var resolvedIdentity: io.github.tabssh.storage.database.entities.Identity? = null

    // Cached password for UserInfo callbacks (set during setupAuthentication).
    // @Volatile: written on the connect coroutine (IO), read by JSch's
    // UserInfo callbacks on JSch's worker thread, cleared from disconnect()
    // and clearCachedCredentials() which can race with the JSch reads.
    @Volatile
    private var cachedPassword: String? = null

    // Cached passphrase for encrypted SSH keys. Same threading constraints
    // as cachedPassword.
    @Volatile
    private var cachedPassphrase: String? = null

    val id: String = profile.id
    val displayName: String = profile.getDisplayName()

    init {
        Logger.d("SSHConnection", "Created connection for ${profile.getDisplayName()}")
    }
    
    /**
     * Connect to the SSH server.
     *
     * This is safe to call both from user UI actions and from the
     * [NetworkAwareReconnector]. When called from user code directly,
     * [NetworkAwareReconnector.onUserInitiated] is called first to cancel
     * any pending backoff timer so the user is never blocked.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // If this is a direct user-driven call (not from the reconnector),
        // cancel any pending retry timer so we connect immediately.
        reconnector?.onUserInitiated()

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

                // Resolve linked identity if set (for effective username/credentials).
                // P1 fix: use safe-call instead of `profile.identityId!!`. Even
                // though we just null-checked on the previous line, the field
                // is a `var`/Room-loaded value and a stale flow emission could
                // (in theory) deliver a profile whose identityId became null
                // between the guard and the bang — easier to just read the
                // field once into a local val and route through that.
                val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
                Logger.d("SSHConnection", "Profile identityId: ${profile.identityId}")
                val identityId = profile.identityId
                resolvedIdentity = if (identityId != null) {
                    try {
                        val identity = app?.database?.identityDao()?.getIdentityById(identityId)
                        if (identity != null) {
                            Logger.i("SSHConnection", "Using identity '${identity.name}' (keyId=${identity.keyId}, authType=${identity.authType})")
                        } else {
                            Logger.w("SSHConnection", "Identity not found in DB for id: $identityId")
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
                Logger.logHostEvent(profile.id, effectiveUsername, profile.host, profile.port, "INFO", "Connecting")

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
                    val tunnelSession = jsch.getSession(effectiveUsername, "localhost", jumpHostPort)
                    // Store the host key under the real target hostname (not localhost:ephemeralPort)
                    // so "Accept & save" persists across reconnects that use different tunnel ports.
                    tunnelSession.setHostKeyAlias(profile.host)
                    tunnelSession
                } else {
                    val resolved = resolveHostForIpMode(profile.host, profile.ipMode)
                    Logger.i("SSHConnection", "Direct connection to $resolved (host=${profile.host}, ipMode=${profile.ipMode}):${profile.port} as $effectiveUsername")
                    jsch.getSession(effectiveUsername, resolved, profile.port)
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
                val authMethod = resolvedIdentity?.authType ?: profile.authType
                Logger.logHostEvent(profile.id, effectiveUsername, profile.host, profile.port, "INFO", "Authenticating ($authMethod)")
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
                sessionStartMs = System.currentTimeMillis()
                Logger.logHostEvent(profile.id, effectiveUsername, profile.host, profile.port, "INFO", "Session started")

                // Issue #29 — apply port forwards parsed from `~/.ssh/config`
                // and stored in `advancedSettings` JSON. Without this, imported
                // configs with `LocalForward` / `RemoteForward` / `DynamicForward`
                // round-tripped fine but did nothing at connect time.
                applyAdvancedSettings(activeSession)

                _connectionState.value = ConnectionState.CONNECTED
                hadSuccessfulConnect = true

                // First successful connect — create the reconnector and start
                // its network observer + poll loop. Subsequent successful
                // reconnects call onConnectionRestored() to reset the backoff.
                if (reconnector == null) {
                    val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
                    if (app != null) {
                        reconnector = NetworkAwareReconnector(
                            networkDetector = app.networkDetector,
                            scope = scope,
                            tag = "SSHConnection/${profile.host}:${profile.port}",
                            reconnect = { connect() },
                        ).also { it.start() }
                    }
                } else {
                    reconnector?.onConnectionRestored()
                }

                notifyListeners { onConnected(id) }

                // Audit logging — best-effort, never break the SSH happy path.
                try {
                    val method = if (profile.keyId != null) "publickey" else "password"
                    app?.auditLogManager?.logAuthSuccess(profile, id, method)
                } catch (e: Exception) {
                    Logger.w("SSHConnection", "Audit log (authSuccess) failed: ${e.message}")
                }

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
        delay(500)

        val retrySession = if (jumpHostPort != null) {
            jsch.getSession(effectiveUsername, "localhost", jumpHostPort).also {
                it.setHostKeyAlias(profile.host)
            }
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
     * Issue #6 — pre-resolve [host] to a single literal address matching
     * the requested [mode] ("ipv4" / "ipv6"). On "auto" or DNS failure
     * the original host is returned verbatim. If no address of the
     * requested family is available we fall back to the other family
     * and surface a toast so the user knows ipMode was overridden.
     */
    private fun resolveHostForIpMode(host: String, mode: String): String {
        if (mode == "auto") return host
        val all = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            Logger.w("SSHConnection", "DNS lookup failed for $host (ipMode=$mode), passing through: ${e.message}")
            return host
        }
        val want4 = mode == "ipv4"
        all.firstOrNull { (it is java.net.Inet4Address) == want4 }
            ?.hostAddress?.let { return it }

        val fallback = all.firstOrNull()?.hostAddress ?: return host
        val msg = if (want4)
            "No IPv4 address found for $host — connecting via IPv6"
        else
            "No IPv6 address found for $host — connecting via IPv4"
        Logger.w("SSHConnection", msg)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        return fallback
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
        
        // Keep-alive — JSch uses Session.setServerAliveInterval (ms) +
        // setServerAliveCountMax. The OpenSSH-style "ServerAliveInterval"
        // config keys do nothing on JSch. Set on Session below after
        // setConfig so the values stick.
        
        // Preferred algorithms (secure defaults)
        config["PreferredAuthentications"] = "publickey,keyboard-interactive,password"
        config["cipher.s2c"] = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        config["cipher.c2s"] = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr"
        config["mac.s2c"] = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
        config["mac.c2s"] = "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha2-256,hmac-sha2-512"
        
        session.setConfig(config)

        // Keepalive — always on; interval uses per-host override when set,
        // otherwise falls back to the global preference (default 60 s).
        // Carrier NAT timeouts, cellular handoffs and Wi-Fi sleep all silently
        // kill idle TCP sockets; a tiny NOOP every N seconds is mandatory.
        val prefs = (context.applicationContext as? io.github.tabssh.TabSSHApplication)
            ?.preferencesManager
        val aliveIntervalMs = profile.serverAliveInterval
            ?.let { it.coerceAtLeast(5) * 1_000L }
            ?: prefs?.getServerAliveIntervalMs()
            ?: 60_000L
        session.serverAliveInterval = aliveIntervalMs.toInt()
        session.serverAliveCountMax = 3

        Logger.d("SSHConnection", "Session configured: compression=${profile.compression}, keepalive=${aliveIntervalMs / 1000}s")
    }

    /**
     * Issue #29 — apply post-connect directives from the per-host
     * `advancedSettings` JSON blob produced by `SSHConfigParser`. Today this
     * covers the three forward types — they round-tripped through import/
     * export but never actually fired at connect time.
     *
     * Format mirrors what the parser stores: each forward is the raw value
     * from the user's `~/.ssh/config`, e.g. `"8080 example.com:80"` for a
     * LocalForward or `"1080"` for a DynamicForward.
     */
    private fun applyAdvancedSettings(session: Session) {
        val raw = profile.advancedSettings?.takeIf { it.isNotBlank() } ?: return
        val json = try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            Logger.w("SSHConnection", "advancedSettings is not valid JSON; skipping (${e.message})")
            return
        }

        applyForwardArray(json, "localForwards") { spec ->
            val (lp, rh, rp) = parseForwardSpec(spec) ?: return@applyForwardArray
            // Force bind to 127.0.0.1 — never expose forwarded ports on the
            // device's LAN interfaces. See PortForwardingManager for rationale.
            val assigned = session.setPortForwardingL("127.0.0.1", lp, rh, rp)
            Logger.i("SSHConnection", "advancedSettings: LocalForward 127.0.0.1:$lp -> $rh:$rp (assigned=$assigned)")
        }
        applyForwardArray(json, "remoteForwards") { spec ->
            val (rp, lh, lp) = parseForwardSpec(spec) ?: return@applyForwardArray
            // Server-side bind defaults to "localhost"; honour the remote
            // sshd's GatewayPorts policy rather than forcing wildcard.
            session.setPortForwardingR(null, rp, lh, lp)
            Logger.i("SSHConnection", "advancedSettings: RemoteForward $rp -> $lh:$lp")
        }
        applyForwardArray(json, "dynamicForwards") { spec ->
            // Accept bare "1080", IPv4 "127.0.0.1:1080", and IPv6 "[::1]:1080".
            val trimmed = spec.trim()
            val (bindAddr, port) = parseDynamicForwardSpec(trimmed) ?: run {
                Logger.w("SSHConnection", "advancedSettings: bad DynamicForward spec '$spec'")
                return@applyForwardArray
            }
            // SOCKS proxy — honour the caller's bind address so IPv6-only
            // environments work correctly with "[::1]:port".
            session.setPortForwardingL("$bindAddr:$port")
            Logger.i("SSHConnection", "advancedSettings: DynamicForward (SOCKS) on $bindAddr:$port")
        }
    }

    private inline fun applyForwardArray(
        json: org.json.JSONObject,
        key: String,
        body: (String) -> Unit
    ) {
        val arr = json.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) {
            val spec = arr.optString(i)?.takeIf { it.isNotBlank() } ?: continue
            try {
                body(spec)
            } catch (e: Exception) {
                Logger.w("SSHConnection", "advancedSettings: $key entry '$spec' failed: ${e.message}")
            }
        }
    }

    /**
     * Parse `[bindAddr:]port` for DynamicForward. Returns (bindAddr, port).
     * Handles bare port, IPv4 address:port, and IPv6 [::1]:port forms.
     * Default bind address is 127.0.0.1 (loopback only).
     */
    private fun parseDynamicForwardSpec(spec: String): Pair<String, Int>? {
        // IPv6 bracketed form: "[::1]:1080"
        if (spec.startsWith("[")) {
            val closeBracket = spec.indexOf(']')
            if (closeBracket < 0) return null
            val addr = spec.substring(1, closeBracket)
            val rest = spec.substring(closeBracket + 1)
            val port = rest.removePrefix(":").toIntOrNull() ?: return null
            return Pair(addr, port)
        }
        // Bare port: "1080"
        val bare = spec.toIntOrNull()
        if (bare != null) return Pair("127.0.0.1", bare)
        // IPv4 with bind address: "127.0.0.1:1080"
        val lastColon = spec.lastIndexOf(':')
        if (lastColon > 0) {
            val port = spec.substring(lastColon + 1).toIntOrNull() ?: return null
            val addr = spec.substring(0, lastColon)
            return Pair(addr, port)
        }
        return null
    }

    /**
     * Parse OpenSSH forward spec: `"<localPart> <remotePart>"` where each
     * part is either `port`, `host:port`, or `[ipv6]:port`. Returns
     * (localPort, remoteHost, remotePort). For LocalForward the local part
     * may be just a port and the remote part is `host:port`; for
     * RemoteForward the meaning is mirrored but the structure is the same.
     */
    private fun parseForwardSpec(spec: String): Triple<Int, String, Int>? {
        val parts = spec.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val left  = parts[0]
        val right = parts[1]

        // Local side: bare port or [bindAddr:]port (we only care about the port).
        val localPort = parseDynamicForwardSpec(left)?.second ?: return null

        // Remote side: host:port or [ipv6host]:port.
        val (rhost, rport) = if (right.startsWith("[")) {
            val close = right.indexOf(']')
            if (close < 0) return null
            val h = right.substring(1, close)
            val p = right.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            Pair(h, p)
        } else {
            val lastColon = right.lastIndexOf(':')
            if (lastColon <= 0) return null
            Pair(right.substring(0, lastColon), right.substring(lastColon + 1).toIntOrNull() ?: return null)
        }
        return Triple(localPort, rhost, rport)
    }

    private companion object {
        // X11 target on the Android device. XServer-XSDL and Termux:X11 both
        // default to display :0 = TCP localhost:6000. If we ever expose this
        // per-host, it lands as columns on ConnectionProfile + a UI field.
        const val X11_DEFAULT_HOST = "localhost"
        const val X11_DEFAULT_PORT = 6000
    }

    /**
     * Apply per-channel forwarding flags before `channel.connect()`. Both
     * agent-forwarding (Wave 1.5) and X11-forwarding (Wave 2.X) need:
     *   1. The toggle to be on in `profile`.
     *   2. The session-level X11 host/port set BEFORE the channel hands its
     *      capability blob to the server.
     *   3. The channel-level `setXForwarding(true)` / `setAgentForwarding(true)`
     *      call BEFORE `channel.connect()` (JSch sends the cap during the
     *      channel-open exchange).
     *
     * X11 target is hardcoded to `localhost:6000` (matches XServer-XSDL and
     * Termux:X11 defaults on Android). If a setup step throws, we surface a
     * one-line error to the listener so the user notices instead of silently
     * losing the feature.
     *
     * `setAgentForwarding` / `setXForwarding` are defined on JSch's
     * `ChannelSession` parent — but that class is package-private, so we
     * dispatch on the concrete subclass here too.
     */
    private fun applyForwardingFlags(session: Session, channel: Channel) {
        val prefs = (context.applicationContext as? io.github.tabssh.TabSSHApplication)
            ?.preferencesManager
        // Per-host boolean fields default to false. When false, fall back to the
        // global preference so the user's default applies to all connections that
        // haven't been explicitly overridden in the per-host editor.
        val effectiveAgentForwarding = profile.agentForwarding
            || (prefs?.isAgentForwardingDefault() == true)
        val effectiveX11Forwarding = profile.x11Forwarding
            || (prefs?.isX11ForwardingDefault() == true)

        if (effectiveAgentForwarding) {
            try {
                when (channel) {
                    is ChannelShell -> channel.setAgentForwarding(true)
                    is ChannelExec -> channel.setAgentForwarding(true)
                }
                Logger.i("SSHConnection", "Enabled SSH agent forwarding for ${profile.host}")
            } catch (e: Exception) {
                Logger.w("SSHConnection", "SSH agent forwarding setup failed: ${e.message}")
                notifyListeners { onError(id, Exception("SSH agent forwarding setup failed: ${e.message}", e)) }
            }
        }
        if (effectiveX11Forwarding) {
            try {
                // Start a local proxy that accepts JSch's X11 connections and
                // relays them to Termux:X11 (Unix socket) or XServer XSDL (TCP).
                // Using port 0 here so the OS assigns a free port — avoids any
                // conflict with a locally running X server on the default :6000.
                val proxy = X11Proxy(onNoServer = {
                    // Non-fatal: session stays alive; user just won't see X windows.
                    // Emit to warnings flow so TabTerminalActivity can show a Snackbar.
                    _warnings.tryEmit(X11NoServerException().message!!)
                })
                proxy.start()
                x11Proxy = proxy
                session.setX11Host(X11_DEFAULT_HOST)
                session.setX11Port(proxy.port)
                when (channel) {
                    is ChannelShell -> channel.setXForwarding(true)
                    is ChannelExec -> channel.setXForwarding(true)
                }
                Logger.i("SSHConnection", "Enabled X11 forwarding for ${profile.host} (proxy port: ${proxy.port})")
            } catch (e: Exception) {
                Logger.w("SSHConnection", "X11 forwarding setup failed: ${e.message}")
                x11Proxy?.stop()
                x11Proxy = null
                notifyListeners {
                    onError(id, Exception("X11 forwarding setup failed: ${e.message}", e))
                }
            }
        }
    }

    /**
     * Setup UserInfo for handling host key verification prompts
     * This is required for JSch to work with StrictHostKeyChecking="ask"
     */
    private fun setupUserInfo(session: Session) {
        session.setUserInfo(object : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
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

            // UIKeyboardInteractive — required for JSch to complete challenge-response auth.
            // Without this implementation JSch cannot drive the keyboard-interactive exchange
            // even when the server offers it. For password-style challenges we return the
            // cached password; for banner-only exchanges (empty prompt array) we return an
            // empty array so the handshake can complete without user interaction.
            override fun promptKeyboardInteractive(
                destination: String,
                name: String,
                instruction: String,
                prompt: Array<String>,
                echo: BooleanArray
            ): Array<String>? {
                Logger.d(
                    "SSHConnection",
                    "UIKeyboardInteractive: destination=$destination name=$name " +
                        "prompts=${prompt.toList()} echo=${echo.toList()}"
                )
                if (prompt.isEmpty()) {
                    Logger.d("SSHConnection", "Keyboard-interactive: banner/info only, returning empty array")
                    return arrayOf()
                }
                val pw = cachedPassword
                if (pw != null) {
                    Logger.d("SSHConnection", "Keyboard-interactive: returning cached password for ${prompt.size} prompt(s)")
                    return Array(prompt.size) { pw }
                }
                Logger.w("SSHConnection", "Keyboard-interactive: no cached credential, cancelling challenge")
                return null
            }
        })

        Logger.d("SSHConnection", "UserInfo configured for host key prompts")
    }

    /**
     * Execute port knock sequence if enabled
     */
    private suspend fun executePortKnockIfEnabled() {
        // Check global default setting
        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication ?: run {
            Logger.w("SSHConnection", "executePortKnockIfEnabled: applicationContext is not TabSSHApplication, skipping")
            return
        }
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
                        // Load jump host SSH key as OpenSSH PEM bytes.
                        // getJSchBytesWithFallback() returns the correct format for
                        // JSch's byte-array addIdentity(); retrievePrivateKey().encoded
                        // returns PKCS#8 DER which JSch rejects as "invalid private key".
                        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
                            ?: throw SSHException("applicationContext is not TabSSHApplication")
                        val jschBytes = app.keyStorage.getJSchBytesWithFallback(profile.proxyKeyId)
                        if (jschBytes != null) {
                            try {
                                jsch.addIdentity(
                                    profile.proxyKeyId,
                                    jschBytes,
                                    null, // public key (JSch can derive it)
                                    null  // passphrase — keys stored unencrypted in Keystore
                                )
                            } finally {
                                // Zero plaintext key bytes once JSch has parsed them.
                                jschBytes.fill(0)
                            }
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
            // Forward a random local port to the target host's SSH port.
            // Bind explicitly to 127.0.0.1 — the tunnel is only consumed by
            // the next JSch session on this device; exposing it on the LAN
            // would let nearby devices use this host as an SSH relay to the
            // target through the jump host without authenticating to us.
            val localPort = jumpSession.setPortForwardingL(
                "127.0.0.1",
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

        // Wave 1.5 runtime wiring — when agent forwarding is enabled we
        // populate JSch's identity repository up-front with every stored
        // key the user has unlocked. JSch services agent-forwarded sign
        // requests from the same identity list it uses for direct auth,
        // so addIdentity() is the whole runtime hookup. Done before the
        // per-connection key path so the connection's own key gets added
        // on top (and a second addIdentity for the same name is a no-op).
        val appForAgent = context.applicationContext as? io.github.tabssh.TabSSHApplication
        val effectiveAgentForwardingForAuth = profile.agentForwarding
            || (appForAgent?.preferencesManager?.isAgentForwardingDefault() == true)
        if (effectiveAgentForwardingForAuth && appForAgent != null) {
            populateAgentIdentities(jsch, appForAgent)
        }

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

        // Priority 1: SSH key (if available and retrievable, and not overridden to keyboard-interactive)
        // When the target explicitly requests keyboard-interactive auth, skip publickey even if an
        // identity key is configured — some servers drop keyboard-interactive from the offered-methods
        // list after a failed publickey attempt, leaving no viable auth path.
        if (effectiveKeyId != null && profile.authType != AuthType.KEYBOARD_INTERACTIVE.name) {
            Logger.i("SSHConnection", "Auth: Attempting SSH key authentication with keyId=$effectiveKeyId")

            // Try to retrieve passphrase if key is encrypted (non-blocking)
            cachedPassphrase = app?.securePasswordManager?.retrievePassword("key_passphrase_$effectiveKeyId")
            if (cachedPassphrase != null) {
                Logger.d("SSHConnection", "Auth: Retrieved passphrase for encrypted key")
            }

            val jschBytes = getJSchBytes(effectiveKeyId)
            if (jschBytes != null) {
                // Track passphrase ByteArray so we can scrub it on every exit path.
                var passphraseBytes: ByteArray? = null
                try {
                    Logger.d("SSHConnection", "Auth: JSch bytes retrieved, size=${jschBytes.size} bytes")
                    passphraseBytes = cachedPassphrase?.toByteArray()

                    // Wave 2.2 — if an OpenSSH user certificate is attached to this
                    // key, pass the cert PEM as the public-key portion so the server
                    // validates against the CA-signed cert instead of the bare key.
                    val storedKeyForCert = app?.database?.keyDao()?.getKeyById(effectiveKeyId)
                    val cert = storedKeyForCert?.certificate?.takeIf { it.isNotBlank() }
                    val pubKeyBytes: ByteArray? = cert?.toByteArray(Charsets.US_ASCII)

                    // Use the byte-array addIdentity variant for both the cert and no-cert
                    // paths. This eliminates the cacheDir temp file that was previously
                    // written for the no-cert case. The cert path has exercised this
                    // variant in production since Wave 2.2, confirming JSch 2.27.7
                    // handles it correctly on all supported Android versions.
                    jsch.addIdentity(
                        "tabssh-$effectiveKeyId",
                        jschBytes,
                        pubKeyBytes,
                        passphraseBytes
                    )
                    if (cert != null) {
                        Logger.i("SSHConnection", "Auth: SSH key + certificate added to JSch (keyId=$effectiveKeyId, cert=${cert.length} bytes)")
                    } else {
                        Logger.i("SSHConnection", "Auth: SSH key added to JSch (keyId=$effectiveKeyId)")
                    }
                    return@withContext
                } catch (e: Exception) {
                    Logger.e("SSHConnection", "Auth: Failed to add SSH key to JSch", e)
                    // Fall through to password auth
                } finally {
                    // Zero the plaintext jschBytes and passphrase — JSch has already
                    // parsed and copied what it needs (or the addIdentity call failed
                    // and we're not using them). Leaving plaintext key material on the
                    // heap extends the window an attacker has to scrape it from a
                    // memory dump.
                    jschBytes.fill(0)
                    passphraseBytes?.fill(0)
                }
            } else {
                // Key ID is set on the profile/identity but doesn't resolve to
                // a stored key. Common shape: connection imported from a
                // `~/.ssh/config` file via `SSHConfigParser` carries
                // `host.identityFileStr.hashCode().toString()` as a placeholder
                // keyId that was never bound to a real key. Without surfacing
                // this, the auth chain falls through to password → keyboard-
                // interactive → "Auth cancel" with no UX clue.
                Logger.w("SSHConnection", "Auth: JSch bytes null for keyId=$effectiveKeyId — stored key missing")
                if (profile.authType == AuthType.PUBLIC_KEY.name && effectivePassword == null) {
                    val msg = "Configured SSH key (id=$effectiveKeyId) is missing from the keystore. " +
                        "Re-import the key in Identities, or change this connection's auth type to Password."
                    _errorMessage.value = msg
                }
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
     * Delegates to [KeyStorage.getJSchBytesWithFallback] which handles both
     * the fast-path (bytes cached at import/generate time) and the fallback
     * (reconstruct from stored PKCS#8 DER for pre-cache legacy keys).
     */
    private suspend fun getJSchBytes(keyId: String): ByteArray? {
        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
        if (app == null) {
            Logger.e("SSHConnection", "getJSchBytes: Application context is null")
            return null
        }
        Logger.d("SSHConnection", "getJSchBytes: Looking up key $keyId")
        return app.keyStorage.getJSchBytesWithFallback(keyId)
    }

    /**
     * Wave 1.5 runtime wiring — load every stored key the user has into
     * JSch's identity repository so agent-forwarded sign requests can be
     * serviced. Skips encrypted keys with no stored passphrase (those
     * would throw on first sign and JSch reports the failure as a
     * generic auth error).
     *
     * The intent of "agent forwarding" on Android is *not* to bridge to
     * a system ssh-agent (there isn't one) — it's to let the remote host
     * sign with the user's TabSSH-stored keys when it hops to a further
     * host. Same end-user effect, no socket plumbing.
     */
    private suspend fun populateAgentIdentities(
        jsch: JSch,
        app: io.github.tabssh.TabSSHApplication
    ) = withContext(Dispatchers.IO) {
        val keys = try {
            app.database.keyDao().getAllKeys().first()
        } catch (e: Exception) {
            Logger.w("SSHConnection", "Agent forwarding: failed to enumerate keys: ${e.message}")
            return@withContext
        }
        var added = 0
        for (key in keys) {
            var bytes: ByteArray? = null
            var passphrase: ByteArray? = null
            try {
                bytes = getJSchBytes(key.keyId) ?: continue
                passphrase = app.securePasswordManager
                    .retrievePassword("key_passphrase_${key.keyId}")
                    ?.toByteArray()
                jsch.addIdentity("tabssh-agent-${key.keyId}", bytes, null, passphrase)
                added++
            } catch (e: Exception) {
                Logger.d("SSHConnection", "Agent forwarding: skipping key ${key.keyId}: ${e.message}")
            } finally {
                // Scrub plaintext key + passphrase from the heap; JSch has copied
                // what it needs into its own identity store.
                bytes?.fill(0)
                passphrase?.fill(0)
            }
        }
        Logger.i("SSHConnection", "Agent forwarding: loaded $added/${keys.size} stored keys into JSch identity repository")
    }

    /**
     * Open a shell channel for terminal access.
     *
     * @param forceShell When true, always opens a ChannelShell regardless of
     *   [ConnectionProfile.remoteCommand]. Used for post-init reconnects (e.g.
     *   SourceForge: `create` runs as exec, then we reopen as a plain shell).
     */
    suspend fun openShellChannel(forceShell: Boolean = false): Channel? = withContext(Dispatchers.IO) {
        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            Logger.e("SSHConnection", "Cannot open shell channel: session not connected")
            return@withContext null
        }

        try {
            // Issue #163 — always open a NEW channel on each call. Previous
            // implementation cached and returned the existing shellChannel,
            // which made "open the same profile in two tabs" silently share
            // one stream pair. With the cache gone each tab gets its own
            // ChannelShell on the same Session (multiplexing).

            // Issue #37 — open a `ChannelExec` instead of `ChannelShell` when
            // a RemoteCommand is set. Required for SourceForge-style hosts
            // (`create`), forced-`command="..."` jails in authorized_keys,
            // SFTP-only accounts (`internal-sftp`), gateway/menu hosts.
            //
            // PTY allocation for exec channels is controlled by the `requestTTY`
            // key in `advancedSettings` (populated by SSHConfigParser from ~/.ssh/config
            // or set manually). Semantics match OpenSSH: "yes"/"force" → PTY allocated;
            // "no"/"auto"/absent → no PTY (exec channels are non-interactive by default).
            //
            // We branch with two parallel blocks rather than a generic
            // `ChannelSession` variable because that class is package-private
            // in JSch (see field declaration above).
            val remoteCmd = if (forceShell) null
                           else profile.remoteCommand?.trim()?.takeIf { it.isNotEmpty() }

            if (remoteCmd != null) {
                val exec = currentSession.openChannel("exec") as ChannelExec
                try {
                exec.setCommand(remoteCmd)
                // Respect RequestTTY from advancedSettings (set via ~/.ssh/config import or manual edit).
                // Semantics mirror OpenSSH: "force"/"yes" → allocate PTY; "no" → never; "auto" → no PTY
                // for exec channels (same as OpenSSH default). Absent value defaults to "auto".
                val requestTTY = try {
                    profile.advancedSettings
                        ?.takeIf { it.isNotBlank() }
                        ?.let { org.json.JSONObject(it).optString("requestTTY", "auto") }
                        ?: "auto"
                } catch (_: Exception) { "auto" }
                if (requestTTY == "yes" || requestTTY == "force") {
                    exec.setPty(true)
                    exec.setPtyType(profile.terminalType)
                }
                exec.setPtySize(80, 24, 0, 0) // Will be updated by terminal
                applyEnvVarsTo(exec)
                applyForwardingFlags(currentSession, exec)
                // JSch requires getInputStream/getOutputStream to be called BEFORE
                // connect() to set up the piped stream infrastructure; accessing them
                // after connect() triggers a warning and may return stale references.
                @Suppress("UNUSED_VARIABLE") val _execIn  = exec.inputStream
                @Suppress("UNUSED_VARIABLE") val _execOut = exec.outputStream
                exec.connect()
                shellChannel = exec
                openChannels.add(exec)
                Logger.i("SSHConnection", "Opened exec channel: ${remoteCmd.take(60)} (pty=$requestTTY)")
                return@withContext exec
                } catch (e: Exception) {
                    // Channel was opened (allocated on the Session) but not yet
                    // tracked in openChannels — disconnect it directly so it
                    // doesn't linger attached to the Session.
                    try { exec.disconnect() } catch (_: Exception) {}
                    throw e
                }
            }

            // Default path — login shell.
            val shell = currentSession.openChannel("shell") as ChannelShell
            try {
                shell.setPtyType(profile.terminalType)
                shell.setPtySize(80, 24, 0, 0) // Will be updated by terminal
                // Wave 1.2: per-host env vars before channel.connect()
                applyEnvVarsTo(shell)
                applyForwardingFlags(currentSession, shell)
                // JSch requires getInputStream/getOutputStream to be called BEFORE
                // connect() — accessing after connect() triggers a warning and may
                // return stale references.
                @Suppress("UNUSED_VARIABLE") val _shellIn  = shell.inputStream
                @Suppress("UNUSED_VARIABLE") val _shellOut = shell.outputStream
                shell.connect()

                shellChannel = shell
                openChannels.add(shell)
                Logger.d("SSHConnection", "Shell channel opened (total open: ${openChannels.size})")
                return@withContext shell
            } catch (e: Exception) {
                // Same pre-tracking-failure case as the exec branch above —
                // explicitly disconnect to release the Session slot.
                try { shell.disconnect() } catch (_: Exception) {}
                throw e
            }

        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to open shell channel", e)
            return@withContext null
        }
    }

    /**
     * Issue #37 — Resize PTY on whichever channel kind we currently hold.
     *
     * Both `ChannelShell` and `ChannelExec` inherit `setPtySize` from the
     * package-private `ChannelSession`. We can't write the call generically
     * against `ChannelSession`, so dispatch on the public concrete type.
     */
    private fun resizeActiveChannelPty(cols: Int, rows: Int) {
        when (val ch = shellChannel) {
            is ChannelShell -> ch.setPtySize(cols, rows, 0, 0)
            is ChannelExec -> ch.setPtySize(cols, rows, 0, 0)
            else -> {} // null or some other channel kind we don't manage
        }
    }

    /**
     * Issue #163 — per-tab PTY resize. Each tab holds its own [Channel];
     * call this with the tab's channel so its dimensions don't bleed onto
     * sibling tabs sharing the same Session.
     */
    fun resizePtyOf(channel: Channel, cols: Int, rows: Int) {
        try {
            when (channel) {
                is ChannelShell -> channel.setPtySize(cols, rows, 0, 0)
                is ChannelExec -> channel.setPtySize(cols, rows, 0, 0)
                else -> {}
            }
        } catch (e: Exception) {
            Logger.w("SSHConnection", "resizePtyOf failed: ${e.message}")
        }
    }

    /**
     * Issue #163 — close ONE tab's channel without tearing down the Session
     * that sibling tabs are still using. Caller (SSHTab) invokes this on
     * tab close. The Session lives until [disconnect] runs.
     */
    fun closeChannel(channel: Channel) {
        if (openChannels.remove(channel)) {
            // Snapshot exit-status BEFORE disconnect — once shellChannel
            // is repointed (or nulled), the reconnect-dialog gate has no
            // way to see the value. JSch sets exitStatus when the remote
            // sends SSH_MSG_CHANNEL_REQUEST exit-status, which arrives
            // before close on a clean `exit`.
            val exit = try { channel.exitStatus } catch (_: Exception) { -1 }
            if (exit >= 0) lastShellExitStatus = exit
            try { channel.disconnect() } catch (_: Exception) {}
            // Keep the legacy [shellChannel] pointer pointing at SOMETHING
            // valid if it was the one we just closed — pick any remaining
            // open channel or null out.
            if (shellChannel === channel) {
                shellChannel = openChannels.toList().firstOrNull()
            }
            Logger.d("SSHConnection", "Channel closed (remaining: ${openChannels.size}, exit=$exit)")
        }
    }

    /**
     * Issue #163 — true if the underlying JSch Session is still up. Tabs
     * use this to distinguish "my shell exited, session is fine" (don't
     * cascade to wrapper-level disconnect) from "session died, everything
     * is gone" (cascade so the global notification fires).
     */
    fun isSessionAlive(): Boolean = session?.isConnected == true

    /**
     * Health-check entry point called by [io.github.tabssh.services.SSHConnectionService]
     * every 30 s from its monitoring loop.
     *
     * Detects sessions that died silently — JSch keepalive timeout, remote EOF
     * after screen lock, NAT expiry — without going through [handleConnectionError].
     * When the JSch session is dead but our state still reports an active value
     * (CONNECTED / CONNECTING / AUTHENTICATING), this method:
     *   1. Transitions state to DISCONNECTED and fires the onDisconnected listeners
     *      so the service notification updates immediately.
     *   2. Arms [NetworkAwareReconnector] via [NetworkAwareReconnector.onConnectionLost]
     *      so it applies exponential backoff and network gating before the next attempt.
     *
     * Safe to call from any thread — all state writes are coroutine-safe StateFlow
     * assignments and the listener list is a CopyOnWriteArrayList.
     */
    fun triggerReconnectIfDead() {
        // Never connected yet, or already in a terminal / reconnecting state.
        if (!hadSuccessfulConnect) return
        if (!_connectionState.value.isActive()) return
        // JSch session is still alive — nothing to do.
        if (session?.isConnected == true) return
        Logger.w(
            "SSHConnection",
            "Health check: JSch session is dead but state=${_connectionState.value} " +
                "for ${profile.host}:${profile.port} — transitioning to DISCONNECTED"
        )
        _connectionState.value = ConnectionState.DISCONNECTED
        notifyListeners { onDisconnected(id) }
        reconnector?.onConnectionLost()
            ?: Logger.w(
                "SSHConnection",
                "Health check: reconnector is null for ${profile.host} — session will not auto-reconnect"
            )
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

        // Channel leak fix: previously `channel.disconnect()` was only on the
        // happy path inside the try{}. Any exception during connect()/read()
        // left the JSch channel open on the Session until the session itself
        // tore down — under sustained errors (timeouts, etc.) the per-session
        // channel limit was reachable.
        var channel: ChannelExec? = null
        try {
            channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val inputStream = channel.inputStream
            val errorStream = channel.errStream

            channel.connect(timeoutMs.toInt())

            val output = StringBuilder()
            val buffer = ByteArray(4096)

            // Blocking read loop: blocks until the server sends data or closes
            // the channel (read returns -1). This replaces the prior
            // inputStream.available() + delay(100) poll which woke every 100 ms
            // even when idle and introduced up to 100 ms of latency per chunk.
            // withTimeoutOrNull bounds the total read phase so a hung server or
            // a command that never terminates cannot stall the caller indefinitely.
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val n = inputStream.read(buffer)
                    if (n == -1) break
                    if (n > 0) output.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            } ?: Logger.w("SSHConnection", "executeCommand timed out after ${timeoutMs}ms: $command")

            // Drain stderr after stdout EOF (channel is closed by remote at this point)
            val errorOutput = StringBuilder()
            while (errorStream.available() > 0) {
                val n = errorStream.read(buffer)
                if (n > 0) errorOutput.append(String(buffer, 0, n, Charsets.UTF_8))
            }

            val exitStatus = channel.exitStatus
            if (exitStatus != 0 && errorOutput.isNotEmpty()) {
                Logger.w("SSHConnection", "Command '$command' exit $exitStatus — stderr: $errorOutput")
            }

            output.toString()

        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to execute command: $command", e)
            throw e
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
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
            try {
                channel.connect()
            } catch (e: Exception) {
                // connect() failed — channel was allocated on the Session
                // but never assigned to sftpChannel, so callers can't reach
                // it for cleanup. Disconnect directly to release the slot.
                try { channel.disconnect() } catch (_: Exception) {}
                throw e
            }

            sftpChannel = channel
            Logger.d("SSHConnection", "SFTP channel opened")
            return@withContext channel

        } catch (e: Exception) {
            Logger.e("SSHConnection", "Failed to open SFTP channel", e)
            return@withContext null
        }
    }
    
    /**
     * Zero in-memory credential caches without disconnecting. Called by
     * [SSHSessionManager.clearCachedCredentials] when the app moves to the
     * background or a biometric-lock event fires. JSch holds its own copy of
     * the password so live sessions are unaffected; next authentication prompt
     * (e.g. after a reconnect) will re-fetch from [SecurePasswordManager].
     */
    internal fun clearCachedCredentials() {
        cachedPassword = null
        cachedPassphrase = null
        Logger.d("SSHConnection", "Credential cache cleared for ${profile.host}")
    }

    /**
     * Disconnect from the SSH server.
     *
     * Runs on [Dispatchers.IO] via `withContext`: JSch channel teardown,
     * session close, and jump-host cleanup are all blocking socket I/O.
     * Making the function `suspend` enforces the dispatcher at the type-system
     * level — a future Main-thread caller will not compile without wrapping in
     * a launch or withContext, preventing silent ANR regressions.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        Logger.i("SSHConnection", "Disconnecting from ${profile.host}")
        val effectiveUser = resolvedIdentity?.username ?: profile.username
        val duration = if (sessionStartMs > 0) {
            val secs = (System.currentTimeMillis() - sessionStartMs) / 1000
            when {
                secs < 60   -> "${secs}s"
                secs < 3600 -> "${secs / 60}m ${secs % 60}s"
                else        -> "${secs / 3600}h ${(secs % 3600) / 60}m"
            }
        } else null
        val msg = if (duration != null) "Session ended (duration: $duration)" else "Session ended"
        Logger.logHostEvent(profile.id, effectiveUser, profile.host, profile.port, "INFO", msg)
        sessionStartMs = 0

        connectJob?.cancel()
        reconnector?.cancel()
        reconnector = null
        hadSuccessfulConnect = false

        // Issue #163 — close every channel a tab opened against this session,
        // not just the legacy single shellChannel pointer. Snapshot the
        // most-recent shell exit-status before disconnecting so the
        // reconnect-dialog gate can still distinguish clean exit (0) from
        // abrupt drop (-1) after this method returns.
        run {
            val exit = try { shellChannel?.exitStatus ?: -1 } catch (_: Exception) { -1 }
            if (exit >= 0) lastShellExitStatus = exit
        }
        openChannels.toList().forEach {
            try { it.disconnect() } catch (_: Exception) {}
        }
        openChannels.clear()
        shellChannel = null

        sftpChannel?.disconnect()
        sftpChannel = null

        // Stop X11 proxy before closing the session so any in-flight relay
        // threads see a closed socket rather than hanging on reads.
        x11Proxy?.stop()
        x11Proxy = null

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

        cachedPassword = null
        cachedPassphrase = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _errorMessage.value = null

        notifyListeners { onDisconnected(id) }
    }
    
    private fun handleConnectionError(error: Exception) {
        // Build detailed error information
        val errorInfo = buildDetailedErrorInfo(error)

        Logger.e("SSHConnection", "Connection failed: ${errorInfo.userMessage}", error)
        val effectiveUser = resolvedIdentity?.username ?: profile.username
        val hostLogLevel = if (errorInfo.errorType.contains("AUTH", ignoreCase = true)) "WARN" else "ERROR"
        Logger.logHostEvent(profile.id, effectiveUser, profile.host, profile.port, hostLogLevel, "Connection failed: ${errorInfo.userMessage}")
        sessionStartMs = 0

        _connectionState.value = ConnectionState.ERROR
        _errorMessage.value = errorInfo.userMessage
        _detailedError.value = errorInfo
        notifyListeners { onError(id, error) }

        // Audit logging — best-effort, fire-and-forget so a logging failure
        // never interferes with error reporting or reconnect logic.
        try {
            val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
            val audit = app?.auditLogManager
            if (audit != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        audit.logAuthFailure(profile, id, "unknown", error.message ?: "")
                    } catch (e: Exception) {
                        Logger.w("SSHConnection", "Audit log (authFailure) failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("SSHConnection", "Audit log (authFailure) dispatch failed: ${e.message}")
        }

        // Auth-fail markers JSch actually emits — substring matches on
        // "password" / "publickey" alone caught unrelated kex failures.
        val isAuthError = error.message?.let { msg ->
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("Auth cancel", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("Too many authentication failures", ignoreCase = true)
        } ?: false

        if (isAuthError) {
            Logger.i("SSHConnection", "Auth error — not retrying")
            return
        }
        if (!hadSuccessfulConnect) {
            Logger.i("SSHConnection", "Initial connect failed — not auto-retrying")
            return
        }

        // Delegate reconnection to NetworkAwareReconnector which handles:
        //  - exponential backoff (5 s → … → 5 min cap)
        //  - pausing when the device is offline (no wasted retries)
        //  - immediate wake when the network link returns
        //  - 5-minute fallback poll for missed network callbacks
        reconnector?.onConnectionLost()
            ?: Logger.w("SSHConnection", "reconnector is null after successful connect — this is a bug")
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
            // Issue #37 — dispatch on the concrete channel subtype since the
            // shared parent (ChannelSession) is package-private in JSch.
            resizeActiveChannelPty(cols, rows)
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
     * Last exit status reported by the remote shell channel, or -1 if
     * unknown / not yet reported. JSch fills this in from the SSH
     * "exit-status" message when the remote shell terminates cleanly
     * (e.g. user typed `exit`/`logout` → bash sends 0). For sudden
     * disconnects (network drop, server kill) the channel tears down
     * without an exit-status message and JSch leaves the field at -1,
     * which is exactly the discriminator the UI wants for "clean exit
     * vs unexpected disconnect — should we offer reconnect?".
     */
    fun getShellExitStatus(): Int {
        if (intentionalClose) return 0
        val live = try { shellChannel?.exitStatus ?: -1 } catch (_: Exception) { -1 }
        return if (live >= 0) live else lastShellExitStatus
    }

    /**
     * Signal that this disconnect is intentional (user-initiated from the
     * notification shade or a similar explicit action). After this call,
     * [getShellExitStatus] returns 0 so [TabTerminalActivity] closes the
     * tab cleanly instead of showing the reconnect dialog.
     */
    fun markIntentionalClose() {
        intentionalClose = true
        lastShellExitStatus = 0
    }
    
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