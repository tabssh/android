package io.github.tabssh.hypervisor.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the JNLP console-launcher parser: well-formed shapes real BMC web
 * UIs emit, malformed shapes that must be rejected, and hostile shapes
 * (oversized documents, a DOCTYPE trying to smuggle an external entity, an
 * absurd argument count) that a malicious file could carry.
 */
class JnlpFileTest {

    private fun parseFails(content: String) {
        try {
            JnlpFile.parse(content)
            throw AssertionError("expected parse to fail")
        } catch (e: JnlpParseException) {
            assertNotNull(e.message)
        }
    }

    // ── Well-formed ───────────────────────────────────────────────────────────

    @Test
    fun `parses a codebase host with a port and ticket argument`() {
        val jnlp = JnlpFile.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://192.0.2.10:443/">
              <information>
                <title>Remote Console</title>
                <vendor>Example BMC</vendor>
              </information>
              <application-desc main-class="com.example.kvm.Main">
                <argument>192.0.2.10</argument>
                <argument>5900</argument>
                <argument>a1b2c3d4e5f60718293a4b5c6d7e8f90</argument>
              </application-desc>
            </jnlp>
            """.trimIndent()
        )

        assertEquals("192.0.2.10", jnlp.host)
        assertEquals(5900, jnlp.port)
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", jnlp.ticket)
        assertEquals("Remote Console", jnlp.title)
    }

    @Test
    fun `falls back to an argument host when codebase is absent`() {
        val jnlp = JnlpFile.parse(
            """
            <jnlp>
              <application-desc>
                <argument>bmc.example.org</argument>
                <argument>15900</argument>
              </application-desc>
            </jnlp>
            """.trimIndent()
        )

        assertEquals("bmc.example.org", jnlp.host)
        assertEquals(15900, jnlp.port)
        assertNull(jnlp.ticket)
    }

    @Test
    fun `defaults the port when no argument looks like one`() {
        val jnlp = JnlpFile.parse(
            """
            <jnlp codebase="https://198.51.100.5/">
              <application-desc>
                <argument>1</argument>
                <argument>0</argument>
              </application-desc>
            </jnlp>
            """.trimIndent()
        )

        assertEquals("198.51.100.5", jnlp.host)
        assertEquals(JnlpFile.DEFAULT_PORT, jnlp.port)
    }

    @Test
    fun `reads arguments from applet-desc too`() {
        val jnlp = JnlpFile.parse(
            """
            <jnlp codebase="https://203.0.113.9/">
              <applet-desc>
                <argument>5901</argument>
              </applet-desc>
            </jnlp>
            """.trimIndent()
        )

        assertEquals("203.0.113.9", jnlp.host)
        assertEquals(5901, jnlp.port)
    }

    @Test
    fun `looksLikeJnlpFile sniffs the root tag`() {
        assertTrue(JnlpFile.looksLikeJnlpFile("<?xml version=\"1.0\"?><jnlp codebase=\"https://h/\"></jnlp>"))
        assertFalse(JnlpFile.looksLikeJnlpFile("[virt-viewer]\ntype=vnc\n"))
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(JnlpFile.parseOrNull("not xml at all"))
    }

    // ── Malformed ──────────────────────────────────────────────────────────────

    @Test
    fun `rejects a document with no jnlp tag`() {
        parseFails("<?xml version=\"1.0\"?><foo></foo>")
    }

    @Test
    fun `rejects a jnlp with wrong root element`() {
        parseFails("<?xml version=\"1.0\"?><notjnlp>the string jnlp appears here too</notjnlp>")
    }

    @Test
    fun `rejects malformed xml`() {
        parseFails("<jnlp codebase=\"https://h/\"><application-desc><argument>unterminated</jnlp>")
    }

    @Test
    fun `rejects when no host can be found at all`() {
        parseFails(
            """
            <jnlp>
              <application-desc>
                <argument>5900</argument>
              </application-desc>
            </jnlp>
            """.trimIndent()
        )
    }

    // ── Hostile ────────────────────────────────────────────────────────────────

    @Test
    fun `rejects an oversized document`() {
        val huge = "x".repeat(JnlpFile.MAX_CONTENT_LEN + 1)
        parseFails("<jnlp codebase=\"https://h/\">$huge</jnlp>")
    }

    @Test
    fun `rejects a doctype declaration outright`() {
        parseFails(
            """
            <?xml version="1.0"?>
            <!DOCTYPE jnlp [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <jnlp codebase="https://h/">
              <application-desc><argument>&xxe;</argument></application-desc>
            </jnlp>
            """.trimIndent()
        )
    }

    @Test
    fun `rejects an oversized single argument`() {
        val hugeArg = "9".repeat(JnlpFile.MAX_ARG_LEN + 1)
        parseFails(
            """
            <jnlp codebase="https://h/">
              <application-desc><argument>$hugeArg</argument></application-desc>
            </jnlp>
            """.trimIndent()
        )
    }

    @Test
    fun `caps the number of arguments inspected`() {
        val sb = StringBuilder("<jnlp codebase=\"https://198.51.100.7/\"><application-desc>")
        repeat(JnlpFile.MAX_ARGUMENTS + 50) { sb.append("<argument>a$it</argument>") }
        sb.append("</application-desc></jnlp>")

        val jnlp = JnlpFile.parse(sb.toString())
        assertEquals("198.51.100.7", jnlp.host)
    }

    @Test
    fun `rejects a host carrying a control character`() {
        val controlHost = "evil" + 7.toChar() + "host"
        parseFails(
            "<jnlp><application-desc><argument>$controlHost</argument></application-desc></jnlp>"
        )
    }

    @Test
    fun `toString never prints the raw ticket`() {
        val jnlp = JnlpConnection(host = "h", port = 5900, ticket = "secretticket12345", title = null)
        assertFalse(jnlp.toString().contains("secretticket12345"))
        assertTrue(jnlp.toString().contains("xxxxx"))
    }
}
