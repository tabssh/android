package io.github.tabssh.terminal.gestures

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prefix-notation parsing tests, including the non-ASCII literal case that a
 * single-byte cast used to mangle.
 */
class PrefixParserTest {

    @Test
    fun `ctrl notations all map to the same control byte`() {
        listOf("C-a", "^a", "^A", "Ctrl-A", "Ctrl+a").forEach { notation ->
            assertEquals(listOf<Byte>(1), PrefixParser.parse(notation)?.toList(), notation)
        }
        assertEquals(listOf<Byte>(2), PrefixParser.parse("C-b")?.toList())
    }

    @Test
    fun `ctrl space is NUL`() {
        assertEquals(listOf<Byte>(0), PrefixParser.parse("C-Space")?.toList())
    }

    @Test
    fun `alt notation is escape plus the key`() {
        assertEquals(
            listOf<Byte>(0x1B, 'b'.code.toByte()),
            PrefixParser.parse("M-b")?.toList()
        )
    }

    @Test
    fun `ascii literal prefixes are a single byte`() {
        assertEquals(listOf<Byte>(0x60), PrefixParser.parse("`")?.toList())
    }

    @Test
    fun `non ascii literal prefixes are encoded as utf8`() {
        // A single-byte cast turned 'ö' (U+00F6) into 0xF6, which is not valid
        // UTF-8 on the wire; the remote saw a replacement character instead of
        // the configured prefix.
        val parsed = PrefixParser.parse("ö")
        assertEquals("ö".toByteArray(Charsets.UTF_8).toList(), parsed?.toList())
        assertTrue((parsed?.size ?: 0) > 1)
    }

    @Test
    fun `hex notation is accepted in both spellings`() {
        assertEquals(listOf<Byte>(2), PrefixParser.parse("0x02")?.toList())
        assertEquals(listOf<Byte>(2), PrefixParser.parse("\\x02")?.toList())
    }

    @Test
    fun `invalid notation returns null`() {
        assertNull(PrefixParser.parse(""))
        assertNull(PrefixParser.parse("C-"))
        assertNull(PrefixParser.parse("nonsense"))
    }
}
