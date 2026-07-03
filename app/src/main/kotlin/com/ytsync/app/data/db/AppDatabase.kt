package com.ytsync.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ytsync.app.data.db.dao.PlaylistDao
import com.ytsync.app.data.db.dao.SongDao
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity

@Database(
    entities = [PlaylistEntity::class, SongEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
}
