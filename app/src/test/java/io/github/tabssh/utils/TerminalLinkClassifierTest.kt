package io.github.tabssh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers TerminalLinkClassifier's scheme classification and the ssh:// /
 * file:// parsers it feeds into TabTerminalActivity's per-scheme link
 * dialogs (TODO.AI.md — "Non-http URL schemes are detected but mishandled
 * on tap").
 */
class TerminalLinkClassifierTest {

    @Test
    fun `classify routes ssh scheme to Ssh action`() {
        val action = TerminalLinkClassifier.classify("ssh://root@example.com:2222/")
        assertTrue(action is TerminalLinkClassifier.LinkAction.Ssh)
        val ssh = action as TerminalLinkClassifier.LinkAction.Ssh
        assertEquals("root", ssh.username)
        assertEquals("example.com", ssh.host)
        assertEquals(2222, ssh.port)
    }

    @Test
    fun `classify routes file scheme to RemoteFile action`() {
        val action = TerminalLinkClassifier.classify("file:///var/log/syslog")
        assertTrue(action is TerminalLinkClassifier.LinkAction.RemoteFile)
        assertEquals("/var/log/syslog", (action as TerminalLinkClassifier.LinkAction.RemoteFile).path)
    }

    @Test
    fun `classify routes sftp scheme to Sftp action`() {
        val action = TerminalLinkClassifier.classify("sftp://deploy@example.com:2222/srv/www")
        assertTrue(action is TerminalLinkClassifier.LinkAction.Sftp)
        val sftp = action as TerminalLinkClassifier.LinkAction.Sftp
        assertEquals("deploy", sftp.username)
        assertEquals("example.com", sftp.host)
        assertEquals(2222, sftp.port)
        assertEquals("/srv/www", sftp.path)
    }

    @Test
    fun `classify routes git ftp ftps svn to ExternalScheme`() {
        for (scheme in listOf("git", "ftp", "ftps", "svn")) {
            val action = TerminalLinkClassifier.classify("$scheme://example.com/repo")
            assertTrue("$scheme should classify as ExternalScheme", action is TerminalLinkClassifier.LinkAction.ExternalScheme)
            assertEquals(scheme, (action as TerminalLinkClassifier.LinkAction.ExternalScheme).scheme)
        }
    }

    @Test
    fun `classify routes http https and www to Browser`() {
        assertTrue(TerminalLinkClassifier.classify("http://example.com") is TerminalLinkClassifier.LinkAction.Browser)
        assertTrue(TerminalLinkClassifier.classify("https://example.com") is TerminalLinkClassifier.LinkAction.Browser)
        // TerminalView.normaliseUrl always prepends http:// before invoking the
        // callback, but the classifier should still degrade gracefully.
        assertTrue(TerminalLinkClassifier.classify("www.example.com") is TerminalLinkClassifier.LinkAction.Browser)
    }

    @Test
    fun `classify routes telnet vnc and spice to ExternalScheme`() {
        for (scheme in listOf("telnet", "vnc", "spice")) {
            val action = TerminalLinkClassifier.classify("$scheme://example.com")
            assertTrue("$scheme should classify as ExternalScheme", action is TerminalLinkClassifier.LinkAction.ExternalScheme)
            assertEquals(scheme, (action as TerminalLinkClassifier.LinkAction.ExternalScheme).scheme)
        }
    }

    @Test
    fun `classify rejects dangerous schemes as NotALink`() {
        for (url in listOf(
            "intent://example.com#Intent;scheme=http;package=com.evil.app;end",
            "javascript:alert(document.cookie)",
            "content://com.evil.provider/secret"
        )) {
            val action = TerminalLinkClassifier.classify(url)
            assertTrue("$url should classify as NotALink", action is TerminalLinkClassifier.LinkAction.NotALink)
        }
    }

    @Test
    fun `classify keeps host-port text as Browser not NotALink`() {
        // An OSC 8 href like "example.com:8080/path" matches the RFC 3986
        // scheme grammar with "example.com" as the "scheme" — it must keep
        // the pre-allowlist Browser behavior, not be silently rejected.
        for (url in listOf("example.com:8080/path", "my.host.local:443")) {
            val action = TerminalLinkClassifier.classify(url)
            assertTrue("$url should classify as Browser", action is TerminalLinkClassifier.LinkAction.Browser)
        }
    }

    @Test
    fun `classify rejects dotted scheme with authority as NotALink`() {
        // A dotted scheme WITH "://" is not host:port text — it stays
        // rejected like any other non-allowlisted scheme.
        val action = TerminalLinkClassifier.classify("evil.scheme://payload")
        assertTrue(action is TerminalLinkClassifier.LinkAction.NotALink)
    }

    @Test
    fun `classify keeps mailto out of the allowlist`() {
        // mailto: isn't explicitly handled anywhere in TerminalLinkClassifier,
        // so a remote hyperlink using it must not fall through to Browser.
        val action = TerminalLinkClassifier.classify("mailto:someone@example.com")
        assertTrue(action is TerminalLinkClassifier.LinkAction.NotALink)
    }

    @Test
    fun `parseSsh handles user host and port`() {
        val (user, host, port) = TerminalLinkClassifier.parseSsh("ssh://deploy@10.0.0.5:2200")!!
        assertEquals("deploy", user)
        assertEquals("10.0.0.5", host)
        assertEquals(2200, port)
    }

    @Test
    fun `parseSsh defaults port to 22 when absent`() {
        val (user, host, port) = TerminalLinkClassifier.parseSsh("ssh://deploy@host.example.com")!!
        assertEquals("deploy", user)
        assertEquals("host.example.com", host)
        assertEquals(22, port)
    }

    @Test
    fun `parseSsh handles missing username`() {
        val (user, host, port) = TerminalLinkClassifier.parseSsh("ssh://host.example.com:22")!!
        assertNull(user)
        assertEquals("host.example.com", host)
        assertEquals(22, port)
    }

    @Test
    fun `parseSsh handles bracketed IPv6 host`() {
        val (user, host, port) = TerminalLinkClassifier.parseSsh("ssh://root@[::1]:2222")!!
        assertEquals("root", user)
        assertEquals("::1", host)
        assertEquals(2222, port)
    }

    @Test
    fun `parseSsh returns null when no host present`() {
        assertNull(TerminalLinkClassifier.parseSsh("ssh://"))
    }

    @Test
    fun `parseSftp handles user host port and path`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://deploy@10.0.0.5:2200/srv/www")!!
        assertEquals("deploy", target.username)
        assertEquals("10.0.0.5", target.host)
        assertEquals(2200, target.port)
        assertEquals("/srv/www", target.path)
    }

    @Test
    fun `parseSftp defaults port to 22 and path to root when absent`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://deploy@host.example.com")!!
        assertEquals("deploy", target.username)
        assertEquals("host.example.com", target.host)
        assertEquals(22, target.port)
        assertEquals("/", target.path)
    }

    @Test
    fun `parseSftp handles missing username`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://host.example.com:22/data")!!
        assertNull(target.username)
        assertEquals("host.example.com", target.host)
        assertEquals(22, target.port)
        assertEquals("/data", target.path)
    }

    @Test
    fun `parseSftp handles bracketed IPv6 host`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://root@[::1]:2222/etc")!!
        assertEquals("root", target.username)
        assertEquals("::1", target.host)
        assertEquals(2222, target.port)
        assertEquals("/etc", target.path)
    }

    @Test
    fun `parseSftp decodes percent-encoded path characters`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://host.example.com/home/user/my%20folder")!!
        assertEquals("/home/user/my folder", target.path)
    }

    @Test
    fun `parseSftp strips query and fragment from path`() {
        val target = TerminalLinkClassifier.parseSftp("sftp://host.example.com/data?x=1#frag")!!
        assertEquals("/data", target.path)
    }

    @Test
    fun `parseSftp returns null when no host present`() {
        assertNull(TerminalLinkClassifier.parseSftp("sftp://"))
    }

    @Test
    fun `parseSftp returns null for blank authority`() {
        assertNull(TerminalLinkClassifier.parseSftp("sftp:///data"))
    }

    @Test
    fun `extractFilePath returns absolute path unchanged`() {
        assertEquals("/etc/hosts", TerminalLinkClassifier.extractFilePath("file:///etc/hosts"))
    }

    @Test
    fun `extractFilePath strips authority component`() {
        assertEquals("/home/user/notes.txt", TerminalLinkClassifier.extractFilePath("file://myhost/home/user/notes.txt"))
    }

    @Test
    fun `extractFilePath decodes percent-encoded characters`() {
        assertEquals("/home/user/my file.txt", TerminalLinkClassifier.extractFilePath("file:///home/user/my%20file.txt"))
    }

    @Test
    fun `extractFilePath falls back to root when no path present`() {
        assertEquals("/", TerminalLinkClassifier.extractFilePath("file://host"))
    }
}
