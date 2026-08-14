package io.github.tabssh.terminal.emulator

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile-input tests for [ANSIParser] against a real [TerminalBuffer].
 *
 * Everything here models output a remote host controls: over-long escape
 * sequences, huge parameters, unterminated OSC strings, and hyperlink targets
 * pointing at non-web schemes.
 */
class ANSIParserHardeningTest {

    private val esc = 27.toChar()
    private val bel = 7.toChar()

    private lateinit var buffer: TerminalBuffer
    private lateinit var parser: ANSIParser

    @Before
    fun setUp() {
        buffer = TerminalBuffer(24, 80)
        parser = ANSIParser(buffer)
    }

    private fun csi(tail: String) = "$esc[$tail"

    private fun osc(payload: String) = "$esc]$payload$bel"

    @Test
    fun `cursor positioning maps row and column the right way round`() {
        parser.processText(csi("10;20H"))
        assertEquals(9, buffer.getCursorRow())
        assertEquals(19, buffer.getCursorCol())
    }

    @Test
    fun `huge delete character count does not throw`() {
        parser.processText("abcdef")
        parser.processText(csi("1;1H"))
        parser.processText(csi("999999P"))
        assertEquals(' ', buffer.getLine(0)?.get(0)?.char)
    }

    @Test
    fun `huge insert and delete line counts do not throw`() {
        parser.processText(csi("999999L"))
        parser.processText(csi("999999M"))
        parser.processText(csi("999999S"))
        parser.processText(csi("999999T"))
        assertEquals(24, buffer.getRows())
    }

    @Test
    fun `an over long escape sequence is abandoned and following text renders`() {
        // A remote that never terminates a CSI used to accumulate its
        // parameters without bound.
        parser.processText(csi("1".repeat(20000)))
        parser.processText("OK")
        val rendered = (0 until buffer.getRows())
            .mapNotNull { buffer.getLine(it) }
            .joinToString("") { line -> line.joinToString("") { it.char.toString() } }
        assertTrue(rendered.contains("OK"), "parser never recovered from the unterminated sequence")
    }

    @Test
    fun `osc titles are sanitised and length capped`() {
        val hostile = "title" + 7.toChar() + 27.toChar() + "x".repeat(1000)
        parser.processText(osc("0;$hostile"))
        val title = buffer.getTitle()
        assertTrue(title.length <= 256, "title was not capped: ${title.length}")
        assertFalse(title.any { it.code < 0x20 || it.code == 0x7F }, "control chars survived sanitising")
    }

    @Test
    fun `an osc string is not terminated by a bare backslash`() {
        // Only BEL or ESC-backslash end a string; a lone backslash inside a
        // title used to truncate it and dump the rest into the screen.
        parser.processText("$esc]0;a\\b$bel")
        assertEquals("a\\b", buffer.getTitle())
    }

    @Test
    fun `osc 8 hyperlinks with a non web scheme are dropped`() {
        parser.processText("$esc]8;;javascript:alert(1)$esc\\")
        parser.processText("click")
        parser.processText("$esc]8;;$esc\\")
        assertNull(buffer.getUrlAt(0, 0))
    }

    @Test
    fun `osc 8 hyperlinks with an allowed scheme are kept`() {
        parser.processText("$esc]8;;https://example.com$esc\\")
        parser.processText("click")
        parser.processText("$esc]8;;$esc\\")
        assertEquals("https://example.com", buffer.getUrlAt(0, 0))
    }

    @Test
    fun `dec private and ansi modes use separate tables`() {
        parser.processText(csi("?2004h"))
        assertTrue(buffer.isBracketedPasteModeActive())
        parser.processText(csi("?2004l"))
        assertFalse(buffer.isBracketedPasteModeActive())

        // ESC[4h is ANSI insert mode, not a DEC private mode: it must not be
        // routed through the private-mode table.
        parser.processText(csi("4h"))
        assertFalse(buffer.isBracketedPasteModeActive())
    }
}
