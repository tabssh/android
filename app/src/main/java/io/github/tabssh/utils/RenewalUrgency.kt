package io.github.tabssh.utils

import android.content.Context
import io.github.tabssh.R
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Shared renewal-urgency tiering for the VPS Hosting Tracker and Domain
 * Tracker list rows — both track a "renews/expires on X" date and both need
 * the same color-coded urgency at a glance, so the threshold logic lives
 * here once instead of being duplicated per activity.
 *
 * Tiers are boundary-exclusive of each other (no day falls in two tiers):
 * OVERDUE (`< 0` days) · CRITICAL (`0..13` days, "0 to 2 weeks") ·
 * WARNING (`14..27` days, "2 to 4 weeks") · OK (`>= 28` days, "more than a
 * month"). UNKNOWN covers hosts/domains with no parsed date at all (e.g.
 * "Free/Never", "TBD", or the field was simply never set).
 */
enum class RenewalUrgency {
    OVERDUE,
    CRITICAL,
    WARNING,
    OK,
    UNKNOWN;

    val colorAttrRes: Int
        get() = when (this) {
            OVERDUE -> R.color.status_overdue
            CRITICAL -> R.color.status_error
            WARNING -> R.color.status_warning
            OK -> R.color.status_success
            UNKNOWN -> R.color.status_neutral
        }

    val containerColorAttrRes: Int
        get() = when (this) {
            OVERDUE -> R.color.status_overdue_container
            CRITICAL -> R.color.status_error_container
            WARNING -> R.color.status_warning_container
            OK -> R.color.status_success_container
            UNKNOWN -> R.color.status_neutral_container
        }

    companion object {
        private const val CRITICAL_MAX_DAYS = 13
        private const val WARNING_MAX_DAYS = 27
        private const val MILLIS_PER_DAY = 86_400_000L

        /** [renewalOrExpirationDate] is epoch millis, or null when no date is tracked. */
        fun of(renewalOrExpirationDate: Long?, now: Long = System.currentTimeMillis()): RenewalUrgency {
            if (renewalOrExpirationDate == null) return UNKNOWN
            // floorDiv (not plain `/`, which truncates toward zero) so a date
            // a few hours in the past reports -1 day, not 0 — keeping it in
            // OVERDUE instead of leaking into CRITICAL's 0-day boundary.
            val daysUntil = Math.floorDiv(renewalOrExpirationDate - now, MILLIS_PER_DAY)
            return when {
                daysUntil < 0 -> OVERDUE
                daysUntil <= CRITICAL_MAX_DAYS -> CRITICAL
                daysUntil <= WARNING_MAX_DAYS -> WARNING
                else -> OK
            }
        }

        /** Whole days between [now] and [renewalOrExpirationDate], negative if already past; null when no date is tracked. */
        fun daysUntil(renewalOrExpirationDate: Long?, now: Long = System.currentTimeMillis()): Long? {
            if (renewalOrExpirationDate == null) return null
            return Math.floorDiv(renewalOrExpirationDate - now, MILLIS_PER_DAY)
        }

        /**
         * A recurring "renews every X" service (tracked by [VpsHost.billingCycle])
         * often has a stored `renewalDate` that is simply the *last known*
         * due date from an old import, not the next one — e.g. a monthly
         * host imported months ago still shows "Jan 10" long after several
         * automatic renewals have already happened. Rather than reporting
         * that as wildly overdue, roll [date] forward by whole billing-cycle
         * periods (using calendar month/year arithmetic, not fixed day
         * counts, so month-length variance is handled correctly) until it
         * lands on or after [now] — i.e. the *next* actual due date. A
         * one-time/unknown cycle (null, or anything not in the recognized
         * set) is returned unchanged, since there is no periodicity to
         * project forward from.
         */
        fun effectiveDate(date: Long?, billingCycle: String?, now: Long = System.currentTimeMillis()): Long? {
            if (date == null || billingCycle == null || date >= now) return date
            // Hosts imported from VPS.md get a normalized cycle string, but
            // ones entered by hand (VpsHostEditActivity's billing-cycle
            // field is free text) may carry any case/synonym the user typed
            // ("Monthly", "MONTHLY", "annual") — match case-insensitively
            // against the same synonym set the importer recognizes so
            // auto-detection doesn't silently no-op on those.
            val normalizedCycle = billingCycle.trim().lowercase(Locale.US)
            val field = when (normalizedCycle) {
                "daily", "day" -> Calendar.DAY_OF_YEAR
                "weekly", "week" -> Calendar.DAY_OF_YEAR
                "monthly", "month" -> Calendar.MONTH
                "yearly", "annually", "annual", "year" -> Calendar.YEAR
                "biennially", "biennial" -> Calendar.YEAR
                "triennially", "triennial" -> Calendar.YEAR
                else -> return date
            }
            val amount = when (normalizedCycle) {
                "weekly", "week" -> 7
                "biennially", "biennial" -> 2
                "triennially", "triennial" -> 3
                else -> 1
            }
            // renewalDate is always anchored in UTC by VpsMarkdownImportExport
            // (both the exact-date and year-less best-effort parse paths use a
            // UTC Calendar) — rolling forward in the default/local timezone
            // here would read the wrong day-of-month whenever the device's
            // offset crosses local midnight, silently shifting the "due on
            // the Nth" day by one. Stay in UTC to match how the date was built.
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = date
            // Safety cap: bounds the loop even for a pathological input
            // (e.g. a decades-old date on a daily cycle) instead of
            // spinning — 10,000 iterations covers ~27 years of daily
            // rollover, far beyond any realistic renewal gap.
            var iterations = 0
            while (calendar.timeInMillis < now && iterations < 10_000) {
                calendar.add(field, amount)
                iterations++
            }
            return calendar.timeInMillis
        }

        /**
         * Human-readable pill label for a renewal/expiration date, shared by
         * the VPS Tracker and Domain Tracker row adapters so the wording
         * never drifts between the two screens.
         */
        fun pillText(context: Context, renewalOrExpirationDate: Long?, now: Long = System.currentTimeMillis()): String {
            val days = daysUntil(renewalOrExpirationDate, now) ?: return context.getString(R.string.renewal_pill_unknown)
            return when {
                days < 0 -> context.resources.getQuantityString(R.plurals.renewal_pill_overdue, (-days).toInt(), -days)
                days == 0L -> context.getString(R.string.renewal_pill_today)
                else -> context.resources.getQuantityString(R.plurals.renewal_pill_days_left, days.toInt(), days)
            }
        }
    }
}
