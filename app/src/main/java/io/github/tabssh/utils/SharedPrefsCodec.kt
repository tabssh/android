package io.github.tabssh.utils

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import io.github.tabssh.utils.logging.Logger

/**
 * Type-preserving encode/decode for whole SharedPreferences files.
 *
 * A SharedPreferences value is not always a String — `cluster_commands` stores
 * its history as a `Set<String>`, and any Boolean/Int/Long/Float written by a
 * Preference widget keeps its own type. Flattening everything to a String makes
 * the *next* typed read (`getStringSet`, `getInt`, …) throw ClassCastException,
 * so both the backup archive and the sync payload carry an explicit type tag.
 *
 * Wire shape, one entry per preference key:
 * `{"t": "<type>", "v": <value>}` where `<type>` is one of
 * `string` · `bool` · `int` · `long` · `float` · `set`. For `set`, `v` is a
 * JSON array of strings; for every other type `v` is a JSON primitive.
 */
object SharedPrefsCodec {

    private const val TAG = "SharedPrefsCodec"

    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "v"

    private const val T_STRING = "string"
    private const val T_BOOL = "bool"
    private const val T_INT = "int"
    private const val T_LONG = "long"
    private const val T_FLOAT = "float"
    private const val T_SET = "set"

    /** Encode one SharedPreferences value into its tagged wire form. */
    fun encodeValue(value: Any?): JsonObject = when (value) {
        is Boolean -> tagged(T_BOOL, JsonPrimitive(value))
        is Int -> tagged(T_INT, JsonPrimitive(value))
        is Long -> tagged(T_LONG, JsonPrimitive(value))
        is Float -> tagged(T_FLOAT, JsonPrimitive(value))
        is Set<*> -> tagged(T_SET, JsonArray(value.map { JsonPrimitive(it?.toString() ?: "") }))
        else -> tagged(T_STRING, JsonPrimitive(value?.toString() ?: ""))
    }

    /** Encode every key in [prefs] into a `key → tagged value` map. */
    fun encodeAll(prefs: SharedPreferences): Map<String, JsonObject> =
        prefs.all.mapValues { (_, v) -> encodeValue(v) }

    /**
     * Write one tagged value into [editor] under [key].
     *
     * Returns false when [element] is not a well-formed tagged value, so the
     * caller can skip it rather than write a wrong-typed entry.
     */
    fun decodeInto(editor: SharedPreferences.Editor, key: String, element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        val type = (obj[KEY_TYPE] as? JsonPrimitive)?.content ?: return false
        val raw = obj[KEY_VALUE] ?: return false
        return try {
            when (type) {
                T_BOOL -> editor.putBoolean(key, raw.jsonPrimitive.content.toBoolean())
                T_INT -> editor.putInt(key, raw.jsonPrimitive.content.toInt())
                T_LONG -> editor.putLong(key, raw.jsonPrimitive.content.toLong())
                T_FLOAT -> editor.putFloat(key, raw.jsonPrimitive.content.toFloat())
                T_SET -> editor.putStringSet(
                    key,
                    raw.jsonArray.map { it.jsonPrimitive.content }.toSet()
                )
                T_STRING -> editor.putString(key, raw.jsonPrimitive.content)
                else -> return false
            }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Malformed preference value for key $key (type=$type): ${e.message}")
            false
        }
    }

    private fun tagged(type: String, value: JsonElement): JsonObject = buildJsonObject {
        put(KEY_TYPE, JsonPrimitive(type))
        put(KEY_VALUE, value)
    }
}
