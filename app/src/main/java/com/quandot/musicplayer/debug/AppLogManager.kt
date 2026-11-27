/**
 * AppLogManager — Ring-buffered diagnostics logger, native UAC log drainer, and report exporter.
 *
 * @remarks
 * Combines a bounded in-memory `ArrayDeque` (`MAX_MEMORY_LINES`) with dual-file log rotation
 * (`MAX_LOG_FILE_BYTES`, capping disk footprint at <= 4 MiB) dispatched onto a single daemon
 * executor so audio-thread and UI callers never block on disk I/O. `flushSync` and the uncaught
 * exception handler drain the native C++ USB driver ring buffer and block briefly on a barrier
 * task so fatal crashes and HAL traces survive process termination.
 *
 * @see UsbDacNative
 * @see UsbDacManager
 * @see LocaleUtils
 */
package com.quandot.musicplayer.debug

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Internal modules
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.audio.UsbDacNative
import com.quandot.musicplayer.audio.UsbDacState
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.util.LocaleUtils

// ═══ Models ═══
data class DebugLogStats(
    val enabled: Boolean = true,
    val fileSizeBytes: Long = 0L,
    val lineCount: Int = 0,
    val lastEventTimeMs: Long = 0L
)

data class PlaybackDiagnosticsSnapshot(
    val playbackStateLabel: String = "IDLE",
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val sourceSpecs: String = "",
    val exoAudioFormat: String = "none",
    val audioSessionId: Int = 0,
    val wakeLockHeld: Boolean = false,
    val mediaSessionVolume: String = "N/A",
    val activeAudioSinkMode: String = "Unknown",
    val activeUsbDirectDetail: String = "Inactive",
    val lastAudioTrackConfig: String = "None",
    val bitPerfectMixerStatus: String = "Not configured",
    val lastPlayerError: String = "None"
)

private data class LiveUsbTelemetrySnapshot(
    val dac: UsbDacState,
    val directReady: Boolean,
    val uacVersion: Int,
    val outputMode: Int,
    val dspFlags: Int,
    val activeSampleRate: Int,
    val activeBitDepth: Int,
    val activeChannels: Int,
    val hasHardwareVolume: Boolean,
    val hardwareVolumeMinDb256: Int,
    val hardwareVolumeMaxDb256: Int,
    val hardwareVolumeResDb256: Int,
    val softwareVolumeBypassed: Boolean,
    val clockSyncMode: Int,
    val feedbackLocked: Boolean,
    val clockDriftPpmX10: Int,
    val framesWritten: Long,
    val underrunCount: Long,
    val stallCount: Long,
    val maxStallMs: Long,
    val jitterCount: Long,
    val maxUrbGapMs: Long,
    val streamingTid: Int,
    val ringAvailBytes: Int,
    val ringFreeBytes: Int
)

object AppLogManager {

    // ═══ Constants & State ═══
    private const val PREFS_NAME = "music_playback_prefs"
    private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
    private const val MAX_LOG_FILE_BYTES = 2L * 1024L * 1024L // 2 MB per file (prev + current <= 4 MB)
    private const val MAX_MEMORY_LINES = 250
    private const val LOG_DIR_NAME = "logs"
    private const val EXPORT_DIR_NAME = "export"
    private const val CURRENT_LOG_FILE = "playdepth_debug.log"
    private const val PREV_LOG_FILE = "playdepth_debug_prev.log"
    private const val DEFAULT_FLUSH_TIMEOUT_MS = 600L
    private const val CRASH_FLUSH_TIMEOUT_MS = 800L
    private const val SUMMARY_BUILDER_CAPACITY = 4096
    private const val REPORT_BUILDER_CAPACITY = 64 * 1024
    private const val LOGCAT_QUERY_LINES = 1500
    private const val LOGCAT_TAIL_LINES = 200
    private const val LOGCAT_WAIT_SECONDS = 2L
    private const val SUMMARY_PREVIEW_LIMIT = 60
    private const val MAX_EXPORT_LOG_LINES = 1200
    private const val DUPLICATE_COALESCE_WINDOW_MS = 5_000L

