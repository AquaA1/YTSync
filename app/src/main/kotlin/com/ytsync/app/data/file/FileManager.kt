package com.ytsync.app.data.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ytsync.app.data.settings.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FileManager"

/**
 * Manages the on-disk layout for YTSync:
 * Supports standard local files and SAF tree URIs.
 */
@Singleton
class FileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Root  →  …/files/Music/YTSync/ */
    val ytSyncRoot: File by lazy {
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

    // ── SAF Uri Validity and Resolution ────────────────────────────────────────

    fun isUriValid(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val hasPerm = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri
            }
            if (!hasPerm) return false
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc != null && doc.exists() && doc.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the target directory for a playlist.
     * Returns a pair of: (DocumentFile directory, Boolean whether fallback occurred)
     */
    fun getPlaylistDocumentDir(playlistName: String, playlistSaveLocationUri: String?): Pair<DocumentFile, Boolean> {
        var fallbackOccurred = false

        // 1. Check per-playlist override
        if (!playlistSaveLocationUri.isNullOrBlank()) {
            if (isUriValid(playlistSaveLocationUri)) {
                val uri = Uri.parse(playlistSaveLocationUri)
                val doc = DocumentFile.fromTreeUri(context, uri)
                if (doc != null && doc.exists()) {
                    return Pair(doc, false)
                }
            }
            // If URI is set but invalid, it's a fallback
            fallbackOccurred = true
        }

        // 2. Check global default
        val globalDefaultUri = settingsManager.defaultSaveLocationUri
        if (!globalDefaultUri.isNullOrBlank()) {
            if (isUriValid(globalDefaultUri)) {
                val uri = Uri.parse(globalDefaultUri)
                val doc = DocumentFile.fromTreeUri(context, uri)
                if (doc != null && doc.exists()) {
                    val safeName = sanitize(playlistName)
                    val subDir = doc.findFile(safeName) ?: doc.createDirectory(safeName)
                    if (subDir != null) {
                        return Pair(subDir, fallbackOccurred)
                    }
                }
            }
            fallbackOccurred = true
        }

        // 3. Fall back to app default sandbox directory
        val localDir = getPlaylistDir(playlistName)
        return Pair(DocumentFile.fromFile(localDir), fallbackOccurred)
    }

    // ── metadata.json ──────────────────────────────────────────────────────────

    fun writeMetadata(playlistDir: File, metadata: PlaylistFolderMetadata) {
        try {
            File(playlistDir, "metadata.json").writeText(json.encodeToString(metadata))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata to File", e)
        }
    }

    fun writeMetadata(playlistDir: DocumentFile, metadata: PlaylistFolderMetadata) {
        try {
            val fileDoc = playlistDir.findFile("metadata.json") ?: playlistDir.createFile("application/json", "metadata.json")
            if (fileDoc != null) {
                context.contentResolver.openOutputStream(fileDoc.uri)?.use { out ->
                    out.write(json.encodeToString(metadata).toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata to DocumentFile", e)
        }
    }

    fun readMetadata(playlistDir: File): PlaylistFolderMetadata? {
        val file = File(playlistDir, "metadata.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read metadata from File", e)
            null
        }
    }

    fun readMetadata(playlistDir: DocumentFile): PlaylistFolderMetadata? {
        val fileDoc = playlistDir.findFile("metadata.json") ?: return null
        return try {
            context.contentResolver.openInputStream(fileDoc.uri)?.use { inputStream ->
                json.decodeFromString(inputStream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read metadata from DocumentFile", e)
            null
        }
    }

    // ── Document File Listing and Copying Helpers ──────────────────────────────

    /** List all MP3 files in a local directory. */
    fun getMp3Files(playlistDir: File): List<File> =
        playlistDir.listFiles { f -> f.extension.equals("mp3", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** List all MP3 files in a DocumentFile directory. */
    fun getMp3Documents(playlistDir: DocumentFile): List<DocumentFile> {
        return playlistDir.listFiles()
            .filter { doc -> doc.isFile && doc.name?.endsWith(".mp3", ignoreCase = true) == true }
            .sortedBy { doc -> doc.name ?: "" }
    }

    fun songExists(playlistDir: DocumentFile, title: String): Boolean {
        val name = "${sanitize(title)}.mp3"
        return playlistDir.findFile(name) != null
    }

    fun getSongFile(playlistDir: File, title: String): File =
        File(playlistDir, "${sanitize(title)}.mp3")

    fun copyFileToDocument(srcFile: File, destFolder: DocumentFile, destFileName: String): DocumentFile? {
        try {
            val fileDoc = destFolder.findFile(destFileName) ?: destFolder.createFile("audio/mpeg", destFileName)
            if (fileDoc != null) {
                context.contentResolver.openOutputStream(fileDoc.uri)?.use { out ->
                    srcFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                return fileDoc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file to document", e)
        }
        return null
    }

    // ── Universal File Helper Methods using String path/URI ─────────────────────

    fun getFileLength(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists()) doc.length() else 0L
            } catch (e: Exception) {
                0L
            }
        } else {
            val file = File(path)
            return if (file.exists()) file.length() else 0L
        }
    }

    fun getFileLastModified(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists()) doc.lastModified() else 0L
            } catch (e: Exception) {
                0L
            }
        } else {
            val file = File(path)
            return if (file.exists()) file.lastModified() else 0L
        }
    }

    fun fileExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc != null && doc.exists()
            } catch (e: Exception) {
                false
            }
        } else {
            return File(path).exists()
        }
    }

    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc != null && doc.exists() && doc.delete()
            } catch (e: Exception) {
                false
            }
        } else {
            val file = File(path)
            return if (file.exists()) file.delete() else false
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    /** Returns all playlist directories currently existing on disk in default storage. */
    fun getExistingPlaylistDirs(): List<File> {
        return ytSyncRoot.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }

    /** Clears all playlist and song folders on disk in default storage. */
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
