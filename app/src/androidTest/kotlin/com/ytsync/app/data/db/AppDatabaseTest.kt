package com.ytsync.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ytsync.app.data.db.dao.PlaylistDao
import com.ytsync.app.data.db.dao.SongDao
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SongEntity
import com.ytsync.app.data.db.entity.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var songDao: SongDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        songDao = db.songDao()
    }

    @After
    fun tearDown() = db.close()

    // ── Playlist tests ─────────────────────────────────────────────────────────

    @Test
    fun insertPlaylist_thenReadBack() = runBlocking {
        val id = playlistDao.insert(
            PlaylistEntity(
                url = "https://music.youtube.com/playlist?list=PLtest",
                name = "Test Playlist",
                syncStatus = SyncStatus.OUT_OF_SYNC,
            )
        )
        val loaded = playlistDao.getById(id)

        assertNotNull(loaded)
        assertEquals("Test Playlist", loaded?.name)
        assertEquals(SyncStatus.OUT_OF_SYNC, loaded?.syncStatus)
        assertNull(loaded?.lastSyncedAt)
    }

    @Test
    fun updatePlaylist_syncStatus() = runBlocking {
        val id = playlistDao.insert(
            PlaylistEntity(url = "url", name = "My Mix", syncStatus = SyncStatus.OUT_OF_SYNC)
        )
        val original = playlistDao.getById(id)!!
        playlistDao.update(original.copy(syncStatus = SyncStatus.SYNCED, lastSyncedAt = 1_000L, songCount = 5))

        val updated = playlistDao.getById(id)!!
        assertEquals(SyncStatus.SYNCED, updated.syncStatus)
        assertEquals(1_000L, updated.lastSyncedAt)
        assertEquals(5, updated.songCount)
    }

    @Test
    fun getAll_flowEmitsInsertedPlaylists() = runBlocking {
        playlistDao.insert(PlaylistEntity(url = "u1", name = "Alpha"))
        playlistDao.insert(PlaylistEntity(url = "u2", name = "Beta"))

        val all = playlistDao.getAll().first()
        assertEquals(2, all.size)
        // Ordered alphabetically
        assertEquals("Alpha", all[0].name)
        assertEquals("Beta", all[1].name)
    }

    @Test
    fun deletePlaylist_cascadesToSongs() = runBlocking {
        val plId = playlistDao.insert(PlaylistEntity(url = "u", name = "P"))
        songDao.insert(SongEntity(playlistId = plId, videoId = "v1", title = "Song 1"))
        songDao.insert(SongEntity(playlistId = plId, videoId = "v2", title = "Song 2"))

        val playlist = playlistDao.getById(plId)!!
        playlistDao.delete(playlist)

        // FK CASCADE — songs should be gone
        val songs = songDao.getByPlaylistId(plId).first()
        assertEquals(0, songs.size)
    }

    // ── Song tests ─────────────────────────────────────────────────────────────

    @Test
    fun insertSong_thenReadBack() = runBlocking {
        val plId = playlistDao.insert(PlaylistEntity(url = "u", name = "P"))
        val songId = songDao.insert(
            SongEntity(playlistId = plId, videoId = "abc123", title = "Never Gonna Give You Up")
        )
        val song = songDao.getById(songId)

        assertNotNull(song)
        assertEquals("abc123", song?.videoId)
        assertEquals(DownloadStatus.PENDING, song?.downloadStatus)
    }

    @Test
    fun getDownloadedCount_countsCorrectly() = runBlocking {
        val plId = playlistDao.insert(PlaylistEntity(url = "u", name = "P"))
        songDao.insert(SongEntity(playlistId = plId, videoId = "v1", title = "T1",
            downloadStatus = DownloadStatus.DOWNLOADED, filePath = "/music/t1.mp3"))
        songDao.insert(SongEntity(playlistId = plId, videoId = "v2", title = "T2",
            downloadStatus = DownloadStatus.PENDING))
        songDao.insert(SongEntity(playlistId = plId, videoId = "v3", title = "T3",
            downloadStatus = DownloadStatus.DOWNLOADED, filePath = "/music/t3.mp3"))

        assertEquals(2, songDao.getDownloadedCount(plId))
    }
}
