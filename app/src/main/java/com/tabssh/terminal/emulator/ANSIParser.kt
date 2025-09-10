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
    
    companion object {
        private const val ESC = '\u001B'
        private const val CSI = '['
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
        NORMAL,         // Normal text processing
        ESCAPE,         // ESC received, waiting for next char
        CSI_ENTRY,      // CSI sequence started
        CSI_PARAM,      // Reading CSI parameters
        CSI_INTERMEDIATE, // Reading intermediate chars
        OSC_STRING,     // Operating System Command string
        DCS_ENTRY,      // Device Control String
        DCS_PARAM,      // DCS parameters
        DCS_INTERMEDIATE, // DCS intermediate
        DCS_PASSTHROUGH  // DCS data
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
            ParserState.DCS_ENTRY -> processDCSEntry(ch)
            ParserState.DCS_PARAM -> processDCSParam(ch)
            ParserState.DCS_INTERMEDIATE -> processDCSIntermediate(ch)
            ParserState.DCS_PASSTHROUGH -> processDCSPassthrough(ch)
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
                if (ch >= ' ' || ch.code >= 160) { // Printable characters
                    buffer.writeChar(ch)
                }
                // Ignore other control characters
            }
        }
    }
    
    private fun processEscapeChar(ch: Char) {
        escapeSequence.append(ch)
        
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
            }
            DCS -> {
                parserState = ParserState.DCS_ENTRY
                parameters.clear()
                currentParam.clear()
                intermediateChars.clear()
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
                    buffer.setCursorPosition(newRow, buffer.getCursorCol())
                }
                resetParser()
            }
            'E' -> {
                // Next line (CR + LF)
                buffer.setCursorPosition(buffer.getCursorRow() + 1, 0)
                resetParser()
            }
            'M' -> {
                // Reverse index (move cursor up, scroll if needed)
                val newRow = buffer.getCursorRow() - 1
                if (newRow < 0) {
                    buffer.scrollDown()
                } else {
                    buffer.setCursorPosition(newRow, buffer.getCursorCol())
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
        escapeSequence.append(ch)
        
        when {
            ch in '0'..'9' -> {
                currentParam.append(ch)
                parserState = ParserState.CSI_PARAM
            }
            ch == ';' -> {
                parameters.add(if (currentParam.isEmpty()) 0 else currentParam.toString().toIntOrNull() ?: 0)
                currentParam.clear()
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
        escapeSequence.append(ch)
        
        when {
            ch in '0'..'9' -> {
                currentParam.append(ch)
            }
            ch == ';' -> {
                parameters.add(if (currentParam.isEmpty()) 0 else currentParam.toString().toIntOrNull() ?: 0)
                currentParam.clear()
            }
            ch in ' '..'/' -> {
                // Add current parameter if exists
                if (currentParam.isNotEmpty()) {
                    parameters.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam.clear()
                }
                intermediateChars.append(ch)
                parserState = ParserState.CSI_INTERMEDIATE
            }
            ch in '@'..'~' -> {
                // Add current parameter if exists
                if (currentParam.isNotEmpty()) {
                    parameters.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam.clear()
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
        escapeSequence.append(ch)
        
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
                buffer.setCursorPosition(buffer.getCursorRow() + n, 0)
            }
            'F' -> { // Cursor Previous Line
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.setCursorPosition(buffer.getCursorRow() - n, 0)
            }
            'G' -> { // Cursor Horizontal Absolute
                val col = parameters.getOrElse(0) { 1 } - 1
                buffer.setCursorPosition(buffer.getCursorRow(), col)
            }
            'H', 'f' -> { // Cursor Position
                val row = (parameters.getOrElse(0) { 1 } - 1).coerceAtLeast(0)
                val col = (parameters.getOrElse(1) { 1 } - 1).coerceAtLeast(0)
                buffer.setCursorPosition(row, col)
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
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                repeat(n) {
                    buffer.scrollDown()
                }
            }
            'M' -> { // Delete Lines
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                repeat(n) {
                    buffer.scrollUp()
                }
            }
            'P' -> { // Delete Characters
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                val row = buffer.getCursorRow()
                val col = buffer.getCursorCol()
                val line = buffer.getLine(row)
                if (line != null) {
                    // Shift characters left
                    for (i in col until buffer.getCols() - n) {
                        line[i] = if (i + n < buffer.getCols()) line[i + n] else TerminalChar.empty()
                    }
                    // Clear the end
                    for (i in (buffer.getCols() - n) until buffer.getCols()) {
                        line[i] = TerminalChar.empty()
                    }
                }
            }
            'S' -> { // Scroll Up
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
                buffer.scrollUp(n)
            }
            'T' -> { // Scroll Down
                val n = parameters.getOrElse(0) { 1 }.coerceAtLeast(1)
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
                8 -> { /* Conceal - not implemented */ }
                9 -> { /* Strikethrough - not implemented */ }
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
    
    private fun handleSetMode(set: Boolean) {
        for (param in parameters) {
            when (param) {
                4 -> buffer.setInsertMode(set) // Insert mode
                20 -> { /* Automatic newline mode - not implemented */ }
                1049 -> buffer.useAlternateScreen(set) // Alternate screen buffer
                25 -> { /* Cursor visible - would affect cursor rendering */ }
                7 -> buffer.setWrapMode(set) // Auto wrap mode
                6 -> buffer.setOriginMode(set) // Origin mode
                else -> {
                    Logger.d("ANSIParser", "Unhandled mode parameter: $param")
                }
            }
        }
    }
    
    private fun processOSCString(ch: Char) {
        when (ch) {
            BEL, ST -> {
                // Execute OSC command
                val command = currentParam.toString()
                executeOSCCommand(command)
                resetParser()
            }
            ESC -> {
                // Might be ESC \ (ST)
                if (currentParam.isNotEmpty()) {
                    currentParam.append(ch)
                }
            }
            else -> {
                currentParam.append(ch)
            }
        }
    }
    
    private fun executeOSCCommand(command: String) {
        Logger.d("ANSIParser", "OSC command: $command")
        
        // OSC commands typically start with a number followed by semicolon
        val parts = command.split(';', limit = 2)
        if (parts.size >= 2) {
            val commandNum = parts[0].toIntOrNull()
            val data = parts[1]
            
            when (commandNum) {
                0, 2 -> {
                    // Set window title
                    Logger.d("ANSIParser", "Setting window title: $data")
                    // This would notify UI to update title
                }
                1 -> {
                    // Set icon name
                    Logger.d("ANSIParser", "Setting icon name: $data")
                }
                else -> {
                    Logger.d("ANSIParser", "Unhandled OSC command $commandNum: $data")
                }
            }
        }
    }
    
    private fun processDCSEntry(ch: Char) {
        // Device Control String - not commonly used
        // For now, just consume until ST
        if (ch == ST || ch == BEL) {
            resetParser()
        }
    }
    
    private fun processDCSParam(ch: Char) {
        if (ch == ST || ch == BEL) {
            resetParser()
        }
    }
    
    private fun processDCSIntermediate(ch: Char) {
        if (ch == ST || ch == BEL) {
            resetParser()
        }
    }
    
    private fun processDCSPassthrough(ch: Char) {
        if (ch == ST || ch == BEL) {
            resetParser()
        }
    }
    
    private fun resetParser() {
        parserState = ParserState.NORMAL
        escapeSequence.clear()
        parameters.clear()
        currentParam.clear()
        intermediateChars.clear()
    }
}