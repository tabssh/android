package io.github.tabssh.themes.definitions

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.themes.validator.ThemeValidator
import io.github.tabssh.themes.parser.ThemeParser
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Manages terminal themes and color schemes
 * Handles built-in themes, custom themes, validation, and application
 */
class ThemeManager(private val context: Context) {
    
    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    private val themeValidator = ThemeValidator()
    private val themeParser = ThemeParser()
    
    // Current active theme
    private val _currentTheme = MutableStateFlow<Theme?>(null)
    val currentTheme: StateFlow<Theme?> = _currentTheme.asStateFlow()
    
    // Available themes
    private val _availableThemes = MutableStateFlow<List<Theme>>(emptyList())
    val availableThemes: StateFlow<List<Theme>> = _availableThemes.asStateFlow()
    
    // Theme cache for performance
    private val themeCache = mutableMapOf<String, Theme>()
    
    // Theme event listeners
    private val listeners = mutableListOf<ThemeChangeListener>()
    
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isInitialized = false
    
    fun initialize() {
        if (isInitialized) return
        
        Logger.d("ThemeManager", "Initializing theme manager")
        
        managerScope.launch {
            try {
                // Install built-in themes if not already present
                installBuiltInThemes()
                
                // Load available themes
                loadAvailableThemes()
                
                // Load current theme from preferences
                loadCurrentTheme()
                
                isInitialized = true
                Logger.i("ThemeManager", "Theme manager initialized with ${_availableThemes.value.size} themes")
                
            } catch (e: Exception) {
                Logger.e("ThemeManager", "Failed to initialize theme manager", e)
            }
        }
    }
    
    private suspend fun installBuiltInThemes() {
        Logger.d("ThemeManager", "Installing built-in themes")
        
        val builtInThemes = BuiltInThemes.getAllThemes()
        val existingThemes = database.themeDao().getBuiltInThemes()
        
        val themesToInstall = builtInThemes.filter { theme ->
            existingThemes.none { it.themeId == theme.id }
        }
        
        if (themesToInstall.isNotEmpty()) {
            val themeDefinitions = themesToInstall.map { theme ->
                ThemeDefinition(
                    themeId = theme.id,
                    name = theme.name,
                    author = theme.author,
                    version = theme.version,
                    isDark = theme.isDark,
                    isBuiltIn = true,
                    backgroundColor = theme.background,
                    foregroundColor = theme.foreground,
                    cursorColor = theme.cursor,
                    selectionColor = theme.selection,
                    ansiColors = Json.encodeToString(kotlinx.serialization.serializer(), theme.ansiColors.toList()),
                    uiColors = encodeUIColors(theme)
                )
            }
            
            database.themeDao().insertThemes(themeDefinitions)
            Logger.i("ThemeManager", "Installed ${themesToInstall.size} built-in themes")
        }
    }
    
    private suspend fun loadAvailableThemes() {
        Logger.d("ThemeManager", "Loading available themes")
        
        val themeDefinitions = database.themeDao().getAllThemes().value ?: emptyList()
        val themes = themeDefinitions.mapNotNull { definition ->
            try {
                convertThemeDefinitionToTheme(definition)
            } catch (e: Exception) {
                Logger.e("ThemeManager", "Failed to convert theme ${definition.name}", e)
                null
            }
        }
        
        _availableThemes.value = themes
        
        // Update cache
        themeCache.clear()
        themes.forEach { theme ->
            themeCache[theme.id] = theme
        }
        
        Logger.d("ThemeManager", "Loaded ${themes.size} themes")
    }
    
    private suspend fun loadCurrentTheme() {
        val currentThemeId = preferenceManager.getTheme()
        val theme = getThemeById(currentThemeId) ?: BuiltInThemes.dracula()
        
        _currentTheme.value = theme
        Logger.d("ThemeManager", "Current theme: ${theme.name}")
    }
    
    /**
     * Apply a theme by ID
     */
    suspend fun applyTheme(themeId: String): Boolean {
        val theme = getThemeById(themeId)
        if (theme == null) {
            Logger.e("ThemeManager", "Theme not found: $themeId")
            return false
        }
        
        return applyTheme(theme)
    }
    
