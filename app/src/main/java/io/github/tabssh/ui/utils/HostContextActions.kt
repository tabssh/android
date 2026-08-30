package io.github.tabssh.ui.utils

import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.TelnetHost
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.ui.activities.ConnectionEditActivity
import io.github.tabssh.ui.activities.SFTPActivity
import io.github.tabssh.ui.activities.TabTerminalActivity
import io.github.tabssh.ui.activities.VncHostEditActivity
import io.github.tabssh.ui.tabs.Tab
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-press context-menu actions (and the VNC "connect" flow) shared
 * between each Hosts sub-tab fragment (Ssh/Vnc/Telnet/ActiveHostsFragment)
 * and the unified search overlay in [io.github.tabssh.ui.fragments.ConnectionsFragment].
 *
 * Pulled out so a host/tab reached via search gets byte-for-byte the same
 * Connect / Edit / Duplicate / Delete behaviour — including tombstone
 * recording and orphan-FK cleanup on delete — as its own sub-tab list,
 * instead of a second, drift-prone copy of that logic.
 */
object HostContextActions {

    private const val TAG_SSH = "SshHostsFragment"
    private const val TAG_VNC = "VncHostsFragment"
    private const val TAG_TELNET = "TelnetHostsFragment"

    // ── SSH ──────────────────────────────────────────────────────────

