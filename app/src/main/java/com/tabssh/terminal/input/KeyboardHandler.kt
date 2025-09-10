package io.github.tabssh.terminal.input

import android.view.KeyEvent
import io.github.tabssh.terminal.emulator.TerminalEmulator
import io.github.tabssh.utils.logging.Logger

/**
 * Handles keyboard input and converts it to terminal sequences
 */
class KeyboardHandler(private val terminal: TerminalEmulator) {
    
    // Key mapping state
    private var ctrlPressed = false
    private var altPressed = false
    private var shiftPressed = false
    
    // Settings
    private var altSendsEscape = true
    private var backspaceSendsDel = false
    private var volumeKeysAsCtrl = false
    
    /**
     * Handle a key down event
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Logger.d("KeyboardHandler", "Key down: code=$keyCode, meta=${event.metaState}")
        
        // Update modifier states
        updateModifierStates(event)
        
        // Handle special key combinations first
        if (handleSpecialKeys(keyCode, event)) {
            return true
        }
        
        // Generate and send key sequence
        val sequence = generateKeySequence(keyCode, event)
        if (sequence.isNotEmpty()) {
            terminal.sendText(sequence)
            return true
        }
        
        return false
    }
    
    /**
     * Handle a key up event
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        updateModifierStates(event)
        return false // Usually don't consume key up events
    }
    
    private fun updateModifierStates(event: KeyEvent) {
        ctrlPressed = event.isCtrlPressed
        altPressed = event.isAltPressed
        shiftPressed = event.isShiftPressed
    }
    
    private fun handleSpecialKeys(keyCode: Int, event: KeyEvent): Boolean {
        // Handle volume keys as Ctrl if enabled
        if (volumeKeysAsCtrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    ctrlPressed = true
                    return false // Let next key combination handle it
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    ctrlPressed = true
                    return false
                }
            }
        }
        
        // Handle Ctrl+key combinations
        if (ctrlPressed && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            val ctrlChar = (keyCode - KeyEvent.KEYCODE_A + 1).toChar()
            terminal.sendText(ctrlChar.toString())
            return true
        }
        
        // Handle special Ctrl combinations
        if (ctrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE -> {
                    terminal.sendText("\u0000") // Ctrl+Space = NUL
                    return true
                }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_AT -> {
                    terminal.sendText("\u0000") // Ctrl+2 or Ctrl+@ = NUL
                    return true
                }
                KeyEvent.KEYCODE_3 -> {
                    terminal.sendText("\u001B") // Ctrl+3 = ESC
                    return true
                }
                KeyEvent.KEYCODE_4 -> {
                    terminal.sendText("\u001C") // Ctrl+4 = FS
                    return true
                }
                KeyEvent.KEYCODE_5 -> {
                    terminal.sendText("\u001D") // Ctrl+5 = GS
                    return true
                }
                KeyEvent.KEYCODE_6 -> {
                    terminal.sendText("\u001E") // Ctrl+6 = RS
                    return true
                }
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_SLASH -> {
                    terminal.sendText("\u001F") // Ctrl+7 or Ctrl+/ = US
                    return true
                }
                KeyEvent.KEYCODE_8 -> {
                    terminal.sendText("\u007F") // Ctrl+8 = DEL
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun generateKeySequence(keyCode: Int, event: KeyEvent): String {
        val isCtrl = event.isCtrlPressed || ctrlPressed
        val isAlt = event.isAltPressed || altPressed
        val isShift = event.isShiftPressed || shiftPressed
        
        Logger.d("KeyboardHandler", "Generating sequence for keyCode=$keyCode, ctrl=$isCtrl, alt=$isAlt, shift=$isShift")
        
        // Handle printable characters
        val unicodeChar = event.unicodeChar
        if (unicodeChar != 0 && !isCtrl && !isAlt) {
            return unicodeChar.toChar().toString()
        }
        
        // Handle arrow keys
        val arrowSequence = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
            else -> null
        }
        
        if (arrowSequence != null) {
            return if (isShift) {
                // Shift+Arrow keys for selection
                "\u001B[1;2" + arrowSequence.last()
            } else if (isCtrl) {
                // Ctrl+Arrow keys for word movement
                "\u001B[1;5" + arrowSequence.last()
            } else if (isAlt) {
                // Alt+Arrow keys
                "\u001B[1;3" + arrowSequence.last()
            } else {
                arrowSequence
            }
        }
        
        // Handle function keys
        val functionKey = when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "\u001BOP"
            KeyEvent.KEYCODE_F2 -> "\u001BOQ"
            KeyEvent.KEYCODE_F3 -> "\u001BOR"
            KeyEvent.KEYCODE_F4 -> "\u001BOS"
            KeyEvent.KEYCODE_F5 -> "\u001B[15~"
            KeyEvent.KEYCODE_F6 -> "\u001B[17~"
            KeyEvent.KEYCODE_F7 -> "\u001B[18~"
            KeyEvent.KEYCODE_F8 -> "\u001B[19~"
            KeyEvent.KEYCODE_F9 -> "\u001B[20~"
            KeyEvent.KEYCODE_F10 -> "\u001B[21~"
            KeyEvent.KEYCODE_F11 -> "\u001B[23~"
            KeyEvent.KEYCODE_F12 -> "\u001B[24~"
            else -> null
        }
        
        if (functionKey != null) {
            return functionKey
        }
        
        // Handle navigation keys
        when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> return "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> return "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> return "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> return "\u001B[6~"
            KeyEvent.KEYCODE_INSERT -> return "\u001B[2~"
            KeyEvent.KEYCODE_FORWARD_DEL -> return "\u001B[3~"
        }
        
        // Handle special keys
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> return "\r"
            KeyEvent.KEYCODE_TAB -> {
                return if (isShift) "\u001B[Z" else "\t"
            }
            KeyEvent.KEYCODE_DEL -> {
                return if (backspaceSendsDel) "\u001B[3~" else "\u007F"
            }
            KeyEvent.KEYCODE_ESCAPE -> return "\u001B"
        }
        
        // Handle Alt+key combinations
        if (isAlt && altSendsEscape && unicodeChar != 0) {
            return "\u001B" + unicodeChar.toChar().toString()
        }
        
        // Handle numeric keypad
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            val digit = (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
            return digit
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_DOT -> return "."
            KeyEvent.KEYCODE_NUMPAD_COMMA -> return ","
            KeyEvent.KEYCODE_NUMPAD_ENTER -> return "\r"
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> return "="
            KeyEvent.KEYCODE_NUMPAD_ADD -> return "+"
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> return "-"
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> return "*"
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> return "/"
            KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> return "("
            KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> return ")"
        }
        
        Logger.d("KeyboardHandler", "No mapping found for keyCode=$keyCode")
        return ""
    }
    
    // Configuration methods
    fun setAltSendsEscape(enabled: Boolean) {
        altSendsEscape = enabled
    }
    
    fun isAltSendsEscape(): Boolean = altSendsEscape
    
    fun setBackspaceSendsDel(enabled: Boolean) {
        backspaceSendsDel = enabled
    }
    
    fun isBackspaceSendsDel(): Boolean = backspaceSendsDel
    
    fun setVolumeKeysAsCtrl(enabled: Boolean) {
        volumeKeysAsCtrl = enabled
    }
    
    fun isVolumeKeysAsCtrl(): Boolean = volumeKeysAsCtrl
    
    /**
     * Send a specific key sequence (for virtual keyboard buttons)
     */
    fun sendKeySequence(sequence: String) {
        terminal.sendText(sequence)
    }
    
