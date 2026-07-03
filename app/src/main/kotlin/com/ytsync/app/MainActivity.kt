package com.ytsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ytsync.app.data.settings.SettingsManager
import com.ytsync.app.navigation.AppNavigation
import com.ytsync.app.ui.theme.YTSyncTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val useDynamicColor by settingsManager.useDynamicColorFlow.collectAsState()

            YTSyncTheme(dynamicColor = useDynamicColor) {
                AppNavigation()
            }
        }
    }
}
