package io.github.tabssh.ssh.connection

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Transport-audit regression tests for [TelnetConnection.buildNawsPacket].
 *
 * RFC 1073 carries the window size as raw 16-bit values inside an
 * IAC SB NAWS … IAC SE subnegotiation. Because 255 is also IAC, any size byte
 * equal to 255 must be doubled or the server sees the subnegotiation end
 * early and resynchronises on garbage. The constructor does no I/O, so this
 * is exercisable off-device.
 */
class TelnetNawsTest {

    private fun packet(cols: Int, rows: Int): List<Int> =
        TelnetConnection("test.invalid", 23).buildNawsPacket(cols, rows).map { it.toInt() and 0xFF }

    @Test
    fun `encodes an ordinary window size`() {
        assertEquals(
            listOf(IAC, SB, OPT_NAWS, 0, 80, 0, 24, IAC, SE),
            packet(80, 24)
        )
    }

    @Test
    fun `encodes a size above one byte`() {
        // 300 = 0x012C
        assertEquals(
            listOf(IAC, SB, OPT_NAWS, 1, 44, 0, 24, IAC, SE),
            packet(300, 24)
        )
    }

    @Test
    fun `doubles a size byte that collides with IAC`() {
        // 255 columns → low byte 0xFF must be sent as FF FF.
        assertEquals(
            listOf(IAC, SB, OPT_NAWS, 0, IAC, IAC, 0, 24, IAC, SE),
            packet(255, 24)
        )
    }

    @Test
    fun `clamps out-of-range dimensions into the 16-bit range`() {
        assertEquals(listOf(IAC, SB, OPT_NAWS, 0, 1, 0, 1, IAC, SE), packet(0, -5))
        // 65535 = FF FF in both dimensions, so all four size bytes are doubled.
        assertEquals(
            listOf(IAC, SB, OPT_NAWS, IAC, IAC, IAC, IAC, IAC, IAC, IAC, IAC, IAC, SE),
            packet(999_999, 999_999)
        )
    }

    private companion object {
        const val IAC = 255
        const val SB = 250
        const val SE = 240
        const val OPT_NAWS = 31
    }
}
