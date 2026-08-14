package io.github.tabssh.hypervisor.proxmox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure helpers ProxmoxApiClient uses to build request paths
 * and to render server-supplied error text. Both are trust-boundary code: the
 * node name lands in a REST path and the error text lands in a log line and a
 * dialog.
 */
class ProxmoxApiHelpersTest {

    @Test
    fun `encodePathSegment escapes path separators`() {
        assertEquals("pve%2F..%2Fnodes", ProxmoxApiClient.encodePathSegment("pve/../nodes"))
    }

    @Test
    fun `encodePathSegment escapes query and fragment introducers`() {
        assertEquals("a%3Fb%23c", ProxmoxApiClient.encodePathSegment("a?b#c"))
    }

    @Test
    fun `encodePathSegment encodes space as percent20 not plus`() {
        assertEquals("my%20node", ProxmoxApiClient.encodePathSegment("my node"))
    }

    @Test
    fun `encodePathSegment leaves an ordinary node name alone`() {
        assertEquals("pve-01", ProxmoxApiClient.encodePathSegment("pve-01"))
    }

    @Test
    fun `sanitizeServerText replaces control characters with spaces`() {
        assertEquals(
            "first second",
            ProxmoxApiClient.sanitizeServerText("first\nsecond")
        )
    }

    @Test
    fun `sanitizeServerText collapses whitespace runs and trims`() {
        assertEquals("a b", ProxmoxApiClient.sanitizeServerText("  a \t\r\n  b  "))
    }

    @Test
    fun `sanitizeServerText truncates past the bound`() {
        val long = "x".repeat(ProxmoxApiClient.MAX_ERROR_TEXT_LEN + 50)
        val result = ProxmoxApiClient.sanitizeServerText(long)
        assertEquals(ProxmoxApiClient.MAX_ERROR_TEXT_LEN + 1, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `sanitizeServerText maps null and empty to empty`() {
        assertEquals("", ProxmoxApiClient.sanitizeServerText(null))
        assertEquals("", ProxmoxApiClient.sanitizeServerText(""))
    }
}
