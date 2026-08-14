package io.github.tabssh.terminal

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the UTF-8 chunk-boundary hold-back in
 * [TermuxBridge.utf8IncompleteTrailingBytes]. A multi-byte character split
 * across two SSH reads used to be decoded chunk-wise with String(bytes),
 * turning both halves into U+FFFD and corrupting the terminal cell
 * (visible as "??" tofu in TUI status lines).
 */
class Utf8ChunkBoundaryTest {

    private fun holdback(bytes: ByteArray): Int =
        TermuxBridge.utf8IncompleteTrailingBytes(bytes, bytes.size)

    @Test
    fun `complete ascii ends on a boundary`() {
        assertEquals(0, holdback("hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `empty buffer holds nothing`() {
        assertEquals(0, holdback(ByteArray(0)))
    }

    @Test
    fun `complete multi-byte sequences end on a boundary`() {
        // 2-byte (é), 3-byte (⏵), 4-byte (🙂)
        assertEquals(0, holdback("é".toByteArray(Charsets.UTF_8)))
        assertEquals(0, holdback("⏵".toByteArray(Charsets.UTF_8)))
        assertEquals(0, holdback("🙂".toByteArray(Charsets.UTF_8)))
        assertEquals(0, holdback("abc⏵".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `split sequences hold back exactly the incomplete tail`() {
        // ⏵ = E2 8F B5: cut after 1 and after 2 bytes.
        val triangle = "⏵".toByteArray(Charsets.UTF_8)
        assertEquals(1, holdback(byteArrayOf(*"a".toByteArray(), triangle[0])))
        assertEquals(2, holdback(byteArrayOf(*"a".toByteArray(), triangle[0], triangle[1])))
        // 🙂 = F0 9F 99 82: cut after 1, 2, and 3 bytes.
        val smiley = "🙂".toByteArray(Charsets.UTF_8)
        assertEquals(1, holdback(byteArrayOf(smiley[0])))
        assertEquals(2, holdback(byteArrayOf(smiley[0], smiley[1])))
        assertEquals(3, holdback(byteArrayOf(smiley[0], smiley[1], smiley[2])))
        // 2-byte é = C3 A9: cut after the lead byte.
        val eAcute = "é".toByteArray(Charsets.UTF_8)
        assertEquals(1, holdback(byteArrayOf(*"abc".toByteArray(), eAcute[0])))
    }

    @Test
    fun `invalid trailing bytes are passed through unchanged`() {
        // Lone continuation bytes with no lead — invalid; decoder handles them.
        assertEquals(0, holdback(byteArrayOf(0x80.toByte())))
        assertEquals(0, holdback(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte())))
        // Overlong run: lead expects 2 bytes but 3 follow — complete-and-invalid.
        assertEquals(0, holdback(byteArrayOf(0xC3.toByte(), 0xA9.toByte(), 0xA9.toByte(), 0xA9.toByte())))
    }

    @Test
    fun `two whole chars split mid-pair holds only the second`() {
        // "⏵⏵" split after 4 bytes: first char complete, second has 1 byte.
        val both = "⏵⏵".toByteArray(Charsets.UTF_8)
        assertEquals(1, holdback(both.copyOf(4)))
        assertEquals(2, holdback(both.copyOf(5)))
        assertEquals(0, holdback(both))
    }
}
