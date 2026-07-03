package com.ytsync.app.data.engine

/**
 * Metadata about a YouTube Music playlist, fetched without downloading any audio.
 *
 * @param name       Human-readable playlist title.
 * @param songIds    Ordered list of YouTube video IDs in the playlist.
 * @param totalCount Total number of tracks (same as songIds.size once resolved).
 */
data class PlaylistMetadata(
    val name: String,
    val songIds: List<String>,
    val totalCount: Int,
    val songTitles: List<String> = emptyList(),
)
