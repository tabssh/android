package io.github.tabssh.terminal.gestures

/**
 * Parses terminal multiplexer prefix key notation into byte sequences
 * Supports common notations: C-a, ^A, Ctrl-A, M-b, Alt-b, etc.
 */
object PrefixParser {
    
    /**
     * Parse prefix notation into byte array
     * 
     * @param notation The prefix notation (e.g., "C-a", "^B", "C-Space", "`", "M-b")
     * @return ByteArray of the prefix sequence, or null if invalid
     * 
     * Examples:
     * - "C-a", "^A", "Ctrl-A" → Ctrl+A (0x01)
     * - "C-b", "^B", "Ctrl-B" → Ctrl+B (0x02)
     * - "C-Space" → Ctrl+Space (0x00)
     * - "`" → Backtick literal (0x60)
     * - "M-b", "Alt-b" → Alt+B (ESC + 'b')
     */
    fun parse(notation: String): ByteArray? {
        if (notation.isEmpty()) {
            return null
        }
        
        val trimmed = notation.trim()
        
        return when {
            // Ctrl+key variants: C-a, ^a, Ctrl-a
            trimmed.matches(Regex("^(C-|\\^|Ctrl-)([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                parseCtrlKey(trimmed)
            }
            
            // Ctrl+Space: C-Space, ^Space, Ctrl-Space
            trimmed.matches(Regex("^(C-|\\^|Ctrl-)Space$", RegexOption.IGNORE_CASE)) -> {
                byteArrayOf(0x00.toByte()) // Ctrl+Space is NUL
            }
            
            // Alt+key variants: M-a, Alt-a
            trimmed.matches(Regex("^(M-|Alt-)([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                parseAltKey(trimmed)
            }
            
            // Literal single character: `, ~, etc.
            trimmed.length == 1 -> {
                byteArrayOf(trimmed[0].code.toByte())
            }
            
            // Hex notation: 0x02, \x02
            trimmed.matches(Regex("^(0x|\\\\x)([0-9a-fA-F]{2})$")) -> {
                parseHexKey(trimmed)
            }
            
            else -> null // Invalid notation
        }
    }
    
    /**
     * Parse Ctrl+key notation
     * Ctrl+A = 0x01, Ctrl+B = 0x02, etc.
     */
    private fun parseCtrlKey(notation: String): ByteArray? {
        val key = notation.last().lowercaseChar()
        
        // Ctrl+key is calculated as: (key - 'a' + 1) for lowercase
        // Valid range: Ctrl+A (0x01) to Ctrl+Z (0x1A)
        if (key in 'a'..'z') {
            val ctrlCode = (key.code - 'a'.code + 1).toByte()
            return byteArrayOf(ctrlCode)
        }
        
        return null
    }
    
    /**
     * Parse Alt+key notation
     * Alt+key is sent as ESC (0x1B) followed by the key
     */
    private fun parseAltKey(notation: String): ByteArray {
        val key = notation.last()
        return byteArrayOf(0x1B.toByte(), key.code.toByte())
    }
    
    /**
     * Parse hex notation: 0x02 or \x02
     */
    private fun parseHexKey(notation: String): ByteArray? {
        return try {
            val hex = notation.substringAfter('x')
            val byte = hex.toInt(16).toByte()
            byteArrayOf(byte)
        } catch (e: NumberFormatException) {
            null
        }
    }
    
    /**
     * Get human-readable description of prefix notation
     */
    fun getDescription(notation: String): String {
        if (notation.isEmpty()) {
            return "Default prefix"
        }
        
        return when {
            notation.matches(Regex("^(C-|\\^|Ctrl-)([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                val key = notation.last().uppercaseChar()
                "Ctrl+$key"
            }
            notation.matches(Regex("^(C-|\\^|Ctrl-)Space$", RegexOption.IGNORE_CASE)) -> {
                "Ctrl+Space"
            }
            notation.matches(Regex("^(M-|Alt-)([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                val key = notation.last().uppercaseChar()
                "Alt+$key"
            }
            notation.length == 1 -> {
                "Literal '${notation}'"
            }
            notation.matches(Regex("^(0x|\\\\x)([0-9a-fA-F]{2})$")) -> {
                "Hex: $notation"
            }
            else -> "Unknown: $notation"
        }
    }
    
    /**
     * Validate prefix notation
     * @return true if notation is valid, false otherwise
     */
    fun isValid(notation: String): Boolean {
        return parse(notation) != null
    }
}