    /** Order: Connect → Browse Files → Edit → Duplicate → Delete. */
    fun showSshConnectionMenu(fragment: Fragment, app: TabSSHApplication, connection: ConnectionProfile) {
        val items = arrayOf(
            fragment.getString(R.string.connect_button),
            fragment.getString(R.string.connections_menu_browse_files),
            fragment.getString(R.string.edit),
            fragment.getString(R.string.connections_menu_duplicate),
            fragment.getString(R.string.delete)
        )

        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(connection.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ConnectionLauncher.launch(fragment.requireContext(), connection)
                    1 -> openSftpBrowser(fragment, connection)
                    2 -> editSshConnection(fragment, connection)
                    3 -> duplicateSshConnection(fragment, app, connection)
                    4 -> confirmDeleteSshConnection(fragment, app, connection)
                }
            }
            .show()
    }

    private fun openSftpBrowser(fragment: Fragment, connection: ConnectionProfile) {
        Logger.d(TAG_SSH, "Opening SFTP browser for ${connection.name}")
        fragment.startActivity(SFTPActivity.createIntent(fragment.requireContext(), connection.id))
    }

    private fun editSshConnection(fragment: Fragment, connection: ConnectionProfile) {
        val intent = Intent(fragment.requireContext(), ConnectionEditActivity::class.java).apply {
            putExtra(ConnectionEditActivity.EXTRA_CONNECTION_ID, connection.id)
        }
        fragment.startActivity(intent)
    }

    private fun duplicateSshConnection(fragment: Fragment, app: TabSSHApplication, connection: ConnectionProfile) {
        fragment.lifecycleScope.launch {
            try {
                val duplicate = connection.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = fragment.getString(R.string.connections_duplicate_name_fmt, connection.name),
                    connectionCount = 0,
                    lastConnected = 0
                )
                app.database.connectionDao().insertConnection(duplicate)
                Logger.d(TAG_SSH, "Connection duplicated: ${duplicate.name}")
            } catch (e: Exception) {
                Logger.e(TAG_SSH, "Failed to duplicate connection", e)
            }
        }
    }

    private fun confirmDeleteSshConnection(fragment: Fragment, app: TabSSHApplication, connection: ConnectionProfile) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.connections_delete_title)
            .setMessage(fragment.getString(R.string.connections_delete_message_fmt, connection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                fragment.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.connectionDao().deleteConnection(connection)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.CONNECTION, connection.id)
                            // Clean up orphan soft-FK references left by this connection.
                            app.database.monitorSlotDao().deleteByConnectionId(connection.id)
                            app.database.hypervisorDao().clearLinkedConnectionId(connection.id)
                            // Keep the Panes registry and any saved pane-group membership accurate.
                            io.github.tabssh.storage.registry.ConnectableHostRegistry
                                .removeConnectionProfile(app.database, connection.id)
                            // clearPassword is suspend + IO-dispatched (KeyStore HAL round-trip).
                            try { app.securePasswordManager.clearPassword(connection.id) } catch (_: Exception) {}
                        }
                        Logger.d(TAG_SSH, "Connection deleted: ${connection.name}")
                    } catch (e: Exception) {
                        Logger.e(TAG_SSH, "Failed to delete connection", e)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── VNC ──────────────────────────────────────────────────────────

    fun showVncHostMenu(fragment: Fragment, app: TabSSHApplication, host: VncHost) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(host.name)
            .setItems(arrayOf(fragment.getString(R.string.edit), fragment.getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> editVncHost(fragment, host)
                    1 -> confirmDeleteVncHost(fragment, app, host)
                }
            }
            .show()
    }

    fun editVncHost(fragment: Fragment, host: VncHost) {
        fragment.startActivity(
            Intent(fragment.requireContext(), VncHostEditActivity::class.java).apply {
                putExtra(VncHostEditActivity.EXTRA_VNC_HOST_ID, host.id)
            }
        )
    }

    private fun confirmDeleteVncHost(fragment: Fragment, app: TabSSHApplication, host: VncHost) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.domain_delete_title, host.name))
            .setMessage(fragment.getString(R.string.vnc_host_delete_message))
            .setPositiveButton(fragment.getString(R.string.delete)) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.vncHostDao().deleteById(host.id)
                            app.securePasswordManager.clearPassword("vnc_host_${host.id}")
                            TombstoneRecorder.record(app, TombstoneRecorder.VNC_HOST, host.id)
                        }
                        Logger.d(TAG_VNC, "Deleted VNC host: ${host.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG_VNC, "Failed to delete VNC host", e)
                        if (!isFragmentAlive(fragment)) return@launch
                        android.widget.Toast.makeText(
                            fragment.requireContext(),
                            fragment.getString(R.string.domain_delete_failed_fmt, e.message),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .show()
    }

    /**
     * Same "resolve credentials, connect, create a [Tab.Vnc], focus it in
     * TabTerminalActivity" flow used by every VNC connect entry point.
     * [isConnecting]/[setConnecting] let each caller keep its own
     * double-tap guard (a connect is a suspend round-trip through the RFB
     * handshake, during which the row/result stays tappable).
     */
    fun connectToVncHost(
        fragment: Fragment,
        app: TabSSHApplication,
        host: VncHost,
        isConnecting: () -> Boolean,
        setConnecting: (Boolean) -> Unit
    ) {
        if (isConnecting()) return
        setConnecting(true)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (password, username) = withContext(Dispatchers.IO) {
                    val identityId = host.identityId
                    val hostPw = try {
                        app.securePasswordManager.retrievePassword("vnc_host_${host.id}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG_VNC, "Could not retrieve VNC host password: ${e.message}")
                        null
                    }
                    if (hostPw != null) {
                        val identityUsername = if (identityId != null) {
                            app.database.vncIdentityDao().getById(identityId)?.username
                        } else null
                        Pair(hostPw, identityUsername)
                    } else if (identityId != null) {
                        val identity = app.database.vncIdentityDao().getById(identityId)
                        val pw = try {
                            app.securePasswordManager.retrievePassword("vnc_identity_$identityId")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG_VNC, "Could not retrieve VNC identity password: ${e.message}")
                            null
                        }
                        Pair(pw, identity?.username)
                    } else {
                        Pair(null, null)
                    }
                }
                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    io.github.tabssh.hypervisor.vnc.VncDirectConnector.connect(host, password, username, fragment.requireContext())
                }
                val tab = app.tabManager.createVncTab(host)
                if (tab == null) {
                    try { rfbClient.stop() } catch (e: Exception) {
                        Logger.d(TAG_VNC, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    if (!isFragmentAlive(fragment)) return@launch
                    android.widget.Toast.makeText(fragment.requireContext(), R.string.virt_viewer_max_tabs, android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                tab.rfbClient = rfbClient
                tab.setConnectionState(io.github.tabssh.ssh.connection.ConnectionState.CONNECTED)
                withContext(Dispatchers.IO) {
                    app.database.vncHostDao().updateLastConnected(host.id, System.currentTimeMillis())
                }
                if (!isFragmentAlive(fragment)) return@launch
                fragment.startActivity(
                    Intent(fragment.requireContext(), TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG_VNC, "Failed to connect to VNC host '${host.name}'", e)
                if (isFragmentAlive(fragment)) {
                    android.widget.Toast.makeText(
                        fragment.requireContext(),
                        fragment.getString(R.string.virt_viewer_connect_failed, e.message),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                setConnecting(false)
            }
        }
    }

    // ── Telnet ───────────────────────────────────────────────────────

    fun showTelnetHostMenu(fragment: Fragment, app: TabSSHApplication, host: TelnetHost) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(host.name)
            .setItems(arrayOf(fragment.getString(R.string.edit), fragment.getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> editTelnetHost(fragment, host)
                    1 -> confirmDeleteTelnetHost(fragment, app, host)
                }
            }
            .show()
    }

    fun editTelnetHost(fragment: Fragment, host: TelnetHost) {
        fragment.startActivity(ConnectionEditActivity.createTelnetIntent(fragment.requireContext(), host.id))
    }

    private fun confirmDeleteTelnetHost(fragment: Fragment, app: TabSSHApplication, host: TelnetHost) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.domain_delete_title, host.name))
            .setMessage(fragment.getString(R.string.telnet_host_delete_message))
            .setPositiveButton(fragment.getString(R.string.delete)) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.telnetHostDao().deleteById(host.id)
                            app.securePasswordManager.clearPassword(host.id)
                            TombstoneRecorder.record(app, TombstoneRecorder.TELNET_HOST, host.id)
                        }
                        Logger.d(TAG_TELNET, "Deleted Telnet host: ${host.name}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG_TELNET, "Failed to delete Telnet host", e)
                        if (!isFragmentAlive(fragment)) return@launch
                        android.widget.Toast.makeText(
                            fragment.requireContext(),
                            fragment.getString(R.string.domain_delete_failed_fmt, e.message),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .show()
    }

    // ── Active tabs ──────────────────────────────────────────────────

    /**
     * Long-press parity with ActiveHostsFragment's swipe-to-disconnect
     * gesture: same "Open" / "Disconnect" pair, reachable without a
     * directional swipe.
     */
    fun showActiveTabMenu(fragment: Fragment, anchor: android.view.View, app: TabSSHApplication, tab: Tab) {
        val popup = android.widget.PopupMenu(anchor.context, anchor)
        popup.menu.add(R.string.active_host_menu_open)
        popup.menu.add(R.string.active_host_menu_disconnect)
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                anchor.context.getString(R.string.active_host_menu_open) -> openActiveTab(fragment, app, tab)
                anchor.context.getString(R.string.active_host_menu_disconnect) -> app.tabManager.closeTabByIdSealed(tab.tabId)
            }
            true
        }
        popup.show()
    }

    fun openActiveTab(fragment: Fragment, app: TabSSHApplication, tab: Tab) {
        app.tabManager.switchToTabById(tab.tabId)
        fragment.startActivity(
            Intent(fragment.requireContext(), TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
            }
        )
    }

    private fun isFragmentAlive(fragment: Fragment): Boolean =
        fragment.isAdded && !fragment.requireActivity().isFinishing && !fragment.requireActivity().isDestroyed
}
