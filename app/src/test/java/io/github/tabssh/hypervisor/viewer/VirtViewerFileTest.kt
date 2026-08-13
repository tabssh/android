package io.github.tabssh.hypervisor.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the virt-viewer `.vv` connection-file parser: the well-formed
 * shapes the hypervisors actually emit, the malformed shapes that must be
 * rejected rather than guessed at, and the hostile shapes (oversized
 * values, out-of-range ports, control characters in the host) that a
 * malicious file could carry.
 */
class VirtViewerFileTest {

    private fun parseFails(content: String) {
        try {
            VirtViewerFile.parse(content)
            throw AssertionError("expected parse to fail")
        } catch (e: VirtViewerParseException) {
            assertNotNull(e.message)
        }
    }

    // ── Well-formed ───────────────────────────────────────────────────────────

    @Test
    fun `parses a full spice descriptor`() {
        val vv = VirtViewerFile.parse(
            """
            [virt-viewer]
            type=spice
            host=192.0.2.10
            port=5900
            tls-port=5901
            password=s3cret
            host-subject=O=Example,CN=host.example.org
            proxy=http://proxy.example.org:3128
            title=web-01
            delete-this-file=1
            fullscreen=1
            enable-usbredir=1
            release-cursor=shift+f12
            secure-attention=ctrl+alt+end
            toggle-fullscreen=shift+f11
            """.trimIndent()
        )

        assertEquals(VirtViewerType.SPICE, vv.type)
        assertEquals("192.0.2.10", vv.host)
        assertEquals(5900, vv.port)
        assertEquals(5901, vv.tlsPort)
        assertEquals("s3cret", vv.password)
        assertEquals("O=Example,CN=host.example.org", vv.hostSubject)
        assertEquals("http://proxy.example.org:3128", vv.proxy)
        assertEquals("web-01", vv.title)
        assertTrue(vv.deleteThisFile)
        assertTrue(vv.fullscreen)
        assertTrue(vv.enableUsbredir)
        assertEquals("shift+f12", vv.releaseCursor)
        assertEquals("ctrl+alt+end", vv.secureAttention)
        assertEquals("shift+f11", vv.toggleFullscreen)
        assertTrue(vv.isTls)
        assertEquals(5900, vv.effectivePort)
    }

    @Test
    fun `parses a minimal vnc descriptor`() {
        val vv = VirtViewerFile.parse(
            """
            [virt-viewer]
            type=vnc
            host=vnc.example.org
            port=5901
            """.trimIndent()
        )

        assertEquals(VirtViewerType.VNC, vv.type)
        assertEquals("vnc.example.org", vv.host)
        assertEquals(5901, vv.port)
        assertEquals(0, vv.tlsPort)
        assertNull(vv.password)
        assertFalse(vv.isTls)
        assertFalse(vv.deleteThisFile)
    }

    @Test
    fun `tls-only descriptor uses the tls port as the effective port`() {
        val vv = VirtViewerFile.parse(
            """
            [virt-viewer]
            type=spice
            host=h
            tls-port=5901
            """.trimIndent()
        )

        assertEquals(0, vv.port)
        assertEquals(5901, vv.tlsPort)
        assertEquals(5901, vv.effectivePort)
        assertTrue(vv.isTls)
    }

    @Test
    fun `unescapes the inlined ca pem`() {
        val vv = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=h\nport=1\n" +
                "ca=-----BEGIN CERTIFICATE-----\\nQUJD\\n-----END CERTIFICATE-----\\n"
        )

