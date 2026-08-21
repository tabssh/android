package io.github.tabssh.terminal

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the ESC[?2004h/l chunk-boundary hold-back in
 * [TermuxBridge.escIncompleteTrailingBytes]. A socket read splitting the
 * bracketed-paste DECSET toggle in two used to leave neither chunk
 * containing the full token, so the enable/disable was never detected and
 * bracketedPasteActive stuck at its old value — a remote that had just
 * enabled bracketed paste could still get raw (unwrapped) multi-line pastes,
 * each embedded CR read by the shell as Enter.
 */
class BracketedPasteChunkBoundaryTest {

    private fun holdback(bytes: ByteArray): Int =
        TermuxBridge.escIncompleteTrailingBytes(bytes, bytes.size)

    private val enable = byteArrayOf(0x1B) + "[?2004h".toByteArray(Charsets.US_ASCII)
    private val disable = byteArrayOf(0x1B) + "[?2004l".toByteArray(Charsets.US_ASCII)

    @Test
    fun `empty buffer holds nothing`() {
        assertEquals(0, holdback(ByteArray(0)))
    }

    @Test
    fun `complete toggle ends on a boundary`() {
        assertEquals(0, holdback(enable))
        assertEquals(0, holdback(disable))
        assertEquals(0, holdback(byteArrayOf(*"prompt$ ".toByteArray(), *enable)))
    }

    @Test
    fun `unrelated text holds nothing`() {
        assertEquals(0, holdback("hello world".toByteArray()))
        // A different escape sequence entirely (cursor up) — not a prefix.
        assertEquals(0, holdback(byteArrayOf(0x1B, 'A'.code.toByte())))
    }

    @Test
    fun `split toggle holds back exactly the partial prefix`() {
        for (cut in 1 until enable.size) {
            assertEquals(cut, holdback(enable.copyOf(cut)), "enable cut at $cut")
        }
        for (cut in 1 until disable.size) {
            assertEquals(cut, holdback(disable.copyOf(cut)), "disable cut at $cut")
        }
    }

    @Test
    fun `split toggle preceded by other text still holds only the partial tail`() {
        val prefixed = "output before\n".toByteArray() + enable.copyOf(5)
        assertEquals(5, holdback(prefixed))
    }

    @Test
    fun `lone leading ESC byte is held back`() {
        assertEquals(1, holdback(byteArrayOf(0x1B)))
        assertEquals(1, holdback(byteArrayOf(*"text".toByteArray(), 0x1B)))
    }
}
