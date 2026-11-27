/**
 * MusicLibraryScreen — Eight-tab media browser with unified search, sorting, and fast scrolling.
 *
 * @remarks
 * All eight tab subtrees remain composed inside [KeepAliveSlot] containers so tab switching is
 * instantaneous and preserves per-tab scroll state and fast-scroller caches. Duration totals
 * are pre-indexed in a single pass over `allSongs` to avoid repeated linear scans across tabs.
 *
 * @see MainScreen
 * @see MusicViewModel
 * @see LibraryFastScroller
 * @see SongActionBottomSheet
 * @see M3KeepAliveSlot
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.AlbumArtImage
import com.quandot.musicplayer.ui.components.CreatePlaylistDialog
import com.quandot.musicplayer.ui.components.LibraryFastScroller
import com.quandot.musicplayer.ui.components.LocalFastScrollingState
import com.quandot.musicplayer.ui.components.QueueEqualizerIndicator
import com.quandot.musicplayer.ui.components.SongActionBottomSheet
import com.quandot.musicplayer.ui.components.rememberIsFastScrolling
import com.quandot.musicplayer.util.isUnknownAlbumName
import com.quandot.musicplayer.util.isUnknownArtistName
import com.quandot.musicplayer.util.isUnknownGenreName
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName
import com.quandot.musicplayer.util.localizedGenreName
import com.quandot.musicplayer.util.localizedPlaylistName

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.*
import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.LocalScreenVisible
import com.quandot.musicplayer.ui.theme.M3KeepAliveSlot
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.PredictiveBackEffect
import com.quandot.musicplayer.ui.theme.paneContentPadding
import com.quandot.musicplayer.ui.theme.paneHorizontalInsets
import com.quandot.musicplayer.ui.theme.paneTopInsets
import com.quandot.musicplayer.ui.theme.predictiveBackInline
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.rememberPredictiveBackState

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_SAVED_LIB_TAB = "saved_lib_tab"
private const val PREF_SAVED_LIB_TAB_ROW_INDEX = "saved_lib_tab_row_index"
private const val PREF_SAVED_LIB_TAB_ROW_OFFSET = "saved_lib_tab_row_offset"
private const val PREF_SAVED_FOLDER_BROWSE_PATH = "saved_folder_browse_path"

private const val TAB_SONGS = 0
private const val TAB_ALBUMS = 1
private const val TAB_ARTISTS = 2
private const val TAB_GENRES = 3
private const val TAB_YEARS = 4
private const val TAB_FOLDERS = 5
private const val TAB_FILES = 6
private const val TAB_PLAYLISTS = 7

private const val DEFAULT_QUEUE_SOURCE = "Mặc định"
private const val UNKNOWN_ARTIST_KEY = "Nghệ sĩ không xác định"
private const val UNKNOWN_GENRE_KEY = "Thể loại không xác định"
private const val DEFAULT_INDEX_BUBBLE = "#"

private const val SEARCH_IME_FOCUS_DELAY_MS = 160L
private const val SEARCH_DEBOUNCE_MS = 80L
private const val SONGS_CROSSFADE_DURATION_MS = 100
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

private val FAVORITES_GRADIENT_START = Color(0xFFFF512F)
private val FAVORITES_GRADIENT_END = Color(0xFFDD2476)

// ═══ Sorting & Localization Helpers ═══
enum class CommonSortMode(val displayName: String) {
    NAME_AZ("Tên (A-Z)"),
    NAME_ZA("Tên (Z-A)"),
    COUNT_DESC("Nhiều bài hát nhất"),
    DEFAULT("Mặc định")
}

private enum class FileBrowserSortMode {
    DEFAULT,
    NAME_AZ,
    NAME_ZA,
    DATE_ADDED,
    DURATION
}

@Composable
internal fun localizedTotalDuration(totalDurationMs: Long): String {
    val totalMinutes = (totalDurationMs / MS_PER_MINUTE).coerceAtLeast(0L)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0L) {
        stringResource(R.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.duration_minutes, minutes)
    }
}

@Composable
internal fun SortOption.localizedLabel(): String = when (this) {
    SortOption.TITLE_AZ -> stringResource(R.string.sort_option_title_az)
    SortOption.DATE_ADDED -> stringResource(R.string.sort_option_date_added)
    SortOption.DURATION -> stringResource(R.string.sort_option_duration)
    SortOption.ARTIST -> stringResource(R.string.sort_option_artist)
}

@Composable
internal fun AlbumSortOption.localizedLabel(): String = when (this) {
    AlbumSortOption.TRACK_NUMBER -> stringResource(R.string.sort_option_track_number)
    AlbumSortOption.TITLE_AZ -> stringResource(R.string.sort_option_title_az)
    AlbumSortOption.DURATION -> stringResource(R.string.sort_option_duration)
    AlbumSortOption.ARTIST -> stringResource(R.string.sort_option_artist)
}

@Composable
internal fun CommonSortMode.localizedLabel(): String = when (this) {
    CommonSortMode.NAME_AZ -> stringResource(R.string.common_sort_name_az)
    CommonSortMode.NAME_ZA -> stringResource(R.string.common_sort_name_za)
    CommonSortMode.COUNT_DESC -> stringResource(R.string.common_sort_count_desc)
    CommonSortMode.DEFAULT -> stringResource(R.string.common_sort_default)
}

@Composable
private fun FileBrowserSortMode.localizedLabel(): String = when (this) {
    FileBrowserSortMode.DEFAULT -> stringResource(R.string.common_sort_default)
    FileBrowserSortMode.NAME_AZ -> stringResource(R.string.common_sort_name_az)
    FileBrowserSortMode.NAME_ZA -> stringResource(R.string.common_sort_name_za)
    FileBrowserSortMode.DATE_ADDED -> stringResource(R.string.sort_option_date_added)
    FileBrowserSortMode.DURATION -> stringResource(R.string.sort_option_duration)
}

@Composable
internal fun SongSortOption.localizedLabel(): String = when (this) {
    SongSortOption.TITLE_AZ -> stringResource(R.string.sort_option_title_az)
    SongSortOption.DATE_ADDED -> stringResource(R.string.sort_option_date_added)
    SongSortOption.DURATION -> stringResource(R.string.sort_option_duration)
    SongSortOption.ARTIST -> stringResource(R.string.sort_option_artist)
    SongSortOption.TRACK_NUMBER -> stringResource(R.string.sort_option_track_number)
}

private data class LibraryProcessedData(
    val filteredSongs: List<Song> = emptyList(),
    val sortedSongs: List<Song> = emptyList(),
    val filteredAlbums: List<Album> = emptyList(),
    val filteredArtists: List<Artist> = emptyList(),
    val filteredGenres: List<Genre> = emptyList(),
    val filteredYears: List<YearGroup> = emptyList(),
    val filteredFolders: List<Folder> = emptyList(),
    val filteredPlaylists: List<Playlist> = emptyList(),
    val songsDurationMs: Long = 0L
)

/**
 * Keeps a tab's Compose subtree alive in memory so switching tabs avoids recreating
 * lazy list states and fast-scroller index caches, while propagating [LocalScreenVisible]
 * so off-screen animated indicators pause automatically.
 */
@Composable
internal fun KeepAliveSlot(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    val parentVisible = LocalScreenVisible.current
    M3KeepAliveSlot(visible = visible) {
        CompositionLocalProvider(LocalScreenVisible provides (parentVisible && visible)) {
            content()
        }
    }
}

