package com.ytsync.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.db.entity.SyncStatus
import com.ytsync.app.data.engine.DownloadEngine
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.file.PlaylistFolderMetadata
import com.ytsync.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ytsync.app.data.sync.SyncWorker

private const val TAG = "PlaylistListViewModel"

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val engine: DownloadEngine,
    private val fileManager: FileManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    init {
        scanAndImportLocalPlaylists()
    }

    private fun scanAndImportLocalPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dirs = fileManager.getExistingPlaylistDirs()
                dirs.forEach { dir ->
                    val metadata = fileManager.readMetadata(dir) ?: return@forEach
                    val existing = repository.getPlaylistByUrl(metadata.url)
                    if (existing == null) {
                        Log.i(TAG, "Importing local playlist found on disk: ${metadata.name}")
                        val newPlaylist = PlaylistEntity(
                            url = metadata.url,
                            name = metadata.name,
                            songCount = metadata.totalCount,
                            syncStatus = SyncStatus.SYNCING,
                            lastSyncedAt = metadata.lastSyncTimestamp
                        )
                        val id = repository.insertPlaylist(newPlaylist)
                        syncPlaylist(id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning and importing local playlists", e)
            }
        }
    }

    fun updateYtdlp() {
        viewModelScope.launch {
            _error.value = "Updating downloader binary…"
            try {
                val status = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                }
                Log.i(TAG, "yt-dlp update status: $status")
                if (status == YoutubeDL.UpdateStatus.DONE || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) {
                    _error.value = "Downloader is up-to-date!"
                } else {
                    _error.value = "Downloader update failed: status is $status"
                }
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp update failed", e)
                _error.value = "Update failed: ${e.message}"
            }
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val lastSyncedTime: StateFlow<Long?> = repository.allPlaylists
        .map { playlists -> playlists.mapNotNull { it.lastSyncedAt }.maxOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() {
        _error.value = null
    }

    fun addPlaylist(url: String) {
        val cleanUrl = url.replace("\\s".toRegex(), "")
        if (!isValidUrl(cleanUrl)) {
            _error.value = "Invalid YouTube playlist URL"
            return
        }
        viewModelScope.launch {
            // Check if already exists to avoid duplicates
            val exists = playlists.value.any { it.url.equals(cleanUrl, ignoreCase = true) }
            if (exists) {
                _error.value = "Playlist is already added"
                return@launch
            }

            try {
                val newPlaylist = PlaylistEntity(
                    url = cleanUrl,
                    name = "Loading Playlist…",
                    syncStatus = SyncStatus.SYNCING
                )
                val id = repository.insertPlaylist(newPlaylist)
                syncPlaylist(id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add playlist", e)
                _error.value = e.message ?: "Unknown error"
            }
        }
    }

    fun syncPlaylist(playlistId: Long) {
        Log.i(TAG, "Enqueuing sync request for playlist $playlistId via WorkManager")
        val data = workDataOf("playlistId" to playlistId)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync_playlist_$playlistId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun syncAllPlaylists() {
        Log.i(TAG, "Syncing all playlists")
        viewModelScope.launch {
            playlists.value.forEach { playlist ->
                syncPlaylist(playlist.id)
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity, deleteFiles: Boolean) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(playlist)
                if (deleteFiles) {
                    val playlistDir = fileManager.getPlaylistDir(playlist.name)
                    if (playlistDir.exists()) {
                        playlistDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete playlist", e)
            }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http") && (url.contains("youtube.com") || url.contains("youtu.be"))
    }
}
