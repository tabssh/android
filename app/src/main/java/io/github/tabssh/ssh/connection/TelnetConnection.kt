package io.github.tabssh.ssh.connection

import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
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
    private val port: Int = 23
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

    // What we expose to TermuxBridge:
    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
    private val outFilter = EscapingOutputStream { socket?.getOutputStream() }

    val inputStream: InputStream get() = pipeIn
    val outputStream: OutputStream get() = outFilter

    private var pumpThread: Thread? = null
    @Volatile private var stopped = false
    @Volatile var connected: Boolean = false; private set

    /** NAWS — push window size. Safe to call any time after connect. */
    @Volatile private var lastCols = 80
    @Volatile private var lastRows = 24
    private val termType: String = "xterm-256color"

    suspend fun connect(timeoutMs: Int = 15_000): Boolean = withContext(Dispatchers.IO) {
        // A prior connect()/disconnect() cycle latches this permanently; reset
        // it here so the pump loop below actually runs on reconnect.
        stopped = false
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
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Telnet connect failed: $host:$port", e)
            // Ensure the freshly allocated socket is released even if it was
            // never assigned to the field (e.g. connect() threw before line
            // `socket = s` ran). disconnect() only closes the field.
            try { s.close() } catch (_: Exception) {}
            disconnect()
            false
        }
    }

    fun disconnect() {
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
        try {
            sendNaws(cols, rows)
        } catch (e: Exception) {
            Logger.w(TAG, "NAWS send failed: ${e.message}")
        }
    }

    private fun startPump() {
        pumpThread = thread(name = "telnet-pump-$host:$port", isDaemon = true) {
            val input = rawIn ?: return@thread
            try {
                while (!stopped) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == IAC) {
                        handleIac(input)
                    } else {
                        pipeOut.write(b)
                    }
                }
            } catch (e: IOException) {
                if (!stopped) Logger.w(TAG, "Telnet pump IO: ${e.message}")
            } catch (e: Exception) {
                Logger.e(TAG, "Telnet pump crashed", e)
            } finally {
                connected = false
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleIac(input: InputStream) {
        val cmd = input.read().also { if (it < 0) return }
        when (cmd) {
            IAC -> pipeOut.write(IAC) // escaped literal 0xFF
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
        } catch (_: IOException) {}
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

    /** OutputStream that escapes literal 0xFF as IAC IAC (RFC 854 §3). */
    private class EscapingOutputStream(private val target: () -> OutputStream?) : OutputStream() {
        override fun write(b: Int) {
            val out = target() ?: throw IOException("Telnet socket closed")
            if ((b and 0xFF) == IAC) {
                out.write(IAC); out.write(IAC)
            } else {
                out.write(b)
            }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
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
        override fun flush() { target()?.flush() }
    }
}
