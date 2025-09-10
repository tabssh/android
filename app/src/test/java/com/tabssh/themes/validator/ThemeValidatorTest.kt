package io.github.tabssh.themes.validator

import io.github.tabssh.themes.definitions.BuiltInThemes
import io.github.tabssh.themes.definitions.Theme
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Comprehensive tests for theme validation and accessibility compliance
 */
class ThemeValidatorTest {
    
    private lateinit var validator: ThemeValidator
    
    @Before
    fun setUp() {
        validator = ThemeValidator()
    }
    
    @Test
    fun `test valid theme passes validation`() {
        val theme = BuiltInThemes.dracula()
        val result = validator.validateTheme(theme)
        
        assertTrue(result.isValid())
        assertFalse(result.hasErrors())
    }
    
    @Test
    fun `test low contrast theme fails validation`() {
        val lowContrastTheme = Theme(
            id = "low_contrast",
            name = "Low Contrast",
            isDark = true,
            background = 0xFF000000.toInt(), // Black
            foreground = 0xFF333333.toInt(), // Dark gray (low contrast)
            cursor = 0xFF333333.toInt(),
            selection = 0x44404040.toInt(),
            highlight = 0xFF404040.toInt(),
            ansiColors = IntArray(16) { 0xFF333333.toInt() } // All low contrast
        )
        
        val result = validator.validateTheme(lowContrastTheme)
        
        assertFalse(result.isValid())
        assertTrue(result.hasErrors())
        assertTrue(result.hasContrastIssues())
    }
    
    @Test
    fun `test contrast ratio calculation`() {
        // Test known contrast ratios
        val blackOnWhite = Theme.calculateContrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, blackOnWhite, 0.1) // Perfect contrast
        
        val whiteOnBlack = Theme.calculateContrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(21.0, whiteOnBlack, 0.1) // Same as black on white
        
