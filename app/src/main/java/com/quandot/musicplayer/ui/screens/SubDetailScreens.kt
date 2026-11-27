/**
 * SubDetailScreens — Collection detail screens for Artist, Folder, Genre, Year, and Playlist.
 *
 * @remarks
 * All five destinations delegate layout, sorting, fast scrolling, and track action sheets to
 * [SubDetailScaffold] so portrait and landscape split-pane behaviors stay uniform across the
 * library without duplicating scaffold and scrollbar plumbing.
 *
 * @see AlbumDetailScreen
 * @see MusicLibraryScreen
 * @see LibraryActionBar
 * @see SongActionBottomSheet
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.AlbumArtImage
import com.quandot.musicplayer.ui.components.LibraryFastScroller
import com.quandot.musicplayer.ui.components.LocalFastScrollingState
import com.quandot.musicplayer.ui.components.QueueEqualizerIndicator
import com.quandot.musicplayer.ui.components.SongActionBottomSheet
import com.quandot.musicplayer.ui.components.rememberIsFastScrolling
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName
import com.quandot.musicplayer.util.isUnknownGenreName
import com.quandot.musicplayer.util.localizedGenreName
import com.quandot.musicplayer.util.localizedPlaylistName

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.*
import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.paneContentPadding
import com.quandot.musicplayer.ui.theme.paneHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopInsets
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo

// ═══ Constants ═══
private const val DEFAULT_STORAGE_PATH = "/Bộ nhớ"
private const val UNKNOWN_GENRE_KEY = "Không rõ thể loại"
private const val UNKNOWN_TAG_MARKER = "<unknown>"

private const val LANDSCAPE_HEADER_WEIGHT_EXPANDED = 0.34f
private const val LANDSCAPE_HEADER_WEIGHT_COMPACT = 0.40f
private const val LANDSCAPE_LIST_WEIGHT_EXPANDED = 0.66f
private const val LANDSCAPE_LIST_WEIGHT_COMPACT = 0.60f

private val FAVORITES_ACCENT_COLOR = Color(0xFFE91E63)

// ═══ Collection Detail Screens ═══
/**
 * Artist detail screen showing an initial avatar, summary counts, horizontal album
 * strip, and all matching tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artist: Artist,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    onNavigateToAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val allAlbums by viewModel.albums.collectAsState()

    val artistSongs = remember(allSongs, artist) {
        val targetArtist = artist.name.trim()
        allSongs.filter { it.artist.trim().equals(targetArtist, ignoreCase = true) }
    }

    val artistAlbums = remember(allAlbums, artistSongs, artist) {
        val targetArtist = artist.name.trim()
        val albumIdsFromSongs = artistSongs.mapTo(HashSet()) { it.albumId }
        allAlbums.filter { album ->
            album.id in albumIdsFromSongs || album.artist.trim().equals(targetArtist, ignoreCase = true)
        }
    }

    val displayArtistName = localizedArtistName(artist.name)

    SubDetailScaffold(
        title = displayArtistName,
        onBackClick = onBackClick,
        songs = artistSongs,
        viewModel = viewModel,
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(84.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayArtistName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.artist_summary_format,
                            pluralStringResource(R.plurals.song_count, artistSongs.size, artistSongs.size),
                            pluralStringResource(R.plurals.album_count, artistAlbums.size, artistAlbums.size)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        extraContentAboveSongs = if (artistAlbums.isNotEmpty()) {
            {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Text(
                        text = stringResource(R.string.artist_albums_count_format, artistAlbums.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        items(
                            items = artistAlbums,
                            key = { it.id },
                            contentType = { "artist_album" }
                        ) { album ->
                            Column(
                                modifier = Modifier
                                    .width(110.dp)
                                    .clickable { onNavigateToAlbum(album) }
                            ) {
                                AlbumArtImage(
                                    album = album,
                                    icon = Icons.Default.Album,
                                    bucket = ArtResolutionBucket.CARD,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(110.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = localizedAlbumName(album.title),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.song_count, album.songCount, album.songCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else null,
        modifier = modifier
    )
}

/** Folder detail screen listing all tracks contained in the selected filesystem directory. */
@Composable
fun FolderDetailScreen(
    folder: Folder,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val folderSongs = remember(allSongs, folder) {
        allSongs.filter {
            val slashIdx = it.path.lastIndexOf('/')
            val parentPath = if (slashIdx > 0) it.path.substring(0, slashIdx) else DEFAULT_STORAGE_PATH
            parentPath == folder.path
        }
    }

    SubDetailScaffold(
        title = folder.name,
        onBackClick = onBackClick,
        songs = folderSongs,
        viewModel = viewModel,
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = folder.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, folderSongs.size, folderSongs.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        modifier = modifier
    )
}

