package com.ytsync.app.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val fileManager: FileManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])

    val playlist: StateFlow<PlaylistEntity?> = repository.observePlaylist(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val songs: StateFlow<List<SongEntity>> = repository.getSongsForPlaylist(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun getSongFileSize(song: SongEntity): String? {
        if (song.filePath == null) return null
        val file = File(song.filePath)
        if (!file.exists()) return null
        val bytes = file.length()
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}
