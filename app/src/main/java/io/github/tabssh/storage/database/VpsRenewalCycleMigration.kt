package io.github.tabssh.storage.database

import io.github.tabssh.tracker.VpsMarkdownImportExport
import io.github.tabssh.utils.logging.Logger

/**
 * One-time data migration for the VPS Hosting Tracker: split the billing cycle
 * out of every existing `vps_hosts.renewal_raw` value.
 *
 * Rows imported before [VpsMarkdownImportExport.splitRenewal] existed stored
 * the whole renewal field verbatim, so the tracker rendered "April 30, 2027,
 * triennially" where only the date belongs — the cadence is its own column and
 * its own edit field. This rewrites `renewal_raw` to the date text alone and
 * fills `billing_cycle` from what was stripped.
 *
 * A row whose renewal text carries no recognizable cycle word is left exactly
 * as it is, and an existing `billing_cycle` is never overwritten — the stored
 * value wins over anything inferred from the text. No schema change is
 * involved, so this runs in app code rather than a raw-SQL Room migration.
 *
 * Idempotent: after a run no `renewal_raw` contains a cycle word, so a second
 * pass finds nothing to change.
 */
object VpsRenewalCycleMigration {

    private const val TAG = "VpsRenewalCycleMigration"

    /**
     * Run the migration against [db]. Returns the number of rows rewritten.
     */
    suspend fun run(db: TabSSHDatabase): Int {
        val dao = db.vpsHostDao()
        var migrated = 0
        for (host in dao.getAllList()) {
            val parts = VpsMarkdownImportExport.splitRenewal(host.renewalRaw)
            if (parts.billingCycle == null) continue

            val cycle = host.billingCycle?.takeIf { it.isNotBlank() } ?: parts.billingCycle
            if (parts.dateText == host.renewalRaw && cycle == host.billingCycle) continue

            val renewalDate = parts.dateText?.let {
                VpsMarkdownImportExport.parseBestEffortDate(it, cycle)
            }
            dao.update(
                host.copy(
                    renewalRaw = parts.dateText,
                    renewalDate = renewalDate,
                    billingCycle = cycle,
                    modifiedAt = System.currentTimeMillis()
                )
            )
            migrated++
        }
        if (migrated > 0) {
            Logger.i(TAG, "Split the billing cycle out of $migrated VPS renewal field(s)")
        }
        return migrated
    }
}
