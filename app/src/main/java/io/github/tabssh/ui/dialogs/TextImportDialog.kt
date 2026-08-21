package io.github.tabssh.ui.dialogs

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R

/**
 * Paste-based import: lets the user paste text copied from a remote host
 * (SSH config, CSV, Markdown, …) instead of picking a file, for sources
 * that only exist off-device and can't be reached with the system file
 * picker.
 */
object TextImportDialog {

    /**
     * Show a paste dialog under [title] with input hint [hint]; invokes
     * [onImport] with the pasted text once the user confirms.
     */
    fun show(context: Context, title: String, hint: String = context.getString(R.string.text_import_hint), onImport: (String) -> Unit) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_text_import, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.input_layout_import)
        val editText = view.findViewById<TextInputEditText>(R.id.edit_text_import)
        inputLayout.hint = hint

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.menu_import, null)
            .setNeutralButton(R.string.paste, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        // Both positive and neutral buttons are overridden after show() so a
        // validation failure (empty text) or a clipboard paste never
        // dismisses the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else null
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.text_import_clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                editText.setText(text)
                editText.setSelection(text.length)
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(context, R.string.text_import_empty_error, Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                onImport(text)
            }
        }
    }
}
