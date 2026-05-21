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
         * Bumped whenever the built-in default layout changes in a way that
         * should be pushed to users who have not explicitly customised their
         * layout. The PreferenceManager stores this value in
         * [PreferenceManager.KEY_LAYOUT_VERSION]; on load, if the stored
         * version is older and the user has not opted-in to a custom layout,
         * the saved JSON is discarded so the new default takes effect.
         */
        const val CURRENT_DEFAULT_LAYOUT_VERSION = 2

        /**
         * Parse multi-row layout from JSON string.
         *
         * Format: JSON array of arrays.  Each element in a row array is either:
         *   - a plain string  — key ID, widthMultiplier = 1f
         *   - a JSON object   — {"id":"CTL","w":2.0}, widthMultiplier from "w"
         *
         * The object form allows persisting non-standard widths set by the
         * default layout (e.g. CTL/TAB/ENT/ESC at 2×) through save/load cycles.
         *
         * Example: [["ESC","TAB"],["UP",{"id":"DOWN","w":2.0}]]
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
                        val item = rowArray.get(j)
                        val keyId: String
                        val widthMultiplier: Float
                        when (item) {
                            is org.json.JSONObject -> {
                                keyId = item.getString("id")
                                widthMultiplier = item.optDouble("w", 1.0).toFloat()
                            }
                            else -> {
                                keyId = item.toString()
                                widthMultiplier = 1f
                            }
                        }
                        val key = allKeys.find { it.id == keyId }
                        if (key != null) {
                            row.add(
                                if (widthMultiplier != 1f) key.copy(widthMultiplier = widthMultiplier)
                                else key
                            )
                        } else {
                            Logger.w("KeyboardLayoutManager", "Unknown key id '$keyId' in saved layout — skipped")
                        }
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
         * Convert multi-row layout to JSON string for storage.
         *
         * Keys with widthMultiplier == 1f are stored as plain strings for
         * compactness and backward compatibility.  Keys with a non-default
         * multiplier are stored as objects: {"id":"CTL","w":2.0}.
         */
        fun layoutToJson(layout: List<List<KeyboardKey>>): String {
            val jsonArray = org.json.JSONArray()
            for (row in layout) {
                val rowArray = org.json.JSONArray()
                for (key in row) {
                    if (key.widthMultiplier != 1f) {
                        val obj = org.json.JSONObject()
                        obj.put("id", key.id)
                        obj.put("w", key.widthMultiplier.toDouble())
                        rowArray.put(obj)
                    } else {
                        rowArray.put(key.id)
                    }
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
