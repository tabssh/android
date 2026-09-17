package io.github.tabssh.ssh.connection

import io.github.tabssh.network.NetworkAwareReconnector
import io.github.tabssh.network.detection.NetworkDetector
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Wave 2.3 — Telnet (RFC 854) backend.
 *
 * Telnet is the polar opposite of SSH: no auth, no encryption, but still
 * useful for network gear (Cisco IOS, console servers, embedded BMCs) and
 * MUDs. We give it a minimal, mostly-reactive implementation:
 *
 *  - Open TCP socket.
 *  - Background pump reads raw bytes; on IAC (0xFF) we either respond to the
 *    negotiation or skip subnegotiation, and forward everything else into a
 *    piped stream the terminal reads from.
 *  - The output stream doubles literal 0xFF bytes (per RFC) before sending.
 *
 * What we negotiate ON:
 *  - ECHO (1)             — server may echo input (most do)
 *  - SUPPRESS-GO-AHEAD (3) — full-duplex, required for sane terminal use
 *  - TERMINAL-TYPE (24)   — reply with `xterm-256color` when asked
 *  - NAWS (31)            — push current window size on resize
 *
 * Everything else we politely refuse (WONT/DONT). Subnegotiations we don't
 * understand are read-and-discarded up to IAC SE.
 */
