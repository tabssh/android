package io.github.tabssh.ui.keyboard

/**
 * Represents a key on the custom keyboard
 */
data class KeyboardKey(
    val id: String,
    val label: String,
    val keySequence: String,
    val category: KeyCategory = KeyCategory.SPECIAL
) {
    enum class KeyCategory {
        SPECIAL,      // ESC, TAB, etc.
        ARROW,        // Arrow keys
        FUNCTION,     // F1-F12
        SYMBOL,       // /, \, |, etc.
        MODIFIER,     // CTL, ALT, FN
        ACTION        // Toggle, Paste
    }
    
    companion object {
        // Default keyboard layout
        fun getDefaultKeys(): List<KeyboardKey> = listOf(
            KeyboardKey("ESC", "ESC", "\u001B"),
            KeyboardKey("SLASH", "/", "/", KeyCategory.SYMBOL),
            KeyboardKey("BACKSLASH", "\\", "\\", KeyCategory.SYMBOL),
            KeyboardKey("PIPE", "|", "|", KeyCategory.SYMBOL),
            KeyboardKey("MINUS", "-", "-", KeyCategory.SYMBOL),
            KeyboardKey("HOME", "HOME", "\u001B[H"),
            KeyboardKey("END", "END", "\u001B[F"),
            KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
            KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
            KeyboardKey("TAB", "TAB", "\t"),
            KeyboardKey("CTL", "CTL", "", KeyCategory.MODIFIER),
            KeyboardKey("ALT", "ALT", "", KeyCategory.MODIFIER),
            KeyboardKey("FN", "FN", "", KeyCategory.MODIFIER),
            KeyboardKey("ENTER", "ENT", "\n"),
            KeyboardKey("TOGGLE", "⌨", "", KeyCategory.ACTION)
        )
        
        // All available keys for customization
        fun getAllAvailableKeys(): List<KeyboardKey> = listOf(
            // Special keys
            KeyboardKey("ESC", "ESC", "\u001B"),
            KeyboardKey("TAB", "TAB", "\t"),
            KeyboardKey("ENTER", "ENT", "\n"),
            KeyboardKey("SPACE", "SPC", " "),
            KeyboardKey("BACKSPACE", "⌫", "\b"),
            KeyboardKey("DELETE", "DEL", "\u001B[3~"),
            KeyboardKey("INSERT", "INS", "\u001B[2~"),
            
            // Navigation
            KeyboardKey("HOME", "HOME", "\u001B[H"),
            KeyboardKey("END", "END", "\u001B[F"),
            KeyboardKey("PGUP", "PGUP", "\u001B[5~"),
            KeyboardKey("PGDN", "PGDN", "\u001B[6~"),
            
            // Arrow keys
            KeyboardKey("UP", "↑", "\u001B[A", KeyCategory.ARROW),
            KeyboardKey("DOWN", "↓", "\u001B[B", KeyCategory.ARROW),
            KeyboardKey("LEFT", "←", "\u001B[D", KeyCategory.ARROW),
            KeyboardKey("RIGHT", "→", "\u001B[C", KeyCategory.ARROW),
            
            // Function keys (F1-F12)
            KeyboardKey("F1", "F1", "\u001BOP", KeyCategory.FUNCTION),
            KeyboardKey("F2", "F2", "\u001BOQ", KeyCategory.FUNCTION),
            KeyboardKey("F3", "F3", "\u001BOR", KeyCategory.FUNCTION),
            KeyboardKey("F4", "F4", "\u001BOS", KeyCategory.FUNCTION),
            KeyboardKey("F5", "F5", "\u001B[15~", KeyCategory.FUNCTION),
            KeyboardKey("F6", "F6", "\u001B[17~", KeyCategory.FUNCTION),
            KeyboardKey("F7", "F7", "\u001B[18~", KeyCategory.FUNCTION),
            KeyboardKey("F8", "F8", "\u001B[19~", KeyCategory.FUNCTION),
            KeyboardKey("F9", "F9", "\u001B[20~", KeyCategory.FUNCTION),
            KeyboardKey("F10", "F10", "\u001B[21~", KeyCategory.FUNCTION),
            KeyboardKey("F11", "F11", "\u001B[23~", KeyCategory.FUNCTION),
            KeyboardKey("F12", "F12", "\u001B[24~", KeyCategory.FUNCTION),
            
            // Symbols
            KeyboardKey("SLASH", "/", "/", KeyCategory.SYMBOL),
            KeyboardKey("BACKSLASH", "\\", "\\", KeyCategory.SYMBOL),
            KeyboardKey("PIPE", "|", "|", KeyCategory.SYMBOL),
            KeyboardKey("MINUS", "-", "-", KeyCategory.SYMBOL),
            KeyboardKey("PLUS", "+", "+", KeyCategory.SYMBOL),
            KeyboardKey("EQUALS", "=", "=", KeyCategory.SYMBOL),
            KeyboardKey("UNDERSCORE", "_", "_", KeyCategory.SYMBOL),
            KeyboardKey("TILDE", "~", "~", KeyCategory.SYMBOL),
            KeyboardKey("BACKTICK", "`", "`", KeyCategory.SYMBOL),
            
            // Modifiers
            KeyboardKey("CTL", "CTL", "", KeyCategory.MODIFIER),
            KeyboardKey("ALT", "ALT", "", KeyCategory.MODIFIER),
            KeyboardKey("FN", "FN", "", KeyCategory.MODIFIER),
            
            // Actions
            KeyboardKey("PASTE", "📋", "", KeyCategory.ACTION),
            KeyboardKey("TOGGLE", "⌨", "", KeyCategory.ACTION)
        )
    }
}
