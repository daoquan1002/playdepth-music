/**
 * MainScreen — Root adaptive navigation shell, overlay coordinator, and DAC volume HUD.
 *
 * @remarks
 * Keeps Home, Library, and pre-warmed full-screen overlays (NowPlaying, Settings) alive in
 * composition and animates transitions via pure `translationX`/`translationY` transforms so
 * opaque layers avoid `saveLayerAlpha` offscreen buffer allocations and base-layer overdraw.
 * System bar tint updates wait for sheet settle state to prevent WindowManager frame drops.
 *
 * @see HomeScreen
 * @see MusicLibraryScreen
 * @see NowPlayingSheet
 * @see SettingsScreen
 * @see SystemBarsColorController
 * @see UsbDacManager
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.SystemBarsColorController
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.MiniPlayer
import com.quandot.musicplayer.ui.components.NowPlayingSheet

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.*
import com.quandot.musicplayer.ui.theme.AnimatedDetailSlot
import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.LocalHasLeftRail
import com.quandot.musicplayer.ui.theme.LocalScreenVisible
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.PredictiveBackEffect
import com.quandot.musicplayer.ui.theme.predictiveBackScreen
import com.quandot.musicplayer.ui.theme.predictiveBackSheet
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.rememberPredictiveBackState
import com.quandot.musicplayer.ui.theme.systemSafeInsets

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_SAVED_NAV_TAB = "saved_nav_tab"
private const val PREF_SAVED_ALBUM_ID = "saved_album_id"

private const val THEME_LIGHT = "light"
private const val THEME_DARK = "dark"

private const val NAV_TAB_HOME = 0
private const val NAV_TAB_LIBRARY = 1
private const val LIBRARY_TAB_ALBUMS = 1

private const val VOLUME_HUD_TIMEOUT_MS = 1600L

private const val NOW_PLAYING_ENTER_MS = M3Motion.FLYOUT_ENTER_MS
private const val NOW_PLAYING_EXIT_MS = M3Motion.FLYOUT_EXIT_MS
private const val SETTINGS_ENTER_MS = M3Motion.SIMPLE_ENTER_MS
private const val OVERLAY_EXIT_MS = M3Motion.OVERLAY_EXIT_MS
private const val VOLUME_HUD_STEP_ANIM_MS = 70
private const val NOW_PLAYING_SCRIM_MAX_ALPHA = 0.38f

private const val ACTIVE_ANIM_THRESHOLD = 0.005f
private const val OFFSCREEN_TRANSLATION_PX = 100_000f

// ═══ Root Navigation Shell ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MusicViewModel,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode by viewModel.themeMode.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemDark
    }

    var selectedNavTab by rememberSaveable {
        mutableIntStateOf(prefs.getInt(PREF_SAVED_NAV_TAB, NAV_TAB_HOME))
    }
    var requestedLibraryTab by remember { mutableStateOf<Int?>(null) }
    var showNowPlayingSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsScreen by rememberSaveable { mutableStateOf(false) }

    // Separate per-tab detail stacks so switching between Home and Library preserves Library's
    // open detail screens, scroll positions, sub-tabs, folder browser, and search state intact.
    var homeSelectedAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var homeSelectedArtistName by rememberSaveable { mutableStateOf<String?>(null) }

    var libSelectedAlbumId by rememberSaveable {
        val savedId = prefs.getLong(PREF_SAVED_ALBUM_ID, -1L)
        mutableStateOf<Long?>(if (savedId != -1L) savedId else null)
    }
    var libSelectedArtistName by rememberSaveable { mutableStateOf<String?>(null) }
    var libSelectedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var libSelectedGenreName by rememberSaveable { mutableStateOf<String?>(null) }
    var libSelectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var libSelectedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }

    val lastSavedNavTabRef = remember { intArrayOf(selectedNavTab) }
    val lastSavedAlbumIdRef = remember { longArrayOf(libSelectedAlbumId ?: -1L) }
    SideEffect {
        if (lastSavedNavTabRef[0] != selectedNavTab) {
            lastSavedNavTabRef[0] = selectedNavTab
            prefs.edit().putInt(PREF_SAVED_NAV_TAB, selectedNavTab).apply()
        }
        val currentSavedAlbumId = libSelectedAlbumId ?: -1L
        if (lastSavedAlbumIdRef[0] != currentSavedAlbumId) {
            lastSavedAlbumIdRef[0] = currentSavedAlbumId
            prefs.edit().putLong(PREF_SAVED_ALBUM_ID, currentSavedAlbumId).apply()
        }
    }

    val currentSong by viewModel.currentSong.collectAsState()
    val trackDirection by viewModel.trackDirection.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val keepScreenOnInPlayer by viewModel.keepScreenOnInPlayer.collectAsState()
    val openNowPlayingRequest by viewModel.openNowPlayingRequest.collectAsState()
    val view = LocalView.current

    var settledSheetOpen by remember { mutableStateOf(showNowPlayingSheet) }
    var isSheetFullyCovering by remember { mutableStateOf(showNowPlayingSheet) }
    val nowPlayingAnim = remember { Animatable(if (showNowPlayingSheet) 1f else 0f) }
    val nowPlayingDragFractionState = remember { mutableFloatStateOf(0f) }
    val nowPlayingBackState = rememberPredictiveBackState()
    LaunchedEffect(currentSong) {
        if (currentSong == null && (showNowPlayingSheet || isSheetFullyCovering || settledSheetOpen)) {
            showNowPlayingSheet = false
            isSheetFullyCovering = false
            settledSheetOpen = false
            nowPlayingDragFractionState.floatValue = 0f
            nowPlayingBackState.reset()
        }
    }
    LaunchedEffect(showNowPlayingSheet) {
        if (showNowPlayingSheet) {
            isSheetFullyCovering = false
            nowPlayingDragFractionState.floatValue = 0f
            nowPlayingBackState.reset()
            nowPlayingAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = NOW_PLAYING_ENTER_MS, easing = M3Motion.SheetDecelerate)
            )
            isSheetFullyCovering = true
            settledSheetOpen = true
        } else {
            isSheetFullyCovering = false
            nowPlayingAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = NOW_PLAYING_EXIT_MS, easing = M3Motion.SheetAccelerate)
            )
            settledSheetOpen = false
            nowPlayingDragFractionState.floatValue = 0f
            nowPlayingBackState.reset()
        }
    }

    var isSettingsFullyCovering by remember { mutableStateOf(showSettingsScreen) }
    val settingsAnim = remember { Animatable(if (showSettingsScreen) 1f else 0f) }
    val settingsBackState = rememberPredictiveBackState()
    LaunchedEffect(showSettingsScreen) {
        if (showSettingsScreen) {
            isSettingsFullyCovering = false
            settingsBackState.reset()
            settingsAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SETTINGS_ENTER_MS, easing = M3Motion.EmphasizedDecelerate)
            )
            isSettingsFullyCovering = true
        } else {
            isSettingsFullyCovering = false
            settingsAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = OVERLAY_EXIT_MS, easing = M3Motion.EmphasizedAccelerate)
            )
            settingsBackState.reset()
        }
    }
    // Use derivedStateOf so per-frame drag/animation ticks never trigger MainScreen recomposition;
    // reveal the base layer immediately when a predictive back gesture starts peeling back an overlay.
    val isBaseLayerHidden by remember {
        derivedStateOf {
            (isSheetFullyCovering &&
                nowPlayingDragFractionState.floatValue <= ACTIVE_ANIM_THRESHOLD &&
                nowPlayingBackState.progress <= ACTIVE_ANIM_THRESHOLD) ||
                (isSettingsFullyCovering && settingsBackState.progress <= ACTIVE_ANIM_THRESHOLD)
        }
    }

    LaunchedEffect(openNowPlayingRequest) {
        if (openNowPlayingRequest) {
            if (showSettingsScreen) {
                showSettingsScreen = false
                isSettingsFullyCovering = false
                settingsAnim.snapTo(0f)
                settingsBackState.reset()
                selectedNavTab = NAV_TAB_HOME
                homeSelectedAlbumId = null
                homeSelectedArtistName = null
            }
            showNowPlayingSheet = true
            viewModel.consumeOpenNowPlayingRequest()
        }
    }

    DisposableEffect(showNowPlayingSheet, keepScreenOnInPlayer) {
        view.keepScreenOn = showNowPlayingSheet && keepScreenOnInPlayer
        onDispose {
            view.keepScreenOn = false
        }
    }

    val isAnyHomeDetailOpen = homeSelectedAlbumId != null || homeSelectedArtistName != null
    val isAnyLibDetailOpen = libSelectedAlbumId != null || libSelectedArtistName != null ||
        libSelectedFolderPath != null || libSelectedGenreName != null ||
        libSelectedYear != null || libSelectedPlaylistId != null

    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val bottomSystemPadding = safeInsets.asPaddingValues().calculateBottomPadding()
    val bottomContentClearance = if (layout.useNavigationRail) {
        64.dp + bottomSystemPadding + 10.dp
    } else {
        64.dp + 80.dp + bottomSystemPadding + 10.dp
    }

    val onSelectHomeTab: () -> Unit = {
        if (selectedNavTab == NAV_TAB_HOME && isAnyHomeDetailOpen) {
            homeSelectedAlbumId = null
            homeSelectedArtistName = null
        }
        selectedNavTab = NAV_TAB_HOME
    }

    val onSelectLibraryTab: () -> Unit = {
        selectedNavTab = NAV_TAB_LIBRARY
    }

    // Sync system bar tint only after the sheet settles to prevent WindowManager jank mid-transition
    var nowPlayingStatusBarDarkOverride by remember { mutableStateOf<Boolean?>(null) }
    var nowPlayingNavBarDarkOverride by remember { mutableStateOf<Boolean?>(null) }
    SystemBarsColorController(
        statusBarColor = MaterialTheme.colorScheme.surface,
        navBarColor = if (settledSheetOpen) {
            MaterialTheme.colorScheme.surface
        } else if (layout.useNavigationRail) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        darkTheme = darkTheme,
        statusBarDarkOverride = if (settledSheetOpen) nowPlayingStatusBarDarkOverride else null,
        navBarDarkOverride = if (settledSheetOpen) nowPlayingNavBarDarkOverride else null,
        hideSystemBars = showNowPlayingSheet && !showSettingsScreen && layout.isLandscape
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Park the base layer off-screen once an opaque overlay covers the viewport to avoid GPU overdraw
        CompositionLocalProvider(
            LocalBottomContentPadding provides bottomContentClearance,
            LocalHasLeftRail provides layout.useNavigationRail
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (isBaseLayerHidden) 0f else 1f
                        translationX = if (isBaseLayerHidden) OFFSCREEN_TRANSLATION_PX else 0f
                    }
            ) {
                if (layout.useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        windowInsets = safeInsets.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        NavigationRailItem(
                            selected = selectedNavTab == NAV_TAB_HOME,
                            onClick = onSelectHomeTab,
                            icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                            label = if (!layout.isShortLandscape || layout.screenHeightDp >= 360) {
                                { Text(stringResource(R.string.nav_home), maxLines = 1) }
                            } else null,
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                        Spacer(modifier = Modifier.height(if (layout.isShortLandscape) 8.dp else 16.dp))
                        NavigationRailItem(
                            selected = selectedNavTab == NAV_TAB_LIBRARY,
                            onClick = onSelectLibraryTab,
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                            label = if (!layout.isShortLandscape || layout.screenHeightDp >= 360) {
                                { Text(stringResource(R.string.nav_library), maxLines = 1) }
                            } else null,
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Keep Home and Library (along with each tab's detail stack) composed simultaneously
                    // so switching tabs preserves scroll offsets, sub-tabs, and open detail screens.
                    Box(modifier = Modifier.fillMaxSize()) {
                        val isHomeTabVisible = !isBaseLayerHidden && selectedNavTab == NAV_TAB_HOME
                        val isLibraryTabVisible = !isBaseLayerHidden && selectedNavTab == NAV_TAB_LIBRARY

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val vis = selectedNavTab == NAV_TAB_HOME
                                    alpha = if (vis) 1f else 0f
                                    translationX = if (vis) 0f else OFFSCREEN_TRANSLATION_PX
                                }
                        ) {
                            CompositionLocalProvider(LocalScreenVisible provides (isHomeTabVisible && !isAnyHomeDetailOpen)) {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onSongSelected = { song -> viewModel.playSong(song) },
                                    onNavigateToLibraryAlbums = {
                                        libSelectedAlbumId = null
                                        libSelectedArtistName = null
                                        libSelectedFolderPath = null
                                        libSelectedGenreName = null
                                        libSelectedYear = null
                                        libSelectedPlaylistId = null
                                        requestedLibraryTab = LIBRARY_TAB_ALBUMS
                                        selectedNavTab = NAV_TAB_LIBRARY
                                    },
                                    onAlbumClick = { album -> homeSelectedAlbumId = album.id },
                                    onArtistClick = { artist -> homeSelectedArtistName = artist.name },
                                    onNavigateToSettings = { showSettingsScreen = true },
                                    onRequestPermission = onRequestPermission
                                )
                            }
                            CompositionLocalProvider(LocalScreenVisible provides isHomeTabVisible) {
                                DetailScreensOverlay(
                                    viewModel = viewModel,
                                    isTabActive = selectedNavTab == NAV_TAB_HOME && !showNowPlayingSheet && !showSettingsScreen,
                                    selectedAlbumId = homeSelectedAlbumId,
                                    selectedArtistName = homeSelectedArtistName,
                                    selectedFolderPath = null,
                                    selectedGenreName = null,
                                    selectedYear = null,
                                    selectedPlaylistId = null,
                                    onClearAlbum = { homeSelectedAlbumId = null },
                                    onClearArtist = { homeSelectedArtistName = null },
                                    onClearFolder = {},
                                    onClearGenre = {},
                                    onClearYear = {},
                                    onClearPlaylist = {},
                                    onOpenAlbum = { albumId -> homeSelectedAlbumId = albumId },
                                    onOpenArtist = { artistName ->
                                        homeSelectedAlbumId = null
                                        homeSelectedArtistName = artistName
                                    }
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val vis = selectedNavTab == NAV_TAB_LIBRARY
                                    alpha = if (vis) 1f else 0f
                                    translationX = if (vis) 0f else OFFSCREEN_TRANSLATION_PX
                                }
                        ) {
                            CompositionLocalProvider(LocalScreenVisible provides (isLibraryTabVisible && !isAnyLibDetailOpen)) {
                                MusicLibraryScreen(
                                    viewModel = viewModel,
                                    requestedTab = requestedLibraryTab,
                                    onRequestedTabHandled = { requestedLibraryTab = null },
                                    onSongSelected = { song -> viewModel.playSong(song) },
                                    onAlbumClick = { album -> libSelectedAlbumId = album.id },
                                    onArtistClick = { artist -> libSelectedArtistName = artist.name },
                                    onFolderClick = { folder -> libSelectedFolderPath = folder.path },
                                    onGenreClick = { genre -> libSelectedGenreName = genre.name },
                                    onYearClick = { yg -> libSelectedYear = yg.year },
                                    onPlaylistClick = { pl -> libSelectedPlaylistId = pl.id },
                                    isActiveOnTop = selectedNavTab == NAV_TAB_LIBRARY &&
                                        !showNowPlayingSheet && !showSettingsScreen && !isAnyLibDetailOpen
                                )
                            }
                            CompositionLocalProvider(LocalScreenVisible provides isLibraryTabVisible) {
                                DetailScreensOverlay(
                                    viewModel = viewModel,
                                    isTabActive = selectedNavTab == NAV_TAB_LIBRARY && !showNowPlayingSheet && !showSettingsScreen,
                                    selectedAlbumId = libSelectedAlbumId,
                                    selectedArtistName = libSelectedArtistName,
                                    selectedFolderPath = libSelectedFolderPath,
                                    selectedGenreName = libSelectedGenreName,
                                    selectedYear = libSelectedYear,
                                    selectedPlaylistId = libSelectedPlaylistId,
                                    onClearAlbum = { libSelectedAlbumId = null },
                                    onClearArtist = { libSelectedArtistName = null },
                                    onClearFolder = { libSelectedFolderPath = null },
                                    onClearGenre = { libSelectedGenreName = null },
                                    onClearYear = { libSelectedYear = null },
                                    onClearPlaylist = { libSelectedPlaylistId = null },
                                    onOpenAlbum = { albumId -> libSelectedAlbumId = albumId },
                                    onOpenArtist = { artistName ->
                                        libSelectedAlbumId = null
                                        libSelectedArtistName = artistName
                                    }
                                )
                            }
                        }
                    }

                    // Bottom dock anchors MiniPlayer above NavigationBar on compact portrait screens
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Transparent)
                    ) {
                        MiniPlayer(
                            song = currentSong,
                            isPlaying = isPlaying,
                            progressFlow = viewModel.playbackProgress,
                            onTogglePlay = { viewModel.togglePlayPause() },
                            onStopAndReset = { viewModel.stopAndReset() },
                            onPrevious = { viewModel.skipPrevious() },
                            onNext = { viewModel.skipNext() },
                            onClick = { if (currentSong != null && !showNowPlayingSheet) showNowPlayingSheet = true },
                            trackDirection = trackDirection,
                            isActive = !isBaseLayerHidden,
                            showPreviousButton = layout.isTabletOrExpanded,
                            contentWindowInsets = if (layout.useNavigationRail) {
                                safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)
                            } else {
                                safeInsets.only(WindowInsetsSides.Horizontal)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (!layout.useNavigationRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 0.dp,
                                windowInsets = safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            ) {
                                NavigationBarItem(
                                    selected = selectedNavTab == NAV_TAB_HOME,
                                    onClick = onSelectHomeTab,
                                    icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) },
                                    label = {
                                        Text(
                                            text = stringResource(R.string.nav_home),
                                            maxLines = 1
                                        )
                                    },
                                    alwaysShowLabel = true,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )

                                NavigationBarItem(
                                    selected = selectedNavTab == NAV_TAB_LIBRARY,
                                    onClick = onSelectLibraryTab,
                                    icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.nav_library)) },
                                    label = {
                                        Text(
                                            text = stringResource(R.string.nav_library),
                                            maxLines = 1
                                        )
                                    },
                                    alwaysShowLabel = true,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Mount NowPlaying as a GPU-only slide flyout; derivedStateOf + graphicsLayer prevent per-frame recomposition
        val activeSheetSong = currentSong
        val isSheetActive by remember {
            derivedStateOf { showNowPlayingSheet || nowPlayingAnim.value > ACTIVE_ANIM_THRESHOLD }
        }
        if (isSheetActive && activeSheetSong != null) {
            PredictiveBackEffect(
                enabled = showNowPlayingSheet && !showSettingsScreen,
                state = nowPlayingBackState,
                onBack = { showNowPlayingSheet = false }
            )
            val scrimInteraction = remember { MutableInteractionSource() }
            Box(modifier = Modifier.fillMaxSize()) {
                // Static dimmed scrim modulated strictly in GPU draw phase (zero composition per frame)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (
                                nowPlayingAnim.value *
                                    (1f - nowPlayingDragFractionState.floatValue) *
                                    (1f - nowPlayingBackState.progress)
                                ).coerceIn(0f, 1f)
                        }
                        .background(Color.Black.copy(alpha = NOW_PLAYING_SCRIM_MAX_ALPHA))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            onClick = { showNowPlayingSheet = false }
                        )
                )

                // Full-screen NowPlaying sliding smoothly from bottom like a flyout
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = (1f - nowPlayingAnim.value) * size.height
                        }
                        .predictiveBackSheet(nowPlayingBackState)
                ) {
                    NowPlayingOverlaySlot(
                        song = activeSheetSong,
                        isPlaying = isPlaying,
                        trackDirection = trackDirection,
                        isSheetActive = isSheetActive,
                        viewModel = viewModel,
                        onDragDismissFractionChanged = { fraction ->
                            nowPlayingDragFractionState.floatValue = fraction
                        },
                        onSystemBarsDarkOverrideChanged = { isStatusDark, isNavDark ->
                            nowPlayingStatusBarDarkOverride = isStatusDark
                            nowPlayingNavBarDarkOverride = isNavDark
                        },
                        onDismissRequest = { showNowPlayingSheet = false },
                        onNavigateToAlbum = { albumId ->
                            showNowPlayingSheet = false
                            if (selectedNavTab == NAV_TAB_HOME) {
                                homeSelectedAlbumId = albumId
                            } else {
                                libSelectedAlbumId = albumId
                            }
                        },
                        onNavigateToArtist = { artistName ->
                            showNowPlayingSheet = false
                            if (selectedNavTab == NAV_TAB_HOME) {
                                homeSelectedAlbumId = null
                                homeSelectedArtistName = artistName
                            } else {
                                libSelectedAlbumId = null
                                libSelectedFolderPath = null
                                libSelectedGenreName = null
                                libSelectedYear = null
                                libSelectedPlaylistId = null
                                libSelectedArtistName = artistName
                            }
                        }
                    )
                }
            }
        }

        // Mount Settings overlay on demand and release its subtree & observers once closed
        val isSettingsActive by remember {
            derivedStateOf { showSettingsScreen || settingsAnim.value > ACTIVE_ANIM_THRESHOLD }
        }
        if (isSettingsActive) {
            PredictiveBackEffect(
                enabled = showSettingsScreen,
                state = settingsBackState,
                onBack = { showSettingsScreen = false }
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = settingsBackState.progress
                            alpha = if (p > ACTIVE_ANIM_THRESHOLD) {
                                0.24f * (1f - p) * settingsAnim.value
                            } else {
                                0f
                            }
                        }
                        .background(Color.Black)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = settingsAnim.value
                        }
                        .predictiveBackScreen(settingsBackState)
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onBackClick = { showSettingsScreen = false },
                        isActive = isSettingsActive
                    )
                }
            }
        }

        // Floating hardware-step HUD shown when physical volume keys control the USB DAC
        BitPerfectVolumeHud(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 10.dp, start = 20.dp, end = 20.dp)
                .widthIn(max = 460.dp)
        )
    }
}

// ═══ Overlay & HUD Slots ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitPerfectVolumeHud(
    modifier: Modifier = Modifier
) {
    val usbState by UsbDacManager.state.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }
    var isSliding by remember { mutableStateOf(false) }

    LaunchedEffect(usbState.volumeHudTick, isSliding) {
        if (usbState.volumeHudTick > 0L) {
            visible = true
            if (!isSliding) {
                delay(VOLUME_HUD_TIMEOUT_MS)
                visible = false
            }
        }
    }

    val displayVolume by animateFloatAsState(
        targetValue = usbState.volumeStep.toFloat(),
        animationSpec = if (isSliding) snap() else tween(durationMillis = VOLUME_HUD_STEP_ANIM_MS, easing = M3Motion.Standard),
        label = "BitPerfectHudSlider"
    )

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = M3Motion.hudEnter(),
        exit = M3Motion.hudExit()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Bit-Perfect",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${usbState.volumeStep} / ${usbState.maxVolumeSteps}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = displayVolume,
                    onValueChange = {
                        isSliding = true
                        UsbDacManager.setVolumeStep(it.roundToInt(), showHud = true, source = "hud_slider")
                    },
                    onValueChangeFinished = {
                        isSliding = false
                    },
                    valueRange = 0f..usbState.maxVolumeSteps.toFloat(),
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            drawStopIndicator = null,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DetailScreensOverlay(
    viewModel: MusicViewModel,
    isTabActive: Boolean,
    selectedAlbumId: Long?,
    selectedArtistName: String?,
    selectedFolderPath: String?,
    selectedGenreName: String?,
    selectedYear: Int?,
    selectedPlaylistId: Long?,
    onClearAlbum: () -> Unit,
    onClearArtist: () -> Unit,
    onClearFolder: () -> Unit,
    onClearGenre: () -> Unit,
    onClearYear: () -> Unit,
    onClearPlaylist: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    // Collect each library StateFlow strictly on-demand when its corresponding detail screen is open;
    // AnimatedDetailSlot caches the last non-null target during exit fade so closing incurs zero reads.
    val selectedArtist = if (selectedArtistName != null) {
        val artists by viewModel.artists.collectAsState()
        remember(artists, selectedArtistName) {
            artists.find { it.name.equals(selectedArtistName, ignoreCase = true) }
        }
    } else null

    val selectedFolder = if (selectedFolderPath != null) {
        val folders by viewModel.folders.collectAsState()
        remember(folders, selectedFolderPath) { folders.find { it.path == selectedFolderPath } }
    } else null

    val selectedGenre = if (selectedGenreName != null) {
        val genres by viewModel.genres.collectAsState()
        remember(genres, selectedGenreName) {
            genres.find { it.name.equals(selectedGenreName, ignoreCase = true) }
        }
    } else null

    val selectedYearGroup = if (selectedYear != null) {
        val yearGroups by viewModel.yearGroups.collectAsState()
        remember(yearGroups, selectedYear) { yearGroups.find { it.year == selectedYear } }
    } else null

    val selectedPlaylist = if (selectedPlaylistId != null) {
        val playlists by viewModel.playlists.collectAsState()
        remember(playlists, selectedPlaylistId) { playlists.find { it.id == selectedPlaylistId } }
    } else null

    val selectedAlbum = if (selectedAlbumId != null) {
        val albums by viewModel.albums.collectAsState()
        remember(albums, selectedAlbumId) { albums.find { it.id == selectedAlbumId } }
    } else null

    AnimatedDetailSlot(
        target = selectedArtist,
        onBack = onClearArtist,
        backEnabled = isTabActive
    ) { artist ->
        ArtistDetailScreen(
            artist = artist,
            viewModel = viewModel,
            onBackClick = onClearArtist,
            onNavigateToAlbum = { album -> onOpenAlbum(album.id) }
        )
    }

    AnimatedDetailSlot(
        target = selectedFolder,
        onBack = onClearFolder,
        backEnabled = isTabActive
    ) { folder ->
        FolderDetailScreen(
            folder = folder,
            viewModel = viewModel,
            onBackClick = onClearFolder
        )
    }

    AnimatedDetailSlot(
        target = selectedGenre,
        onBack = onClearGenre,
        backEnabled = isTabActive
    ) { genre ->
        GenreDetailScreen(
            genre = genre,
            viewModel = viewModel,
            onBackClick = onClearGenre
        )
    }

    AnimatedDetailSlot(
        target = selectedYearGroup,
        onBack = onClearYear,
        backEnabled = isTabActive
    ) { yearGroup ->
        YearDetailScreen(
            yearGroup = yearGroup,
            viewModel = viewModel,
            onBackClick = onClearYear
        )
    }

    AnimatedDetailSlot(
        target = selectedPlaylist,
        onBack = onClearPlaylist,
        backEnabled = isTabActive
    ) { playlist ->
        PlaylistDetailScreen(
            playlist = playlist,
            viewModel = viewModel,
            onBackClick = onClearPlaylist
        )
    }

    AnimatedDetailSlot(
        target = selectedAlbum,
        onBack = onClearAlbum,
        backEnabled = isTabActive
    ) { album ->
        AlbumDetailScreen(
            album = album,
            viewModel = viewModel,
            onBackClick = onClearAlbum,
            onArtistClick = { artistName ->
                if (viewModel.artists.value.any { it.name.equals(artistName, ignoreCase = true) }) {
                    onOpenArtist(artistName)
                }
            }
        )
    }
}

@Composable
private fun NowPlayingOverlaySlot(
    song: Song,
    isPlaying: Boolean,
    trackDirection: Int,
    isSheetActive: Boolean,
    viewModel: MusicViewModel,
    onDragDismissFractionChanged: (Float) -> Unit,
    onSystemBarsDarkOverrideChanged: (Boolean?, Boolean?) -> Unit,
    onDismissRequest: () -> Unit,
    onNavigateToAlbum: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit
) {
    // Freeze offscreen collection when NowPlayingSheet is hidden to avoid background recomposition
    val isLiked by remember(isSheetActive) {
        if (isSheetActive) viewModel.isLiked else MutableStateFlow(viewModel.isLiked.value)
    }.collectAsState()
    val isShuffle by remember(isSheetActive) {
        if (isSheetActive) viewModel.isShuffle else MutableStateFlow(viewModel.isShuffle.value)
    }.collectAsState()
    val repeatMode by remember(isSheetActive) {
        if (isSheetActive) viewModel.repeatMode else MutableStateFlow(viewModel.repeatMode.value)
    }.collectAsState()
    val currentQueue by remember(isSheetActive) {
        if (isSheetActive) viewModel.currentQueue else MutableStateFlow(viewModel.currentQueue.value)
    }.collectAsState()
    val queueSource by remember(isSheetActive) {
        if (isSheetActive) viewModel.queueSource else MutableStateFlow(viewModel.queueSource.value)
    }.collectAsState()

    val onProgressChange = remember(viewModel) { { progress: Float -> viewModel.seekToProgress(progress) } }
    val onTogglePlay = remember(viewModel) { { viewModel.togglePlayPause() } }
    val onStopAndReset = remember(viewModel) { { viewModel.stopAndReset() } }
    val onPrevious = remember(viewModel) { { viewModel.skipPrevious() } }
    val onNext = remember(viewModel) { { viewModel.skipNext() } }
    val onToggleLike = remember(viewModel) { { viewModel.toggleLike() } }
    val onToggleShuffle = remember(viewModel) { { viewModel.toggleShuffle() } }
    val onToggleRepeat = remember(viewModel) { { viewModel.toggleRepeat() } }
    val onSelectFromQueue = remember(viewModel) { { s: Song -> viewModel.playSongFromQueue(s) } }
    val onReorderQueue = remember(viewModel) { { from: Int, to: Int -> viewModel.reorderQueue(from, to) } }
    val onRemoveFromQueue = remember(viewModel) { { s: Song -> viewModel.removeFromQueue(s) } }

    NowPlayingSheet(
        song = song,
        audioSpecsFlow = viewModel.currentAudioSpecs,
        isPlaying = isPlaying,
        progressFlow = viewModel.playbackProgress,
        currentPositionMsFlow = viewModel.currentPosition,
        isLiked = isLiked,
        isShuffle = isShuffle,
        repeatMode = repeatMode,
        queue = currentQueue,
        queueSource = queueSource,
        onProgressChange = onProgressChange,
        onTogglePlay = onTogglePlay,
        onStopAndReset = onStopAndReset,
        onPrevious = onPrevious,
        onNext = onNext,
        onToggleLike = onToggleLike,
        onToggleShuffle = onToggleShuffle,
        onToggleRepeat = onToggleRepeat,
        onSelectSongFromQueue = onSelectFromQueue,
        onReorderQueue = onReorderQueue,
        onRemoveFromQueue = onRemoveFromQueue,
        onDismissRequest = onDismissRequest,
        onDragDismissFractionChanged = onDragDismissFractionChanged,
        onSystemBarsDarkOverrideChanged = onSystemBarsDarkOverrideChanged,
        viewModel = viewModel,
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToArtist = onNavigateToArtist,
        trackDirection = trackDirection,
        isSheetActive = isSheetActive
    )
}
