package io.github.tabssh.utils

import android.app.Activity
import android.app.Service
import android.view.accessibility.AccessibilityManager
import androidx.fragment.app.Fragment
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication

/**
 * Activity and Fragment extension functions for consistent error handling
 * All error messages will have a copy button for easy debugging
 */

/**
 * Typed accessor for the app-wide [TabSSHApplication] instance, replacing the
 * repeated `application as TabSSHApplication` cast previously duplicated
 * across activities, fragments, and services (AI.md PART 7 § Reuse Before
 * Creating).
 */
val Activity.tabSSHApp: TabSSHApplication
    get() = application as TabSSHApplication

/**
 * Fragment counterpart of [Activity.tabSSHApp].
 */
val Fragment.tabSSHApp: TabSSHApplication
    get() = requireActivity().application as TabSSHApplication

/**
 * Service counterpart of [Activity.tabSSHApp].
 */
val Service.tabSSHApp: TabSSHApplication
    get() = application as TabSSHApplication

/**
 * Show error dialog with copy button
 *
 * @param message Error message to display
 * @param title Dialog title; null uses the localized default
 * @param copyText Raw technical detail copied by the Copy button; defaults to [message]
 * @param onRetry When non-null, adds a Retry button for retryable failures
 */
fun Activity.showError(message: String, title: String? = null, copyText: String? = null, onRetry: (() -> Unit)? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = this,
        title = title ?: getString(R.string.status_error),
        message = message,
        copyText = copyText,
        onRetry = onRetry
    )
}

/**
 * Show error dialog with copy button (Fragment version)
 *
 * @param message Error message to display
 * @param title Dialog title; null uses the localized default
 * @param copyText Raw technical detail copied by the Copy button; defaults to [message]
 * @param onRetry When non-null, adds a Retry button for retryable failures
 */
fun Fragment.showError(message: String, title: String? = null, copyText: String? = null, onRetry: (() -> Unit)? = null) {
    io.github.tabssh.ui.utils.DialogUtils.showErrorDialog(
        context = requireContext(),
        title = title ?: getString(R.string.status_error),
        message = message,
        copyText = copyText,
        onRetry = onRetry
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

/**
 * Speak a state-transition message to TalkBack/screen readers.
 *
 * Use for async-operation outcomes with no dialog of their own (connect
 * succeeded/failed, transfer finished, import finished) — anything already
 * shown in a dialog or Snackbar reaches TalkBack on its own and does not
 * need this. No-ops when no accessibility service is enabled, so callers
 * never need their own guard.
 *
 * @param message Announcement text; must be final, human-readable copy
 */
fun Activity.announceAccessibility(message: String) {
    val accessibilityManager =
        getSystemService(Activity.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (accessibilityManager?.isEnabled != true) {
        return
    }
    window?.decorView?.rootView?.announceForAccessibility(message)
}
