package com.ytsync.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.engine.DownloadEngine
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.file.PlaylistFolderMetadata
import com.ytsync.app.data.repository.PlaylistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File

private const val TAG = "SyncWorker"

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PlaylistRepository,
    private val engine: DownloadEngine,
    private val fileManager: FileManager,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val singlePlaylistId = inputData.getLong("playlistId", -1L)
        Log.i(TAG, "Starting sync worker. singlePlaylistId = $singlePlaylistId")

        val playlistsToSync = if (singlePlaylistId != -1L) {
            val playlist = repository.getPlaylistById(singlePlaylistId)
            if (playlist == null) {
                Log.e(TAG, "Playlist not found in DB: $singlePlaylistId")
                return Result.failure()
            }
            listOf(playlist)
        } else {
            repository.allPlaylists.first()
        }

        if (playlistsToSync.isEmpty()) {
            Log.i(TAG, "No playlists found to sync.")
            return Result.success()
        }

        var hasErrors = false

        for (playlist in playlistsToSync) {
            Log.i(TAG, "Syncing playlist: ${playlist.name} (${playlist.url})")
            try {
                repository.markPlaylistSyncing(playlist.id)

                val playlistDir = fileManager.getPlaylistDir(playlist.name)

                // 1. Fetch metadata
                val meta = engine.getPlaylistMetadata(playlist.url)

                // 2. Insert/update songs in Room
                val songEntities = meta.songIds.mapIndexed { index, videoId ->
                    val title = meta.songTitles.getOrElse(index) { "Song $videoId" }
                    val existing = repository.getSongByVideoId(playlist.id, videoId)
                    existing ?: SongEntity(
                        playlistId = playlist.id,
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

                // 3. Scan and identify missing songs
                val mp3s = fileManager.getMp3Files(playlistDir)
                val allSongsInDb = repository.getSongsForPlaylist(playlist.id).first()

                val missingSongs = allSongsInDb.filter { song ->
                    val songFile = fileManager.getSongFile(playlistDir, song.title)
                    if (songFile.exists()) {
                        false
                    } else {
                        val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                        val matched = mp3s.find { mp3 ->
                            val mp3Alphanumeric = mp3.nameWithoutExtension.filter { it.isLetterOrDigit() }.lowercase()
                            mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                        }
                        matched == null
                    }
                }

                // 4. Download missing files sequentially, updating database in real time
                if (missingSongs.isNotEmpty()) {
                    notificationHelper.showSyncNotification(playlist.name, missingSongs.size)
                    
                    missingSongs.forEachIndexed { idx, song ->
                        val videoUrl = "https://www.youtube.com/watch?v=${song.videoId}"
                        Log.i(TAG, "Downloading track ${idx + 1}/${missingSongs.size}: ${song.title}")
                        
                        try {
                            engine.downloadSong(videoUrl, playlistDir.absolutePath)
                            
                            // Immediately scan directory to find the downloaded file for this song
                            val currentMp3s = fileManager.getMp3Files(playlistDir)
                            val songFile = fileManager.getSongFile(playlistDir, song.title)
                            if (songFile.exists()) {
                                repository.markSongDownloaded(song.id, songFile.absolutePath)
                            } else {
                                val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                                val matched = currentMp3s.find { mp3 ->
                                    val mp3Alphanumeric = mp3.nameWithoutExtension.filter { it.isLetterOrDigit() }.lowercase()
                                    mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                                }
                                if (matched != null) {
                                    repository.markSongDownloaded(song.id, matched.absolutePath)
                                } else {
                                    Log.w(TAG, "Download finished but file not found on disk for: ${song.title}")
                                    repository.markSongFailed(song.id)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed downloading song: ${song.title}", e)
                            repository.markSongFailed(song.id)
                        }
                    }
                }

                // 5. Final reconciliation of all songs
                val finalMp3s = fileManager.getMp3Files(playlistDir)
                val finalSongsInDb = repository.getSongsForPlaylist(playlist.id).first()
                finalSongsInDb.forEach { song ->
                    val songFile = fileManager.getSongFile(playlistDir, song.title)
                    if (songFile.exists()) {
                        repository.markSongDownloaded(song.id, songFile.absolutePath)
                    } else {
                        val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                        val matched = finalMp3s.find { mp3 ->
                            val mp3Alphanumeric = mp3.nameWithoutExtension.filter { it.isLetterOrDigit() }.lowercase()
                            mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                        }
                        if (matched != null) {
                            repository.markSongDownloaded(song.id, matched.absolutePath)
                        } else if (song.downloadStatus != DownloadStatus.DOWNLOADED) {
                            repository.markSongFailed(song.id)
                        }
                    }
                }

                // 6. Write metadata.json
                val updatedSongs = repository.getSongsForPlaylist(playlist.id).first()
                fileManager.writeMetadata(
                    playlistDir,
                    PlaylistFolderMetadata(
                        url = playlist.url,
                        name = meta.name,
                        downloadedIds = updatedSongs.filter {
                            it.downloadStatus == DownloadStatus.DOWNLOADED && it.filePath != null && File(it.filePath).exists()
                        }.map { it.videoId },
                        totalCount = meta.totalCount,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                )

                // 7. Complete sync
                repository.markPlaylistSynced(playlist.id, meta.totalCount)
                Log.i(TAG, "Finished syncing playlist: ${playlist.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed syncing playlist: ${playlist.name}", e)
                repository.markPlaylistError(playlist.id)
                hasErrors = true
            }
        }

        return if (hasErrors) Result.success() else Result.success()
    }
}
