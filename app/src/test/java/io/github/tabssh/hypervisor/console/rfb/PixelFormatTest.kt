package io.github.tabssh.hypervisor.console.rfb

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [PixelFormat]'s wire-pixel conversion.
 *
 * Each test here covers a conversion that was previously wrong: 24bpp servers
 * rendered every pixel black, a zero channel maximum divided by zero, and a
 * big-endian ZRLE CPixel was reassembled with the little-endian byte order.
 */
class PixelFormatTest {

    private fun trueColor(
        bpp: Int,
        depth: Int,
        bigEndian: Int,
        redShift: Int,
        greenShift: Int,
        blueShift: Int,
        max: Int = 255
    ) = PixelFormat(
        bitsPerPixel = bpp,
        depth = depth,
        bigEndianFlag = bigEndian,
        trueColorFlag = 1,
        redMax = max, greenMax = max, blueMax = max,
        redShift = redShift, greenShift = greenShift, blueShift = blueShift
    )

    @Test
    fun `24bpp little-endian pixels keep their colour`() {
        val fmt = trueColor(bpp = 24, depth = 24, bigEndian = 0, redShift = 16, greenShift = 8, blueShift = 0)
        // Value 0x11AA33 stored little-endian: least significant byte first.
        val wire = byteArrayOf(0x33, 0xAA.toByte(), 0x11)
        assertEquals(0xFF11AA33.toInt(), fmt.toArgb(wire))
    }

    @Test
    fun `24bpp big-endian pixels keep their colour`() {
        val fmt = trueColor(bpp = 24, depth = 24, bigEndian = 1, redShift = 16, greenShift = 8, blueShift = 0)
        val wire = byteArrayOf(0x11, 0xAA.toByte(), 0x33)
        assertEquals(0xFF11AA33.toInt(), fmt.toArgb(wire))
    }

    @Test
    fun `a zero channel maximum yields a zero channel instead of dividing by zero`() {
        // A malformed ServerInit can advertise a zero maximum; scaling by it
        // used to throw ArithmeticException and kill the reader thread.
        val fmt = trueColor(bpp = 32, depth = 24, bigEndian = 0, redShift = 16, greenShift = 8, blueShift = 0, max = 0)
        val wire = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)
        assertEquals(0xFF000000.toInt(), fmt.toArgb(wire))
    }

    @Test
    fun `channels narrower than 8 bits scale up to full range`() {
        // RGB-565: red max 31 at shift 11, green max 63 at shift 5, blue max 31.
        val fmt = PixelFormat(
            bitsPerPixel = 16, depth = 16, bigEndianFlag = 0, trueColorFlag = 1,
            redMax = 31, greenMax = 63, blueMax = 31,
            redShift = 11, greenShift = 5, blueShift = 0
        )
        // All channels at maximum must map to pure white, not a dim grey.
        val wire = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(0xFFFFFFFF.toInt(), fmt.toArgb(wire))
    }

    @Test
    fun `3-byte CPixel is reassembled little-endian for a little-endian server`() {
        val fmt = trueColor(bpp = 32, depth = 24, bigEndian = 0, redShift = 16, greenShift = 8, blueShift = 0)
        assertEquals(3, fmt.cpixelBytes)
        // 0x00112233 little-endian drops the trailing zero byte.
        val wire = byteArrayOf(0x33, 0x22, 0x11)
        assertEquals(0xFF112233.toInt(), fmt.cpixelToArgb(wire))
    }

    @Test
    fun `3-byte CPixel is reassembled big-endian for a big-endian server`() {
        val fmt = trueColor(bpp = 32, depth = 24, bigEndian = 1, redShift = 16, greenShift = 8, blueShift = 0)
        assertEquals(3, fmt.cpixelBytes)
        // Big-endian omits the LEADING zero byte, so the remaining bytes are
        // the pixel's high-to-low colour bytes. Treating them as little-endian
        // shifted every channel and inverted the colour order.
        val wire = byteArrayOf(0x11, 0x22, 0x33)
        assertEquals(0xFF112233.toInt(), fmt.cpixelToArgb(wire))
    }
}
