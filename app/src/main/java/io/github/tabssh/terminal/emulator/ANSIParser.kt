package io.github.tabssh.terminal.emulator

import io.github.tabssh.utils.logging.Logger
import java.util.*

/**
 * ANSI/VT100 escape sequence parser
 * Parses terminal escape sequences and applies them to the terminal buffer
 */
class ANSIParser(private val buffer: TerminalBuffer) {
    
    // Parser state
    private var parserState = ParserState.NORMAL
    private val escapeSequence = StringBuilder()
    private val parameters = mutableListOf<Int>()
    private var currentParam = StringBuilder()
    private var intermediateChars = StringBuilder()

    // Set while an ESC has been seen inside an OSC/DCS string: the next character
    // decides whether it was the ST terminator (ESC backslash) or literal payload.
    private var stringEscapePending = false


    companion object {
        private const val ESC = '\u001B'
        private const val CSI = '['

        // Hostile-server bounds. A malicious or broken peer can open a control
        // sequence and never terminate it; every accumulator below is capped so
        // the parser abandons the sequence instead of growing without limit.
        private const val MAX_ESCAPE_LENGTH = 4096
        private const val MAX_STRING_LENGTH = 8192
        private const val MAX_PARAMETERS = 32
        private const val MAX_PARAM_VALUE = 65535
        private const val MAX_LINK_URL_LENGTH = 2048
        private const val MAX_TITLE_LENGTH = 256

        // Schemes an OSC 8 hyperlink is allowed to carry. Anything else
        // (javascript:, intent:, content:, data:, file:) is dropped: the URL is
        // server-controlled and is handed to an "open link" dialog, so it must
        // never be allowed to reach an Intent with an arbitrary scheme.
        private val ALLOWED_LINK_SCHEMES = setOf(
            "http", "https", "ftp", "ftps", "ssh", "sftp", "telnet", "mailto"
        )
        private const val OSC = ']'
        private const val DCS = 'P'
        private const val ST = '\\'
        
        // Control characters
        private const val BEL = '\u0007'
        private const val TAB = '\t'
        private const val LF = '\n'
        private const val CR = '\r'
        private const val BS = '\u0008'
        private const val FF = '\u000C'
        private const val VT = '\u000B'
    }
    
    private enum class ParserState {
        // Normal text processing
        NORMAL,
        // ESC received, waiting for next char
        ESCAPE,
        // CSI sequence started
        CSI_ENTRY,
        // Reading CSI parameters
        CSI_PARAM,
        // Reading intermediate chars
        CSI_INTERMEDIATE,
        // Operating System Command string
        OSC_STRING,
        // Device Control String payload, consumed until ST but not interpreted
        DCS_STRING
    }
    
    /**
     * Process input data and update terminal buffer
     */
    fun processInput(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        processText(text)
    }
    
    /**
     * Process text character by character
     */
    fun processText(text: String) {
        for (ch in text) {
            processChar(ch)
        }
    }
    
    private fun processChar(ch: Char) {
        when (parserState) {
            ParserState.NORMAL -> processNormalChar(ch)
            ParserState.ESCAPE -> processEscapeChar(ch)
            ParserState.CSI_ENTRY -> processCSIEntry(ch)
            ParserState.CSI_PARAM -> processCSIParam(ch)
            ParserState.CSI_INTERMEDIATE -> processCSIIntermediate(ch)
            ParserState.OSC_STRING -> processOSCString(ch)
            ParserState.DCS_STRING -> processDCSString(ch)
        }
    }
    
    private fun processNormalChar(ch: Char) {
        when (ch) {
            ESC -> {
                parserState = ParserState.ESCAPE
                escapeSequence.clear()
                escapeSequence.append(ch)
            }
            BEL -> {
                // Bell - could trigger notification or vibration
                Logger.d("ANSIParser", "Bell character received")
            }
            TAB -> buffer.writeChar('\t')
            LF, VT, FF -> buffer.writeChar('\n')
            CR -> buffer.writeChar('\r')
            BS -> buffer.writeChar('\b')
            else -> {
                // Printable characters only: DEL (0x7F) and the C1 control block
                // (0x80..0x9F) are control codes, not glyphs, and must never be
                // written into the buffer where a server could use them to smuggle
                // unrenderable cells into copied text.
                val printable = ch.code >= 0x20 &&
                    ch.code != 0x7F &&
                    (ch.code < 0x80 || ch.code > 0x9F)
                if (printable) {
                    buffer.writeChar(ch)
                }
            }
        }
    }
    