class TelnetConnection(
    private val host: String,
    private val port: Int = 23,
    /** Supplies network up/down transitions to the auto-reconnector. When
     *  null (tests, callers with no Application handle) the connection still
     *  works, it simply never reconnects itself. */
    private val networkDetector: NetworkDetector? = null
) {
    companion object {
        private const val TAG = "TelnetConnection"

        // RFC 854 control bytes
        private const val IAC: Int = 0xFF
        private const val DONT: Int = 0xFE
        private const val DO: Int = 0xFD
        private const val WONT: Int = 0xFC
        private const val WILL: Int = 0xFB
        private const val SB: Int = 0xFA
        private const val SE: Int = 0xF0

        // Options we care about
        private const val OPT_ECHO: Int = 1
        private const val OPT_SUPPRESS_GA: Int = 3
        private const val OPT_TERMINAL_TYPE: Int = 24
        private const val OPT_NAWS: Int = 31

        // Subnegotiation: TERMINAL-TYPE
        private const val TT_IS: Int = 0
        private const val TT_SEND: Int = 1

        // Upper bound on bytes scanned while looking for the IAC SE that ends
        // a subnegotiation. Real subnegotiations are a handful of bytes.
        private const val MAX_SUBNEG_SCAN_BYTES: Int = 8192
    }

    // Assigned on the connecting coroutine, read by the pump thread and by
    // setWindowSize() on the UI thread — must be volatile.
    @Volatile private var socket: Socket? = null
    @Volatile private var rawIn: InputStream? = null
    @Volatile private var rawOut: OutputStream? = null

    // RFC 854 §"option negotiation": a party must only send a response when
    // the response changes the option's state. Without this, two conforming
    // implementations that both refuse an option ping-pong DONT/WONT forever.
    // null = never negotiated, true = enabled, false = refused/disabled.
    private val remoteOptionState = java.util.HashMap<Int, Boolean>()
    private val localOptionState = java.util.HashMap<Int, Boolean>()

    // Serializes every write to rawOut. The pump thread emits negotiation
    // replies while the UI thread can call setWindowSize() → sendNaws()
    // concurrently; interleaved writes would corrupt the IAC framing.
    private val writeLock = Any()

    // What we expose to TermuxBridge. A PipedInputStream is single-use — once
    // its writer closes, every later read throws — so these are recreated on
    // each connect() rather than allocated once, which is what lets a
    // reconnected session hand the bridge a working pair of streams.
    @Volatile private var pipeOut = PipedOutputStream()
    @Volatile private var pipeIn = PipedInputStream(pipeOut, 64 * 1024)
    private val outFilter = EscapingOutputStream(writeLock) { socket?.getOutputStream() }

    val inputStream: InputStream get() = pipeIn
    val outputStream: OutputStream get() = outFilter

    private var pumpThread: Thread? = null
    @Volatile private var stopped = false
    @Volatile var connected: Boolean = false; private set

    // Telnet has no auth or channel phases, so this only ever carries
    // CONNECTING / CONNECTED / DISCONNECTED / ERROR. SSHTab collects it to
    // rewire TermuxBridge onto the new streams after an auto-reconnect —
    // without that the socket comes back and the terminal stays inert.
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Owns the reconnector's timers. IO rather than Main: nothing here
    // touches views, and connect() is a blocking socket dial.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Created only after the first successful connect, mirroring
    // SSHConnection — a host that never answered is a user-input problem, and
    // retrying it on a 5-second backoff forever would be worse than useless.
    private var reconnector: NetworkAwareReconnector? = null

    /** NAWS — push window size. Safe to call any time after connect. */
    @Volatile private var lastCols = 80
    @Volatile private var lastRows = 24
    private val termType: String = "xterm-256color"

    suspend fun connect(timeoutMs: Int = 15_000): Boolean = withContext(Dispatchers.IO) {
        // A prior connect()/disconnect() cycle latches this permanently; reset
        // it here so the pump loop below actually runs on reconnect.
        stopped = false
        // Option state is per-TCP-session: a reconnected peer re-negotiates
        // from scratch, and stale entries here would suppress the replies it
        // is waiting for (see the RFC 854 note on remoteOptionState).
        remoteOptionState.clear()
        localOptionState.clear()
        // Fresh pipes for a fresh session — the previous pair was closed when
        // the old pump thread ended.
        pipeOut = PipedOutputStream()
        pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        _connectionState.value = ConnectionState.CONNECTING
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            socket = s
            rawIn = s.getInputStream()
            rawOut = s.getOutputStream()
            connected = true
            startPump()
            Logger.i(TAG, "Telnet connected $host:$port")
            armReconnector()
            _connectionState.value = ConnectionState.CONNECTED
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Telnet connect failed: $host:$port", e)
            // Ensure the freshly allocated socket is released even if it was
            // never assigned to the field (e.g. connect() threw before line
            // `socket = s` ran). closeTransport() only closes the field.
            try { s.close() } catch (_: Exception) {}
            closeTransport()
            _connectionState.value = ConnectionState.ERROR
            // A failed retry must schedule the next one; a failed first dial
            // has no reconnector yet and so stops here, as intended.
            reconnector?.onConnectionLost()
            false
        }
    }

    /**
     * Creates the reconnector on the first successful connect and resets its
     * backoff on every later one. Mirrors `SSHConnection.connect()`, including
     * the "only after a connect has actually worked" gate.
     */
    private fun armReconnector() {
        val detector = networkDetector ?: return
        val existing = reconnector
        if (existing != null) {
            existing.onConnectionRestored()
            return
        }
        reconnector = NetworkAwareReconnector(
            networkDetector = detector,
            scope = scope,
            tag = "TelnetConnection/$host:$port",
            reconnect = { connect() },
        ).also { it.start() }
    }

    /**
     * Permanent teardown — the user disconnected or the tab is going away.
     * Stops the reconnector too, so nothing dials the host again afterwards.
     */
    fun disconnect() {
        reconnector?.cancel()
        reconnector = null
        scope.cancel()
        closeTransport()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Drops the socket and streams without touching the reconnector, so a
     * retry can reuse this instance. [disconnect] is the permanent form.
     */
    private fun closeTransport() {
        stopped = true
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        try { pipeOut.close() } catch (_: Exception) {}
        try { pipeIn.close() } catch (_: Exception) {}
        socket = null
        rawIn = null
        rawOut = null
    }

    fun setWindowSize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        if (!connected) return
        // sendNaws contains its own failures — a window-size hint (RFC 1073) is
        // best-effort and the peer-driven send in handleIac(DO NAWS) carries the
        // authoritative size, so nothing here needs to escalate.
        sendNaws(cols, rows)
    }

    private fun startPump() {
        // Capture the pipe this session owns: connect() installs a new pair,
        // so a lingering thread from a previous session must never write into
        // the current one.
        val sessionPipe = pipeOut
        pumpThread = thread(name = "telnet-pump-$host:$port", isDaemon = true) {
            val input = rawIn ?: return@thread
            try {
                while (!stopped) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == IAC) {
                        handleIac(input, sessionPipe)
                    } else {
                        sessionPipe.write(b)
                    }
                }
            } catch (e: IOException) {
                if (!stopped) Logger.w(TAG, "Telnet pump IO: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "Telnet pump crashed", e)
            } finally {
                val unexpected = !stopped
                connected = false
                try { sessionPipe.close() } catch (_: Exception) {}
                if (unexpected) {
                    // Peer closed the socket or the link dropped — this is the
                    // only place telnet learns it died, so it is where the
                    // reconnector gets armed. A user-initiated disconnect sets
                    // `stopped` first and is deliberately silent here.
                    Logger.i(TAG, "Telnet session lost $host:$port")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    reconnector?.onConnectionLost()
                }
            }
        }
    }

    private fun handleIac(input: InputStream, sessionPipe: PipedOutputStream) {
        val cmd = input.read().also { if (it < 0) return }
        when (cmd) {
            // escaped literal 0xFF
            IAC -> sessionPipe.write(IAC)
            WILL -> {
                val opt = input.read().also { if (it < 0) return }
                val desired = acceptWill(opt)
                if (remoteOptionState[opt] != desired) {
                    remoteOptionState[opt] = desired
                    respond(if (desired) DO else DONT, opt)
                }
            }
            WONT -> {
                val opt = input.read().also { if (it < 0) return }
                if (remoteOptionState[opt] != false) {
                    remoteOptionState[opt] = false
                    respond(DONT, opt)
                }
            }
            DO -> {
                val opt = input.read().also { if (it < 0) return }
                val desired = acceptDo(opt)
                if (localOptionState[opt] != desired) {
                    localOptionState[opt] = desired
                    respond(if (desired) WILL else WONT, opt)
                    // For NAWS we MUST follow up with the actual size sub-neg.
                    if (desired && opt == OPT_NAWS) sendNaws(lastCols, lastRows)
                }
            }
            DONT -> {
                val opt = input.read().also { if (it < 0) return }
                if (localOptionState[opt] != false) {
                    localOptionState[opt] = false
                    respond(WONT, opt)
                }
            }
            SB -> handleSubneg(input)
            else -> {
                // GA, NOP, EC, EL, etc. — silently ignore.
            }
        }
    }

    private fun handleSubneg(input: InputStream) {
        // Read until IAC SE.
        val opt = input.read().also { if (it < 0) return }
        val buf = ByteArray(256)
        var len = 0
        // A hostile or broken server can send a subnegotiation that never
        // terminates with IAC SE. Bound the scan so the pump thread cannot be
        // parked in this loop indefinitely.
        var scanned = 0
        while (true) {
            if (++scanned > MAX_SUBNEG_SCAN_BYTES) {
                Logger.w(TAG, "Telnet subnegotiation exceeded $MAX_SUBNEG_SCAN_BYTES bytes without IAC SE — aborting")
                return
            }
            val b = input.read()
            if (b < 0) return
            if (b == IAC) {
                val nxt = input.read()
                if (nxt == SE) break
                if (nxt < 0) return
                if (nxt == IAC && len < buf.size) { buf[len++] = 0xFF.toByte(); continue }
                // Other IAC mid-subneg — drop and bail to keep state sane.
                return
            }
            if (len < buf.size) buf[len++] = b.toByte()
        }
        when (opt) {
            OPT_TERMINAL_TYPE -> {
                if (len >= 1 && (buf[0].toInt() and 0xFF) == TT_SEND) sendTerminalType()
            }
            // ignore others
        }
    }

    private fun acceptWill(opt: Int): Boolean = when (opt) {
        OPT_ECHO, OPT_SUPPRESS_GA -> true
        else -> false
    }

    private fun acceptDo(opt: Int): Boolean = when (opt) {
        OPT_TERMINAL_TYPE, OPT_NAWS, OPT_SUPPRESS_GA -> true
        else -> false
    }

    private fun respond(verb: Int, opt: Int) {
        val out = rawOut ?: return
        try {
            synchronized(writeLock) {
                out.write(byteArrayOf(IAC.toByte(), verb.toByte(), opt.toByte()))
                out.flush()
            }
        } catch (e: IOException) {
            Logger.w(TAG, "Telnet response IO: ${e.message}")
        }
    }

    private fun sendTerminalType() {
        val out = rawOut ?: return
        val name = termType.toByteArray(Charsets.US_ASCII)
        val pkt = ByteArray(6 + name.size)
        var i = 0
        pkt[i++] = IAC.toByte()
        pkt[i++] = SB.toByte()
        pkt[i++] = OPT_TERMINAL_TYPE.toByte()
        pkt[i++] = TT_IS.toByte()
        System.arraycopy(name, 0, pkt, i, name.size); i += name.size
        pkt[i++] = IAC.toByte()
        pkt[i] = SE.toByte()
        try {
            synchronized(writeLock) { out.write(pkt); out.flush() }
        } catch (_: IOException) {}
    }

    private fun sendNaws(cols: Int, rows: Int) {
        val out = rawOut ?: return
        try {
            synchronized(writeLock) { out.write(buildNawsPacket(cols, rows)); out.flush() }
        } catch (e: IOException) {
            // Transport tearing down mid-write — expected on disconnect; NAWS is
            // best-effort so this is debug-level, never a warning.
            Logger.d(TAG, "NAWS send skipped (io)", e)
        } catch (e: Exception) {
            // Any non-IO failure (e.g. the output stream being closed under us on
            // another thread, which surfaces as an NPE with a null message) must
            // never propagate: a size hint is not worth disrupting the session.
            // Log with the throwable so the cause is diagnosable, not a bare null.
            Logger.d(TAG, "NAWS send skipped", e)
        }
    }

    /**
     * Build an RFC 1073 NAWS subnegotiation.
     *
     * Two correctness requirements the naive encoding misses:
     *  - Dimensions are clamped to 1..65535. A 0 or negative value from a
     *    not-yet-measured view would tell the server the window has no size,
     *    and anything above 65535 silently truncates to a bogus dimension.
     *  - Any width/height byte that equals 255 must be doubled, because the
     *    payload travels inside an IAC-framed subnegotiation (RFC 854 §3 /
     *    RFC 1073). Sending a bare 0xFF for a 255-column window terminates
     *    the subnegotiation early and desynchronises the stream.
     */
    internal fun buildNawsPacket(cols: Int, rows: Int): ByteArray {
        val c = cols.coerceIn(1, 65535)
        val r = rows.coerceIn(1, 65535)
        val out = java.io.ByteArrayOutputStream(12)
        out.write(IAC)
        out.write(SB)
        out.write(OPT_NAWS)
        for (v in intArrayOf((c ushr 8) and 0xFF, c and 0xFF, (r ushr 8) and 0xFF, r and 0xFF)) {
            out.write(v)
            if (v == IAC) out.write(IAC)
        }
        out.write(IAC)
        out.write(SE)
        return out.toByteArray()
    }

    /**
     * OutputStream that escapes literal 0xFF as IAC IAC (RFC 854 §3).
     *
     * Holds [lock] — the connection's writeLock — for the whole of each write.
     * Without it, user keystrokes on this stream could interleave with an
     * option negotiation or NAWS subnegotiation packet sent from the reader
     * thread, splicing two Telnet commands into one corrupt byte sequence.
     */
    private class EscapingOutputStream(
        private val lock: Any,
        private val target: () -> OutputStream?
    ) : OutputStream() {
        override fun write(b: Int) {
            synchronized(lock) {
                val out = target() ?: throw IOException("Telnet socket closed")
                if ((b and 0xFF) == IAC) {
                    out.write(IAC); out.write(IAC)
                } else {
                    out.write(b)
                }
            }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                val out = target() ?: throw IOException("Telnet socket closed")
                // Fast path for the common case (no 0xFF in payload).
                var start = off
                val end = off + len
                var i = off
                while (i < end) {
                    if ((b[i].toInt() and 0xFF) == IAC) {
                        if (i > start) out.write(b, start, i - start)
                        out.write(IAC); out.write(IAC)
                        start = i + 1
                    }
                    i++
                }
                if (start < end) out.write(b, start, end - start)
            }
        }
        override fun flush() {
            synchronized(lock) { target()?.flush() }
        }
    }
}
