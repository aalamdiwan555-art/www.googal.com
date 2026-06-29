package com.example.data.repository

import com.example.data.database.RecordingDao
import com.example.data.database.RecordingEntity
import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<RecordingEntity>> = recordingDao.getAllRecordings()

    suspend fun insert(recording: RecordingEntity): Long {
        return recordingDao.insertRecording(recording)
    }

    suspend fun deleteById(id: Int) {
        recordingDao.deleteRecordingById(id)
    }

    suspend fun getById(id: Int): RecordingEntity? {
        return recordingDao.getRecordingById(id)
    }
}
