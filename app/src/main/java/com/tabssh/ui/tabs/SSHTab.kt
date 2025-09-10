package io.github.tabssh.ui.tabs

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.terminal.emulator.TerminalEmulator
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Represents a single SSH tab with its connection, terminal, and UI state
 * This is the core of TabSSH's tabbed interface innovation
 */
class SSHTab(
    val profile: ConnectionProfile,
    val terminal: TerminalEmulator
) {
    val tabId: String = UUID.randomUUID().toString()
    
    // Connection
    private var connection: SSHConnection? = null
    
    // Tab state
    private val _title = MutableStateFlow(profile.name)
    val title: StateFlow<String> = _title.asStateFlow()
    
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
    
    init {
        Logger.d("SSHTab", "Created tab ${profile.getDisplayName()}")
        
        // Set up terminal listener to track activity
        setupTerminalListener()
        
        // Set initial title from terminal if available
        terminal.title.value.takeIf { it != "Terminal" }?.let { terminalTitle ->
            _title.value = terminalTitle
        }
    }
    
    private fun setupTerminalListener() {
        terminal.addListener(object : com.tabssh.terminal.emulator.TerminalListener {
            override fun onDataReceived(data: ByteArray) {
                updateActivity()
                bytesReceived += data.size
                
                // Mark as having unread output if tab is not active
                if (!_isActive.value) {
                    _hasUnreadOutput.value = true
                    _unreadLines.value += 1
                }
            }
            
            override fun onDataSent(data: ByteArray) {
                updateActivity()
                bytesSent += data.size
            }
            
            override fun onTitleChanged(newTitle: String) {
                // Update tab title from terminal (e.g., from OSC sequences)
                if (newTitle.isNotBlank()) {
                    _title.value = newTitle
                } else {
                    _title.value = profile.getDisplayName()
                }
                Logger.d("SSHTab", "Tab title changed to: $newTitle")
            }
            
            override fun onTerminalError(error: Exception) {
                _hasError.value = true
                Logger.e("SSHTab", "Terminal error in tab ${profile.getDisplayName()}", error)
            }
            
            override fun onTerminalConnected() {
                sessionStartTime = System.currentTimeMillis()
                _connectionState.value = ConnectionState.CONNECTED
                _hasError.value = false
            }
            
            override fun onTerminalDisconnected() {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }
    
    /**
     * Connect this tab's terminal to the SSH connection
     */
    suspend fun connect(sshConnection: SSHConnection): Boolean {
        return try {
            connection = sshConnection
            
            // Update connection state based on SSH connection state
            sshConnection.connectionState.collect { state ->
                _connectionState.value = state
                if (state == ConnectionState.ERROR) {
                    _hasError.value = true
                }
            }
            
            // Connect terminal to SSH streams
            val shellChannel = sshConnection.openShellChannel()
            if (shellChannel != null) {
                terminal.connect(shellChannel.inputStream, shellChannel.outputStream)
                Logger.i("SSHTab", "Connected tab ${profile.getDisplayName()} to SSH")
                true
            } else {
                Logger.e("SSHTab", "Failed to open shell channel for ${profile.getDisplayName()}")
                false
            }
            
        } catch (e: Exception) {
            Logger.e("SSHTab", "Error connecting tab ${profile.getDisplayName()}", e)
            _hasError.value = true
            false
        }
    }
    
    /**
     * Disconnect this tab
     */
    fun disconnect() {
        Logger.d("SSHTab", "Disconnecting tab ${profile.getDisplayName()}")
        
        terminal.disconnect()
        connection = null
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
            terminalRows = terminal.getRows(),
            terminalCols = terminal.getCols(),
            lastActivity = _lastActivity.value
        )
    }
    
    /**
     * Send text to this tab's terminal
     */
    fun sendText(text: String) {
        terminal.sendText(text)
        updateActivity()
    }
    
    /**
     * Send key press to this tab's terminal
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        terminal.sendKeyPress(keyCode, isCtrl, isAlt, isShift)
        updateActivity()
    }
    
    /**
     * Resize this tab's terminal
     */
    fun resize(rows: Int, cols: Int) {
        terminal.resize(rows, cols)
        Logger.d("SSHTab", "Resized tab ${profile.getDisplayName()} terminal to ${cols}x${rows}")
    }
    
    /**
     * Clear terminal screen
     */
    fun clearScreen() {
        terminal.clearScreen()
    }
    
    /**
     * Get terminal content for sharing/copying
     */
    fun getTerminalContent(): String {
        return terminal.getScreenContent()
    }
    
    /**
     * Get scrollback content
     */
    fun getScrollbackContent(): String {
        return terminal.getScrollbackContent()
    }
    
    /**
     * Check if tab is connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED && terminal.isActive.value
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
        _title.value = profile.getDisplayName()
    }
    
    /**
     * Cleanup tab resources
     */
    fun cleanup() {
        Logger.d("SSHTab", "Cleaning up tab ${profile.getDisplayName()}")
        
        disconnect()
        terminal.cleanup()
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