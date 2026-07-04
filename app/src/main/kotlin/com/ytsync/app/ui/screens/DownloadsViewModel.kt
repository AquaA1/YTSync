package com.ytsync.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.DownloadPhase
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.repository.PlaylistRepository
import com.ytsync.app.data.file.FileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class DownloadSortOrder {
    DATE_DOWNLOADED,
    TITLE,
    PLAYLIST
}

data class DownloadedSongItem(
    val song: SongEntity,
    val playlistName: String,
    val fileSize: Long,
    val downloadedAt: Long
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val fileManager: FileManager
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(DownloadSortOrder.DATE_DOWNLOADED)
    val sortOrder: StateFlow<DownloadSortOrder> = _sortOrder.asStateFlow()

    private val _filterPlaylistId = MutableStateFlow<Long?>(null)
    val filterPlaylistId: StateFlow<Long?> = _filterPlaylistId.asStateFlow()

    val playlists = repository.allPlaylists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val downloadedSongs: StateFlow<List<DownloadedSongItem>> = combine(
        repository.allSongs,
        repository.allPlaylists,
        _sortOrder,
        _filterPlaylistId
    ) { songs, playlistsList, sort, filterId ->
        val playlistMap = playlistsList.associate { it.id to it.name }
        
        val items = songs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED || it.downloadPhase != DownloadPhase.DONE }
            .map { song ->
                val exists = fileManager.fileExists(song.filePath)
                val size = if (exists) fileManager.getFileLength(song.filePath) else 0L
                val lastModified = if (exists) fileManager.getFileLastModified(song.filePath) else 0L
                DownloadedSongItem(
                    song = song,
                    playlistName = playlistMap[song.playlistId] ?: "Unknown Playlist",
                    fileSize = size,
                    // Prefer the Room-stamped downloadedAt (precise, set at download time).
                    // Fall back to file lastModified for songs that pre-date this field.
                    // For actively downloading songs, assign Long.MAX_VALUE so they sort to the very top.
                    downloadedAt = if (song.downloadPhase != DownloadPhase.DONE && song.downloadStatus != DownloadStatus.DOWNLOADED) {
                        Long.MAX_VALUE
                    } else {
                        song.downloadedAt ?: lastModified
                    }
                )
            }

        val filtered = if (filterId != null) {
            items.filter { it.song.playlistId == filterId }
        } else {
            items
        }

        when (sort) {
            DownloadSortOrder.DATE_DOWNLOADED -> filtered.sortedByDescending { it.downloadedAt }
            DownloadSortOrder.TITLE -> filtered.sortedBy { it.song.title.lowercase() }
            DownloadSortOrder.PLAYLIST -> filtered.sortedBy { it.playlistName.lowercase() }
        }
    }.flowOn(Dispatchers.IO).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setSortOrder(order: DownloadSortOrder) {
        _sortOrder.value = order
    }

    fun setFilterPlaylistId(playlistId: Long?) {
        _filterPlaylistId.value = playlistId
    }

    fun deleteSongFile(songItem: DownloadedSongItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                songItem.song.filePath?.let { path ->
                    fileManager.deleteFile(path)
                }
                repository.resetSongToPending(songItem.song.id)
            } catch (e: Exception) {
                Log.e("DownloadsViewModel", "Failed to delete song file and reset DB", e)
            }
        }
    }
}
