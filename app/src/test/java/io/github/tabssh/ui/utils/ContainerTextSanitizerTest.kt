package io.github.tabssh.ui.utils

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the shared Docker display sanitiser.
 *
 * Container names, image tags, volume/network names and daemon error bodies
 * are all remote-controlled and are rendered in list rows and destructive
 * confirmation dialogs. Without stripping, a bidi override could make a
 * "remove volume X" prompt read as a different volume, and a C0 escape run
 * echoed into a console tab could drive the terminal emulator.
 */
class ContainerTextSanitizerTest {

    @Test
    fun `display strips C0 controls and escape sequences`() {
        val raw = "web\u001B[31mprod\u0007"
        val out = ContainerText.display(raw)
        // The whole CSI sequence goes, not just the ESC byte — leaving
        // "[31m" residue would still corrupt the rendered name.
        assertEquals("webprod", out)
        assertFalse(out.any { it.code < 0x20 })
    }

    @Test
    fun `display strips bidi overrides and isolates`() {
        val raw = "safe\u202Eesrever\u202C\u2066x\u2069"
        val out = ContainerText.display(raw)
        assertFalse(out.any { it.code in 0x202A..0x202E })
        assertFalse(out.any { it.code in 0x2066..0x2069 })
        assertEquals("safeesreverx", out)
    }

    @Test
    fun `display strips C1 controls and DEL`() {
        val out = ContainerText.display("a\u0085b\u009Bc\u007Fd")
        assertEquals("abcd", out)
    }

    @Test
    fun `display collapses line breaks to spaces`() {
        assertEquals("one two three", ContainerText.display("one\ntwo\tthree"))
    }

    @Test
    fun `display trims surrounding whitespace`() {
        assertEquals("name", ContainerText.display("  name\n"))
    }

    @Test
    fun `display caps at the requested length with an ellipsis`() {
        val out = ContainerText.display("x".repeat(400), 16)
        assertEquals(17, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `display returns empty for null and empty input`() {
        assertEquals("", ContainerText.display(null))
        assertEquals("", ContainerText.display(""))
    }

    @Test
    fun `display leaves ordinary unicode intact`() {
        assertEquals("café-日本", ContainerText.display("café-日本"))
    }

    @Test
    fun `block preserves newlines but drops controls`() {
        assertEquals("line1\nline2\n", ContainerText.block("line1\n\u001B[2Jline2\n"))
    }

    @Test
    fun `block caps oversized input`() {
        val out = ContainerText.block("a".repeat(100), 10)
        assertEquals(11, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `block returns empty for null input`() {
        assertEquals("", ContainerText.block(null))
    }
}
