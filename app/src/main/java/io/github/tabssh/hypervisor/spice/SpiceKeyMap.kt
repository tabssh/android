package io.github.tabssh.hypervisor.spice

import android.view.KeyEvent

/**
 * Translate Android [KeyEvent] key codes to PS/2 Set 1 scancodes for
 * the SPICE inputs channel.
 *
 * Printable ASCII characters map through a lookup table keyed by their
 * Unicode code point; the corresponding shift state is returned as a
 * separate flag so the caller can bracket the make code with
 * left-shift press/release when needed.
 *
 * This is deliberately not exhaustive — it covers the keys a mobile
 * user is likely to send from a hardware or software keyboard. Media
 * keys, keypad-specific codes, and locale-specific extras (yen,
 * dead-key composers) are absent; a follow-up can extend the table as
 * feedback arrives from real hardware use.
 */
object SpiceKeyMap {

    /**
     * Result of a key translation. [scancode] is the value to hand to
     * [SpiceClient.sendKeyEvent]. [needsShift] is true when the
     * character requires the shift modifier — the caller should send a
     * shift make code before and a shift release after the key event
     * pair so the guest sees the correct character.
     */
    data class Translation(val scancode: Int, val needsShift: Boolean = false)

    /**
     * Map an Android key code + KeyEvent (used only for its
     * `unicodeChar` fallback when the key code alone is ambiguous) to
     * a PS/2 scancode. Returns null when the key has no mapping — the
     * caller should ignore it rather than send garbage.
     */
    fun translate(keyCode: Int, event: KeyEvent): Translation? {
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> return Translation(SpiceConstants.SC_ESC)
            KeyEvent.KEYCODE_DEL -> return Translation(SpiceConstants.SC_BACKSPACE)
            KeyEvent.KEYCODE_TAB -> return Translation(SpiceConstants.SC_TAB)
            KeyEvent.KEYCODE_ENTER -> return Translation(SpiceConstants.SC_ENTER)
            KeyEvent.KEYCODE_SPACE -> return Translation(SpiceConstants.SC_SPACE)
            KeyEvent.KEYCODE_CAPS_LOCK -> return Translation(SpiceConstants.SC_CAPSLOCK)
            KeyEvent.KEYCODE_SHIFT_LEFT -> return Translation(SpiceConstants.SC_LEFT_SHIFT)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return Translation(SpiceConstants.SC_RIGHT_SHIFT)
            KeyEvent.KEYCODE_CTRL_LEFT -> return Translation(SpiceConstants.SC_LEFT_CTRL)
            KeyEvent.KEYCODE_CTRL_RIGHT -> return Translation(SpiceConstants.SC_RIGHT_CTRL)
            KeyEvent.KEYCODE_ALT_LEFT -> return Translation(SpiceConstants.SC_LEFT_ALT)
            KeyEvent.KEYCODE_ALT_RIGHT -> return Translation(SpiceConstants.SC_RIGHT_ALT)
            KeyEvent.KEYCODE_META_LEFT -> return Translation(SpiceConstants.SC_LEFT_META)
            KeyEvent.KEYCODE_META_RIGHT -> return Translation(SpiceConstants.SC_RIGHT_META)
            KeyEvent.KEYCODE_INSERT -> return Translation(SpiceConstants.SC_INSERT)
            KeyEvent.KEYCODE_FORWARD_DEL -> return Translation(SpiceConstants.SC_DELETE)
            KeyEvent.KEYCODE_MOVE_HOME -> return Translation(SpiceConstants.SC_HOME)
            KeyEvent.KEYCODE_MOVE_END -> return Translation(SpiceConstants.SC_END)
            KeyEvent.KEYCODE_PAGE_UP -> return Translation(SpiceConstants.SC_PAGE_UP)
            KeyEvent.KEYCODE_PAGE_DOWN -> return Translation(SpiceConstants.SC_PAGE_DOWN)
            KeyEvent.KEYCODE_DPAD_UP -> return Translation(SpiceConstants.SC_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> return Translation(SpiceConstants.SC_DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> return Translation(SpiceConstants.SC_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return Translation(SpiceConstants.SC_RIGHT)
            KeyEvent.KEYCODE_F1 -> return Translation(SpiceConstants.SC_F1)
            KeyEvent.KEYCODE_F2 -> return Translation(SpiceConstants.SC_F2)
            KeyEvent.KEYCODE_F3 -> return Translation(SpiceConstants.SC_F3)
            KeyEvent.KEYCODE_F4 -> return Translation(SpiceConstants.SC_F4)
            KeyEvent.KEYCODE_F5 -> return Translation(SpiceConstants.SC_F5)
            KeyEvent.KEYCODE_F6 -> return Translation(SpiceConstants.SC_F6)
            KeyEvent.KEYCODE_F7 -> return Translation(SpiceConstants.SC_F7)
            KeyEvent.KEYCODE_F8 -> return Translation(SpiceConstants.SC_F8)
            KeyEvent.KEYCODE_F9 -> return Translation(SpiceConstants.SC_F9)
            KeyEvent.KEYCODE_F10 -> return Translation(SpiceConstants.SC_F10)
            KeyEvent.KEYCODE_F11 -> return Translation(SpiceConstants.SC_F11)
            KeyEvent.KEYCODE_F12 -> return Translation(SpiceConstants.SC_F12)
        }
        val ch = event.unicodeChar
        if (ch <= 0) return null
        return translateChar(ch.toChar())
    }

    /**
     * Map a Unicode code point (BMP printable range) to a PS/2 make
     * code. Uppercase letters and shifted punctuation set
     * [Translation.needsShift]. Returns null for anything not in the
     * US-QWERTY layout — non-Latin scripts are unreachable via PS/2
     * scancodes and must go through the SPICE agent clipboard path.
     */
    fun translateChar(ch: Char): Translation? {
        val lower = ch.lowercaseChar()
        val shift = ch.isUpperCase() || SHIFTED_PUNCTUATION.contains(ch)
        val code = LOWER_MAP[lower] ?: return null
        return Translation(code, shift)
    }

    /*
     * Base PS/2 Set 1 make codes for lowercase letters, digits,
     * and unshifted punctuation on a US QWERTY layout.
     */
    private val LOWER_MAP: Map<Char, Int> = mapOf(
        '1' to 0x02, '2' to 0x03, '3' to 0x04, '4' to 0x05, '5' to 0x06,
        '6' to 0x07, '7' to 0x08, '8' to 0x09, '9' to 0x0A, '0' to 0x0B,
        '-' to 0x0C, '=' to 0x0D,
        'q' to 0x10, 'w' to 0x11, 'e' to 0x12, 'r' to 0x13, 't' to 0x14,
        'y' to 0x15, 'u' to 0x16, 'i' to 0x17, 'o' to 0x18, 'p' to 0x19,
        '[' to 0x1A, ']' to 0x1B,
        'a' to 0x1E, 's' to 0x1F, 'd' to 0x20, 'f' to 0x21, 'g' to 0x22,
        'h' to 0x23, 'j' to 0x24, 'k' to 0x25, 'l' to 0x26,
        ';' to 0x27, '\'' to 0x28, '`' to 0x29, '\\' to 0x2B,
        'z' to 0x2C, 'x' to 0x2D, 'c' to 0x2E, 'v' to 0x2F, 'b' to 0x30,
        'n' to 0x31, 'm' to 0x32,
        ',' to 0x33, '.' to 0x34, '/' to 0x35,
        ' ' to SpiceConstants.SC_SPACE,
    )

    /*
     * Characters that share a physical key with an unshifted glyph
     * and require the shift modifier to be sent alongside. Uppercase
     * letters are covered by isUpperCase; everything else is listed
     * here.
     */
    private val SHIFTED_PUNCTUATION: Set<Char> = setOf(
        '!', '@', '#', '$', '%', '^', '&', '*', '(', ')',
        '_', '+', '{', '}', '|', ':', '"', '~', '<', '>', '?',
    )
}
