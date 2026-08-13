package io.github.tabssh.hypervisor.xcpng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Console-URL construction for the XCP-ng (XAPI) and Xen Orchestra clients.
 *
 * Both are security- and correctness-relevant: the XAPI location is useless
 * without the session credential, and the XO-supplied URL is dialled with the
 * caller's auth token attached.
 */
class XapiConsoleUrlTest {

    // ── XCP-ng: console.get_location returns no credential ───────────────────

    @Test
    fun `session id is appended to a location that has a query string`() {
        assertEquals(
            "wss://xen.example/console?ref=OpaqueRef:abc&session_id=OpaqueRef:sess",
            XCPngApiClient.consoleUrlWithSession(
                "wss://xen.example/console?ref=OpaqueRef:abc",
                "OpaqueRef:sess"
            )
        )
    }

    @Test
    fun `session id is appended to a location with no query string`() {
        assertEquals(
            "wss://xen.example/console?session_id=OpaqueRef:sess",
            XCPngApiClient.consoleUrlWithSession("wss://xen.example/console", "OpaqueRef:sess")
        )
    }

    @Test
    fun `an existing session id is not duplicated`() {
        val url = "wss://xen.example/console?session_id=OpaqueRef:already"
        assertEquals(url, XCPngApiClient.consoleUrlWithSession(url, "OpaqueRef:sess"))
    }

    @Test
    fun `url is returned untouched when there is no session`() {
        val url = "wss://xen.example/console?ref=OpaqueRef:abc"
        assertEquals(url, XCPngApiClient.consoleUrlWithSession(url, null))
        assertEquals(url, XCPngApiClient.consoleUrlWithSession(url, ""))
    }

    // ── Xen Orchestra: server-supplied console URL ───────────────────────────

    @Test
    fun `same host websocket urls are accepted`() {
        assertTrue(XenOrchestraApiClient.isAcceptableConsoleUrl("wss://xo.example/api/console/1", "xo.example"))
        assertTrue(XenOrchestraApiClient.isAcceptableConsoleUrl("ws://xo.example:8080/console", "xo.example"))
        assertTrue(XenOrchestraApiClient.isAcceptableConsoleUrl("WSS://XO.EXAMPLE/c", "xo.example"))
    }

    @Test
    fun `off host console urls are rejected`() {
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("wss://attacker.test/api/console/1", "xo.example"))
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("wss://xo.example.attacker.test/c", "xo.example"))
    }

    @Test
    fun `non websocket schemes are rejected`() {
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("https://xo.example/api/console/1", "xo.example"))
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("file:///etc/passwd", "xo.example"))
    }

    @Test
    fun `garbage and hostless urls are rejected`() {
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("", "xo.example"))
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("not a url", "xo.example"))
        assertFalse(XenOrchestraApiClient.isAcceptableConsoleUrl("wss:///console", "xo.example"))
    }
}
