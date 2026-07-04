package com.ytsync.app.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val fileManager: FileManager,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
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

    val folderDisplayName: StateFlow<String> = playlist.map { p ->
        getFolderDisplayName(p?.saveLocationUri)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "App default location"
    )

    private val _relocationState = MutableStateFlow<RelocationState>(RelocationState.Idle)
    val relocationState: StateFlow<RelocationState> = _relocationState.asStateFlow()

    private fun getFolderDisplayName(uriString: String?): String {
        if (uriString.isNullOrBlank()) return "App default location"
        return try {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: "Custom Folder"
        } catch (e: Exception) {
            "Custom Folder"
        }
    }

    fun onNewLocationSelected(uriString: String) {
        _relocationState.value = RelocationState.ConfirmDialog(uriString)
    }

    fun dismissRelocation() {
        _relocationState.value = RelocationState.Idle
    }

    fun applyNewLocationKeepFiles(newUri: String) {
        viewModelScope.launch {
            val p = playlist.value ?: return@launch
            val targetUri = if (newUri.isEmpty()) null else newUri
            repository.updatePlaylist(p.copy(saveLocationUri = targetUri))
            _relocationState.value = RelocationState.Idle
        }
    }

    fun applyNewLocationMoveFiles(newUri: String) {
        viewModelScope.launch {
            val p = playlist.value ?: return@launch
            val oldUri = p.saveLocationUri
            val targetUri = if (newUri.isEmpty()) null else newUri
            
            // Update playlist saveLocationUri in DB first
            val updatedPlaylist = p.copy(saveLocationUri = targetUri)
            repository.updatePlaylist(updatedPlaylist)

            _relocationState.value = RelocationState.Progress(0, 0, "Initializing...")

            try {
                val songsToMove = repository.getSongsForPlaylist(p.id).first()
                    .filter { it.downloadStatus == DownloadStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }

                if (songsToMove.isEmpty()) {
                    _relocationState.value = RelocationState.Summary(0, 0)
                    return@launch
                }

                val (succ, fail) = repository.relocatePlaylistFiles(
                    playlist = updatedPlaylist,
                    oldSaveLocationUri = oldUri,
                    newSaveLocationUri = targetUri
                ) { current, total, title ->
                    _relocationState.value = RelocationState.Progress(
                        current = current,
                        total = total,
                        currentTitle = title
                    )
                }

                _relocationState.value = RelocationState.Summary(succ, fail)
            } catch (e: Exception) {
                Log.e("PlaylistDetailViewModel", "Failed to relocate playlist files", e)
                _relocationState.value = RelocationState.Summary(0, 0)
            }
        }
    }

    fun resetToDefaultSelected() {
        // Show confirm dialog to reset to default save location (i.e. passing empty/null URI)
        _relocationState.value = RelocationState.ConfirmDialog("")
    }

    fun getSongFileSize(song: SongEntity): String? {
        val path = song.filePath ?: return null
        if (!fileManager.fileExists(path)) return null
        val bytes = fileManager.getFileLength(path)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }
}
