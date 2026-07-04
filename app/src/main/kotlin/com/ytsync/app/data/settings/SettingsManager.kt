package com.ytsync.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DEFAULT_SAVE_LOCATION_URI = "default_save_location_uri"
    }

    private val _useDynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, true))
    val useDynamicColorFlow: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    private val _defaultSaveLocationUri = MutableStateFlow(prefs.getString(KEY_DEFAULT_SAVE_LOCATION_URI, null))
    val defaultSaveLocationUriFlow: StateFlow<String?> = _defaultSaveLocationUri.asStateFlow()

    var defaultSaveLocationUri: String?
        get() = prefs.getString(KEY_DEFAULT_SAVE_LOCATION_URI, null)
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_SAVE_LOCATION_URI, value).apply()
            _defaultSaveLocationUri.value = value
        }

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
            _useDynamicColor.value = value
        }

    var isWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var syncFrequencyHours: Int
        get() = prefs.getInt(KEY_SYNC_FREQUENCY, 24)
        set(value) = prefs.edit().putInt(KEY_SYNC_FREQUENCY, value).apply()
}
