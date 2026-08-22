package io.github.tabssh.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.spice.SpiceClient
import io.github.tabssh.hypervisor.viewer.SpiceUri
import io.github.tabssh.hypervisor.viewer.VirtViewerConnection
import io.github.tabssh.hypervisor.viewer.VirtViewerFile
import io.github.tabssh.hypervisor.viewer.VirtViewerParseException
import io.github.tabssh.hypervisor.viewer.VirtViewerType
import io.github.tabssh.hypervisor.viewer.VncUri
import io.github.tabssh.hypervisor.vnc.VncAuthProbe
import io.github.tabssh.hypervisor.vnc.VncDirectConnector
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.ui.tabs.ConsoleConnectParams
import io.github.tabssh.ui.tabs.HypervisorConsoleType
import io.github.tabssh.utils.TerminalLinkClassifier
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * System-wide entry point for ssh://, sftp://, spice://, spice+tls:// and
 * vnc:// links tapped in OTHER apps (browsers, email, chat, ...), and for
 * virt-viewer `.vv` connection files opened from a download or a file
 * manager. Android routes ACTION_VIEW for those schemes and for
 * `application/x-virt-viewer` here because this is the only exported
 * activity that declares them.
 *
 * Transparent/no-history by design (mirrors [ConfirmDisconnectActivity]): it
 * never shows its own screen, only ever one AlertDialog, then either starts
 * [TabTerminalActivity] and finishes, or finishes immediately on Cancel /
 * invalid input. It is never left in the back stack.
 *
 * Security: `intent.data` is untrusted input from an arbitrary caller app.
 * It is parsed exclusively through [TerminalLinkClassifier] (the same
 * framework-free parser in-app ssh:// links already go through) and never
 * auto-connects — the user always sees host/user/port first and must tap
 * Connect. A link-chosen host always gets a transient (never persisted)
 * profile with KEYBOARD_INTERACTIVE auth, so no stored password or key from
 * an existing saved connection can ever be attached to it.
 *
 * The same discipline covers virt-viewer input: `spice://` URIs go through
 * [SpiceUri] and `.vv` files through [VirtViewerFile] — both framework-free
 * parsers that bound every length and range — the file read is capped, the
 * user always confirms the target host before anything is dialled, and a
 * ticket carried by the descriptor is used once in memory and never
 * persisted or logged.
 */
class LinkHandlerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinkHandlerActivity"

        /** MIME type hypervisor web UIs use for a virt-viewer connection file. */
        private const val MIME_VIRT_VIEWER = "application/x-virt-viewer"

        /**
         * Hard cap on how much of an incoming `.vv` file is read. A connection
         * file is a few hundred bytes plus a PEM chain; anything past this is
         * either not a `.vv` file or is trying to make us allocate.
         */
        private const val MAX_VV_BYTES = VirtViewerFile.MAX_CONTENT_LEN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The launching intent is handled exactly once. On a recreation (rotation,
        // process death restore) the same intent is redelivered, which would parse
        // the descriptor a second time, re-show the confirmation dialog, and — for
        // a `.vv` carrying delete-this-file — re-run the deletion.
        if (savedInstanceState != null) {
            Logger.d(TAG, "Recreated after the link was already handled — finishing")
            finish()
            return
        }

        val data = intent?.data
        val scheme = data?.scheme?.lowercase()

        if (scheme == SpiceUri.SCHEME_PLAIN || scheme == SpiceUri.SCHEME_TLS) {
            handleDisplayUri(intent?.dataString.orEmpty(), spice = true)
            return
        }

        if (scheme == VncUri.SCHEME) {
            handleDisplayUri(intent?.dataString.orEmpty(), spice = false)
            return
        }

        if (data != null && (scheme == "content" || scheme == "file" || intent?.type == MIME_VIRT_VIEWER)) {
            handleVirtViewerFile(data)
            return
        }

        val rawUrl = intent?.dataString
        if (rawUrl.isNullOrBlank()) {
            Logger.w(TAG, "Launched with no intent data — nothing to handle")
            Toast.makeText(this, getString(R.string.link_handler_invalid_link), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        when (val action = TerminalLinkClassifier.classify(rawUrl)) {
            is TerminalLinkClassifier.LinkAction.Ssh -> showSshDialog(action)
            is TerminalLinkClassifier.LinkAction.Sftp -> showSftpDialog(action)
            else -> {
                // classify() never returns Ssh/Sftp for a scheme this activity
                // wasn't registered for, but a malformed ssh(s)://../sftp://
                // authority (e.g. "ssh://" alone) falls back to Browser — reject
                // rather than silently doing nothing.
                // A malformed link can still carry userinfo (ssh://user:secret@…),
                // so redact before logging it.
                Logger.w(TAG, "Unhandled or malformed link: ${Logger.urlForLogging(rawUrl)}")
                Toast.makeText(this, getString(R.string.link_handler_could_not_parse), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showSshDialog(action: TerminalLinkClassifier.LinkAction.Ssh) {
        val display = describe(action.username, action.host, action.port)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_handler_ssh_dialog_title)
            .setMessage(getString(R.string.link_handler_ssh_dialog_message, display))
            .setPositiveButton(R.string.virt_viewer_connect) { _, _ -> connectSsh(action) }
            .setNegativeButton(R.string.virt_viewer_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showSftpDialog(action: TerminalLinkClassifier.LinkAction.Sftp) {
        val display = describe(action.username, action.host, action.port)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_handler_sftp_dialog_title)
            .setMessage(getString(R.string.link_handler_sftp_dialog_message, display, action.path))
            .setPositiveButton(R.string.virt_viewer_connect) { _, _ -> connectSftp(action) }
            .setNegativeButton(R.string.virt_viewer_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun connectSsh(action: TerminalLinkClassifier.LinkAction.Ssh) {
        val profile = transientProfile(action.username, action.host, action.port)
        val intent = TabTerminalActivity.createIntent(this, profile, autoConnect = true, forceNew = true)
        startActivity(intent)
        Logger.i(TAG, "Connecting ssh:// link: ${profile.username}@${action.host}:${action.port}")
        finish()
    }

    private fun connectSftp(action: TerminalLinkClassifier.LinkAction.Sftp) {
        val profile = transientProfile(action.username, action.host, action.port)
        val intent = TabTerminalActivity.createIntent(this, profile, autoConnect = true, forceNew = true).apply {
            putExtra(TabTerminalActivity.EXTRA_OPEN_SFTP_PATH, action.path)
        }
        startActivity(intent)
        Logger.i(TAG, "Connecting sftp:// link: ${profile.username}@${action.host}:${action.port}${action.path}")
        finish()
    }

    // ── virt-viewer: spice:// URIs and .vv connection files ───────────────────

    /**
     * Handle a `spice://` / `spice+tls://` / `vnc://` link. Parsing happens
     * entirely in [SpiceUri] or [VncUri] — framework-free parsers that bound
     * every field — so a hostile link cannot get further than a rejected
     * parse.
     */
    private fun handleDisplayUri(rawUrl: String, spice: Boolean) {
        val connection = try {
            if (spice) SpiceUri.parse(rawUrl) else VncUri.parse(rawUrl)
        } catch (e: VirtViewerParseException) {
            Logger.w(TAG, "Rejected display link: ${e.message}")
            Toast.makeText(
                this,
                getString(R.string.virt_viewer_invalid_link, e.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        confirmVirtViewer(connection)
    }

    /**
     * Handle a `.vv` connection file handed over by a browser download, a
     * file manager, or a mail attachment.
     *
     * The read is off the main thread and bounded at [MAX_VV_BYTES]; the file
     * content is untrusted and only ever reaches [VirtViewerFile.parse].
     * `delete-this-file=1` is honoured after a successful parse — a failed
     * deletion is logged and otherwise ignored, since the connection itself
     * is still perfectly usable.
     */
    private fun handleVirtViewerFile(uri: Uri) {
        lifecycleScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { readBounded(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Could not read connection file: ${e.message}")
                null
            }

            if (content.isNullOrEmpty()) {
                Toast.makeText(
                    this@LinkHandlerActivity,
                    R.string.virt_viewer_unreadable_file,
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            val connection = try {
                VirtViewerFile.parse(content)
            } catch (e: VirtViewerParseException) {
                Logger.w(TAG, "Rejected .vv file: ${e.message}")
                Toast.makeText(
                    this@LinkHandlerActivity,
                    getString(R.string.virt_viewer_invalid_link, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            if (connection.deleteThisFile) {
                withContext(Dispatchers.IO) { deleteQuietly(uri) }
            }

            confirmVirtViewer(connection)
        }
    }

    /**
     * Read at most [MAX_VV_BYTES] of `uri` as UTF-8 text. Returns null when
     * the stream cannot be opened at all.
     */
    private fun readBounded(uri: Uri): String? =
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(MAX_VV_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }

    /**
     * Best-effort deletion of a consumed `.vv` file. Most providers will
     * refuse (a downloads content URI is usually read-only to us), which is
     * expected and never surfaced to the user.
     */
    private fun deleteQuietly(uri: Uri) {
        try {
            val deleted = when (uri.scheme?.lowercase()) {
                "file" -> uri.path?.let { File(it).delete() } ?: false
                else -> contentResolver.delete(uri, null, null) > 0
            }
            Logger.d(TAG, "delete-this-file requested, deleted=$deleted")
        } catch (e: Exception) {
            Logger.d(TAG, "delete-this-file failed (expected for read-only providers): ${e.message}")
        }
    }

    /**
     * Same never-auto-connect discipline the ssh:// path uses: the user sees
     * the target host, port and whether the transport is encrypted, and must
     * tap Connect. The password (if the descriptor carried one) is never
     * shown, logged, or persisted — it is passed straight through to the
     * session and dropped with the tab.
     */
    private fun confirmVirtViewer(connection: VirtViewerConnection) {
        val transport = getString(
            if (connection.isTls) R.string.virt_viewer_encrypted_label
            else R.string.virt_viewer_plain_label
        )
        val target = "${connection.host}:${connection.effectivePort} ($transport)"
        val extra = if (connection.enableUsbredir) {
            "\n\n" + getString(R.string.virt_viewer_usbredir_unsupported)
        } else {
            ""
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (connection.type == VirtViewerType.SPICE) R.string.virt_viewer_spice_dialog_title
                else R.string.virt_viewer_vnc_dialog_title
            )
            .setMessage(getString(R.string.virt_viewer_dialog_message, target) + extra)
            .setPositiveButton(R.string.virt_viewer_connect) { _, _ ->
                when (connection.type) {
                    VirtViewerType.SPICE -> connectSpice(connection)
                    VirtViewerType.VNC -> connectVirtViewerVnc(connection)
                }
            }
            .setNegativeButton(R.string.virt_viewer_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Open a SPICE console tab, mirroring `LibvirtManagerActivity.openSpiceTab`:
     * the [SpiceClient] is created un-started and handed to the tab, which
     * starts it once its view is attached.
     *
     * The console type is recorded as [HypervisorConsoleType.LIBVIRT] because
     * a `.vv` file or `spice://` link describes a bare display server with no
     * hypervisor API behind it — the same shape a libvirt console has. No API
     * credentials exist for such a session, so there is no reconnect path and
     * the connect params carry no password.
     */
    private fun connectSpice(connection: VirtViewerConnection) {
        val app = applicationContext as TabSSHApplication
        val name = connection.title?.takeIf { it.isNotBlank() } ?: connection.host
        val params = ConsoleConnectParams(
            type = HypervisorConsoleType.LIBVIRT,
            host = connection.host,
            port = connection.effectivePort,
            username = "",
            password = "",
            verifySsl = connection.caCert != null,
            pinnedCertSha256 = null,
            vmId = connection.host,
            vmName = name
        )

        val tab = app.tabManager.createConsoleTab(params)
        if (tab == null) {
            Toast.makeText(this, R.string.virt_viewer_max_tabs, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tab.markSpice(SpiceClient(connection.toSpiceParams()))
        tab.setConnectionState(ConnectionState.CONNECTED)
        startActivity(
            Intent(this, TabTerminalActivity::class.java).apply {
                putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
            }
        )
        Logger.i(TAG, "Opened SPICE tab from virt-viewer descriptor: ${connection.host}")
        finish()
    }

    /**
     * Single launch path for every VNC descriptor this activity handles —
     * both a `.vv` file with `type=vnc` and a `vnc://` URI land here.
     *
     * A descriptor offering only a TLS port is rejected: RFB-over-TLS without
     * a VeNCrypt handshake is not something the direct connector can dial.
     *
     * When the descriptor carries no password, the server is probed once (see
     * [VncAuthProbe]) to find out whether it will actually demand one. Only
     * then is the user asked, so a security-type-None console still opens with
     * a single tap.
     */
    private fun connectVirtViewerVnc(connection: VirtViewerConnection) {
        if (connection.port == 0) {
            Toast.makeText(this, R.string.virt_viewer_vnc_tls_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!connection.password.isNullOrEmpty()) {
            launchVncTab(connection, connection.password)
            return
        }

        lifecycleScope.launch {
            val needsPassword = try {
                VncAuthProbe.requiresPassword(connection.host, connection.port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.d(TAG, "VNC auth probe failed; connecting without a password: ${e.message}")
                false
            }

            if (needsPassword) {
                promptVncPassword(connection)
            } else {
                launchVncTab(connection, null)
            }
        }
    }

    /**
     * Masked password prompt for a VNC target that demands one, mirroring the
     * backup-import password dialog. The entered value is used for this one
     * handshake and never stored — a link-launched host has no database row to
     * store it against.
     */
    private fun promptVncPassword(connection: VirtViewerConnection) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.virt_viewer_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(passwordInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.virt_viewer_password_title)
            .setMessage(getString(R.string.virt_viewer_password_message, connection.host))
            .setView(layout)
            .setPositiveButton(R.string.virt_viewer_connect) { _, _ ->
                val entered = passwordInput.text?.toString().orEmpty()
                if (entered.isEmpty()) {
                    Toast.makeText(this, R.string.virt_viewer_password_required, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    launchVncTab(connection, entered)
                }
            }
            .setNegativeButton(R.string.virt_viewer_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Open the VNC tab, mirroring `VncHostsActivity`'s connect path but with a
     * transient [VncHost] that is never written to the database and never
     * parked in the background session store — there is no saved host to
     * resume it against.
     */
    private fun launchVncTab(connection: VirtViewerConnection, password: String?) {
        val app = applicationContext as TabSSHApplication
        val name = connection.title?.takeIf { it.isNotBlank() } ?: connection.host
        val now = System.currentTimeMillis()
        val host = VncHost(
            id = UUID.randomUUID().toString(),
            name = name,
            host = connection.host,
            port = connection.port,
            identityId = null,
            keepAliveInBackground = false,
            createdAt = now,
            modifiedAt = now
        )

        lifecycleScope.launch {
            try {
                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    VncDirectConnector.connect(
                        host,
                        password,
                        connection.username,
                        this@LinkHandlerActivity
                    )
                }
                val tab = app.tabManager.createVncTab(vncHost = null, ephemeralDisplayName = name)
                if (tab == null) {
                    try {
                        rfbClient.stop()
                    } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    Toast.makeText(
                        this@LinkHandlerActivity,
                        R.string.virt_viewer_max_tabs,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                    return@launch
                }

                tab.rfbClient = rfbClient
                tab.setConnectionState(ConnectionState.CONNECTED)
                startActivity(
                    Intent(this@LinkHandlerActivity, TabTerminalActivity::class.java).apply {
                        putExtra(TabTerminalActivity.EXTRA_TAB_ID, tab.tabId)
                    }
                )
                Logger.i(TAG, "Opened VNC tab from virt-viewer descriptor: ${connection.host}")
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "virt-viewer VNC connect failed for ${connection.host}", e)
                Toast.makeText(
                    this@LinkHandlerActivity,
                    getString(R.string.virt_viewer_connect_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    /**
     * Never-persisted profile for a link-chosen host, mirroring
     * TabTerminalActivity.connectSshLink() for in-terminal ssh:// links:
     * KEYBOARD_INTERACTIVE auth with no keyId, so connecting only ever
     * prompts for a password rather than reusing a saved credential.
     */
    private fun transientProfile(username: String?, host: String, port: Int): ConnectionProfile {
        val app = applicationContext as TabSSHApplication
        val resolvedUsername = username?.takeIf { it.isNotBlank() }
            ?: app.preferencesManager.getDefaultUsername().trim().takeIf { it.isNotBlank() }
            ?: "root"
        return ConnectionProfile(
            id = UUID.randomUUID().toString(),
            name = "$resolvedUsername@$host",
            host = host,
            port = port,
            username = resolvedUsername,
            authType = AuthType.KEYBOARD_INTERACTIVE.name,
            keyId = null,
            groupId = null
        )
    }

    private fun describe(username: String?, host: String, port: Int): String =
        (username?.let { "$it@" } ?: "") + host + if (port != 22) ":$port" else ""
}
