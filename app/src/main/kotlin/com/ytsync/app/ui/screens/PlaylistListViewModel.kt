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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ytsync.app.data.sync.SyncWorker
import com.ytsync.app.data.settings.SettingsManager
import com.ytsync.app.data.sync.LaunchCheckManager

private const val TAG = "PlaylistListViewModel"

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val engine: DownloadEngine,
    private val fileManager: FileManager,
    private val settingsManager: SettingsManager,
    private val launchCheckManager: LaunchCheckManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _newSongsCount = MutableStateFlow(0)
    val newSongsCount: StateFlow<Int> = _newSongsCount

    private val _bannerDismissed = MutableStateFlow(false)

    val showUpdateBanner: StateFlow<Boolean> = combine(
        _newSongsCount,
        _bannerDismissed
    ) { count, dismissed ->
        count > 0 && !dismissed
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private var affectedPlaylistIds = emptyList<Long>()
    private var previousSyncingIds = emptySet<Long>()

    // ── Core StateFlows — must be declared BEFORE init so they are non-null
    //    when the init block's coroutine calls playlists.collect() ──────────

    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val lastSyncedTime: StateFlow<Long?> = repository.allPlaylists
        .map { list -> list.mapNotNull { it.lastSyncedAt }.maxOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        scanAndImportLocalPlaylists()
        triggerLaunchCheck(force = false)

        viewModelScope.launch {
            playlists.collect { list ->
                val currentSyncingIds = list.filter { it.syncStatus == SyncStatus.SYNCING }.map { it.id }.toSet()
                val completedSyncIds = previousSyncingIds - currentSyncingIds
                if (completedSyncIds.isNotEmpty()) {
                    val successfullySynced = list.any { it.id in completedSyncIds && it.syncStatus == SyncStatus.SYNCED }
                    if (successfullySynced) {
                        Log.i(TAG, "A sync completed successfully. Resetting banner dismiss state.")
                        _bannerDismissed.value = false
                        triggerLaunchCheck(force = true)
                    }
                }
                previousSyncingIds = currentSyncingIds
            }
        }
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

    fun dismissBanner() {
        _bannerDismissed.value = true
    }

    fun syncAffectedPlaylists() {
        val ids = affectedPlaylistIds
        Log.i(TAG, "Syncing affected playlists from launch check banner: $ids")
        ids.forEach { id ->
            syncPlaylist(id)
        }
        dismissBanner()
    }

    fun triggerLaunchCheck(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. WiFi Check
                val isWifiOnly = settingsManager.isWifiOnly
                val isWifi = launchCheckManager.isWifiConnected()
                if (isWifiOnly && !isWifi) {
                    Log.i(TAG, "Launch Check: WiFi-only is on, but device is not on WiFi. Skipping.")
                    return@launch
                }

                // 2. Debounce Check (15 minutes) - skip if force == true
                val lastChecked = launchCheckManager.getLastCheckedAt()
                val now = System.currentTimeMillis()
                if (!force && (now - lastChecked < 15 * 60 * 1000)) {
                    Log.i(TAG, "Launch Check: Checked ${(now - lastChecked) / 1000}s ago. Skipping due to 15m debounce.")
                    return@launch
                }

                Log.i(TAG, "Launch Check: Starting light library update check (force = $force)...")
                
                // Get all playlists
                val playlistList = repository.allPlaylists.first()
                if (playlistList.isEmpty()) {
                    Log.i(TAG, "Launch Check: No playlists found. Skipping.")
                    return@launch
                }

                // 3. Concurrently fetch metadata for each playlist
                // coroutineScope {} is critical here: it contains any exception/cancellation
                // from the async blocks so they never propagate up to cancel viewModelScope.
                val newSongsResults = coroutineScope {
                    playlistList.map { playlist ->
                        async {
                            try {
                                Log.i("SyncDiagnostics", "Launch Check: getPlaylistMetadata start for URL: ${playlist.url}")
                                val meta = engine.getPlaylistMetadata(playlist.url)
                                val allSongsInDb = repository.getSongsForPlaylist(playlist.id).first()
                                val existingVideoIds = allSongsInDb.map { it.videoId }.toSet()
                                val newCount = meta.songIds.count { it !in existingVideoIds }
                                Log.i("SyncDiagnostics", "Launch Check: playlist ${playlist.name} has $newCount new songs")
                                if (newCount > 0) {
                                    playlist.id to newCount
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                Log.e("SyncDiagnostics", "Launch Check: Failed to fetch metadata for ${playlist.name}", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                val totalNewSongs = newSongsResults.sumOf { it.second }
                val affectedIds = newSongsResults.map { it.first }

                // Save last checked timestamp in DataStore
                launchCheckManager.setLastCheckedAt(now)

                if (totalNewSongs > 0) {
                    affectedPlaylistIds = affectedIds
                    _newSongsCount.value = totalNewSongs
                    // Reset dismiss status since a fresh check found something new
                    _bannerDismissed.value = false
                    Log.i(TAG, "Launch Check completed. Found $totalNewSongs new songs across ${affectedIds.size} playlists.")
                } else {
                    affectedPlaylistIds = emptyList()
                    _newSongsCount.value = 0
                    Log.i(TAG, "Launch Check completed. Library is up to date.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch Check: Failed silently during execution", e)
            }
        }
    }


    fun updateYtdlp() {
        viewModelScope.launch {
            _error.value = "Updating downloader binary…"
            try {
                val status = withContext(Dispatchers.IO) {
                    com.ytsync.app.data.engine.YoutubeDLInitializer.awaitInitialization()
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

    fun clearError() {
        _error.value = null
    }

    fun addPlaylist(url: String, saveLocationUri: String?) {
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
                    syncStatus = SyncStatus.SYNCING,
                    saveLocationUri = saveLocationUri
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deletePlaylist(playlist)
                if (deleteFiles) {
                    val (playlistDir, _) = fileManager.getPlaylistDocumentDir(playlist.name, playlist.saveLocationUri)
                    if (playlistDir.exists()) {
                        playlistDir.delete()
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