    private val IGNORED_LOGCAT_TAGS = listOf(
        " gralloc4:",
        " BLASTBufferQueue:",
        " BufferQueueConsumer:",
        " OpenGLRenderer:",
        " MIUIInput:",
        " HandWritingStubImpl:",
        "SkJpegCodec::onGetPixels",
        " PipelineWatcher:",
        " BufferPoolAccessor2.0:",
        " CCodecBufferChannel: elapsed:",
        " libPerfCtl:",
        "[onConstructor]: an error ged handle",
        " Choreographer: Frame time is ",
        " VRI[",
        " DecorView["
    )

    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PlayDepthLogger").apply { isDaemon = true }
    }
    private val fileLock = Any()
    private val memoryLock = Any()
    private val recentLines = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val lineTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }
    private val fileStampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
    }

    private val _stats = MutableStateFlow(DebugLogStats())
    val stats: StateFlow<DebugLogStats> = _stats.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var loggingEnabled = true

    @Volatile
    private var crashHandlerInstalled = false

    @Volatile
    var serviceSnapshotProvider: (() -> PlaybackDiagnosticsSnapshot)? = null

    @Volatile
    private var sinkModeSummary: String = "Uninitialized"

    @Volatile
    private var sinkDirectDetail: String = "Inactive"

    @Volatile
    private var lastAudioTrackSummary: String = "None"

    @Volatile
    private var lastMixerAttrSummary: String = "Not checked"

    @Volatile
    private var lastPlaybackErrSummary: String = "None"

    private var cachedLineCount = 0
    private var lastEventKey: String? = null
    private var lastEventTimeMs: Long = 0L
    private var lastSuppressedLevel: String = "I"
    private var lastSuppressedTag: String = ""
    private var suppressedDuplicateCount: Int = 0

    // ═══ Initialization & Configuration ═══
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val ctx = context.applicationContext
            appContext = ctx
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loggingEnabled = prefs.getBoolean(KEY_DEBUG_LOGGING_ENABLED, true)
            ensureLogDir(ctx)
            installCrashHandler()
            isInitialized = true
            refreshStatsAsync()
            PDLog.i(
                PDLog.UI,
                "LOGGER_INIT",
                "enabled=$loggingEnabled pid=${Process.myPid()} app=${getAppVersionLabel(ctx)} sdk=${Build.VERSION.SDK_INT}"
            )
        }
    }

    fun isEnabled(): Boolean = loggingEnabled

    fun setEnabled(enabled: Boolean) {
        loggingEnabled = enabled
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_DEBUG_LOGGING_ENABLED, enabled)
            ?.apply()
        _stats.value = _stats.value.copy(enabled = enabled)
        if (enabled) {
            PDLog.i(PDLog.UI, "LOGGER_STATE", "enabled=true")
        } else {
            val msg = "LOGGER_STATE | enabled=false"
            Log.i(PDLog.UI, msg)
            appendForcedLine("I", PDLog.UI, msg)
        }
    }

    // ═══ State Tracking & Structured Sink API ═══
    fun updateSinkState(mode: String, directDetail: String) {
        sinkModeSummary = mode
        sinkDirectDetail = directDetail
    }

    fun updateAudioTrackInfo(summary: String) {
        lastAudioTrackSummary = summary
    }

    fun updateBitPerfectMixerStatus(summary: String) {
        lastMixerAttrSummary = summary
    }

    fun updateLastPlayerError(summary: String) {
        lastPlaybackErrSummary = summary
    }

    fun getCurrentSinkSummary(): Pair<String, String> = sinkModeSummary to sinkDirectDetail
    fun getLastAudioTrackSummary(): String = lastAudioTrackSummary
    fun getLastMixerAttrSummary(): String = lastMixerAttrSummary
    fun getLastPlayerErrorSummary(): String = lastPlaybackErrSummary

    @PublishedApi
    internal fun recordStructured(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        if (!loggingEnabled) return
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable).trimEnd()}"
        } else {
            message
        }
        appendForcedLine(level, tag, fullMessage)
    }

    fun drainNativeLogs() {
        if (!loggingEnabled || !UsbDacNative.isLibraryLoaded) return
        val nativeLines = runCatching { UsbDacNative.nativeDrainLogs() }.getOrNull() ?: return
        if (nativeLines.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        synchronized(memoryLock) {
            for (line in nativeLines) {
                if (recentLines.size >= MAX_MEMORY_LINES) recentLines.removeFirst()
                recentLines.addLast(line)
            }
        }
        val joined = nativeLines.joinToString(separator = "\n", postfix = "\n")
        writerExecutor.execute {
            appendRawToDisk(joined, nowMs)
        }
    }

    // ═══ Persistence & Export ═══
    fun flushSync(timeoutMs: Long = 600L) {
        flushPendingDuplicateSummary()
        drainNativeLogs()
        runCatching {
            writerExecutor.submit { /* barrier */ }.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    fun clearLogs() {
        synchronized(memoryLock) {
            recentLines.clear()
            lastEventKey = null
            lastEventTimeMs = 0L
            suppressedDuplicateCount = 0
        }
        if (UsbDacNative.isLibraryLoaded) {
            runCatching { UsbDacNative.nativeDrainLogs() }
        }
        val ctx = appContext ?: return
        writerExecutor.execute {
            synchronized(fileLock) {
                val dir = ensureLogDir(ctx)
                File(dir, CURRENT_LOG_FILE).delete()
                File(dir, PREV_LOG_FILE).delete()
                File(dir, EXPORT_DIR_NAME).deleteRecursively()
                cachedLineCount = 0
                _stats.value = DebugLogStats(
                    enabled = loggingEnabled,
                    fileSizeBytes = 0L,
                    lineCount = 0,
                    lastEventTimeMs = System.currentTimeMillis()
                )
            }
            if (loggingEnabled) {
                recordStructured("I", PDLog.UI, "LOGS_CLEARED")
            }
        }
    }

    fun getRecentLinesPreview(limit: Int = 80): String {
        drainNativeLogs()
        val lines = synchronized(memoryLock) { recentLines.toList() }
        val tail = if (lines.size > limit) lines.takeLast(limit) else lines
        return if (tail.isEmpty()) "(No events recorded yet)" else tail.joinToString("\n")
    }

    fun buildPreviewSummary(context: Context): String {
        val sb = StringBuilder(SUMMARY_BUILDER_CAPACITY)
        appendDeviceAndOutputOverview(context, sb)
        sb.appendLine()
        sb.appendLine("=== RECENT EVENTS ===")
        sb.appendLine(getRecentLinesPreview(SUMMARY_PREVIEW_LIMIT))
        return sb.toString()
    }

    fun defaultExportFileName(): String {
        val stamp = fileStampFormat.get()?.format(Date()) ?: System.currentTimeMillis().toString()
        return "PlayDepth_Debug_$stamp.txt"
    }

    fun buildFullDiagnosticReport(context: Context): String {
        flushSync()
        val sb = StringBuilder(REPORT_BUILDER_CAPACITY)
        appendHeaderAndSettings(context, sb)
        sb.appendLine()
        appendDeviceAndOutputOverview(context, sb)
        sb.appendLine()
        appendUsbAndNativeSection(context, sb)
        sb.appendLine()
        appendPlaybackSessionSection(sb)
        sb.appendLine()
        appendOwnProcessLogcat(sb)
        sb.appendLine()
        appendStoredChronologicalLogs(context, sb)
        return sb.toString()
    }

    fun writeReportToExportFile(context: Context): File {
        val report = buildFullDiagnosticReport(context)
        val exportDir = File(ensureLogDir(context), EXPORT_DIR_NAME).apply { mkdirs() }
        exportDir.listFiles()?.forEach { old ->
            runCatching { old.delete() }
        }
        val outFile = File(exportDir, defaultExportFileName())
        outFile.writeText(report, Charsets.UTF_8)
        return outFile
    }

    fun createShareLogIntent(context: Context): Intent {
        val outFile = writeReportToExportFile(context)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val version = getAppVersionLabel(context)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PlayDepth Debug Log ($version - ${Build.MODEL})")
            putExtra(
                Intent.EXTRA_TEXT,
                "PlayDepth diagnostic & audio output log (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ═══ Private Storage & Crash Handler ═══
    private fun appendForcedLine(level: String, tag: String, message: String) {
        val nowMs = System.currentTimeMillis()
        val tid = Process.myTid()
        val eventKey = "$level/$tag:$message"
        var pendingSummaryLine: String? = null

        synchronized(memoryLock) {
            if (level != "F" &&
                eventKey == lastEventKey &&
                nowMs - lastEventTimeMs <= DUPLICATE_COALESCE_WINDOW_MS
            ) {
                suppressedDuplicateCount++
                lastEventTimeMs = nowMs
                return
            }
            if (suppressedDuplicateCount > 0) {
                val summaryTs = lineTimeFormat.get()?.format(Date(lastEventTimeMs)) ?: lastEventTimeMs.toString()
                pendingSummaryLine = "$summaryTs [T$tid] $lastSuppressedLevel/$lastSuppressedTag: " +
                    "(previous message repeated $suppressedDuplicateCount more times)"
                suppressedDuplicateCount = 0
            }
            lastEventKey = eventKey
            lastEventTimeMs = nowMs
            lastSuppressedLevel = level
            lastSuppressedTag = tag
        }

        val ts = lineTimeFormat.get()?.format(Date(nowMs)) ?: nowMs.toString()
        val formatted = buildString {
            val lines = message.lines()
            lines.forEachIndexed { idx, part ->
                if (idx > 0) append('\n')
                append(ts).append(" [T").append(tid).append("] ")
                append(level).append('/').append(tag).append(": ").append(part)
            }
        }
        val payload = if (pendingSummaryLine != null) {
            "$pendingSummaryLine\n$formatted"
        } else {
            formatted
        }
        synchronized(memoryLock) {
            if (pendingSummaryLine != null) {
                if (recentLines.size >= MAX_MEMORY_LINES) recentLines.removeFirst()
                recentLines.addLast(pendingSummaryLine!!)
            }
            if (recentLines.size >= MAX_MEMORY_LINES) recentLines.removeFirst()
            recentLines.addLast(formatted)
        }
        writerExecutor.execute {
            appendRawToDisk("$payload\n", nowMs)
        }
    }

    private fun flushPendingDuplicateSummary() {
        val summaryLine: String
        val nowMs = System.currentTimeMillis()
        val tid = Process.myTid()
        synchronized(memoryLock) {
            if (suppressedDuplicateCount <= 0) return
            val summaryTs = lineTimeFormat.get()?.format(Date(lastEventTimeMs)) ?: lastEventTimeMs.toString()
            summaryLine = "$summaryTs [T$tid] $lastSuppressedLevel/$lastSuppressedTag: " +
                "(previous message repeated $suppressedDuplicateCount more times)"
            suppressedDuplicateCount = 0
            lastEventKey = null
            if (recentLines.size >= MAX_MEMORY_LINES) recentLines.removeFirst()
            recentLines.addLast(summaryLine)
        }
        writerExecutor.execute {
            appendRawToDisk("$summaryLine\n", nowMs)
        }
    }

    private fun appendRawToDisk(text: String, eventTimeMs: Long) {
        val ctx = appContext ?: return
        synchronized(fileLock) {
            try {
                val dir = ensureLogDir(ctx)
                val currentFile = File(dir, CURRENT_LOG_FILE)
                val bytes = text.toByteArray(Charsets.UTF_8)
                var rotated = false
                if (currentFile.exists() && currentFile.length() + bytes.size > MAX_LOG_FILE_BYTES) {
                    val prevFile = File(dir, PREV_LOG_FILE)
                    if (prevFile.exists()) prevFile.delete()
                    currentFile.renameTo(prevFile)
                    rotated = true
                }
                FileOutputStream(currentFile, true).use { fos ->
                    fos.write(bytes)
                }
                val addedLines = text.count { it == '\n' }.coerceAtLeast(1)
                cachedLineCount = if (rotated || cachedLineCount == 0) {
                    computeCombinedLineCountLocked(dir)
                } else {
                    cachedLineCount + addedLines
                }
                val totalBytes = currentFile.length() + File(dir, PREV_LOG_FILE).let {
                    if (it.exists()) it.length() else 0L
                }
                _stats.value = DebugLogStats(
                    enabled = loggingEnabled,
                    fileSizeBytes = totalBytes,
                    lineCount = cachedLineCount,
                    lastEventTimeMs = eventTimeMs
                )
            } catch (t: Throwable) {
                Log.w(PDLog.UI, "LOG_WRITE_FAIL | err=${t.message}")
            }
        }
    }

    private fun refreshStatsAsync() {
        val ctx = appContext ?: return
        writerExecutor.execute {
            synchronized(fileLock) {
                val dir = ensureLogDir(ctx)
                val current = File(dir, CURRENT_LOG_FILE)
                val prev = File(dir, PREV_LOG_FILE)
                val totalBytes = (if (current.exists()) current.length() else 0L) +
                    (if (prev.exists()) prev.length() else 0L)
                cachedLineCount = computeCombinedLineCountLocked(dir)
                if (recentLines.isEmpty() && current.exists()) {
                    runCatching {
                        val tail = current.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_LINES)
                        synchronized(memoryLock) {
                            recentLines.clear()
                            recentLines.addAll(tail)
                        }
                    }
                }
                _stats.value = DebugLogStats(
                    enabled = loggingEnabled,
                    fileSizeBytes = totalBytes,
                    lineCount = cachedLineCount,
                    lastEventTimeMs = current.lastModified()
                )
            }
        }
    }

    private fun computeCombinedLineCountLocked(dir: File): Int {
        var total = 0
        for (name in listOf(PREV_LOG_FILE, CURRENT_LOG_FILE)) {
            val f = File(dir, name)
            if (f.exists()) {
                runCatching {
                    f.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (reader.readLine() != null) total++
                    }
                }
            }
        }
        return total
    }

    private fun ensureLogDir(context: Context): File {
        return File(context.filesDir, LOG_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendForcedLine(
                    "F",
                    PDLog.UI,
                    "FATAL_CRASH | thread=${thread.name} tid=${thread.id}\n" +
                        Log.getStackTraceString(throwable).trimEnd()
                )
                flushSync(CRASH_FLUSH_TIMEOUT_MS)
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // ═══ Diagnostic Report Builders ═══
    private fun appendHeaderAndSettings(context: Context, sb: StringBuilder) {
        val now = Date()
        val tz = TimeZone.getDefault()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dacState = UsbDacManager.state.value
        val curStats = _stats.value

        sb.appendLine("============================================================")
        sb.appendLine("PLAYDEPTH DIAGNOSTICS & AUDIO LOG REPORT")
        sb.appendLine("============================================================")
        sb.appendLine("Generated: ${lineTimeFormat.get()?.format(now)} (${tz.id}, UTC${formatUtcOffset(tz.rawOffset)})")
        sb.appendLine("UptimeElapsedRealtime: ${SystemClock.elapsedRealtime() / 1000L}s | PID=${Process.myPid()}")
        sb.appendLine("App: ${context.packageName} | Version=${getAppVersionLabel(context)}")
        sb.appendLine("DebugLogger: enabled=${curStats.enabled}, sizeBytes=${curStats.fileSizeBytes}, lines=${curStats.lineCount}")
        sb.appendLine("Settings:")
        val sysLocales = Resources.getSystem().configuration.locales.toLanguageTags()
        sb.appendLine("  language=${LocaleUtils.getResolvedLanguageCode(context)} (system=$sysLocales), theme=${prefs.getString("theme_mode", "system")}, dynamicColor=${prefs.getBoolean("dynamic_color", true)}")
        sb.appendLine(
            "  usbDacDirectEnabled=${dacState.directEnabled}, keepAliveAudio=${dacState.keepAliveAudioEnabled}, " +
                "warmUpMs=${dacState.warmUpMs}, idleTimeoutSec=${dacState.idleTimeoutSec}s, " +
                "volumeStep=${dacState.volumeStep}/${dacState.maxVolumeSteps}, muted=${dacState.isMuted}"
        )
        sb.appendLine(
            "  dsdMode=${dsdModeName(dacState.dsdMode)}, dsdGainCompDb=${formatSignedDb(dacState.dsdGainCompensationDb)}, " +
                "dither=${dacState.ditherEnabled}, resamplerQuality=${resamplerQualityName(dacState.resamplerQuality)}"
        )
        sb.appendLine("  autoPlayHeadset=${prefs.getBoolean("auto_play_headset", false)}, keepScreenOn=${prefs.getBoolean("keep_screen_on_player", false)}")
        sb.appendLine("  minAudioDurationSec=${prefs.getInt("min_audio_duration_sec", 30)}, shuffle=${prefs.getBoolean("is_shuffle", false)}, repeatMode=${prefs.getInt("repeat_mode", 0)}")
    }

    private fun appendDeviceAndOutputOverview(context: Context, sb: StringBuilder) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val usbTelemetry = captureLiveUsbTelemetry()
        val dac = usbTelemetry.dac

        sb.appendLine("=== DEVICE & POWER / OS ===")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (brand=${Build.BRAND}, device=${Build.DEVICE}, board=${Build.BOARD}, hardware=${Build.HARDWARE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), SecurityPatch=${if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "N/A"}")
        sb.appendLine("Build: ${Build.DISPLAY} | Fingerprint=${Build.FINGERPRINT}")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Power: isInteractive=${pm?.isInteractive}, ignoringBatteryOpt=${pm?.isIgnoringBatteryOptimizations(context.packageName)}")

        sb.appendLine()
        sb.appendLine("=== AUDIO ROUTING & OUTPUT DEVICES (SPEAKER / HEADSET / BT / USB) ===")
        sb.appendLine("PlayDepth Active Sink Route: $sinkModeSummary | DirectDetail: $sinkDirectDetail")
        if (usbTelemetry.directReady || dac.status != UsbDacStatus.DISCONNECTED) {
            val modeLabel = outputModeName(usbTelemetry.outputMode)
            val wireSummary = if (usbTelemetry.activeSampleRate > 0) {
                "${usbTelemetry.activeSampleRate}Hz/${usbTelemetry.activeBitDepth}-bit/${usbTelemetry.activeChannels}ch"
            } else {
                "idle"
            }
            val inputSummary = if (dac.inputSampleRate > 0) {
                "${dac.inputSampleRate}Hz/${dac.inputBitDepth}-bit/${dac.inputChannels}ch(${nativeInputEncodingName(dac.inputEncoding)})"
            } else {
                "none"
            }
            val volRoute = if (usbTelemetry.hasHardwareVolume) {
                "HW-UAC (swVolBypass=${usbTelemetry.softwareVolumeBypassed})"
            } else {
                "SW-64bit (swVolBypass=${usbTelemetry.softwareVolumeBypassed})"
            }
            sb.appendLine(
                "USB Direct Transport: status=${dac.status}, dac='${dac.deviceName.ifBlank { "none" }}', " +
                    "protocol=${formatUacVersion(usbTelemetry.uacVersion)}, mode=$modeLabel, " +
                    "in=$inputSummary -> wire=$wireSummary"
            )
            sb.appendLine(
                "USB Direct Pipeline: dspFlags=0x${usbTelemetry.dspFlags.toString(16)} [${formatDspFlags(usbTelemetry.dspFlags)}], " +
                    "volPath=$volRoute, clock=${clockSyncModeName(usbTelemetry.clockSyncMode)} " +
                    "(fbLocked=${usbTelemetry.feedbackLocked}, drift=${formatDriftPpm(usbTelemetry.clockDriftPpmX10)}), " +
                    "underruns=${usbTelemetry.underrunCount}, stalls=${usbTelemetry.stallCount}"
            )
        }
        sb.appendLine("Last AudioTrack Config: $lastAudioTrackSummary")
        sb.appendLine("Bit-Perfect MixerAttrs (API 34+): $lastMixerAttrSummary")

        if (am == null) {
            sb.appendLine("AudioManager unavailable")
            return
        }

        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
        val outRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val outFrames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        sb.appendLine(
            "AudioManager: mode=${audioModeName(am.mode)}, isMusicActive=${am.isMusicActive}, " +
                "STREAM_MUSIC=$curVol/$maxVol (min=$minVol, mute=${am.isStreamMute(AudioManager.STREAM_MUSIC)}), " +
                "halOutputRate=${outRate}Hz, halFramesPerBuf=$outFrames"
        )

        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val activeRouted = am.getAudioDevicesForAttributes(mediaAttrs)
                val routedStr = if (activeRouted.isEmpty()) "none" else activeRouted.joinToString("; ") {
                    "${audioDeviceTypeName(it.type)}(id=${it.id}, name='${it.productName}')"
                }
                val directNote = when {
                    usbTelemetry.directReady && dac.keepAliveAudioEnabled ->
                        " [USB Direct (${outputModeName(usbTelemetry.outputMode)}) bypasses AudioFlinger; SilentAudioKeepAlive pins BUILTIN_SPEAKER to avoid usbdevfs conflict]"
                    usbTelemetry.directReady ->
                        " [USB Direct (${outputModeName(usbTelemetry.outputMode)}) active via usbdevfs, bypassing AudioFlinger]"
                    else -> ""
                }
                sb.appendLine("Android Media Routing (getAudioDevicesForAttributes USAGE_MEDIA): $routedStr$directNote")
            }
        }

        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty()
        sb.appendLine("Connected Audio Outputs (${outputs.size}):")
        for (dev in outputs) {
            val rates = dev.sampleRates.let { if (it.isEmpty()) "any/dynamic" else it.joinToString(",") }
            val encs = dev.encodings.let {
                if (it.isEmpty()) "any" else it.joinToString(",") { e -> audioEncodingName(e) }
            }
            val chs = dev.channelCounts.let { if (it.isEmpty()) "any" else it.joinToString(",") }
            val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.address else ""
            sb.appendLine(
                "  - [id=${dev.id}] ${audioDeviceTypeName(dev.type)} (${dev.type}) | name='${dev.productName}' | " +
                    "rates=[$rates] | ch=[$chs] | enc=[$encs]" +
                    if (addr.isNotBlank()) " | addr=$addr" else ""
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    val supportedMixer = am.getSupportedMixerAttributes(dev)
                    if (supportedMixer.isNotEmpty()) {
                        val bpCount = supportedMixer.count {
                            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                        }
                        val pref = am.getPreferredMixerAttributes(mediaAttrs, dev)
                        val prefStr = pref?.let {
                            "${mixerBehaviorName(it.mixerBehavior)} ${it.format.sampleRate}Hz/${audioEncodingName(it.format.encoding)}"
                        } ?: "none"
                        sb.appendLine("      MixerAttrs(API34+): total=${supportedMixer.size}, bitPerfectSupported=$bpCount, preferred=$prefStr")
                        supportedMixer.take(12).forEach { attr ->
                            sb.appendLine(
                                "        * ${mixerBehaviorName(attr.mixerBehavior)}: " +
                                    "${attr.format.sampleRate}Hz ${audioEncodingName(attr.format.encoding)} chMask=0x${attr.format.channelMask.toString(16)}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun appendUsbAndNativeSection(context: Context, sb: StringBuilder) {
        val telemetry = captureLiveUsbTelemetry()
        val dac = telemetry.dac
        val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager

        sb.appendLine("=== USB DAC & NATIVE UAC DRIVER ===")
        sb.appendLine(
            "UsbDacManager: status=${dac.status}, directReady=${telemetry.directReady}, " +
                "directEnabled=${dac.directEnabled}, device='${dac.deviceName.ifBlank { "none" }}' " +
                "(${formatVidPid(dac.vendorId, dac.productId)}, path=${dac.devicePath.ifBlank { "none" }})"
        )
        sb.appendLine(
            "  Protocol & Capabilities: ${formatUacVersion(telemetry.uacVersion)}, " +
                "maxChannels=${dac.maxSupportedChannels}, nativeDsd=${dac.supportsNativeDsd}, " +
                "dop=${dac.supportsDop}, maxDsd=${if (dac.maxDsdRate > 0) "DSD${dac.maxDsdRate}" else "None"}"
        )
        sb.appendLine(
            "  Supported PCM: rates=${dac.supportedSampleRates}, bitDepths=${dac.supportedBitDepths}"
        )
        val inputDesc = if (dac.inputSampleRate > 0) {
            "${dac.inputSampleRate}Hz/${dac.inputBitDepth}-bit/${dac.inputChannels}ch (${nativeInputEncodingName(dac.inputEncoding)})"
        } else {
            "none"
        }
        val wireDesc = if (telemetry.activeSampleRate > 0) {
            "${telemetry.activeSampleRate}Hz/${telemetry.activeBitDepth}-bit/${telemetry.activeChannels}ch"
        } else {
            "inactive"
        }
        sb.appendLine(
            "  Active Transport: mode=${outputModeName(telemetry.outputMode)}, " +
                "input=$inputDesc -> wire=$wireDesc"
        )
        sb.appendLine(
            "  Active DSP Stages: mask=0x${telemetry.dspFlags.toString(16)} [${formatDspFlags(telemetry.dspFlags)}]"
        )
        val hwRangeDesc = if (telemetry.hasHardwareVolume) {
            String.format(
                Locale.US,
                "%.2f..%.2f dB (res %.2f dB)",
                telemetry.hardwareVolumeMinDb256 / 256.0,
                telemetry.hardwareVolumeMaxDb256 / 256.0,
                telemetry.hardwareVolumeResDb256 / 256.0
            )
        } else {
            "N/A"
        }
        sb.appendLine(
            "  Volume Control: hasHardwareVolume=${telemetry.hasHardwareVolume} ($hwRangeDesc), " +
                "swVolBypass=${telemetry.softwareVolumeBypassed}, " +
                "step=${dac.volumeStep}/${dac.maxVolumeSteps}, muted=${dac.isMuted}"
        )
        sb.appendLine(
            "  Clock Sync & RT Health: mode=${clockSyncModeName(telemetry.clockSyncMode)}, " +
                "fbLocked=${telemetry.feedbackLocked}, fbDrift=${formatDriftPpm(telemetry.clockDriftPpmX10)}, " +
                "framesWritten=${telemetry.framesWritten}, underruns=${telemetry.underrunCount}, " +
                "stalls=${telemetry.stallCount} (max=${telemetry.maxStallMs}ms), " +
                "jitter>=16ms=${telemetry.jitterCount} (maxGap=${telemetry.maxUrbGapMs}ms), " +
                "ring=${telemetry.ringAvailBytes}B/${telemetry.ringAvailBytes + telemetry.ringFreeBytes}B, " +
                "isoTid=${telemetry.streamingTid}"
        )
        sb.appendLine(
            "  DSP/DSD Config & Guard: dsdMode=${dsdModeName(dac.dsdMode)}, " +
                "dsdGainComp=${formatSignedDb(dac.dsdGainCompensationDb)}, dither=${dac.ditherEnabled}, " +
                "resampler=${resamplerQualityName(dac.resamplerQuality)}, warmUpMs=${dac.warmUpMs}, " +
                "idleTimeoutSec=${dac.idleTimeoutSec}s, wakeLock=${dac.wakeLockHeld}, keepAliveAudio=${dac.keepAliveAudioEnabled}, " +
                "error=${dac.errorMessage ?: "none"}"
        )

        val devices = runCatching { usbMgr?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        sb.appendLine("Android UsbManager DeviceList (${devices.size}):")
        if (devices.isEmpty()) {
            sb.appendLine("  (no USB devices visible to UsbManager)")
        } else {
            for (d in devices) {
                val hasPerm = runCatching { usbMgr?.hasPermission(d) == true }.getOrDefault(false)
                val mfg = runCatching { d.manufacturerName }.getOrNull().orEmpty()
                val prod = runCatching { d.productName }.getOrNull().orEmpty()
                sb.appendLine(
                    "  - ${d.deviceName}: VID:PID=${formatVidPid(d.vendorId, d.productId)} " +
                        "mfg='$mfg' prod='$prod' class=${d.deviceClass}:${d.deviceSubclass}:${d.deviceProtocol} " +
                        "hasPermission=$hasPerm interfaces=${d.interfaceCount}"
                )
                for (i in 0 until d.interfaceCount) {
                    val intf = d.getInterface(i)
                    val clsLabel = if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) "AUDIO" else "cls=${intf.interfaceClass}"
                    sb.appendLine(
                        "      iface[${intf.id}] alt=${intf.alternateSetting} $clsLabel " +
                            "sub=${intf.interfaceSubclass} proto=${intf.interfaceProtocol} eps=${intf.endpointCount}"
                    )
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        sb.appendLine(
                            "        ep[$e] addr=0x${ep.address.toString(16)} type=${ep.type} " +
                                "dir=${if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"} " +
                                "maxPkt=${ep.maxPacketSize} interval=${ep.interval}"
                        )
                    }
                }
            }
        }

        sb.appendLine("Native Library Loaded: ${UsbDacNative.isLibraryLoaded}")
        if (UsbDacNative.isLibraryLoaded) {
            val nativeDiag = runCatching { UsbDacNative.nativeGetDiagnosticsSummary() }
                .getOrElse { "Error querying native diagnostics: ${it.message}" }
            sb.appendLine("--- Native UAC Driver Internals ---")
            sb.append(nativeDiag)
            if (!nativeDiag.endsWith('\n')) sb.appendLine()
        }
    }

    private fun appendPlaybackSessionSection(sb: StringBuilder) {
        sb.appendLine("=== PLAYBACK SERVICE & MEDIASESSION ===")
        val snap = runCatching { serviceSnapshotProvider?.invoke() }.getOrNull()
        if (snap == null) {
            sb.appendLine("PlaybackService: not active / unbound")
            sb.appendLine("Last Recorded PlayerError: $lastPlaybackErrSummary")
            return
        }
        sb.appendLine(
            "State=${snap.playbackStateLabel}, playWhenReady=${snap.playWhenReady}, isPlaying=${snap.isPlaying}, " +
                "shuffle=${snap.shuffleEnabled}, repeat=${snap.repeatMode}, audioSessionId=${snap.audioSessionId}, wakeLockHeld=${snap.wakeLockHeld}"
        )
        sb.appendLine("Track: '${snap.currentTitle}' - '${snap.currentArtist}' | Specs='${snap.sourceSpecs}'")
        sb.appendLine("ExoPlayer AudioFormat: ${snap.exoAudioFormat}")
        sb.appendLine("MediaSession Remote Volume: ${snap.mediaSessionVolume}")
        sb.appendLine("Sink Route: ${snap.activeAudioSinkMode} | ${snap.activeUsbDirectDetail}")
        sb.appendLine("AudioTrack Config: ${snap.lastAudioTrackConfig}")
        sb.appendLine("MixerAttributes: ${snap.bitPerfectMixerStatus}")
        sb.appendLine("Last PlayerError: ${snap.lastPlayerError}")
    }

    private fun appendOwnProcessLogcat(sb: StringBuilder) {
        sb.appendLine("=== OWN PROCESS LOGCAT TAIL (AudioTrack / Media3 / CCodec / UsbDac) ===")
        val pid = Process.myPid()
        val result = runCatching {
            val proc = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", LOGCAT_QUERY_LINES.toString(), "--pid=$pid"
            )
                .redirectErrorStream(true)
                .start()
            val lines = proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence()
                    .filter { line -> IGNORED_LOGCAT_TAGS.none { line.contains(it) } }
                    .toList()
            }
            proc.waitFor(LOGCAT_WAIT_SECONDS, TimeUnit.SECONDS)
            runCatching { proc.destroy() }
            lines.takeLast(LOGCAT_TAIL_LINES).joinToString("\n").trim()
        }
        val content = result.getOrElse { "Failed reading logcat: ${it.message}" }
        sb.appendLine(if (content.isNotBlank()) content else "(Empty logcat buffer)")
    }

    private fun appendStoredChronologicalLogs(context: Context, sb: StringBuilder) {
        sb.appendLine("=== CHRONOLOGICAL APP & DAC LOG HISTORY ===")
        synchronized(fileLock) {
            val dir = ensureLogDir(context)
            val tailWindow = ArrayDeque<String>(MAX_EXPORT_LOG_LINES + 1)
            var totalLines = 0
            var totalBytes = 0L

            for (name in listOf(PREV_LOG_FILE, CURRENT_LOG_FILE)) {
                val f = File(dir, name)
                if (f.exists() && f.length() > 0L) {
                    totalBytes += f.length()
                    f.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            totalLines++
                            if (tailWindow.size >= MAX_EXPORT_LOG_LINES) {
                                tailWindow.removeFirst()
                            }
                            tailWindow.addLast(line)
                        }
                    }
                }
            }

            if (tailWindow.isEmpty()) {
                sb.appendLine("(No persisted log entries)")
            } else {
                if (totalLines > MAX_EXPORT_LOG_LINES) {
                    sb.appendLine(
                        "--- Showing last $MAX_EXPORT_LOG_LINES of $totalLines recorded lines " +
                            "($totalBytes bytes on disk; older entries omitted) ---"
                    )
                } else {
                    sb.appendLine("--- Persisted log history ($totalLines lines, $totalBytes bytes) ---")
                }
                for (line in tailWindow) {
                    sb.appendLine(line)
                }
            }
        }
    }

    // ═══ Enum & Format Helpers ═══
    private fun captureLiveUsbTelemetry(): LiveUsbTelemetrySnapshot {
        val dac = UsbDacManager.state.value
        val directReady = UsbDacManager.isDirectReady()
        val canQueryNative = directReady && UsbDacNative.isLibraryLoaded
        if (!canQueryNative) {
            return LiveUsbTelemetrySnapshot(
                dac = dac,
                directReady = directReady,
                uacVersion = dac.uacVersion,
                outputMode = dac.activeOutputMode,
                dspFlags = dac.activeDspFlags,
                activeSampleRate = dac.activeSampleRate,
                activeBitDepth = dac.activeBitDepth,
                activeChannels = dac.activeChannels,
                hasHardwareVolume = dac.hasHardwareVolume,
                hardwareVolumeMinDb256 = dac.hardwareVolumeMinDb256,
                hardwareVolumeMaxDb256 = dac.hardwareVolumeMaxDb256,
                hardwareVolumeResDb256 = dac.hardwareVolumeResDb256,
                softwareVolumeBypassed = dac.softwareVolumeBypassed,
                clockSyncMode = dac.clockSyncMode,
                feedbackLocked = dac.feedbackLocked,
                clockDriftPpmX10 = dac.clockDriftPpmX10,
                framesWritten = 0L,
                underrunCount = dac.underrunCount,
                stallCount = dac.stallCount,
                maxStallMs = dac.maxStallMs,
                jitterCount = 0L,
                maxUrbGapMs = 0L,
                streamingTid = 0,
                ringAvailBytes = 0,
                ringFreeBytes = 0
            )
        }

        val volRange = runCatching { UsbDacNative.nativeGetHardwareVolumeRangeDb256() }.getOrNull()
        return LiveUsbTelemetrySnapshot(
            dac = dac,
            directReady = true,
            uacVersion = runCatching { UsbDacNative.nativeGetUacVersion() }.getOrDefault(dac.uacVersion),
            outputMode = runCatching { UsbDacNative.nativeGetActiveOutputMode() }.getOrDefault(dac.activeOutputMode),
            dspFlags = runCatching { UsbDacNative.nativeGetActiveDspFlags() }.getOrDefault(dac.activeDspFlags),
            activeSampleRate = runCatching { UsbDacNative.nativeGetActiveSampleRate() }.getOrDefault(dac.activeSampleRate),
            activeBitDepth = runCatching { UsbDacNative.nativeGetActiveBitDepth() }.getOrDefault(dac.activeBitDepth),
            activeChannels = runCatching { UsbDacNative.nativeGetActiveChannels() }.getOrDefault(dac.activeChannels),
            hasHardwareVolume = runCatching { UsbDacNative.nativeHasHardwareVolume() }.getOrDefault(dac.hasHardwareVolume),
            hardwareVolumeMinDb256 = volRange?.getOrElse(0) { dac.hardwareVolumeMinDb256 } ?: dac.hardwareVolumeMinDb256,
            hardwareVolumeMaxDb256 = volRange?.getOrElse(1) { dac.hardwareVolumeMaxDb256 } ?: dac.hardwareVolumeMaxDb256,
            hardwareVolumeResDb256 = volRange?.getOrElse(2) { dac.hardwareVolumeResDb256 } ?: dac.hardwareVolumeResDb256,
            softwareVolumeBypassed = runCatching { UsbDacNative.nativeIsSoftwareVolumeBypassed() }.getOrDefault(dac.softwareVolumeBypassed),
            clockSyncMode = runCatching { UsbDacNative.nativeGetClockSyncMode() }.getOrDefault(dac.clockSyncMode),
            feedbackLocked = runCatching { UsbDacNative.nativeIsFeedbackLocked() }.getOrDefault(dac.feedbackLocked),
            clockDriftPpmX10 = runCatching { UsbDacNative.nativeGetClockDriftPpmX10() }.getOrDefault(dac.clockDriftPpmX10),
            framesWritten = runCatching { UsbDacNative.nativeGetFramesWritten() }.getOrDefault(0L),
            underrunCount = runCatching { UsbDacNative.nativeGetUnderrunCount() }.getOrDefault(dac.underrunCount),
            stallCount = runCatching { UsbDacNative.nativeGetStallCount() }.getOrDefault(dac.stallCount),
            maxStallMs = runCatching { UsbDacNative.nativeGetMaxStallMs() }.getOrDefault(dac.maxStallMs),
            jitterCount = runCatching { UsbDacNative.nativeGetJitterCount() }.getOrDefault(0L),
            maxUrbGapMs = runCatching { UsbDacNative.nativeGetMaxUrbGapMs() }.getOrDefault(0L),
            streamingTid = runCatching { UsbDacNative.nativeGetStreamingTid() }.getOrDefault(0),
            ringAvailBytes = runCatching { UsbDacNative.nativeGetRingBufferAvailableBytes() }.getOrDefault(0),
            ringFreeBytes = runCatching { UsbDacNative.nativeGetRingBufferFreeBytes() }.getOrDefault(0)
        )
    }

    private fun formatUacVersion(uacVersion: Int): String = when {
        uacVersion >= UsbDacNative.UAC_VERSION_3_0 -> String.format(Locale.US, "UAC 3.0 (0x%04x)", uacVersion)
        uacVersion >= UsbDacNative.UAC_VERSION_2_0 -> String.format(Locale.US, "UAC 2.0 (0x%04x)", uacVersion)
        uacVersion >= UsbDacNative.UAC_VERSION_1_0 -> String.format(Locale.US, "UAC 1.0 (0x%04x)", uacVersion)
        uacVersion > 0 -> String.format(Locale.US, "UAC (0x%04x)", uacVersion)
        else -> "N/A"
    }

    private fun outputModeName(mode: Int): String = when (mode) {
        UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT -> "PCM_BIT_PERFECT"
        UsbDacNative.OUTPUT_MODE_PCM_DSP -> "PCM_DSP"
        UsbDacNative.OUTPUT_MODE_DOP -> "DOP"
        UsbDacNative.OUTPUT_MODE_NATIVE_DSD -> "NATIVE_DSD"
        UsbDacNative.OUTPUT_MODE_DSD_TO_PCM -> "DSD_TO_PCM"
        else -> "INACTIVE"
    }

    private fun formatDspFlags(flags: Int): String {
        if (flags == UsbDacNative.DSP_FLAG_NONE) return "NONE"
        val active = ArrayList<String>(8)
        if ((flags and UsbDacNative.DSP_FLAG_RESAMPLER) != 0) active.add("RESAMPLER")
        if ((flags and UsbDacNative.DSP_FLAG_DITHER_TPDF) != 0) active.add("DITHER_TPDF")
        if ((flags and UsbDacNative.DSP_FLAG_DSD_TO_PCM) != 0) active.add("DSD_TO_PCM")
        if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_DOWNMIX) != 0) active.add("CHANNEL_DOWNMIX")
        if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_MONO_TO_STEREO) != 0) active.add("MONO_TO_STEREO")
        if ((flags and UsbDacNative.DSP_FLAG_SW_VOLUME) != 0) active.add("SW_VOLUME")
        if ((flags and UsbDacNative.DSP_FLAG_DOP_PACKING) != 0) active.add("DOP_PACKING")
        if ((flags and UsbDacNative.DSP_FLAG_ZERO_PADDING) != 0) active.add("ZERO_PADDING")
        if ((flags and UsbDacNative.DSP_FLAG_FLOAT_TO_PCM) != 0) active.add("FLOAT_TO_PCM")
        return if (active.isEmpty()) "UNKNOWN(0x${flags.toString(16)})" else active.joinToString("|")
    }

    private fun clockSyncModeName(mode: Int): String = when (mode) {
        UsbDacNative.SYNC_MODE_ASYNC_FEEDBACK -> "ASYNC_FEEDBACK"
        UsbDacNative.SYNC_MODE_ADAPTIVE -> "ADAPTIVE"
        UsbDacNative.SYNC_MODE_SYNCHRONOUS -> "SYNCHRONOUS"
        UsbDacNative.SYNC_MODE_WATCHDOG_FALLBACK -> "WATCHDOG_FALLBACK"
        else -> "NONE"
    }

    private fun dsdModeName(mode: Int): String = when (mode) {
        UsbDacNative.DSD_MODE_AUTO -> "AUTO"
        UsbDacNative.DSD_MODE_NATIVE -> "NATIVE"
        UsbDacNative.DSD_MODE_DOP -> "DOP"
        UsbDacNative.DSD_MODE_PCM_DECIMATE -> "PCM_DECIMATE"
        else -> "MODE($mode)"
    }

    private fun resamplerQualityName(quality: Int): String = when (quality) {
        UsbDacNative.RESAMPLER_QUALITY_MEDIUM -> "MEDIUM"
        UsbDacNative.RESAMPLER_QUALITY_HIGH -> "HIGH"
        UsbDacNative.RESAMPLER_QUALITY_ULTRA -> "ULTRA"
        else -> "QUALITY($quality)"
    }

    private fun nativeInputEncodingName(encoding: Int): String = when (encoding) {
        UsbDacNative.ENCODING_PCM_16BIT -> "PCM_16"
        UsbDacNative.ENCODING_PCM_24BIT -> "PCM_24"
        UsbDacNative.ENCODING_PCM_32BIT -> "PCM_32"
        UsbDacNative.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        UsbDacNative.ENCODING_DSD_U8 -> "DSD_U8"
        UsbDacNative.ENCODING_DSD_U32_BE -> "DSD_U32_BE"
        UsbDacNative.ENCODING_DSD_U32_LE -> "DSD_U32_LE"
        else -> "ENC($encoding)"
    }

    private fun formatDriftPpm(driftPpmX10: Int): String =
        String.format(Locale.US, "%+.1f ppm", driftPpmX10 / 10.0)

    private fun formatSignedDb(db: Float): String =
        String.format(Locale.US, "%+.1f dB", db)

    private fun getAppVersionLabel(context: Context): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "?"} ($code)"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun formatVidPid(vid: Int, pid: Int): String {
        if (vid == 0 && pid == 0) return "0000:0000"
        return String.format(Locale.US, "%04x:%04x", vid, pid)
    }

    private fun formatUtcOffset(offsetMs: Int): String {
        val totalMin = offsetMs / 60_000
        val sign = if (totalMin >= 0) "+" else "-"
        val absMin = abs(totalMin)
        return String.format(Locale.US, "%s%02d:%02d", sign, absMin / 60, absMin % 60)
    }

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "MODE($mode)"
    }

    private fun mixerBehaviorName(behavior: Int): String = when (behavior) {
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "BIT_PERFECT"
        AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "DEFAULT"
        else -> "BEHAVIOR($behavior)"
    }

    fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        else -> "TYPE_$type"
    }

    private fun audioEncodingName(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM_32"
        AudioFormat.ENCODING_MP3 -> "MP3"
        AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TRUEHD"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_OPUS -> "OPUS"
        AudioFormat.ENCODING_DSD -> "DSD"
        else -> "ENC($enc)"
    }
}
