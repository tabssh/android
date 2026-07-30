package io.github.tabssh.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transparent dialog activity that confirms an SSH tab disconnect
 * requested from the notification shade.
 *
 * Android does not allow showing an AlertDialog directly from a
 * BroadcastReceiver. The "Disconnect" notification action therefore
 * launches this lightweight transparent Activity instead of a receiver.
 * The Activity shows a confirmation dialog immediately in [onCreate],
 * tears itself down either way, and never appears in the task stack.
 *
 * Tab-scoped (per-tab notifications): the action carries [EXTRA_TAB_ID],
 * so confirming closes exactly one tab. When other tabs still share the
 * same profile's SSH session, only this tab's channel is closed (Issue
 * #163); the underlying session is torn down only when the last tab for
 * that profile goes away.
 *
 * Theme: [Theme.TabSSH.Transparent] — the window background is clear so
 * the notification shade / underlying app shows through while the dialog
 * is visible.
 */
class ConfirmDisconnectActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ConfirmDisconnectActivity"

        /** String — the [io.github.tabssh.ui.tabs.SSHTab.tabId] of the tab to close. */
        const val EXTRA_TAB_ID = "tab_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tabId = intent.getStringExtra(EXTRA_TAB_ID)
        if (tabId.isNullOrBlank()) {
            Logger.w(TAG, "No tab_id in intent — nothing to disconnect")
            finish()
            return
        }

        val app = applicationContext as TabSSHApplication
        val tab = app.tabManager.getAllTabs().find { it.tabId == tabId }
        if (tab == null) {
            // Tab already closed (stale notification action) — just make
            // sure the notification itself is gone and bail out quietly.
            io.github.tabssh.utils.NotificationHelper.cancelTabNotification(this, tabId)
            Logger.w(TAG, "Tab $tabId no longer exists — cancelled stale notification")
            finish()
            return
        }
        val displayName = tab.getDisplayTitle()

        AlertDialog.Builder(this)
            .setTitle("Disconnect?")
            .setMessage("Close the SSH session \"$displayName\"?")
            .setPositiveButton("Disconnect") { _, _ ->
                Logger.i(TAG, "User confirmed disconnect for tab $tabId")
                // Cancel the notification immediately for instant visual
                // feedback — the async disconnect chain below can take
                // several seconds (JSch socket teardown) and should never
                // block the UI or leave a stale notification.
                io.github.tabssh.utils.NotificationHelper.cancelTabNotification(
                    this, tabId
                )
                // Dispatch all blocking work (JSch channel/session teardown is
                // network I/O) to IO to avoid ANR on the main thread.
                // Use applicationScope (not lifecycleScope) so the coroutine
                // survives the activity being destroyed by finish() below.
                app.applicationScope.launch(Dispatchers.IO) {
                    try {
                        // Close exactly this tab (cleanup() closes its own
                        // channel, bridge, mosh PTY — all connection types —
                        // and fires TabManagerListener.onTabClosed so the
                        // service updates its notifications and FG anchor).
                        val closed = app.tabManager.closeTabById(tabId)
                        val profileId = (closed ?: tab).profile.id
                        // Tear the shared SSH session down only when no other
                        // tab still uses this profile (Issue #163 siblings).
                        val stillShared = app.tabManager.getAllTabs()
                            .any { it.profile.id == profileId }
                        if (!stillShared) {
                            try {
                                app.sshSessionManager.closeConnectionIntentionally(profileId)
                            } catch (_: Exception) { }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Disconnect failed for tab $tabId", e)
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