    /**
     * Apply a theme
     */
    suspend fun applyTheme(theme: Theme): Boolean {
        try {
            // Validate theme first
            val validationResult = themeValidator.validateTheme(theme)
            if (!validationResult.isValid()) {
                Logger.w("ThemeManager", "Theme validation failed for ${theme.name}: ${validationResult.getIssues()}")
                notifyListeners { onThemeValidationFailed(theme, validationResult) }
                
                // Auto-fix contrast issues if possible
                val fixedTheme = themeValidator.autoFixContrast(theme)
                if (fixedTheme != null && themeValidator.validateTheme(fixedTheme).isValid()) {
                    Logger.i("ThemeManager", "Auto-fixed theme contrast issues")
                    return applyTheme(fixedTheme)
                }
                
                return false
            }
            
            // Apply the theme
            _currentTheme.value = theme
            
            // Save to preferences
            preferenceManager.setTheme(theme.id)
            
            // Update usage statistics
            if (theme.isBuiltIn) {
                database.themeDao().incrementUsageCount(theme.id)
            }
            
            Logger.i("ThemeManager", "Applied theme: ${theme.name}")
            notifyListeners { onThemeChanged(theme) }
            
            return true
            
        } catch (e: Exception) {
            Logger.e("ThemeManager", "Failed to apply theme ${theme.name}", e)
            return false
        }
    }
    
    /**
     * Get theme by ID
     */
    fun getThemeById(themeId: String): Theme? {
        // Check cache first
        themeCache[themeId]?.let { return it }
        
        // Check built-in themes
        return BuiltInThemes.getThemeById(themeId)
    }
    
    /**
     * Get all available themes
     */
    fun getAvailableThemes(): List<Theme> = _availableThemes.value
    
    /**
     * Get built-in themes only
     */
    fun getBuiltInThemes(): List<Theme> {
        return _availableThemes.value.filter { it.isBuiltIn }
    }
    
    /**
     * Get custom themes only
     */
    fun getCustomThemes(): List<Theme> {
        return _availableThemes.value.filter { !it.isBuiltIn }
    }
    
    /**
     * Import a custom theme from JSON
     */
    suspend fun importTheme(themeJson: String, themeName: String? = null): ImportThemeResult {
        return try {
            val theme = themeParser.parseThemeFromJson(themeJson)
                ?: return ImportThemeResult.Error("Invalid theme format")
            
            // Override name if provided
            val finalTheme = if (themeName != null) {
                theme.copy(name = themeName, id = generateThemeId(themeName))
            } else {
                theme.copy(id = generateThemeId(theme.name))
            }
            
            // Validate theme
            val validationResult = themeValidator.validateTheme(finalTheme)
            if (!validationResult.isValid()) {
                return ImportThemeResult.Error("Theme validation failed: ${validationResult.getIssues()}")
            }
            
            // Save to database
            val themeDefinition = ThemeDefinition(
                themeId = finalTheme.id,
                name = finalTheme.name,
                author = finalTheme.author,
                version = finalTheme.version,
                isDark = finalTheme.isDark,
                isBuiltIn = false,
                backgroundColor = finalTheme.background,
                foregroundColor = finalTheme.foreground,
                cursorColor = finalTheme.cursor,
                selectionColor = finalTheme.selection,
                ansiColors = Json.encodeToString(kotlinx.serialization.serializer(), finalTheme.ansiColors.toList()),
                uiColors = encodeUIColors(finalTheme)
            )
            
            database.themeDao().insertTheme(themeDefinition)
            
            // Reload available themes
            loadAvailableThemes()
            
            Logger.i("ThemeManager", "Imported custom theme: ${finalTheme.name}")
            ImportThemeResult.Success(finalTheme)
            
        } catch (e: Exception) {
            Logger.e("ThemeManager", "Failed to import theme", e)
            ImportThemeResult.Error("Import failed: ${e.message}")
        }
    }
    
    /**
     * Export a theme to JSON
     */
    suspend fun exportTheme(themeId: String): String? {
        val theme = getThemeById(themeId) ?: return null
        
        return try {
            themeParser.themeToJson(theme)
        } catch (e: Exception) {
            Logger.e("ThemeManager", "Failed to export theme $themeId", e)
            null
        }
    }
    
    /**
     * Delete a custom theme
     */
    suspend fun deleteCustomTheme(themeId: String): Boolean {
        val theme = getThemeById(themeId)
        if (theme == null || theme.isBuiltIn) {
            Logger.w("ThemeManager", "Cannot delete built-in theme: $themeId")
            return false
        }
        
        return try {
            database.themeDao().deleteThemeById(themeId)
            themeCache.remove(themeId)
            
            // Reload themes
            loadAvailableThemes()
            
            // Switch to default if this was the current theme
            if (_currentTheme.value?.id == themeId) {
                applyTheme(BuiltInThemes.dracula())
            }
            
            Logger.i("ThemeManager", "Deleted custom theme: $themeId")
            true
            
        } catch (e: Exception) {
            Logger.e("ThemeManager", "Failed to delete theme $themeId", e)
            false
        }
    }
    
