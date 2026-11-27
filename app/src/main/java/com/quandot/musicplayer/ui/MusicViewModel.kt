/**
 * MusicViewModel — Central UI state holder and playback/queue coordinator for PlayDepth.
 *
 * @remarks
 * Connects Compose screens to [PlaybackService] via a [MediaController] `SessionToken`, maintains
 * dual queue projections (`currentQueue` and `originalQueue`) with unique per-instance IDs so
 * duplicate tracks in a queue remain independently addressable across Media3 transitions, and
 * debounces rapid skip commands with optimistic UI resets so track-switch animations never hitch.
 *
 * @see PlaybackService
 * @see MusicRepository
 */
package com.quandot.musicplayer.ui

// External SDK / Jetpack / third-party libraries
import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Internal modules
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.audio.UsbDacNative
import com.quandot.musicplayer.audio.UsbDacState
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.data.AiffMetadataHelper
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.data.AlbumArtRequest
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.data.ExoAudioFormatSnapshot
import com.quandot.musicplayer.data.MusicDbHelper
import com.quandot.musicplayer.data.MusicRepository
import com.quandot.musicplayer.data.SettingsPreferencesRepository
import com.quandot.musicplayer.data.scanner.LibraryScanConfig
import com.quandot.musicplayer.data.toAlbumArtRequest
import com.quandot.musicplayer.debug.PDLog
import com.quandot.musicplayer.service.PlaybackService
import com.quandot.musicplayer.util.ScanNotificationHelper

// Domain models & theme tokens
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Artist
import com.quandot.musicplayer.model.Folder
import com.quandot.musicplayer.model.Genre
import com.quandot.musicplayer.model.LibraryScanState
import com.quandot.musicplayer.model.NowPlayingBlendStyle
import com.quandot.musicplayer.model.NowPlayingSeekbarStyle
import com.quandot.musicplayer.model.NowPlayingStyle
import com.quandot.musicplayer.model.Playlist
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.model.SortOption
import com.quandot.musicplayer.model.YearGroup
import com.quandot.musicplayer.ui.theme.MaterialYouColorEngine

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_THEME_MODE = "theme_mode"
private const val PREF_DYNAMIC_COLOR = "dynamic_color"
private const val PREF_SONG_DYNAMIC_COLOR = "song_dynamic_color"
private const val PREF_AMOLED_MODE = "amoled_mode"
private const val PREF_NOW_PLAYING_STYLE = "now_playing_style"
private const val PREF_NOW_PLAYING_BLEND_STYLE = "now_playing_blend_style"
private const val PREF_NOW_PLAYING_SEEKBAR_STYLE = "now_playing_seekbar_style"
private const val PREF_SAVED_GRID_VIEW = "saved_is_grid_view"
private const val PREF_FILTER_SHORT_AUDIO = "filter_short_audio"
private const val PREF_MIN_AUDIO_DURATION_SEC = "min_audio_duration_sec"
private const val PREF_EXCLUDED_FOLDERS = "excluded_folders"
private const val PREF_CUSTOM_SCAN_FOLDERS = "custom_scan_folders"
private const val PREF_SAF_SCAN_URIS = "saf_scan_uris"
private const val PREF_AUTO_PLAY_HEADSET = "auto_play_headset"
private const val PREF_KEEP_SCREEN_ON_PLAYER = "keep_screen_on_player"
private const val PREF_LYRICS_FONT_SIZE = "lyrics_font_size"
private const val PREF_LYRICS_ALIGNMENT = "lyrics_alignment"
private const val PREF_LYRICS_AUTO_SCROLL = "lyrics_auto_scroll"
private const val PREF_LYRICS_DELAY_NORMAL_MS = "lyrics_delay_normal_ms"
private const val PREF_LYRICS_DELAY_BLUETOOTH_MS = "lyrics_delay_bluetooth_ms"
private const val PREF_LYRICS_DELAY_BIT_PERFECT_MS = "lyrics_delay_bit_perfect_ms"
private const val PREF_SORT_OPTION = "sort_option"
private const val PREF_REPEAT_MODE = "repeat_mode"
private const val PREF_IS_SHUFFLE = "is_shuffle"
private const val PREF_QUEUE_SOURCE = "queue_source"
private const val PREF_GENRE_POPULATED = "genre_populated_v6"
private const val PREF_YEAR_PARSED = "year_parsed_v1"
private const val PREF_METADATA_PARSER_VERSION = "metadata_parser_version"
private const val CURRENT_METADATA_PARSER_VERSION = 2
private const val PREF_SAVED_QUEUE_IDS = "saved_queue_ids"
private const val PREF_SAVED_ORIGINAL_QUEUE_IDS = "saved_original_queue_ids"
private const val PREF_LAST_SONG_ID = "last_song_id"
private const val PREF_LAST_POSITION_MS = "last_position_ms"

private const val EXTRA_HANDLED_EXTERNAL = "com.quandot.HANDLED_EXTERNAL"
private const val EXTRA_HANDLED_OPEN_PLAYER = "com.quandot.HANDLED_OPEN_PLAYER"
private const val EXTRA_GRAD_START = "grad_start"
private const val EXTRA_GRAD_END = "grad_end"
private const val EXTRA_SONG_ID = "song_id"
private const val EXTRA_ALBUM_ID = "album_id"
private const val EXTRA_ALBUM_ARTIST = "album_artist"
private const val EXTRA_SONG_PATH = "song_path"
private const val EXTRA_AUDIO_SPECS = "audio_specs"
private const val EXTRA_SOURCE_BIT_DEPTH = "source_bit_depth"
private const val EXTRA_SOURCE_CODEC = "source_codec"
private const val QUERY_GRAD_START = "gradStart"
private const val QUERY_GRAD_END = "gradEnd"

private const val DEFAULT_QUEUE_SOURCE = "Mặc định"
private const val UNKNOWN_ARTIST_FALLBACK = "Nghệ sĩ không xác định"
private const val UNKNOWN_ALBUM_FALLBACK = "Album không xác định"
private const val EXTERNAL_TRACK_FALLBACK_TITLE = "Audio Track"
private const val DEFAULT_THEME_MODE = "system"
private const val DEFAULT_LYRICS_FONT_SIZE = "medium"
private const val DEFAULT_LYRICS_ALIGNMENT = "left"
private const val DEFAULT_MIN_DURATION_SEC = 10
private const val SLEEP_TIMER_END_OF_TRACK = -1
private const val SELF_DELETE_OBSERVER_COOLDOWN_MS = 2_000L
private const val OBSERVER_POST_SCAN_COOLDOWN_MS = 15_000L

private const val AUDIO_SPECS_DEFER_MS = 260L
private const val STATS_UPDATE_DEFER_MS = 420L
private const val QUEUE_SAVE_DEBOUNCE_MS = 400L
private const val SKIP_DEBOUNCE_MS = 160L
private const val OPTIMISTIC_RESET_WINDOW_MS = 1_200L
private const val PREVIOUS_RESTART_THRESHOLD_MS = 1_500L
private const val PREVIOUS_DOUBLE_TAP_WINDOW_MS = 3_000L
private const val STALE_POSITION_THRESHOLD_MS = 2_500L
private const val SEEK_END_SAFETY_MARGIN_MS = 800L
private const val SEEK_END_CLAMP_MIN_DURATION_MS = 2_000L
private const val SEEK_OPTIMISTIC_HOLD_MS = 450L
private const val PROGRESS_POLL_INTERVAL_MS = 100L
private const val PROGRESS_PENDING_POLL_INTERVAL_MS = 60L
private const val POSITION_PERSIST_INTERVAL_TICKS = 100
private const val PRELOAD_ART_CONCURRENCY = 2
private const val PRELOAD_MEMORY_HEADROOM_RATIO = 0.50f
private const val PRELOAD_MAX_ALBUM_THUMBS = 24
private const val PRELOAD_MAX_SONG_THUMBS = 24
private const val SYNTHETIC_ID_FLAG = 0x40000000L
private const val SYNTHETIC_ID_MASK = 0x7FFFFFFFL
private const val STATE_FLOW_STOP_TIMEOUT_MS = 5_000L
private const val LARGE_QUEUE_ASYNC_THRESHOLD = 200
private const val DSD64_THRESHOLD_HZ = 2_800_000
private const val DSD128_THRESHOLD_HZ = 5_500_000
private const val DSD256_THRESHOLD_HZ = 11_000_000
private const val DSD512_THRESHOLD_HZ = 22_000_000

private const val BADGE_NATIVE_DSD = "Native DSD"
private const val BADGE_DOP = "DoP"
private const val BADGE_BIT_PERFECT = "Bit-Perfect"
private const val BADGE_DSD_TO_PCM = "DSD → PCM"
private const val BADGE_RESAMPLED = "Resampled"
private const val BADGE_DOWNMIX = "Downmix"
private const val BADGE_DITHERED = "Dithered"
private const val BADGE_DIRECT_PCM = "Direct PCM"

// ═══ State & Contracts ═══
enum class AudioOutputRouteMode {
    ANDROID_DEFAULT,
    PCM_BIT_PERFECT,
    NATIVE_DSD,
    DOP,
    PCM_DSP_FALLBACK,
    DSD_TO_PCM_FALLBACK
}

enum class ActiveDspStage {
    DSD_TO_PCM,
    RESAMPLED,
    DOWNMIXED,
    MONO_TO_STEREO,
    DITHERED,
    SW_VOLUME,
    ZERO_PADDED,
    FLOAT_TO_PCM
}

@Immutable
data class AudioOutputUiState(
    val sourceSpecs: String = "",
    val isSourceHiRes: Boolean = false,
    val dacStatus: UsbDacStatus = UsbDacStatus.DISCONNECTED,
    val directEnabled: Boolean = true,
    val isDirectStreaming: Boolean = false,
    val deviceName: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val uacVersion: Int = 0,
    val uacVersionLabel: String = "",
    val supportedSampleRates: List<Int> = emptyList(),
    val supportedBitDepths: List<Int> = emptyList(),
    val maxSupportedChannels: Int = 2,
    val supportsNativeDsd: Boolean = false,
    val supportsDop: Boolean = false,
    val maxDsdRate: Int = 0,
    val inputSampleRate: Int = 0,
    val inputBitDepth: Int = 0,
    val inputChannels: Int = 0,
    val inputEncoding: Int = 0,
    val wireSampleRate: Int = 0,
    val wireBitDepth: Int = 0,
    val wireChannels: Int = 0,
    val wireFormatLabel: String = "",
    val dsdRateLabel: String = "",
    val routeMode: AudioOutputRouteMode = AudioOutputRouteMode.ANDROID_DEFAULT,
    val audioPathBadge: String = "",
    val activeDspFlags: Int = UsbDacNative.DSP_FLAG_NONE,
    val activeDspStages: List<ActiveDspStage> = emptyList(),
    val isBitExactRoute: Boolean = false,
    val hasHardwareVolume: Boolean = false,
    val hardwareVolumeMinDb256: Int = 0,
    val hardwareVolumeMaxDb256: Int = 0,
    val hardwareVolumeResDb256: Int = 256,
    val softwareVolumeBypassed: Boolean = true,
    val volumeStep: Int = 50,
    val maxVolumeSteps: Int = UsbDacManager.DEFAULT_VOLUME_STEPS,
    val isMuted: Boolean = false,
    val clockSyncMode: Int = UsbDacNative.SYNC_MODE_NONE,
    val feedbackLocked: Boolean = false,
    val clockDriftPpmX10: Int = 0,
    val underrunCount: Long = 0L,
    val stallCount: Long = 0L,
    val maxStallMs: Long = 0L,
    val dsdMode: Int = UsbDacNative.DSD_MODE_AUTO,
    val dsdGainCompensationDb: Float = 6.0f,
    val ditherEnabled: Boolean = true,
    val resamplerQuality: Int = UsbDacNative.RESAMPLER_QUALITY_HIGH,
    val warmUpMs: Int = UsbDacManager.DEFAULT_WARMUP_MS,
    val idleTimeoutSec: Int = UsbDacManager.DEFAULT_IDLE_TIMEOUT_SEC,
    val keepAliveAudioEnabled: Boolean = false,
    val errorMessage: String? = null
)

fun buildAudioOutputUiState(
    sourceSpecs: String,
    dac: UsbDacState
): AudioOutputUiState {
    val isDirectStreaming = dac.directEnabled &&
        (dac.status == UsbDacStatus.STREAMING || dac.status == UsbDacStatus.PAUSED) &&
        dac.activeOutputMode != UsbDacNative.OUTPUT_MODE_INACTIVE
    val flags = if (isDirectStreaming) dac.activeDspFlags else UsbDacNative.DSP_FLAG_NONE
    val swVolActive = (flags and UsbDacNative.DSP_FLAG_SW_VOLUME) != 0
    val swVolBypassed = dac.softwareVolumeBypassed && !swVolActive

    val routeMode = if (!isDirectStreaming) {
        AudioOutputRouteMode.ANDROID_DEFAULT
    } else {
        when (dac.activeOutputMode) {
            UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT -> {
                if (swVolBypassed) AudioOutputRouteMode.PCM_BIT_PERFECT else AudioOutputRouteMode.PCM_DSP_FALLBACK
            }
            UsbDacNative.OUTPUT_MODE_NATIVE_DSD -> AudioOutputRouteMode.NATIVE_DSD
            UsbDacNative.OUTPUT_MODE_DOP -> AudioOutputRouteMode.DOP
            UsbDacNative.OUTPUT_MODE_PCM_DSP -> AudioOutputRouteMode.PCM_DSP_FALLBACK
            UsbDacNative.OUTPUT_MODE_DSD_TO_PCM -> AudioOutputRouteMode.DSD_TO_PCM_FALLBACK
            else -> AudioOutputRouteMode.ANDROID_DEFAULT
        }
    }

    val audioPathBadge = resolveAudioPathBadge(
        isDirectStreaming = isDirectStreaming,
        outputMode = dac.activeOutputMode,
        dspFlags = flags,
        swVolBypassed = swVolBypassed
    )

    val activeStages = buildList {
        if (isDirectStreaming) {
            if ((flags and UsbDacNative.DSP_FLAG_DSD_TO_PCM) != 0 || routeMode == AudioOutputRouteMode.DSD_TO_PCM_FALLBACK) {
                add(ActiveDspStage.DSD_TO_PCM)
            }
            if ((flags and UsbDacNative.DSP_FLAG_RESAMPLER) != 0) add(ActiveDspStage.RESAMPLED)
            if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_DOWNMIX) != 0) add(ActiveDspStage.DOWNMIXED)
            if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_MONO_TO_STEREO) != 0) add(ActiveDspStage.MONO_TO_STEREO)
            if ((flags and UsbDacNative.DSP_FLAG_DITHER_TPDF) != 0) add(ActiveDspStage.DITHERED)
            if (swVolActive) add(ActiveDspStage.SW_VOLUME)
            if ((flags and UsbDacNative.DSP_FLAG_ZERO_PADDING) != 0) add(ActiveDspStage.ZERO_PADDED)
            if ((flags and UsbDacNative.DSP_FLAG_FLOAT_TO_PCM) != 0) add(ActiveDspStage.FLOAT_TO_PCM)
        }
    }

    val effectiveDsdClockHz = when (routeMode) {
        AudioOutputRouteMode.NATIVE_DSD -> when {
            dac.inputSampleRate >= DSD64_THRESHOLD_HZ -> dac.inputSampleRate
            dac.activeSampleRate > 0 && dac.activeBitDepth > 0 -> dac.activeSampleRate * dac.activeBitDepth
            else -> 0
        }
        AudioOutputRouteMode.DOP -> when {
            dac.inputSampleRate >= DSD64_THRESHOLD_HZ -> dac.inputSampleRate
            dac.activeSampleRate > 0 -> dac.activeSampleRate * 16
            else -> 0
        }
        else -> 0
    }
    val dsdRateLabel = formatDsdMultiplierLabel(effectiveDsdClockHz)

    val wireFormatLabel = if (!isDirectStreaming || dac.activeSampleRate <= 0) {
        ""
    } else {
        when (routeMode) {
            AudioOutputRouteMode.NATIVE_DSD -> {
                val mhzLabel = if (effectiveDsdClockHz > 0) formatMhzCompact(effectiveDsdClockHz) else formatKhzCompact(dac.activeSampleRate)
                "1-bit/$mhzLabel"
            }
            else -> {
                val rateStr = formatKhzCompact(dac.activeSampleRate)
                if (dac.activeBitDepth > 0) "$rateStr/${dac.activeBitDepth}-bit" else rateStr
            }
        }
    }

    val isBitExactRoute = routeMode == AudioOutputRouteMode.PCM_BIT_PERFECT ||
        routeMode == AudioOutputRouteMode.NATIVE_DSD ||
        routeMode == AudioOutputRouteMode.DOP

    return AudioOutputUiState(
        sourceSpecs = sourceSpecs,
        isSourceHiRes = AudioSpecsHelper.isHiRes(sourceSpecs),
        dacStatus = dac.status,
        directEnabled = dac.directEnabled,
        isDirectStreaming = isDirectStreaming,
        deviceName = dac.deviceName,
        vendorId = dac.vendorId,
        productId = dac.productId,
        uacVersion = dac.uacVersion,
        uacVersionLabel = formatUacProtocolLabel(dac.uacVersion),
        supportedSampleRates = dac.supportedSampleRates,
        supportedBitDepths = dac.supportedBitDepths,
        maxSupportedChannels = dac.maxSupportedChannels,
        supportsNativeDsd = dac.supportsNativeDsd,
        supportsDop = dac.supportsDop,
        maxDsdRate = dac.maxDsdRate,
        inputSampleRate = dac.inputSampleRate,
        inputBitDepth = dac.inputBitDepth,
        inputChannels = dac.inputChannels,
        inputEncoding = dac.inputEncoding,
        wireSampleRate = dac.activeSampleRate,
        wireBitDepth = dac.activeBitDepth,
        wireChannels = dac.activeChannels,
        wireFormatLabel = wireFormatLabel,
        dsdRateLabel = dsdRateLabel,
        routeMode = routeMode,
        audioPathBadge = audioPathBadge,
        activeDspFlags = flags,
        activeDspStages = activeStages,
        isBitExactRoute = isBitExactRoute,
        hasHardwareVolume = dac.hasHardwareVolume,
        hardwareVolumeMinDb256 = dac.hardwareVolumeMinDb256,
        hardwareVolumeMaxDb256 = dac.hardwareVolumeMaxDb256,
        hardwareVolumeResDb256 = dac.hardwareVolumeResDb256,
        softwareVolumeBypassed = dac.softwareVolumeBypassed,
        volumeStep = dac.volumeStep,
        maxVolumeSteps = dac.maxVolumeSteps,
        isMuted = dac.isMuted,
        clockSyncMode = dac.clockSyncMode,
        feedbackLocked = dac.feedbackLocked,
        clockDriftPpmX10 = dac.clockDriftPpmX10,
        underrunCount = dac.underrunCount,
        stallCount = dac.stallCount,
        maxStallMs = dac.maxStallMs,
        dsdMode = dac.dsdMode,
        dsdGainCompensationDb = dac.dsdGainCompensationDb,
        ditherEnabled = dac.ditherEnabled,
        resamplerQuality = dac.resamplerQuality,
        warmUpMs = dac.warmUpMs,
        idleTimeoutSec = dac.idleTimeoutSec,
        keepAliveAudioEnabled = dac.keepAliveAudioEnabled,
        errorMessage = dac.errorMessage
    )
}

