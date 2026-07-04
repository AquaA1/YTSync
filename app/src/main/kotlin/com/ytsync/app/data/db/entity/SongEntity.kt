package com.ytsync.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("playlist_id"), Index(value = ["playlist_id", "video_id"], unique = true)],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    /** YouTube video ID (e.g. "dQw4w9WgXcQ"). */
    @ColumnInfo(name = "video_id")
    val videoId: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Absolute path to the downloaded MP3; null until download completes. */
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "download_status")
    val downloadStatus: DownloadStatus = DownloadStatus.PENDING,

    @ColumnInfo(name = "download_phase")
    val downloadPhase: DownloadPhase = DownloadPhase.DONE,

    @ColumnInfo(name = "progress")
    val progress: Int = 0,
)