    private fun processEscapeChar(ch: Char) {
        if (appendToEscapeSequence(ch)) return

        when (ch) {
            CSI -> {
                parserState = ParserState.CSI_ENTRY
                parameters.clear()
                currentParam.clear()
                intermediateChars.clear()
            }
            OSC -> {
                parserState = ParserState.OSC_STRING
                currentParam.clear()
                stringEscapePending = false
            }
            DCS -> {
                parserState = ParserState.DCS_STRING
                parameters.clear()
                currentParam.clear()
                intermediateChars.clear()
                stringEscapePending = false
            }
            // Two-character escape sequences
            '7' -> {
                buffer.saveCursor()
                resetParser()
            }
            '8' -> {
                buffer.restoreCursor()
                resetParser()
            }
            'c' -> {
                // Reset terminal
                buffer.clearScreen()
                buffer.setCursorPosition(0, 0)
                buffer.resetCharacterAttributes()
                resetParser()
            }
            'D' -> {
                // Index (move cursor down, scroll if needed)
                val newRow = buffer.getCursorRow() + 1
                if (newRow >= buffer.getRows()) {
                    buffer.scrollUp()
                } else {
                    buffer.setCursorPosition(buffer.getCursorCol(), newRow)
                }
                resetParser()
            }
            'E' -> {
                // Next line (CR + LF)
                buffer.setCursorPosition(0, buffer.getCursorRow() + 1)
                resetParser()
            }
            'M' -> {
                // Reverse index (move cursor up, scroll if needed)
                val newRow = buffer.getCursorRow() - 1
                if (newRow < 0) {
                    buffer.scrollDown()
                } else {
                    buffer.setCursorPosition(buffer.getCursorCol(), newRow)
                }
                resetParser()
            }
            else -> {
                Logger.d("ANSIParser", "Unhandled escape sequence: ESC$ch")
                resetParser()
            }
        }
    }
    
    private fun processCSIEntry(ch: Char) {
        if (appendToEscapeSequence(ch)) return

        when {
            ch in '0'..'9' -> {
                appendParamDigit(ch)
                parserState = ParserState.CSI_PARAM
            }
            ch == ';' -> {
                addParameter()
                parserState = ParserState.CSI_PARAM
            }
            ch in '<'..'?' -> {
                // DEC private-mode marker (< = > ?) — e.g. ESC[?1049h. Record it
                // and keep parsing parameters so private modes (alternate screen,
                // cursor visibility, bracketed paste) dispatch instead of aborting.
                intermediateChars.append(ch)
                parserState = ParserState.CSI_PARAM
            }
            ch in ' '..'/' -> {
                intermediateChars.append(ch)
                parserState = ParserState.CSI_INTERMEDIATE
            }
            ch in '@'..'~' -> {
                executeCSISequence(ch)
                resetParser()
            }
            else -> {
                Logger.w("ANSIParser", "Invalid CSI character: $ch")
                resetParser()
            }
        }
    }
    
    private fun processCSIParam(ch: Char) {
        if (appendToEscapeSequence(ch)) return

        when {
            ch in '0'..'9' -> {
                appendParamDigit(ch)
            }
            ch == ';' -> {
                addParameter()
            }
            ch in ' '..'/' -> {
                // Add current parameter if exists
                if (currentParam.isNotEmpty()) {
                    addParameter()
                }
                intermediateChars.append(ch)
                parserState = ParserState.CSI_INTERMEDIATE
            }
            ch in '@'..'~' -> {
                // Add current parameter if exists
                if (currentParam.isNotEmpty()) {
                    addParameter()
                }
                executeCSISequence(ch)
                resetParser()
            }
            else -> {
                Logger.w("ANSIParser", "Invalid CSI parameter character: $ch")
                resetParser()
            }
        }
    }
    