    /**
     * Get theme recommendations based on accessibility settings
     */
    fun getRecommendedThemes(): List<Theme> {
        val isHighContrast = preferenceManager.isHighContrastMode()
        
        return if (isHighContrast) {
            BuiltInThemes.getHighContrastThemes()
        } else {
            BuiltInThemes.getAccessibleThemes()
        }
    }
    
    /**
     * Clear theme cache
     */
    fun clearCache() {
        themeCache.clear()
        Logger.d("ThemeManager", "Theme cache cleared")
    }
    
    /**
     * Get theme usage statistics
     */
    suspend fun getThemeStatistics(): ThemeStatistics {
        val allThemes = database.themeDao().getAllThemes().value ?: emptyList()
        val customThemes = allThemes.count { !it.isBuiltIn }
        val totalUsage = allThemes.sumOf { it.usageCount }
        val mostPopular = allThemes.maxByOrNull { it.usageCount }
        
        return ThemeStatistics(
            totalThemes = allThemes.size,
            builtInThemes = allThemes.size - customThemes,
            customThemes = customThemes,
            totalUsageCount = totalUsage,
            mostPopularTheme = mostPopular?.name ?: "Unknown"
        )
    }
    
    private fun generateThemeId(name: String): String {
        return "custom_" + name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_') + "_" + System.currentTimeMillis()
    }
    
    private fun convertThemeDefinitionToTheme(definition: ThemeDefinition): Theme {
        val ansiColors = Json.decodeFromString<List<Int>>(
            kotlinx.serialization.serializer(),
            definition.ansiColors
        ).toIntArray()
        
        val uiColors = definition.uiColors?.let { 
            Json.decodeFromString<Map<String, Int>>(
                kotlinx.serialization.serializer(),
                it
            )
        } ?: emptyMap()
        
        return Theme(
            id = definition.themeId,
            name = definition.name,
            author = definition.author,
            version = definition.version,
            isDark = definition.isDark,
            isBuiltIn = definition.isBuiltIn,
            background = definition.backgroundColor,
            foreground = definition.foregroundColor,
            cursor = definition.cursorColor,
            selection = definition.selectionColor,
            highlight = uiColors["highlight"] ?: definition.selectionColor,
            ansiColors = ansiColors,
            primary = uiColors["primary"],
            secondary = uiColors["secondary"],
            surface = uiColors["surface"],
            onPrimary = uiColors["onPrimary"],
            onSurface = uiColors["onSurface"]
        )
    }
    
    private fun encodeUIColors(theme: Theme): String? {
        val uiColors = mutableMapOf<String, Int>()
        
        theme.primary?.let { uiColors["primary"] = it }
        theme.secondary?.let { uiColors["secondary"] = it }
        theme.surface?.let { uiColors["surface"] = it }
        theme.onPrimary?.let { uiColors["onPrimary"] = it }
        theme.onSurface?.let { uiColors["onSurface"] = it }
        theme.highlight.let { uiColors["highlight"] = it }
        
        return if (uiColors.isNotEmpty()) {
            Json.encodeToString(kotlinx.serialization.serializer(), uiColors)
        } else {
            null
        }
    }
    
    /**
     * Add theme change listener
     */
    fun addThemeChangeListener(listener: ThemeChangeListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove theme change listener
     */
    fun removeThemeChangeListener(listener: ThemeChangeListener) {
        listeners.remove(listener)
    }
    
    private inline fun notifyListeners(action: ThemeChangeListener.() -> Unit) {
        listeners.forEach { it.action() }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Logger.d("ThemeManager", "Cleaning up theme manager")
        
        managerScope.cancel()
        themeCache.clear()
        listeners.clear()
        
        Logger.i("ThemeManager", "Theme manager cleanup complete")
    }
}

/**
 * Result of theme import operation
 */
sealed class ImportThemeResult {
    data class Success(val theme: Theme) : ImportThemeResult()
    data class Error(val message: String) : ImportThemeResult()
}

/**
 * Theme usage statistics
 */
data class ThemeStatistics(
    val totalThemes: Int,
    val builtInThemes: Int,
    val customThemes: Int,
    val totalUsageCount: Int,
    val mostPopularTheme: String
)

/**
 * Interface for theme change events
 */
interface ThemeChangeListener {
    fun onThemeChanged(theme: Theme)
    fun onThemeValidationFailed(theme: Theme, validationResult: com.tabssh.themes.validator.ValidationResult)
}