package com.ytsync.app.navigation

/**
 * Sealed hierarchy of every navigable destination in the app.
 * Arguments are encoded into the route string so NavHost can parse them.
 */
sealed class Screen(val route: String) {

    /** Root screen — shows all tracked playlists. */
    data object PlaylistList : Screen("playlist_list")

    /** Per-playlist screen — shows tracks for a given playlist ID. */
    data object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlist_detail/$playlistId"
    }

    /** Settings screen — options to configure app configurations. */
    data object Settings : Screen("settings")
}
