package com.tabssh.storage.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager as AndroidPreferenceManager
import com.tabssh.utils.logging.Logger

/**
 * Centralized preference management for TabSSH
 * Provides type-safe access to SharedPreferences with default values
 */
class PreferenceManager(private val context: Context) {
    
    private val preferences: SharedPreferences by lazy {
        AndroidPreferenceManager.getDefaultSharedPreferences(context)
    }
    
    companion object {
        // General preferences
        private const val KEY_STARTUP_BEHAVIOR = "general_startup_behavior"
        private const val KEY_AUTO_BACKUP = "general_auto_backup"
        private const val KEY_BACKUP_FREQUENCY = "general_backup_frequency"
        private const val KEY_LANGUAGE = "general_language"
        
        // Security preferences
        private const val KEY_PASSWORD_STORAGE_LEVEL = "security_password_storage_level"
        private const val KEY_REQUIRE_BIOMETRIC = "security_require_biometric"
        private const val KEY_PASSWORD_TTL = "security_password_ttl_hours"
        private const val KEY_AUTO_LOCK_ON_BACKGROUND = "security_auto_lock_background"
        private const val KEY_AUTO_LOCK_TIMEOUT = "security_auto_lock_timeout"
        private const val KEY_STRICT_HOST_KEY_CHECKING = "security_strict_host_key_checking"
        private const val KEY_PREVENT_SCREENSHOTS = "security_prevent_screenshots"
        private const val KEY_CLEAR_CLIPBOARD_TIMEOUT = "security_clear_clipboard_timeout"
        
        // Terminal preferences
        private const val KEY_THEME = "terminal_theme"
        private const val KEY_FONT_FAMILY = "terminal_font_family"
        private const val KEY_FONT_SIZE = "terminal_font_size"
        private const val KEY_LINE_SPACING = "terminal_line_spacing"
        private const val KEY_CURSOR_STYLE = "terminal_cursor_style"
        private const val KEY_CURSOR_BLINK = "terminal_cursor_blink"
        private const val KEY_SCROLLBACK_LINES = "terminal_scrollback_lines"
        private const val KEY_WORD_WRAP = "terminal_word_wrap"
        private const val KEY_COPY_ON_SELECT = "terminal_copy_on_select"
        private const val KEY_BELL_NOTIFICATION = "terminal_bell_notification"
        private const val KEY_BELL_VIBRATE = "terminal_bell_vibrate"
        private const val KEY_BELL_VISUAL = "terminal_bell_visual"
        
        // UI preferences
        private const val KEY_MAX_TABS = "ui_max_tabs"
        private const val KEY_CONFIRM_TAB_CLOSE = "ui_confirm_tab_close"
        private const val KEY_SHOW_FUNCTION_KEYS = "ui_show_function_keys"
        private const val KEY_FULLSCREEN_MODE = "ui_fullscreen_mode"
        private const val KEY_KEEP_SCREEN_ON = "ui_keep_screen_on"
        private const val KEY_APP_THEME = "ui_app_theme"
        private const val KEY_DYNAMIC_COLORS = "ui_dynamic_colors"
        
        // Connection preferences
        private const val KEY_DEFAULT_USERNAME = "connection_default_username"
        private const val KEY_DEFAULT_PORT = "connection_default_port"
        private const val KEY_CONNECT_TIMEOUT = "connection_connect_timeout"
        private const val KEY_AUTO_RECONNECT = "connection_auto_reconnect"
        private const val KEY_COMPRESSION = "connection_compression"
        private const val KEY_KEEP_ALIVE_INTERVAL = "connection_keep_alive_interval"
        
        // Accessibility preferences
        private const val KEY_HIGH_CONTRAST = "accessibility_high_contrast"
        private const val KEY_LARGE_TOUCH_TARGETS = "accessibility_large_touch_targets"
        private const val KEY_SCREEN_READER_ENABLED = "accessibility_screen_reader"
        
        // Defaults
        const val DEFAULT_STARTUP_BEHAVIOR = "last_session"
        const val DEFAULT_PASSWORD_STORAGE_LEVEL = "encrypted"
        const val DEFAULT_THEME = "dracula"
        const val DEFAULT_FONT_FAMILY = "Roboto Mono"
        const val DEFAULT_FONT_SIZE = 14f
        const val DEFAULT_APP_THEME = "system"
    }
    
