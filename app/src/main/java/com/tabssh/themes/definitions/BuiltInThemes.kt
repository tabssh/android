package com.tabssh.themes.definitions

/**
 * All 12 built-in themes for TabSSH
 * Includes popular terminal themes with proper color accessibility
 */
object BuiltInThemes {
    
    /**
     * Get all built-in themes
     */
    fun getAllThemes(): List<Theme> {
        return listOf(
            dracula(),
            solarizedDark(),
            solarizedLight(),
            nord(),
            oneDark(),
            monokai(),
            gruvboxDark(),
            gruvboxLight(),
            tomorrowNight(),
            githubLight(),
            atomOneDark(),
            materialDark()
        )
    }
    
    /**
     * Get theme by ID
     */
    fun getThemeById(id: String): Theme? {
        return getAllThemes().find { it.id == id }
    }
    
    /**
     * Get dark themes
     */
    fun getDarkThemes(): List<Theme> {
        return getAllThemes().filter { it.isDark }
    }
    
    /**
     * Get light themes  
     */
    fun getLightThemes(): List<Theme> {
        return getAllThemes().filter { !it.isDark }
    }
    
    // Theme definitions
    
    fun dracula(): Theme {
        return Theme(
            id = "dracula",
            name = "Dracula",
            author = "Dracula Theme",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF282A36.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFFF8F8F2.toInt(),
            selection = 0x4444475A.toInt(),
            highlight = 0xFF50FA7B.toInt(),
            ansiColors = intArrayOf(
                0xFF21222C.toInt(), // Black
                0xFFFF5555.toInt(), // Red
                0xFF50FA7B.toInt(), // Green
                0xFFF1FA8C.toInt(), // Yellow
                0xFFBD93F9.toInt(), // Blue
                0xFFFF79C6.toInt(), // Magenta
                0xFF8BE9FD.toInt(), // Cyan
                0xFFF8F8F2.toInt(), // White
                0xFF6272A4.toInt(), // Bright Black
                0xFFFF6E6E.toInt(), // Bright Red
                0xFF69FF94.toInt(), // Bright Green
                0xFFFFFFA5.toInt(), // Bright Yellow
                0xFFD6ACFF.toInt(), // Bright Blue
                0xFFFF92DF.toInt(), // Bright Magenta
                0xFFA4FFFF.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    fun solarizedDark(): Theme {
        return Theme(
            id = "solarized_dark",
            name = "Solarized Dark",
            author = "Ethan Schoonover",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF002B36.toInt(),
            foreground = 0xFF839496.toInt(),
            cursor = 0xFF93A1A1.toInt(),
            selection = 0x44073642.toInt(),
            highlight = 0xFFB58900.toInt(),
            ansiColors = intArrayOf(
                0xFF073642.toInt(), // Black
                0xFFDC322F.toInt(), // Red
                0xFF859900.toInt(), // Green
                0xFFB58900.toInt(), // Yellow
                0xFF268BD2.toInt(), // Blue
                0xFFD33682.toInt(), // Magenta
                0xFF2AA198.toInt(), // Cyan
                0xFFEEE8D5.toInt(), // White
                0xFF002B36.toInt(), // Bright Black
                0xFFCB4B16.toInt(), // Bright Red
                0xFF586E75.toInt(), // Bright Green
                0xFF657B83.toInt(), // Bright Yellow
                0xFF839496.toInt(), // Bright Blue
                0xFF6C71C4.toInt(), // Bright Magenta
                0xFF93A1A1.toInt(), // Bright Cyan
                0xFFFDF6E3.toInt()  // Bright White
            )
        )
    }
    
    fun solarizedLight(): Theme {
        return Theme(
            id = "solarized_light",
            name = "Solarized Light",
            author = "Ethan Schoonover",
            isDark = false,
            isBuiltIn = true,
            background = 0xFFFDF6E3.toInt(),
            foreground = 0xFF657B83.toInt(),
            cursor = 0xFF586E75.toInt(),
            selection = 0x44EEE8D5.toInt(),
            highlight = 0xFFB58900.toInt(),
            ansiColors = intArrayOf(
                0xFF073642.toInt(), // Black
                0xFFDC322F.toInt(), // Red
                0xFF859900.toInt(), // Green
                0xFFB58900.toInt(), // Yellow
                0xFF268BD2.toInt(), // Blue
                0xFFD33682.toInt(), // Magenta
                0xFF2AA198.toInt(), // Cyan
                0xFFEEE8D5.toInt(), // White
                0xFF002B36.toInt(), // Bright Black
                0xFFCB4B16.toInt(), // Bright Red
                0xFF586E75.toInt(), // Bright Green
                0xFF657B83.toInt(), // Bright Yellow
                0xFF839496.toInt(), // Bright Blue
                0xFF6C71C4.toInt(), // Bright Magenta
                0xFF93A1A1.toInt(), // Bright Cyan
                0xFFFDF6E3.toInt()  // Bright White
            )
        )
    }
    
    fun nord(): Theme {
        return Theme(
            id = "nord",
            name = "Nord",
            author = "Arctic Ice Studio",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF2E3440.toInt(),
            foreground = 0xFFD8DEE9.toInt(),
            cursor = 0xFFD8DEE9.toInt(),
            selection = 0x44434C5E.toInt(),
            highlight = 0xFF88C0D0.toInt(),
            ansiColors = intArrayOf(
                0xFF3B4252.toInt(), // Black
                0xFFBF616A.toInt(), // Red
                0xFFA3BE8C.toInt(), // Green
                0xFFEBCB8B.toInt(), // Yellow
                0xFF81A1C1.toInt(), // Blue
                0xFFB48EAD.toInt(), // Magenta
                0xFF88C0D0.toInt(), // Cyan
                0xFFE5E9F0.toInt(), // White
                0xFF4C566A.toInt(), // Bright Black
                0xFFBF616A.toInt(), // Bright Red
                0xFFA3BE8C.toInt(), // Bright Green
                0xFFEBCB8B.toInt(), // Bright Yellow
                0xFF81A1C1.toInt(), // Bright Blue
                0xFFB48EAD.toInt(), // Bright Magenta
                0xFF8FBCBB.toInt(), // Bright Cyan
                0xFFECEFF4.toInt()  // Bright White
            )
        )
    }
    
    fun oneDark(): Theme {
        return Theme(
            id = "one_dark",
            name = "One Dark",
            author = "Atom",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF282C34.toInt(),
            foreground = 0xFFABB2BF.toInt(),
            cursor = 0xFFABB2BF.toInt(),
            selection = 0x44404859.toInt(),
            highlight = 0xFFE5C07B.toInt(),
            ansiColors = intArrayOf(
                0xFF282C34.toInt(), // Black
                0xFFE06C75.toInt(), // Red
                0xFF98C379.toInt(), // Green
                0xFFE5C07B.toInt(), // Yellow
                0xFF61AFEF.toInt(), // Blue
                0xFFC678DD.toInt(), // Magenta
                0xFF56B6C2.toInt(), // Cyan
                0xFFABB2BF.toInt(), // White
                0xFF3E4451.toInt(), // Bright Black
                0xFFE06C75.toInt(), // Bright Red
                0xFF98C379.toInt(), // Bright Green
                0xFFE5C07B.toInt(), // Bright Yellow
                0xFF61AFEF.toInt(), // Bright Blue
                0xFFC678DD.toInt(), // Bright Magenta
                0xFF56B6C2.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    fun monokai(): Theme {
        return Theme(
            id = "monokai",
            name = "Monokai",
            author = "Monokai",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF272822.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFFF8F8F2.toInt(),
            selection = 0x4449483E.toInt(),
            highlight = 0xFFE6DB74.toInt(),
            ansiColors = intArrayOf(
                0xFF272822.toInt(), // Black
                0xFFF92672.toInt(), // Red
                0xFFA6E22E.toInt(), // Green
                0xFFE6DB74.toInt(), // Yellow
                0xFF66D9EF.toInt(), // Blue
                0xFFAE81FF.toInt(), // Magenta
                0xFF2AA198.toInt(), // Cyan
                0xFFF8F8F2.toInt(), // White
                0xFF75715E.toInt(), // Bright Black
                0xFFF92672.toInt(), // Bright Red
                0xFFA6E22E.toInt(), // Bright Green
                0xFFE6DB74.toInt(), // Bright Yellow
                0xFF66D9EF.toInt(), // Bright Blue
                0xFFAE81FF.toInt(), // Bright Magenta
                0xFF2AA198.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    fun gruvboxDark(): Theme {
        return Theme(
            id = "gruvbox_dark",
            name = "Gruvbox Dark",
            author = "Pavel Pertsev",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF282828.toInt(),
            foreground = 0xFFEBDBB2.toInt(),
            cursor = 0xFFEBDBB2.toInt(),
            selection = 0x443C3836.toInt(),
            highlight = 0xFFB8BB26.toInt(),
            ansiColors = intArrayOf(
                0xFF282828.toInt(), // Black
                0xFFCC241D.toInt(), // Red
                0xFF98971A.toInt(), // Green
                0xFFD79921.toInt(), // Yellow
                0xFF458588.toInt(), // Blue
                0xFFB16286.toInt(), // Magenta
                0xFF689D6A.toInt(), // Cyan
                0xFFA89984.toInt(), // White
                0xFF928374.toInt(), // Bright Black
                0xFFFB4934.toInt(), // Bright Red
                0xFFB8BB26.toInt(), // Bright Green
                0xFFFABD2F.toInt(), // Bright Yellow
                0xFF83A598.toInt(), // Bright Blue
                0xFFD3869B.toInt(), // Bright Magenta
                0xFF8EC07C.toInt(), // Bright Cyan
                0xFFEBDBB2.toInt()  // Bright White
            )
        )
    }
    
    fun gruvboxLight(): Theme {
        return Theme(
            id = "gruvbox_light",
            name = "Gruvbox Light",
            author = "Pavel Pertsev",
            isDark = false,
            isBuiltIn = true,
            background = 0xFFFBF1C7.toInt(),
            foreground = 0xFF3C3836.toInt(),
            cursor = 0xFF3C3836.toInt(),
            selection = 0x44EBDBB2.toInt(),
            highlight = 0xFF98971A.toInt(),
            ansiColors = intArrayOf(
                0xFFFBF1C7.toInt(), // Black
                0xFFCC241D.toInt(), // Red
                0xFF98971A.toInt(), // Green
                0xFFD79921.toInt(), // Yellow
                0xFF458588.toInt(), // Blue
                0xFFB16286.toInt(), // Magenta
                0xFF689D6A.toInt(), // Cyan
                0xFF7C6F64.toInt(), // White
                0xFF928374.toInt(), // Bright Black
                0xFF9D0006.toInt(), // Bright Red
                0xFF79740E.toInt(), // Bright Green
                0xFFB57614.toInt(), // Bright Yellow
                0xFF076678.toInt(), // Bright Blue
                0xFF8F3F71.toInt(), // Bright Magenta
                0xFF427B58.toInt(), // Bright Cyan
                0xFF3C3836.toInt()  // Bright White
            )
        )
    }
    
    fun tomorrowNight(): Theme {
        return Theme(
            id = "tomorrow_night",
            name = "Tomorrow Night",
            author = "Chris Kempson",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF1D1F21.toInt(),
            foreground = 0xFFC5C8C6.toInt(),
            cursor = 0xFFC5C8C6.toInt(),
            selection = 0x44373B41.toInt(),
            highlight = 0xFFF0C674.toInt(),
            ansiColors = intArrayOf(
                0xFF1D1F21.toInt(), // Black
                0xFFCC6666.toInt(), // Red
                0xFFB5BD68.toInt(), // Green
                0xFFF0C674.toInt(), // Yellow
                0xFF81A2BE.toInt(), // Blue
                0xFFB294BB.toInt(), // Magenta
                0xFF8ABEB7.toInt(), // Cyan
                0xFFC5C8C6.toInt(), // White
                0xFF969896.toInt(), // Bright Black
                0xFFCC6666.toInt(), // Bright Red
                0xFFB5BD68.toInt(), // Bright Green
                0xFFF0C674.toInt(), // Bright Yellow
                0xFF81A2BE.toInt(), // Bright Blue
                0xFFB294BB.toInt(), // Bright Magenta
                0xFF8ABEB7.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    fun githubLight(): Theme {
        return Theme(
            id = "github_light",
            name = "GitHub Light",
            author = "GitHub",
            isDark = false,
            isBuiltIn = true,
            background = 0xFFFFFFFF.toInt(),
            foreground = 0xFF24292E.toInt(),
            cursor = 0xFF24292E.toInt(),
            selection = 0x44C6E2F1.toInt(),
            highlight = 0xFFFFF8DC.toInt(),
            ansiColors = intArrayOf(
                0xFF24292E.toInt(), // Black
                0xFFD73A49.toInt(), // Red
                0xFF28A745.toInt(), // Green
                0xFFFFAB00.toInt(), // Yellow
                0xFF0366D6.toInt(), // Blue
                0xFF5A32A3.toInt(), // Magenta
                0xFF17A2B8.toInt(), // Cyan
                0xFF6A737D.toInt(), // White
                0xFF959DA5.toInt(), // Bright Black
                0xFFD73A49.toInt(), // Bright Red
                0xFF28A745.toInt(), // Bright Green
                0xFFFFAB00.toInt(), // Bright Yellow
                0xFF0366D6.toInt(), // Bright Blue
                0xFF5A32A3.toInt(), // Bright Magenta
                0xFF17A2B8.toInt(), // Bright Cyan
                0xFF24292E.toInt()  // Bright White
            )
        )
    }
    
    fun atomOneDark(): Theme {
        return Theme(
            id = "atom_one_dark",
            name = "Atom One Dark",
            author = "Atom",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF282C34.toInt(),
            foreground = 0xFFABB2BF.toInt(),
            cursor = 0xFFABB2BF.toInt(),
            selection = 0x443E4451.toInt(),
            highlight = 0xFFE5C07B.toInt(),
            ansiColors = intArrayOf(
                0xFF282C34.toInt(), // Black
                0xFFE06C75.toInt(), // Red
                0xFF98C379.toInt(), // Green
                0xFFE5C07B.toInt(), // Yellow
                0xFF61AFEF.toInt(), // Blue
                0xFFC678DD.toInt(), // Magenta
                0xFF56B6C2.toInt(), // Cyan
                0xFFABB2BF.toInt(), // White
                0xFF5C6370.toInt(), // Bright Black
                0xFFE06C75.toInt(), // Bright Red
                0xFF98C379.toInt(), // Bright Green
                0xFFE5C07B.toInt(), // Bright Yellow
                0xFF61AFEF.toInt(), // Bright Blue
                0xFFC678DD.toInt(), // Bright Magenta
                0xFF56B6C2.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    fun materialDark(): Theme {
        return Theme(
            id = "material_dark",
            name = "Material Dark",
            author = "Google",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF121212.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            selection = 0x44BB86FC.toInt(),
            highlight = 0xFFBB86FC.toInt(),
            ansiColors = intArrayOf(
                0xFF000000.toInt(), // Black
                0xFFF44336.toInt(), // Red
                0xFF4CAF50.toInt(), // Green
                0xFFFFEB3B.toInt(), // Yellow
                0xFF2196F3.toInt(), // Blue
                0xFF9C27B0.toInt(), // Magenta
                0xFF00BCD4.toInt(), // Cyan
                0xFFFFFFFF.toInt(), // White
                0xFF757575.toInt(), // Bright Black
                0xFFEF5350.toInt(), // Bright Red
                0xFF66BB6A.toInt(), // Bright Green
                0xFFFFEE58.toInt(), // Bright Yellow
                0xFF42A5F5.toInt(), // Bright Blue
                0xFFAB47BC.toInt(), // Bright Magenta
                0xFF26C6DA.toInt(), // Bright Cyan
                0xFFFFFFFF.toInt()  // Bright White
            )
        )
    }
    
    /**
     * Default theme based on system settings
     */
    fun getDefaultTheme(isDarkMode: Boolean): Theme {
        return if (isDarkMode) dracula() else githubLight()
    }
    
    /**
     * Get theme recommendations based on accessibility needs
     */
    fun getAccessibleThemes(): List<Theme> {
        return getAllThemes().filter { theme ->
            theme.meetsAccessibilityStandards()
        }
    }
    
    /**
     * Get high contrast versions of all themes
     */
    fun getHighContrastThemes(): List<Theme> {
        return getAllThemes().map { it.toHighContrast() }
    }
}