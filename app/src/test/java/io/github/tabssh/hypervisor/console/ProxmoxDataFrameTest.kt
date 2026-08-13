package io.github.tabssh.hypervisor.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Proxmox termproxy data frame.
 *
 * termproxy reads exactly LENGTH bytes of MSG off the wire, and OkHttp encodes
 * a text frame as UTF-8. The frame therefore has to declare the UTF-8 byte
 * count of MSG — decoding the input as ISO-8859-1 (the previous behaviour)
 * declared fewer bytes than were actually sent for any non-ASCII input, which
 * desynchronised the stream.
 */
class ProxmoxDataFrameTest {

    private fun frameOf(text: String): String =
        ConsoleWebSocketClient.proxmoxDataFrame(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `ascii input declares its byte length`() {
        assertEquals("0:2:ls", frameOf("ls"))
    }

    @Test
    fun `carriage return is preserved verbatim`() {
        assertEquals("0:3:ls\r", frameOf("ls\r"))
    }

    @Test
    fun `declared length matches the utf8 bytes actually put on the wire`() {
        for (input in listOf("ls", "é", "日本語", "naïve\r", "€uro")) {
            val frame = frameOf(input)
            val parts = frame.split(":", limit = 3)
            assertEquals("0", parts[0])
            val declared = parts[1].toInt()
            val onWire = parts[2].toByteArray(Charsets.UTF_8).size
            assertEquals("length mismatch for '$input'", onWire, declared)
        }
    }

    @Test
    fun `multibyte input is not truncated to one byte per character`() {
        // The ISO-8859-1 regression declared 3 here (the input byte count of the
        // decoded latin-1 string) while sending 6 UTF-8 bytes.
        val frame = frameOf("é€")
        assertTrue("expected the full multibyte payload, got '$frame'", frame.startsWith("0:5:"))
    }

    @Test
    fun `empty input still produces a well formed frame`() {
        assertEquals("0:0:", frameOf(""))
    }
}
