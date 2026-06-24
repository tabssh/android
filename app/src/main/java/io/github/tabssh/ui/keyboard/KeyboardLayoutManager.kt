package io.github.tabssh.ui.keyboard

import io.github.tabssh.utils.logging.Logger

/**
 * Custom keyboard layout persistence helpers.
 *
 * All live callers (MultiRowKeyboardView, PreferenceManager, TabTerminalActivity)
 * use the multi-row JSON helpers exposed here as object methods. The original
 * single-row instance API (getLayout/saveLayout/addKey/removeKey/moveKey/
 * resetToDefault/parseLayout) was unused across the app and removed in Pass 16.
 */
object KeyboardLayoutManager {

    /**
     * Bumped whenever the built-in default layout changes in a way that
     * should be pushed to users who have not explicitly customised their
     * layout. The PreferenceManager stores this value in
     * [PreferenceManager.KEY_LAYOUT_VERSION]; on load, if the stored
     * version is older and the user has not opted-in to a custom layout,
     * the saved JSON is discarded so the new default takes effect.
     */
    const val CURRENT_DEFAULT_LAYOUT_VERSION = 3

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
