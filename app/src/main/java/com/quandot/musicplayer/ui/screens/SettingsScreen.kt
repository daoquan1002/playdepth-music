/**
 * SettingsScreen — Application preferences, USB DAC tuning, library maintenance, backup/restore, and log export.
 *
 * @remarks
 * Root settings screen displays the PlayDepth identity card (`AboutHeaderCard`) followed by six
 * dedicated category buttons (`Appearance`, `Playback Options`, `Lyrics`, `Library Management`,
 * `Debugging`, and `Backup & Restore`) that open full-screen sub-pages via [AnimatedDetailSlot].
 * When [isActive] is `false` while parked off-screen by [MainScreen], rapidly updating flows
 * (USB DAC telemetry, log statistics, and library counts) are wrapped in snapshot [MutableStateFlow]s
 * to eliminate off-screen recomposition churn.
 *
 * @see MainScreen
 * @see UsbDacManager
 * @see AppLogManager
 * @see MusicViewModel
 */
package com.quandot.musicplayer.ui.screens

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.BuildConfig
import com.quandot.musicplayer.R
import com.quandot.musicplayer.audio.UsbDacNative
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog
import com.quandot.musicplayer.ui.AudioOutputUiState
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.components.ExpressiveLoadingIndicator
import com.quandot.musicplayer.ui.components.usbDacStatisticsItems

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.LibraryScanState
import com.quandot.musicplayer.model.NowPlayingBlendStyle
import com.quandot.musicplayer.model.NowPlayingSeekbarStyle
import com.quandot.musicplayer.model.NowPlayingStyle
import com.quandot.musicplayer.ui.theme.AnimatedDetailSlot
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.systemSafeInsets

// ═══ Constants ═══
private const val MIME_TYPE_TEXT_PLAIN = "text/plain"
private const val MIME_TYPE_JSON = "application/json"

private const val THEME_SYSTEM = "system"
private const val THEME_LIGHT = "light"
private const val THEME_DARK = "dark"

private const val LYRICS_FONT_SMALL = "small"
private const val LYRICS_FONT_MEDIUM = "medium"
private const val LYRICS_FONT_LARGE = "large"
private const val LYRICS_ALIGN_LEFT = "left"
private const val LYRICS_ALIGN_CENTER = "center"

private const val DEFAULT_MIN_DURATION_SEC = 10
private const val DEFAULT_USB_WARMUP_MS = 500
private const val DEFAULT_USB_IDLE_TIMEOUT_SEC = 15
private const val DEFAULT_USB_MAX_VOLUME_STEPS = 100

private const val WIDE_LAYOUT_BREAKPOINT_DP = 820
private const val WIDE_CONTENT_MAX_WIDTH_DP = 900

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L

private val DURATION_FILTER_OPTIONS_SEC = listOf(0, 10, 15, 30, 60)
private val USB_WARMUP_OPTIONS_MS = listOf(0, 300, 500, 1000, 2000)
private val USB_WARMUP_LABELS = listOf("0s", "0.3s", "0.5s", "1s", "2s")
private val USB_IDLE_TIMEOUT_OPTIONS_SEC = listOf(0, 5, 15, 30, 60)
private val USB_IDLE_TIMEOUT_LABELS = listOf("0s", "5s", "15s", "30s", "60s")
private val USB_VOLUME_STEP_OPTIONS = listOf(20, 40, 50, 100)
private val USB_VOLUME_STEP_LABELS = listOf("20", "40", "50", "100")
private val USB_DSD_MODE_OPTIONS = listOf(
    UsbDacNative.DSD_MODE_AUTO,
    UsbDacNative.DSD_MODE_NATIVE,
    UsbDacNative.DSD_MODE_DOP,
    UsbDacNative.DSD_MODE_PCM_DECIMATE
)
private val USB_DSD_GAIN_OPTIONS_DB = listOf(0.0f, 3.0f, 6.0f)
private val USB_DSD_GAIN_LABELS = listOf("0 dB", "+3 dB", "+6 dB")
private val USB_RESAMPLER_QUALITY_OPTIONS = listOf(
    UsbDacNative.RESAMPLER_QUALITY_MEDIUM,
    UsbDacNative.RESAMPLER_QUALITY_HIGH,
    UsbDacNative.RESAMPLER_QUALITY_ULTRA
)

private enum class SettingsSubPage {
    APPEARANCE,
    PLAYBACK,
    LYRICS,
    LIBRARY,
    DEBUG,
    BACKUP_RESTORE
}

