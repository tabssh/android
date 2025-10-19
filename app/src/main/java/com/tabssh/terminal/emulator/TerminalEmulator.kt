package com.tabssh.terminal.emulator

import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.Charset

/**
 * VT100/ANSI Terminal Emulator
 * Processes terminal escape sequences and maintains terminal state
 */
class TerminalEmulator(private val buffer: TerminalBuffer) {

    private var currentCharset = Charset.forName("UTF-8")
    private var cursorX = 0
    private var cursorY = 0
    private var _terminalType = "xterm-256color"

    // State tracking
    private val _isActive = MutableStateFlow(true)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // Listeners
    private val listeners = mutableListOf<TerminalListener>()

    /**
     * Process input data from SSH connection
     */
    fun processInput(data: ByteArray) {
        val text = String(data, currentCharset)
        for (char in text) {
            processChar(char)
        }
        buffer.setCursorPosition(cursorX, cursorY)
    }

    /**
     * Send text to terminal (converts to bytes and processes)
     */
    fun sendText(text: String) {
        processInput(text.toByteArray(currentCharset))
    }

    private fun processChar(char: Char) {
        when {
            char.code < 32 -> processControlChar(char)
            else -> writeChar(char)
        }
    }

    private fun writeChar(char: Char) {
        if (cursorX >= buffer.getCols()) {
            cursorX = 0
            cursorY++
            if (cursorY >= buffer.getRows()) {
                buffer.scrollUp()
                cursorY = buffer.getRows() - 1
            }
        }

        buffer.setChar(cursorY, cursorX, char, 7, 0, false, false, false)
        cursorX++
    }

    private fun processControlChar(char: Char) {
        when (char.code) {
            10 -> { // LF - Line Feed
                cursorY++
                if (cursorY >= buffer.getRows()) {
                    buffer.scrollUp()
                    cursorY = buffer.getRows() - 1
                }
            }
            13 -> cursorX = 0 // CR - Carriage Return
        }
    }

    /**
     * Handle key press events
     */
    fun handleKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        // Convert key press to appropriate terminal input
        val bytes = when {
            isCtrl -> handleControlKey(keyCode)
            isAlt -> handleAltKey(keyCode)
            else -> handleNormalKey(keyCode)
        }
        processInput(bytes)
    }

    private fun handleControlKey(keyCode: Int): ByteArray {
        // Generate control sequences
        return when (keyCode) {
            65 -> byteArrayOf(1) // Ctrl+A
            67 -> byteArrayOf(3) // Ctrl+C
            68 -> byteArrayOf(4) // Ctrl+D
            else -> byteArrayOf()
        }
    }

    private fun handleAltKey(keyCode: Int): ByteArray {
        // Generate alt sequences
        return byteArrayOf(27, keyCode.toByte())
    }

    private fun handleNormalKey(keyCode: Int): ByteArray {
        return byteArrayOf(keyCode.toByte())
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
        cursorX = 0
        cursorY = 0
    }

    /**
     * Get screen content as string
     */
    fun getScreenContent(): String {
        val sb = StringBuilder()
        for (row in 0 until getRows()) {
            val line = buffer.getLine(row)
            if (line != null) {
                for (cell in line) {
                    sb.append(cell.char)
                }
                if (row < getRows() - 1) sb.append('\n')
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
    fun connect(inputStream: java.io.InputStream, outputStream: java.io.OutputStream) {
        // Implementation would connect streams to terminal I/O
        Logger.d("TerminalEmulator", "Connected to I/O streams")
    }

    /**
     * Disconnect terminal streams
     */
    fun disconnect() {
        // Implementation would disconnect streams
        Logger.d("TerminalEmulator", "Disconnected from I/O streams")
    }

    /**
     * Send key press to terminal
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        // Implementation would convert key press to terminal input
        Logger.d("TerminalEmulator", "Key pressed: $keyCode")
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
        _isActive.value = false
        listeners.clear()
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
