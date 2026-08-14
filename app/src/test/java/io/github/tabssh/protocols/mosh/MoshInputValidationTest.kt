package io.github.tabssh.protocols.mosh

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transport-audit regression tests for the two validators that stand between
 * the remote `mosh-server` handshake and a locally spawned process.
 *
 * `MOSH CONNECT <port> <key>` is entirely server-controlled text. Before these
 * checks existed the host and key flowed straight into `ProcessBuilder` argv
 * and the child environment (and into Termux's `RUN_COMMAND` extras), so a
 * hostile or compromised server could steer `mosh-client`'s own option parser.
 */
class MoshInputValidationTest {

    @Test
    fun `accepts a realistic base64 session key`() {
        assertTrue(MoshHandoff.isValidMoshKey("K1uZ0mQ0uYcZ7t2mQ0uYcA"))
        assertTrue(MoshHandoff.isValidMoshKey("AAAAAAAAAAAAAAAAAAAAAA=="))
    }

    @Test
    fun `rejects keys that are not base64`() {
        assertFalse(MoshHandoff.isValidMoshKey("key with spaces!!"))
        assertFalse(MoshHandoff.isValidMoshKey("abc\ndef0123456789abcd"))
        assertFalse(MoshHandoff.isValidMoshKey("../../etc/passwd0000"))
    }

    @Test
    fun `rejects keys outside the length bounds`() {
        assertFalse(MoshHandoff.isValidMoshKey(""))
        assertFalse(MoshHandoff.isValidMoshKey("short"))
        assertFalse(MoshHandoff.isValidMoshKey("A".repeat(65)))
    }

    @Test
    fun `accepts hostnames and IP literals`() {
        assertTrue(MoshNativeClient.isValidHostArgument("example.com"))
        assertTrue(MoshNativeClient.isValidHostArgument("192.0.2.10"))
        assertTrue(MoshNativeClient.isValidHostArgument("[2001:db8::1]"))
        assertTrue(MoshNativeClient.isValidHostArgument("host-1.sub.example.com"))
    }

    @Test
    fun `rejects a host that would be parsed as a mosh-client option`() {
        assertFalse(MoshNativeClient.isValidHostArgument("-4"))
        assertFalse(MoshNativeClient.isValidHostArgument("--help"))
    }

    @Test
    fun `rejects hosts with whitespace, shell metacharacters, or excess length`() {
        assertFalse(MoshNativeClient.isValidHostArgument(""))
        assertFalse(MoshNativeClient.isValidHostArgument("host name"))
        assertFalse(MoshNativeClient.isValidHostArgument("host;rm -rf /"))
        assertFalse(MoshNativeClient.isValidHostArgument("host\nMOSH_KEY=x"))
        assertFalse(MoshNativeClient.isValidHostArgument("a".repeat(256)))
    }
}
