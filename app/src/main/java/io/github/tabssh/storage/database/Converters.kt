package io.github.tabssh.storage.database

import android.util.Base64
import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
