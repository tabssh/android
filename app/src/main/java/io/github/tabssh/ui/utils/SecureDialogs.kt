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
    dialog.secureWindow()
    return dialog
}

/**
 * Blocks screenshots of this dialog the way [ScreenshotPolicy] blocks a secret screen: at the
 * Disabled and Limited levels, not at Allow. Use for dialogs that show or accept a secret.
 */
fun AlertDialog.secureWindow() {
    val mode = (context.applicationContext as? io.github.tabssh.TabSSHApplication)
        ?.preferencesManager?.getScreenshotMode()
    // Without preferences (never expected) stay safe and block.
    val blocked = mode == null || ScreenshotPolicy.shouldBlock(mode, ScreenshotPolicy.Screen.SECRET)
    if (blocked) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
