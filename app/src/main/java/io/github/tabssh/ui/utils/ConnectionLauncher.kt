package io.github.tabssh.ui.utils

import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.activities.TabTerminalActivity

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
        val existingTab = app.tabManager.getAllTabs().firstOrNull { it.profile.id == profile.id }

        if (existingTab == null) {
            context.startActivity(TabTerminalActivity.createIntent(context, profile, autoConnect))
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(profile.name)
            .setMessage("There is already an active session for this host. Reattach to it, or start a new connection?")
            .setPositiveButton("Reattach") { _, _ ->
                val intent = Intent(context, TabTerminalActivity::class.java).apply {
                    putExtra(TabTerminalActivity.EXTRA_TAB_ID, existingTab.tabId)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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
