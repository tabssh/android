package com.tabssh.storage.database

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Type converters for Room database
 */
class Converters {
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { json.encodeToString(it) }
    }
    
    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        return value?.let { json.decodeFromString<List<String>>(it) }
    }
    
    @TypeConverter
    fun fromIntList(value: List<Int>?): String? {
        return value?.let { json.encodeToString(it) }
    }
    
    @TypeConverter
    fun toIntList(value: String?): List<Int>? {
        return value?.let { json.decodeFromString<List<Int>>(it) }
    }
    
    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { json.encodeToString(it) }
    }
    
    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let { json.decodeFromString<Map<String, String>>(it) }
    }
    
    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? {
        return value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    }
    
    @TypeConverter
    fun toByteArray(value: String?): ByteArray? {
        return value?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
    }
}