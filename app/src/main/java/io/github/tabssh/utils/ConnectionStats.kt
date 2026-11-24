package io.github.tabssh.utils

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records one successful connect to a saved host (count and last-connected stamp) for paths that
 * open a session without going through the main terminal tab: split panes, pane groups, SFTP,
 * port forwarding, Tasker and the cluster runner. Without it a host used only that way shows no
 * history and drops out of Recently connected and Most used.
 */
suspend fun TabSSHApplication.recordConnection(profileId: String) {
    try {
        withContext(Dispatchers.IO) { database.connectionDao().updateLastConnected(profileId) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w("ConnectionStats", "Could not record connection for $profileId: ${e.message}")
    }
}
