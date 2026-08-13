package io.github.tabssh.hypervisor.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `spice://` / `spice+tls://` URI parser: the plain and TLS
 * scheme forms, query-parameter overrides, and the malformed or hostile
 * inputs a tapped link can carry.
 */
class SpiceUriTest {

    private fun parseFails(uri: String) {
        try {
            SpiceUri.parse(uri)
            throw AssertionError("expected parse to fail: $uri")
        } catch (e: VirtViewerParseException) {
            assertNotNull(e.message)
        }
    }

    // ── Scheme handling ───────────────────────────────────────────────────────

    @Test
    fun `plain scheme yields a plain port`() {
        val c = SpiceUri.parse("spice://192.0.2.10:5900")

        assertEquals(VirtViewerType.SPICE, c.type)
        assertEquals("192.0.2.10", c.host)
        assertEquals(5900, c.port)
        assertEquals(0, c.tlsPort)
        assertFalse(c.isTls)
        assertEquals(5900, c.effectivePort)
    }

    @Test
    fun `tls scheme yields a tls port`() {
        val c = SpiceUri.parse("spice+tls://host.example.org:5901")

        assertEquals(0, c.port)
        assertEquals(5901, c.tlsPort)
        assertTrue(c.isTls)
        assertEquals(5901, c.effectivePort)
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals(5900, SpiceUri.parse("SPICE://h:5900").port)
        assertEquals(5901, SpiceUri.parse("Spice+TLS://h:5901").tlsPort)
    }

    @Test
    fun `isSpiceUri recognises both schemes and nothing else`() {
        assertTrue(SpiceUri.isSpiceUri("spice://h:1"))
        assertTrue(SpiceUri.isSpiceUri("spice+tls://h:1"))
        assertFalse(SpiceUri.isSpiceUri("ssh://h:22"))
        assertFalse(SpiceUri.isSpiceUri("https://example.org"))
        assertFalse(SpiceUri.isSpiceUri("h:1"))
    }

    // ── Query parameters ──────────────────────────────────────────────────────

    @Test
    fun `tls-port parameter supplies a tls-only target`() {
        val c = SpiceUri.parse("spice://host.example.org/?tls-port=5901")

        assertEquals("host.example.org", c.host)
        assertEquals(0, c.port)
        assertEquals(5901, c.tlsPort)
        assertTrue(c.isTls)
    }

    @Test
    fun `both ports can be given together`() {
        val c = SpiceUri.parse("spice://h:5900?tls-port=5901")

        assertEquals(5900, c.port)
        assertEquals(5901, c.tlsPort)
        assertEquals(5900, c.effectivePort)
    }

    @Test
    fun `port parameter overrides the authority port`() {
        assertEquals(5910, SpiceUri.parse("spice://h:5900?port=5910").port)
    }

    @Test
    fun `carries password ca host-subject proxy and title`() {
        val c = SpiceUri.parse(
            "spice://h:5900?password=s3%20cret&host-subject=O%3DExample%2CCN%3Dh" +
                "&proxy=http%3A%2F%2Fp%3A3128&title=web-01&ca=PEMDATA"
        )

        assertEquals("s3 cret", c.password)
        assertEquals("O=Example,CN=h", c.hostSubject)
        assertEquals("http://p:3128", c.proxy)
        assertEquals("web-01", c.title)
        assertEquals("PEMDATA", c.caCert)
    }

    @Test
    fun `parameter names are case insensitive and the last value wins`() {
        val c = SpiceUri.parse("spice://h:5900?TLS-Port=5901&tls-port=5902")

        assertEquals(5902, c.tlsPort)
    }

    @Test
    fun `fragment and path are ignored`() {
        val c = SpiceUri.parse("spice://h:5900/some/path?title=t#fragment")

        assertEquals("h", c.host)
        assertEquals(5900, c.port)
        assertEquals("t", c.title)
    }

    @Test
    fun `ipv6 literals are accepted with and without a port`() {
        assertEquals("::1", SpiceUri.parse("spice://[::1]:5900").host)
        assertEquals(5900, SpiceUri.parse("spice://[::1]:5900").port)

        val tlsOnly = SpiceUri.parse("spice://[2001:db8::1]/?tls-port=5901")
        assertEquals("2001:db8::1", tlsOnly.host)
        assertEquals(5901, tlsOnly.tlsPort)
    }

    // ── Malformed and hostile ─────────────────────────────────────────────────

    @Test
    fun `rejects a non-spice scheme`() {
        parseFails("vnc://h:5900")
        parseFails("https://example.org")
    }

    @Test
    fun `rejects a URI with no scheme separator`() = parseFails("spice:h:5900")

    @Test
    fun `rejects a missing host`() {
        parseFails("spice://")
        parseFails("spice:///?tls-port=5901")
    }

    @Test
    fun `rejects a target with no port anywhere`() = parseFails("spice://h")

    @Test
    fun `rejects userinfo`() = parseFails("spice://user:pass@h:5900")

    @Test
    fun `rejects out-of-range and non-numeric ports`() {
        parseFails("spice://h:0")
        parseFails("spice://h:65536")
        parseFails("spice://h:notaport")
        parseFails("spice://h:5900?tls-port=0")
        parseFails("spice://h:5900?tls-port=65536")
        parseFails("spice://h:5900?port=99999999999")
    }

    @Test
    fun `rejects a malformed ipv6 authority`() {
        parseFails("spice://[::1:5900")
        parseFails("spice://[::1]5900")
    }

    @Test
    fun `rejects an oversized URI`() {
        parseFails("spice://h:5900?title=" + "x".repeat(SpiceUri.MAX_URI_LEN))
    }

    @Test
    fun `rejects an oversized host`() {
        parseFails("spice://" + "h".repeat(VirtViewerFile.MAX_HOST_LEN + 1) + ":5900")
    }

    @Test
    fun `rejects a host carrying a control character`() =
        parseFails("spice://h%00evil:5900")

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(SpiceUri.parseOrNull("vnc://h:5900"))
        assertNotNull(SpiceUri.parseOrNull("spice://h:5900"))
    }

    @Test
    fun `toString redacts the password`() {
        val rendered = SpiceUri.parse("spice://h:5900?password=hunter2").toString()

        assertFalse(rendered.contains("hunter2"))
        assertTrue(rendered.contains("xxxxx"))
    }

    @Test
    fun `converts to spice params`() {
        val params = SpiceUri.parse("spice+tls://h:5901?password=t&ca=PEM").toSpiceParams()

        assertEquals("h", params.host)
        assertEquals(0, params.port)
        assertEquals(5901, params.tlsPort)
        assertEquals("t", params.password)
        assertTrue(params.tlsVerify)
    }
}
