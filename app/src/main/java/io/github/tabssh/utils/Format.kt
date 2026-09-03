package io.github.tabssh.utils

import android.content.Context
import android.text.format.DateUtils
import io.github.tabssh.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Shared human-readable formatting for user-facing values.
 *
 * One implementation per value kind, never per-screen ad-hoc formatting
 * (AI.md PART 7 § Human-Readable Values). Machine surfaces — JSON payloads,
 * log lines — keep their raw values and must not call in here.
 */
object Format {

    private const val BYTES_PER_UNIT = 1024.0

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86400L

    // Ordered smallest to largest; each step is one 1024 boundary.
    private val SIZE_UNITS = intArrayOf(
        R.plurals.format_size_bytes,
        R.plurals.format_size_kilobytes,
        R.plurals.format_size_megabytes,
        R.plurals.format_size_gigabytes,
        R.plurals.format_size_terabytes
    )

    /**
     * A count with the current locale's grouping separators, e.g. `12,847` in
     * en-US and `12.847` in de-DE.
     */
    fun count(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())

    /**
     * A byte count as a human-readable size on 1024 boundaries with full unit
     * names and at most one decimal, e.g. `1 byte`, `512 bytes`, `2.5 megabytes`.
     *
     * A trailing `.0` is dropped so whole values read as `5 gigabytes`.
     * Negative input is treated as zero — sizes are never negative and a
     * caller passing one has a bug upstream, not a display problem here.
     */
    fun size(context: Context, bytes: Long): String {
        var value = (if (bytes < 0) 0L else bytes).toDouble()
        var unitIndex = 0
        while (value >= BYTES_PER_UNIT && unitIndex < SIZE_UNITS.lastIndex) {
            value /= BYTES_PER_UNIT
            unitIndex++
        }
        // Rounding to one decimal can lift a value onto the next boundary
        // (1023.97 kilobytes becomes 1024.0), so promote it rather than print
        // a number that belongs in the unit above.
        var rounded = roundToOneDecimal(value)
        if (rounded >= BYTES_PER_UNIT && unitIndex < SIZE_UNITS.lastIndex) {
            rounded = roundToOneDecimal(rounded / BYTES_PER_UNIT)
            unitIndex++
        }
        return context.resources.getQuantityString(
            SIZE_UNITS[unitIndex],
            pluralQuantity(rounded),
            decimal(rounded)
        )
    }

    /**
     * A duration as the largest fitting unit plus at most one smaller unit,
     * e.g. `45 seconds`, `2 minutes 5 seconds`, `1 hour 30 minutes`, `3 days 4 hours`.
     *
     * Sub-second input floors to `0 seconds`; negative input is treated as zero.
     */
    fun duration(context: Context, milliseconds: Long): String {
        val totalSeconds = (if (milliseconds < 0) 0L else milliseconds) / MILLIS_PER_SECOND
        return when {
            totalSeconds < SECONDS_PER_MINUTE ->
                unit(context, R.plurals.format_duration_seconds, totalSeconds)

            totalSeconds < SECONDS_PER_HOUR -> twoUnits(
                context,
                R.plurals.format_duration_minutes,
                totalSeconds / SECONDS_PER_MINUTE,
                R.plurals.format_duration_seconds,
                totalSeconds % SECONDS_PER_MINUTE
            )

            totalSeconds < SECONDS_PER_DAY -> twoUnits(
                context,
                R.plurals.format_duration_hours,
                totalSeconds / SECONDS_PER_HOUR,
                R.plurals.format_duration_minutes,
                (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            )

            else -> twoUnits(
                context,
                R.plurals.format_duration_days,
                totalSeconds / SECONDS_PER_DAY,
                R.plurals.format_duration_hours,
                (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
            )
        }
    }

    /**
     * A per-second rate built from an already-formatted value, e.g. `2.5 megabytes/s`.
     * The suffix lives in a string resource so translations control its placement.
     */
    fun rate(context: Context, bytesPerSecond: Long): String =
        context.getString(R.string.format_rate_fmt, size(context, bytesPerSecond))

    /**
     * A past event timestamp in hybrid style: relative within the last week
     * (`22 hours ago`, `2 days ago`), an absolute locale-formatted date beyond
     * it (`Aug 23, 2026`) — so recent activity reads at a glance while old
     * activity stays unambiguous. Every screen showing "when did this last
     * happen" uses this one helper instead of a per-screen SimpleDateFormat.
     */
    fun pastTimestamp(context: Context, timestampMillis: Long): String {
        val now = System.currentTimeMillis()
        return if (now - timestampMillis < DateUtils.WEEK_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(
                timestampMillis,
                now,
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } else {
            DateUtils.formatDateTime(
                context,
                timestampMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            )
        }
    }

    /**
     * A connection-usage count with correct pluralization, e.g.
     * `Connected 1 time` / `Connected 5 times`, via a plurals resource so
     * translations control the forms.
     */
    fun connectedTimes(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.format_connected_times, count, count)

    /** A single quantified unit, e.g. `3 minutes`. */
    private fun unit(context: Context, pluralRes: Int, amount: Long): String =
        context.resources.getQuantityString(
            pluralRes,
            pluralQuantity(amount.toDouble()),
            decimal(amount.toDouble())
        )

    /**
     * Two quantified units joined by a locale-controlled separator; the smaller
     * unit is dropped entirely when it is zero, so `2 hours` never reads
     * `2 hours 0 minutes`.
     */
    private fun twoUnits(
        context: Context,
        majorRes: Int,
        majorAmount: Long,
        minorRes: Int,
        minorAmount: Long
    ): String {
        val major = unit(context, majorRes, majorAmount)
        if (minorAmount <= 0L) return major
        return context.getString(R.string.format_duration_pair_fmt, major, unit(context, minorRes, minorAmount))
    }

    /**
     * Android's plural lookup takes an integer quantity, so a fractional value
     * cannot be handed to it directly. A whole value passes its own magnitude
     * through (1 selects `one`, 5 selects `other`); a fractional value always
     * takes the plural form, and 2 is the smallest integer that selects
     * `other` in every CLDR language rule set.
     */
    private fun pluralQuantity(value: Double): Int =
        if (value == floor(value)) value.toInt() else 2

    /** The number itself, locale-formatted, with a trailing `.0` dropped. */
    private fun decimal(value: Double): String =
        if (value == floor(value)) {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong())
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }

    private fun roundToOneDecimal(value: Double): Double = (value * 10.0).roundToLong() / 10.0
}
