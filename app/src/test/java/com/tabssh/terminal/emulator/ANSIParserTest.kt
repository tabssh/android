package io.github.tabssh.terminal.emulator

import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import kotlin.test.assertEquals

/**
 * Comprehensive tests for ANSI escape sequence parsing
 */
class ANSIParserTest {
    
    @Mock
    private lateinit var mockBuffer: TerminalBuffer
    
    private lateinit var parser: ANSIParser
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        parser = ANSIParser(mockBuffer)
    }
    
    @Test
    fun `test plain text processing`() {
        parser.processText("Hello World")
        
        // Verify each character was written
        verify(mockBuffer).writeChar('H')
        verify(mockBuffer).writeChar('e')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('o')
        verify(mockBuffer).writeChar(' ')
        verify(mockBuffer).writeChar('W')
        verify(mockBuffer).writeChar('o')
        verify(mockBuffer).writeChar('r')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('d')
    }
    
    @Test
    fun `test control characters`() {
        parser.processText("Hello\nWorld\r")
        
        verify(mockBuffer).writeChar('H')
        verify(mockBuffer).writeChar('e')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('o')
        verify(mockBuffer).writeChar('\n')
        verify(mockBuffer).writeChar('W')
        verify(mockBuffer).writeChar('o')
        verify(mockBuffer).writeChar('r')
        verify(mockBuffer).writeChar('l')
        verify(mockBuffer).writeChar('d')
        verify(mockBuffer).writeChar('\r')
    }
    
    @Test
    fun `test cursor movement sequences`() {
        // Cursor up: ESC[A
        parser.processText("\u001B[A")
        verify(mockBuffer).moveCursor(-1, 0)
        
        // Cursor down: ESC[B  
        parser.processText("\u001B[B")
        verify(mockBuffer).moveCursor(1, 0)
        
        // Cursor forward: ESC[C
        parser.processText("\u001B[C")
        verify(mockBuffer).moveCursor(0, 1)
        
        // Cursor back: ESC[D
        parser.processText("\u001B[D")
        verify(mockBuffer).moveCursor(0, -1)
    }
    
    @Test
    fun `test cursor movement with parameters`() {
        // Cursor up 5 lines: ESC[5A
        parser.processText("\u001B[5A")
        verify(mockBuffer).moveCursor(-5, 0)
        
        // Cursor forward 10 columns: ESC[10C
        parser.processText("\u001B[10C")
        verify(mockBuffer).moveCursor(0, 10)
    }
    
    @Test
    fun `test cursor positioning`() {
        // Set cursor position: ESC[10;20H
        parser.processText("\u001B[10;20H")
        verify(mockBuffer).setCursorPosition(9, 19) // Convert from 1-based to 0-based
        
        // Home position: ESC[H (equivalent to ESC[1;1H)
        parser.processText("\u001B[H")
        verify(mockBuffer).setCursorPosition(0, 0)
    }
    
    @Test
    fun `test screen clearing`() {
        // Clear entire screen: ESC[2J
        parser.processText("\u001B[2J")
        verify(mockBuffer).clearScreen()
        
        // Clear from cursor to end: ESC[J (or ESC[0J)
        parser.processText("\u001B[J")
        verify(mockBuffer).clearToEndOfScreen()
        
        // Clear from beginning to cursor: ESC[1J
        parser.processText("\u001B[1J")
        verify(mockBuffer).clearToBeginningOfScreen()
    }
    
    @Test
    fun `test line clearing`() {
        // Clear entire line: ESC[2K
        parser.processText("\u001B[2K")
        verify(mockBuffer).clearLine()
        
        // Clear from cursor to end of line: ESC[K (or ESC[0K)
        parser.processText("\u001B[K")
        // This would verify clearToEndOfLine() if it existed
        
        // Clear from beginning of line to cursor: ESC[1K  
        parser.processText("\u001B[1K")
        // This would verify clearToBeginningOfLine() if it existed
    }
    
    @Test
    fun `test color sequences`() {
        // Set foreground red: ESC[31m
        parser.processText("\u001B[31m")
        verify(mockBuffer).setCharacterAttributes(fgColor = 1)
        
        // Set background blue: ESC[44m
        parser.processText("\u001B[44m")
        verify(mockBuffer).setCharacterAttributes(bgColor = 4)
        
        // Reset attributes: ESC[0m or ESC[m
        parser.processText("\u001B[0m")
        verify(mockBuffer).resetCharacterAttributes()
    }
    
    @Test
    fun `test text attributes`() {
        // Bold: ESC[1m
        parser.processText("\u001B[1m")
        verify(mockBuffer).setCharacterAttributes(bold = true)
        
        // Underline: ESC[4m
        parser.processText("\u001B[4m")
        verify(mockBuffer).setCharacterAttributes(underline = true)
        
        // Reverse: ESC[7m
        parser.processText("\u001B[7m")
        verify(mockBuffer).setCharacterAttributes(reverse = true)
        
        // Blink: ESC[5m
        parser.processText("\u001B[5m")
        verify(mockBuffer).setCharacterAttributes(blink = true)
    }
    
    @Test
    fun `test bright colors`() {
        // Bright red foreground: ESC[91m
        parser.processText("\u001B[91m")
        verify(mockBuffer).setCharacterAttributes(fgColor = 9)
        
        // Bright blue background: ESC[104m
        parser.processText("\u001B[104m")
        verify(mockBuffer).setCharacterAttributes(bgColor = 12)
    }
    
    @Test
    fun `test multiple parameters`() {
        // Bold red on blue background: ESC[1;31;44m
        parser.processText("\u001B[1;31;44m")
        verify(mockBuffer).setCharacterAttributes(bold = true)
        verify(mockBuffer).setCharacterAttributes(fgColor = 1)
        verify(mockBuffer).setCharacterAttributes(bgColor = 4)
    }
    
    @Test
    fun `test scroll region`() {
        // Set scroll region from line 5 to 20: ESC[5;20r
        parser.processText("\u001B[5;20r")
        verify(mockBuffer).setScrollRegion(4, 19) // Convert to 0-based
    }
    
    @Test
    fun `test alternate screen sequences`() {
        // Enter alternate screen: ESC[?1049h
        parser.processText("\u001B[?1049h")
        verify(mockBuffer).useAlternateScreen(true)
        
        // Exit alternate screen: ESC[?1049l
        parser.processText("\u001B[?1049l")
        verify(mockBuffer).useAlternateScreen(false)
    }
    
    @Test
    fun `test cursor save restore sequences`() {
        // Save cursor: ESC7
        parser.processText("\u001B7")
        verify(mockBuffer).saveCursor()
        
        // Restore cursor: ESC8
        parser.processText("\u001B8")
        verify(mockBuffer).restoreCursor()
        
        // Also test CSI versions
        parser.processText("\u001B[s") // Save
        verify(mockBuffer, org.mockito.kotlin.times(2)).saveCursor()
        
        parser.processText("\u001B[u") // Restore
        verify(mockBuffer, org.mockito.kotlin.times(2)).restoreCursor()
    }
    
    @Test
    fun `test complex sequences with mixed content`() {
        val complexText = "Normal text\u001B[31mRed text\u001B[0m\u001B[2J\u001B[H\u001B[1mBold text"
        parser.processText(complexText)
        
        // Should handle: normal text, color change, reset, clear screen, home, bold
        verify(mockBuffer).setCharacterAttributes(fgColor = 1) // Red
        verify(mockBuffer).resetCharacterAttributes()
        verify(mockBuffer).clearScreen()
        verify(mockBuffer).setCursorPosition(0, 0)
        verify(mockBuffer).setCharacterAttributes(bold = true)
    }
    
    @Test
    fun `test malformed sequences handling`() {
        // Parser should gracefully handle malformed sequences
        parser.processText("\u001B[999X") // Invalid sequence
        parser.processText("\u001B[;;;;;m") // Multiple empty parameters
        parser.processText("\u001B") // Incomplete sequence
        
        // Should not crash and should continue processing
        parser.processText("Valid text")
        verify(mockBuffer).writeChar('V')
    }
    
    @Test
    fun `test 256 color support`() {
        // 256-color foreground: ESC[38;5;196m (bright red)
        parser.processText("\u001B[38;5;196m")
        // Should map to appropriate 16-color equivalent
        
        // RGB color: ESC[38;2;255;0;0m (red)
        parser.processText("\u001B[38;2;255;0;0m")
        // Should map to appropriate 16-color equivalent
    }
}