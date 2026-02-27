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

        /**
         * Parse multi-row layout from JSON string
         * Format: JSON array of arrays of key IDs
         * Example: [["ESC","TAB","CTL"],["UP","DOWN","LEFT","RIGHT"]]
         */
        fun parseLayoutJson(json: String): List<List<KeyboardKey>> {
            val allKeys = KeyboardKey.getAllAvailableKeys()
            val result = mutableListOf<List<KeyboardKey>>()

            try {
                val jsonArray = org.json.JSONArray(json)
                for (i in 0 until jsonArray.length()) {
                    val rowArray = jsonArray.getJSONArray(i)
                    val row = mutableListOf<KeyboardKey>()
                    for (j in 0 until rowArray.length()) {
                        val keyId = rowArray.getString(j)
                        allKeys.find { it.id == keyId }?.let { row.add(it) }
                    }
                    if (row.isNotEmpty()) {
                        result.add(row)
                    }
                }
            } catch (e: Exception) {
                Logger.e("KeyboardLayoutManager", "Failed to parse layout JSON", e)
                throw e
            }

            return result
        }

        /**
         * Convert multi-row layout to JSON string for storage
         */
        fun layoutToJson(layout: List<List<KeyboardKey>>): String {
            val jsonArray = org.json.JSONArray()
            for (row in layout) {
                val rowArray = org.json.JSONArray()
                for (key in row) {
                    rowArray.put(key.id)
                }
                jsonArray.put(rowArray)
            }
            return jsonArray.toString()
        }
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
