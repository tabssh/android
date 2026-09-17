package io.github.tabssh.tracker

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A VPS billing cadence: either "every [count] [period]s" or "[count] times
 * per [period]".
 *
 * The edit screen builds one from three dropdowns (Every / Times per,
 * 1-9, Month / Year). Stored in `VpsHost.billingCycle` as [storageText]:
 * "1 year", "3 years", "1 month", or "6 times a year". The "N unit" shape is
 * the one older app versions already roll forward, so a synced row keeps
 * working on a device that has not updated yet.
 *
 * [Period.DAY] and [Period.WEEK] exist only for rows written before the
 * dropdowns ("weekly", "2 weeks"); the edit screen cannot pick them.
 */
data class BillingCycle(val mode: Mode, val count: Int, val period: Period) {

    enum class Mode { EVERY, TIMES_PER }

    enum class Period { DAY, WEEK, MONTH, YEAR }

    init {
        require(count >= 1) { "count must be at least 1" }
        require(mode == Mode.EVERY || period == Period.MONTH || period == Period.YEAR) {
            "times-per cycles only exist for months and years"
        }
    }

    /** True when the edit screen's dropdowns can show this cycle exactly. */
    val fitsDropdowns: Boolean
        get() = count in 1..MAX_DROPDOWN_COUNT && (period == Period.MONTH || period == Period.YEAR)

    /** Canonical text stored in `VpsHost.billingCycle`. */
    fun storageText(): String = when (mode) {
        Mode.EVERY -> "$count ${period.word}${if (count == 1) "" else "s"}"
        Mode.TIMES_PER -> "$count times a ${period.word}"
    }

    /** English label used in exported Markdown, e.g. "Annually", "Every 3 years", "6 times a year". */
    fun englishLabel(): String = when {
        mode == Mode.TIMES_PER -> "$count times a ${period.word}"
        count == 1 -> when (period) {
            Period.DAY -> "Daily"
            Period.WEEK -> "Weekly"
            Period.MONTH -> "Monthly"
            Period.YEAR -> "Annually"
        }
        else -> "Every $count ${period.word}s"
    }

    /**
     * One renewal step as a [Calendar] field and amount.
     *
     * A times-per cycle that divides the period into whole months stays on
     * month arithmetic (6 times a year = every 2 months). One that does not
     * (5 times a year, 2 times a month) uses the nearest whole number of days.
     */
    fun step(): Pair<Int, Int> = when (mode) {
        Mode.EVERY -> when (period) {
            Period.DAY -> Calendar.DAY_OF_YEAR to count
            Period.WEEK -> Calendar.DAY_OF_YEAR to count * DAYS_PER_WEEK
            Period.MONTH -> Calendar.MONTH to count
            Period.YEAR -> Calendar.YEAR to count
        }
        Mode.TIMES_PER -> {
            val months = if (period == Period.YEAR) MONTHS_PER_YEAR else 1
            if (months % count == 0) {
                Calendar.MONTH to months / count
            } else {
                Calendar.DAY_OF_YEAR to (months * DAYS_PER_MONTH / count).roundToInt().coerceAtLeast(1)
            }
        }
    }

    private val Period.word: String
        get() = name.lowercase(Locale.US)

    companion object {
        const val MAX_DROPDOWN_COUNT = 9
        private const val DAYS_PER_WEEK = 7
        private const val MONTHS_PER_YEAR = 12
        private const val DAYS_PER_MONTH = 365.2425 / 12

        /** The cycle every free (N/A) host renews on. */
        val ANNUALLY = BillingCycle(Mode.EVERY, 1, Period.YEAR)

        private val TIMES_PER_REGEX = Regex("""^(\d+)\s*(?:times|x)\s*(?:a|per|/)?\s*(years?|months?)$""")
        private val N_UNIT_REGEX = Regex("""^(\d+)\s*(years?|yrs?|months?|mos?|weeks?|wks?|days?)$""")

        /**
         * Build a cycle from dropdown choices. "1 time per year" is the same
         * cadence as "every 1 year", so it is stored as the latter.
         */
        fun of(mode: Mode, count: Int, period: Period): BillingCycle =
            if (mode == Mode.TIMES_PER && count == 1) BillingCycle(Mode.EVERY, 1, period) else BillingCycle(mode, count, period)

        /**
         * Read a stored or typed cycle: the canonical [storageText] shapes, an
         * exported [englishLabel], or the older words ("yearly", "annually",
         * the common misspelling "anually", "biennially", "quarterly",
         * "semiannually", ...). Returns null for blank or unrecognized text.
         */
        fun parse(text: String?): BillingCycle? {
            val normalized = text?.trim()?.lowercase(Locale.US)
                ?.replace(Regex("""\s+"""), " ")
                ?.removePrefix("every ")
                ?.trim()
            if (normalized.isNullOrEmpty()) return null

            TIMES_PER_REGEX.find(normalized)?.let { match ->
                val n = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val period = if (match.groupValues[2].startsWith("year")) Period.YEAR else Period.MONTH
                return of(Mode.TIMES_PER, n, period)
            }
            N_UNIT_REGEX.find(normalized)?.let { match ->
                val n = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val unit = match.groupValues[2]
                val period = when {
                    unit.startsWith("y") -> Period.YEAR
                    unit.startsWith("mo") -> Period.MONTH
                    unit.startsWith("w") -> Period.WEEK
                    else -> Period.DAY
                }
                return BillingCycle(Mode.EVERY, n, period)
            }
            return when (normalized) {
                "daily", "day" -> BillingCycle(Mode.EVERY, 1, Period.DAY)
                "weekly", "week" -> BillingCycle(Mode.EVERY, 1, Period.WEEK)
                "monthly", "month" -> BillingCycle(Mode.EVERY, 1, Period.MONTH)
                "quarterly" -> BillingCycle(Mode.EVERY, 3, Period.MONTH)
                "semiannually", "semi-annually", "semiannual", "semi-annual", "twice a year" ->
                    BillingCycle(Mode.TIMES_PER, 2, Period.YEAR)
                "yearly", "annually", "anually", "annual", "year" -> ANNUALLY
                "biennially", "biennial", "biannually", "biannual" -> BillingCycle(Mode.EVERY, 2, Period.YEAR)
                "triennially", "triennial", "triannually", "triannual" -> BillingCycle(Mode.EVERY, 3, Period.YEAR)
                else -> null
            }
        }
    }
}
