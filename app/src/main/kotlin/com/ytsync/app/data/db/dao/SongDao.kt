package com.ytsync.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ytsync.app.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: SongEntity): Long

    /** Bulk insert for syncing a full playlist's track list. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Delete
    suspend fun delete(song: SongEntity)

    @Query("SELECT * FROM songs")
    fun getAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY title ASC")
    fun getByPlaylistId(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs WHERE playlist_id = :playlistId AND download_status = 'DOWNLOADED'")
    suspend fun getDownloadedCount(playlistId: Long): Int

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId AND video_id = :videoId LIMIT 1")
    suspend fun getByVideoId(playlistId: Long, videoId: String): SongEntity?

    @Query("DELETE FROM songs WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylistId(playlistId: Long)

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId AND download_status = 'PENDING'")
    suspend fun getPendingForPlaylist(playlistId: Long): List<SongEntity>
}
