package com.ytsync.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.repository.PlaylistRepository
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
    private val repository: PlaylistRepository
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
        
        val items = songs.filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
            .map { song ->
                val file = song.filePath?.let { File(it) }
                val exists = file?.exists() == true
                val size = if (exists) file!!.length() else 0L
                val lastModified = if (exists) file!!.lastModified() else 0L
                DownloadedSongItem(
                    song = song,
                    playlistName = playlistMap[song.playlistId] ?: "Unknown Playlist",
                    fileSize = size,
                    downloadedAt = lastModified
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
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                repository.resetSongToPending(songItem.song.id)
            } catch (e: Exception) {
                Log.e("DownloadsViewModel", "Failed to delete song file and reset DB", e)
            }
        }
    }
}
