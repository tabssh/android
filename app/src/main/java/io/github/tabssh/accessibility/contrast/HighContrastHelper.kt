package io.github.tabssh.accessibility.contrast

import io.github.tabssh.storage.database.entities.ThemeDefinition
import org.json.JSONArray

class HighContrastHelper {

    fun createHighContrastTheme(): ThemeDefinition {
        val ansiColors = JSONArray().apply {
            put("#000000") // Black
            put("#FF0000") // Red
            put("#00FF00") // Green
            put("#FFFF00") // Yellow
            put("#0000FF") // Blue
            put("#FF00FF") // Magenta
            put("#00FFFF") // Cyan
            put("#FFFFFF") // White
            // Bright colors
            put("#808080") // Bright Black
            put("#FF8080") // Bright Red
            put("#80FF80") // Bright Green
            put("#FFFF80") // Bright Yellow
            put("#8080FF") // Bright Blue
            put("#FF80FF") // Bright Magenta
            put("#80FFFF") // Bright Cyan
            put("#FFFFFF") // Bright White
        }

        return ThemeDefinition(
            themeId = "high_contrast",
            name = "High Contrast",
            author = "System",
            isDark = false,
            isBuiltIn = true,
            backgroundColor = 0xFF000000.toInt(),
            foregroundColor = 0xFFFFFFFF.toInt(),
            cursorColor = 0xFFFFFFFF.toInt(),
            selectionColor = 0x80FFFF00.toInt(),
            ansiColors = ansiColors.toString(),
            createdAt = System.currentTimeMillis()
        )
    }
}
