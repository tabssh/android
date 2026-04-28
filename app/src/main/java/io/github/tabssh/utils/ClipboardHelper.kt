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
 * Centralized clipboard writer with optional auto-clear.
 *
 * The `security_clear_clipboard_timeout` pref (seconds; 0 = never)
 * lets users wipe whatever was copied from the terminal after a delay,
 * so a stray screenshot or another app peeking at the clipboard later
 * can't recover it. Only the *current* primary clip is cleared, and
 * only if it still matches what we wrote — so if the user copied
 * something else in the meantime we leave their clipboard alone.
 *
 * On API 33+ we also set the "sensitive content" extra so the system
 * doesn't surface the value in clipboard preview chips.
 */
object ClipboardHelper {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun copy(context: Context, label: String, text: String, sensitive: Boolean = true) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            if (sensitive && Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            cm.setPrimaryClip(clip)
            scheduleClearIfRequested(context, text)
        } catch (e: Exception) {
            Logger.w("ClipboardHelper", "copy failed: ${e.message}")
        }
    }

    private fun scheduleClearIfRequested(context: Context, justCopied: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val timeoutStr = prefs.getString("security_clear_clipboard_timeout", "0") ?: "0"
        val timeoutSec = timeoutStr.toIntOrNull() ?: 0
        if (timeoutSec <= 0) return

        // Only one pending clear at a time — newest wins.
        pendingClear?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val current = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (current == justCopied) {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    Logger.d("ClipboardHelper", "Auto-cleared clipboard after ${timeoutSec}s")
                }
            } catch (e: Exception) {
                Logger.w("ClipboardHelper", "auto-clear failed: ${e.message}")
            }
        }
        pendingClear = task
        handler.postDelayed(task, timeoutSec * 1000L)
    }
}