        assertEquals(
            "-----BEGIN CERTIFICATE-----\nQUJD\n-----END CERTIFICATE-----\n",
            vv.caCert
        )
    }

    @Test
    fun `ignores unknown keys comments and other sections`() {
        val vv = VirtViewerFile.parse(
            """
            # a comment
            ; another comment
            [ovirt]
            host=should-be-ignored
            port=1
            [virt-viewer]
            type=spice
            host=real.example.org
            port=5900
            versions=some-future-key
            """.trimIndent()
        )

        assertEquals("real.example.org", vv.host)
        assertEquals(5900, vv.port)
    }

    @Test
    fun `key lookup is case insensitive and values are trimmed`() {
        val vv = VirtViewerFile.parse(
            "[Virt-Viewer]\nType = SPICE \n Host = h.example.org \nPort= 5900 "
        )

        assertEquals(VirtViewerType.SPICE, vv.type)
        assertEquals("h.example.org", vv.host)
        assertEquals(5900, vv.port)
    }

    @Test
    fun `a repeated key takes the last value`() {
        val vv = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=first\nhost=second\nport=5900"
        )

        assertEquals("second", vv.host)
    }

    @Test
    fun `boolean keys accept the true spellings hypervisors emit`() {
        val vv = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=h\nport=1\n" +
                "delete-this-file=true\nfullscreen=yes\nenable-usbredir=0"
        )

        assertTrue(vv.deleteThisFile)
        assertTrue(vv.fullscreen)
        assertFalse(vv.enableUsbredir)
    }

    // ── Malformed ─────────────────────────────────────────────────────────────

    @Test
    fun `rejects an empty document`() = parseFails("")

    @Test
    fun `rejects a document with no virt-viewer section`() =
        parseFails("[ovirt]\ntype=spice\nhost=h\nport=1")

    @Test
    fun `rejects a missing type`() = parseFails("[virt-viewer]\nhost=h\nport=1")

    @Test
    fun `rejects an unsupported type`() =
        parseFails("[virt-viewer]\ntype=rdp\nhost=h\nport=1")

    @Test
    fun `rejects a missing host`() = parseFails("[virt-viewer]\ntype=spice\nport=1")

    @Test
    fun `rejects a blank host`() = parseFails("[virt-viewer]\ntype=spice\nhost=   \nport=1")

    @Test
    fun `rejects a descriptor with no port at all`() =
        parseFails("[virt-viewer]\ntype=spice\nhost=h")

    @Test
    fun `rejects a malformed section header`() =
        parseFails("[virt-viewer\ntype=spice\nhost=h\nport=1")

    @Test
    fun `rejects a non-numeric port`() =
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=five")

    // ── Hostile ───────────────────────────────────────────────────────────────

    @Test
    fun `rejects port zero and out-of-range ports`() {
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=0")
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=-1")
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=65536")
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=999999999999")
        parseFails("[virt-viewer]\ntype=spice\nhost=h\ntls-port=70000")
    }

    @Test
    fun `rejects an oversized document`() {
        val filler = "x".repeat(VirtViewerFile.MAX_CONTENT_LEN + 1)
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=1\ntitle=$filler")
    }

    @Test
    fun `rejects an oversized value`() {
        val filler = "x".repeat(VirtViewerFile.MAX_VALUE_LEN + 1)
        parseFails("[virt-viewer]\ntype=spice\nhost=h\nport=1\ntitle=$filler")
    }

    @Test
    fun `rejects a document with too many lines`() {
        val body = StringBuilder("[virt-viewer]\ntype=spice\nhost=h\nport=1\n")
        repeat(VirtViewerFile.MAX_LINES + 1) { body.append("k$it=v\n") }
        parseFails(body.toString())
    }

    @Test
    fun `rejects an oversized host`() {
        val host = "h".repeat(VirtViewerFile.MAX_HOST_LEN + 1)
        parseFails("[virt-viewer]\ntype=spice\nhost=$host\nport=1")
    }

    @Test
    fun `rejects hosts carrying separators or control characters`() {
        parseFails("[virt-viewer]\ntype=spice\nhost=evil.example.org/../x\nport=1")
        parseFails("[virt-viewer]\ntype=spice\nhost=user@evil.example.org\nport=1")
        parseFails("[virt-viewer]\ntype=spice\nhost=evil .org\nport=1")
        parseFails("[virt-viewer]\ntype=spice\nhost=evil\\share\nport=1")
    }

    // ── Helpers on the parsed value ───────────────────────────────────────────

    @Test
    fun `toString redacts the password`() {
        val vv = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=h\nport=1\npassword=hunter2"
        )

        val rendered = vv.toString()
        assertFalse(rendered.contains("hunter2"))
        assertTrue(rendered.contains("xxxxx"))
    }

    @Test
    fun `toSpiceParams carries the ticket and enables verification only with a ca`() {
        val withoutCa = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=h\nport=5900\npassword=t"
        ).toSpiceParams()
        assertEquals("h", withoutCa.host)
        assertEquals(5900, withoutCa.port)
        assertEquals("t", withoutCa.password)
        assertNull(withoutCa.caCert)
        assertFalse(withoutCa.tlsVerify)

        val withCa = VirtViewerFile.parse(
            "[virt-viewer]\ntype=spice\nhost=h\ntls-port=5901\nca=PEM"
        ).toSpiceParams()
        assertEquals(5901, withCa.tlsPort)
        assertNotNull(withCa.caCert)
        assertTrue(withCa.tlsVerify)
    }

    @Test
    fun `toSpiceParams refuses a vnc descriptor`() {
        val vv = VirtViewerFile.parse("[virt-viewer]\ntype=vnc\nhost=h\nport=5901")
        try {
            vv.toSpiceParams()
            throw AssertionError("expected toSpiceParams to reject a VNC descriptor")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(VirtViewerFile.parseOrNull("not a connection file"))
        assertNotNull(VirtViewerFile.parseOrNull("[virt-viewer]\ntype=spice\nhost=h\nport=1"))
    }

    @Test
    fun `looksLikeVirtViewerFile sniffs the section header`() {
        assertTrue(VirtViewerFile.looksLikeVirtViewerFile("[virt-viewer]\ntype=spice"))
        assertTrue(VirtViewerFile.looksLikeVirtViewerFile("# comment\n[Virt-Viewer]\n"))
        assertFalse(VirtViewerFile.looksLikeVirtViewerFile("<html><body>nope</body></html>"))
    }
}
