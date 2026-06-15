package io.github.tabssh.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import io.github.tabssh.utils.logging.Logger

/**
 * Centralized clipboard writer with sensitive-only auto-clear.
 *
 * ## Sensitive vs non-sensitive copies
 *
 * Pass `sensitive = true` only for content the user explicitly asked to copy
 * from TabSSH's own credential surfaces (passwords, private key passphrases,
 * etc.). Terminal text selections, snippets, URLs, crash reports, and log
 * content are all `sensitive = false`.
 *
 * ## Auto-clear (sensitive only)
 *
 * When `security_clear_clipboard_timeout` is set to N seconds and
 * `sensitive = true`, a delayed runnable is posted to clear the clip after N
 * seconds. The runnable verifies the clip description label still matches the
 * unique token we stamped at write time before clearing — so:
 *
 * - Another app (browser, notes, etc.) writing to the clipboard after us
 *   produces a different label → we leave it alone.
 * - A subsequent non-sensitive copy via this helper cancels the pending clear
 *   immediately, so we never wipe content the user intentionally copied.
 *
 * The token is embedded in the `ClipDescription.label` field, which is
 * readable by any app but is not surfaced to users in any Android UI. It does
 * not leak the secret value — only a non-guessable identifier that lets us
 * claim ownership.
 *
 * On API 33+ the `IS_SENSITIVE` extra is set so the system hides the value
 * from IME clipboard previews and suggestion chips.
 */
object ClipboardHelper {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    private const val SENSITIVE_LABEL_PREFIX = "tabssh-secure-"

    // Label we stamped on the last sensitive clip; null when no sensitive clip is pending.
    private var lastSensitiveLabel: String? = null

    /**
     * Copy [text] to the system clipboard.
     *
     * @param label Human-readable label for non-sensitive clips (shown in some
     *              system clipboard UIs). Ignored for sensitive clips — the
     *              label is replaced with an internal ownership token.
     * @param sensitive When true, schedules auto-clear and marks the clip as
     *                  sensitive on API 33+. Use only for passwords and other
     *                  app-originated credentials, not for terminal output or
     *                  general text.
     */
    fun copy(context: Context, label: String, text: String, sensitive: Boolean = false) {
        // Use applicationContext so we never retain an Activity context across
        // the potentially multi-minute auto-clear window.
        val appCtx = context.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            val clipLabel: String
            if (sensitive) {
                // Stamp a unique token into the clip label so we can prove we
                // were the last writer when the auto-clear fires. If the user
                // or another app wrote anything after us the label won't match
                // and we leave the clipboard alone.
                val token = java.util.UUID.randomUUID().toString()
                clipLabel = "$SENSITIVE_LABEL_PREFIX$token"
                lastSensitiveLabel = clipLabel
            } else {
                // Non-sensitive write: cancel any pending clear immediately so
                // we never auto-wipe content the user just intentionally copied.
                cancelPendingClear()
                clipLabel = label
            }

            val clip = ClipData.newPlainText(clipLabel, text)
            if (sensitive && Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)

            if (sensitive) {
                scheduleClearIfRequested(appCtx, clipLabel)
            }
        } catch (e: Exception) {
            Logger.w("ClipboardHelper", "copy failed: ${e.message}")
        }
    }

    private fun cancelPendingClear() {
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        lastSensitiveLabel = null
    }

    private fun scheduleClearIfRequested(context: Context, sensitiveLabel: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val timeoutSec = prefs.getString("security_clear_clipboard_timeout", "0")
            ?.toIntOrNull() ?: 0
        if (timeoutSec <= 0) return

        // Cancel any previously-scheduled clear; newest sensitive copy wins.
        pendingClear?.let { handler.removeCallbacks(it) }

        val task = Runnable {
            pendingClear = null
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // Verify ownership by label — NOT by content. Content-matching would
                // incorrectly wipe a clip another app wrote with the same text.
                val currentLabel = cm.primaryClip?.description?.label?.toString()
                if (currentLabel == sensitiveLabel) {
                    // clearPrimaryClip() (API 28+) removes the clip without triggering
                    // the Android 13+ "Text copied" system popup that setPrimaryClip("")
                    // would produce.
                    if (Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                    Logger.d("ClipboardHelper", "Auto-cleared sensitive clipboard after ${timeoutSec}s")
                } else {
                    Logger.d("ClipboardHelper", "Clipboard changed since sensitive copy — not clearing (external write detected)")
                }
            } catch (e: Exception) {
                Logger.w("ClipboardHelper", "auto-clear failed: ${e.message}")
            }
        }
        pendingClear = task
        handler.postDelayed(task, timeoutSec * 1000L)
    }
}
