package com.ytsync.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ytsync.app.data.db.entity.SongEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val filterPlaylistId by viewModel.filterPlaylistId.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<DownloadedSongItem?>(null) }
    var songDetailsToShow by remember { mutableStateOf<DownloadedSongItem?>(null) }

    // Grouping helper
    val grouped = remember(downloadedSongs, sortOrder) {
        when (sortOrder) {
            DownloadSortOrder.DATE_DOWNLOADED -> downloadedSongs.groupBy { formatGroupDate(it.downloadedAt) }
            DownloadSortOrder.PLAYLIST -> downloadedSongs.groupBy { it.playlistName }
            DownloadSortOrder.TITLE -> downloadedSongs.groupBy { it.song.title.take(1).uppercase() }
        }
    }

    // Delete Song Confirmation Dialog
    songToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text("Delete Song", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${item.song.title}' from your storage? The song status will be set back to Pending.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSongFile(item)
                        songToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Song Details Bottom Sheet
    songDetailsToShow?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { songDetailsToShow = null },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = "Song Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                DetailItem(label = "Title", value = item.song.title)
                DetailItem(label = "Playlist", value = item.playlistName)
                DetailItem(label = "Format", value = "MP3 Audio (128kbps)")
                DetailItem(
                    label = "File Size",
                    value = "%.2f MB".format(item.fileSize / (1024.0 * 1024.0))
                )
                DetailItem(
                    label = "Downloaded At",
                    value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.downloadedAt))
                )
                DetailItem(label = "Storage Path", value = item.song.filePath ?: "Unknown")

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { songDetailsToShow = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Sort & Filter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sort Chip Selector
                Box(modifier = Modifier.wrapContentWidth()) {
                    val sortLabel = when (sortOrder) {
                        DownloadSortOrder.DATE_DOWNLOADED -> "Date Downloaded"
                        DownloadSortOrder.TITLE -> "Title (A-Z)"
                        DownloadSortOrder.PLAYLIST -> "Playlist"
                    }
                    FilterChip(
                        selected = true,
                        onClick = { showSortMenu = true },
                        label = { Text("Sort: $sortLabel") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Date Downloaded") },
                            onClick = {
                                viewModel.setSortOrder(DownloadSortOrder.DATE_DOWNLOADED)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Title (A-Z)") },
                            onClick = {
                                viewModel.setSortOrder(DownloadSortOrder.TITLE)
                                showSortMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Playlist") },
                            onClick = {
                                viewModel.setSortOrder(DownloadSortOrder.PLAYLIST)
                                showSortMenu = false
                            }
                        )
                    }
                }

                // Filter Chip Selector
                Box(modifier = Modifier.wrapContentWidth()) {
                    val activeFilterName = if (filterPlaylistId == null) {
                        "All Playlists"
                    } else {
                        playlists.find { it.id == filterPlaylistId }?.name ?: "Unknown"
                    }
                    FilterChip(
                        selected = filterPlaylistId != null,
                        onClick = { showFilterMenu = true },
                        label = { Text("Filter: $activeFilterName") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
                    )
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Playlists") },
                            onClick = {
                                viewModel.setFilterPlaylistId(null)
                                showFilterMenu = false
                            }
                        )
                        playlists.forEach { pl ->
                            DropdownMenuItem(
                                text = { Text(pl.name) },
                                onClick = {
                                    viewModel.setFilterPlaylistId(pl.id)
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (downloadedSongs.isEmpty()) {
                EmptyDownloadsState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    grouped.forEach { (headerName, itemsList) ->
                        stickyHeader(key = headerName) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = headerName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        items(itemsList, key = { it.song.id }) { item ->
                            SwipeToDismissSongWrapper(
                                onDeleteTriggered = { songToDelete = item },
                                content = {
                                    DownloadedSongRow(
                                        item = item,
                                        onRowClick = { songDetailsToShow = item }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadedSongRow(
    item: DownloadedSongItem,
    onRowClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Small Playlist Tag
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = item.playlistName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(20.dp)
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "%.1f MB".format(item.fileSize / (1024.0 * 1024.0)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissSongWrapper(
    onDeleteTriggered: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteTriggered()
                false
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val isSwiped = dismissState.currentValue != SwipeToDismissBoxValue.Settled || 
                          dismissState.targetValue != SwipeToDismissBoxValue.Settled
            if (isSwiped) {
                val color = MaterialTheme.colorScheme.errorContainer
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Surface(
                        color = color,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(72.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Song",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        content = { content() }
    )
}

@Composable
fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun EmptyDownloadsState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No downloads yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tracked playlist songs will automatically populate here once downloaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatGroupDate(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown"
    val calendar = Calendar.getInstance()
    val today = calendar.get(Calendar.DAY_OF_YEAR)
    val year = calendar.get(Calendar.YEAR)

    val fileCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val fileDay = fileCalendar.get(Calendar.DAY_OF_YEAR)
    val fileYear = fileCalendar.get(Calendar.YEAR)

    return when {
        year == fileYear && today == fileDay -> "Today"
        year == fileYear && today - fileDay == 1 -> "Yesterday"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
