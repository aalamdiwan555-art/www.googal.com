package com.example.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.RecordingEntity
import com.example.data.models.ClickConfig
import com.example.data.models.ClickMode
import com.example.data.models.PriceConfig
import com.example.data.repository.RecordingRepository
import com.example.data.repository.SettingsRepository
import com.example.service.AutoClickService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val recordingDao = AppDatabase.getDatabase(application).recordingDao()
    private val recordingRepository = RecordingRepository(recordingDao)

    val settingsState: StateFlow<ClickConfig> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ClickConfig()
        )

    val recordingsState: StateFlow<List<RecordingEntity>> = recordingRepository.allRecordings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateSettings(config: ClickConfig) {
        viewModelScope.launch {
            settingsRepository.updateSettings(config)
        }
    }

    fun updatePriceConfig(config: PriceConfig) {
        viewModelScope.launch {
            settingsRepository.updatePriceConfig(config)
        }
    }

    fun deleteRecording(id: Int) {
        viewModelScope.launch {
            recordingRepository.deleteById(id)
        }
    }

    fun saveSequence(name: String, actions: List<com.example.data.models.RecordedAction>) {
        viewModelScope.launch {
            recordingRepository.insert(
                RecordingEntity(
                    name = name,
                    actions = actions
                )
            )
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return AutoClickService.instance != null
    }

    fun isOverlayPermissionEnabled(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}
