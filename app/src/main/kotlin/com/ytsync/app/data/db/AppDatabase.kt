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
    version = 5,
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

        /** Adds the exclude_from_sync column (non-null INTEGER default 0) to songs. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE songs ADD COLUMN exclude_from_sync INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
