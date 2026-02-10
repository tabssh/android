package io.github.tabssh.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogUtils {
    
    /**
     * Shows an error dialog with a copy button to copy the error message to clipboard
     */
    fun showErrorDialog(
        context: Context,
        title: String = "Error",
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard(context, message)
                Toast.makeText(context, "Error message copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(true)
            .show()
    }
    
    /**
     * Shows a success dialog
     */
    fun showSuccessDialog(
        context: Context,
        title: String = "Success",
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setCancelable(true)
            .show()
    }
    
    /**
     * Shows a confirmation dialog with Yes/No buttons
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        positiveButton: String = "Yes",
        negativeButton: String = "No",
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton(negativeButton) { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .setCancelable(false)
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
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard(context, message)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(true)
            .show()
    }
    
    /**
     * Copies text to clipboard
     */
    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TabSSH", text)
        clipboard.setPrimaryClip(clip)
    }
}
