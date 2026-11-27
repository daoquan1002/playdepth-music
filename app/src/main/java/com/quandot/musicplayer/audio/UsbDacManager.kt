/**
 * UsbDacManager — USB Audio DAC discovery, permission workflow, lifecycle, WakeLock, and volume controller.
 *
 * @remarks
 * Coordinates Android `UsbManager` attach/detach broadcasts, runtime permission prompts,
 * kernel `snd-usb-audio` detachment via `UsbDeviceConnection.claimInterface(iface, true)`,
 * and native `usbdevfs` handoff through [UsbDacNative]. Invokes permission-granted listeners
 * strictly before routing-change listeners so ExoPlayer pauses and flushes active fallback
 * sinks prior to audio capabilities reconfiguration.
 *
 * Enforces a one-time per-DAC (`VID:PID`) 30% safe volume cap because UAC2/UAC3 Feature Units
 * frequently initialize at 0 dB full scale on first attachment, and holds a non-reference-counted
 * `PARTIAL_WAKE_LOCK` during active USB Isochronous playback so screen-off CPU sleep does not
 * starve the real-time URB pipeline. Hardware volume slider writes update [state] immediately for
 * zero-lag UI feedback while dispatching native USB control transfers through a conflated
 * coroutine pipeline (`Channel.CONFLATED` + 25 ms pacing) so Full-Speed UAC 1.0 per-channel L/R
 * transfers never queue up and stagger audibly during rapid slider drags.
 *
 * @see UsbDacNative
 * @see UsbDacAudioSink
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Internal modules
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog

// ═══ State & Contracts ═══
enum class UsbDacStatus {
    DISCONNECTED,
    PERMISSION_REQUIRED,
    READY,
    PAUSED,
    STREAMING,
    ERROR
}

@Immutable
data class UsbDacState(
    val status: UsbDacStatus = UsbDacStatus.DISCONNECTED,
    val deviceName: String = "",
    val devicePath: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val uacVersion: Int = 0,
    val supportedSampleRates: List<Int> = emptyList(),
    val supportedBitDepths: List<Int> = emptyList(),
    val maxSupportedChannels: Int = 2,
    val supportsNativeDsd: Boolean = false,
    val supportsDop: Boolean = false,
    val maxDsdRate: Int = 0,
    val hasHardwareVolume: Boolean = false,
    val hardwareVolumeMinDb256: Int = 0,
    val hardwareVolumeMaxDb256: Int = 0,
    val hardwareVolumeResDb256: Int = 256,
    val softwareVolumeBypassed: Boolean = true,
    val inputSampleRate: Int = 0,
    val inputBitDepth: Int = 0,
    val inputChannels: Int = 0,
    val inputEncoding: Int = 0,
    val activeSampleRate: Int = 0,
    val activeBitDepth: Int = 0,
    val activeChannels: Int = 0,
    val activeOutputMode: Int = UsbDacNative.OUTPUT_MODE_INACTIVE,
    val activeDspFlags: Int = UsbDacNative.DSP_FLAG_NONE,
    val clockSyncMode: Int = UsbDacNative.SYNC_MODE_NONE,
    val feedbackLocked: Boolean = false,
    val clockDriftPpmX10: Int = 0,
    val underrunCount: Long = 0L,
    val stallCount: Long = 0L,
    val maxStallMs: Long = 0L,
    val directEnabled: Boolean = true,
    val warmUpMs: Int = UsbDacManager.DEFAULT_WARMUP_MS,
    val idleTimeoutSec: Int = UsbDacManager.DEFAULT_IDLE_TIMEOUT_SEC,
    val volumeStep: Int = 50,
    val maxVolumeSteps: Int = 100,
    val isMuted: Boolean = false,
    val dsdMode: Int = UsbDacNative.DSD_MODE_AUTO,
    val dsdGainCompensationDb: Float = 6.0f,
    val ditherEnabled: Boolean = true,
    val resamplerQuality: Int = UsbDacNative.RESAMPLER_QUALITY_HIGH,
    val wakeLockHeld: Boolean = false,
    val keepAliveAudioEnabled: Boolean = false,
    val volumeHudTick: Long = 0L,
    val errorMessage: String? = null
)

// ═══ Device & Volume Manager ═══
object UsbDacManager {
    // ═══ Constants ═══
    private const val ACTION_USB_PERMISSION = "com.quandot.playdepth.music.USB_DAC_PERMISSION"
    private const val WAKE_LOCK_TAG = "PlayDepth:UsbDacIsoStreaming"
    private const val PREFS_NAME = "music_playback_prefs"

    // Persisted preference keys (existing keys must never be renamed)
    private const val KEY_USB_DIRECT_ENABLED = "usb_dac_direct_enabled"
    private const val KEY_USB_WARMUP_MS = "usb_dac_warmup_ms"
    private const val KEY_USB_IDLE_TIMEOUT_SEC = "usb_dac_idle_timeout_sec"
    private const val KEY_USB_KEEP_ALIVE_AUDIO = "usb_dac_keep_alive_audio"
    private const val KEY_USB_KEEP_ALIVE_USER_SET = "usb_dac_keep_alive_user_set"
    private const val KEY_USB_VOLUME_STEP = "usb_dac_volume_step_val"
    private const val KEY_USB_MAX_VOLUME_STEPS = "usb_dac_max_volume_steps"
    private const val KEY_USB_SAFE_INIT_APPLIED = "usb_dac_first_safe_volume_applied"
    private const val KEY_USB_SAFE_CHECKED_DEVICES = "usb_dac_safe_checked_devices"
    private const val KEY_USB_DSD_MODE = "usb_dac_dsd_mode"
    private const val KEY_USB_DSD_GAIN_DB = "usb_dac_dsd_gain_db"
    private const val KEY_USB_DITHER_ENABLED = "usb_dac_dither_enabled"
    private const val KEY_USB_RESAMPLER_QUALITY = "usb_dac_resampler_quality"

    private const val USB_SUBCLASS_AUDIOCONTROL = 0x01
    private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02

    const val DRIVER_NAME = "PlayDepth USB DAC Driver"
    const val DRIVER_VERSION = "1.1.3-20261126"
    const val DEFAULT_VOLUME_STEPS = 100
    const val DEFAULT_WARMUP_MS = 500
    private const val MAX_WARMUP_MS = 3000
    const val DEFAULT_IDLE_TIMEOUT_SEC = 15
    private const val MAX_IDLE_TIMEOUT_SEC = 300
    private const val IDLE_TIMEOUT_OFF_GRACE_MS = 300L
    private const val EXPIRE_STANDBY_DEBOUNCE_MS = 50L
    private const val DEFAULT_DSD_GAIN_DB = 6.0f
    private const val MIN_DSD_GAIN_DB = -6.0f
    private const val MAX_DSD_GAIN_DB = 12.0f
    private const val SAFE_MAX_VOLUME_FRACTION = 0.30f
    private const val VOLUME_DISPATCH_THROTTLE_MS = 25L
    private const val VOLUME_LOG_COALESCE_MS = 600L
    private const val POST_STREAM_VOLUME_SYNC_DELAY_MS = 120L
    private const val POST_STREAM_VOLUME_SYNC_MIN_DELAY_MS = 80L
    private const val STREAM_TID_POLL_STEP_MS = 10L
    private const val STREAM_TID_POLL_ATTEMPTS = 8
    private const val TELEMETRY_POLL_INTERVAL_MS = 1_500L

    // Target URB cycle duration for ADPF PerformanceHintSession; reported every 50 ms from real
    // native RT measurements so PowerHAL never observes a stale session or fake actual == target.
    private const val PERF_HINT_DEFAULT_TARGET_NS = 4_000_000L
    private const val PERF_HINT_MIN_TARGET_NS = 2_000_000L
    private const val PERF_HINT_MAX_TARGET_NS = 10_000_000L
    private const val PERF_HINT_REPORT_INTERVAL_MS = 50L
    private const val PERF_HINT_MAX_IDLE_TICKS = 20
    private val SUPPORTED_VOLUME_STEP_SCALES = listOf(20, 40, 50, 100)

    // ═══ State & Executors ═══
    private val _state = MutableStateFlow(UsbDacState())
    val state: StateFlow<UsbDacState> = _state.asStateFlow()

    private val routingListeners = CopyOnWriteArrayList<() -> Unit>()
    private val permissionGrantedListeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            r.run()
        }, "UsbDacControl").apply { isDaemon = true }
    }
    private val volumeScope = CoroutineScope(SupervisorJob() + controlExecutor.asCoroutineDispatcher())
    private val volumeDispatchChannel = Channel<Float>(Channel.CONFLATED)
    private val volumePipelineStarted = AtomicBoolean(false)

    private var appContext: Context? = null
    private var usbManager: UsbManager? = null
    private var audioManager: AudioManager? = null
    private var powerManager: PowerManager? = null
    private var streamingWakeLock: PowerManager.WakeLock? = null
    private var currentDevice: UsbDevice? = null
    @Volatile
    private var currentConnection: UsbDeviceConnection? = null
    private val claimedUsbInterfaces = mutableListOf<UsbInterface>()
    private var connectedDeviceId: Int = -1
    private var receiverRegistered = false
    private var perfHintSession: Any? = null
    private var perfHintHeartbeat: ScheduledFuture<*>? = null
    private var perfHintRetryFuture: ScheduledFuture<*>? = null
    private var telemetryHeartbeat: ScheduledFuture<*>? = null
    private var postStartVolumeSyncFuture: ScheduledFuture<*>? = null
    private var pauseIdleCloseFuture: ScheduledFuture<*>? = null
    @Volatile
    private var halHiResHintActive = false
    private var halHiResHintTid = 0
    @Volatile
    private var adpfUnsupportedLogged = false
    @Volatile
    private var lastOpenedInputSampleRate: Int = 0
    @Volatile
    private var lastOpenedInputBitDepth: Int = 0
    @Volatile
    private var lastOpenedInputChannels: Int = 0
    @Volatile
    private var lastOpenedInputEncoding: Int = 0

    @Volatile
    private var activityForeground = false
    private var lastPromptedDeviceId: Int = -1
    private var lastPromptWasForeground: Boolean = false
    private var userDeniedDeviceId: Int = -1

    private val volumeLogLock = Any()
    private var pendingVolLogStart: Int? = null
    private var pendingVolLogEnd: Int = 0
    private var pendingVolLogMax: Int = DEFAULT_VOLUME_STEPS
    private var pendingVolLogSource: String = "ui"
    private val flushVolumeLogRunnable = Runnable { flushCoalescedVolumeLog() }

    // ═══ Broadcast Receiver ═══
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getParcelableDevice(intent)
                    controlExecutor.execute {
                        if (device != null && isUsbAudioDevice(device)) {
                            PDLog.i(
                                PDLog.USB_DAC,
                                "USB_ATTACH_BCAST",
                                "dev=${device.productName} vidPid=${formatVidPid(device)} id=${device.deviceId}"
                            )
                            if (userDeniedDeviceId == device.deviceId) {
                                userDeniedDeviceId = -1
                            }
                            onAudioDeviceFound(device, retryBackgroundPermission = false)
                        } else {
                            PDLog.d(PDLog.USB_DAC, "USB_ATTACH_RESCAN") { "dev=${device?.productName}" }
                            scanConnectedDevices()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getParcelableDevice(intent)
                    controlExecutor.execute {
                        val matchesActive = device != null &&
                            (currentDevice?.deviceId == device.deviceId || connectedDeviceId == device.deviceId)
                        if (device != null && (matchesActive || isUsbAudioDevice(device))) {
                            PDLog.i(
                                PDLog.USB_DAC,
                                "USB_DETACH_BCAST",
                                "dev=${device.productName} vidPid=${formatVidPid(device)} id=${device.deviceId}"
                            )
                            if (userDeniedDeviceId == device.deviceId) userDeniedDeviceId = -1
                            if (lastPromptedDeviceId == device.deviceId) {
                                lastPromptedDeviceId = -1
                                lastPromptWasForeground = false
                            }
                            if (matchesActive || findFirstAudioDevice() == null) {
                                detachCurrentDevice()
                            } else {
                                scanConnectedDevices()
                            }
                        }
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = getParcelableDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    PDLog.i(
                        PDLog.USB_DAC,
                        "USB_PERM_RESULT",
                        "granted=$granted dev=${device?.productName} id=${device?.deviceId}"
                    )
                    if (granted && device != null && isUsbAudioDevice(device)) {
                        userDeniedDeviceId = -1
                        lastPromptedDeviceId = -1
                        lastPromptWasForeground = false
                        if (_state.value.directEnabled) {
                            controlExecutor.execute {
                                if (_state.value.directEnabled) {
                                    attachDeviceConnection(device, notifyRouting = false)
                                    if (isDirectReady()) {
                                        notifyPermissionGrantedThenRouting()
                                    } else {
                                        notifyRoutingChanged()
                                    }
                                }
                            }
                        }
                    } else if (device != null) {
                        userDeniedDeviceId = device.deviceId
                        lastPromptedDeviceId = device.deviceId
                        lastPromptWasForeground = true
                        _state.update {
                            it.copy(
                                status = UsbDacStatus.PERMISSION_REQUIRED,
                                deviceName = formatDeviceName(device),
                                devicePath = device.deviceName.orEmpty(),
                                vendorId = device.vendorId,
                                productId = device.productId,
                                errorMessage = "USB permission denied"
                            )
                        }
                    }
                }
            }
        }
    }

    // ═══ Lifecycle & Discovery ═══
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        streamingWakeLock = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        }.getOrNull()

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_USB_DIRECT_ENABLED, true)
        val warmUp = prefs.getInt(KEY_USB_WARMUP_MS, DEFAULT_WARMUP_MS).coerceIn(0, MAX_WARMUP_MS)
        val idleTimeoutSec = prefs.getInt(KEY_USB_IDLE_TIMEOUT_SEC, DEFAULT_IDLE_TIMEOUT_SEC)
            .coerceIn(0, MAX_IDLE_TIMEOUT_SEC)
        val keepAlive = if (prefs.getBoolean(KEY_USB_KEEP_ALIVE_USER_SET, false)) {
            prefs.getBoolean(KEY_USB_KEEP_ALIVE_AUDIO, false)
        } else {
            false
        }
        val maxSteps = sanitizeVolumeStepScale(prefs.getInt(KEY_USB_MAX_VOLUME_STEPS, DEFAULT_VOLUME_STEPS))
        val safeCap = safeCapStepForScale(maxSteps)
        val savedStep = if (prefs.contains(KEY_USB_VOLUME_STEP)) {
            val stored = prefs.getInt(KEY_USB_VOLUME_STEP, safeCap).coerceIn(0, maxSteps)
            if (!prefs.getBoolean(KEY_USB_SAFE_INIT_APPLIED, false) && stored > safeCap) {
                safeCap
            } else {
                stored
            }
        } else {
            readSystemStreamVolumeStep(maxSteps).coerceAtMost(safeCap)
        }
        val dsdMode = sanitizeDsdMode(prefs.getInt(KEY_USB_DSD_MODE, UsbDacNative.DSD_MODE_AUTO))
        val dsdGainDb = prefs.getFloat(KEY_USB_DSD_GAIN_DB, DEFAULT_DSD_GAIN_DB)
            .coerceIn(MIN_DSD_GAIN_DB, MAX_DSD_GAIN_DB)
        val ditherEnabled = prefs.getBoolean(KEY_USB_DITHER_ENABLED, true)
        val resamplerQuality = sanitizeResamplerQuality(
            prefs.getInt(KEY_USB_RESAMPLER_QUALITY, UsbDacNative.RESAMPLER_QUALITY_HIGH)
        )

        _state.update {
            it.copy(
                directEnabled = enabled,
                warmUpMs = warmUp,
                idleTimeoutSec = idleTimeoutSec,
                volumeStep = savedStep,
                maxVolumeSteps = maxSteps,
                dsdMode = dsdMode,
                dsdGainCompensationDb = dsdGainDb,
                ditherEnabled = ditherEnabled,
                resamplerQuality = resamplerQuality,
                keepAliveAudioEnabled = keepAlive
            )
        }

        ensureVolumeDispatchPipeline()
        syncDspPreferencesToNative()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }

        controlExecutor.execute {
            scanConnectedDevices()
        }
    }

    fun getWarmUpMs(): Int = _state.value.warmUpMs

    fun setWarmUpMs(ms: Int) {
        val clamped = ms.coerceIn(0, MAX_WARMUP_MS)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_WARMUP_MS, clamped)
            ?.apply()
        _state.update { it.copy(warmUpMs = clamped) }
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeSetWarmUpMs(clamped)
        }
        PDLog.d(PDLog.USB_DAC, "CFG_WARMUP") { "ms=$clamped" }
    }

    fun getIdleTimeoutSec(): Int = _state.value.idleTimeoutSec

    @Synchronized
    fun setIdleTimeoutSec(sec: Int) {
        val clamped = sec.coerceIn(0, MAX_IDLE_TIMEOUT_SEC)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_IDLE_TIMEOUT_SEC, clamped)
            ?.apply()
        _state.update { it.copy(idleTimeoutSec = clamped) }
        PDLog.d(PDLog.USB_DAC, "CFG_IDLE_TIMEOUT") { "sec=$clamped" }
        if (_state.value.status == UsbDacStatus.PAUSED && isDirectReady()) {
            schedulePauseIdleClose()
        }
    }

    fun setDsdMode(mode: Int) {
        val sanitized = sanitizeDsdMode(mode)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_DSD_MODE, sanitized)
            ?.apply()
        _state.update { it.copy(dsdMode = sanitized) }
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeSetDsdMode(sanitized)
        }
        PDLog.d(PDLog.USB_DAC, "CFG_DSD_MODE") { "mode=$sanitized" }
    }

    fun setDsdGainCompensationDb(gainDb: Float) {
        val clamped = gainDb.coerceIn(MIN_DSD_GAIN_DB, MAX_DSD_GAIN_DB)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(KEY_USB_DSD_GAIN_DB, clamped)
            ?.apply()
        _state.update { it.copy(dsdGainCompensationDb = clamped) }
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeSetDsdGainCompensationDb(clamped)
        }
        PDLog.d(PDLog.USB_DAC, "CFG_DSD_GAIN") { "db=$clamped" }
    }

    fun setDitherEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_USB_DITHER_ENABLED, enabled)
            ?.apply()
        _state.update { it.copy(ditherEnabled = enabled) }
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeSetDitherEnabled(enabled)
        }
        PDLog.d(PDLog.USB_DAC, "CFG_DITHER") { "enabled=$enabled" }
    }

    fun setResamplerQuality(quality: Int) {
        val sanitized = sanitizeResamplerQuality(quality)
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_RESAMPLER_QUALITY, sanitized)
            ?.apply()
        _state.update { it.copy(resamplerQuality = sanitized) }
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeSetResamplerQuality(sanitized)
        }
        PDLog.d(PDLog.USB_DAC, "CFG_RESAMPLER") { "quality=$sanitized" }
    }

    fun setKeepAliveAudioEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_USB_KEEP_ALIVE_AUDIO, enabled)
            ?.putBoolean(KEY_USB_KEEP_ALIVE_USER_SET, true)
            ?.apply()
        _state.update { it.copy(keepAliveAudioEnabled = enabled) }
        PDLog.d(PDLog.USB_DAC, "CFG_KEEPALIVE") { "enabled=$enabled" }
    }

    fun isKeepAliveEligible(): Boolean {
        val st = _state.value
        return isDirectReady() && st.keepAliveAudioEnabled
    }

    fun isKeepAliveActiveForCurrentStream(): Boolean {
        val st = _state.value
        return isDirectReady() && st.keepAliveAudioEnabled && st.status == UsbDacStatus.STREAMING
    }

    fun onActivityResumed() {
        activityForeground = true
        controlExecutor.execute {
            scanConnectedDevices(retryBackgroundPermission = true)
        }
    }

    fun onActivityPaused() {
        activityForeground = false
    }

    fun setDirectEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USB_DIRECT_ENABLED, enabled)
            .apply()

        _state.update { it.copy(directEnabled = enabled) }
        controlExecutor.execute {
            if (!enabled) {
                // Release USB interfaces so Android Audio HAL / AudioTrack can reclaim the DAC
                PDLog.i(PDLog.USB_DAC, "USB_DIRECT_MODE", "enabled=false action=release_to_hal")
                detachCurrentDevice(keepDisconnectedState = true)
                if (!_state.value.directEnabled) {
                    scanConnectedDevices()
                    notifyRoutingChanged()
                }
            } else {
                PDLog.i(PDLog.USB_DAC, "USB_DIRECT_MODE", "enabled=true action=scan")
                if (_state.value.directEnabled) {
                    scanConnectedDevices()
                }
            }
        }
    }

    @Synchronized
    fun requestPermissionForCurrentDevice() {
        val device = currentDevice ?: findFirstAudioDevice() ?: return
        userDeniedDeviceId = -1
        requestPermissionForDevice(device)
    }

    private fun requestPermissionForDevice(device: UsbDevice) {
        val mgr = usbManager ?: return
        val ctx = appContext ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            flags
        )
        lastPromptedDeviceId = device.deviceId
        lastPromptWasForeground = activityForeground
        PDLog.i(
            PDLog.USB_DAC,
            "USB_PERM_REQ",
            "dev=${formatDeviceName(device)} id=${device.deviceId} fg=$activityForeground"
        )
        mgr.requestPermission(device, pi)
    }

    @Synchronized
    fun scanConnectedDevices(retryBackgroundPermission: Boolean = false) {
        usbManager ?: return
        val device = findFirstAudioDevice()
        if (device == null) {
            detachCurrentDevice()
            return
        }
        onAudioDeviceFound(device, retryBackgroundPermission)
    }

    private fun findFirstAudioDevice(): UsbDevice? {
        val mgr = usbManager ?: return null
        return mgr.deviceList.values.firstOrNull { isUsbAudioDevice(it) }
    }

    @Synchronized
    private fun onAudioDeviceFound(device: UsbDevice, retryBackgroundPermission: Boolean = false) {
        val mgr = usbManager ?: return
        val alreadyAttached = isSameDeviceAttached(device)
        currentDevice = device

        if (!_state.value.directEnabled) {
            _state.update {
                it.copy(
                    status = UsbDacStatus.READY,
                    deviceName = formatDeviceName(device),
                    devicePath = device.deviceName.orEmpty(),
                    vendorId = device.vendorId,
                    productId = device.productId,
                    errorMessage = null
                )
            }
            return
        }

        if (mgr.hasPermission(device)) {
            if (userDeniedDeviceId == device.deviceId) userDeniedDeviceId = -1
            if (!alreadyAttached) {
                attachDeviceConnection(device)
            }
        } else {
            if (currentConnection != null) {
                // Previous device instance disconnected without detach broadcast; tear down stale handle cleanly
                detachCurrentDevice(keepDisconnectedState = true)
            }
            _state.update {
                it.copy(
                    status = UsbDacStatus.PERMISSION_REQUIRED,
                    deviceName = formatDeviceName(device),
                    devicePath = device.deviceName.orEmpty(),
                    vendorId = device.vendorId,
                    productId = device.productId,
                    errorMessage = if (device.deviceId == userDeniedDeviceId) "USB permission denied" else null
                )
            }
            val isNewDeviceInstance = device.deviceId != lastPromptedDeviceId
            val shouldRetryInForeground = retryBackgroundPermission &&
                activityForeground &&
                !lastPromptWasForeground &&
                device.deviceId != userDeniedDeviceId
            if (isNewDeviceInstance || shouldRetryInForeground) {
                requestPermissionForDevice(device)
            }
        }
    }

    private fun isSameDeviceAttached(device: UsbDevice): Boolean {
        return currentConnection != null &&
            connectedDeviceId == device.deviceId &&
            _state.value.vendorId == device.vendorId &&
            _state.value.productId == device.productId &&
            (_state.value.status == UsbDacStatus.READY ||
                _state.value.status == UsbDacStatus.PAUSED ||
                _state.value.status == UsbDacStatus.STREAMING)
    }

    @Synchronized
    private fun attachDeviceConnection(device: UsbDevice, notifyRouting: Boolean = true) {
        if (!UsbDacNative.isLibraryLoaded) {
            PDLog.e(PDLog.USB_DAC, "DAC_ATTACH_ERR", "reason=native_lib_not_loaded dev=${formatDeviceName(device)}")
            _state.update {
                it.copy(
                    status = UsbDacStatus.ERROR,
                    deviceName = formatDeviceName(device),
                    devicePath = device.deviceName.orEmpty(),
                    errorMessage = "Native USB DAC library not loaded"
                )
            }
            return
        }

        val mgr = usbManager ?: return
        detachCurrentDevice(keepDisconnectedState = true)

        val conn = mgr.openDevice(device)
        if (conn == null) {
            PDLog.e(PDLog.USB_DAC, "DAC_ATTACH_ERR", "reason=open_device_null dev=${formatDeviceName(device)}")
            _state.update {
                it.copy(
                    status = UsbDacStatus.ERROR,
                    deviceName = formatDeviceName(device),
                    devicePath = device.deviceName.orEmpty(),
                    errorMessage = "Failed to open USB device connection"
                )
            }
            return
        }

        val rawDescriptors = conn.rawDescriptors
        if (rawDescriptors == null || rawDescriptors.isEmpty()) {
            conn.close()
            PDLog.e(PDLog.USB_DAC, "DAC_ATTACH_ERR", "reason=empty_descriptors dev=${formatDeviceName(device)}")
            _state.update {
                it.copy(
                    status = UsbDacStatus.ERROR,
                    deviceName = formatDeviceName(device),
                    devicePath = device.deviceName.orEmpty(),
                    errorMessage = "Empty USB descriptors"
                )
            }
            return
        }

        // Force-claim AudioControl & AudioStreaming interfaces at the Android USB Host layer
        // to detach kernel snd-usb-audio before handing the file descriptor to native usbdevfs.
        claimAudioInterfacesForce(device, conn)

        val devicePath = device.deviceName.orEmpty()
        val attached = UsbDacNative.nativeAttach(conn.fileDescriptor, devicePath, rawDescriptors)
        AppLogManager.drainNativeLogs()
        if (!attached) {
            releaseClaimedUsbInterfaces(conn)
            conn.close()
            PDLog.e(PDLog.USB_DAC, "DAC_ATTACH_ERR", "reason=unsupported_uac descLen=${rawDescriptors.size}")
            _state.update {
                it.copy(
                    status = UsbDacStatus.ERROR,
                    deviceName = formatDeviceName(device),
                    devicePath = devicePath,
                    errorMessage = "Unsupported UAC audio descriptors"
                )
            }
            return
        }

        currentDevice = device
        currentConnection = conn
        connectedDeviceId = device.deviceId

        syncDspPreferencesToNative()

        val uacVer = UsbDacNative.nativeGetUacVersion()
        val rates = UsbDacNative.nativeGetSupportedSampleRates().toList()
        val depths = UsbDacNative.nativeGetSupportedBitDepths().toList()
        val maxChannels = UsbDacNative.nativeGetMaxSupportedChannels().coerceAtLeast(1)
        val supportsNativeDsd = UsbDacNative.nativeSupportsNativeDsd()
        val supportsDop = UsbDacNative.nativeSupportsDop()
        val maxDsdRate = UsbDacNative.nativeGetMaxDsdRate()
        val hasHwVol = UsbDacNative.nativeHasHardwareVolume()
        val volRange = UsbDacNative.nativeGetHardwareVolumeRangeDb256()
        val volMin = volRange.getOrElse(0) { 0 }
        val volMax = volRange.getOrElse(1) { 0 }
        val volRes = volRange.getOrElse(2) { 256 }
        val swVolBypass = UsbDacNative.nativeIsSoftwareVolumeBypassed()

        PDLog.i(
            PDLog.USB_DAC,
            "DAC_READY",
            "dev=${formatDeviceName(device)} vidPid=${formatVidPid(device)} path=$devicePath " +
                "uac=0x${uacVer.toString(16)} hwVol=$hasHwVol[$volMin..$volMax/$volRes] " +
                "maxCh=$maxChannels nativeDsd=$supportsNativeDsd dop=$supportsDop maxDsd=$maxDsdRate " +
                "rates=$rates depths=$depths fd=${conn.fileDescriptor}"
        )

        val (safeVolumeStep, wasSafeAdjusted) = applyFirstPermissionSafeVolumeIfNeeded(device)
        val adjustedTick = if (wasSafeAdjusted) System.nanoTime() else 0L

        lastOpenedInputSampleRate = 0
        lastOpenedInputBitDepth = 0
        lastOpenedInputChannels = 0
        lastOpenedInputEncoding = 0

        var shouldApplyMute = false
        _state.update { prev ->
            shouldApplyMute = prev.isMuted
            prev.copy(
                status = UsbDacStatus.READY,
                deviceName = formatDeviceName(device),
                devicePath = devicePath,
                vendorId = device.vendorId,
                productId = device.productId,
                uacVersion = uacVer,
                supportedSampleRates = rates,
                supportedBitDepths = depths,
                maxSupportedChannels = maxChannels,
                supportsNativeDsd = supportsNativeDsd,
                supportsDop = supportsDop,
                maxDsdRate = maxDsdRate,
                hasHardwareVolume = hasHwVol,
                hardwareVolumeMinDb256 = volMin,
                hardwareVolumeMaxDb256 = volMax,
                hardwareVolumeResDb256 = volRes,
                softwareVolumeBypassed = swVolBypass,
                inputSampleRate = 0,
                inputBitDepth = 0,
                inputChannels = 0,
                inputEncoding = 0,
                activeSampleRate = 0,
                activeBitDepth = 0,
                activeChannels = 0,
                activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE,
                activeDspFlags = UsbDacNative.DSP_FLAG_NONE,
                clockSyncMode = UsbDacNative.SYNC_MODE_NONE,
                feedbackLocked = false,
                clockDriftPpmX10 = 0,
                underrunCount = 0L,
                stallCount = 0L,
                maxStallMs = 0L,
                volumeStep = safeVolumeStep,
                volumeHudTick = if (wasSafeAdjusted) adjustedTick else prev.volumeHudTick,
                errorMessage = null
            )
        }

        applyStoredVolumeToDac()
        if (shouldApplyMute) {
            controlExecutor.execute {
                if (isDirectReady()) {
                    UsbDacNative.nativeSetMuted(true)
                }
            }
        }
        if (notifyRouting) {
            notifyRoutingChanged()
        }
    }

    @Synchronized
    fun detachCurrentDevice(keepDisconnectedState: Boolean = false) {
        lastOpenedInputSampleRate = 0
        lastOpenedInputBitDepth = 0
        lastOpenedInputChannels = 0
        lastOpenedInputEncoding = 0
        cancelPauseIdleClose()
        cancelPostStreamVolumeSync()
        stopTelemetryHeartbeat()
        closePerformanceHintSession()
        releaseStreamingWakeLock()
        volumeDispatchChannel.tryReceive()

        val wasConnected = currentConnection != null
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeDetach()
            AppLogManager.drainNativeLogs()
        }

        currentConnection?.let { conn ->
            releaseClaimedUsbInterfaces(conn)
            runCatching { conn.close() }
        }
        claimedUsbInterfaces.clear()
        currentConnection = null
        connectedDeviceId = -1

        if (!keepDisconnectedState) {
            currentDevice = null
            lastPromptedDeviceId = -1
            lastPromptWasForeground = false
            userDeniedDeviceId = -1
            _state.update { prev ->
                UsbDacState(
                    status = UsbDacStatus.DISCONNECTED,
                    directEnabled = prev.directEnabled,
                    warmUpMs = prev.warmUpMs,
                    idleTimeoutSec = prev.idleTimeoutSec,
                    volumeStep = prev.volumeStep,
                    maxVolumeSteps = prev.maxVolumeSteps,
                    isMuted = prev.isMuted,
                    dsdMode = prev.dsdMode,
                    dsdGainCompensationDb = prev.dsdGainCompensationDb,
                    ditherEnabled = prev.ditherEnabled,
                    resamplerQuality = prev.resamplerQuality,
                    wakeLockHeld = false,
                    keepAliveAudioEnabled = prev.keepAliveAudioEnabled
                )
            }
            if (wasConnected) {
                notifyRoutingChanged()
            }
        }
    }

    // ═══ Listeners ═══
    fun addRoutingListener(listener: () -> Unit) {
        routingListeners.addIfAbsent(listener)
    }

    fun removeRoutingListener(listener: () -> Unit) {
        routingListeners.remove(listener)
    }

    fun addPermissionGrantedListener(listener: () -> Unit) {
        permissionGrantedListeners.addIfAbsent(listener)
    }

    fun removePermissionGrantedListener(listener: () -> Unit) {
        permissionGrantedListeners.remove(listener)
    }

    fun isAwaitingPermission(): Boolean {
        val st = _state.value
        return st.directEnabled && st.status == UsbDacStatus.PERMISSION_REQUIRED
    }

    private fun notifyRoutingChanged() {
        mainHandler.post {
            routingListeners.forEach { it.invoke() }
        }
    }

    private fun notifyPermissionGrantedThenRouting() {
        val task = Runnable {
            // Notify pause/reset listeners BEFORE routingListeners so ExoPlayer pauses cleanly
            // before onAudioCapabilitiesChanged triggers a sink reconfiguration.
            permissionGrantedListeners.forEach { it.invoke() }
            routingListeners.forEach { it.invoke() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }

    // ═══ Hardware & 64-Bit Software Volume Control ═══
    fun setMaxVolumeSteps(steps: Int) {
        val validSteps = sanitizeVolumeStepScale(steps)
        var oldMax = DEFAULT_VOLUME_STEPS
        var oldStep = 0
        var scaledStep = 0
        _state.update { cur ->
            oldMax = cur.maxVolumeSteps.coerceAtLeast(1)
            oldStep = cur.volumeStep.coerceIn(0, oldMax)
            scaledStep = ((oldStep.toFloat() / oldMax.toFloat()) * validSteps)
                .let { kotlin.math.round(it).toInt() }
                .coerceIn(0, validSteps)
            cur.copy(
                maxVolumeSteps = validSteps,
                volumeStep = scaledStep
            )
        }

        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_MAX_VOLUME_STEPS, validSteps)
            ?.putInt(KEY_USB_VOLUME_STEP, scaledStep)
            ?.apply()

        PDLog.d(PDLog.USB_DAC, "VOL_SCALE") { "oldMax=$oldMax newMax=$validSteps step=$oldStep->$scaledStep" }
        applyStoredVolumeToDac()
    }

    fun stepVolume(delta: Int, showHud: Boolean = true, source: String = "in_app") {
        setVolumeStep(_state.value.volumeStep + delta, showHud = showHud, source = source)
    }

    fun setVolumeStep(step: Int, showHud: Boolean = false, source: String = "ui") {
        var maxSteps = DEFAULT_VOLUME_STEPS
        var prev = 0
        var clamped = 0
        var unmutedByStep = false
        val newHudTick = if (showHud) System.nanoTime() else 0L
        // Update UI StateFlow atomically for responsive slider feedback before throttled USB dispatch
        _state.update { cur ->
            maxSteps = cur.maxVolumeSteps.coerceAtLeast(1)
            prev = cur.volumeStep
            clamped = step.coerceIn(0, maxSteps)
            unmutedByStep = clamped > 0 && cur.isMuted
            cur.copy(
                volumeStep = clamped,
                maxVolumeSteps = maxSteps,
                isMuted = if (unmutedByStep) false else cur.isMuted,
                volumeHudTick = if (showHud) newHudTick else cur.volumeHudTick
            )
        }
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_USB_VOLUME_STEP, clamped)
            ?.apply()
        if (clamped != prev) {
            scheduleCoalescedVolumeLog(prev, clamped, maxSteps, source)
        }

        if (unmutedByStep) {
            controlExecutor.execute {
                if (isDirectReady()) {
                    UsbDacNative.nativeSetMuted(false)
                }
            }
        }
        applyStoredVolumeToDac()
    }

    fun setMuted(muted: Boolean) {
        var changed = false
        _state.update { cur ->
            if (cur.isMuted == muted) {
                changed = false
                cur
            } else {
                changed = true
                cur.copy(isMuted = muted)
            }
        }
        if (!changed) return
        controlExecutor.execute {
            if (isDirectReady()) {
                UsbDacNative.nativeSetMuted(muted)
                refreshVolumeAndDspTelemetry()
            }
        }
        PDLog.d(PDLog.USB_DAC, "VOL_MUTE") { "muted=$muted" }
    }

    fun applyStoredVolumeToDac() {
        val normalized = normalizedVolumeFraction() ?: return
        ensureVolumeDispatchPipeline()
        volumeDispatchChannel.trySend(normalized)
    }

    private fun ensureVolumeDispatchPipeline() {
        if (!volumePipelineStarted.compareAndSet(false, true)) return
        volumeScope.launch {
            for (target in volumeDispatchChannel) {
                if (isDirectReady()) {
                    UsbDacNative.nativeSetVolume(target)
                    refreshVolumeAndDspTelemetry()
                }
                // Conflate intermediate slider ticks during this 25 ms pacing window so Full-Speed
                // UAC 1.0 L/R Feature Unit transfers never back up and ping-pong audibly.
                delay(VOLUME_DISPATCH_THROTTLE_MS)
            }
        }
    }

    private fun applyStoredVolumeToDacAsync() {
        if (!isDirectReady()) return
        controlExecutor.execute {
            volumeDispatchChannel.tryReceive()
            val normalized = normalizedVolumeFraction() ?: return@execute
            if (isDirectReady()) {
                UsbDacNative.nativeSetVolume(normalized)
                refreshVolumeAndDspTelemetry()
            }
        }
    }

    private fun schedulePostStreamVolumeSync() {
        cancelPostStreamVolumeSync()
        val warmUp = _state.value.warmUpMs
        // Re-apply UAC volume/unmute after DAC PLL/I2S clock locks (still inside warm-up silence when enabled)
        val delayMs = if (warmUp > 0) {
            minOf(POST_STREAM_VOLUME_SYNC_DELAY_MS, (warmUp / 2).toLong().coerceAtLeast(POST_STREAM_VOLUME_SYNC_MIN_DELAY_MS))
        } else {
            POST_STREAM_VOLUME_SYNC_DELAY_MS
        }
        postStartVolumeSyncFuture = controlExecutor.schedule(
            {
                val normalized = normalizedVolumeFraction() ?: return@schedule
                if (_state.value.status == UsbDacStatus.STREAMING && isDirectReady()) {
                    UsbDacNative.nativeSetVolume(normalized)
                    refreshVolumeAndDspTelemetry()
                    AppLogManager.drainNativeLogs()
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelPostStreamVolumeSync() {
        runCatching { postStartVolumeSyncFuture?.cancel(false) }
        postStartVolumeSyncFuture = null
    }

    private fun schedulePauseIdleClose() {
        cancelPauseIdleClose()
        val timeoutSec = _state.value.idleTimeoutSec
        val delayMs = if (timeoutSec <= 0) IDLE_TIMEOUT_OFF_GRACE_MS else timeoutSec * 1000L
        pauseIdleCloseFuture = controlExecutor.schedule(
            {
                synchronized(this) {
                    if (_state.value.status == UsbDacStatus.PAUSED && isDirectReady()) {
                        PDLog.i(
                            PDLog.USB_DAC,
                            "HOT_STANDBY_TIMEOUT",
                            "timeoutSec=$timeoutSec delayMs=$delayMs action=close_alt0"
                        )
                        closeStream()
                    }
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    /**
     * Immediately expires Hot-Standby on long/permanent interruptions (such as permanent Audio Focus
     * loss or player stop) so the DAC drops to `alt=0` and releases `PARTIAL_WAKE_LOCK` right away.
     */
    @Synchronized
    fun expireHotStandbyNow(reason: String) {
        if (!isDirectReady()) return
        val status = _state.value.status
        if (status != UsbDacStatus.PAUSED && status != UsbDacStatus.STREAMING) return
        cancelPauseIdleClose()
        pauseIdleCloseFuture = controlExecutor.schedule(
            {
                synchronized(this) {
                    if (_state.value.status == UsbDacStatus.PAUSED && isDirectReady()) {
                        PDLog.i(PDLog.USB_DAC, "HOT_STANDBY_EXPIRE", "reason=$reason action=close_alt0")
                        closeStream()
                    }
                }
            },
            EXPIRE_STANDBY_DEBOUNCE_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelPauseIdleClose() {
        runCatching { pauseIdleCloseFuture?.cancel(false) }
        pauseIdleCloseFuture = null
    }

    // ═══ Stream Control, Telemetry & WakeLock ═══
    fun isDirectReady(): Boolean {
        val st = _state.value
        return st.directEnabled &&
            UsbDacNative.isLibraryLoaded &&
            currentConnection != null &&
            !UsbDacNative.nativeIsDeviceDisconnected() &&
            (st.status == UsbDacStatus.READY ||
                st.status == UsbDacStatus.PAUSED ||
                st.status == UsbDacStatus.STREAMING)
    }

    /**
     * Immediately transitions state to `DISCONNECTED`, stops the dead USB stream, and schedules
     * full device detachment off the playback thread when the native RT ISO loop detects
     * `ENODEV`/`POLLHUP` ahead of Android's delayed `ACTION_USB_DEVICE_DETACHED` broadcast.
     */
    @Synchronized
    fun handleNativeDeviceDisconnected() {
        markDisconnectedStateLocked("native_iso_disconnect")
        closeStream()
        controlExecutor.execute {
            detachCurrentDevice()
        }
    }

    private fun markDisconnectedStateLocked(reason: String) {
        var transitioned = false
        _state.update { prev ->
            if (prev.status == UsbDacStatus.DISCONNECTED) {
                transitioned = false
                prev
            } else {
                transitioned = true
                UsbDacState(
                    status = UsbDacStatus.DISCONNECTED,
                    directEnabled = prev.directEnabled,
                    warmUpMs = prev.warmUpMs,
                    idleTimeoutSec = prev.idleTimeoutSec,
                    volumeStep = prev.volumeStep,
                    maxVolumeSteps = prev.maxVolumeSteps,
                    isMuted = prev.isMuted,
                    dsdMode = prev.dsdMode,
                    dsdGainCompensationDb = prev.dsdGainCompensationDb,
                    ditherEnabled = prev.ditherEnabled,
                    resamplerQuality = prev.resamplerQuality,
                    wakeLockHeld = false,
                    keepAliveAudioEnabled = prev.keepAliveAudioEnabled
                )
            }
        }
        if (transitioned) {
            PDLog.w(PDLog.USB_DAC, "DAC_DISCONNECTED", "reason=$reason status=DISCONNECTED")
        }
    }

    fun supportsSampleRate(sampleRate: Int): Boolean {
        val rates = _state.value.supportedSampleRates
        return rates.isEmpty() || rates.contains(sampleRate)
    }

    fun supportsNativeDsd(): Boolean = _state.value.supportsNativeDsd

    fun supportsDop(): Boolean = _state.value.supportsDop

    fun getMaxDsdRate(): Int = _state.value.maxDsdRate

    fun openStream(
        sampleRate: Int,
        channels: Int,
        preferredBitDepth: Int,
        inputEncoding: Int = UsbDacNative.ENCODING_PCM_16BIT
    ): Boolean {
        var shouldSyncVolume = false
        val ok = synchronized(this) {
            if (!isDirectReady()) return false
            cancelPauseIdleClose()
            val prevStatus = _state.value.status
            val prevRate = _state.value.activeSampleRate
            val prevBits = _state.value.activeBitDepth
            val prevMode = _state.value.activeOutputMode

            val opened = UsbDacNative.nativeOpenStream(
                sampleRate,
                channels,
                preferredBitDepth,
                inputEncoding,
                _state.value.warmUpMs
            )
            AppLogManager.drainNativeLogs()
            if (opened) {
                val activeRate = UsbDacNative.nativeGetActiveSampleRate()
                val activeBits = UsbDacNative.nativeGetActiveBitDepth()
                val activeCh = UsbDacNative.nativeGetActiveChannels()
                val activeMode = UsbDacNative.nativeGetActiveOutputMode()
                val activeDsp = UsbDacNative.nativeGetActiveDspFlags()
                val syncMode = UsbDacNative.nativeGetClockSyncMode()
                val fbLocked = UsbDacNative.nativeIsFeedbackLocked()
                val driftPpmX10 = UsbDacNative.nativeGetClockDriftPpmX10()
                val underruns = UsbDacNative.nativeGetUnderrunCount()
                val stalls = UsbDacNative.nativeGetStallCount()
                val maxStallMs = UsbDacNative.nativeGetMaxStallMs()
                val swVolBypass = UsbDacNative.nativeIsSoftwareVolumeBypassed()
                val effInputBits = effectiveInputBitDepth(inputEncoding, preferredBitDepth)

                lastOpenedInputSampleRate = sampleRate
                lastOpenedInputBitDepth = effInputBits
                lastOpenedInputChannels = channels
                lastOpenedInputEncoding = inputEncoding

                _state.update {
                    it.copy(
                        inputSampleRate = sampleRate,
                        inputBitDepth = effInputBits,
                        inputChannels = channels,
                        inputEncoding = inputEncoding,
                        activeSampleRate = activeRate,
                        activeBitDepth = activeBits,
                        activeChannels = activeCh,
                        activeOutputMode = activeMode,
                        activeDspFlags = activeDsp,
                        clockSyncMode = syncMode,
                        feedbackLocked = fbLocked,
                        clockDriftPpmX10 = driftPpmX10,
                        underrunCount = underruns,
                        stallCount = stalls,
                        maxStallMs = maxStallMs,
                        softwareVolumeBypassed = swVolBypass
                    )
                }
                val sameActiveFormat =
                    prevStatus == UsbDacStatus.STREAMING &&
                        activeRate == prevRate &&
                        activeBits == prevBits &&
                        activeMode == prevMode
                shouldSyncVolume = !sameActiveFormat
                PDLog.i(
                    PDLog.USB_DAC,
                    "STREAM_OPEN",
                    "req=${sampleRate}Hz/${preferredBitDepth}b/${channels}ch/enc=$inputEncoding " +
                        "wire=${activeRate}Hz/${activeBits}b/${activeCh}ch mode=${outputModeLabel(activeMode)} " +
                        "dsp=0x${activeDsp.toString(16)} warmMs=${_state.value.warmUpMs}"
                )
                if (prevStatus == UsbDacStatus.STREAMING) {
                    if (!sameActiveFormat) {
                        PDLog.d(PDLog.USB_DAC, "STREAM_STATE") {
                            "state=STREAMING rate=$activeRate bits=$activeBits mode=${outputModeLabel(activeMode)}"
                        }
                    }
                    acquireStreamingWakeLock()
                    if (!sameActiveFormat) {
                        schedulePostStreamVolumeSync()
                    }
                    startTelemetryHeartbeat()
                    updatePerformanceHintSession(Process.myTid())
                }
            } else {
                PDLog.w(
                    PDLog.USB_DAC,
                    "STREAM_OPEN_FAIL",
                    "req=${sampleRate}Hz/${preferredBitDepth}b/${channels}ch/enc=$inputEncoding"
                )
                // Never leave PARTIAL_WAKE_LOCK leaked after cancelPauseIdleClose() when openStream fails.
                val curStatus = _state.value.status
                if (curStatus == UsbDacStatus.PAUSED || curStatus == UsbDacStatus.STREAMING) {
                    val nativeDisconnected = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsDeviceDisconnected()
                    val streamStillOpen = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeGetActiveSampleRate() > 0
                    if (nativeDisconnected) {
                        cancelPostStreamVolumeSync()
                        stopTelemetryHeartbeat()
                        closePerformanceHintSession()
                        releaseStreamingWakeLock()
                        markDisconnectedStateLocked("open_stream_disconnected")
                    } else if (streamStillOpen && isDirectReady()) {
                        if (curStatus == UsbDacStatus.PAUSED) {
                            schedulePauseIdleClose()
                        }
                    } else {
                        cancelPostStreamVolumeSync()
                        stopTelemetryHeartbeat()
                        closePerformanceHintSession()
                        releaseStreamingWakeLock()
                        if (currentConnection != null) {
                            _state.update {
                                it.copy(
                                    status = UsbDacStatus.READY,
                                    inputSampleRate = 0,
                                    inputBitDepth = 0,
                                    inputChannels = 0,
                                    inputEncoding = 0,
                                    activeSampleRate = 0,
                                    activeBitDepth = 0,
                                    activeChannels = 0,
                                    activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE,
                                    activeDspFlags = UsbDacNative.DSP_FLAG_NONE,
                                    clockSyncMode = UsbDacNative.SYNC_MODE_NONE,
                                    feedbackLocked = false,
                                    clockDriftPpmX10 = 0,
                                    underrunCount = 0L,
                                    stallCount = 0L,
                                    maxStallMs = 0L
                                )
                            }
                        }
                    }
                }
            }
            opened
        }
        if (ok && shouldSyncVolume) {
            // Dispatch Feature Unit volume asynchronously on fresh open or format switch so
            // gapless same-format reuse avoids redundant I2C control transfers.
            applyStoredVolumeToDacAsync()
        }
        return ok
    }

    @Synchronized
    fun setStreaming(streaming: Boolean) {
        if (!streaming) {
            cancelPostStreamVolumeSync()
            stopTelemetryHeartbeat()
            closePerformanceHintSession()
            val cur = _state.value
            val nativeDisconnected = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsDeviceDisconnected()
            if (nativeDisconnected) {
                releaseStreamingWakeLock()
                markDisconnectedStateLocked("pause_on_disconnected")
                return
            }
            if (currentConnection != null && cur.directEnabled) {
                val swVolBypass = if (UsbDacNative.isLibraryLoaded) {
                    UsbDacNative.nativeIsSoftwareVolumeBypassed()
                } else {
                    cur.softwareVolumeBypassed
                }
                val streamStillOpen = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeGetActiveSampleRate() > 0
                if (cur.status == UsbDacStatus.STREAMING && streamStillOpen) {
                    // True Hot-Standby: keep ISO thread, PLL lock, active format badge, and
                    // PARTIAL_WAKE_LOCK alive across seeks and short pauses until Idle Timeout
                    // closes the stream back to alt=0 (or permanent Audio Focus loss expires it).
                    _state.update {
                        it.copy(
                            status = UsbDacStatus.PAUSED,
                            softwareVolumeBypassed = swVolBypass
                        )
                    }
                    PDLog.i(
                        PDLog.USB_DAC,
                        "STREAM_STATE",
                        "state=PAUSED_STANDBY rate=${cur.activeSampleRate} bits=${cur.activeBitDepth} " +
                            "mode=${outputModeLabel(cur.activeOutputMode)} idleSec=${cur.idleTimeoutSec}"
                    )
                    AppLogManager.drainNativeLogs()
                    schedulePauseIdleClose()
                    return
                } else if (cur.status == UsbDacStatus.STREAMING) {
                    cancelPauseIdleClose()
                    releaseStreamingWakeLock()
                    _state.update {
                        it.copy(
                            status = UsbDacStatus.READY,
                            inputSampleRate = 0,
                            inputBitDepth = 0,
                            inputChannels = 0,
                            inputEncoding = 0,
                            activeSampleRate = 0,
                            activeBitDepth = 0,
                            activeChannels = 0,
                            activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE,
                            activeDspFlags = UsbDacNative.DSP_FLAG_NONE,
                            clockSyncMode = UsbDacNative.SYNC_MODE_NONE,
                            feedbackLocked = false,
                            clockDriftPpmX10 = 0,
                            underrunCount = 0L,
                            stallCount = 0L,
                            maxStallMs = 0L,
                            softwareVolumeBypassed = swVolBypass
                        )
                    }
                    PDLog.i(PDLog.USB_DAC, "STREAM_STATE", "state=READY")
                    AppLogManager.drainNativeLogs()
                    return
                }
            }
            if (cur.status != UsbDacStatus.PAUSED) {
                releaseStreamingWakeLock()
            }
            return
        }

        // Guard under the same monitor as attach/detach so an unplug racing with play() can never
        // overwrite DISCONNECTED back to STREAMING or leak PARTIAL_WAKE_LOCK.
        if (!isDirectReady()) return
        cancelPauseIdleClose()
        val inRate = lastOpenedInputSampleRate
        val inBits = lastOpenedInputBitDepth
        val inCh = lastOpenedInputChannels
        val inEnc = lastOpenedInputEncoding
        val activeRate = UsbDacNative.nativeGetActiveSampleRate()
        val activeBits = UsbDacNative.nativeGetActiveBitDepth()
        val activeCh = UsbDacNative.nativeGetActiveChannels()
        val activeMode = UsbDacNative.nativeGetActiveOutputMode()
        val activeDsp = UsbDacNative.nativeGetActiveDspFlags()
        val syncMode = UsbDacNative.nativeGetClockSyncMode()
        val fbLocked = UsbDacNative.nativeIsFeedbackLocked()
        val driftPpmX10 = UsbDacNative.nativeGetClockDriftPpmX10()
        val underruns = UsbDacNative.nativeGetUnderrunCount()
        val stalls = UsbDacNative.nativeGetStallCount()
        val maxStall = UsbDacNative.nativeGetMaxStallMs()
        val swVolBypass = UsbDacNative.nativeIsSoftwareVolumeBypassed()

        var stateChanged = false
        _state.update { cur ->
            val changed = cur.status != UsbDacStatus.STREAMING ||
                cur.inputSampleRate != inRate ||
                cur.activeSampleRate != activeRate ||
                cur.activeBitDepth != activeBits ||
                cur.activeOutputMode != activeMode ||
                cur.activeDspFlags != activeDsp
            stateChanged = changed
            if (changed) {
                cur.copy(
                    status = UsbDacStatus.STREAMING,
                    inputSampleRate = inRate,
                    inputBitDepth = inBits,
                    inputChannels = inCh,
                    inputEncoding = inEnc,
                    activeSampleRate = activeRate,
                    activeBitDepth = activeBits,
                    activeChannels = activeCh,
                    activeOutputMode = activeMode,
                    activeDspFlags = activeDsp,
                    clockSyncMode = syncMode,
                    feedbackLocked = fbLocked,
                    clockDriftPpmX10 = driftPpmX10,
                    underrunCount = underruns,
                    stallCount = stalls,
                    maxStallMs = maxStall,
                    softwareVolumeBypassed = swVolBypass
                )
            } else {
                cur
            }
        }
        if (stateChanged) {
            PDLog.i(
                PDLog.USB_DAC,
                "STREAM_STATE",
                "state=STREAMING rate=$activeRate bits=$activeBits mode=${outputModeLabel(activeMode)}"
            )
            AppLogManager.drainNativeLogs()
        }

        acquireStreamingWakeLock()
        if (stateChanged) {
            schedulePostStreamVolumeSync()
        }
        startTelemetryHeartbeat()
        updatePerformanceHintSession(Process.myTid())
    }

    @Synchronized
    fun closeStream() {
        lastOpenedInputSampleRate = 0
        lastOpenedInputBitDepth = 0
        lastOpenedInputChannels = 0
        lastOpenedInputEncoding = 0
        cancelPauseIdleClose()
        cancelPostStreamVolumeSync()
        stopTelemetryHeartbeat()
        closePerformanceHintSession()
        releaseStreamingWakeLock()

        val nativeDisconnected = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsDeviceDisconnected()
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeCloseStream()
            AppLogManager.drainNativeLogs()
        }
        if (nativeDisconnected) {
            // Never report READY after ENODEV/ESHUTDOWN; force DISCONNECTED and schedule full detach.
            markDisconnectedStateLocked("close_stream_disconnected")
            if (currentConnection != null) {
                controlExecutor.execute {
                    detachCurrentDevice()
                }
            }
        } else if (currentConnection != null &&
            (_state.value.status == UsbDacStatus.STREAMING || _state.value.status == UsbDacStatus.PAUSED)
        ) {
            _state.update {
                it.copy(
                    status = UsbDacStatus.READY,
                    inputSampleRate = 0,
                    inputBitDepth = 0,
                    inputChannels = 0,
                    inputEncoding = 0,
                    activeSampleRate = 0,
                    activeBitDepth = 0,
                    activeChannels = 0,
                    activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE,
                    activeDspFlags = UsbDacNative.DSP_FLAG_NONE,
                    clockSyncMode = UsbDacNative.SYNC_MODE_NONE,
                    feedbackLocked = false,
                    clockDriftPpmX10 = 0,
                    underrunCount = 0L,
                    stallCount = 0L,
                    maxStallMs = 0L
                )
            }
            PDLog.i(PDLog.USB_DAC, "STREAM_CLOSE", "status=READY")
        }
    }

    fun refreshStreamTelemetry() {
        if (!isDirectReady()) return
        if (UsbDacNative.nativeHasPendingLogs()) {
            AppLogManager.drainNativeLogs()
        }
        val activeRate = UsbDacNative.nativeGetActiveSampleRate()
        val activeBits = UsbDacNative.nativeGetActiveBitDepth()
        val activeCh = UsbDacNative.nativeGetActiveChannels()
        val activeMode = UsbDacNative.nativeGetActiveOutputMode()
        val activeDsp = UsbDacNative.nativeGetActiveDspFlags()
        val syncMode = UsbDacNative.nativeGetClockSyncMode()
        val fbLocked = UsbDacNative.nativeIsFeedbackLocked()
        val driftPpmX10 = UsbDacNative.nativeGetClockDriftPpmX10()
        val underruns = UsbDacNative.nativeGetUnderrunCount()
        val stalls = UsbDacNative.nativeGetStallCount()
        val maxStall = UsbDacNative.nativeGetMaxStallMs()
        val swVolBypass = UsbDacNative.nativeIsSoftwareVolumeBypassed()

        _state.update { cur ->
            if (cur.activeSampleRate != activeRate ||
                cur.activeBitDepth != activeBits ||
                cur.activeChannels != activeCh ||
                cur.activeOutputMode != activeMode ||
                cur.activeDspFlags != activeDsp ||
                cur.clockSyncMode != syncMode ||
                cur.feedbackLocked != fbLocked ||
                cur.clockDriftPpmX10 != driftPpmX10 ||
                cur.underrunCount != underruns ||
                cur.stallCount != stalls ||
                cur.maxStallMs != maxStall ||
                cur.softwareVolumeBypassed != swVolBypass
            ) {
                cur.copy(
                    activeSampleRate = activeRate,
                    activeBitDepth = activeBits,
                    activeChannels = activeCh,
                    activeOutputMode = activeMode,
                    activeDspFlags = activeDsp,
                    clockSyncMode = syncMode,
                    feedbackLocked = fbLocked,
                    clockDriftPpmX10 = driftPpmX10,
                    underrunCount = underruns,
                    stallCount = stalls,
                    maxStallMs = maxStall,
                    softwareVolumeBypassed = swVolBypass
                )
            } else {
                cur
            }
        }
    }

    private fun refreshVolumeAndDspTelemetry() {
        if (!isDirectReady()) return
        val swVolBypass = UsbDacNative.nativeIsSoftwareVolumeBypassed()
        val activeDsp = UsbDacNative.nativeGetActiveDspFlags()
        val activeRate = UsbDacNative.nativeGetActiveSampleRate()
        val activeBits = UsbDacNative.nativeGetActiveBitDepth()
        val activeCh = UsbDacNative.nativeGetActiveChannels()
        val activeMode = UsbDacNative.nativeGetActiveOutputMode()
        _state.update { cur ->
            val hasOpenStreamState = cur.status == UsbDacStatus.STREAMING || cur.status == UsbDacStatus.PAUSED
            if (cur.softwareVolumeBypassed != swVolBypass ||
                cur.activeDspFlags != activeDsp ||
                (hasOpenStreamState && (
                    cur.activeSampleRate != activeRate ||
                        cur.activeBitDepth != activeBits ||
                        cur.activeChannels != activeCh ||
                        cur.activeOutputMode != activeMode
                    ))
            ) {
                cur.copy(
                    softwareVolumeBypassed = swVolBypass,
                    activeDspFlags = activeDsp,
                    activeSampleRate = if (hasOpenStreamState) activeRate else cur.activeSampleRate,
                    activeBitDepth = if (hasOpenStreamState) activeBits else cur.activeBitDepth,
                    activeChannels = if (hasOpenStreamState) activeCh else cur.activeChannels,
                    activeOutputMode = if (hasOpenStreamState) activeMode else cur.activeOutputMode
                )
            } else {
                cur
            }
        }
    }

    private fun startTelemetryHeartbeat() {
        if (telemetryHeartbeat != null && telemetryHeartbeat?.isCancelled == false) return
        telemetryHeartbeat = controlExecutor.scheduleWithFixedDelay(
            {
                if (_state.value.status == UsbDacStatus.STREAMING && isDirectReady()) {
                    refreshStreamTelemetry()
                }
            },
            TELEMETRY_POLL_INTERVAL_MS,
            TELEMETRY_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopTelemetryHeartbeat() {
        runCatching { telemetryHeartbeat?.cancel(false) }
        telemetryHeartbeat = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireStreamingWakeLock() {
        val wl = streamingWakeLock ?: return
        try {
            if (!wl.isHeld) {
                // Non-timed hold matches active audio stream lifecycle; always released on pause/close/detach
                wl.acquire()
                _state.update { it.copy(wakeLockHeld = true) }
                PDLog.d(PDLog.USB_DAC, "WAKELOCK_ACQUIRE")
            }
        } catch (t: Throwable) {
            PDLog.w(PDLog.USB_DAC, "WAKELOCK_ACQUIRE_ERR", "err=${t.message}")
        }
    }

    private fun releaseStreamingWakeLock() {
        val wl = streamingWakeLock
        try {
            if (wl != null && wl.isHeld) {
                wl.release()
                PDLog.d(PDLog.USB_DAC, "WAKELOCK_RELEASE")
            }
        } catch (t: Throwable) {
            PDLog.w(PDLog.USB_DAC, "WAKELOCK_RELEASE_ERR", "err=${t.message}")
        }
        _state.update { cur ->
            if (cur.wakeLockHeld) cur.copy(wakeLockHeld = false) else cur
        }
    }

    // ═══ Performance Hints & Async Log Drain ═══
    /**
     * Offloads native log ring draining onto `controlExecutor` so hot audio producer paths
     * (such as `UsbDacAudioSink.maybeReportUnderrun()`) never stall on JNI allocations or log locks.
     */
    fun drainNativeLogsAsync() {
        controlExecutor.execute {
            runCatching { AppLogManager.drainNativeLogs() }
        }
    }

    /**
     * Releases any stale ADPF session when falling back to Android Audio HAL, because user-space
     * cannot measure real AudioFlinger thread work durations and reporting fake constants degrades
     * PowerHAL scheduling.
     */
    fun setHalHiResPerfHint(active: Boolean, playbackTid: Int = 0, sampleRate: Int = 0) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!active && !halHiResHintActive) return
        controlExecutor.execute {
            runCatching {
                halHiResHintActive = false
                halHiResHintTid = 0
                if (_state.value.status != UsbDacStatus.STREAMING) {
                    closePerformanceHintSessionLocked()
                }
            }
        }
    }

    private fun updatePerformanceHintSession(callerTid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ctx = appContext ?: return
        controlExecutor.execute {
            try {
                halHiResHintActive = false
                halHiResHintTid = 0
                closePerformanceHintSessionLocked()
                if (_state.value.status != UsbDacStatus.STREAMING) return@execute
                schedulePerformanceHintCreationLocked(ctx, callerTid, attempt = 0)
            } catch (t: Throwable) {
                PDLog.w(PDLog.USB_DAC, "ADPF_SESSION_ERR", "err=${t.message}")
            }
        }
    }

    private fun schedulePerformanceHintCreationLocked(ctx: Context, callerTid: Int, attempt: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (_state.value.status != UsbDacStatus.STREAMING || !UsbDacNative.isLibraryLoaded) {
            closePerformanceHintSessionLocked()
            return
        }
        val usbTid = UsbDacNative.nativeGetStreamingTid()
        if (usbTid <= 0) {
            if (attempt + 1 < STREAM_TID_POLL_ATTEMPTS) {
                runCatching { perfHintRetryFuture?.cancel(false) }
                perfHintRetryFuture = controlExecutor.schedule(
                    {
                        runCatching {
                            schedulePerformanceHintCreationLocked(ctx, callerTid, attempt + 1)
                        }
                    },
                    STREAM_TID_POLL_STEP_MS,
                    TimeUnit.MILLISECONDS
                )
            }
            return
        }
        perfHintRetryFuture = null
        if (_state.value.status != UsbDacStatus.STREAMING) {
            closePerformanceHintSessionLocked()
            return
        }
        val tids = if (usbTid != callerTid) {
            intArrayOf(callerTid, usbTid)
        } else {
            intArrayOf(usbTid)
        }
        closePerformanceHintSessionLocked()
        val isHighRateOrDsd =
            UsbDacNative.nativeGetActiveSampleRate() > 48_000 ||
                UsbDacNative.nativeGetActiveBitDepth() >= 24 ||
                UsbDacNative.nativeGetActiveOutputMode() == UsbDacNative.OUTPUT_MODE_NATIVE_DSD ||
                UsbDacNative.nativeGetActiveOutputMode() == UsbDacNative.OUTPUT_MODE_DOP ||
                UsbDacNative.nativeGetActiveOutputMode() == UsbDacNative.OUTPUT_MODE_DSD_TO_PCM
        val targetNs = when {
            !UsbDacNative.nativeIsHighSpeed() -> PERF_HINT_MAX_TARGET_NS
            isHighRateOrDsd -> PERF_HINT_MIN_TARGET_NS
            else -> PERF_HINT_DEFAULT_TARGET_NS
        }.coerceIn(PERF_HINT_MIN_TARGET_NS, PERF_HINT_MAX_TARGET_NS)
        val phm = ctx.getSystemService(PerformanceHintManager::class.java)
        val session = phm?.createHintSession(tids, targetNs)
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Explicitly prefer big-core performance over power efficiency while streaming
            runCatching { session.setPreferPowerEfficiency(false) }
        }
        perfHintSession = session
        if (session != null) {
            var idleTicks = 0
            // Report real measured RT work duration every 50 ms (inside PowerHAL's staleness window)
            perfHintHeartbeat = controlExecutor.scheduleWithFixedDelay(
                {
                    runCatching {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            _state.value.status != UsbDacStatus.STREAMING
                        ) {
                            closePerformanceHintSessionLocked()
                            return@scheduleWithFixedDelay
                        }
                        val measuredNs = UsbDacNative.nativeGetLastWorkDurationNs()
                        if (measuredNs > 0L) {
                            idleTicks = 0
                            (perfHintSession as? PerformanceHintManager.Session)
                                ?.reportActualWorkDuration(measuredNs)
                        } else {
                            idleTicks++
                            if (idleTicks >= PERF_HINT_MAX_IDLE_TICKS) {
                                closePerformanceHintSessionLocked()
                            }
                        }
                    }
                },
                PERF_HINT_REPORT_INTERVAL_MS,
                PERF_HINT_REPORT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        } else if (!adpfUnsupportedLogged) {
            adpfUnsupportedLogged = true
            PDLog.i(PDLog.USB_DAC, "ADPF_UNAVAILABLE", "reason=create_hint_session_null")
        }
    }

    private fun closePerformanceHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        controlExecutor.execute {
            closePerformanceHintSessionLocked()
        }
    }

    private fun closePerformanceHintSessionLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { perfHintRetryFuture?.cancel(false) }
        perfHintRetryFuture = null
        runCatching { perfHintHeartbeat?.cancel(false) }
        perfHintHeartbeat = null
        runCatching { (perfHintSession as? PerformanceHintManager.Session)?.close() }
        perfHintSession = null
        halHiResHintActive = false
        halHiResHintTid = 0
    }

    // ═══ Private Helpers ═══
    private fun claimAudioInterfacesForce(device: UsbDevice, conn: UsbDeviceConnection) {
        claimedUsbInterfaces.clear()
        val seenInterfaceIds = HashSet<Int>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            if (iface.interfaceSubclass != USB_SUBCLASS_AUDIOCONTROL &&
                iface.interfaceSubclass != USB_SUBCLASS_AUDIOSTREAMING
            ) {
                continue
            }
            if (!seenInterfaceIds.add(iface.id)) continue

            val claimed = runCatching { conn.claimInterface(iface, true) }.getOrDefault(false)
            if (claimed) {
                claimedUsbInterfaces.add(iface)
                PDLog.d(PDLog.USB_DAC, "USB_IFACE_CLAIM") { "id=${iface.id} sub=${iface.interfaceSubclass} force=true" }
            } else {
                PDLog.w(PDLog.USB_DAC, "USB_IFACE_CLAIM_FAIL", "id=${iface.id} sub=${iface.interfaceSubclass} force=true")
            }
        }
    }

    private fun releaseClaimedUsbInterfaces(conn: UsbDeviceConnection) {
        for (iface in claimedUsbInterfaces.asReversed()) {
            runCatching { conn.releaseInterface(iface) }
        }
        claimedUsbInterfaces.clear()
    }

    private fun syncDspPreferencesToNative() {
        if (!UsbDacNative.isLibraryLoaded) return
        val st = _state.value
        UsbDacNative.nativeSetDsdMode(st.dsdMode)
        UsbDacNative.nativeSetDsdGainCompensationDb(st.dsdGainCompensationDb)
        UsbDacNative.nativeSetDitherEnabled(st.ditherEnabled)
        UsbDacNative.nativeSetResamplerQuality(st.resamplerQuality)
    }

    private fun scheduleCoalescedVolumeLog(prev: Int, next: Int, maxSteps: Int, source: String) {
        synchronized(volumeLogLock) {
            if (pendingVolLogStart == null) {
                pendingVolLogStart = prev
            }
            pendingVolLogEnd = next
            pendingVolLogMax = maxSteps
            pendingVolLogSource = source
        }
        mainHandler.removeCallbacks(flushVolumeLogRunnable)
        mainHandler.postDelayed(flushVolumeLogRunnable, VOLUME_LOG_COALESCE_MS)
    }

    private fun flushCoalescedVolumeLog() {
        val start: Int
        val end: Int
        val maxSteps: Int
        val source: String
        synchronized(volumeLogLock) {
            start = pendingVolLogStart ?: return
            end = pendingVolLogEnd
            maxSteps = pendingVolLogMax
            source = pendingVolLogSource
            pendingVolLogStart = null
        }
        if (start != end) {
            val modeLabel = if (_state.value.hasHardwareVolume) "HW" else "SW64"
            PDLog.d(PDLog.USB_DAC, "VOL_CHANGE") { "mode=$modeLabel step=$start->$end/$maxSteps src=$source" }
        }
    }

    private fun sanitizeVolumeStepScale(steps: Int): Int {
        return steps.takeIf { it in SUPPORTED_VOLUME_STEP_SCALES } ?: DEFAULT_VOLUME_STEPS
    }

    private fun sanitizeDsdMode(mode: Int): Int = when (mode) {
        UsbDacNative.DSD_MODE_AUTO,
        UsbDacNative.DSD_MODE_NATIVE,
        UsbDacNative.DSD_MODE_DOP,
        UsbDacNative.DSD_MODE_PCM_DECIMATE -> mode
        else -> UsbDacNative.DSD_MODE_AUTO
    }

    private fun sanitizeResamplerQuality(quality: Int): Int = when (quality) {
        UsbDacNative.RESAMPLER_QUALITY_MEDIUM,
        UsbDacNative.RESAMPLER_QUALITY_HIGH,
        UsbDacNative.RESAMPLER_QUALITY_ULTRA -> quality
        else -> UsbDacNative.RESAMPLER_QUALITY_HIGH
    }

    private fun safeCapStepForScale(maxSteps: Int): Int {
        val safeMax = maxSteps.coerceAtLeast(1)
        return kotlin.math.round(safeMax * SAFE_MAX_VOLUME_FRACTION).toInt().coerceIn(1, safeMax)
    }

    private fun readSystemStreamVolumeStep(maxSteps: Int): Int {
        val am = audioManager ?: return safeCapStepForScale(maxSteps)
        val maxSys = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxSys)
        return ((curSys.toFloat() / maxSys.toFloat()) * maxSteps).toInt().coerceIn(0, maxSteps)
    }

    /**
     * Ensures the first Bit-Perfect session for this USB DAC (VID:PID) starts at or below 30%.
     * Leaves lower volumes unchanged; clamps higher levels down once per DAC and persists its key.
     */
    private fun applyFirstPermissionSafeVolumeIfNeeded(device: UsbDevice): Pair<Int, Boolean> {
        val maxSteps = _state.value.maxVolumeSteps.coerceAtLeast(1)
        val safeCap = safeCapStepForScale(maxSteps)
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentStep = _state.value.volumeStep.coerceIn(0, maxSteps)
        val deviceKey = formatVidPid(device)
        if (prefs == null) {
            return currentStep to false
        }
        val checkedSet = prefs.getStringSet(KEY_USB_SAFE_CHECKED_DEVICES, null) ?: emptySet()
        if (prefs.getBoolean(KEY_USB_SAFE_INIT_APPLIED, false) && checkedSet.contains(deviceKey)) {
            return currentStep to false
        }

        val candidateStep = if (prefs.contains(KEY_USB_VOLUME_STEP)) {
            prefs.getInt(KEY_USB_VOLUME_STEP, currentStep).coerceIn(0, maxSteps)
        } else {
            readSystemStreamVolumeStep(maxSteps)
        }
        val safeStep = candidateStep.coerceAtMost(safeCap)
        val wasAdjusted = safeStep < candidateStep
        val updatedCheckedSet = HashSet(checkedSet).apply { add(deviceKey) }

        prefs.edit()
            .putInt(KEY_USB_VOLUME_STEP, safeStep)
            .putBoolean(KEY_USB_SAFE_INIT_APPLIED, true)
            .putStringSet(KEY_USB_SAFE_CHECKED_DEVICES, updatedCheckedSet)
            .apply()

        if (wasAdjusted) {
            PDLog.i(
                PDLog.USB_DAC,
                "SAFE_VOL_CLAMP",
                "dev=$deviceKey from=$candidateStep/$maxSteps to=$safeStep/$maxSteps"
            )
        } else {
            PDLog.d(PDLog.USB_DAC, "SAFE_VOL_OK") { "dev=$deviceKey step=$safeStep/$maxSteps cap=$safeCap" }
        }
        return safeStep to wasAdjusted
    }

    private fun normalizedVolumeFraction(): Float? {
        if (!isDirectReady()) return null
        val maxSteps = _state.value.maxVolumeSteps.coerceAtLeast(1)
        return _state.value.volumeStep.toFloat() / maxSteps.toFloat()
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private fun formatVidPid(device: UsbDevice): String {
        return String.format(Locale.US, "%04x:%04x", device.vendorId, device.productId)
    }

    private fun formatDeviceName(device: UsbDevice): String {
        val mfg = device.manufacturerName?.trim().orEmpty()
        val prod = device.productName?.trim().orEmpty()
        return when {
            mfg.isNotEmpty() && prod.isNotEmpty() && !prod.startsWith(mfg, ignoreCase = true) -> "$mfg $prod"
            prod.isNotEmpty() -> prod
            mfg.isNotEmpty() -> mfg
            else -> "USB Audio DAC (${String.format(Locale.US, "%04X:%04X", device.vendorId, device.productId)})"
        }
    }

    private fun effectiveInputBitDepth(inputEncoding: Int, preferredBitDepth: Int): Int = when (inputEncoding) {
        UsbDacNative.ENCODING_PCM_16BIT -> 16
        UsbDacNative.ENCODING_PCM_24BIT -> 24
        UsbDacNative.ENCODING_PCM_32BIT,
        UsbDacNative.ENCODING_PCM_FLOAT -> 32
        UsbDacNative.ENCODING_DSD_U8,
        UsbDacNative.ENCODING_DSD_U32_BE,
        UsbDacNative.ENCODING_DSD_U32_LE -> 1
        else -> preferredBitDepth.coerceAtLeast(16)
    }

    private fun outputModeLabel(mode: Int): String = when (mode) {
        UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT -> "PCM_BIT_PERFECT"
        UsbDacNative.OUTPUT_MODE_PCM_DSP -> "PCM_DSP"
        UsbDacNative.OUTPUT_MODE_DOP -> "DOP"
        UsbDacNative.OUTPUT_MODE_NATIVE_DSD -> "NATIVE_DSD"
        UsbDacNative.OUTPUT_MODE_DSD_TO_PCM -> "DSD_TO_PCM"
        else -> "INACTIVE"
    }

    @Suppress("DEPRECATION")
    private fun getParcelableDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}