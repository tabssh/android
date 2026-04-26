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
    }

    private var socket: Socket? = null
    private var rawIn: InputStream? = null
    private var rawOut: OutputStream? = null

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
    private var termType: String = "xterm-256color"

    suspend fun connect(timeoutMs: Int = 15_000): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
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
                respond(if (acceptWill(opt)) DO else DONT, opt)
            }
            WONT -> {
                val opt = input.read().also { if (it < 0) return }
                respond(DONT, opt)
            }
            DO -> {
                val opt = input.read().also { if (it < 0) return }
                if (acceptDo(opt)) {
                    respond(WILL, opt)
                    // For NAWS we MUST follow up with the actual size sub-neg.
                    if (opt == OPT_NAWS) sendNaws(lastCols, lastRows)
                } else {
                    respond(WONT, opt)
                }
            }
            DONT -> {
                val opt = input.read().also { if (it < 0) return }
                respond(WONT, opt)
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
        while (true) {
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
            out.write(byteArrayOf(IAC.toByte(), verb.toByte(), opt.toByte()))
            out.flush()
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
        try { out.write(pkt); out.flush() } catch (_: IOException) {}
    }

    private fun sendNaws(cols: Int, rows: Int) {
        val out = rawOut ?: return
        val pkt = ByteArray(9)
        var i = 0
        pkt[i++] = IAC.toByte()
        pkt[i++] = SB.toByte()
        pkt[i++] = OPT_NAWS.toByte()
        pkt[i++] = ((cols ushr 8) and 0xFF).toByte()
        pkt[i++] = (cols and 0xFF).toByte()
        pkt[i++] = ((rows ushr 8) and 0xFF).toByte()
        pkt[i++] = (rows and 0xFF).toByte()
        pkt[i++] = IAC.toByte()
        pkt[i] = SE.toByte()
        try { out.write(pkt); out.flush() } catch (_: IOException) {}
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
