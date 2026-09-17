package io.github.tabssh.storage.database

import io.github.tabssh.tracker.VpsMarkdownImportExport
import io.github.tabssh.utils.logging.Logger

/**
 * One-time data migration for the VPS Hosting Tracker: bring every stored
 * renewal field into the shape the edit screen now writes.
 *
 * - A billing cycle embedded in `renewal_raw` ("April 30, 2027, triennially")
 *   moves to `billing_cycle`; an existing `billing_cycle` is never overwritten.
 * - `billing_cycle` is rewritten to its canonical form ("yearly" → "1 year").
 * - `renewal_raw` becomes "Month d, yyyy" whenever a date can be read from it,
 *   and a free host ("Free/Never", "N/A") becomes January 1st of next year,
 *   renewing yearly (see [VpsMarkdownImportExport.normalizeRenewal]).
 * - Text with no readable date ("TBD") keeps its text; if the row already had
 *   a parsed date, the text is replaced with that date.
 *
 * No schema change is involved, so this runs in app code rather than a raw-SQL
 * Room migration. Idempotent: a second pass finds nothing to change.
 */
object VpsRenewalCycleMigration {

    private const val TAG = "VpsRenewalCycleMigration"

    /**
     * Run the migration against [db]. Returns the number of rows rewritten.
     */
    suspend fun run(db: TabSSHDatabase, now: Long = System.currentTimeMillis()): Int {
        val dao = db.vpsHostDao()
        var migrated = 0
        for (host in dao.getAllList()) {
            val parts = VpsMarkdownImportExport.splitRenewal(host.renewalRaw)
            val cycle = host.billingCycle?.takeIf { it.isNotBlank() } ?: parts.billingCycle
            val fields = VpsMarkdownImportExport.normalizeRenewal(parts.dateText, cycle, now)
            val existingDate = host.renewalDate
            val renewalDate = fields.renewalDate ?: existingDate
            val renewalRaw = if (fields.renewalDate == null && existingDate != null) {
                VpsMarkdownImportExport.formatRenewalDate(existingDate)
            } else {
                fields.renewalRaw
            }
            if (renewalRaw == host.renewalRaw && renewalDate == host.renewalDate && fields.billingCycle == host.billingCycle) continue

            dao.update(
                host.copy(
                    renewalRaw = renewalRaw,
                    renewalDate = renewalDate,
                    billingCycle = fields.billingCycle,
                    lastReminderSentAt = if (renewalDate == host.renewalDate) host.lastReminderSentAt else null,
                    modifiedAt = System.currentTimeMillis()
                )
            )
            migrated++
        }
        if (migrated > 0) {
            Logger.i(TAG, "Normalized the renewal date and billing cycle of $migrated VPS host(s)")
        }
        return migrated
    }
}
