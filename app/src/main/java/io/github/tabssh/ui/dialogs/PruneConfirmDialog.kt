package io.github.tabssh.ui.dialogs

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R

/**
 * Shared confirmation for the destructive prune actions (images, volumes,
 * networks) launched from the Docker host manager menu.
 */
object PruneConfirmDialog {

    /** Confirm before running [onConfirm]; cancel is the default action. */
    fun show(
        context: Context,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.container_prune_action) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
