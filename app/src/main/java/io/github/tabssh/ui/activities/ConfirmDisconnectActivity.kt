package io.github.tabssh.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                // Cancel the notification immediately for instant visual
                // feedback — the async disconnect chain below can take
                // several seconds (JSch socket teardown) and should never
                // block the UI or leave a stale notification.
                io.github.tabssh.utils.NotificationHelper.cancelHostNotification(
                    this, profileId
                )
                // Dispatch all blocking work (JSch session.disconnect() is
                // network I/O) to IO to avoid ANR on the main thread.
                // Use applicationScope (not lifecycleScope) so the coroutine
                // survives the activity being destroyed by finish() below.
                app.applicationScope.launch(Dispatchers.IO) {
                    try {
                        val tab = app.tabManager.getAllTabs()
                            .find { it.profile.id == profileId }
                        if (tab != null) {
                            // Disconnect the SSHTab (closes bridge, channels,
                            // mosh PTY, telnet — all connection types).
                            tab.disconnect()
                        }
                        // Always call closeConnectionIntentionally so the
                        // SSH/mosh session is removed from activeConnections
                        // and the service can stop cleanly. Safe to call even
                        // if the tab was not found (handles already-dropped
                        // connections) or if tab.disconnect() already cleaned
                        // up (idempotent in SSHSessionManager).
                        try {
                            app.sshSessionManager.closeConnectionIntentionally(profileId)
                        } catch (_: Exception) { }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Disconnect failed for $profileId", e)
                    }
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
