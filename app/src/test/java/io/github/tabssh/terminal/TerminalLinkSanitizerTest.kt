package io.github.tabssh.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [TerminalLinkSanitizer], the single allowlist applied to every
 * server-supplied OSC 8 hyperlink target before it is made tappable.
 *
 * This is a security boundary, not a formatting helper: the URL comes from
 * whatever is running on the remote host, and a tap ultimately routes an
 * unrecognised scheme to an `ACTION_VIEW` intent. A `javascript:`,
 * `intent:` or `content:` target that slipped through would be launched
 * from what looks like ordinary terminal output, so the allowlist is closed
 * (known-good schemes only) rather than a blocklist.
 */
class TerminalLinkSanitizerTest {

    @Test
    fun `allowed schemes survive unchanged`() {
        for (url in listOf(
            "http://example.com",
            "https://example.com/path?q=1#frag",
            "ftp://files.example.com/pub",
            "ftps://files.example.com/pub",
            "ssh://user@host:22",
            "sftp://user@host/dir",
            "telnet://host:23",
            "mailto:someone@example.com"
        )) {
            assertEquals(url, TerminalLinkSanitizer.sanitize(url), "should allow $url")
        }
    }

    @Test
    fun `scheme matching is case insensitive but the url keeps its case`() {
        assertEquals("HTTPS://example.com", TerminalLinkSanitizer.sanitize("HTTPS://example.com"))
        assertEquals("HtTp://example.com", TerminalLinkSanitizer.sanitize("HtTp://example.com"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.com", TerminalLinkSanitizer.sanitize("   https://example.com   "))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        for (url in listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "intent://scan/#Intent;scheme=zxing;end",
            "content://com.example.provider/secret",
            "jar:http://example.com/a.jar!/",
            "vbscript:msgbox(1)"
        )) {
            assertNull(TerminalLinkSanitizer.sanitize(url), "should reject $url")
        }
    }

    @Test
    fun `scheme-less and relative targets are rejected`() {
        assertNull(TerminalLinkSanitizer.sanitize("example.com"))
        assertNull(TerminalLinkSanitizer.sanitize("/etc/passwd"))
        assertNull(TerminalLinkSanitizer.sanitize("../../secret"))
        assertNull(TerminalLinkSanitizer.sanitize(""))
        assertNull(TerminalLinkSanitizer.sanitize("   "))
    }

    @Test
    fun `the length cap is inclusive at the boundary`() {
        val prefix = "https://example.com/"
        val exactlyMax = prefix + "a".repeat(TerminalLinkSanitizer.MAX_URL_LENGTH - prefix.length)
        assertEquals(TerminalLinkSanitizer.MAX_URL_LENGTH, exactlyMax.length)
        assertEquals(exactlyMax, TerminalLinkSanitizer.sanitize(exactlyMax), "exactly MAX must be allowed")

        val oneOver = exactlyMax + "a"
        assertNull(TerminalLinkSanitizer.sanitize(oneOver), "MAX + 1 must be rejected")
    }

    @Test
    fun `embedded control characters are rejected`() {
        // A control byte inside the target is the classic vector for smuggling
        // terminal escapes back out through a link, or for spoofing what the
        // confirmation dialog shows the link pointing at.
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/\u001b[31m"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/\u0007"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/a\nb"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/a\rb"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/a\tb"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/a\u007fb"))
        assertNull(TerminalLinkSanitizer.sanitize("https://example.com/a\u0000b"))
    }
}
