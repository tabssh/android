package io.github.tabssh.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.tabssh.utils.logging.Logger

/**
 * Locale/Tasker plugin fire receiver (ACTION_FIRE_SETTING). Exported
 * without a permission — the Locale plugin protocol requires the host
 * app (Tasker/Locale/Automate, arbitrary signature) to reach it. The
 * attack surface is bounded instead: the bundle is strictly validated
 * ([LocalePlugin.isBundleValid] — known action, capped lengths), and
 * every action still passes [TaskerWorker]'s runtime gates (integration
 * enabled in settings, optional device-unlock requirement, per-connection
 * allowlist). A caller can never execute anything the user has not both
 * configured a profile for and enabled Tasker access to.
 */
class LocaleFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalePlugin.ACTION_FIRE_SETTING) return
        val data = try {
            LocalePlugin.toWorkerData(intent.getBundleExtra(LocalePlugin.EXTRA_BUNDLE))
        } catch (_: RuntimeException) {
            null
        }
        if (data == null) {
            Logger.w("LocaleFireReceiver", "Rejected invalid or oversized plugin bundle")
            return
        }
        val action = data.getString(TaskerWorker.KEY_ACTION)

        Logger.d("LocaleFireReceiver", "Enqueuing plugin action $action")
        val request = OneTimeWorkRequestBuilder<TaskerWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
