package com.ytsync.app.data.file

import android.content.Context
import android.os.Environment
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FileManager"

/**
 * Manages the on-disk layout for YTSync:
 *
 *   <externalFilesDir>/Music/YTSync/<PlaylistName>/
 *       ├── metadata.json
 *       ├── Track 1.mp3
 *       └── Track 2.mp3
 *
 * Using [Context.getExternalFilesDir] avoids needing WRITE_EXTERNAL_STORAGE
 * on API 29+.  Files are visible via MTP/file-manager at:
 *   /sdcard/Android/data/com.ytsync.app/files/Music/YTSync/
 *
 * Stage 4 will add an option to move to the public Music/ directory
 * with MANAGE_EXTERNAL_STORAGE.
 */
@Singleton
class FileManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Root  →  …/files/Music/YTSync/ */
    private val ytSyncRoot: File by lazy {
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            "YTSync",
        ).also { it.mkdirs() }
    }

    /**
     * Returns (and creates) the directory for [playlistName].
     * The name is sanitised to be filesystem-safe.
     */
    fun getPlaylistDir(playlistName: String): File {
        val safe = sanitize(playlistName)
        return File(ytSyncRoot, safe).also { it.mkdirs() }
    }

    /** Returns the absolute string path to pass to yt-dlp as the output directory. */
    fun getPlaylistDirPath(playlistName: String): String =
        getPlaylistDir(playlistName).absolutePath

    // ── metadata.json ──────────────────────────────────────────────────────────

    fun writeMetadata(playlistDir: File, metadata: PlaylistFolderMetadata) {
        try {
            File(playlistDir, "metadata.json").writeText(json.encodeToString(metadata))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata", e)
        }
    }

    fun readMetadata(playlistDir: File): PlaylistFolderMetadata? {
        val file = File(playlistDir, "metadata.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read metadata", e)
            null
        }
    }

    /** List all MP3 files in a playlist directory. */
    fun getMp3Files(playlistDir: File): List<File> =
        playlistDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Returns true if an MP3 file for [title] already exists in [playlistDir]. */
    fun songExists(playlistDir: File, title: String): Boolean =
        getSongFile(playlistDir, title).exists()

    /** Returns the MP3 file instance for a given track title inside a playlist directory. */
    fun getSongFile(playlistDir: File, title: String): File =
        File(playlistDir, "${sanitize(title)}.mp3")

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    /** Returns all playlist directories currently existing on disk. */
    fun getExistingPlaylistDirs(): List<File> {
        return ytSyncRoot.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }

    /** Clears all playlist and song folders on disk. */
    fun clearAllDownloads() {
        try {
            if (ytSyncRoot.exists()) {
                ytSyncRoot.deleteRecursively()
            }
            ytSyncRoot.mkdirs()
            Log.i(TAG, "Successfully cleared all downloaded files from disk.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all downloads", e)
        }
    }
}
