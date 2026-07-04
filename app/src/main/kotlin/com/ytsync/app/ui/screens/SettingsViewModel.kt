package com.ytsync.app.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.repository.PlaylistRepository
import com.ytsync.app.data.settings.SettingsManager
import com.ytsync.app.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

sealed interface RelocationState {
    object Idle : RelocationState
    data class ConfirmDialog(val pendingNewUri: String) : RelocationState
    data class Progress(val current: Int, val total: Int, val currentTitle: String) : RelocationState
    data class Summary(val successCount: Int, val failureCount: Int) : RelocationState
}

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

    private val _useDynamicColor = MutableStateFlow(settingsManager.useDynamicColor)
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor

    val lastSyncedTime: StateFlow<Long?> = repository.allPlaylists
        .map { playlists -> playlists.mapNotNull { it.lastSyncedAt }.maxOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val defaultSaveLocationUri: StateFlow<String?> = settingsManager.defaultSaveLocationUriFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = settingsManager.defaultSaveLocationUri
        )

    val folderDisplayName: StateFlow<String> = settingsManager.defaultSaveLocationUriFlow
        .map { uriString -> getFolderDisplayName(uriString) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = getFolderDisplayName(settingsManager.defaultSaveLocationUri)
        )

    private val _relocationState = MutableStateFlow<RelocationState>(RelocationState.Idle)
    val relocationState: StateFlow<RelocationState> = _relocationState.asStateFlow()

    private fun getFolderDisplayName(uriString: String?): String {
        if (uriString.isNullOrBlank()) return "App Default Private Storage"
        return try {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: "Custom Folder"
        } catch (e: Exception) {
            "Custom Folder"
        }
    }

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

    fun setUseDynamicColor(enabled: Boolean) {
        Log.i(TAG, "Setting useDynamicColor to $enabled")
        settingsManager.useDynamicColor = enabled
        _useDynamicColor.value = enabled
    }

    fun onNewDefaultLocationSelected(uriString: String) {
        _relocationState.value = RelocationState.ConfirmDialog(uriString)
    }

    fun dismissRelocation() {
        _relocationState.value = RelocationState.Idle
    }

    fun applyNewLocationKeepFiles(newUri: String) {
        settingsManager.defaultSaveLocationUri = newUri
        _relocationState.value = RelocationState.Idle
    }

    fun applyNewLocationMoveFiles(newUri: String) {
        viewModelScope.launch {
            val oldUri = settingsManager.defaultSaveLocationUri
            settingsManager.defaultSaveLocationUri = newUri

            _relocationState.value = RelocationState.Progress(0, 0, "Initializing...")

            try {
                val playlists = repository.allPlaylists.first()
                val affectedPlaylists = playlists.filter { it.saveLocationUri.isNullOrBlank() }

                val allSongsToMove = affectedPlaylists.flatMap { playlist ->
                    repository.getSongsForPlaylist(playlist.id).first()
                        .filter { it.downloadStatus == DownloadStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }
                }

                if (allSongsToMove.isEmpty()) {
                    _relocationState.value = RelocationState.Summary(0, 0)
                    return@launch
                }

                var successCount = 0
                var failureCount = 0
                var currentProcessed = 0

                for (playlist in affectedPlaylists) {
                    val (succ, fail) = repository.relocatePlaylistFiles(
                        playlist = playlist,
                        oldSaveLocationUri = oldUri,
                        newSaveLocationUri = newUri
                    ) { currentInPlaylist, _, title ->
                        _relocationState.value = RelocationState.Progress(
                            current = currentProcessed + currentInPlaylist,
                            total = allSongsToMove.size,
                            currentTitle = title
                        )
                    }
                    successCount += succ
                    failureCount += fail
                    currentProcessed += (succ + fail)
                }

                _relocationState.value = RelocationState.Summary(successCount, failureCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed during global location relocation", e)
                _relocationState.value = RelocationState.Summary(0, 0)
            }
        }
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

                // 4. Clear global save location override
                settingsManager.defaultSaveLocationUri = null

                Log.i(TAG, "All data successfully cleared.")
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all data", e)
            }
        }
    }
}
