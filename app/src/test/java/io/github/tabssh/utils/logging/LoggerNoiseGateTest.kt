package io.github.tabssh.utils.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writer-side noise control (Logger.NoiseGate) — duplicate collapse and
 * the per-tag rate cap that keep chatty call sites from flooding the
 * debug log past export caps.
 */
class LoggerNoiseGateTest {

    private var clock = 0L

    private fun gate() = Logger.NoiseGate { clock }

    @Test
    fun `distinct lines pass through unchanged`() {
        val g = gate()
        val a = g.filter("I", "Tag", "first", false, "ts")
        val b = g.filter("I", "Tag", "second", false, "ts")
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertTrue(a[0].contains("first"))
        assertTrue(b[0].contains("second"))
    }

    @Test
    fun `identical consecutive lines collapse into repeat summary`() {
        val g = gate()
        assertEquals(1, g.filter("D", "Tag", "same", false, "ts").size)
        assertEquals(0, g.filter("D", "Tag", "same", false, "ts").size)
        assertEquals(0, g.filter("D", "Tag", "same", false, "ts").size)
        val out = g.filter("D", "Tag", "different", false, "ts")
        assertEquals(2, out.size)
        assertTrue(out[0].contains("repeated 2 more times"))
        assertTrue(out[1].contains("different"))
    }

    @Test
    fun `rate cap suppresses excess D lines per tag within a window`() {
        val g = gate()
        var written = 0
        for (i in 1..20) {
            written += g.filter("D", "Chatty", "msg $i", true, "ts").size
        }
        assertEquals(8, written)
    }

    @Test
    fun `rate cap emits suppression summary when window rolls`() {
        val g = gate()
        for (i in 1..20) {
            g.filter("D", "Chatty", "msg $i", true, "ts")
        }
        clock += 1000L
        val out = g.filter("D", "Chatty", "next window", true, "ts")
        assertEquals(2, out.size)
        assertTrue(out[0].contains("suppressed 12 lines"))
        assertTrue(out[1].contains("next window"))
    }

    @Test
    fun `rate cap is per tag not global`() {
        val g = gate()
        for (i in 1..8) {
            g.filter("D", "TagA", "a $i", true, "ts")
        }
        val out = g.filter("D", "TagB", "b 1", true, "ts")
        assertEquals(1, out.size)
    }

    @Test
    fun `non rate-capped levels are never suppressed by the cap`() {
        val g = gate()
        var written = 0
        for (i in 1..20) {
            written += g.filter("W", "Warny", "warn $i", false, "ts").size
        }
        assertEquals(20, written)
    }
}
