package io.github.tabssh.utils

import android.app.Activity
import androidx.fragment.app.Fragment

/**
 * Activity and Fragment extension functions for consistent error handling
 * All error messages will have a copy button for easy debugging
 */

/**
 * Show error dialog with copy button
 * 
 * @param message Error message to display
 * @param title Dialog title (default: "Error")
 */
fun Activity.showError(message: String, title: String = "Error") {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = this,
        title = title,
        message = message
    )
}

/**
 * Show error dialog with copy button (Fragment version)
 * 
 * @param message Error message to display
 * @param title Dialog title (default: "Error")
 */
fun Fragment.showError(message: String, title: String = "Error") {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = requireContext(),
        title = title,
        message = message
    )
}

/**
 * Show success message dialog
 * 
 * @param message Success message to display
 * @param title Dialog title (default: "Success")
 */
fun Activity.showSuccess(message: String, title: String = "Success") {
    io.github.tabssh.ui.utils.DialogUtils.showSuccessDialog(
        context = this,
        title = title,
        message = message
    )
}

/**
 * Show success message dialog (Fragment version)
 * 
 * @param message Success message to display
 * @param title Dialog title (default: "Success")
 */
fun Fragment.showSuccess(message: String, title: String = "Success") {
    io.github.tabssh.ui.utils.DialogUtils.showSuccessDialog(
        context = requireContext(),
        title = title,
        message = message
    )
}

/**
 * Show copyable dialog with custom title
 * 
 * @param title Dialog title
 * @param message Message content
 */
fun Activity.showCopyable(title: String, message: String) {
    io.github.tabssh.ui.utils.DialogUtils.showCopyableDialog(
        context = this,
        title = title,
        message = message
    )
}
