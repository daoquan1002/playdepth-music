/**
 * PlaybackService — Media3 foreground playback service with bit-perfect USB DAC and HAL routing.
 *
 * @remarks
 * Owns the single [ExoPlayer] instance and wraps it in [UsbDacSessionPlayer] so hardware volume
 * keys outside the app step through the USB DAC's discrete UAC hardware steps rather than Android's
 * software mixer. Also configures Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` and offload preferences
 * to bypass system resampling and external DSP sessions whenever output hardware permits.
 *
 * @see UsbDacAudioSink
 * @see UsbDacManager
 * @see MainActivity
 */
package com.quandot.musicplayer.service

// External SDK / Jetpack / third-party libraries
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Internal modules
import com.quandot.musicplayer.MainActivity
import com.quandot.musicplayer.R
import com.quandot.musicplayer.audio.SilentAudioKeepAlive
import com.quandot.musicplayer.audio.SourceFormatTrackingAudioRenderer
import com.quandot.musicplayer.audio.UsbDacAudioSink
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.audio.UsbDacNative
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.audio.WavFloatFixExtractorsFactory
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.data.AlbumArtRequest
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.data.ExoAudioFormatSnapshot
import com.quandot.musicplayer.data.computeAlbumArtKey
import com.quandot.musicplayer.data.computeTrackArtKey
import com.quandot.musicplayer.data.isStandaloneTrackAlbum
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog
import com.quandot.musicplayer.debug.PlaybackDiagnosticsSnapshot
import com.quandot.musicplayer.util.LocaleUtils

// ═══ Constants ═══
private const val PREFS_NAME = "music_playback_prefs"
private const val PREF_IS_SHUFFLE = "is_shuffle"
private const val PREF_REPEAT_MODE = "repeat_mode"
private const val PREF_AUTO_PLAY_HEADSET = "auto_play_headset"

private const val BACK_BUFFER_DURATION_MS = 3_000
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 35_000
private const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024 // 48 MB read-ahead prevents storage I/O stalls on 384 kHz PCM & DSD256/DSD512
private const val BUFFER_FOR_PLAYBACK_MS = 500
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000
private const val HAL_MIN_PCM_BUFFER_DURATION_US = 500_000
private const val HAL_MAX_PCM_BUFFER_DURATION_US = 1_000_000
private const val HAL_PCM_BUFFER_MULTIPLICATION_FACTOR = 4
private const val HAL_MAX_PCM_BUFFER_BYTES = 960 * 1024 // Cap under 1 MB AudioFlinger heap ceiling at 352.8-384 kHz

private const val STARTUP_PLUG_SUPPRESSION_WINDOW_MS = 2_500L
private const val MAIN_LOOPER_SNAPSHOT_TIMEOUT_MS = 500L
private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
private const val WAKE_LOCK_TAG = "PlayDepth:UsbDacWakeLock"

private const val DEFAULT_GRADIENT_START = 0xFF8A2387L
private const val DEFAULT_GRADIENT_END = 0xFFE94057L
private const val NOTIFICATION_ARTWORK_SIZE_PX = 512
private const val NOTIFICATION_ICON_RATIO = 0.44f
private const val NOTIFICATION_ICON_ALPHA = 217
private const val NOTIFICATION_BITMAP_CACHE_MAX_ENTRIES = 16
private const val GRADIENT_BITMAP_CACHE_MAX_ENTRIES = 8
private const val SYNTHETIC_ID_FLAG = 0x40000000_00000000L
private const val ALBUM_ART_BASE_URI = "content://media/external/audio/albumart"
private const val DEFAULT_PRE_MUTE_VOLUME_STEP = 50

private const val EXTRA_AUDIO_SPECS = "audio_specs"
private const val EXTRA_GRAD_START = "grad_start"
private const val EXTRA_GRAD_END = "grad_end"
private const val EXTRA_SONG_ID = "song_id"
private const val EXTRA_ALBUM_ID = "album_id"
private const val EXTRA_ALBUM_ARTIST = "album_artist"
private const val EXTRA_SONG_PATH = "song_path"
private const val EXTRA_SOURCE_BIT_DEPTH = "source_bit_depth"
private const val EXTRA_SOURCE_CODEC = "source_codec"
private const val MAX_DIRECT_FILE_URI_MAP_ENTRIES = 1024
private const val QUERY_GRAD_START = "gradStart"
private const val QUERY_GRAD_END = "gradEnd"

// ═══ Playback Service ═══
class PlaybackService : MediaSessionService() {

