package io.github.tabssh.themes.definitions

/**
 * All 23 built-in themes for TabSSH
 * Includes popular terminal themes with proper color accessibility
 */
object BuiltInThemes {
    
    /**
     * Get all built-in themes
     */
    fun getAllThemes(): List<Theme> {
        return listOf(
            // System themes (3)
            systemDefault(),
            systemDark(),
            systemLight(),
            // Classic themes (12)
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
            materialDark(),
            // New popular themes (8)
            tokyoNight(),
            tokyoNightLight(),
            catppuccin(),
            rosePine(),
            everforest(),
            kanagawa(),
            nightOwl(),
            cobalt2()
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
    
    // System-aware themes
    
    fun systemDefault(): Theme {
        val isDark = android.content.res.Resources.getSystem().configuration.uiMode and 
                     android.content.res.Configuration.UI_MODE_NIGHT_MASK == 
                     android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (isDark) systemDark() else systemLight()
    }
    
    fun systemDark(): Theme {
        return dracula().copy(
            id = "system_dark",
            name = "System Dark",
            author = "TabSSH",
            isBuiltIn = true
        )
    }
    
    fun systemLight(): Theme {
        return githubLight().copy(
            id = "system_light",
            name = "System Light",
            author = "TabSSH",
            isBuiltIn = true
        )
    }
    
    // Classic themes
    
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
                // Black
                0xFF21222C.toInt(),
                // Red
                0xFFFF5555.toInt(),
                // Green
                0xFF50FA7B.toInt(),
                // Yellow
                0xFFF1FA8C.toInt(),
                // Blue
                0xFFBD93F9.toInt(),
                // Magenta
                0xFFFF79C6.toInt(),
                // Cyan
                0xFF8BE9FD.toInt(),
                // White
                0xFFF8F8F2.toInt(),
                // Bright Black
                0xFF6272A4.toInt(),
                // Bright Red
                0xFFFF6E6E.toInt(),
                // Bright Green
                0xFF69FF94.toInt(),
                // Bright Yellow
                0xFFFFFFA5.toInt(),
                // Bright Blue
                0xFFD6ACFF.toInt(),
                // Bright Magenta
                0xFFFF92DF.toInt(),
                // Bright Cyan
                0xFFA4FFFF.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
                // Black
                0xFF073642.toInt(),
                // Red
                0xFFDC322F.toInt(),
                // Green
                0xFF859900.toInt(),
                // Yellow
                0xFFB58900.toInt(),
                // Blue
                0xFF268BD2.toInt(),
                // Magenta
                0xFFD33682.toInt(),
                // Cyan
                0xFF2AA198.toInt(),
                // White
                0xFFEEE8D5.toInt(),
                // Bright Black
                0xFF002B36.toInt(),
                // Bright Red
                0xFFCB4B16.toInt(),
                // Bright Green
                0xFF586E75.toInt(),
                // Bright Yellow
                0xFF657B83.toInt(),
                // Bright Blue
                0xFF839496.toInt(),
                // Bright Magenta
                0xFF6C71C4.toInt(),
                // Bright Cyan
                0xFF93A1A1.toInt(),
                // Bright White
                0xFFFDF6E3.toInt()
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
            // base01 (emphasized content) instead of canonical base00 body
            // text: base00 (#657B83) on base3 is ~4.1:1, below WCAG AA 4.5:1
            // (ThemeValidator ERROR). base01 is ~5.0:1 and still Solarized.
            foreground = 0xFF586E75.toInt(),
            cursor = 0xFF586E75.toInt(),
            selection = 0x44EEE8D5.toInt(),
            highlight = 0xFFB58900.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF073642.toInt(),
                // Red
                0xFFDC322F.toInt(),
                // Green
                0xFF859900.toInt(),
                // Yellow
                0xFFB58900.toInt(),
                // Blue
                0xFF268BD2.toInt(),
                // Magenta
                0xFFD33682.toInt(),
                // Cyan
                0xFF2AA198.toInt(),
                // White
                0xFFEEE8D5.toInt(),
                // Bright Black
                0xFF002B36.toInt(),
                // Bright Red
                0xFFCB4B16.toInt(),
                // Bright Green
                0xFF586E75.toInt(),
                // Bright Yellow
                0xFF657B83.toInt(),
                // Bright Blue
                0xFF839496.toInt(),
                // Bright Magenta
                0xFF6C71C4.toInt(),
                // Bright Cyan
                0xFF93A1A1.toInt(),
                // Bright White
                0xFFFDF6E3.toInt()
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
                // Black
                0xFF3B4252.toInt(),
                // Red
                0xFFBF616A.toInt(),
                // Green
                0xFFA3BE8C.toInt(),
                // Yellow
                0xFFEBCB8B.toInt(),
                // Blue
                0xFF81A1C1.toInt(),
                // Magenta
                0xFFB48EAD.toInt(),
                // Cyan
                0xFF88C0D0.toInt(),
                // White
                0xFFE5E9F0.toInt(),
                // Bright Black
                0xFF4C566A.toInt(),
                // Bright Red
                0xFFBF616A.toInt(),
                // Bright Green
                0xFFA3BE8C.toInt(),
                // Bright Yellow
                0xFFEBCB8B.toInt(),
                // Bright Blue
                0xFF81A1C1.toInt(),
                // Bright Magenta
                0xFFB48EAD.toInt(),
                // Bright Cyan
                0xFF8FBCBB.toInt(),
                // Bright White
                0xFFECEFF4.toInt()
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
                // Black
                0xFF282C34.toInt(),
                // Red
                0xFFE06C75.toInt(),
                // Green
                0xFF98C379.toInt(),
                // Yellow
                0xFFE5C07B.toInt(),
                // Blue
                0xFF61AFEF.toInt(),
                // Magenta
                0xFFC678DD.toInt(),
                // Cyan
                0xFF56B6C2.toInt(),
                // White
                0xFFABB2BF.toInt(),
                // Bright Black
                0xFF3E4451.toInt(),
                // Bright Red
                0xFFE06C75.toInt(),
                // Bright Green
                0xFF98C379.toInt(),
                // Bright Yellow
                0xFFE5C07B.toInt(),
                // Bright Blue
                0xFF61AFEF.toInt(),
                // Bright Magenta
                0xFFC678DD.toInt(),
                // Bright Cyan
                0xFF56B6C2.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
                // Black
                0xFF272822.toInt(),
                // Red
                0xFFF92672.toInt(),
                // Green
                0xFFA6E22E.toInt(),
                // Yellow
                0xFFE6DB74.toInt(),
                // Blue
                0xFF66D9EF.toInt(),
                // Magenta
                0xFFAE81FF.toInt(),
                // Cyan
                0xFF2AA198.toInt(),
                // White
                0xFFF8F8F2.toInt(),
                // Bright Black
                0xFF75715E.toInt(),
                // Bright Red
                0xFFF92672.toInt(),
                // Bright Green
                0xFFA6E22E.toInt(),
                // Bright Yellow
                0xFFE6DB74.toInt(),
                // Bright Blue
                0xFF66D9EF.toInt(),
                // Bright Magenta
                0xFFAE81FF.toInt(),
                // Bright Cyan
                0xFF2AA198.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
                // Black
                0xFF282828.toInt(),
                // Red
                0xFFCC241D.toInt(),
                // Green
                0xFF98971A.toInt(),
                // Yellow
                0xFFD79921.toInt(),
                // Blue
                0xFF458588.toInt(),
                // Magenta
                0xFFB16286.toInt(),
                // Cyan
                0xFF689D6A.toInt(),
                // White
                0xFFA89984.toInt(),
                // Bright Black
                0xFF928374.toInt(),
                // Bright Red
                0xFFFB4934.toInt(),
                // Bright Green
                0xFFB8BB26.toInt(),
                // Bright Yellow
                0xFFFABD2F.toInt(),
                // Bright Blue
                0xFF83A598.toInt(),
                // Bright Magenta
                0xFFD3869B.toInt(),
                // Bright Cyan
                0xFF8EC07C.toInt(),
                // Bright White
                0xFFEBDBB2.toInt()
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
                // Black
                0xFFFBF1C7.toInt(),
                // Red
                0xFFCC241D.toInt(),
                // Green
                0xFF98971A.toInt(),
                // Yellow
                0xFFD79921.toInt(),
                // Blue
                0xFF458588.toInt(),
                // Magenta
                0xFFB16286.toInt(),
                // Cyan
                0xFF689D6A.toInt(),
                // White
                0xFF7C6F64.toInt(),
                // Bright Black
                0xFF928374.toInt(),
                // Bright Red
                0xFF9D0006.toInt(),
                // Bright Green
                0xFF79740E.toInt(),
                // Bright Yellow
                0xFFB57614.toInt(),
                // Bright Blue
                0xFF076678.toInt(),
                // Bright Magenta
                0xFF8F3F71.toInt(),
                // Bright Cyan
                0xFF427B58.toInt(),
                // Bright White
                0xFF3C3836.toInt()
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
                // Black
                0xFF1D1F21.toInt(),
                // Red
                0xFFCC6666.toInt(),
                // Green
                0xFFB5BD68.toInt(),
                // Yellow
                0xFFF0C674.toInt(),
                // Blue
                0xFF81A2BE.toInt(),
                // Magenta
                0xFFB294BB.toInt(),
                // Cyan
                0xFF8ABEB7.toInt(),
                // White
                0xFFC5C8C6.toInt(),
                // Bright Black
                0xFF969896.toInt(),
                // Bright Red
                0xFFCC6666.toInt(),
                // Bright Green
                0xFFB5BD68.toInt(),
                // Bright Yellow
                0xFFF0C674.toInt(),
                // Bright Blue
                0xFF81A2BE.toInt(),
                // Bright Magenta
                0xFFB294BB.toInt(),
                // Bright Cyan
                0xFF8ABEB7.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
                // Black
                0xFF24292E.toInt(),
                // Red
                0xFFD73A49.toInt(),
                // Green
                0xFF28A745.toInt(),
                // Yellow
                0xFFFFAB00.toInt(),
                // Blue
                0xFF0366D6.toInt(),
                // Magenta
                0xFF5A32A3.toInt(),
                // Cyan
                0xFF17A2B8.toInt(),
                // White
                0xFF6A737D.toInt(),
                // Bright Black
                0xFF959DA5.toInt(),
                // Bright Red
                0xFFD73A49.toInt(),
                // Bright Green
                0xFF28A745.toInt(),
                // Bright Yellow
                0xFFFFAB00.toInt(),
                // Bright Blue
                0xFF0366D6.toInt(),
                // Bright Magenta
                0xFF5A32A3.toInt(),
                // Bright Cyan
                0xFF17A2B8.toInt(),
                // Bright White
                0xFF24292E.toInt()
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
                // Black
                0xFF282C34.toInt(),
                // Red
                0xFFE06C75.toInt(),
                // Green
                0xFF98C379.toInt(),
                // Yellow
                0xFFE5C07B.toInt(),
                // Blue
                0xFF61AFEF.toInt(),
                // Magenta
                0xFFC678DD.toInt(),
                // Cyan
                0xFF56B6C2.toInt(),
                // White
                0xFFABB2BF.toInt(),
                // Bright Black
                0xFF5C6370.toInt(),
                // Bright Red
                0xFFE06C75.toInt(),
                // Bright Green
                0xFF98C379.toInt(),
                // Bright Yellow
                0xFFE5C07B.toInt(),
                // Bright Blue
                0xFF61AFEF.toInt(),
                // Bright Magenta
                0xFFC678DD.toInt(),
                // Bright Cyan
                0xFF56B6C2.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
                // Black
                0xFF000000.toInt(),
                // Red
                0xFFF44336.toInt(),
                // Green
                0xFF4CAF50.toInt(),
                // Yellow
                0xFFFFEB3B.toInt(),
                // Blue
                0xFF2196F3.toInt(),
                // Magenta
                0xFF9C27B0.toInt(),
                // Cyan
                0xFF00BCD4.toInt(),
                // White
                0xFFFFFFFF.toInt(),
                // Bright Black
                0xFF757575.toInt(),
                // Bright Red
                0xFFEF5350.toInt(),
                // Bright Green
                0xFF66BB6A.toInt(),
                // Bright Yellow
                0xFFFFEE58.toInt(),
                // Bright Blue
                0xFF42A5F5.toInt(),
                // Bright Magenta
                0xFFAB47BC.toInt(),
                // Bright Cyan
                0xFF26C6DA.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
            )
        )
    }
    
    // New popular themes (2024)
    
    fun tokyoNight(): Theme {
        return Theme(
            id = "tokyo_night",
            name = "Tokyo Night",
            author = "enkia",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF1A1B26.toInt(),
            foreground = 0xFFA9B1D6.toInt(),
            cursor = 0xFFA9B1D6.toInt(),
            selection = 0x44283457.toInt(),
            highlight = 0xFF7AA2F7.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF1A1B26.toInt(),
                // Red
                0xFFF7768E.toInt(),
                // Green
                0xFF9ECE6A.toInt(),
                // Yellow
                0xFFE0AF68.toInt(),
                // Blue
                0xFF7AA2F7.toInt(),
                // Magenta
                0xFFBB9AF7.toInt(),
                // Cyan
                0xFF7DCFFF.toInt(),
                // White
                0xFFA9B1D6.toInt(),
                // Bright Black
                0xFF414868.toInt(),
                // Bright Red
                0xFFF7768E.toInt(),
                // Bright Green
                0xFF9ECE6A.toInt(),
                // Bright Yellow
                0xFFE0AF68.toInt(),
                // Bright Blue
                0xFF7AA2F7.toInt(),
                // Bright Magenta
                0xFFBB9AF7.toInt(),
                // Bright Cyan
                0xFF7DCFFF.toInt(),
                // Bright White
                0xFFC0CAF5.toInt()
            )
        )
    }
    
    fun tokyoNightLight(): Theme {
        return Theme(
            id = "tokyo_night_light",
            name = "Tokyo Night Light",
            author = "enkia",
            isDark = false,
            isBuiltIn = true,
            background = 0xFFD5D6DB.toInt(),
            foreground = 0xFF565A6E.toInt(),
            cursor = 0xFF565A6E.toInt(),
            selection = 0x44C4C8DA.toInt(),
            highlight = 0xFF2E7DE9.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFFD5D6DB.toInt(),
                // Red
                0xFFF52A65.toInt(),
                // Green
                0xFF587539.toInt(),
                // Yellow
                0xFF8C6C3E.toInt(),
                // Blue
                0xFF2E7DE9.toInt(),
                // Magenta
                0xFF9854F1.toInt(),
                // Cyan
                0xFF007197.toInt(),
                // White
                0xFF565A6E.toInt(),
                // Bright Black
                0xFF9699A3.toInt(),
                // Bright Red
                0xFFF52A65.toInt(),
                // Bright Green
                0xFF587539.toInt(),
                // Bright Yellow
                0xFF8C6C3E.toInt(),
                // Bright Blue
                0xFF2E7DE9.toInt(),
                // Bright Magenta
                0xFF9854F1.toInt(),
                // Bright Cyan
                0xFF007197.toInt(),
                // Bright White
                0xFF343B59.toInt()
            )
        )
    }
    
    fun catppuccin(): Theme {
        return Theme(
            id = "catppuccin",
            name = "Catppuccin Mocha",
            author = "Catppuccin",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF1E1E2E.toInt(),
            foreground = 0xFFCDD6F4.toInt(),
            cursor = 0xFFF5E0DC.toInt(),
            selection = 0x44585B70.toInt(),
            highlight = 0xFFF5C2E7.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF45475A.toInt(),
                // Red
                0xFFF38BA8.toInt(),
                // Green
                0xFFA6E3A1.toInt(),
                // Yellow
                0xFFF9E2AF.toInt(),
                // Blue
                0xFF89B4FA.toInt(),
                // Magenta
                0xFFF5C2E7.toInt(),
                // Cyan
                0xFF94E2D5.toInt(),
                // White
                0xFFBAC2DE.toInt(),
                // Bright Black
                0xFF585B70.toInt(),
                // Bright Red
                0xFFF38BA8.toInt(),
                // Bright Green
                0xFFA6E3A1.toInt(),
                // Bright Yellow
                0xFFF9E2AF.toInt(),
                // Bright Blue
                0xFF89B4FA.toInt(),
                // Bright Magenta
                0xFFF5C2E7.toInt(),
                // Bright Cyan
                0xFF94E2D5.toInt(),
                // Bright White
                0xFFA6ADC8.toInt()
            )
        )
    }
    
    fun rosePine(): Theme {
        return Theme(
            id = "rose_pine",
            name = "Rosé Pine",
            author = "Rosé Pine",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF191724.toInt(),
            foreground = 0xFFE0DEF4.toInt(),
            // "subtle" (#908CAA) instead of "highlight-high" (#524F67):
            // highlight-high on base is ~2.3:1, below the WCAG AA 4.5:1
            // cursor check (ThemeValidator ERROR). subtle is ~5.5:1 and
            // still a canonical Rosé Pine role color.
            cursor = 0xFF908CAA.toInt(),
            selection = 0x442A2837.toInt(),
            highlight = 0xFFEBBCBA.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF26233A.toInt(),
                // Red
                0xFFEB6F92.toInt(),
                // Green
                0xFF9CCFD8.toInt(),
                // Yellow
                0xFFF6C177.toInt(),
                // Blue
                0xFF31748F.toInt(),
                // Magenta
                0xFFC4A7E7.toInt(),
                // Cyan
                0xFFEBBCBA.toInt(),
                // White
                0xFFE0DEF4.toInt(),
                // Bright Black
                0xFF6E6A86.toInt(),
                // Bright Red
                0xFFEB6F92.toInt(),
                // Bright Green
                0xFF9CCFD8.toInt(),
                // Bright Yellow
                0xFFF6C177.toInt(),
                // Bright Blue
                0xFF31748F.toInt(),
                // Bright Magenta
                0xFFC4A7E7.toInt(),
                // Bright Cyan
                0xFFEBBCBA.toInt(),
                // Bright White
                0xFFE0DEF4.toInt()
            )
        )
    }
    
    fun everforest(): Theme {
        return Theme(
            id = "everforest",
            name = "Everforest",
            author = "sainnhe",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF2D353B.toInt(),
            foreground = 0xFFD3C6AA.toInt(),
            cursor = 0xFFD3C6AA.toInt(),
            selection = 0x44475258.toInt(),
            highlight = 0xFFA7C080.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF475258.toInt(),
                // Red
                0xFFE67E80.toInt(),
                // Green
                0xFFA7C080.toInt(),
                // Yellow
                0xFFDBBC7F.toInt(),
                // Blue
                0xFF7FBBB3.toInt(),
                // Magenta
                0xFFD699B6.toInt(),
                // Cyan
                0xFF83C092.toInt(),
                // White
                0xFFD3C6AA.toInt(),
                // Bright Black
                0xFF859289.toInt(),
                // Bright Red
                0xFFE67E80.toInt(),
                // Bright Green
                0xFFA7C080.toInt(),
                // Bright Yellow
                0xFFDBBC7F.toInt(),
                // Bright Blue
                0xFF7FBBB3.toInt(),
                // Bright Magenta
                0xFFD699B6.toInt(),
                // Bright Cyan
                0xFF83C092.toInt(),
                // Bright White
                0xFFD3C6AA.toInt()
            )
        )
    }
    
    fun kanagawa(): Theme {
        return Theme(
            id = "kanagawa",
            name = "Kanagawa",
            author = "rebelot",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF1F1F28.toInt(),
            foreground = 0xFFDCD7BA.toInt(),
            cursor = 0xFFC8C093.toInt(),
            selection = 0x44223249.toInt(),
            highlight = 0xFF7E9CD8.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF090618.toInt(),
                // Red
                0xFFC34043.toInt(),
                // Green
                0xFF76946A.toInt(),
                // Yellow
                0xFFDCA561.toInt(),
                // Blue
                0xFF7E9CD8.toInt(),
                // Magenta
                0xFF957FB8.toInt(),
                // Cyan
                0xFF6A9589.toInt(),
                // White
                0xFFC8C093.toInt(),
                // Bright Black
                0xFF727169.toInt(),
                // Bright Red
                0xFFE82424.toInt(),
                // Bright Green
                0xFF98BB6C.toInt(),
                // Bright Yellow
                0xFFE6C384.toInt(),
                // Bright Blue
                0xFF7FB4CA.toInt(),
                // Bright Magenta
                0xFF938AA9.toInt(),
                // Bright Cyan
                0xFF7AA89F.toInt(),
                // Bright White
                0xFFDCD7BA.toInt()
            )
        )
    }
    
    fun nightOwl(): Theme {
        return Theme(
            id = "night_owl",
            name = "Night Owl",
            author = "Sarah Drasner",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF011627.toInt(),
            foreground = 0xFFD6DEEB.toInt(),
            cursor = 0xFF80A4C2.toInt(),
            selection = 0x441D3B53.toInt(),
            highlight = 0xFF7FDBCA.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF011627.toInt(),
                // Red
                0xFFEF5350.toInt(),
                // Green
                0xFF22DA6E.toInt(),
                // Yellow
                0xFFADDB67.toInt(),
                // Blue
                0xFF82AAFF.toInt(),
                // Magenta
                0xFFC792EA.toInt(),
                // Cyan
                0xFF7FDBCA.toInt(),
                // White
                0xFFFFFFFF.toInt(),
                // Bright Black
                0xFF575656.toInt(),
                // Bright Red
                0xFFEF5350.toInt(),
                // Bright Green
                0xFF22DA6E.toInt(),
                // Bright Yellow
                0xFFFFEB95.toInt(),
                // Bright Blue
                0xFF82AAFF.toInt(),
                // Bright Magenta
                0xFFC792EA.toInt(),
                // Bright Cyan
                0xFF7FDBCA.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
            )
        )
    }
    
    fun cobalt2(): Theme {
        return Theme(
            id = "cobalt2",
            name = "Cobalt2",
            author = "Wes Bos",
            isDark = true,
            isBuiltIn = true,
            background = 0xFF193549.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            cursor = 0xFFF0CB09.toInt(),
            selection = 0x440D3A58.toInt(),
            highlight = 0xFFFFC600.toInt(),
            ansiColors = intArrayOf(
                // Black
                0xFF000000.toInt(),
                // Red
                0xFFFF0000.toInt(),
                // Green
                0xFF38DE21.toInt(),
                // Yellow
                0xFFFFC600.toInt(),
                // Blue
                0xFF0088FF.toInt(),
                // Magenta
                0xFFFF628C.toInt(),
                // Cyan
                0xFF80FCFF.toInt(),
                // White
                0xFFFFFFFF.toInt(),
                // Bright Black
                0xFF555555.toInt(),
                // Bright Red
                0xFFFF0000.toInt(),
                // Bright Green
                0xFF38DE21.toInt(),
                // Bright Yellow
                0xFFFFC600.toInt(),
                // Bright Blue
                0xFF0088FF.toInt(),
                // Bright Magenta
                0xFFFF628C.toInt(),
                // Bright Cyan
                0xFF80FCFF.toInt(),
                // Bright White
                0xFFFFFFFF.toInt()
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
