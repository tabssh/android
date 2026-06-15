package io.github.tabssh.terminal.emulator

import android.os.Handler
import android.os.Looper
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
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
import java.nio.charset.Charset

/**
 * VT100/ANSI Terminal Emulator
 * Processes terminal escape sequences and maintains terminal state
 * Uses ANSIParser for full ANSI/VT100 escape sequence handling
 */
class TerminalEmulator(private val buffer: TerminalBuffer) {

    companion object {
        private const val READ_BUFFER_SIZE = 4096
        private const val PASTE_CHUNK_SIZE = 4096
        private const val BRACKETED_PASTE_START = "[200~"
        private const val BRACKETED_PASTE_END = "[201~"
    }

    // ANSI Parser for proper escape sequence handling
    private val ansiParser = ANSIParser(buffer)

    private var currentCharset = Charset.forName("UTF-8")
    private var _terminalType = "xterm-256color"

    // State tracking
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Listeners — CopyOnWriteArrayList because notifyListeners fires from the
    // read coroutine on Dispatchers.IO while add/remove happen on UI.
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<TerminalListener>()

    // I/O streams
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Process input data from SSH connection
     * Uses ANSIParser for full ANSI/VT100 escape sequence handling
     */
    fun processInput(data: ByteArray) {
        // Use ANSIParser for proper escape sequence handling
        ansiParser.processInput(data)
    }

    /**
     * Send text to terminal (sends to SSH output stream)
     */
    fun sendText(text: String) {
        outputStream?.let { stream ->
            try {
                val bytes = text.toByteArray(currentCharset)
                stream.write(bytes)
                stream.flush()
                listeners.forEach { it.onDataSent(bytes) }
                Logger.d("TerminalEmulator", "Sent ${bytes.size} bytes to SSH")
            } catch (e: Exception) {
                Logger.e("TerminalEmulator", "Error sending text to SSH", e)
                listeners.forEach { it.onTerminalError(e) }
            }
        } ?: run {
            Logger.w("TerminalEmulator", "Cannot send text - output stream not connected")
        }
    }

    /**
     * Send clipboard text to the remote, applying bracketed paste mode markers
     * (ESC[200~ / ESC[201~) when the remote has enabled ?2004, and chunking
     * large payloads so a single 2 MB write never stalls for a whole round-trip.
     * Line endings are normalised: CRLF and bare LF both become CR.
     */
    fun pasteText(text: String) {
        val stream = outputStream ?: run {
            Logger.w("TerminalEmulator", "Cannot paste - output stream not connected")
            return
        }
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = buffer.isBracketedPasteModeActive()
        try {
            if (bracketed) stream.write("$BRACKETED_PASTE_START".toByteArray(currentCharset))
            var offset = 0
            while (offset < normalized.length) {
                val end = minOf(offset + PASTE_CHUNK_SIZE, normalized.length)
                val bytes = normalized.substring(offset, end).toByteArray(currentCharset)
                stream.write(bytes)
                stream.flush()
                offset = end
            }
            if (bracketed) {
                stream.write("$BRACKETED_PASTE_END".toByteArray(currentCharset))
                stream.flush()
            }
            Logger.d("TerminalEmulator", "Pasted ${normalized.length} chars (bracketed=$bracketed)")
        } catch (e: Exception) {
            Logger.e("TerminalEmulator", "Error pasting text", e)
            listeners.forEach { it.onTerminalError(e) }
        }
    }

    /**
     * Resize terminal
     */
    fun resize(newRows: Int, newCols: Int) {
        buffer.resize(newRows, newCols)
    }

    /**
     * Clear terminal screen
     */
    fun clearScreen() {
        buffer.clear()
    }

