package io.github.tabssh.ui.keyboard

import android.content.Context
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.utils.logging.Logger

/**
 * Manages custom keyboard layout and persistence
 */
class KeyboardLayoutManager(
    private val context: Context,
    private val preferencesManager: PreferenceManager
) {
    
    companion object {
        private const val PREF_KEYBOARD_LAYOUT = "custom_keyboard_layout"
        private const val SEPARATOR = ","
    }
    
    /**
     * Get current keyboard layout
     */
    fun getLayout(): List<KeyboardKey> {
        val savedLayout = preferencesManager.getString(PREF_KEYBOARD_LAYOUT, "")
        
        return if (savedLayout.isNotEmpty()) {
            parseLayout(savedLayout)
        } else {
            KeyboardKey.getDefaultKeys()
        }
    }
    
    /**
     * Save keyboard layout
     */
    fun saveLayout(keys: List<KeyboardKey>) {
        val layoutString = keys.joinToString(SEPARATOR) { it.id }
        preferencesManager.setString(PREF_KEYBOARD_LAYOUT, layoutString)
        Logger.i("KeyboardLayoutManager", "Saved keyboard layout: ${keys.size} keys")
    }
    
    /**
     * Reset to default layout
     */
    fun resetToDefault() {
        saveLayout(KeyboardKey.getDefaultKeys())
        Logger.i("KeyboardLayoutManager", "Reset keyboard to default layout")
    }
    
    /**
     * Parse layout from string
     */
    private fun parseLayout(layoutString: String): List<KeyboardKey> {
        val allKeys = KeyboardKey.getAllAvailableKeys()
        val keyIds = layoutString.split(SEPARATOR)
        
        return keyIds.mapNotNull { id ->
            allKeys.find { it.id == id }
        }
    }
    
    /**
     * Add key to layout
     */
    fun addKey(key: KeyboardKey, position: Int? = null) {
        val currentLayout = getLayout().toMutableList()
        
        if (position != null && position in 0..currentLayout.size) {
            currentLayout.add(position, key)
        } else {
            currentLayout.add(key)
        }
        
        saveLayout(currentLayout)
    }
    
    /**
     * Remove key from layout
     */
    fun removeKey(keyId: String) {
        val currentLayout = getLayout().toMutableList()
        currentLayout.removeAll { it.id == keyId }
        saveLayout(currentLayout)
    }
    
    /**
     * Move key to new position
     */
    fun moveKey(fromPosition: Int, toPosition: Int) {
        val currentLayout = getLayout().toMutableList()
        
        if (fromPosition in currentLayout.indices && toPosition in currentLayout.indices) {
            val key = currentLayout.removeAt(fromPosition)
            currentLayout.add(toPosition, key)
            saveLayout(currentLayout)
        }
    }
}
