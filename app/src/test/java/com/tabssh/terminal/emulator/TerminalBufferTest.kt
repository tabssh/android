package io.github.tabssh.terminal.emulator

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive tests for terminal buffer functionality
 */
class TerminalBufferTest {
    
    private lateinit var buffer: TerminalBuffer
    
    @Before
    fun setUp() {
        buffer = TerminalBuffer(rows = 24, cols = 80, maxScrollbackLines = 1000)
    }
    
    @Test
    fun `test buffer initialization`() {
        assertEquals(24, buffer.getRows())
        assertEquals(80, buffer.getCols())
        assertEquals(0, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())
        assertEquals(0, buffer.getScrollbackSize())
    }
    
    @Test
    fun `test character writing`() {
        buffer.writeChar('H')
        buffer.writeChar('i')
        
        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(0, 1)?.char)
        assertEquals(0, buffer.getCursorRow())
        assertEquals(2, buffer.getCursorCol())
    }
    
    @Test
    fun `test newline handling`() {
        buffer.writeChar('H')
        buffer.writeChar('\n')
        buffer.writeChar('i')
        
        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(1, 0)?.char)
        assertEquals(1, buffer.getCursorRow())
        assertEquals(1, buffer.getCursorCol())
    }
    
    @Test
    fun `test carriage return handling`() {
        buffer.writeString("Hello")
        buffer.writeChar('\r')
        buffer.writeString("Hi")
        
        // Should overwrite beginning of line
        assertEquals('H', buffer.getChar(0, 0)?.char)
        assertEquals('i', buffer.getChar(0, 1)?.char)
        assertEquals('l', buffer.getChar(0, 2)?.char) // Rest of "Hello" remains
    }
    
    @Test
    fun `test tab handling`() {
        buffer.writeChar('\t')
        assertEquals(8, buffer.getCursorCol()) // Tab stops at column 8
        
        buffer.writeChar('X')
        buffer.writeChar('\t')
        assertEquals(16, buffer.getCursorCol()) // Next tab stop
    }
    
    @Test
    fun `test line wrapping`() {
        // Fill a line completely
        val longString = "A".repeat(80)
        buffer.writeString(longString)
        
        assertEquals(0, buffer.getCursorRow())
        assertEquals(80, buffer.getCursorCol())
        
        // Next character should wrap to next line
        buffer.writeChar('B')
        assertEquals(1, buffer.getCursorRow())
        assertEquals(1, buffer.getCursorCol())
        assertEquals('B', buffer.getChar(1, 0)?.char)
    }
    
    @Test
    fun `test cursor positioning`() {
        buffer.setCursorPosition(5, 10)
        assertEquals(5, buffer.getCursorRow())
        assertEquals(10, buffer.getCursorCol())
        
        // Test bounds checking
        buffer.setCursorPosition(-1, -1)
        assertEquals(0, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())
        
        buffer.setCursorPosition(100, 100)
        assertEquals(23, buffer.getCursorRow()) // rows - 1
        assertEquals(79, buffer.getCursorCol()) // cols - 1
    }
    
    @Test
    fun `test cursor save and restore`() {
        buffer.setCursorPosition(10, 20)
        buffer.saveCursor()
        
        buffer.setCursorPosition(5, 15)
        assertEquals(5, buffer.getCursorRow())
        assertEquals(15, buffer.getCursorCol())
        
        buffer.restoreCursor()
        assertEquals(10, buffer.getCursorRow())
        assertEquals(20, buffer.getCursorCol())
    }
    
    @Test
    fun `test screen clearing`() {
        buffer.writeString("Hello World")
        buffer.clearScreen()
        
        val char = buffer.getChar(0, 0)
        assertEquals(' ', char?.char)
        assertEquals(0, buffer.getCursorRow())
        assertEquals(0, buffer.getCursorCol())
    }
    
    @Test
    fun `test scrolling up`() {
        // Fill screen with lines
        for (i in 0 until 24) {
            buffer.writeString("Line $i")
            if (i < 23) buffer.writeChar('\n')
        }
        
        val originalScrollbackSize = buffer.getScrollbackSize()
        buffer.scrollUp()
        
        // First line should move to scrollback
        assertEquals(originalScrollbackSize + 1, buffer.getScrollbackSize())
    }
    
    @Test
    fun `test resize functionality`() {
        buffer.writeString("Test content")
        
        buffer.resize(30, 100)
        assertEquals(30, buffer.getRows())
        assertEquals(100, buffer.getCols())
        
        // Content should be preserved where possible
        assertEquals('T', buffer.getChar(0, 0)?.char)
    }
    
    @Test
    fun `test alternate screen buffer`() {
        buffer.writeString("Main screen content")
        val originalContent = buffer.getScreenContent()
        
        // Switch to alternate screen
        buffer.useAlternateScreen(true)
        assertTrue(buffer.isAlternateScreen())
        
        // Write to alternate screen
        buffer.writeString("Alternate screen content")
        val alternateContent = buffer.getScreenContent()
        
        // Switch back to main screen
        buffer.useAlternateScreen(false)
        assertFalse(buffer.isAlternateScreen())
        
        // Main screen content should be restored
        assertTrue(buffer.getScreenContent().contains("Main screen"))
    }
    
    @Test
    fun `test character attributes`() {
        buffer.setCharacterAttributes(fgColor = 1, bgColor = 2, bold = true, underline = true)
        buffer.writeChar('X')
        
        val char = buffer.getChar(0, 0)!!
        assertEquals('X', char.char)
        assertEquals(1, char.fgColor)
        assertEquals(2, char.bgColor)
        assertTrue(char.isBold)
        assertTrue(char.isUnderline)
    }
    
    @Test
    fun `test scroll region`() {
        buffer.setScrollRegion(5, 15)
        
        // Cursor should move to origin if origin mode is set
        buffer.setCursorPosition(0, 0)
        
        // Scrolling should only affect the specified region
        buffer.scrollUp()
        // Test would verify only the region scrolled
    }
    
    @Test
    fun `test terminal modes`() {
        assertFalse(buffer.isInsertMode())
        buffer.setInsertMode(true)
        assertTrue(buffer.isInsertMode())
        
        assertFalse(buffer.isOriginMode())
        buffer.setOriginMode(true)
        assertTrue(buffer.isOriginMode())
        
        assertTrue(buffer.isWrapMode())
        buffer.setWrapMode(false)
        assertFalse(buffer.isWrapMode())
    }
    
    @Test
    fun `test dirty tracking`() {
        assertFalse(buffer.isLineDirty(0))
        
        buffer.writeChar('X')
        assertTrue(buffer.isLineDirty(0))
        
        buffer.clearDirtyFlags()
        assertFalse(buffer.isLineDirty(0))
    }
    
    @Test
    fun `test scrollback management`() {
        val maxScrollback = 5
        val smallBuffer = TerminalBuffer(3, 10, maxScrollback)
        
        // Fill more lines than scrollback can hold
        for (i in 0..10) {
            smallBuffer.writeString("Line $i")
            smallBuffer.writeChar('\n')
        }
        
        // Scrollback should be limited to max size
        assertTrue(smallBuffer.getScrollbackSize() <= maxScrollback)
    }
    
    @Test
    fun `test terminal char functionality`() {
        val char = TerminalChar('A', 1, 2, true, false, true, false)
        
        assertEquals('A', char.char)
        assertEquals(1, char.fgColor)
        assertEquals(2, char.bgColor)
        assertTrue(char.isBold)
        assertFalse(char.isUnderline)
        assertTrue(char.isReverse)
        
        // Test effective colors with reverse
        assertEquals(2, char.getEffectiveFgColor()) // bgColor due to reverse
        assertEquals(1, char.getEffectiveBgColor()) // fgColor due to reverse
        
        assertTrue(char.hasFormatting())
        assertTrue(char.hasCustomColors())
        assertTrue(char.isPrintable())
        assertFalse(char.isWhitespace())
    }
    
    @Test
    fun `test terminal char factory methods`() {
        val empty = TerminalChar.empty()
        assertEquals(' ', empty.char)
        assertEquals(TerminalChar.DEFAULT_FG, empty.fgColor)
        assertEquals(TerminalChar.DEFAULT_BG, empty.bgColor)
        
        val withFg = TerminalChar.withFg('X', 5)
        assertEquals('X', withFg.char)
        assertEquals(5, withFg.fgColor)
        assertEquals(TerminalChar.DEFAULT_BG, withFg.bgColor)
        
        val withColors = TerminalChar.withColors('Y', 3, 7)
        assertEquals('Y', withColors.char)
        assertEquals(3, withColors.fgColor)
        assertEquals(7, withColors.bgColor)
    }
    
    @Test
    fun `test terminal char modifications`() {
        val original = TerminalChar('A', 1, 0)
        
        val withAttrs = original.withAttributes(bold = true, underline = true)
        assertEquals('A', withAttrs.char)
        assertTrue(withAttrs.isBold)
        assertTrue(withAttrs.isUnderline)
        
        val withNewColors = original.withColors(newFgColor = 5, newBgColor = 3)
        assertEquals(5, withNewColors.fgColor)
        assertEquals(3, withNewColors.bgColor)
        
        val withNewChar = original.withChar('B')
        assertEquals('B', withNewChar.char)
        assertEquals(1, withNewChar.fgColor) // Original colors preserved
        
        val reset = withAttrs.resetFormatting()
        assertFalse(reset.isBold)
        assertFalse(reset.isUnderline)
        assertEquals(TerminalChar.DEFAULT_FG, reset.fgColor)
    }
    
    @Test
    fun `test visual identity comparison`() {
        val char1 = TerminalChar('A', 1, 0, true, false)
        val char2 = TerminalChar('A', 1, 0, true, false)
        val char3 = TerminalChar('B', 1, 0, true, false)
        val char4 = TerminalChar('A', 2, 0, true, false)
        
        assertTrue(char1.isVisuallyIdentical(char2))
        assertFalse(char1.isVisuallyIdentical(char3)) // Different character
        assertFalse(char1.isVisuallyIdentical(char4)) // Different color
        
        assertEquals(char1.visualHashCode(), char2.visualHashCode())
    }
}