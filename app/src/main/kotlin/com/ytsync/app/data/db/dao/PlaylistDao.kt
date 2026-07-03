package com.ytsync.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ytsync.app.data.db.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    /** Hot observable list of all playlists, ordered alphabetically. */
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAll(): Flow<List<PlaylistEntity>>

    /** One-shot read — used by WorkManager sync tasks. */
    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    /** Hot observable for a single playlist — used by detail screen. */
    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE sync_status = 'OUT_OF_SYNC' OR sync_status = 'ERROR'")
    suspend fun getOutOfSync(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): PlaylistEntity?
}
