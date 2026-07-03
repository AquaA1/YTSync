package com.ytsync.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Stored as String via Converters; default = out of sync on first add. */
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.OUT_OF_SYNC,

    /** Unix epoch millis; null until the first successful sync. */
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "song_count")
    val songCount: Int = 0,
)
