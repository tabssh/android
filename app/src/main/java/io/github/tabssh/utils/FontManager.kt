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

}
