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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val transcriptRows: Int = 2000,
    private val cursorStyle: Int = 2 // 0=block, 1=underline, 2=bar (I-beam default)
) {
    companion object {
        private const val TAG = "TermuxBridge"
        private const val READ_BUFFER_SIZE = 8192

        /**
         * When false (default), keystroke writes log only `Sent N bytes to SSH`.
         * When true, the first ≤16 bytes are also dumped via `toBriefHex()`.
         *
         * Off by default because user keystrokes flow through this path —
         * including sudo passwords, ssh passphrases entered via `read -s`,
         * and anything else typed into the terminal. The byte-content payload
         * is useful for diagnosing protocol-level disconnects (it caught the
         * GCM-tag race that produced `ssh_dispatch_run_fatal: message
         * authentication code incorrect` server-side) but should be opt-in.
         *
         * Toggled by `LoggingSettingsFragment` from the
         * `log_keystroke_bytes` preference.
         */
        @Volatile
        @JvmStatic
        var logKeystrokeBytes: Boolean = false
    }

    // Termux emulator instance
    private var emulator: TerminalEmulator? = null

    // Wave 9.2 B-12 — when non-null, mosh-client is running inside a
    // PTY-backed TerminalSession. Writes are routed through the session
    // instead of outputStream; resize calls updateSize() on the PTY.
    private var moshSession: TerminalSession? = null

    // I/O streams from SSH
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /** Wave 2.7 — public read of the SSH outputStream so a sibling bridge can
     *  fan-out broadcast input to it. May be null until [connect] runs. */
    fun peerOutputStream(): OutputStream? = outputStream

    /** Wave 2.7 — when non-empty, every keystroke written to our SSH stream is
     *  also written to each of these. The owning Activity manages the list. */
    @Volatile
    var broadcastTargets: List<OutputStream> = emptyList()

    // Read loop job
    private var readJob: Job? = null

    // Coroutine scope for write operations (IO thread)
    private val writeScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Serializes ALL writes to the SSH OutputStream. JSch's
     * ChannelOutputStream maintains an internal buffer that is NOT safe
     * against concurrent `write()` calls — concurrent appends race on
     * the buffer index AND on the GCM cipher state shared with the
     * surrounding session, producing on-the-wire ciphertext whose
     * authentication tag fails server-side. The server then closes the
     * TCP stream with `ssh_dispatch_run_fatal: message authentication
     * code incorrect` (verified via /var/log/secure on the user's
     * AlmaLinux 9.7 servers — see commit message).
     *
     * The race window opens whenever two `write()` calls land within
     * the same flush window, which on Dispatchers.IO (a multi-thread
     * pool) is trivial — the broadcast-input fan-out, post-connect
     * script writes, and a fast typist plus the macro recorder are
     * all routine producers. Funnelling every write through this
     * Mutex closes the window without changing the public API or
     * adding a dedicated thread.
     */
    private val writeLock = Mutex()

    // Issue #173 — recordable macros. When non-null, every byte heading
    // out to SSH is also appended to this buffer so the activity can
    // save it as a Macro and replay verbatim later.
    @Volatile private var macroRecording: java.io.ByteArrayOutputStream? = null

    /** Begin capturing outbound bytes. No-op if already recording. */
    fun startMacroRecording() {
        if (macroRecording == null) macroRecording = java.io.ByteArrayOutputStream()
    }

    /** Stop capturing and return the recorded bytes (empty if not recording). */
    fun stopMacroRecording(): ByteArray {
        val buf = macroRecording ?: return ByteArray(0)
        macroRecording = null
        return buf.toByteArray()
    }

    fun isRecordingMacro(): Boolean = macroRecording != null

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
            // Copy data to avoid race conditions (input may be reused)
            val dataCopy = data.copyOfRange(offset, offset + count)
            // Issue #173 — if a macro recording is active, append the
            // bytes BEFORE we hand them off to writeScope. The recorder
            // is intentionally byte-exact (no decoding) so escape codes
            // and paste payloads round-trip on replay.
            macroRecording?.write(dataCopy)
            // Run on IO thread to avoid NetworkOnMainThreadException.
            // EVERY write (own stream + broadcast targets) is wrapped in
            // `writeLock.withLock` so JSch's per-channel cipher state
            // never sees concurrent append+flush from two coroutines —
            // the GCM tag race that was producing
            // `ssh_dispatch_run_fatal: message authentication code
            // incorrect` server-side.
            writeScope.launch {
                writeLock.withLock {
                    try {
                        outputStream?.let { stream ->
                            stream.write(dataCopy)
                            stream.flush()
                            if (logKeystrokeBytes) {
                                Logger.d(
                                    TAG,
                                    "Sent ${dataCopy.size} bytes to SSH (bytes=${dataCopy.toBriefHex()})"
                                )
                            } else {
                                Logger.d(TAG, "Sent ${dataCopy.size} bytes to SSH")
                            }
                        }
                        // Wave 2.7 — broadcast input. After our own SSH write
                        // succeeds, fan the same bytes out to every registered
                        // target (other tabs). Still inside the lock so the
                        // own-stream write and the broadcast writes can't
                        // interleave on JSch's session lock.
                        val targets = broadcastTargets
                        if (targets.isNotEmpty()) {
                            for (t in targets) {
                                try {
                                    t.write(dataCopy)
                                    t.flush()
                                } catch (e: Exception) {
                                    Logger.w(TAG, "Broadcast to peer stream failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to SSH", e)
                        runOnMain { notifyError(e) }
                    }
                }
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
            // Termux parses OSC 0/1/2 → title; surface it to listeners
            // so the foreground service can rebuild the per-host
            // notification ("Connected to {host}:{title}").
            val newTitle = try { changedSession.title ?: "" } catch (_: Exception) { "" }
            runOnMain {
                listeners.forEach { it.onTitleChanged(newTitle) }
            }
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
            return cursorStyle // Use configured style (default: I-beam)
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
        Logger.i(TAG, "=== CONNECTING TO SSH STREAMS ===")
        Logger.i(TAG, "InputStream: $sshInputStream")
        Logger.i(TAG, "OutputStream: $sshOutputStream")

        // Ensure emulator is initialized
        if (emulator == null) {
            Logger.i(TAG, "Emulator was null, initializing...")
            initialize()
        }
        Logger.i(TAG, "Emulator ready: ${emulator != null}, size: ${currentColumns}x${currentRows}")

        // Store streams
        this.inputStream = sshInputStream
        this.outputStream = sshOutputStream
        _isConnected.value = true

        // Start read loop
        Logger.i(TAG, "Starting read loop...")
        startReadLoop()

        // Notify listeners
        Logger.i(TAG, "Notifying ${listeners.size} listeners of connection")
        runOnMain {
            listeners.forEach { it.onConnected() }
        }

        Logger.i(TAG, "=== SSH STREAMS CONNECTED SUCCESSFULLY ===")
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
                        val em = emulator
                        if (em != null) {
                            em.append(buffer, bytesRead)
                            Logger.i(TAG, "Fed $bytesRead bytes to emulator, cursor at (${em.cursorRow},${em.cursorCol})")
                        } else {
                            Logger.e(TAG, "EMULATOR IS NULL - cannot process $bytesRead bytes!")
                        }

                        // Notify screen changed (emulator may not call client for every change)
                        runOnMain {
                            Logger.d(TAG, "Notifying ${listeners.size} listeners of screen change")
                            listeners.forEach { it.onScreenChanged() }
                        }
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
     * Write data to SSH (user input), or to the mosh-client PTY when in
     * mosh mode. The mosh path bypasses the SSH outputStream entirely.
     */
    fun write(data: ByteArray) {
        val ms = moshSession
        if (ms != null) {
            writeScope.launch {
                writeLock.withLock {
                    try {
                        ms.write(data, 0, data.size)
                        Logger.d(TAG, "Sent ${data.size} bytes to mosh-client PTY")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to mosh session", e)
                    }
                }
            }
        } else {
            terminalOutput.write(data, 0, data.size)
        }
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
     * Get cursor style
     * @return 0=block, 1=underline, 2=bar (I-beam)
     */
    fun getCursorStyle(): Int = cursorStyle

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
     * Inject bytes directly into the LOCAL emulator without sending to the
     * remote shell. Used for setting DECSET / DECRST modes (auto-wrap,
     * cursor visibility, alt-screen, …) on the local renderer based on
     * user preferences without involving the remote.
     *
     * Safe to call from any thread; `append` is internally synchronized
     * on Termux's screen.
     */
    fun injectLocally(bytes: ByteArray) {
        try {
            emulator?.append(bytes, bytes.size)
        } catch (e: Exception) {
            Logger.w(TAG, "injectLocally failed: ${e.message}")
        }
    }

    /**
     * Resize the terminal
     */
    // Resize callback for VM console to forward to WebSocket
    var onResizeCallback: ((cols: Int, rows: Int) -> Unit)? = null

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns != currentColumns || newRows != currentRows) {
            currentColumns = newColumns
            currentRows = newRows
            Logger.d(TAG, "Resized to ${newColumns}x${newRows}")

            val ms = moshSession
            if (ms != null) {
                // In mosh mode: updateSize() resizes both the TerminalEmulator
                // and the PTY (via ioctl TIOCSWINSZ → SIGWINCH to mosh-client).
                // No SSH resize callback is needed.
                try { ms.updateSize(newColumns, newRows) } catch (_: Exception) {}
            } else {
                emulator?.resize(newColumns, newRows)

                // SSH-side window-change MUST share the writeLock with the
                // keystroke writes — both produce packets on the same JSch
                // session and a concurrent send corrupts the GCM cipher
                // state, ending in `ssh_dispatch_run_fatal: message
                // authentication code incorrect` server-side and an EOF
                // back to us. Symptom: open keyboard (resize fires) →
                // type a char → server EOF within 1s.
                //
                // The callback usually invokes `Channel.setPtySize`
                // synchronously, which JSch will route through its own
                // session writer. By acquiring `writeLock` first we
                // guarantee no in-flight keystroke is mid-encrypt when
                // the resize packet goes out, and vice versa.
                //
                // We launch on `writeScope` (Dispatchers.IO) for the same
                // reason keystroke writes do — `setPtySize` blocks on
                // socket I/O and we MUST NOT do that from the UI thread.
                val cb = onResizeCallback
                if (cb != null) {
                    writeScope.launch {
                        writeLock.withLock {
                            try {
                                cb(newColumns, newRows)
                            } catch (e: Exception) {
                                Logger.w(TAG, "Resize callback failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Disconnect from SSH streams.
     *
     * Idempotent (Issue #51): the VM-console teardown path used to call
     * disconnect() three times (read-loop finally, console-manager
     * disconnect, cleanup), each one firing onDisconnected() on every
     * registered listener — producing the user-visible "Terminal
     * disconnected" toast triplicate. We now gate on the previous state:
     * fire the listener callbacks ONLY on the first transition from
     * connected to disconnected.
     */
    fun disconnect() {
        val wasConnected = _isConnected.value
        if (!wasConnected && inputStream == null && outputStream == null && readJob == null && moshSession == null) {
            // Already torn down — nothing to do, don't re-fire listeners.
            return
        }

        Logger.i(TAG, "Disconnecting")

        _isConnected.value = false
        readJob?.cancel()
        readJob = null
        // Don't cancel writeScope's Job — it's a `val` shared across the
        // bridge's lifetime, and cancelling kills it permanently. Pending
        // writes drop on the floor when outputStream becomes null below.

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

        // Mosh PTY session — finishIfRunning() sends SIGHUP to the child
        // process and closes the master PTY fd.
        try { moshSession?.finishIfRunning() } catch (_: Exception) {}
        moshSession = null

        if (wasConnected) {
            runOnMain {
                listeners.forEach { it.onDisconnected() }
            }
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
     * Wave 9.2 B-12 — Connect via a PTY-backed TerminalSession for
     * mosh-client. mosh-client calls tcgetattr() on stdin at startup and
     * exits immediately with ENOTTY when stdin is a plain pipe (the old
     * ProcessBuilder path). TerminalSession uses JNI forkpty() so the child
     * process gets a real TTY as its controlling terminal.
     *
     * The bridge delegates emulation to the session's own TerminalEmulator so
     * getBuffer()/getEmulator() return live mosh-client screen state for the
     * TerminalView. All screen callbacks continue to route through
     * [sessionClient] → [TermuxBridgeListener] as normal.
     *
     * @return true if the mosh-client binary is bundled for this ABI and
     *         the PTY was created, false if no binary is available.
     */
    fun connectMoshClient(
        context: android.content.Context,
        host: String,
        port: Int,
        moshKeyBase64: String
    ): Boolean {
        val binary = io.github.tabssh.protocols.mosh.MoshNativeClient.resolveBinary(context)
            ?: run {
                Logger.w(TAG, "mosh-client binary not bundled — cannot create PTY session")
                return false
            }

        // Cancel any existing stream-based connection first.
        readJob?.cancel()
        readJob = null
        inputStream = null
        outputStream = null

        val envList = arrayOf(
            "MOSH_KEY=$moshKeyBase64",
            "TERM=xterm-256color",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}"
        )

        Logger.i(TAG, "Creating PTY TerminalSession for mosh-client $host:$port")
        val session = TerminalSession(
            binary.absolutePath,
            context.filesDir.absolutePath,
            // argv[0] = program name, argv[1..] = arguments passed to execvp
            arrayOf(binary.absolutePath, host, port.toString()),
            envList,
            transcriptRows,
            sessionClient   // wire our callbacks immediately — no race
        )

        connectSession(session)
        return true
    }

    /**
     * Wire a [TerminalSession] (PTY-backed) as this bridge's active terminal.
     * Initializes the emulator if the constructor hasn't already done so,
     * then replaces [emulator] with the session's own emulator so rendering
     * picks up the PTY output.
     */
    private fun connectSession(session: TerminalSession) {
        // If the TerminalSession constructor deferred initialization, start it.
        // If it already auto-initialized (constructor called initializeEmulator),
        // skip to avoid a second fork; just resize to current dimensions.
        if (session.getEmulator() == null) {
            session.initializeEmulator(currentColumns, currentRows)
        } else {
            try { session.updateSize(currentColumns, currentRows) } catch (_: Exception) {}
        }

        // Delegate rendering to the session's TerminalEmulator.
        emulator = session.getEmulator()
        moshSession = session
        _isConnected.value = true

        runOnMain {
            listeners.forEach { it.onConnected() }
        }
        Logger.i(TAG, "Connected via PTY TerminalSession (mosh-client)")
    }

    /**
     * Returns true when the mosh-client PTY session is still running.
     * Used by SSHTab to distinguish a stale SSH-teardown onDisconnected
     * (fired during handoff while mosh is alive) from a real mosh death
     * (process has already exited, isRunning() = false).
     */
    fun isMoshSessionAlive(): Boolean = moshSession?.isRunning() == true

    /**
     * Get the raw emulator for advanced operations
     */
    fun getEmulator(): TerminalEmulator? = emulator
}

/**
 * Diagnostic helper — render the first ≤16 bytes as `decimal,decimal,…`,
 * appending `…` when truncated. Used to make `Sent N bytes to SSH` log
 * lines self-describing so future "did this packet kill the session?"
 * triage doesn't require a tcpdump. Decimal (not hex) so the output
 * matches the existing `sequence=[27, 91, 65]` style on the custom-key
 * path.
 */
private fun ByteArray.toBriefHex(): String {
    if (isEmpty()) return "[]"
    val limit = 16
    val head = take(limit).joinToString(",") { (it.toInt() and 0xFF).toString() }
    return if (size > limit) "[$head,…(${size - limit} more)]" else "[$head]"
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