    fun initialize() {
        Logger.d("PreferenceManager", "Initialized with ${preferences.all.size} preferences")
    }
    
    // General preferences
    fun getStartupBehavior(): String = getString(KEY_STARTUP_BEHAVIOR, DEFAULT_STARTUP_BEHAVIOR)
    fun setStartupBehavior(value: String) = setString(KEY_STARTUP_BEHAVIOR, value)
    
    fun isAutoBackupEnabled(): Boolean = getBoolean(KEY_AUTO_BACKUP, true)
    fun setAutoBackupEnabled(enabled: Boolean) = setBoolean(KEY_AUTO_BACKUP, enabled)
    
    fun getLanguage(): String = getString(KEY_LANGUAGE, "system")
    fun setLanguage(language: String) = setString(KEY_LANGUAGE, language)
    
    // Security preferences
    fun getPasswordStorageLevel(): String = getString(KEY_PASSWORD_STORAGE_LEVEL, DEFAULT_PASSWORD_STORAGE_LEVEL)
    fun setPasswordStorageLevel(level: String) = setString(KEY_PASSWORD_STORAGE_LEVEL, level)
    
    fun isRequireBiometricForSensitive(): Boolean = getBoolean(KEY_REQUIRE_BIOMETRIC, true)
    fun setRequireBiometricForSensitive(require: Boolean) = setBoolean(KEY_REQUIRE_BIOMETRIC, require)
    
    fun getPasswordTTLHours(): Int = getInt(KEY_PASSWORD_TTL, 24)
    fun setPasswordTTLHours(hours: Int) = setInt(KEY_PASSWORD_TTL, hours)
    
    fun isAutoLockOnBackground(): Boolean = getBoolean(KEY_AUTO_LOCK_ON_BACKGROUND, false)
    fun setAutoLockOnBackground(enabled: Boolean) = setBoolean(KEY_AUTO_LOCK_ON_BACKGROUND, enabled)
    
    fun isStrictHostKeyChecking(): Boolean = getBoolean(KEY_STRICT_HOST_KEY_CHECKING, true)
    fun setStrictHostKeyChecking(enabled: Boolean) = setBoolean(KEY_STRICT_HOST_KEY_CHECKING, enabled)
    
    fun isPreventScreenshots(): Boolean = getBoolean(KEY_PREVENT_SCREENSHOTS, false)
    fun setPreventScreenshots(prevent: Boolean) = setBoolean(KEY_PREVENT_SCREENSHOTS, prevent)
    
    fun getClearClipboardTimeout(): Int = getInt(KEY_CLEAR_CLIPBOARD_TIMEOUT, 60)
    fun setClearClipboardTimeout(seconds: Int) = setInt(KEY_CLEAR_CLIPBOARD_TIMEOUT, seconds)
    
    // Terminal preferences
    fun getTheme(): String = getString(KEY_THEME, DEFAULT_THEME)
    fun setTheme(theme: String) = setString(KEY_THEME, theme)
    
