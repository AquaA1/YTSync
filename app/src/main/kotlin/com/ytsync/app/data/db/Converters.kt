package com.ytsync.app.data.db

import androidx.room.TypeConverter
import com.ytsync.app.data.db.entity.DownloadStatus
import com.ytsync.app.data.db.entity.SyncStatus

class Converters {

    @TypeConverter fun syncStatusToString(v: SyncStatus): String = v.name
    @TypeConverter fun stringToSyncStatus(v: String): SyncStatus = enumValueOf(v)

    @TypeConverter fun downloadStatusToString(v: DownloadStatus): String = v.name
    @TypeConverter fun stringToDownloadStatus(v: String): DownloadStatus = enumValueOf(v)
}
