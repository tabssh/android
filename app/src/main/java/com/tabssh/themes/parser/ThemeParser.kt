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
    
    /**
     * Parse theme from VS Code theme format
     */
    fun parseFromVSCodeTheme(jsonString: String): Theme? {
        return try {
            // VS Code theme format is more complex - this is a simplified parser
            val vscodeData = json.decodeFromString<VSCodeThemeData>(jsonString)
            convertVSCodeToTheme(vscodeData)
        } catch (e: Exception) {
            Logger.e("ThemeParser", "Failed to parse VS Code theme", e)
            null
        }
    }
    
    /**
     * Parse theme from iTerm2 color scheme
     */
    fun parseFromITermScheme(xmlString: String): Theme? {
        // iTerm2 uses XML plist format - would need XML parsing
        Logger.w("ThemeParser", "iTerm2 theme parsing not implemented")
        return null
    }
    
    /**
     * Parse theme from terminal.sexy JSON format
     */
    fun parseFromTerminalSexy(jsonString: String): Theme? {
        return try {
            val terminalSexyData = json.decodeFromString<TerminalSexyData>(jsonString)
            convertTerminalSexyToTheme(terminalSexyData)
        } catch (e: Exception) {
            Logger.e("ThemeParser", "Failed to parse Terminal.sexy theme", e)
            null
        }
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
    
    private fun convertVSCodeToTheme(data: VSCodeThemeData): Theme? {
        // Simplified VS Code theme conversion
        val colors = data.colors ?: return null
        
        val background = colors["terminal.background"] ?: colors["editor.background"] ?: "#000000"
        val foreground = colors["terminal.foreground"] ?: colors["editor.foreground"] ?: "#ffffff"
        
        // Map VS Code terminal colors to ANSI colors
        val ansiColors = IntArray(16) { index ->
            when (index) {
                0 -> hexToColor(colors["terminal.ansiBlack"] ?: "#000000")
                1 -> hexToColor(colors["terminal.ansiRed"] ?: "#cd0000")
                2 -> hexToColor(colors["terminal.ansiGreen"] ?: "#00cd00")
                3 -> hexToColor(colors["terminal.ansiYellow"] ?: "#cdcd00")
                4 -> hexToColor(colors["terminal.ansiBlue"] ?: "#0000ee")
                5 -> hexToColor(colors["terminal.ansiMagenta"] ?: "#cd00cd")
                6 -> hexToColor(colors["terminal.ansiCyan"] ?: "#00cdcd")
                7 -> hexToColor(colors["terminal.ansiWhite"] ?: "#e5e5e5")
                8 -> hexToColor(colors["terminal.ansiBrightBlack"] ?: "#7f7f7f")
                9 -> hexToColor(colors["terminal.ansiBrightRed"] ?: "#ff0000")
                10 -> hexToColor(colors["terminal.ansiBrightGreen"] ?: "#00ff00")
                11 -> hexToColor(colors["terminal.ansiBrightYellow"] ?: "#ffff00")
                12 -> hexToColor(colors["terminal.ansiBrightBlue"] ?: "#5c5cff")
                13 -> hexToColor(colors["terminal.ansiBrightMagenta"] ?: "#ff00ff")
                14 -> hexToColor(colors["terminal.ansiBrightCyan"] ?: "#00ffff")
                15 -> hexToColor(colors["terminal.ansiBrightWhite"] ?: "#ffffff")
                else -> hexToColor(foreground)
            }
        }
        
        return Theme(
            id = generateId(data.name ?: "VS Code Theme"),
            name = data.name ?: "VS Code Theme",
            author = "VS Code",
            isDark = data.type == "dark",
            isBuiltIn = false,
            background = hexToColor(background),
            foreground = hexToColor(foreground),
            cursor = hexToColor(colors["terminalCursor.foreground"] ?: foreground),
            selection = hexToColor(colors["terminal.selectionBackground"] ?: "#404040"),
            highlight = hexToColor(colors["terminal.findMatchHighlightBackground"] ?: "#404040"),
            ansiColors = ansiColors
        )
    }
    
    private fun convertTerminalSexyToTheme(data: TerminalSexyData): Theme {
        return Theme(
            id = generateId(data.name),
            name = data.name,
            author = data.author,
            isDark = guessIfDark(hexToColor(data.background)),
            isBuiltIn = false,
            background = hexToColor(data.background),
            foreground = hexToColor(data.foreground),
            cursor = hexToColor(data.cursor ?: data.foreground),
            selection = hexToColor("#404040"), // Terminal.sexy doesn't include selection color
            highlight = hexToColor("#404040"),
            ansiColors = intArrayOf(
                hexToColor(data.color0),
                hexToColor(data.color1),
                hexToColor(data.color2),
                hexToColor(data.color3),
                hexToColor(data.color4),
                hexToColor(data.color5),
                hexToColor(data.color6),
                hexToColor(data.color7),
                hexToColor(data.color8),
                hexToColor(data.color9),
                hexToColor(data.color10),
                hexToColor(data.color11),
                hexToColor(data.color12),
                hexToColor(data.color13),
                hexToColor(data.color14),
                hexToColor(data.color15)
            )
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

@Serializable
data class VSCodeThemeData(
    val name: String? = null,
    val type: String? = null,
    val colors: Map<String, String>? = null
)

@Serializable
data class TerminalSexyData(
    val name: String,
    val author: String? = null,
    val background: String,
    val foreground: String,
    val cursor: String? = null,
    val color0: String, val color1: String, val color2: String, val color3: String,
    val color4: String, val color5: String, val color6: String, val color7: String,
    val color8: String, val color9: String, val color10: String, val color11: String,
    val color12: String, val color13: String, val color14: String, val color15: String
)