    private fun processCSIIntermediate(ch: Char) {
        if (appendToEscapeSequence(ch)) return

        when {
            ch in ' '..'/' -> {
                intermediateChars.append(ch)
            }
            ch in '@'..'~' -> {
                executeCSISequence(ch)
                resetParser()
            }
            else -> {
                Logger.w("ANSIParser", "Invalid CSI intermediate character: $ch")
                resetParser()
            }
        }
    }
    
    private fun executeCSISequence(finalChar: Char) {
        Logger.d("ANSIParser", "Executing CSI sequence: ${escapeSequence}$finalChar with params: $parameters")
        
        when (finalChar) {
            'A' -> { // Cursor Up
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.moveCursor(-n, 0)
            }
            'B' -> { // Cursor Down
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.moveCursor(n, 0)
            }
            'C' -> { // Cursor Forward
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.moveCursor(0, n)
            }
            'D' -> { // Cursor Back
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.moveCursor(0, -n)
            }
            'E' -> { // Cursor Next Line
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.setCursorPosition(0, buffer.getCursorRow() + n)
            }
            'F' -> { // Cursor Previous Line
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.setCursorPosition(0, buffer.getCursorRow() - n)
            }
            'G' -> { // Cursor Horizontal Absolute
                val col = parameters.getOrElse(0) { 1 } - 1
                buffer.setCursorPosition(col, buffer.getCursorRow())
            }
            'H', 'f' -> { // Cursor Position
                val row = (parameters.getOrElse(0) { 1 } - 1).coerceAtLeast(0)
                val col = (parameters.getOrElse(1) { 1 } - 1).coerceAtLeast(0)
                buffer.setCursorPosition(col, row)
            }
            'J' -> { // Erase Display
                when (parameters.getOrElse(0) { 0 }) {
                    0 -> buffer.clearToEndOfScreen()
                    1 -> buffer.clearToBeginningOfScreen()
                    2, 3 -> buffer.clearScreen()
                }
            }
            'K' -> { // Erase Line
                when (parameters.getOrElse(0) { 0 }) {
                    0 -> {
                        // Clear from cursor to end of line
                        val row = buffer.getCursorRow()
                        val startCol = buffer.getCursorCol()
                        for (c in startCol until buffer.getCols()) {
                            buffer.getLine(row)?.set(c, TerminalChar.empty())
                        }
                    }
                    1 -> {
                        // Clear from start of line to cursor
                        val row = buffer.getCursorRow()
                        val endCol = buffer.getCursorCol()
                        for (c in 0..endCol) {
                            buffer.getLine(row)?.set(c, TerminalChar.empty())
                        }
                    }
                    2 -> buffer.clearLine()
                }
            }
            'L' -> { // Insert Lines
                // Clamped to the screen height: a server may send ESC[999999999L,
                // and scrolling more than one screen has no additional effect.
                val n = parameters.getOrElse(0) { 1 }.coerceIn(1, buffer.getRows())
                repeat(n) {
                    buffer.scrollDown()
                }
            }
            'M' -> { // Delete Lines
                val n = parameters.getOrElse(0) { 1 }.coerceIn(1, buffer.getRows())
                repeat(n) {
                    buffer.scrollUp()
                }
            }
            'P' -> { // Delete Characters
                // n is clamped to the line width: an unclamped count produced a
                // negative range start below and crashed on a hostile ESC[999P.
                val cols = buffer.getCols()
                val n = parameters.getOrElse(0) { 1 }.coerceIn(1, cols)
                val row = buffer.getCursorRow()
                val col = buffer.getCursorCol()
                val line = buffer.getLine(row)
                if (line != null) {
                    for (i in col until cols) {
                        line[i] = if (i + n < cols) line[i + n] else TerminalChar.empty()
                    }
                }
            }
            'S' -> { // Scroll Up
                val n = parameters.getOrElse(0) { 1 }.coerceIn(1, buffer.getRows())
                buffer.scrollUp(n)
            }
            'T' -> { // Scroll Down
                val n = parameters.getOrElse(0) { 1 }.coerceIn(1, buffer.getRows())
                buffer.scrollDown(n)
            }
            'm' -> { // Select Graphic Rendition (SGR)
                handleSGRSequence()
            }
            'r' -> { // Set Scrolling Region
                val top = (parameters.getOrElse(0) { 1 } - 1).coerceAtLeast(0)
                val bottom = (parameters.getOrElse(1) { buffer.getRows() } - 1).coerceAtMost(buffer.getRows() - 1)
                buffer.setScrollRegion(top, bottom)
            }
            's' -> { // Save Cursor Position
                buffer.saveCursor()
            }
            'u' -> { // Restore Cursor Position
                buffer.restoreCursor()
            }
            'h' -> { // Set Mode
                handleSetMode(true)
            }
            'l' -> { // Reset Mode
                handleSetMode(false)
            }
            else -> {
                Logger.d("ANSIParser", "Unhandled CSI sequence: ${escapeSequence}$finalChar")
            }
        }
    }
    
