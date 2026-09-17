package io.github.tabssh.tracker

import android.content.Context
import io.github.tabssh.R

/** Localized display labels for [BillingCycle]: "Monthly", "Annually", "Every 3 years", "6 times a year". */
object BillingCycleText {

    fun label(context: Context, cycle: BillingCycle): String {
        val res = context.resources
        return when (cycle.mode) {
            BillingCycle.Mode.TIMES_PER -> {
                val plural = if (cycle.period == BillingCycle.Period.YEAR) {
                    R.plurals.billing_cycle_times_a_year
                } else {
                    R.plurals.billing_cycle_times_a_month
                }
                res.getQuantityString(plural, cycle.count, cycle.count)
            }
            BillingCycle.Mode.EVERY -> if (cycle.count == 1) {
                context.getString(
                    when (cycle.period) {
                        BillingCycle.Period.DAY -> R.string.billing_cycle_daily
                        BillingCycle.Period.WEEK -> R.string.billing_cycle_weekly
                        BillingCycle.Period.MONTH -> R.string.billing_cycle_monthly
                        BillingCycle.Period.YEAR -> R.string.billing_cycle_annually
                    }
                )
            } else {
                val plural = when (cycle.period) {
                    BillingCycle.Period.DAY -> R.plurals.billing_cycle_every_days
                    BillingCycle.Period.WEEK -> R.plurals.billing_cycle_every_weeks
                    BillingCycle.Period.MONTH -> R.plurals.billing_cycle_every_months
                    BillingCycle.Period.YEAR -> R.plurals.billing_cycle_every_years
                }
                res.getQuantityString(plural, cycle.count, cycle.count)
            }
        }
    }

    /** Label for a stored `billingCycle`; unrecognized text is shown as written, blank as null. */
    fun label(context: Context, stored: String?): String? {
        val text = stored?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return BillingCycle.parse(text)?.let { label(context, it) } ?: text
    }
}
