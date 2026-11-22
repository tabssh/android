package io.github.tabssh.ui.utils

import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager

/**
 * Which screens refuse screenshots and screen recording (FLAG_SECURE), by the user's chosen level:
 * - [MODE_DISABLED]: every screen.
 * - [MODE_LIMITED]: screens that show secrets, and Settings > App Security.
 * - [MODE_ALLOW]: only Settings > App Security.
 * The PIN screen is blocked at every level.
 */
object ScreenshotPolicy {

    const val KEY_MODE = "security_screenshot_mode"
    private const val LEGACY_KEY_PREVENT = "security_prevent_screenshots"

    const val MODE_DISABLED = "disabled"
    const val MODE_LIMITED = "limited"
    const val MODE_ALLOW = "allow"

    enum class Screen { PIN, SECRET, APP_SECURITY, NORMAL }

    /** The saved level; before one was ever chosen, the old "Prevent screenshots" switch decides. */
    fun modeFrom(prefs: SharedPreferences): String {
        val stored = try { prefs.getString(KEY_MODE, null) } catch (_: ClassCastException) { null }
        if (stored == MODE_DISABLED || stored == MODE_LIMITED || stored == MODE_ALLOW) return stored
        val legacyPrevent = try { prefs.getBoolean(LEGACY_KEY_PREVENT, false) } catch (_: ClassCastException) { false }
        return if (legacyPrevent) MODE_DISABLED else MODE_LIMITED
    }

    fun shouldBlock(mode: String, screen: Screen): Boolean = when (screen) {
        Screen.PIN -> true
        Screen.APP_SECURITY -> true
        Screen.SECRET -> mode != MODE_ALLOW
        Screen.NORMAL -> mode == MODE_DISABLED
    }

    fun apply(window: Window?, mode: String, screen: Screen) {
        if (window == null) return
        if (shouldBlock(mode, screen)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
