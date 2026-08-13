package io.github.tabssh.hypervisor.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VncUri].
 *
 * Covers the shapes RFC 7869 and the common TigerVNC/RealVNC userinfo
 * convention produce, plus the hostile inputs a tapped link can carry:
 * oversized fields, out-of-range ports, and percent-encoded separators.
 */
class VncUriTest {

    @Test
    fun `host only uses the default port`() {
        val c = VncUri.parse("vnc://console.example.com")
        assertEquals(VirtViewerType.VNC, c.type)
        assertEquals("console.example.com", c.host)
        assertEquals(VncUri.DEFAULT_PORT, c.port)
        assertEquals(0, c.tlsPort)
        assertNull(c.password)
        assertNull(c.username)
    }

    @Test
    fun `explicit port wins over the default`() {
        val c = VncUri.parse("vnc://10.0.0.5:5901")
        assertEquals("10.0.0.5", c.host)
        assertEquals(5901, c.port)
    }

    @Test
    fun `userinfo without a password yields a username only`() {
        val c = VncUri.parse("vnc://operator@host.example:5902")
        assertEquals("operator", c.username)
        assertNull(c.password)
        assertEquals(5902, c.port)
        assertEquals("host.example", c.host)
    }

    @Test
    fun `userinfo with a password yields both`() {
        val c = VncUri.parse("vnc://operator:s3cret@host.example:5902")
        assertEquals("operator", c.username)
        assertEquals("s3cret", c.password)
        assertEquals("host.example", c.host)
    }

    @Test
    fun `password only userinfo has no username`() {
        val c = VncUri.parse("vnc://:s3cret@host.example")
        assertNull(c.username)
        assertEquals("s3cret", c.password)
        assertEquals(VncUri.DEFAULT_PORT, c.port)
    }

    @Test
    fun `password query parameter is accepted`() {
        val c = VncUri.parse("vnc://host.example:5901?password=s3cret")
        assertEquals("s3cret", c.password)
        assertEquals(5901, c.port)
    }

    @Test
    fun `username query parameter is accepted`() {
        val c = VncUri.parse("vnc://host.example?username=operator&password=s3cret")
        assertEquals("operator", c.username)
        assertEquals("s3cret", c.password)
    }

    @Test
    fun `userinfo wins over the query parameters`() {
        val c = VncUri.parse("vnc://fromuserinfo:frominfo@host.example?username=fromquery&password=fromquery")
        assertEquals("fromuserinfo", c.username)
        assertEquals("frominfo", c.password)
    }

    @Test
    fun `port query parameter overrides the authority port`() {
        val c = VncUri.parse("vnc://host.example:5901?port=5906")
        assertEquals(5906, c.port)
    }

    @Test
    fun `percent encoded userinfo is decoded`() {
        val c = VncUri.parse("vnc://dom%5Cuser:p%40ss%20word@host.example")
        assertEquals("dom\\user", c.username)
        assertEquals("p@ss word", c.password)
        assertEquals("host.example", c.host)
    }

    @Test
    fun `host boundary is the last at sign`() {
        val c = VncUri.parse("vnc://user@name:pw@host.example:5903")
        assertEquals("user@name", c.username)
        assertEquals("pw", c.password)
        assertEquals("host.example", c.host)
        assertEquals(5903, c.port)
    }

    @Test
    fun `ipv6 literal is unwrapped`() {
        val c = VncUri.parse("vnc://[fe80::1]:5901")
        assertEquals("fe80::1", c.host)
        assertEquals(5901, c.port)
    }

    @Test
    fun `ipv6 literal without a port uses the default`() {
        val c = VncUri.parse("vnc://[::1]")
        assertEquals("::1", c.host)
        assertEquals(VncUri.DEFAULT_PORT, c.port)
    }

