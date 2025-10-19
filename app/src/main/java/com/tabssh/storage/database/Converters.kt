package com.tabssh.storage.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

/**
 * Room TypeConverters for complex data types
 * Handles serialization/deserialization of custom types
 */
class Converters {

    private val gson = Gson()

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
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let {
            val listType = object : TypeToken<List<String>>() {}.type
            gson.fromJson(it, listType)
        }
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let {
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(it, mapType)
        }
    }

    @TypeConverter
    fun fromStringSet(value: Set<String>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringSet(value: String?): Set<String>? {
        return value?.let {
            val setType = object : TypeToken<Set<String>>() {}.type
            gson.fromJson(it, setType)
        }
    }

    @TypeConverter
    fun fromIntList(value: List<Int>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toIntList(value: String?): List<Int>? {
        return value?.let {
            val listType = object : TypeToken<List<Int>>() {}.type
            gson.fromJson(it, listType)
        }
    }

    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? {
        return value?.let { Base64.getEncoder().encodeToString(it) }
    }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? {
        return value?.let { Base64.getDecoder().decode(it) }
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