/**
 * Resolves the single strict-priority audio path badge for an active USB DAC stream.
 *
 * @remarks
 * Evaluates transport modes and DSP stage flags top-down so only the most significant
 * signal-path state is shown without concatenating multiple tags or sample-rate text.
 */
private fun resolveAudioPathBadge(
    isDirectStreaming: Boolean,
    outputMode: Int,
    dspFlags: Int,
    swVolBypassed: Boolean
): String {
    if (!isDirectStreaming) return ""
    return when {
        outputMode == UsbDacNative.OUTPUT_MODE_NATIVE_DSD -> BADGE_NATIVE_DSD
        outputMode == UsbDacNative.OUTPUT_MODE_DOP -> BADGE_DOP
        outputMode == UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT && swVolBypassed -> BADGE_BIT_PERFECT
        (dspFlags and UsbDacNative.DSP_FLAG_DSD_TO_PCM) != 0 ||
            outputMode == UsbDacNative.OUTPUT_MODE_DSD_TO_PCM -> BADGE_DSD_TO_PCM
        (dspFlags and UsbDacNative.DSP_FLAG_RESAMPLER) != 0 -> BADGE_RESAMPLED
        (dspFlags and UsbDacNative.DSP_FLAG_CHANNEL_DOWNMIX) != 0 -> BADGE_DOWNMIX
        (dspFlags and UsbDacNative.DSP_FLAG_DITHER_TPDF) != 0 -> BADGE_DITHERED
        else -> BADGE_DIRECT_PCM
    }
}

private fun formatUacProtocolLabel(uacVersion: Int): String = when {
    uacVersion >= UsbDacNative.UAC_VERSION_3_0 -> "UAC 3.0"
    uacVersion >= UsbDacNative.UAC_VERSION_2_0 -> "UAC 2.0"
    uacVersion >= UsbDacNative.UAC_VERSION_1_0 -> "UAC 1.0"
    uacVersion > 0 -> String.format(Locale.US, "UAC 0x%04X", uacVersion)
    else -> ""
}

private fun formatDsdMultiplierLabel(dsdClockHz: Int): String = when {
    dsdClockHz >= DSD512_THRESHOLD_HZ -> "DSD512"
    dsdClockHz >= DSD256_THRESHOLD_HZ -> "DSD256"
    dsdClockHz >= DSD128_THRESHOLD_HZ -> "DSD128"
    dsdClockHz >= DSD64_THRESHOLD_HZ -> "DSD64"
    else -> ""
}

private fun formatKhzCompact(sampleRateHz: Int): String =
    AudioSpecsHelper.formatKhz(sampleRateHz)

private fun formatMhzCompact(sampleRateHz: Int): String =
    AudioSpecsHelper.formatMhz(sampleRateHz)

