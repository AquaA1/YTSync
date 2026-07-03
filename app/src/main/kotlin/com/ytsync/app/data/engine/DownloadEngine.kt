package com.ytsync.app.data.engine

/**
 * Abstraction over the underlying YouTube download tool.
 *
 * The concrete implementation is [YtdlpDownloadEngine]; swap here if the
 * library ever changes without touching any other layer.
 *
 * All functions are suspend — callers must use a coroutine (IO dispatcher).
 */
interface DownloadEngine {

    /**
     * Fetch playlist title and ordered list of video IDs without downloading audio.
     * This uses --flat-playlist so it is fast even for large playlists.
     */
    suspend fun getPlaylistMetadata(url: String): PlaylistMetadata

    /**
     * Download all tracks in a playlist to [outputDir].
     * [onProgress] receives an overall 0-100 integer progress value.
     */
    suspend fun downloadPlaylist(
        url: String,
        outputDir: String,
        limit: Int? = null,
        onProgress: (Int) -> Unit,
    )

    /**
     * Download a single video by its full YouTube URL to [outputDir].
     * Audio is extracted as MP3 with embedded metadata and thumbnail.
     */
    suspend fun downloadSong(url: String, outputDir: String)
}
