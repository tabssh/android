package io.github.tabssh.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.NotificationHelper
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

        /** Unique work name used with [ExistingPeriodicWorkPolicy.KEEP]. */
        const val WORK_NAME = "renewal_reminder_check"

        /**
         * Enqueue (or keep) the periodic worker. Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.KEEP] is idempotent. Runs once a day;
         * purely local DB reads and local notifications, so no network
         * constraint is required — only battery-not-low, matching the other
         * background-monitoring workers' posture.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<RenewalReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Logger.d(TAG, "Periodic renewal reminder check scheduled (1 day, not-low-battery)")
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
            val daysRemaining = (expiration - now) / dayMs
            if (daysRemaining > domain.reminderDaysBefore) continue
            if (domain.lastReminderSentAt == expiration) continue
            // Renewal already passed by more than the reminder window without
            // being updated (e.g. auto-renew handled it elsewhere) — still
            // worth one reminder, but don't let stale expired rows re-fire
            // every single day forever once past due.
            if (daysRemaining < -domain.reminderDaysBefore) continue

            Logger.i(TAG, "Domain '${domain.domainName}' renewal reminder: $daysRemaining day(s) remaining")
            NotificationHelper.notifyDomainRenewal(appContext, domain.id, domain.domainName, daysRemaining)
            db.domainDao().update(domain.copy(lastReminderSentAt = expiration, modifiedAt = now))
        }

        val vpsHosts = db.vpsHostDao().getAllWithRenewal()
        for (host in vpsHosts) {
            val renewal = host.renewalDate ?: continue
            val daysRemaining = (renewal - now) / dayMs
            if (daysRemaining > host.reminderDaysBefore) continue
            if (host.lastReminderSentAt == renewal) continue
            if (daysRemaining < -host.reminderDaysBefore) continue

            Logger.i(TAG, "VPS host '${host.hostname}' renewal reminder: $daysRemaining day(s) remaining")
            NotificationHelper.notifyVpsRenewal(appContext, host.id, host.hostname, daysRemaining)
            db.vpsHostDao().update(host.copy(lastReminderSentAt = renewal, modifiedAt = now))
        }

        Logger.d(TAG, "Renewal reminder check complete (${domains.size} domain(s), ${vpsHosts.size} VPS host(s) evaluated)")
        return Result.success()
    }
}
