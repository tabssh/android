package io.github.tabssh.hypervisor.vnc.console

import android.view.KeyEvent
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.hypervisor.console.rfb.RfbListener
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
 * // ... on session end:
 * channel.close()
 * ```
 *
 * Thread safety: all public send methods are safe to call from any thread,
 * including the main thread.  They post work to an internal single-threaded
 * executor so socket writes never happen on the calling thread.  Key ordering
 * is preserved because the executor is strictly FIFO.
 */
class VncConsoleChannel(private val rfbClient: RfbClient) {

    enum class VncModifier(val keysym: Long, internal val bit: Int) {
        CTRL(RfbConstants.KEY_CTRL_L, 1),
        ALT(RfbConstants.KEY_ALT_L,  2),
        WIN(RfbConstants.KEY_SUPER_L, 4)
    }

    /** Bitmask of currently armed one-shot modifiers. UI thread writes; IO thread reads+clears. */
    private val armedModMask = AtomicInteger(0)

    /**
     * Called on the IO thread immediately after armed modifiers are consumed
     * (wrapped around a key and cleared). Wire to refresh toolbar button states.
     */
    var onArmedModsConsumed: (() -> Unit)? = null

    /** Toggle the armed state of [mod]. Call from the UI thread when a toolbar modifier button is tapped. */
    fun armModifier(mod: VncModifier) {
        armedModMask.updateAndGet { it xor mod.bit }
    }

    /** Returns true if [mod] is currently armed (highlight the toolbar button). */
    fun isModifierArmed(mod: VncModifier): Boolean = (armedModMask.get() and mod.bit) != 0

    /** Disarm all modifiers (e.g. when the VNC session ends). */
    fun clearArmedModifiers() { armedModMask.set(0) }

    /**
     * Atomically read and clear the armed modifier mask.
     * Returns the list of modifier keysyms to send down before the key and up after it.
     * Calls [onArmedModsConsumed] if any mods were consumed.
     */
    private fun consumeArmedMods(): List<Long> {
        val mask = armedModMask.getAndSet(0)
        if (mask == 0) return emptyList()
        val mods = buildList {
            if (mask and VncModifier.CTRL.bit != 0) add(RfbConstants.KEY_CTRL_L)
            if (mask and VncModifier.ALT.bit  != 0) add(RfbConstants.KEY_ALT_L)
            if (mask and VncModifier.WIN.bit  != 0) add(RfbConstants.KEY_SUPER_L)
        }
        onArmedModsConsumed?.invoke()
        return mods
    }

    /** Pixel width of one terminal character cell (used for SetDesktopSize via [resize]). */
    var fontW: Int = 8

    /** Pixel height of one terminal character cell (used for SetDesktopSize via [resize]). */
    var fontH: Int = 16

    private var initialCols: Int = 80
    private var initialRows: Int = 24

    /**
     * Desired VNC framebuffer pixel dimensions, set by [resizeToPixels] when the
     * hosting [VncView] reports its measured size.  Volatile so the RFB reader
     * thread can safely read them in [wrapListener.onExtendedDesktopSizeReady].
     *
     * Zero means "not yet known — wait for VncView to measure itself".
     */
    @Volatile private var pendingViewW: Int = 0
    @Volatile private var pendingViewH: Int = 0

    /**
     * Single-threaded executor that serialises all outgoing RFB writes.
     *
     * All public send methods post work here so that callers on the main
     * thread never touch the socket directly — avoiding
     * [android.os.NetworkOnMainThreadException] and keeping key ordering
     * deterministic (the executor is a FIFO single-thread pool).
     */
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tabssh-vnc-writer").also { it.isDaemon = true }
    }

    /**
     * Post [block] to the serialised writer thread.
     *
     * Silently drops the task if the executor has already been shut down —
     * this happens when [close] is called while a touch/key event is still
     * in flight from the UI thread.  The session is over; dropping is correct
     * and avoids a [java.util.concurrent.RejectedExecutionException] crash.
     *
     * The task itself is wrapped in a try-catch for [java.io.IOException]: on
     * Proxmox vncproxy connections the RFB output travels through a
     * PipedOutputStream whose read end is owned by the WebSocket sender.
     * When the WebSocket closes, the read end dies before [close] shuts down
     * this executor, so a pointer or key event arriving in that narrow window
     * throws "Read end dead" inside the task.  The RFB reader thread will
     * detect the broken stream independently and fire [RfbListener.onDisconnected];
     * swallowing the IOException here is correct — the event cannot be delivered
     * and the session is already ending.
     */
    private fun io(block: () -> Unit) {
        if (writeExecutor.isShutdown) return
        try {
            writeExecutor.execute {
                try {
                    block()
                } catch (_: java.io.IOException) {
                    // Underlying stream is dead (e.g. PipedOutputStream "Read end dead"
                    // when the WebSocket closes before the executor shuts down).
                    // The reader thread handles the session teardown; drop this write.
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Race between isShutdown check and execute() — session is ending; drop safely.
        }
    }

    /**
     * Record the initial terminal size.  Call before [rfbClient] is started.
     * The size is sent via [RfbClient.sendSetDesktopSize] once the server is
     * ready (inside [wrapListener]'s [RfbListener.onExtendedDesktopSizeReady]
     * override).
     */
    fun setInitialSize(cols: Int, rows: Int) {
        initialCols = cols.coerceAtLeast(1)
        initialRows = rows.coerceAtLeast(1)
    }

    /**
     * Shut down the writer executor.  Call when the VNC session ends to
     * release the background thread.  Any sends queued before this call will
     * still be delivered; sends after this call are silently dropped.
     */
    fun close() {
        clearArmedModifiers()
        writeExecutor.shutdownNow()
    }

    /**
     * Resize the VNC server's framebuffer to exactly [w]×[h] pixels.
     *
     * This is the preferred resize path for graphical VNC sessions: call it
     * with the [VncView]'s measured dimensions so the VM's virtual display
     * fills the available screen area without the character-cell math that
     * [resize] uses.
     *
     * Safe to call from any thread including the main thread.
     *
     * If the server has not yet confirmed ExtendedDesktopSize support,
     * the values are stored in [pendingViewW]/[pendingViewH] and sent once
     * [wrapListener.onExtendedDesktopSizeReady] fires.
     */
    fun resizeToPixels(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        pendingViewW = w
        pendingViewH = h
        io { rfbClient.sendSetDesktopSize(w, h) }
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
            // Server confirmed ExtendedDesktopSize support.  Send a resize request
            // only if the VncView has already reported its pixel dimensions via
            // resizeToPixels().  If not, the pending size will be sent the moment
            // the view fires its onViewSizeReady callback.
            //
            // We deliberately do NOT fall back to initialCols * fontW here: that
            // 640×384 size resets the server framebuffer to blank and the user sees
            // a black screen until a second resize (from the view) arrives.
            val pw = pendingViewW
            val ph = pendingViewH
            if (pw > 0 && ph > 0) {
                io { rfbClient.sendSetDesktopSize(pw, ph) }
            }
            delegate.onExtendedDesktopSizeReady()
        }
    }

    // ── Public send API (all dispatch to writeExecutor) ───────────────────

    /**
     * Translate a terminal grid resize to a SetDesktopSize message.
     * Safe to call from any thread including the main thread.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) {
            io { rfbClient.sendSetDesktopSize(cols * fontW, rows * fontH) }
        }
    }

    /**
     * Send a single keysym as a down+up pair, preceded by any armed one-shot
     * modifiers (Ctrl/Alt/Win) which are automatically cleared after sending.
     * Safe to call from any thread including the main thread.
     */
    fun sendKey(keysym: Long) = io {
        val mods = consumeArmedMods()
        for (m in mods) rfbClient.sendKeyEvent(m, true)
        sendKeyDirect(keysym)
        for (m in mods.reversed()) rfbClient.sendKeyEvent(m, false)
    }

    /**
     * Translate an Android [KeyEvent] to RFB key events and send them.
     *
     * Handles [KeyEvent.ACTION_DOWN] only (modifier state is read from the
     * event's metaState, so held modifiers are captured correctly).  Returns
     * true when the event was consumed.  Safe to call from any thread
     * including the main thread.
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

        // Key translation is pure computation — safe on main thread.
        val mods = collectModifiers(event)
        // Network writes are dispatched to the writer thread.
        io {
            for (mod in mods) rfbClient.sendKeyEvent(mod, true)
            rfbClient.sendKeyEvent(keysym, true)
            rfbClient.sendKeyEvent(keysym, false)
            for (mod in mods.reversed()) rfbClient.sendKeyEvent(mod, false)
        }
        return true
    }

    /**
     * Send a raw byte sequence produced by the custom keyboard bar.
     * Plain characters are sent as Unicode keysyms; ANSI/VT220 escape
     * sequences are decoded to the corresponding X11 keysyms.
     *
     * An empty string is treated as Escape (keyboard bar convention).
     * Safe to call from any thread including the main thread.
     */
    fun sendSequence(seq: String) = io { sendSequenceDirect(seq) }

    /**
     * Send a string of plain text as a series of Unicode keysyms, preceded by
     * any armed one-shot modifiers (Ctrl/Alt/Win) which are automatically cleared
     * after sending.
     * Safe to call from any thread including the main thread.
     */
    fun sendText(text: String) = io {
        val mods = consumeArmedMods()
        for (m in mods) rfbClient.sendKeyEvent(m, true)
        text.forEach { sendCharDirect(it) }
        for (m in mods.reversed()) rfbClient.sendKeyEvent(m, false)
    }

    /**
     * Send a raw keysym with an explicit down/up state.
     *
     * Use when the caller manages modifier sequencing itself (e.g. wiring
     * [VncView.onKeyEvent] which already provides separate down and up events).
     * Safe to call from any thread including the main thread.
     */
    fun sendRawKeyEvent(keysym: Long, down: Boolean) = io { rfbClient.sendKeyEvent(keysym, down) }

    /**
     * Send Ctrl+[char] as an atomic modifier sequence.
     *
     * Sends CTRL_L down → char down+up → CTRL_L up in a single executor task
     * so no other key event can be interleaved between the modifier and the key.
     * Safe to call from any thread including the main thread.
     */
    fun sendCtrlChar(ch: Char) = io {
        rfbClient.sendKeyEvent(RfbConstants.KEY_CTRL_L, true)
        sendCharDirect(ch)
        rfbClient.sendKeyEvent(RfbConstants.KEY_CTRL_L, false)
    }

    /**
     * Forward a pointer (touch/mouse) event to the VM.
     * [x] and [y] are framebuffer-space coordinates.
     * [mask] is a bitmask of [RfbConstants.BTN_*] buttons.
     * Safe to call from any thread including the main thread.
     */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) = io { rfbClient.sendPointerEvent(x, y, mask) }

    // ── Internal helpers (called only from inside io{} blocks) ────────────
    //
    // These methods call rfbClient.send* directly and must NOT be called from
    // the main thread.  They must NOT call any of the public send methods
    // (which would re-enter the executor); call the *Direct variants instead.

    /** Send a keysym down+up without executor dispatch. */
    private fun sendKeyDirect(keysym: Long) {
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
    }

    private fun sendCharDirect(ch: Char) {
        val keysym = charToKeysym(ch)
        // Assert Shift_L around characters that require it on a US layout so
        // servers that reverse-map keysym→keycode (QEMU/libvirt) emit the
        // shifted glyph instead of the base key (e.g. '@' instead of '2').
        // The hardware-key path already sends Shift via collectModifiers, so
        // this only affects the text/on-screen-keyboard paths.
        val needsShift = charNeedsShift(ch)
        if (needsShift) rfbClient.sendKeyEvent(RfbConstants.KEY_SHIFT_L, true)
        rfbClient.sendKeyEvent(keysym, true)
        rfbClient.sendKeyEvent(keysym, false)
        if (needsShift) rfbClient.sendKeyEvent(RfbConstants.KEY_SHIFT_L, false)
    }

    private fun sendSequenceDirect(seq: String) {
        when {
            seq.isEmpty()               -> sendKeyDirect(RfbConstants.KEY_ESCAPE)
            seq == "\t"                 -> sendKeyDirect(RfbConstants.KEY_TAB)
            seq == "\r" || seq == "\n"  -> sendKeyDirect(RfbConstants.KEY_RETURN)
            seq.startsWith("")    -> parseEscapeSequenceDirect(seq.removePrefix(""))
            seq.length == 1             -> sendCharDirect(seq[0])
            else                        -> seq.forEach { sendCharDirect(it) }
        }
    }

    private fun parseEscapeSequenceDirect(seq: String) {
        when {
            // CSI (ESC [)
            seq.startsWith("[") -> parseCsiSequenceDirect(seq.removePrefix("["))
            // SS3 (ESC O)
            seq.startsWith("O") -> parseSs3SequenceDirect(seq.removePrefix("O"))
            // Alt+key: one char remains after ESC was stripped (ESC + single char => length 1)
            seq.length == 1 -> {
                rfbClient.sendKeyEvent(RfbConstants.KEY_ALT_L, true)
                sendCharDirect(seq[0])
                rfbClient.sendKeyEvent(RfbConstants.KEY_ALT_L, false)
            }
            // Bare ESC: nothing remains after the leading ESC byte was stripped
            seq.isEmpty() -> sendKeyDirect(RfbConstants.KEY_ESCAPE)
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
    private fun parseCsiSequenceDirect(body: String) {
        val hasModifier = body.contains(';')
        when {
            // Cursor keys — bare or with VT220 modifier (e.g. "A", "1;5A")
            body == "A" || (hasModifier && body.endsWith("A")) ->
                sendWithCsiModifierDirect(body, RfbConstants.KEY_UP)
            body == "B" || (hasModifier && body.endsWith("B")) ->
                sendWithCsiModifierDirect(body, RfbConstants.KEY_DOWN)
            body == "C" || (hasModifier && body.endsWith("C")) ->
                sendWithCsiModifierDirect(body, RfbConstants.KEY_RIGHT)
            body == "D" || (hasModifier && body.endsWith("D")) ->
                sendWithCsiModifierDirect(body, RfbConstants.KEY_LEFT)
            // Navigation
            body == "1~" || body == "H" -> sendKeyDirect(RfbConstants.KEY_HOME)
            body == "4~" || body == "F" -> sendKeyDirect(RfbConstants.KEY_END)
            body == "5~"                -> sendKeyDirect(RfbConstants.KEY_PAGE_UP)
            body == "6~"                -> sendKeyDirect(RfbConstants.KEY_PAGE_DOWN)
            body == "2~"                -> sendKeyDirect(RfbConstants.KEY_INSERT)
            body == "3~"                -> sendKeyDirect(RfbConstants.KEY_DELETE)
            // Function keys (xterm / VT220 sequences)
            body == "11~" -> sendKeyDirect(RfbConstants.KEY_F1)
            body == "12~" -> sendKeyDirect(RfbConstants.KEY_F2)
            body == "13~" -> sendKeyDirect(RfbConstants.KEY_F3)
            body == "14~" -> sendKeyDirect(RfbConstants.KEY_F4)
            body == "15~" -> sendKeyDirect(RfbConstants.KEY_F5)
            body == "17~" -> sendKeyDirect(RfbConstants.KEY_F6)
            body == "18~" -> sendKeyDirect(RfbConstants.KEY_F7)
            body == "19~" -> sendKeyDirect(RfbConstants.KEY_F8)
            body == "20~" -> sendKeyDirect(RfbConstants.KEY_F9)
            body == "21~" -> sendKeyDirect(RfbConstants.KEY_F10)
            body == "23~" -> sendKeyDirect(RfbConstants.KEY_F11)
            body == "24~" -> sendKeyDirect(RfbConstants.KEY_F12)
        }
    }

    /** Decode an SS3 body (the part after ESC O). */
    private fun parseSs3SequenceDirect(body: String) {
        when (body) {
            "A" -> sendKeyDirect(RfbConstants.KEY_UP)
            "B" -> sendKeyDirect(RfbConstants.KEY_DOWN)
            "C" -> sendKeyDirect(RfbConstants.KEY_RIGHT)
            "D" -> sendKeyDirect(RfbConstants.KEY_LEFT)
            "H" -> sendKeyDirect(RfbConstants.KEY_HOME)
            "F" -> sendKeyDirect(RfbConstants.KEY_END)
            "P" -> sendKeyDirect(RfbConstants.KEY_F1)
            "Q" -> sendKeyDirect(RfbConstants.KEY_F2)
            "R" -> sendKeyDirect(RfbConstants.KEY_F3)
            "S" -> sendKeyDirect(RfbConstants.KEY_F4)
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
    private fun sendWithCsiModifierDirect(body: String, keysym: Long) {
        val semi = body.indexOf(';')
        if (semi < 0) {
            sendKeyDirect(keysym)
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

        /**
         * True when [ch] is produced with Shift on a US QWERTY layout: the
         * uppercase letters and the shifted-punctuation set.
         *
         * Some VNC servers (notably QEMU/libvirt) reverse-map an incoming
         * keysym to a physical keycode and, if no Shift is asserted, emit the
         * unshifted glyph — so '@' (keysym 0x40) arrives as '2', '#' as '3',
         * 'A' as 'a', and so on. Bracketing these characters with a synthetic
         * Shift_L makes them arrive correctly and is harmless on servers that
         * resolve the keysym themselves. This mirrors what noVNC and Guacamole
         * do. Heuristic is US-layout: it matches the default en-us QEMU keymap;
         * a server configured for a different keymap may still mis-map exotic
         * punctuation, which is a server-side keymap concern.
         */
        fun charNeedsShift(ch: Char): Boolean =
            ch in 'A'..'Z' || ch in "~!@#$%^&*()_+{}|:\"<>?"
    }
}