    /**
     * Get screen content as string.
     * Rows that soft-wrapped (auto-wrap at column boundary) are joined to the
     * next row without a newline so that the logical line is reconstructed
     * correctly.  Only rows that end with a hard newline (or the last row)
     * receive a '\n'.
     */
    fun getScreenContent(): String {
        val sb = StringBuilder()
        val rows = getRows()
        for (row in 0 until rows) {
            val line = buffer.getLine(row) ?: continue
            for (cell in line) {
                sb.append(cell.char)
            }
            // Only append a newline when the row was NOT soft-wrapped into the next
            if (!buffer.isRowWrapped(row) && row < rows - 1) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Get scrollback content
     */
    fun getScrollbackContent(): String {
        return buffer.getScrollbackContent()
    }

    /**
     * Get the terminal buffer
     */
    fun getBuffer(): TerminalBuffer = buffer

    /**
     * Set encoding
     */
    fun setEncoding(encoding: String) {
        currentCharset = Charset.forName(encoding)
    }

    /**
     * Set terminal type
     */
    fun setTerminalType(type: String) {
        _terminalType = type
        Logger.d("TerminalEmulator", "Terminal type set to: $type")
    }

    /**
     * Get terminal type
     */
    fun getTerminalType(): String = _terminalType

    /**
     * Get number of columns
     */
    fun getCols(): Int = buffer.getCols()

    /**
     * Get number of rows
     */
    fun getRows(): Int = buffer.getRows()

    /**
     * Connect terminal to input/output streams
     */
    fun connect(inputStream: InputStream, outputStream: OutputStream) {
        // Tear down any existing connection before wiring new streams
        disconnect()

        this.inputStream = inputStream
        this.outputStream = outputStream
        _isActive.value = true

        Logger.i("TerminalEmulator", "Connecting to I/O streams")

        // Start reading from SSH in background coroutine
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val activeInput = this@TerminalEmulator.inputStream
            if (activeInput == null) {
                Logger.w("TerminalEmulator", "Input stream missing during connect()")
                return@launch
            }

            try {
                val readBuffer = ByteArray(READ_BUFFER_SIZE)
                Logger.d("TerminalEmulator", "Started reading from SSH input stream")

                while (isActive) {
                    val bytesRead = activeInput.read(readBuffer)

                    if (bytesRead == 0) {
                        continue
                    }

                    if (bytesRead < 0) {
                        Logger.i("TerminalEmulator", "SSH input stream closed (end of stream)")
                        break
                    }

                    val data = readBuffer.copyOf(bytesRead)
                    runOnMain {
                        processInput(data)
                        listeners.forEach { it.onDataReceived(data) }
                    }

                    Logger.d("TerminalEmulator", "Received and processed ${bytesRead} bytes from SSH")
                }
            } catch (e: CancellationException) {
                Logger.d("TerminalEmulator", "SSH read loop cancelled")
            } catch (e: Exception) {
                Logger.e("TerminalEmulator", "Error reading from SSH input stream", e)
                runOnMain {
                    listeners.forEach { it.onTerminalError(e) }
                }
            } finally {
                if (_isActive.value) {
                    _isActive.value = false
                    runOnMain {
                        listeners.forEach { it.onTerminalDisconnected() }
                    }
                }

                closeStreams()
                this@TerminalEmulator.readJob = null
                Logger.d("TerminalEmulator", "SSH read loop terminated")
            }
        }

        // Notify listeners that terminal is connected
        runOnMain {
            listeners.forEach { it.onTerminalConnected() }
        }
        Logger.i("TerminalEmulator", "Terminal connected to I/O streams successfully")
    }

    /**
     * Attach/replace the output stream without resetting input
     */
    fun attachOutputStream(stream: OutputStream) {
        outputStream = stream
        Logger.d("TerminalEmulator", "Output stream updated")
    }

    /**
     * Disconnect terminal streams
     */
    fun disconnect() {
        Logger.i("TerminalEmulator", "Disconnecting from I/O streams")

        val wasConnected = _isActive.value || readJob != null || inputStream != null || outputStream != null
        _isActive.value = false

        // Cancel read job
        readJob?.cancel()
        readJob = null

        closeStreams()

        // Notify listeners
        if (wasConnected) {
            runOnMain {
                listeners.forEach { it.onTerminalDisconnected() }
            }
        }

        Logger.d("TerminalEmulator", "Disconnected from I/O streams")
    }

    /**
     * Add listener
     */
    fun addListener(listener: TerminalListener) {
        listeners.add(listener)
    }

    /**
     * Remove listener
     */
    fun removeListener(listener: TerminalListener) {
        listeners.remove(listener)
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        disconnect()
        listeners.clear()
    }

    private fun closeStreams() {
        try {
            inputStream?.close()
        } catch (e: Exception) {
            Logger.w("TerminalEmulator", "Failed to close input stream: ${e.localizedMessage}")
        } finally {
            inputStream = null
        }

        try {
            outputStream?.close()
        } catch (e: Exception) {
            Logger.w("TerminalEmulator", "Failed to close output stream: ${e.localizedMessage}")
        } finally {
            outputStream = null
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

/**
 * Terminal event listener interface
 */
interface TerminalListener {
    fun onDataReceived(data: ByteArray)
    fun onDataSent(data: ByteArray)
    fun onTitleChanged(title: String)
    fun onTerminalError(error: Exception)
    fun onTerminalConnected()
    fun onTerminalDisconnected()
}
