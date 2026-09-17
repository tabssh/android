package io.github.tabssh.ui.utils

import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows the dialog with `FLAG_SECURE` always set on its own window, the same
 * guarantee [AlwaysSecureScreen] gives a full activity. Use for any dialog
 * that shows or accepts a password, passphrase, token, or key.
 *
 * A dialog opens its own [android.view.Window], separate from the host
 * activity's — so the activity-level flag applied in
 * `TabSSHApplication.applyWindowSecurityFlags` (which only turns on
 * `FLAG_SECURE` for [AlwaysSecureScreen] activities or when the user opted
 * into "prevent screenshots" globally) never reaches it. Without this, a
 * credential dialog stays screenshottable even on an unlocked, non-secure
 * host screen.
 */
fun MaterialAlertDialogBuilder.showSecurely(): AlertDialog {
    val dialog = show()
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    return dialog
}
