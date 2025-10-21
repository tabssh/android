package io.github.tabssh.ui.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * UI utility functions for TabSSH
 * Provides common UI operations and Material Design helpers
 */
object UIHelper {

    /**
     * Convert dp to pixels
     */
    fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Convert sp to pixels
     */
    fun spToPx(context: Context, sp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Get theme color attribute
     */
    @ColorInt
    fun getThemeColor(context: Context, @AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    /**
     * Check if device is in dark mode
     */
    fun isDarkMode(context: Context): Boolean {
        return when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }

    /**
     * Hide soft keyboard
     */
    fun hideKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * Show soft keyboard
     */
    fun showKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Calculate contrast ratio between two colors
     */
    fun calculateContrastRatio(color1: Int, color2: Int): Double {
        val luminance1 = calculateLuminance(color1)
        val luminance2 = calculateLuminance(color2)

        val lighter = maxOf(luminance1, luminance2)
        val darker = minOf(luminance1, luminance2)

        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Calculate relative luminance of a color
     */
    private fun calculateLuminance(color: Int): Double {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0

        val r = if (red <= 0.03928) red / 12.92 else Math.pow((red + 0.055) / 1.055, 2.4)
        val g = if (green <= 0.03928) green / 12.92 else Math.pow((green + 0.055) / 1.055, 2.4)
        val b = if (blue <= 0.03928) blue / 12.92 else Math.pow((blue + 0.055) / 1.055, 2.4)

        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Check if a color meets WCAG accessibility standards
     */
    fun meetsAccessibilityStandards(foreground: Int, background: Int, level: AccessibilityLevel = AccessibilityLevel.AA): Boolean {
        val contrast = calculateContrastRatio(foreground, background)
        return when (level) {
            AccessibilityLevel.AA -> contrast >= 4.5
            AccessibilityLevel.AAA -> contrast >= 7.0
        }
    }

    /**
     * Get a high contrast version of a color
     */
    fun getHighContrastColor(color: Int, isDark: Boolean): Int {
        return if (isDark) {
            // For dark mode, make colors brighter
            Color.rgb(
                minOf(255, (Color.red(color) * 1.5).toInt()),
                minOf(255, (Color.green(color) * 1.5).toInt()),
                minOf(255, (Color.blue(color) * 1.5).toInt())
            )
        } else {
            // For light mode, make colors darker
            Color.rgb(
                maxOf(0, (Color.red(color) * 0.7).toInt()),
                maxOf(0, (Color.green(color) * 0.7).toInt()),
                maxOf(0, (Color.blue(color) * 0.7).toInt())
            )
        }
    }

    /**
     * Format file size in human readable format
     */
    fun formatFileSize(bytes: Long): String {
        val kilobyte = 1024
        val megabyte = kilobyte * 1024
        val gigabyte = megabyte * 1024

        return when {
            bytes >= gigabyte -> "%.1f GB".format(bytes.toDouble() / gigabyte)
            bytes >= megabyte -> "%.1f MB".format(bytes.toDouble() / megabyte)
            bytes >= kilobyte -> "%.1f KB".format(bytes.toDouble() / kilobyte)
            else -> "$bytes B"
        }
    }

    enum class AccessibilityLevel {
        AA, AAA
    }
}