    private fun handleSGRSequence() {
        if (parameters.isEmpty()) {
            parameters.add(0) // Default to reset
        }
        
        var i = 0
        while (i < parameters.size) {
            when (val param = parameters[i]) {
                0 -> buffer.resetCharacterAttributes() // Reset
                1 -> buffer.setCharacterAttributes(bold = true) // Bold
                2 -> buffer.setCharacterAttributes(bold = false) // Dim (treat as non-bold)
                3 -> buffer.setCharacterAttributes() // Italic (not widely supported)
                4 -> buffer.setCharacterAttributes(underline = true) // Underline
                5, 6 -> buffer.setCharacterAttributes(blink = true) // Blink
                7 -> buffer.setCharacterAttributes(reverse = true) // Reverse
                8 -> { /* Conceal/hidden text - not supported */ }
                9 -> { /* Strikethrough - not supported */ }
                22 -> buffer.setCharacterAttributes(bold = false) // Normal intensity
                23 -> { /* Not italic */ }
                24 -> buffer.setCharacterAttributes(underline = false) // Not underlined
                25 -> buffer.setCharacterAttributes(blink = false) // Not blinking
                27 -> buffer.setCharacterAttributes(reverse = false) // Not reversed
                in 30..37 -> { // Foreground colors
                    buffer.setCharacterAttributes(fgColor = param - 30)
                }
                38 -> { // Extended foreground color
                    i = handleExtendedColor(i, true)
                }
                39 -> buffer.setCharacterAttributes(fgColor = 7) // Default foreground
                in 40..47 -> { // Background colors
                    buffer.setCharacterAttributes(bgColor = param - 40)
                }
                48 -> { // Extended background color
                    i = handleExtendedColor(i, false)
                }
                49 -> buffer.setCharacterAttributes(bgColor = 0) // Default background
                in 90..97 -> { // Bright foreground colors
                    buffer.setCharacterAttributes(fgColor = param - 90 + 8)
                }
                in 100..107 -> { // Bright background colors
                    buffer.setCharacterAttributes(bgColor = param - 100 + 8)
                }
                else -> {
                    Logger.d("ANSIParser", "Unhandled SGR parameter: $param")
                }
            }
            i++
        }
    }
    
