package io.github.tabssh.hypervisor.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Stream-level tests for [VncAuthProbe].
 *
 * Each case feeds the probe a scripted server side of the RFB handshake and
 * checks both the verdict and that the probe stops before authenticating.
 */
class VncAuthProbeTest {

    private fun probe(vararg parts: ByteArray): Pair<Boolean, ByteArray> {
        val server = ByteArrayOutputStream()
        parts.forEach { server.write(it) }
        val client = ByteArrayOutputStream()
        val result = VncAuthProbe.requiresPassword(ByteArrayInputStream(server.toByteArray()), client)
        return result to client.toByteArray()
    }

    private fun greeting(version: String): ByteArray = version.toByteArray(Charsets.US_ASCII)

    private fun types(vararg values: Int): ByteArray =
        byteArrayOf(values.size.toByte(), *values.map { it.toByte() }.toByteArray())

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    @Test
    fun `vnc auth only means a password is needed`() {
        val (needed, sent) = probe(greeting("RFB 003.008\n"), types(2))
        assertTrue(needed)
        assertEquals("RFB 003.008\n", String(sent, Charsets.US_ASCII))
    }

    @Test
    fun `security none means no password`() {
        val (needed, _) = probe(greeting("RFB 003.008\n"), types(1, 2))
        assertFalse(needed)
    }

    @Test
    fun `vencrypt on offer means no password prompt`() {
        val (needed, _) = probe(greeting("RFB 003.008\n"), types(19, 2))
        assertFalse(needed)
    }

    @Test
    fun `rfb 3 7 server is answered with 3 7`() {
        val (needed, sent) = probe(greeting("RFB 003.007\n"), types(2))
        assertTrue(needed)
        assertEquals("RFB 003.007\n", String(sent, Charsets.US_ASCII))
    }

    @Test
    fun `higher minor version negotiates 3 8`() {
        val (_, sent) = probe(greeting("RFB 003.889\n"), types(1))
        assertEquals("RFB 003.008\n", String(sent, Charsets.US_ASCII))
    }

    @Test
    fun `unknown low minor version falls back to 3 3`() {
        val (needed, sent) = probe(greeting("RFB 003.005\n"), u32(1))
        assertFalse(needed)
        assertEquals("RFB 003.003\n", String(sent, Charsets.US_ASCII))
    }

    @Test
    fun `rfb 3 3 server dictating vnc auth needs a password`() {
        val (needed, sent) = probe(greeting("RFB 003.003\n"), u32(2))
        assertTrue(needed)
        assertEquals("RFB 003.003\n", String(sent, Charsets.US_ASCII))
    }

    @Test
    fun `rfb 3 3 server dictating none needs no password`() {
        val (needed, _) = probe(greeting("RFB 003.003\n"), u32(1))
        assertFalse(needed)
    }

    @Test
    fun `zero type count is treated as no password`() {
        val (needed, _) = probe(greeting("RFB 003.008\n"), byteArrayOf(0))
        assertFalse(needed)
    }

    @Test
    fun `non rfb greeting is rejected without writing a reply`() {
        val (needed, sent) = probe(greeting("HTTP/1.1 200"))
        assertFalse(needed)
        assertEquals(0, sent.size)
    }

    @Test
    fun `unparseable minor version is rejected`() {
        val (needed, sent) = probe(greeting("RFB 003.xxx\n"))
        assertFalse(needed)
        assertEquals(0, sent.size)
    }

    @Test
    fun `probe never sends more than the version reply`() {
        val (_, sent) = probe(greeting("RFB 003.008\n"), types(2))
        assertEquals(12, sent.size)
    }
}
