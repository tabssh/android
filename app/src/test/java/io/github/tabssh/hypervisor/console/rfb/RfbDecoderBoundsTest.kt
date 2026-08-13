package io.github.tabssh.hypervisor.console.rfb

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for [RfbDecoder]'s handling of hostile rectangle geometry.
 *
 * The VNC server is untrusted input (IDEA.md § Trust boundaries), so every
 * coordinate, dimension and count taken off the wire has to be checked. Each
 * test below reproduces a case that previously either corrupted memory outside
 * the rectangle, threw an ArrayIndexOutOfBoundsException that killed the reader
 * thread, or silently desynchronised the stream.
 */
class RfbDecoderBoundsTest {

    private val fmt = PixelFormat.PREFERRED

    private fun decoder() = RfbDecoder(fmt)

    private fun din(vararg bytes: Int): DataInputStream =
        DataInputStream(ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() }))

    /** Four little-endian bytes for an 0xFF-alpha ARGB colour. */
    private fun pixel(argb: Int): IntArray = intArrayOf(
        argb and 0xFF,
        (argb ushr 8) and 0xFF,
        (argb ushr 16) and 0xFF,
        0
    )

    @Test
    fun `a rectangle extending past the framebuffer is rejected`() {
        val fb = IntArray(16)
        val ex = assertFailsWith<IOException> {
            decoder().decodeRect(din(), fb, 4, 4, 2, 2, 4, 4, RfbConstants.ENC_RAW)
        }
        assertTrue(ex.message!!.contains("does not fit"))
    }

    @Test
    fun `a rectangle with negative coordinates is rejected`() {
        val fb = IntArray(16)
        assertFailsWith<IOException> {
            decoder().decodeRect(din(), fb, 4, 4, -1, 0, 2, 2, RfbConstants.ENC_RAW)
        }
    }

    @Test
    fun `a framebuffer smaller than its declared geometry is rejected`() {
        // Guards against a desktop-resize race handing the decoder a stale,
        // too-small pixel array for the new dimensions.
        val fb = IntArray(4)
        assertFailsWith<IOException> {
            decoder().decodeRect(din(), fb, 4, 4, 0, 0, 4, 4, RfbConstants.ENC_RAW)
        }
    }

    @Test
    fun `an unhandled encoding is fatal rather than being skipped`() {
        val fb = IntArray(16)
        assertFailsWith<IOException> {
            decoder().decodeRect(din(), fb, 4, 4, 0, 0, 2, 2, 0x6000)
        }
    }

    @Test
    fun `CopyRect with an out-of-bounds source is dropped and consumes its payload`() {
        val fb = IntArray(16) { 0x11 }
        // srcX = 3 with w = 2 runs one pixel past a 4-wide framebuffer.
        val stream = din(0x00, 0x03, 0x00, 0x00)
        decoder().decodeRect(stream, fb, 4, 4, 0, 0, 2, 2, RfbConstants.ENC_COPY_RECT)
        assertTrue(fb.all { it == 0x11 })
        // The 4-byte header must still have been read, or the stream desyncs.
        assertEquals(0, stream.available())
    }

    @Test
    fun `a negative RRE sub-rectangle count is fatal`() {
        // repeat(-1) is a silent no-op, so the sub-rectangle payload stayed in
        // the stream and every later message was parsed at the wrong offset.
        val fb = IntArray(16)
        val stream = din(0xFF, 0xFF, 0xFF, 0xFF)
        assertFailsWith<IOException> {
            decoder().decodeRect(stream, fb, 4, 4, 0, 0, 4, 4, RfbConstants.ENC_RRE)
        }
    }

    @Test
    fun `an RRE sub-rectangle running past the framebuffer is clipped`() {
        val fb = IntArray(16)
        val bytes = ArrayList<Int>()
        // One sub-rectangle.
        bytes.addAll(listOf(0x00, 0x00, 0x00, 0x01))
        // Background pixel: black.
        bytes.addAll(pixel(0x000000).toList())
        // Foreground pixel: 0x00CCDDEE.
        bytes.addAll(pixel(0xCCDDEE).toList())
        // Sub-rectangle at (3,3) claiming to be 100x100.
        bytes.addAll(listOf(0x00, 0x03, 0x00, 0x03, 0x00, 0x64, 0x00, 0x64))
        decoder().decodeRect(
            din(*bytes.toIntArray()), fb, 4, 4, 0, 0, 4, 4, RfbConstants.ENC_RRE
        )
        // Only the single in-bounds pixel may be painted.
        assertEquals(0xFFCCDDEE.toInt(), fb[15])
        assertEquals(15, fb.count { it == 0xFF000000.toInt() })
    }

    @Test
    fun `a Hextile sub-rectangle larger than its tile is clipped to the tile`() {
        val fb = IntArray(16 * 16)
        val bytes = ArrayList<Int>()
        // BG specified + FG specified + any sub-rects.
        bytes.add(
            RfbConstants.HEXTILE_BG_SPECIFIED or
                RfbConstants.HEXTILE_FG_SPECIFIED or
                RfbConstants.HEXTILE_ANY_SUBRECTS
        )
        bytes.addAll(pixel(0x000000).toList())
        bytes.addAll(pixel(0xCCDDEE).toList())
        // One sub-rectangle at tile offset (15,15) with the maximum encodable
        // 16x16 size, which runs 15 pixels past the tile in both axes.
        bytes.add(1)
        bytes.add((15 shl 4) or 15)
        bytes.add((15 shl 4) or 15)
        decoder().decodeRect(
            din(*bytes.toIntArray()), fb, 16, 16, 0, 0, 16, 16, RfbConstants.ENC_HEXTILE
        )
        assertEquals(1, fb.count { it == 0xFFCCDDEE.toInt() })
        assertEquals(0xFFCCDDEE.toInt(), fb[15 * 16 + 15])
    }

    @Test
    fun `a reserved Tight compression type is fatal`() {
        // compType 0xB..0xF is undefined in every Tight variant; treating it as
        // BasicCompression read a wrong-length payload and desynced the zlib
        // stream for the rest of the session.
        val fb = IntArray(16)
        assertFailsWith<IOException> {
            decoder().decodeRect(din(0xB0), fb, 4, 4, 0, 0, 2, 2, RfbConstants.ENC_TIGHT)
        }
    }

    @Test
    fun `a zero-dimension rectangle still consumes its payload`() {
        // Zero-width and zero-height rectangles are legal; reference clients
        // parse the payload in full rather than special-casing them. Skipping
        // the payload left the Tight control byte in the stream.
        val fb = IntArray(16)
        val stream = din(0x80, 0x11, 0x22, 0x33)
        decoder().decodeRect(stream, fb, 4, 4, 0, 0, 0, 0, RfbConstants.ENC_TIGHT)
        assertEquals(0, stream.available())
        assertTrue(fb.all { it == 0 })
    }
}
