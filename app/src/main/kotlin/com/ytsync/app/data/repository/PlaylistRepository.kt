package com.ytsync.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ytsync.app.data.db.AppDatabase
import com.ytsync.app.data.db.dao.PlaylistDao
import com.ytsync.app.data.db.dao.SongDao
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.DownloadPhase
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.db.entity.SyncStatus
import com.ytsync.app.data.file.FileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for playlist and song data.
 *
 * Exposes cold [Flow]s for UI observation; ViewModels convert these to
 * [kotlinx.coroutines.flow.StateFlow] via [kotlinx.coroutines.flow.stateIn].
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val db: AppDatabase,
    private val fileManager: FileManager,
    @ApplicationContext private val context: Context
) {

    // ── Playlists ──────────────────────────────────────────────────────────────

    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAll()
    val allSongs: Flow<List<SongEntity>> = songDao.getAll()

    suspend fun insertPlaylist(playlist: PlaylistEntity): Long =
        playlistDao.insert(playlist)

    suspend fun updatePlaylist(playlist: PlaylistEntity) =
        playlistDao.update(playlist)

    suspend fun deletePlaylist(playlist: PlaylistEntity) =
        playlistDao.delete(playlist)

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> =
        playlistDao.observeById(id)

    suspend fun getPlaylistById(id: Long): PlaylistEntity? =
        playlistDao.getById(id)

    suspend fun getPlaylistByUrl(url: String): PlaylistEntity? =
        playlistDao.getByUrl(url)

    suspend fun getOutOfSyncPlaylists(): List<PlaylistEntity> =
        playlistDao.getOutOfSync()

    suspend fun markPlaylistSyncing(id: Long) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(p.copy(syncStatus = SyncStatus.SYNCING))
    }

    suspend fun updatePlaylistName(id: Long, name: String) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(p.copy(name = name))
    }

    suspend fun markPlaylistSynced(id: Long, name: String, songCount: Int) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(
            p.copy(
                name = name,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = System.currentTimeMillis(),
                songCount = songCount,
            )
        )
    }

    suspend fun markPlaylistError(id: Long) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(p.copy(syncStatus = SyncStatus.ERROR))
    }

    // ── Songs ──────────────────────────────────────────────────────────────────

    fun getSongsForPlaylist(playlistId: Long): Flow<List<SongEntity>> =
        songDao.getByPlaylistId(playlistId)

    suspend fun insertSong(song: SongEntity): Long = songDao.insert(song)

    suspend fun insertSongs(songs: List<SongEntity>) = songDao.insertAll(songs)

    suspend fun updateSong(song: SongEntity) = songDao.update(song)

    suspend fun deleteSong(song: SongEntity) = songDao.delete(song)

    suspend fun deleteSongsForPlaylist(playlistId: Long) =
        songDao.deleteByPlaylistId(playlistId)

    suspend fun getDownloadedCount(playlistId: Long): Int =
        songDao.getDownloadedCount(playlistId)

    suspend fun getSongByVideoId(playlistId: Long, videoId: String): SongEntity? =
        songDao.getByVideoId(playlistId, videoId)

    suspend fun getPendingSongs(playlistId: Long): List<SongEntity> =
        songDao.getPendingForPlaylist(playlistId)

    suspend fun markSongDownloaded(songId: Long, filePath: String) {
        val s = songDao.getById(songId) ?: return
        songDao.update(s.copy(
            filePath = filePath,
            downloadStatus = DownloadStatus.DOWNLOADED,
            downloadPhase = DownloadPhase.DONE,
            progress = 0,
            downloadedAt = s.downloadedAt ?: System.currentTimeMillis()
        ))
    }

    suspend fun markSongFailed(songId: Long) {
        val s = songDao.getById(songId) ?: return
        songDao.update(s.copy(
            downloadStatus = DownloadStatus.FAILED,
            downloadPhase = DownloadPhase.DONE,
            progress = 0
        ))
    }

    suspend fun resetSongToPending(songId: Long) {
        val s = songDao.getById(songId) ?: return
        songDao.update(s.copy(
            filePath = null, 
            downloadStatus = DownloadStatus.PENDING,
            downloadPhase = DownloadPhase.DONE,
            progress = 0
        ))
    }

    suspend fun updateSongDownloadPhase(songId: Long, phase: DownloadPhase, progress: Int) {
        val s = songDao.getById(songId) ?: return
        songDao.update(s.copy(
            downloadPhase = phase,
            progress = progress
        ))
    }

    /** Clears all playlist and song records from Room. */
    suspend fun clearAllData() {
        db.clearAllTables()
    }

    suspend fun relocatePlaylistFiles(
        playlist: PlaylistEntity,
        oldSaveLocationUri: String?,
        newSaveLocationUri: String?,
        onProgress: (current: Int, total: Int, title: String) -> Unit
    ): Pair<Int, Int> {
        val (oldPlaylistDir, _) = fileManager.getPlaylistDocumentDir(playlist.name, oldSaveLocationUri)
        val (newPlaylistDir, _) = fileManager.getPlaylistDocumentDir(playlist.name, newSaveLocationUri)

        // If old and new folder URIs are identical, nothing to copy/move
        if (oldPlaylistDir.uri == newPlaylistDir.uri) {
            return Pair(0, 0)
        }

        val playlistSongs = songDao.getByPlaylistId(playlist.id).first()
            .filter { it.downloadStatus == DownloadStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }

        if (playlistSongs.isEmpty()) {
            return Pair(0, 0)
        }

        var successCount = 0
        var failureCount = 0

        playlistSongs.forEachIndexed { index, song ->
            onProgress(index + 1, playlistSongs.size, song.title)
            val oldPath = song.filePath!!
            try {
                if (fileManager.fileExists(oldPath)) {
                    // Extract file name
                    val fileName = if (oldPath.startsWith("content://")) {
                        val doc = DocumentFile.fromSingleUri(context, Uri.parse(oldPath))
                        doc?.name ?: "${fileManager.sanitize(song.title)}.mp3"
                    } else {
                        java.io.File(oldPath).name
                    }

                    // Copy to temp file first because fileManager.copyFileToDocument takes a local source File
                    val tempFile = java.io.File(fileManager.ytSyncRoot, "relocate_temp.mp3")
                    if (tempFile.exists()) tempFile.delete()

                    val inputStream = if (oldPath.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(oldPath))
                    } else {
                        java.io.File(oldPath).inputStream()
                    }

                    if (inputStream != null) {
                        tempFile.outputStream().use { out ->
                            inputStream.use { inp ->
                                inp.copyTo(out)
                            }
                        }

                        // Relocate
                        val copiedDoc = fileManager.copyFileToDocument(tempFile, newPlaylistDir, fileName)
                        tempFile.delete()

                        if (copiedDoc != null && copiedDoc.exists()) {
                            // Delete old file
                            fileManager.deleteFile(oldPath)

                            val savedPath = if (copiedDoc.uri.scheme == "file") {
                                copiedDoc.uri.path ?: copiedDoc.uri.toString()
                            } else {
                                copiedDoc.uri.toString()
                            }

                            // Update DB
                            songDao.update(song.copy(filePath = savedPath))
                            successCount++
                        } else {
                            failureCount++
                        }
                    } else {
                        failureCount++
                    }
                } else {
                    failureCount++
                }
            } catch (e: Exception) {
                Log.e("PlaylistRepository", "Failed to relocate song '${song.title}'", e)
                failureCount++
            }
        }

        // Relocate metadata.json
        try {
            val oldMeta = fileManager.readMetadata(oldPlaylistDir)
            if (oldMeta != null) {
                fileManager.writeMetadata(newPlaylistDir, oldMeta)
                val oldMetaDoc = oldPlaylistDir.findFile("metadata.json")
                oldMetaDoc?.delete()
            }
        } catch (e: Exception) {
            Log.e("PlaylistRepository", "Failed to copy metadata.json during relocation", e)
        }

        return Pair(successCount, failureCount)
    }
}
