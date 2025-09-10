package io.github.tabssh.terminal.emulator

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Complete terminal emulator that handles VT100/ANSI terminal emulation
 * Connects SSH streams to terminal buffer with ANSI parsing
 */
class TerminalEmulator(
    initialRows: Int = 24,
    initialCols: Int = 80,
    maxScrollback: Int = 10000
) {
    private val buffer = TerminalBuffer(initialRows, initialCols, maxScrollback)
    private val parser = ANSIParser(buffer)
    
    // I/O streams
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    // Terminal state
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    private val _title = MutableStateFlow("Terminal")
    val title: StateFlow<String> = _title.asStateFlow()
    
    private val _bellTriggered = MutableStateFlow(false)
    val bellTriggered: StateFlow<Boolean> = _bellTriggered.asStateFlow()
    
    // Coroutine scope for terminal I/O
    private val emulatorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Reader job
    private var readerJob: Job? = null
    
    // Terminal settings
    private var terminalType = "xterm-256color"
    private var encoding = Charsets.UTF_8
    
    // Listeners
    private val listeners = mutableListOf<TerminalListener>()
    
    // Thread-safe flags
    private val isReading = AtomicBoolean(false)
    
    init {
        Logger.d("TerminalEmulator", "Created terminal emulator ${initialCols}x${initialRows}")
    }
    
    /**
     * Connect terminal to SSH streams
     */
    fun connect(inputStream: InputStream, outputStream: OutputStream) {
        disconnect() // Disconnect any existing streams
        
        this.inputStream = inputStream
        this.outputStream = outputStream
        
        _isActive.value = true
        
        // Start reading from input stream
        startReading()
        
        Logger.i("TerminalEmulator", "Terminal connected to SSH streams")
        notifyListeners { onTerminalConnected() }
    }
    
    /**
     * Disconnect terminal from streams
     */
    fun disconnect() {
        Logger.d("TerminalEmulator", "Disconnecting terminal")
        
        stopReading()
        
        inputStream = null
        outputStream = null
        _isActive.value = false
        
        notifyListeners { onTerminalDisconnected() }
    }
    
    private fun startReading() {
        if (isReading.get()) {
            Logger.w("TerminalEmulator", "Already reading from input stream")
            return
        }
        
        readerJob = emulatorScope.launch {
            isReading.set(true)
            
            try {
                val inputBuffer = ByteArray(8192)
                val currentInputStream = inputStream
                
                if (currentInputStream == null) {
                    Logger.e("TerminalEmulator", "No input stream available")
                    return@launch
                }
                
                Logger.d("TerminalEmulator", "Started reading from input stream")
                
                while (isActive && currentInputStream == inputStream && !currentCoroutineContext().job.isCancelled) {
                    try {
                        val available = currentInputStream.available()
                        if (available > 0) {
                            val bytesRead = currentInputStream.read(inputBuffer, 0, minOf(inputBuffer.size, available))
                            if (bytesRead > 0) {
                                val data = inputBuffer.sliceArray(0 until bytesRead)
                                
                                // Process data on UI thread for thread safety
                                withContext(Dispatchers.Main) {
                                    processReceivedData(data)
                                }
                            }
                        } else {
                            // No data available, small delay to prevent busy waiting
                            delay(10)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Logger.e("TerminalEmulator", "Error reading from input stream", e)
                            withContext(Dispatchers.Main) {
                                notifyListeners { onTerminalError(e) }
                            }
                        }
                        break
                    }
                }
                
            } catch (e: Exception) {
                Logger.e("TerminalEmulator", "Fatal error in input reader", e)
                withContext(Dispatchers.Main) {
                    notifyListeners { onTerminalError(e) }
                }
            } finally {
                isReading.set(false)
                Logger.d("TerminalEmulator", "Stopped reading from input stream")
            }
        }
    }
    
    private fun stopReading() {
        readerJob?.cancel()
        readerJob = null
        isReading.set(false)
    }
    
    private fun processReceivedData(data: ByteArray) {
        try {
            // Convert bytes to string using terminal encoding
            val text = String(data, encoding)
            
            // Parse ANSI escape sequences and update buffer
            parser.processText(text)
            
            // Notify listeners of new data
            notifyListeners { onDataReceived(data) }
            
            // Log received data in debug mode
            if (Logger.isDebugMode() && text.isNotBlank()) {
                val printableText = text.replace(Regex("[\\x00-\\x1F\\x7F-\\x9F]")) { "\\x${it.value[0].code.toString(16).padStart(2, '0')}" }
                Logger.d("TerminalEmulator", "Received: $printableText")
            }
            
        } catch (e: Exception) {
            Logger.e("TerminalEmulator", "Error processing received data", e)
            notifyListeners { onTerminalError(e) }
        }
    }
    
    /**
     * Send data to the remote server
     */
    fun sendData(data: ByteArray) {
        emulatorScope.launch {
            try {
                val currentOutputStream = outputStream
                if (currentOutputStream != null && _isActive.value) {
                    currentOutputStream.write(data)
                    currentOutputStream.flush()
                    
                    // Log sent data in debug mode
                    if (Logger.isDebugMode()) {
                        val text = String(data, encoding)
                        val printableText = text.replace(Regex("[\\x00-\\x1F\\x7F-\\x9F]")) { "\\x${it.value[0].code.toString(16).padStart(2, '0')}" }
                        Logger.d("TerminalEmulator", "Sent: $printableText")
                    }
                    
                    withContext(Dispatchers.Main) {
                        notifyListeners { onDataSent(data) }
                    }
                } else {
                    Logger.w("TerminalEmulator", "Cannot send data: no output stream or terminal inactive")
                }
            } catch (e: Exception) {
                Logger.e("TerminalEmulator", "Error sending data", e)
                withContext(Dispatchers.Main) {
                    notifyListeners { onTerminalError(e) }
                }
            }
        }
    }
    
    /**
     * Send text to the remote server
     */
    fun sendText(text: String) {
        sendData(text.toByteArray(encoding))
    }
    
    /**
     * Send a key press to the server
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        val keySequence = generateKeySequence(keyCode, isCtrl, isAlt, isShift)
        if (keySequence.isNotEmpty()) {
            sendData(keySequence.toByteArray(encoding))
        }
    }
    
    private fun generateKeySequence(keyCode: Int, isCtrl: Boolean, isAlt: Boolean, isShift: Boolean): String {
        // Convert Android key codes to terminal escape sequences
        return when (keyCode) {
            // Arrow keys
            19 -> "\u001B[A" // Up
            20 -> "\u001B[B" // Down
            21 -> "\u001B[D" // Left
            22 -> "\u001B[C" // Right
            
            // Function keys
            131 -> "\u001BOP" // F1
            132 -> "\u001BOQ" // F2
            133 -> "\u001BOR" // F3
            134 -> "\u001BOS" // F4
            135 -> "\u001B[15~" // F5
            136 -> "\u001B[17~" // F6
            137 -> "\u001B[18~" // F7
            138 -> "\u001B[19~" // F8
            139 -> "\u001B[20~" // F9
            140 -> "\u001B[21~" // F10
            141 -> "\u001B[23~" // F11
            142 -> "\u001B[24~" // F12
            
            // Navigation keys
            122 -> "\u001B[1~" // Home
            123 -> "\u001B[4~" // End
            92 -> "\u001B[5~" // Page Up
            93 -> "\u001B[6~" // Page Down
            112 -> "\u001B[2~" // Insert
            67 -> "\u001B[3~" // Delete
            
            // Tab key
            61 -> if (isShift) "\u001B[Z" else "\t"
            
            // Enter key
            66 -> "\r"
            
            // Backspace
            67 -> "\u007F"
            
            // Escape key
            111 -> "\u001B"
            
            else -> {
                // Handle Ctrl combinations
                if (isCtrl && keyCode >= 29 && keyCode <= 54) { // A-Z
                    val ctrlChar = (keyCode - 29 + 1).toChar() // Ctrl+A = 1, etc.
                    ctrlChar.toString()
                } else {
                    ""
                }
            }
        }
    }
    
    /**
     * Resize the terminal
     */
    fun resize(rows: Int, cols: Int) {
        buffer.resize(rows, cols)
        
        // Send resize notification to server if connected
        if (_isActive.value) {
            // This would typically involve sending a SIGWINCH signal or ANSI sequence
            Logger.d("TerminalEmulator", "Terminal resized to ${cols}x${rows}")
        }
        
        notifyListeners { onTerminalResized(rows, cols) }
    }
    
    /**
     * Get terminal buffer for rendering
     */
    fun getBuffer(): TerminalBuffer = buffer
    
    /**
     * Get terminal size
     */
    fun getRows(): Int = buffer.getRows()
    fun getCols(): Int = buffer.getCols()
    
    /**
     * Get cursor position
     */
    fun getCursorRow(): Int = buffer.getCursorRow()
    fun getCursorCol(): Int = buffer.getCursorCol()
    
    /**
     * Get screen content as text
     */
    fun getScreenContent(): String = buffer.getScreenContent()
    
    /**
     * Get scrollback content as text
     */
    fun getScrollbackContent(): String = buffer.getScrollbackContent()
    
    /**
     * Set terminal title (from OSC sequences)
     */
    fun setTitle(newTitle: String) {
        _title.value = newTitle
        notifyListeners { onTitleChanged(newTitle) }
    }
    
    /**
     * Trigger bell notification
     */
    fun triggerBell() {
        _bellTriggered.value = true
        notifyListeners { onBellTriggered() }
        
        // Reset bell state after a short delay
        emulatorScope.launch {
            delay(100)
            _bellTriggered.value = false
        }
    }
    
    /**
     * Clear the screen
     */
    fun clearScreen() {
        buffer.clearScreen()
        buffer.setCursorPosition(0, 0)
        notifyListeners { onScreenCleared() }
    }
    
    /**
     * Reset terminal to initial state
     */
    fun reset() {
        buffer.clearScreen()
        buffer.setCursorPosition(0, 0)
        buffer.resetCharacterAttributes()
        buffer.useAlternateScreen(false)
        buffer.setScrollRegion(0, buffer.getRows() - 1)
        
        _title.value = "Terminal"
        
        Logger.d("TerminalEmulator", "Terminal reset")
        notifyListeners { onTerminalReset() }
    }
    
    /**
     * Add terminal listener
     */
    fun addListener(listener: TerminalListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove terminal listener
     */
    fun removeListener(listener: TerminalListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: TerminalListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("TerminalEmulator", "Cleaning up terminal emulator")
        
        disconnect()
        emulatorScope.cancel()
        listeners.clear()
    }
    
    // Settings
    fun setTerminalType(type: String) {
        terminalType = type
    }
    
    fun getTerminalType(): String = terminalType
    
    fun setEncoding(charset: String) {
        try {
            encoding = Charsets.forName(charset)
            Logger.d("TerminalEmulator", "Set encoding to $charset")
        } catch (e: Exception) {
            Logger.w("TerminalEmulator", "Invalid encoding $charset, keeping ${encoding.name()}")
        }
    }
    
    fun getEncoding(): String = encoding.name()
}

/**
 * Interface for terminal events
 */
interface TerminalListener {
    fun onTerminalConnected() {}
    fun onTerminalDisconnected() {}
    fun onTerminalError(error: Exception) {}
    fun onTerminalReset() {}
    fun onTerminalResized(rows: Int, cols: Int) {}
    fun onDataReceived(data: ByteArray) {}
    fun onDataSent(data: ByteArray) {}
    fun onTitleChanged(title: String) {}
    fun onBellTriggered() {}
    fun onScreenCleared() {}
}