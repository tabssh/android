package io.github.tabssh.tracker

import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RenewalDateInputTest {

    private fun utc(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day)
        }.timeInMillis

    private fun dateMillis(text: String): Long? {
        val parsed = RenewalDateInput.parse(text) ?: return null
        assertIs<RenewalDateInput.Parsed.Date>(parsed, text)
        return parsed.epochMillis
    }

    @Test
    fun `accepts month day year and year month day shapes`() {
        val may15 = utc(2027, Calendar.MAY, 15)
        for (text in listOf("May 15, 2027", "may 15 2027", "May 15th, 2027", "2027, May 15", "2027 May 15", "  May   15,  2027 ")) {
            assertEquals(may15, dateMillis(text), text)
        }
        assertEquals(utc(2027, Calendar.SEPTEMBER, 3), dateMillis("Sep 3, 2027"))
    }

    @Test
    fun `rejects impossible dates, missing years and numeric shapes`() {
        for (text in listOf("February 30, 2027", "May 15", "2027", "5/15/2027", "2027-05-15", "May 15, 27", "TBD", "soon")) {
            assertNull(RenewalDateInput.parse(text), text)
        }
    }

    @Test
    fun `blank is not a value`() {
        assertNull(RenewalDateInput.parse(null))
        assertNull(RenewalDateInput.parse("   "))
    }

    @Test
    fun `N-A means the next January 1st`() {
        val now = utc(2026, Calendar.SEPTEMBER, 17)
        for (text in listOf("N/A", "n/a", "NA", "n / a")) {
            val parsed = RenewalDateInput.parse(text, now)
            assertIs<RenewalDateInput.Parsed.NotApplicable>(parsed, text)
            assertEquals(utc(2027, Calendar.JANUARY, 1), parsed.epochMillis)
        }
        // On January 1st itself the next one is a year away, never today.
        assertEquals(utc(2027, Calendar.JANUARY, 1), RenewalDateInput.nextJanuaryFirst(utc(2026, Calendar.JANUARY, 1)))
    }

    @Test
    fun `format writes the canonical display text`() {
        assertEquals("January 1, 2027", RenewalDateInput.format(utc(2027, Calendar.JANUARY, 1)))
        assertEquals("May 15, 2027", RenewalDateInput.format(utc(2027, Calendar.MAY, 15)))
    }
}
