package com.ytsync.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "YTSyncApplication"

@HiltAndroidApp
class YTSyncApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initYoutubeDL()
    }

    private fun initYoutubeDL() {
        try {
            YoutubeDL.getInstance().init(this)
            Log.i(TAG, "YoutubeDL initialized")
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }
        try {
            FFmpeg.getInstance().init(this)
            Log.i(TAG, "FFmpeg initialized")
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize FFmpeg", e)
        }
    }
}

