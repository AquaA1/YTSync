package com.ytsync.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ytsync_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_SYNC_FREQUENCY = "sync_frequency"
    }

    var isWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var syncFrequencyHours: Int
        get() = prefs.getInt(KEY_SYNC_FREQUENCY, 24)
        set(value) = prefs.edit().putInt(KEY_SYNC_FREQUENCY, value).apply()
}
