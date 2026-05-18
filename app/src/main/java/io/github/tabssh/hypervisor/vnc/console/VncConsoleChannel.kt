package io.github.tabssh.hypervisor.vnc.console

import android.view.KeyEvent
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.console.rfb.RfbListener

/**
 * Keyboard + resize bridge between the Android UI and an [RfbClient] running in
 * console mode.
 *
 * Responsibilities:
 *  - Translate Android [KeyEvent]s to X11 keysyms and send them as RFB KeyEvent
 *    messages (message type 4), including correct modifier sequencing per
 *    RFB §7.5.4.
 *  - Parse ANSI / VT220 escape sequences sent by the custom keyboard bar
 *    ([sendSequence]) and map them to the appropriate X11 keysyms.
 *  - Convert Unicode characters to X11 keysyms ([charToKeysym]).
 *  - Translate terminal grid resize events to RFB SetDesktopSize messages
 *    ([resize]).
 *
 * Usage:
 * ```
 * val channel = VncConsoleChannel(rfbClient)
 * channel.setInitialSize(cols, rows)
 * rfbClient.listener = channel.wrapListener(vncView.asRfbListener())
 * rfbClient.start()
 * ```
 *
 * Thread safety: all public methods are safe to call from any thread; they
 * delegate to [RfbClient] which synchronises on its output lock.
 */
class VncConsoleChannel(private val rfbClient: RfbClient) {

    /** Pixel width of one terminal character cell (used for SetDesktopSize). */
    var fontW: Int = 8

    /** Pixel height of one terminal character cell (used for SetDesktopSize). */
    var fontH: Int = 16

    private var initialCols: Int = 80
    private var initialRows: Int = 24

    /**
     * Record the initial terminal size.  Call before [rfbClient] is started.
     * The size is sent via [RfbClient.sendSetDesktopSize] once the server is
     * ready (inside [wrapListener]'s [RfbListener.onConnected] override).
     */
    fun setInitialSize(cols: Int, rows: Int) {
        initialCols = cols.coerceAtLeast(1)
        initialRows = rows.coerceAtLeast(1)
    }

    /**
     * Wrap [delegate] with a listener that sends [RfbClient.sendSetDesktopSize]
     * once the server signals ExtendedDesktopSize support ([RfbListener.onExtendedDesktopSizeReady]).
     * Pass the result to [rfbClient].listener before calling [rfbClient].start().
     */
    fun wrapListener(delegate: RfbListener): RfbListener = object : RfbListener by delegate {
        override fun onConnected(width: Int, height: Int, name: String, framebuffer: IntArray) {
            delegate.onConnected(width, height, name, framebuffer)
            // Do NOT send SetDesktopSize here.  We wait for the server to send
            // an unsolicited ExtendedDesktopSize rect (reason=0) via
            // onExtendedDesktopSizeReady() before requesting a specific size.
            // Sending unconditionally causes QEMU to reject the request and
            // close the connection (reason=1/status=3 → EOF).
        }

        override fun onExtendedDesktopSizeReady() {
            // Server confirmed ExtendedDesktopSize support — request the
            // desired terminal size now.
            rfbClient.sendSetDesktopSize(initialCols * fontW, initialRows * fontH)
            delegate.onExtendedDesktopSizeReady()
        }
    }

    /**
     * Translate a terminal grid resize to a SetDesktopSize message.
     * Safe to call any time after [rfbClient].start(); the message is only
     * sent when both dimensions are positive.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) {
            rfbClient.sendSetDesktopSize(cols * fontW, rows * fontH)
        }
    }

    /**
     * Send a single keysym as a down+up pair.
     */
    fun sendKey(keysym: Long) {
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
    }

    /**
     * Translate an Android [KeyEvent] to RFB key events and send them.
     *
     * Handles [KeyEvent.ACTION_DOWN] only (modifier state is read from the
     * event's metaState, so held modifiers are captured correctly).  Returns
     * true when the event was consumed.
     *
     * Modifier sequencing per RFB §7.5.4:
     *   modifier-down → key-down → key-up → modifier-up
     */
    fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val keysym = androidKeyToKeysym(event.keyCode, event) ?: return false