// ═══ Music Library Screen ═══
/**
 * Main library browser hosting eight tabs (Songs, Albums, Artists, Genres, Years,
 * Folders, Files, Playlists), unified search filtering, and per-tab action bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(
    viewModel: MusicViewModel,
    onSongSelected: (Song) -> Unit,
    onAlbumClick: (Album) -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    onFolderClick: (Folder) -> Unit = {},
    onGenreClick: (Genre) -> Unit = {},
    onYearClick: (YearGroup) -> Unit = {},
    onPlaylistClick: (Playlist) -> Unit = {},
    initialTab: Int = 0,
    requestedTab: Int? = null,
    onRequestedTabHandled: () -> Unit = {},
    isActiveOnTop: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var selectedTabIndex by rememberSaveable {
        mutableIntStateOf(prefs.getInt(PREF_SAVED_LIB_TAB, initialTab).coerceIn(TAB_SONGS, TAB_PLAYLISTS))
    }
    // Bitmask of lazily-mounted library tabs so cold launch only composes the active tab
    var visitedTabMask by rememberSaveable { mutableIntStateOf(1 shl selectedTabIndex) }
    val selectTab: (Int) -> Unit = { tab ->
        selectedTabIndex = tab
        visitedTabMask = visitedTabMask or (1 shl tab)
    }
    val isGridView by viewModel.isLibraryGridView.collectAsState()
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var selectedSongForAction by remember { mutableStateOf<Song?>(null) }

    var albumSortMode by rememberSaveable { mutableStateOf(CommonSortMode.NAME_AZ) }
    var artistSortMode by rememberSaveable { mutableStateOf(CommonSortMode.NAME_AZ) }
    var genreSortMode by rememberSaveable { mutableStateOf(CommonSortMode.NAME_AZ) }
    var yearSortMode by rememberSaveable { mutableStateOf(CommonSortMode.DEFAULT) }
    var folderSortMode by rememberSaveable { mutableStateOf(CommonSortMode.NAME_AZ) }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            selectedTabIndex = requestedTab
            visitedTabMask = visitedTabMask or (1 shl requestedTab)
            onRequestedTabHandled()
        }
    }

    val lastSavedTabRef = remember { intArrayOf(Int.MIN_VALUE) }
    SideEffect {
        if (lastSavedTabRef[0] != selectedTabIndex) {
            lastSavedTabRef[0] = selectedTabIndex
            prefs.edit().putInt(PREF_SAVED_LIB_TAB, selectedTabIndex).apply()
        }
    }

    // Hoist scroll states so switching tabs preserves scroll offsets; persist only when scrolling settles
    val tabsRowListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = prefs.getInt(PREF_SAVED_LIB_TAB_ROW_INDEX, 0),
            firstVisibleItemScrollOffset = prefs.getInt(PREF_SAVED_LIB_TAB_ROW_OFFSET, 0)
        )
    }
    LaunchedEffect(tabsRowListState) {
        snapshotFlow { tabsRowListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    prefs.edit()
                        .putInt(PREF_SAVED_LIB_TAB_ROW_INDEX, tabsRowListState.firstVisibleItemIndex)
                        .putInt(PREF_SAVED_LIB_TAB_ROW_OFFSET, tabsRowListState.firstVisibleItemScrollOffset)
                        .apply()
                }
            }
    }
    val songsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val songsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val albumsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val artistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val genresGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val yearsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val foldersListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val playlistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val allSongs by viewModel.allSongs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val genres by viewModel.genres.collectAsState()
    val yearGroups by viewModel.yearGroups.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    val unknownAlbumLabel = stringResource(R.string.unknown_album)
    val unknownArtistLabel = stringResource(R.string.unknown_artist)
    val unknownGenreLabel = stringResource(R.string.unknown_genre)
    val unknownYearLabel = stringResource(R.string.year_unknown)

    // Debounce non-empty search input and run multi-tab filtering + natural sorting off the Main thread
    var debouncedSearchQuery by remember { mutableStateOf(searchQuery) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            delay(SEARCH_DEBOUNCE_MS)
            debouncedSearchQuery = searchQuery
        }
    }

    var processedLibrary by remember { mutableStateOf(LibraryProcessedData()) }
    LaunchedEffect(
        allSongs,
        albums,
        artists,
        genres,
        yearGroups,
        folders,
        playlists,
        debouncedSearchQuery,
        sortOption,
        albumSortMode,
        artistSortMode,
        genreSortMode,
        yearSortMode,
        folderSortMode,
        unknownAlbumLabel,
        unknownArtistLabel,
        unknownGenreLabel,
        unknownYearLabel
    ) {
        val query = debouncedSearchQuery
        val computed = withContext(Dispatchers.Default) {
            val filteredSongs = if (query.isBlank()) allSongs else allSongs.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
            }
            val sortedSongs = when (sortOption) {
                SortOption.TITLE_AZ -> filteredSongs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.title })
                SortOption.DATE_ADDED -> filteredSongs.sortedByDescending { it.dateAdded }
                SortOption.DURATION -> filteredSongs.sortedByDescending { it.duration }
                SortOption.ARTIST -> filteredSongs.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.artist })
            }
            val albumList = if (query.isBlank()) albums else albums.filter {
                val titleLabel = if (isUnknownAlbumName(it.title)) unknownAlbumLabel else it.title
                val artistLabel = if (isUnknownArtistName(it.artist)) unknownArtistLabel else it.artist
                titleLabel.contains(query, ignoreCase = true) ||
                    artistLabel.contains(query, ignoreCase = true)
            }
            val filteredAlbums = when (albumSortMode) {
                CommonSortMode.NAME_AZ, CommonSortMode.DEFAULT -> albumList.sortedWith(
                    compareBy<Album> { if (isUnknownAlbumName(it.title)) 0 else 1 }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
                )
                CommonSortMode.NAME_ZA -> albumList.sortedWith(
                    compareBy<Album> { if (isUnknownAlbumName(it.title)) 0 else 1 }
                        .thenByDescending(NATURAL_TEXT_COMPARATOR) { it.title }
                )
                CommonSortMode.COUNT_DESC -> albumList.sortedWith(
                    compareBy<Album> { if (isUnknownAlbumName(it.title)) 0 else 1 }
                        .thenByDescending { it.songCount }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
                )
            }
            val artistList = if (query.isBlank()) artists else artists.filter {
                val artistLabel = if (isUnknownArtistName(it.name)) unknownArtistLabel else it.name
                artistLabel.contains(query, ignoreCase = true)
            }
            val filteredArtists = when (artistSortMode) {
                CommonSortMode.NAME_AZ, CommonSortMode.DEFAULT -> artistList.sortedWith(
                    compareBy<Artist> { if (isUnknownArtistName(it.name)) 0 else 1 }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.NAME_ZA -> artistList.sortedWith(
                    compareBy<Artist> { if (isUnknownArtistName(it.name)) 0 else 1 }
                        .thenByDescending(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.COUNT_DESC -> artistList.sortedWith(
                    compareBy<Artist> { if (isUnknownArtistName(it.name)) 0 else 1 }
                        .thenByDescending { it.songCount }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
            }
            val genreList = if (query.isBlank()) genres else genres.filter {
                val genreLabel = if (isUnknownGenreName(it.name)) unknownGenreLabel else it.name
                genreLabel.contains(query, ignoreCase = true)
            }
            val filteredGenres = when (genreSortMode) {
                CommonSortMode.NAME_AZ, CommonSortMode.DEFAULT -> genreList.sortedWith(
                    compareBy<Genre> { if (isUnknownGenreName(it.name)) 0 else 1 }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.NAME_ZA -> genreList.sortedWith(
                    compareBy<Genre> { if (isUnknownGenreName(it.name)) 0 else 1 }
                        .thenByDescending(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.COUNT_DESC -> genreList.sortedWith(
                    compareBy<Genre> { if (isUnknownGenreName(it.name)) 0 else 1 }
                        .thenByDescending { it.songCount }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
            }
            val yearList = if (query.isBlank()) yearGroups else yearGroups.filter {
                val yStr = if (it.year > 0) it.year.toString() else unknownYearLabel
                yStr.contains(query, ignoreCase = true)
            }
            val filteredYears = when (yearSortMode) {
                CommonSortMode.NAME_AZ -> yearList.sortedWith(
                    compareBy<YearGroup> { if (it.year <= 0) 0 else 1 }
                        .thenBy { it.year }
                )
                CommonSortMode.NAME_ZA -> yearList.sortedWith(
                    compareBy<YearGroup> { if (it.year <= 0) 0 else 1 }
                        .thenByDescending { it.year }
                )
                CommonSortMode.COUNT_DESC -> yearList.sortedWith(
                    compareBy<YearGroup> { if (it.year <= 0) 0 else 1 }
                        .thenByDescending { it.songCount }
                        .thenByDescending { it.year }
                )
                CommonSortMode.DEFAULT -> yearList.sortedWith(
                    compareBy<YearGroup> { if (it.year <= 0) 0 else 1 }
                        .thenByDescending { it.year }
                )
            }
            val folderList = if (query.isBlank()) folders else folders.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.path.contains(query, ignoreCase = true)
            }
            val filteredFolders = when (folderSortMode) {
                CommonSortMode.NAME_ZA -> folderList.sortedWith(
                    compareByDescending<Folder, String>(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.COUNT_DESC -> folderList.sortedWith(
                    compareByDescending<Folder> { it.songCount }
                        .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
                CommonSortMode.NAME_AZ, CommonSortMode.DEFAULT -> folderList.sortedWith(
                    compareBy(NATURAL_TEXT_COMPARATOR) { it.name }
                )
            }
            val filteredPlaylists = if (query.isBlank()) playlists else playlists.filter {
                it.name.contains(query, ignoreCase = true)
            }
            val songsDurationMs = filteredSongs.sumOf { it.duration }
            LibraryProcessedData(
                filteredSongs = filteredSongs,
                sortedSongs = sortedSongs,
                filteredAlbums = filteredAlbums,
                filteredArtists = filteredArtists,
                filteredGenres = filteredGenres,
                filteredYears = filteredYears,
                filteredFolders = filteredFolders,
                filteredPlaylists = filteredPlaylists,
                songsDurationMs = songsDurationMs
            )
        }
        processedLibrary = computed
    }

    val filteredSongs = processedLibrary.filteredSongs
    val sortedSongs = processedLibrary.sortedSongs
    val filteredAlbums = processedLibrary.filteredAlbums
    val filteredArtists = processedLibrary.filteredArtists
    val filteredGenres = processedLibrary.filteredGenres
    val filteredYears = processedLibrary.filteredYears
    val filteredFolders = processedLibrary.filteredFolders
    val filteredPlaylists = processedLibrary.filteredPlaylists
    val songsDurationMs = processedLibrary.songsDurationMs

    val tabs = listOf(
        stringResource(R.string.tab_songs_count, sortedSongs.size),
        stringResource(R.string.tab_albums_count, filteredAlbums.size),
        stringResource(R.string.tab_artists_count, filteredArtists.size),
        stringResource(R.string.tab_genres_count, filteredGenres.size),
        stringResource(R.string.tab_years_count, filteredYears.size),
        stringResource(R.string.tab_folders_count, filteredFolders.size),
        stringResource(R.string.tab_files),
        stringResource(R.string.tab_playlists_count, filteredPlaylists.size)
    )

    val searchFocusRequester = remember { FocusRequester() }
    val searchBackState = rememberPredictiveBackState()
    PredictiveBackEffect(
        enabled = isSearchActive && isActiveOnTop,
        state = searchBackState,
        onBack = {
            isSearchActive = false
            viewModel.updateSearchQuery("")
        }
    )
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchBackState.reset()
            // Defer IME focus slightly so the pill morph completes on GPU before keyboard resize begins
            delay(SEARCH_IME_FOCUS_DELAY_MS)
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Throwable) {
            }
        } else {
            delay(120L)
            searchBackState.reset()
        }
    }

    val layout = rememberAdaptiveLayoutInfo()

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(paneTopInsets())
    ) {
        // Fixed-height header/search morph prevents vertical list relayout jitter
        Spacer(modifier = Modifier.height(if (layout.isLandscape) 4.dp else 8.dp))
        AnimatedContent(
            targetState = isSearchActive,
            transitionSpec = { M3Motion.searchHeaderTransform().using(M3Motion.NoClipSizeTransform) },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(paneHorizontalInsets())
                .padding(horizontal = layout.horizontalPadding)
                .height(if (layout.isLandscape) 48.dp else 54.dp),
            label = "LibrarySearchBarMorph"
        ) { searching ->
            if (!searching) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.library_title),
                        style = if (layout.isLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (layout.isLandscape) {
                        LibraryTabsRow(
                            tabs = tabs,
                            selectedTabIndex = selectedTabIndex,
                            onSelectTab = selectTab,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            listState = tabsRowListState,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selectedTabIndex == TAB_SONGS) {
                            IconButton(
                                onClick = { viewModel.setLibraryGridView(!isGridView) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (isGridView) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    contentColor = if (isGridView) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                    contentDescription = stringResource(R.string.library_toggle_layout)
                                )
                            }
                        }

                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .then(
                                if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                                else Modifier
                            )
                            .fillMaxWidth()
                            .height(if (layout.isLandscape) 46.dp else 50.dp)
                            .predictiveBackInline(searchBackState)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    isSearchActive = false
                                    viewModel.updateSearchQuery("")
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.library_search_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.updateSearchQuery(it) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.action_clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!layout.isLandscape || isSearchActive) {
            Spacer(modifier = Modifier.height(if (layout.isLandscape) 4.dp else 6.dp))

            LibraryTabsRow(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onSelectTab = selectTab,
                contentPadding = paneContentPadding(
                    baseHorizontal = layout.horizontalPadding,
                    top = if (layout.isLandscape) 2.dp else 4.dp,
                    bottom = if (layout.isLandscape) 2.dp else 4.dp
                ),
                listState = tabsRowListState
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Lazily mount each tab on first visit and keep visited tabs alive for zero-latency switching
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { clip = true }
        ) {
            if ((visitedTabMask and (1 shl TAB_SONGS)) != 0 || selectedTabIndex == TAB_SONGS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_SONGS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LibraryActionBar(
                        onPlayAll = { viewModel.playAll(sortedSongs, sourceName = DEFAULT_QUEUE_SOURCE) },
                        onShuffleAll = { viewModel.shuffleAll(sortedSongs, sourceName = DEFAULT_QUEUE_SOURCE) },
                        isPlayEnabled = sortedSongs.isNotEmpty(),
                        summaryText = localizedTotalDuration(songsDurationMs),
                        constrainMaxWidth = !isGridView,
                        sortMenuContent = { closeMenu ->
                            Text(
                                stringResource(R.string.sort_songs_header),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.localizedLabel(),
                                            fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sortOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = {
                                        if (sortOption == option) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateSortOption(option)
                                        closeMenu()
                                    }
                                )
                            }
                        }
                    )

                    Crossfade(
                        targetState = isGridView,
                        animationSpec = tween(durationMillis = SONGS_CROSSFADE_DURATION_MS, easing = M3Motion.Standard),
                        modifier = Modifier.fillMaxSize(),
                        label = "SongsLayoutCrossfade"
                    ) { gridMode ->
                        if (gridMode) {
                            SongsGridView(
                                songs = sortedSongs,
                                onSongClick = { song -> viewModel.playSong(song, sortedSongs, sourceName = DEFAULT_QUEUE_SOURCE) },
                                onSongMoreClick = { song -> selectedSongForAction = song },
                                currentSongId = currentSong?.id,
                                isPlaying = isPlaying,
                                isSearching = searchQuery.isNotBlank(),
                                gridState = songsGridState
                            )
                        } else {
                            SongsListView(
                                songs = sortedSongs,
                                onSongClick = { song -> viewModel.playSong(song, sortedSongs, sourceName = DEFAULT_QUEUE_SOURCE) },
                                onSongMoreClick = { song -> selectedSongForAction = song },
                                currentSongId = currentSong?.id,
                                isPlaying = isPlaying,
                                isSearching = searchQuery.isNotBlank(),
                                listState = songsListState
                            )
                        }
                    }
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_ALBUMS)) != 0 || selectedTabIndex == TAB_ALBUMS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_ALBUMS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val headerTitle = stringResource(R.string.sort_albums_header)
                    LibraryActionBar(
                        onPlayAll = {},
                        onShuffleAll = {},
                        showPlaybackActions = false,
                        summaryText = "",
                        sortMenuContent = { closeMenu ->
                            CommonSortMenuItems(
                                headerTitle = headerTitle,
                                currentMode = albumSortMode,
                                onSelectMode = {
                                    albumSortMode = it
                                    closeMenu()
                                }
                            )
                        }
                    )
                    AlbumsGridView(
                        albums = filteredAlbums,
                        onAlbumClick = onAlbumClick,
                        gridState = albumsGridState
                    )
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_ARTISTS)) != 0 || selectedTabIndex == TAB_ARTISTS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_ARTISTS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val headerTitle = stringResource(R.string.sort_artists_header)
                    LibraryActionBar(
                        onPlayAll = {},
                        onShuffleAll = {},
                        showPlaybackActions = false,
                        summaryText = "",
                        constrainMaxWidth = true,
                        sortMenuContent = { closeMenu ->
                            CommonSortMenuItems(
                                headerTitle = headerTitle,
                                currentMode = artistSortMode,
                                onSelectMode = {
                                    artistSortMode = it
                                    closeMenu()
                                }
                            )
                        }
                    )
                    ArtistsListView(
                        artists = filteredArtists,
                        onArtistClick = onArtistClick,
                        listState = artistsListState
                    )
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_GENRES)) != 0 || selectedTabIndex == TAB_GENRES) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_GENRES) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val headerTitle = stringResource(R.string.sort_genres_header)
                    LibraryActionBar(
                        onPlayAll = {},
                        onShuffleAll = {},
                        showPlaybackActions = false,
                        summaryText = "",
                        sortMenuContent = { closeMenu ->
                            CommonSortMenuItems(
                                headerTitle = headerTitle,
                                currentMode = genreSortMode,
                                onSelectMode = {
                                    genreSortMode = it
                                    closeMenu()
                                }
                            )
                        }
                    )
                    GenresGridView(
                        genres = filteredGenres,
                        onGenreClick = onGenreClick,
                        gridState = genresGridState
                    )
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_YEARS)) != 0 || selectedTabIndex == TAB_YEARS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_YEARS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val headerTitle = stringResource(R.string.sort_years_header)
                    LibraryActionBar(
                        onPlayAll = {},
                        onShuffleAll = {},
                        showPlaybackActions = false,
                        summaryText = "",
                        sortMenuContent = { closeMenu ->
                            CommonSortMenuItems(
                                headerTitle = headerTitle,
                                currentMode = yearSortMode,
                                onSelectMode = {
                                    yearSortMode = it
                                    closeMenu()
                                }
                            )
                        }
                    )
                    YearsGridView(
                        years = filteredYears,
                        onYearClick = onYearClick,
                        gridState = yearsGridState
                    )
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_FOLDERS)) != 0 || selectedTabIndex == TAB_FOLDERS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_FOLDERS) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val headerTitle = stringResource(R.string.sort_folders_header)
                    LibraryActionBar(
                        onPlayAll = {},
                        onShuffleAll = {},
                        showPlaybackActions = false,
                        summaryText = "",
                        constrainMaxWidth = true,
                        sortMenuContent = { closeMenu ->
                            CommonSortMenuItems(
                                headerTitle = headerTitle,
                                currentMode = folderSortMode,
                                onSelectMode = {
                                    folderSortMode = it
                                    closeMenu()
                                }
                            )
                        }
                    )
                    MusicFoldersListView(
                        folders = filteredFolders,
                        onFolderClick = onFolderClick,
                        listState = foldersListState
                    )
                }
            }
            }

            if ((visitedTabMask and (1 shl TAB_FILES)) != 0 || selectedTabIndex == TAB_FILES) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_FILES) {
                FoldersListView(
                    folders = filteredFolders,
                    songs = filteredSongs,
                    onFolderClick = onFolderClick,
                    onSongClick = { song, folderSongs, folderName ->
                        viewModel.playSong(song, folderSongs, sourceName = folderName)
                    },
                    onSongMoreClick = { selectedSongForAction = it },
                    onStorageMountChanged = { viewModel.triggerSilentIncrementalSync() },
                    currentSongId = currentSong?.id,
                    isPlaying = isPlaying,
                    isActiveOnTop = isActiveOnTop && selectedTabIndex == TAB_FILES
                )
            }
            }

            if ((visitedTabMask and (1 shl TAB_PLAYLISTS)) != 0 || selectedTabIndex == TAB_PLAYLISTS) {
            KeepAliveSlot(visible = selectedTabIndex == TAB_PLAYLISTS) {
                PlaylistsListView(
                    playlists = filteredPlaylists,
                    onPlaylistClick = onPlaylistClick,
                    viewModel = viewModel,
                    listState = playlistsListState
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
            onNavigateToAlbum = { albumId ->
                albums.find { it.id == albumId }?.let { onAlbumClick(it) }
            },
            onNavigateToArtist = { artistName ->
                artists.find { it.name.equals(artistName, ignoreCase = true) }?.let { onArtistClick(it) }
            }
        )
    }
}

// ═══ Library Controls & Tabs ═══
@Composable
private fun LibraryTabsRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding
    ) {
        itemsIndexed(
            items = tabs,
            key = { index, _ -> index },
            contentType = { _, _ -> "lib_tab" }
        ) { index, title ->
            val isSelected = selectedTabIndex == index
            FilterChip(
                selected = isSelected,
                onClick = { onSelectTab(index) },
                label = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = null
            )
        }
    }
}

/**
 * Action bar displaying a summary label on the left and Play All / Shuffle / Sort controls
 * on the right, shared by library tabs and detail screens.
 */
