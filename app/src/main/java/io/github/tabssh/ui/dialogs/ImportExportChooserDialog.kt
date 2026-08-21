package io.github.tabssh.ui.dialogs

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R

/**
 * Shared "how" choosers wrapping the existing SAF (file) flows with a
 * paste/text-block alternative, since the source content may live on a
 * remote host the user has no direct file-system access to.
 */
object ImportExportChooserDialog {

    /** Ask whether to import from a file (SAF picker) or by pasting text. */
    fun showImportSource(context: Context, onFile: () -> Unit, onPaste: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.import_source_chooser_title)
            .setItems(
                arrayOf(context.getString(R.string.import_source_file), context.getString(R.string.import_source_paste))
            ) { _, which ->
                if (which == 0) onFile() else onPaste()
            }
            .show()
    }

    /** Ask whether to export to a file (SAF picker) or view as a themed, copyable text block. */
    fun showExportTarget(context: Context, onFile: () -> Unit, onText: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.export_target_chooser_title)
            .setItems(
                arrayOf(context.getString(R.string.export_target_file), context.getString(R.string.export_target_text))
            ) { _, which ->
                if (which == 0) onFile() else onText()
            }
            .show()
    }
}
