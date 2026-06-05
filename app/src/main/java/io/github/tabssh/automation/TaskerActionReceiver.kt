package io.github.tabssh.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.tabssh.utils.logging.Logger

/**
 * Replaces the previous `TaskerIntentService : JobIntentService` entry
 * point. The system delivers Tasker action broadcasts here; we marshal
 * the intent extras into [Data] and hand them to [TaskerWorker], which
 * runs the actual SSH work under WorkManager (so it survives the
 * process going to background once the broadcast returns).
 *
 * The signature-level `io.github.tabssh.permission.TASKER` is enforced
 * in `AndroidManifest.xml` on the `<receiver>` element, same as it was
 * on the old `<service>`.
 */
class TaskerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Logger.d("TaskerActionReceiver", "Received $action")

        // ConnectionProfile.id is a UUID String, not a Long. Accept either
        // form on the wire (older Tasker tasks may have written numeric
        // values) and prefer the string extra when present.
        val connectionIdExtra: String? = intent.getStringExtra(TaskerWorker.KEY_CONNECTION_ID)
            ?: intent.getLongExtra(TaskerWorker.KEY_CONNECTION_ID, -1L)
                .takeIf { it > 0 }?.toString()

        // Length-limit free-form strings so a malicious Tasker integration
        // cannot embed a megabyte-sized blob in WorkManager input data
        // (capped at ~10 KB by androidx.work, but better to fail fast).
        val command = intent.getStringExtra(TaskerWorker.KEY_COMMAND)?.take(8192)
        val keys = intent.getStringExtra(TaskerWorker.KEY_KEYS)?.take(1024)
        val name = intent.getStringExtra(TaskerWorker.KEY_CONNECTION_NAME)?.take(256)

        val data = Data.Builder()
            .putString(TaskerWorker.KEY_ACTION, action)
            .putString(TaskerWorker.KEY_CONNECTION_ID, connectionIdExtra)
            .putString(TaskerWorker.KEY_CONNECTION_NAME, name)
            .putString(TaskerWorker.KEY_COMMAND, command)
            .putString(TaskerWorker.KEY_KEYS, keys)
            .putBoolean(TaskerWorker.KEY_WAIT_FOR_RESULT, intent.getBooleanExtra(TaskerWorker.KEY_WAIT_FOR_RESULT, false))
            .putLong(TaskerWorker.KEY_TIMEOUT_MS, intent.getLongExtra(TaskerWorker.KEY_TIMEOUT_MS, TaskerWorker.DEFAULT_TIMEOUT_MS))
            .build()

        val request = OneTimeWorkRequestBuilder<TaskerWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
