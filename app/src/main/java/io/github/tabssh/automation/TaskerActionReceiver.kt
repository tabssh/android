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

        val data = try {
            val connectionId = intent.getStringExtra(TaskerWorker.KEY_CONNECTION_ID)
                ?: intent.getLongExtra(TaskerWorker.KEY_CONNECTION_ID, -1L)
                    .takeIf { it > 0 }?.toString()
            TaskerInputData.create(
                action = action,
                connectionId = connectionId,
                connectionName = intent.getStringExtra(TaskerWorker.KEY_CONNECTION_NAME),
                command = intent.getStringExtra(TaskerWorker.KEY_COMMAND),
                keys = intent.getStringExtra(TaskerWorker.KEY_KEYS),
                waitForResult = intent.getBooleanExtra(TaskerWorker.KEY_WAIT_FOR_RESULT, false),
                timeoutMs = if (intent.hasExtra(TaskerWorker.KEY_TIMEOUT_MS)) {
                    intent.getLongExtra(TaskerWorker.KEY_TIMEOUT_MS, TaskerWorker.DEFAULT_TIMEOUT_MS)
                } else null
            )
        } catch (_: RuntimeException) {
            null
        }
        if (data == null) {
            Logger.w("TaskerActionReceiver", "Rejected invalid or oversized automation request")
            return
        }

        val request = OneTimeWorkRequestBuilder<TaskerWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
