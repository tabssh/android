package io.github.tabssh.hypervisor.console.rfb

import io.github.tabssh.hypervisor.console.ByteStreamPipe
import io.github.tabssh.hypervisor.vnc.console.VncConsoleChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression test for the Proxmox VNC console key-input path:
 * a fake RFB 3.8 server (VncAuth, like Proxmox vncproxy) is wired to a real
 * [RfbClient] over two [ByteStreamPipe]s, and a real [VncConsoleChannel]
 * drives input exactly the way TerminalPagerAdapter wires VncView callbacks.
 *
 * Asserts the server actually receives the C2S KeyEvent messages — down AND
 * up, in order — proving the full sendKey/sendText/sendRawKeyEvent →
 * RfbClient → wire path works after the VncAuth handshake.
 */
class RfbClientKeyInputEndToEndTest {

    /** One received C2S KeyEvent: keysym (unsigned) + down flag. */
    private data class Key(val keysym: Long, val down: Boolean)

    private val receivedKeys = LinkedBlockingQueue<Key>()
    private val handshakeDone = CountDownLatch(1)

    // Server → client and client → server transports.
    private val s2c = ByteStreamPipe()
    private val c2s = ByteStreamPipe()

    private var client: RfbClient? = null
    private var channel: VncConsoleChannel? = null
    private var serverThread: Thread? = null

    @After
    fun tearDown() {
        channel?.close()
        client?.stop()
        s2c.close()
        c2s.close()
        serverThread?.interrupt()
        serverThread?.join(2_000)
    }

    /**
     * Run the fake server's RFB 3.8 + VncAuth handshake, then parse client
     * messages forever, recording KeyEvents and skipping everything else.
     */
    private fun runFakeServer() {
        val din = DataInputStream(c2s.source)
        val dout = DataOutputStream(s2c.sink)

        // ProtocolVersion exchange.
        dout.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        dout.flush()
        val clientVersion = ByteArray(12)
        din.readFully(clientVersion)

        // Security: offer VncAuth only (type 2), like Proxmox vncproxy.
        dout.writeByte(1)
        dout.writeByte(2)
        dout.flush()
        val chosen = din.readUnsignedByte()
        assertEquals("client must choose VncAuth", 2, chosen)

        // 16-byte DES challenge; response contents are irrelevant here —
        // the test asserts transport framing, not DES math.
        dout.write(ByteArray(16) { it.toByte() })
        dout.flush()
        val response = ByteArray(16)
        din.readFully(response)

        // SecurityResult: OK.
        dout.writeInt(0)
        dout.flush()

        // ClientInit (shared flag — 1 for non-console mode).
        val shared = din.readUnsignedByte()
        assertEquals("non-console client must request shared access", 1, shared)

        // ServerInit: 640x480, 32bpp true-colour pixel format, neutral name.
        dout.writeShort(640)
        dout.writeShort(480)
        dout.writeByte(32)   // bits-per-pixel
        dout.writeByte(24)   // depth
        dout.writeByte(0)    // big-endian flag
        dout.writeByte(1)    // true-colour flag
        dout.writeShort(255) // red max
        dout.writeShort(255) // green max
        dout.writeShort(255) // blue max
        dout.writeByte(16)   // red shift
        dout.writeByte(8)    // green shift
        dout.writeByte(0)    // blue shift
        dout.write(ByteArray(3))
        val name = "fake-rfb-test-server".toByteArray(Charsets.US_ASCII)
        dout.writeInt(name.size)
        dout.write(name)
        dout.flush()
        handshakeDone.countDown()

        // C2S message loop — record KeyEvents, skip everything else by its
        // RFC 6143 message length so the stream never desyncs.
        while (!Thread.currentThread().isInterrupted) {
            val type = try { din.readUnsignedByte() } catch (_: EOFException) { return }
            when (type) {
                // SetPixelFormat: 3 pad + 16-byte format.
                0 -> din.skipFully(19)
                // SetEncodings: 1 pad + u16 count + count * s32.
                2 -> {
                    din.skipFully(1)
                    val count = din.readUnsignedShort()
                    din.skipFully(count * 4)
                }
                // FramebufferUpdateRequest: incremental + x + y + w + h.
                3 -> din.skipFully(9)
                // KeyEvent: down + 2 pad + u32 keysym — the assertion target.
                4 -> {
                    val down = din.readUnsignedByte() == 1
                    din.skipFully(2)
                    val keysym = din.readInt().toLong() and 0xFFFFFFFFL
                    receivedKeys.put(Key(keysym, down))
                }
                // PointerEvent: mask + x + y.
                5 -> din.skipFully(5)
                // ClientCutText: 3 pad + u32 length + text.
                6 -> {
                    din.skipFully(3)
                    din.skipFully(din.readInt().toLong())
                }
                // EnableContinuousUpdates: enable + x + y + w + h.
                150 -> din.skipFully(9)
                // SetDesktopSize: pad + w + h + numScreens + pad + screens.
                251 -> {
                    din.skipFully(5)
                    val screens = din.readUnsignedShort()
                    din.skipFully(2 + screens * 16)
                }
                else -> throw AssertionError("Unexpected C2S message type $type")
            }
        }
    }