@Composable
fun LibraryActionBar(
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    isPlayEnabled: Boolean = true,
    showPlaybackActions: Boolean = true,
    showSort: Boolean = true,
    summaryText: String = "",
    sortMenuExpanded: Boolean? = null,
    onSortMenuExpandedChange: ((Boolean) -> Unit)? = null,
    horizontalPadding: Dp? = null,
    constrainMaxWidth: Boolean = false,
    sortMenuContent: (@Composable ColumnScope.(closeMenu: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val layout = rememberAdaptiveLayoutInfo()
    val resolvedPadding = horizontalPadding ?: layout.horizontalPadding
    val applyEdgeInsets = horizontalPadding == null
    var internalShowMenu by remember { mutableStateOf(false) }
    val expanded = sortMenuExpanded ?: internalShowMenu
    val setExpanded: (Boolean) -> Unit = onSortMenuExpandedChange ?: { internalShowMenu = it }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (applyEdgeInsets) Modifier.windowInsetsPadding(paneHorizontalInsets()) else Modifier)
            .padding(horizontal = resolvedPadding, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (constrainMaxWidth && layout.isTabletOrExpanded) {
                        Modifier.widthIn(max = layout.maxListContentWidth)
                    } else {
                        Modifier
                    }
                )
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (summaryText.isNotBlank()) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Use non-clickable Surface + inner clickable so M3 minimumInteractiveComponentSize
            // does not inject asymmetric 5.dp horizontal padding around the icon-only Sort pill.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showPlaybackActions) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .height(34.dp)
                                .clickable(enabled = isPlayEnabled, role = Role.Button, onClick = onPlayAll)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.action_play_all),
                                tint = if (isPlayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.action_play),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPlayEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .height(34.dp)
                                .clickable(enabled = isPlayEnabled, role = Role.Button, onClick = onShuffleAll)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = stringResource(R.string.action_shuffle),
                                tint = if (isPlayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.action_shuffle_short),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPlayEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                if (showSort && sortMenuContent != null) {
                    Box {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clickable(role = Role.Button, onClick = { setExpanded(true) })
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.action_sort),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { setExpanded(false) }
                        ) {
                            sortMenuContent { setExpanded(false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommonSortMenuItems(
    headerTitle: String,
    currentMode: CommonSortMode,
    onSelectMode: (CommonSortMode) -> Unit
) {
    Text(
        headerTitle,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
    CommonSortMode.entries.forEach { mode ->
        DropdownMenuItem(
            text = {
                Text(
                    mode.localizedLabel(),
                    fontWeight = if (currentMode == mode) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            leadingIcon = {
                if (currentMode == mode) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            onClick = { onSelectMode(mode) }
        )
    }
}

// ═══ Library Tab Views ═══
/** List layout for the Songs tab with fast-scroller index bubbles. */
@Composable
fun SongsListView(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onSongMoreClick: (Song) -> Unit = {},
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    isSearching: Boolean = false,
    listState: LazyListState = rememberLazyListState()
) {
    if (songs.isEmpty()) {
        EmptyLibraryMessage(
            message = stringResource(R.string.empty_no_songs),
            hint = if (isSearching) stringResource(R.string.search_empty_hint) else null
        )
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current
    val isFastScrolling = rememberIsFastScrolling(listState)

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFastScrollingState provides isFastScrolling) {
        LazyColumn(
            state = listState,
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
                contentType = { _, _ -> "song" }
            ) { _, song ->
                val isCurrent = currentSongId == song.id
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
                        .clickable { onSongClick(song) }
                        .padding(vertical = 7.dp, horizontal = 8.dp),
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
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    IconButton(onClick = { onSongMoreClick(song) }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }

        LibraryFastScroller(
            listState = listState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> songs.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** Adaptive grid layout for the Songs tab with high-contrast overlay action dots. */
@Composable
fun SongsGridView(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onSongMoreClick: (Song) -> Unit = {},
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    isSearching: Boolean = false,
    gridState: LazyGridState = rememberLazyGridState()
) {
    if (songs.isEmpty()) {
        EmptyLibraryMessage(
            message = stringResource(R.string.empty_no_songs),
            hint = if (isSearching) stringResource(R.string.search_empty_hint) else null
        )
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current
    val coverCorner = if (layout.isTabletOrExpanded) 14.dp else 12.dp
    val isFastScrolling = rememberIsFastScrolling(gridState)

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFastScrollingState provides isFastScrolling) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layout.coverGridColumns),
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 16.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 16.dp else 14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
                contentType = { _, _ -> "song_grid" }
            ) { _, song ->
                val isCurrent = currentSongId == song.id
                Column(
                    modifier = Modifier.clickable { onSongClick(song) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(coverCorner))
                            .then(
                                if (isCurrent) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(coverCorner)
                                    )
                                } else Modifier
                            )
                    ) {
                        AlbumArtImage(
                            song = song,
                            icon = Icons.Default.MusicNote,
                            bucket = ArtResolutionBucket.CARD,
                            shape = RoundedCornerShape(coverCorner),
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                QueueEqualizerIndicator(
                                    isPlaying = isPlaying,
                                    barColor = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        val moreDesc = stringResource(R.string.action_more)
                        IconButton(
                            onClick = { onSongMoreClick(song) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .semantics { contentDescription = moreDesc }
                                    .drawWithCache {
                                        val cx = size.width / 2f
                                        val dotRadius = 1.6.dp.toPx()
                                        val borderRadius = dotRadius + 0.9.dp.toPx()
                                        val c0 = Offset(cx, 4.2.dp.toPx())
                                        val c1 = Offset(cx, 10.0.dp.toPx())
                                        val c2 = Offset(cx, 15.8.dp.toPx())
                                        onDrawBehind {
                                            drawCircle(color = Color.Black, radius = borderRadius, center = c0)
                                            drawCircle(color = Color.White, radius = dotRadius, center = c0)
                                            drawCircle(color = Color.Black, radius = borderRadius, center = c1)
                                            drawCircle(color = Color.White, radius = dotRadius, center = c1)
                                            drawCircle(color = Color.Black, radius = borderRadius, center = c2)
                                            drawCircle(color = Color.White, radius = dotRadius, center = c2)
                                        }
                                    }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
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
            }
        }
        }

        LibraryFastScroller(
            gridState = gridState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> songs.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** Adaptive cover grid for the Albums tab. */
@Composable
fun AlbumsGridView(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    gridState: LazyGridState = rememberLazyGridState()
) {
    if (albums.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_albums))
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current
    val coverCorner = if (layout.isTabletOrExpanded) 14.dp else 12.dp
    val isFastScrolling = rememberIsFastScrolling(gridState)

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFastScrollingState provides isFastScrolling) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layout.coverGridColumns),
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 16.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 16.dp else 14.dp)
        ) {
            items(
                items = albums,
                key = { it.id },
                contentType = { "album" }
            ) { album ->
                Column(
                    modifier = Modifier.clickable { onAlbumClick(album) }
                ) {
                    AlbumArtImage(
                        album = album,
                        icon = Icons.Default.Album,
                        bucket = ArtResolutionBucket.CARD,
                        shape = RoundedCornerShape(coverCorner),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = localizedAlbumName(album.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.album_card_subtitle,
                            album.songCount,
                            localizedArtistName(album.artist),
                            album.songCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        }

        LibraryFastScroller(
            gridState = gridState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> albums.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** List layout for the Artists tab. */
@Composable
fun ArtistsListView(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    if (artists.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_artists))
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = artists,
                key = { it.name },
                contentType = { "artist" }
            ) { artist ->
                Row(
                    modifier = Modifier
                        .then(
                            if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                            else Modifier
                        )
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onArtistClick(artist) }
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localizedArtistName(artist.name),
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.artist_summary_format,
                                pluralStringResource(R.plurals.song_count, artist.songCount, artist.songCount),
                                pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        LibraryFastScroller(
            listState = listState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> artists.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** Adaptive card grid for the Genres tab. */
@Composable
fun GenresGridView(
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit,
    gridState: LazyGridState = rememberLazyGridState()
) {
    if (genres.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_genres))
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layout.compactCardColumns),
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 14.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 14.dp else 12.dp)
        ) {
            items(genres, key = { it.name }, contentType = { "genre" }) { genre ->
                val isUnknown = isUnknownGenreName(genre.name)
                CompactCategoryCard(
                    icon = Icons.Default.MusicNote,
                    badgeContainerColor = if (isUnknown) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    badgeContentColor = if (isUnknown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                    title = localizedGenreName(genre.name),
                    songCount = genre.songCount,
                    onClick = { onGenreClick(genre) }
                )
            }
        }

        LibraryFastScroller(
            gridState = gridState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> genres.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** Adaptive card grid for the Release Years tab. */
@Composable
fun YearsGridView(
    years: List<YearGroup>,
    onYearClick: (YearGroup) -> Unit,
    gridState: LazyGridState = rememberLazyGridState()
) {
    if (years.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_years))
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layout.compactCardColumns),
            contentPadding = paneContentPadding(baseHorizontal = layout.horizontalPadding, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 14.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.isTabletOrExpanded) 14.dp else 12.dp)
        ) {
            items(years, key = { it.year }, contentType = { "year" }) { yg ->
                val isUnknown = yg.year <= 0
                val title = if (!isUnknown) yg.year.toString() else stringResource(R.string.year_unknown)
                CompactCategoryCard(
                    icon = Icons.Default.CalendarToday,
                    badgeContainerColor = if (isUnknown) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
                    badgeContentColor = if (isUnknown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
                    title = title,
                    songCount = yg.songCount,
                    onClick = { onYearClick(yg) }
                )
            }
        }

        LibraryFastScroller(
            gridState = gridState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx ->
                years.getOrNull(idx)?.year?.takeIf { it > 0 }?.toString() ?: DEFAULT_INDEX_BUBBLE
            }
        )
    }
}

/** Flat list of all folders containing music with A–Z teardrop fast-scroll indicator. */
@Composable
fun MusicFoldersListView(
    folders: List<Folder>,
    onFolderClick: (Folder) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    if (folders.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_folders))
        return
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = paneContentPadding(
                baseHorizontal = layout.horizontalPadding,
                top = 4.dp,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = folders,
                key = { it.path },
                contentType = { "music_folder" }
            ) { folder ->
                Surface(
                    onClick = { onFolderClick(folder) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .then(
                            if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                            else Modifier
                        )
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = folder.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pluralStringResource(R.plurals.song_count, folder.songCount, folder.songCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        LibraryFastScroller(
            listState = listState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = { idx -> folders.getOrNull(idx)?.indexLetter ?: DEFAULT_INDEX_BUBBLE }
        )
    }
}

/** Hierarchical directory tree browser for the Files tab with independent per-path scroll state. */
@Composable
fun FoldersListView(
    folders: List<Folder>,
    songs: List<Song> = emptyList(),
    onFolderClick: (Folder) -> Unit = {},
    onSongClick: (Song, List<Song>, String) -> Unit = { _, _, _ -> },
    onSongMoreClick: (Song) -> Unit = {},
    onStorageMountChanged: () -> Unit = {},
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    isActiveOnTop: Boolean = false
) {
    val context = LocalContext.current
    var storageMountRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                // Only refresh local volume list UI; MediaStoreChangeObserver handles debounced rescans
                storageMountRevision++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current

    val internalStorageLabel = stringResource(R.string.storage_internal_name)
    val externalStorageLabel = stringResource(R.string.storage_external_name)
    val sdCardLabel = stringResource(R.string.storage_sdcard_name)
    val rootStorageLabel = stringResource(R.string.storage_root_label)

    var storageVolumes by remember { mutableStateOf<List<StorageVolumeNode>>(emptyList()) }
    var hasLoadedVolumes by remember { mutableStateOf(false) }
    LaunchedEffect(
        context,
        storageMountRevision,
        songs,
        folders,
        internalStorageLabel,
        externalStorageLabel,
        sdCardLabel
    ) {
        storageVolumes = withContext(Dispatchers.IO) {
            buildStorageVolumes(
                context = context,
                songs = songs,
                folders = folders,
                internalLabel = internalStorageLabel,
                externalLabel = externalStorageLabel,
                sdCardLabel = sdCardLabel
            )
        }
        hasLoadedVolumes = true
    }

    if (hasLoadedVolumes && storageVolumes.isEmpty() && folders.isEmpty() && songs.isEmpty()) {
        EmptyLibraryMessage(stringResource(R.string.empty_no_folders))
        return
    }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var currentBrowsePath by rememberSaveable {
        mutableStateOf(prefs.getString(PREF_SAVED_FOLDER_BROWSE_PATH, "") ?: "")
    }
    val lastSavedBrowsePathRef = remember { arrayOfNulls<String>(1) }
    SideEffect {
        if (lastSavedBrowsePathRef[0] != currentBrowsePath) {
            lastSavedBrowsePathRef[0] = currentBrowsePath
            prefs.edit().putString(PREF_SAVED_FOLDER_BROWSE_PATH, currentBrowsePath).apply()
        }
    }

    // Validate currentBrowsePath against available storage roots
    val activeVolume = remember(currentBrowsePath, storageVolumes) {
        if (currentBrowsePath.isBlank()) null
        else storageVolumes.firstOrNull { vol ->
            currentBrowsePath == vol.rootPath || currentBrowsePath.startsWith("${vol.rootPath}/")
        }
    }
    LaunchedEffect(currentBrowsePath, activeVolume, storageVolumes.isNotEmpty()) {
        if (storageVolumes.isNotEmpty() && currentBrowsePath.isNotBlank() && activeVolume == null) {
            currentBrowsePath = ""
        }
    }

    val filesBackState = rememberPredictiveBackState()
    LaunchedEffect(currentBrowsePath) {
        filesBackState.reset()
    }
    PredictiveBackEffect(
        enabled = isActiveOnTop && currentBrowsePath.isNotBlank(),
        state = filesBackState,
        onBack = {
            val vol = activeVolume
            if (vol == null || currentBrowsePath == vol.rootPath) {
                currentBrowsePath = ""
            } else {
                val parent = currentBrowsePath.substringBeforeLast('/', "")
                currentBrowsePath = if (parent.length >= vol.rootPath.length && parent.startsWith(vol.rootPath)) {
                    parent
                } else {
                    vol.rootPath
                }
            }
        }
    )

    var fileSortMode by rememberSaveable { mutableStateOf(FileBrowserSortMode.DEFAULT) }
    var showFileSortMenu by remember { mutableStateOf(false) }

    val currentDirLevelState = remember { mutableStateOf<DirectoryLevelContent?>(null) }
    LaunchedEffect(currentBrowsePath, activeVolume, songs, folders, fileSortMode) {
        currentDirLevelState.value = if (activeVolume == null || currentBrowsePath.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                buildDirectoryLevel(
                    currentPath = currentBrowsePath,
                    songs = songs,
                    folders = folders,
                    sortMode = fileSortMode
                )
            }
        }
    }
    val currentDirLevel = currentDirLevelState.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .predictiveBackInline(filesBackState)
    ) {
        // Breadcrumb navigation bar when inside a storage volume
        if (activeVolume != null && currentBrowsePath.isNotBlank()) {
            val breadcrumbs = remember(currentBrowsePath, activeVolume) {
                buildBreadcrumbs(currentBrowsePath, activeVolume)
            }
            val breadcrumbListState = rememberLazyListState()
            LaunchedEffect(currentBrowsePath, breadcrumbs.size) {
                // Index 0 is the root chip; last breadcrumb chip is at index breadcrumbs.size
                val targetIndex = breadcrumbs.size
                if (targetIndex > 0) {
                    runCatching {
                        breadcrumbListState.animateScrollToItem(targetIndex)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(paneHorizontalInsets())
                    .padding(horizontal = layout.horizontalPadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        if (currentBrowsePath == activeVolume.rootPath) {
                            currentBrowsePath = ""
                        } else {
                            val parent = currentBrowsePath.substringBeforeLast('/', "")
                            currentBrowsePath = if (parent.length >= activeVolume.rootPath.length && parent.startsWith(activeVolume.rootPath)) {
                                parent
                            } else {
                                activeVolume.rootPath
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                LazyRow(
                    state = breadcrumbListState,
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "crumb_root", contentType = "crumb_root") {
                        Surface(
                            onClick = { currentBrowsePath = "" },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Text(
                                text = rootStorageLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    itemsIndexed(
                        items = breadcrumbs,
                        key = { _, item -> item.path },
                        contentType = { _, _ -> "crumb_seg" }
                    ) { idx, crumb ->
                        val isLast = idx == breadcrumbs.lastIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Surface(
                                onClick = { currentBrowsePath = crumb.path },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isLast) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                }
                            ) {
                                Text(
                                    text = crumb.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isLast) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(role = Role.Button, onClick = { showFileSortMenu = true }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(R.string.action_sort),
                                tint = if (fileSortMode != FileBrowserSortMode.DEFAULT) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showFileSortMenu,
                        onDismissRequest = { showFileSortMenu = false }
                    ) {
                        Text(
                            text = stringResource(R.string.sort_files_header),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FileBrowserSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mode.localizedLabel(),
                                        fontWeight = if (fileSortMode == mode) FontWeight.Bold else FontWeight.Normal,
                                        color = if (fileSortMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    if (fileSortMode == mode) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    fileSortMode = mode
                                    showFileSortMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Independent LazyListState per directory path so each folder level preserves its own scroll position
        val pathScrollStates = remember { mutableMapOf<String, LazyListState>() }
        val activeListState = remember(currentBrowsePath) {
            pathScrollStates.getOrPut(currentBrowsePath) { LazyListState() }
        }
        key(currentBrowsePath) {
        val isFolderFastScrolling = rememberIsFastScrolling(activeListState)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            CompositionLocalProvider(LocalFastScrollingState provides isFolderFastScrolling) {
                LazyColumn(
                    state = activeListState,
                    contentPadding = paneContentPadding(
                        baseHorizontal = layout.horizontalPadding,
                        top = 8.dp,
                        bottom = bottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
            if (activeVolume == null || currentDirLevel == null) {
                // Root level: list all storage volumes (Internal Storage, SD Card, USB OTG / External drive)
                items(
                    items = storageVolumes,
                    key = { "vol_${it.rootPath}" },
                    contentType = { "storage_volume" }
                ) { volume ->
                    Surface(
                        onClick = { currentBrowsePath = volume.rootPath },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .then(
                                if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                                else Modifier
                            )
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (volume.type) {
                                            StorageVolumeType.INTERNAL -> Icons.Default.PhoneAndroid
                                            StorageVolumeType.SD_CARD -> Icons.Default.SdStorage
                                            StorageVolumeType.EXTERNAL_USB -> Icons.Default.Usb
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = volume.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.folder_tree_summary_both,
                                        pluralStringResource(R.plurals.folder_count, volume.folderCount, volume.folderCount),
                                        pluralStringResource(R.plurals.song_count, volume.songCount, volume.songCount)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = volume.rootPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else {
                // Child directories inside the current path
                items(
                    items = currentDirLevel.subDirectories,
                    key = { "dir_${it.path}" },
                    contentType = { "folder_node" }
                ) { dirNode ->
                    Surface(
                        onClick = {
                            if (dirNode.subFolderCount == 0 && dirNode.directSongCount > 0 && songs.isEmpty()) {
                                onFolderClick(Folder(name = dirNode.name, path = dirNode.path, songCount = dirNode.totalSongCount))
                            } else {
                                currentBrowsePath = dirNode.path
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .then(
                                if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                                else Modifier
                            )
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = dirNode.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subSummary = when {
                                    dirNode.subFolderCount > 0 && dirNode.totalSongCount > 0 -> stringResource(
                                        R.string.folder_tree_summary_both,
                                        pluralStringResource(R.plurals.folder_count, dirNode.subFolderCount, dirNode.subFolderCount),
                                        pluralStringResource(R.plurals.song_count, dirNode.totalSongCount, dirNode.totalSongCount)
                                    )
                                    dirNode.subFolderCount > 0 -> pluralStringResource(
                                        R.plurals.folder_count,
                                        dirNode.subFolderCount,
                                        dirNode.subFolderCount
                                    )
                                    else -> pluralStringResource(R.plurals.song_count, dirNode.totalSongCount, dirNode.totalSongCount)
                                }
                                Text(
                                    text = subSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Direct audio files inside the current directory
                if (currentDirLevel.directSongs.isNotEmpty()) {
                    val currentFolderDisplayName = currentBrowsePath.substringAfterLast('/').ifBlank { activeVolume.displayName }
                    itemsIndexed(
                        items = currentDirLevel.directSongs,
                        key = { _, song -> "file_${song.id}" },
                        contentType = { _, _ -> "folder_song" }
                    ) { _, song ->
                        val isCurrent = currentSongId == song.id
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
                                    onSongClick(song, currentDirLevel.directSongs, currentFolderDisplayName)
                                }
                                .padding(vertical = 7.dp, horizontal = 8.dp),
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
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )

                            IconButton(onClick = { onSongMoreClick(song) }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.action_more),
                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
                }
            }

            LibraryFastScroller(
                listState = activeListState,
                modifier = Modifier.align(Alignment.TopEnd),
                getBubbleText = null
            )
        }
        }
    }
}

private enum class StorageVolumeType {
    INTERNAL,
    SD_CARD,
    EXTERNAL_USB
}

private data class StorageVolumeNode(
    val rootPath: String,
    val displayName: String,
    val type: StorageVolumeType,
    val folderCount: Int,
    val songCount: Int
)

private data class DirectoryTreeNode(
    val name: String,
    val path: String,
    val subFolderCount: Int,
    val directSongCount: Int,
    val totalSongCount: Int,
    val latestDateAdded: Long = 0L,
    val totalDurationMs: Long = 0L
)

private data class DirectoryLevelContent(
    val subDirectories: List<DirectoryTreeNode>,
    val directSongs: List<Song>
)

private data class BreadcrumbSegment(
    val label: String,
    val path: String
)

private fun normalizeDirectoryPath(rawPath: String): String {
    return rawPath.replace('\\', '/').trimEnd('/').ifBlank { "/" }
}

private fun resolveStorageRoot(dirPath: String): Triple<String, String, StorageVolumeType> {
    val norm = normalizeDirectoryPath(dirPath)
    return when {
        norm.startsWith("/storage/emulated/") -> {
            val userSeg = norm.removePrefix("/storage/emulated/").substringBefore('/').ifBlank { "0" }
            Triple("/storage/emulated/$userSeg", "", StorageVolumeType.INTERNAL)
        }
        norm == "/sdcard" || norm.startsWith("/sdcard/") -> {
            Triple("/sdcard", "", StorageVolumeType.INTERNAL)
        }
        norm.startsWith("/storage/") -> {
            val volId = norm.removePrefix("/storage/").substringBefore('/')
            if (volId.equals("self", ignoreCase = true) || volId.isBlank()) {
                Triple("/storage/self", "self", StorageVolumeType.INTERNAL)
            } else {
                val isUsb = volId.contains("usb", ignoreCase = true) ||
                    volId.contains("otg", ignoreCase = true) ||
                    norm.startsWith("/mnt/media_rw/")
                Triple(
                    "/storage/$volId",
                    volId,
                    if (isUsb) StorageVolumeType.EXTERNAL_USB else StorageVolumeType.SD_CARD
                )
            }
        }
        norm.startsWith("/mnt/media_rw/") -> {
            val volId = norm.removePrefix("/mnt/media_rw/").substringBefore('/')
            Triple("/mnt/media_rw/$volId", volId, StorageVolumeType.EXTERNAL_USB)
        }
        norm.startsWith("/mnt/") -> {
            val volId = norm.removePrefix("/mnt/").substringBefore('/')
            Triple("/mnt/$volId", volId, StorageVolumeType.EXTERNAL_USB)
        }
        else -> {
            val firstSeg = norm.removePrefix("/").substringBefore('/').ifBlank { "storage" }
            Triple("/$firstSeg", firstSeg, StorageVolumeType.EXTERNAL_USB)
        }
    }
}

private fun isUsbVolumeDescription(desc: String, rawPath: String): Boolean {
    if (rawPath.startsWith("/mnt/media_rw/") ||
        rawPath.contains("usb", ignoreCase = true) ||
        rawPath.contains("otg", ignoreCase = true)
    ) {
        return true
    }
    return desc.contains("usb", ignoreCase = true) ||
        desc.contains("otg", ignoreCase = true) ||
        desc.contains("drive", ignoreCase = true) ||
        desc.contains("flash", ignoreCase = true) ||
        desc.contains("external", ignoreCase = true) ||
        desc.contains("ngoài", ignoreCase = true)
}

private fun buildStorageVolumes(
    context: Context,
    songs: List<Song>,
    folders: List<Folder>,
    internalLabel: String,
    externalLabel: String,
    sdCardLabel: String
): List<StorageVolumeNode> {
    data class Acc(
        val rootPath: String,
        val rawId: String,
        var type: StorageVolumeType,
        var customDescription: String? = null,
        val folderPaths: HashSet<String> = HashSet(),
        var songCount: Int = 0
    )

    val map = LinkedHashMap<String, Acc>()
    val aliasToCanonicalRoot = HashMap<String, String>()

    // 1. Discover all mounted storage volumes (Internal, SD Card, USB OTG drives) via StorageManager
    runCatching {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm != null) {
            for (vol in sm.storageVolumes) {
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    vol.directory
                } else {
                    runCatching {
                        val getPath = vol.javaClass.getMethod("getPath")
                        (getPath.invoke(vol) as? String)?.let { File(it) }
                    }.getOrNull()
                }
                val rawPathStr = dir?.absolutePath?.takeIf { it.isNotBlank() } ?: continue
                val normRaw = normalizeDirectoryPath(rawPathStr)
                val uuid = vol.uuid?.trim().orEmpty()
                val desc = runCatching { vol.getDescription(context) }.getOrNull().orEmpty()
                val isUsb = !vol.isPrimary && vol.isRemovable && isUsbVolumeDescription(desc, normRaw)
                val type = when {
                    vol.isPrimary || !vol.isRemovable -> StorageVolumeType.INTERNAL
                    isUsb -> StorageVolumeType.EXTERNAL_USB
                    else -> StorageVolumeType.SD_CARD
                }

                // Prefer public /storage/<UUID> when available so paths match MediaStore & app permissions
                val canonicalRoot = if (normRaw.startsWith("/mnt/media_rw/") && uuid.isNotBlank()) {
                    val publicDir = File("/storage/$uuid")
                    if (publicDir.exists()) "/storage/$uuid" else normRaw
                } else {
                    normRaw
                }

                val rawId = uuid.ifBlank { canonicalRoot.substringAfterLast('/') }
                aliasToCanonicalRoot[normRaw] = canonicalRoot
                aliasToCanonicalRoot[canonicalRoot] = canonicalRoot
                if (uuid.isNotBlank()) {
                    aliasToCanonicalRoot["/storage/$uuid"] = canonicalRoot
                    aliasToCanonicalRoot["/mnt/media_rw/$uuid"] = canonicalRoot
                }

                map.getOrPut(canonicalRoot) {
                    Acc(
                        rootPath = canonicalRoot,
                        rawId = rawId,
                        type = type,
                        customDescription = desc.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }

    // 2. Also probe context.getExternalFilesDirs(null) to catch secondary SD / OTG mount points
    runCatching {
        for (extDir in context.getExternalFilesDirs(null)) {
            val abs = extDir?.absolutePath ?: continue
            val rootPath = normalizeDirectoryPath(abs.substringBefore("/Android/"))
            val canonical = aliasToCanonicalRoot[rootPath] ?: rootPath
            if (canonical.isNotBlank() && !map.containsKey(canonical)) {
                val (resolvedRoot, rawId, type) = resolveStorageRoot(canonical)
                aliasToCanonicalRoot[rootPath] = resolvedRoot
                map.getOrPut(resolvedRoot) { Acc(resolvedRoot, rawId, type) }
            }
        }
    }

    fun resolveBucketForDirectory(normDir: String): Acc {
        for ((aliasRoot, canonicalRoot) in aliasToCanonicalRoot) {
            if (normDir == aliasRoot || normDir.startsWith("$aliasRoot/")) {
                map[canonicalRoot]?.let { return it }
            }
        }
        val matchedKey = map.keys.firstOrNull { root ->
            normDir == root || normDir.startsWith("$root/")
        }
        if (matchedKey != null) {
            return map[matchedKey]!!
        }
        val (rootPath, rawId, type) = resolveStorageRoot(normDir)
        val canonical = aliasToCanonicalRoot[rootPath] ?: rootPath
        return map.getOrPut(canonical) { Acc(canonical, rawId, type) }
    }

    // 3. Aggregate library tracks & folders into their respective storage volume roots
    if (songs.isNotEmpty()) {
        for (song in songs) {
            val slashIdx = song.path.lastIndexOf('/')
            val parent = if (slashIdx > 0) song.path.substring(0, slashIdx) else "/storage/emulated/0"
            val normParent = normalizeDirectoryPath(parent)
            val acc = resolveBucketForDirectory(normParent)
            acc.folderPaths.add(normParent)
            acc.songCount++
        }
    } else {
        for (folder in folders) {
            val normFolder = normalizeDirectoryPath(folder.path)
            val acc = resolveBucketForDirectory(normFolder)
            acc.folderPaths.add(normFolder)
            acc.songCount += folder.songCount
        }
    }

    return map.values.map { acc ->
        val displayName = when (acc.type) {
            StorageVolumeType.INTERNAL -> internalLabel
            StorageVolumeType.SD_CARD -> sdCardLabel
            StorageVolumeType.EXTERNAL_USB -> {
                val custom = acc.customDescription
                if (!custom.isNullOrBlank() && !custom.equals(sdCardLabel, ignoreCase = true)) {
                    custom
                } else {
                    externalLabel
                }
            }
        }
        StorageVolumeNode(
            rootPath = acc.rootPath,
            displayName = displayName,
            type = acc.type,
            folderCount = acc.folderPaths.size,
            songCount = acc.songCount
        )
    }.sortedWith(
        compareBy<StorageVolumeNode> {
            when (it.type) {
                StorageVolumeType.INTERNAL -> 0
                StorageVolumeType.SD_CARD -> 1
                StorageVolumeType.EXTERNAL_USB -> 2
            }
        }.thenBy(NATURAL_TEXT_COMPARATOR) { it.displayName }
    )
}

private fun buildDirectoryLevel(
    currentPath: String,
    songs: List<Song>,
    folders: List<Folder>,
    sortMode: FileBrowserSortMode = FileBrowserSortMode.DEFAULT
): DirectoryLevelContent {
    val normCurrent = normalizeDirectoryPath(currentPath)
    val prefix = if (normCurrent == "/") "/" else "$normCurrent/"

    data class ChildAcc(
        val name: String,
        val path: String,
        val immediateSubDirs: HashSet<String> = HashSet(),
        var directSongCount: Int = 0,
        var totalSongCount: Int = 0,
        var latestDateAdded: Long = 0L,
        var totalDurationMs: Long = 0L
    )

    val childMap = HashMap<String, ChildAcc>()
    val directSongs = mutableListOf<Song>()

    if (songs.isNotEmpty()) {
        for (song in songs) {
            val slashIdx = song.path.lastIndexOf('/')
            val parent = normalizeDirectoryPath(if (slashIdx > 0) song.path.substring(0, slashIdx) else "")
            if (parent == normCurrent) {
                directSongs.add(song)
            } else if (parent.startsWith(prefix)) {
                val remainder = parent.removePrefix(prefix)
                val childName = remainder.substringBefore('/')
                if (childName.isNotBlank()) {
                    val childPath = "$prefix$childName"
                    val acc = childMap.getOrPut(childPath) { ChildAcc(childName, childPath) }
                    acc.totalSongCount++
                    acc.totalDurationMs += song.duration
                    if (song.dateAdded > acc.latestDateAdded) {
                        acc.latestDateAdded = song.dateAdded
                    }
                    val afterChild = remainder.substringAfter('/', "")
                    if (afterChild.isEmpty()) {
                        acc.directSongCount++
                    } else {
                        acc.immediateSubDirs.add(afterChild.substringBefore('/'))
                    }
                }
            }
        }
    } else {
        for (folder in folders) {
            val normFolder = normalizeDirectoryPath(folder.path)
            if (normFolder.startsWith(prefix)) {
                val remainder = normFolder.removePrefix(prefix)
                val childName = remainder.substringBefore('/')
                if (childName.isNotBlank()) {
                    val childPath = "$prefix$childName"
                    val acc = childMap.getOrPut(childPath) { ChildAcc(childName, childPath) }
                    acc.totalSongCount += folder.songCount
                    val afterChild = remainder.substringAfter('/', "")
                    if (afterChild.isEmpty()) {
                        acc.directSongCount += folder.songCount
                    } else {
                        acc.immediateSubDirs.add(afterChild.substringBefore('/'))
                    }
                }
            }
        }
    }

    val rawSubDirs = childMap.values.map { acc ->
        DirectoryTreeNode(
            name = acc.name,
            path = acc.path,
            subFolderCount = acc.immediateSubDirs.size,
            directSongCount = acc.directSongCount,
            totalSongCount = acc.totalSongCount,
            latestDateAdded = acc.latestDateAdded,
            totalDurationMs = acc.totalDurationMs
        )
    }

    val subDirs = when (sortMode) {
        FileBrowserSortMode.DEFAULT, FileBrowserSortMode.NAME_AZ -> rawSubDirs.sortedWith(
            compareBy(NATURAL_TEXT_COMPARATOR) { it.name }
        )
        FileBrowserSortMode.NAME_ZA -> rawSubDirs.sortedWith(
            compareByDescending<DirectoryTreeNode, String>(NATURAL_TEXT_COMPARATOR) { it.name }
        )
        FileBrowserSortMode.DATE_ADDED -> rawSubDirs.sortedWith(
            compareByDescending<DirectoryTreeNode> { it.latestDateAdded }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
        )
        FileBrowserSortMode.DURATION -> rawSubDirs.sortedWith(
            compareByDescending<DirectoryTreeNode> { it.totalDurationMs }
                .thenByDescending { it.totalSongCount }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
        )
    }

    when (sortMode) {
        FileBrowserSortMode.DEFAULT -> directSongs.sortWith(
            compareBy<Song> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
        )
        FileBrowserSortMode.NAME_AZ -> directSongs.sortWith(
            compareBy(NATURAL_TEXT_COMPARATOR) { it.title }
        )
        FileBrowserSortMode.NAME_ZA -> directSongs.sortWith(
            compareByDescending<Song, String>(NATURAL_TEXT_COMPARATOR) { it.title }
        )
        FileBrowserSortMode.DATE_ADDED -> directSongs.sortWith(
            compareByDescending<Song> { it.dateAdded }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
        )
        FileBrowserSortMode.DURATION -> directSongs.sortWith(
            compareByDescending<Song> { it.duration }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
        )
    }

    return DirectoryLevelContent(
        subDirectories = subDirs,
        directSongs = directSongs
    )
}

private fun buildBreadcrumbs(
    currentPath: String,
    volume: StorageVolumeNode
): List<BreadcrumbSegment> {
    val normCurrent = normalizeDirectoryPath(currentPath)
    val normRoot = normalizeDirectoryPath(volume.rootPath)
    val segments = mutableListOf(BreadcrumbSegment(label = volume.displayName, path = normRoot))
    if (normCurrent.length > normRoot.length && normCurrent.startsWith("$normRoot/")) {
        val relative = normCurrent.removePrefix("$normRoot/")
        var runningPath = normRoot
        for (part in relative.split('/')) {
            if (part.isNotBlank()) {
                runningPath = "$runningPath/$part"
                segments.add(BreadcrumbSegment(label = part, path = runningPath))
            }
        }
    }
    return segments
}

/** List layout for the Playlists tab with creation, rename, and delete dialogs. */
@Composable
fun PlaylistsListView(
    playlists: List<Playlist>,
    onPlaylistClick: (Playlist) -> Unit,
    viewModel: MusicViewModel,
    listState: LazyListState = rememberLazyListState()
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    val layout = rememberAdaptiveLayoutInfo()
    val bottomPadding = LocalBottomContentPadding.current
    val dashedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = paneContentPadding(
                baseHorizontal = layout.horizontalPadding,
                top = 8.dp,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "create_playlist_cta", contentType = "playlist_cta") {
                Surface(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier
                        .then(
                            if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                            else Modifier
                        )
                        .fillMaxWidth()
                        .drawWithCache {
                            val cornerRadius = CornerRadius(16.dp.toPx())
                            val dashStroke = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                            )
                            onDrawBehind {
                                drawRoundRect(
                                    color = dashedBorderColor,
                                    cornerRadius = cornerRadius,
                                    style = dashStroke
                                )
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.playlist_create_new),
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.playlist_custom_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(playlists, key = { it.id }, contentType = { "playlist" }) { pl ->
                var showMenu by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onPlaylistClick(pl) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .then(
                            if (layout.isTabletOrExpanded) Modifier.widthIn(max = layout.maxListContentWidth)
                            else Modifier
                        )
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (pl.isSystem) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(FAVORITES_GRADIENT_START, FAVORITES_GRADIENT_END)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = localizedPlaylistName(pl.name, pl.isSystem),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (pl.isSystem) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.badge_system),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = pluralStringResource(R.plurals.song_count, pl.songCount, pl.songCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (pl.isSystem) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.action_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_rename)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            playlistToRename = pl
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            playlistToDelete = pl
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        LibraryFastScroller(
            listState = listState,
            modifier = Modifier.align(Alignment.TopEnd),
            getBubbleText = null
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false }
        )
    }

    if (playlistToRename != null) {
        var newName by remember { mutableStateOf(playlistToRename!!.name) }
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.playlist_rename_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.renamePlaylist(playlistToRename!!.id, newName)
                            playlistToRename = null
                        }
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (playlistToDelete != null) {
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.playlist_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.playlist_delete_msg, playlistToDelete!!.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlistToDelete!!.id)
                        playlistToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ═══ Private Helpers ═══
@Composable
private fun EmptyLibraryMessage(
    message: String,
    hint: String? = null
) {
    val bottomPadding = LocalBottomContentPadding.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 32.dp,
                top = 16.dp,
                end = 32.dp,
                bottom = bottomPadding + 48.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(52.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CompactCategoryCard(
    icon: ImageVector,
    badgeContainerColor: Color,
    badgeContentColor: Color,
    title: String,
    songCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = badgeContainerColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = badgeContentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