    fun getFontFamily(): String = getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY)
    fun setFontFamily(fontFamily: String) = setString(KEY_FONT_FAMILY, fontFamily)
    
    fun getFontSize(): Float = getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    fun setFontSize(size: Float) = setFloat(KEY_FONT_SIZE, size)
    
    fun getLineSpacing(): Float = getFloat(KEY_LINE_SPACING, 1.2f)
    fun setLineSpacing(spacing: Float) = setFloat(KEY_LINE_SPACING, spacing)
    
    fun getCursorStyle(): String = getString(KEY_CURSOR_STYLE, "block")
    fun setCursorStyle(style: String) = setString(KEY_CURSOR_STYLE, style)
    
    fun isCursorBlinkEnabled(): Boolean = getBoolean(KEY_CURSOR_BLINK, true)
    fun setCursorBlinkEnabled(enabled: Boolean) = setBoolean(KEY_CURSOR_BLINK, enabled)
    
    fun getScrollbackLines(): Int = getInt(KEY_SCROLLBACK_LINES, 1000)
    fun setScrollbackLines(lines: Int) = setInt(KEY_SCROLLBACK_LINES, lines)
    
    fun isBellNotificationEnabled(): Boolean = getBoolean(KEY_BELL_NOTIFICATION, true)
    fun setBellNotificationEnabled(enabled: Boolean) = setBoolean(KEY_BELL_NOTIFICATION, enabled)
    
    // UI preferences
    fun getMaxTabs(): Int = getInt(KEY_MAX_TABS, 10)
    fun setMaxTabs(maxTabs: Int) = setInt(KEY_MAX_TABS, maxTabs)
    
    fun isConfirmTabClose(): Boolean = getBoolean(KEY_CONFIRM_TAB_CLOSE, true)
    fun setConfirmTabClose(confirm: Boolean) = setBoolean(KEY_CONFIRM_TAB_CLOSE, confirm)
    
    fun isShowFunctionKeys(): Boolean = getBoolean(KEY_SHOW_FUNCTION_KEYS, true)
    fun setShowFunctionKeys(show: Boolean) = setBoolean(KEY_SHOW_FUNCTION_KEYS, show)
    
    fun isFullscreenMode(): Boolean = getBoolean(KEY_FULLSCREEN_MODE, false)
    fun setFullscreenMode(fullscreen: Boolean) = setBoolean(KEY_FULLSCREEN_MODE, fullscreen)
    
    fun isKeepScreenOn(): Boolean = getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(keepOn: Boolean) = setBoolean(KEY_KEEP_SCREEN_ON, keepOn)
    
    fun getAppTheme(): String = getString(KEY_APP_THEME, DEFAULT_APP_THEME)
    fun setAppTheme(theme: String) = setString(KEY_APP_THEME, theme)
    
    fun isDynamicColors(): Boolean = getBoolean(KEY_DYNAMIC_COLORS, true)
    fun setDynamicColors(enabled: Boolean) = setBoolean(KEY_DYNAMIC_COLORS, enabled)
    
    // Connection preferences
    fun getDefaultUsername(): String = getString(KEY_DEFAULT_USERNAME, System.getProperty("user.name", ""))
    fun setDefaultUsername(username: String) = setString(KEY_DEFAULT_USERNAME, username)
    
    fun getDefaultPort(): Int = getInt(KEY_DEFAULT_PORT, 22)
    fun setDefaultPort(port: Int) = setInt(KEY_DEFAULT_PORT, port)
    
    fun getConnectTimeout(): Int = getInt(KEY_CONNECT_TIMEOUT, 15)
    fun setConnectTimeout(timeout: Int) = setInt(KEY_CONNECT_TIMEOUT, timeout)
    
    fun isAutoReconnect(): Boolean = getBoolean(KEY_AUTO_RECONNECT, true)
    fun setAutoReconnect(enabled: Boolean) = setBoolean(KEY_AUTO_RECONNECT, enabled)
    
    fun isCompressionEnabled(): Boolean = getBoolean(KEY_COMPRESSION, true)
    fun setCompressionEnabled(enabled: Boolean) = setBoolean(KEY_COMPRESSION, enabled)
    
    // Accessibility preferences
    fun isHighContrastMode(): Boolean = getBoolean(KEY_HIGH_CONTRAST, false)
    fun setHighContrastMode(enabled: Boolean) = setBoolean(KEY_HIGH_CONTRAST, enabled)
    
    fun isLargeTouchTargets(): Boolean = getBoolean(KEY_LARGE_TOUCH_TARGETS, false)
    fun setLargeTouchTargets(enabled: Boolean) = setBoolean(KEY_LARGE_TOUCH_TARGETS, enabled)
    
    // Helper methods
    private fun getString(key: String, defaultValue: String): String {
        return preferences.getString(key, defaultValue) ?: defaultValue
    }
    
    private fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    
    private fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }
    
    private fun setBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
    
    private fun getInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }
    
    private fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }
    
    private fun getFloat(key: String, defaultValue: Float): Float {
        return preferences.getFloat(key, defaultValue)
    }
    
    private fun setFloat(key: String, value: Float) {
        preferences.edit().putFloat(key, value).apply()
    }
    
    /**
     * Clear all preferences (factory reset)
     */
    fun clearAll() {
        preferences.edit().clear().apply()
        Logger.i("PreferenceManager", "All preferences cleared")
    }
    
    /**
     * Export preferences as JSON for backup
     */
    fun exportPreferences(): String {
        val allPrefs = preferences.all
        return kotlinx.serialization.json.Json.encodeToString(allPrefs)
    }
}