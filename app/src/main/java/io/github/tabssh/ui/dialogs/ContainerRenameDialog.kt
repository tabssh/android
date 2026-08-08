package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R

/**
 * Rename prompt for a container (PLAN.AI.md step 24). Validation only —
 * the caller performs the actual transport rename.
 */
object ContainerRenameDialog {

    /** Prompt for a new name, pre-filled with [currentName]. */
    fun show(context: Context, currentName: String, onRename: (String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_rename, null)
        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        editName.setText(currentName)
        editName.setSelection(currentName.length)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.docker_rename_title)
            .setView(view)
            .setPositiveButton(R.string.docker_action_rename) { _, _ ->
                val newName = editName.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty() || newName == currentName) {
                    Toast.makeText(context, R.string.docker_rename_error, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    onRename(newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
