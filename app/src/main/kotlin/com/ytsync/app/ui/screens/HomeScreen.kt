package com.ytsync.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.ytsync.app.data.db.entity.PlaylistEntity
import com.ytsync.app.data.db.entity.SyncStatus
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onPlaylistClick: (Long) -> Unit,
    viewModel: PlaylistListViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val lastSyncedTime by viewModel.lastSyncedTime.collectAsState()
    val error by viewModel.error.collectAsState()
    val showUpdateBanner by viewModel.showUpdateBanner.collectAsState()
    val newSongsCount by viewModel.newSongsCount.collectAsState()

    var showBottomSheet by remember { mutableStateOf(false) }
    var showRationaleDialog by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deletePhysicalFiles by remember { mutableStateOf(false) }
    var revealedPlaylistId by remember { mutableStateOf<Long?>(null) }

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current

    // Runtime Permission Request Setup
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    LaunchedEffect(Unit) {
        val hasPermissions = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) {
            showRationaleDialog = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                Log.i("HomeScreen", "All permissions successfully granted.")
            } else {
                Log.w("HomeScreen", "Some or all permissions denied by user.")
            }
        }
    )

    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text("Permissions Required", fontWeight = FontWeight.Bold) },
            text = {
                val rationaleText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "YTSync needs permission to post notifications. This allows you to track background metadata synchronization and download operations progress."
                } else {
                    "YTSync needs storage access to scan local downloaded directories and save audio files to your external storage directory."
                }
                Text(rationaleText)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationaleDialog = false
                        permissionLauncher.launch(permissionsToRequest)
                    }
                ) {
                    Text("Grant", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) {
                    Text("Not Now")
                }
            }
        )
    }

    // Playlist Delete Confirmation Dialog
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = {
                playlistToDelete = null
                deletePhysicalFiles = false
            },
            title = { Text("Delete Playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Are you sure you want to delete '${playlist.name}'? This will remove its synchronization records and songs from the app database.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deletePhysicalFiles = !deletePhysicalFiles }
                            .padding(vertical = 8.dp)
                    ) {
                        Checkbox(
                            checked = deletePhysicalFiles,
                            onCheckedChange = { deletePhysicalFiles = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Also delete downloaded music files from device storage",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlaylist(playlist, deletePhysicalFiles)
                        playlistToDelete = null
                        deletePhysicalFiles = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        playlistToDelete = null
                        deletePhysicalFiles = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("YTSync", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.updateYtdlp() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Update Downloader Engine"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showBottomSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Playlist")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(
                    enabled = revealedPlaylistId != null,
                    onClick = { revealedPlaylistId = null }
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                // Top Summary Card
                item {
                    SummaryCard(
                        lastSynced = lastSyncedTime,
                        onSyncAll = { viewModel.syncAllPlaylists() }
                    )
                }

                if (showUpdateBanner) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "$newSongsCount new songs found across your playlists",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { viewModel.dismissBanner() }) {
                                        Text("Dismiss")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { viewModel.syncAffectedPlaylists() }) {
                                        Text("Sync Now")
                                    }
                                }
                            }
                        }
                    }
                }

                if (playlists.isEmpty()) {
                    item {
                        EmptyHomeState(onAddClick = { showBottomSheet = true })
                    }
                } else {
                    items(playlists, key = { it.id }) { playlist ->
                        SwipeToDismissPlaylistWrapper(
                            isRevealed = revealedPlaylistId == playlist.id,
                            onRevealStateChanged = { revealed ->
                                if (revealed) {
                                    revealedPlaylistId = playlist.id
                                } else if (revealedPlaylistId == playlist.id) {
                                    revealedPlaylistId = null
                                }
                            },
                            onDeleteTriggered = { playlistToDelete = playlist },
                            onRowClick = { onPlaylistClick(playlist.id) }
                        ) { clickHandler ->
                            PlaylistItemCard(
                                playlist = playlist,
                                onClick = clickHandler,
                                onSyncClick = { viewModel.syncPlaylist(playlist.id) }
                            )
                        }
                    }
                }
            }

            if (showBottomSheet) {
                AddPlaylistBottomSheet(
                    onDismiss = { showBottomSheet = false },
                    onAddPlaylist = { url, saveLocationUri ->
                        viewModel.addPlaylist(url, saveLocationUri)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showBottomSheet = false
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SummaryCard(
    lastSynced: Long?,
    onSyncAll: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Library Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last synced: ${formatRelativeTimestamp(lastSynced)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Button(
                onClick = onSyncAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync All", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PlaylistItemCard(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${playlist.songCount} songs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatRelativeTimestamp(playlist.lastSyncedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SyncStatusChip(status = playlist.syncStatus)

                if (playlist.syncStatus == SyncStatus.SYNCING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    FilledIconButton(
                        onClick = onSyncClick,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Playlist",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToDismissPlaylistWrapper(
    isRevealed: Boolean,
    onRevealStateChanged: (Boolean) -> Unit,
    onDeleteTriggered: () -> Unit,
    onRowClick: () -> Unit,
    content: @Composable (onClick: () -> Unit) -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val revealOffsetPx = with(density) { -80.dp.toPx() }

    val state = remember {
        AnchoredDraggableState(
            initialValue = SwipeState.CLOSED,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { 100.dp.toPx() } },
            animationSpec = tween()
        )
    }

    LaunchedEffect(revealOffsetPx) {
        state.updateAnchors(
            DraggableAnchors {
                SwipeState.CLOSED at 0f
                SwipeState.REVEALED at revealOffsetPx
            }
        )
    }

    LaunchedEffect(isRevealed) {
        if (isRevealed) {
            if (state.currentValue != SwipeState.REVEALED) {
                state.animateTo(SwipeState.REVEALED)
            }
        } else {
            if (state.currentValue != SwipeState.CLOSED) {
                state.animateTo(SwipeState.CLOSED)
            }
        }
    }

    val targetValue = state.targetValue
    LaunchedEffect(targetValue) {
        onRevealStateChanged(targetValue == SwipeState.REVEALED)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Background: Action buttons
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(80.dp)
                    .clickable {
                        coroutineScope.launch {
                            state.animateTo(SwipeState.CLOSED)
                        }
                        onDeleteTriggered()
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Foreground: Row content
        val rowClick = {
            if (state.currentValue == SwipeState.REVEALED) {
                coroutineScope.launch {
                    state.animateTo(SwipeState.CLOSED)
                }
            } else {
                onRowClick()
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    val offset = if (state.offset.isNaN()) 0f else state.offset
                    IntOffset(offset.roundToInt(), 0)
                }
                .anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal
                )
        ) {
            content(rowClick)
        }
    }
}

@Composable
fun SyncStatusChip(status: SyncStatus) {
    val (text, containerColor, contentColor) = when (status) {
        SyncStatus.SYNCED -> Triple("Synced", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        SyncStatus.OUT_OF_SYNC -> Triple("Out of Sync", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        SyncStatus.SYNCING -> Triple("Syncing", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SyncStatus.ERROR -> Triple("Error", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun EmptyHomeState(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No playlists added",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap the + button to enter your first YouTube Music or YouTube playlist URL.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddClick) {
            Text("Add Playlist")
        }
    }
}

private fun formatRelativeTimestamp(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "Never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> {
            val sdf = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlaylistBottomSheet(
    onDismiss: () -> Unit,
    onAddPlaylist: (String, String?) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var selectedFolderUri by remember { mutableStateOf<String?>(null) }
    var folderDisplayName by remember { mutableStateOf("App default location") }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                Log.e("AddPlaylistSheet", "Failed to take persistable permission", e)
            }
            selectedFolderUri = uri.toString()
            folderDisplayName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Custom Folder"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = "Add YouTube Playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    isError = false
                },
                label = { Text("Playlist URL") },
                placeholder = { Text("https://www.youtube.com/playlist?list=…") },
                isError = isError,
                supportingText = {
                    if (isError) {
                        Text("Please enter a valid YouTube playlist URL")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Save to option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickerLauncher.launch(null) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Save Location",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = folderDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { pickerLauncher.launch(null) }) {
                    Text("Choose folder…")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    if (url.trim().startsWith("http") && url.contains("list=")) {
                        onAddPlaylist(url, selectedFolderUri)
                    } else {
                        isError = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync Playlist")
            }
        }
    }
}
