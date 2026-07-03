package com.ytsync.app.data.repository

import com.ytsync.app.data.db.dao.PlaylistDao
import com.ytsync.app.data.db.dao.SongDao
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.db.entity.SyncStatus
import kotlinx.coroutines.flow.Flow
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
) {

    // ── Playlists ──────────────────────────────────────────────────────────────

    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAll()

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

    suspend fun markPlaylistSynced(id: Long, songCount: Int) {
        val p = playlistDao.getById(id) ?: return
        playlistDao.update(
            p.copy(
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
        songDao.update(s.copy(filePath = filePath, downloadStatus = DownloadStatus.DOWNLOADED))
    }

    suspend fun markSongFailed(songId: Long) {
        val s = songDao.getById(songId) ?: return
        songDao.update(s.copy(downloadStatus = DownloadStatus.FAILED))
    }
}
