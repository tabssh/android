package io.github.tabssh.ui.tabs

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.terminal.TermuxBridge
import io.github.tabssh.terminal.TermuxBridgeListener
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Connection (public for gesture command sending)
    var connection: SSHConnection? = null

    // Wave 2.3 — telnet alternative. Only one of `connection` / `telnetConnection`
    // is set; gesture command sending and clean disconnect both check both.
    var telnetConnection: io.github.tabssh.ssh.connection.TelnetConnection? = null

    // Wave 9.2 — bundled native mosh-client session. Lives in parallel to
    // (or in place of) the SSH session; mosh-server detaches from its
    // bootstrap SSH and roams independently after start.
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

    // Session statistics
    private var sessionStartTime: Long = 0
    private var bytesReceived: Long = 0
    private var bytesSent: Long = 0

    // Session recording
    var sessionRecorder: io.github.tabssh.terminal.recording.SessionRecorder? = null

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
                _connectionState.value = ConnectionState.DISCONNECTED
                updateTitleWithStatus(ConnectionState.DISCONNECTED)
                Logger.i("SSHTab", "Terminal disconnected for ${profile.getDisplayName()}")
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
     * Connect this tab's terminal to the SSH connection
     */
    suspend fun connect(sshConnection: SSHConnection): Boolean {
        return try {
            Logger.i("SSHTab", "=== CONNECTING TAB TERMINAL for ${profile.getDisplayName()} ===")
            connection = sshConnection

            // Launch coroutine to observe connection state
            connectionScope.launch {
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
                // callback. We forward to SSHConnection.resizePty so the
                // remote shell receives SIGWINCH and reflows long lines.
                // Without this, opening the soft keyboard makes the local
                // viewport 16 rows but the remote keeps thinking it's 31
                // rows tall and full-screen apps (vim, htop, less) draw
                // off-screen.
                termuxBridge.onResizeCallback = { cols, rows ->
                    sshConnection.resizePty(cols, rows)
                }

                Logger.i("SSHTab", "=== TERMINAL WIRED TO SSH SUCCESSFULLY for ${profile.getDisplayName()} ===")
                true
            } else {
                Logger.e("SSHTab", "CRITICAL: Failed to open shell channel for ${profile.getDisplayName()}")
                false
            }

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
            Logger.i("SSHTab", "=== CONNECTING MOSH TAB for ${profile.getDisplayName()} ($host:$port) ===")
            _connectionState.value = ConnectionState.CONNECTING
            val session = io.github.tabssh.protocols.mosh.MoshNativeClient.spawn(
                context, host, port, moshKeyBase64
            )
            moshSession = session
            termuxBridge.connect(session.input, session.output)
            _connectionState.value = ConnectionState.CONNECTED
            updateTitleWithStatus(ConnectionState.CONNECTED)
            Logger.i("SSHTab", "=== MOSH TAB WIRED for ${profile.getDisplayName()} ===")
            true
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

        termuxBridge.disconnect()
        connection = null
        try { telnetConnection?.disconnect() } catch (_: Exception) {}
        telnetConnection = null
        try { moshSession?.close() } catch (_: Exception) {}
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

    // Legacy compatibility - provide terminal property that aliases to termuxBridge
    @Deprecated("Use termuxBridge directly", ReplaceWith("termuxBridge"))
    val terminal: TermuxBridge get() = termuxBridge
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
