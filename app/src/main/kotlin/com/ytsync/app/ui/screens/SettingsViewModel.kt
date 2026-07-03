package com.ytsync.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.repository.PlaylistRepository
import com.ytsync.app.data.settings.SettingsManager
import com.ytsync.app.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val syncScheduler: SyncScheduler,
    private val repository: PlaylistRepository,
    private val fileManager: FileManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isWifiOnly = MutableStateFlow(settingsManager.isWifiOnly)
    val isWifiOnly: StateFlow<Boolean> = _isWifiOnly

    private val _syncFrequencyHours = MutableStateFlow(settingsManager.syncFrequencyHours)
    val syncFrequencyHours: StateFlow<Int> = _syncFrequencyHours

    fun setWifiOnly(wifiOnly: Boolean) {
        Log.i(TAG, "Setting wifiOnly to $wifiOnly")
        settingsManager.isWifiOnly = wifiOnly
        _isWifiOnly.value = wifiOnly
        syncScheduler.scheduleBackgroundSync()
    }

    fun setSyncFrequencyHours(hours: Int) {
        Log.i(TAG, "Setting sync frequency to $hours hours")
        settingsManager.syncFrequencyHours = hours
        _syncFrequencyHours.value = hours
        syncScheduler.scheduleBackgroundSync()
    }

    fun clearAllData(onComplete: () -> Unit) {
        Log.w(TAG, "User requested full data clear.")
        viewModelScope.launch {
            try {
                // 1. Cancel all WorkManager background and sync requests
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(context).cancelAllWork()
                }

                // 2. Clear local downloads from external storage files directory
                withContext(Dispatchers.IO) {
                    fileManager.clearAllDownloads()
                }

                // 3. Clear database tables
                repository.clearAllData()

                Log.i(TAG, "All data successfully cleared.")
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all data", e)
            }
        }
    }
}
