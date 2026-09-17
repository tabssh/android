package io.github.tabssh.tracker

import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingCycleTest {

    @Test
    fun `annual synonyms and the anually misspelling all read as every 1 year`() {
        for (text in listOf("annually", "Anually", "YEARLY", "annual", "1 year", "every 1 year", "Annually")) {
            assertEquals(BillingCycle.ANNUALLY, BillingCycle.parse(text), text)
        }
    }

    @Test
    fun `storage text and english label round-trip through parse`() {
        val cycles = listOf(
            BillingCycle.of(BillingCycle.Mode.EVERY, 1, BillingCycle.Period.MONTH),
            BillingCycle.of(BillingCycle.Mode.EVERY, 3, BillingCycle.Period.YEAR),
            BillingCycle.of(BillingCycle.Mode.TIMES_PER, 6, BillingCycle.Period.YEAR),
            BillingCycle.of(BillingCycle.Mode.TIMES_PER, 2, BillingCycle.Period.MONTH)
        )
        for (cycle in cycles) {
            assertEquals(cycle, BillingCycle.parse(cycle.storageText()))
            assertEquals(cycle, BillingCycle.parse(cycle.englishLabel()))
        }
    }

    @Test
    fun `storage text keeps the N unit shape older app versions roll forward`() {
        assertEquals("1 year", BillingCycle.ANNUALLY.storageText())
        assertEquals("3 years", BillingCycle.of(BillingCycle.Mode.EVERY, 3, BillingCycle.Period.YEAR).storageText())
        assertEquals("6 times a year", BillingCycle.of(BillingCycle.Mode.TIMES_PER, 6, BillingCycle.Period.YEAR).storageText())
    }

    @Test
    fun `one time per period is stored as every one period`() {
        assertEquals(BillingCycle.ANNUALLY, BillingCycle.of(BillingCycle.Mode.TIMES_PER, 1, BillingCycle.Period.YEAR))
        assertEquals(BillingCycle.ANNUALLY, BillingCycle.parse("1 times a year"))
    }

    @Test
    fun `english labels read naturally`() {
        assertEquals("Annually", BillingCycle.ANNUALLY.englishLabel())
        assertEquals("Monthly", BillingCycle.parse("monthly")?.englishLabel())
        assertEquals("Every 3 years", BillingCycle.parse("triennially")?.englishLabel())
        assertEquals("6 times a year", BillingCycle.parse("6 times a year")?.englishLabel())
    }

    @Test
    fun `older words map to their cadence`() {
        assertEquals("3 months", BillingCycle.parse("quarterly")?.storageText())
        assertEquals("2 times a year", BillingCycle.parse("semi-annually")?.storageText())
        assertEquals("2 years", BillingCycle.parse("biannually")?.storageText())
        assertEquals("1 week", BillingCycle.parse("weekly")?.storageText())
    }

    @Test
    fun `blank and unknown text parse to null`() {
        assertNull(BillingCycle.parse(null))
        assertNull(BillingCycle.parse("  "))
        assertNull(BillingCycle.parse("one-time"))
        assertNull(BillingCycle.parse("0 years"))
    }

    @Test
    fun `only 1-9 months or years fit the dropdowns`() {
        assertTrue(BillingCycle.ANNUALLY.fitsDropdowns)
        assertFalse(requireNotNull(BillingCycle.parse("10 years")).fitsDropdowns)
        assertFalse(requireNotNull(BillingCycle.parse("weekly")).fitsDropdowns)
    }

    @Test
    fun `times per steps use whole months when they divide evenly, else days`() {
        assertEquals(Calendar.MONTH to 2, BillingCycle.parse("6 times a year")?.step())
        assertEquals(Calendar.MONTH to 3, BillingCycle.parse("4 times a year")?.step())
        assertEquals(Calendar.DAY_OF_YEAR to 73, BillingCycle.parse("5 times a year")?.step())
        assertEquals(Calendar.DAY_OF_YEAR to 15, BillingCycle.parse("2 times a month")?.step())
        assertEquals(Calendar.YEAR to 3, BillingCycle.parse("3 years")?.step())
    }
}
