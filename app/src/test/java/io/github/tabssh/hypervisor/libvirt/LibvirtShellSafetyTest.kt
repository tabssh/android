package io.github.tabssh.hypervisor.libvirt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two helpers that stand between remote-supplied strings and
 * a remote shell / a user-facing dialog: [LibvirtApiClient.shQuote] is the
 * command-injection barrier, [LibvirtApiClient.sanitizeVirshText] bounds and
 * de-fangs virsh output before it is shown or logged.
 */
class LibvirtShellSafetyTest {

    @Test
    fun `shQuote wraps an ordinary name in single quotes`() {
        assertEquals("'web-01'", LibvirtApiClient.shQuote("web-01"))
    }

    @Test
    fun `shQuote neutralises a command separator`() {
        assertEquals("'x; rm -rf ~'", LibvirtApiClient.shQuote("x; rm -rf ~"))
    }

    @Test
    fun `shQuote escapes an embedded single quote`() {
        // The classic break-out attempt: close the quote, run a command, reopen.
        assertEquals("''\\''; id; '\\'''", LibvirtApiClient.shQuote("'; id; '"))
    }

    @Test
    fun `shQuote leaves no unescaped quote that could terminate the argument`() {
        val quoted = LibvirtApiClient.shQuote("a'b")
        assertEquals("'a'\\''b'", quoted)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
    }

    @Test
    fun `sanitizeVirshText flattens newlines that could forge log records`() {
        assertEquals(
            "error: failed W/Logger: fake",
            LibvirtApiClient.sanitizeVirshText("error: failed\nW/Logger: fake")
        )
    }

    @Test
    fun `sanitizeVirshText collapses whitespace and trims`() {
        assertEquals("a b", LibvirtApiClient.sanitizeVirshText("\t a   b \n"))
    }

    @Test
    fun `sanitizeVirshText truncates past the bound`() {
        val long = "y".repeat(LibvirtApiClient.MAX_ERROR_TEXT_LEN + 10)
        val result = LibvirtApiClient.sanitizeVirshText(long)
        assertEquals(LibvirtApiClient.MAX_ERROR_TEXT_LEN + 1, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `sanitizeVirshText maps empty input to empty output`() {
        assertEquals("", LibvirtApiClient.sanitizeVirshText(""))
    }
}
