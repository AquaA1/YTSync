package com.ytsync.app.data.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object YoutubeDLInitializer {
    private const val TAG = "YoutubeDLInitializer"
    private val initializedDeferred = CompletableDeferred<Unit>()

    fun init(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting YoutubeDL initialization on background thread...")
                YoutubeDL.getInstance().init(context.applicationContext)
                Log.i(TAG, "YoutubeDL initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize YoutubeDL", e)
            }

            try {
                Log.d(TAG, "Starting FFmpeg initialization on background thread...")
                FFmpeg.getInstance().init(context.applicationContext)
                Log.i(TAG, "FFmpeg initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FFmpeg", e)
            }

            initializedDeferred.complete(Unit)
        }
    }

    suspend fun awaitInitialization() {
        initializedDeferred.await()
    }
}