// ═══ Music ViewModel ═══
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // ═══ Dependencies & Internal State ═══
    private val repository = MusicRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private var progressJob: Job? = null
    private var preloadArtJob: Job? = null
    private var libraryScanJob: Job? = null
    private var libraryObserverJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var statsUpdateJob: Job? = null
    private var saveQueueJob: Job? = null
    private var seekJob: Job? = null
    private var queuePrepareJob: Job? = null
    private var lastSelfDeleteElapsedMs: Long = 0L
    private var lastScanCompletedElapsedMs: Long = 0L
    private val scanGeneration = AtomicLong(0L)
    private var hasCompletedInitialSync: Boolean = false
    private var nextInstanceId = 1L
    private var pendingTrackMediaId: String? = null
    private var pendingResetDeadlineMs: Long = 0L
    private var seekHoldUntilElapsedMs: Long = 0L
    private var lastPreviousResetElapsedMs: Long = 0L
    private var lastPreviousResetSongId: Long = -1L
    private var pendingExternalSong: Song? = null
    private var isUiVisible: Boolean = true
    private var lastHandledIntentUriStr: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val preloadArtDispatcher = Dispatchers.IO.limitedParallelism(PRELOAD_ART_CONCURRENCY)

    // ═══ UI State Flows ═══
    // Permission & scan state
    val hasStoragePermission = MutableStateFlow(false)
    val isScanning = MutableStateFlow(false)
    val scanState: StateFlow<LibraryScanState> = repository.scanState

    // Library collections
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs = _allSongs.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists = _artists.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders = _folders.asStateFlow()

    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    val genres = _genres.asStateFlow()

    private val _yearGroups = MutableStateFlow<List<YearGroup>>(emptyList())
    val yearGroups = _yearGroups.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    val favoriteSongIds = MutableStateFlow<Set<Long>>(emptySet())

    // Active playback queue projections
    val currentQueue = MutableStateFlow<List<Song>>(emptyList())
    val originalQueue = MutableStateFlow<List<Song>>(emptyList())

    // Search & sort
    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow(SortOption.TITLE_AZ)

    // Playback history statistics
    val mostPlayedSongs = MutableStateFlow<List<Song>>(emptyList())
    val mostPlayedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val mostPlayedArtists = MutableStateFlow<List<Artist>>(emptyList())

    // Playback state
    val currentSong = MutableStateFlow<Song?>(null)
    val trackDirection = MutableStateFlow(1)
    val currentQueueIndex = MutableStateFlow(0)
    val currentAudioSpecs = MutableStateFlow("")
    val usbDacState: StateFlow<UsbDacState> = UsbDacManager.state
    val audioOutputState: StateFlow<AudioOutputUiState> = combine(
        currentAudioSpecs,
        UsbDacManager.state
    ) { sourceSpecs, dacState ->
        buildAudioOutputUiState(sourceSpecs, dacState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_FLOW_STOP_TIMEOUT_MS),
        initialValue = buildAudioOutputUiState(currentAudioSpecs.value, UsbDacManager.state.value)
    )
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val currentDuration = MutableStateFlow(0L)
    val playbackProgress = MutableStateFlow(0f)
    val isShuffle = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF) // 0: Off, 1: One, 2: All
    val isLiked = MutableStateFlow(false)
    val queueSource = MutableStateFlow(DEFAULT_QUEUE_SOURCE)

    // User settings & preferences
    private val settingsPreferencesRepository = SettingsPreferencesRepository(application)
    val nowPlayingStyle: StateFlow<NowPlayingStyle> = settingsPreferencesRepository.nowPlayingStyle
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NowPlayingStyle.MODERN
        )
    val nowPlayingBlendStyle: StateFlow<NowPlayingBlendStyle> = settingsPreferencesRepository.nowPlayingBlendStyle
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NowPlayingBlendStyle.LINEAR
        )
    val nowPlayingSeekbarStyle: StateFlow<NowPlayingSeekbarStyle> = settingsPreferencesRepository.nowPlayingSeekbarStyle
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = NowPlayingSeekbarStyle.SLIDER
        )
    val themeMode = MutableStateFlow(DEFAULT_THEME_MODE) // "system", "light", "dark"
    val dynamicColor = MutableStateFlow(true)
    val songDynamicColor = MutableStateFlow(true)
    private val _songSeedColorArgb = MutableStateFlow<Int?>(null)
    val songSeedColorArgb: StateFlow<Int?> = _songSeedColorArgb.asStateFlow()
    private val songSeedColorCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 128
    }
    val amoledMode = MutableStateFlow(false)
    val isLibraryGridView = MutableStateFlow(false)
    val minAudioDurationSec = MutableStateFlow(DEFAULT_MIN_DURATION_SEC) // 0, 10, 15, 30, 60
    val filterShortAudio = MutableStateFlow(true)
    val excludedFolders = MutableStateFlow<Set<String>>(emptySet())
    val customScanFolders = MutableStateFlow<Set<String>>(emptySet())
    val safScanUris = MutableStateFlow<Set<String>>(emptySet())
    val sleepTimerMinutes = MutableStateFlow(0) // 0 = Off, >0 = countdown minutes, -1 = Stop after current track
    val sleepTimerRemainingSec = MutableStateFlow(0L)
    val sleepTimerTargetEpochMs = MutableStateFlow(0L)
    val autoPlayOnHeadset = MutableStateFlow(false)
    val keepScreenOnInPlayer = MutableStateFlow(false)
    val lyricsFontSize = MutableStateFlow(DEFAULT_LYRICS_FONT_SIZE) // "small", "medium", "large"
    val lyricsAlignment = MutableStateFlow(DEFAULT_LYRICS_ALIGNMENT) // "left", "center"
    val lyricsAutoScroll = MutableStateFlow(true)
    val lyricsDelayNormalMs = MutableStateFlow(0)
    val lyricsDelayBluetoothMs = MutableStateFlow(0)
    val lyricsDelayBitPerfectMs = MutableStateFlow(0)
    val cacheSizeFormatted = MutableStateFlow("0 B")
    val openNowPlayingRequest = MutableStateFlow(false)

    // ═══ Initialization & Controller Binding ═══
    init {
        themeMode.value = prefs.getString(PREF_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        dynamicColor.value = prefs.getBoolean(PREF_DYNAMIC_COLOR, true)
        songDynamicColor.value = prefs.getBoolean(PREF_SONG_DYNAMIC_COLOR, true)
        amoledMode.value = prefs.getBoolean(PREF_AMOLED_MODE, false)
        isLibraryGridView.value = prefs.getBoolean(PREF_SAVED_GRID_VIEW, false)
        val defaultMinSec = if (prefs.getBoolean(PREF_FILTER_SHORT_AUDIO, true)) DEFAULT_MIN_DURATION_SEC else 0
        minAudioDurationSec.value = prefs.getInt(PREF_MIN_AUDIO_DURATION_SEC, defaultMinSec)
        filterShortAudio.value = minAudioDurationSec.value > 0
        excludedFolders.value = (prefs.getStringSet(PREF_EXCLUDED_FOLDERS, emptySet()) ?: emptySet())
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotBlank() }
            .toSet()
        customScanFolders.value = (prefs.getStringSet(PREF_CUSTOM_SCAN_FOLDERS, emptySet()) ?: emptySet())
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotBlank() }
            .toSet()
        safScanUris.value = (prefs.getStringSet(PREF_SAF_SCAN_URIS, emptySet()) ?: emptySet())
            .filter { it.isNotBlank() }
            .toSet()
        autoPlayOnHeadset.value = prefs.getBoolean(PREF_AUTO_PLAY_HEADSET, false)
        keepScreenOnInPlayer.value = prefs.getBoolean(PREF_KEEP_SCREEN_ON_PLAYER, false)
        lyricsFontSize.value = prefs.getString(PREF_LYRICS_FONT_SIZE, DEFAULT_LYRICS_FONT_SIZE) ?: DEFAULT_LYRICS_FONT_SIZE
        lyricsAlignment.value = prefs.getString(PREF_LYRICS_ALIGNMENT, DEFAULT_LYRICS_ALIGNMENT) ?: DEFAULT_LYRICS_ALIGNMENT
        lyricsAutoScroll.value = prefs.getBoolean(PREF_LYRICS_AUTO_SCROLL, true)
        lyricsDelayNormalMs.value = prefs.getInt(PREF_LYRICS_DELAY_NORMAL_MS, 0)
        lyricsDelayBluetoothMs.value = prefs.getInt(PREF_LYRICS_DELAY_BLUETOOTH_MS, 0)
        lyricsDelayBitPerfectMs.value = prefs.getInt(PREF_LYRICS_DELAY_BIT_PERFECT_MS, 0)
        val savedSort = prefs.getString(PREF_SORT_OPTION, SortOption.TITLE_AZ.name) ?: SortOption.TITLE_AZ.name
        sortOption.value = runCatching { SortOption.valueOf(savedSort) }.getOrDefault(SortOption.TITLE_AZ)
        repeatMode.value = prefs.getInt(PREF_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        isShuffle.value = prefs.getBoolean(PREF_IS_SHUFFLE, false)
        queueSource.value = prefs.getString(PREF_QUEUE_SOURCE, DEFAULT_QUEUE_SOURCE) ?: DEFAULT_QUEUE_SOURCE
        initMediaController()
        initAudioSpecsTracker()
        initSongDynamicColorTracker()
        refreshCacheSize()
        refreshPlaylists()
    }

    private fun initSongDynamicColorTracker() {
        viewModelScope.launch {
            val artManager = AlbumArtManager.getInstance(getApplication())
            var lastCacheVersion = -1L
            combine(songDynamicColor, currentSong, artManager.cacheVersion) { enabled, song, version ->
                Triple(enabled, song, version)
            }
                .distinctUntilChanged { old, new ->
                    old.first == new.first &&
                        old.third == new.third &&
                        old.second?.id == new.second?.id &&
                        old.second?.albumId == new.second?.albumId &&
                        old.second?.albumArtUri == new.second?.albumArtUri &&
                        old.second?.path == new.second?.path &&
                        old.second?.albumGradientColorStart == new.second?.albumGradientColorStart &&
                        old.second?.albumGradientColorEnd == new.second?.albumGradientColorEnd
                }
                .collectLatest { (enabled, song, cacheVersion) ->
                    if (cacheVersion != lastCacheVersion) {
                        lastCacheVersion = cacheVersion
                        synchronized(songSeedColorCache) { songSeedColorCache.clear() }
                    }
                    if (!enabled || song == null) {
                        _songSeedColorArgb.value = null
                        return@collectLatest
                    }
                    val request = song.toAlbumArtRequest()
                    val cacheKey = "${request.albumKey}_${song.albumGradientColorStart}_${song.albumGradientColorEnd}"
                    val sharedSeedKey = "${cacheVersion}_${request.albumKey}"
                    val cached = synchronized(songSeedColorCache) { songSeedColorCache[cacheKey] }
                        ?: MaterialYouColorEngine.getCachedSeedColorArgb(sharedSeedKey)
                    if (cached != null) {
                        synchronized(songSeedColorCache) { songSeedColorCache[cacheKey] = cached }
                        _songSeedColorArgb.value = if (cached == 0) null else cached
                        return@collectLatest
                    }
                    val extracted = withContext(Dispatchers.IO) {
                        val bitmap = artManager.loadBitmap(request, ArtResolutionBucket.THUMBNAIL)
                        if (bitmap != null) {
                            withContext(Dispatchers.Default) {
                                MaterialYouColorEngine.extractSeedColorArgbCached(sharedSeedKey, bitmap)
                            }
                        } else {
                            MaterialYouColorEngine.extractFallbackGradientSeedArgb(
                                song.albumGradientColorStart,
                                song.albumGradientColorEnd
                            )
                        }
                    }
                    if (extracted != null) {
                        synchronized(songSeedColorCache) {
                            songSeedColorCache[cacheKey] = extracted
                        }
                    }
                    _songSeedColorArgb.value = extracted
                }
        }
    }

    private fun initAudioSpecsTracker() {
        viewModelScope.launch {
            val artManager = AlbumArtManager.getInstance(getApplication())
            currentSong.collectLatest { song ->
                if (song != null) {
                    val artRequest = song.toAlbumArtRequest()
                    artManager.setPinnedAlbumKey(artRequest.albumKey)
                    val targetNowPlayingBucket = if (nowPlayingStyle.value == NowPlayingStyle.MODERN) {
                        ArtResolutionBucket.ULTRA_RES
                    } else {
                        ArtResolutionBucket.HIGH_RES
                    }
                    artManager.preload(artRequest, targetNowPlayingBucket)

                    isLiked.value = favoriteSongIds.value.contains(song.id)
                    val cached = AudioSpecsHelper.getCachedSpecs(song.id)
                    val isExact = AudioSpecsHelper.hasExactCachedSpecs(song.id)
                    if (cached != null && isExact) {
                        currentAudioSpecs.value = cached
                    } else {
                        currentAudioSpecs.value = cached ?: AudioSpecsHelper.getFastFallbackSpecs(song)
                        // Defer IO header parse slightly so rapid skips cancel cleanly before hitting storage/IPC
                        delay(AUDIO_SPECS_DEFER_MS)
                        val exoFormat = PlaybackService.currentAudioFormat.value?.takeIf { it.songId == song.id }
                        val specs = withContext(Dispatchers.IO) {
                            AudioSpecsHelper.getAudioSpecs(getApplication(), song, exoFormat)
                        }
                        if (currentSong.value?.id == song.id) {
                            currentAudioSpecs.value = specs
                        }
                    }
                } else {
                    artManager.setPinnedAlbumKey(null)
                    currentAudioSpecs.value = ""
                }
            }
        }
        viewModelScope.launch {
            PlaybackService.currentAudioFormat.collectLatest { exoFormat ->
                val song = currentSong.value ?: return@collectLatest
                if (exoFormat == null || exoFormat.songId != song.id) return@collectLatest
                if (AudioSpecsHelper.hasExactCachedSpecs(song.id)) return@collectLatest

                val specs = withContext(Dispatchers.IO) {
                    AudioSpecsHelper.getAudioSpecs(getApplication(), song, exoFormat)
                }
                if (currentSong.value?.id == song.id) {
                    currentAudioSpecs.value = specs
                }
            }
        }
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()?.apply {
                    val savedRepeat = prefs.getInt(PREF_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                    val physicalShuffle = PlaybackService.isPhysicalShuffleEnabled.value

                    // Apply persisted repeat flag only when the service is idle with no active media item;
                    // ExoPlayer's internal shuffleModeEnabled stays false because PlayDepth shuffles physically.
                    if (!isPlaying && currentMediaItem == null) {
                        repeatMode = savedRepeat
                    }
                    shuffleModeEnabled = false

                    // Synchronize UI state immediately with the live PlaybackService session
                    this@MusicViewModel.isPlaying.value = isPlaying
                    this@MusicViewModel.repeatMode.value = repeatMode
                    this@MusicViewModel.isShuffle.value = physicalShuffle
                    PlaybackService.onNotificationShuffleToggle = {
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            toggleShuffle()
                        }
                    }
                    if (isPlaying) {
                        startProgressTracker()
                    }

                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            this@MusicViewModel.isPlaying.value = playing
                            if (playing) startProgressTracker() else stopProgressTracker()
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                currentDuration.value = duration.coerceAtLeast(0L)
                            } else if (playbackState == Player.STATE_ENDED) {
                                this@MusicViewModel.isPlaying.value = false
                                val finalDur = duration.takeIf { it > 0L && it != C.TIME_UNSET }
                                    ?: this@MusicViewModel.currentDuration.value
                                if (finalDur > 0L) {
                                    this@MusicViewModel.currentDuration.value = finalDur
                                    this@MusicViewModel.currentPosition.value = finalDur
                                }
                                this@MusicViewModel.playbackProgress.value = 1f
                                if (this@MusicViewModel.sleepTimerMinutes.value == SLEEP_TIMER_END_OF_TRACK) {
                                    this@MusicViewModel.sleepTimerMinutes.value = 0
                                    this@MusicViewModel.sleepTimerRemainingSec.value = 0L
                                    this@MusicViewModel.sleepTimerTargetEpochMs.value = 0L
                                }
                            }
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int
                        ) {
                            if (this@MusicViewModel.sleepTimerMinutes.value == SLEEP_TIMER_END_OF_TRACK &&
                                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                            ) {
                                pause()
                                this@MusicViewModel.isPlaying.value = false
                                stopProgressTracker()
                                this@MusicViewModel.sleepTimerMinutes.value = 0
                                this@MusicViewModel.sleepTimerRemainingSec.value = 0L
                                this@MusicViewModel.sleepTimerTargetEpochMs.value = 0L
                            }
                        }

                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            if (this@MusicViewModel.sleepTimerMinutes.value == SLEEP_TIMER_END_OF_TRACK &&
                                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                            ) {
                                pause()
                                this@MusicViewModel.isPlaying.value = false
                                stopProgressTracker()
                                this@MusicViewModel.sleepTimerMinutes.value = 0
                                this@MusicViewModel.sleepTimerRemainingSec.value = 0L
                                this@MusicViewModel.sleepTimerTargetEpochMs.value = 0L
                            }

                            val mediaIdStr = item?.mediaId ?: return
                            val songId = mediaIdStr.substringBefore('#').toLongOrNull() ?: return

                            val queue = currentQueue.value
                            val currentIdx = currentMediaItemIndex
                            val found = resolveSongFromMediaId(
                                mediaId = mediaIdStr,
                                index = currentIdx,
                                queue = queue,
                                fallbackSongs = _allSongs.value,
                                requireIndexSongIdMatch = true
                            )

                            if (found != null) {
                                val current = this@MusicViewModel.currentSong.value
                                val isSameSongInstance = if (current != null) {
                                    if (found.instanceId > 0L && current.instanceId > 0L) {
                                        found.instanceId == current.instanceId
                                    } else {
                                        found.id == current.id
                                    }
                                } else false

                                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && isSameSongInstance) {
                                    // Queue reorder keeps the same track instance playing; refresh index without resetting playback position
                                    this@MusicViewModel.currentQueueIndex.value = currentIdx
                                    this@MusicViewModel.currentSong.value = found
                                    return
                                }

                                val expectedKey = pendingTrackMediaId
                                if (expectedKey != null) {
                                    val expectedSongId = expectedKey.substringBefore('#').toLongOrNull()
                                    val matchesPending = (mediaIdStr == expectedKey) ||
                                        (expectedSongId != null && songId == expectedSongId)
                                    if (!matchesPending) {
                                        // Skip stale transition event when user skipped rapidly to a newer target track
                                        return
                                    }
                                    pendingTrackMediaId = null
                                    pendingResetDeadlineMs = 0L
                                }

                                recordSongPlayed(songId)
                                seekHoldUntilElapsedMs = 0L
                                this@MusicViewModel.currentQueueIndex.value = currentIdx
                                this@MusicViewModel.currentSong.value = found
                                this@MusicViewModel.currentPosition.value = 0L
                                this@MusicViewModel.playbackProgress.value = 0f
                                this@MusicViewModel.currentDuration.value = found.duration
                                prefs.edit().putLong(PREF_LAST_SONG_ID, found.id).apply()
                                AlbumArtManager.getInstance(getApplication()).preloadQueueNeighbors(
                                    queue = queue,
                                    currentIndex = currentIdx,
                                    repeatMode = this@MusicViewModel.repeatMode.value
                                )
                                if (isPlaying) {
                                    startProgressTracker()
                                }
                            }
                        }

                        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                            // Physical queue shuffling keeps ExoPlayer's ShuffleOrder disabled; ignore false resets.
                            if (shuffleModeEnabled) {
                                this@apply.shuffleModeEnabled = false
                            }
                        }

                        override fun onRepeatModeChanged(newRepeatMode: Int) {
                            this@MusicViewModel.repeatMode.value = newRepeatMode
                            prefs.edit().putInt(PREF_REPEAT_MODE, newRepeatMode).apply()
                        }
                    })
                }
                val externalPending = pendingExternalSong
                if (externalPending != null) {
                    pendingExternalSong = null
                    playSong(externalPending, listOf(externalPending), sourceName = externalPending.album)
                    openNowPlayingRequest.value = true
                } else {
                    restorePlaybackStateIfPossible()
                }
            } catch (e: Exception) {
                PDLog.e(PDLog.UI, "MEDIA_CTRL_INIT_ERR", "err=${e.message}", e)
            }
        }, MoreExecutors.directExecutor())
    }

    // ═══ External Intent Resolution ═══
    fun consumeOpenNowPlayingRequest() {
        openNowPlayingRequest.value = false
    }

    fun handleExternalAudioIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == PlaybackService.ACTION_OPEN_NOW_PLAYING ||
            intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_NOW_PLAYING, false)
        ) {
            if (!intent.getBooleanExtra(EXTRA_HANDLED_OPEN_PLAYER, false)) {
                intent.putExtra(EXTRA_HANDLED_OPEN_PLAYER, true)
                openNowPlayingRequest.value = true
            }
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (intent.getBooleanExtra(EXTRA_HANDLED_EXTERNAL, false)) return
        intent.putExtra(EXTRA_HANDLED_EXTERNAL, true)

        pendingExternalSong = null
        lastHandledIntentUriStr = uri.toString()

        viewModelScope.launch {
            val existingSongs = _allSongs.value
            val mediaStoreId = if (uri.scheme == "content" && uri.authority == "media") {
                runCatching { ContentUris.parseId(uri) }.getOrNull()
            } else null

            val matched = existingSongs.find { it.contentUri == uri }
                ?: (if (mediaStoreId != null) existingSongs.find { it.id == mediaStoreId } else null)
                ?: (if (uri.scheme == "file" && !uri.path.isNullOrBlank()) existingSongs.find { it.path == uri.path } else null)

            val resolvedSong = matched ?: withContext(Dispatchers.IO) {
                resolveExternalSongFromUri(uri)
            }

            if (resolvedSong != null) {
                val controller = mediaController
                if (controller != null) {
                    playSong(resolvedSong, listOf(resolvedSong), sourceName = resolvedSong.album)
                    openNowPlayingRequest.value = true
                } else {
                    pendingExternalSong = resolvedSong
                    currentSong.value = resolvedSong
                    currentQueue.value = listOf(resolvedSong)
                    originalQueue.value = listOf(resolvedSong)
                    currentQueueIndex.value = 0
                    currentDuration.value = resolvedSong.duration
                    openNowPlayingRequest.value = true
                }
            }
        }
    }

    private fun resolveExternalSongFromUri(uri: Uri): Song? {
        val appContext = getApplication<Application>()
        var displayName: String? = null
        if (uri.scheme == "content") {
            runCatching {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (col >= 0) displayName = cursor.getString(col)
                    }
                }
            }
        }
        val fallbackName = (displayName ?: uri.lastPathSegment?.substringAfterLast('/'))
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: EXTERNAL_TRACK_FALLBACK_TITLE

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L

        val mmr = MediaMetadataRetriever()
        fun extractMetadataFields() {
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        try {
            mmr.setDataSource(appContext, uri)
            extractMetadataFields()
        } catch (_: Exception) {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                runCatching {
                    mmr.setDataSource(uri.path)
                    extractMetadataFields()
                }
            }
        } finally {
            runCatching { mmr.release() }
        }

        val uriPath = if (uri.scheme == "file") uri.path.orEmpty() else ""
        val mimeType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val isAiffSource = AiffMetadataHelper.isAiffExtensionOrMime(displayName ?: uriPath, mimeType)
        if (isAiffSource && (duration <= 0L || title.isNullOrBlank() || artist.isNullOrBlank() || album.isNullOrBlank())) {
            AiffMetadataHelper.readMetadataFromUri(appContext, uri)?.let { aiffMeta ->
                if (title.isNullOrBlank()) title = aiffMeta.title
                if (artist.isNullOrBlank()) artist = aiffMeta.artist
                if (album.isNullOrBlank()) album = aiffMeta.album
                if (duration <= 0L && aiffMeta.durationMs > 0L) duration = aiffMeta.durationMs
            }
        }

        // High bit prevents synthetic external track IDs from colliding with MediaStore row IDs
        val syntheticId = ((uri.toString().hashCode().toLong() and SYNTHETIC_ID_MASK).coerceAtLeast(1L)) or SYNTHETIC_ID_FLAG
        return Song(
            id = syntheticId,
            title = title.takeIf { !it.isNullOrBlank() } ?: fallbackName,
            artist = artist.takeIf { !it.isNullOrBlank() } ?: UNKNOWN_ARTIST_FALLBACK,
            album = album.takeIf { !it.isNullOrBlank() } ?: UNKNOWN_ALBUM_FALLBACK,
            duration = duration,
            contentUri = uri,
            albumArtUri = uri,
            path = uriPath
        )
    }

    // ═══ Library Scanning & Artwork Caching ═══
    fun onPermissionGranted() {
        hasStoragePermission.value = true
        startLibraryChangeObserver()
        loadMusic()
    }

    private fun startLibraryChangeObserver() {
        if (libraryObserverJob != null) return
        libraryObserverJob = viewModelScope.launch {
            repository.observeLibraryChanges().collect {
                if (!hasStoragePermission.value || !hasCompletedInitialSync) return@collect
                if (isScanning.value || libraryScanJob?.isActive == true) return@collect
                val now = SystemClock.elapsedRealtime()
                if (now - lastSelfDeleteElapsedMs < SELF_DELETE_OBSERVER_COOLDOWN_MS) {
                    return@collect
                }
                if (now - lastScanCompletedElapsedMs < OBSERVER_POST_SCAN_COOLDOWN_MS) {
                    return@collect
                }
                PDLog.d(PDLog.SCANNER, "MEDIASTORE_CHANGE_SYNC") { "mode=silent_incremental" }
                loadMusic(forceRescan = true, clearArtCache = false, silent = true)
            }
        }
    }

    /**
     * Requests a quiet background incremental sync if no scan is currently running and cooldown
     * has elapsed, never cancelling an active scan or flashing redundant notifications.
     */
    fun triggerSilentIncrementalSync() {
        if (!hasStoragePermission.value || !hasCompletedInitialSync) return
        if (isScanning.value || libraryScanJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanCompletedElapsedMs < OBSERVER_POST_SCAN_COOLDOWN_MS) return
        loadMusic(forceRescan = true, clearArtCache = false, silent = true)
    }

    /**
     * Cancels any active library scan safely and dismisses the progress notification.
     */
    fun cancelLibraryScan() {
        scanGeneration.incrementAndGet()
        libraryScanJob?.cancel()
        libraryScanJob = null
        isScanning.value = false
        ScanNotificationHelper.cancel(getApplication())
    }

    fun loadMusic(
        forceRescan: Boolean = false,
        clearArtCache: Boolean = false,
        silent: Boolean = false,
        fullMetadataRefresh: Boolean = false
    ) {
        if (!forceRescan && !clearArtCache && (hasCompletedInitialSync || libraryScanJob?.isActive == true)) {
            return
        }
        // Avoid cancelling an active foreground scan for a silent background observer ping
        if (silent && libraryScanJob?.isActive == true) {
            return
        }

        val myGeneration = scanGeneration.incrementAndGet()
        libraryScanJob?.cancel()
        libraryScanJob = viewModelScope.launch {
            var wasCancelled = false
            // Fast path: hydrate UI immediately from the local SQLite cache on cold start
            if (_allSongs.value.isEmpty() && !clearArtCache) {
                val cachedSongs = repository.getCachedSongs()
                if (cachedSongs.isNotEmpty()) {
                    val cachedAlbums = repository.getCachedAlbums()
                    val resolvedAlbums = if (cachedAlbums.isNotEmpty()) {
                        cachedAlbums
                    } else {
                        withContext(Dispatchers.Default) { repository.extractAlbums(cachedSongs) }
                    }
                    val artists = withContext(Dispatchers.Default) { repository.extractArtists(cachedSongs) }
                    val folders = withContext(Dispatchers.Default) { repository.extractFolders(cachedSongs) }
                    val genres = withContext(Dispatchers.Default) { repository.extractGenres(cachedSongs) }
                    val yearGroups = withContext(Dispatchers.Default) { repository.extractYears(cachedSongs) }
                    publishLibrarySnapshot(
                        songs = cachedSongs,
                        albums = resolvedAlbums,
                        artists = artists,
                        folders = folders,
                        genres = genres,
                        yearGroups = yearGroups,
                        targetPreloadAlbumKeys = null
                    )
                }
            }

            // Incremental two-tier delta scan: skips unchanged files with zero file I/O
            val showNotification = !silent && (_allSongs.value.isEmpty() || forceRescan || clearArtCache)
            isScanning.value = true
            if (showNotification) {
                ScanNotificationHelper.showProgress(getApplication(), _allSongs.value.size, 0, isInitial = true)
            }
            try {
                val appContext = getApplication<Application>()
                val artManager = AlbumArtManager.getInstance(appContext)
                val needsParserMigration =
                    prefs.getInt(PREF_METADATA_PARSER_VERSION, 0) < CURRENT_METADATA_PARSER_VERSION
                val forceFullRefresh = clearArtCache || needsParserMigration || fullMetadataRefresh
                val shouldInvalidateAllArt = clearArtCache || needsParserMigration

                if (shouldInvalidateAllArt) {
                    withContext(Dispatchers.IO) {
                        artManager.invalidateAllForRescan()
                    }
                }

                val config = LibraryScanConfig(
                    minDurationMs = minAudioDurationSec.value * 1000L,
                    excludedFolders = excludedFolders.value,
                    customScanFolders = customScanFolders.value,
                    safTreeUris = safScanUris.value,
                    forceFullMetadataRefresh = forceFullRefresh,
                    clearArtCache = shouldInvalidateAllArt
                )

                val result = repository.runIncrementalScan(
                    config = config,
                    onProgress = { scanned, total, _ ->
                        if (showNotification && scanGeneration.get() == myGeneration) {
                            ScanNotificationHelper.showProgress(appContext, scanned, total)
                        }
                    }
                )

                if (scanGeneration.get() != myGeneration) return@launch

                hasCompletedInitialSync = true
                prefs.edit()
                    .putBoolean(PREF_GENRE_POPULATED, true)
                    .putBoolean(PREF_YEAR_PARSED, true)
                    .putInt(PREF_METADATA_PARSER_VERSION, CURRENT_METADATA_PARSER_VERSION)
                    .apply()

                val hasLibraryDelta = result.addedCount > 0 || result.updatedCount > 0 || result.removedCount > 0
                if (hasLibraryDelta || _allSongs.value.isEmpty() || shouldInvalidateAllArt) {
                    // Selectively invalidate only albums/tracks added, modified, or removed
                    if (!shouldInvalidateAllArt && result.affectedAlbumKeys.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            for (albumKey in result.affectedAlbumKeys) {
                                artManager.invalidateAlbumArt(albumKey)
                            }
                        }
                    }

                    val artists = withContext(Dispatchers.Default) { repository.extractArtists(result.songs) }
                    val folders = withContext(Dispatchers.Default) { repository.extractFolders(result.songs) }
                    val genres = withContext(Dispatchers.Default) { repository.extractGenres(result.songs) }
                    val yearGroups = withContext(Dispatchers.Default) { repository.extractYears(result.songs) }

                    publishLibrarySnapshot(
                        songs = result.songs,
                        albums = result.albums,
                        artists = artists,
                        folders = folders,
                        genres = genres,
                        yearGroups = yearGroups,
                        targetPreloadAlbumKeys = if (!shouldInvalidateAllArt && _allSongs.value.isNotEmpty()) {
                            result.newlyAddedOrUpdatedAlbumKeys
                        } else {
                            null
                        }
                    )
                }

                refreshCacheSize()
                PDLog.i(
                    PDLog.SCANNER,
                    "SCAN_DONE",
                    "ms=${result.durationMs} total=${result.songs.size} albums=${result.albums.size} " +
                        "added=${result.addedCount} updated=${result.updatedCount} removed=${result.removedCount} " +
                        "unchanged=${result.unchangedCount} affectedAlbums=${result.affectedAlbumKeys.size}"
                )
            } catch (ce: CancellationException) {
                wasCancelled = true
                if (scanGeneration.get() == myGeneration) {
                    ScanNotificationHelper.cancel(getApplication())
                }
                throw ce
            } catch (t: Throwable) {
                PDLog.e(PDLog.SCANNER, "SCAN_ERR", "err=${t.message}", t)
            } finally {
                // Only the latest, non-cancelled scan generation may finalize shared UI/notification state
                if (scanGeneration.get() == myGeneration) {
                    isScanning.value = false
                    lastScanCompletedElapsedMs = SystemClock.elapsedRealtime()
                    if (showNotification && !wasCancelled) {
                        ScanNotificationHelper.showCompleted(getApplication(), _allSongs.value.size)
                    }
                }
            }
        }
    }

    private suspend fun publishLibrarySnapshot(
        songs: List<Song>,
        albums: List<Album>,
        artists: List<Artist>,
        folders: List<Folder>,
        genres: List<Genre>,
        yearGroups: List<YearGroup>,
        targetPreloadAlbumKeys: Set<String>? = null
    ) {
        withContext(Dispatchers.Default) {
            AlbumArtManager.getInstance(getApplication()).indexLibrarySources(songs, albums)
        }
        _allSongs.value = songs
        _albums.value = albums
        _artists.value = artists
        _folders.value = folders
        _genres.value = genres
        _yearGroups.value = yearGroups
        refreshPlaylists()
        preloadAlbumArtIntoRam(songs, albums, targetPreloadAlbumKeys)

        if (currentQueue.value.isEmpty()) {
            currentQueue.value = restoreSavedQueue(songs)
        }
        restorePlaybackStateIfPossible()
        updatePlaybackStats()
    }

    @OptIn(ExperimentalCoilApi::class)
    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val artDiskBytes = AlbumArtManager.getInstance(getApplication()).getDiskCacheSizeBytes()
            val coilDiskBytes = Coil.imageLoader(getApplication()).diskCache?.size ?: 0L
            cacheSizeFormatted.value = repository.artCache.formatCacheSize(artDiskBytes + coilDiskBytes)
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearAlbumArtCache() {
        synchronized(songSeedColorCache) { songSeedColorCache.clear() }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AlbumArtManager.getInstance(getApplication()).clearAll()
                Coil.imageLoader(getApplication()).memoryCache?.clear()
                Coil.imageLoader(getApplication()).diskCache?.clear()
            }
            refreshCacheSize()
        }
    }

    /**
     * Actively invalidates L1 RAM, L2 WebP disk, and negative caches for a single album after
     * tag edits or cover updates.
     */
    fun invalidateAlbumArt(
        albumId: Long,
        albumName: String? = null,
        albumArtist: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            AlbumArtManager.getInstance(getApplication()).invalidateAlbumArt(
                albumId = albumId,
                albumName = albumName,
                albumArtist = albumArtist
            )
            refreshCacheSize()
        }
    }

    /**
     * Preloads album-deduplicated `CARD` (for top albums) and `THUMBNAIL` (for top songs) artwork
     * into [AlbumArtManager]'s L1 RAM + L2 WebP caches. When [targetAlbumKeys] is non-null
     * (incremental delta sync), only newly added or modified items are preloaded.
     */
    private fun preloadAlbumArtIntoRam(
        songs: List<Song>,
        albums: List<Album>,
        targetAlbumKeys: Set<String>? = null
    ) {
        preloadArtJob?.cancel()
        val cardTargets = LinkedHashMap<String, AlbumArtRequest>(PRELOAD_MAX_ALBUM_THUMBS)
        val thumbTargets = LinkedHashMap<String, AlbumArtRequest>(PRELOAD_MAX_SONG_THUMBS)
        if (targetAlbumKeys != null) {
            if (targetAlbumKeys.isEmpty()) return
            for (album in albums) {
                val req = album.toAlbumArtRequest()
                if (req.albumKey in targetAlbumKeys) {
                    cardTargets.putIfAbsent(req.albumKey, req)
                    if (cardTargets.size >= PRELOAD_MAX_ALBUM_THUMBS) break
                }
            }
            for (song in songs) {
                val req = song.toAlbumArtRequest()
                if (req.albumKey in targetAlbumKeys && req.albumKey !in cardTargets) {
                    thumbTargets.putIfAbsent(req.albumKey, req)
                    if (thumbTargets.size >= PRELOAD_MAX_SONG_THUMBS) break
                }
            }
        } else {
            for (album in albums.take(PRELOAD_MAX_ALBUM_THUMBS)) {
                val req = album.toAlbumArtRequest()
                cardTargets.putIfAbsent(req.albumKey, req)
            }
            for (song in songs.take(PRELOAD_MAX_SONG_THUMBS)) {
                val req = song.toAlbumArtRequest()
                if (req.albumKey !in cardTargets) {
                    thumbTargets.putIfAbsent(req.albumKey, req)
                }
            }
        }
        if (cardTargets.isEmpty() && thumbTargets.isEmpty()) return

        val cardList = cardTargets.values.toList()
        val thumbList = thumbTargets.values.toList()
        preloadArtJob = viewModelScope.launch(preloadArtDispatcher) {
            val artManager = AlbumArtManager.getInstance(getApplication())
            val runtime = Runtime.getRuntime()

            fun hasMemoryHeadroom(): Boolean {
                val used = runtime.totalMemory() - runtime.freeMemory()
                return used.toFloat() / runtime.maxMemory().toFloat() < PRELOAD_MEMORY_HEADROOM_RATIO
            }

            // Loading CARD also opportunistically derives THUMBNAIL in RAM with zero extra I/O
            for (req in cardList) {
                if (!isActive || !hasMemoryHeadroom()) break
                runCatching { artManager.loadEntry(req, ArtResolutionBucket.CARD) }
            }
            for (req in thumbList) {
                if (!isActive || !hasMemoryHeadroom()) break
                runCatching { artManager.loadEntry(req, ArtResolutionBucket.THUMBNAIL) }
            }
        }
    }

    // ═══ Playback History & Statistics ═══
    fun recordSongPlayed(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.recordPlay(songId)
        }
        statsUpdateJob?.cancel()
        statsUpdateJob = viewModelScope.launch {
            // Defer HomeScreen carousel recalculation until after track-change animations settle
            delay(STATS_UPDATE_DEFER_MS)
            updatePlaybackStats()
        }
    }

    fun updatePlaybackStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = _allSongs.value
            val albumsList = _albums.value
            val artistsList = _artists.value
            if (all.isEmpty()) return@launch

            val recentIds = repository.getRecentlyPlayedSongIds()
            if (recentIds.isEmpty()) {
                mostPlayedSongs.value = emptyList()
                mostPlayedAlbums.value = emptyList()
                mostPlayedArtists.value = emptyList()
                return@launch
            }

            val songsById = all.associateBy { it.id }
            val recentSongs = recentIds.mapNotNull { songsById[it] }

            // Recently played tracks ordered most-recent first
            mostPlayedSongs.value = recentSongs

            // Recently played albums ordered by most-recent track
            val albumsById = albumsList.associateBy { it.id }
            val seenAlbumIds = HashSet<Long>()
            val recentAlbums = mutableListOf<Album>()
            for (song in recentSongs) {
                if (seenAlbumIds.add(song.albumId)) {
                    albumsById[song.albumId]?.let { recentAlbums.add(it) }
                }
            }
            mostPlayedAlbums.value = recentAlbums

            // Recently played artists ordered by most-recent track
            val artistsByName = artistsList.associateBy { it.name.trim().lowercase() }
            val seenArtistKeys = HashSet<String>()
            val recentArtists = mutableListOf<Artist>()
            for (song in recentSongs) {
                if (song.artist.isNotBlank() && song.artist != UNKNOWN_ARTIST_FALLBACK) {
                    val key = song.artist.trim().lowercase()
                    if (seenArtistKeys.add(key)) {
                        artistsByName[key]?.let { recentArtists.add(it) }
                    }
                }
            }
            mostPlayedArtists.value = recentArtists
        }
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            mostPlayedSongs.value = emptyList()
            mostPlayedAlbums.value = emptyList()
            mostPlayedArtists.value = emptyList()
        }
    }

    // ═══ Queue Persistence & State Restoration ═══
    private fun assignInstanceId(song: Song): Song = song.copy(instanceId = nextInstanceId++)

    private fun assignInstanceIds(songs: List<Song>): List<Song> = songs.map { it.copy(instanceId = nextInstanceId++) }

    private fun alignInstanceIdsWithQueue(targetSongs: List<Song>, referenceQueue: List<Song>): List<Song> {
        val buckets = HashMap<Long, ArrayDeque<Long>>(referenceQueue.size)
        for (s in referenceQueue) {
            if (s.instanceId > 0L) {
                buckets.getOrPut(s.id) { ArrayDeque() }.addLast(s.instanceId)
            }
        }
        return targetSongs.map { song ->
            val matchedInstId = buckets[song.id]?.removeFirstOrNull()
            if (matchedInstId != null) {
                song.copy(instanceId = matchedInstId)
            } else {
                assignInstanceId(song)
            }
        }
    }

    private fun saveQueueToPrefs() {
        val queueSnapshot = currentQueue.value
        val origSnapshot = originalQueue.value
        saveQueueJob?.cancel()
        saveQueueJob = viewModelScope.launch(Dispatchers.Default) {
            delay(QUEUE_SAVE_DEBOUNCE_MS)
            val editor = prefs.edit()
            if (queueSnapshot.isNotEmpty()) {
                editor.putString(PREF_SAVED_QUEUE_IDS, queueSnapshot.joinToString(",") { it.id.toString() })
            }
            if (origSnapshot.isNotEmpty()) {
                editor.putString(PREF_SAVED_ORIGINAL_QUEUE_IDS, origSnapshot.joinToString(",") { it.id.toString() })
            }
            editor.apply()
        }
    }

    private fun restoreSavedQueue(availableSongs: List<Song>): List<Song> {
        val savedIdsString = prefs.getString(PREF_SAVED_QUEUE_IDS, null) ?: return assignInstanceIds(availableSongs)
        val ids = savedIdsString.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return assignInstanceIds(availableSongs)

        val songMap = availableSongs.associateBy { it.id }
        val restored = ids.mapNotNull { songMap[it] }
        return assignInstanceIds(if (restored.isNotEmpty()) restored else availableSongs)
    }

    private fun restorePlaybackStateIfPossible() {
        if (pendingExternalSong != null) return
        val songs = _allSongs.value
        if (songs.isEmpty()) return

        if (currentQueue.value.isEmpty()) {
            currentQueue.value = restoreSavedQueue(songs)
        }

        if (originalQueue.value.isEmpty()) {
            val savedOrigString = prefs.getString(PREF_SAVED_ORIGINAL_QUEUE_IDS, null)
            if (savedOrigString != null) {
                val origIds = savedOrigString.split(",").mapNotNull { it.trim().toLongOrNull() }
                val songMap = songs.associateBy { it.id }
                val restoredOrig = origIds.mapNotNull { songMap[it] }
                if (restoredOrig.isNotEmpty()) {
                    originalQueue.value = alignInstanceIdsWithQueue(restoredOrig, currentQueue.value)
                }
            }
            if (originalQueue.value.isEmpty()) {
                originalQueue.value = currentQueue.value
            }
        }

        val controller = mediaController
        if (controller != null && controller.currentMediaItem != null) {
            // Re-sync live queue order from MediaController in case notification shuffle ran while UI was closed
            if (controller.mediaItemCount > 0) {
                val songMap = songs.associateBy { it.id }
                val liveQueue = (0 until controller.mediaItemCount).mapNotNull { idx ->
                    val mediaId = controller.getMediaItemAt(idx).mediaId
                    val parts = mediaId.split('#')
                    val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val instId = parts.getOrNull(1)?.toLongOrNull()?.also { existing ->
                        if (existing >= nextInstanceId) nextInstanceId = existing + 1L
                    } ?: nextInstanceId++
                    songMap[id]?.copy(instanceId = instId)
                }
                if (liveQueue.isNotEmpty()) {
                    currentQueue.value = liveQueue
                    if (!PlaybackService.isPhysicalShuffleEnabled.value && originalQueue.value.isEmpty()) {
                        originalQueue.value = liveQueue
                    } else if (originalQueue.value.isNotEmpty()) {
                        originalQueue.value = alignInstanceIdsWithQueue(originalQueue.value, liveQueue)
                    }
                }
            }
            if (PlaybackService.isPhysicalShuffleEnabled.value && originalQueue.value.isNotEmpty()) {
                PlaybackService.setPhysicalShuffleState(true, originalQueue.value.map { it.toMediaItem() })
            }
            val queue = currentQueue.value
            val mediaIdStr = controller.currentMediaItem?.mediaId
            val songId = mediaIdStr?.substringBefore('#')?.toLongOrNull()
            val currentIdx = controller.currentMediaItemIndex
            val activeSong = resolveSongFromMediaId(
                mediaId = mediaIdStr,
                index = currentIdx,
                queue = queue,
                fallbackSongs = songs,
                requireIndexSongIdMatch = true
            )
            if (activeSong != null) {
                currentSong.value = activeSong
                val resolvedIdx = if (currentIdx in queue.indices && queue[currentIdx].id == songId) {
                    currentIdx
                } else {
                    queue.indexOfFirst {
                        if (activeSong.instanceId > 0L && it.instanceId > 0L) {
                            it.instanceId == activeSong.instanceId
                        } else {
                            it.id == activeSong.id
                        }
                    }.coerceAtLeast(0)
                }
                currentQueueIndex.value = resolvedIdx
            }
            isPlaying.value = controller.isPlaying
            val pos = controller.currentPosition.coerceAtLeast(0L)
            val dur = controller.duration.coerceAtLeast(1L)
            currentPosition.value = pos
            currentDuration.value = dur
            playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            repeatMode.value = controller.repeatMode
            isShuffle.value = PlaybackService.isPhysicalShuffleEnabled.value

            if (controller.isPlaying) {
                startProgressTracker()
            }
            return
        }

        val queue = currentQueue.value

        val savedSongId = prefs.getLong(PREF_LAST_SONG_ID, -1L)
        // On a fresh install with no playback history, keep currentSong null so MiniPlayer remains idle
        if (savedSongId == -1L) {
            return
        }

        val savedPos = prefs.getLong(PREF_LAST_POSITION_MS, 0L)
        val initialSong = queue.find { it.id == savedSongId } ?: songs.find { it.id == savedSongId } ?: return

        if (currentSong.value == null) {
            val startIndex = queue.indexOfFirst { it.id == initialSong.id }.coerceAtLeast(0)
            currentQueueIndex.value = startIndex
            currentSong.value = initialSong
            val safePos = if (savedPos in 0..initialSong.duration) savedPos else 0L
            currentPosition.value = safePos
            currentDuration.value = initialSong.duration
            playbackProgress.value = if (initialSong.duration > 0) {
                (safePos.toFloat() / initialSong.duration).coerceIn(0f, 1f)
            } else {
                0f
            }

            controller?.let { c ->
                if (c.currentMediaItem == null) {
                    val mediaItems = queue.map { it.toMediaItem() }
                    c.playWhenReady = false
                    c.setMediaItems(mediaItems, startIndex, safePos)
                    c.repeatMode = repeatMode.value
                    c.shuffleModeEnabled = false
                    if (isShuffle.value && originalQueue.value.isNotEmpty()) {
                        PlaybackService.setPhysicalShuffleState(true, originalQueue.value.map { it.toMediaItem() })
                    }
                    c.prepare()
                }
                isPlaying.value = c.isPlaying
            }
        }
    }

    private fun resolveSongFromMediaId(
        mediaId: String?,
        index: Int,
        queue: List<Song>,
        fallbackSongs: List<Song>,
        requireIndexSongIdMatch: Boolean = true
    ): Song? {
        val parts = mediaId?.split("#")
        val songId = parts?.getOrNull(0)?.toLongOrNull()
        val instanceId = parts?.getOrNull(1)?.toLongOrNull()
        val atIndex = if (index in queue.indices) {
            queue[index].let { candidate ->
                if (requireIndexSongIdMatch) candidate.takeIf { it.id == songId } else candidate
            }
        } else {
            null
        }
        return atIndex
            ?: (if (instanceId != null) queue.find { it.instanceId == instanceId } else null)
            ?: queue.find { it.id == songId }
            ?: fallbackSongs.find { it.id == songId }
    }

    // ═══ Playback & Queue Controls ═══
    fun playSong(song: Song, queue: List<Song> = _allSongs.value, sourceName: String = DEFAULT_QUEUE_SOURCE) {
        val controller = mediaController ?: return
        val hadPendingQueuePrep = queuePrepareJob?.isActive == true
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        recordSongPlayed(song.id)
        isLiked.value = favoriteSongIds.value.contains(song.id)
        prefs.edit().putLong(PREF_LAST_SONG_ID, song.id).apply()

        val sourceList = if (queue.isNotEmpty()) queue else listOf(song)
        val existingQueue = currentQueue.value
        val isSameQueue = !hadPendingQueuePrep &&
            existingQueue.isNotEmpty() &&
            queueSource.value == sourceName &&
            controller.mediaItemCount == existingQueue.size &&
            sourceList.size == existingQueue.size &&
            sourceList.first().id == existingQueue.first().id &&
            sourceList.last().id == existingQueue.last().id &&
            sourceList.indices.all { sourceList[it].id == existingQueue[it].id }

        if (isSameQueue) {
            val targetIndex = sourceList.indexOfFirst {
                if (song.instanceId > 0L && it.instanceId > 0L) it.instanceId == song.instanceId else it === song
            }.takeIf { it >= 0 } ?: existingQueue.indexOfFirst {
                if (song.instanceId > 0L && it.instanceId > 0L) it.instanceId == song.instanceId else it.id == song.id
            }.coerceAtLeast(0)
            val targetSong = existingQueue[targetIndex]
            trackDirection.value = if (targetIndex < currentQueueIndex.value) -1 else 1
            currentQueueIndex.value = targetIndex
            currentSong.value = targetSong
            beginOptimisticTrackReset(targetSong)

            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.seekToDefaultPosition(targetIndex)
            if (!controller.isPlaying) {
                controller.play()
            }
            isPlaying.value = true
            startProgressTracker()
            return
        }

        queueSource.value = sourceName
        prefs.edit().putString(PREF_QUEUE_SOURCE, sourceName).apply()

        val songsToPlay = assignInstanceIds(sourceList)
        originalQueue.value = songsToPlay

        val originalIndex = sourceList.indexOfFirst {
            if (song.instanceId > 0L && it.instanceId > 0L) it.instanceId == song.instanceId else it === song
        }.takeIf { it >= 0 } ?: sourceList.indexOfFirst { it.id == song.id }
        val targetSong = if (originalIndex in songsToPlay.indices) {
            songsToPlay[originalIndex]
        } else {
            songsToPlay.find { it.id == song.id } ?: song
        }

        val shuffleActive = isShuffle.value && songsToPlay.size > 1
        val activeQueue = if (shuffleActive) {
            listOf(targetSong) + songsToPlay.filter { it.instanceId != targetSong.instanceId }.shuffled()
        } else {
            songsToPlay
        }
        currentQueue.value = activeQueue
        saveQueueToPrefs()

        val startIndex = activeQueue.indexOfFirst { it.instanceId == targetSong.instanceId }.coerceAtLeast(0)
        currentQueueIndex.value = startIndex
        currentSong.value = targetSong
        beginOptimisticTrackReset(targetSong)
        isPlaying.value = true

        if (activeQueue.size > LARGE_QUEUE_ASYNC_THRESHOLD) {
            val targetRepeatMode = repeatMode.value
            queuePrepareJob = viewModelScope.launch {
                val (mediaItems, origMediaItems) = withContext(Dispatchers.Default) {
                    val activeItems = activeQueue.map { it.toMediaItem() }
                    val origItems = if (shuffleActive) songsToPlay.map { it.toMediaItem() } else emptyList()
                    activeItems to origItems
                }
                ensureActive()
                val c = mediaController ?: return@launch
                if (shuffleActive) {
                    PlaybackService.setPhysicalShuffleState(true, origMediaItems)
                }
                c.setMediaItems(mediaItems, startIndex, 0L)
                c.repeatMode = targetRepeatMode
                c.shuffleModeEnabled = false
                c.prepare()
                c.play()
                isPlaying.value = true
                startProgressTracker()
            }
            return
        }

        if (shuffleActive) {
            PlaybackService.setPhysicalShuffleState(true, songsToPlay.map { it.toMediaItem() })
        }
        val mediaItems = activeQueue.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.repeatMode = repeatMode.value
        controller.shuffleModeEnabled = false
        controller.prepare()
        controller.play()
        startProgressTracker()
    }

    fun playSongFromQueue(song: Song) {
        playSongFromQueue(-1, song)
    }

    fun playSongFromQueue(index: Int, song: Song) {
        val controller = mediaController ?: return
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val queue = currentQueue.value
        val targetIndex = if (index in queue.indices) {
            index
        } else {
            queue.indexOfFirst {
                if (song.instanceId > 0L && it.instanceId > 0L) it.instanceId == song.instanceId else it.id == song.id
            }.takeIf { it >= 0 } ?: queue.indexOfFirst { it.id == song.id }
        }
        if (targetIndex >= 0) {
            trackDirection.value = if (targetIndex < currentQueueIndex.value) -1 else 1
            currentQueueIndex.value = targetIndex
            val selectedSong = queue.getOrNull(targetIndex) ?: song
            currentSong.value = selectedSong
            beginOptimisticTrackReset(selectedSong)
            prefs.edit().putLong(PREF_LAST_SONG_ID, selectedSong.id).apply()
            recordSongPlayed(selectedSong.id)

            if (targetIndex in 0 until controller.mediaItemCount) {
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.seekToDefaultPosition(targetIndex)
                if (!controller.isPlaying) {
                    controller.play()
                }
                isPlaying.value = true
                startProgressTracker()
            }
        }
    }

    fun playAll(songs: List<Song>, startIndex: Int = 0, sourceName: String = DEFAULT_QUEUE_SOURCE) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val songsToPlay = assignInstanceIds(songs)
        originalQueue.value = songsToPlay
        val safeStartIndex = startIndex.coerceIn(0, songsToPlay.size - 1)
        val initialSong = songsToPlay[safeStartIndex]
        val shuffleActive = isShuffle.value && songsToPlay.size > 1
        val activeQueue = if (shuffleActive) {
            listOf(initialSong) + songsToPlay.filter { it.instanceId != initialSong.instanceId }.shuffled()
        } else {
            songsToPlay
        }
        currentQueue.value = activeQueue
        queueSource.value = sourceName
        prefs.edit().putString(PREF_QUEUE_SOURCE, sourceName).apply()
        saveQueueToPrefs()

        val actualStartIndex = if (shuffleActive) 0 else safeStartIndex
        currentQueueIndex.value = actualStartIndex
        currentSong.value = initialSong
        beginOptimisticTrackReset(initialSong)
        recordSongPlayed(initialSong.id)
        prefs.edit().putLong(PREF_LAST_SONG_ID, initialSong.id).apply()
        isPlaying.value = true

        if (activeQueue.size > LARGE_QUEUE_ASYNC_THRESHOLD) {
            val targetRepeatMode = repeatMode.value
            queuePrepareJob = viewModelScope.launch {
                val (mediaItems, origMediaItems) = withContext(Dispatchers.Default) {
                    val activeItems = activeQueue.map { it.toMediaItem() }
                    val origItems = if (shuffleActive) songsToPlay.map { it.toMediaItem() } else emptyList()
                    activeItems to origItems
                }
                ensureActive()
                val c = mediaController ?: return@launch
                if (shuffleActive) {
                    PlaybackService.setPhysicalShuffleState(true, origMediaItems)
                }
                c.setMediaItems(mediaItems, actualStartIndex, 0L)
                c.repeatMode = targetRepeatMode
                c.shuffleModeEnabled = false
                c.prepare()
                c.play()
                isPlaying.value = true
                startProgressTracker()
            }
            return
        }

        if (shuffleActive) {
            PlaybackService.setPhysicalShuffleState(true, songsToPlay.map { it.toMediaItem() })
        }
        val mediaItems = activeQueue.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, actualStartIndex, 0L)
        controller.repeatMode = repeatMode.value
        controller.shuffleModeEnabled = false
        controller.prepare()
        controller.play()
        startProgressTracker()
    }

    fun shuffleAll(songs: List<Song>, sourceName: String = DEFAULT_QUEUE_SOURCE) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        // Preserve original queue order while building a physically shuffled active queue starting at index 0
        val origSongs = assignInstanceIds(songs)
        val shuffledSongs = origSongs.shuffled()
        originalQueue.value = origSongs
        currentQueue.value = shuffledSongs
        queueSource.value = sourceName
        prefs.edit().putString(PREF_QUEUE_SOURCE, sourceName).apply()
        saveQueueToPrefs()

        val selectedSong = shuffledSongs.first()
        currentQueueIndex.value = 0
        currentSong.value = selectedSong
        beginOptimisticTrackReset(selectedSong)
        isShuffle.value = true
        prefs.edit().putBoolean(PREF_IS_SHUFFLE, true).apply()
        recordSongPlayed(selectedSong.id)
        prefs.edit().putLong(PREF_LAST_SONG_ID, selectedSong.id).apply()
        isPlaying.value = true

        if (shuffledSongs.size > LARGE_QUEUE_ASYNC_THRESHOLD) {
            val targetRepeatMode = repeatMode.value
            queuePrepareJob = viewModelScope.launch {
                val (mediaItems, origMediaItems) = withContext(Dispatchers.Default) {
                    shuffledSongs.map { it.toMediaItem() } to origSongs.map { it.toMediaItem() }
                }
                ensureActive()
                val c = mediaController ?: return@launch
                c.setMediaItems(mediaItems, 0, 0L)
                c.repeatMode = targetRepeatMode
                c.shuffleModeEnabled = false
                PlaybackService.setPhysicalShuffleState(true, origMediaItems)
                c.prepare()
                c.play()
                isPlaying.value = true
                startProgressTracker()
            }
            return
        }

        val mediaItems = shuffledSongs.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, 0, 0L)
        controller.repeatMode = repeatMode.value
        controller.shuffleModeEnabled = false
        PlaybackService.setPhysicalShuffleState(true, origSongs.map { it.toMediaItem() })
        controller.prepare()
        controller.play()
        startProgressTracker()
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val list = currentQueue.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        currentQueue.value = list
        if (!isShuffle.value) {
            originalQueue.value = list
        }

        // Keep currentQueueIndex aligned with the dragged item's new position
        val cur = currentQueueIndex.value
        currentQueueIndex.value = when {
            fromIndex == cur -> toIndex
            fromIndex < cur && toIndex >= cur -> (cur - 1).coerceAtLeast(0)
            fromIndex > cur && toIndex <= cur -> (cur + 1).coerceAtMost(list.size - 1)
            else -> cur
        }

        saveQueueToPrefs()
        mediaController?.let { c ->
            try {
                if (fromIndex in 0 until c.mediaItemCount && toIndex in 0 until c.mediaItemCount) {
                    c.moveMediaItem(fromIndex, toIndex)
                }
            } catch (e: Exception) {
                PDLog.e(PDLog.UI, "QUEUE_MOVE_ERR", "from=$fromIndex to=$toIndex", e)
            }
        }
    }

    fun removeFromQueue(index: Int) {
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val list = currentQueue.value.toMutableList()
        if (index !in list.indices) return
        val cur = currentQueueIndex.value
        val wasRemovingCurrent = (index == cur)
        val removedSong = list.removeAt(index)
        currentQueue.value = list
        originalQueue.value = originalQueue.value.filterNot {
            if (removedSong.instanceId > 0L && it.instanceId > 0L) it.instanceId == removedSong.instanceId
            else it.id == removedSong.id
        }
        saveQueueToPrefs()

        mediaController?.let { c ->
            try {
                if (index in 0 until c.mediaItemCount) {
                    c.removeMediaItem(index)
                }
            } catch (e: Exception) {
                PDLog.e(PDLog.UI, "QUEUE_REMOVE_ERR", "idx=$index", e)
            }
        }

        if (wasRemovingCurrent) {
            if (list.isNotEmpty()) {
                val nextIndex = index.coerceAtMost(list.size - 1)
                currentQueueIndex.value = nextIndex
                val nextSong = list[nextIndex]
                currentSong.value = nextSong
                currentPosition.value = 0L
                playbackProgress.value = 0f
                currentDuration.value = nextSong.duration
                prefs.edit().putLong(PREF_LAST_SONG_ID, nextSong.id).apply()
                mediaController?.let { c ->
                    if (nextIndex in 0 until c.mediaItemCount) {
                        c.seekToDefaultPosition(nextIndex)
                        if (isPlaying.value && !c.isPlaying) {
                            c.play()
                        }
                    }
                }
            } else {
                try {
                    mediaController?.stop()
                    mediaController?.clearMediaItems()
                } catch (e: Exception) {
                    PDLog.w(PDLog.UI, "QUEUE_CLEAR_ERR", "err=${e.message}", e)
                }
                currentQueueIndex.value = 0
                currentSong.value = null
                isPlaying.value = false
            }
        } else if (index < cur) {
            currentQueueIndex.value = (cur - 1).coerceAtLeast(0)
        }
    }

    fun removeFromQueue(song: Song) {
        val index = currentQueue.value.indexOfFirst {
            if (song.instanceId > 0L && it.instanceId > 0L) it.instanceId == song.instanceId else it.id == song.id
        }
        if (index >= 0) {
            removeFromQueue(index)
        }
    }

    private fun resolveAccuratePositionMs(controller: MediaController): Long {
        return PlaybackService.getLivePlayerPositionMs() ?: controller.currentPosition.coerceAtLeast(0L)
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            prefs.edit().putLong(PREF_LAST_POSITION_MS, resolveAccuratePositionMs(controller)).apply()
            controller.pause()
            isPlaying.value = false
            stopProgressTracker()
        } else {
            if (controller.currentMediaItem == null && currentSong.value != null) {
                playSong(currentSong.value!!)
            } else if (controller.currentMediaItem == null && currentSong.value == null) {
                val firstSong = currentQueue.value.firstOrNull() ?: _allSongs.value.firstOrNull()
                if (firstSong != null) {
                    playSong(firstSong, currentQueue.value.ifEmpty { _allSongs.value })
                }
            } else {
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                    controller.seekTo(currentPosition.value.coerceAtLeast(0L))
                } else if (controller.playbackState == Player.STATE_ENDED) {
                    controller.seekTo(0, 0L)
                    val firstSong = currentQueue.value.firstOrNull()
                    if (firstSong != null) {
                        currentQueueIndex.value = 0
                        currentSong.value = firstSong
                        currentPosition.value = 0L
                        playbackProgress.value = 0f
                        currentDuration.value = firstSong.duration
                    }
                }
                controller.play()
                isPlaying.value = true
                startProgressTracker()
            }
        }
    }

    /**
     * Stops playback completely (`STATE_IDLE`), rewinds to `0s`, and clears persisted resume state
     * so long-pressing Play/Pause acts as a clean hardware/audio stop & reset.
     */
    fun stopAndReset() {
        val controller = mediaController ?: return
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        seekJob?.cancel()
        pendingTrackMediaId = null
        pendingResetDeadlineMs = 0L
        seekHoldUntilElapsedMs = 0L
        lastPreviousResetElapsedMs = 0L
        lastPreviousResetSongId = -1L

        controller.pause()
        if (controller.currentMediaItem != null) {
            controller.seekTo(0L)
        }
        controller.stop()

        isPlaying.value = false
        currentPosition.value = 0L
        playbackProgress.value = 0f
        stopProgressTracker()
        prefs.edit().putLong(PREF_LAST_POSITION_MS, 0L).apply()
    }

    private fun beginOptimisticTrackReset(target: Song) {
        pendingTrackMediaId = if (target.instanceId > 0L) "${target.id}#${target.instanceId}" else target.id.toString()
        pendingResetDeadlineMs = SystemClock.elapsedRealtime() + OPTIMISTIC_RESET_WINDOW_MS
        seekHoldUntilElapsedMs = 0L
        stopProgressTracker()
        currentPosition.value = 0L
        playbackProgress.value = 0f
        currentDuration.value = target.duration
    }

    fun skipNext() {
        lastPreviousResetElapsedMs = 0L
        lastPreviousResetSongId = -1L
        skipTrack(forward = true)
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        val activeSongId = currentSong.value?.id ?: -1L
        val now = SystemClock.elapsedRealtime()
        val isRapidSkipping = seekJob?.isActive == true || pendingTrackMediaId != null
        val isSecondTap = lastPreviousResetSongId == activeSongId &&
            (now - lastPreviousResetElapsedMs) <= PREVIOUS_DOUBLE_TAP_WINDOW_MS
        val currentPosMs = if (controller.playbackState == Player.STATE_IDLE) {
            currentPosition.value
        } else {
            resolveAccuratePositionMs(controller)
        }

        if (!isRapidSkipping && !isSecondTap && currentPosMs > PREVIOUS_RESTART_THRESHOLD_MS) {
            if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
                controller.prepare()
            }
            controller.seekTo(0L)
            currentPosition.value = 0L
            playbackProgress.value = 0f
            prefs.edit().putLong(PREF_LAST_POSITION_MS, 0L).apply()
            lastPreviousResetElapsedMs = now
            lastPreviousResetSongId = activeSongId
            return
        }

        lastPreviousResetElapsedMs = 0L
        lastPreviousResetSongId = -1L
        skipTrack(forward = false)
    }

    private fun skipTrack(forward: Boolean) {
        val controller = mediaController ?: return
        val queue = currentQueue.value
        if (queue.isEmpty()) return

        try {
            val current = currentSong.value
            val currentIdx = controller.currentMediaItemIndex.takeIf { it in queue.indices }
                ?: queue.indexOfFirst { it.instanceId == current?.instanceId }.takeIf { it >= 0 }
                ?: queue.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)

            val shuffleTargetIdx = if (forward) controller.nextMediaItemIndex else controller.previousMediaItemIndex
            val hasValidShuffleTarget = controller.shuffleModeEnabled &&
                shuffleTargetIdx != C.INDEX_UNSET &&
                shuffleTargetIdx in 0 until controller.mediaItemCount

            val linearTargetIdx = if (forward) {
                if (currentIdx in 0 until queue.size - 1) currentIdx + 1 else 0
            } else {
                if (currentIdx > 0) currentIdx - 1 else (queue.size - 1).coerceAtLeast(0)
            }

            val targetSong: Song? = if (hasValidShuffleTarget) {
                val targetMediaId = controller.getMediaItemAt(shuffleTargetIdx).mediaId
                resolveSongFromMediaId(
                    mediaId = targetMediaId,
                    index = shuffleTargetIdx,
                    queue = queue,
                    fallbackSongs = _allSongs.value,
                    requireIndexSongIdMatch = false
                )
            } else {
                queue.getOrNull(linearTargetIdx)
            }

            val targetQueueIdx = if (hasValidShuffleTarget) shuffleTargetIdx else linearTargetIdx

            // Apply optimistic track state immediately and freeze progress polling to prevent old-track position flicker
            if (targetSong != null) {
                trackDirection.value = if (forward) 1 else -1
                currentQueueIndex.value = targetQueueIdx
                currentSong.value = targetSong
                beginOptimisticTrackReset(targetSong)
                // Warm 512px CARD artwork during the 160ms skip debounce so MediaSession resolves
                // notification artwork synchronously (isDone == true) when the seek executes.
                AlbumArtManager.getInstance(getApplication()).preload(
                    targetSong.toAlbumArtRequest(),
                    ArtResolutionBucket.CARD
                )
                prefs.edit()
                    .putLong(PREF_LAST_SONG_ID, targetSong.id)
                    .putLong(PREF_LAST_POSITION_MS, 0L)
                    .apply()
            } else {
                stopProgressTracker()
            }

            // Debounce MediaController seek so rapid consecutive skips coalesce cleanly
            seekJob?.cancel()
            seekJob = viewModelScope.launch {
                delay(SKIP_DEBOUNCE_MS)
                val c = mediaController ?: return@launch
                try {
                    val shouldContinuePlaying = c.isPlaying ||
                        (c.playWhenReady &&
                            c.playbackState != Player.STATE_ENDED &&
                            c.playbackState != Player.STATE_IDLE) ||
                        isPlaying.value

                    if (c.shuffleModeEnabled &&
                        shuffleTargetIdx != C.INDEX_UNSET &&
                        shuffleTargetIdx in 0 until c.mediaItemCount
                    ) {
                        c.seekToDefaultPosition(shuffleTargetIdx)
                    } else {
                        val fallbackIdx = if (forward) {
                            (currentIdx + 1).coerceAtMost(c.mediaItemCount - 1)
                        } else {
                            (currentIdx - 1).coerceAtLeast(0)
                        }
                        val targetIndex = if (targetSong != null) {
                            queue.indexOfFirst { it.instanceId == targetSong.instanceId }.takeIf { it >= 0 }
                                ?: fallbackIdx
                        } else {
                            fallbackIdx
                        }

                        if (targetIndex in 0 until c.mediaItemCount) {
                            c.seekToDefaultPosition(targetIndex)
                        } else if (forward && c.hasNextMediaItem()) {
                            c.seekToNextMediaItem()
                        } else if (!forward && c.hasPreviousMediaItem()) {
                            c.seekToPreviousMediaItem()
                        }
                    }

                    if (c.playbackState == Player.STATE_IDLE) {
                        c.prepare()
                    }
                    if (shouldContinuePlaying) {
                        if (!c.isPlaying) {
                            c.play()
                            isPlaying.value = true
                        }
                        startProgressTracker()
                    } else {
                        if (c.playWhenReady || c.isPlaying) {
                            c.pause()
                        }
                        isPlaying.value = false
                        stopProgressTracker()
                    }
                } catch (e: Exception) {
                    PDLog.w(PDLog.UI, "PLAY_TOGGLE_ERR", "err=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            PDLog.w(PDLog.UI, "PLAY_TOGGLE_ERR", "err=${e.message}", e)
        }
    }

    private fun clampSeekTargetMs(requestedMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return requestedMs.coerceAtLeast(0L)
        val maxSeekableMs = if (durationMs >= SEEK_END_CLAMP_MIN_DURATION_MS) {
            (durationMs - SEEK_END_SAFETY_MARGIN_MS).coerceAtLeast(0L)
        } else {
            durationMs
        }
        return requestedMs.coerceIn(0L, maxSeekableMs)
    }

    fun seekToProgress(progress: Float) {
        val controller = mediaController ?: return
        val dur = controller.duration.takeIf { it > 0L } ?: currentDuration.value
        if (dur > 0) {
            pendingTrackMediaId = null
            pendingResetDeadlineMs = 0L
            if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
                controller.prepare()
            }
            val clampedProgress = progress.coerceIn(0f, 1f)
            val targetMs = clampSeekTargetMs((dur * clampedProgress).toLong(), dur)
            seekHoldUntilElapsedMs = SystemClock.elapsedRealtime() + SEEK_OPTIMISTIC_HOLD_MS
            controller.seekTo(targetMs)
            currentPosition.value = targetMs
            playbackProgress.value = (targetMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun seekToPositionMs(positionMs: Long) {
        val controller = mediaController ?: return
        val dur = controller.duration.takeIf { it > 0L } ?: currentDuration.value
        val targetMs = clampSeekTargetMs(positionMs, dur)
        pendingTrackMediaId = null
        pendingResetDeadlineMs = 0L
        if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
            controller.prepare()
        }
        seekHoldUntilElapsedMs = SystemClock.elapsedRealtime() + SEEK_OPTIMISTIC_HOLD_MS
        controller.seekTo(targetMs)
        currentPosition.value = targetMs
        if (dur > 0L) {
            playbackProgress.value = (targetMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val newShuffle = !isShuffle.value
        val curQueue = currentQueue.value

        if (curQueue.size <= 1) {
            controller.shuffleModeEnabled = false
            isShuffle.value = newShuffle
            prefs.edit().putBoolean(PREF_IS_SHUFFLE, newShuffle).apply()
            PlaybackService.setPhysicalShuffleState(newShuffle)
            return
        }

        val liveIdx = controller.currentMediaItemIndex
            .takeIf { it in curQueue.indices }
            ?: currentQueueIndex.value.coerceIn(0, curQueue.lastIndex)
        val activeSong = curQueue[liveIdx]

        if (newShuffle) {
            // Preserve the unshuffled queue, pin the currently playing track at index 0,
            // and replace items [1..end) in-place so playback / USB DAC streaming never glitches.
            if (originalQueue.value.isEmpty()) {
                originalQueue.value = curQueue
            }
            val origSnapshot = originalQueue.value
            val others = curQueue.filterIndexed { index, _ -> index != liveIdx }.shuffled()
            val shuffledQueue = listOf(activeSong) + others

            currentQueue.value = shuffledQueue
            currentQueueIndex.value = 0
            isShuffle.value = true
            prefs.edit().putBoolean(PREF_IS_SHUFFLE, true).apply()
            saveQueueToPrefs()

            if (curQueue.size > LARGE_QUEUE_ASYNC_THRESHOLD) {
                queuePrepareJob = viewModelScope.launch {
                    val (otherMediaItems, origMediaItems) = withContext(Dispatchers.Default) {
                        others.map { it.toMediaItem() } to origSnapshot.map { it.toMediaItem() }
                    }
                    ensureActive()
                    val c = mediaController ?: return@launch
                    c.shuffleModeEnabled = false
                    val currentLiveIdx = c.currentMediaItemIndex
                    if (c.mediaItemCount > 0 && currentLiveIdx in 0 until c.mediaItemCount) {
                        if (currentLiveIdx > 0) {
                            c.moveMediaItem(currentLiveIdx, 0)
                        }
                        if (c.mediaItemCount > 1) {
                            if (otherMediaItems.isEmpty()) {
                                c.removeMediaItems(1, c.mediaItemCount)
                            } else {
                                c.replaceMediaItems(1, c.mediaItemCount, otherMediaItems)
                            }
                        } else if (otherMediaItems.isNotEmpty()) {
                            c.addMediaItems(1, otherMediaItems)
                        }
                    }
                    PlaybackService.setPhysicalShuffleState(enabled = true, originalMediaItems = origMediaItems)
                }
                return
            }

            controller.shuffleModeEnabled = false
            if (controller.mediaItemCount > 0 && liveIdx in 0 until controller.mediaItemCount) {
                if (liveIdx > 0) {
                    controller.moveMediaItem(liveIdx, 0)
                }
                val otherMediaItems = others.map { it.toMediaItem() }
                if (controller.mediaItemCount > 1) {
                    if (otherMediaItems.isEmpty()) {
                        controller.removeMediaItems(1, controller.mediaItemCount)
                    } else {
                        controller.replaceMediaItems(1, controller.mediaItemCount, otherMediaItems)
                    }
                } else if (otherMediaItems.isNotEmpty()) {
                    controller.addMediaItems(1, otherMediaItems)
                }
            }
            PlaybackService.setPhysicalShuffleState(
                enabled = true,
                originalMediaItems = origSnapshot.map { it.toMediaItem() }
            )
        } else {
            // Restore original queue order strictly around the live playing MediaItem so audio stays uninterrupted
            val baseOrig = originalQueue.value.takeIf { it.isNotEmpty() } ?: curQueue
            val foundIdx = baseOrig.indexOfFirst {
                if (activeSong.instanceId > 0L && it.instanceId > 0L) it.instanceId == activeSong.instanceId
                else it.id == activeSong.id
            }.takeIf { it >= 0 } ?: baseOrig.indexOfFirst { it.id == activeSong.id }

            val restoredMutable = baseOrig.toMutableList()
            val origIdx = if (foundIdx >= 0) {
                restoredMutable[foundIdx] = activeSong
                foundIdx
            } else {
                restoredMutable.add(0, activeSong)
                0
            }
            val restoredQueue = restoredMutable.toList()
            val before = restoredQueue.subList(0, origIdx)
            val after = restoredQueue.subList(origIdx + 1, restoredQueue.size)

            currentQueue.value = restoredQueue
            originalQueue.value = restoredQueue
            currentQueueIndex.value = origIdx
            isShuffle.value = false
            prefs.edit().putBoolean(PREF_IS_SHUFFLE, false).apply()
            saveQueueToPrefs()

            if (restoredQueue.size > LARGE_QUEUE_ASYNC_THRESHOLD) {
                queuePrepareJob = viewModelScope.launch {
                    val (beforeItems, afterItems) = withContext(Dispatchers.Default) {
                        before.map { it.toMediaItem() } to after.map { it.toMediaItem() }
                    }
                    ensureActive()
                    val c = mediaController ?: return@launch
                    c.shuffleModeEnabled = false
                    val currentLiveIdx = c.currentMediaItemIndex
                    if (c.mediaItemCount > 0 && currentLiveIdx in 0 until c.mediaItemCount) {
                        if (currentLiveIdx + 1 < c.mediaItemCount) {
                            if (afterItems.isEmpty()) {
                                c.removeMediaItems(currentLiveIdx + 1, c.mediaItemCount)
                            } else {
                                c.replaceMediaItems(currentLiveIdx + 1, c.mediaItemCount, afterItems)
                            }
                        } else if (afterItems.isNotEmpty()) {
                            c.addMediaItems(currentLiveIdx + 1, afterItems)
                        }
                        if (currentLiveIdx > 0) {
                            if (beforeItems.isEmpty()) {
                                c.removeMediaItems(0, currentLiveIdx)
                            } else {
                                c.replaceMediaItems(0, currentLiveIdx, beforeItems)
                            }
                        } else if (beforeItems.isNotEmpty()) {
                            c.addMediaItems(0, beforeItems)
                        }
                    }
                    PlaybackService.setPhysicalShuffleState(enabled = false, originalMediaItems = emptyList())
                }
                return
            }

            controller.shuffleModeEnabled = false
            if (controller.mediaItemCount > 0 && liveIdx in 0 until controller.mediaItemCount) {
                val afterItems = after.map { it.toMediaItem() }
                if (liveIdx + 1 < controller.mediaItemCount) {
                    if (afterItems.isEmpty()) {
                        controller.removeMediaItems(liveIdx + 1, controller.mediaItemCount)
                    } else {
                        controller.replaceMediaItems(liveIdx + 1, controller.mediaItemCount, afterItems)
                    }
                } else if (afterItems.isNotEmpty()) {
                    controller.addMediaItems(liveIdx + 1, afterItems)
                }

                val beforeItems = before.map { it.toMediaItem() }
                if (liveIdx > 0) {
                    if (beforeItems.isEmpty()) {
                        controller.removeMediaItems(0, liveIdx)
                    } else {
                        controller.replaceMediaItems(0, liveIdx, beforeItems)
                    }
                } else if (beforeItems.isNotEmpty()) {
                    controller.addMediaItems(0, beforeItems)
                }
            }
            PlaybackService.setPhysicalShuffleState(enabled = false, originalMediaItems = emptyList())
        }
    }

    fun toggleRepeat() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        repeatMode.value = nextMode
        prefs.edit().putInt(PREF_REPEAT_MODE, nextMode).apply()
    }

    fun playNext(song: Song) {
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val list = currentQueue.value.toMutableList()
        val current = currentSong.value
        val controller = mediaController
        val currentIndex = controller?.currentMediaItemIndex?.takeIf { it in list.indices }
            ?: if (current != null) {
                list.indexOfFirst { it.instanceId == current.instanceId }.takeIf { it >= 0 }
                    ?: list.indexOfFirst { it.id == current.id }
            } else {
                -1
            }
        val insertIndex = if (currentIndex >= 0) currentIndex + 1 else list.size
        val songToAdd = assignInstanceId(song)
        list.add(insertIndex, songToAdd)
        currentQueue.value = list
        if (!isShuffle.value) {
            originalQueue.value = list
        } else {
            val origList = originalQueue.value.toMutableList()
            val origCurIdx = if (current != null) {
                origList.indexOfFirst {
                    if (current.instanceId > 0L && it.instanceId > 0L) it.instanceId == current.instanceId
                    else it.id == current.id
                }
            } else {
                -1
            }
            val origInsertIdx = if (origCurIdx >= 0) origCurIdx + 1 else origList.size
            origList.add(origInsertIdx, songToAdd)
            originalQueue.value = origList
        }
        saveQueueToPrefs()

        try {
            if (controller != null) {
                if (controller.mediaItemCount == 0) {
                    controller.setMediaItems(
                        list.map { it.toMediaItem() },
                        insertIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
                        0L
                    )
                    controller.prepare()
                } else {
                    val safeIndex = insertIndex.coerceIn(0, controller.mediaItemCount)
                    controller.addMediaItem(safeIndex, songToAdd.toMediaItem())
                }
            }
        } catch (e: Exception) {
            PDLog.w(PDLog.UI, "QUEUE_PLAY_NEXT_ERR", "songId=${song.id} err=${e.message}", e)
        }
    }

    fun addToQueue(song: Song) {
        queuePrepareJob?.cancel()
        queuePrepareJob = null
        val list = currentQueue.value.toMutableList()
        val songToAdd = assignInstanceId(song)
        list.add(songToAdd)
        currentQueue.value = list
        originalQueue.value = originalQueue.value + songToAdd
        saveQueueToPrefs()

        try {
            val controller = mediaController
            if (controller != null) {
                if (controller.mediaItemCount == 0) {
                    controller.setMediaItems(list.map { it.toMediaItem() }, 0, 0L)
                    controller.prepare()
                } else {
                    controller.addMediaItem(songToAdd.toMediaItem())
                }
            }
        } catch (e: Exception) {
            PDLog.w(PDLog.UI, "QUEUE_ADD_ERR", "songId=${song.id} err=${e.message}", e)
        }
    }

    // ═══ Settings & Sleep Timer ═══
    fun setThemeMode(mode: String) {
        themeMode.value = mode
        prefs.edit().putString(PREF_THEME_MODE, mode).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        dynamicColor.value = enabled
        prefs.edit().putBoolean(PREF_DYNAMIC_COLOR, enabled).apply()
    }

    fun setSongDynamicColor(enabled: Boolean) {
        songDynamicColor.value = enabled
        prefs.edit().putBoolean(PREF_SONG_DYNAMIC_COLOR, enabled).apply()
        if (!enabled) {
            _songSeedColorArgb.value = null
        }
    }

    fun setAmoledMode(enabled: Boolean) {
        amoledMode.value = enabled
        prefs.edit().putBoolean(PREF_AMOLED_MODE, enabled).apply()
    }

    fun setNowPlayingStyle(style: NowPlayingStyle) {
        viewModelScope.launch {
            settingsPreferencesRepository.setNowPlayingStyle(style)
        }
        currentSong.value?.let { activeSong ->
            val targetBucket = if (style == NowPlayingStyle.MODERN) {
                ArtResolutionBucket.ULTRA_RES
            } else {
                ArtResolutionBucket.HIGH_RES
            }
            AlbumArtManager.getInstance(getApplication()).preload(
                activeSong.toAlbumArtRequest(),
                targetBucket
            )
        }
    }

    fun setNowPlayingBlendStyle(style: NowPlayingBlendStyle) {
        viewModelScope.launch {
            settingsPreferencesRepository.setNowPlayingBlendStyle(style)
        }
    }

    fun setNowPlayingSeekbarStyle(style: NowPlayingSeekbarStyle) {
        viewModelScope.launch {
            settingsPreferencesRepository.setNowPlayingSeekbarStyle(style)
        }
    }

    fun setLibraryGridView(isGrid: Boolean) {
        isLibraryGridView.value = isGrid
        prefs.edit().putBoolean(PREF_SAVED_GRID_VIEW, isGrid).apply()
    }

    fun setMinAudioDurationSec(seconds: Int) {
        minAudioDurationSec.value = seconds
        val filterEnabled = seconds > 0
        filterShortAudio.value = filterEnabled
        prefs.edit()
            .putInt(PREF_MIN_AUDIO_DURATION_SEC, seconds)
            .putBoolean(PREF_FILTER_SHORT_AUDIO, filterEnabled)
            .apply()
        loadMusic(forceRescan = true)
    }

    fun setFilterShortAudio(enabled: Boolean) {
        setMinAudioDurationSec(if (enabled) DEFAULT_MIN_DURATION_SEC else 0)
    }

    fun toggleFilterShortAudio() {
        setFilterShortAudio(!filterShortAudio.value)
    }

    fun addExcludedFolder(folderPath: String) {
        val norm = folderPath.replace('\\', '/').trimEnd('/')
        if (norm.isBlank()) return
        val updated = excludedFolders.value + norm
        if (updated != excludedFolders.value) {
            excludedFolders.value = updated
            prefs.edit().putStringSet(PREF_EXCLUDED_FOLDERS, HashSet(updated)).apply()
            loadMusic(forceRescan = true)
        }
    }

    fun removeExcludedFolder(folderPath: String) {
        val norm = folderPath.replace('\\', '/').trimEnd('/')
        val updated = excludedFolders.value.filterNot { it.equals(norm, ignoreCase = true) }.toSet()
        if (updated != excludedFolders.value) {
            excludedFolders.value = updated
            prefs.edit().putStringSet(PREF_EXCLUDED_FOLDERS, HashSet(updated)).apply()
            loadMusic(forceRescan = true)
        }
    }

    /**
     * Registers an additional Tier 2 scan directory (direct filesystem path and/or persisted SAF
     * document tree URI) so `.nomedia` folders and audiophile containers are included during scans.
     */
    fun addCustomScanFolder(folderPath: String?, safTreeUri: Uri?) {
        var changed = false
        val normPath = folderPath?.replace('\\', '/')?.trimEnd('/').orEmpty()
        if (normPath.isNotBlank()) {
            val updatedPaths = customScanFolders.value + normPath
            if (updatedPaths != customScanFolders.value) {
                customScanFolders.value = updatedPaths
                prefs.edit().putStringSet(PREF_CUSTOM_SCAN_FOLDERS, HashSet(updatedPaths)).apply()
                changed = true
            }
        }
        val uriStr = safTreeUri?.toString().orEmpty()
        if (uriStr.isNotBlank()) {
            val updatedUris = safScanUris.value + uriStr
            if (updatedUris != safScanUris.value) {
                safScanUris.value = updatedUris
                prefs.edit().putStringSet(PREF_SAF_SCAN_URIS, HashSet(updatedUris)).apply()
                changed = true
            }
        }
        if (changed) {
            loadMusic(forceRescan = true)
        }
    }

    fun removeCustomScanFolder(folderPath: String) {
        val norm = folderPath.replace('\\', '/').trimEnd('/')
        val updatedPaths = customScanFolders.value.filterNot { it.equals(norm, ignoreCase = true) }.toSet()
        // Also prune any SAF tree URI whose decoded document ID matches this folder
        val updatedUris = safScanUris.value.filterNot { uriStr ->
            uriStr.equals(folderPath, ignoreCase = true) ||
                runCatching {
                    val decoded = Uri.decode(uriStr).replace('\\', '/').trimEnd('/')
                    norm.isNotBlank() && decoded.endsWith(norm.substringAfterLast('/'), ignoreCase = true)
                }.getOrDefault(false)
        }.toSet()

        if (updatedPaths != customScanFolders.value || updatedUris != safScanUris.value) {
            customScanFolders.value = updatedPaths
            safScanUris.value = updatedUris
            prefs.edit()
                .putStringSet(PREF_CUSTOM_SCAN_FOLDERS, HashSet(updatedPaths))
                .putStringSet(PREF_SAF_SCAN_URIS, HashSet(updatedUris))
                .apply()
            loadMusic(forceRescan = true)
        }
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepTimerJob?.cancel()
            sleepTimerMinutes.value = minutes
            sleepTimerRemainingSec.value = 0L
            sleepTimerTargetEpochMs.value = 0L
            return
        }
        val totalSeconds = minutes * 60L
        setSleepTimerSeconds(
            totalSeconds = totalSeconds,
            targetEpochMs = System.currentTimeMillis() + totalSeconds * 1000L
        )
    }

    /**
     * Schedules playback to pause at the next occurrence of [hour]:[minute] (24-hour clock),
     * wrapping to tomorrow if that wall-clock time has already passed today.
     *
     * @return Total countdown duration in seconds.
     */
    fun setSleepTimerUntil(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val deltaSec = ((target.timeInMillis - now.timeInMillis + 999L) / 1000L).coerceAtLeast(1L)
        setSleepTimerSeconds(totalSeconds = deltaSec, targetEpochMs = target.timeInMillis)
        return deltaSec
    }

    private fun setSleepTimerSeconds(totalSeconds: Long, targetEpochMs: Long) {
        sleepTimerJob?.cancel()
        val safeSec = totalSeconds.coerceAtLeast(1L)
        sleepTimerMinutes.value = ((safeSec + 59L) / 60L).toInt().coerceAtLeast(1)
        sleepTimerRemainingSec.value = safeSec
        sleepTimerTargetEpochMs.value = targetEpochMs
        val deadlineElapsedMs = SystemClock.elapsedRealtime() + safeSec * 1000L
        sleepTimerJob = viewModelScope.launch {
            while (isActive) {
                val remainingMs = deadlineElapsedMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break
                val remainingSec = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                sleepTimerRemainingSec.value = remainingSec
                delay(minOf(1000L, remainingMs))
            }
            if (isActive) {
                mediaController?.pause()
                isPlaying.value = false
                stopProgressTracker()
                sleepTimerMinutes.value = 0
                sleepTimerRemainingSec.value = 0L
                sleepTimerTargetEpochMs.value = 0L
            }
        }
    }

    fun setAutoPlayOnHeadset(enabled: Boolean) {
        autoPlayOnHeadset.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_PLAY_HEADSET, enabled).apply()
    }

    fun setKeepScreenOnInPlayer(enabled: Boolean) {
        keepScreenOnInPlayer.value = enabled
        prefs.edit().putBoolean(PREF_KEEP_SCREEN_ON_PLAYER, enabled).apply()
    }

    fun setLyricsFontSize(size: String) {
        lyricsFontSize.value = size
        prefs.edit().putString(PREF_LYRICS_FONT_SIZE, size).apply()
    }

    fun setLyricsAlignment(alignment: String) {
        lyricsAlignment.value = alignment
        prefs.edit().putString(PREF_LYRICS_ALIGNMENT, alignment).apply()
    }

    fun setLyricsAutoScroll(enabled: Boolean) {
        lyricsAutoScroll.value = enabled
        prefs.edit().putBoolean(PREF_LYRICS_AUTO_SCROLL, enabled).apply()
    }

    fun setLyricsDelayNormalMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-2000, 2000)
        lyricsDelayNormalMs.value = clamped
        prefs.edit().putInt(PREF_LYRICS_DELAY_NORMAL_MS, clamped).apply()
    }

    fun setLyricsDelayBluetoothMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-2000, 2000)
        lyricsDelayBluetoothMs.value = clamped
        prefs.edit().putInt(PREF_LYRICS_DELAY_BLUETOOTH_MS, clamped).apply()
    }

    fun setLyricsDelayBitPerfectMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-2000, 2000)
        lyricsDelayBitPerfectMs.value = clamped
        prefs.edit().putInt(PREF_LYRICS_DELAY_BIT_PERFECT_MS, clamped).apply()
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun updateSortOption(option: SortOption) {
        sortOption.value = option
        prefs.edit().putString(PREF_SORT_OPTION, option.name).apply()
    }

    // ═══ Playlists & Device File Deletion ═══
    /**
     * Permanently deletes [song] from device storage, invoking [onNeedPermission] if scoped
     * storage requires explicit user confirmation.
     */
    fun deleteSongFromDevice(
        song: Song,
        onNeedPermission: (IntentSender) -> Unit,
        onCompleted: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val pendingSender = repository.deleteSongFromDevice(song)
                if (pendingSender != null) {
                    onNeedPermission(pendingSender)
                } else {
                    finalizeSongDeletion(song)
                    onCompleted(true)
                }
            } catch (e: Exception) {
                PDLog.e(PDLog.UI, "SONG_DELETE_ERR", "songId=${song.id} err=${e.message}", e)
                onCompleted(false)
            }
        }
    }

    /**
     * Removes [song] from the local database, in-memory library projections, active queue, and
     * playback history stats after storage deletion succeeds.
     */
    fun finalizeSongDeletion(song: Song) {
        lastSelfDeleteElapsedMs = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            repository.removeSongFromLocalDb(song.id)
            withContext(Dispatchers.IO) {
                AlbumArtManager.getInstance(getApplication()).invalidateAlbumArt(
                    albumId = song.albumId,
                    albumName = song.album,
                    albumArtist = song.albumArtist.ifBlank { song.artist }
                )
            }

            val updatedSongs = _allSongs.value.filterNot { it.id == song.id }
            withContext(Dispatchers.Default) {
                val albums = repository.extractAlbums(updatedSongs)
                val artists = repository.extractArtists(updatedSongs)
                val folders = repository.extractFolders(updatedSongs)
                val genres = repository.extractGenres(updatedSongs)
                val years = repository.extractYears(updatedSongs)
                AlbumArtManager.getInstance(getApplication()).indexLibrarySources(updatedSongs, albums)
                _allSongs.value = updatedSongs
                _albums.value = albums
                _artists.value = artists
                _folders.value = folders
                _genres.value = genres
                _yearGroups.value = years
            }

            originalQueue.value = originalQueue.value.filterNot { it.id == song.id }
            val indicesToRemove = currentQueue.value.indices.filter { currentQueue.value[it].id == song.id }
            for (idx in indicesToRemove.asReversed()) {
                removeFromQueue(idx)
            }

            refreshPlaylists()
            updatePlaybackStats()
        }
    }

    fun refreshPlaylists() {
        viewModelScope.launch {
            _playlists.value = repository.getAllPlaylists()
            favoriteSongIds.value = repository.getAllFavoriteSongIds()
            currentSong.value?.let { song ->
                isLiked.value = favoriteSongIds.value.contains(song.id)
            }
        }
    }

    fun toggleLike() {
        val song = currentSong.value ?: return
        viewModelScope.launch {
            val isNowFavorite = repository.toggleFavorite(song.id)
            isLiked.value = isNowFavorite
            favoriteSongIds.value = repository.getAllFavoriteSongIds()
            _playlists.value = repository.getAllPlaylists()
        }
    }

    fun toggleLikeSong(song: Song) {
        viewModelScope.launch {
            val isNowFavorite = repository.toggleFavorite(song.id)
            if (currentSong.value?.id == song.id) {
                isLiked.value = isNowFavorite
            }
            favoriteSongIds.value = repository.getAllFavoriteSongIds()
            _playlists.value = repository.getAllPlaylists()
        }
    }

    fun createPlaylist(name: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            if (newId > 0) {
                _playlists.value = repository.getAllPlaylists()
                onCreated?.invoke(newId)
            }
        }
    }

    fun createPlaylistWithSongs(name: String, songs: List<Song>, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            if (newId > 0) {
                val distinctIds = songs.map { it.id }.distinct()
                if (distinctIds.isNotEmpty()) {
                    repository.addSongsToPlaylist(newId, distinctIds)
                }
                _playlists.value = repository.getAllPlaylists()
                onCreated?.invoke(newId)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            if (repository.deletePlaylist(playlistId)) {
                _playlists.value = repository.getAllPlaylists()
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            if (repository.renamePlaylist(playlistId, newName)) {
                _playlists.value = repository.getAllPlaylists()
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            if (repository.addSongToPlaylist(playlistId, songId)) {
                _playlists.value = repository.getAllPlaylists()
                if (playlistId == MusicDbHelper.FAVORITES_PLAYLIST_ID) {
                    favoriteSongIds.value = repository.getAllFavoriteSongIds()
                    if (currentSong.value?.id == songId) {
                        isLiked.value = true
                    }
                }
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            if (repository.removeSongFromPlaylist(playlistId, songId)) {
                _playlists.value = repository.getAllPlaylists()
                if (playlistId == MusicDbHelper.FAVORITES_PLAYLIST_ID) {
                    favoriteSongIds.value = repository.getAllFavoriteSongIds()
                    if (currentSong.value?.id == songId) {
                        isLiked.value = false
                    }
                }
            }
        }
    }

    suspend fun getSongsForPlaylist(playlistId: Long): List<Song> {
        val ids = repository.getPlaylistSongIds(playlistId)
        val songMap = _allSongs.value.associateBy { it.id }
        return ids.mapNotNull { songMap[it] }
    }

    // ═══ USB DAC & DSP Preferences ═══
    fun setUsbDirectEnabled(enabled: Boolean) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setDirectEnabled(enabled)
    }

    fun requestUsbDacPermission() {
        UsbDacManager.init(getApplication())
        UsbDacManager.requestPermissionForCurrentDevice()
    }

    fun setUsbWarmUpMs(ms: Int) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setWarmUpMs(ms)
    }

    fun setUsbIdleTimeoutSec(sec: Int) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setIdleTimeoutSec(sec)
    }

    fun setUsbMaxVolumeSteps(steps: Int) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setMaxVolumeSteps(steps)
    }

    fun setUsbKeepAliveAudioEnabled(enabled: Boolean) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setKeepAliveAudioEnabled(enabled)
    }

    fun setUsbDsdMode(mode: Int) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setDsdMode(mode)
    }

    fun setUsbDsdGainCompensationDb(gainDb: Float) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setDsdGainCompensationDb(gainDb)
    }

    fun setUsbDitherEnabled(enabled: Boolean) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setDitherEnabled(enabled)
    }

    fun setUsbResamplerQuality(quality: Int) {
        UsbDacManager.init(getApplication())
        UsbDacManager.setResamplerQuality(quality)
    }

    fun refreshUsbDacTelemetry() {
        UsbDacManager.refreshStreamTelemetry()
    }

    // ═══ Backup & Restore ═══
    suspend fun buildBackupJsonString(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("createdAt", System.currentTimeMillis())

        val usbState = UsbDacManager.state.value
        val settingsObj = JSONObject().apply {
            put(PREF_THEME_MODE, themeMode.value)
            put(PREF_DYNAMIC_COLOR, dynamicColor.value)
            put(PREF_SONG_DYNAMIC_COLOR, songDynamicColor.value)
            put(PREF_AMOLED_MODE, amoledMode.value)
            put(PREF_NOW_PLAYING_STYLE, nowPlayingStyle.value.name)
            put(PREF_NOW_PLAYING_BLEND_STYLE, nowPlayingBlendStyle.value.name)
            put(PREF_NOW_PLAYING_SEEKBAR_STYLE, nowPlayingSeekbarStyle.value.name)
            put(PREF_SAVED_GRID_VIEW, isLibraryGridView.value)
            put(PREF_MIN_AUDIO_DURATION_SEC, minAudioDurationSec.value)
            val excludedArr = JSONArray()
            excludedFolders.value.forEach { excludedArr.put(it) }
            put(PREF_EXCLUDED_FOLDERS, excludedArr)
            val customScanArr = JSONArray()
            customScanFolders.value.forEach { customScanArr.put(it) }
            put(PREF_CUSTOM_SCAN_FOLDERS, customScanArr)
            put(PREF_AUTO_PLAY_HEADSET, autoPlayOnHeadset.value)
            put(PREF_KEEP_SCREEN_ON_PLAYER, keepScreenOnInPlayer.value)
            put(PREF_LYRICS_FONT_SIZE, lyricsFontSize.value)
            put(PREF_LYRICS_ALIGNMENT, lyricsAlignment.value)
            put(PREF_LYRICS_AUTO_SCROLL, lyricsAutoScroll.value)
            put(PREF_LYRICS_DELAY_NORMAL_MS, lyricsDelayNormalMs.value)
            put(PREF_LYRICS_DELAY_BLUETOOTH_MS, lyricsDelayBluetoothMs.value)
            put(PREF_LYRICS_DELAY_BIT_PERFECT_MS, lyricsDelayBitPerfectMs.value)
            put(PREF_SORT_OPTION, sortOption.value.name)
            put("usb_dac_direct_enabled", usbState.directEnabled)
            put("usb_dac_warmup_ms", usbState.warmUpMs)
            put("usb_dac_idle_timeout_sec", usbState.idleTimeoutSec)
            put("usb_dac_max_volume_steps", usbState.maxVolumeSteps)
            put("usb_dac_keep_alive_audio", usbState.keepAliveAudioEnabled)
            put("usb_dac_dsd_mode", usbState.dsdMode)
            put("usb_dac_dsd_gain_db", usbState.dsdGainCompensationDb.toDouble())
            put("usb_dac_dither_enabled", usbState.ditherEnabled)
            put("usb_dac_resampler_quality", usbState.resamplerQuality)
        }
        root.put("settings", settingsObj)
        root.put("libraryData", repository.exportUserDataJson())
        root.toString(2)
    }

    suspend fun restoreFromBackupJsonString(jsonString: String): Pair<Int, Int> {
        val root = JSONObject(jsonString)
        val settingsObj = root.optJSONObject("settings")
        if (settingsObj != null) {
            val appCtx = getApplication<Application>()
            if (settingsObj.has(PREF_THEME_MODE)) {
                setThemeMode(settingsObj.optString(PREF_THEME_MODE, DEFAULT_THEME_MODE))
            }
            if (settingsObj.has(PREF_DYNAMIC_COLOR)) {
                setDynamicColor(settingsObj.optBoolean(PREF_DYNAMIC_COLOR, true))
            }
            if (settingsObj.has(PREF_SONG_DYNAMIC_COLOR)) {
                setSongDynamicColor(settingsObj.optBoolean(PREF_SONG_DYNAMIC_COLOR, true))
            }
            if (settingsObj.has(PREF_AMOLED_MODE)) {
                setAmoledMode(settingsObj.optBoolean(PREF_AMOLED_MODE, false))
            }
            if (settingsObj.has(PREF_NOW_PLAYING_STYLE)) {
                setNowPlayingStyle(
                    NowPlayingStyle.fromStoredValue(
                        settingsObj.optString(PREF_NOW_PLAYING_STYLE, NowPlayingStyle.MODERN.name)
                    )
                )
            }
            if (settingsObj.has(PREF_NOW_PLAYING_BLEND_STYLE)) {
                setNowPlayingBlendStyle(
                    NowPlayingBlendStyle.fromStoredValue(
                        settingsObj.optString(PREF_NOW_PLAYING_BLEND_STYLE, NowPlayingBlendStyle.LINEAR.name)
                    )
                )
            }
            if (settingsObj.has(PREF_NOW_PLAYING_SEEKBAR_STYLE)) {
                setNowPlayingSeekbarStyle(
                    NowPlayingSeekbarStyle.fromStoredValue(
                        settingsObj.optString(PREF_NOW_PLAYING_SEEKBAR_STYLE, NowPlayingSeekbarStyle.SLIDER.name)
                    )
                )
            }
            if (settingsObj.has(PREF_SAVED_GRID_VIEW)) {
                setLibraryGridView(settingsObj.optBoolean(PREF_SAVED_GRID_VIEW, false))
            }
            if (settingsObj.has(PREF_MIN_AUDIO_DURATION_SEC)) {
                val restoredSec = settingsObj.optInt(PREF_MIN_AUDIO_DURATION_SEC, DEFAULT_MIN_DURATION_SEC)
                if (restoredSec != minAudioDurationSec.value) {
                    setMinAudioDurationSec(restoredSec)
                }
            }
            if (settingsObj.has(PREF_EXCLUDED_FOLDERS)) {
                val arr = settingsObj.optJSONArray(PREF_EXCLUDED_FOLDERS)
                if (arr != null) {
                    val set = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "").replace('\\', '/').trimEnd('/')
                        if (p.isNotBlank()) set.add(p)
                    }
                    if (set != excludedFolders.value) {
                        excludedFolders.value = set
                        prefs.edit().putStringSet(PREF_EXCLUDED_FOLDERS, HashSet(set)).apply()
                        loadMusic(forceRescan = true)
                    }
                }
            }
            if (settingsObj.has(PREF_CUSTOM_SCAN_FOLDERS)) {
                val arr = settingsObj.optJSONArray(PREF_CUSTOM_SCAN_FOLDERS)
                if (arr != null) {
                    val set = LinkedHashSet<String>()
                    for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "").replace('\\', '/').trimEnd('/')
                        if (p.isNotBlank()) set.add(p)
                    }
                    if (set != customScanFolders.value) {
                        customScanFolders.value = set
                        prefs.edit().putStringSet(PREF_CUSTOM_SCAN_FOLDERS, HashSet(set)).apply()
                        loadMusic(forceRescan = true)
                    }
                }
            }
            if (settingsObj.has(PREF_AUTO_PLAY_HEADSET)) {
                setAutoPlayOnHeadset(settingsObj.optBoolean(PREF_AUTO_PLAY_HEADSET, false))
            }
            if (settingsObj.has(PREF_KEEP_SCREEN_ON_PLAYER)) {
                setKeepScreenOnInPlayer(settingsObj.optBoolean(PREF_KEEP_SCREEN_ON_PLAYER, false))
            }
            if (settingsObj.has(PREF_LYRICS_FONT_SIZE)) {
                setLyricsFontSize(settingsObj.optString(PREF_LYRICS_FONT_SIZE, DEFAULT_LYRICS_FONT_SIZE))
            }
            if (settingsObj.has(PREF_LYRICS_ALIGNMENT)) {
                setLyricsAlignment(settingsObj.optString(PREF_LYRICS_ALIGNMENT, DEFAULT_LYRICS_ALIGNMENT))
            }
            if (settingsObj.has(PREF_LYRICS_AUTO_SCROLL)) {
                setLyricsAutoScroll(settingsObj.optBoolean(PREF_LYRICS_AUTO_SCROLL, true))
            }
            if (settingsObj.has(PREF_LYRICS_DELAY_NORMAL_MS)) {
                setLyricsDelayNormalMs(settingsObj.optInt(PREF_LYRICS_DELAY_NORMAL_MS, 0))
            }
            if (settingsObj.has(PREF_LYRICS_DELAY_BLUETOOTH_MS)) {
                setLyricsDelayBluetoothMs(settingsObj.optInt(PREF_LYRICS_DELAY_BLUETOOTH_MS, 0))
            }
            if (settingsObj.has(PREF_LYRICS_DELAY_BIT_PERFECT_MS)) {
                setLyricsDelayBitPerfectMs(settingsObj.optInt(PREF_LYRICS_DELAY_BIT_PERFECT_MS, 0))
            }
            if (settingsObj.has(PREF_SORT_OPTION)) {
                runCatching { SortOption.valueOf(settingsObj.optString(PREF_SORT_OPTION)) }
                    .getOrNull()
                    ?.let { updateSortOption(it) }
            }
            UsbDacManager.init(appCtx)
            if (settingsObj.has("usb_dac_direct_enabled")) {
                UsbDacManager.setDirectEnabled(settingsObj.optBoolean("usb_dac_direct_enabled", true))
            }
            if (settingsObj.has("usb_dac_warmup_ms")) {
                UsbDacManager.setWarmUpMs(settingsObj.optInt("usb_dac_warmup_ms", UsbDacManager.DEFAULT_WARMUP_MS))
            }
            if (settingsObj.has("usb_dac_idle_timeout_sec")) {
                UsbDacManager.setIdleTimeoutSec(
                    settingsObj.optInt("usb_dac_idle_timeout_sec", UsbDacManager.DEFAULT_IDLE_TIMEOUT_SEC)
                )
            }
            if (settingsObj.has("usb_dac_max_volume_steps")) {
                UsbDacManager.setMaxVolumeSteps(settingsObj.optInt("usb_dac_max_volume_steps", UsbDacManager.DEFAULT_VOLUME_STEPS))
            }
            if (settingsObj.has("usb_dac_keep_alive_audio")) {
                UsbDacManager.setKeepAliveAudioEnabled(settingsObj.optBoolean("usb_dac_keep_alive_audio", false))
            }
            if (settingsObj.has("usb_dac_dsd_mode")) {
                UsbDacManager.setDsdMode(settingsObj.optInt("usb_dac_dsd_mode", UsbDacNative.DSD_MODE_AUTO))
            }
            if (settingsObj.has("usb_dac_dsd_gain_db")) {
                UsbDacManager.setDsdGainCompensationDb(settingsObj.optDouble("usb_dac_dsd_gain_db", 6.0).toFloat())
            }
            if (settingsObj.has("usb_dac_dither_enabled")) {
                UsbDacManager.setDitherEnabled(settingsObj.optBoolean("usb_dac_dither_enabled", true))
            }
            if (settingsObj.has("usb_dac_resampler_quality")) {
                UsbDacManager.setResamplerQuality(
                    settingsObj.optInt("usb_dac_resampler_quality", UsbDacNative.RESAMPLER_QUALITY_HIGH)
                )
            }
        }

        val libraryObj = root.optJSONObject("libraryData") ?: root
        val counts = repository.importUserDataJson(libraryObj)
        refreshPlaylists()
        updatePlaybackStats()
        return counts
    }

    // ═══ MediaItem Conversion & Progress Tracking ═══
    private fun Song.toMediaItem(): MediaItem {
        val baseArtUri = albumArtUri ?: contentUri
        val effectiveArtUri = if (baseArtUri.scheme == ContentResolver.SCHEME_CONTENT) {
            baseArtUri.buildUpon()
                .appendQueryParameter(QUERY_GRAD_START, albumGradientColorStart.toString())
                .appendQueryParameter(QUERY_GRAD_END, albumGradientColorEnd.toString())
                .build()
        } else {
            baseArtUri
        }
        val metadataExtras = Bundle().apply {
            putLong(EXTRA_GRAD_START, albumGradientColorStart)
            putLong(EXTRA_GRAD_END, albumGradientColorEnd)
            putLong(EXTRA_SONG_ID, id)
            putLong(EXTRA_ALBUM_ID, albumId)
            putString(EXTRA_ALBUM_ARTIST, albumArtist.ifBlank { artist })
            putString(EXTRA_SONG_PATH, path)
            putString(EXTRA_AUDIO_SPECS, AudioSpecsHelper.getFastFallbackSpecs(this@toMediaItem))
            if (bitDepth > 0) putInt(EXTRA_SOURCE_BIT_DEPTH, bitDepth)
            if (codec.isNotBlank()) putString(EXTRA_SOURCE_CODEC, codec)
        }

        val uniqueId = if (instanceId > 0L) "$id#$instanceId" else id.toString()
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(effectiveArtUri)
            .setExtras(metadataExtras)
        if (duration > 0L) {
            metadataBuilder.setDurationMs(duration)
        }
        return MediaItem.Builder()
            .setMediaId(uniqueId)
            .setUri(contentUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun setUiVisible(visible: Boolean) {
        if (isUiVisible == visible) return
        isUiVisible = visible
        if (visible) {
            mediaController?.let { controller ->
                val pos = resolveAccuratePositionMs(controller)
                val dur = controller.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                    ?: currentDuration.value.coerceAtLeast(1L)
                currentPosition.value = pos.coerceIn(0L, dur)
                currentDuration.value = dur
                playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                if (controller.isPlaying) {
                    startProgressTracker()
                }
            }
        } else {
            mediaController?.let { controller ->
                if (controller.isPlaying) {
                    prefs.edit().putLong(PREF_LAST_POSITION_MS, resolveAccuratePositionMs(controller)).apply()
                }
            }
            stopProgressTracker()
        }
    }

    private fun startProgressTracker() {
        if (!isUiVisible) return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            var tickCount = 0
            while (isActive) {
                var nextDelayMs = PROGRESS_POLL_INTERVAL_MS
                mediaController?.let { controller ->
                    if (controller.isPlaying) {
                        val pendingKey = pendingTrackMediaId
                        if (pendingKey != null) {
                            val activeMediaId = controller.currentMediaItem?.mediaId
                            val pendingSongId = pendingKey.substringBefore('#').toLongOrNull()
                            val activeSongId = activeMediaId?.substringBefore('#')?.toLongOrNull()
                            val matchesTarget = (activeMediaId == pendingKey) ||
                                (pendingSongId != null && activeSongId == pendingSongId)
                            val posNow = resolveAccuratePositionMs(controller)
                            val beforeDeadline = SystemClock.elapsedRealtime() < pendingResetDeadlineMs
                            // Hold progress at 0 while MediaController finishes seeking to the newly selected track
                            if (!matchesTarget || (beforeDeadline && posNow > STALE_POSITION_THRESHOLD_MS)) {
                                nextDelayMs = PROGRESS_PENDING_POLL_INTERVAL_MS
                                return@let
                            }
                            pendingTrackMediaId = null
                            pendingResetDeadlineMs = 0L
                        }
                        if (SystemClock.elapsedRealtime() < seekHoldUntilElapsedMs) {
                            // Hold optimistic seek position briefly while MediaController IPC and sink flush settle
                            nextDelayMs = PROGRESS_PENDING_POLL_INTERVAL_MS
                            return@let
                        }
                        val pos = resolveAccuratePositionMs(controller)
                        val dur = controller.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                            ?: currentDuration.value.coerceAtLeast(1L)
                        val clampedPos = pos.coerceIn(0L, dur)
                        val progress = (clampedPos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        if (currentPosition.value != clampedPos) currentPosition.value = clampedPos
                        if (currentDuration.value != dur) currentDuration.value = dur
                        if (playbackProgress.value != progress) playbackProgress.value = progress
                        if (UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()) {
                            // Poll faster during DAC warm-up so UI/lyrics lock onto playout start promptly
                            nextDelayMs = PROGRESS_PENDING_POLL_INTERVAL_MS
                        }
                        tickCount++
                        if (tickCount >= POSITION_PERSIST_INTERVAL_TICKS) {
                            tickCount = 0
                            prefs.edit().putLong(PREF_LAST_POSITION_MS, pos).apply()
                        }
                    }
                }
                delay(nextDelayMs)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
    }

    // ═══ Lifecycle Cleanup ═══
    override fun onCleared() {
        PlaybackService.onNotificationShuffleToggle = null
        libraryObserverJob?.cancel()
        libraryScanJob?.cancel()
        queuePrepareJob?.cancel()
        stopProgressTracker()
        mediaController?.let { controller ->
            prefs.edit().putLong(PREF_LAST_POSITION_MS, resolveAccuratePositionMs(controller)).apply()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