    /**
     * Send common terminal key combinations
     */
    fun sendCtrlKey(key: Char) {
        val ctrlChar = when (key.lowercaseChar()) {
            in 'a'..'z' -> (key.lowercaseChar() - 'a' + 1).toChar()
            '@' -> '\u0000'
            '[' -> '\u001B'
            '\\' -> '\u001C'
            ']' -> '\u001D'
            '^' -> '\u001E'
            '_' -> '\u001F'
            else -> return
        }
        terminal.sendText(ctrlChar.toString())
    }
    
    fun sendAltKey(key: Char) {
        if (altSendsEscape) {
            terminal.sendText("\u001B$key")
        }
    }
    
    /**
     * Handle text input from software keyboard
     */
    fun onTextInput(text: String) {
        terminal.sendText(text)
    }
    
    /**
     * Handle composing text (IME input)
     */
    fun onComposingTextChanged(text: String) {
        // For now, just send the text immediately
        // In a more advanced implementation, this could show a composition indicator
        if (text.isNotEmpty()) {
            terminal.sendText(text)
        }
    }
    
    /**
     * Reset modifier states (useful when focus is lost)
     */
    fun resetModifierStates() {
        ctrlPressed = false
        altPressed = false
        shiftPressed = false
    }
    
    /**
     * Get current modifier states for debugging
     */
    fun getModifierStates(): String {
        return "ctrl=$ctrlPressed, alt=$altPressed, shift=$shiftPressed"
    }
}