    private fun handleExtendedColor(index: Int, isForeground: Boolean): Int {
        // Handle 256-color and RGB color sequences
        if (index + 1 < parameters.size) {
            when (parameters[index + 1]) {
                5 -> { // 256-color mode
                    if (index + 2 < parameters.size) {
                        val colorIndex = parameters[index + 2]
                        // Convert 256-color index to 16-color (simplified)
                        val color16 = when {
                            colorIndex < 8 -> colorIndex
                            colorIndex < 16 -> colorIndex
                            colorIndex < 232 -> {
                                // 216-color cube - map to nearest 16-color
                                val adjusted = colorIndex - 16
                                val r = (adjusted / 36) % 6
                                val g = (adjusted / 6) % 6
                                val b = adjusted % 6
                                // Simple mapping to 16 colors
                                when {
                                    r > 3 && g <= 3 && b <= 3 -> 1 // Red
                                    r <= 3 && g > 3 && b <= 3 -> 2 // Green
                                    r <= 3 && g <= 3 && b > 3 -> 4 // Blue
                                    r > 3 && g > 3 && b <= 3 -> 3 // Yellow
                                    r > 3 && g <= 3 && b > 3 -> 5 // Magenta
                                    r <= 3 && g > 3 && b > 3 -> 6 // Cyan
                                    r > 3 && g > 3 && b > 3 -> 7 // White
                                    else -> 0 // Black
                                }
                            }
                            else -> { // Grayscale
                                if (colorIndex < 244) 0 else 7
                            }
                        }
                        
                        if (isForeground) {
                            buffer.setCharacterAttributes(fgColor = color16)
                        } else {
                            buffer.setCharacterAttributes(bgColor = color16)
                        }
                        return index + 2
                    }
                }
                2 -> { // RGB mode
                    if (index + 4 < parameters.size) {
                        val r = parameters[index + 2]
                        val g = parameters[index + 3]
                        val b = parameters[index + 4]
                        
                        // Convert RGB to nearest 16-color (simplified)
                        val color16 = when {
                            r > 128 && g <= 128 && b <= 128 -> 1 // Red
                            r <= 128 && g > 128 && b <= 128 -> 2 // Green
                            r <= 128 && g <= 128 && b > 128 -> 4 // Blue
                            r > 128 && g > 128 && b <= 128 -> 3 // Yellow
                            r > 128 && g <= 128 && b > 128 -> 5 // Magenta
                            r <= 128 && g > 128 && b > 128 -> 6 // Cyan
                            r > 128 && g > 128 && b > 128 -> 7 // White
                            else -> 0 // Black
                        }
                        
                        if (isForeground) {
                            buffer.setCharacterAttributes(fgColor = color16)
                        } else {
                            buffer.setCharacterAttributes(bgColor = color16)
                        }
                        return index + 4
                    }
                }
            }
        }
        return index + 1
    }
    
    /**
     * Handle SM (ESC[...h) and RM (ESC[...l).
     *
     * The DEC private marker '?' selects a completely different mode namespace:
     * ESC[4h is insert mode while ESC[?4h is smooth scrolling, and ESC[7h is a
     * DECSET-only mode number. Dispatching both through one table let a server
     * toggle auto-wrap or origin mode with an ANSI sequence that means something
     * else entirely, so the two namespaces are now separated.
     */
    private fun handleSetMode(set: Boolean) {
        val decPrivate = intermediateChars.contains('?')
        for (param in parameters) {
            if (decPrivate) {
                when (param) {
                    6 -> buffer.setOriginMode(set)
                    7 -> buffer.setWrapMode(set)
                    1049 -> buffer.useAlternateScreen(set)
                    2004 -> buffer.setBracketedPasteMode(set)
                    else -> {
                        Logger.d("ANSIParser", "Unhandled DEC private mode: $param")
                    }
                }
            } else {
                when (param) {
                    4 -> buffer.setInsertMode(set)
                    else -> {
                        Logger.d("ANSIParser", "Unhandled ANSI mode: $param")
                    }
                }
            }
        }
    }
    
    /**
     * Accumulate an OSC payload until it is terminated by BEL or ST (ESC backslash).
     *
     * A bare backslash used to terminate the string, which truncated every title
     * or hyperlink containing one and left the remainder to be printed as text;
     * only the two-character ESC backslash sequence ends the string now.
     */
    private fun processOSCString(ch: Char) {
        if (stringEscapePending) {
            stringEscapePending = false
            if (ch == ST) {
                executeOSCCommand(currentParam.toString())
                resetParser()
                return
            }
            appendToStringPayload(ESC)
            if (parserState != ParserState.OSC_STRING) return
        }

        when (ch) {
            BEL -> {
                executeOSCCommand(currentParam.toString())
                resetParser()
            }
            ESC -> {
                stringEscapePending = true
            }
            else -> {
                appendToStringPayload(ch)
            }
        }
    }
    
