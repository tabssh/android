package io.github.tabssh.themes.parser

import io.github.tabssh.themes.definitions.Theme
import io.github.tabssh.utils.logging.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Parses theme files and converts between formats
 * Supports JSON theme format and various terminal theme formats
 */
class ThemeParser {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    
    /**
     * Parse theme from JSON string
     */
    fun parseThemeFromJson(jsonString: String): Theme? {
        return try {
            val themeData = json.decodeFromString<ThemeJsonData>(jsonString)
            convertJsonDataToTheme(themeData)
        } catch (e: Exception) {
            Logger.e("ThemeParser", "Failed to parse theme from JSON", e)
            null
        }
    }
    
    /**
     * Convert theme to JSON string
     */
    fun themeToJson(theme: Theme): String {
        val themeData = ThemeJsonData(
            id = theme.id,
            name = theme.name,
            author = theme.author,
            version = theme.version,
            isDark = theme.isDark,
            background = colorToHex(theme.background),
            foreground = colorToHex(theme.foreground),
            cursor = colorToHex(theme.cursor),
            selection = colorToHex(theme.selection),
            highlight = colorToHex(theme.highlight),
            ansiColors = theme.ansiColors.map { colorToHex(it) },
            ui = theme.primary?.let { primary ->
                UIColors(
                    primary = colorToHex(primary),
                    primaryVariant = theme.primaryVariant?.let { colorToHex(it) },
                    secondary = theme.secondary?.let { colorToHex(it) },
                    surface = theme.surface?.let { colorToHex(it) },
                    onPrimary = theme.onPrimary?.let { colorToHex(it) },
                    onSecondary = theme.onSecondary?.let { colorToHex(it) },
                    onSurface = theme.onSurface?.let { colorToHex(it) }
                )
            }
        )
        
        return json.encodeToString(themeData)
    }
    
    private fun convertJsonDataToTheme(data: ThemeJsonData): Theme {
        val ansiColors = data.ansiColors.map { hexToColor(it) }.toIntArray()
        
        return Theme(
            id = data.id ?: generateId(data.name),
            name = data.name,
            author = data.author,
            version = data.version ?: "1.0",
            isDark = data.isDark ?: guessIfDark(hexToColor(data.background)),
            isBuiltIn = false,
            background = hexToColor(data.background),
            foreground = hexToColor(data.foreground),
            cursor = hexToColor(data.cursor ?: data.foreground),
            selection = hexToColor(data.selection ?: "#404040"),
            highlight = hexToColor(data.highlight ?: data.selection ?: "#404040"),
            ansiColors = ansiColors,
            primary = data.ui?.primary?.let { hexToColor(it) },
            secondary = data.ui?.secondary?.let { hexToColor(it) },
            surface = data.ui?.surface?.let { hexToColor(it) },
            onPrimary = data.ui?.onPrimary?.let { hexToColor(it) },
            onSurface = data.ui?.onSurface?.let { hexToColor(it) }
        )
    }
    
    private fun colorToHex(color: Int): String {
        return "#${String.format("%06X", color and 0xFFFFFF)}"
    }
    
    private fun hexToColor(hex: String): Int {
        val cleanHex = hex.removePrefix("#").removePrefix("0x")
        return when (cleanHex.length) {
            6 -> (0xFF000000.toInt()) or cleanHex.toInt(16)
            8 -> cleanHex.toLong(16).toInt()
            3 -> {
                // Short hex format #RGB -> #RRGGBB
                val r = cleanHex[0]
                val g = cleanHex[1]
                val b = cleanHex[2]
                hexToColor("#$r$r$g$g$b$b")
            }
            else -> 0xFF000000.toInt() // Default to black
        }
    }
    
    private fun guessIfDark(backgroundColor: Int): Boolean {
        val r = (backgroundColor shr 16) and 0xFF
        val g = (backgroundColor shr 8) and 0xFF
        val b = backgroundColor and 0xFF
        val brightness = (r + g + b) / 3
        return brightness < 128
    }
    
    private fun generateId(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }
}

// Data classes for JSON parsing

@Serializable
data class ThemeJsonData(
    val id: String? = null,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val isDark: Boolean? = null,
    val background: String,
    val foreground: String,
    val cursor: String? = null,
    val selection: String? = null,
    val highlight: String? = null,
    val ansiColors: List<String>,
    val ui: UIColors? = null
)

@Serializable
data class UIColors(
    val primary: String? = null,
    val primaryVariant: String? = null,
    val secondary: String? = null,
    val surface: String? = null,
    val onPrimary: String? = null,
    val onSecondary: String? = null,
    val onSurface: String? = null
)