    private fun DataInputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() may return 0 on a blocking stream — fall back to read.
                if (read() < 0) throw EOFException("stream ended mid-skip")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun DataInputStream.skipFully(n: Int) = skipFully(n.toLong())

    /** Start the fake server + real client and block until ServerInit is done. */
    private fun connect(): VncConsoleChannel {
        serverThread = Thread(::runFakeServer, "fake-rfb-server").also {
            it.isDaemon = true
            it.start()
        }
        val rfb = RfbClient(
            inputStream = s2c.source,
            outputStream = c2s.sink,
            vncPassword = "vncticket"
        )
        client = rfb
        rfb.start()
        assertTrue(
            "handshake did not complete",
            handshakeDone.await(5, TimeUnit.SECONDS)
        )
        // sendKeyEvent drops keys until serverInit() has populated fb size —
        // wait for it so the test exercises the steady-state input path.
        val deadline = System.currentTimeMillis() + 5_000
        while (rfb.framebufferWidth <= 0) {
            assertTrue("serverInit never completed", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        return VncConsoleChannel(rfb).also { channel = it }
    }

    private fun nextKey(): Key {
        val key = receivedKeys.poll(5, TimeUnit.SECONDS)
        assertTrue("timed out waiting for a KeyEvent on the server", key != null)
        return key!!
    }

    @Test
    fun rawKeyEventsArriveDownAndUpInOrder() {
        val ch = connect()
        // The TerminalPagerAdapter wiring: VncView.onKeyEvent forwards both
        // states raw — a modifier chord must arrive held-down, not collapsed.
        ch.sendRawKeyEvent(RfbConstants.KEY_CTRL_L, true)
        ch.sendRawKeyEvent(0x63L, true)   // 'c' down
        ch.sendRawKeyEvent(0x63L, false)  // 'c' up
        ch.sendRawKeyEvent(RfbConstants.KEY_CTRL_L, false)
        assertEquals(Key(RfbConstants.KEY_CTRL_L, true), nextKey())
        assertEquals(Key(0x63L, true), nextKey())
        assertEquals(Key(0x63L, false), nextKey())
        assertEquals(Key(RfbConstants.KEY_CTRL_L, false), nextKey())
    }

    @Test
    fun sendKeyProducesDownUpPair() {
        val ch = connect()
        ch.sendKey(RfbConstants.KEY_RETURN)
        assertEquals(Key(RfbConstants.KEY_RETURN, true), nextKey())
        assertEquals(Key(RfbConstants.KEY_RETURN, false), nextKey())
    }

    @Test
    fun sendTextBracketsShiftedCharsWithShift() {
        val ch = connect()
        // 'H' needs Shift on a US layout; 'i' does not.
        ch.sendText("Hi")
        assertEquals(Key(RfbConstants.KEY_SHIFT_L, true), nextKey())
        assertEquals(Key(0x48L, true), nextKey())
        assertEquals(Key(0x48L, false), nextKey())
        assertEquals(Key(RfbConstants.KEY_SHIFT_L, false), nextKey())
        assertEquals(Key(0x69L, true), nextKey())
        assertEquals(Key(0x69L, false), nextKey())
    }

    @Test
    fun keyEventBeforeHandshakeIsDroppedNotWritten() {
        // Un-started client: fbWidth/fbHeight are 0, so a key typed during
        // the handshake must be dropped instead of desyncing the RFB stream.
        val pipeOut = ByteStreamPipe()
        val rfb = RfbClient(
            inputStream = ByteStreamPipe().source,
            outputStream = pipeOut.sink,
            vncPassword = "vncticket"
        )
        rfb.sendKeyEvent(0x61L, true)
        rfb.sendClipboardText("blocked")
        assertEquals(
            "pre-handshake input must not reach the wire",
            0, pipeOut.bufferedBytes
        )
        pipeOut.close()
    }

    @Test
    fun serverParserSeesNoStrayMessages() {
        val ch = connect()
        ch.sendKey(0x61L)
        assertEquals(Key(0x61L, true), nextKey())
        assertEquals(Key(0x61L, false), nextKey())
        // Anything unexpected the parser saw would have thrown AssertionError
        // on the server thread; also verify no extra key events trickled in.
        assertNull(receivedKeys.poll(300, TimeUnit.MILLISECONDS))
    }
}
