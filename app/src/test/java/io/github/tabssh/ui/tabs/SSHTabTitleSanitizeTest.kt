package io.github.tabssh.ui.tabs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the OSC 0/1/2 title sanitiser.
 *
 * A remote host fully controls this string. Before the fix it was written
 * straight into the tab bar and the notification, so a hostile host could
 * inject newlines and ANSI/bidi control characters to spoof surrounding UI
 * text, or flood the bar with a multi-kilobyte title.
 */
class SSHTabTitleSanitizeTest {

    @Test
    fun `control characters are stripped`() {
        assertEquals(
            "roothost",
            SSHTab.sanitizeRemoteTitle("root\u0007\n\rhost\u001B")
        )
    }

    @Test
    fun `c1 control characters are stripped`() {
        assertEquals("ab", SSHTab.sanitizeRemoteTitle("a\u0085\u009Bb"))
    }

    @Test
    fun `bidi override characters are stripped`() {
        assertEquals(
            "gpj.exe",
            SSHTab.sanitizeRemoteTitle("\u202Egpj.exe\u202C")
        )
        assertEquals("ab", SSHTab.sanitizeRemoteTitle("a\u2066\u2069b"))
    }

    @Test
    fun `an overlong title is capped`() {
        val sanitized = SSHTab.sanitizeRemoteTitle("x".repeat(10_000))
        assertEquals(256, sanitized?.length)
    }

    @Test
    fun `a title that is empty once sanitised falls back to null`() {
        assertNull(SSHTab.sanitizeRemoteTitle(""))
        assertNull(SSHTab.sanitizeRemoteTitle("   "))
        assertNull(SSHTab.sanitizeRemoteTitle("\u0007\u001B\n"))
    }

    @Test
    fun `an ordinary title is passed through untouched`() {
        assertEquals("root@web01: ~/src", SSHTab.sanitizeRemoteTitle("root@web01: ~/src"))
    }
}
