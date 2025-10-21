package io.github.tabssh.themes.definitions

/**
 * Represents a complete terminal theme with colors and UI customization
 */
data class Theme(
    // Theme metadata
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String = "1.0",
    val isDark: Boolean = false,
    val isBuiltIn: Boolean = false,
    
    // Terminal colors
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val highlight: Int, // Search highlight
    
    // ANSI colors (16-color palette)
    val ansiColors: IntArray, // Must be exactly 16 colors (0-15)
    
    // UI colors (Material Design 3 integration)
    val primary: Int? = null,
    val primaryVariant: Int? = null,
    val secondary: Int? = null,
    val surface: Int? = null,
    val surfaceVariant: Int? = null,
    val onPrimary: Int? = null,
    val onSecondary: Int? = null,
    val onSurface: Int? = null,
    val onBackground: Int? = null,
    val error: Int? = null,
    
    // Status and navigation colors
    val statusBar: Int? = null,
    val navigationBar: Int? = null,
    val tabBackground: Int? = null,
    val tabText: Int? = null,
    val tabSelected: Int? = null,
    val divider: Int? = null
) {
    init {
        require(ansiColors.size == 16) { "ANSI colors array must contain exactly 16 colors" }
    }
    
    /**
     * Get ANSI color by index (0-15)
     */
    fun getAnsiColor(index: Int): Int {
        return if (index in 0..15) ansiColors[index] else foreground
    }
    
    /**
     * Get effective foreground color for the UI theme
     */
    fun getEffectiveForeground(): Int = onSurface ?: foreground
    
    /**
     * Get effective background color for the UI theme  
     */
    fun getEffectiveBackground(): Int = surface ?: background
    
    /**
     * Check if theme colors meet accessibility standards
     */
    fun meetsAccessibilityStandards(): Boolean {
        // Check contrast ratio between foreground and background
        val contrast = calculateContrastRatio(foreground, background)
        
        // WCAG 2.1 AA standard requires 4.5:1 for normal text
        return contrast >= 4.5
    }
    
    /**
     * Create a high contrast version of this theme
     */
    fun toHighContrast(): Theme {
        val highContrastBg = if (isDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val highContrastFg = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        
        // Ensure all ANSI colors meet high contrast standards
        val highContrastAnsiColors = ansiColors.mapIndexed { index, color ->
            adjustColorForHighContrast(color, highContrastBg, index)
        }.toIntArray()
        
        return copy(
            id = "${id}_high_contrast",
            name = "$name (High Contrast)",
            background = highContrastBg,
            foreground = highContrastFg,
            ansiColors = highContrastAnsiColors,
            selection = if (isDark) 0xFF0066FF.toInt() else 0xFF3399FF.toInt()
        )
    }
    
    /**
     * Create a copy with custom colors
     */
    fun withCustomColors(
        newBackground: Int? = null,
        newForeground: Int? = null,
        newAnsiColors: IntArray? = null
    ): Theme {
        return copy(
            id = "${id}_custom",
            name = "$name (Custom)",
            background = newBackground ?: background,
            foreground = newForeground ?: foreground,
            ansiColors = newAnsiColors ?: ansiColors,
            isBuiltIn = false
        )
    }
    
    companion object {
        /**
         * Calculate contrast ratio between two colors
         */
        fun calculateContrastRatio(color1: Int, color2: Int): Double {
            val luminance1 = getLuminance(color1)
            val luminance2 = getLuminance(color2)
            
            val lighter = maxOf(luminance1, luminance2)
            val darker = minOf(luminance1, luminance2)
            
            return (lighter + 0.05) / (darker + 0.05)
        }
        
        private fun getLuminance(color: Int): Double {
            val r = ((color shr 16) and 0xFF) / 255.0
            val g = ((color shr 8) and 0xFF) / 255.0
            val b = (color and 0xFF) / 255.0
            
            val rs = if (r <= 0.03928) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
            val gs = if (g <= 0.03928) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
            val bs = if (b <= 0.03928) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)
            
            return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
        }
        
        private fun adjustColorForHighContrast(color: Int, backgroundColor: Int, ansiIndex: Int): Int {
            val contrast = calculateContrastRatio(color, backgroundColor)
            
            // If contrast is already high enough, keep the color
            if (contrast >= 7.0) return color
            
            // Otherwise, adjust to meet AAA standards (7:1 ratio)
            return when {
                backgroundColor == 0xFF000000.toInt() -> { // Dark background
                    // Make colors brighter for dark backgrounds
                    when (ansiIndex) {
                        0 -> 0xFF7F7F7F.toInt() // Bright black (gray)
                        1 -> 0xFFFF5555.toInt() // Bright red
                        2 -> 0xFF55FF55.toInt() // Bright green
                        3 -> 0xFFFFFF55.toInt() // Bright yellow
                        4 -> 0xFF5555FF.toInt() // Bright blue
                        5 -> 0xFFFF55FF.toInt() // Bright magenta
                        6 -> 0xFF55FFFF.toInt() // Bright cyan
                        7 -> 0xFFFFFFFF.toInt() // White
                        8 -> 0xFFAAAAAA.toInt() // Bright gray
                        9 -> 0xFFFF7777.toInt() // Very bright red
                        10 -> 0xFF77FF77.toInt() // Very bright green
                        11 -> 0xFFFFFF77.toInt() // Very bright yellow
                        12 -> 0xFF7777FF.toInt() // Very bright blue
                        13 -> 0xFFFF77FF.toInt() // Very bright magenta
                        14 -> 0xFF77FFFF.toInt() // Very bright cyan
                        15 -> 0xFFFFFFFF.toInt() // Bright white
                        else -> color
                    }
                }
                else -> { // Light background
                    // Make colors darker for light backgrounds
                    when (ansiIndex) {
                        0 -> 0xFF000000.toInt() // Black
                        1 -> 0xFF800000.toInt() // Dark red
                        2 -> 0xFF008000.toInt() // Dark green
                        3 -> 0xFF808000.toInt() // Dark yellow
                        4 -> 0xFF000080.toInt() // Dark blue
                        5 -> 0xFF800080.toInt() // Dark magenta
                        6 -> 0xFF008080.toInt() // Dark cyan
                        7 -> 0xFF404040.toInt() // Dark gray
                        8 -> 0xFF202020.toInt() // Very dark gray
                        9 -> 0xFF600000.toInt() // Very dark red
                        10 -> 0xFF006000.toInt() // Very dark green
                        11 -> 0xFF606000.toInt() // Very dark yellow
                        12 -> 0xFF000060.toInt() // Very dark blue
                        13 -> 0xFF600060.toInt() // Very dark magenta
                        14 -> 0xFF006060.toInt() // Very dark cyan
                        15 -> 0xFF000000.toInt() // Black
                        else -> color
                    }
                }
            }
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Theme) return false
        return id == other.id
    }
    
    override fun hashCode(): Int {
        return id.hashCode()
    }
}