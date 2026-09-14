package io.github.tabssh.hypervisor.libvirt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for parsing `virsh domdisplay --include-password` output.
 *
 * libvirt puts the display password in URI userinfo only for `vnc://`; for
 * `spice://` it emits a raw `password=` query parameter, and in both cases the
 * value is written verbatim, never percent-encoded (virsh-domain.c,
 * virshGetOneDisplay). The old code read only SPICE userinfo (password always
 * empty) and URL-decoded the VNC value (corrupting `+` and `%hh` sequences).
 */
class LibvirtDisplayUriTest {

    @Test
    fun `spice password comes from the query parameter`() {
        val parsed = LibvirtApiClient.parseSpiceUri(
            "spice://127.0.0.1:5900?tls-port=5901&password=secretTicket"
        )!!
        assertEquals("secretTicket", parsed.password)
        assertEquals("127.0.0.1", parsed.host)
        assertEquals(5900, parsed.port)
        assertEquals(5901, parsed.tlsPort)
    }

    @Test
    fun `spice query password wins over userinfo`() {
        val parsed = LibvirtApiClient.parseSpiceUri(
            "spice://:fromUserinfo@127.0.0.1:5900?password=fromQuery"
        )!!
        assertEquals("fromQuery", parsed.password)
    }

    @Test
    fun `spice password is kept raw, never url-decoded`() {
        // URLDecoder would turn '+' into a space and eat the '%41'.
        val parsed = LibvirtApiClient.parseSpiceUri(
            "spice://127.0.0.1:5900?password=a+b%41c"
        )!!
        assertEquals("a+b%41c", parsed.password)
    }

    @Test
    fun `spice ipv6 listen address is unwrapped`() {
        val parsed = LibvirtApiClient.parseSpiceUri("spice://[::1]:5900")!!
        assertEquals("::1", parsed.host)
        assertEquals(5900, parsed.port)
    }

    @Test
    fun `spice uri with neither port nor tls-port is rejected`() {
        assertNull(LibvirtApiClient.parseSpiceUri("spice://127.0.0.1?password=x"))
    }

    @Test
    fun `spice uri with an out-of-range port is rejected`() {
        assertNull(LibvirtApiClient.parseSpiceUri("spice://127.0.0.1:99999"))
    }

    @Test
    fun `vnc userinfo password is extracted verbatim`() {
        // A literal '+' must survive; URL-decoding turned it into a space.
        assertEquals(
            "a+b2cd42",
            LibvirtApiClient.extractVncUserinfoPassword("vnc://:a+b2cd42@127.0.0.1:5901")
        )
    }

    @Test
    fun `vnc percent sequences are not reinterpreted`() {
        assertEquals(
            "p%20ss",
            LibvirtApiClient.extractVncUserinfoPassword("vnc://:p%20ss@localhost:5900")
        )
    }

    @Test
    fun `vnc line without userinfo yields no password`() {
        assertNull(LibvirtApiClient.extractVncUserinfoPassword("vnc://localhost:5900"))
    }

    @Test
    fun `vnc line with empty password yields no password`() {
        assertNull(LibvirtApiClient.extractVncUserinfoPassword("vnc://:@localhost:5900"))
    }
}
