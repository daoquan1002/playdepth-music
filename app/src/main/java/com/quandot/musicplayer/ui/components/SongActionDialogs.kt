/**
 * SongActionDialogs — Track action bottom sheet and playlist, metadata, and deletion dialogs.
 *
 * @remarks
 * `ModalBottomSheet` and `AlertDialog` create standalone Android `Window` instances that default
 * to system light/dark styling rather than the user's in-app theme override; `SyncDialogSystemBars`
 * derives bar glyph contrast from actual Compose surface luminance and re-applies after window
 * attachment. Device deletion routes through `StartIntentSenderForResult` to honor Android 11+
 * scoped-storage `MediaStore.createDeleteRequest` confirmation prompts.
 *
 * @see MusicViewModel
 */
package com.quandot.musicplayer.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName
import com.quandot.musicplayer.util.localizedGenreName
import com.quandot.musicplayer.util.localizedPlaylistName

import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Constants ═══
private const val FAVORITE_ACCENT_HEX = 0xFFE91E63L
private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val LUMINANCE_THRESHOLD = 0.5f

// ═══ Action Bottom Sheet ═══
/**
 * Modal action sheet for a track (queueing, playlist management, favorites, sharing,
 * album/artist navigation, technical metadata inspection, and scoped-storage deletion).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionBottomSheet(
    song: Song,
    viewModel: MusicViewModel,
    onDismissRequest: () -> Unit,
    onNavigateToAlbum: ((Long) -> Unit)? = null,
    onNavigateToArtist: ((String) -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showPlayNow: Boolean = true,
    showPlayNext: Boolean = true,
    showAddToQueue: Boolean = true
) {
    val context = LocalContext.current
    val favoriteSongIds by viewModel.favoriteSongIds.collectAsState()
    val isFavorite = favoriteSongIds.contains(song.id)

    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showSongInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val deletePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.finalizeSongDeletion(song)
            Toast.makeText(context, context.getString(R.string.toast_deleted_song), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.toast_delete_cancelled), Toast.LENGTH_SHORT).show()
        }
        showDeleteConfirmDialog = false
        onDismissRequest()
    }

    val layout = rememberAdaptiveLayoutInfo()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = if (layout.isTabletOrExpanded) 560.dp else BottomSheetDefaults.SheetMaxWidth,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = if (layout.isTabletOrExpanded) 28.dp else 24.dp,
            topEnd = if (layout.isTabletOrExpanded) 28.dp else 24.dp
        )
    ) {
        SyncDialogSystemBars()

        val actionEntries = buildList {
            if (showPlayNow) {
                add(
                    SheetActionEntry(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.song_action_play_now),
                        onClick = {
                            viewModel.playSong(song)
                            onDismissRequest()
                        }
                    )
                )
            }
            if (showPlayNext) {
                add(
                    SheetActionEntry(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = stringResource(R.string.song_action_play_next),
                        onClick = {
                            viewModel.playNext(song)
                            Toast.makeText(context, context.getString(R.string.toast_added_play_next), Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
            }
            if (showAddToQueue) {
                add(
                    SheetActionEntry(
                        icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                        title = stringResource(R.string.song_action_add_queue),
                        onClick = {
                            viewModel.addToQueue(song)
                            Toast.makeText(context, context.getString(R.string.toast_added_queue), Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
            }
            add(
                SheetActionEntry(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.song_action_add_playlist),
                    onClick = { showAddToPlaylistDialog = true }
                )
            )
            add(
                SheetActionEntry(
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    iconTint = if (isFavorite) Color(FAVORITE_ACCENT_HEX) else MaterialTheme.colorScheme.onSurface,
                    title = stringResource(if (isFavorite) R.string.song_action_fav_remove else R.string.song_action_fav_add),
                    onClick = {
                        viewModel.toggleLikeSong(song)
                        val msg = context.getString(if (isFavorite) R.string.toast_fav_removed else R.string.toast_fav_added)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        onDismissRequest()
                    }
                )
            )
            add(
                SheetActionEntry(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.song_action_share),
                    onClick = {
                        if (song.contentUri != Uri.EMPTY) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, song.contentUri)
                                putExtra(Intent.EXTRA_SUBJECT, song.title)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title, song.title)))
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.toast_share_error), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.toast_share_missing), Toast.LENGTH_SHORT).show()
                        }
                        onDismissRequest()
                    }
                )
            )
            if (onRemoveFromPlaylist != null) {
                add(
                    SheetActionEntry(
                        icon = Icons.Default.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.song_action_remove_playlist),
                        textColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            onRemoveFromPlaylist()
                            onDismissRequest()
                        }
                    )
                )
            }
            if (onNavigateToAlbum != null && song.albumId > 0) {
                add(
                    SheetActionEntry(
                        icon = Icons.Default.Album,
                        title = stringResource(R.string.song_action_go_album),
                        onClick = {
                            onNavigateToAlbum(song.albumId)
                            onDismissRequest()
                        }
                    )
                )
            }
            if (onNavigateToArtist != null && song.artist.isNotBlank() &&
                !song.artist.equals("Nghệ sĩ không xác định", ignoreCase = true) &&
                !song.artist.equals("Unknown artist", ignoreCase = true)
            ) {
                add(
                    SheetActionEntry(
                        icon = Icons.Default.Person,
                        title = stringResource(R.string.song_action_go_artist),
                        onClick = {
                            onNavigateToArtist(song.artist)
                            onDismissRequest()
                        }
                    )
                )
            }
            add(
                SheetActionEntry(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.song_action_info),
                    onClick = { showSongInfoDialog = true }
                )
            )
            add(
                SheetActionEntry(
                    icon = Icons.Default.DeleteForever,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.song_action_delete_device),
                    textColor = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteConfirmDialog = true }
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (layout.isTabletOrExpanded) 24.dp else 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AlbumArtImage(
                    song = song,
                    icon = Icons.Default.MusicNote,
                    bucket = ArtResolutionBucket.THUMBNAIL,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(52.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${localizedArtistName(song.artist)} • ${localizedAlbumName(song.album)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            if (layout.isTabletOrExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    actionEntries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            pair.forEach { entry ->
                                SongActionItem(
                                    icon = entry.icon,
                                    title = entry.title,
                                    iconTint = entry.iconTint ?: MaterialTheme.colorScheme.onSurface,
                                    textColor = entry.textColor ?: MaterialTheme.colorScheme.onSurface,
                                    compact = true,
                                    onClick = entry.onClick,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                actionEntries.forEach { entry ->
                    SongActionItem(
                        icon = entry.icon,
                        title = entry.title,
                        iconTint = entry.iconTint ?: MaterialTheme.colorScheme.onSurface,
                        textColor = entry.textColor ?: MaterialTheme.colorScheme.onSurface,
                        onClick = entry.onClick
                    )
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                SyncDialogSystemBars()
                Text(stringResource(R.string.dialog_delete_song_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.dialog_delete_song_msg, song.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSongFromDevice(
                            song = song,
                            onNeedPermission = { intentSender ->
                                deletePermissionLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            },
                            onCompleted = { success ->
                                showDeleteConfirmDialog = false
                                val msg = context.getString(if (success) R.string.toast_deleted_song else R.string.toast_delete_failed)
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        )
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            song = song,
            viewModel = viewModel,
            onDismiss = {
                showAddToPlaylistDialog = false
                onDismissRequest()
            }
        )
    }

    if (showSongInfoDialog) {
        SongInfoDialog(
            song = song,
            onDismiss = {
                showSongInfoDialog = false
                onDismissRequest()
            }
        )
    }
}

// ═══ Dialogs ═══
/**
 * Playlist picker dialog allowing the user to append [song] to an existing or newly created playlist.
 */
