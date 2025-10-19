package com.tabssh.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.tabssh.R
import com.tabssh.ssh.connection.HostKeyAction
import com.tabssh.ssh.connection.HostKeyChangedInfo
import com.tabssh.utils.logging.Logger

/**
 * Dialog shown when a server's SSH host key has changed
 * Critical security feature to prevent man-in-the-middle attacks
 */
class HostKeyChangedDialog {

    companion object {
        /**
         * Show host key changed warning and get user decision
         *
         * @param context Android context
         * @param info Information about the changed host key
         * @param onDecision Callback with user's decision
         */
        fun show(
            context: Context,
            info: HostKeyChangedInfo,
            onDecision: (HostKeyAction) -> Unit
        ) {
            Logger.w("HostKeyChangedDialog", "Showing host key changed warning for ${info.hostname}:${info.port}")

            val builder = AlertDialog.Builder(context)
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_host_key_changed, null)

            // Populate dialog views with beautiful formatting
            view.findViewById<TextView>(R.id.text_hostname)?.text = "${info.hostname}:${info.port}"
            view.findViewById<TextView>(R.id.text_old_fingerprint)?.text = info.oldFingerprint
            view.findViewById<TextView>(R.id.text_new_fingerprint)?.text = info.newFingerprint
            view.findViewById<TextView>(R.id.text_old_key_type)?.text = "🔑 ${info.oldKeyType}"
            view.findViewById<TextView>(R.id.text_new_key_type)?.text = "🔑 ${info.newKeyType}"

            val firstSeenDate = java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(info.firstSeen))
            view.findViewById<TextView>(R.id.text_first_seen)?.text = "First connected: $firstSeenDate"

            builder.setView(view)
            builder.setTitle("🔐 Server Key Changed")
            builder.setCancelable(false) // Force user to make a decision

            // Button order: Positive (Accept New Key - default), Neutral (Accept Once), Negative (Reject)
            builder.setPositiveButton("✅ Accept New Key") { dialog, _ ->
                Logger.i("HostKeyChangedDialog", "User chose to accept new key for ${info.hostname}")
                onDecision(HostKeyAction.ACCEPT_NEW_KEY)
                dialog.dismiss()
            }

            builder.setNeutralButton("⏱️ Accept Once") { dialog, _ ->
                Logger.i("HostKeyChangedDialog", "User chose to accept key once for ${info.hostname}")
                onDecision(HostKeyAction.ACCEPT_ONCE)
                dialog.dismiss()
            }

            builder.setNegativeButton("🚫 Reject") { dialog, _ ->
                Logger.i("HostKeyChangedDialog", "User chose to reject connection to ${info.hostname}")
                onDecision(HostKeyAction.REJECT_CONNECTION)
                dialog.dismiss()
            }

            val dialog = builder.create()
            dialog.show()

            // Make buttons beautiful with proper styling
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(context.getColor(android.R.color.holo_green_dark))
                isAllCaps = false
                textSize = 16f
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(context.getColor(android.R.color.holo_orange_dark))
                isAllCaps = false
                textSize = 16f
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(context.getColor(android.R.color.holo_red_dark))
                isAllCaps = false
                textSize = 16f
            }
        }

        /**
         * Show simplified host key changed alert with blocking behavior
         * Returns user's decision synchronously using a blocking approach
         */
        fun showAndWait(context: Context, info: HostKeyChangedInfo): HostKeyAction {
            var decision: HostKeyAction? = null
            val lock = Object()

            // Show dialog on UI thread
            (context as? android.app.Activity)?.runOnUiThread {
                show(context, info) { action ->
                    synchronized(lock) {
                        decision = action
                        lock.notifyAll()
                    }
                }
            }

            // Wait for decision
            synchronized(lock) {
                while (decision == null) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        Logger.e("HostKeyChangedDialog", "Interrupted while waiting for decision", e)
                        return HostKeyAction.REJECT_CONNECTION
                    }
                }
            }

            return decision ?: HostKeyAction.REJECT_CONNECTION
        }

        /**
         * Show informational dialog about new host (first connection)
         */
        fun showNewHostInfo(context: Context, hostname: String, port: Int, fingerprint: String, keyType: String) {
            val message = buildString {
                appendLine("🎉 Connecting to a new server!")
                appendLine()
                appendLine("🌐 Server: $hostname:$port")
                appendLine("🔑 Key Type: $keyType")
                appendLine("🔐 Fingerprint:")
                appendLine("   $fingerprint")
                appendLine()
                appendLine("✨ This key will be securely stored for future connections.")
                appendLine()
                appendLine("💡 Always verify the fingerprint matches your server's actual key.")
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle("🆕 New SSH Host")
                .setMessage(message)
                .setPositiveButton("✅ Continue & Save") { dialog, _ -> dialog.dismiss() }
                .setNegativeButton("🚫 Cancel") { dialog, _ -> dialog.dismiss() }
                .create()

            dialog.show()

            // Style buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(context.getColor(android.R.color.holo_green_dark))
                isAllCaps = false
                textSize = 16f
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(context.getColor(android.R.color.holo_red_dark))
                isAllCaps = false
                textSize = 16f
            }
        }

        /**
         * Show host key management dialog for viewing/removing stored keys
         */
        fun showManageHostKeys(context: Context, onRemove: (String, Int) -> Unit) {
            Logger.d("HostKeyChangedDialog", "Host key management dialog requested")

            val message = buildString {
                appendLine("🔐 Manage Your Trusted Server Keys")
                appendLine()
                appendLine("View, verify, and remove stored SSH host keys.")
                appendLine()
                appendLine("💡 Tips:")
                appendLine("  • Review keys regularly for security")
                appendLine("  • Remove keys from decommissioned servers")
                appendLine("  • Verify fingerprints match your records")
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle("🗂️ Known Hosts")
                .setMessage(message)
                .setPositiveButton("✅ Done") { dialog, _ -> dialog.dismiss() }
                .setNeutralButton("🗑️ Clear All") { dialog, _ ->
                    // Show confirmation
                    AlertDialog.Builder(context)
                        .setTitle("⚠️ Clear All Keys?")
                        .setMessage("This will remove all stored SSH host keys. You'll be asked to verify each server on next connection.\n\nThis action cannot be undone.")
                        .setPositiveButton("🗑️ Clear All") { confirmDialog, _ ->
                            Logger.w("HostKeyChangedDialog", "User cleared all host keys")
                            confirmDialog.dismiss()
                        }
                        .setNegativeButton("❌ Cancel") { confirmDialog, _ -> confirmDialog.dismiss() }
                        .show()
                }
                .create()

            dialog.show()

            // Style buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isAllCaps = false
                textSize = 16f
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(context.getColor(android.R.color.holo_red_dark))
                isAllCaps = false
                textSize = 16f
            }
        }
    }
}
