package com.ytsync.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sealed hierarchy of every navigable destination in the app.
 * Arguments are encoded into the route string so NavHost can parse them.
 */
sealed class Screen(val route: String) {

    /** Root screen — shows all tracked playlists with summary. */
    data object Home : Screen("home")

    /** Downloads screen — shows all downloaded tracks. */
    data object Downloads : Screen("downloads")

    /** Settings screen — options to configure app configurations. */
    data object Settings : Screen("settings")

    /** Per-playlist screen — shows tracks for a given playlist ID. */
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlist_detail/$playlistId"
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home.route, "Home", Icons.Default.Home),
    BottomNavItem(Screen.Downloads.route, "Downloads", Icons.Default.Download),
    BottomNavItem(Screen.Settings.route, "Settings", Icons.Default.Settings)
)
