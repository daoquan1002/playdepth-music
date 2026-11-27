/**
 * HomeScreen — Playback-history dashboard surfacing recently played albums, tracks, and artists.
 *
 * @remarks
 * Consumes [LocalBottomContentPadding] so horizontal carousels clear the floating MiniPlayer and
 * bottom NavigationBar uniformly across portrait, landscape, and rail layouts. Recent songs are
 * chunked into multi-row columns to keep vertical scrolling compact.
 *
 * @see MainScreen
 * @see MusicViewModel
 * @see AlbumArtImage
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Calendar
import java.util.TimeZone

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.AlbumArtImage
import com.quandot.musicplayer.ui.components.ExpressiveLoadingIndicator
import com.quandot.musicplayer.ui.components.LocalFastScrollingState
import com.quandot.musicplayer.ui.components.rememberIsFastScrolling
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Artist
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.paneContentPadding
import com.quandot.musicplayer.ui.theme.paneHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopInsets
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Constants ═══
private const val RECENT_SONGS_ROWS_PORTRAIT = 3
private const val RECENT_SONGS_ROWS_SHORT_LANDSCAPE = 2
private const val TABLET_RECENT_SONGS_COLUMNS = 3
private const val TABLET_RECENT_SONGS_MAX_ITEMS = 9
private const val MORNING_START_HOUR = 5
private const val AFTERNOON_START_HOUR = 12
private const val EVENING_START_HOUR = 18

private fun resolveContextualGreetingResId(hourOfDay24: Int): Int = when {
    hourOfDay24 in MORNING_START_HOUR until AFTERNOON_START_HOUR -> R.string.home_greeting_morning
    hourOfDay24 in AFTERNOON_START_HOUR until EVENING_START_HOUR -> R.string.home_greeting_afternoon
    else -> R.string.home_greeting_evening
}

private fun currentContextualGreetingResId(): Int {
    // Calendar.HOUR_OF_DAY always reports 0..23 independently of the device's 12-hour/24-hour setting.
    val hourOfDay24 = Calendar.getInstance(TimeZone.getDefault()).get(Calendar.HOUR_OF_DAY)
    return resolveContextualGreetingResId(hourOfDay24)
}

// ═══ Home Screen ═══
@Composable
fun HomeScreen(
    viewModel: MusicViewModel,
    onSongSelected: (Song) -> Unit,
    onNavigateToLibraryAlbums: () -> Unit,
    onAlbumClick: (Album) -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recentSongs by viewModel.mostPlayedSongs.collectAsState()
    val recentAlbums by viewModel.mostPlayedAlbums.collectAsState()
    val recentArtists by viewModel.mostPlayedArtists.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val hasPermission by viewModel.hasStoragePermission.collectAsState()

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current
    val horizontalInsetsMod = Modifier
        .windowInsetsPadding(paneHorizontalInsets())
        .padding(horizontal = layout.horizontalPadding)
    val rowContentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding + 2.dp)
    val chunkRows = if (layout.isShortLandscape) RECENT_SONGS_ROWS_SHORT_LANDSCAPE else RECENT_SONGS_ROWS_PORTRAIT
    val recentSongColumns = remember(recentSongs, chunkRows) {
        recentSongs.chunked(chunkRows)
    }
    val recentPlaylistSource = stringResource(R.string.home_recent_source)

    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var greetingResId by remember { mutableIntStateOf(currentContextualGreetingResId()) }

    DisposableEffect(context, lifecycleOwner) {
        val refreshGreeting = {
            val next = currentContextualGreetingResId()
            if (next != greetingResId) {
                greetingResId = next
            }
        }
        val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshGreeting()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                timeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshGreeting()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refreshGreeting()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(timeReceiver) }
        }
    }

    val listState = rememberLazyListState()
    val recentAlbumsListState = rememberLazyListState()
    val recentSongsListState = rememberLazyListState()
    val recentArtistsListState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(paneTopInsets())
    ) {
        Spacer(modifier = Modifier.height(if (layout.isLandscape) 4.dp else 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(horizontalInsetsMod)
                .height(if (layout.isLandscape) 48.dp else 54.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(greetingResId),
                style = if (layout.isLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.home_menu_options)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_rescan)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        enabled = !isScanning,
                        onClick = {
                            showMenu = false
                            viewModel.loadMusic(forceRescan = true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_settings)) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onNavigateToSettings()
                        }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(if (layout.isShortLandscape) 16.dp else 24.dp)
            ) {
                if (!hasPermission) {
                    item(key = "home_permission", contentType = "home_banner") {
                        Surface(
                            shape = RoundedCornerShape(if (layout.isTabletOrExpanded) 18.dp else 16.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(horizontalInsetsMod)
                        ) {
                            if (layout.isTabletOrExpanded) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.home_permission_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            stringResource(R.string.home_permission_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                    Button(
                                        onClick = onRequestPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(stringResource(R.string.home_permission_grant))
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.home_permission_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        stringResource(R.string.home_permission_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                    )
                                    Button(
                                        onClick = onRequestPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(stringResource(R.string.home_permission_grant))
                                    }
                                }
                            }
                        }
                    }
                }

                if (isScanning) {
                    item(key = "home_scanning", contentType = "home_scanning") {
                        val scanningLabel = when (val state = scanState) {
                            is com.quandot.musicplayer.model.LibraryScanState.Scanning -> when {
                                state.totalCount > 0 && state.currentFolder.isNotBlank() -> stringResource(
                                    R.string.settings_rescan_progress_folder,
                                    state.scannedCount,
                                    state.totalCount,
                                    state.currentFolder
                                )
                                state.totalCount > 0 -> pluralStringResource(
                                    R.plurals.settings_rescan_progress,
                                    state.totalCount,
                                    state.scannedCount,
                                    state.totalCount
                                )
                                else -> stringResource(R.string.home_scanning)
                            }
                            else -> stringResource(R.string.home_scanning)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(horizontalInsetsMod)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ExpressiveLoadingIndicator(modifier = Modifier.size(26.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = scanningLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val allHistoryEmpty = recentAlbums.isEmpty() && recentSongs.isEmpty() && recentArtists.isEmpty()
                if (layout.isTabletOrExpanded && allHistoryEmpty) {
                    item(key = "home_empty_grid", contentType = "home_empty_grid") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(horizontalInsetsMod),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            EmptyStatGridCard(
                                icon = Icons.Default.Album,
                                sectionTitle = stringResource(R.string.home_recent_albums),
                                title = stringResource(R.string.home_empty_title),
                                subtitle = stringResource(R.string.home_empty_albums),
                                modifier = Modifier.weight(1f)
                            )
                            EmptyStatGridCard(
                                icon = Icons.Default.MusicNote,
                                sectionTitle = stringResource(R.string.home_recent_songs),
                                title = stringResource(R.string.home_empty_title),
                                subtitle = stringResource(R.string.home_empty_songs),
                                modifier = Modifier.weight(1f)
                            )
                            EmptyStatGridCard(
                                icon = Icons.Default.Person,
                                sectionTitle = stringResource(R.string.home_recent_artists),
                                title = stringResource(R.string.home_empty_title),
                                subtitle = stringResource(R.string.home_empty_artists),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    item(key = "home_recent_albums", contentType = "home_history_section") {
                        HomeHistorySection(
                            title = stringResource(R.string.home_recent_albums),
                            titleModifier = horizontalInsetsMod
                        ) {
                            if (recentAlbums.isEmpty()) {
                                EmptyStatCard(
                                    icon = Icons.Default.Album,
                                    title = stringResource(R.string.home_empty_title),
                                    subtitle = stringResource(R.string.home_empty_albums),
                                    modifier = horizontalInsetsMod
                                )
                            } else {
                                val albumsFastScroll = rememberIsFastScrolling(recentAlbumsListState)
                                CompositionLocalProvider(LocalFastScrollingState provides albumsFastScroll) {
                                LazyRow(
                                    state = recentAlbumsListState,
                                    horizontalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 16.dp else 14.dp),
                                    contentPadding = rowContentPadding
                                ) {
                                    // Slot-index key keeps horizontal scroll anchored when history reorders
                                    itemsIndexed(
                                        items = recentAlbums,
                                        key = { index, _ -> "recent_album_slot_$index" },
                                        contentType = { _, _ -> "recent_album" }
                                    ) { _, album ->
                                        AlbumSuggestionCard(
                                            album = album,
                                            onClick = { onAlbumClick(album) },
                                            cardWidth = when {
                                                layout.isShortLandscape -> 132.dp
                                                layout.isTabletOrExpanded -> 152.dp
                                                else -> 144.dp
                                            },
                                            cornerRadius = if (layout.isTabletOrExpanded) 14.dp else 12.dp
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }

                    // 3-column static grid on tablets matches desktop/tablet dashboard density; horizontal columns on phones
                    item(key = "home_recent_songs", contentType = "home_history_section") {
                        HomeHistorySection(
                            title = stringResource(R.string.home_recent_songs),
                            titleModifier = horizontalInsetsMod
                        ) {
                            if (recentSongs.isEmpty()) {
                                EmptyStatCard(
                                    icon = Icons.Default.MusicNote,
                                    title = stringResource(R.string.home_empty_title),
                                    subtitle = stringResource(R.string.home_empty_songs),
                                    modifier = horizontalInsetsMod
                                )
                            } else if (layout.isTabletOrExpanded) {
                                val tabletRows = remember(recentSongs) {
                                    recentSongs.take(TABLET_RECENT_SONGS_MAX_ITEMS).chunked(TABLET_RECENT_SONGS_COLUMNS)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(horizontalInsetsMod),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    tabletRows.forEach { rowSongs ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                                        ) {
                                            rowSongs.forEach { song ->
                                                QuickPlayItem(
                                                    song = song,
                                                    onClick = {
                                                        viewModel.playSong(
                                                            song,
                                                            recentSongs,
                                                            sourceName = recentPlaylistSource
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            repeat(TABLET_RECENT_SONGS_COLUMNS - rowSongs.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else {
                                val songsFastScroll = rememberIsFastScrolling(recentSongsListState)
                                CompositionLocalProvider(LocalFastScrollingState provides songsFastScroll) {
                                LazyRow(
                                    state = recentSongsListState,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = rowContentPadding
                                ) {
                                    // Slot-index key keeps horizontal scroll anchored when history reorders
                                    itemsIndexed(
                                        items = recentSongColumns,
                                        key = { colIdx, _ -> "recent_song_col_slot_$colIdx" },
                                        contentType = { _, _ -> "recent_song_column" }
                                    ) { _, columnSongs ->
                                        val quickPlayWidth = if (layout.isShortLandscape) 276.dp else 296.dp
                                        Column(
                                            modifier = Modifier.width(quickPlayWidth),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            columnSongs.forEach { song ->
                                                QuickPlayItem(
                                                    song = song,
                                                    onClick = {
                                                        viewModel.playSong(
                                                            song,
                                                            recentSongs,
                                                            sourceName = recentPlaylistSource
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
                    }

                    item(key = "home_recent_artists", contentType = "home_history_section") {
                        HomeHistorySection(
                            title = stringResource(R.string.home_recent_artists),
                            titleModifier = horizontalInsetsMod
                        ) {
                            if (recentArtists.isEmpty()) {
                                EmptyStatCard(
                                    icon = Icons.Default.Person,
                                    title = stringResource(R.string.home_empty_title),
                                    subtitle = stringResource(R.string.home_empty_artists),
                                    modifier = horizontalInsetsMod
                                )
                            } else {
                                LazyRow(
                                    state = recentArtistsListState,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = rowContentPadding
                                ) {
                                    // Slot-index key keeps horizontal scroll anchored when history reorders
                                    itemsIndexed(
                                        items = recentArtists,
                                        key = { index, _ -> "recent_artist_slot_$index" },
                                        contentType = { _, _ -> "recent_artist" }
                                    ) { _, artist ->
                                        ArtistStatCard(
                                            artist = artist,
                                            onClick = { onArtistClick(artist) },
                                            cardWidth = when {
                                                layout.isShortLandscape -> 88.dp
                                                layout.isTabletOrExpanded -> 96.dp
                                                else -> 96.dp
                                            },
                                            avatarSize = when {
                                                layout.isShortLandscape -> 64.dp
                                                layout.isTabletOrExpanded -> 68.dp
                                                else -> 84.dp
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══ Reusable Home Cards ═══

@Composable
fun EmptyStatCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyStatGridCard(
    icon: ImageVector,
    sectionTitle: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ArtistStatCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 96.dp,
    avatarSize: Dp = 84.dp
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(avatarSize)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(if (avatarSize >= 80.dp) 36.dp else 30.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = localizedArtistName(artist.name),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AlbumSuggestionCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 144.dp,
    cornerRadius: Dp = 12.dp
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .clickable(onClick = onClick)
    ) {
        AlbumArtImage(
            album = album,
            icon = Icons.Default.Album,
            bucket = ArtResolutionBucket.CARD,
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = localizedAlbumName(album.title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = localizedArtistName(album.artist),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun QuickPlayItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AlbumArtImage(
            song = song,
            icon = Icons.Default.MusicNote,
            bucket = ArtResolutionBucket.THUMBNAIL,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(48.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${localizedArtistName(song.artist)} • ${song.formattedDuration}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ═══ Private Helpers ═══
@Composable
private fun HomeHistorySection(
    title: String,
    titleModifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = titleModifier
        )
        content()
    }
}