// ═══ Settings Screen ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    onBackClick: () -> Unit,
    isActive: Boolean = true
) {
    val context = LocalContext.current
    val audioOutputState by remember(isActive, viewModel) {
        if (isActive) viewModel.audioOutputState else MutableStateFlow(viewModel.audioOutputState.value)
    }.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val songDynamicColor by viewModel.songDynamicColor.collectAsStateWithLifecycle()
    val amoledMode by viewModel.amoledMode.collectAsStateWithLifecycle()
    val nowPlayingStyle by viewModel.nowPlayingStyle.collectAsStateWithLifecycle()
    val nowPlayingBlendStyle by viewModel.nowPlayingBlendStyle.collectAsStateWithLifecycle()
    val nowPlayingSeekbarStyle by viewModel.nowPlayingSeekbarStyle.collectAsStateWithLifecycle()
    val minAudioDurationSec by viewModel.minAudioDurationSec.collectAsStateWithLifecycle()
    val excludedFolders by viewModel.excludedFolders.collectAsStateWithLifecycle()
    val customScanFolders by viewModel.customScanFolders.collectAsStateWithLifecycle()
    val safScanUris by viewModel.safScanUris.collectAsStateWithLifecycle()
    val autoPlayOnHeadset by viewModel.autoPlayOnHeadset.collectAsStateWithLifecycle()
    val keepScreenOnInPlayer by viewModel.keepScreenOnInPlayer.collectAsStateWithLifecycle()
    val lyricsFontSize by viewModel.lyricsFontSize.collectAsStateWithLifecycle()
    val lyricsAlignment by viewModel.lyricsAlignment.collectAsStateWithLifecycle()
    val lyricsAutoScroll by viewModel.lyricsAutoScroll.collectAsStateWithLifecycle()
    val lyricsDelayNormalMs by viewModel.lyricsDelayNormalMs.collectAsStateWithLifecycle()
    val lyricsDelayBluetoothMs by viewModel.lyricsDelayBluetoothMs.collectAsStateWithLifecycle()
    val lyricsDelayBitPerfectMs by viewModel.lyricsDelayBitPerfectMs.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val allSongs by remember(isActive) {
        if (isActive) viewModel.allSongs else MutableStateFlow(viewModel.allSongs.value)
    }.collectAsStateWithLifecycle()
    val albums by remember(isActive) {
        if (isActive) viewModel.albums else MutableStateFlow(viewModel.albums.value)
    }.collectAsStateWithLifecycle()
    val debugLogStats by remember(isActive) {
        if (isActive) AppLogManager.stats else MutableStateFlow(AppLogManager.stats.value)
    }.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var showAboutScreen by remember { mutableStateOf(false) }
    var showUsbDacStatsScreen by remember { mutableStateOf(false) }
    var activeSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    var showExcludedFoldersDialog by remember { mutableStateOf(false) }
    var showCustomScanFoldersDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    var logPreviewText by remember { mutableStateOf<String?>(null) }
    var isExportingLogs by remember { mutableStateOf(false) }
    var isProcessingBackup by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            showAboutScreen = false
            showUsbDacStatsScreen = false
            activeSubPage = null
        }
    }

    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isIgnoringBatteryOpt by remember(powerManager) {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isActive, powerManager) {
        if (isActive) {
            isIgnoringBatteryOpt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOpt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickExcludedFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val resolvedPath = resolveDocumentTreeToFilePath(treeUri)
        if (!resolvedPath.isNullOrBlank()) {
            viewModel.addExcludedFolder(resolvedPath)
        }
    }

    val pickCustomScanFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val resolvedPath = resolveDocumentTreeToFilePath(treeUri)
        viewModel.addCustomScanFolder(folderPath = resolvedPath, safTreeUri = treeUri)
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_TYPE_TEXT_PLAIN)
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isExportingLogs = true
            try {
                val report = withContext(Dispatchers.IO) {
                    AppLogManager.buildFullDiagnosticReport(context)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(report.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
                Toast.makeText(context, context.getString(R.string.toast_logs_saved), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                PDLog.e(PDLog.UI, "LOG_SAVE_ERR", "err=${t.message}", t)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_logs_error, t.message ?: "error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isExportingLogs = false
            }
        }
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_TYPE_JSON)
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isProcessingBackup = true
            try {
                val backupJson = viewModel.buildBackupJsonString()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(backupJson.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot open output stream")
                }
                Toast.makeText(context, context.getString(R.string.toast_backup_saved), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                PDLog.e(PDLog.UI, "BACKUP_CREATE_ERR", "err=${t.message}", t)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_error, t.message ?: "error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isProcessingBackup = false
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isProcessingBackup = true
            try {
                val jsonContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("Cannot open input stream")
                }
                val (restoredPlaylists, restoredFavorites) = viewModel.restoreFromBackupJsonString(jsonContent)
                val playlistsText = context.resources.getQuantityString(
                    R.plurals.playlist_count,
                    restoredPlaylists,
                    restoredPlaylists
                )
                val favoritesText = context.resources.getQuantityString(
                    R.plurals.favorite_count,
                    restoredFavorites,
                    restoredFavorites
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_restored, playlistsText, favoritesText),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (t: Throwable) {
                PDLog.e(PDLog.UI, "BACKUP_RESTORE_ERR", "err=${t.message}", t)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_error, t.message ?: "error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isProcessingBackup = false
            }
        }
    }

    val shareDebugReport: () -> Unit = {
        coroutineScope.launch {
            isExportingLogs = true
            try {
                val shareIntent = withContext(Dispatchers.IO) {
                    AppLogManager.createShareLogIntent(context)
                }
                val chooser = Intent.createChooser(
                    shareIntent,
                    context.getString(R.string.share_log_chooser_title)
                )
                context.startActivity(chooser)
            } catch (t: Throwable) {
                PDLog.e(PDLog.UI, "LOG_SHARE_ERR", "err=${t.message}", t)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_logs_error, t.message ?: "error"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isExportingLogs = false
            }
        }
    }

    val listState = rememberLazyListState()
    val layout = rememberAdaptiveLayoutInfo()
    val safeInsets = systemSafeInsets()
    val contentSidePadding = if (layout.screenWidthDp > WIDE_LAYOUT_BREAKPOINT_DP) {
        ((layout.screenWidthDp - WIDE_CONTENT_MAX_WIDTH_DP) / 2).dp.coerceAtLeast(layout.horizontalPadding)
    } else {
        layout.horizontalPadding
    }
    val formattedLogSize = remember(debugLogStats.fileSizeBytes) {
        formatLogSize(debugLogStats.fileSizeBytes)
    }

    val layoutDir = LocalLayoutDirection.current
    val horizontalSafePad = safeInsets.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val safeStart = maxOf(horizontalSafePad.calculateStartPadding(layoutDir) + layout.horizontalPadding, contentSidePadding)
    val safeEnd = maxOf(horizontalSafePad.calculateEndPadding(layoutDir) + layout.horizontalPadding, contentSidePadding)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)),
                contentPadding = PaddingValues(
                    start = safeStart,
                    end = safeEnd,
                    top = 6.dp,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "about_header", contentType = "settings_about_header") {
                    AboutHeaderCard(
                        onClick = { showAboutScreen = true }
                    )
                }

                item(key = "cat_appearance", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_cat_appearance_title),
                        onClick = { activeSubPage = SettingsSubPage.APPEARANCE }
                    )
                }

                item(key = "cat_playback", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.Outlined.Tune,
                        title = stringResource(R.string.settings_cat_playback_title),
                        onClick = { activeSubPage = SettingsSubPage.PLAYBACK }
                    )
                }

                item(key = "cat_lyrics", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = stringResource(R.string.settings_cat_lyrics_title),
                        onClick = { activeSubPage = SettingsSubPage.LYRICS }
                    )
                }

                item(key = "cat_library", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.Outlined.Storage,
                        title = stringResource(R.string.settings_cat_library_title),
                        badgeText = pluralStringResource(R.plurals.settings_song_badge, allSongs.size, allSongs.size),
                        onClick = { activeSubPage = SettingsSubPage.LIBRARY }
                    )
                }

                item(key = "cat_debug", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_cat_debug_title),
                        badgeText = formattedLogSize,
                        onClick = { activeSubPage = SettingsSubPage.DEBUG }
                    )
                }

                item(key = "cat_backup_restore", contentType = "settings_category") {
                    SettingsCategoryButton(
                        icon = Icons.Outlined.SettingsBackupRestore,
                        title = stringResource(R.string.settings_cat_backup_title),
                        onClick = { activeSubPage = SettingsSubPage.BACKUP_RESTORE }
                    )
                }
            }
        }

        AnimatedDetailSlot(
            target = activeSubPage,
            onBack = { activeSubPage = null },
            backEnabled = isActive
        ) { subPage ->
            val pageTitle = when (subPage) {
                SettingsSubPage.APPEARANCE -> stringResource(R.string.settings_cat_appearance_title)
                SettingsSubPage.PLAYBACK -> stringResource(R.string.settings_cat_playback_title)
                SettingsSubPage.LYRICS -> stringResource(R.string.settings_cat_lyrics_title)
                SettingsSubPage.LIBRARY -> stringResource(R.string.settings_cat_library_title)
                SettingsSubPage.DEBUG -> stringResource(R.string.settings_cat_debug_title)
                SettingsSubPage.BACKUP_RESTORE -> stringResource(R.string.settings_cat_backup_title)
            }

            SettingsSubPageScaffold(
                title = pageTitle,
                safeInsets = safeInsets,
                safeStart = safeStart,
                safeEnd = safeEnd,
                onBackClick = { activeSubPage = null }
            ) {
                when (subPage) {
                    SettingsSubPage.APPEARANCE -> {
                        val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        item(key = "appearance_theme_color_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.Palette,
                                title = stringResource(R.string.settings_cat_appearance_title)
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = stringResource(R.string.settings_theme_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsSegmentedPill(
                                    options = listOf(
                                        stringResource(R.string.settings_theme_system),
                                        stringResource(R.string.settings_theme_light),
                                        stringResource(R.string.settings_theme_dark)
                                    ),
                                    selectedIndex = when (themeMode) {
                                        THEME_LIGHT -> 1
                                        THEME_DARK -> 2
                                        else -> 0
                                    },
                                    onSelect = { idx ->
                                        val mode = when (idx) {
                                            1 -> THEME_LIGHT
                                            2 -> THEME_DARK
                                            else -> THEME_SYSTEM
                                        }
                                        viewModel.setThemeMode(mode)
                                    }
                                )

                                if (supportsDynamicColor) {
                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = stringResource(R.string.settings_color_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SettingsSegmentedPill(
                                        options = listOf(
                                            stringResource(R.string.settings_color_dynamic),
                                            stringResource(R.string.settings_color_default)
                                        ),
                                        selectedIndex = if (dynamicColor) 0 else 1,
                                        onSelect = { idx ->
                                            viewModel.setDynamicColor(idx == 0)
                                        }
                                    )
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                                )

                                SettingsSwitchRow(
                                    icon = null,
                                    title = stringResource(R.string.settings_song_color_title),
                                    subtitle = stringResource(R.string.settings_song_color_desc),
                                    checked = songDynamicColor,
                                    onCheckedChange = { viewModel.setSongDynamicColor(it) }
                                )
                            }
                        }

                        item(key = "now_playing_style_card") {
                            val isTablet = layout.isTabletOrExpanded
                            SettingsSectionCard(
                                icon = Icons.Outlined.MusicNote,
                                title = stringResource(R.string.settings_now_playing_style_title),
                                badgeText = if (isTablet) stringResource(R.string.settings_now_playing_tablet_badge) else null
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))

                                SettingsSegmentedPill(
                                    options = listOf(
                                        stringResource(R.string.settings_now_playing_style_modern),
                                        stringResource(R.string.settings_now_playing_style_classic)
                                    ),
                                    selectedIndex = if (nowPlayingStyle == NowPlayingStyle.MODERN) 0 else 1,
                                    onSelect = { idx ->
                                        viewModel.setNowPlayingStyle(
                                            if (idx == 0) NowPlayingStyle.MODERN else NowPlayingStyle.CLASSIC
                                        )
                                    }
                                )

                                Text(
                                    text = stringResource(
                                        when {
                                            isTablet && nowPlayingStyle == NowPlayingStyle.MODERN ->
                                                R.string.settings_now_playing_style_modern_desc_tablet
                                            isTablet ->
                                                R.string.settings_now_playing_style_classic_desc_tablet
                                            nowPlayingStyle == NowPlayingStyle.MODERN ->
                                                R.string.settings_now_playing_style_modern_desc
                                            else ->
                                                R.string.settings_now_playing_style_classic_desc
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp)
                                )

                                // Phone-only artwork edge feathering (Tablet uses framed rounded artwork in both styles)
                                AnimatedVisibility(
                                    visible = nowPlayingStyle == NowPlayingStyle.MODERN && !isTablet,
                                    enter = M3Motion.simpleFadeIn(110),
                                    exit = M3Motion.simpleFadeOut(85)
                                ) {
                                    Column {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
                                        )

                                        Text(
                                            text = stringResource(R.string.settings_now_playing_blend_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SettingsSegmentedPill(
                                            options = listOf(
                                                stringResource(R.string.settings_now_playing_blend_linear),
                                                stringResource(R.string.settings_now_playing_blend_curved)
                                            ),
                                            selectedIndex = if (nowPlayingBlendStyle == NowPlayingBlendStyle.LINEAR) 0 else 1,
                                            onSelect = { idx ->
                                                viewModel.setNowPlayingBlendStyle(
                                                    if (idx == 0) NowPlayingBlendStyle.LINEAR else NowPlayingBlendStyle.CURVED
                                                )
                                            }
                                        )
                                        Text(
                                            text = stringResource(
                                                if (nowPlayingBlendStyle == NowPlayingBlendStyle.LINEAR) {
                                                    R.string.settings_now_playing_blend_linear_desc
                                                } else {
                                                    R.string.settings_now_playing_blend_curved_desc
                                                }
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 10.dp)
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
                                )

                                Text(
                                    text = stringResource(R.string.settings_now_playing_seekbar_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsSegmentedPill(
                                    options = listOf(
                                        stringResource(R.string.settings_now_playing_seekbar_slider),
                                        stringResource(R.string.settings_now_playing_seekbar_wavy)
                                    ),
                                    selectedIndex = if (nowPlayingSeekbarStyle == NowPlayingSeekbarStyle.SLIDER) 0 else 1,
                                    onSelect = { idx ->
                                        viewModel.setNowPlayingSeekbarStyle(
                                            if (idx == 0) NowPlayingSeekbarStyle.SLIDER else NowPlayingSeekbarStyle.WAVY
                                        )
                                    }
                                )
                                Text(
                                    text = stringResource(
                                        if (nowPlayingSeekbarStyle == NowPlayingSeekbarStyle.SLIDER) {
                                            R.string.settings_now_playing_seekbar_slider_desc
                                        } else {
                                            R.string.settings_now_playing_seekbar_wavy_desc
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }

                        item(key = "amoled_mode_card") {
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                                    SettingsSwitchRow(
                                        icon = Icons.Default.DarkMode,
                                        title = stringResource(R.string.settings_amoled_title),
                                        subtitle = stringResource(R.string.settings_amoled_desc),
                                        checked = amoledMode,
                                        onCheckedChange = { viewModel.setAmoledMode(it) }
                                    )
                                }
                            }
                        }
                    }

                    SettingsSubPage.PLAYBACK -> {
                        item(key = "playback_display_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.Tune,
                                title = stringResource(R.string.settings_cat_playback_title)
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                SettingsSwitchRow(
                                    icon = Icons.Default.Headset,
                                    title = stringResource(R.string.settings_autoplay_title),
                                    subtitle = stringResource(R.string.settings_autoplay_desc),
                                    checked = autoPlayOnHeadset,
                                    onCheckedChange = { viewModel.setAutoPlayOnHeadset(it) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Default.BrightnessHigh,
                                    title = stringResource(R.string.settings_keep_screen_title),
                                    subtitle = stringResource(R.string.settings_keep_screen_desc),
                                    checked = keepScreenOnInPlayer,
                                    onCheckedChange = { viewModel.setKeepScreenOnInPlayer(it) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.BatteryChargingFull,
                                    title = stringResource(R.string.settings_battery_opt_title),
                                    subtitle = stringResource(
                                        if (isIgnoringBatteryOpt) R.string.settings_battery_opt_exempt
                                        else R.string.settings_battery_opt_restricted
                                    ),
                                    iconTint = if (isIgnoringBatteryOpt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    onClick = {
                                        try {
                                            val reqIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(reqIntent)
                                        } catch (_: Throwable) {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        item(key = "usb_dac_card") {
                            SettingsSectionCard(
                                icon = Icons.Default.Usb,
                                title = stringResource(R.string.settings_usb_dac_section_title)
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                val usbDacSubtitle = if (!audioOutputState.directEnabled) {
                                    stringResource(R.string.settings_usb_dac_desc_disconnected)
                                } else {
                                    when (audioOutputState.dacStatus) {
                                        UsbDacStatus.STREAMING, UsbDacStatus.PAUSED -> {
                                            val modeText = audioOutputState.audioPathBadge.ifBlank {
                                                stringResource(
                                                    if (audioOutputState.dacStatus == UsbDacStatus.PAUSED) {
                                                        R.string.usb_stats_status_paused
                                                    } else {
                                                        R.string.usb_stats_status_streaming
                                                    }
                                                )
                                            }
                                            val wireText = audioOutputState.wireFormatLabel.ifBlank {
                                                val rateLabel = AudioSpecsHelper.formatKhz(audioOutputState.wireSampleRate)
                                                    .ifBlank { "${audioOutputState.wireSampleRate} Hz" }
                                                "$rateLabel / ${audioOutputState.wireBitDepth}-bit"
                                            }
                                            stringResource(
                                                R.string.settings_usb_dac_desc_streaming_mode,
                                                audioOutputState.deviceName,
                                                modeText,
                                                wireText
                                            )
                                        }
                                        UsbDacStatus.READY -> {
                                            val uacLabel = audioOutputState.uacVersionLabel.ifBlank { "USB" }
                                            stringResource(
                                                R.string.settings_usb_dac_desc_ready,
                                                audioOutputState.deviceName,
                                                uacLabel
                                            )
                                        }
                                        UsbDacStatus.PERMISSION_REQUIRED -> stringResource(
                                            R.string.settings_usb_dac_desc_permission,
                                            audioOutputState.deviceName
                                        )
                                        UsbDacStatus.ERROR -> "${audioOutputState.deviceName}: ${audioOutputState.errorMessage ?: stringResource(R.string.usb_stats_status_error)}"
                                        UsbDacStatus.DISCONNECTED -> stringResource(R.string.settings_usb_dac_desc_disconnected)
                                    }
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Default.Usb,
                                    title = stringResource(R.string.settings_usb_dac_title),
                                    subtitle = usbDacSubtitle,
                                    checked = audioOutputState.directEnabled,
                                    onCheckedChange = { viewModel.setUsbDirectEnabled(it) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Outlined.Analytics,
                                    title = stringResource(R.string.settings_usb_stats_btn_title),
                                    subtitle = null,
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        viewModel.refreshUsbDacTelemetry()
                                        showUsbDacStatsScreen = true
                                    }
                                )

                                AnimatedVisibility(
                                    visible = audioOutputState.directEnabled,
                                    enter = M3Motion.simpleFadeIn(110),
                                    exit = M3Motion.simpleFadeOut(85)
                                ) {
                                    Column {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                        SettingsSwitchRow(
                                            icon = null,
                                            title = stringResource(R.string.settings_usb_keep_alive_title),
                                            subtitle = stringResource(R.string.settings_usb_keep_alive_desc),
                                            checked = audioOutputState.keepAliveAudioEnabled,
                                            onCheckedChange = { viewModel.setUsbKeepAliveAudioEnabled(it) }
                                        )

                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                        )

                                        // DAC Warm-Up Time
                                        val selectedWarmUpIdx = USB_WARMUP_OPTIONS_MS.indexOf(audioOutputState.warmUpMs)
                                            .takeIf { it >= 0 }
                                            ?: USB_WARMUP_OPTIONS_MS.indices.minByOrNull {
                                                kotlin.math.abs(USB_WARMUP_OPTIONS_MS[it] - audioOutputState.warmUpMs)
                                            }
                                            ?: 2
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_usb_warmup_title),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = if (audioOutputState.warmUpMs == 0) "0s" else "${audioOutputState.warmUpMs} ms",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.settings_usb_warmup_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                        )
                                        SettingsSegmentedPill(
                                            options = USB_WARMUP_LABELS,
                                            selectedIndex = selectedWarmUpIdx,
                                            onSelect = { idx ->
                                                viewModel.setUsbWarmUpMs(
                                                    USB_WARMUP_OPTIONS_MS.getOrElse(idx) { DEFAULT_USB_WARMUP_MS }
                                                )
                                            }
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // DAC Hot-Standby Idle Timeout
                                        val selectedIdleIdx = USB_IDLE_TIMEOUT_OPTIONS_SEC.indexOf(audioOutputState.idleTimeoutSec)
                                            .takeIf { it >= 0 }
                                            ?: USB_IDLE_TIMEOUT_OPTIONS_SEC.indices.minByOrNull {
                                                kotlin.math.abs(USB_IDLE_TIMEOUT_OPTIONS_SEC[it] - audioOutputState.idleTimeoutSec)
                                            }
                                            ?: 2
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_usb_idle_timeout_title),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = "${audioOutputState.idleTimeoutSec}s",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.settings_usb_idle_timeout_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                        )
                                        SettingsSegmentedPill(
                                            options = USB_IDLE_TIMEOUT_LABELS,
                                            selectedIndex = selectedIdleIdx,
                                            onSelect = { idx ->
                                                viewModel.setUsbIdleTimeoutSec(
                                                    USB_IDLE_TIMEOUT_OPTIONS_SEC.getOrElse(idx) { DEFAULT_USB_IDLE_TIMEOUT_SEC }
                                                )
                                            }
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))

                                        // DAC Volume Steps
                                        val selectedStepIdx = USB_VOLUME_STEP_OPTIONS
                                            .indexOf(audioOutputState.maxVolumeSteps)
                                            .takeIf { it >= 0 } ?: 3
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_usb_vol_steps_title),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = "${audioOutputState.volumeStep} / ${audioOutputState.maxVolumeSteps}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SettingsSegmentedPill(
                                            options = USB_VOLUME_STEP_LABELS,
                                            selectedIndex = selectedStepIdx,
                                            onSelect = { idx ->
                                                viewModel.setUsbMaxVolumeSteps(
                                                    USB_VOLUME_STEP_OPTIONS.getOrElse(idx) { DEFAULT_USB_MAX_VOLUME_STEPS }
                                                )
                                            }
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = audioOutputState.dacStatus == UsbDacStatus.PERMISSION_REQUIRED && audioOutputState.directEnabled,
                                    enter = M3Motion.simpleFadeIn(110),
                                    exit = M3Motion.simpleFadeOut(85)
                                ) {
                                    Surface(
                                        onClick = { viewModel.requestUsbDacPermission() },
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_usb_dac_grant_permission),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Usb,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (audioOutputState.directEnabled) {
                            item(key = "usb_dsp_dsd_card") {
                                SettingsSectionCard(
                                    icon = Icons.Outlined.GraphicEq,
                                    title = stringResource(R.string.settings_usb_dsp_section_title)
                                ) {
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = stringResource(R.string.settings_usb_dsd_mode_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_usb_dsd_mode_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                    )
                                    val selectedDsdModeIdx = USB_DSD_MODE_OPTIONS
                                        .indexOf(audioOutputState.dsdMode)
                                        .takeIf { it >= 0 } ?: 0
                                    SettingsSegmentedPill(
                                        options = listOf(
                                            stringResource(R.string.settings_usb_dsd_mode_auto),
                                            stringResource(R.string.settings_usb_dsd_mode_native),
                                            stringResource(R.string.settings_usb_dsd_mode_dop),
                                            stringResource(R.string.settings_usb_dsd_mode_pcm)
                                        ),
                                        selectedIndex = selectedDsdModeIdx,
                                        onSelect = { idx ->
                                            viewModel.setUsbDsdMode(
                                                USB_DSD_MODE_OPTIONS.getOrElse(idx) { UsbDacNative.DSD_MODE_AUTO }
                                            )
                                        }
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_usb_dsd_gain_title),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = formatSignedGainDb(audioOutputState.dsdGainCompensationDb),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_usb_dsd_gain_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                    )
                                    val selectedGainIdx = USB_DSD_GAIN_OPTIONS_DB.indices.minByOrNull { idx ->
                                        kotlin.math.abs(USB_DSD_GAIN_OPTIONS_DB[idx] - audioOutputState.dsdGainCompensationDb)
                                    } ?: 2
                                    SettingsSegmentedPill(
                                        options = USB_DSD_GAIN_LABELS,
                                        selectedIndex = selectedGainIdx,
                                        onSelect = { idx ->
                                            viewModel.setUsbDsdGainCompensationDb(
                                                USB_DSD_GAIN_OPTIONS_DB.getOrElse(idx) { 6.0f }
                                            )
                                        }
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = stringResource(R.string.settings_usb_resampler_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_usb_resampler_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                                    )
                                    val selectedResamplerIdx = USB_RESAMPLER_QUALITY_OPTIONS
                                        .indexOf(audioOutputState.resamplerQuality)
                                        .takeIf { it >= 0 } ?: 1
                                    SettingsSegmentedPill(
                                        options = listOf(
                                            stringResource(R.string.settings_usb_resampler_medium),
                                            stringResource(R.string.settings_usb_resampler_high),
                                            stringResource(R.string.settings_usb_resampler_ultra)
                                        ),
                                        selectedIndex = selectedResamplerIdx,
                                        onSelect = { idx ->
                                            viewModel.setUsbResamplerQuality(
                                                USB_RESAMPLER_QUALITY_OPTIONS.getOrElse(idx) {
                                                    UsbDacNative.RESAMPLER_QUALITY_HIGH
                                                }
                                            )
                                        }
                                    )

                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                    )

                                    SettingsSwitchRow(
                                        icon = null,
                                        title = stringResource(R.string.settings_usb_dither_title),
                                        subtitle = stringResource(R.string.settings_usb_dither_desc),
                                        checked = audioOutputState.ditherEnabled,
                                        onCheckedChange = { viewModel.setUsbDitherEnabled(it) }
                                    )
                                }
                            }
                        }
                    }

                    SettingsSubPage.LYRICS -> {
                        item(key = "lyrics_settings_card") {
                            SettingsSectionCard(
                                icon = Icons.AutoMirrored.Filled.Article,
                                title = stringResource(R.string.settings_lyrics_title)
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = stringResource(R.string.settings_lyrics_font_size_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsSegmentedPill(
                                    options = listOf(
                                        stringResource(R.string.settings_lyrics_size_small),
                                        stringResource(R.string.settings_lyrics_size_medium),
                                        stringResource(R.string.settings_lyrics_size_large)
                                    ),
                                    selectedIndex = when (lyricsFontSize) {
                                        LYRICS_FONT_SMALL -> 0
                                        LYRICS_FONT_LARGE -> 2
                                        else -> 1
                                    },
                                    onSelect = { idx ->
                                        val mode = when (idx) {
                                            0 -> LYRICS_FONT_SMALL
                                            2 -> LYRICS_FONT_LARGE
                                            else -> LYRICS_FONT_MEDIUM
                                        }
                                        viewModel.setLyricsFontSize(mode)
                                    }
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = stringResource(R.string.settings_lyrics_align_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsSegmentedPill(
                                    options = listOf(
                                        stringResource(R.string.settings_lyrics_align_left),
                                        stringResource(R.string.settings_lyrics_align_center)
                                    ),
                                    selectedIndex = if (lyricsAlignment == LYRICS_ALIGN_CENTER) 1 else 0,
                                    onSelect = { idx ->
                                        viewModel.setLyricsAlignment(
                                            if (idx == 1) LYRICS_ALIGN_CENTER else LYRICS_ALIGN_LEFT
                                        )
                                    }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Default.Sync,
                                    title = stringResource(R.string.settings_lyrics_autoscroll_title),
                                    subtitle = stringResource(R.string.settings_lyrics_autoscroll_desc),
                                    checked = lyricsAutoScroll,
                                    onCheckedChange = { viewModel.setLyricsAutoScroll(it) }
                                )
                            }
                        }

                        item(key = "lyrics_delay_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.AvTimer,
                                title = stringResource(R.string.settings_lyrics_delay_section_title)
                            ) {
                                Spacer(modifier = Modifier.height(10.dp))

                                LyricsDelaySeekbarRow(
                                    icon = Icons.Default.Speaker,
                                    title = stringResource(R.string.settings_lyrics_delay_normal_title),
                                    delayMs = lyricsDelayNormalMs,
                                    onDelayChange = { viewModel.setLyricsDelayNormalMs(it) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                LyricsDelaySeekbarRow(
                                    icon = Icons.Default.BluetoothAudio,
                                    title = stringResource(R.string.settings_lyrics_delay_bt_title),
                                    delayMs = lyricsDelayBluetoothMs,
                                    onDelayChange = { viewModel.setLyricsDelayBluetoothMs(it) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                LyricsDelaySeekbarRow(
                                    icon = Icons.Default.Usb,
                                    title = stringResource(R.string.settings_lyrics_delay_bp_title),
                                    delayMs = lyricsDelayBitPerfectMs,
                                    onDelayChange = { viewModel.setLyricsDelayBitPerfectMs(it) }
                                )
                            }
                        }
                    }

                    SettingsSubPage.LIBRARY -> {
                        item(key = "short_audio_filter_card") {
                            val selectedIdx = DURATION_FILTER_OPTIONS_SEC.indexOf(minAudioDurationSec).takeIf { it >= 0 } ?: 1
                            SettingsSegmentedCard(
                                icon = Icons.Outlined.FilterAlt,
                                title = stringResource(R.string.settings_short_filter_title),
                                options = listOf(
                                    stringResource(R.string.settings_filter_0s),
                                    stringResource(R.string.settings_filter_10s),
                                    stringResource(R.string.settings_filter_15s),
                                    stringResource(R.string.settings_filter_30s),
                                    stringResource(R.string.settings_filter_60s)
                                ),
                                selectedIndex = selectedIdx,
                                onSelect = { idx ->
                                    val sec = DURATION_FILTER_OPTIONS_SEC.getOrElse(idx) { DEFAULT_MIN_DURATION_SEC }
                                    viewModel.setMinAudioDurationSec(sec)
                                }
                            )
                        }

                        item(key = "custom_scan_folders_card") {
                            val customFolderCount = maxOf(customScanFolders.size, safScanUris.size)
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                                    SettingsActionRow(
                                        icon = Icons.Outlined.CreateNewFolder,
                                        title = stringResource(R.string.settings_custom_scan_folders_title),
                                        subtitle = if (customFolderCount == 0) {
                                            stringResource(R.string.settings_custom_scan_folders_desc_none)
                                        } else {
                                            pluralStringResource(
                                                R.plurals.settings_custom_scan_folders_desc_count,
                                                customFolderCount,
                                                customFolderCount
                                            )
                                        },
                                        trailingContent = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = { showCustomScanFoldersDialog = true }
                                    )
                                }
                            }
                        }

                        item(key = "excluded_folders_card") {
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                                    SettingsActionRow(
                                        icon = Icons.Outlined.FolderOff,
                                        title = stringResource(R.string.settings_excluded_folders_title),
                                        subtitle = if (excludedFolders.isEmpty()) {
                                            stringResource(R.string.settings_excluded_folders_desc_none)
                                        } else {
                                            pluralStringResource(
                                                R.plurals.settings_excluded_folders_desc_count,
                                                excludedFolders.size,
                                                excludedFolders.size
                                            )
                                        },
                                        trailingContent = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = { showExcludedFoldersDialog = true }
                                    )
                                }
                            }
                        }

                        item(key = "library_storage_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.Storage,
                                title = stringResource(R.string.settings_library_title),
                                badgeText = pluralStringResource(R.plurals.settings_song_badge, allSongs.size, allSongs.size)
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = stringResource(
                                        R.string.settings_library_stats,
                                        pluralStringResource(R.plurals.song_count, allSongs.size, allSongs.size),
                                        pluralStringResource(R.plurals.album_count, albums.size, albums.size)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                val rescanSubtitle = when (val state = scanState) {
                                    is LibraryScanState.Scanning -> when {
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
                                        else -> stringResource(R.string.settings_rescan_scanning)
                                    }
                                    is LibraryScanState.Completed -> if (isScanning) {
                                        stringResource(R.string.settings_rescan_scanning)
                                    } else {
                                        stringResource(R.string.settings_rescan_desc)
                                    }
                                    LibraryScanState.Idle -> if (isScanning) {
                                        stringResource(R.string.settings_rescan_scanning)
                                    } else {
                                        stringResource(R.string.settings_rescan_desc)
                                    }
                                }

                                SettingsActionRow(
                                    icon = Icons.Default.Refresh,
                                    title = stringResource(R.string.settings_rescan_title),
                                    subtitle = rescanSubtitle,
                                    enabled = !isScanning,
                                    trailingContent = if (isScanning) {
                                        {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                ExpressiveLoadingIndicator(
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                IconButton(
                                                    onClick = { viewModel.cancelLibraryScan() },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.settings_rescan_cancel),
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else null,
                                    onClick = { viewModel.loadMusic(forceRescan = true) }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.History,
                                    title = stringResource(R.string.settings_clear_history_title),
                                    subtitle = stringResource(R.string.settings_clear_history_desc),
                                    iconTint = MaterialTheme.colorScheme.error,
                                    titleColor = MaterialTheme.colorScheme.error,
                                    onClick = { showClearHistoryDialog = true }
                                )
                            }
                        }
                    }

                    SettingsSubPage.DEBUG -> {
                        item(key = "debug_logs_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.BugReport,
                                title = stringResource(R.string.settings_logs_title),
                                badgeText = formattedLogSize
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                SettingsSwitchRow(
                                    icon = Icons.Default.Description,
                                    title = stringResource(R.string.settings_logs_toggle_title),
                                    subtitle = pluralStringResource(
                                        if (debugLogStats.enabled) R.plurals.settings_logs_toggle_desc_on
                                        else R.plurals.settings_logs_toggle_desc_off,
                                        debugLogStats.lineCount,
                                        debugLogStats.lineCount,
                                        formattedLogSize
                                    ),
                                    checked = debugLogStats.enabled,
                                    onCheckedChange = { enabled ->
                                        AppLogManager.init(context)
                                        AppLogManager.setEnabled(enabled)
                                    }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.Visibility,
                                    title = stringResource(R.string.settings_logs_preview_title),
                                    subtitle = stringResource(R.string.settings_logs_preview_desc),
                                    onClick = {
                                        coroutineScope.launch {
                                            val preview = withContext(Dispatchers.IO) {
                                                AppLogManager.buildPreviewSummary(context)
                                            }
                                            logPreviewText = preview
                                        }
                                    }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.Share,
                                    title = stringResource(R.string.settings_logs_share_title),
                                    subtitle = stringResource(R.string.settings_logs_share_desc),
                                    enabled = !isExportingLogs,
                                    trailingContent = if (isExportingLogs) {
                                        {
                                            ExpressiveLoadingIndicator(
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = { shareDebugReport() }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.SaveAlt,
                                    title = stringResource(R.string.settings_logs_save_title),
                                    subtitle = stringResource(R.string.settings_logs_save_desc),
                                    enabled = !isExportingLogs,
                                    onClick = {
                                        try {
                                            saveLogLauncher.launch(AppLogManager.defaultExportFileName())
                                        } catch (t: Throwable) {
                                            PDLog.e(PDLog.UI, "LOG_PICKER_ERR", "err=${t.message}", t)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_logs_error, t.message ?: "error"),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.DeleteOutline,
                                    title = stringResource(R.string.settings_logs_clear_title),
                                    subtitle = stringResource(R.string.settings_logs_clear_desc),
                                    iconTint = MaterialTheme.colorScheme.error,
                                    titleColor = MaterialTheme.colorScheme.error,
                                    onClick = { showClearLogsDialog = true }
                                )
                            }
                        }
                    }

                    SettingsSubPage.BACKUP_RESTORE -> {
                        item(key = "backup_restore_card") {
                            SettingsSectionCard(
                                icon = Icons.Outlined.SettingsBackupRestore,
                                title = stringResource(R.string.settings_backup_section_title)
                            ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = stringResource(R.string.settings_backup_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.SaveAlt,
                                    title = stringResource(R.string.settings_backup_create_title),
                                    subtitle = stringResource(R.string.settings_backup_create_desc),
                                    enabled = !isProcessingBackup,
                                    trailingContent = if (isProcessingBackup) {
                                        {
                                            ExpressiveLoadingIndicator(
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        try {
                                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                            createBackupLauncher.launch("PlayDepth_Backup_$stamp.json")
                                        } catch (t: Throwable) {
                                            PDLog.e(PDLog.UI, "BACKUP_PICKER_ERR", "mode=create err=${t.message}", t)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_backup_error, t.message ?: "error"),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )

                                SettingsActionRow(
                                    icon = Icons.Default.Restore,
                                    title = stringResource(R.string.settings_backup_restore_title),
                                    subtitle = stringResource(R.string.settings_backup_restore_desc),
                                    enabled = !isProcessingBackup,
                                    onClick = {
                                        try {
                                            restoreBackupLauncher.launch(arrayOf(MIME_TYPE_JSON, "*/*"))
                                        } catch (t: Throwable) {
                                            PDLog.e(PDLog.UI, "BACKUP_PICKER_ERR", "mode=restore err=${t.message}", t)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_backup_error, t.message ?: "error"),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedDetailSlot(
            target = if (showUsbDacStatsScreen) Unit else null,
            onBack = { showUsbDacStatsScreen = false },
            backEnabled = isActive
        ) {
            UsbDacStatisticsPage(
                audioOutputState = audioOutputState,
                safeInsets = safeInsets,
                safeStart = safeStart,
                safeEnd = safeEnd,
                onRefresh = { viewModel.refreshUsbDacTelemetry() },
                onBackClick = { showUsbDacStatsScreen = false }
            )
        }

        AnimatedDetailSlot(
            target = if (showAboutScreen) Unit else null,
            onBack = { showAboutScreen = false },
            backEnabled = isActive
        ) {
            AboutScreen(
                onBackClick = { showAboutScreen = false }
            )
        }
    }

    if (showCustomScanFoldersDialog) {
        val sortedCustom = remember(customScanFolders, safScanUris) {
            if (customScanFolders.isNotEmpty()) {
                customScanFolders.sorted()
            } else {
                safScanUris.sorted()
            }
        }
        AlertDialog(
            onDismissRequest = { showCustomScanFoldersDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.settings_custom_scan_folders_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = {
                            runCatching { pickCustomScanFolderLauncher.launch(null) }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_custom_scan_folders_add),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (sortedCustom.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_custom_scan_folders_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (folderEntry in sortedCustom) {
                                val displayLabel = Uri.decode(folderEntry).substringAfterLast('/').ifBlank { folderEntry }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = displayLabel,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = folderEntry,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.removeCustomScanFolder(folderEntry) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_delete),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomScanFoldersDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (showExcludedFoldersDialog) {
        val sortedExcluded = remember(excludedFolders) { excludedFolders.sorted() }
        AlertDialog(
            onDismissRequest = { showExcludedFoldersDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.settings_excluded_folders_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = {
                            runCatching { pickExcludedFolderLauncher.launch(null) }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_excluded_folders_add),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    if (sortedExcluded.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_excluded_folders_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (folderPath in sortedExcluded) {
                                val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = folderName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = folderPath,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.removeExcludedFolder(folderPath) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_delete),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExcludedFoldersDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.dialog_clear_history_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_clear_history_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        viewModel.clearPlaybackHistory()
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_history_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearLogsDialog) {
        AlertDialog(
            onDismissRequest = { showClearLogsDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.dialog_logs_clear_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_logs_clear_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearLogsDialog = false
                        AppLogManager.clearLogs()
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_logs_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLogsDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    logPreviewText?.let { preview ->
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { logPreviewText = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.dialog_logs_preview_title), fontWeight = FontWeight.Bold) },
            text = {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = if (layout.isShortLandscape) 170.dp else 380.dp)
                ) {
                    Text(
                        text = preview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll)
                            .padding(12.dp)
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(preview))
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_logs_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text(stringResource(R.string.action_copy))
                    }
                    TextButton(
                        onClick = {
                            logPreviewText = null
                            shareDebugReport()
                        }
                    ) {
                        Text(stringResource(R.string.action_share))
                    }
                    TextButton(onClick = { logPreviewText = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }
}

// ═══ Settings Components ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubPageScaffold(
    title: String,
    safeInsets: WindowInsets,
    safeStart: Dp,
    safeEnd: Dp,
    onBackClick: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
            windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(
                start = safeStart,
                end = safeEnd,
                top = 6.dp,
                bottom = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsCategoryButton(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!badgeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun LyricsDelaySeekbarRow(
    icon: ImageVector,
    title: String,
    delayMs: Int,
    onDelayChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            val sign = if (delayMs > 0) "+" else ""
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "$sign$delayMs ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            if (delayMs != 0) {
                Text(
                    text = stringResource(R.string.settings_lyrics_delay_reset),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDelayChange(0) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        Slider(
            value = delayMs.toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw / 50f).roundToInt() * 50).coerceIn(-2000, 2000)
                if (snapped != delayMs) {
                    onDelayChange(snapped)
                }
            },
            valueRange = -2000f..2000f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AboutHeaderCard(
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AsyncImage(
                model = R.drawable.ic_splash_logo,
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.settings_version_text,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsSegmentedCard(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    badgeText: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    SettingsSectionCard(
        icon = icon,
        title = title,
        badgeText = badgeText,
        modifier = modifier
    ) {
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }

        SettingsSegmentedPill(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect
        )
    }
}

@Composable
private fun SettingsSegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        val safeCount = options.size.coerceAtLeast(1)
        val safeIndex = selectedIndex.coerceIn(0, safeCount - 1)
        val pillPosition by animateFloatAsState(
            targetValue = safeIndex.toFloat(),
            animationSpec = tween(
                durationMillis = 110,
                easing = M3Motion.Standard
            ),
            label = "SegmentedPillPos"
        )
        val pillColor = MaterialTheme.colorScheme.primary

        // Pure GPU Canvas drawBehind avoids BoxWithConstraints subcomposition overhead on screen entry
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .drawBehind {
                    val slotWidth = size.width / safeCount.toFloat()
                    val corner = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(
                        color = pillColor,
                        topLeft = Offset(slotWidth * pillPosition, 0f),
                        size = Size(slotWidth, size.height),
                        cornerRadius = corner
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val labelFontSize = if (options.size >= 4) 12.sp else 14.sp
            options.forEachIndexed { idx, label ->
                val selected = idx == safeIndex
                val labelColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(idx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = labelFontSize,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    badgeContentColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!badgeText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeContainerColor
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.5f)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(10.dp))
            trailingContent()
        }
    }
}

// ═══ USB DAC Statistics Overlay ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsbDacStatisticsPage(
    audioOutputState: AudioOutputUiState,
    safeInsets: WindowInsets,
    safeStart: Dp,
    safeEnd: Dp,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit
) {
    val didInitialRefreshRef = remember { booleanArrayOf(false) }
    SideEffect {
        if (!didInitialRefreshRef[0]) {
            didInitialRefreshRef[0] = true
            onRefresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_usb_stats_screen_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.usb_stats_refresh)
                    )
                }
            },
            windowInsets = safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(
                start = safeStart,
                end = safeEnd,
                top = 6.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            usbDacStatisticsItems(audioOutputState = audioOutputState)
        }
    }
}

// ═══ Private Helpers ═══
private fun formatSignedGainDb(db: Float): String {
    val rounded = db.roundToInt()
    return if (kotlin.math.abs(db - rounded) < 0.05f) {
        if (rounded > 0) "+$rounded dB" else "$rounded dB"
    } else {
        String.format(Locale.US, "%+.1f dB", db)
    }
}
private fun resolveDocumentTreeToFilePath(treeUri: Uri): String? {
    val rawSegment = treeUri.lastPathSegment ?: return null
    val docId = rawSegment.substringAfter("tree/", rawSegment)
    val parts = docId.split(":", limit = 2)
    val volume = parts.getOrNull(0).orEmpty()
    val relative = parts.getOrNull(1).orEmpty().trim('/')
    return when {
        volume.equals("primary", ignoreCase = true) -> {
            if (relative.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
        }
        volume.equals("home", ignoreCase = true) -> {
            if (relative.isEmpty()) "/storage/emulated/0/Documents" else "/storage/emulated/0/Documents/$relative"
        }
        volume.isNotBlank() -> {
            if (relative.isEmpty()) "/storage/$volume" else "/storage/$volume/$relative"
        }
        else -> null
    }
}

private fun formatLogSize(bytes: Long): String {
    return when {
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB.toFloat())
        else -> String.format(Locale.US, "%.2f MB", bytes / BYTES_PER_MB.toFloat())
    }
}
