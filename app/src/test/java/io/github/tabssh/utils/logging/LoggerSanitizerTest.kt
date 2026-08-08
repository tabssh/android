package io.github.tabssh.utils.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #12 follow-up — the exported app log leaked hostnames, domain
 * names, ports, and usernames outside the TIMING lines:
 *
 *  1. Any host ending in .com/.org/.net was preserved VERBATIM by the
 *     SAFE_DOMAIN_SUFFIXES bypass (meant only for algorithm-vendor
 *     domains like openssh.com) — including the user@ part in front.
 *  2. The hostname regex matched only two labels, so host.example.com
 *     anonymized to "server2.com", leaking the real TLD and, for
 *     longer names, real intermediate labels.
 *  3. The safe-keyword check used substring contains(), so private
 *     hosts like myandroid.example were preserved.
 *  4. Ports were never sanitized.
 *
 * These tests pin the corrected behavior. Logger is a singleton with
 * session-consistent counters, so assertions check for absence of raw
 * values rather than exact server{N} numbers.
 */
class LoggerSanitizerTest {

    // ── leak class 1: .com/.org/.net domains must be anonymized ──────────

    @Test
    fun `private com domain is anonymized`() {
        val out = Logger.sanitize("Connecting to myprivatebox.com now")
        assertFalse("real .com host leaked: $out", out.contains("myprivatebox"))
    }

    @Test
    fun `private net and org domains are anonymized`() {
        val out = Logger.sanitize("hosts: alpha-vpn.net and beta-home.org")
        assertFalse("real .net host leaked: $out", out.contains("alpha-vpn"))
        assertFalse("real .org host leaked: $out", out.contains("beta-home"))
    }

    @Test
    fun `user at com domain leaks neither user nor host`() {
        val out = Logger.sanitize("auth as bob@myprivatebox.com")
        assertFalse("username leaked: $out", out.contains("bob"))
        assertFalse("host leaked: $out", out.contains("myprivatebox"))
    }

    // ── leak class 2: multi-label FQDNs replaced as a unit ───────────────

    @Test
    fun `multi-label fqdn is fully replaced with no tld residue`() {
        val out = Logger.sanitize("TIMING vps.internal.example.com: connect")
        assertFalse("label leaked: $out", out.contains("vps.internal"))
        assertFalse("real domain leaked: $out", out.contains("example.com"))
        // no "serverN.com"-style residue
        assertFalse("TLD residue after anonymization: $out",
            Regex("""(server|jump)\d+\.[a-zA-Z]""").containsMatchIn(out))
    }

    // ── leak class 3: keyword must match a whole label, not a substring ──

    @Test
    fun `host merely containing a safe keyword is anonymized`() {
        val out = Logger.sanitize("probing myandroid.example and sungrove.internal")
        assertFalse("substring-keyword host leaked: $out", out.contains("myandroid"))
        assertFalse("substring-keyword host leaked: $out", out.contains("sungrove"))
    }

    // ── leak class 4: ports ──────────────────────────────────────────────

    @Test
    fun `port after anonymized host is redacted`() {
        val out = Logger.sanitize("connecting to porthost.example.com:2222 for shell")
        assertFalse("port leaked: $out", out.contains("2222"))
    }

    @Test
    fun `port key-value forms are redacted`() {
        val out = Logger.sanitize("using port 2222 (fallback port=8022, port: 443)")
        assertFalse("port leaked: $out", out.contains("2222"))
        assertFalse("port leaked: $out", out.contains("8022"))
        assertFalse("port leaked: $out", out.contains("443"))
    }

    // ── behavior that must NOT regress ───────────────────────────────────

    @Test
    fun `ssh algorithm vendor names stay verbatim`() {
        val line = "kex: chacha20-poly1305@openssh.com, ext-info-c"
        assertEquals(line, Logger.sanitize(line))
    }

    @Test
    fun `jvm and library stack-trace packages stay verbatim`() {
        val line = "at com.jcraft.jsch.Session.connect(Session.java:222) " +
            "caused by java.lang.RuntimeException at io.github.tabssh.ssh.SSHConnection " +
            "via okhttp3.internal.connection.RealCall and androidx.lifecycle.LiveData " +
            "and kotlinx.coroutines.JobSupport"
        assertEquals(line, Logger.sanitize(line))
    }

    @Test
    fun `version banners are not treated as hostnames`() {
        val line = "remote ident: SSH-2.0 protocol 8.7 build 1.0.0"
        assertEquals(line, Logger.sanitize(line))
    }

    @Test
    fun `same host anonymizes to the same alias within a session`() {
        val a = Logger.sanitize("first sight of stable-host.example.com")
        val b = Logger.sanitize("second sight of stable-host.example.com")
        val aliasA = Regex("""server\d+""").find(a)?.value
        val aliasB = Regex("""server\d+""").find(b)?.value
        assertTrue("no alias assigned: $a", aliasA != null)
        assertEquals("alias not session-consistent", aliasA, aliasB)
    }

    @Test
    fun `credential key-value pairs are redacted`() {
        val out = Logger.sanitize("login password=hunter2 token: abc123 secret=shh")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("shh"))
    }

    @Test
    fun `ipv4 addresses are anonymized`() {
        val out = Logger.sanitize("resolved to 203.0.113.7 via cache")
        assertFalse(out.contains("203.0.113.7"))
        assertTrue("expected IP alias: $out", Regex("""IP\d+""").containsMatchIn(out))
    }

    // ── URL query-string secrets, auth headers, JSON credentials ─────────

    @Test
    fun `console url query secrets are redacted`() {
        val out = Logger.sanitize(
            "Connecting to console: wss://host/console?vncticket=abc123XYZ&port=5900"
        )
        assertFalse("vncticket leaked: $out", out.contains("abc123XYZ"))
        assertTrue("expected redaction marker: $out", out.contains("[REDACTED]"))
    }

    @Test
    fun `session id and access token query params are redacted`() {
        val out = Logger.sanitize(
            "wss://xo.example.com/api?session_id=deadbeef1234&access_token=topsecrettoken"
        )
        assertFalse("session_id leaked: $out", out.contains("deadbeef1234"))
        assertFalse("access_token leaked: $out", out.contains("topsecrettoken"))
    }

    @Test
    fun `authorization basic header is redacted`() {
        val out = Logger.sanitize("Authorization: Basic dXNlcjpwYXNzd29yZA==")
        assertFalse("basic credentials leaked: $out", out.contains("dXNlcjpwYXNzd29yZA=="))
        assertTrue("expected redaction marker: $out", out.contains("Authorization: [REDACTED]"))
    }

    @Test
    fun `authorization bearer header is redacted`() {
        val out = Logger.sanitize("authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secret.payload")
        assertFalse("bearer token leaked: $out", out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue("expected redaction marker: $out", out.contains("Authorization: [REDACTED]"))
    }

    @Test
    fun `json quoted credentials are redacted`() {
        val out = Logger.sanitize(
            """{"username":"bob","password":"hunter2","api_key":"sk-abcdef123456"}"""
        )
        assertFalse("password leaked: $out", out.contains("hunter2"))
        assertFalse("api_key leaked: $out", out.contains("sk-abcdef123456"))
        assertTrue("expected xxxxx marker: $out", out.contains("\"password\": \"xxxxx\""))
        assertTrue("expected xxxxx marker: $out", out.contains("\"api_key\": \"xxxxx\""))
    }
}
