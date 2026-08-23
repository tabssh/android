package io.github.tabssh.storage.database

import android.util.Base64
import androidx.room.TypeConverter
import io.github.tabssh.storage.database.entities.PaneWindowConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Room TypeConverters for complex data types
 * Handles serialization/deserialization of custom types
 */
class Converters {

    // Lenient parsing keeps rows written by the previous Gson converters readable
    // (Gson emitted <-style escapes and tolerated loose JSON).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val stringSetSerializer = SetSerializer(String.serializer())
    private val intListSerializer = ListSerializer(Int.serializer())

    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { json.encodeToString(stringListSerializer, it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { json.decodeFromString(stringListSerializer, it) }
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { json.encodeToString(stringMapSerializer, it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let { json.decodeFromString(stringMapSerializer, it) }
    }

    @TypeConverter
    fun fromStringSet(value: Set<String>?): String? {
        return value?.let { json.encodeToString(stringSetSerializer, it) }
    }

    @TypeConverter
    fun toStringSet(value: String?): Set<String>? {
        return value?.let { json.decodeFromString(stringSetSerializer, it) }
    }

    @TypeConverter
    fun fromIntList(value: List<Int>?): String? {
        return value?.let { json.encodeToString(intListSerializer, it) }
    }

    @TypeConverter
    fun toIntList(value: String?): List<Int>? {
        return value?.let { json.decodeFromString(intListSerializer, it) }
    }

    // Manual org.json encoding here — not the kotlinx.serialization path used
    // above for built-in types. Referencing a custom @Serializable class's
    // compiler-plugin-generated PaneWindowConfig.serializer() directly from
    // this Room @TypeConverters class trips a Room-KSP/kotlinx-serialization
    // plugin resolution ordering issue ("MissingType" during kspDebugKotlin).
    // org.json is already used elsewhere in this codebase (e.g.
    // TabTerminalActivity's cloud-host credential JSON) and sidesteps the
    // interaction entirely. PaneWindowConfig itself stays @Serializable for
    // BackupExporter/BackupImporter, which serialize the whole PaneGroup at
    // ordinary compileKotlin time (unaffected by this Room/KSP-only issue).
    @TypeConverter
    fun fromPaneWindowConfigList(value: List<PaneWindowConfig>?): String? {
        if (value == null) return null
        val array = JSONArray()
        for (window in value) {
            val obj = JSONObject()
            obj.put("hostId", window.hostId)
            if (!window.workingDir.isNullOrBlank()) obj.put("workingDir", window.workingDir)
            if (!window.customTitle.isNullOrBlank()) obj.put("customTitle", window.customTitle)
            array.put(obj)
        }
        return array.toString()
    }

    @TypeConverter
    fun toPaneWindowConfigList(value: String?): List<PaneWindowConfig>? {
        if (value.isNullOrBlank()) return value?.let { emptyList() }
        val array = JSONArray(value)
        val result = mutableListOf<PaneWindowConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                PaneWindowConfig(
                    hostId = obj.getString("hostId"),
                    workingDir = obj.optString("workingDir").takeUnless { it.isNullOrBlank() },
                    customTitle = obj.optString("customTitle").takeUnless { it.isNullOrBlank() }
                )
            )
        }
        return result
    }

    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? {
        return value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? {
        return value?.let { Base64.decode(it, Base64.DEFAULT) }
    }

    @TypeConverter
    fun fromUUID(value: UUID?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toUUID(value: String?): UUID? {
        return value?.let { UUID.fromString(it) }
    }
}
