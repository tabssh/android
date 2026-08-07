package io.github.tabssh.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tabssh.utils.logging.Logger

/**
 * Restarts saved port forwards after the device boots or the app is updated.
 *
 * Fires on BOOT_COMPLETED and MY_PACKAGE_REPLACED, then hands off to
 * [PortForwardStartupWorker] so the actual (network-dependent) work runs under
 * WorkManager constraints rather than in the receiver's short-lived context.
 *
 * Only forwards that are both enabled and marked auto-start are started; that
 * filtering lives in [io.github.tabssh.ssh.forwarding.PortForwardCoordinator].
 */
class PortForwardBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Logger.i("PortForwardBootReceiver", "Boot/update received (${intent.action}) — scheduling port-forward auto-start")
                PortForwardStartupWorker.schedule(context)
            }
        }
    }
}
