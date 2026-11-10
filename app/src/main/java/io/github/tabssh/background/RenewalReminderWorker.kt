package io.github.tabssh.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.RenewalUrgency
import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Daily background check for tracked domain expirations and VPS renewals.
 *
 * Runs as a [androidx.work.PeriodicWorkRequest] via WorkManager, mirroring
 * [HostAvailabilityWorker]'s gating philosophy: every skip condition returns
 * [Result.success] (never retry/failure) so WorkManager's schedule stays
 * healthy even when there's nothing to do.
 *
 * For every [io.github.tabssh.storage.database.entities.Domain] with a set
 * `expirationDate`, and every [io.github.tabssh.storage.database.entities.VpsHost]
 * with a set `renewalDate`, fires a reminder notification once the renewal
 * falls within that row's `reminderDaysBefore` window — deduped against
 * `lastReminderSentAt` so a reminder for the same renewal date only fires
 * once, not once per day until the date rolls over. When `lastReminderSentAt`
 * predates the current renewal date (e.g. the user edited the date forward,
 * or a recurring renewal advanced past a prior reminder), the dedupe resets
 * automatically because the comparison is always against the *current*
 * renewal timestamp, not merely "was any reminder ever sent."
 */
class RenewalReminderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "RenewalReminderWorker"

        /** Unique work name used with [ExistingPeriodicWorkPolicy.UPDATE]. */
        const val WORK_NAME = "renewal_reminder_check"

        /**
         * Enqueue (or keep) the periodic worker. Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.UPDATE] swaps in the current definition without resetting the schedule. Runs once a day;
         * purely local DB reads and local notifications, so no constraints
         * (a battery-not-low constraint could defer reminders for days).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RenewalReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Logger.d(TAG, "Periodic renewal reminder check scheduled (1 day)")
        }

        /** Cancel the periodic worker (e.g., when the renewal reminders toggle is off). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.d(TAG, "Periodic renewal reminder check cancelled")
        }
    }

    override suspend fun doWork(): Result {
        val app = TabSSHApplication.get()
        val preferencesManager = app.preferencesManager

        if (!preferencesManager.isRenewalRemindersEnabled()) {
            Logger.d(TAG, "Skipping run: renewal_reminders_enabled = false (or notifications master switch off)")
            return Result.success()
        }

        val db = app.database
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)

        val domains = db.domainDao().getAllWithExpiration()
        for (domain in domains) {
            val expiration = domain.expirationDate ?: continue
            // A canceled row is awaiting its 30-day sweep; nothing to remind about.
            if (domain.canceledAt != null) continue
            // floorDiv so a date a few hours past reports -1 (overdue), not 0 (today).
            val daysRemaining = Math.floorDiv(expiration - now, dayMs)
            if (daysRemaining > domain.reminderDaysBefore) continue
            // One reminder per due date (past-due included): the dedupe below
            // stops re-firing, and renewing/editing the date resets it.
            if (domain.lastReminderSentAt == expiration) continue

            Logger.i(TAG, "Domain '${domain.domainName}' renewal reminder: $daysRemaining day(s) remaining")
            NotificationHelper.notifyDomainRenewal(appContext, domain.id, domain.domainName, daysRemaining)
            db.domainDao().update(domain.copy(lastReminderSentAt = expiration, modifiedAt = now))
        }

        val vpsHosts = db.vpsHostDao().getAllWithRenewal()
        for (host in vpsHosts) {
            // A recurring host's stored renewalDate is the last known due
            // date, not necessarily the next one — project it forward by
            // billingCycle first (same as the tracker list/detail screens),
            // so a monthly host that's months past its stored date still
            // gets reminded for its actual next due date instead of being
            // silently skipped as "too far overdue" below.
            val renewal = RenewalUrgency.effectiveDate(host.renewalDate, host.billingCycle, now) ?: continue
            if (host.canceledAt != null) continue
            val daysRemaining = Math.floorDiv(renewal - now, dayMs)
            if (daysRemaining > host.reminderDaysBefore) continue
            if (host.lastReminderSentAt == renewal) continue

            Logger.i(TAG, "VPS host '${host.hostname}' renewal reminder: $daysRemaining day(s) remaining")
            NotificationHelper.notifyVpsRenewal(appContext, host.id, host.hostname, daysRemaining)
            db.vpsHostDao().update(host.copy(lastReminderSentAt = renewal, modifiedAt = now))
        }

        Logger.d(TAG, "Renewal reminder check complete (${domains.size} domain(s), ${vpsHosts.size} VPS host(s) evaluated)")
        return Result.success()
    }
}
