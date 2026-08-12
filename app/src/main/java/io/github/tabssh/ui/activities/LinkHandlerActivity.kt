package io.github.tabssh.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.TerminalLinkClassifier
import io.github.tabssh.utils.logging.Logger
import java.util.UUID

/**
 * System-wide entry point for ssh:// and sftp:// links tapped in OTHER apps
 * (browsers, email, chat, ...). Android routes ACTION_VIEW for those schemes
 * here because this is the only exported activity that declares them.
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
 */
class LinkHandlerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LinkHandlerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawUrl = intent?.dataString
        if (rawUrl.isNullOrBlank()) {
            Logger.w(TAG, "Launched with no intent data — nothing to handle")
            Toast.makeText(this, "Invalid link", Toast.LENGTH_SHORT).show()
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
                Logger.w(TAG, "Unhandled or malformed link: $rawUrl")
                Toast.makeText(this, "Could not parse this link", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showSshDialog(action: TerminalLinkClassifier.LinkAction.Ssh) {
        val display = describe(action.username, action.host, action.port)
        MaterialAlertDialogBuilder(this)
            .setTitle("Connect via SSH?")
            .setMessage(
                "$display\n\nTabSSH will open a new session to this host. " +
                    "No stored password or key is attached automatically."
            )
            .setPositiveButton("Connect") { _, _ -> connectSsh(action) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showSftpDialog(action: TerminalLinkClassifier.LinkAction.Sftp) {
        val display = describe(action.username, action.host, action.port)
        MaterialAlertDialogBuilder(this)
            .setTitle("Browse via SFTP?")
            .setMessage(
                "$display\nRemote path: ${action.path}\n\n" +
                    "TabSSH will connect to this host, then open the SFTP browser " +
                    "at that path. No stored password or key is attached automatically."
            )
            .setPositiveButton("Connect") { _, _ -> connectSftp(action) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
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