        // Modifier key events are ignored: modifier state is captured via
        // event.isCtrlPressed / isShiftPressed / isAltPressed when a non-modifier
        // key is pressed, so there is no need to track modifier down/up separately.
        if (isModifierKey(event.keyCode)) return true

        val mods = collectModifiers(event)
        for (mod in mods) rfbClient.sendKeyEvent(mod, true)
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
        for (mod in mods.reversed()) rfbClient.sendKeyEvent(mod, false)
        return true
    }

    /**
     * Send a raw byte sequence produced by the custom keyboard bar.
     * Plain characters are sent as Unicode keysyms; ANSI/VT220 escape
     * sequences are decoded to the corresponding X11 keysyms.
     *
     * An empty string is treated as Escape (keyboard bar convention).
     */
    fun sendSequence(seq: String) {
        when {
            seq.isEmpty()            -> sendKey(RfbConstants.KEY_ESCAPE)
            seq == "\t"              -> sendKey(RfbConstants.KEY_TAB)
            seq == "\r" || seq == "\n" -> sendKey(RfbConstants.KEY_RETURN)
            seq.startsWith("") -> parseEscapeSequence(seq)
            seq.length == 1          -> sendCharKey(seq[0])
            else                     -> seq.forEach { sendCharKey(it) }
        }
    }

    /**
     * Send a string of plain text as a series of Unicode keysyms.
     */
    fun sendText(text: String) {
        text.forEach { sendCharKey(it) }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun sendCharKey(ch: Char) {
        val keysym = charToKeysym(ch)
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
    }

    private fun parseEscapeSequence(seq: String) {
        when {
            // CSI (ESC [)
            seq.startsWith("[") -> parseCsiSequence(seq.removePrefix("["))
            // SS3 (ESC O)
            seq.startsWith("O") -> parseSs3Sequence(seq.removePrefix("O"))
            // Alt+key (ESC + single char)
            seq.length == 2 -> {
                rfbClient.sendKeyEvent(RfbConstants.KEY_ALT_L, true)
                sendCharKey(seq[1])
                rfbClient.sendKeyEvent(RfbConstants.KEY_ALT_L, false)
            }
            // Bare ESC
            seq == "" -> sendKey(RfbConstants.KEY_ESCAPE)
        }
    }

    /**
     * Decode a CSI body (the part after ESC[) and send the corresponding keysym.
     *
     * Handles:
     *   - Cursor keys: A (up), B (down), C (right), D (left)
     *   - Navigation: 1~/H (Home), 4~/F (End), 5~ (PgUp), 6~ (PgDn), 2~ (Ins), 3~ (Del)
     *   - Function keys: 11~–24~ (F1–F12)
     *   - Modifier variants: ESC[1;Na where N = VT220 modifier bitmask + 1
     *     (e.g. "1;5A" = Ctrl+Up; body contains ';' for modifier forms)
     */
    private fun parseCsiSequence(body: String) {
        val hasModifier = body.contains(';')
        when {
            // Cursor keys — bare or with VT220 modifier (e.g. "A", "1;5A")
            body == "A" || (hasModifier && body.endsWith("A")) ->
                sendWithCsiModifier(body, RfbConstants.KEY_UP)
            body == "B" || (hasModifier && body.endsWith("B")) ->
                sendWithCsiModifier(body, RfbConstants.KEY_DOWN)
            body == "C" || (hasModifier && body.endsWith("C")) ->
                sendWithCsiModifier(body, RfbConstants.KEY_RIGHT)
            body == "D" || (hasModifier && body.endsWith("D")) ->
                sendWithCsiModifier(body, RfbConstants.KEY_LEFT)
            // Navigation
            body == "1~" || body == "H" -> sendKey(RfbConstants.KEY_HOME)
            body == "4~" || body == "F" -> sendKey(RfbConstants.KEY_END)
            body == "5~"                -> sendKey(RfbConstants.KEY_PAGE_UP)
            body == "6~"                -> sendKey(RfbConstants.KEY_PAGE_DOWN)
            body == "2~"                -> sendKey(RfbConstants.KEY_INSERT)
            body == "3~"                -> sendKey(RfbConstants.KEY_DELETE)
            // Function keys (xterm / VT220 sequences)
            body == "11~" -> sendKey(RfbConstants.KEY_F1)
            body == "12~" -> sendKey(RfbConstants.KEY_F2)
            body == "13~" -> sendKey(RfbConstants.KEY_F3)
            body == "14~" -> sendKey(RfbConstants.KEY_F4)
            body == "15~" -> sendKey(RfbConstants.KEY_F5)
            body == "17~" -> sendKey(RfbConstants.KEY_F6)
            body == "18~" -> sendKey(RfbConstants.KEY_F7)
            body == "19~" -> sendKey(RfbConstants.KEY_F8)
            body == "20~" -> sendKey(RfbConstants.KEY_F9)
            body == "21~" -> sendKey(RfbConstants.KEY_F10)
            body == "23~" -> sendKey(RfbConstants.KEY_F11)
            body == "24~" -> sendKey(RfbConstants.KEY_F12)
        }
    }

    /** Decode an SS3 body (the part after ESC O). */
    private fun parseSs3Sequence(body: String) {
        when (body) {
            "A" -> sendKey(RfbConstants.KEY_UP)
            "B" -> sendKey(RfbConstants.KEY_DOWN)
            "C" -> sendKey(RfbConstants.KEY_RIGHT)
            "D" -> sendKey(RfbConstants.KEY_LEFT)
            "H" -> sendKey(RfbConstants.KEY_HOME)
            "F" -> sendKey(RfbConstants.KEY_END)
            "P" -> sendKey(RfbConstants.KEY_F1)
            "Q" -> sendKey(RfbConstants.KEY_F2)
            "R" -> sendKey(RfbConstants.KEY_F3)
            "S" -> sendKey(RfbConstants.KEY_F4)
        }
    }

    /**
     * Send [keysym] with VT220 modifier keys extracted from a CSI [body] string
     * like "1;5A" (modifier=5, key='A').  Bodies without a semicolon are sent
     * without modifiers.
     *
     * VT220 modifier encoding (N-1 = bitmask):
     *   bit 0 = Shift, bit 1 = Alt, bit 2 = Ctrl.
     */
    private fun sendWithCsiModifier(body: String, keysym: Long) {
        val semi = body.indexOf(';')
        if (semi < 0) {
            sendKey(keysym)
            return
        }
        val modN = body.substring(semi + 1, body.length - 1).toIntOrNull() ?: 1
        val mods = vt220ModifierKeysyms(modN)
        for (mod in mods) rfbClient.sendKeyEvent(mod, true)
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
        for (mod in mods.reversed()) rfbClient.sendKeyEvent(mod, false)
    }

    /** Expand a VT220 modifier number into the corresponding list of modifier keysyms. */
    private fun vt220ModifierKeysyms(n: Int): List<Long> {
        val m = n - 1 // bit 0=Shift, bit 1=Alt, bit 2=Ctrl
        return buildList {
            if (m and 1 != 0) add(RfbConstants.KEY_SHIFT_L)
            if (m and 2 != 0) add(RfbConstants.KEY_ALT_L)
            if (m and 4 != 0) add(RfbConstants.KEY_CTRL_L)
        }
    }

    /** Read currently-held modifiers from the event's meta state. */
    private fun collectModifiers(event: KeyEvent): List<Long> = buildList {
        if (event.isShiftPressed) add(RfbConstants.KEY_SHIFT_L)
        if (event.isAltPressed)   add(RfbConstants.KEY_ALT_L)
        if (event.isCtrlPressed)  add(RfbConstants.KEY_CTRL_L)
        if (event.isMetaPressed)  add(RfbConstants.KEY_SUPER_L)
    }

    private fun isModifierKey(keyCode: Int): Boolean = keyCode in MODIFIER_KEYS

    /**
     * Translate an Android key code to an X11 keysym.
     *
     * Special keys use the named [RfbConstants.KEY_*] constants.
     * Printable characters fall through to [charToKeysym] via the event's
     * Unicode character value.  When Ctrl is held, [event.unicodeChar] may be
     * 0 or a control code; we fall back to the unmodified character in that case
     * (e.g. Ctrl+C → keysym 'c' = 0x63 with CTRL modifier held separately).
     */
    private fun androidKeyToKeysym(keyCode: Int, event: KeyEvent): Long? = when (keyCode) {
        KeyEvent.KEYCODE_DEL            -> RfbConstants.KEY_BACK_SPACE
        KeyEvent.KEYCODE_TAB            -> RfbConstants.KEY_TAB
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER   -> RfbConstants.KEY_RETURN
        KeyEvent.KEYCODE_ESCAPE         -> RfbConstants.KEY_ESCAPE
        KeyEvent.KEYCODE_FORWARD_DEL    -> RfbConstants.KEY_DELETE
        KeyEvent.KEYCODE_INSERT         -> RfbConstants.KEY_INSERT
        KeyEvent.KEYCODE_MOVE_HOME      -> RfbConstants.KEY_HOME
        KeyEvent.KEYCODE_MOVE_END       -> RfbConstants.KEY_END
        KeyEvent.KEYCODE_PAGE_UP        -> RfbConstants.KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN      -> RfbConstants.KEY_PAGE_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT      -> RfbConstants.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_UP        -> RfbConstants.KEY_UP
        KeyEvent.KEYCODE_DPAD_RIGHT     -> RfbConstants.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN      -> RfbConstants.KEY_DOWN
        KeyEvent.KEYCODE_F1             -> RfbConstants.KEY_F1
        KeyEvent.KEYCODE_F2             -> RfbConstants.KEY_F2
        KeyEvent.KEYCODE_F3             -> RfbConstants.KEY_F3
        KeyEvent.KEYCODE_F4             -> RfbConstants.KEY_F4
        KeyEvent.KEYCODE_F5             -> RfbConstants.KEY_F5
        KeyEvent.KEYCODE_F6             -> RfbConstants.KEY_F6
        KeyEvent.KEYCODE_F7             -> RfbConstants.KEY_F7
        KeyEvent.KEYCODE_F8             -> RfbConstants.KEY_F8
        KeyEvent.KEYCODE_F9             -> RfbConstants.KEY_F9
        KeyEvent.KEYCODE_F10            -> RfbConstants.KEY_F10
        KeyEvent.KEYCODE_F11            -> RfbConstants.KEY_F11
        KeyEvent.KEYCODE_F12            -> RfbConstants.KEY_F12
        KeyEvent.KEYCODE_SHIFT_LEFT     -> RfbConstants.KEY_SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT    -> RfbConstants.KEY_SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT      -> RfbConstants.KEY_CTRL_L
        KeyEvent.KEYCODE_CTRL_RIGHT     -> RfbConstants.KEY_CTRL_R
        KeyEvent.KEYCODE_ALT_LEFT       -> RfbConstants.KEY_ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT      -> RfbConstants.KEY_ALT_R
        KeyEvent.KEYCODE_META_LEFT      -> RfbConstants.KEY_SUPER_L
        KeyEvent.KEYCODE_META_RIGHT     -> RfbConstants.KEY_SUPER_R
        else -> {
            val unicodeChar = event.unicodeChar
            if (unicodeChar > 0) {
                charToKeysym(unicodeChar.toChar())
            } else {
                // When Ctrl is held, unicodeChar is 0 or a control byte.
                // Use the bare (no-modifier) character so we can send the
                // correct keysym while holding the Ctrl modifier separately.
                val bare = event.getUnicodeChar(0)
                if (bare > 0) charToKeysym(bare.toChar()) else null
            }
        }
    }

    companion object {
        private val MODIFIER_KEYS = setOf(
            KeyEvent.KEYCODE_SHIFT_LEFT,  KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,   KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,    KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,   KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK,   KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK
        )

        /**
         * Map a Unicode character to an X11 keysym.
         *
         * Latin-1 (U+0000–U+00FF): keysym == code point (direct mapping).
         * All others:              0x01000000 | code point (Unicode extension).
         */
        fun charToKeysym(ch: Char): Long {
            val cp = ch.code
            return if (cp <= 0xFF) cp.toLong() else 0x01000000L or cp.toLong()
        }
    }
}
