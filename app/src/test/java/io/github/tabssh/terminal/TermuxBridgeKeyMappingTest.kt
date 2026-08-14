package io.github.tabssh.terminal

import android.view.KeyEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-mapping tests for the key-code translation and OSC 8 sanitiser in
 * [TermuxBridge]. Both are companion-level functions, so no Android runtime
 * or live SSH session is required.
 */
class TermuxBridgeKeyMappingTest {

    private fun seq(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false
    ): ByteArray = TermuxBridge.keySequenceFor(keyCode, ctrl, alt, shift)

    @Test
    fun `ctrl letters map through android key codes not ascii`() {
        // KEYCODE_A is 29, not 65: the old ASCII arithmetic produced nothing
        // here, so Ctrl+A / Ctrl+C never reached the remote.
        assertEquals(listOf<Byte>(1), seq(KeyEvent.KEYCODE_A, ctrl = true).toList())
        assertEquals(listOf<Byte>(3), seq(KeyEvent.KEYCODE_C, ctrl = true).toList())
        assertEquals(listOf<Byte>(26), seq(KeyEvent.KEYCODE_Z, ctrl = true).toList())
    }

    @Test
    fun `ctrl enter is not silently rewritten to another control code`() {
        // KEYCODE_ENTER is 66, which the old ASCII arithmetic turned into 0x02
        // (Ctrl+B). Enter has no control form, so nothing must be sent.
        assertTrue(seq(KeyEvent.KEYCODE_ENTER, ctrl = true).isEmpty())
    }

    @Test
    fun `ctrl punctuation covers the standard C0 codes`() {
        assertEquals(listOf<Byte>(0), seq(KeyEvent.KEYCODE_SPACE, ctrl = true).toList())
        assertEquals(listOf<Byte>(27), seq(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl = true).toList())
        assertEquals(listOf<Byte>(28), seq(KeyEvent.KEYCODE_BACKSLASH, ctrl = true).toList())
        assertEquals(listOf<Byte>(29), seq(KeyEvent.KEYCODE_RIGHT_BRACKET, ctrl = true).toList())
        assertEquals(listOf<Byte>(31), seq(KeyEvent.KEYCODE_MINUS, ctrl = true).toList())
    }

    @Test
    fun `alt sends escape followed by the printable character`() {
        // The old code sent ESC plus the raw Android key code (29 for A),
        // which is a group-separator control byte, not the letter.
        assertEquals(
            listOf<Byte>(0x1B, 'a'.code.toByte()),
            seq(KeyEvent.KEYCODE_A, alt = true).toList()
        )
        assertEquals(
            listOf<Byte>(0x1B, 'A'.code.toByte()),
            seq(KeyEvent.KEYCODE_A, alt = true, shift = true).toList()
        )
    }

    @Test
    fun `alt prefixes multi byte escape sequences`() {
        assertEquals(
            listOf<Byte>(0x1B, 0x1B, '['.code.toByte(), 'D'.code.toByte()),
            seq(KeyEvent.KEYCODE_DPAD_LEFT, alt = true).toList()
        )
    }

    @Test
    fun `plain printable keys emit their character`() {
        assertEquals("a", String(seq(KeyEvent.KEYCODE_A)))
        assertEquals("A", String(seq(KeyEvent.KEYCODE_A, shift = true)))
        assertEquals("7", String(seq(KeyEvent.KEYCODE_7)))
        assertEquals("&", String(seq(KeyEvent.KEYCODE_7, shift = true)))
        assertEquals("/", String(seq(KeyEvent.KEYCODE_SLASH)))
        assertEquals("?", String(seq(KeyEvent.KEYCODE_SLASH, shift = true)))
    }

    @Test
    fun `special keys keep their vt sequences`() {
        assertEquals(listOf<Byte>(0x1B, '['.code.toByte(), 'A'.code.toByte()),
            seq(KeyEvent.KEYCODE_DPAD_UP).toList())
        assertEquals(listOf<Byte>(13), seq(KeyEvent.KEYCODE_ENTER).toList())
        assertEquals(listOf<Byte>(9), seq(KeyEvent.KEYCODE_TAB).toList())
        assertEquals(listOf<Byte>(127), seq(KeyEvent.KEYCODE_DEL).toList())
        assertEquals(listOf<Byte>(0x1B), seq(KeyEvent.KEYCODE_ESCAPE).toList())
        assertEquals("OP", String(seq(KeyEvent.KEYCODE_F1)).substring(1))
    }

    @Test
    fun `unmapped key codes send nothing`() {
        assertTrue(seq(KeyEvent.KEYCODE_VOLUME_UP).isEmpty())
        assertTrue(seq(KeyEvent.KEYCODE_CAMERA, alt = true).isEmpty())
    }

    @Test
    fun `osc8 sanitiser accepts ordinary web and ssh targets`() {
        assertEquals("https://example.com/x", TermuxBridge.sanitizeOsc8Url("https://example.com/x"))
        assertEquals("ssh://host", TermuxBridge.sanitizeOsc8Url("  ssh://host  "))
        assertEquals("mailto:a@b.c", TermuxBridge.sanitizeOsc8Url("mailto:a@b.c"))
    }

    @Test
    fun `osc8 sanitiser rejects hostile targets`() {
        assertNull(TermuxBridge.sanitizeOsc8Url("javascript:alert(1)"))
        assertNull(TermuxBridge.sanitizeOsc8Url("file:///data/data/io.github.tabssh"))
        assertNull(TermuxBridge.sanitizeOsc8Url("intent://scan#Intent;scheme=zxing;end"))
        assertNull(TermuxBridge.sanitizeOsc8Url("not-a-url"))
        assertNull(TermuxBridge.sanitizeOsc8Url(""))
        assertNull(TermuxBridge.sanitizeOsc8Url("https://example.com/" + 7.toChar() + "bell"))
        assertNull(TermuxBridge.sanitizeOsc8Url("https://example.com/" + "a".repeat(4096)))
    }
}
