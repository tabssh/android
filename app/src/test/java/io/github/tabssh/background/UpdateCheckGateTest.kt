package io.github.tabssh.background

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-host update-check due-time gate — enable flag, interval override, and
 * the twice-daily default cadence.
 */
class UpdateCheckGateTest {

    private val hourMs = 60L * 60L * 1000L
    private val now = 100L * hourMs

    @Test
    fun `disabled host is never due`() {
        assertFalse(UpdateCheckGate.isHostDue(false, 0L, null, 12L, now))
    }

    @Test
    fun `never-checked host is due immediately`() {
        assertTrue(UpdateCheckGate.isHostDue(true, 0L, null, 12L, now))
    }

    @Test
    fun `default cadence gates at twelve hours`() {
        assertFalse(UpdateCheckGate.isHostDue(true, now - 11 * hourMs, null, 12L, now))
        assertTrue(UpdateCheckGate.isHostDue(true, now - 12 * hourMs, null, 12L, now))
    }

    @Test
    fun `per-host override shortens the interval`() {
        assertTrue(UpdateCheckGate.isHostDue(true, now - 7 * hourMs, 6, 12L, now))
        assertFalse(UpdateCheckGate.isHostDue(true, now - 5 * hourMs, 6, 12L, now))
    }

    @Test
    fun `per-host override lengthens the interval`() {
        assertFalse(UpdateCheckGate.isHostDue(true, now - 13 * hourMs, 24, 12L, now))
        assertTrue(UpdateCheckGate.isHostDue(true, now - 24 * hourMs, 24, 12L, now))
    }

    @Test
    fun `non-positive override falls back to the default`() {
        assertTrue(UpdateCheckGate.isHostDue(true, now - 12 * hourMs, 0, 12L, now))
        assertFalse(UpdateCheckGate.isHostDue(true, now - 11 * hourMs, -3, 12L, now))
    }
}
