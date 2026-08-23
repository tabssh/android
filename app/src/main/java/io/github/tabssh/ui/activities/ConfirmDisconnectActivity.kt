package io.github.tabssh.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
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
        // Sealed-aware lookup: the Disconnect action is carried by SSH AND
        // graphical (VNC/console) per-tab notifications, so the tab can be
        // any kind.
        val tab = app.tabManager.getAllTabsSealed().find { it.tabId == tabId }
        if (tab == null) {
            // Tab already closed (stale notification action) — just make
            // sure the notification itself is gone and bail out quietly.
            io.github.tabssh.utils.NotificationHelper.cancelTabNotification(this, tabId)
            Logger.w(TAG, "Tab $tabId no longer exists — cancelled stale notification")
            finish()
            return
        }
        val displayName: String
        val sessionKind: String
        when (tab) {
            is io.github.tabssh.ui.tabs.Tab.Ssh -> {
                displayName = tab.sshTab.getDisplayTitle()
                sessionKind = "SSH"
            }
            is io.github.tabssh.ui.tabs.Tab.Vnc -> {
                displayName = tab.vncTab.getDisplayTitle()
                sessionKind = "VNC"
            }
            is io.github.tabssh.ui.tabs.Tab.Console -> {
                displayName = tab.consoleTab.getDisplayTitle()
                sessionKind = "console"
            }
            is io.github.tabssh.ui.tabs.Tab.Panes -> {
                displayName = tab.panesTab.getDisplayTitle()
                sessionKind = "panes"
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_disconnect_title))
            .setMessage(getString(R.string.confirm_disconnect_message, sessionKind, displayName))
            .setPositiveButton(getString(R.string.locale_action_disconnect)) { _, _ ->
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
                        // Close exactly this tab (cleanup() stops its own
                        // channel/bridge/mosh PTY for SSH, or the RFB/SPICE
                        // client for graphical tabs — and fires the matching
                        // TabManagerListener close callback so the service
                        // and activity update their state).
                        val closed = app.tabManager.closeTabByIdSealed(tabId)
                        val sshTab = ((closed ?: tab) as? io.github.tabssh.ui.tabs.Tab.Ssh)?.sshTab
                        if (sshTab != null) {
                            val profileId = sshTab.profile.id
                            // Tear the shared SSH session down only when no other
                            // tab still uses this profile (Issue #163 siblings).
                            val stillShared = app.tabManager.getAllTabs()
                                .any { it.profile.id == profileId }
                            if (!stillShared) {
                                try {
                                    app.sshSessionManager.closeConnectionIntentionally(profileId)
                                } catch (_: Exception) { }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "Disconnect failed for tab $tabId", e)
                    }
                }
                finish()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }
}
