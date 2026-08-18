package com.ytsync.app.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.work.ForegroundInfo
import android.content.pm.ServiceInfo

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "ytsync_notifications"
        const val CHANNEL_NAME = "Playlist Sync"
        const val NOTIFICATION_ID = 1001
        const val FOREGROUND_NOTIFICATION_ID = 1002
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Status notifications for YouTube Music playlists sync"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showSyncNotification(playlistName: String, outOfSyncCount: Int) {
        val message = if (outOfSyncCount == 1) {
            "1 song out of sync in $playlistName"
        } else {
            "$outOfSyncCount songs out of sync in $playlistName"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("YTSync Update")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                return
            }
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun createForegroundInfo(playlistName: String, statusText: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("YTSync Active Sync: $playlistName")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    fun updateForegroundNotification(playlistName: String, statusText: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("YTSync Active Sync: $playlistName")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }

        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    fun cancelForegroundNotification() {
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    fun cancelSyncNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
