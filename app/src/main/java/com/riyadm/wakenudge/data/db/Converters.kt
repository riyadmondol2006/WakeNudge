package com.riyadm.wakenudge.data.db

import androidx.room.TypeConverter
import com.riyadm.wakenudge.core.WakeJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    @TypeConverter
    fun stringListToJson(value: List<String>): String =
        WakeJson.instance.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> =
        runCatching { WakeJson.instance.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

    @TypeConverter
    fun stringSetToJson(value: Set<String>): String =
        WakeJson.instance.encodeToString(SetSerializer(String.serializer()), value)

    @TypeConverter
    fun jsonToStringSet(value: String): Set<String> =
        runCatching { WakeJson.instance.decodeFromString(SetSerializer(String.serializer()), value) }.getOrDefault(emptySet())

    @TypeConverter
    fun longListToJson(value: List<Long>): String =
        WakeJson.instance.encodeToString(ListSerializer(Long.serializer()), value)

    @TypeConverter
    fun jsonToLongList(value: String): List<Long> =
        runCatching { WakeJson.instance.decodeFromString(ListSerializer(Long.serializer()), value) }.getOrDefault(emptyList())
}
