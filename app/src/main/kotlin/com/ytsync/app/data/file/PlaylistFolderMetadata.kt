package com.ytsync.app.data.file

import kotlinx.serialization.Serializable

/**
 * Contents of the metadata.json file written alongside downloaded MP3s
 * in each playlist folder.  Persisted as pretty-printed JSON.
 */
@Serializable
data class PlaylistFolderMetadata(
    /** Original YouTube Music playlist URL. */
    val url: String,

    /** Playlist display name (used as the folder name). */
    val name: String,

    /** Video IDs of tracks that have been fully downloaded. */
    val downloadedIds: List<String>,

    /** Total track count as of the last metadata fetch. */
    val totalCount: Int,

    /** Unix epoch millis of the last successful sync. */
    val lastSyncTimestamp: Long,
)
