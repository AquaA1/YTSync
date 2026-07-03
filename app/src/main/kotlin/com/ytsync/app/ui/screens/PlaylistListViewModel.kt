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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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
        viewModelScope.launch {
            val playlist = repository.getPlaylistById(playlistId) ?: return@launch
            try {
                repository.markPlaylistSyncing(playlistId)

                // 1. Fetch playlist metadata
                val meta = engine.getPlaylistMetadata(playlist.url)

                // 2. Create playlist folder
                val playlistDir = fileManager.getPlaylistDir(meta.name)

                // 3. Update playlist name and songCount in Room
                val updatedPlaylist = playlist.copy(
                    name = meta.name,
                    songCount = meta.totalCount,
                    syncStatus = SyncStatus.SYNCING
                )
                repository.updatePlaylist(updatedPlaylist)

                // 4. Insert/update songs in Room
                val songEntities = meta.songIds.mapIndexed { index, videoId ->
                    val title = meta.songTitles.getOrElse(index) { "Song $videoId" }
                    val existing = repository.getSongByVideoId(playlistId, videoId)
                    existing ?: SongEntity(
                        playlistId = playlistId,
                        videoId = videoId,
                        title = title,
                        downloadStatus = DownloadStatus.PENDING
                    )
                }

                songEntities.forEach { song ->
                    if (song.id == 0L) {
                        repository.insertSong(song)
                    } else {
                        repository.updateSong(song)
                    }
                }

                // 5. Download the playlist (limiting to 3 for Stage 4 live test, or download full if preferred.
                // Let's download the full playlist but allow it to run in the background thread.
                // We don't specify limit here so it syncs all tracks.)
                engine.downloadPlaylist(playlist.url, playlistDir.absolutePath) { progress ->
                    Log.d(TAG, "Syncing playlist $playlistId: $progress%")
                }

                // 6. Scan directory and update song statuses
                val mp3s = fileManager.getMp3Files(playlistDir)
                val allSongsInDb = repository.getSongsForPlaylist(playlistId).first()
                allSongsInDb.forEach { song ->
                    val songFile = fileManager.getSongFile(playlistDir, song.title)
                    if (songFile.exists()) {
                        repository.markSongDownloaded(song.id, songFile.absolutePath)
                    } else {
                        // fallback title contains match by removing all non-alphanumeric characters
                        val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                        val matched = mp3s.find { mp3 ->
                            val mp3Alphanumeric = mp3.nameWithoutExtension.filter { it.isLetterOrDigit() }.lowercase()
                            mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                        }
                        if (matched != null) {
                            repository.markSongDownloaded(song.id, matched.absolutePath)
                        } else {
                            repository.markSongFailed(song.id)
                        }
                    }
                }

                // 7. Write metadata.json
                val finalSongs = repository.getSongsForPlaylist(playlistId).first()
                fileManager.writeMetadata(
                    playlistDir,
                    PlaylistFolderMetadata(
                        url = playlist.url,
                        name = meta.name,
                        downloadedIds = finalSongs.filter { 
                            it.downloadStatus == DownloadStatus.DOWNLOADED && it.filePath != null && File(it.filePath).exists()
                        }.map { it.videoId },
                        totalCount = meta.totalCount,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                )

                // 8. Mark playlist synced
                repository.markPlaylistSynced(playlistId, meta.totalCount)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for playlist $playlistId", e)
                repository.markPlaylistError(playlistId)
                _error.value = "Sync failed: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(playlist)
                // Optionally clean up folder from disk
                val playlistDir = fileManager.getPlaylistDir(playlist.name)
                playlistDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete playlist", e)
            }
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http") && (url.contains("youtube.com") || url.contains("youtu.be"))
    }
}
