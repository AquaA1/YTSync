package com.ytsync.app.data.db.entity

enum class DownloadPhase {
    PREPARING,   // Resolving URL, fetching formats, decrypting client signatures (~83% of time)
    DOWNLOADING, // Transferring raw audio file stream (~4% of time)
    CONVERTING,  // Transcoding to MP3, metadata injection, cover art embedding (~13% of time)
    DONE         // Process finished, failed, or idle
}