        val grayOnGray = Theme.calculateContrastRatio(0xFF808080.toInt(), 0xFF808080.toInt())
        assertEquals(1.0, grayOnGray, 0.1) // No contrast
    }
    
    @Test
    fun `test auto-fix contrast`() {
        val lowContrastTheme = Theme(
            id = "low_contrast",
            name = "Low Contrast",
            isDark = true,
            background = 0xFF000000.toInt(),
            foreground = 0xFF333333.toInt(), // Too dark
            cursor = 0xFF333333.toInt(),
            selection = 0x44404040.toInt(),
            highlight = 0xFF404040.toInt(),
            ansiColors = IntArray(16) { if (it % 2 == 0) 0xFF333333.toInt() else 0xFF666666.toInt() }
        )
        
        val fixedTheme = validator.autoFixContrast(lowContrastTheme)
        assertNotNull(fixedTheme)
        
        // Fixed theme should have better contrast
        val originalContrast = Theme.calculateContrastRatio(lowContrastTheme.foreground, lowContrastTheme.background)
        val fixedContrast = Theme.calculateContrastRatio(fixedTheme.foreground, fixedTheme.background)
        
        assertTrue(fixedContrast > originalContrast)
        assertTrue(fixedContrast >= 4.5) // Should meet AA standard
    }
    
    @Test
    fun `test validation result reporting`() {
        val theme = Theme(
            id = "test_theme",
            name = "Test Theme",
            isDark = true,
            background = 0xFF000000.toInt(),
            foreground = 0xFF333333.toInt(), // Low contrast
            cursor = 0xFF333333.toInt(),
            selection = 0x44404040.toInt(),
            highlight = 0xFF404040.toInt(),
            ansiColors = IntArray(16) { 0xFF333333.toInt() }
        )
        
        val result = validator.validateTheme(theme)
        
        assertFalse(result.isValid())
        assertTrue(result.hasErrors())
        assertTrue(result.hasContrastIssues())
        
        val issues = result.getIssues()
        assertTrue(issues.isNotEmpty())
        
        val contrastIssue = issues.find { it.category == "contrast" }
        assertNotNull(contrastIssue)
        assertEquals(IssueSeverity.ERROR, contrastIssue.severity)
        assertTrue(contrastIssue.autoFixAvailable)
        
        val summary = result.getValidationSummary()
        assertTrue(summary.contains("Errors:"))
        
        val report = result.getDetailedReport()
        assertTrue(report.contains("Theme Validation Report"))
        assertTrue(report.contains("❌"))
    }
    
    @Test
    fun `test ANSI color validation`() {
        // Create theme with some ANSI colors that have low contrast
        val ansiColors = IntArray(16) { index ->
            when (index) {
                0 -> 0xFF000000.toInt() // Black on black - no contrast
                1 -> 0xFF111111.toInt() // Very dark red - low contrast
                else -> 0xFFFFFFFF.toInt() // White - good contrast
            }
        }
        
        val theme = Theme(
            id = "ansi_test",
            name = "ANSI Test",
            isDark = true,
            background = 0xFF000000.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            selection = 0x44404040.toInt(),
            highlight = 0xFF404040.toInt(),
            ansiColors = ansiColors
        )
        
        val result = validator.validateTheme(theme)
        
        // Should have warnings for low contrast ANSI colors
        assertTrue(result.hasWarnings())
        
        val ansiIssues = result.getIssues().filter { it.category == "ansi_color" }
        assertTrue(ansiIssues.isNotEmpty())
        
        // Should have issues for colors 0 and 1
        val color0Issue = ansiIssues.find { it.details["ansi_index"] == "0" }
        val color1Issue = ansiIssues.find { it.details["ansi_index"] == "1" }
        assertNotNull(color0Issue)
        assertNotNull(color1Issue)
    }
    
    @Test
    fun `test UI color validation`() {
        val theme = Theme(
            id = "ui_test",
            name = "UI Test",
            isDark = false,
            background = 0xFFFFFFFF.toInt(),
            foreground = 0xFF000000.toInt(),
            cursor = 0xFF000000.toInt(),
            selection = 0x44404040.toInt(),
            highlight = 0xFF404040.toInt(),
            ansiColors = IntArray(16) { if (it < 8) 0xFF000000.toInt() else 0xFF808080.toInt() },
            primary = 0xFFEEEEEE.toInt(), // Light gray
            onPrimary = 0xFFDDDDDD.toInt() // Slightly darker gray (low contrast)
        )
        
        val result = validator.validateTheme(theme)
        
        // Should have UI color contrast issues
        val uiColorIssues = result.getIssues().filter { it.category == "ui_color" }
        assertTrue(uiColorIssues.isNotEmpty())
    }
    
    @Test
    fun `test all built-in themes meet standards`() {
        val builtInThemes = BuiltInThemes.getAllThemes()
        
        builtInThemes.forEach { theme ->
            val result = validator.validateTheme(theme)
            
            // All built-in themes should be valid (may have warnings but no errors)
            assertTrue(result.isValid(), "Theme ${theme.name} failed validation: ${result.getValidationSummary()}")
            
            // Check that they meet basic accessibility standards
            assertTrue(theme.meetsAccessibilityStandards(), "Theme ${theme.name} doesn't meet accessibility standards")
        }
    }
    
    @Test
    fun `test high contrast theme creation`() {
        val originalTheme = BuiltInThemes.dracula()
        val highContrastTheme = originalTheme.toHighContrast()
        
        assertNotEquals(originalTheme.id, highContrastTheme.id)
        assertTrue(highContrastTheme.name.contains("High Contrast"))
        
        // High contrast theme should have better contrast
        val originalContrast = Theme.calculateContrastRatio(originalTheme.foreground, originalTheme.background)
        val highContrastContrast = Theme.calculateContrastRatio(highContrastTheme.foreground, highContrastTheme.background)
        
        assertTrue(highContrastContrast >= originalContrast)
        assertTrue(highContrastContrast >= 7.0) // Should meet AAA standard
    }
    
    @Test
    fun `test validation issue severity levels`() {
        val infoIssue = ValidationIssue(
            severity = IssueSeverity.INFO,
            category = "info",
            description = "Info message"
        )
        
        val warningIssue = ValidationIssue(
            severity = IssueSeverity.WARNING,
            category = "warning", 
            description = "Warning message"
        )
        
        val errorIssue = ValidationIssue(
            severity = IssueSeverity.ERROR,
            category = "error",
            description = "Error message"
        )
        
        val result = ValidationResult()
        result.addIssue(infoIssue)
        result.addIssue(warningIssue)
        
        assertTrue(result.isValid()) // No errors yet
        assertTrue(result.hasWarnings())
        
        result.addIssue(errorIssue)
        assertFalse(result.isValid()) // Now has errors
        assertTrue(result.hasErrors())
    }
    
    @Test
    fun `test color blindness simulation`() {
        val theme = BuiltInThemes.solarizedDark()
        
        // Test that color blindness simulation doesn't crash
        val result = validator.validateTheme(theme)
        
        // May have warnings about color blindness but shouldn't be errors
        val colorBlindnessIssues = result.getIssues().filter { it.category == "color_blindness" }
        colorBlindnessIssues.forEach { issue ->
            assertTrue(issue.severity == IssueSeverity.WARNING || issue.severity == IssueSeverity.INFO)
        }
    }
}