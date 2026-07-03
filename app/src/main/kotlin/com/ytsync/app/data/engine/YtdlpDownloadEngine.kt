package com.ytsync.app.data.engine

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YtdlpDownloadEngine"

/**
 * Concrete [DownloadEngine] backed by youtubedl-android (yt-dlp).
 *
 * Audio options used for every download:
 *  -x                   extract audio only
 *  --audio-format mp3   transcode to MP3 via bundled FFmpeg
 *  --audio-quality 0    best available quality
 *  --embed-metadata     write track/album/artist tags
 *  --embed-thumbnail    embed cover art
 *  --no-warnings        suppress yt-dlp stderr noise
 */
@Singleton
class YtdlpDownloadEngine @Inject constructor() : DownloadEngine {

    // ── Metadata ───────────────────────────────────────────────────────────────

    override suspend fun getPlaylistMetadata(url: String): PlaylistMetadata =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Fetching metadata for: $url")
            val request = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request, UUID.randomUUID().toString())
            val json = JSONObject(response.out)

            val title = json.optString("title", "Unknown Playlist")
            val entries = json.optJSONArray("entries")
            val songIds = mutableListOf<String>()
            val songTitles = mutableListOf<String>()
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val id = entry.optString("id") ?: continue
                    val entryTitle = entry.optString("title", "Song $id")
                    if (id.isNotBlank()) {
                        songIds += id
                        songTitles += entryTitle
                    }
                }
            }

            Log.d(TAG, "Playlist '$title' — ${songIds.size} tracks")
            PlaylistMetadata(
                name = title,
                songIds = songIds,
                totalCount = songIds.size,
                songTitles = songTitles
            )
        }

    // ── Full-playlist download ─────────────────────────────────────────────────

    override suspend fun downloadPlaylist(
        url: String,
        outputDir: String,
        limit: Int?,
        onProgress: (Int) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "Downloading playlist to: $outputDir (limit: $limit)")
        val request = buildAudioRequest(url, outputDir).apply {
            if (limit != null) {
                addOption("--playlist-end", limit)
            }
        }
        YoutubeDL.getInstance().execute(request, UUID.randomUUID().toString()) { progress, _, _ ->
            onProgress(progress.toInt().coerceIn(0, 100))
        }
        Log.d(TAG, "Playlist download complete")
    }

    // ── Single-song download ───────────────────────────────────────────────────

    override suspend fun downloadSong(url: String, outputDir: String): Unit =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Downloading song: $url  →  $outputDir")
            val request = buildAudioRequest(url, outputDir).apply {
                addOption("--no-playlist") // guard: never expand to full playlist
            }
            YoutubeDL.getInstance().execute(request, UUID.randomUUID().toString()) { p, _, _ ->
                Log.v(TAG, "Song progress: ${p.toInt()}%")
            }
            Log.d(TAG, "Song download complete")
        }

    // ── Shared request builder ─────────────────────────────────────────────────

    private fun buildAudioRequest(url: String, outputDir: String): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("-o", "$outputDir/%(title)s.%(ext)s")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            addOption("--no-warnings")
            addOption("--retries", "3")
        }
}
