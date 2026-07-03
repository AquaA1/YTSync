package com.ytsync.app.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ytsync.app.data.settings.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncScheduler"

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager
) {

    fun scheduleBackgroundSync() {
        val isWifiOnly = settingsManager.isWifiOnly
        val frequencyHours = settingsManager.syncFrequencyHours.toLong()

        Log.i(TAG, "Scheduling background periodic sync: every ${frequencyHours}h, wifiOnly = $isWifiOnly")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(frequencyHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ytsync_periodic_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicSyncRequest
        )
    }
}
