package com.ytsync.app.data.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.DownloadPhase
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.engine.DownloadEngine
import com.ytsync.app.data.file.FileManager
import com.ytsync.app.data.file.PlaylistFolderMetadata
import com.ytsync.app.data.repository.PlaylistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SyncWorker"

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PlaylistRepository,
    private val engine: DownloadEngine,
    private val fileManager: FileManager,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return notificationHelper.createForegroundInfo("Library Sync", "Preparing sync...")
    }

    override suspend fun doWork(): Result {
        val singlePlaylistId = inputData.getLong("playlistId", -1L)
        Log.i("SyncDiagnostics", "doWork start. singlePlaylistId = $singlePlaylistId")
        Log.i(TAG, "Starting sync worker. singlePlaylistId = $singlePlaylistId")

        try {
            setForeground(notificationHelper.createForegroundInfo("Library Sync", "Initializing..."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set foreground status at startup", e)
        }

        var hasErrors = false

        try {
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

            for (playlist in playlistsToSync) {
                Log.i(TAG, "Syncing playlist: ${playlist.name} (${playlist.url})")
                try {
                    try {
                        setForeground(notificationHelper.createForegroundInfo(playlist.name, "Fetching playlist metadata..."))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update foreground status", e)
                    }
                    repository.markPlaylistSyncing(playlist.id)

                    // 1. Fetch metadata
                    Log.i("SyncDiagnostics", "getPlaylistMetadata start for URL: ${playlist.url}")
                    val meta = engine.getPlaylistMetadata(playlist.url)
                    Log.i("SyncDiagnostics", "getPlaylistMetadata end. Name = ${meta.name}, count = ${meta.totalCount}")

                    // Update playlist name in database immediately
                    repository.updatePlaylistName(playlist.id, meta.name)

                    // Resolve target playlist directory (supports SAF tree and local file system)
                    val (playlistDir, fallbackOccurred) = fileManager.getPlaylistDocumentDir(meta.name, playlist.saveLocationUri)
                    if (fallbackOccurred) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                "Save folder is inaccessible. Falling back to default storage.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    // 2. Insert/update songs in Room
                    val songEntities = meta.songIds.mapIndexed { index, videoId ->
                        val title = meta.songTitles.getOrElse(index) { "Song $videoId" }
                        val existing = repository.getSongByVideoId(playlist.id, videoId)
                        if (existing != null) {
                            Log.i("SyncDiagnostics", "Checking DB: Song '${title}' already exists with status: ${existing.downloadStatus}")
                        } else {
                            Log.i("SyncDiagnostics", "Checking DB: Song '${title}' is brand new (to be inserted)")
                        }
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
                    val mp3s = fileManager.getMp3Documents(playlistDir)
                    val allSongsInDb = repository.getSongsForPlaylist(playlist.id).first()

                    Log.i("SyncDiagnostics", "Identifying missing songs: Scanning target directory and comparing with ${allSongsInDb.size} DB tracks")
                    val missingSongs = allSongsInDb.filter { song ->
                        if (song.excludeFromSync) {
                            Log.i("SyncDiagnostics", "Song skip check: '${song.title}' is excluded from sync, skipping.")
                            false
                        } else {
                            val exists = fileManager.fileExists(song.filePath)
                            if (exists) {
                                Log.i("SyncDiagnostics", "Song skip check: File exists on disk for '${song.title}'")
                                false
                            } else {
                            val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                            val matched = mp3s.find { mp3 ->
                                val mp3Alphanumeric = (mp3.name ?: "").substringBeforeLast(".").filter { it.isLetterOrDigit() }.lowercase()
                                mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                            }
                            if (matched != null) {
                                Log.i("SyncDiagnostics", "Song skip check: Matched file exists on disk for '${song.title}' -> '${matched.name}'")
                                val matchedPath = if (matched.uri.scheme == "file") matched.uri.path ?: matched.uri.toString() else matched.uri.toString()
                                runBlocking {
                                    repository.markSongDownloaded(song.id, matchedPath)
                                }
                                false
                            } else {
                                Log.i("SyncDiagnostics", "Song skip check: Missing file for '${song.title}' (marked as PENDING/needs download)")
                                true
                            }
                        }
                    }
                }

                    // Temporary directory for downloads to feed into native yt-dlp
                    val tempDownloadDir = File(appContext.cacheDir, "ytsync_temp").apply {
                        deleteRecursively()
                        mkdirs()
                    }

                    // 4. Download missing files sequentially, updating database in real time
                    if (missingSongs.isNotEmpty()) {
                        notificationHelper.showSyncNotification(playlist.name, missingSongs.size)
                        Log.i("SyncDiagnostics", "Starting download loop for ${missingSongs.size} missing songs")

                        missingSongs.forEachIndexed { idx, song ->
                            val videoUrl = "https://www.youtube.com/watch?v=${song.videoId}"
                            Log.i("SyncDiagnostics", "downloadSong start [${idx + 1}/${missingSongs.size}]: '${song.title}' (${videoUrl})")
                            Log.i(TAG, "Downloading track ${idx + 1}/${missingSongs.size}: ${song.title}")

                            // Clear temp download directory for this single song download to avoid confusion
                            tempDownloadDir.deleteRecursively()
                            tempDownloadDir.mkdirs()

                            try {
                                val trackProgressLabel = "Track ${idx + 1} of ${missingSongs.size}"
                                notificationHelper.updateForegroundNotification(
                                    playlist.name,
                                    "$trackProgressLabel: Preparing '${song.title}'"
                                )

                                engine.downloadSong(videoUrl, tempDownloadDir.absolutePath) { phase, progress ->
                                    runBlocking {
                                        repository.updateSongDownloadPhase(song.id, phase, progress)
                                    }
                                    val phaseLabel = when (phase) {
                                        DownloadPhase.PREPARING -> "Preparing"
                                        DownloadPhase.DOWNLOADING -> "Downloading ($progress%)"
                                        DownloadPhase.CONVERTING -> "Converting"
                                        DownloadPhase.DONE -> "Done"
                                    }
                                    notificationHelper.updateForegroundNotification(
                                        playlist.name,
                                        "$trackProgressLabel: $phaseLabel '${song.title}'"
                                    )
                                }
                                Log.i("SyncDiagnostics", "downloadSong end successfully [${idx + 1}/${missingSongs.size}]: '${song.title}'")

                                // Find the downloaded file in temp directory
                                val tempFiles = tempDownloadDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
                                val tempFile = tempFiles?.firstOrNull()

                                if (tempFile != null && tempFile.exists()) {
                                    Log.i("SyncDiagnostics", "Post-download: Found temp file at ${tempFile.absolutePath}. Moving to target SAF/local dir.")
                                    
                                    val targetFileName = tempFile.name
                                    val copiedDoc = fileManager.copyFileToDocument(tempFile, playlistDir, targetFileName)
                                    
                                    if (copiedDoc != null && copiedDoc.exists()) {
                                        tempFile.delete()
                                        val savedPath = if (copiedDoc.uri.scheme == "file") {
                                            copiedDoc.uri.path ?: copiedDoc.uri.toString()
                                        } else {
                                            copiedDoc.uri.toString()
                                        }
                                        Log.i("SyncDiagnostics", "Post-download relocation success: savedPath = $savedPath")
                                        repository.markSongDownloaded(song.id, savedPath)
                                    } else {
                                        Log.e(TAG, "Failed to copy downloaded file to target directory: ${song.title}")
                                        repository.markSongFailed(song.id)
                                    }
                                } else {
                                    Log.w(TAG, "Download finished but temp file not found for: ${song.title}")
                                    repository.markSongFailed(song.id)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed downloading song: ${song.title}", e)
                                Log.i("SyncDiagnostics", "Post-download: EXCEPTION downloading '${song.title}': ${e.message}")
                                repository.markSongFailed(song.id)
                            }
                        }
                    } else {
                        Log.i("SyncDiagnostics", "Download loop skipped: No missing songs found")
                    }

                    // 5. Final reconciliation of all songs
                    Log.i("SyncDiagnostics", "Step 5: Final reconciliation start")
                    val finalMp3s = fileManager.getMp3Documents(playlistDir)
                    val finalSongsInDb = repository.getSongsForPlaylist(playlist.id).first()
                    finalSongsInDb.forEach { song ->
                        if (song.excludeFromSync) {
                            Log.i("SyncDiagnostics", "Reconciliation: '${song.title}' is excluded from sync, skipping.")
                            return@forEach
                        }
                        val exists = fileManager.fileExists(song.filePath)
                        if (exists) {
                            Log.i("SyncDiagnostics", "Reconciliation: Song exists on disk, marking DOWNLOADED: '${song.title}'")
                            val currentPath = song.filePath!!
                            repository.markSongDownloaded(song.id, currentPath)
                        } else {
                            val songAlphanumeric = song.title.filter { it.isLetterOrDigit() }.lowercase()
                            val matched = finalMp3s.find { mp3 ->
                                val mp3Alphanumeric = (mp3.name ?: "").substringBeforeLast(".").filter { it.isLetterOrDigit() }.lowercase()
                                mp3Alphanumeric.contains(songAlphanumeric) || songAlphanumeric.contains(mp3Alphanumeric)
                            }
                            if (matched != null) {
                                Log.i("SyncDiagnostics", "Reconciliation: Fuzzy match exists on disk, marking DOWNLOADED: '${song.title}' -> '${matched.name}'")
                                val matchedPath = if (matched.uri.scheme == "file") matched.uri.path ?: matched.uri.toString() else matched.uri.toString()
                                repository.markSongDownloaded(song.id, matchedPath)
                            } else if (song.downloadStatus != DownloadStatus.DOWNLOADED) {
                                Log.i("SyncDiagnostics", "Reconciliation: File missing, marking FAILED: '${song.title}'")
                                repository.markSongFailed(song.id)
                            } else {
                                Log.i("SyncDiagnostics", "Reconciliation: Song '${song.title}' is marked DOWNLOADED but file is missing from disk! Leaving state as is.")
                            }
                        }
                    }
                    Log.i("SyncDiagnostics", "Step 5: Final reconciliation end")

                    // 6. Write metadata.json
                    Log.i("SyncDiagnostics", "Step 6: Writing metadata.json start")
                    val updatedSongs = repository.getSongsForPlaylist(playlist.id).first()
                    fileManager.writeMetadata(
                        playlistDir,
                        PlaylistFolderMetadata(
                            url = playlist.url,
                            name = meta.name,
                            downloadedIds = updatedSongs.filter {
                                it.downloadStatus == DownloadStatus.DOWNLOADED && fileManager.fileExists(it.filePath)
                            }.map { it.videoId },
                            totalCount = meta.totalCount,
                            lastSyncTimestamp = System.currentTimeMillis()
                        )
                    )
                    Log.i("SyncDiagnostics", "Step 6: Writing metadata.json end")

                    // 7. Complete sync
                    Log.i("SyncDiagnostics", "Step 7: Complete sync start")
                    repository.markPlaylistSynced(playlist.id, meta.name, meta.totalCount)
                    Log.i("SyncDiagnostics", "Step 7: Complete sync end")
                    Log.i(TAG, "Finished syncing playlist: ${meta.name}")
                    notificationHelper.cancelSyncNotification()

                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing playlist: ${playlist.name}", e)
                    Log.i("SyncDiagnostics", "Sync loop EXCEPTION: ${e.message}")
                    repository.markPlaylistError(playlist.id)
                    hasErrors = true
                }
            }
        } finally {
            notificationHelper.cancelForegroundNotification()
        }

        // Clean up temp dir
        try {
            val tempDownloadDir = File(appContext.cacheDir, "ytsync_temp")
            if (tempDownloadDir.exists()) {
                tempDownloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed cleaning up temp download directory", e)
        }

        Log.i("SyncDiagnostics", "doWork end. hasErrors = $hasErrors")
        return Result.success()
    }
}
