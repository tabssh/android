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
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.hypervisor.spice.SpiceClient
import io.github.tabssh.hypervisor.viewer.JnlpConnection
import io.github.tabssh.hypervisor.viewer.JnlpFile
import io.github.tabssh.hypervisor.viewer.JnlpParseException
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

        /** MIME type BMC/KVM-over-IP web UIs use for a JNLP console launcher. */
        private const val MIME_JNLP = "application/x-java-jnlp-file"

        /** Hard cap on how much of an incoming `.jnlp` file is read. */
        private const val MAX_JNLP_BYTES = JnlpFile.MAX_CONTENT_LEN
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

        if (data != null && intent?.type == MIME_JNLP) {
            handleJnlpFile(data)
            return
        }

        if (data != null && (scheme == "content" || scheme == "file")) {
            // A generic MIME type gives no signal, so the filename's own
            // extension decides which parser this file goes to — same
            // fallback the manifest's pathPattern-based routing implies.
            if (fileNameOf(data).endsWith(".jnlp", ignoreCase = true)) {
                handleJnlpFile(data)
            } else {
                handleVirtViewerFile(data)
            }
            return
        }

        if (data != null && intent?.type == MIME_VIRT_VIEWER) {
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
            .setPositiveButton(R.string.connect_button) { _, _ -> connectSsh(action) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showSftpDialog(action: TerminalLinkClassifier.LinkAction.Sftp) {
        val display = describe(action.username, action.host, action.port)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_handler_sftp_dialog_title)
            .setMessage(getString(R.string.link_handler_sftp_dialog_message, display, action.path))
            .setPositiveButton(R.string.connect_button) { _, _ -> connectSftp(action) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
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
                withContext(Dispatchers.IO) { readBounded(uri, MAX_VV_BYTES) }
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
     * Read at most `maxBytes` of `uri` as UTF-8 text. Returns null when the
     * stream cannot be opened at all.
     */
    private fun readBounded(uri: Uri, maxBytes: Int): String? =
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }

    /**
     * Last path segment of `uri`, lowercased, used only to decide which
     * connection-file parser a generic-MIME file goes to. Never trusted for
     * anything beyond that routing decision.
     */
    private fun fileNameOf(uri: Uri): String =
        (uri.lastPathSegment ?: uri.path.orEmpty()).substringAfterLast('/').lowercase()

    /**
     * Best-effort deletion of a consumed `.vv`/`.jnlp` file. Most providers
     * will refuse (a downloads content URI is usually read-only to us),
     * which is expected and never surfaced to the user.
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

    // ── JNLP console-launcher files (BMC/KVM-over-IP "Launch Console") ────────

    /**
     * Handle a `.jnlp` file downloaded from an out-of-band management
     * console web UI (iLO, iDRAC, Supermicro/AMI MegaRAC and similar).
     *
     * The read is off the main thread and bounded at [MAX_JNLP_BYTES]; the
     * file content is untrusted and only ever reaches [JnlpFile.parse].
     * There is no vendor allowlist: [JnlpFile] extracts a best-effort
     * host/port/ticket and [confirmJnlp] always lets the user review or
     * edit them before anything is dialled, which covers both a heuristic
     * misfire and a target whose console isn't actually RFB-based — the
     * latter simply fails the handshake in [connectJnlp] like any bad VNC
     * host does today.
     */
    private fun handleJnlpFile(uri: Uri) {
        lifecycleScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { readBounded(uri, MAX_JNLP_BYTES) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Could not read JNLP file: ${e.message}")
                null
            }

            if (content.isNullOrEmpty()) {
                Toast.makeText(this@LinkHandlerActivity, R.string.jnlp_unreadable_file, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val connection = try {
                JnlpFile.parse(content)
            } catch (e: JnlpParseException) {
                Logger.w(TAG, "Rejected .jnlp file: ${e.message}")
                Toast.makeText(
                    this@LinkHandlerActivity,
                    getString(R.string.jnlp_invalid_file, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            confirmJnlp(connection)
        }
    }

    /**
     * Confirmation dialog for a parsed JNLP console: shows the extracted
     * host/port (editable, to cover a wrong heuristic guess) and a
     * "Save as VNC host" option. Unlike the transient `.vv`/`vnc://` flow,
     * checking this box inserts a real [VncHost] row — the specific ask
     * that motivated this feature ("save under VNC hosts if reusable").
     */
    private fun confirmJnlp(connection: JnlpConnection) {
        val hostInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.jnlp_host_hint)
            setText(connection.host)
        }
        val portInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.jnlp_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(connection.port.toString())
        }
        val saveSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.save_as_vnc_host)
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(hostInput)
            addView(portInput)
            addView(saveSwitch)
        }

        val display = connection.title?.takeIf { it.isNotBlank() }
            ?: "${connection.host}:${connection.port}"

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.jnlp_dialog_title)
            .setMessage(getString(R.string.jnlp_dialog_message, display))
            .setView(layout)
            .setPositiveButton(R.string.connect_button, null)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val editedHost = hostInput.text?.toString()?.trim().orEmpty()
                val editedPort = portInput.text?.toString()?.trim()?.toIntOrNull()

                if (editedHost.isEmpty()) {
                    Toast.makeText(this, R.string.main_quick_connect_error_enter_hostname, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (editedPort == null || editedPort !in 1..65535) {
                    Toast.makeText(this, R.string.jnlp_invalid_port, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                connectJnlp(
                    connection.copy(host = editedHost, port = editedPort),
                    saveAsVncHost = saveSwitch.isChecked
                )
            }
        }
        dialog.show()
    }

    /**
     * Connect a confirmed JNLP-derived target, mirroring the transient VNC
     * path in [launchVncTab] but adding the "Save as VNC host" branch from
     * `MainActivity`'s Quick Connect dialog when requested. Best-effort:
     * this always attempts a standard RFB handshake; a proprietary console
     * (classic HP iLO IRC, old Dell avctKVM) simply fails it, surfaced here
     * exactly like any other bad VNC host.
     */
    private fun connectJnlp(connection: JnlpConnection, saveAsVncHost: Boolean) {
        val app = applicationContext as TabSSHApplication
        val name = connection.title?.takeIf { it.isNotBlank() } ?: connection.host
        val now = System.currentTimeMillis()
        val vncHost = VncHost(
            id = UUID.randomUUID().toString(),
            name = name,
            host = connection.host,
            port = connection.port,
            identityId = null,
            keepAliveInBackground = saveAsVncHost,
            groupId = null,
            createdAt = now,
            modifiedAt = now
        )
        val password = connection.ticket

        lifecycleScope.launch {
            try {
                if (saveAsVncHost) {
                    app.database.vncHostDao().insert(vncHost)
                    if (!password.isNullOrEmpty()) {
                        app.securePasswordManager.storePassword(
                            "vnc_host_${vncHost.id}",
                            password,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    }
                }

                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    VncDirectConnector.connect(vncHost, password, context = this@LinkHandlerActivity)
                }
                val tab = if (saveAsVncHost) {
                    app.tabManager.createVncTab(vncHost)
                } else {
                    app.tabManager.createVncTab(null, ephemeralDisplayName = name)
                }
                if (tab == null) {
                    try {
                        rfbClient.stop()
                    } catch (e: Exception) {
                        Logger.d(TAG, "rfbClient.stop() suppressed after max-tabs reject: ${e.message}")
                    }
                    Toast.makeText(this@LinkHandlerActivity, R.string.virt_viewer_max_tabs, Toast.LENGTH_SHORT).show()
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
                Logger.i(TAG, "Opened VNC tab from JNLP descriptor: ${connection.host} (saved=$saveAsVncHost)")
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "JNLP VNC connect failed for ${connection.host}", e)
                Toast.makeText(
                    this@LinkHandlerActivity,
                    getString(R.string.jnlp_connect_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    /**
     * Same never-auto-connect discipline the ssh:// path uses: the user sees
     * the target host, port and whether the transport is encrypted, and must
     * tap Connect. The password (if the descriptor carried one) is never
     * shown, logged, or persisted unless "Save as VNC host" is checked — it
     * is otherwise passed straight through to the session and dropped with
     * the tab.
     *
     * The "Save as VNC host" switch only applies to [VirtViewerType.VNC]: a
     * `.vv`/`vnc://` SPICE target has no equivalent saved-host entity in this
     * flow (it opens through [ConsoleConnectParams]/[HypervisorConsoleType],
     * not a database row), so it stays transient-only like before.
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

        val saveSwitch = if (connection.type == VirtViewerType.VNC) {
            com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                text = getString(R.string.save_as_vnc_host)
            }
        } else {
            null
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (connection.type == VirtViewerType.SPICE) R.string.virt_viewer_spice_dialog_title
                else R.string.virt_viewer_vnc_dialog_title
            )
            .setMessage(getString(R.string.virt_viewer_dialog_message, target) + extra)
            .setPositiveButton(R.string.connect_button) { _, _ ->
                when (connection.type) {
                    VirtViewerType.SPICE -> connectSpice(connection)
                    VirtViewerType.VNC -> connectVirtViewerVnc(connection, saveSwitch?.isChecked == true)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }

        if (saveSwitch != null) {
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(64, 32, 64, 0)
                addView(saveSwitch)
            }
            builder.setView(layout)
        }
        builder.show()
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
    private fun connectVirtViewerVnc(connection: VirtViewerConnection, saveAsVncHost: Boolean) {
        if (connection.port == 0) {
            Toast.makeText(this, R.string.virt_viewer_vnc_tls_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!connection.password.isNullOrEmpty()) {
            launchVncTab(connection, connection.password, saveAsVncHost)
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
                promptVncPassword(connection, saveAsVncHost)
            } else {
                launchVncTab(connection, null, saveAsVncHost)
            }
        }
    }

    /**
     * Masked password prompt for a VNC target that demands one, mirroring the
     * backup-import password dialog. The entered value is used for this one
     * handshake and never stored — a link-launched host has no database row to
     * store it against.
     */
    private fun promptVncPassword(connection: VirtViewerConnection, saveAsVncHost: Boolean) {
        val passwordInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.password_hint)
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
            .setPositiveButton(R.string.connect_button) { _, _ ->
                val entered = passwordInput.text?.toString().orEmpty()
                if (entered.isEmpty()) {
                    Toast.makeText(this, R.string.virt_viewer_password_required, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    launchVncTab(connection, entered, saveAsVncHost)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Open the VNC tab, mirroring `VncHostsActivity`'s connect path. By
     * default [host] is transient — never written to the database and never
     * parked in the background session store, since there is no saved host
     * to resume it against. When [saveAsVncHost] is set (from the confirm
     * dialog's "Save as VNC host" switch), the host row and any password are
     * persisted first, mirroring [connectJnlp]'s save branch, and the tab is
     * opened against that saved row instead of an ephemeral one.
     */
    private fun launchVncTab(connection: VirtViewerConnection, password: String?, saveAsVncHost: Boolean) {
        val app = applicationContext as TabSSHApplication
        val name = connection.title?.takeIf { it.isNotBlank() } ?: connection.host
        val now = System.currentTimeMillis()
        val host = VncHost(
            id = UUID.randomUUID().toString(),
            name = name,
            host = connection.host,
            port = connection.port,
            identityId = null,
            keepAliveInBackground = saveAsVncHost,
            createdAt = now,
            modifiedAt = now
        )

        lifecycleScope.launch {
            try {
                if (saveAsVncHost) {
                    app.database.vncHostDao().insert(host)
                    if (!password.isNullOrEmpty()) {
                        app.securePasswordManager.storePassword(
                            "vnc_host_${host.id}",
                            password,
                            SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    }
                }

                val (rfbClient, _) = withContext(Dispatchers.IO) {
                    VncDirectConnector.connect(
                        host,
                        password,
                        connection.username,
                        this@LinkHandlerActivity
                    )
                }
                val tab = if (saveAsVncHost) {
                    app.tabManager.createVncTab(host)
                } else {
                    app.tabManager.createVncTab(vncHost = null, ephemeralDisplayName = name)
                }
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
                Logger.i(TAG, "Opened VNC tab from virt-viewer descriptor: ${connection.host} (saved=$saveAsVncHost)")
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
