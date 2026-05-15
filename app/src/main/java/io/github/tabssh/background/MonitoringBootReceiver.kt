package io.github.tabssh.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import io.github.tabssh.utils.logging.Logger

/**
 * Ensures the periodic host-availability worker is re-registered after
 * device boot or app update.
 *
 * ## Why this is necessary
 *
 * WorkManager persists its work queue in its own Room database and normally
 * survives device reboots on stock Android. However:
 *
 *  - Several OEM ROM layers (Samsung One UI, Xiaomi MIUI, Huawei EMUI, etc.)
 *    aggressively clear the WorkManager DB or kill pending work as part of
 *    their own battery-optimization passes.
 *  - An OS upgrade or app reinstall can wipe the WorkManager DB entirely.
 *  - [MY_PACKAGE_REPLACED] fires after an app update so the new worker
 *    version is registered even if the old work item survived.
 *
 * Using [ExistingPeriodicWorkPolicy.KEEP] in [HostAvailabilityWorker.schedule]
 * means this call is idempotent: if the work item already exists and is
 * healthy, it is left untouched; only a missing item is recreated.
 *
 * ## Permissions required
 *
 * `android.permission.RECEIVE_BOOT_COMPLETED` — declared in AndroidManifest.
 */
class MonitoringBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MonitoringBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val monitoringOn = prefs.getBoolean("monitoring_enabled", true)
                if (monitoringOn) {
                    Logger.i(TAG, "Rescheduling host-availability worker after ${intent.action}")
                    HostAvailabilityWorker.schedule(context)
                } else {
                    Logger.d(TAG, "monitoring_enabled=false — not rescheduling")
                }
            }
        }
    }
}
