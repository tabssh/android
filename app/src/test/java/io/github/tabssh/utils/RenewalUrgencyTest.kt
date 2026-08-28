package io.github.tabssh.utils

import android.app.Application
import io.github.tabssh.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for [RenewalUrgency]'s day-boundary tiers — the exact
 * bug class this guards against is two tiers silently overlapping at a
 * boundary (e.g. day 14 counted as both "2 weeks" and "4 weeks").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RenewalUrgencyTest {

    private val now = 1_700_000_000_000L
    private val dayMillis = 86_400_000L
    private fun daysFromNow(days: Long) = now + days * dayMillis

    @Test
    fun `unknown when no date is tracked`() {
        assertEquals(RenewalUrgency.UNKNOWN, RenewalUrgency.of(null, now))
        assertNull(RenewalUrgency.daysUntil(null, now))
    }

    @Test
    fun `overdue for any date in the past`() {
        assertEquals(RenewalUrgency.OVERDUE, RenewalUrgency.of(daysFromNow(-1), now))
        assertEquals(RenewalUrgency.OVERDUE, RenewalUrgency.of(now - 1, now))
    }

    @Test
    fun `critical covers exactly 0 to 13 days`() {
        assertEquals(RenewalUrgency.CRITICAL, RenewalUrgency.of(daysFromNow(0), now))
        assertEquals(RenewalUrgency.CRITICAL, RenewalUrgency.of(daysFromNow(13), now))
    }

    @Test
    fun `warning covers exactly 14 to 27 days with no overlap at the boundary`() {
        assertEquals(RenewalUrgency.WARNING, RenewalUrgency.of(daysFromNow(14), now))
        assertEquals(RenewalUrgency.WARNING, RenewalUrgency.of(daysFromNow(27), now))
    }

    @Test
    fun `ok covers 28 days and beyond`() {
        assertEquals(RenewalUrgency.OK, RenewalUrgency.of(daysFromNow(28), now))
        assertEquals(RenewalUrgency.OK, RenewalUrgency.of(daysFromNow(365), now))
    }

    @Test
    fun `every tier boundary is covered exactly once`() {
        val seen = (-1L..30L).associateWith { RenewalUrgency.of(daysFromNow(it), now) }
        assertEquals(RenewalUrgency.OVERDUE, seen[-1L])
        assertEquals(RenewalUrgency.CRITICAL, seen[0L])
        assertEquals(RenewalUrgency.CRITICAL, seen[13L])
        assertEquals(RenewalUrgency.WARNING, seen[14L])
        assertEquals(RenewalUrgency.WARNING, seen[27L])
        assertEquals(RenewalUrgency.OK, seen[28L])
        assertEquals(RenewalUrgency.OK, seen[30L])
    }

    @Test
    fun `daysUntil rounds down through midnight rather than truncating toward zero`() {
        // 12 hours before `now` — a naive `/ dayMillis` truncation would
        // report 0 (still today); floorDiv must report -1 (already past).
        assertEquals(-1L, RenewalUrgency.daysUntil(now - dayMillis / 2, now))
    }

    // ── effectiveDate() — recurring-cycle rollover ──────────────────────────

    @Test
    fun `effectiveDate leaves a future date untouched`() {
        val future = daysFromNow(10)
        assertEquals(future, RenewalUrgency.effectiveDate(future, "monthly", now))
    }

    @Test
    fun `effectiveDate passes through null date or unknown cycle unchanged`() {
        assertNull(RenewalUrgency.effectiveDate(null, "monthly", now))
        val past = daysFromNow(-5)
        assertEquals(past, RenewalUrgency.effectiveDate(past, null, now))
        assertEquals(past, RenewalUrgency.effectiveDate(past, "one-time", now))
    }

    @Test
    fun `effectiveDate rolls a stale monthly date forward to the next occurrence`() {
        // Jan 10 2025, billing monthly, "now" is well past that — the
        // projected date must be the next monthly occurrence on/after now,
        // not the stale original.
        val cal = Calendar.getInstance()
        cal.set(2025, Calendar.JANUARY, 10, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val staleDate = cal.timeInMillis

        // Pin "now" relative to the stale date itself (not the fixture `now`,
        // which predates it) so the rollover math stays deterministic.
        val referenceNow = staleDate + 75L * dayMillis // ~2.5 months after Jan 10

        val result = RenewalUrgency.effectiveDate(staleDate, "monthly", referenceNow)
        requireNotNull(result)
        assertEquals(true, result >= referenceNow)

        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = result
        assertEquals(10, resultCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `effectiveDate rolls a stale yearly date forward by whole years`() {
        val cal = Calendar.getInstance()
        cal.set(2020, Calendar.MARCH, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val staleDate = cal.timeInMillis
        val referenceNow = staleDate + 1500L * dayMillis // a few years later

        val result = RenewalUrgency.effectiveDate(staleDate, "yearly", referenceNow)
        requireNotNull(result)
        val resultCal = Calendar.getInstance()
        resultCal.timeInMillis = result
        assertEquals(Calendar.MARCH, resultCal.get(Calendar.MONTH))
        assertEquals(1, resultCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(true, result >= referenceNow)
    }

    // ── pillText() — i18n label text ────────────────────────────────────────

    @Test
    fun `pillText reports unknown when no date is tracked`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.renewal_pill_unknown), RenewalUrgency.pillText(context, null, now))
    }

    @Test
    fun `pillText reports overdue days for a past date`() {
        val context = RuntimeEnvironment.getApplication()
        val text = RenewalUrgency.pillText(context, daysFromNow(-3), now)
        assertEquals(true, text.contains("3"))
    }

    @Test
    fun `pillText reports due today at the zero boundary`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(context.getString(R.string.renewal_pill_today), RenewalUrgency.pillText(context, daysFromNow(0), now))
    }

    @Test
    fun `pillText reports days left for a future date`() {
        val context = RuntimeEnvironment.getApplication()
        val text = RenewalUrgency.pillText(context, daysFromNow(5), now)
        assertEquals(true, text.contains("5"))
    }
}
