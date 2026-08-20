package io.github.tabssh.utils

import android.app.Activity
import androidx.fragment.app.Fragment
import io.github.tabssh.R

/**
 * Activity and Fragment extension functions for consistent error handling
 * All error messages will have a copy button for easy debugging
 */

/**
 * Show error dialog with copy button
 *
 * @param message Error message to display
 * @param title Dialog title; null uses the localized default
 */
fun Activity.showError(message: String, title: String? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = this,
        title = title ?: getString(R.string.dialog_title_error),
        message = message
    )
}

/**
 * Show error dialog with copy button (Fragment version)
 *
 * @param message Error message to display
 * @param title Dialog title; null uses the localized default
 */
fun Fragment.showError(message: String, title: String? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = requireContext(),
        title = title ?: getString(R.string.dialog_title_error),
        message = message
    )
}

/**
 * Show success message dialog
 *
 * @param message Success message to display
 * @param title Dialog title; null uses the localized default
 */
fun Activity.showSuccess(message: String, title: String? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showSuccessDialog(
        context = this,
        title = title ?: getString(R.string.dialog_title_success),
        message = message
    )
}

/**
 * Show success message dialog (Fragment version)
 *
 * @param message Success message to display
 * @param title Dialog title; null uses the localized default
 */
fun Fragment.showSuccess(message: String, title: String? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showSuccessDialog(
        context = requireContext(),
        title = title ?: getString(R.string.dialog_title_success),
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
