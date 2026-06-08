package io.github.tabssh.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger

/**
 * Transparent dialog activity that confirms an SSH disconnect requested
 * from the notification shade.
 *
 * Android does not allow showing an AlertDialog directly from a
 * BroadcastReceiver. The "Disconnect" notification action therefore
 * launches this lightweight transparent Activity instead of a receiver.
 * The Activity shows a confirmation dialog immediately in [onCreate],
 * tears itself down either way, and never appears in the task stack.
 *
 * Theme: [Theme.TabSSH.Transparent] — the window background is clear so
 * the notification shade / underlying app shows through while the dialog
 * is visible.
 */
class ConfirmDisconnectActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ConfirmDisconnectActivity"

        /** String — the `id` field of the [ConnectionProfile] to close. */
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            Logger.w(TAG, "No profile_id in intent — nothing to disconnect")
            finish()
            return
        }

        val app = applicationContext as TabSSHApplication
        val conn = app.sshSessionManager.getConnection(profileId)
        val displayName = conn?.profile?.getDisplayName() ?: profileId

        AlertDialog.Builder(this)
            .setTitle("Disconnect?")
            .setMessage("Close the SSH connection to $displayName?")
            .setPositiveButton("Disconnect") { _, _ ->
                Logger.i(TAG, "User confirmed disconnect for profile $profileId")
                try {
                    // Primary path: disconnect via TabManager so the SSHTab
                    // state is updated and the notification clears. The old
                    // sshSessionManager.closeConnectionIntentionally path only
                    // works if the connection is still in activeConnections,
                    // which it won't be after a natural disconnect or reconnect.
                    val tab = app.tabManager.getAllTabs()
                        .find { it.profile.id == profileId }
                    if (tab != null) {
                        tab.disconnect()
                        // Also mark intentional in session manager so
                        // TabTerminalActivity doesn't auto-reconnect.
                        try { app.sshSessionManager.closeConnectionIntentionally(profileId) }
                        catch (_: Exception) { }
                    } else {
                        // No active tab — connection may have already dropped.
                        // Force-clear the notification so it doesn't linger.
                        app.sshSessionManager.closeConnectionIntentionally(profileId)
                        io.github.tabssh.utils.NotificationHelper.cancelHostNotification(
                            this, profileId
                        )
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Disconnect failed for $profileId", e)
                    Toast.makeText(this, "Failed to disconnect: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }
}
