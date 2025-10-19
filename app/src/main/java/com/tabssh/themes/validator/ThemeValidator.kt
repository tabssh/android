package com.tabssh.themes.validator

import com.tabssh.themes.definitions.Theme
import com.tabssh.utils.logging.Logger
import kotlin.math.pow

/**
 * Validates themes for accessibility compliance and color standards
 * Ensures WCAG 2.1 AA compliance and provides auto-fixing capabilities
 */
class ThemeValidator {
    
    companion object {
        // WCAG 2.1 contrast ratio requirements
        private const val MIN_CONTRAST_AA = 4.5
        private const val MIN_CONTRAST_AAA = 7.0
        private const val MIN_CONTRAST_LARGE_TEXT_AA = 3.0
        
        // Color difference thresholds
        private const val MIN_COLOR_DIFFERENCE = 500
        private const val MIN_BRIGHTNESS_DIFFERENCE = 125
    }
    
    /**
     * Validate a theme for accessibility compliance
     */
    fun validateTheme(theme: Theme): ValidationResult {
        val result = ValidationResult()
        
        Logger.d("ThemeValidator", "Validating theme: ${theme.name}")
        
        // Validate basic contrast ratios
        validateBasicContrast(theme, result)
        
        // Validate ANSI color contrast
        validateANSIColors(theme, result)
        
        // Validate UI color combinations
        validateUIColors(theme, result)
        
        // Check for color blindness issues
        validateColorBlindness(theme, result)
        
        // Validate color differences
        validateColorDifferences(theme, result)
        
        Logger.d("ThemeValidator", "Theme validation complete: ${result.getValidationSummary()}")
        return result
    }
    
