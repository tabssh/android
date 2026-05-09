package io.github.tabssh.ui.utils

import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.utils.logging.Logger

/**
 * Single entry point for opening a connection profile in a tab.
 *
 * Before this helper, every tap on a connection (frequent list, full
 * connections list, history, widget, etc.) created a brand-new tab even
 * when an existing tab was already attached to that profile. The user
 * had no way to surface the running session — they'd end up with
 * duplicate tabs and (sometimes) duplicate authentication round-trips,
 * and their cursor history was effectively orphaned.
 *
 * This helper checks the shared `TabManager` for a tab whose profile id
 * matches. If one exists, the user is prompted to reattach (focus the
 * existing tab) or start a new tab. "Reattach" uses
 * `TabTerminalActivity.EXTRA_TAB_ID`, which `handleIntent` already
 * recognises (issue #165). "New connection" falls through to the
 * existing `createIntent` path.
 *
 * Widget / notification / history paths are intentionally NOT routed
 * through this — those are background-y entry points where a dialog
 * would be unexpected. The two interactive list-tap paths
 * (FrequentConnectionsFragment, ConnectionsFragment) are the ones that
 * should prompt.
 */
object ConnectionLauncher {

    fun launch(context: Context, profile: ConnectionProfile, autoConnect: Boolean = true) {
        val app = context.applicationContext as TabSSHApplication

        val allTabs = app.tabManager.getAllTabs()
        val existingTab = allTabs.firstOrNull { it.profile.id == profile.id }

        // A pooled SSH session can outlive its UI tab (foreground service keeps
        // it alive). Surface that too — a literal "tab" isn't required for the
        // user's mental model of "this host is already connected".
        val pooledConnection = app.sshSessionManager.getConnection(profile.id)
        val pooledAlive = pooledConnection?.isConnected() == true

        Logger.d(
            "ConnectionLauncher",
            "launch profile=${profile.id} (${profile.name}) tabs=${allTabs.size} " +
                "matchingTab=${existingTab != null} pooledAlive=$pooledAlive"
        )

        if (existingTab == null && !pooledAlive) {
            context.startActivity(TabTerminalActivity.createIntent(context, profile, autoConnect))
            return
        }

        val message = if (existingTab != null) {
            "There is already an active session for this host. Reattach to it, or start a new connection?"
        } else {
            "A background SSH session is still connected to this host. Reattach, or start a new connection?"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(profile.name)
            .setMessage(message)
            .setPositiveButton("Reattach") { _, _ ->
                val intent = if (existingTab != null) {
                    Intent(context, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, existingTab.tabId)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                } else {
                    // Pooled-but-no-tab: createIntent + autoConnect=true triggers
                    // SSHSessionManager.connectToServer which reuses the pooled
                    // session (see SSHSessionManager.createConnection — pool hit
                    // returns the existing SSHConnection without re-authenticating).
                    TabTerminalActivity.createIntent(context, profile, true)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("New connection") { _, _ ->
                context.startActivity(TabTerminalActivity.createIntent(context, profile, autoConnect))
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
}