/** Genre detail screen grouping tracks by normalized genre metadata. */
@Composable
fun GenreDetailScreen(
    genre: Genre,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val genreSongs = remember(allSongs, genre) {
        allSongs.filter {
            if (isUnknownGenreName(genre.name)) {
                isUnknownGenreName(it.genre)
            } else {
                it.genre.trim().equals(genre.name, ignoreCase = true)
            }
        }
    }

    val displayGenreName = localizedGenreName(genre.name)

    SubDetailScaffold(
        title = displayGenreName,
        onBackClick = onBackClick,
        songs = genreSongs,
        viewModel = viewModel,
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayGenreName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, genreSongs.size, genreSongs.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = modifier
    )
}

/** Release-year detail screen listing tracks matching the selected year bucket. */
@Composable
fun YearDetailScreen(
    yearGroup: YearGroup,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSongs by viewModel.allSongs.collectAsState()
    val yearSongs = remember(allSongs, yearGroup) {
        allSongs.filter { it.year == yearGroup.year }
    }
    val yearTitle = if (yearGroup.year > 0) {
        stringResource(R.string.year_title_format, yearGroup.year)
    } else {
        stringResource(R.string.year_unknown)
    }

    SubDetailScaffold(
        title = yearTitle,
        onBackClick = onBackClick,
        songs = yearSongs,
        viewModel = viewModel,
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = yearTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, yearSongs.size, yearSongs.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Playlist detail screen supporting track removal and, for user-created playlists,
 * playlist deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allSongs by viewModel.allSongs.collectAsState()
    val favoriteSongIds by viewModel.favoriteSongIds.collectAsState()

    var playlistSongIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.id, playlist.songCount, favoriteSongIds, allSongs) {
        playlistSongIds = viewModel.getSongsForPlaylist(playlist.id).map { it.id }
    }

    val songMap = remember(allSongs) { allSongs.associateBy { it.id } }
    val playlistSongs = remember(playlistSongIds, songMap) {
        playlistSongIds.mapNotNull { songMap[it] }
    }

    val displayPlaylistName = localizedPlaylistName(playlist.name, playlist.isSystem)

    SubDetailScaffold(
        title = displayPlaylistName,
        onBackClick = onBackClick,
        songs = playlistSongs,
        viewModel = viewModel,
        headerContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (playlist.isSystem) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFFF512F), Color(0xFFDD2476))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayPlaylistName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(if (playlist.isSystem) R.string.playlist_fav_desc else R.string.playlist_custom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, playlistSongs.size, playlistSongs.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (playlist.isSystem) FAVORITES_ACCENT_COLOR else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        topBarActions = {
            if (!playlist.isSystem) {
                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        onRemoveSong = { songToRemove ->
            viewModel.removeSongFromPlaylist(playlist.id, songToRemove.id)
            playlistSongIds = playlistSongIds.filter { it != songToRemove.id }
            Toast.makeText(context, context.getString(R.string.playlist_removed_song_toast), Toast.LENGTH_SHORT).show()
        },
        modifier = modifier
    )

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.playlist_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.playlist_delete_msg, playlist.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        showDeleteConfirmDialog = false
                        onBackClick()
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ═══ Shared Sub-Detail Scaffold ═══
/**
 * Shared adaptive scaffold providing Material 3 top-bar navigation, split-pane landscape
 * support, sort controls, system scrollbar, and per-track action sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubDetailScaffold(
    title: String,
    onBackClick: () -> Unit,
    songs: List<Song>,
    viewModel: MusicViewModel,
    headerContent: @Composable () -> Unit,
    extraContentAboveSongs: (@Composable () -> Unit)? = null,
    topBarActions: (@Composable RowScope.() -> Unit)? = null,
    onRemoveSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    var sortOption by remember { mutableStateOf(SongSortOption.TITLE_AZ) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedSongForAction by remember { mutableStateOf<Song?>(null) }

    val sortedSongs = remember(songs, sortOption) {
        when (sortOption) {
            SongSortOption.TITLE_AZ -> songs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.title })
            SongSortOption.DATE_ADDED -> songs.sortedByDescending { it.dateAdded }
            SongSortOption.DURATION -> songs.sortedByDescending { it.duration }
            SongSortOption.ARTIST -> songs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.artist })
            SongSortOption.TRACK_NUMBER -> songs.sortedWith(
                compareBy<Song> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
            )
        }
    }

    val totalDurationMs = remember(songs) { songs.sumOf { it.duration } }
    val formattedTotalDuration = localizedTotalDuration(totalDurationMs)

    val listState = rememberLazyListState()
    val isFastScrolling = rememberIsFastScrolling(listState)
    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    val subDetailActionBar: @Composable () -> Unit = {
        LibraryActionBar(
            onPlayAll = {
                if (sortedSongs.isNotEmpty()) {
                    viewModel.playSong(sortedSongs.first(), sortedSongs, sourceName = title)
                }
            },
            onShuffleAll = {
                if (sortedSongs.isNotEmpty()) {
                    viewModel.shuffleAll(sortedSongs, sourceName = title)
                }
            },
            isPlayEnabled = sortedSongs.isNotEmpty(),
            summaryText = formattedTotalDuration,
            sortMenuExpanded = showSortMenu,
            onSortMenuExpandedChange = { showSortMenu = it },
            horizontalPadding = 0.dp,
            sortMenuContent = {
                Text(
                    stringResource(R.string.sort_by_header),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                SongSortOption.entries.forEach { option ->
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
        )
    }

    val renderSongItems: LazyListScope.() -> Unit = {
        itemsIndexed(
            items = sortedSongs,
            key = { _, song -> song.id },
            contentType = { _, _ -> "subdetail_song_item" }
        ) { _, song ->
            val isCurrent = currentSong?.id == song.id

            Row(
                modifier = Modifier
                    .then(
                        if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                        else Modifier
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent
                    )
                    .clickable {
                        viewModel.playSong(song, sortedSongs, sourceName = title)
                    }
                    .padding(vertical = 7.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AlbumArtImage(
                        song = song,
                        icon = Icons.Default.MusicNote,
                        bucket = ArtResolutionBucket.THUMBNAIL,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            QueueEqualizerIndicator(
                                isPlaying = isPlaying,
                                barColor = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 15.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${localizedArtistName(song.artist)} • ${localizedAlbumName(song.album)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = song.formattedDuration,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                IconButton(onClick = { selectedSongForAction = song }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalFastScrollingState provides isFastScrolling) {
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
                    item(key = "subdetail_tablet_header", contentType = "subdetail_header") {
                        Column(
                            modifier = Modifier
                                .widthIn(max = layout.maxListContentWidth)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (topBarActions != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        content = topBarActions
                                    )
                                }
                            }

                            headerContent()
                            if (extraContentAboveSongs != null) {
                                extraContentAboveSongs()
                            }
                            subDetailActionBar()
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                    renderSongItems()
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
                        .weight(if (layout.isTabletOrExpanded) LANDSCAPE_HEADER_WEIGHT_EXPANDED else LANDSCAPE_HEADER_WEIGHT_COMPACT)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = layout.horizontalPadding,
                            end = layout.horizontalPadding,
                            top = 4.dp,
                            bottom = bottomPadding
                        )
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
                        if (topBarActions != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                content = topBarActions
                            )
                        }
                    }

                    headerContent()
                    Spacer(modifier = Modifier.height(6.dp))
                    subDetailActionBar()
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                Box(
                    modifier = Modifier
                        .weight(if (layout.isTabletOrExpanded) LANDSCAPE_LIST_WEIGHT_EXPANDED else LANDSCAPE_LIST_WEIGHT_COMPACT)
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
                        if (extraContentAboveSongs != null) {
                            item(key = "subdetail_landscape_extra", contentType = "subdetail_extra") {
                                extraContentAboveSongs()
                            }
                        }
                        renderSongItems()
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
                        topBarActions?.invoke(this)
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
                    item(key = "subdetail_phone_header", contentType = "subdetail_header") { headerContent() }
                    if (extraContentAboveSongs != null) {
                        item(key = "subdetail_phone_extra", contentType = "subdetail_extra") { extraContentAboveSongs() }
                    }
                    item(key = "subdetail_phone_actions", contentType = "subdetail_actions") { subDetailActionBar() }
                    item(key = "subdetail_phone_divider", contentType = "subdetail_divider") {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    renderSongItems()
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
    }

    if (selectedSongForAction != null) {
        SongActionBottomSheet(
            song = selectedSongForAction!!,
            viewModel = viewModel,
            onDismissRequest = { selectedSongForAction = null },
            onRemoveFromPlaylist = if (onRemoveSong != null) {
                { onRemoveSong(selectedSongForAction!!) }
            } else null
        )
    }
}
