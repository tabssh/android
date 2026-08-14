package io.github.tabssh.ssh.connection

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

/**
 * Transport-audit regression tests for [HostKeyVerifier]'s two pure parsing
 * helpers. Both feed the known-hosts lookup, so a parsing mistake in either
 * silently changes *which* stored row a presented key is compared against —
 * the failure mode a host-key check exists to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HostKeyVerifierParsingTest {

    private fun verifier(): HostKeyVerifier =
        HostKeyVerifier(ApplicationProvider.getApplicationContext<Application>())

    /** Build an RFC 4253 §6.6 public-key blob: uint32 length + algorithm name + payload. */
    private fun blob(algorithm: String, payload: ByteArray = ByteArray(0)): ByteArray {
        val name = algorithm.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write((name.size ushr 24) and 0xFF)
        out.write((name.size ushr 16) and 0xFF)
        out.write((name.size ushr 8) and 0xFF)
        out.write(name.size and 0xFF)
        out.write(name)
        out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `detects the key type from the wire-format algorithm name`() {
        val v = verifier()
        assertEquals("ssh-ed25519", v.detectKeyType(blob("ssh-ed25519")))
        assertEquals("ssh-rsa", v.detectKeyType(blob("ssh-rsa")))
        assertEquals("ecdsa-sha2-nistp256", v.detectKeyType(blob("ecdsa-sha2-nistp256")))
    }

    /**
     * The pre-fix implementation substring-searched the whole blob, so a
     * server could embed a different algorithm name inside an RSA key's
     * payload and have the key filed under — and compared against — the
     * wrong stored row.
     */
    @Test
    fun `ignores an algorithm name embedded in the key payload`() {
        val spoofed = blob("ssh-rsa", "ssh-ed25519".toByteArray(Charsets.US_ASCII))
        assertEquals("ssh-rsa", verifier().detectKeyType(spoofed))
    }

    @Test
    fun `returns unknown for truncated or nonsense blobs`() {
        val v = verifier()
        assertEquals("unknown", v.detectKeyType(ByteArray(0)))
        assertEquals("unknown", v.detectKeyType(byteArrayOf(0, 0, 0)))
        // Declared name length runs past the end of the buffer.
        assertEquals("unknown", v.detectKeyType(byteArrayOf(0, 0, 0, 99, 65, 66)))
        assertEquals("unknown", v.detectKeyType(blob("totally-made-up")))
    }

    @Test
    fun `parses plain hosts and host colon port`() {
        val v = verifier()
        assertEquals(Pair("example.com", 22), v.parseHostPort("example.com"))
        assertEquals(Pair("example.com", 2222), v.parseHostPort("example.com:2222"))
        assertEquals(Pair("192.0.2.10", 2022), v.parseHostPort(" 192.0.2.10:2022 "))
    }

    @Test
    fun `parses IPv6 literals with and without brackets`() {
        val v = verifier()
        assertEquals(Pair("2001:db8::1", 22), v.parseHostPort("2001:db8::1"))
        assertEquals(Pair("2001:db8::1", 22), v.parseHostPort("[2001:db8::1]"))
        assertEquals(Pair("2001:db8::1", 2222), v.parseHostPort("[2001:db8::1]:2222"))
    }

    /**
     * An out-of-range or non-numeric port must fall back to 22 rather than be
     * carried into the lookup key: a lookup for `host:0` misses the stored
     * `host:22` row, which silently downgrades a known host to an unknown one.
     */
    @Test
    fun `falls back to port 22 for out-of-range ports`() {
        val v = verifier()
        assertEquals(Pair("example.com", 22), v.parseHostPort("example.com:0"))
        assertEquals(Pair("example.com", 22), v.parseHostPort("example.com:99999"))
        assertEquals(Pair("example.com", 22), v.parseHostPort("example.com:-1"))
        assertEquals(Pair("example.com", 22), v.parseHostPort("example.com:ssh"))
    }
}
