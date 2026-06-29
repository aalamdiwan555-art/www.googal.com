package com.example.data.database

import androidx.room.TypeConverter
import com.example.data.models.RecordedAction
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class ActionConverter {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val listType = Types.newParameterizedType(List::class.java, RecordedAction::class.java)
    private val adapter = moshi.adapter<List<RecordedAction>>(listType)

    @TypeConverter
    fun fromString(value: String?): List<RecordedAction>? {
        if (value == null) return emptyList()
        return try {
            adapter.fromJson(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(list: List<RecordedAction>?): String? {
        if (list == null) return "[]"
        return try {
            adapter.toJson(list)
        } catch (e: Exception) {
            "[]"
        }
    }
}
