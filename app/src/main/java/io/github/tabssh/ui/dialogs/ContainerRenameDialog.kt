package io.github.tabssh.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.ui.utils.DockerNames
import io.github.tabssh.ui.utils.DockerText

/**
 * Rename prompt for a container. Validation only —
 * the caller performs the actual transport rename.
 */
object ContainerRenameDialog {

    /** Prompt for a new name, pre-filled with [currentName]. */
    fun show(context: Context, currentName: String, onRename: (String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_rename, null)
        val editName = view.findViewById<TextInputEditText>(R.id.edit_name)
        // The pre-fill comes from the daemon; a control or bidi character in it
        // would otherwise be edited back into the new name.
        val safeCurrent = DockerText.display(currentName, DockerNames.MAX_NAME_LENGTH)
        editName.setText(safeCurrent)
        editName.setSelection(safeCurrent.length)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.docker_rename_title)
            .setView(view)
            .setPositiveButton(R.string.docker_action_rename) { _, _ ->
                val newName = editName.text?.toString()?.trim().orEmpty()
                // The new name becomes a `docker rename` argument — enforce the
                // daemon's own grammar before it reaches the transport.
                if (newName.isEmpty() || newName == safeCurrent) {
                    Toast.makeText(context, R.string.docker_rename_error, Toast.LENGTH_SHORT)
                        .show()
                } else if (!DockerNames.isValidResourceName(newName)) {
                    Toast.makeText(context, R.string.docker_error_name_format, Toast.LENGTH_LONG)
                        .show()
                } else {
                    onRename(newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
