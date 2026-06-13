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

    // Tab state
    // Default title format: user@host (shows connection info)
    private val _title = MutableStateFlow(generateDefaultTitle())
    val title: StateFlow<String> = _title.asStateFlow()

    // Track if title was set by OSC sequence (should not be overwritten by status)
    private var titleSetByTerminal = false

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
    // @Volatile: bytesReceived/bytesSent are incremented from TermuxBridge's
    // IO read loop and read from Main (status bar). sessionStartTime is set
    // from connect-success on IO and read from Main.
    @Volatile
    private var sessionStartTime: Long = 0
    @Volatile
    private var bytesReceived: Long = 0
    @Volatile
    private var bytesSent: Long = 0

    // Session recording
    var sessionRecorder: io.github.tabssh.terminal.recording.SessionRecorder? = null

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

    private var multiplexerDetectionJob: Job? = null

    // Screen change listener for UI updates
    private var onScreenChangedListener: (() -> Unit)? = null

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
                bytesReceived++ // Approximate - actual bytes tracked in bridge

                // Mark as having unread output if tab is not active
                if (!_isActive.value) {
                    _hasUnreadOutput.value = true
                    _unreadLines.value += 1
                }

                // Notify UI to redraw
                onScreenChangedListener?.invoke()
            }

            override fun onTitleChanged(title: String) {
                // Update tab title from terminal (e.g., from OSC sequences)
                if (title.isNotBlank()) {
                    _title.value = title
                    titleSetByTerminal = true  // Mark that terminal set the title
                } else {
                    titleSetByTerminal = false
                    _title.value = generateDefaultTitle()
                }
                // Stash on the SSHConnection so the foreground service
                // can read it when rebuilding the per-host notification
                // text. Triggers a state-change re-broadcast so the
                // SessionManagerListener pipeline (which the service
                // listens on) refreshes without a new event type.
                connection?.let { conn ->
                    conn.terminalTitle = title.takeIf { it.isNotBlank() }
                    conn.notifyMetadataChanged()
                }
                Logger.d("SSHTab", "Tab title changed to: $title")
            }

            override fun onBell() {
                // Terminal bell - could vibrate or play sound
                Logger.d("SSHTab", "Terminal bell")
            }

            override fun onColorsChanged() {
                // Colors changed - redraw
                onScreenChangedListener?.invoke()
            }

            override fun onCursorStateChanged(visible: Boolean) {
                // Cursor visibility changed - redraw
                onScreenChangedListener?.invoke()
            }

            override fun onCopyToClipboard(text: String) {
                // Handle clipboard copy request
                Logger.d("SSHTab", "Copy to clipboard: ${text.take(50)}...")
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
     * Set listener for screen changes (for UI redraw)
     */
    fun setOnScreenChangedListener(listener: (() -> Unit)?) {
        onScreenChangedListener = listener
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

            // Launch coroutine to observe connection state
            stateCollectorJob?.cancel()
            stateCollectorJob = connectionScope.launch {
                sshConnection.connectionState.collect { state ->
                    _connectionState.value = state
                    updateTitleWithStatus(state)  // Update title with status indicator
                    Logger.d("SSHTab", "Connection state changed to: $state")
                    if (state == ConnectionState.ERROR) {
                        _hasError.value = true
                    }
                }
            }

            // Connect terminal to SSH streams
            Logger.i("SSHTab", "Opening shell channel...")
            val shellChannel = sshConnection.openShellChannel()
            if (shellChannel != null) {
                Logger.i("SSHTab", "Shell channel opened successfully, isConnected=${shellChannel.isConnected}")

                ownChannel = shellChannel

                val inputStream = shellChannel.inputStream
                val outputStream = shellChannel.outputStream

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
            bytesReceived = bytesReceived,
            bytesSent = bytesSent,
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
        bytesSent += text.length
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
     * ASK mode is currently treated as AUTO_ATTACH — a future iteration
     * could surface a tab-level dialog. AUTO_ATTACH and CREATE_NEW already
     * cover the practical cases; a global "always create new" toggle has
     * never landed in any SSH client we've copied from.
     */
    private fun runPostConnectCommands() {
        val lines = mutableListOf<String>()

        if (profile.multiplexerMode != "OFF") {
            val app = try { io.github.tabssh.TabSSHApplication.get() } catch (_: Exception) { null }
            val type = app?.let {
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(it)
                    .getString("gesture_multiplexer_type", "tmux")
            } ?: "tmux"
            val name = profile.multiplexerSessionName?.takeIf { it.isNotBlank() } ?: "tabssh"
            val cmd = buildMultiplexerCommand(type, profile.multiplexerMode, name)
            if (cmd != null) {
                Logger.i("SSHTab", "Multiplexer auto-launch: $cmd")
                lines.add(cmd)
                // Record the active type so the PREFIX keyboard key sends the
                // right prefix byte without needing a global preference lookup.
                _activeMultiplexerType.value = type
            }
        }

        // Schedule an env-var probe on a separate exec channel. Fires 2 s
        // after connect so the login shell (and any auto-started multiplexer)
        // has time to set up environment. Only sets activeMultiplexerType if
        // it wasn't already determined by the auto-launch branch above.
        // Skip when the user explicitly opted out of all multiplexer features.
        if (profile.multiplexerMode != "OFF") {
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
                try {
                    val output = conn.executeCommand(
                        // POSIX sh one-liner: no bashisms. Prints one word or nothing.
                        "sh -c '" +
                            "if [ -n \"\$TMUX\" ]; then echo tmux; " +
                            "elif [ -n \"\$STY\" ]; then echo screen; " +
                            "elif [ -n \"\$ZELLIJ_SESSION_NAME\" ]; then echo zellij; " +
                            "fi'",
                        timeoutMs = 5000
                    ).trim()
                    val detected = if (output in listOf("tmux", "screen", "zellij")) output else null
                    if (detected != _activeMultiplexerType.value) {
                        _activeMultiplexerType.value = detected
                        if (detected != null)
                            Logger.i("SSHTab", "Multiplexer attached: $detected")
                        else
                            Logger.i("SSHTab", "Multiplexer detached (none found in env)")
                    }
                } catch (_: Exception) {
                    // Detection failure is non-fatal — PREFIX key shows "unknown" state.
                }
                delay(30_000)
            }
        }
    }

    /** Cancel the detection loop on disconnect so it doesn't probe a dead session. */
    fun stopMultiplexerDetection() {
        multiplexerDetectionJob?.cancel()
        multiplexerDetectionJob = null
    }

    private fun buildMultiplexerCommand(type: String, mode: String, name: String): String? {
        val safeName = name.replace("'", "")
        return when (type) {
            "tmux" -> when (mode) {
                "AUTO_ATTACH", "ASK" -> "tmux new -A -s '$safeName'"
                "CREATE_NEW"         -> "tmux new -s '$safeName'"
                else                 -> null
            }
            "screen" -> when (mode) {
                "AUTO_ATTACH", "ASK" -> "screen -RR '$safeName'"
                "CREATE_NEW"         -> "screen -S '$safeName'"
                else                 -> null
            }
            "zellij" -> when (mode) {
                "AUTO_ATTACH", "ASK" -> "zellij attach --create '$safeName'"
                "CREATE_NEW"         -> "zellij --session '$safeName'"
                else                 -> null
            }
            else -> null
        }
    }

    /**
     * Reset title to default (connection name)
     */
    fun resetTitle() {
        titleSetByTerminal = false
        _title.value = generateDefaultTitle()
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
        // Don't override terminal-set title (from OSC sequences)
        if (titleSetByTerminal) return

        val baseTitle = generateDefaultTitle()
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
