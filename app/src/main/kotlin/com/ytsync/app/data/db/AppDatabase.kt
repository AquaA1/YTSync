package com.ytsync.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ytsync.app.data.db.dao.PlaylistDao
import com.ytsync.app.data.db.dao.SongDao
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity

@Database(
    entities = [PlaylistEntity::class, SongEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao

    companion object {
        /** Adds the downloaded_at column (nullable INTEGER) to songs. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE songs ADD COLUMN downloaded_at INTEGER DEFAULT NULL"
                )
            }
        }

        /** Adds the save_location_uri column (nullable TEXT) to playlists. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE playlists ADD COLUMN save_location_uri TEXT DEFAULT NULL"
                )
            }
        }
    }
}