    // ═══ Companion & Service Fields ═══
    private var player: ExoPlayer? = null
    private var sessionPlayer: UsbDacSessionPlayer? = null
    private var mediaSession: MediaSession? = null
    private var usbDacAudioSink: UsbDacAudioSink? = null
    private var silentAudioKeepAlive: SilentAudioKeepAlive? = null
    private var usbPermissionGrantedListener: (() -> Unit)? = null

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var serviceStartElapsedMs = 0L
    private var lastDetachedDspSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var lastResolvedSourceMediaId: String? = null
    private var currentSourceResolveJob: Job? = null
    private var nextSourcePrefetchJob: Job? = null
    private var notificationArtPrefetchJob: Job? = null
    private val directFileUriMap = ConcurrentHashMap<Uri, String>()
    private val notificationBitmapCache = LruCache<String, Bitmap>(NOTIFICATION_BITMAP_CACHE_MAX_ENTRIES)
    private val gradientBitmapCache = LruCache<String, Bitmap>(GRADIENT_BITMAP_CACHE_MAX_ENTRIES)
    private val inFlightNotificationArt = ConcurrentHashMap<String, ListenableFuture<Bitmap>>()
    private val artCacheVersionLock = Any()
    @Volatile
    private var observedArtCacheVersion: Long = -1L
    private var serviceOriginalMediaItems: List<MediaItem> = emptyList()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    companion object {
        const val ACTION_SHUFFLE = "com.quandot.playdepth.music.ACTION_SHUFFLE"
        const val ACTION_REPEAT = "com.quandot.playdepth.music.ACTION_REPEAT"
        const val ACTION_OPEN_NOW_PLAYING = "com.quandot.playdepth.music.ACTION_OPEN_NOW_PLAYING"
        const val EXTRA_OPEN_NOW_PLAYING = "com.quandot.playdepth.music.EXTRA_OPEN_NOW_PLAYING"
        private const val REQUEST_CODE_OPEN_NOW_PLAYING = 1001

        private val _currentAudioFormat = MutableStateFlow<ExoAudioFormatSnapshot?>(null)
        val currentAudioFormat: StateFlow<ExoAudioFormatSnapshot?> = _currentAudioFormat.asStateFlow()

        private val _physicalShuffleEnabled = MutableStateFlow(false)
        val isPhysicalShuffleEnabled: StateFlow<Boolean> = _physicalShuffleEnabled.asStateFlow()

        private val _isBluetoothOutputActive = MutableStateFlow(false)
        val isBluetoothOutputActive: StateFlow<Boolean> = _isBluetoothOutputActive.asStateFlow()

        @Volatile
        var onNotificationShuffleToggle: (() -> Unit)? = null

        @Volatile
        private var activeInstance: PlaybackService? = null

        /**
         * Returns the live hardware-synchronized playback position on the Main Thread directly from
         * [ExoPlayer] (whose `playbackInfo.positionUs` is updated every 10 ms from
         * [UsbDacAudioSink.getCurrentPositionUs] without `MediaController` wall-clock extrapolation),
         * keeping UI seekbars and synced lyrics locked during DAC warm-up and end-of-track drains.
         */
        fun getLivePlayerPositionMs(): Long? {
            if (Looper.myLooper() != Looper.getMainLooper()) return null
            val p = activeInstance?.player ?: return null
            if (p.playbackState == Player.STATE_IDLE) return null
            return p.currentPosition.coerceAtLeast(0L)
        }

        /**
         * Synchronizes physical queue-shuffle state between [MusicViewModel] and the active
         * MediaSession notification while keeping ExoPlayer's internal `shuffleModeEnabled` false.
         */
        fun setPhysicalShuffleState(
            enabled: Boolean,
            originalMediaItems: List<MediaItem>? = null
        ) {
            _physicalShuffleEnabled.value = enabled
            activeInstance?.let { svc ->
                Handler(Looper.getMainLooper()).post {
                    if (originalMediaItems != null) {
                        svc.serviceOriginalMediaItems = originalMediaItems
                    } else if (!enabled) {
                        svc.serviceOriginalMediaItems = emptyList()
                    }
                    svc.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_IS_SHUFFLE, enabled)
                        .apply()
                    svc.updateCustomLayout()
                }
            }
        }
    }

    // ═══ Service Lifecycle ═══
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                PDLog.w(PDLog.PLAYBACK, "FGS_START_BLOCKED", "action=pause")
                player?.pause()
                releaseCpuWakeLock()
                updateSilentKeepAlive()
            }
        })

        // Monochrome/transparent icon prevents white-square clipping in system notifications on Android 11 and older
        val notificationProvider = DefaultMediaNotificationProvider(this)
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val initialShuffle = prefs.getBoolean(PREF_IS_SHUFFLE, false)
        val initialRepeat = prefs.getInt(PREF_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        activeInstance = this
        _physicalShuffleEnabled.value = initialShuffle

        serviceStartElapsedMs = SystemClock.elapsedRealtime()
        AppLogManager.init(this)
        val ffmpegReady = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        PDLog.i(PDLog.PLAYBACK, "SVC_CREATE", "shuffle=$initialShuffle repeat=$initialRepeat ffmpeg=$ffmpegReady")
        UsbDacManager.init(this)
        silentAudioKeepAlive = SilentAudioKeepAlive(this)
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        registerBitPerfectAudioDeviceObserver()
        val permListener = {
            handleUsbPermissionGrantedForBitPerfect()
        }
        usbPermissionGrantedListener = permListener
        UsbDacManager.addPermissionGrantedListener(permListener)

        player = createExoPlayer(initialRepeat, prefs)

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOW_PLAYING
            putExtra(EXTRA_OPEN_NOW_PLAYING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_NOW_PLAYING,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wrappedSessionPlayer = UsbDacSessionPlayer(wrapped = player!!)
        sessionPlayer = wrappedSessionPlayer

        mediaSession = MediaSession.Builder(this, wrappedSessionPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(createMediaSessionCallback(prefs))
            .setBitmapLoader(createNotificationBitmapLoader())
            .setCustomLayout(buildCustomLayout())
            .build()

        observeUsbDacVolumeChanges(wrappedSessionPlayer)
        serviceScope.launch(Dispatchers.Main) {
            UsbDacManager.state.collect {
                updateSilentKeepAlive()
                updateCpuWakeLock()
            }
        }
        registerDiagnosticsSnapshotProvider()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        PDLog.i(PDLog.PLAYBACK, "SVC_TASK_REMOVED", "playWhenReady=${p?.playWhenReady} items=${p?.mediaItemCount}")
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post { updateCustomLayout() }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onDestroy() {
        PDLog.i(PDLog.PLAYBACK, "SVC_DESTROY")
        clearListener()
        AppLogManager.serviceSnapshotProvider = null
        usbPermissionGrantedListener?.let { UsbDacManager.removePermissionGrantedListener(it) }
        usbPermissionGrantedListener = null
        usbDacAudioSink = null
        silentAudioKeepAlive?.release()
        silentAudioKeepAlive = null
        releaseCpuWakeLock()
        notificationArtPrefetchJob?.cancel()
        notificationArtPrefetchJob = null
        inFlightNotificationArt.clear()
        notificationBitmapCache.evictAll()
        gradientBitmapCache.evictAll()
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
            audioDeviceCallback = null
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        sessionPlayer = null
        player = null
        lastDetachedDspSessionId = C.AUDIO_SESSION_ID_UNSET
        _currentAudioFormat.value = null
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    // ═══ ExoPlayer Configuration & Listeners ═══
    private fun createExoPlayer(
        initialRepeat: Int,
        prefs: SharedPreferences
    ): ExoPlayer {
        // Always route through UsbDacAudioSink: writes raw PCM directly to the native UAC1/UAC2 driver when Direct
        // is active, and falls back to 32-bit float DefaultAudioSink with Audio HAL Direct/Offload support.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val baseBufferProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(HAL_MIN_PCM_BUFFER_DURATION_US)
                    .setMaxPcmBufferDurationUs(HAL_MAX_PCM_BUFFER_DURATION_US)
                    .setPcmBufferMultiplicationFactor(HAL_PCM_BUFFER_MULTIPLICATION_FACTOR)
                    .build()
                val boundedBufferProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
                        minBufferSizeInBytes,
                        encoding,
                        outputMode,
                        pcmFrameSize,
                        sampleRate,
                        bitrate,
                        maxAudioTrackPlaybackSpeed ->
                    val target = baseBufferProvider.getBufferSizeInBytes(
                        minBufferSizeInBytes,
                        encoding,
                        outputMode,
                        pcmFrameSize,
                        sampleRate,
                        bitrate,
                        maxAudioTrackPlaybackSpeed
                    )
                    if (outputMode == DefaultAudioSink.OUTPUT_MODE_PCM && pcmFrameSize > 0) {
                        val cap = maxOf(minBufferSizeInBytes * 2, HAL_MAX_PCM_BUFFER_BYTES)
                        val clamped = minOf(target, cap).coerceAtLeast(minBufferSizeInBytes)
                        val aligned = clamped - (clamped % pcmFrameSize)
                        if (aligned >= minBufferSizeInBytes) aligned else minBufferSizeInBytes
                    } else {
                        target
                    }
                }
                val defaultSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setAudioTrackBufferSizeProvider(boundedBufferProvider)
                    .setAudioOffloadSupportProvider(DefaultAudioOffloadSupportProvider(context))
                    .build()
                return UsbDacAudioSink(defaultSink).also { sink ->
                    sink.onWarmUpCompleted = {
                        sessionPlayer?.notifyPositionClockSynced()
                    }
                    sink.onUsbDacUnpluggedDuringPlayback = {
                        Handler(Looper.getMainLooper()).post {
                            val p = player
                            if (p != null && (p.isPlaying || p.playWhenReady)) {
                                PDLog.w(PDLog.USB_DAC, "USB_UNPLUG_PAUSE", "hook=early action=pause")
                                p.pause()
                            }
                        }
                    }
                    usbDacAudioSink = sink
                }
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                val startIdx = out.size
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
                val usbSink = audioSink as? UsbDacAudioSink ?: return
                for (i in startIdx until out.size) {
                    out[i] = SourceFormatTrackingAudioRenderer(out[i]) { inputFormat ->
                        usbSink.onDecoderInputFormat(inputFormat)
                    }
                }
            }
        }.apply {
            setEnableAudioFloatOutput(true)
            setEnableAudioTrackPlaybackParams(false)
            // Prefer FFmpeg SW decoder for bit-exact Hi-Res integer/float PCM (avoids vendor Codec2
            // decoders like c2.vivo.flac.decoder corrupting 24-bit 96kHz+ PCM_FLOAT output), falling
            // back to MediaCodecAudioRenderer when a codec is unsupported by FFmpeg.
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // Block third-party audio capture to keep the playback stream unaltered
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(BACK_BUFFER_DURATION_MS, true)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            // Enforce the 48 MB ceiling on DSD256/DSD512 so DefaultAllocator never balloons to
            // 100-200 MB of Java heap and triggers 200+ ms GC stalls, while still keeping 15-35 s
            // read-ahead on PCM/DSD64/DSD128 and 8.5-17 s on DSD256/DSD512.
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS, // Start playback after 500ms buffer for responsive track switching
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val dataSourceFactory = DirectFileFirstDataSourceFactory(this, directFileUriMap)

        // Delegate Android AudioFocus handling to ExoPlayer so transient interruptions pause/resume cleanly and
        // permanent loss stops playback; UsbDacAudioSink ignores ducking in USB Direct mode to preserve 0 dB PCM.
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, WavFloatFixExtractorsFactory()))
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build().apply {
                // Keep ExoPlayer's internal shuffle off; PlayDepth reorders queue items physically
                // around the active track so queue UI, gapless next, and notifications stay in sync.
                shuffleModeEnabled = false
                repeatMode = initialRepeat
                playbackParameters = PlaybackParameters.DEFAULT
                volume = 1.0f

                applyOffloadAndDirectSettings()
                configureBitPerfectMixerAttributes()

                addListener(createPlayerListener(prefs))
                addAnalyticsListener(createAnalyticsListener())
            }
    }

    private fun createPlayerListener(prefs: SharedPreferences): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING && UsbDacManager.isDirectReady()) {
                    val p = player
                    val bufferedAheadMs = if (p != null) (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L) else 0L
                    val ringAvail = runCatching { UsbDacNative.nativeGetRingBufferAvailableBytes() }.getOrDefault(0)
                    val underruns = runCatching { UsbDacNative.nativeGetUnderrunCount() }.getOrDefault(0L)
                    PDLog.d(PDLog.PLAYBACK, "STATE_BUFFERING") {
                        "bufAheadMs=$bufferedAheadMs ringAvailB=$ringAvail underruns=$underruns"
                    }
                } else {
                    PDLog.d(PDLog.PLAYBACK, "PLAYBACK_STATE") { "state=${playbackStateName(playbackState)}" }
                }
                if (playbackState == Player.STATE_IDLE) {
                    UsbDacManager.expireHotStandbyNow("player_idle")
                }
                updateSilentKeepAlive()
                updateCpuWakeLock()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PDLog.d(PDLog.PLAYBACK, "PLAY_WHEN_READY") { "playWhenReady=$playWhenReady reason=$reason" }
                updateSilentKeepAlive()
                if (playWhenReady) {
                    onPlaybackActivated()
                } else {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        // Permanent audio focus loss: release USB DAC Hot-Standby & WakeLock immediately.
                        PDLog.i(PDLog.PLAYBACK, "AUDIO_FOCUS_LOSS", "permanent=true action=expire_standby")
                        UsbDacManager.expireHotStandbyNow("permanent_audio_focus_loss")
                    }
                    updateCpuWakeLock()
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    PDLog.w(PDLog.PLAYBACK, "PLAYBACK_SUPPRESSED", "reason=$playbackSuppressionReason")
                } else {
                    PDLog.d(PDLog.PLAYBACK, "PLAYBACK_SUPPRESSION_CLEARED")
                }
                updateSilentKeepAlive()
                updateCpuWakeLock()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PDLog.d(PDLog.PLAYBACK, "IS_PLAYING") { "playing=$isPlaying" }
                updateSilentKeepAlive()
                if (isPlaying) {
                    onPlaybackActivated()
                } else {
                    updateCpuWakeLock()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                PDLog.d(PDLog.PLAYBACK, "TRACK_TRANSITION") { "mediaId=${mediaItem?.mediaId ?: "none"} reason=$reason" }
                _currentAudioFormat.value = null
                updateUsbSinkSourceFormat(mediaItem)
                preloadNotificationArtworkNeighbors()
                configureBitPerfectMixerAttributes()
                detachExternalDsp()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val trackAudioFmt = extractSelectedAudioFormat(tracks)
                PDLog.d(PDLog.PLAYBACK, "TRACKS_CHANGED") {
                    "track=${formatSummary(trackAudioFmt)} exo=${formatSummary(player?.audioFormat)}"
                }
                if (trackAudioFmt != null) {
                    publishExoAudioFormat(trackAudioFmt, player?.currentMediaItem)
                }
                configureBitPerfectMixerAttributes()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                PDLog.d(PDLog.PLAYBACK, "AUDIO_SESSION") { "sessionId=$audioSessionId" }
                detachExternalDsp()
            }

            override fun onPlayerError(error: PlaybackException) {
                val summary = "${error.errorCodeName} (${error.errorCode}): ${error.message}"
                AppLogManager.updateLastPlayerError(summary)
                PDLog.e(PDLog.PLAYBACK, "PLAYER_ERR", "code=${error.errorCodeName}(${error.errorCode}) msg=${error.message}", error)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) {
                    // External controllers (e.g. Bluetooth/Auto) toggled Player.shuffleModeEnabled;
                    // route through physical in-place queue shuffle instead of ExoPlayer's ShuffleOrder.
                    player?.shuffleModeEnabled = false
                    val handler = onNotificationShuffleToggle
                    if (handler != null) {
                        handler.invoke()
                    } else {
                        player?.let { togglePhysicalShuffleInService(it, prefs) }
                    }
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                indexActiveQueueDirectFilePaths()
                prefetchNextUsbSinkSourceFormat()
                preloadNotificationArtworkNeighbors()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                prefs.edit().putInt(PREF_REPEAT_MODE, repeatMode).apply()
                PDLog.d(PDLog.PLAYBACK, "REPEAT_MODE") { "mode=$repeatMode" }
                updateCustomLayout()
                prefetchNextUsbSinkSourceFormat()
                preloadNotificationArtworkNeighbors()
            }
        }
    }

    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                PDLog.d(PDLog.PLAYBACK, "DECODER_INIT") { "name=$decoderName initMs=$initializationDurationMs" }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                PDLog.d(PDLog.PLAYBACK, "AUDIO_IN_FMT") { formatSummary(format) }
                val p = player
                if (p != null && eventTime.windowIndex == p.currentMediaItemIndex) {
                    val curItem = p.currentMediaItem
                    if (curItem != null && lastResolvedSourceMediaId != curItem.mediaId) {
                        updateUsbSinkSourceFormat(curItem, format.sampleMimeType)
                    }
                    publishExoAudioFormat(format, curItem)
                }
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                val cfg = "rate=${audioTrackConfig.sampleRate}Hz enc=${audioTrackConfig.encoding} " +
                    "chCfg=0x${audioTrackConfig.channelConfig.toString(16)} " +
                    "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling} " +
                    "bufSize=${audioTrackConfig.bufferSize}"
                AppLogManager.updateAudioTrackInfo(cfg)
                PDLog.i(PDLog.PLAYBACK, "AUDIOTRACK_INIT", cfg)
            }

            override fun onAudioTrackReleased(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                PDLog.d(PDLog.PLAYBACK, "AUDIOTRACK_RELEASE") {
                    "rate=${audioTrackConfig.sampleRate} offload=${audioTrackConfig.offload}"
                }
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                PDLog.w(
                    PDLog.PLAYBACK,
                    "AUDIOTRACK_UNDERRUN",
                    "bufB=$bufferSize bufMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs"
                )
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                AppLogManager.updateLastPlayerError("AudioSinkError: ${audioSinkError.message}")
                PDLog.e(PDLog.PLAYBACK, "AUDIOSINK_ERR", "msg=${audioSinkError.message}", audioSinkError)
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                AppLogManager.updateLastPlayerError("AudioCodecError: ${audioCodecError.message}")
                PDLog.e(PDLog.PLAYBACK, "AUDIOCODEC_ERR", "msg=${audioCodecError.message}", audioCodecError)
            }
        }
    }

    /**
     * Enables Audio HAL Offload preferences without requiring gapless or speed-change support so
     * capable output hardware can engage direct/offload playback paths.
     */
    private fun applyOffloadAndDirectSettings() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build()
            )
            .build()
    }

    // ═══ MediaSession & Notification Artwork ═══
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createMediaSessionCallback(prefs: SharedPreferences): MediaSession.Callback {
        return object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val isOwnApp = controller.packageName == packageName
                val isNotificationController = session.isMediaNotificationController(controller)
                val sessionCommands = if (isOwnApp || isNotificationController) {
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
                        .build()
                } else {
                    SessionCommands.EMPTY.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
                        .build()
                }
                val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setCustomLayout(buildCustomLayout())
                if (!isOwnApp) {
                    val restrictedPlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .build()
                    builder.setAvailablePlayerCommands(restrictedPlayerCommands)
                }
                return builder.build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val p = player ?: return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_SESSION_DISCONNECTED)
                )
                when (customCommand.customAction) {
                    ACTION_SHUFFLE -> {
                        val handler = onNotificationShuffleToggle
                        if (handler != null) {
                            handler.invoke()
                        } else {
                            togglePhysicalShuffleInService(p, prefs)
                        }
                    }
                    ACTION_REPEAT -> {
                        val nextRepeat = when (p.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        p.repeatMode = nextRepeat
                        prefs.edit().putInt(PREF_REPEAT_MODE, nextRepeat).apply()
                        updateCustomLayout()
                    }
                    else -> return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_UNKNOWN)
                    )
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildCustomLayout(): List<CommandButton> {
        val p = player ?: return emptyList()
        val locContext = LocaleUtils.getLocalizedContext(this)
        val isShuffled = _physicalShuffleEnabled.value
        val shuffleIcon = if (isShuffled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle
        val shuffleSemanticIcon = if (isShuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        val shuffleTitle = locContext.getString(
            if (isShuffled) R.string.service_shuffle_on else R.string.service_shuffle_off
        )

        val (repeatIcon, repeatSemanticIcon, repeatTitleRes) = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> Triple(R.drawable.ic_repeat_one, CommandButton.ICON_REPEAT_ONE, R.string.service_repeat_one)
            Player.REPEAT_MODE_ALL -> Triple(R.drawable.ic_repeat_all, CommandButton.ICON_REPEAT_ALL, R.string.service_repeat_all)
            else -> Triple(R.drawable.ic_repeat, CommandButton.ICON_REPEAT_OFF, R.string.service_repeat_off)
        }
        val repeatTitle = locContext.getString(repeatTitleRes)

        val shuffleButton = CommandButton.Builder(shuffleSemanticIcon)
            .setDisplayName(shuffleTitle)
            .setCustomIconResId(shuffleIcon)
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .build()

        val repeatButton = CommandButton.Builder(repeatSemanticIcon)
            .setDisplayName(repeatTitle)
            .setCustomIconResId(repeatIcon)
            .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
            .build()

        return listOf(shuffleButton, repeatButton)
    }

    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        session.setCustomLayout(buildCustomLayout())
    }

    /**
     * Fallback physical in-place queue shuffle used when the user taps Shuffle in the notification
     * after the Activity / [MusicViewModel] has been destroyed. Mutates items strictly around the
     * active [MediaItem] so playback and USB DAC streaming are never interrupted.
     */
    private fun togglePhysicalShuffleInService(p: ExoPlayer, prefs: SharedPreferences) {
        val newShuffle = !_physicalShuffleEnabled.value
        val count = p.mediaItemCount
        if (count > 1) {
            val curIdx = p.currentMediaItemIndex.coerceIn(0, count - 1)
            val liveItems = (0 until count).map { p.getMediaItemAt(it) }
            val activeItem = liveItems[curIdx]
            p.shuffleModeEnabled = false

            if (newShuffle) {
                serviceOriginalMediaItems = liveItems
                val shuffledOthers = liveItems.filterIndexed { index, _ -> index != curIdx }.shuffled()
                if (curIdx > 0) {
                    p.moveMediaItem(curIdx, 0)
                }
                if (p.mediaItemCount > 1) {
                    p.replaceMediaItems(1, p.mediaItemCount, shuffledOthers)
                } else if (shuffledOthers.isNotEmpty()) {
                    p.addMediaItems(1, shuffledOthers)
                }
            } else if (serviceOriginalMediaItems.isNotEmpty()) {
                val origIdx = serviceOriginalMediaItems.indexOfFirst { it.mediaId == activeItem.mediaId }
                    .takeIf { it >= 0 }
                    ?: serviceOriginalMediaItems.indexOfFirst {
                        it.mediaId.substringBefore('#') == activeItem.mediaId.substringBefore('#')
                    }
                if (origIdx >= 0) {
                    val before = serviceOriginalMediaItems.subList(0, origIdx)
                    val after = serviceOriginalMediaItems.subList(origIdx + 1, serviceOriginalMediaItems.size)
                    if (curIdx + 1 < p.mediaItemCount) {
                        if (after.isEmpty()) {
                            p.removeMediaItems(curIdx + 1, p.mediaItemCount)
                        } else {
                            p.replaceMediaItems(curIdx + 1, p.mediaItemCount, after)
                        }
                    } else if (after.isNotEmpty()) {
                        p.addMediaItems(curIdx + 1, after)
                    }
                    if (curIdx > 0) {
                        if (before.isEmpty()) {
                            p.removeMediaItems(0, curIdx)
                        } else {
                            p.replaceMediaItems(0, curIdx, before)
                        }
                    } else if (before.isNotEmpty()) {
                        p.addMediaItems(0, before)
                    }
                }
                serviceOriginalMediaItems = emptyList()
            }
        }
        _physicalShuffleEnabled.value = newShuffle
        prefs.edit().putBoolean(PREF_IS_SHUFFLE, newShuffle).apply()
        PDLog.d(PDLog.PLAYBACK, "PHYSICAL_SHUFFLE") { "enabled=$newShuffle" }
        updateCustomLayout()
    }

    private data class NotificationArtDescriptor(
        val cacheKey: String,
        val artRequest: AlbumArtRequest?,
        val gradStart: Long,
        val gradEnd: Long,
        val fallbackArtworkData: ByteArray? = null
    )

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createNotificationBitmapLoader(): BitmapLoader {
        return object : BitmapLoader {
            override fun supportsMimeType(mimeType: String): Boolean {
                return mimeType.startsWith("image/")
            }

            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
                val desc = NotificationArtDescriptor(
                    cacheKey = "bytes:${data.size}:${data.contentHashCode()}:$DEFAULT_GRADIENT_START:$DEFAULT_GRADIENT_END",
                    artRequest = null,
                    gradStart = DEFAULT_GRADIENT_START,
                    gradEnd = DEFAULT_GRADIENT_END,
                    fallbackArtworkData = data
                )
                return loadNotificationBitmap(desc)
            }

            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                val gradStart = uri.getQueryParameter(QUERY_GRAD_START)?.toLongOrNull() ?: DEFAULT_GRADIENT_START
                val gradEnd = uri.getQueryParameter(QUERY_GRAD_END)?.toLongOrNull() ?: DEFAULT_GRADIENT_END
                val cleanUri = stripQuery(uri)
                val isSongMediaUri = cleanUri.toString().contains("/audio/media/") ||
                    cleanUri.scheme == ContentResolver.SCHEME_FILE
                val albumArtUri = cleanUri.takeIf { it.toString().contains("albumart") }
                val parsedId = runCatching { ContentUris.parseId(cleanUri) }.getOrDefault(0L)
                val albumId = if (albumArtUri != null) parsedId else 0L
                val key = computeAlbumArtKey(albumId, null, null, cleanUri.toString())
                val request = AlbumArtRequest(
                    albumKey = key,
                    albumId = albumId,
                    songContentUri = if (isSongMediaUri) cleanUri else null,
                    albumArtUri = albumArtUri,
                    songPath = if (cleanUri.scheme == ContentResolver.SCHEME_FILE) cleanUri.path else null
                )
                val desc = NotificationArtDescriptor(
                    cacheKey = "uri:$key:$gradStart:$gradEnd",
                    artRequest = request,
                    gradStart = gradStart,
                    gradEnd = gradEnd
                )
                return loadNotificationBitmap(desc)
            }

            override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
                val effectiveMetadata = enrichMetadataWithCurrentItemIfNeeded(metadata)
                return loadNotificationBitmap(buildNotificationArtDescriptor(effectiveMetadata))
            }
        }
    }

    private fun enrichMetadataWithCurrentItemIfNeeded(metadata: MediaMetadata): MediaMetadata {
        if (metadata.extras != null && metadata.artworkUri != null) return metadata
        if (Looper.myLooper() != Looper.getMainLooper()) return metadata
        val itemMeta = player?.currentMediaItem?.mediaMetadata ?: return metadata
        val sameTrack = metadata.title == null || itemMeta.title == null || metadata.title == itemMeta.title
        if (!sameTrack) return metadata
        return metadata.buildUpon().apply {
            if (metadata.extras == null && itemMeta.extras != null) {
                setExtras(itemMeta.extras)
            }
            if (metadata.artworkUri == null && itemMeta.artworkUri != null) {
                setArtworkUri(itemMeta.artworkUri)
            }
            if (metadata.albumTitle.isNullOrEmpty() && !itemMeta.albumTitle.isNullOrEmpty()) {
                setAlbumTitle(itemMeta.albumTitle)
            }
            if (metadata.artist.isNullOrEmpty() && !itemMeta.artist.isNullOrEmpty()) {
                setArtist(itemMeta.artist)
            }
        }.build()
    }

    private fun buildNotificationArtDescriptor(metadata: MediaMetadata): NotificationArtDescriptor {
        val extras = metadata.extras
        val gradStart = extras?.takeIf { it.containsKey(EXTRA_GRAD_START) }?.getLong(EXTRA_GRAD_START)
            ?: metadata.artworkUri?.getQueryParameter(QUERY_GRAD_START)?.toLongOrNull()
            ?: DEFAULT_GRADIENT_START
        val gradEnd = extras?.takeIf { it.containsKey(EXTRA_GRAD_END) }?.getLong(EXTRA_GRAD_END)
            ?: metadata.artworkUri?.getQueryParameter(QUERY_GRAD_END)?.toLongOrNull()
            ?: DEFAULT_GRADIENT_END
        val songId = extras?.getLong(EXTRA_SONG_ID, 0L) ?: 0L
        val albumId = extras?.getLong(EXTRA_ALBUM_ID, 0L) ?: 0L
        val albumName = metadata.albumTitle?.toString().orEmpty()
        val albumArtist = extras?.getString(EXTRA_ALBUM_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.artist?.toString().orEmpty()
        val songPath = extras?.getString(EXTRA_SONG_PATH)?.takeIf { it.isNotBlank() }
        val cleanUri = metadata.artworkUri?.let { stripQuery(it) }
        val fallbackData = metadata.artworkData?.takeIf { it.isNotEmpty() }

        val hasLocalSource = cleanUri != null || songId > 0L || albumId > 0L || !songPath.isNullOrBlank()
        if (!hasLocalSource) {
            return if (fallbackData != null) {
                NotificationArtDescriptor(
                    cacheKey = "bytes:${fallbackData.size}:${fallbackData.contentHashCode()}:$gradStart:$gradEnd",
                    artRequest = null,
                    gradStart = gradStart,
                    gradEnd = gradEnd,
                    fallbackArtworkData = fallbackData
                )
            } else {
                NotificationArtDescriptor(
                    cacheKey = "grad:$gradStart:$gradEnd",
                    artRequest = null,
                    gradStart = gradStart,
                    gradEnd = gradEnd
                )
            }
        }

        val uriStr = cleanUri?.toString().orEmpty()
        val isAlbumArtUri = uriStr.contains("albumart")
        val isSongMediaUri = cleanUri != null &&
            (uriStr.contains("/audio/media/") || cleanUri.scheme == ContentResolver.SCHEME_FILE)
        val resolvedPath = songPath ?: if (cleanUri?.scheme == ContentResolver.SCHEME_FILE) cleanUri.path else null
        val isValidMediaStoreSongId = songId > 0L && (songId and SYNTHETIC_ID_FLAG) == 0L
        val resolvedSongUri = when {
            isSongMediaUri -> cleanUri
            isValidMediaStoreSongId -> ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                songId
            )
            cleanUri != null && !isAlbumArtUri -> cleanUri
            else -> null
        }

        val effectiveSongId = if (songId > 0L) {
            songId
        } else if (uriStr.contains("/audio/media/")) {
            runCatching { ContentUris.parseId(cleanUri!!) }.getOrDefault(0L)
        } else {
            0L
        }

        val isolated = isStandaloneTrackAlbum(albumName, resolvedPath) &&
            (effectiveSongId > 0L || !resolvedPath.isNullOrBlank() || resolvedSongUri != null)

        val request = if (isolated) {
            val trackKey = computeTrackArtKey(
                songId = effectiveSongId,
                songPath = resolvedPath,
                contentUriStr = resolvedSongUri?.toString() ?: uriStr.takeIf { it.isNotEmpty() }
            )
            AlbumArtRequest(
                albumKey = trackKey,
                albumId = 0L,
                albumName = albumName,
                albumArtist = albumArtist,
                songContentUri = resolvedSongUri,
                albumArtUri = null,
                songPath = resolvedPath,
                isTrackIsolated = true
            )
        } else {
            val effectiveAlbumId = if (albumId > 0L) {
                albumId
            } else if (isAlbumArtUri) {
                runCatching { ContentUris.parseId(cleanUri!!) }.getOrDefault(0L)
            } else {
                0L
            }
            val validMediaStoreAlbumId = effectiveAlbumId > 0L && (effectiveAlbumId and SYNTHETIC_ID_FLAG) == 0L
            val resolvedAlbumArtUri = when {
                isAlbumArtUri -> cleanUri
                validMediaStoreAlbumId -> ContentUris.withAppendedId(Uri.parse(ALBUM_ART_BASE_URI), effectiveAlbumId)
                else -> null
            }
            val albumKey = computeAlbumArtKey(
                albumId = effectiveAlbumId,
                albumName = albumName,
                albumArtist = albumArtist,
                fallbackUriOrPath = resolvedPath ?: resolvedSongUri?.toString() ?: uriStr.takeIf { it.isNotEmpty() }
            )
            AlbumArtRequest(
                albumKey = albumKey,
                albumId = effectiveAlbumId,
                albumName = albumName,
                albumArtist = albumArtist,
                songContentUri = resolvedSongUri,
                albumArtUri = resolvedAlbumArtUri,
                songPath = resolvedPath,
                isTrackIsolated = false
            )
        }

        // Canonical album-keyed cache key stays invariant even when ExoPlayer later merges embedded
        // artworkData, track tags, or duration into MediaMetadata for the same playing item.
        return NotificationArtDescriptor(
            cacheKey = "art:${request.albumKey}:$gradStart:$gradEnd",
            artRequest = request,
            gradStart = gradStart,
            gradEnd = gradEnd,
            fallbackArtworkData = fallbackData
        )
    }

    private fun loadNotificationBitmap(desc: NotificationArtDescriptor): ListenableFuture<Bitmap> {
        val artManager = AlbumArtManager.getInstance(applicationContext)
        syncArtCacheVersionIfNeeded(artManager)

        // 1. Synchronous notification bitmap cache hit -> Futures.immediateFuture (isDone == true, 0 ms)
        notificationBitmapCache.get(desc.cacheKey)?.takeIf { !it.isRecycled }?.let { cached ->
            return Futures.immediateFuture(cached)
        }

        val request = desc.artRequest
        if (request == null) {
            if (desc.fallbackArtworkData == null) {
                return Futures.immediateFuture(getOrCreateGradientBitmap(desc.gradStart, desc.gradEnd))
            }
            return loadNotificationBitmapAsync(desc, artManager)
        }

        // 2. Synchronous L1 RAM hit across CARD (512px) / HIGH_RES (1024px) / ULTRA_RES (1500px)
        val syncFullArt = artManager.getFromL1(request.albumKey, ArtResolutionBucket.CARD)?.bitmap
            ?: artManager.getFromL1(request.albumKey, ArtResolutionBucket.HIGH_RES)?.bitmap
            ?: artManager.getFromL1(request.albumKey, ArtResolutionBucket.ULTRA_RES)?.bitmap
        if (syncFullArt != null && !syncFullArt.isRecycled) {
            val ready = prepareNotificationBitmap(syncFullArt)
            notificationBitmapCache.put(desc.cacheKey, ready)
            return Futures.immediateFuture(ready)
        }

        // 3. Synchronous known-missing album fallback -> instant gradient without null-art flash.
        //    Do not store gradients in notificationBitmapCache so later artworkData or recoveries still win.
        if (artManager.isKnownMissing(request.albumKey) && desc.fallbackArtworkData == null) {
            return Futures.immediateFuture(getOrCreateGradientBitmap(desc.gradStart, desc.gradEnd))
        }

        // 4. Synchronous L1 THUMBNAIL hit -> return immediately so SystemUI/Notification never flashes
        //    null/blank artwork when tapping a track from a list, while warming 512px CARD in background.
        val syncThumb = artManager.getFromL1(request.albumKey, ArtResolutionBucket.THUMBNAIL)?.bitmap
        if (syncThumb != null && !syncThumb.isRecycled) {
            warmNotificationBitmapInBackground(desc, artManager)
            return Futures.immediateFuture(prepareNotificationBitmap(syncThumb))
        }

        // 5. True cold miss -> single-flight async decode on Dispatchers.IO
        return loadNotificationBitmapAsync(desc, artManager)
    }

    private fun loadNotificationBitmapAsync(
        desc: NotificationArtDescriptor,
        artManager: AlbumArtManager
    ): ListenableFuture<Bitmap> {
        inFlightNotificationArt[desc.cacheKey]?.let { existing ->
            if (!existing.isCancelled) return existing
        }
        val future = SettableFuture.create<Bitmap>()
        val previous = inFlightNotificationArt.putIfAbsent(desc.cacheKey, future)
        if (previous != null && !previous.isCancelled) {
            return previous
        }
        if (previous != null) {
            inFlightNotificationArt[desc.cacheKey] = future
        }
        serviceScope.launch {
            try {
                val resolved = resolveNotificationBitmapOnIo(desc, artManager)
                if (resolved != null && !resolved.isRecycled) {
                    notificationBitmapCache.put(desc.cacheKey, resolved)
                    future.set(resolved)
                } else {
                    // Never cache fallback gradients in notificationBitmapCache; gradientBitmapCache
                    // already serves them synchronously while keeping real artwork cache unpoisoned.
                    future.set(getOrCreateGradientBitmap(desc.gradStart, desc.gradEnd))
                }
            } catch (ce: CancellationException) {
                future.cancel(false)
                throw ce
            } catch (_: Exception) {
                future.set(getOrCreateGradientBitmap(desc.gradStart, desc.gradEnd))
            } finally {
                inFlightNotificationArt.remove(desc.cacheKey, future)
            }
        }
        return future
    }

    private fun warmNotificationBitmapInBackground(
        desc: NotificationArtDescriptor,
        artManager: AlbumArtManager
    ) {
        if (notificationBitmapCache.get(desc.cacheKey) != null) return
        if (inFlightNotificationArt.containsKey(desc.cacheKey)) return
        loadNotificationBitmapAsync(desc, artManager)
    }

    private suspend fun resolveNotificationBitmapOnIo(
        desc: NotificationArtDescriptor,
        artManager: AlbumArtManager
    ): Bitmap? {
        val request = desc.artRequest
        val loadedFromManager = if (request != null) {
            try {
                artManager.loadBitmap(request, ArtResolutionBucket.CARD)?.takeIf { !it.isRecycled }
            } catch (ce: CancellationException) {
                // Propagate coroutine cancellation so cancelled prefetches never fall back to a gradient
                throw ce
            } catch (_: Throwable) {
                null
            } ?: (
                artManager.getFromL1(request.albumKey, ArtResolutionBucket.HIGH_RES)?.bitmap
                    ?: artManager.getFromL1(request.albumKey, ArtResolutionBucket.ULTRA_RES)?.bitmap
                    ?: artManager.getFromL1(request.albumKey, ArtResolutionBucket.THUMBNAIL)?.bitmap
                )?.takeIf { !it.isRecycled }
        } else {
            null
        }
        val resolvedRaw = loadedFromManager
            ?: desc.fallbackArtworkData?.let { bytes ->
                decodeByteArrayDownsampled(bytes, NOTIFICATION_ARTWORK_SIZE_PX)
            }
        return resolvedRaw?.takeIf { !it.isRecycled }?.let { prepareNotificationBitmap(it) }
    }

    /**
     * Proactively warms 512px notification artwork for the current track and immediate queue
     * neighbors on `Dispatchers.IO` so Next/Previous/gapless transitions (including background or
     * notification/Bluetooth controls) resolve synchronously (`isDone() == true`) with zero flash.
     */
    private fun preloadNotificationArtworkNeighbors() {
        val p = player ?: return
        val count = p.mediaItemCount
        if (count <= 0) {
            notificationArtPrefetchJob?.cancel()
            notificationArtPrefetchJob = null
            return
        }
        val artManager = AlbumArtManager.getInstance(applicationContext)
        syncArtCacheVersionIfNeeded(artManager)

        val candidateIndices = LinkedHashSet<Int>(4)
        val curIdx = p.currentMediaItemIndex
        if (curIdx in 0 until count) candidateIndices.add(curIdx)
        val nextIdx = p.nextMediaItemIndex
        if (nextIdx != C.INDEX_UNSET && nextIdx in 0 until count) candidateIndices.add(nextIdx)
        val prevIdx = p.previousMediaItemIndex
        if (prevIdx != C.INDEX_UNSET && prevIdx in 0 until count) candidateIndices.add(prevIdx)
        if (nextIdx != C.INDEX_UNSET && nextIdx + 1 in 0 until count) {
            candidateIndices.add(nextIdx + 1)
        } else if (p.repeatMode == Player.REPEAT_MODE_ALL && nextIdx == count - 1 && count > 1) {
            candidateIndices.add(0)
        }

        val descriptors = LinkedHashMap<String, NotificationArtDescriptor>(candidateIndices.size)
        for (idx in candidateIndices) {
            val item = p.getMediaItemAt(idx)
            val desc = buildNotificationArtDescriptor(item.mediaMetadata)
            val req = desc.artRequest
            if (req != null && artManager.isKnownMissing(req.albumKey) && desc.fallbackArtworkData == null) {
                continue
            }
            val cached = notificationBitmapCache.get(desc.cacheKey)
            if (cached == null || cached.isRecycled) {
                descriptors.putIfAbsent(desc.cacheKey, desc)
            }
        }
        if (descriptors.isEmpty()) return

        notificationArtPrefetchJob?.cancel()
        notificationArtPrefetchJob = serviceScope.launch {
            for (desc in descriptors.values) {
                ensureActive()
                val alreadyCached = notificationBitmapCache.get(desc.cacheKey)
                if (alreadyCached != null && !alreadyCached.isRecycled) continue
                try {
                    val bitmap = resolveNotificationBitmapOnIo(desc, artManager)
                    ensureActive()
                    if (bitmap != null && !bitmap.isRecycled) {
                        notificationBitmapCache.put(desc.cacheKey, bitmap)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Best-effort prefetch; never cache a fallback gradient on transient failure
                }
            }
        }
    }

    private fun syncArtCacheVersionIfNeeded(artManager: AlbumArtManager) {
        val currentVersion = artManager.cacheVersion.value
        if (observedArtCacheVersion == currentVersion) return
        synchronized(artCacheVersionLock) {
            if (observedArtCacheVersion != currentVersion) {
                observedArtCacheVersion = currentVersion
                notificationBitmapCache.evictAll()
                inFlightNotificationArt.clear()
            }
        }
    }

    private fun prepareNotificationBitmap(source: Bitmap): Bitmap {
        val softwareBitmap = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            runCatching { source.copy(Bitmap.Config.RGB_565, false) }.getOrNull() ?: source
        } else {
            source
        }
        val maxDim = max(softwareBitmap.width, softwareBitmap.height)
        if (maxDim <= NOTIFICATION_ARTWORK_SIZE_PX || maxDim <= 0) {
            return softwareBitmap
        }
        val ratio = NOTIFICATION_ARTWORK_SIZE_PX.toFloat() / maxDim.toFloat()
        val dstW = (softwareBitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val dstH = (softwareBitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(softwareBitmap, dstW, dstH, true)
        }.getOrDefault(softwareBitmap)
    }

    private fun decodeByteArrayDownsampled(bytes: ByteArray, maxSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var inSampleSize = 1
            if (bounds.outHeight > maxSizePx || bounds.outWidth > maxSizePx) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / inSampleSize) >= maxSizePx && (halfW / inSampleSize) >= maxSizePx) {
                    inSampleSize *= 2
                }
            }
            val opts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private fun stripQuery(uri: Uri): Uri {
        return if (!uri.query.isNullOrEmpty()) uri.buildUpon().clearQuery().build() else uri
    }

    private fun getOrCreateGradientBitmap(gradStart: Long, gradEnd: Long): Bitmap {
        val key = "$gradStart:$gradEnd"
        gradientBitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        return renderGradientAlbumBitmap(gradStart, gradEnd).also {
            gradientBitmapCache.put(key, it)
        }
    }

    /**
     * Renders a fallback gradient cover matching the in-app `FallbackGradient` style for
     * media notifications when a track lacks embedded cover art.
     */
    private fun renderGradientAlbumBitmap(gradStart: Long, gradEnd: Long): Bitmap {
        val size = NOTIFICATION_ARTWORK_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            gradStart.toInt(), gradEnd.toInt(),
            Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        ContextCompat.getDrawable(this, R.drawable.ic_notification)?.mutate()?.let { icon ->
            val iconSize = (size * NOTIFICATION_ICON_RATIO).toInt()
            val left = (size - iconSize) / 2
            val top = (size - iconSize) / 2
            icon.setTint(Color.argb(NOTIFICATION_ICON_ALPHA, 255, 255, 255))
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
        }
        return bitmap
    }

    // ═══ USB DAC & Bit-Perfect Audio Routing ═══
    private fun observeUsbDacVolumeChanges(wrappedSessionPlayer: UsbDacSessionPlayer) {
        // Mirror USB DAC readiness, discrete UAC step changes, and Hot-Standby WakeLock transitions
        serviceScope.launch(Dispatchers.Main) {
            var prevReady = UsbDacManager.isDirectReady()
            var prevMax = UsbDacManager.state.value.maxVolumeSteps
            var prevStep = UsbDacManager.state.value.volumeStep
            var prevStatus = UsbDacManager.state.value.status
            UsbDacManager.state.collect { st ->
                val ready = UsbDacManager.isDirectReady()
                val maxSteps = st.maxVolumeSteps
                val step = st.volumeStep
                if (st.status != prevStatus) {
                    prevStatus = st.status
                    updateCpuWakeLock()
                }
                if (ready != prevReady || maxSteps != prevMax) {
                    prevReady = ready
                    prevMax = maxSteps
                    prevStep = step
                    wrappedSessionPlayer.notifyDeviceVolumeConfigChanged()
                } else if (step != prevStep) {
                    prevStep = step
                    wrappedSessionPlayer.notifyDeviceVolumeChanged()
                }
            }
        }
    }

    private fun handleUsbPermissionGrantedForBitPerfect() {
        val p = player ?: return
        val wasPlaying = p.isPlaying || p.playWhenReady
        if (wasPlaying) {
            val resumePos = p.currentPosition.coerceAtLeast(0L)
            PDLog.i(PDLog.USB_DAC, "USB_PERM_SWITCH", "state=playing posMs=$resumePos action=pause_reset")
            p.pause()
            usbDacAudioSink?.resetForUsbDirectSwitch()
            p.seekTo(resumePos)
        } else {
            PDLog.i(PDLog.USB_DAC, "USB_PERM_SWITCH", "state=idle action=reset_sink")
            val hasLoadedItem = p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE
            val resumePos = if (hasLoadedItem) p.currentPosition.coerceAtLeast(0L) else 0L
            usbDacAudioSink?.resetForUsbDirectSwitch()
            if (hasLoadedItem) {
                p.seekTo(resumePos)
            }
        }
        configureBitPerfectMixerAttributes()
        sessionPlayer?.notifyDeviceVolumeConfigChanged()
    }

    private fun registerBitPerfectAudioDeviceObserver() {
        val am = audioManager ?: return
        val initialOutputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        updateBluetoothOutputState(initialOutputs)
        val knownDeviceIds = initialOutputs.mapTo(HashSet()) { it.id }
        val initDesc = if (initialOutputs.isEmpty()) "none" else initialOutputs.joinToString("; ") {
            "${AppLogManager.audioDeviceTypeName(it.type)}(id=${it.id}, '${it.productName}')"
        }
        PDLog.i(PDLog.PLAYBACK, "OUTPUT_INIT", "count=${initialOutputs.size} devs=$initDesc")

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                val addedDesc = describeOutputDevices(addedDevices)
                if (addedDesc.isNotEmpty()) {
                    PDLog.i(PDLog.PLAYBACK, "OUTPUT_ADDED", "devs=$addedDesc")
                }
                updateBluetoothOutputState(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                configureBitPerfectMixerAttributes()
                val newlyConnected = addedDevices?.filter { knownDeviceIds.add(it.id) }.orEmpty()
                if (newlyConnected.isEmpty()) return
                // Ignore initial enumeration callbacks emitted immediately after service startup
                if (SystemClock.elapsedRealtime() - serviceStartElapsedMs <= STARTUP_PLUG_SUPPRESSION_WINDOW_MS) return

                val hasHeadsetOrDac = newlyConnected.any { device ->
                    if (!device.isSink) return@any false
                    val t = device.type
                    t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && t == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                if (hasHeadsetOrDac) {
                    val isUsbAudioAdded = newlyConnected.any { device ->
                        device.isSink && (device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
                    }
                    if (isUsbAudioAdded && UsbDacManager.state.value.directEnabled && !UsbDacManager.isDirectReady()) {
                        // Rescan UsbManager in case ACTION_USB_DEVICE_ATTACHED broadcast was delayed or dropped in background
                        UsbDacManager.scanConnectedDevices()
                    }
                    // Defer auto-play while awaiting USB host permission so audio doesn't briefly route through speakers
                    val awaitingUsbPermission = isUsbAudioAdded &&
                        UsbDacManager.state.value.directEnabled &&
                        (!UsbDacManager.isDirectReady() || UsbDacManager.isAwaitingPermission())
                    val autoPlay = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(PREF_AUTO_PLAY_HEADSET, false)
                    val p = player
                    val isBackgroundBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isAppInForeground()
                    PDLog.i(
                        PDLog.PLAYBACK,
                        "HEADSET_PLUG",
                        "autoPlay=$autoPlay playing=${p?.isPlaying} waitUsbPerm=$awaitingUsbPermission bgBlocked=$isBackgroundBlocked"
                    )
                    if (autoPlay && !awaitingUsbPermission && !isBackgroundBlocked && p != null && p.mediaItemCount > 0 && !p.isPlaying) {
                        p.play()
                    }
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val removedDesc = describeOutputDevices(removedDevices)
                if (removedDesc.isNotEmpty()) {
                    PDLog.i(PDLog.PLAYBACK, "OUTPUT_REMOVED", "devs=$removedDesc")
                }
                removedDevices?.forEach { knownDeviceIds.remove(it.id) }
                val isUsbAudioRemoved = removedDevices?.any { device ->
                    device.isSink && (device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
                } == true
                if (isUsbAudioRemoved) {
                    UsbDacManager.scanConnectedDevices()
                }
                updateBluetoothOutputState(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                configureBitPerfectMixerAttributes()
            }
        }
        audioDeviceCallback = cb
        am.registerAudioDeviceCallback(cb, null)
    }

    private fun updateBluetoothOutputState(devices: Array<out AudioDeviceInfo>?) {
        val hasBt = devices?.any { device ->
            if (!device.isSink) return@any false
            val t = device.type
            t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        t == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        t == AudioDeviceInfo.TYPE_BLE_BROADCAST))
        } == true
        _isBluetoothOutputActive.value = hasBt
    }

    private fun isAppInForeground(): Boolean {
        return runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
    }

    private fun describeOutputDevices(devices: Array<out AudioDeviceInfo>?): String =
        devices?.filter { it.isSink }?.joinToString("; ") {
            "${AppLogManager.audioDeviceTypeName(it.type)}(id=${it.id}, '${it.productName}')"
        }.orEmpty()

    /**
     * Activates `MIXER_BEHAVIOR_BIT_PERFECT` on Android 14+ for capable USB DAC and Hi-Res outputs
     * so audio bypasses Android's software mixer and routes directly via Audio HAL.
     */
    private fun configureBitPerfectMixerAttributes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AppLogManager.updateBitPerfectMixerStatus("N/A (Android SDK ${Build.VERSION.SDK_INT} < 34)")
            return
        }
        val am = audioManager ?: return
        try {
            val platformAudioAttrs = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val targetSampleRate = player?.audioFormat?.sampleRate ?: 0
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var foundBitPerfect = false
            for (device in outputs) {
                val supported = am.getSupportedMixerAttributes(device)
                val bitPerfectList = supported.filter {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                if (bitPerfectList.isNotEmpty()) {
                    foundBitPerfect = true
                    val bestAttr = (if (targetSampleRate > 0) {
                        bitPerfectList.find { it.format.sampleRate == targetSampleRate }
                    } else null) ?: bitPerfectList.maxByOrNull { it.format.sampleRate }
                    if (bestAttr != null) {
                        val applied = am.setPreferredMixerAttributes(platformAudioAttrs, device, bestAttr)
                        val msg = "HAL Bit-Perfect on ${AppLogManager.audioDeviceTypeName(device.type)}('${device.productName}'): " +
                            "${bestAttr.format.sampleRate}Hz enc=${bestAttr.format.encoding} applied=$applied"
                        AppLogManager.updateBitPerfectMixerStatus(msg)
                        PDLog.i(
                            PDLog.PLAYBACK,
                            "HAL_MIXER_BITPERFECT",
                            "dev=${AppLogManager.audioDeviceTypeName(device.type)} rate=${bestAttr.format.sampleRate} " +
                                "enc=${bestAttr.format.encoding} applied=$applied"
                        )
                    }
                }
            }
            if (!foundBitPerfect) {
                AppLogManager.updateBitPerfectMixerStatus(
                    "No HAL MIXER_BEHAVIOR_BIT_PERFECT reported by outputs (SDK ${Build.VERSION.SDK_INT})"
                )
            }
        } catch (e: Exception) {
            AppLogManager.updateBitPerfectMixerStatus("Error: ${e.message}")
            PDLog.w(PDLog.PLAYBACK, "HAL_MIXER_ERR", "err=${e.message}")
        }
    }

    /**
     * Closes any external equalizer or DSP control sessions bound to ExoPlayer's audio session to
     * preserve an unaltered signal path.
     */
    private fun detachExternalDsp() {
        val p = player ?: return
        val sessionId = p.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != lastDetachedDspSessionId) {
            try {
                val closeIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                }
                sendBroadcast(closeIntent)
                lastDetachedDspSessionId = sessionId
            } catch (_: Exception) {
            }
        }
    }

    private fun extractSelectedAudioFormat(tracks: Tracks): Format? {
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected && group.length > 0) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        return group.getTrackFormat(i)
                    }
                }
                return group.getTrackFormat(0)
            }
        }
        return null
    }

    private fun registerDirectFilePath(mediaItem: MediaItem?) {
        val uri = mediaItem?.localConfiguration?.uri ?: return
        if (uri == Uri.EMPTY || uri.scheme.equals("file", ignoreCase = true)) return
        val path = mediaItem.mediaMetadata.extras?.getString(EXTRA_SONG_PATH)?.trim().orEmpty()
        if (path.isEmpty()) return
        if (directFileUriMap.size >= MAX_DIRECT_FILE_URI_MAP_ENTRIES && !directFileUriMap.containsKey(uri)) {
            directFileUriMap.clear()
        }
        directFileUriMap[uri] = path
    }

    private fun indexActiveQueueDirectFilePaths() {
        val p = player ?: return
        val count = p.mediaItemCount
        if (count <= 0) return
        val cur = p.currentMediaItemIndex.coerceIn(0, count - 1)
        val start = (cur - 2).coerceAtLeast(0)
        val end = (cur + 6).coerceAtMost(count - 1)
        for (idx in start..end) {
            registerDirectFilePath(p.getMediaItemAt(idx))
        }
    }

    private fun updateUsbSinkSourceFormat(mediaItem: MediaItem?, mimeHint: String? = null) {
        val sink = usbDacAudioSink ?: return
        currentSourceResolveJob?.cancel()
        currentSourceResolveJob = null
        if (mediaItem == null) {
            lastResolvedSourceMediaId = null
            nextSourcePrefetchJob?.cancel()
            nextSourcePrefetchJob = null
            sink.resetSourceAudioFormat()
            return
        }

        registerDirectFilePath(mediaItem)

        // Publish an instant zero-I/O format hint on Main Thread from in-memory cache or persisted
        // Song metadata so onMediaItemTransition never stalls UI rendering on storage/Binder IPC.
        val mediaId = mediaItem.mediaId
        val uri = mediaItem.localConfiguration?.uri
        val extras = mediaItem.mediaMetadata.extras
        val path = extras?.getString(EXTRA_SONG_PATH)
        val persistedBits = extras?.getInt(EXTRA_SOURCE_BIT_DEPTH, 0) ?: 0
        val persistedCodec = extras?.getString(EXTRA_SOURCE_CODEC).orEmpty()
        val (fastFormat, isExact) = AudioSpecsHelper.inferFastSourceAudioFormat(
            uri = uri,
            path = path,
            persistedBitDepth = persistedBits,
            persistedCodec = persistedCodec,
            mimeHint = mimeHint
        )
        lastResolvedSourceMediaId = mediaId
        sink.setSourceAudioFormat(
            sourceBitsPerSample = fastFormat.sourceBitsPerSample,
            isSourceLossless = fastFormat.isSourceLossless,
            isSourceFloat = fastFormat.isSourceFloat,
            mediaId = mediaId
        )
        PDLog.d(PDLog.PLAYBACK, "SOURCE_FMT_HINT") {
            "bits=${fastFormat.sourceBitsPerSample} lossless=${fastFormat.isSourceLossless} float=${fastFormat.isSourceFloat} exact=$isExact"
        }

        if (!isExact) {
            currentSourceResolveJob = serviceScope.launch {
                val resolved = AudioSpecsHelper.resolveSourceAudioFormat(
                    context = applicationContext,
                    uri = uri,
                    path = path,
                    mimeHint = mimeHint
                )
                if (lastResolvedSourceMediaId == mediaId && resolved != fastFormat) {
                    sink.setSourceAudioFormat(
                        sourceBitsPerSample = resolved.sourceBitsPerSample,
                        isSourceLossless = resolved.isSourceLossless,
                        isSourceFloat = resolved.isSourceFloat,
                        mediaId = mediaId
                    )
                    PDLog.d(PDLog.PLAYBACK, "SOURCE_FMT_REFINED") {
                        "bits=${resolved.sourceBitsPerSample} lossless=${resolved.isSourceLossless} float=${resolved.isSourceFloat}"
                    }
                }
            }
        }
        prefetchNextUsbSinkSourceFormat()
    }

    /**
     * Prefetches the upcoming queue track's source bit depth on an I/O coroutine so gapless
     * transitions invoking `UsbDacAudioSink.configure()` ahead of `onMediaItemTransition` open
     * the USB DAC stream once without a follow-up `pendingSourceReconfigure`.
     */
    private fun prefetchNextUsbSinkSourceFormat() {
        val p = player ?: return
        val sink = usbDacAudioSink ?: return
        if (p.repeatMode == Player.REPEAT_MODE_ONE) {
            // Repeat-One loops the active track whose sourceFormatHint is already active;
            // clearing nextSourceFormatHint prevents promoting the next queue track's bit depth.
            nextSourcePrefetchJob?.cancel()
            nextSourcePrefetchJob = null
            sink.clearNextSourceAudioFormat()
            return
        }
        val nextIndex = p.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until p.mediaItemCount) {
            nextSourcePrefetchJob?.cancel()
            nextSourcePrefetchJob = null
            sink.clearNextSourceAudioFormat()
            return
        }

        val nextItem = p.getMediaItemAt(nextIndex)
        registerDirectFilePath(nextItem)
        val nextMediaId = nextItem.mediaId
        val nextUri = nextItem.localConfiguration?.uri
        val nextPath = nextItem.mediaMetadata.extras?.getString(EXTRA_SONG_PATH)
        nextSourcePrefetchJob?.cancel()
        nextSourcePrefetchJob = serviceScope.launch {
            val nextFormat = AudioSpecsHelper.resolveSourceAudioFormat(
                context = applicationContext,
                uri = nextUri,
                path = nextPath
            )
            sink.setNextSourceAudioFormat(
                mediaId = nextMediaId,
                sourceBitsPerSample = nextFormat.sourceBitsPerSample,
                isSourceLossless = nextFormat.isSourceLossless,
                isSourceFloat = nextFormat.isSourceFloat
            )
        }
    }

    private fun publishExoAudioFormat(format: Format?, mediaItem: MediaItem?) {
        if (format == null || format.sampleRate <= 0) return
        val songId = mediaItem?.mediaId?.substringBefore('#')?.toLongOrNull() ?: return
        _currentAudioFormat.value = ExoAudioFormatSnapshot(
            songId = songId,
            mimeType = format.sampleMimeType,
            sampleRate = format.sampleRate,
            pcmEncoding = format.pcmEncoding,
            bitrate = format.bitrate
        )
    }

    // ═══ Diagnostics & WakeLock ═══
    private fun registerDiagnosticsSnapshotProvider() {
        AppLogManager.serviceSnapshotProvider = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                captureDiagnosticsSnapshot()
            } else {
                val latch = CountDownLatch(1)
                var snap: PlaybackDiagnosticsSnapshot? = null
                Handler(Looper.getMainLooper()).post {
                    try {
                        snap = captureDiagnosticsSnapshot()
                    } finally {
                        latch.countDown()
                    }
                }
                latch.await(MAIN_LOOPER_SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                snap ?: PlaybackDiagnosticsSnapshot(playbackStateLabel = "TIMEOUT_MAIN_LOOPER")
            }
        }
    }

    private fun captureDiagnosticsSnapshot(): PlaybackDiagnosticsSnapshot {
        val p = player
        val sp = sessionPlayer
        val (sinkMode, directDetail) = AppLogManager.getCurrentSinkSummary()
        val curMd = p?.currentMediaItem?.mediaMetadata
        val remoteVolStr = if (sp != null) {
            val info = sp.deviceInfo
            "playbackType=${if (info.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) "REMOTE" else "LOCAL"}, " +
                "vol=${sp.deviceVolume}/${info.maxVolume}, muted=${sp.isDeviceMuted}"
        } else {
            "N/A"
        }
        return PlaybackDiagnosticsSnapshot(
            playbackStateLabel = playbackStateName(p?.playbackState ?: Player.STATE_IDLE),
            playWhenReady = p?.playWhenReady == true,
            isPlaying = p?.isPlaying == true,
            shuffleEnabled = _physicalShuffleEnabled.value,
            repeatMode = p?.repeatMode ?: 0,
            currentTitle = curMd?.title?.toString().orEmpty(),
            currentArtist = curMd?.artist?.toString().orEmpty(),
            sourceSpecs = curMd?.extras?.getString(EXTRA_AUDIO_SPECS).orEmpty(),
            exoAudioFormat = formatSummary(p?.audioFormat),
            audioSessionId = p?.audioSessionId ?: 0,
            wakeLockHeld = cpuWakeLock?.isHeld == true,
            mediaSessionVolume = remoteVolStr,
            activeAudioSinkMode = sinkMode,
            activeUsbDirectDetail = directDetail,
            lastAudioTrackConfig = AppLogManager.getLastAudioTrackSummary(),
            bitPerfectMixerStatus = AppLogManager.getLastMixerAttrSummary(),
            lastPlayerError = AppLogManager.getLastPlayerErrorSummary()
        )
    }

    /**
     * Acquires a partial CPU WakeLock and re-applies HAL bit-perfect mixer settings and DSP
     * detachment whenever playback becomes active.
     */
    private fun onPlaybackActivated() {
        acquireCpuWakeLock()
        configureBitPerfectMixerAttributes()
        detachExternalDsp()
    }

    private fun updateSilentKeepAlive() {
        val p = player
        // Keep the silent AudioTrack alive across brief buffering/track transitions so AudioFlinger
        // isn't torn down and recreated on every song skip or seek.
        val playbackActive = p != null &&
            p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE &&
            p.playbackState != Player.STATE_ENDED &&
            p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val active = playbackActive && UsbDacManager.isKeepAliveEligible()
        silentAudioKeepAlive?.setActive(active)
    }

    private fun updateCpuWakeLock() {
        val p = player
        val playbackActive = p != null &&
            p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE &&
            p.playbackState != Player.STATE_ENDED
        val dacStatus = UsbDacManager.state.value.status
        val usbIsoActive = dacStatus == UsbDacStatus.STREAMING || dacStatus == UsbDacStatus.PAUSED
        if (playbackActive || usbIsoActive) {
            acquireCpuWakeLock()
        } else {
            releaseCpuWakeLock()
        }
    }

    private fun acquireCpuWakeLock() {
        try {
            if (cpuWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (_: Throwable) {
        }
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "STATE_IDLE"
        Player.STATE_BUFFERING -> "STATE_BUFFERING"
        Player.STATE_READY -> "STATE_READY"
        Player.STATE_ENDED -> "STATE_ENDED"
        else -> "STATE($state)"
    }

    private fun formatSummary(fmt: Format?): String {
        if (fmt == null) return "none"
        return "mime=${fmt.sampleMimeType}, rate=${fmt.sampleRate}Hz, ch=${fmt.channelCount}, " +
            "pcmEnc=${fmt.pcmEncoding}, bitrate=${fmt.bitrate}, codecs=${fmt.codecs}"
    }

    // ═══ MediaSession Remote Volume Wrapper ═══
    /**
     * [ForwardingPlayer] wrapper that exposes discrete UAC hardware volume steps to [MediaSession]
     * so hardware volume key presses on the lock screen, external apps, or SystemUI volume sliders
     * control [UsbDacManager]'s hardware step table (20/40/50/100) rather than software gain.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private class UsbDacSessionPlayer(
        wrapped: Player
    ) : ForwardingPlayer(wrapped) {

        private val listeners = CopyOnWriteArrayList<Player.Listener>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var preMuteStep = DEFAULT_PRE_MUTE_VOLUME_STEP
        private val deviceVolumeCommands = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
        )

        override fun addListener(listener: Player.Listener) {
            listeners.addIfAbsent(listener)
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)
            super.removeListener(listener)
        }

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            return if (UsbDacManager.isDirectReady()) {
                base.buildUpon()
                    .addAll(*deviceVolumeCommands)
                    .build()
            } else {
                base
            }
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return if (UsbDacManager.isDirectReady() && command in deviceVolumeCommands) {
                true
            } else {
                super.isCommandAvailable(command)
            }
        }

        override fun getDeviceInfo(): DeviceInfo {
            return if (UsbDacManager.isDirectReady()) {
                val maxSteps = UsbDacManager.state.value.maxVolumeSteps.coerceAtLeast(1)
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(maxSteps)
                    .build()
            } else {
                super.getDeviceInfo()
            }
        }

        override fun getDeviceVolume(): Int {
            return if (UsbDacManager.isDirectReady()) {
                UsbDacManager.state.value.volumeStep
            } else {
                super.getDeviceVolume()
            }
        }

        override fun isDeviceMuted(): Boolean {
            return if (UsbDacManager.isDirectReady()) {
                UsbDacManager.state.value.volumeStep <= 0
            } else {
                super.isDeviceMuted()
            }
        }

        override fun setDeviceVolume(volume: Int) {
            setDeviceVolume(volume, 0)
        }

        override fun setDeviceVolume(volume: Int, flags: Int) {
            if (UsbDacManager.isDirectReady()) {
                UsbDacManager.setVolumeStep(volume, showHud = false, source = "remote")
            } else {
                super.setDeviceVolume(volume, flags)
            }
        }

        override fun increaseDeviceVolume() {
            increaseDeviceVolume(0)
        }

        override fun increaseDeviceVolume(flags: Int) {
            if (UsbDacManager.isDirectReady()) {
                UsbDacManager.stepVolume(+1, showHud = false, source = "remote")
            } else {
                super.increaseDeviceVolume(flags)
            }
        }

        override fun decreaseDeviceVolume() {
            decreaseDeviceVolume(0)
        }

        override fun decreaseDeviceVolume(flags: Int) {
            if (UsbDacManager.isDirectReady()) {
                UsbDacManager.stepVolume(-1, showHud = false, source = "remote")
            } else {
                super.decreaseDeviceVolume(flags)
            }
        }

        override fun setDeviceMuted(muted: Boolean) {
            setDeviceMuted(muted, 0)
        }

        override fun setDeviceMuted(muted: Boolean, flags: Int) {
            if (UsbDacManager.isDirectReady()) {
                val cur = UsbDacManager.state.value.volumeStep
                if (muted) {
                    if (cur > 0) preMuteStep = cur
                    UsbDacManager.setVolumeStep(0, showHud = false)
                } else {
                    val restore = if (preMuteStep > 0) {
                        preMuteStep
                    } else {
                        (UsbDacManager.state.value.maxVolumeSteps / 2).coerceAtLeast(1)
                    }
                    UsbDacManager.setVolumeStep(restore, showHud = false)
                }
            } else {
                super.setDeviceMuted(muted, flags)
            }
        }

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        }

        fun notifyDeviceVolumeConfigChanged() {
            runOnMain {
                val cmds = availableCommands
                val info = deviceInfo
                val vol = deviceVolume
                val muted = isDeviceMuted
                listeners.forEach { l ->
                    l.onAvailableCommandsChanged(cmds)
                    l.onDeviceInfoChanged(info)
                    l.onDeviceVolumeChanged(vol, muted)
                }
            }
        }

        fun notifyDeviceVolumeChanged() {
            runOnMain {
                val vol = deviceVolume
                val muted = isDeviceMuted
                listeners.forEach { l ->
                    l.onDeviceVolumeChanged(vol, muted)
                }
            }
        }

        /**
         * Broadcasts an internal position sync event to MediaSession right as USB DAC warm-up
         * finishes so MediaController, SystemUI notifications, and lock-screen seekbars re-anchor
         * to the exact hardware playout timestamp with zero +warmUpMs drift.
         */
        fun notifyPositionClockSynced() {
            runOnMain {
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return@runOnMain
                val posMs = currentPosition.coerceAtLeast(0L)
                val info = Player.PositionInfo(
                    /* windowUid = */ null,
                    /* mediaItemIndex = */ currentMediaItemIndex,
                    /* mediaItem = */ currentMediaItem,
                    /* periodUid = */ null,
                    /* periodIndex = */ currentPeriodIndex,
                    /* positionMs = */ posMs,
                    /* contentPositionMs = */ posMs,
                    /* adGroupIndex = */ C.INDEX_UNSET,
                    /* adIndexInAdGroup = */ C.INDEX_UNSET
                )
                val events = Player.Events(
                    FlagSet.Builder()
                        .add(Player.EVENT_POSITION_DISCONTINUITY)
                        .build()
                )
                listeners.forEach { l ->
                    l.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_INTERNAL)
                    l.onEvents(this@UsbDacSessionPlayer, events)
                }
            }
        }
    }

    // ═══ Direct-File-First DataSource (Bypasses Android FUSE/MediaProvider When Readable) ═══
    @androidx.annotation.OptIn(UnstableApi::class)
    private class DirectFileFirstDataSourceFactory(
        context: Context,
        private val uriPathMap: ConcurrentHashMap<Uri, String>
    ) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context.applicationContext)
        private val fileFactory = FileDataSource.Factory()

        override fun createDataSource(): DataSource = DirectFileFirstDataSource(
            defaultDataSource = defaultFactory.createDataSource(),
            fileDataSource = fileFactory.createDataSource(),
            uriPathMap = uriPathMap
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private class DirectFileFirstDataSource(
        private val defaultDataSource: DataSource,
        private val fileDataSource: DataSource,
        private val uriPathMap: ConcurrentHashMap<Uri, String>
    ) : DataSource {
        private var activeSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            defaultDataSource.addTransferListener(transferListener)
            fileDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val mappedPath = uriPathMap[dataSpec.uri]
            if (!mappedPath.isNullOrBlank()) {
                val file = File(mappedPath)
                if (file.isFile && file.canRead()) {
                    try {
                        val fileSpec = dataSpec.buildUpon()
                            .setUri(Uri.fromFile(file))
                            .build()
                        val bytes = fileDataSource.open(fileSpec)
                        activeSource = fileDataSource
                        return bytes
                    } catch (_: Exception) {
                        runCatching { fileDataSource.close() }
                    }
                }
            }
            val bytes = defaultDataSource.open(dataSpec)
            activeSource = defaultDataSource
            return bytes
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = activeSource?.uri

        override fun close() {
            val src = activeSource
            activeSource = null
            src?.close()
        }
    }
}