    private fun executeOSCCommand(command: String) {
        // OSC commands typically start with a number followed by semicolon
        val parts = command.split(';', limit = 2)
        if (parts.size >= 2) {
            val commandNum = parts[0].toIntOrNull()
            val data = parts[1]

            // Payloads are never logged: OSC 52 carries clipboard contents, which
            // can hold a password the user copied from a manager.
            Logger.d("ANSIParser", "OSC command $commandNum (${data.length} chars)")

            when (commandNum) {
                0, 2 -> {
                    buffer.setTitle(sanitizeTitle(data))
                }
                1 -> {
                    // Icon name is not surfaced anywhere in the UI; accepted and ignored.
                }
                8 -> {
                    // OSC 8 hyperlink: data = "params;URI"
                    // An empty URI closes the current link; non-empty opens one.
                    val semiIdx = data.indexOf(';')
                    val uri = if (semiIdx >= 0) data.substring(semiIdx + 1) else data
                    buffer.setCurrentLinkUrl(sanitizeLinkUrl(uri))
                }
                else -> {
                    Logger.d("ANSIParser", "Unhandled OSC command $commandNum")
                }
            }
        }
    }
    
    /**
     * Consume a Device Control String. The payload is not interpreted, but it is
     * length-counted so an unterminated DCS cannot make the parser swallow the
     * rest of the session's output forever.
     */
    private fun processDCSString(ch: Char) {
        if (stringEscapePending) {
            stringEscapePending = false
            if (ch == ST) {
                resetParser()
                return
            }
            appendToStringPayload(ESC)
            if (parserState != ParserState.DCS_STRING) return
        }

        when (ch) {
            BEL -> resetParser()
            ESC -> stringEscapePending = true
            else -> appendToStringPayload(ch)
        }
    }

    /**
     * Append one character to the escape sequence trace.
     * @return true if the sequence exceeded [MAX_ESCAPE_LENGTH] and was abandoned.
     */
    private fun appendToEscapeSequence(ch: Char): Boolean {
        if (escapeSequence.length >= MAX_ESCAPE_LENGTH) {
            Logger.w("ANSIParser", "Escape sequence exceeded $MAX_ESCAPE_LENGTH chars, discarding")
            resetParser()
            return true
        }
        escapeSequence.append(ch)
        return false
    }

    /**
     * Append one character to an OSC/DCS payload, abandoning the string if it
     * grows past [MAX_STRING_LENGTH].
     */
    private fun appendToStringPayload(ch: Char) {
        if (currentParam.length >= MAX_STRING_LENGTH) {
            Logger.w("ANSIParser", "Control string exceeded $MAX_STRING_LENGTH chars, discarding")
            resetParser()
            return
        }
        currentParam.append(ch)
    }

    /**
     * Append a digit to the parameter being parsed. Digits past the value cap are
     * dropped so a long run cannot overflow Int (which silently parsed to 0).
     */
    private fun appendParamDigit(ch: Char) {
        if (currentParam.length < MAX_PARAM_VALUE.toString().length) {
            currentParam.append(ch)
        }
    }

    /**
     * Commit the parameter under construction, clamping both its value and the
     * total parameter count.
     */
    private fun addParameter() {
        val value = (currentParam.toString().toIntOrNull() ?: 0).coerceIn(0, MAX_PARAM_VALUE)
        currentParam.clear()
        if (parameters.size < MAX_PARAMETERS) {
            parameters.add(value)
        }
    }

    /**
     * Strip control characters from a server-supplied window title and cap its
     * length. The title is shown in the tab strip, so newlines or a multi-kilobyte
     * string would let a remote host wreck or spoof the local UI.
     */
    private fun sanitizeTitle(title: String): String =
        title.filter { it.code >= 0x20 && it.code != 0x7F }.take(MAX_TITLE_LENGTH)

    /**
     * Validate a server-supplied OSC 8 hyperlink target.
     *
     * @return the URL if it is safe to surface to the user, or null to drop it.
     */
    private fun sanitizeLinkUrl(uri: String): String? {
        if (uri.isBlank() || uri.length > MAX_LINK_URL_LENGTH) return null
        // Control characters would let a server spoof the "open this link" dialog.
        if (uri.any { it.code < 0x20 || it.code == 0x7F }) return null
        val scheme = uri.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || scheme !in ALLOWED_LINK_SCHEMES) {
            Logger.w("ANSIParser", "Dropping OSC 8 link with disallowed scheme")
            return null
        }
        return uri
    }

    private fun resetParser() {
        parserState = ParserState.NORMAL
        escapeSequence.clear()
        parameters.clear()
        currentParam.clear()
        intermediateChars.clear()
        stringEscapePending = false
    }
}
