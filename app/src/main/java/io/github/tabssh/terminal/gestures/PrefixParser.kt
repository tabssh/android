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
            // Ctrl+key variants: C-a, ^a, Ctrl-a, Ctrl+a
            trimmed.matches(Regex("^(C-|\\^|Ctrl[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                parseCtrlKey(trimmed)
            }

            // Ctrl+Space: C-Space, ^Space, Ctrl-Space, Ctrl+Space
            trimmed.matches(Regex("^(C-|\\^|Ctrl[-+])Space$", RegexOption.IGNORE_CASE)) -> {
                // Ctrl+Space is NUL
                byteArrayOf(0x00.toByte())
            }

            // Alt+key variants: M-a, Alt-a, Alt+a
            trimmed.matches(Regex("^(M-|Alt[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                parseAltKey(trimmed)
            }

            // Literal single character: `, ~, etc. Encoded as UTF-8 rather than
            // a single truncated byte so a non-ASCII prefix (e.g. a European
            // keyboard's dead key) reaches the remote as the character the user
            // actually configured instead of a mangled low byte.
            trimmed.length == 1 -> {
                trimmed.toByteArray(Charsets.UTF_8)
            }

            // Hex notation: 0x02, \x02
            trimmed.matches(Regex("^(0x|\\\\x)([0-9a-fA-F]{2})$")) -> {
                parseHexKey(trimmed)
            }

            // Invalid notation
            else -> null
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
     * A prefix expressed as a modifier plus a base key, for transports that
     * carry key events rather than bytes.
     *
     * [parse] returns the terminal byte encoding, which a graphical console
     * cannot use: RFB carries keysyms and SPICE carries PS/2 scancodes, so
     * Ctrl+B has to travel as a real Control_L + 'b' chord rather than as the
     * 0x02 control byte a pty would receive.
     *
     * [modifier] is a custom-keyboard-bar modifier id ("CTL"/"ALT"/"SFT", the
     * keys of ConsoleKeyMapper's modifier maps) or null for a literal key.
     */
    data class Chord(val modifier: String?, val key: Char)

    /**
     * Parse prefix notation into a modifier + key [Chord], or null when the
     * notation is invalid or has no chord form.
     *
     * Accepts the same notations as [parse]. A hex/control byte in the
     * Ctrl+letter range maps back to its letter (0x02 → Ctrl+B); NUL maps to
     * Ctrl+Space, matching [parse]'s encoding of it.
     */
    fun parseChord(notation: String): Chord? {
        if (notation.isEmpty()) {
            return null
        }

        val trimmed = notation.trim()

        return when {
            trimmed.matches(Regex("^(C-|\\^|Ctrl[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) ->
                Chord("CTL", trimmed.last().lowercaseChar())

            trimmed.matches(Regex("^(C-|\\^|Ctrl[-+])Space$", RegexOption.IGNORE_CASE)) ->
                Chord("CTL", ' ')

            trimmed.matches(Regex("^(M-|Alt[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) ->
                Chord("ALT", trimmed.last().lowercaseChar())

            trimmed.length == 1 -> Chord(null, trimmed[0])

            trimmed.matches(Regex("^(0x|\\\\x)([0-9a-fA-F]{2})$")) -> {
                val code = try {
                    trimmed.substringAfter('x').toInt(16)
                } catch (e: NumberFormatException) {
                    return null
                }
                when (code) {
                    0x00 -> Chord("CTL", ' ')
                    in 0x01..0x1A -> Chord("CTL", (code + 0x60).toChar())
                    in 0x20..0x7E -> Chord(null, code.toChar())
                    else -> null
                }
            }

            else -> null
        }
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
            notation.matches(Regex("^(C-|\\^|Ctrl[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
                val key = notation.last().uppercaseChar()
                "Ctrl+$key"
            }
            notation.matches(Regex("^(C-|\\^|Ctrl[-+])Space$", RegexOption.IGNORE_CASE)) -> {
                "Ctrl+Space"
            }
            notation.matches(Regex("^(M-|Alt[-+])([a-zA-Z])$", RegexOption.IGNORE_CASE)) -> {
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
