package io.github.tabssh.ui.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R

object DialogUtils {

    /**
     * Returns true if the dialog cannot safely attach — e.g. the host
     * Activity is finishing/destroyed because a coroutine continuation
     * fired after the user backed out. Without this guard
     * `MaterialAlertDialogBuilder.show()` throws `BadTokenException`.
     */
    private fun isContextDead(context: Context): Boolean {
        val activity = context as? Activity ?: return false
        return activity.isFinishing || activity.isDestroyed
    }

    /**
     * Shows an error dialog with a copy button to copy the error message to clipboard
     */
    fun showErrorDialog(
        context: Context,
        title: String = context.getString(R.string.dialog_title_error),
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        if (isContextDead(context)) return
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNeutralButton(context.getString(R.string.copy)) { _, _ ->
                copyToClipboard(context, message)
                Toast.makeText(context, context.getString(R.string.dialog_error_message_copied_toast), Toast.LENGTH_SHORT).show()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Shows a success dialog
     */
    fun showSuccessDialog(
        context: Context,
        title: String = context.getString(R.string.dialog_title_success),
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        if (isContextDead(context)) return
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Shows a dialog with a copy button for any text content
     */
    fun showCopyableDialog(
        context: Context,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        if (isContextDead(context)) return
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.close)) { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNeutralButton(context.getString(R.string.copy)) { _, _ ->
                copyToClipboard(context, message)
                Toast.makeText(context, context.getString(R.string.dialog_copied_to_clipboard_toast), Toast.LENGTH_SHORT).show()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Copies text to clipboard
     */
    private fun copyToClipboard(context: Context, text: String) {
        // Route through ClipboardHelper so this non-sensitive write cancels
        // any pending sensitive auto-clear (see ClipboardHelper KDoc).
        io.github.tabssh.utils.ClipboardHelper.copy(context, "TabSSH", text, sensitive = false)
    }
}