@Composable
fun AddToPlaylistDialog(
    song: Song,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
            SyncDialogSystemBars()
            Text(
                stringResource(R.string.dialog_add_playlist_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val layout = rememberAdaptiveLayoutInfo()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (layout.isShortLandscape) 140.dp else 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = playlists,
                        key = { it.id },
                        contentType = { "playlist_picker_item" }
                    ) { pl ->
                        Surface(
                            onClick = {
                                viewModel.addSongToPlaylist(pl.id, song.id)
                                val displayPlaylistName = if (pl.isSystem) {
                                    context.getString(R.string.favorites_playlist_name)
                                } else pl.name
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_added_to_playlist, displayPlaylistName),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 11.dp, horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (pl.isSystem) Icons.Default.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = if (pl.isSystem) Color(FAVORITE_ACCENT_HEX) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = localizedPlaylistName(pl.name, pl.isSystem),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.song_count, pl.songCount, pl.songCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { showCreateDialog = true }) {
                Text("+ ${stringResource(R.string.playlist_create_new)}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )

    if (showCreateDialog) {
        CreatePlaylistDialog(
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false },
            onCreated = { newId ->
                viewModel.addSongToPlaylist(newId, song.id)
                Toast.makeText(context, context.getString(R.string.toast_added_to_new_playlist), Toast.LENGTH_SHORT).show()
                showCreateDialog = false
                onDismiss()
            }
        )
    }
}

/**
 * Dialog for creating a new user playlist.
 */
@Composable
fun CreatePlaylistDialog(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit,
    onCreated: ((Long) -> Unit)? = null
) {
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
            SyncDialogSystemBars()
            Text(
                stringResource(R.string.dialog_create_playlist_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dialog_playlist_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        viewModel.createPlaylist(name) { id ->
                            Toast.makeText(context, context.getString(R.string.toast_created_playlist, name), Toast.LENGTH_SHORT).show()
                            onCreated?.invoke(id)
                        }
                        if (onCreated == null) onDismiss()
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Technical track metadata dialog displaying tags, decoded audio specs, file size, and disk path.
 */
@Composable
fun SongInfoDialog(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var fullSpecs by remember { mutableStateOf("") }
    var fileSizeFormatted by remember { mutableStateOf("") }

    LaunchedEffect(song) {
        withContext(Dispatchers.IO) {
            val specs = AudioSpecsHelper.getAudioSpecs(context, song)
            fullSpecs = specs
            val file = File(song.path)
            if (file.exists()) {
                val size = file.length()
                val mb = size.toDouble() / BYTES_PER_MIB
                fileSizeFormatted = String.format(Locale.US, "%.2f MB", mb)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = {
            SyncDialogSystemBars()
            Text(
                stringResource(R.string.dialog_song_info_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            val layout = rememberAdaptiveLayoutInfo()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (layout.isShortLandscape) 190.dp else 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoRow(stringResource(R.string.info_title), song.title)
                InfoRow(stringResource(R.string.info_artist), localizedArtistName(song.artist))
                InfoRow(stringResource(R.string.info_album), localizedAlbumName(song.album))
                if (song.genre.isNotBlank()) {
                    InfoRow(stringResource(R.string.info_genre), localizedGenreName(song.genre))
                }
                if (song.year > 0) {
                    InfoRow(stringResource(R.string.info_year), song.year.toString())
                }
                InfoRow(stringResource(R.string.info_duration), song.formattedDuration)
                if (fullSpecs.isNotBlank()) {
                    InfoRow(stringResource(R.string.info_format), fullSpecs)
                }
                if (fileSizeFormatted.isNotBlank()) {
                    InfoRow(stringResource(R.string.info_size), fileSizeFormatted)
                }
                InfoRow(stringResource(R.string.info_path), song.path)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

// ═══ Private Helpers ═══
private tailrec fun findDialogWindow(parent: ViewParent?): Window? {
    if (parent == null) return null
    if (parent is DialogWindowProvider) return parent.window
    return findDialogWindow(parent.parent)
}

/**
 * Aligns status and navigation bar icon contrast inside modal windows with the in-app theme
 * so manual dark-mode overrides never inherit light-theme dark icons from the host activity.
 */
@Composable
internal fun SyncDialogSystemBars() {
    val view = LocalView.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val navSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val isDarkTheme = (surfaceColor.red * 0.2126f + surfaceColor.green * 0.7152f + surfaceColor.blue * 0.0722f) < LUMINANCE_THRESHOLD

    SideEffect {
        val dialogWindow = findDialogWindow(view.parent) ?: return@SideEffect
        fun applyAppearance(targetWindow: Window, targetView: View) {
            val controller = WindowCompat.getInsetsController(targetWindow, targetView)
            controller.isAppearanceLightStatusBars = !isDarkTheme
            controller.isAppearanceLightNavigationBars = !isDarkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                targetWindow.isNavigationBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                targetWindow.navigationBarColor = navSurfaceColor.toArgb()
            }
        }
        applyAppearance(dialogWindow, view)
        view.post {
            findDialogWindow(view.parent)?.let { w -> applyAppearance(w, view) }
        }
    }
}

private data class SheetActionEntry(
    val icon: ImageVector,
    val title: String,
    val iconTint: Color? = null,
    val textColor: Color? = null,
    val onClick: () -> Unit
)

@Composable
private fun SongActionItem(
    icon: ImageVector,
    title: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    compact: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 12.dp else 0.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 11.dp else 13.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(if (compact) 20.dp else 22.dp)
        )
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = textColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
