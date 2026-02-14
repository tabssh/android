package io.github.tabssh.terminal

import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * Bridge between SSH streams and Termux terminal emulator.
 *
 * This class wraps Termux's TerminalEmulator to provide proper VT100/ANSI
 * terminal emulation for SSH connections, replacing the custom basic emulator.
 *
 * Data flow:
 * - SSH InputStream → read loop → emulator.append() → screen buffer updates
 * - User input → write() → SSH OutputStream
 */
class TermuxBridge(
    private val columns: Int = 80,
    private val rows: Int = 24,
    private val transcriptRows: Int = 2000
) {
    companion object {
        private const val TAG = "TermuxBridge"
        private const val READ_BUFFER_SIZE = 8192
    }

    // Termux emulator instance
    private var emulator: TerminalEmulator? = null

    // I/O streams from SSH
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Read loop job
    private var readJob: Job? = null

    // State
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Listeners
    private val listeners = mutableListOf<TermuxBridgeListener>()

    // Main thread handler for callbacks
    private val mainHandler = Handler(Looper.getMainLooper())

    // Terminal dimensions (can be updated)
    private var currentColumns = columns
    private var currentRows = rows

    /**
     * TerminalOutput implementation - handles data going TO the SSH server
     */
    private val terminalOutput = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            try {
                outputStream?.let { stream ->
                    stream.write(data, offset, count)
                    stream.flush()
                    Logger.d(TAG, "Sent $count bytes to SSH")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error writing to SSH", e)
                notifyError(e)
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            Logger.d(TAG, "Title changed: $oldTitle -> $newTitle")
            runOnMain {
                listeners.forEach { it.onTitleChanged(newTitle ?: "") }
            }
        }

        override fun onCopyTextToClipboard(text: String?) {
            text?.let {
                runOnMain {
                    listeners.forEach { listener -> listener.onCopyToClipboard(it) }
                }
            }
        }

        override fun onPasteTextFromClipboard() {
            runOnMain {
                listeners.forEach { it.onPasteFromClipboard() }
            }
        }

        override fun onBell() {
            runOnMain {
                listeners.forEach { it.onBell() }
            }
        }

        override fun onColorsChanged() {
            runOnMain {
                listeners.forEach { it.onColorsChanged() }
            }
        }
    }

    /**
     * TerminalSessionClient implementation - handles emulator events
     * Note: We don't have a TerminalSession, so session parameter will be handled carefully
     */
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            runOnMain {
                listeners.forEach { it.onScreenChanged() }
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            // Handled by TerminalOutput.titleChanged()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            Logger.i(TAG, "Session finished")
            runOnMain {
                listeners.forEach { it.onDisconnected() }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            // Handled by TerminalOutput
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            // Handled by TerminalOutput
        }

        override fun onBell(session: TerminalSession) {
            // Handled by TerminalOutput
        }

        override fun onColorsChanged(session: TerminalSession) {
            // Handled by TerminalOutput
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            runOnMain {
                listeners.forEach { it.onCursorStateChanged(state) }
            }
        }

        override fun getTerminalCursorStyle(): Int {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }

        // Note: setTerminalShellPid may not exist in all Termux versions
        // It's not needed for SSH-based terminals anyway

        // Logging methods
        override fun logError(tag: String?, message: String?) {
            Logger.e(tag ?: TAG, message ?: "Unknown error")
        }

        override fun logWarn(tag: String?, message: String?) {
            Logger.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Logger.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logVerbose(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Logger.e(tag ?: TAG, message ?: "", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Logger.e(tag ?: TAG, "Stack trace", e)
        }
    }

    /**
     * Initialize the Termux emulator
     */
    fun initialize() {
        Logger.i(TAG, "Initializing Termux emulator ${currentColumns}x${currentRows}")

        // Constructor: (TerminalOutput, columns, rows, transcriptRows, TerminalSessionClient)
        emulator = TerminalEmulator(
            terminalOutput,
            currentColumns,
            currentRows,
            transcriptRows,
            sessionClient
        )

        Logger.i(TAG, "Termux emulator initialized")
    }

    /**
     * Connect to SSH streams and start processing
     */
    fun connect(sshInputStream: InputStream, sshOutputStream: OutputStream) {
        Logger.i(TAG, "Connecting to SSH streams")

        // Ensure emulator is initialized
        if (emulator == null) {
            initialize()
        }

        // Store streams
        this.inputStream = sshInputStream
        this.outputStream = sshOutputStream
        _isConnected.value = true

        // Start read loop
        startReadLoop()

        // Notify listeners
        runOnMain {
            listeners.forEach { it.onConnected() }
        }

        Logger.i(TAG, "Connected to SSH streams")
    }

    /**
     * Start the background read loop for SSH input
     */
    private fun startReadLoop() {
        readJob?.cancel()

        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)

            Logger.d(TAG, "Read loop started")

            try {
                while (isActive && _isConnected.value) {
                    val stream = inputStream ?: break
                    val bytesRead = stream.read(buffer)

                    if (bytesRead < 0) {
                        Logger.i(TAG, "SSH stream closed (EOF)")
                        break
                    }

                    if (bytesRead > 0) {
                        // Feed data to Termux emulator
                        emulator?.append(buffer, bytesRead)

                        // Notify screen changed (emulator may not call client for every change)
                        runOnMain {
                            listeners.forEach { it.onScreenChanged() }
                        }

                        Logger.d(TAG, "Processed $bytesRead bytes from SSH")
                    }
                }
            } catch (e: Exception) {
                if (_isConnected.value) {
                    Logger.e(TAG, "Error reading from SSH", e)
                    notifyError(e)
                }
            } finally {
                Logger.d(TAG, "Read loop ended")
                if (_isConnected.value) {
                    disconnect()
                }
            }
        }
    }

    /**
     * Write data to SSH (user input)
     */
    fun write(data: ByteArray) {
        terminalOutput.write(data, 0, data.size)
    }

    /**
     * Write string to SSH
     */
    fun writeString(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Send text to terminal (alias for writeString, matches old API)
     */
    fun sendText(text: String) {
        writeString(text)
    }

    /**
     * Send key press to terminal
     * Converts key codes to appropriate terminal escape sequences
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        val sequence = when {
            isCtrl -> {
                // Control key sequences
                when (keyCode) {
                    in 65..90 -> byteArrayOf((keyCode - 64).toByte()) // Ctrl+A-Z = 1-26
                    else -> byteArrayOf()
                }
            }
            isAlt -> {
                // Alt key sequences (ESC + char)
                byteArrayOf(27, keyCode.toByte())
            }
            else -> {
                // Normal keys - handle special keys
                when (keyCode) {
                    // Arrow keys
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray()
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray()
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray()
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray()
                    // Home/End
                    android.view.KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H".toByteArray()
                    android.view.KeyEvent.KEYCODE_MOVE_END -> "\u001b[F".toByteArray()
                    // Page Up/Down
                    android.view.KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~".toByteArray()
                    android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~".toByteArray()
                    // Delete/Insert
                    android.view.KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~".toByteArray()
                    android.view.KeyEvent.KEYCODE_INSERT -> "\u001b[2~".toByteArray()
                    // Enter, Tab, Backspace, Escape
                    android.view.KeyEvent.KEYCODE_ENTER -> byteArrayOf(13)
                    android.view.KeyEvent.KEYCODE_TAB -> byteArrayOf(9)
                    android.view.KeyEvent.KEYCODE_DEL -> byteArrayOf(127)
                    android.view.KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(27)
                    // Function keys F1-F12
                    android.view.KeyEvent.KEYCODE_F1 -> "\u001bOP".toByteArray()
                    android.view.KeyEvent.KEYCODE_F2 -> "\u001bOQ".toByteArray()
                    android.view.KeyEvent.KEYCODE_F3 -> "\u001bOR".toByteArray()
                    android.view.KeyEvent.KEYCODE_F4 -> "\u001bOS".toByteArray()
                    android.view.KeyEvent.KEYCODE_F5 -> "\u001b[15~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F6 -> "\u001b[17~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F7 -> "\u001b[18~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F8 -> "\u001b[19~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F9 -> "\u001b[20~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F10 -> "\u001b[21~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F11 -> "\u001b[23~".toByteArray()
                    android.view.KeyEvent.KEYCODE_F12 -> "\u001b[24~".toByteArray()
                    else -> byteArrayOf()
                }
            }
        }
        if (sequence.isNotEmpty()) {
            write(sequence)
        }
    }

    /**
     * Clear terminal screen (send ANSI clear sequence)
     */
    fun clearScreen() {
        // Send ESC[2J (clear screen) + ESC[H (cursor home)
        writeString("\u001b[2J\u001b[H")
    }

    /**
     * Get screen content as text
     */
    fun getScreenContent(): String {
        val screen = emulator?.screen ?: return ""
        val sb = StringBuilder()
        val rows = currentRows
        val cols = currentColumns

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val char = screen.getSelectedText(col, row, col + 1, row)
                sb.append(char ?: " ")
            }
            if (row < rows - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Get scrollback buffer content as text
     */
    fun getScrollbackContent(): String {
        val screen = emulator?.screen ?: return ""
        // Get transcript (scrollback) - activeTranscriptRows gives us how many rows of history
        return try {
            val transcriptRows = screen.activeTranscriptRows
            if (transcriptRows > 0) {
                screen.getSelectedText(0, -transcriptRows, currentColumns, -1) ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error getting scrollback content", e)
            ""
        }
    }

    /**
     * Get the terminal screen buffer for rendering
     */
    fun getScreen(): TerminalBuffer? {
        return emulator?.screen
    }

    /**
     * Get cursor row position
     */
    fun getCursorRow(): Int {
        return emulator?.cursorRow ?: 0
    }

    /**
     * Get cursor column position
     */
    fun getCursorCol(): Int {
        return emulator?.cursorCol ?: 0
    }

    /**
     * Check if cursor should be visible
     */
    fun isCursorVisible(): Boolean {
        return emulator?.shouldCursorBeVisible() ?: false
    }

    /**
     * Get number of columns
     */
    fun getColumns(): Int = currentColumns

    /**
     * Get number of columns (alias for compatibility)
     */
    fun getCols(): Int = currentColumns

    /**
     * Get number of rows
     */
    fun getRows(): Int = currentRows

    /**
     * Get buffer (returns Termux TerminalBuffer, for compatibility)
     */
    fun getBuffer(): com.termux.terminal.TerminalBuffer? = emulator?.screen

    /**
     * Resize the terminal
     */
    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns != currentColumns || newRows != currentRows) {
            currentColumns = newColumns
            currentRows = newRows
            emulator?.resize(newColumns, newRows)
            Logger.d(TAG, "Resized to ${newColumns}x${newRows}")
        }
    }

    /**
     * Disconnect from SSH streams
     */
    fun disconnect() {
        Logger.i(TAG, "Disconnecting")

        _isConnected.value = false
        readJob?.cancel()
        readJob = null

        try {
            inputStream?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing input stream", e)
        }
        inputStream = null

        try {
            outputStream?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing output stream", e)
        }
        outputStream = null

        runOnMain {
            listeners.forEach { it.onDisconnected() }
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        emulator = null
        listeners.clear()
    }

    /**
     * Add event listener
     */
    fun addListener(listener: TermuxBridgeListener) {
        listeners.add(listener)
    }

    /**
     * Remove event listener
     */
    fun removeListener(listener: TermuxBridgeListener) {
        listeners.remove(listener)
    }

    private fun notifyError(e: Exception) {
        runOnMain {
            listeners.forEach { it.onError(e) }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Get the raw emulator for advanced operations
     */
    fun getEmulator(): TerminalEmulator? = emulator
}

/**
 * Listener interface for TermuxBridge events
 */
interface TermuxBridgeListener {
    fun onConnected()
    fun onDisconnected()
    fun onScreenChanged()
    fun onTitleChanged(title: String)
    fun onBell()
    fun onColorsChanged()
    fun onCursorStateChanged(visible: Boolean)
    fun onCopyToClipboard(text: String)
    fun onPasteFromClipboard()
    fun onError(e: Exception)
}
