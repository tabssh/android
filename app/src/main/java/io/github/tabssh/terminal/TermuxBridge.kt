package io.github.tabssh.terminal

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridge between SSH streams and Termux terminal emulator.
 *
 * This class wraps Termux's TerminalEmulator to provide proper VT100/ANSI
 * terminal emulation for SSH connections, replacing the custom basic emulator.
 *
 * Data flow:
 * - SSH InputStream → read loop → emulator.append() → screen buffer updates
 * - User input → write() → SSH OutputStream
 */
class TermuxBridge(
    private val columns: Int = 80,
    private val rows: Int = 24,
    private val transcriptRows: Int = 2000,
    // 0=block, 1=underline, 2=bar (I-beam default)
    private val cursorStyle: Int = 2
) {
    companion object {
        private const val TAG = "TermuxBridge"
        private const val READ_BUFFER_SIZE = 8192

        // Shared empty array for the pendingUtf8 hold-back buffer.
        private val EMPTY_BYTES = ByteArray(0)

        /**
         * Number of bytes at the end of [buf] (within [len]) that form the
         * start of an incomplete UTF-8 multi-byte sequence, or 0 when the
         * buffer ends on a complete boundary (or on bytes that are invalid
         * anyway — those are passed through so the decoder's replacement
         * behavior stays unchanged). Internal for unit testing.
         */
        internal fun utf8IncompleteTrailingBytes(buf: ByteArray, len: Int): Int {
            if (len == 0) return 0
            // Walk back over at most 3 continuation bytes (0b10xxxxxx) to
            // find the lead byte of the trailing sequence.
            var i = len - 1
            var back = 0
            while (i >= 0 && back < 3 && (buf[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i < 0) return 0
            val lead = buf[i].toInt() and 0xFF
            val seqLen = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> return 0
            }
            val have = len - i
            return if (have < seqLen) have else 0
        }

        // Common 7-byte prefix of the bracketed-paste DECSET toggles
        // ESC[?2004h (enable) and ESC[?2004l (disable) — they differ only in
        // the final byte, so a trailing match against this prefix covers both.
        private val ESC_2004_PREFIX = byteArrayOf(
            0x1B, '['.code.toByte(), '?'.code.toByte(), '2'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '4'.code.toByte()
        )

        /**
         * Number of trailing bytes in [buf] (within [len]) that form a partial,
         * still-extendable prefix of ESC[?2004h / ESC[?2004l, or 0 when the
         * buffer does not end mid-sequence. Internal for unit testing.
         *
         * Bracketed-paste tracking below scans a single read()'s worth of
         * decoded text with String.lastIndexOf(). That scan is not stateful
         * across chunks the way Termux's own escape parser is — if a socket
         * read splits ESC[?2004h/l in two, neither chunk contains the full
         * token and the toggle is silently missed, leaving bracketedPasteActive
         * stuck at its old value. Held-back bytes are re-prepended to the next
         * chunk, same pattern as [pendingUtf8].
         */
        internal fun escIncompleteTrailingBytes(buf: ByteArray, len: Int): Int {
            if (len == 0) return 0
            val maxCheck = minOf(len, ESC_2004_PREFIX.size)
            for (k in maxCheck downTo 1) {
                var matches = true
                for (j in 0 until k) {
                    if (buf[len - k + j] != ESC_2004_PREFIX[j]) { matches = false; break }
                }
                if (matches) return k
            }
            return 0
        }

        // Hard cap on tracked OSC 8 hyperlink spans. A long-running session that
        // tails a log file emitting OSC 8 sequences would otherwise grow the list
        // unbounded; once we hit this number we drop the oldest span on insert.
        private const val OSC8_LINK_CAP = 200

        // Upper bound on a tracked OSC 8 target. A hostile server can emit an
        // arbitrarily long URI; anything past this is not a usable link, only
        // memory pressure and an unreadable confirmation dialog.
        private const val OSC8_MAX_URL_LENGTH = 2048

        // Schemes a remote is allowed to hand us through OSC 8. The tap handler
        // ultimately routes an unrecognised scheme to an ACTION_VIEW intent, so
        // without this a hostile server could aim the user at intent:// or
        // javascript:/file:// targets from what looks like ordinary output.
        private val OSC8_ALLOWED_SCHEMES = setOf(
            "http", "https", "ftp", "ftps", "ssh", "sftp", "telnet", "mailto"
        )

        /**
         * Validate an OSC 8 target, returning null when it must not be tracked.
         */
        internal fun sanitizeOsc8Url(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty() || trimmed.length > OSC8_MAX_URL_LENGTH) return null
            if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
            val scheme = trimmed.substringBefore(':', "").lowercase()
            if (scheme.isEmpty() || scheme !in OSC8_ALLOWED_SCHEMES) return null
            return trimmed
        }

        private const val ESC: Byte = 0x1B

        // Characters produced by shift + the top-row digit keys, indexed by
        // KEYCODE_0..KEYCODE_9.
        private val SHIFTED_DIGITS = charArrayOf(
            ')', '!', '@', '#', '$', '%', '^', '&', '*', '('
        )

        /**
         * Build an escape sequence: ESC followed by the ASCII of [tail].
         */
        private fun escapeSequence(tail: String): ByteArray {
            val out = ByteArray(tail.length + 1)
            out[0] = ESC
            for (i in tail.indices) out[i + 1] = tail[i].code.toByte()
            return out
        }

        /**
         * Translate an Android key code plus modifier state into the bytes a
         * VT-style remote expects.
         *
         * Android key codes are not ASCII (KEYCODE_A is 29, not 65), so the
         * control and meta paths must map through the key code table rather
         * than doing arithmetic on the raw value — an earlier revision treated
         * the code as ASCII, which meant Ctrl+A sent nothing at all while
         * Ctrl+Enter sent Ctrl+B.
         *
         * Internal rather than private so the mapping can be unit tested.
         */
        internal fun keySequenceFor(
            keyCode: Int,
            isCtrl: Boolean,
            isAlt: Boolean,
            isShift: Boolean
        ): ByteArray {
            if (isCtrl) {
                val ctrlByte = controlByteFor(keyCode) ?: return ByteArray(0)
                return if (isAlt) byteArrayOf(ESC, ctrlByte) else byteArrayOf(ctrlByte)
            }
            val base = baseSequenceFor(keyCode, isShift)
            if (base.isEmpty()) return base
            return if (isAlt) byteArrayOf(ESC) + base else base
        }

        private fun controlByteFor(keyCode: Int): Byte? = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                (keyCode - KeyEvent.KEYCODE_A + 1).toByte()
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_AT -> 0
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_ESCAPE -> 27
            KeyEvent.KEYCODE_BACKSLASH -> 28
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 29
            KeyEvent.KEYCODE_6 -> 30
            KeyEvent.KEYCODE_MINUS -> 31
            KeyEvent.KEYCODE_DEL -> 8
            else -> null
        }

        private fun baseSequenceFor(keyCode: Int, isShift: Boolean): ByteArray = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> escapeSequence("[A")
            KeyEvent.KEYCODE_DPAD_DOWN -> escapeSequence("[B")
            KeyEvent.KEYCODE_DPAD_RIGHT -> escapeSequence("[C")
            KeyEvent.KEYCODE_DPAD_LEFT -> escapeSequence("[D")
            KeyEvent.KEYCODE_MOVE_HOME -> escapeSequence("[1~")
            KeyEvent.KEYCODE_MOVE_END -> escapeSequence("[4~")
            KeyEvent.KEYCODE_PAGE_UP -> escapeSequence("[5~")
            KeyEvent.KEYCODE_PAGE_DOWN -> escapeSequence("[6~")
            KeyEvent.KEYCODE_FORWARD_DEL -> escapeSequence("[3~")
            KeyEvent.KEYCODE_INSERT -> escapeSequence("[2~")
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf(13)
            KeyEvent.KEYCODE_TAB -> byteArrayOf(9)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(127)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(ESC)
            KeyEvent.KEYCODE_F1 -> escapeSequence("OP")
            KeyEvent.KEYCODE_F2 -> escapeSequence("OQ")
            KeyEvent.KEYCODE_F3 -> escapeSequence("OR")
            KeyEvent.KEYCODE_F4 -> escapeSequence("OS")
            KeyEvent.KEYCODE_F5 -> escapeSequence("[15~")
            KeyEvent.KEYCODE_F6 -> escapeSequence("[17~")
            KeyEvent.KEYCODE_F7 -> escapeSequence("[18~")
            KeyEvent.KEYCODE_F8 -> escapeSequence("[19~")
            KeyEvent.KEYCODE_F9 -> escapeSequence("[20~")
            KeyEvent.KEYCODE_F10 -> escapeSequence("[21~")
            KeyEvent.KEYCODE_F11 -> escapeSequence("[23~")
            KeyEvent.KEYCODE_F12 -> escapeSequence("[24~")
            else -> {
                val ch = printableCharFor(keyCode, isShift)
                if (ch == null) ByteArray(0) else ch.toString().toByteArray(Charsets.UTF_8)
            }
        }

        private fun printableCharFor(keyCode: Int, isShift: Boolean): Char? = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val base = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                if (isShift) base.uppercaseChar() else base
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                if (isShift) SHIFTED_DIGITS[keyCode - KeyEvent.KEYCODE_0]
                else '0' + (keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_SPACE -> ' '
            KeyEvent.KEYCODE_COMMA -> if (isShift) '<' else ','
            KeyEvent.KEYCODE_PERIOD -> if (isShift) '>' else '.'
            KeyEvent.KEYCODE_GRAVE -> if (isShift) '~' else '`'
            KeyEvent.KEYCODE_MINUS -> if (isShift) '_' else '-'
            KeyEvent.KEYCODE_EQUALS -> if (isShift) '+' else '='
            KeyEvent.KEYCODE_LEFT_BRACKET -> if (isShift) '{' else '['
            KeyEvent.KEYCODE_RIGHT_BRACKET -> if (isShift) '}' else ']'
            KeyEvent.KEYCODE_BACKSLASH -> if (isShift) '|' else '\\'
            KeyEvent.KEYCODE_SEMICOLON -> if (isShift) ':' else ';'
            KeyEvent.KEYCODE_APOSTROPHE -> if (isShift) '"' else '\''
            KeyEvent.KEYCODE_SLASH -> if (isShift) '?' else '/'
            KeyEvent.KEYCODE_AT -> '@'
            KeyEvent.KEYCODE_PLUS -> '+'
            KeyEvent.KEYCODE_STAR -> '*'
            KeyEvent.KEYCODE_POUND -> '#'
            else -> null
        }

        /**
         * When false (default), keystroke writes log only `Sent N bytes to SSH`.
         * When true, the first ≤16 bytes are also dumped via `toBriefHex()`.
         *
         * Off by default because user keystrokes flow through this path —
         * including sudo passwords, ssh passphrases entered via `read -s`,
         * and anything else typed into the terminal. The byte-content payload
         * is useful for diagnosing protocol-level disconnects (it caught the
         * GCM-tag race that produced `ssh_dispatch_run_fatal: message
         * authentication code incorrect` server-side) but should be opt-in.
         *
         * Toggled by `LoggingSettingsFragment` from the
         * `log_keystroke_bytes` preference.
         */
        @Volatile
        @JvmStatic
        var logKeystrokeBytes: Boolean = false
    }

    // Termux emulator instance.
    // @Volatile — assigned on the calling (typically main) thread in
    // initialize()/connectSession() and read from the IO-dispatcher read
    // loop and from arbitrary threads via getEmulator()/getBuffer().
    @Volatile
    private var emulator: TerminalEmulator? = null

    // Wave 9.2 B-12 — when non-null, mosh-client is running inside a
    // PTY-backed TerminalSession. Writes are routed through the session
    // instead of outputStream; resize calls updateSize() on the PTY.
    // @Volatile — set on main, read by writeScope (IO) inside write()/resize().
    @Volatile
    private var moshSession: TerminalSession? = null

    /** Exit code of the most recently finished mosh PTY session.
     *  -1 = not yet finished / no mosh session ran.
     *  0  = clean exit (user typed exit/logout).
     *  >0 = abnormal termination.
     *  Read by TabTerminalActivity to decide reconnect-dialog vs auto-close.
     *  @Volatile — written from sessionClient.onSessionFinished (Termux
     *  worker thread), read from main. */
    @Volatile
    var moshLastExitCode: Int = -1
        private set

    /** Wall-clock ms at which the most recent mosh PTY session was started.
     *  0 = no session has run yet. Used together with [moshLastExitCode] to
     *  detect a "fast fail" (UDP blocked / server unreachable) so the UI can
     *  offer an SSH fallback button. */
    @Volatile
    private var moshSessionStartMs: Long = 0L

    /** Watchdog for fast mosh failure detection. Cancelled when the PTY
     *  exits naturally; cancels itself after killing a hung mosh-client. */
    @Volatile
    private var moshWatchdog: Job? = null

    /** Edge-detect state for the "TerminalView.AltScreen" diagnostic log
     *  below — null until the first onTextChanged fires, then holds the
     *  last logged (altScreen, appCursorKeys) pair so we only log on
     *  actual transitions, not on every screen redraw. */
    private var lastLoggedAltScreenState: Pair<Boolean, Boolean>? = null

    /**
     * True when the last mosh session exited abnormally within 120 s —
     * the classic signature of a UDP-blocked "nothing received from server"
     * failure. Checked by TabTerminalActivity to offer a "Try SSH instead"
     * reconnect option.
     */
    val moshFailedFast: Boolean
        get() = moshLastExitCode != 0 && moshLastExitCode != -1 &&
                System.currentTimeMillis() - moshSessionStartMs < 120_000L

    // I/O streams from SSH.
    // @Volatile — assigned by connect()/disconnect() on the caller's
    // thread (often main) and read by the read loop on Dispatchers.IO
    // and by writeScope coroutines on Dispatchers.IO.
    @Volatile
    private var inputStream: InputStream? = null
    @Volatile
    private var outputStream: OutputStream? = null

    /** Wave 2.7 — public read of the SSH outputStream so a sibling bridge can
     *  fan-out broadcast input to it. May be null until [connect] runs. */
    fun peerOutputStream(): OutputStream? = outputStream

    /** Wave 2.7 — when non-empty, every keystroke written to our SSH stream is
     *  also written to each of these. The owning Activity manages the list. */
    @Volatile
    var broadcastTargets: List<OutputStream> = emptyList()

    // Read loop job.
    // @Volatile — disconnect() reads/cancels from any thread while
    // startReadLoop() assigns from connect() (typically main).
    @Volatile
    private var readJob: Job? = null

    // Serializes disconnect() so it is atomic and idempotent across threads.
    private val disconnectLock = Any()

    // Coroutine scope for write operations. Deliberately limited to a single
    // thread (Issue: intermittent single-character drop/reorder near the
    // cursor during history-recall + Enter): plain Dispatchers.IO is a
    // multi-thread pool, and while writeLock (below) guarantees mutual
    // exclusion on the actual stream write, it does NOT guarantee the
    // *order* in which concurrently-launched callers acquire it — that was
    // left to IO-pool thread scheduling, independent of the order write()
    // was originally called. limitedParallelism(1) makes this scope's own
    // task queue FIFO, so writes execute in strict call order without
    // needing a dedicated Thread or changing the public API.
    private val writeScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + Job())

    // Session-lifecycle scope for the read loop and Mosh watchdog. Both used to
    // launch on ad-hoc CoroutineScope(Dispatchers.IO) instances whose root Job
    // was never cancelled — cancelling readJob/moshWatchdog stopped the child
    // coroutine but leaked the enclosing scope's Job across reconnects. Rooting
    // them here means cleanup() tears every straggler down in one cancel.
    private val sessionScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Serializes ALL writes to the SSH OutputStream. JSch's
     * ChannelOutputStream maintains an internal buffer that is NOT safe
     * against concurrent `write()` calls — concurrent appends race on
     * the buffer index AND on the GCM cipher state shared with the
     * surrounding session, producing on-the-wire ciphertext whose
     * authentication tag fails server-side. The server then closes the
     * TCP stream with `ssh_dispatch_run_fatal: message authentication
     * code incorrect` (verified via /var/log/secure on the user's
     * AlmaLinux 9.7 servers — see commit message).
     *
     * The race window opens whenever two `write()` calls land within
     * the same flush window — the broadcast-input fan-out, post-connect
     * script writes, and a fast typist plus the macro recorder are
     * all routine producers. Funnelling every write through this
     * Mutex closes the window without changing the public API or
     * adding a dedicated thread.
     *
     * Kept as defense-in-depth now that writeScope itself is a
     * single-threaded dispatcher (see writeScope's doc comment) — the
     * dispatcher already serializes and orders every write, so this
     * Mutex can never actually contend, but removing it would re-open
     * the GCM-cipher-corruption risk if writeScope's dispatcher ever
     * changes back to a multi-thread pool.
     */
    private val writeLock = Mutex()

    // Issue #173 — recordable macros. When non-null, every byte heading
    // out to SSH is also appended to this buffer so the activity can
    // save it as a Macro and replay verbatim later.
    @Volatile private var macroRecording: java.io.ByteArrayOutputStream? = null

    /** Begin capturing outbound bytes. No-op if already recording. */
    fun startMacroRecording() {
        if (macroRecording == null) macroRecording = java.io.ByteArrayOutputStream()
    }

    /** Stop capturing and return the recorded bytes (empty if not recording). */
    fun stopMacroRecording(): ByteArray {
        val buf = macroRecording ?: return ByteArray(0)
        macroRecording = null
        return buf.toByteArray()
    }

    fun isRecordingMacro(): Boolean = macroRecording != null

    // State
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Listeners.
    // CopyOnWriteArrayList — addListener/removeListener may be invoked
    // off the main thread (Activity onDestroy can run on a binder
    // thread; SSHTab.teardown is called from an IO coroutine), while
    // the read loop and runOnMain callbacks iterate concurrently. Plain
    // mutableListOf is not thread-safe and would throw
    // ConcurrentModificationException under load.
    private val listeners = CopyOnWriteArrayList<TermuxBridgeListener>()

    // Main thread handler for callbacks
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── OSC 8 hyperlink tracking ────────────────────────────────────────────
    //
    // Termux v0.118.1 does not implement OSC 8 natively. We intercept the raw
    // byte stream in the read loop, split data at OSC 8 boundaries, feed only
    // the anchor text to the emulator, and record start/end cursor coordinates.
    // Scroll is tracked by watching `em.screen.activeTranscriptRows` grow —
    // each additional transcript row means one screen row scrolled off the top,
    // so stored row indices are decremented accordingly.
    //
    // Thread safety: all writes happen on Dispatchers.IO (read loop); reads
    // happen on the UI thread (render, long-press).  Using `@Volatile var`
    // pointing to a CopyOnWriteArrayList lets us atomically swap the whole
    // list during scroll adjustment so the UI thread never sees a partially
    // updated state.

    /** Represents one complete OSC 8 span in screen-row coordinates. */
    data class Osc8Link(
        val url: String,
        val startRow: Int,
        val startCol: Int,
        val endRow: Int,
        val endCol: Int
    )

    @Volatile
    private var osc8Links = CopyOnWriteArrayList<Osc8Link>()

    /**
     * Optional sink for raw remote output, invoked with the read buffer and the
     * number of valid bytes in it. Used by session recording.
     *
     * Volatile: installed and cleared from Main while the read loop invokes it
     * on the IO dispatcher. The callback must not retain the array — it is the
     * read loop's reusable buffer.
     */
    @Volatile
    var outputRecorder: ((ByteArray, Int) -> Unit)? = null

    /**
     * Independent sink for the asciinema `.cast` writer (TODO.AI.md item 53's
     * session video recorder). Kept separate from [outputRecorder] so the
     * existing "Transcript" feature and the new "Session Recording" feature
     * never share or contend for the same field — both can be active at once.
     * Same volatile/no-retain contract as [outputRecorder].
     */
    @Volatile
    var castRecorder: ((ByteArray, Int) -> Unit)? = null

    // Set by the read loop whenever the remote sends ESC[?2004h (enable) or
    // ESC[?2004l (disable).  Read by pasteText() on the UI/main thread.
    @Volatile
    var bracketedPasteActive = false
        private set

    // Matches: ESC ] 8 ; params ; url ESC \ anchor ESC ] 8 ; ; ESC \
    // Groups: 1=params, 2=url, 3=anchor
    // DOT_MATCHES_ALL so anchor can contain any byte (including LF).
    private val osc8Pattern = Regex(
        "\u001b]8;([^;]*);([^\u001b]*)\u001b\\\\(.*?)\u001b]8;;\u001b\\\\",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Return the OSC 8 URL at the given screen position, or null if no link
     * covers that cell.  Safe to call from any thread.
     */
    fun getOsc8UrlAt(row: Int, col: Int): String? =
        osc8Links.firstOrNull { link ->
            when {
                link.startRow == link.endRow ->
                    row == link.startRow && col in link.startCol until link.endCol
                row == link.startRow -> col >= link.startCol
                row == link.endRow   -> col < link.endCol
                else -> row in (link.startRow + 1) until link.endRow
            }
        }?.url

    /**
     * Return all OSC 8 link ranges that intersect [row] as
     * (startColInclusive, endColExclusive, url) triples.
     * Used by the renderer to draw underlines without a per-cell loop.
     */
    fun getOsc8RangesForRow(row: Int): List<Triple<Int, Int, String>> {
        val result = mutableListOf<Triple<Int, Int, String>>()
        for (link in osc8Links) {
            when {
                link.startRow == link.endRow && link.startRow == row ->
                    result.add(Triple(link.startCol, link.endCol, link.url))
                link.startRow == row && link.startRow < link.endRow ->
                    result.add(Triple(link.startCol, currentColumns, link.url))
                link.endRow == row && link.startRow < link.endRow ->
                    result.add(Triple(0, link.endCol, link.url))
                link.startRow < row && row < link.endRow ->
                    result.add(Triple(0, currentColumns, link.url))
            }
        }
        return result
    }

    // Trailing bytes of an incomplete UTF-8 sequence held back from the
    // previous append — prepended to the next chunk by appendWithOsc8Tracking
    // so a multi-byte character split across two SSH reads is never decoded
    // as U+FFFD. Only touched from the read loop, no synchronization needed.
    private var pendingUtf8: ByteArray = EMPTY_BYTES

    // Trailing bytes of a partial ESC[?2004h/l token held back from the
    // previous append — see [escIncompleteTrailingBytes]. Only touched from
    // the read loop, no synchronization needed.
    private var pendingEscTail: ByteArray = EMPTY_BYTES

    /**
     * Feed data to the emulator with OSC 8 interception.
     *
     * Complete OSC 8 sequences within [data] are split: only the anchor text is
     * forwarded to the emulator (the OSC tags are consumed here). Cursor
     * positions before and after the anchor are recorded as an [Osc8Link].
     * When rows scroll off the top during the append, all stored link row
     * indices are adjusted downward to stay aligned with screen coordinates.
     */
    private fun appendWithOsc8Tracking(em: TerminalEmulator, rawData: ByteArray, rawLength: Int) {
        // Re-assemble UTF-8 sequences across chunk boundaries. The ESC path
        // below decodes the chunk with String(bytes, UTF_8); a multi-byte
        // character split across two SSH reads would decode its halves as
        // U+FFFD, and the re-encoded replacement bytes would be fed to the
        // emulator — permanently corrupting the cell (visible as "??" tofu
        // in TUIs). Hold back a trailing incomplete sequence and prepend it
        // to the next chunk. Applied to every path (the no-ESC fast path
        // tolerates splits — em.append decodes statefully — but held-back
        // bytes from an ESC chunk must always be re-prepended first).
        var data = rawData
        var length = rawLength
        if (pendingEscTail.isNotEmpty() || pendingUtf8.isNotEmpty()) {
            data = pendingEscTail + pendingUtf8 + rawData.copyOf(rawLength)
            length = data.size
            pendingEscTail = EMPTY_BYTES
            pendingUtf8 = EMPTY_BYTES
        }
        val hold = utf8IncompleteTrailingBytes(data, length)
        if (hold > 0) {
            pendingUtf8 = data.copyOfRange(length - hold, length)
            length -= hold
        }
        // Hold back a partial ESC[?2004h/l token the same way, so the
        // bracketed-paste scan below always sees the full toggle in one
        // pass instead of missing it when a socket read splits it in two.
        val escHold = escIncompleteTrailingBytes(data, length)
        if (escHold > 0) {
            pendingEscTail = data.copyOfRange(length - escHold, length)
            length -= escHold
        }
        if (length == 0) return

        // Fast path: every sequence intercepted below — OSC 8 anchors and the
        // bracketed-paste mode toggles — begins with ESC (0x1B). If the buffer
        // holds no ESC byte, the UTF-8 decode, the regex scan, and both
        // bracketed-paste searches are guaranteed no-ops, so append the raw
        // bytes directly. This is the common case for bulk output (e.g. cat of
        // a large text file), which carries no escape sequences at all.
        // Scroll-adjust logic here mirrors appendAndAdjust below (the canonical
        // copy); keep the two in sync.
        var hasEsc = false
        for (i in 0 until length) {
            if ((data[i].toInt() and 0xFF) == 0x1B) { hasEsc = true; break }
        }
        if (!hasEsc) {
            val prevTranscript = em.screen?.activeTranscriptRows ?: 0
            em.append(data, length)
            val scrolled = (em.screen?.activeTranscriptRows ?: 0) - prevTranscript
            if (scrolled > 0) {
                osc8Links = CopyOnWriteArrayList(
                    osc8Links.mapNotNull { link ->
                        val newEnd = link.endRow - scrolled
                        if (newEnd < 0) null
                        else link.copy(startRow = link.startRow - scrolled, endRow = newEnd)
                    }
                )
            }
            return
        }

        val text = String(data, 0, length, Charsets.UTF_8)

        // Track DEC private mode ?2004 (bracketed paste).  When both enable and
        // disable appear in the same buffer, the later occurrence wins.
        val lastEnable  = text.lastIndexOf("\u001b[?2004h")
        val lastDisable = text.lastIndexOf("\u001b[?2004l")
        if (lastEnable >= 0 || lastDisable >= 0) {
            bracketedPasteActive = lastEnable > lastDisable
        }

        val matches = osc8Pattern.findAll(text).toList()

        // Track scroll by monitoring the transcript size before/after each
        // append.  Each additional transcript row = one screen row scrolled.
        fun appendAndAdjust(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val prevTranscript = em.screen?.activeTranscriptRows ?: 0
            em.append(bytes, bytes.size)
            val scrolled = (em.screen?.activeTranscriptRows ?: 0) - prevTranscript
            if (scrolled > 0) {
                // Atomically replace the list so readers never see a partial update.
                osc8Links = CopyOnWriteArrayList(
                    osc8Links.mapNotNull { link ->
                        val newEnd = link.endRow - scrolled
                        if (newEnd < 0) null
                        else link.copy(startRow = link.startRow - scrolled, endRow = newEnd)
                    }
                )
            }
        }

        if (matches.isEmpty()) {
            appendAndAdjust(data.copyOf(length))
            return
        }

        var pos = 0
        for (match in matches) {
            val url    = match.groupValues[2]
            val anchor = match.groupValues[3]

            // Feed everything that came before this OSC 8 sequence.
            val before = text.substring(pos, match.range.first)
            if (before.isNotEmpty()) appendAndAdjust(before.toByteArray(Charsets.UTF_8))

            // Record cursor position — this is where the link underline starts.
            val startRow = em.cursorRow
            val startCol = em.cursorCol

            // Feed only the anchor text; the OSC tags are consumed here.
            if (anchor.isNotEmpty()) appendAndAdjust(anchor.toByteArray(Charsets.UTF_8))

            // Record cursor position — this is where the link underline ends.
            val endRow = em.cursorRow
            val endCol = em.cursorCol

            val safeUrl = sanitizeOsc8Url(url)
            if (safeUrl == null) {
                if (url.isNotBlank()) {
                    // Never log the payload itself: an OSC 8 target can carry
                    // session tokens or other secrets from the remote side.
                    Logger.w(TAG, "Dropping OSC 8 link with disallowed scheme or length")
                }
            } else {
                // Cap the list to prevent unbounded growth across a long session.
                // CopyOnWriteArrayList.removeAt(0) is O(N) because it allocates
                // a fresh backing array each call; on a flood that produces 200+
                // links in one append this becomes O(N^2). Swap the whole list
                // in one allocation when the cap is reached so the cost stays
                // bounded and readers (UI thread) still see a consistent snapshot.
                val newLink = Osc8Link(safeUrl, startRow, startCol, endRow, endCol)
                if (osc8Links.size >= OSC8_LINK_CAP) {
                    val keep = osc8Links.subList(osc8Links.size - OSC8_LINK_CAP + 1, osc8Links.size).toList()
                    osc8Links = CopyOnWriteArrayList<Osc8Link>(keep).apply { add(newLink) }
                } else {
                    osc8Links.add(newLink)
                }
            }

            pos = match.range.last + 1
        }

        // Feed any trailing bytes after the last OSC 8 sequence.
        if (pos < text.length) {
            appendAndAdjust(text.substring(pos).toByteArray(Charsets.UTF_8))
        }
    }

    // Terminal dimensions (can be updated).
    // @Volatile — resize() may be called from the main thread (keyboard
    // open) while the read loop and writeScope coroutines read these
    // values to log cursor positions and forward to the PTY/SSH side.
    @Volatile
    private var currentColumns = columns
    @Volatile
    private var currentRows = rows

    /**
     * TerminalOutput implementation - handles data going TO the SSH server
     */
    private val terminalOutput = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            // Copy data to avoid race conditions (input may be reused)
            val dataCopy = data.copyOfRange(offset, offset + count)
            // Issue #173 — if a macro recording is active, append the
            // bytes BEFORE we hand them off to writeScope. The recorder
            // is intentionally byte-exact (no decoding) so escape codes
            // and paste payloads round-trip on replay.
            macroRecording?.write(dataCopy)
            // Run on IO thread to avoid NetworkOnMainThreadException.
            // EVERY write (own stream + broadcast targets) is wrapped in
            // `writeLock.withLock` so JSch's per-channel cipher state
            // never sees concurrent append+flush from two coroutines —
            // the GCM tag race that was producing
            // `ssh_dispatch_run_fatal: message authentication code
            // incorrect` server-side.
            writeScope.launch {
                writeLock.withLock {
                    try {
                        outputStream?.let { stream ->
                            stream.write(dataCopy)
                            stream.flush()
                            // Throttled — rapid writes (paste, scripted PTY
                            // output) can otherwise flood the Logger write
                            // queue with one entry per chunk.
                            if (logKeystrokeBytes) {
                                Logger.dThrottled(TAG, "sentBytesSsh", 300) {
                                    "Sent ${dataCopy.size} bytes to SSH (bytes=${dataCopy.toBriefHex()})"
                                }
                            } else {
                                Logger.dThrottled(TAG, "sentBytesSsh", 300) {
                                    "Sent ${dataCopy.size} bytes to SSH"
                                }
                            }
                        }
                        // Wave 2.7 — broadcast input. After our own SSH write
                        // succeeds, fan the same bytes out to every registered
                        // target (other tabs). Note: each target belongs to a
                        // *different* JSch session with its own writeLock on
                        // its owning bridge — our lock only serializes the
                        // fan-out order from THIS bridge's perspective, it
                        // does NOT prevent the peer bridge's own keystroke
                        // writer from racing on the peer's GCM state. The
                        // peer's TermuxBridge.terminalOutput.write() path
                        // takes the peer's writeLock, which is the correct
                        // place for that serialization; we deliberately
                        // bypass it here because the broadcast bytes are
                        // already serialized at the source and the peer
                        // bridge's writeLock would deadlock on a circular
                        // broadcast topology.
                        val targets = broadcastTargets
                        if (targets.isNotEmpty()) {
                            for (t in targets) {
                                try {
                                    t.write(dataCopy)
                                    t.flush()
                                } catch (e: Exception) {
                                    Logger.w(TAG, "Broadcast to peer stream failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to SSH", e)
                        runOnMain { notifyError(e) }
                    }
                }
            }
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            // Some shells emit OSC title updates on every prompt/keystroke —
            // throttle to avoid flooding the writer with per-event lines.
            Logger.dThrottled(TAG, "titleChangedBridge", 1000) {
                "Title changed: $oldTitle -> $newTitle"
            }
            runOnMain {
                listeners.forEach { it.onTitleChanged(newTitle ?: "") }
            }
        }

        override fun onCopyTextToClipboard(text: String?) {
            text?.let {
                runOnMain {
                    listeners.forEach { listener -> listener.onCopyToClipboard(it) }
                }
            }
        }

        override fun onPasteTextFromClipboard() {
            runOnMain {
                listeners.forEach { it.onPasteFromClipboard() }
            }
        }

        override fun onBell() {
            runOnMain {
                listeners.forEach { it.onBell() }
            }
        }

        override fun onColorsChanged() {
            runOnMain {
                listeners.forEach { it.onColorsChanged() }
            }
        }
    }

    /**
     * TerminalSessionClient implementation - handles emulator events
     * Note: We don't have a TerminalSession, so session parameter will be handled carefully
     */
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            // Edge-triggered alt-screen/app-cursor-mode tracer: logs only on
            // an actual state transition, not on every redraw. This is
            // ground truth on whether the emulator is genuinely being
            // pushed into alt-screen plus app-cursor-keys mode by real
            // bytes from the remote or mosh-client side.
            val em = changedSession.getEmulator()
            if (em != null) {
                val current = Pair(em.isAlternateBufferActive(), em.isCursorKeysApplicationMode())
                if (current != lastLoggedAltScreenState) {
                    Logger.d(
                        "TerminalView.AltScreen",
                        "transition altScreen=${lastLoggedAltScreenState?.first}->${current.first} " +
                            "appCursorKeys=${lastLoggedAltScreenState?.second}->${current.second} " +
                            "viaMosh=${moshSession != null}"
                    )
                    lastLoggedAltScreenState = current
                }
            }
            runOnMain {
                listeners.forEach { it.onScreenChanged() }
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            // Termux parses OSC 0/1/2 → title; surface it to listeners
            // so the foreground service can rebuild the per-host
            // notification ("Connected to {host}:{title}").
            val newTitle = try { changedSession.title ?: "" } catch (_: Exception) { "" }
            runOnMain {
                listeners.forEach { it.onTitleChanged(newTitle) }
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val code = finishedSession.getExitStatus()
            moshLastExitCode = code
            // Mosh PTY is gone — watchdog is no longer needed.
            try { moshWatchdog?.cancel() } catch (_: Exception) {}
            moshWatchdog = null
            Logger.i(TAG, "Session finished (exit=$code)")
            runOnMain {
                listeners.forEach { it.onDisconnected() }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            // Handled by TerminalOutput
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            // Handled by TerminalOutput
        }

        override fun onBell(session: TerminalSession) {
            // Handled by TerminalOutput
        }

        override fun onColorsChanged(session: TerminalSession) {
            // Handled by TerminalOutput
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            runOnMain {
                listeners.forEach { it.onCursorStateChanged(state) }
            }
        }

        override fun getTerminalCursorStyle(): Int {
            // Use configured style (default: I-beam)
            return cursorStyle
        }

        // Note: setTerminalShellPid may not exist in all Termux versions
        // It's not needed for SSH-based terminals anyway

        // Logging methods
        override fun logError(tag: String?, message: String?) {
            Logger.e(tag ?: TAG, message ?: "Unknown error")
        }

        override fun logWarn(tag: String?, message: String?) {
            Logger.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Logger.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logVerbose(tag: String?, message: String?) {
            Logger.d(tag ?: TAG, message ?: "")
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Logger.e(tag ?: TAG, message ?: "", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Logger.e(tag ?: TAG, "Stack trace", e)
        }
    }

    /**
     * Initialize the Termux emulator
     */
    fun initialize() {
        Logger.i(TAG, "Initializing Termux emulator ${currentColumns}x${currentRows}")

        // Constructor: (TerminalOutput, columns, rows, transcriptRows, TerminalSessionClient)
        emulator = TerminalEmulator(
            terminalOutput,
            currentColumns,
            currentRows,
            transcriptRows,
            sessionClient
        )

        Logger.i(TAG, "Termux emulator initialized")
    }

    /**
     * Connect to SSH streams and start processing
     */
    fun connect(sshInputStream: InputStream, sshOutputStream: OutputStream) {
        Logger.i(TAG, "=== CONNECTING TO SSH STREAMS ===")
        Logger.i(TAG, "InputStream: $sshInputStream")
        Logger.i(TAG, "OutputStream: $sshOutputStream")

        // Ensure emulator is initialized
        if (emulator == null) {
            Logger.i(TAG, "Emulator was null, initializing...")
            initialize()
        }
        Logger.i(TAG, "Emulator ready: ${emulator != null}, size: ${currentColumns}x${currentRows}")

        // Store streams
        this.inputStream = sshInputStream
        this.outputStream = sshOutputStream
        _isConnected.value = true

        // Start read loop
        Logger.i(TAG, "Starting read loop...")
        startReadLoop()

        // Notify listeners
        Logger.i(TAG, "Notifying ${listeners.size} listeners of connection")
        runOnMain {
            listeners.forEach { it.onConnected() }
        }

        Logger.i(TAG, "=== SSH STREAMS CONNECTED SUCCESSFULLY ===")
    }

    /**
     * Start the background read loop for SSH input
     */
    private fun startReadLoop() {
        readJob?.cancel()

        readJob = sessionScope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)

            Logger.d(TAG, "Read loop started")

            try {
                while (isActive && _isConnected.value) {
                    val stream = inputStream ?: break
                    val bytesRead = stream.read(buffer)

                    if (bytesRead < 0) {
                        // EOF: stream is permanently closed. Break out
                        // and let finally{} drive the disconnect — never
                        // spin or retry on EOF (busy-loop hazard).
                        Logger.i(TAG, "SSH stream closed (EOF)")
                        break
                    }

                    if (bytesRead == 0) {
                        // JSch's ChannelInputStream returns 0 only when a
                        // non-blocking peek finds nothing. Yield to other
                        // coroutines so we don't burn a core if the
                        // underlying stream ever becomes non-blocking.
                        kotlinx.coroutines.yield()
                        continue
                    }

                    // Feed data to Termux emulator. The append() call is
                    // internally synchronized on the screen object so
                    // injectLocally() from other threads cannot interleave.
                    // appendWithOsc8Tracking wraps append() and intercepts
                    // OSC 8 hyperlink sequences before they reach the emulator.
                    val em = emulator
                    if (em != null) {
                        appendWithOsc8Tracking(em, buffer, bytesRead)
                        val current = Pair(em.isAlternateBufferActive(), em.isCursorKeysApplicationMode())
                        if (current != lastLoggedAltScreenState) {
                            Logger.d(
                                "TerminalView.AltScreen",
                                "transition altScreen=${lastLoggedAltScreenState?.first}->${current.first} " +
                                    "appCursorKeys=${lastLoggedAltScreenState?.second}->${current.second} " +
                                    "viaMosh=false"
                            )
                            lastLoggedAltScreenState = current
                        }
                    } else {
                        Logger.e(TAG, "EMULATOR IS NULL - cannot process $bytesRead bytes!")
                    }

                    // Session recording. Never let a recorder failure (full
                    // disk, revoked storage permission) take down the session.
                    outputRecorder?.let { sink ->
                        try {
                            sink(buffer, bytesRead)
                        } catch (e: Exception) {
                            Logger.w(TAG, "Session recorder rejected output: ${e.message}")
                        }
                    }

                    // Terminal-cast recording, independent of the transcript sink above.
                    castRecorder?.let { sink ->
                        try {
                            sink(buffer, bytesRead)
                        } catch (e: Exception) {
                            Logger.w(TAG, "Cast writer rejected output: ${e.message}")
                        }
                    }

                    // Notify screen changed (emulator may not call client
                    // for every change). One post per read chunk — NEVER
                    // per byte — so the UI thread sees at most ~one
                    // invalidate per blocking read return regardless of
                    // chunk size, even on a flood like `yes` or `cat
                    // largefile`.
                    runOnMain {
                        listeners.forEach { it.onScreenChanged() }
                    }
                }
            } catch (e: CancellationException) {
                // Tab close / scope shutdown. Rethrow so the job really ends up
                // cancelled instead of being reported to the user as a read
                // failure and completing normally.
                throw e
            } catch (e: Exception) {
                if (_isConnected.value) {
                    Logger.e(TAG, "Error reading from SSH", e)
                    notifyError(e)
                }
            } finally {
                Logger.d(TAG, "Read loop ended")
                if (_isConnected.value) {
                    disconnect()
                }
            }
        }
    }

    /**
     * Write data to SSH (user input), or to the mosh-client PTY when in
     * mosh mode. The mosh path bypasses the SSH outputStream entirely.
     */
    fun write(data: ByteArray) {
        val ms = moshSession
        if (ms != null) {
            writeScope.launch {
                writeLock.withLock {
                    try {
                        ms.write(data, 0, data.size)
                        // Throttled — an active mosh session can write PTY
                        // bytes many times per second, otherwise flooding the
                        // Logger write queue with one entry per write.
                        Logger.dThrottled(TAG, "sentBytesMosh", 300) {
                            "Sent ${data.size} bytes to mosh-client PTY"
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to mosh session", e)
                    }
                }
            }
        } else {
            terminalOutput.write(data, 0, data.size)
        }
    }

    /**
     * Write string to SSH
     */
    fun writeString(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Send text to terminal (alias for writeString, matches old API)
     */
    fun sendText(text: String) {
        writeString(text)
    }

    /**
     * Send clipboard text to the SSH server, applying bracketed paste mode
     * markers (ESC[200~ / ESC[201~) when the remote has enabled ?2004.
     * Line endings are normalised: CRLF and bare LF both become CR.
     */
    fun pasteText(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val bracketed = bracketedPasteActive
        // A separate write for the open marker / body / close marker each
        // becomes its own writeScope.launch{} coroutine that independently
        // acquires writeLock -- launch order is not execution order under a
        // multi-threaded dispatcher, so those writes could reach the remote
        // out of order, or with an unrelated concurrent keystroke write
        // interleaved between them. The remote's bracketed-paste parser
        // then sees a broken ESC[200~ ... ESC[201~ block and falls back to
        // treating the embedded \r line breaks as literal Enter presses,
        // submitting only the first line and misrouting the rest as
        // separate input. Always send the whole paste -- markers and body
        // together -- as a single write so it reaches the remote
        // atomically, no matter how large.
        val payload = buildString {
            if (bracketed) append("\u001b[200~")
            append(normalized)
            if (bracketed) append("\u001b[201~")
        }
        writeString(payload)
        Logger.d(TAG, "Pasted ${normalized.length} chars (bracketed=$bracketed)")
    }

    /**
     * Send key press to terminal
     * Converts key codes to appropriate terminal escape sequences
     */
    fun sendKeyPress(keyCode: Int, isCtrl: Boolean = false, isAlt: Boolean = false, isShift: Boolean = false) {
        val sequence = keySequenceFor(keyCode, isCtrl, isAlt, isShift)
        if (sequence.isNotEmpty()) {
            write(sequence)
        }
    }

    /**
     * Clear terminal screen (send ANSI clear sequence)
     */
    fun clearScreen() {
        // Send ESC[2J (clear screen) + ESC[H (cursor home)
        writeString("\u001b[2J\u001b[H")
    }

    /**
     * Get screen content as text.
     *
     * Uses a single getSelectedText span covering the full visible screen so
     * that the Termux library's mLineWrap flags are respected — wrapped rows
     * do NOT get a spurious '\n' at their visual break point, matching what
     * the user sees on screen. A row-by-row loop with per-row '\n' injection
     * would produce broken output for any line wider than the terminal width.
     */
    fun getScreenContent(): String {
        val screen = emulator?.screen ?: return ""
        val rows = currentRows
        val cols = currentColumns
        return try {
            screen.getSelectedText(0, 0, cols, rows - 1) ?: ""
        } catch (e: Exception) {
            Logger.w(TAG, "Error getting screen content", e)
            ""
        }
    }

    /**
     * Get scrollback buffer content as text
     */
    fun getScrollbackContent(): String {
        val screen = emulator?.screen ?: return ""
        // Get transcript (scrollback) - activeTranscriptRows gives us how many rows of history
        return try {
            val transcriptRows = screen.activeTranscriptRows
            if (transcriptRows > 0) {
                screen.getSelectedText(0, -transcriptRows, currentColumns, -1) ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Error getting scrollback content", e)
            ""
        }
    }

    /**
     * Get the terminal screen buffer for rendering
     */
    fun getScreen(): TerminalBuffer? {
        return emulator?.screen
    }

    /**
     * Get cursor row position
     */
    fun getCursorRow(): Int {
        return emulator?.cursorRow ?: 0
    }

    /**
     * Get cursor column position
     */
    fun getCursorCol(): Int {
        return emulator?.cursorCol ?: 0
    }

    /**
     * Check if cursor should be visible
     */
    fun isCursorVisible(): Boolean {
        return emulator?.shouldCursorBeVisible() ?: true
    }

    /**
     * Get cursor style
     * @return 0=block, 1=underline, 2=bar (I-beam)
     */
    fun getCursorStyle(): Int = cursorStyle

    /**
     * Get number of columns
     */
    fun getColumns(): Int = currentColumns

    /**
     * Get number of columns (alias for compatibility)
     */
    fun getCols(): Int = currentColumns

    /**
     * Get number of rows
     */
    fun getRows(): Int = currentRows

    /**
     * Get buffer (returns Termux TerminalBuffer, for compatibility)
     */
    fun getBuffer(): com.termux.terminal.TerminalBuffer? = emulator?.screen

    /**
     * Inject bytes directly into the LOCAL emulator without sending to the
     * remote shell. Used for setting DECSET / DECRST modes (auto-wrap,
     * cursor visibility, alt-screen, …) on the local renderer based on
     * user preferences without involving the remote.
     *
     * Safe to call from any thread; `append` is internally synchronized
     * on Termux's screen.
     */
    fun injectLocally(bytes: ByteArray) {
        try {
            emulator?.append(bytes, bytes.size)
        } catch (e: Exception) {
            Logger.w(TAG, "injectLocally failed: ${e.message}")
        }
    }

    /**
     * Resize the terminal
     */
    // Resize callback for VM console to forward to WebSocket.
    // @Volatile — set on main, invoked from writeScope (IO).
    @Volatile
    var onResizeCallback: ((cols: Int, rows: Int) -> Unit)? = null

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns != currentColumns || newRows != currentRows) {
            currentColumns = newColumns
            currentRows = newRows
            Logger.d(TAG, "Resized to ${newColumns}x${newRows}")

            val ms = moshSession
            if (ms != null) {
                // In mosh mode: updateSize() resizes both the TerminalEmulator
                // and the PTY (via ioctl TIOCSWINSZ → SIGWINCH to mosh-client).
                // No SSH resize callback is needed.
                try { ms.updateSize(newColumns, newRows) } catch (_: Exception) {}
            } else {
                emulator?.resize(newColumns, newRows)

                // SSH-side window-change MUST share the writeLock with the
                // keystroke writes — both produce packets on the same JSch
                // session and a concurrent send corrupts the GCM cipher
                // state, ending in `ssh_dispatch_run_fatal: message
                // authentication code incorrect` server-side and an EOF
                // back to us. Symptom: open keyboard (resize fires) →
                // type a char → server EOF within 1s.
                //
                // The callback usually invokes `Channel.setPtySize`
                // synchronously, which JSch will route through its own
                // session writer. By acquiring `writeLock` first we
                // guarantee no in-flight keystroke is mid-encrypt when
                // the resize packet goes out, and vice versa.
                //
                // We launch on `writeScope` (Dispatchers.IO) for the same
                // reason keystroke writes do — `setPtySize` blocks on
                // socket I/O and we MUST NOT do that from the UI thread.
                val cb = onResizeCallback
                if (cb != null) {
                    writeScope.launch {
                        writeLock.withLock {
                            try {
                                cb(newColumns, newRows)
                            } catch (e: Exception) {
                                Logger.w(TAG, "Resize callback failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Disconnect from SSH streams.
     *
     * Idempotent (Issue #51): the VM-console teardown path used to call
     * disconnect() three times (read-loop finally, console-manager
     * disconnect, cleanup), each one firing onDisconnected() on every
     * registered listener — producing the user-visible "Terminal
     * disconnected" toast triplicate. We now gate on the previous state:
     * fire the listener callbacks ONLY on the first transition from
     * connected to disconnected.
     */
    fun disconnect() = synchronized(disconnectLock) {
        // Guarded by disconnectLock so concurrent callers (e.g. the read loop's
        // onSessionFinished and a user tap) can't both pass the check-then-act
        // guard and double-close streams or double-fire onDisconnected().
        val wasConnected = _isConnected.value
        if (!wasConnected && inputStream == null && outputStream == null && readJob == null && moshSession == null) {
            // Already torn down — nothing to do, don't re-fire listeners.
            return@synchronized
        }

        Logger.i(TAG, "Disconnecting")

        _isConnected.value = false
        readJob?.cancel()
        readJob = null
        // Don't cancel writeScope's Job — it's a `val` shared across the
        // bridge's lifetime, and cancelling kills it permanently. Pending
        // writes drop on the floor when outputStream becomes null below.

        try {
            inputStream?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing input stream", e)
        }
        inputStream = null

        try {
            outputStream?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing output stream", e)
        }
        outputStream = null

        // Mosh PTY session — finishIfRunning() sends SIGHUP to the child
        // process and closes the master PTY fd.
        try { moshSession?.finishIfRunning() } catch (_: Exception) {}
        moshSession = null

        // OSC 8 links and bracketed paste state are session-scoped — clear them
        // on every disconnect so stale data from one SSH session doesn't bleed
        // into the next.
        osc8Links = CopyOnWriteArrayList()
        bracketedPasteActive = false
        pendingEscTail = EMPTY_BYTES

        if (wasConnected) {
            runOnMain {
                listeners.forEach { it.onDisconnected() }
            }
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        emulator = null
        listeners.clear()
        // The bridge is being permanently destroyed — cancel writeScope
        // so any queued writeLock.withLock { ... } blocks unwind instead
        // of holding the JSch session reference past the bridge's life.
        // disconnect() intentionally does NOT do this (it must be safe
        // for reconnect), but cleanup() is terminal.
        try {
            writeScope.coroutineContext[Job]?.cancel()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cancelling writeScope", e)
        }
        // Terminal teardown: cancel the read loop / Mosh watchdog scope too, so
        // no straggler coroutine outlives the bridge holding a stream reference.
        try {
            sessionScope.coroutineContext[Job]?.cancel()
        } catch (e: Exception) {
            Logger.w(TAG, "Error cancelling sessionScope", e)
        }
    }

    /**
     * Add event listener
     */
    fun addListener(listener: TermuxBridgeListener) {
        listeners.add(listener)
    }

    /**
     * Remove event listener
     */
    fun removeListener(listener: TermuxBridgeListener) {
        listeners.remove(listener)
    }

    private fun notifyError(e: Exception) {
        runOnMain {
            listeners.forEach { it.onError(e) }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Wave 9.2 B-12 — Connect via a PTY-backed TerminalSession for
     * mosh-client. mosh-client calls tcgetattr() on stdin at startup and
     * exits immediately with ENOTTY when stdin is a plain pipe (the old
     * ProcessBuilder path). TerminalSession uses JNI forkpty() so the child
     * process gets a real TTY as its controlling terminal.
     *
     * The bridge delegates emulation to the session's own TerminalEmulator so
     * getBuffer()/getEmulator() return live mosh-client screen state for the
     * TerminalView. All screen callbacks continue to route through
     * [sessionClient] → [TermuxBridgeListener] as normal.
     *
     * @return true if the mosh-client binary is bundled for this ABI and
     *         the PTY was created, false if no binary is available.
     */
    fun connectMoshClient(
        context: android.content.Context,
        host: String,
        port: Int,
        moshKeyBase64: String
    ): Boolean {
        val binary = io.github.tabssh.protocols.mosh.MoshNativeClient.resolveBinary(context)
            ?: run {
                Logger.w(TAG, "mosh-client binary not bundled — cannot create PTY session")
                return false
            }

        // Cancel any existing stream-based connection first.
        readJob?.cancel()
        readJob = null
        inputStream = null
        outputStream = null

        // If a prior mosh PTY session is still running (e.g. reconnect after
        // a mosh handoff failure), tear it down before creating a new one.
        // Without this the old TerminalSession holds an open PTY fd and the
        // mosh-client child process is never sent SIGHUP.
        try { moshSession?.finishIfRunning() } catch (_: Exception) {}
        moshSession = null

        val envList = arrayOf(
            "MOSH_KEY=$moshKeyBase64",
            "TERM=xterm-256color",
            // mosh-client checks nl_langinfo(CODESET) at startup and exits if not UTF-8;
            // Android subprocess envs built from scratch don't inherit LANG from the app process.
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${context.cacheDir.absolutePath}"
        )

        moshSessionStartMs = System.currentTimeMillis()
        moshLastExitCode = -1
        Logger.i(TAG, "Creating PTY TerminalSession for mosh-client $host:$port")
        val session = TerminalSession(
            binary.absolutePath,
            context.filesDir.absolutePath,
            // argv[0] = program name, argv[1..] = arguments passed to execvp
            arrayOf(binary.absolutePath, host, port.toString()),
            envList,
            transcriptRows,
            // wire our callbacks immediately — no race
            sessionClient
        )

        connectSession(session)
        startMoshFailWatchdog()
        return true
    }

    /**
     * Mosh fast-fail watchdog — an *optional latency optimisation*, never the
     * source of truth. We fast-fail to SSH ONLY when mosh-client itself has
     * reported a real error on screen; we never kill a session on a blind
     * timeout with no evidence of failure.
     *
     * mosh-client is its own authority on the UDP path (verified against the
     * upstream `stmclient.cc` / `mosh-client.cc`):
     *  - On a blocked/firewalled UDP path it paints
     *    "Nothing received from server on UDP port N." within ~1 s of launch.
     *  - Failing that, at 15 s it paints "Timed out waiting for server...",
     *    begins its own shutdown, and exits non-zero on its own — which the
     *    existing onSessionFinished → moshFailedFast → silent SSH fallback in
     *    TabTerminalActivity already handles without any help from us.
     *
     * So the ONLY thing this watchdog adds is speed: when mosh-client's own
     * failure string is visible on screen we can fall back in ~2–3 s instead
     * of waiting out mosh-client's ~15–20 s self-timeout. Both strings are
     * unambiguous, mosh-reported errors — the exact "real errors" we are
     * allowed to fast-fail on (UDP blocked / firewalled / server unreachable).
     *
     * What this watchdog must NOT do is kill a silent, still-bootstrapping
     * session. A blank screen at N seconds is not evidence of failure — a
     * slow or high-RTT link can render its first frame several seconds in, and
     * a genuinely-dead link is already covered by mosh-client's own non-zero
     * self-exit above. An earlier revision killed at a blind 8000 ms ceiling;
     * that fired before mosh-client's own verdict and tore down working links,
     * so it has been removed. When we reach the stop-watching boundary with no
     * mosh-reported error and no visible content, we simply stop watching and
     * leave the outcome to mosh-client.
     */
    private fun startMoshFailWatchdog() {
        try { moshWatchdog?.cancel() } catch (_: Exception) {}
        moshWatchdog = sessionScope.launch {
            val startedAt = System.currentTimeMillis()
            // Minimum age before an on-screen failure string is trusted.
            // mosh-client can paint bits of its own initial UI within a few
            // hundred ms of launch, long before a UDP handshake could really
            // have failed; a sub-2 s trip would mean killing a session that
            // was still bootstrapping.
            val minFailureAgeMs = 2000L
            // Point at which we stop watching WITHOUT killing. By here
            // mosh-client has painted its 15 s "Timed out waiting for server"
            // notice and started its own non-zero self-exit if UDP is dead —
            // it owns the outcome from here, so we never kill at this boundary.
            val stopWatchingMs = 16000L
            val pollMs = 250L
            try {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val session = moshSession ?: return@launch
                    if (!session.isRunning()) return@launch
                    val screen = try { getScreenContent() } catch (_: Exception) { "" }
                    // Real, mosh-reported UDP failures. Either string is proof
                    // the server can't be reached — the only case we fast-fail.
                    val sawRealError = elapsed >= minFailureAgeMs && (
                        screen.contains("Nothing received from server", ignoreCase = true) ||
                        screen.contains("Timed out waiting for server", ignoreCase = true)
                    )
                    if (sawRealError) {
                        Logger.i(TAG, "Mosh watchdog: mosh-client reported a UDP failure on screen after ${elapsed}ms — killing PTY for fast SSH fallback")
                        try { session.finishIfRunning() } catch (_: Exception) {}
                        return@launch
                    }
                    // Cheap short-circuit: if the emulator has rendered
                    // *anything* substantive that isn't the failure string,
                    // the remote side is talking to us. Stop watching.
                    val nonWs = screen.count { !it.isWhitespace() }
                    val looksConnected = nonWs > 4 &&
                        !screen.contains("Nothing received", ignoreCase = true)
                    if (looksConnected) {
                        Logger.i(TAG, "Mosh watchdog: ${elapsed}ms — remote content visible (${nonWs} chars), leaving session alone")
                        return@launch
                    }
                    if (elapsed >= stopWatchingMs) {
                        // No mosh-reported error and no visible content. This is
                        // NOT a failure — do not kill. mosh-client keeps
                        // ownership; if UDP really is dead it has already begun
                        // its own timed shutdown and its non-zero exit drives
                        // the SSH fallback.
                        Logger.i(TAG, "Mosh watchdog: ${elapsed}ms with no mosh-reported error — leaving the verdict to mosh-client")
                        return@launch
                    }
                    kotlinx.coroutines.delay(pollMs)
                }
            } catch (e: CancellationException) {
                // Session teardown cancelled the watchdog — unwind rather than
                // swallowing it as a polling error.
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Mosh watchdog stopped: ${e.message}")
            }
        }
    }

    /**
     * Wire a [TerminalSession] (PTY-backed) as this bridge's active terminal.
     * Initializes the emulator if the constructor hasn't already done so,
     * then replaces [emulator] with the session's own emulator so rendering
     * picks up the PTY output.
     */
    private fun connectSession(session: TerminalSession) {
        // If the TerminalSession constructor deferred initialization, start it.
        // If it already auto-initialized (constructor called initializeEmulator),
        // skip to avoid a second fork; just resize to current dimensions.
        if (session.getEmulator() == null) {
            session.initializeEmulator(currentColumns, currentRows)
        } else {
            try { session.updateSize(currentColumns, currentRows) } catch (_: Exception) {}
        }

        // Delegate rendering to the session's TerminalEmulator.
        emulator = session.getEmulator()
        moshSession = session
        _isConnected.value = true

        runOnMain {
            listeners.forEach { it.onConnected() }
        }
        Logger.i(TAG, "Connected via PTY TerminalSession (mosh-client)")
    }

    /**
     * Returns true when the mosh-client PTY session is still running.
     * Used by SSHTab to distinguish a stale SSH-teardown onDisconnected
     * (fired during handoff while mosh is alive) from a real mosh death
     * (process has already exited, isRunning() = false).
     */
    fun isMoshSessionAlive(): Boolean = moshSession?.isRunning() == true

    /**
     * Get the raw emulator for advanced operations
     */
    fun getEmulator(): TerminalEmulator? = emulator
}

/**
 * Diagnostic helper — render the first ≤16 bytes as `decimal,decimal,…`,
 * appending `…` when truncated. Used to make `Sent N bytes to SSH` log
 * lines self-describing so future "did this packet kill the session?"
 * triage doesn't require a tcpdump. Decimal (not hex) so the output
 * matches the existing `sequence=[27, 91, 65]` style on the custom-key
 * path.
 */
private fun ByteArray.toBriefHex(): String {
    if (isEmpty()) return "[]"
    val limit = 16
    val head = take(limit).joinToString(",") { (it.toInt() and 0xFF).toString() }
    return if (size > limit) "[$head,…(${size - limit} more)]" else "[$head]"
}

/**
 * Listener interface for TermuxBridge events
 */
interface TermuxBridgeListener {
    fun onConnected()
    fun onDisconnected()
    fun onScreenChanged()
    fun onTitleChanged(title: String)
    fun onBell()
    fun onColorsChanged()
    fun onCursorStateChanged(visible: Boolean)
    fun onCopyToClipboard(text: String)
    fun onPasteFromClipboard()
    fun onError(e: Exception)
}
