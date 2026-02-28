package io.github.tabssh.utils

import android.content.Context
import android.graphics.Typeface
import io.github.tabssh.utils.logging.Logger

/**
 * Manages custom fonts including Nerdfonts for terminal display
 * Nerdfonts include icons for powerline, devicons, and more
 */
object FontManager {

    private const val TAG = "FontManager"
    private const val FONTS_ASSET_PATH = "fonts"

    // Font cache to avoid reloading
    private val fontCache = mutableMapOf<String, Typeface>()

    // Map of font values to asset filenames
    private val fontAssetMap = mapOf(
        "monospace" to null, // System default
        "jetbrains_mono_nerd" to "JetBrainsMonoNerdFont-Regular.ttf",
        "fira_code_nerd" to "FiraCodeNerdFont-Regular.ttf",
        "hack_nerd" to "HackNerdFont-Regular.ttf",
        "cascadia_code_nerd" to "CascadiaCodeNerdFont-Regular.ttf",
        "source_code_pro_nerd" to "SourceCodeProNerdFont-Regular.ttf",
        "meslo_nerd" to "MesloLGSNerdFont-Regular.ttf",
        "roboto_mono_nerd" to "RobotoMonoNerdFont-Regular.ttf",
        "ubuntu_mono_nerd" to "UbuntuMonoNerdFont-Regular.ttf",
        "dejavu_mono_nerd" to "DejaVuSansMNerdFont-Regular.ttf"
    )

    /**
     * Get typeface for the given font value
     * Returns system monospace as fallback if font not found
     */
    fun getTypeface(context: Context, fontValue: String): Typeface {
        // Return cached font if available
        fontCache[fontValue]?.let { return it }

        // System default monospace
        if (fontValue == "monospace" || fontAssetMap[fontValue] == null) {
            return Typeface.MONOSPACE
        }

        val assetFilename = fontAssetMap[fontValue]
        if (assetFilename == null) {
            Logger.w(TAG, "Unknown font value: $fontValue, using system monospace")
            return Typeface.MONOSPACE
        }

        return try {
            val typeface = Typeface.createFromAsset(context.assets, "$FONTS_ASSET_PATH/$assetFilename")
            fontCache[fontValue] = typeface
            Logger.d(TAG, "Loaded font: $fontValue from $assetFilename")
            typeface
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load font $fontValue ($assetFilename): ${e.message}")
            // Return system monospace as fallback
            Typeface.MONOSPACE
        }
    }

    /**
     * Check if a font is available (exists in assets)
     */
    fun isFontAvailable(context: Context, fontValue: String): Boolean {
        if (fontValue == "monospace") return true

        val assetFilename = fontAssetMap[fontValue] ?: return false

        return try {
            context.assets.open("$FONTS_ASSET_PATH/$assetFilename").use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get list of available fonts (that exist in assets)
     */
    fun getAvailableFonts(context: Context): List<String> {
        return fontAssetMap.keys.filter { isFontAvailable(context, it) }
    }

    /**
     * Get display name for a font value
     */
    fun getFontDisplayName(fontValue: String): String {
        return when (fontValue) {
            "monospace" -> "System Monospace"
            "jetbrains_mono_nerd" -> "JetBrains Mono Nerd"
            "fira_code_nerd" -> "Fira Code Nerd"
            "hack_nerd" -> "Hack Nerd"
            "cascadia_code_nerd" -> "Cascadia Code Nerd"
            "source_code_pro_nerd" -> "Source Code Pro Nerd"
            "meslo_nerd" -> "Meslo Nerd"
            "roboto_mono_nerd" -> "Roboto Mono Nerd"
            "ubuntu_mono_nerd" -> "Ubuntu Mono Nerd"
            "dejavu_mono_nerd" -> "DejaVu Sans Mono Nerd"
            else -> fontValue
        }
    }

    /**
     * Clear the font cache (useful when low on memory)
     */
    fun clearCache() {
        fontCache.clear()
        Logger.d(TAG, "Font cache cleared")
    }

    /**
     * Get font info for display
     */
    fun getFontInfo(fontValue: String): FontInfo {
        val isNerdfont = fontValue.contains("nerd")
        val assetFilename = fontAssetMap[fontValue]

        return FontInfo(
            value = fontValue,
            displayName = getFontDisplayName(fontValue),
            assetFilename = assetFilename,
            isNerdfont = isNerdfont,
            hasLigatures = fontValue in listOf("fira_code_nerd", "jetbrains_mono_nerd", "cascadia_code_nerd"),
            hasPowerlineSymbols = isNerdfont
        )
    }

    data class FontInfo(
        val value: String,
        val displayName: String,
        val assetFilename: String?,
        val isNerdfont: Boolean,
        val hasLigatures: Boolean,
        val hasPowerlineSymbols: Boolean
    )
}
