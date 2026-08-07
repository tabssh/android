package io.github.tabssh.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
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
        val bundle = intent.getBundleExtra(LocalePlugin.EXTRA_BUNDLE)
        if (!LocalePlugin.isBundleValid(bundle)) {
            Logger.w("LocaleFireReceiver", "Rejected invalid plugin bundle")
            return
        }
        requireNotNull(bundle)
        val action = bundle.getString(LocalePlugin.BUNDLE_KEY_ACTION) ?: return

        val data = Data.Builder()
            .putString(TaskerWorker.KEY_ACTION, action)
            .putString(TaskerWorker.KEY_CONNECTION_ID, bundle.getString(LocalePlugin.BUNDLE_KEY_CONNECTION_ID))
            .putString(TaskerWorker.KEY_CONNECTION_NAME, bundle.getString(LocalePlugin.BUNDLE_KEY_CONNECTION_NAME))
            .putString(TaskerWorker.KEY_COMMAND, bundle.getString(LocalePlugin.BUNDLE_KEY_COMMAND))
            .putString(TaskerWorker.KEY_KEYS, bundle.getString(LocalePlugin.BUNDLE_KEY_KEYS))
            .putBoolean(
                TaskerWorker.KEY_WAIT_FOR_RESULT,
                bundle.getBoolean(LocalePlugin.BUNDLE_KEY_WAIT_FOR_RESULT, false)
            )
            .putLong(TaskerWorker.KEY_TIMEOUT_MS, TaskerWorker.DEFAULT_TIMEOUT_MS)
            .build()

        Logger.d("LocaleFireReceiver", "Enqueuing plugin action $action")
        val request = OneTimeWorkRequestBuilder<TaskerWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
