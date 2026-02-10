package io.github.tabssh.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Utility class for creating dialogs with copy functionality
 */
object DialogUtils {
    
    /**
     * Show error dialog with copy button
     */
    fun showErrorDialog(
        context: Context,
        title: String,
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
                copyToClipboard(context, "Error: $title", message)
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Show error dialog with exception details
     */
    fun showErrorDialog(
        context: Context,
        title: String,
        exception: Exception,
        onDismiss: (() -> Unit)? = null
    ) {
        val message = buildString {
            append(exception.message ?: "Unknown error")
            append("\n\n")
            append("Stack trace:\n")
            append(exception.stackTraceToString())
        }
        
        showErrorDialog(context, title, message, onDismiss)
    }
    
    /**
     * Show log viewer dialog with copy button
     */
    fun showLogDialog(
        context: Context,
        title: String,
        logs: String
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(logs)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy All") { _, _ ->
                copyToClipboard(context, title, logs)
            }
            .show()
    }
    
    /**
     * Show confirmation dialog with action
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        positiveButton: String = "Yes",
        negativeButton: String = "No",
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ -> onConfirm() }
            .setNegativeButton(negativeButton, null)
            .show()
    }
    
    /**
     * Copy text to clipboard
     */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Show info dialog
     */
    fun showInfoDialog(
        context: Context,
        title: String,
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
            .show()
    }
}