    private fun validateBasicContrast(theme: Theme, result: ValidationResult) {
        // Terminal text contrast
        val terminalContrast = Theme.calculateContrastRatio(theme.foreground, theme.background)
        if (terminalContrast < MIN_CONTRAST_AA) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    category = "contrast",
                    description = "Terminal text contrast ratio (${"%.2f".format(terminalContrast)}) below AA standard ($MIN_CONTRAST_AA)",
                    colors = listOf(theme.foreground, theme.background),
                    autoFixAvailable = true
                )
            )
        } else if (terminalContrast < MIN_CONTRAST_AAA) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    category = "contrast",
                    description = "Terminal text contrast ratio (${"%.2f".format(terminalContrast)}) below AAA standard ($MIN_CONTRAST_AAA)",
                    colors = listOf(theme.foreground, theme.background),
                    autoFixAvailable = true
                )
            )
        }
        
        // Cursor contrast
        val cursorContrast = Theme.calculateContrastRatio(theme.cursor, theme.background)
        if (cursorContrast < MIN_CONTRAST_AA) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    category = "cursor",
                    description = "Cursor contrast ratio (${"%.2f".format(cursorContrast)}) below AA standard",
                    colors = listOf(theme.cursor, theme.background),
                    autoFixAvailable = true
                )
            )
        }
    }
    
    private fun validateANSIColors(theme: Theme, result: ValidationResult) {
        for (i in theme.ansiColors.indices) {
            val color = theme.ansiColors[i]
            val contrast = Theme.calculateContrastRatio(color, theme.background)
            
            if (contrast < MIN_CONTRAST_AA) {
                result.addIssue(
                    ValidationIssue(
                        severity = IssueSeverity.WARNING,
                        category = "ansi_color",
                        description = "ANSI color $i contrast ratio (${"%.2f".format(contrast)}) below AA standard",
                        colors = listOf(color, theme.background),
                        autoFixAvailable = true,
                        details = mapOf("ansi_index" to i.toString())
                    )
                )
            }
        }
    }
    
    private fun validateUIColors(theme: Theme, result: ValidationResult) {
        // Validate UI color combinations if present
        theme.primary?.let { primary ->
            theme.onPrimary?.let { onPrimary ->
                val contrast = Theme.calculateContrastRatio(onPrimary, primary)
                if (contrast < MIN_CONTRAST_AA) {
                    result.addIssue(
                        ValidationIssue(
                            severity = IssueSeverity.ERROR,
                            category = "ui_color",
                            description = "Primary/OnPrimary contrast ratio (${"%.2f".format(contrast)}) below AA standard",
                            colors = listOf(onPrimary, primary),
                            autoFixAvailable = true
                        )
                    )
                }
            }
        }
        
        theme.surface?.let { surface ->
            theme.onSurface?.let { onSurface ->
                val contrast = Theme.calculateContrastRatio(onSurface, surface)
                if (contrast < MIN_CONTRAST_AA) {
                    result.addIssue(
                        ValidationIssue(
                            severity = IssueSeverity.ERROR,
                            category = "ui_color",
                            description = "Surface/OnSurface contrast ratio (${"%.2f".format(contrast)}) below AA standard",
                            colors = listOf(onSurface, surface),
                            autoFixAvailable = true
                        )
                    )
                }
            }
        }
    }
    
    private fun validateColorBlindness(theme: Theme, result: ValidationResult) {
        // Simulate different types of color blindness
        val protanopia = simulateProtanopia(theme)
        val deuteranopia = simulateDeuteranopia(theme)
        val tritanopia = simulateTritanopia(theme)
        
        // Check if important color distinctions are lost
        checkColorBlindnessContrast(theme, protanopia, "Protanopia", result)
        checkColorBlindnessContrast(theme, deuteranopia, "Deuteranopia", result)
        checkColorBlindnessContrast(theme, tritanopia, "Tritanopia", result)
    }
    
    private fun checkColorBlindnessContrast(
        original: Theme,
        simulated: Theme,
        type: String,
        result: ValidationResult
    ) {
        val originalContrast = Theme.calculateContrastRatio(original.foreground, original.background)
        val simulatedContrast = Theme.calculateContrastRatio(simulated.foreground, simulated.background)
        
        if (simulatedContrast < MIN_CONTRAST_AA && originalContrast >= MIN_CONTRAST_AA) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    category = "color_blindness",
                    description = "$type simulation reduces contrast below AA standard",
                    autoFixAvailable = false,
                    details = mapOf("color_blindness_type" to type)
                )
            )
        }
    }
    
    private fun validateColorDifferences(theme: Theme, result: ValidationResult) {
        // Check color and brightness differences for accessibility
        val colorDiff = calculateColorDifference(theme.foreground, theme.background)
        val brightnessDiff = calculateBrightnessDifference(theme.foreground, theme.background)
        
        if (colorDiff < MIN_COLOR_DIFFERENCE) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    category = "color_difference",
                    description = "Color difference ($colorDiff) below recommended threshold ($MIN_COLOR_DIFFERENCE)",
                    autoFixAvailable = true
                )
            )
        }
        
        if (brightnessDiff < MIN_BRIGHTNESS_DIFFERENCE) {
            result.addIssue(
                ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    category = "brightness_difference",
                    description = "Brightness difference ($brightnessDiff) below recommended threshold ($MIN_BRIGHTNESS_DIFFERENCE)",
                    autoFixAvailable = true
                )
            )
        }
    }
    
    /**
     * Auto-fix contrast issues in a theme
     */
    fun autoFixContrast(theme: Theme): Theme? {
        Logger.d("ThemeValidator", "Auto-fixing contrast issues for theme: ${theme.name}")
        
        var fixedTheme = theme
        
        // Fix terminal foreground/background contrast
        val terminalContrast = Theme.calculateContrastRatio(theme.foreground, theme.background)
        if (terminalContrast < MIN_CONTRAST_AA) {
            fixedTheme = fixedTheme.copy(
                foreground = adjustColorForContrast(theme.foreground, theme.background, MIN_CONTRAST_AA)
            )
        }
        
        // Fix cursor contrast
        val cursorContrast = Theme.calculateContrastRatio(theme.cursor, theme.background)
        if (cursorContrast < MIN_CONTRAST_AA) {
            fixedTheme = fixedTheme.copy(
                cursor = adjustColorForContrast(theme.cursor, theme.background, MIN_CONTRAST_AA)
            )
        }
        
        // Fix ANSI colors
        val fixedAnsiColors = theme.ansiColors.mapIndexed { index, color ->
            val contrast = Theme.calculateContrastRatio(color, theme.background)
            if (contrast < MIN_CONTRAST_AA) {
                adjustColorForContrast(color, theme.background, MIN_CONTRAST_AA)
            } else {
                color
            }
        }.toIntArray()
        
        fixedTheme = fixedTheme.copy(
            id = "${theme.id}_fixed",
            name = "${theme.name} (Auto-Fixed)",
            ansiColors = fixedAnsiColors
        )
        
        return if (fixedTheme != theme) {
            Logger.i("ThemeValidator", "Auto-fixed theme contrast issues")
            fixedTheme
        } else {
            null
        }
    }
    
    private fun adjustColorForContrast(color: Int, backgroundColor: Int, targetContrast: Double): Int {
        val currentContrast = Theme.calculateContrastRatio(color, backgroundColor)
        if (currentContrast >= targetContrast) return color
        
        // Extract RGB components
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        // Determine if we need to make the color lighter or darker
        val bgLuminance = getLuminance(backgroundColor)
        val colorLuminance = getLuminance(color)
        
        // Adjust luminance to meet target contrast
        val targetLuminance = if (bgLuminance > colorLuminance) {
            // Background is lighter, make color darker
            (bgLuminance + 0.05) / targetContrast - 0.05
        } else {
            // Background is darker, make color lighter
            (bgLuminance + 0.05) * targetContrast - 0.05
        }
        
        // Convert back to RGB (simplified approach)
        val adjustmentFactor = targetLuminance / colorLuminance
        val newR = (r * adjustmentFactor).toInt().coerceIn(0, 255)
        val newG = (g * adjustmentFactor).toInt().coerceIn(0, 255)
        val newB = (b * adjustmentFactor).toInt().coerceIn(0, 255)
        
        return (0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
    }
    
    private fun getLuminance(color: Int): Double {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        
        val rs = if (r <= 0.03928) r / 12.92 else ((r + 0.055) / 1.055).pow(2.4)
        val gs = if (g <= 0.03928) g / 12.92 else ((g + 0.055) / 1.055).pow(2.4)
        val bs = if (b <= 0.03928) b / 12.92 else ((b + 0.055) / 1.055).pow(2.4)
        
        return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
    }
    
    private fun calculateColorDifference(color1: Int, color2: Int): Int {
        val r1 = (color1 shr 16) and 0xFF
        val g1 = (color1 shr 8) and 0xFF
        val b1 = color1 and 0xFF
        
        val r2 = (color2 shr 16) and 0xFF
        val g2 = (color2 shr 8) and 0xFF
        val b2 = color2 and 0xFF
        
        return kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
    }
    
    private fun calculateBrightnessDifference(color1: Int, color2: Int): Int {
        val brightness1 = getBrightness(color1)
        val brightness2 = getBrightness(color2)
        return kotlin.math.abs(brightness1 - brightness2)
    }
    
    private fun getBrightness(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return ((r * 299) + (g * 587) + (b * 114)) / 1000
    }
    
    // Color blindness simulation (simplified)
    private fun simulateProtanopia(theme: Theme): Theme {
        return theme.copy(ansiColors = theme.ansiColors.map { simulateProtanopiaColor(it) }.toIntArray())
    }
    
    private fun simulateDeuteranopia(theme: Theme): Theme {
        return theme.copy(ansiColors = theme.ansiColors.map { simulateDeuteranopiaColor(it) }.toIntArray())
    }
    
    private fun simulateTritanopia(theme: Theme): Theme {
        return theme.copy(ansiColors = theme.ansiColors.map { simulateTritanopiaColor(it) }.toIntArray())
    }
    
    private fun simulateProtanopiaColor(color: Int): Int {
        // Simplified protanopia simulation (removes red sensitivity)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        val newR = (0.567 * r + 0.433 * g).toInt().coerceIn(0, 255)
        val newG = (0.558 * r + 0.442 * g).toInt().coerceIn(0, 255)
        val newB = b
        
        return (0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
    }
    
    private fun simulateDeuteranopiaColor(color: Int): Int {
        // Simplified deuteranopia simulation (removes green sensitivity)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        val newR = (0.625 * r + 0.375 * g).toInt().coerceIn(0, 255)
        val newG = (0.7 * r + 0.3 * g).toInt().coerceIn(0, 255)
        val newB = b
        
        return (0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
    }
    
    private fun simulateTritanopiaColor(color: Int): Int {
        // Simplified tritanopia simulation (removes blue sensitivity)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        
        val newR = r
        val newG = (0.967 * g + 0.033 * b).toInt().coerceIn(0, 255)
        val newB = (0.183 * g + 0.817 * b).toInt().coerceIn(0, 255)
        
        return (0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
    }
}

/**
 * Validation result containing issues and recommendations
 */
class ValidationResult {
    private val issues = mutableListOf<ValidationIssue>()
    
    fun addIssue(issue: ValidationIssue) {
        issues.add(issue)
    }
    
    fun getIssues(): List<ValidationIssue> = issues.toList()
    
    fun isValid(): Boolean = issues.none { it.severity == IssueSeverity.ERROR }
    
    fun hasWarnings(): Boolean = issues.any { it.severity == IssueSeverity.WARNING }
    
    fun hasErrors(): Boolean = issues.any { it.severity == IssueSeverity.ERROR }
    
    fun hasContrastIssues(): Boolean = issues.any { it.category == "contrast" }
    
    fun getValidationSummary(): String {
        val errorCount = issues.count { it.severity == IssueSeverity.ERROR }
        val warningCount = issues.count { it.severity == IssueSeverity.WARNING }
        return "Errors: $errorCount, Warnings: $warningCount"
    }
    
    fun getDetailedReport(): String {
        return buildString {
            appendLine("Theme Validation Report")
            appendLine("======================")
            
            if (issues.isEmpty()) {
                appendLine("✅ No issues found - theme meets accessibility standards")
            } else {
                appendLine("Issues found:")
                issues.forEach { issue ->
                    val icon = when (issue.severity) {
                        IssueSeverity.ERROR -> "❌"
                        IssueSeverity.WARNING -> "⚠️"
                        IssueSeverity.INFO -> "ℹ️"
                    }
                    appendLine("$icon ${issue.severity}: ${issue.description}")
                    if (issue.autoFixAvailable) {
                        appendLine("   🔧 Auto-fix available")
                    }
                }
            }
        }
    }
}

/**
 * Individual validation issue
 */
data class ValidationIssue(
    val severity: IssueSeverity,
    val category: String,
    val description: String,
    val colors: List<Int> = emptyList(),
    val autoFixAvailable: Boolean = false,
    val details: Map<String, String> = emptyMap()
)

/**
 * Severity levels for validation issues
 */
enum class IssueSeverity {
    INFO,    // Informational
    WARNING, // May cause usability issues
    ERROR    // Violates accessibility standards
}