    @Test
    fun `path and fragment are ignored`() {
        val c = VncUri.parse("vnc://host.example:5901/some/path?title=Web#frag")
        assertEquals("host.example", c.host)
        assertEquals(5901, c.port)
        assertEquals("Web", c.title)
    }

    @Test
    fun `title parameter is carried through`() {
        val c = VncUri.parse("vnc://host.example?title=Build%20Server")
        assertEquals("Build Server", c.title)
    }

    @Test
    fun `scheme detection is case insensitive`() {
        assertTrue(VncUri.isVncUri("VNC://host.example"))
        assertEquals("host.example", VncUri.parse("VNC://host.example").host)
    }

    @Test
    fun `spice uri is not a vnc uri`() {
        assertFalse(VncUri.isVncUri("spice://host.example:5900"))
    }

    @Test
    fun `wrong scheme is rejected`() {
        assertThrows("spice://host.example:5900")
    }

    @Test
    fun `missing scheme separator is rejected`() {
        assertThrows("host.example:5901")
    }

    @Test
    fun `missing host is rejected`() {
        assertThrows("vnc://")
    }

    @Test
    fun `userinfo with no host is rejected`() {
        assertThrows("vnc://operator:s3cret@")
    }

    @Test
    fun `zero port is rejected`() {
        assertThrows("vnc://host.example:0")
    }

    @Test
    fun `negative port is rejected`() {
        assertThrows("vnc://host.example:-1")
    }

    @Test
    fun `out of range port is rejected`() {
        assertThrows("vnc://host.example:65536")
    }

    @Test
    fun `absurd port is rejected`() {
        assertThrows("vnc://host.example:999999999999")
    }

    @Test
    fun `non numeric port is rejected`() {
        assertThrows("vnc://host.example:vnc")
    }

    @Test
    fun `out of range port parameter is rejected`() {
        assertThrows("vnc://host.example?port=70000")
    }

    @Test
    fun `oversized uri is rejected`() {
        assertThrows("vnc://" + "a".repeat(UriParsing.MAX_URI_LEN))
    }

    @Test
    fun `oversized host is rejected`() {
        assertThrows("vnc://" + "a".repeat(UriParsing.MAX_HOST_LEN + 1))
    }

    @Test
    fun `oversized username is rejected`() {
        assertThrows("vnc://" + "u".repeat(UriParsing.MAX_HOST_LEN + 1) + "@host.example")
    }

    @Test
    fun `oversized password is rejected`() {
        assertThrows("vnc://u:" + "p".repeat(VncUri.MAX_PASSWORD_LEN + 1) + "@host.example")
    }

    @Test
    fun `encoded control character in the host is rejected`() {
        assertThrows("vnc://h%00evil:5901")
    }

    @Test
    fun `encoded separator in the host is rejected`() {
        assertThrows("vnc://host%2Fevil:5901")
    }

    @Test
    fun `malformed ipv6 host is rejected`() {
        assertThrows("vnc://[fe80::1:5901")
    }

    @Test
    fun `parseOrNull returns null for a bad uri`() {
        assertNull(VncUri.parseOrNull("vnc://host.example:0"))
        assertNotNull(VncUri.parseOrNull("vnc://host.example"))
    }

    @Test
    fun `toString redacts the password`() {
        val text = VncUri.parse("vnc://operator:s3cret@host.example").toString()
        assertFalse(text.contains("s3cret"))
        assertTrue(text.contains("xxxxx"))
    }

    @Test
    fun `parse error message never contains the password`() {
        val message = try {
            VncUri.parse("vnc://operator:s3cret@host.example:70000")
            ""
        } catch (e: VirtViewerParseException) {
            e.message.orEmpty()
        }
        assertFalse(message.contains("s3cret"))
    }

    private fun assertThrows(uri: String) {
        try {
            VncUri.parse(uri)
            throw AssertionError("expected a parse failure for: $uri")
        } catch (e: VirtViewerParseException) {
            assertNotNull(e.message)
        }
    }
}
