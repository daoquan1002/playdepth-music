/**
 * AlbumDetailScreen — Album tracklist view with centered hero artwork and adaptive layouts.
 *
 * @remarks
 * Uses a centered editorial hero header (large rounded album cover, centered title, artist,
 * year/track summary, and Play/Shuffle pills) across phone portrait, phone landscape split-pane,
 * and tablet screens while keeping tracklists fast and allocation-free.
 *
 * @see MusicLibraryScreen
 * @see QueueEqualizerIndicator
 * @see SongActionBottomSheet
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.AlbumArtImage
import com.quandot.musicplayer.ui.components.LibraryFastScroller
import com.quandot.musicplayer.ui.components.QueueEqualizerIndicator
import com.quandot.musicplayer.ui.components.SongActionBottomSheet
import com.quandot.musicplayer.util.isUnknownAlbumName
import com.quandot.musicplayer.util.isUnknownArtistName
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.AlbumSortOption
import com.quandot.musicplayer.model.NATURAL_TEXT_COMPARATOR
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.paneContentPadding
import com.quandot.musicplayer.ui.theme.paneHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopInsets
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Constants ═══
private const val LANDSCAPE_HEADER_WEIGHT_COMPACT = 0.42f
private const val LANDSCAPE_LIST_WEIGHT_COMPACT = 0.58f
private const val NO_TRACK_NUMBER_LABEL = "-"

// ═══ Album Detail Screen ═══
/**
 * Displays an album's centered hero artwork, metadata, playback actions, and sortable tracklist
 * while preserving the host navigation shell and MiniPlayer below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var selectedSongForAction by remember { mutableStateOf<Song?>(null) }

    val albumSongs = remember(allSongs, album) {
        if (isUnknownAlbumName(album.title)) {
            allSongs.filter { isUnknownAlbumName(it.album) || it.albumId == album.id }
        } else {
            allSongs.filter { it.albumId == album.id }
        }
    }
    val hasAnyTrackNumber = remember(albumSongs) {
        albumSongs.any { it.trackNumber > 0 }
    }
    var sortOption by remember(album.id, hasAnyTrackNumber) {
        mutableStateOf(if (hasAnyTrackNumber) AlbumSortOption.TRACK_NUMBER else AlbumSortOption.TITLE_AZ)
    }

    val sortedSongs = remember(albumSongs, sortOption) {
        when (sortOption) {
            AlbumSortOption.TRACK_NUMBER -> albumSongs.sortedWith(
                compareBy<Song> { if (it.trackNumber > 0) 0 else 1 }
                    .thenBy { if (it.discNumber > 0) it.discNumber else 0 }
                    .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
            )
            AlbumSortOption.TITLE_AZ -> albumSongs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.title })
            AlbumSortOption.DURATION -> albumSongs.sortedByDescending { it.duration }
            AlbumSortOption.ARTIST -> albumSongs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.artist })
        }
    }

    val totalDurationMs = remember(albumSongs) { albumSongs.sumOf { it.duration } }
    val formattedTotalDuration = localizedTotalDuration(totalDurationMs)

    val listState = rememberLazyListState()
    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    val albumSortControl: @Composable (Boolean) -> Unit = { iconOnly ->
        Box {
            if (iconOnly) {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.action_sort),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Surface(
                    onClick = { showSortMenu = true },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.action_sort),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = sortOption.localizedLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                Text(
                    stringResource(R.string.sort_by_header),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                AlbumSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.localizedLabel(),
                                fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                color = if (sortOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            sortOption = option
                            showSortMenu = false
                        },
                        leadingIcon = {
                            if (sortOption == option) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
            }
        }
    }

    val albumCenteredHero: @Composable (Boolean, Boolean) -> Unit = { isLandscapePane, isTablet ->
        val artSize = when {
            isTablet && layout.isCompactHeight -> 208.dp
            isTablet -> 244.dp
            isLandscapePane && layout.isCompactHeight -> 124.dp
            isLandscapePane -> 148.dp
            layout.isCompactHeight -> 188.dp
            else -> 232.dp
        }
        val artCorner = when {
            isTablet -> 22.dp
            isLandscapePane -> 16.dp
            else -> 18.dp
        }
        val yearSongsText = if (album.year > 0) {
            pluralStringResource(R.plurals.album_year_songs, sortedSongs.size, album.year, sortedSongs.size)
        } else {
            pluralStringResource(R.plurals.song_count, sortedSongs.size, sortedSongs.size)
        }
        val metaSummary = if (formattedTotalDuration.isNotBlank()) {
            "$yearSongsText • $formattedTotalDuration"
        } else {
            yearSongsText
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (isLandscapePane) 4.dp else 8.dp,
                    bottom = if (isLandscapePane) 8.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(artSize),
                shape = RoundedCornerShape(artCorner),
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                AlbumArtImage(
                    album = album,
                    icon = Icons.Default.Album,
                    bucket = ArtResolutionBucket.HIGH_RES,
                    shape = RoundedCornerShape(artCorner),
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(if (isLandscapePane) 12.dp else 18.dp))

            Text(
                text = localizedAlbumName(album.title),
                style = when {
                    isTablet -> MaterialTheme.typography.headlineMedium
                    isLandscapePane -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isLandscapePane) 4.dp else 12.dp)
            )

            Spacer(modifier = Modifier.height(if (isLandscapePane) 2.dp else 4.dp))

            val canOpenArtist = onArtistClick != null &&
                album.artist.isNotBlank() &&
                !isUnknownArtistName(album.artist)
            Box(
                modifier = Modifier
                    .padding(horizontal = if (isLandscapePane) 4.dp else 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (canOpenArtist) {
                            Modifier.clickable { onArtistClick?.invoke(album.artist) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = localizedArtistName(album.artist),
                    style = if (isTablet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = metaSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isLandscapePane) 4.dp else 12.dp)
            )

            Spacer(modifier = Modifier.height(if (isLandscapePane) 12.dp else 18.dp))

            Row(
                modifier = if (isTablet) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isLandscapePane) 0.dp else 4.dp)
                },
                horizontalArrangement = Arrangement.spacedBy(
                    space = if (isLandscapePane) 8.dp else 12.dp,
                    alignment = Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (sortedSongs.isNotEmpty()) {
                            viewModel.playSong(sortedSongs.first(), sortedSongs, sourceName = album.title)
                        }
                    },
                    enabled = sortedSongs.isNotEmpty(),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (isLandscapePane) 12.dp else 18.dp,
                        vertical = if (isLandscapePane) 8.dp else 10.dp
                    ),
                    modifier = if (isTablet) {
                        Modifier.widthIn(min = 152.dp).height(44.dp)
                    } else {
                        Modifier.weight(1f).height(if (isLandscapePane) 40.dp else 44.dp)
                    }
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_play_all),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (sortedSongs.isNotEmpty()) {
                            viewModel.shuffleAll(sortedSongs, sourceName = album.title)
                        }
                    },
                    enabled = sortedSongs.isNotEmpty(),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (isLandscapePane) 12.dp else 18.dp,
                        vertical = if (isLandscapePane) 8.dp else 10.dp
                    ),
                    modifier = if (isTablet) {
                        Modifier.widthIn(min = 152.dp).height(44.dp)
                    } else {
                        Modifier.weight(1f).height(if (isLandscapePane) 40.dp else 44.dp)
                    }
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (isLandscapePane) R.string.action_shuffle_short else R.string.action_shuffle
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    val songListItems: LazyListScope.(Boolean) -> Unit = { constrainWidth ->
        items(
            items = sortedSongs,
            key = { song -> if (constrainWidth) "tablet_album_${song.id}" else song.id },
            contentType = { "album_song_item" }
        ) { song ->
            val isCurrent = currentSong?.id == song.id

            Row(
                modifier = Modifier
                    .then(
                        if (constrainWidth) Modifier.widthIn(max = layout.maxListContentWidth)
                        else Modifier
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent
                    )
                    .clickable {
                        viewModel.playSong(song, sortedSongs, sourceName = album.title)
                    }
                    .padding(vertical = 9.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrent) {
                        QueueEqualizerIndicator(
                            isPlaying = isPlaying,
                            barColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        val trackText = if (song.trackNumber > 0) "${song.trackNumber}" else NO_TRACK_NUMBER_LABEL
                        Text(
                            text = trackText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = localizedArtistName(song.artist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = song.formattedDuration,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = { selectedSongForAction = song },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (layout.isTabletOrExpanded) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(paneTopInsets())
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = paneContentPadding(
                        baseHorizontal = layout.horizontalPadding,
                        top = 10.dp,
                        bottom = bottomPadding
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "album_tablet_header", contentType = "album_header") {
                        Column(
                            modifier = Modifier
                                .widthIn(max = layout.maxListContentWidth)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = onBackClick,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.action_back),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                albumSortControl(false)
                            }

                            albumCenteredHero(false, true)

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }

                    songListItems(true)
                }

                LibraryFastScroller(
                    listState = listState,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.align(Alignment.TopEnd),
                    getBubbleText = null
                )
            }
        }
    } else if (layout.isLandscape) {
        val layoutDir = LocalLayoutDirection.current
        val rightSafeEnd = paneHorizontalInsets().asPaddingValues().calculateEndPadding(layoutDir)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(paneTopInsets())
            ) {
                Column(
                    modifier = Modifier
                        .weight(LANDSCAPE_HEADER_WEIGHT_COMPACT)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = layout.horizontalPadding,
                            end = layout.horizontalPadding,
                            top = 4.dp,
                            bottom = bottomPadding
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                        albumSortControl(true)
                    }
                    albumCenteredHero(true, false)
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                Box(
                    modifier = Modifier
                        .weight(LANDSCAPE_LIST_WEIGHT_COMPACT)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = rightSafeEnd + layout.horizontalPadding,
                            top = 8.dp,
                            bottom = bottomPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        songListItems(false)
                    }

                    LibraryFastScroller(
                        listState = listState,
                        bottomPadding = bottomPadding,
                        modifier = Modifier.align(Alignment.TopEnd),
                        getBubbleText = null
                    )
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        Box(modifier = Modifier.padding(end = 12.dp)) {
                            albumSortControl(false)
                        }
                    },
                    windowInsets = paneTopHorizontalInsets(),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = paneContentPadding(
                        baseHorizontal = layout.horizontalPadding,
                        top = 4.dp,
                        bottom = bottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "album_phone_hero", contentType = "album_header") { albumCenteredHero(false, false) }
                    item(key = "album_phone_divider", contentType = "album_divider") {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    songListItems(false)
                }

                LibraryFastScroller(
                    listState = listState,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.align(Alignment.TopEnd),
                    getBubbleText = null
                )
            }
        }
    }

    if (selectedSongForAction != null) {
        SongActionBottomSheet(
            song = selectedSongForAction!!,
            viewModel = viewModel,
            onDismissRequest = { selectedSongForAction = null },
            onNavigateToArtist = onArtistClick?.let { cb ->
                { artistName ->
                    selectedSongForAction = null
                    cb(artistName)
                }
            }
        )
    }
}
