package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.models.RecordedAction

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actions: List<RecordedAction>
)
