package com.ytsync.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ytsync_datastore")

@Singleton
class LaunchCheckManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_LAST_CHECKED_AT = longPreferencesKey("last_checked_at")
    }

    suspend fun getLastCheckedAt(): Long {
        return context.dataStore.data.first()[KEY_LAST_CHECKED_AT] ?: 0L
    }

    suspend fun setLastCheckedAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_CHECKED_AT] = timestamp
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
