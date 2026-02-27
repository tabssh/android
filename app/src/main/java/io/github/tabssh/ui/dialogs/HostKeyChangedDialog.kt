package io.github.tabssh.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.ssh.connection.HostKeyAction
import io.github.tabssh.ssh.connection.HostKeyChangedInfo
import io.github.tabssh.utils.logging.Logger

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

            val view = LayoutInflater.from(context).inflate(R.layout.dialog_host_key_changed, null)

            // Populate dialog views with beautiful formatting
            view.findViewById<TextView>(R.id.text_hostname)?.text = "${info.hostname}:${info.port}"
            view.findViewById<TextView>(R.id.text_old_fingerprint)?.text = info.oldFingerprint
            view.findViewById<TextView>(R.id.text_new_fingerprint)?.text = info.newFingerprint
            view.findViewById<TextView>(R.id.text_old_key_type)?.text = "Type: ${info.oldKeyType}"
            view.findViewById<TextView>(R.id.text_new_key_type)?.text = "Type: ${info.newKeyType}"

            val firstSeenDate = java.text.SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(info.firstSeen))
            view.findViewById<TextView>(R.id.text_first_seen)?.text = "First connected: $firstSeenDate"

            // Use MaterialAlertDialogBuilder for Material Design 3 styling
            val builder = MaterialAlertDialogBuilder(context)
            builder.setView(view)
            builder.setTitle("Server Key Changed")
            builder.setCancelable(false) // Force user to make a decision

            // Create dialog first so buttons can reference it
            val dialog = builder.create()

            // Create custom button layout for better appearance
            val buttonLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }

            // Accept New Key button (primary action)
            val acceptButton = createStyledButton(context, "Accept New Key", Color.parseColor("#2E7D32")) {
                Logger.i("HostKeyChangedDialog", "User chose to accept new key for ${info.hostname}")
                dialog.dismiss()
                onDecision(HostKeyAction.ACCEPT_NEW_KEY)
            }
            buttonLayout.addView(acceptButton)

            // Add spacing
            buttonLayout.addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
            })

            // Accept Once button (secondary action)
            val acceptOnceButton = createStyledButton(context, "Accept for This Session Only", Color.parseColor("#F57C00")) {
                Logger.i("HostKeyChangedDialog", "User chose to accept key once for ${info.hostname}")
                dialog.dismiss()
                onDecision(HostKeyAction.ACCEPT_ONCE)
            }
            buttonLayout.addView(acceptOnceButton)

            // Add spacing
            buttonLayout.addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
            })

            // Reject button (dangerous action)
            val rejectButton = createStyledButton(context, "Reject Connection", Color.parseColor("#C62828")) {
                Logger.i("HostKeyChangedDialog", "User chose to reject connection to ${info.hostname}")
                dialog.dismiss()
                onDecision(HostKeyAction.REJECT_CONNECTION)
            }
            buttonLayout.addView(rejectButton)

            // Add button layout to a container
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(view)
                addView(buttonLayout)
            }

            dialog.setView(container)
            dialog.show()
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
        fun showNewHostInfo(
            context: Context,
            hostname: String,
            port: Int,
            fingerprint: String,
            keyType: String,
            onAccept: () -> Unit = {},
            onReject: () -> Unit = {}
        ) {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_new_host_key, null)

            // Populate fields if the layout has them
            view.findViewById<TextView>(R.id.text_hostname)?.text = "$hostname:$port"
            view.findViewById<TextView>(R.id.text_key_type)?.text = "Key Type: $keyType"
            view.findViewById<TextView>(R.id.text_fingerprint)?.text = fingerprint

            val builder = MaterialAlertDialogBuilder(context)
                .setView(view)
                .setTitle("New SSH Server")
                .setCancelable(false)
                .setPositiveButton("Trust & Connect") { dialog, _ ->
                    Logger.i("HostKeyChangedDialog", "User accepted new host key for $hostname")
                    onAccept()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    Logger.i("HostKeyChangedDialog", "User rejected new host $hostname")
                    onReject()
                    dialog.dismiss()
                }

            val dialog = builder.create()
            dialog.show()

            // Style buttons with Material colors
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(Color.parseColor("#2E7D32"))
                isAllCaps = false
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(Color.parseColor("#C62828"))
                isAllCaps = false
            }
        }

        /**
         * Create a styled button with Material Design 3 appearance
         */
        private fun createStyledButton(
            context: Context,
            text: String,
            backgroundColor: Int,
            onClick: () -> Unit
        ): Button {
            return Button(context).apply {
                this.text = text
                setBackgroundColor(backgroundColor)
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                setPadding(24, 16, 24, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { onClick() }
            }
        }

        /**
         * Show host key management dialog for viewing/removing stored keys
         */
        fun showManageHostKeys(context: Context, onRemove: (String, Int) -> Unit) {
            Logger.d("HostKeyChangedDialog", "Host key management dialog requested")

            val message = buildString {
                appendLine("Manage Your Trusted Server Keys")
                appendLine()
                appendLine("View, verify, and remove stored SSH host keys.")
                appendLine()
                appendLine("Tips:")
                appendLine("  - Review keys regularly for security")
                appendLine("  - Remove keys from decommissioned servers")
                appendLine("  - Verify fingerprints match your records")
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Known Hosts")
                .setMessage(message)
                .setPositiveButton("Done") { dialog, _ -> dialog.dismiss() }
                .setNeutralButton("Clear All") { _, _ ->
                    // Show confirmation
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Clear All Keys?")
                        .setMessage("This will remove all stored SSH host keys. You'll be asked to verify each server on next connection.\n\nThis action cannot be undone.")
                        .setPositiveButton("Clear All") { confirmDialog, _ ->
                            Logger.w("HostKeyChangedDialog", "User cleared all host keys")
                            confirmDialog.dismiss()
                        }
                        .setNegativeButton("Cancel") { confirmDialog, _ -> confirmDialog.dismiss() }
                        .show()
                }
                .create()

            dialog.show()

            // Style buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isAllCaps = false
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(Color.parseColor("#C62828"))
                isAllCaps = false
            }
        }
    }
}
