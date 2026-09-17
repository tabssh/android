package io.github.tabssh.tracker

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Strict parsing for the VPS renewal date field.
 *
 * Accepted: "Month Day, Year" ("May 15, 2027", "May 15th 2027", "Sep 3, 2027")
 * and "Year, Month Day" ("2027, May 15"), or "N/A" for a free host. Every date
 * is a real calendar day (no "February 30") with a four-digit year. Dates are
 * UTC midnight, matching how the tracker stores and rolls renewal dates.
 */
object RenewalDateInput {

    /** Result of reading the field: a date, or N/A (a free host). */
    sealed interface Parsed {
        val epochMillis: Long

        data class Date(override val epochMillis: Long) : Parsed

        /** "N/A": the host is free, so it renews on the next January 1st, yearly. */
        data class NotApplicable(override val epochMillis: Long) : Parsed
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val ORDINAL_SUFFIX_REGEX = Regex("""(\d+)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val FOUR_DIGIT_YEAR_REGEX = Regex("""(^|\D)\d{4}(\D|$)""")
    private val NOT_APPLICABLE_REGEX = Regex("""^n\s*/?\s*a$""", RegexOption.IGNORE_CASE)
    private val PATTERNS = listOf(
        "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy", "MMM d yyyy",
        "yyyy, MMMM d", "yyyy, MMM d", "yyyy MMMM d", "yyyy MMM d"
    )
    private val DISPLAY_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).apply { timeZone = UTC }
    }

    /** Parse [text] as typed; null when it is blank or not an accepted date or N/A. */
    fun parse(text: String?, now: Long = System.currentTimeMillis()): Parsed? {
        val trimmed = text?.trim()?.replace(Regex("""\s+"""), " ")
        if (trimmed.isNullOrEmpty()) return null
        if (NOT_APPLICABLE_REGEX.matches(trimmed)) return Parsed.NotApplicable(nextJanuaryFirst(now))
        return parseDate(trimmed)?.let { Parsed.Date(it) }
    }

    /** Parse an accepted date shape to UTC-midnight epoch millis, or null. */
    fun parseDate(text: String): Long? {
        val cleaned = ORDINAL_SUFFIX_REGEX.replace(text.trim().replace(Regex("""\s+"""), " "), "$1")
        if (!FOUR_DIGIT_YEAR_REGEX.containsMatchIn(cleaned)) return null
        for (pattern in PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = UTC
                isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = format.parse(cleaned, position) ?: continue
            if (position.index == cleaned.length) return parsed.time
        }
        return null
    }

    /** January 1st of the year after [now]'s year, UTC midnight. */
    fun nextJanuaryFirst(now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance(UTC)
        calendar.timeInMillis = now
        val nextYear = calendar.get(Calendar.YEAR) + 1
        calendar.clear()
        calendar.set(nextYear, Calendar.JANUARY, 1)
        return calendar.timeInMillis
    }

    /** Canonical display text for a stored date, e.g. "May 15, 2027". */
    fun format(epochMillis: Long): String =
        checkNotNull(DISPLAY_FORMAT.get()) { "ThreadLocal SimpleDateFormat initializer returned null" }.format(epochMillis)
}
