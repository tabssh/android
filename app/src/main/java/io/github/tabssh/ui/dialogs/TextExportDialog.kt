package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.utils.ClipboardHelper

/**
 * Themed scrollable text-block viewer for exported content (CSV, Markdown,
 * SSH config, …), with a Copy button — for when the user wants the export
 * as a text block instead of (or in addition to) a saved file, e.g. to
 * paste into a remote host they don't have direct file access to.
 */
object TextExportDialog {

    /** Show [text] under [title] with a Copy-to-clipboard button. */
    fun show(context: Context, title: String, text: String) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_text_export, null)
        view.findViewById<TextView>(R.id.text_export).text = text

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.copy) { _, _ ->
                ClipboardHelper.copy(context, title, text, sensitive = false)
                Toast.makeText(context, R.string.text_export_copied_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
