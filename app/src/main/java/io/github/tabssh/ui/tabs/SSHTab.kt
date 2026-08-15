package io.github.tabssh.ui.tabs

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Represents a single SSH tab with its connection, terminal, and UI state.
 * This is the core of TabSSH's tabbed interface innovation.
 *
 * Uses Termux terminal emulator for proper VT100/ANSI/xterm-256color support.
 */
class SSHTab(
    val profile: ConnectionProfile,
    val termuxBridge: TermuxBridge
) {
    val tabId: String = UUID.randomUUID().toString()

    // Coroutine scope for managing tab lifecycle
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active state-flow collector — cancelled before each new connect() so
    // the mosh-fallback path doesn't accumulate observers.
    private var stateCollectorJob: Job? = null

    // Connection (public for gesture command sending).
    // @Volatile: written from Dispatchers.IO (connect/disconnect coroutines)
    // and read from Main (gesture send, UI status) and from JSch/TermuxBridge
    // worker threads (listener callbacks).
    @Volatile
    var connection: SSHConnection? = null

    // Issue #163 — this tab's own ChannelShell (or ChannelExec if the
    // profile carries a RemoteCommand). Each tab gets one. PTY resize and
    // close-on-tab-disconnect route through this rather than the
    // connection-level shellChannel pointer, so opening the same profile
    // in multiple tabs no longer makes them share one stream.
    // @Volatile: written from Dispatchers.IO (connect path), read from Main
    // (resize/PTY size), and cleared from disconnect() that may be invoked
    // from either thread.
    @Volatile
    private var ownChannel: com.jcraft.jsch.Channel? = null

    // Wave 2.3 — telnet alternative. Only one of `connection` / `telnetConnection`
    // is set; gesture command sending and clean disconnect both check both.
    @Volatile
    var telnetConnection: io.github.tabssh.ssh.connection.TelnetConnection? = null

    // Wave 9.2 — bundled native mosh-client session. Lives in parallel to
    // (or in place of) the SSH session; mosh-server detaches from its
    // bootstrap SSH and roams independently after start.
    @Volatile
    var moshSession: io.github.tabssh.protocols.mosh.MoshNativeClient.Session? = null

    // Mosh only carries terminal I/O over its own UDP transport, never X11.
    // When the profile requests X11 forwarding on a mosh tab, connectMosh()
    // retains the bootstrap SSHConnection here (instead of nulling it out)
    // and keeps an X11-carrier channel open on it for the tab's lifetime;
    // null for every non-X11 mosh connection and for all non-mosh tabs.
    @Volatile
    private var moshX11BootstrapConnection: SSHConnection? = null

    // Tab state
    // Default title format: user@host (shows connection info)
    private val _title = MutableStateFlow(generateDefaultTitle())
    val title: StateFlow<String> = _title.asStateFlow()

    // Title the remote set via OSC 0/1/2, already sanitised. Kept separate from
    // _title so the connection-status indicator can still be applied on top of
    // it — a server that set a title once used to suppress the status prefix
    // for the rest of the session.
    private var terminalTitle: String? = null

    // Last logged title with spinner glyphs stripped — gates title-change log spam
    private var lastLoggedTitle: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _hasUnreadOutput = MutableStateFlow(false)
    val hasUnreadOutput: StateFlow<Boolean> = _hasUnreadOutput.asStateFlow()

    private val _lastActivity = MutableStateFlow(System.currentTimeMillis())
    val lastActivity: StateFlow<Long> = _lastActivity.asStateFlow()

    // Tab visual state
    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _unreadLines = MutableStateFlow(0)
    val unreadLines: StateFlow<Int> = _unreadLines.asStateFlow()

    // Tab position and ordering
    var tabIndex: Int = 0
        internal set

    // Session statistics.
    // @Volatile: sessionStartTime is set from connect-success on IO and read
    // from Main. The byte counters are read-modify-written from the terminal
    // callbacks and from sendText(), so they are AtomicLong rather than
    // volatile fields — a volatile Long makes ++ no safer than a plain field.
    @Volatile
    private var sessionStartTime: Long = 0
    private val bytesReceived = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesSent = java.util.concurrent.atomic.AtomicLong(0)

    // Session recording. Assigning a recorder installs the bridge's raw-output
    // sink; without this the recorder wrote only its header and footer because
    // nothing ever fed it session output.
    var sessionRecorder: io.github.tabssh.terminal.recording.SessionRecorder? = null
        set(value) {
            field = value
            termuxBridge.outputRecorder = if (value == null) {
                null
            } else {
                { bytes, length -> value.recordOutput(String(bytes, 0, length, Charsets.UTF_8)) }
            }
        }

    /**
     * Active multiplexer type for this tab ("tmux", "screen", "zellij", or null
     * when none is detected). Exposed as a [StateFlow] so the keyboard bar can
     * react in real time when the user attaches or detaches a multiplexer.
     *
     * Updated by:
     *  - [runPostConnectCommands] when the app auto-launches one (immediate)
     *  - [detectMultiplexerViaExec] which probes $TMUX/$STY/$ZELLIJ_SESSION_NAME
     *    via a lightweight exec channel: once at connect + every 30 s thereafter
     *    so attach/detach events are caught without requiring a reconnect
     *
     * Also writable by the host activity when the user explicitly selects a
     * multiplexer type via the PREFIX-key picker dialog.
     */
    private val _activeMultiplexerType = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val activeMultiplexerTypeFlow: kotlinx.coroutines.flow.StateFlow<String?> =
        _activeMultiplexerType.asStateFlow()

    /** Convenience getter for cases that don't need the flow. */
    val activeMultiplexerType: String? get() = _activeMultiplexerType.value

    /** Allow the host activity to set the type from the picker dialog. */
    fun setActiveMultiplexerType(type: String?) {
        _activeMultiplexerType.value = type
    }

    /**
     * Per-connection PRE-key override, seeded from
     * [ConnectionProfile.multiplexerOverride] and updatable mid-session via
     * the long-press picker (the picker persists the same value to the DB
     * row separately). Precedence: override > live detection > global
     * default. null = auto; "tmux"/"screen"/"zellij" = pinned; "off" = the
     * PRE key is disabled for this connection.
     */
    @Volatile
    private var multiplexerOverride: String? = profile.multiplexerOverride

    /** True when a user override (including "off") is pinning the PRE key. */
    val hasMultiplexerOverride: Boolean get() = multiplexerOverride != null

    /**
     * True unless this connection's PRE key has been explicitly disabled via
     * the long-press picker's per-connection "off" override. PRE key
     * enablement is per-connection only — there is no global toggle.
     */
    val isPrefixKeyEnabled: Boolean get() = multiplexerOverride != "off"

    /**
     * Apply a per-connection PRE-key override chosen in the long-press
     * picker. Pinned types take effect immediately; "off" clears the active
     * type (PRE key dims); null returns to auto mode and restarts the
     * detection loop so live state catches up without waiting for a
     * reconnect. Detection probes never overwrite a pinned value — see
     * [probeMultiplexerOnce].
     */
    fun applyMultiplexerOverride(type: String?) {
        multiplexerOverride = type
        when (type) {
            null -> {
                // Back to auto: let the next probe decide, starting now.
                if (connection?.isConnected() == true) detectMultiplexerViaExec()
            }
            "off" -> _activeMultiplexerType.value = null
            else -> _activeMultiplexerType.value = type
        }
    }

    private var multiplexerDetectionJob: Job? = null

    /**
     * Pending ASK-mode picker request (IDEA.md feature 21). Set once after
     * connect when profile.multiplexerMode == "ASK": carries the multiplexer
     * type, the session names found on the remote, and the profile's default
     * session name. StateFlow (not SharedFlow) so a host activity that
     * attaches late — tab switch, recreation — still sees the pending
     * request. Cleared by [attachMultiplexerSession],
     * [createMultiplexerSession], or [dismissMultiplexerAsk].
     */
    data class MultiplexerAskRequest(
        val type: String,
        val sessions: List<String>,
        val defaultSessionName: String
    )

    private val _multiplexerAskRequest =
        kotlinx.coroutines.flow.MutableStateFlow<MultiplexerAskRequest?>(null)
    val multiplexerAskRequestFlow: kotlinx.coroutines.flow.StateFlow<MultiplexerAskRequest?> =
        _multiplexerAskRequest.asStateFlow()

    init {
        Logger.d("SSHTab", "Created tab ${profile.getDisplayName()}")

        // Set up terminal listener to track activity
        setupTerminalListener()

        // Initialize the Termux emulator
        termuxBridge.initialize()
    }

    private fun setupTerminalListener() {
        termuxBridge.addListener(object : TermuxBridgeListener {
            override fun onConnected() {
                sessionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.CONNECTED
                _hasError.value = false
                updateTitleWithStatus(ConnectionState.CONNECTED)
                Logger.i("SSHTab", "Terminal connected for ${profile.getDisplayName()}")
            }

            override fun onDisconnected() {
                Logger.i("SSHTab", "Terminal disconnected for ${profile.getDisplayName()}")
                val conn = connection
                if (conn != null) {
                    // Issue #163 — close THIS tab's channel only. Sibling tabs
                    // (same profile, separate channels on the same Session)
                    // keep working.
                    //
                    // IMPORTANT: closeChannel() snapshots the JSch exit-status
                    // into SSHConnection.lastShellExitStatus. This MUST happen
                    // BEFORE we emit DISCONNECTED — if we emit first, the
                    // TabTerminalActivity observer runs getShellExitStatus()
                    // while lastShellExitStatus is still -1 (the default), and
                    // the reconnect-prompt gate incorrectly treats a clean exit
                    // (status 0) as an unexpected drop.
                    ownChannel?.let { conn.closeChannel(it) }
                    ownChannel = null

                    // Emit DISCONNECTED after exit status has been captured so
                    // the reconnect-dialog gate in TabTerminalActivity reads the
                    // correct status (0 = clean exit, -1 = unexpected drop).
                    _connectionState.value = ConnectionState.DISCONNECTED
                    updateTitleWithStatus(ConnectionState.DISCONNECTED)

                    // SourceForge shell init — `create` runs as a ChannelExec,
                    // provisions the shell environment, then exits. The SSH
                    // session is still alive; reopen a plain ChannelShell after
                    // a short delay so the user gets an interactive prompt
                    // without having to manually reconnect.
                    if (profile.remoteCommand?.trim() == "create" && conn.isSessionAlive()) {
                        connectionScope.launch {
                            // The scope has no exception handler, so anything
                            // thrown here would reach the default handler and
                            // take the process down instead of failing the tab.
                            try {
                                Logger.i("SSHTab", "SourceForge shell init complete — reopening plain shell in 2s")
                                delay(2000)
                                if (!conn.isSessionAlive()) return@launch
                                val newChannel = conn.openShellChannel(forceShell = true)
                                if (newChannel != null) {
                                    ownChannel = newChannel
                                    val inp = newChannel.inputStream
                                    val out = newChannel.outputStream
                                    if (inp != null && out != null) {
                                        termuxBridge.onResizeCallback = { cols, rows ->
                                            conn.resizePtyOf(newChannel, cols, rows)
                                        }
                                        termuxBridge.connect(inp, out)
                                    } else {
                                        Logger.e("SSHTab", "SourceForge reconnect: null streams on new channel")
                                    }
                                } else {
                                    Logger.e("SSHTab", "SourceForge reconnect: failed to open shell channel")
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                _hasError.value = true
                                Logger.e("SSHTab", "SourceForge shell reopen failed", e)
                            }
                        }
                        return
                    }

                    // Cascade only if the underlying Session is gone — that's
                    // the case where every sibling is also dead and we want
                    // the global disconnect notification to fire. If only the
                    // shell process under this channel exited, leave the
                    // session up for siblings.
                    if (!conn.isSessionAlive()) {
                        // disconnect() sends an SSH disconnect packet and joins the
                        // reader thread — blocking I/O. Must not run on the main thread
                        // (onDisconnected fires via TermuxBridge.runOnMain). Launch on
                        // connectionScope (Dispatchers.IO) to avoid ANR.
                        connectionScope.launch { conn.disconnect() }
                    }
                } else {
                    // No SSH connection (Telnet/Mosh/standalone).
                    //
                    // Two distinct events arrive here:
                    //
                    // 1. Stale SSH-teardown event during mosh handoff — the mosh
                    //    handoff path calls tab.disconnect() (SSH) then
                    //    tab.connectMosh(). TermuxBridge.disconnect() posts
                    //    onDisconnected() to the main thread asynchronously, so it
                    //    may arrive AFTER connectMosh() finishes with the mosh
                    //    PTY session already alive. Clobbering CONNECTED here would
                    //    kill the mosh session from the user's perspective.
                    //
                    // 2. Real mosh death — the mosh-client process exited. The PTY
                    //    session is no longer running. We must emit DISCONNECTED so
                    //    the reconnect dialog appears instead of leaving the user
                    //    stranded on the "[Process completed - press Enter]" screen.
                    //
                    // Distinguish by checking whether the mosh PTY is still alive.
                    // If yes → stale handoff event, ignore. If no → real death, emit.
                    if (termuxBridge.isMoshSessionAlive()) {
                        // Stale SSH teardown during handoff — mosh is running fine.
                        Logger.d("SSHTab", "Ignoring stale disconnect: mosh session still alive")
                        return
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED
                    updateTitleWithStatus(ConnectionState.DISCONNECTED)
                }
            }

            override fun onScreenChanged() {
                updateActivity()
                // Approximate - actual bytes tracked in bridge
                bytesReceived.incrementAndGet()

                // Mark as having unread output if tab is not active
                if (!_isActive.value) {
                    _hasUnreadOutput.value = true
                    _unreadLines.value += 1
                }
            }

            override fun onTitleChanged(title: String) {
                // The title is fully remote-controlled: strip control and
                // bidi-override characters and cap the length before it can
                // reach the tab bar or the foreground-service notification.
                val safeTitle = sanitizeRemoteTitle(title)
                terminalTitle = safeTitle
                updateTitleWithStatus(_connectionState.value)
                // Mosh animates the title with Braille spinner glyphs (U+2800–U+28FF)
                // about once a second — only react when the title changed beyond
                // the spinner frame, so neither the log nor the session-manager
                // broadcast is driven at the animation rate.
                val stableTitle = safeTitle?.filterNot { it.code in 0x2800..0x28FF }
                if (stableTitle == lastLoggedTitle) return
                lastLoggedTitle = stableTitle
                // Stash on the SSHConnection so the foreground service
                // can read it when rebuilding the per-host notification
                // text. Triggers a state-change re-broadcast so the
                // SessionManagerListener pipeline (which the service
                // listens on) refreshes without a new event type.
                connection?.let { conn ->
                    conn.terminalTitle = safeTitle
                    conn.notifyMetadataChanged()
                }
                // Never log the title itself: it is remote-controlled and can
                // carry whatever the host chooses to put in it.
                Logger.d("SSHTab", "Tab title changed (${safeTitle?.length ?: 0} chars)")
            }

            override fun onBell() {
                // Terminal bell - could vibrate or play sound
                Logger.d("SSHTab", "Terminal bell")
            }

            override fun onColorsChanged() {
                // TerminalView registers its own bridge listener for redraws;
                // the tab only tracks activity.
                updateActivity()
            }

            override fun onCursorStateChanged(visible: Boolean) {
                updateActivity()
            }

            override fun onCopyToClipboard(text: String) {
                // Handle clipboard copy request. The payload is whatever the
                // remote put in an OSC 52 sequence, so log the size only.
                Logger.d("SSHTab", "Copy to clipboard requested: xxxxx (${text.length} chars)")
            }

            override fun onPasteFromClipboard() {
                // Handle clipboard paste request
                Logger.d("SSHTab", "Paste from clipboard requested")
            }

            override fun onError(e: Exception) {
                _hasError.value = true
                Logger.e("SSHTab", "Terminal error in tab ${profile.getDisplayName()}", e)
            }
        })
    }

    /**
     * Wire the SSH connection for cleanup/state tracking without opening a
     * shell channel. Called on the mosh path when we bootstrap mosh-server
     * before touching the shell — avoids the SSH shell briefly flashing
     * lastlog on screen then getting wiped when mosh-client takes over.
     */
    fun initConnectionForMosh(sshConnection: SSHConnection) {
        connection = sshConnection
        stateCollectorJob?.cancel()
        stateCollectorJob = connectionScope.launch {
            sshConnection.connectionState.collect { state ->
                _connectionState.value = state
                updateTitleWithStatus(state)
                if (state == ConnectionState.ERROR) _hasError.value = true
            }
        }
    }

    /**
     * Connect this tab's terminal to the SSH connection
     */
    suspend fun connect(sshConnection: SSHConnection): Boolean {
        return try {
            Logger.i("SSHTab", "=== CONNECTING TAB TERMINAL for ${profile.getDisplayName()} ===")
            connection = sshConnection

            // Launch coroutine to observe connection state.
            //
            // Auto-recovery path: NetworkAwareReconnector re-establishes the
            // SSH Session at the connection layer after a network outage or
            // silent drop, but the per-tab shell channel is a separate object
            // — the old ChannelShell died with the old Session. If we only
            // mirror the state flag here, the user sees "connected" but the
            // terminal is inert.
            //
            // Watch for CONNECTED transitions that arrive AFTER we've
            // successfully wired at least once, and if our own shell channel
            // is dead, open a fresh one and re-wire TermuxBridge. This gives
            // multiplexer profiles their auto-reattach for free (the
            // post-connect script fires `tmux new -A -s name` which attaches
            // to the still-running session), and gives raw-SSH tabs a working
            // prompt again — no fake attempt to resurrect the dead shell.
            stateCollectorJob?.cancel()
            stateCollectorJob = connectionScope.launch {
                var previousState: ConnectionState? = null
                sshConnection.connectionState.collect { state ->
                    _connectionState.value = state
                    updateTitleWithStatus(state)
                    Logger.d("SSHTab", "Connection state changed to: $state")
                    if (state == ConnectionState.ERROR) {
                        _hasError.value = true
                    }
                    val wasDown = previousState != null &&
                        previousState != ConnectionState.CONNECTED
                    val isChannelAlive = ownChannel?.isConnected == true
                    if (state == ConnectionState.CONNECTED && wasDown && !isChannelAlive) {
                        Logger.i("SSHTab",
                            "Session reconnected — opening fresh shell channel for ${profile.getDisplayName()}")
                        try {
                            rewireShellChannel(sshConnection)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w("SSHTab", "Auto-recovery shell open failed: ${e.message}")
                        }
                    }
                    previousState = state
                }
            }

            // Connect terminal to SSH streams
            Logger.i("SSHTab", "Opening shell channel...")
            val shellChannel = sshConnection.openShellChannel()
            if (shellChannel != null) {
                Logger.i("SSHTab", "Shell channel opened successfully, isConnected=${shellChannel.isConnected}")

                ownChannel = shellChannel

                // Read via SSHConnection's accessors, which return the piped
                // streams captured BEFORE Channel.connect() — avoids JSch's
                // "getInputStream() should be called before connect()" warning
                // that fires on a second call to channel.getInputStream().
                val inputStream = sshConnection.getInputStream()
                val outputStream = sshConnection.getOutputStream()

                Logger.i("SSHTab", "Stream check - Input: ${inputStream?.javaClass?.simpleName ?: "NULL"}, Output: ${outputStream?.javaClass?.simpleName ?: "NULL"}")

                if (inputStream == null || outputStream == null) {
                    Logger.e("SSHTab", "CRITICAL: Shell channel streams are NULL for ${profile.getDisplayName()}")
                    return false
                }

                Logger.i("SSHTab", "TermuxBridge state before connect: emulator=${termuxBridge.getEmulator() != null}, listeners=${termuxBridge.isConnected.value}")
                Logger.i("SSHTab", "Wiring Termux terminal to SSH streams...")
                termuxBridge.connect(inputStream, outputStream)

                // SIGWINCH plumbing — every time the local terminal view
                // resizes (rotation, IME show/hide, font-size change),
                // TerminalView calls bridge.resize() which fires this
                // callback. Issue #163 — route through resizePtyOf with
                // THIS tab's channel so resizing one tab doesn't reshape
                // sibling tabs that share the same Session.
                termuxBridge.onResizeCallback = { cols, rows ->
                    ownChannel?.let { sshConnection.resizePtyOf(it, cols, rows) }
                }

                // Issue #170 — multiplexer auto-launch + post-connect script.
                // Fire-and-forget on connectionScope; runs after a short
                // delay so the remote shell has a chance to print its
                // greeting/PS1 before we inject anything.
                connectionScope.launch {
                    try {
                        kotlinx.coroutines.delay(500)
                        runPostConnectCommands()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w("SSHTab", "post-connect script failed: ${e.message}")
                    }
                }

                Logger.i("SSHTab", "=== TERMINAL WIRED TO SSH SUCCESSFULLY for ${profile.getDisplayName()} ===")
                true
            } else {
                Logger.e("SSHTab", "CRITICAL: Failed to open shell channel for ${profile.getDisplayName()}")
                false
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("SSHTab", "ERROR connecting tab ${profile.getDisplayName()}", e)
            _hasError.value = true
            false
        }
    }

    /**
     * Auto-recovery: open a fresh ChannelShell on an already-connected
     * SSHConnection and rewire TermuxBridge to its streams. Used when
     * NetworkAwareReconnector has re-established the Session after a
     * network outage.
     *
     * The old ChannelShell died with the old Session — this creates a
     * genuinely new interactive shell. For multiplexer profiles the
     * post-connect script (`tmux new -A -s name`) will attach back to the
     * still-running session on the server; for raw SSH the user gets a
     * fresh prompt (state loss is inherent — see CLAUDE.md "Honesty over
     * agreement").
     */
    private suspend fun rewireShellChannel(sshConnection: SSHConnection) {
        val shellChannel = sshConnection.openShellChannel() ?: run {
            Logger.w("SSHTab", "rewireShellChannel: openShellChannel returned null")
            return
        }
        ownChannel = shellChannel
        // Read via SSHConnection's accessors, which return the piped streams
        // captured BEFORE Channel.connect() in openShellChannel(). Reading
        // shellChannel.inputStream / outputStream here would be a second
        // post-connect fetch and trip JSch's
        // "getInputStream() should be called before connect()" warning.
        val inp = sshConnection.getInputStream()
        val out = sshConnection.getOutputStream()
        if (inp == null || out == null) {
            Logger.w("SSHTab", "rewireShellChannel: null streams on new channel")
            return
        }
        termuxBridge.onResizeCallback = { cols, rows ->
            ownChannel?.let { sshConnection.resizePtyOf(it, cols, rows) }
        }
        termuxBridge.connect(inp, out)

        // Re-run post-connect (multiplexer auto-attach + user script) so
        // the recovered tab lands back in the same tmux session for users
        // who have that configured. The delay matches the initial connect
        // path — remote shell needs time to print its greeting/PS1 before
        // we inject commands.
        connectionScope.launch {
            try {
                kotlinx.coroutines.delay(500)
                runPostConnectCommands()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("SSHTab", "post-connect script failed on rewire: ${e.message}")
            }
        }
        Logger.i("SSHTab",
            "=== TERMINAL REWIRED after reconnect for ${profile.getDisplayName()} ===")
    }

    /**
     * Wave 2.3 — Connect this tab to a Telnet backend.
     * Telnet has no separate "shell channel"; we wire its filtered streams
     * directly into TermuxBridge and drive state manually (Telnet has no
     * fine-grained CONNECTED/AUTHENTICATING phases — it's just connected).
     */
    suspend fun connect(telnet: io.github.tabssh.ssh.connection.TelnetConnection): Boolean {
        return try {
            Logger.i("SSHTab", "=== CONNECTING TELNET TAB for ${profile.getDisplayName()} ===")
            telnetConnection = telnet
            _connectionState.value = ConnectionState.CONNECTING
            val ok = telnet.connect()
            if (!ok) {
                _connectionState.value = ConnectionState.ERROR
                _hasError.value = true
                return false
            }
            termuxBridge.connect(telnet.inputStream, telnet.outputStream)
            _connectionState.value = ConnectionState.CONNECTED
            updateTitleWithStatus(ConnectionState.CONNECTED)
            // Push initial NAWS using the bridge's current size.
            telnet.setWindowSize(termuxBridge.getCols(), termuxBridge.getRows())
            Logger.i("SSHTab", "=== TELNET TAB WIRED for ${profile.getDisplayName()} ===")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("SSHTab", "ERROR connecting telnet tab ${profile.getDisplayName()}", e)
            _hasError.value = true
            _connectionState.value = ConnectionState.ERROR
            false
        }
    }

    /**
     * Wave 9.2 — Connect this tab to a bundled native mosh-client session.
     * Caller has already run [io.github.tabssh.protocols.mosh.MoshHandoff]
     * over an SSH connection to capture the (port, key) pair; we spawn
     * mosh-client locally and wire its stdio into the terminal.
     *
     * The SSH session that bootstrapped Mosh can be torn down after this —
     * Mosh's design is that mosh-server detaches from its parent SSH
     * immediately and listens on UDP independently.
     */
    suspend fun connectMosh(
        context: android.content.Context,
        host: String,
        port: Int,
        moshKeyBase64: String
    ): Boolean {
        return try {
            Logger.i("SSHTab", "=== CONNECTING MOSH TAB (PTY) for ${profile.getDisplayName()} ($host:$port) ===")
            _connectionState.value = ConnectionState.CONNECTING
            // B-12 — use the PTY-backed path via TermuxBridge.connectMoshClient()
            // instead of ProcessBuilder. mosh-client calls tcgetattr() at startup;
            // a plain pipe would cause ENOTTY and immediate exit. The JNI forkpty()
            // inside TerminalSession gives mosh-client a real TTY.
            val ok = termuxBridge.connectMoshClient(context, host, port, moshKeyBase64)
            if (!ok) {
                Logger.e("SSHTab", "mosh-client binary not available for this ABI")
                _hasError.value = true
                _connectionState.value = ConnectionState.ERROR
                return false
            }
            // moshSession (MoshNativeClient.Session) is not used in the PTY path —
            // the TerminalSession is owned by TermuxBridge.
            moshSession = null

            // Mosh carries only terminal I/O over its own UDP transport —
            // never X11. If the profile wants X11 forwarding, the bootstrap
            // SSH session is the only channel that can carry it, so it must
            // be kept alive (with an x11-req-carrying channel open) instead
            // of being dropped here. When X11 isn't wanted, the bootstrap
            // session has done its job and is disconnected explicitly so it
            // isn't orphaned.
            val bootstrap = connection
            if (bootstrap != null && bootstrap.wantsX11Forwarding()) {
                val x11Channel = bootstrap.openX11CarrierChannel()
                if (x11Channel != null) {
                    moshX11BootstrapConnection = bootstrap
                    Logger.i("SSHTab", "Retained mosh bootstrap session for X11 forwarding (${profile.getDisplayName()})")
                } else {
                    Logger.w("SSHTab", "X11 carrier channel failed to open; disconnecting mosh bootstrap session")
                    disconnectBootstrapSession(bootstrap)
                }
            } else {
                bootstrap?.let { disconnectBootstrapSession(it) }
            }

            // Detach from the SSH connection state collector. Keeping it
            // running would mirror the SSH session's CONNECTED state onto
            // _connectionState and immediately override the DISCONNECTED
            // emitted by onSessionFinished when the mosh-client PTY exits
            // (the SSH session itself may still be alive at that point).
            // Null connection so getShellExitStatus() correctly falls back
            // to termuxBridge.moshLastExitCode when the reconnect-dialog
            // gate runs in TabTerminalActivity.
            stateCollectorJob?.cancel()
            stateCollectorJob = null
            connection = null

            _connectionState.value = ConnectionState.CONNECTED
            updateTitleWithStatus(ConnectionState.CONNECTED)
            Logger.i("SSHTab", "=== MOSH TAB WIRED (PTY) for ${profile.getDisplayName()} ===")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("SSHTab", "ERROR connecting mosh tab ${profile.getDisplayName()}", e)
            _hasError.value = true
            _connectionState.value = ConnectionState.ERROR
            false
        }
    }

    /**
     * Disconnect a mosh bootstrap SSHConnection that nothing else
     * references (either it never carried X11 and its job is done, or its
     * X11 carrier channel failed to open). Unlike the tab's primary
     * `connection`, SSHSessionManager never took ownership of this one — it
     * was created solely for MoshHandoff.bootstrap() — so a full disconnect
     * here is correct and does not touch the non-mosh teardown path below.
     *
     * disconnect() is a suspend fun; dispatched on the process-lifetime
     * applicationScope (mirrors PortForwardingManager's teardown pattern)
     * so the disconnect survives connectionScope.cancel() in cleanup().
     */
    private fun disconnectBootstrapSession(bootstrap: SSHConnection) {
        val appScope = (bootstrap.context.applicationContext as? io.github.tabssh.TabSSHApplication)?.applicationScope
        val block: suspend () -> Unit = {
            try {
                bootstrap.disconnect()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d("SSHTab", "mosh bootstrap disconnect suppressed: ${e.message}")
            }
        }
        if (appScope != null) {
            appScope.launch(Dispatchers.IO) { block() }
        } else {
            connectionScope.launch { block() }
        }
    }

    /**
     * Disconnect this tab
     */
    fun disconnect() {
        Logger.d("SSHTab", "Disconnecting tab ${profile.getDisplayName()}")

        stateCollectorJob?.cancel()
        stateCollectorJob = null
        stopMultiplexerDetection()
        _activeMultiplexerType.value = null
        termuxBridge.disconnect()
        // Issue #163 — close just this tab's channel before dropping the
        // wrapper reference. The underlying Session belongs to whatever
        // sibling tabs may still be holding it; SSHSessionManager owns its
        // lifecycle, not us.
        connection?.let { c -> ownChannel?.let { c.closeChannel(it) } }
        ownChannel = null
        connection = null
        // Retained mosh bootstrap session (X11 carrier) — nothing else
        // references it, so tear it all the way down, not just its channel.
        moshX11BootstrapConnection?.let { disconnectBootstrapSession(it) }
        moshX11BootstrapConnection = null
        try { telnetConnection?.disconnect() } catch (e: Exception) {
            Logger.d("SSHTab", "telnetConnection.disconnect suppressed: ${e.message}")
        }
        telnetConnection = null
        try { moshSession?.close() } catch (e: Exception) {
            Logger.d("SSHTab", "moshSession.close suppressed: ${e.message}")
        }
        moshSession = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Activate this tab (mark as current/visible)
     */
    fun activate() {
        _isActive.value = true
        _hasUnreadOutput.value = false
        _unreadLines.value = 0
        updateActivity()

        Logger.d("SSHTab", "Activated tab ${profile.getDisplayName()}")
    }

    /**
     * Deactivate this tab (mark as background)
     */
    fun deactivate() {
        _isActive.value = false
        Logger.d("SSHTab", "Deactivated tab ${profile.getDisplayName()}")
    }

    /**
     * Update last activity timestamp
     */
    private fun updateActivity() {
        _lastActivity.value = System.currentTimeMillis()
    }

    /**
     * Get display title for tab bar
     */
    fun getDisplayTitle(): String {
        return when {
            _title.value.isNotBlank() -> _title.value
            else -> profile.getDisplayName()
        }
    }

    /**
     * Get short title for narrow tabs
     */
    fun getShortTitle(): String {
        val fullTitle = getDisplayTitle()
        return when {
            fullTitle.length <= 12 -> fullTitle
            fullTitle.contains("@") -> {
                // For user@host format, show just host
                fullTitle.substringAfter("@").take(12)
            }
            fullTitle.contains(" ") -> {
                // Take first word if multiple words
                fullTitle.substringBefore(" ").take(12)
            }
            else -> {
                // Truncate long single words
                fullTitle.take(10) + "…"
            }
        }
    }

    /**
     * Check if tab can be closed safely
     */
    fun canClose(): Boolean {
        return _connectionState.value == ConnectionState.DISCONNECTED ||
               _connectionState.value == ConnectionState.ERROR
    }

    /**
     * Get connection statistics
     */
    fun getConnectionStats(): TabStats {
        val duration = if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else 0

        return TabStats(
            connectionProfile = profile,
            connectionState = _connectionState.value,
            isActive = _isActive.value,
            hasUnreadOutput = _hasUnreadOutput.value,
            unreadLines = _unreadLines.value,
            sessionDuration = duration,
            bytesReceived = bytesReceived.get(),
            bytesSent = bytesSent.get(),
            terminalRows = termuxBridge.getRows(),
            terminalCols = termuxBridge.getColumns(),
            lastActivity = _lastActivity.value
        )
    }

    /**
     * Send text to this tab's terminal
     */
    fun sendText(text: String) {
        termuxBridge.sendText(text)
        bytesSent.addAndGet(text.length.toLong())
        updateActivity()
    }

    /**
     * Send key press to this tab's terminal
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        termuxBridge.sendKeyPress(keyCode, isCtrl, isAlt, isShift)
        updateActivity()
    }

    /**
     * Resize this tab's terminal
     */
    fun resize(rows: Int, cols: Int) {
        termuxBridge.resize(cols, rows)
        // Push the new size to the remote PTY too — the local emulator
        // alone isn't enough; the remote shell reflows lines based on
        // what it thinks the terminal width is. `connection` may be null
        // briefly during initial setup; safe-call.
        connection?.resizePty(cols, rows)
        Logger.d("SSHTab", "Resized tab ${profile.getDisplayName()} terminal to ${cols}x${rows}")
    }

    /**
     * Clear terminal screen
     */
    fun clearScreen() {
        termuxBridge.clearScreen()
    }

    /**
     * Get terminal content for sharing/copying
     */
    fun getTerminalContent(): String {
        return termuxBridge.getScreenContent()
    }

    /**
     * Get scrollback content
     */
    fun getScrollbackContent(): String {
        return termuxBridge.getScrollbackContent()
    }

    /**
     * Check if tab is connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED && termuxBridge.isConnected.value
    }

    /**
     * Set custom title (user-defined)
     */
    fun setCustomTitle(newTitle: String) {
        if (newTitle.isNotBlank()) {
            _title.value = newTitle
            Logger.d("SSHTab", "Set custom title for tab: $newTitle")
        }
    }

    /**
     * Issue #170 — assemble the post-connect command stream:
     * (1) optional tmux/screen/zellij auto-launch (if profile.multiplexerMode
     *     != OFF), (2) profile.postConnectScript lines (one per line, in
     *     order). Both are sent down the same shell channel; the remote
     *     reads them as if the user typed them.
     *
     * Multiplexer type comes from the global preference (`gesture_multiplexer_type`,
     * default tmux), session name from profile.multiplexerSessionName
     * (default `tabssh`).
     *
     * ASK mode (IDEA.md feature 21) defers the launch: the remote's existing
     * sessions are listed over an exec channel and surfaced through
     * [multiplexerAskRequestFlow] so the host activity can show an
     * attach/create picker; the chosen command is written to the shell only
     * after the user picks. Cancelling the picker leaves a plain shell.
     */
    private fun runPostConnectCommands() {
        val lines = mutableListOf<String>()

        // Seed the PRE-key state from the persisted per-connection override
        // before anything else runs — a pinned type shows immediately and
        // "off" keeps the key dimmed regardless of what detection would say.
        multiplexerOverride?.let { ov ->
            _activeMultiplexerType.value = if (ov == "off") null else ov
        }

        if (profile.multiplexerMode != "OFF") {
            val app = try { io.github.tabssh.TabSSHApplication.get() } catch (_: Exception) { null }
            // A pinned per-connection type also drives auto-launch; "off"
            // only disables the PRE key, not the profile's auto-launch mode.
            val type = multiplexerOverride?.takeIf { it != "off" }
                ?: app?.let {
                    androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(it)
                        .getString("gesture_multiplexer_type", "tmux")
                } ?: "tmux"
            val name = profile.multiplexerSessionName?.takeIf { it.isNotBlank() } ?: "tabssh"
            if (profile.multiplexerMode == "ASK") {
                // ASK defers the launch to a user pick: list the remote's
                // sessions on an exec channel, then publish the picker
                // request; the host activity writes nothing until the user
                // chooses attach / create / skip.
                connectionScope.launch {
                    // Give the login shell a moment so the listing exec
                    // channel doesn't race initial connection setup.
                    delay(1500)
                    val sessions = listMultiplexerSessions(type)
                    _multiplexerAskRequest.value =
                        MultiplexerAskRequest(type, sessions, name)
                }
            } else {
                val cmd = buildMultiplexerCommand(type, profile.multiplexerMode, name)
                if (cmd != null) {
                    Logger.i("SSHTab", "Multiplexer auto-launch: $cmd")
                    lines.add(cmd)
                    // Record the active type so the PREFIX keyboard key sends the
                    // right prefix byte without needing a global preference lookup.
                    // An "off" override keeps the key dimmed even while the
                    // profile's auto-launch still starts the multiplexer.
                    if (multiplexerOverride != "off") {
                        _activeMultiplexerType.value = type
                    }
                }
            }
        }

        // Schedule an env-var probe on a separate exec channel. Fires 2 s
        // after connect so the login shell (and any auto-started multiplexer)
        // has time to set up environment. Only sets activeMultiplexerType if
        // it wasn't already determined by the auto-launch branch above.
        // Skip when the user explicitly opted out of all multiplexer features
        // or pinned a per-connection override — a pinned value never yields
        // to detection, so the probe loop would be wasted exec channels.
        if (profile.multiplexerMode != "OFF" && multiplexerOverride == null) {
            detectMultiplexerViaExec()
        }

        profile.postConnectScript?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("#") }
            ?.forEach { lines.add(it) }

        if (lines.isEmpty()) return

        val payload = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        // Route through TermuxBridge so the write is serialised by the same
        // writeLock that protects the IME/keyboard/broadcast paths. Writing
        // directly to JSch's ChannelOutputStream here raced with concurrent
        // keystrokes on the GCM cipher state, producing server-side
        // "ssh_dispatch_run_fatal: message authentication code incorrect"
        // and a dropped session (see TermuxBridge writeLock docstring).
        try {
            termuxBridge.write(payload)
        } catch (e: Exception) {
            Logger.w("SSHTab", "Failed to write post-connect commands: ${e.message}")
        }
    }

    /**
     * Start a repeating multiplexer detection loop that probes environment
     * variables via a lightweight exec channel.
     *
     * Schedule:
     *  - First probe: 2 s after connect (gives the login shell + any dotfile
     *    multiplexer auto-start time to set $TMUX/$STY/$ZELLIJ_SESSION_NAME)
     *  - Subsequent probes: every 30 s while the tab is connected
     *
     * This lets the PREFIX key react dynamically:
     *  - User types `tmux` → detected at the next 30 s tick → key goes green
     *  - User exits multiplexer → next tick clears the state → key dims
     *
     * Cancels any previous detection job first so tab reconnects don't
     * accumulate parallel detection loops.
     */
    private fun detectMultiplexerViaExec() {
        multiplexerDetectionJob?.cancel()
        multiplexerDetectionJob = connectionScope.launch {
            delay(2000)
            while (true) {
                val conn = connection
                if (conn == null || !conn.isConnected()) break
                probeMultiplexerOnce(conn)
                delay(30_000)
            }
        }
    }

    /**
     * Run a single multiplexer probe and update state. Public so the PRE key
     * handler can trigger a fresh probe on demand when the user first presses
     * the key — the 30 s periodic loop may not have caught a newly-launched
     * multiplexer yet.
     *
     * Returns the detected type ("tmux"/"screen"/"zellij") or null. Also
     * updates `_activeMultiplexerType` as a side-effect so any collectors
     * (e.g. the PREFIX key visual state) refresh.
     */
    suspend fun probeMultiplexerNow(timeoutMs: Long = 3000L): String? {
        val conn = connection ?: return null
        if (!conn.isConnected()) return null
        return probeMultiplexerOnce(conn, timeoutMs)
    }

    private suspend fun probeMultiplexerOnce(
        conn: SSHConnection,
        timeoutMs: Long = 5000L
    ): String? {
        return try {
            // SSH exec channels spawn a FRESH shell as a child of sshd —
            // they never inherit $TMUX/$STY/$ZELLIJ from the user's
            // interactive multiplexer session (that's a different process
            // tree entirely). So env-var probing alone always returns
            // nothing for a user who ran `tmux` in their interactive shell.
            //
            // Layered probe:
            //   1. Env vars — cheap; catches the rare case where the
            //      user's .profile re-exports $TMUX (or the exec channel
            //      is somehow a child of tmux).
            //   2. Live server check — `tmux ls`, `screen -ls`,
            //      `zellij list-sessions`. If a server exists for the
            //      user, they're almost certainly attached to it in the
            //      interactive session. False positives are possible
            //      (server running, user detached) but far less painful
            //      than never detecting anything.
            // Each branch is tagged with which check matched (":env", ":live-socket",
            // ":live-proc") so a misdetection is diagnosable from a debug-log capture
            // alone — without this, "zellij" in the log doesn't say whether
            // $ZELLIJ_SESSION_NAME was (wrongly) set, whether `zellij list-sessions`
            // found a stray/leftover session, or whether `tmux ls` failed to see a
            // genuinely running tmux server.
            //
            // `tmux ls` / `screen -ls` look for the server's socket under
            // $TMUX_TMPDIR/tmux-$UID (default /tmp/tmux-$UID) or $SCREENDIR — but an
            // SSH exec channel is a fresh non-interactive, non-login shell that does
            // NOT source ~/.bashrc or ~/.profile, so a $TMUX_TMPDIR/$SCREENDIR/custom
            // -S socket path set only there is invisible here. That makes the socket
            // check silently fail to find a genuinely running server, falling through
            // to a later (wrong) branch. A `pgrep` process check has no socket-path
            // dependency at all, so it's tried as a fallback after each socket check.
            val rawOutput = conn.executeCommand(
                "sh -c '" +
                    "if [ -n \"\$TMUX\" ]; then echo tmux:env; exit 0; fi; " +
                    "if [ -n \"\$STY\" ]; then echo screen:env; exit 0; fi; " +
                    "if [ -n \"\$ZELLIJ_SESSION_NAME\" ]; then echo zellij:env; exit 0; fi; " +
                    "if command -v tmux >/dev/null 2>&1 && tmux ls >/dev/null 2>&1; then echo tmux:live-socket; exit 0; fi; " +
                    "if command -v screen >/dev/null 2>&1 && screen -ls 2>/dev/null | grep -qE \"[0-9]+\\.[^[:space:]]+\"; then echo screen:live-socket; exit 0; fi; " +
                    // `zellij list-sessions` prints "No active zellij sessions found."
                    // to STDOUT with exit 0 when nothing is running — a bare
                    // `grep -q .` false-positives on that boilerplate on any server
                    // with zellij installed, regardless of whether a session exists.
                    // Exclude lines containing "No active" to require a real session line.
                    "if command -v zellij >/dev/null 2>&1 && zellij list-sessions 2>/dev/null | grep -v \"No active\" | grep -q .; then echo zellij:live-socket; exit 0; fi; " +
                    "if pgrep -u \"\$(id -u)\" -x tmux >/dev/null 2>&1; then echo tmux:live-proc; exit 0; fi; " +
                    "if pgrep -u \"\$(id -u)\" -x screen >/dev/null 2>&1; then echo screen:live-proc; exit 0; fi; " +
                    "if pgrep -u \"\$(id -u)\" -x zellij >/dev/null 2>&1; then echo zellij:live-proc; exit 0; fi'",
                timeoutMs = timeoutMs
            ).trim()
            // Log the raw tagged probe output on every run (not just on state change) so
            // a misdetection is visible in a debug-log capture even when the wrong value
            // is being detected repeatedly and never triggers the "state changed" branch
            // below (e.g. reporting zellij:live for a tmux-only session, meaning `tmux ls`
            // failed to see a genuinely running tmux server over the exec channel).
            Logger.d("SSHTab", "Multiplexer probe raw output: '$rawOutput'")
            val output = rawOutput.substringBefore(':')
            val detected = if (output in listOf("tmux", "screen", "zellij")) output else null
            // A pinned per-connection override always wins over detection —
            // report what was detected but never overwrite the pinned state.
            // Guards the on-demand probeMultiplexerNow() path too (the
            // periodic loop is already skipped while an override is set).
            if (multiplexerOverride != null) return detected
            if (detected != _activeMultiplexerType.value) {
                _activeMultiplexerType.value = detected
                if (detected != null)
                    Logger.i("SSHTab", "Multiplexer attached: $detected")
                else
                    Logger.i("SSHTab", "Multiplexer detached (none found in env)")
            }
            detected
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelling the detection loop must not be reported as a probe
            // failure, or the job stays alive past stopMultiplexerDetection().
            throw e
        } catch (_: Exception) {
            // Detection failure is non-fatal — PREFIX key shows "unknown" state.
            null
        }
    }

    /** Cancel the detection loop on disconnect so it doesn't probe a dead session. */
    fun stopMultiplexerDetection() {
        multiplexerDetectionJob?.cancel()
        multiplexerDetectionJob = null
    }

    /**
     * List the remote's existing multiplexer session names for the ASK-mode
     * picker. Runs on a lightweight exec channel; every failure path returns
     * an empty list — the picker then simply offers only "create new".
     */
    private suspend fun listMultiplexerSessions(type: String): List<String> {
        val conn = connection ?: return emptyList()
        if (!conn.isConnected()) return emptyList()
        val cmd = when (type) {
            "tmux" -> "tmux ls -F '#S' 2>/dev/null"
            "screen" -> "screen -ls 2>/dev/null"
            "zellij" -> "zellij list-sessions -s 2>/dev/null || zellij list-sessions 2>/dev/null"
            else -> return emptyList()
        }
        return try {
            val raw = conn.executeCommand(cmd, timeoutMs = 5000L)
            parseMultiplexerSessions(type, raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Write a multiplexer command chosen by the ASK-mode picker to the shell
     * and record the active type (unless an "off" override pins the PRE key
     * dark). Shared tail of [attachMultiplexerSession] and
     * [createMultiplexerSession].
     */
    private fun sendAskModeCommand(type: String, cmd: String) {
        _multiplexerAskRequest.value = null
        Logger.i("SSHTab", "Multiplexer ASK pick: $cmd")
        try {
            termuxBridge.write((cmd + "\n").toByteArray(Charsets.UTF_8))
            if (multiplexerOverride != "off") {
                _activeMultiplexerType.value = type
            }
        } catch (e: Exception) {
            Logger.w("SSHTab", "Failed to write ASK-mode multiplexer command: ${e.message}")
        }
    }

    /** ASK-mode picker chose an existing session — attach to it. */
    fun attachMultiplexerSession(type: String, session: String) {
        buildAttachCommand(type, session)?.let { sendAskModeCommand(type, it) }
    }

    /** ASK-mode picker chose "create new" — start a fresh named session. */
    fun createMultiplexerSession(type: String, name: String) {
        buildMultiplexerCommand(type, "CREATE_NEW", name)?.let { sendAskModeCommand(type, it) }
    }

    /** ASK-mode picker dismissed — keep the plain shell, clear the request. */
    fun dismissMultiplexerAsk() {
        _multiplexerAskRequest.value = null
    }

    // Companion-scoped and internal (not private on the instance) so the pure
    // command-assembly logic is unit-testable without constructing an SSHTab.
    internal companion object {
        // Upper bound for a remote-supplied OSC title. Long enough for any
        // real shell prompt, short enough that a hostile host cannot flood the
        // tab bar or the notification with a multi-kilobyte string.
        private const val MAX_TITLE_LENGTH = 256

        /**
         * Strip everything a remote host could use to spoof or corrupt UI text
         * out of an OSC 0/1/2 title, and bound its length. Returns null for a
         * title that is empty once sanitised, meaning "fall back to the default
         * title".
         */
        internal fun sanitizeRemoteTitle(title: String): String? {
            val cleaned = title.filterNot { ch ->
                val code = ch.code
                code < 0x20 ||
                    code in 0x7F..0x9F ||
                    code in 0x202A..0x202E ||
                    code in 0x2066..0x2069
            }.trim().take(MAX_TITLE_LENGTH)
            return cleaned.ifBlank { null }
        }

        /**
         * tmux gets session-scoped mouse mode enabled in the same command
         * sequence (`\; set -q mouse on`): with mouse on, tmux enables mouse
         * tracking client-side, so TerminalView forwards swipe gestures as
         * wheel events and tmux scrolls its own server-side scrollback —
         * swipes act like a scrollbar instead of falling back to arrow keys
         * that a plain shell prompt just echoes. `set` without -g is session
         * scoped (only the TabSSH-launched session), -q suppresses the
         * unknown-option error on pre-2.1 tmux servers. zellij has mouse mode
         * on by default; GNU screen has no mouse-scroll support at all.
         */
        internal fun buildMultiplexerCommand(type: String, mode: String, name: String): String? {
            val safeName = shQuote(name)
            return when (type) {
                "tmux" -> when (mode) {
                    "AUTO_ATTACH", "ASK" -> "tmux new -A -s $safeName \\; set -q mouse on"
                    "CREATE_NEW"         -> "tmux new -s $safeName \\; set -q mouse on"
                    else                 -> null
                }
                "screen" -> when (mode) {
                    "AUTO_ATTACH", "ASK" -> "screen -RR $safeName"
                    "CREATE_NEW"         -> "screen -S $safeName"
                    else                 -> null
                }
                "zellij" -> when (mode) {
                    "AUTO_ATTACH", "ASK" -> "zellij attach --create $safeName"
                    "CREATE_NEW"         -> "zellij --session $safeName"
                    else                 -> null
                }
                else -> null
            }
        }

        /**
         * POSIX single-quote-escape [value] for interpolation into a remote
         * shell command. Wraps it in single quotes and renders any embedded
         * single quote as `'\''`, so nothing in [value] can break out of the
         * argument — session names reach us from the remote host's own
         * listing output and from free-text user input.
         *
         * Escaping rather than stripping: a session genuinely named `dev'box`
         * must still attach, which a quote-stripping form silently breaks.
         */
        internal fun shQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        /**
         * Attach command for an existing session chosen in the ASK-mode
         * picker. tmux keeps the same session-scoped mouse-mode tail as
         * [buildMultiplexerCommand] so scroll gestures behave identically
         * whichever path launched the attach.
         */
        internal fun buildAttachCommand(type: String, session: String): String? {
            val safe = shQuote(session)
            return when (type) {
                "tmux"   -> "tmux attach -t $safe \\; set -q mouse on"
                "screen" -> "screen -r $safe"
                "zellij" -> "zellij attach $safe"
                else     -> null
            }
        }

        /**
         * Parse the session-listing output for the ASK-mode picker.
         * tmux: one bare name per line (`tmux ls -F '#S'`). screen: extract
         * `pid.name` tokens from `screen -ls` (that full token is what
         * `screen -r` accepts unambiguously). zellij: first token per line,
         * ANSI color codes stripped, boilerplate/error lines skipped.
         */
        internal fun parseMultiplexerSessions(type: String, raw: String): List<String> {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return when (type) {
                "tmux" -> lines
                "screen" -> {
                    val token = Regex("([0-9]+\\.[^\\s(]+)")
                    lines.mapNotNull { token.find(it)?.groupValues?.get(1) }
                }
                "zellij" -> {
                    val ansi = Regex("\\u001B\\[[0-9;]*m")
                    lines.map { ansi.replace(it, "") }
                        .filter { it.isNotBlank() && !it.startsWith("No active") }
                        // The verbose `zellij list-sessions` fallback also lists dead
                        // sessions as "name [Created ...] (EXITED - attach to resurrect)".
                        // `zellij attach <name>` fails on those, so they must not be
                        // offered in the picker as if they were live.
                        .filter { !it.contains("EXITED") }
                        .mapNotNull { it.split(Regex("\\s+")).firstOrNull() }
                        .filter { it.isNotBlank() }
                }
                else -> emptyList()
            }
        }
    }

    /**
     * Reset title to default (connection name)
     */
    fun resetTitle() {
        terminalTitle = null
        lastLoggedTitle = null
        updateTitleWithStatus(_connectionState.value)
    }

    /**
     * Generate default title in format: user@host
     */
    private fun generateDefaultTitle(): String {
        val user = profile.username
        val host = profile.host
        return if (user.isNotBlank() && host.isNotBlank()) {
            "$user@$host"
        } else {
            profile.getDisplayName()
        }
    }

    /**
     * Update title with connection status prefix
     */
    private fun updateTitleWithStatus(state: ConnectionState) {
        // A terminal-set title replaces the user@host base, but never the
        // status indicator: a disconnected tab must still read as disconnected.
        val baseTitle = terminalTitle ?: generateDefaultTitle()
        _title.value = when (state) {
            ConnectionState.CONNECTING -> "⏳ $baseTitle"
            ConnectionState.CONNECTED -> baseTitle
            ConnectionState.DISCONNECTED -> "⏸ $baseTitle"
            ConnectionState.ERROR -> "❌ $baseTitle"
            ConnectionState.AUTHENTICATING -> "🔐 $baseTitle"
        }
    }

    /**
     * Paste clipboard content into terminal
     */
    fun paste(clipboardText: String) {
        sendText(clipboardText)
        Logger.d("SSHTab", "Pasted ${clipboardText.length} characters to terminal")
    }

    /**
     * Get the Termux screen buffer for rendering
     */
    fun getScreen() = termuxBridge.getScreen()

    /**
     * Get cursor row
     */
    fun getCursorRow() = termuxBridge.getCursorRow()

    /**
     * Get cursor column
     */
    fun getCursorCol() = termuxBridge.getCursorCol()

    /**
     * Check if cursor is visible
     */
    fun isCursorVisible() = termuxBridge.isCursorVisible()

    /**
     * Cleanup tab resources
     */
    fun cleanup() {
        Logger.d("SSHTab", "Cleaning up tab ${profile.getDisplayName()}")

        disconnect()
        // Close the transcript file before the bridge goes away: a tab closed
        // while recording used to leave the FileWriter open until the process
        // died, with the trailing footer never written.
        sessionRecorder?.stopRecording()
        sessionRecorder = null
        termuxBridge.cleanup()
        connectionScope.cancel() // Cancel all coroutines
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SSHTab) return false
        return tabId == other.tabId
    }

    override fun hashCode(): Int {
        return tabId.hashCode()
    }

    override fun toString(): String {
        return "SSHTab(id=$tabId, profile=${profile.getDisplayName()}, state=${_connectionState.value})"
    }

}

/**
 * Statistics and information about a tab
 */
data class TabStats(
    val connectionProfile: ConnectionProfile,
    val connectionState: ConnectionState,
    val isActive: Boolean,
    val hasUnreadOutput: Boolean,
    val unreadLines: Int,
    val sessionDuration: Long,
    val bytesReceived: Long,
    val bytesSent: Long,
    val terminalRows: Int,
    val terminalCols: Int,
    val lastActivity: Long
)
