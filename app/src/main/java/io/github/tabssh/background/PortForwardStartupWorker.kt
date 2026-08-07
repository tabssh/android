package io.github.tabssh.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger

/**
 * One-shot worker that starts every enabled, auto-start port forward once the
 * network is available after a boot or app update.
 *
 * A [OneTimeWorkRequest] (not periodic) is correct here: forwards need to come
 * up exactly once when connectivity returns, and stay up as long-lived tunnels.
 * The CONNECTED constraint defers the work until the device actually has a
 * network, so we don't burn retries dialing an unreachable server at boot.
 */
class PortForwardStartupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? TabSSHApplication ?: return Result.success()
        return try {
            app.portForwardCoordinator.startAllAutoStart()
            Result.success()
        } catch (e: Exception) {
            Logger.e("PortForwardStartupWorker", "Auto-start of port forwards failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "port_forward_autostart"

        /**
         * Enqueue the one-shot auto-start work, waiting for network. REPLACE
         * keeps the latest request if boot and package-replaced both fire.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PortForwardStartupWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
