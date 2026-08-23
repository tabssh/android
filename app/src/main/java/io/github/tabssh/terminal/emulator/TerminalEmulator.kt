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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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

    // Wall-clock time of the last byte read or written, used by TerminalManager to
    // decide which terminals are idle enough to reclaim. Volatile because the read
    // coroutine updates it while the maintenance coroutine reads it.
    @Volatile
    private var lastActivityTime = System.currentTimeMillis()

    // I/O streams
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Single-threaded executor that serialises every write to the SSH
    // output stream (sendText/pasteText). Callers post work here instead of
    // writing directly so that key escapes (page up/down, function keys,
    // bar shortcuts) queued from the UI thread can never interleave with an
    // in-flight composing-text send — ordering is strictly FIFO. This also
    // keeps the network write itself off the calling (UI) thread.
    // Recreated on every connect() since an ExecutorService cannot be
    // restarted once shut down. @Volatile: reassigned from connect()
    // (background dispatcher) while postWrite() reads it from the UI
    // thread — same convention as lastActivityTime above.
    @Volatile
    private var writeExecutor: ExecutorService = newWriteExecutor()

    private fun newWriteExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tabssh-terminal-writer").also { it.isDaemon = true }
        }

    /**
     * Post a write task to the serialised writer thread.
     *
     * Silently drops the task if the executor has already been shut down —
     * this happens when [disconnect]/[cleanup] runs while a send is still in
     * flight from the UI thread. The session is over; dropping is correct
     * and avoids a [RejectedExecutionException] crash.
     */
    private fun postWrite(block: () -> Unit) {
        if (writeExecutor.isShutdown) return
        try {
            writeExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Race between isShutdown check and execute() — session is ending; drop safely.
        }
    }

    /**
     * Shut down [executor], letting any already-queued writes finish in
     * order before the thread dies. Falls back to a hard
     * [ExecutorService.shutdownNow] if draining takes too long, so
     * disconnect/cleanup never blocks indefinitely.
     *
     * Takes the executor explicitly rather than reading the [writeExecutor]
     * field: the read loop's `finally` block calls this asynchronously after
     * [readJob] is cancelled, and by then [connect] may already have
     * reassigned [writeExecutor] to a fresh instance for a new connection —
     * reading the field at that point would shut down the wrong executor.
     *
     * Blocks the caller up to 2s draining queued writes — callers
     * (disconnect/cleanup) must stay on background dispatchers, never
     * Dispatchers.Main.
     */
    private fun shutdownWriteExecutor(executor: ExecutorService) {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Process input data from SSH connection
     * Uses ANSIParser for full ANSI/VT100 escape sequence handling
     */
    fun processInput(data: ByteArray) {
        lastActivityTime = System.currentTimeMillis()
        // Use ANSIParser for proper escape sequence handling
        ansiParser.processInput(data)
    }

    /**
     * Wall-clock timestamp of the most recent terminal activity.
     */
    fun getLastActivityTime(): Long = lastActivityTime

    /**
     * Block until every write queued so far on the writer executor has
     * drained (or [timeoutMs] elapses).
     *
     * Test-support only — production callers must never block the calling
     * (UI) thread on the network write completing, which is exactly what
     * moving writes to [writeExecutor] was meant to avoid. It exists so JVM
     * tests can assert against a fake [OutputStream] deterministically
     * instead of sleeping.
     */
    fun awaitPendingWrites(timeoutMs: Long = 2_000) {
        val latch = java.util.concurrent.CountDownLatch(1)
        postWrite { latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Send text to terminal (sends to SSH output stream)
     */
    fun sendText(text: String) {
        val stream = outputStream ?: run {
            Logger.w("TerminalEmulator", "Cannot send text - output stream not connected")
            return
        }
        postWrite {
            try {
                lastActivityTime = System.currentTimeMillis()
                val bytes = text.toByteArray(currentCharset)
                stream.write(bytes)
                stream.flush()
                listeners.forEach { it.onDataSent(bytes) }
                Logger.d("TerminalEmulator", "Sent ${bytes.size} bytes to SSH")
            } catch (e: Exception) {
                Logger.e("TerminalEmulator", "Error sending text to SSH", e)
                listeners.forEach { it.onTerminalError(e) }
            }
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
        postWrite {
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

        // disconnect() shuts down the previous writer executor; an
        // ExecutorService cannot be restarted, so a fresh one is needed here.
        // Captured into a local so the coroutine's finally block below shuts
        // down exactly this connection's executor, even if a later
        // connect() call reassigns the writeExecutor field first.
        writeExecutor = newWriteExecutor()
        val activeWriteExecutor = writeExecutor

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
                // Only touch shared connection state if this loop is still
                // the CURRENT one. A rapid reconnect calls connect(), which
                // installs a new readJob and new streams, before this dying
                // loop's finally runs (its blocking read() only unblocks once
                // disconnect() closes ITS streams, which can race the new
                // connect()'s stream assignment). Running closeStreams()/
                // nulling readJob/notifying onTerminalDisconnected()
                // unconditionally here would tear down the NEW connection's
                // streams and misreport it as disconnected right after it
                // was established.
                val thisJob = coroutineContext[Job]
                val isCurrentJob = this@TerminalEmulator.readJob === thisJob
                if (isCurrentJob) {
                    if (_isActive.value) {
                        _isActive.value = false
                        runOnMain {
                            listeners.forEach { it.onTerminalDisconnected() }
                        }
                    }
                    closeStreams()
                    this@TerminalEmulator.readJob = null
                }
                // Always safe: this shuts down the executor captured locally
                // for THIS connection, never the (possibly reassigned) field.
                shutdownWriteExecutor(activeWriteExecutor)
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
        shutdownWriteExecutor(writeExecutor)

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
