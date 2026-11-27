/**
 * SilentAudioKeepAlive — Sub-audible streaming AudioTrack heartbeat for OEM background protection.
 *
 * @remarks
 * Because `UsbDacAudioSink` writes raw PCM directly to Linux `usbdevfs`, Android's AudioFlinger
 * sees zero active tracks and reports `AudioManager.isMusicActive() == false`. Aggressive OEM
 * process managers (such as Huawei HarmonyOS/EMUI `PowerGenie` and `iAware`) freeze or `SIGKILL`
 * backgrounded players that lack an actively streaming, non-zero-gain AudioTrack.
 *
 * Uses `MODE_STREAM` rather than `MODE_STATIC` (which emits `AudioTrack_Buffer_Timeout` errors on
 * HarmonyOS 4.2) and feeds a zero-DC `±1` LSB waveform (`-90.3 dBFS`) attenuated to `0.005f`
 * (`-46 dB`, combined `< -135 dBFS` — completely inaudible on speaker hardware) pinned strictly to
 * `TYPE_BUILTIN_SPEAKER` so AudioFlinger never attempts to open `USB_HEADSET` or touch the Bit-Perfect path.
 *
 * @see UsbDacManager
 * @see com.quandot.musicplayer.service.PlaybackService
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

// Internal modules
import com.quandot.musicplayer.debug.PDLog

// ═══ Constants ═══
private const val SAMPLE_RATE_HZ = 48_000
private const val CHUNK_DURATION_DIVISOR = 10 // 100ms chunk (4800 mono 16-bit frames = 9600 B, ~10 writes/sec)
private const val BYTES_PER_MONO_16BIT_FRAME = 2
private const val MIN_STREAM_BUFFER_MULTIPLIER = 2
// Combined level: ±1 LSB (-90.3 dBFS) * 0.005 (-46 dB) = -136.3 dBFS (below speaker thermal noise floor)
private const val SUB_AUDIBLE_TRACK_VOLUME = 0.005f
private const val WRITER_JOIN_TIMEOUT_MS = 350L

// ═══ Keep-Alive Controller ═══
class SilentAudioKeepAlive(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val streamRunning = AtomicBoolean(false)
    private var silentTrack: AudioTrack? = null
    private var writerThread: Thread? = null

    /**
     * Starts or stops the sub-audible `MODE_STREAM` speaker heartbeat idempotently.
     */
    @Synchronized
    fun setActive(active: Boolean) {
        if (!active) {
            release()
            return
        }
        val existing = silentTrack
        if (existing != null && streamRunning.get() && existing.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }

        val speakerDevice = findBuiltinSpeakerDevice() ?: run {
            PDLog.w(PDLog.PLAYBACK, "KEEPALIVE_SKIP", "reason=no_builtin_speaker")
            return
        }

        release()
        try {
            val chunkSamples = SAMPLE_RATE_HZ / CHUNK_DURATION_DIVISOR
            val chunkBytes = chunkSamples * BYTES_PER_MONO_16BIT_FRAME
            val minBufBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(chunkBytes)
            val bufferBytes = maxOf(minBufBytes * MIN_STREAM_BUFFER_MULTIPLIER, chunkBytes * 2)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Pin explicitly to built-in speaker BEFORE play() so AudioFlinger never claims USB_HEADSET
            track.setPreferredDevice(speakerDevice)
            track.setVolume(SUB_AUDIBLE_TRACK_VOLUME)
            track.play()

            val pcmChunk = buildSubAudiblePcmChunk(chunkSamples)
            streamRunning.set(true)
            silentTrack = track

            val thread = Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                while (streamRunning.get()) {
                    val written = try {
                        track.write(pcmChunk, 0, pcmChunk.size, AudioTrack.WRITE_BLOCKING)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (written < 0) break
                }
            }, "SilentKeepAliveAudio").apply {
                isDaemon = true
                start()
            }
            writerThread = thread

            PDLog.i(
                PDLog.PLAYBACK,
                "KEEPALIVE_START",
                "mode=STREAM vol=$SUB_AUDIBLE_TRACK_VOLUME speakerId=${speakerDevice.id}"
            )
        } catch (t: Throwable) {
            PDLog.w(PDLog.PLAYBACK, "KEEPALIVE_START_ERR", "err=${t.message}", t)
            release()
        }
    }

    /**
     * Stops the writer thread and releases any active silent keep-alive track cleanly.
     */
    @Synchronized
    fun release() {
        val track = silentTrack
        val thread = writerThread
        streamRunning.set(false)
        silentTrack = null
        writerThread = null
        if (track == null && thread == null) return

        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.stop()
            } catch (_: Throwable) {
            }
        }

        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.interrupt()
                thread.join(WRITER_JOIN_TIMEOUT_MS)
            } catch (_: Throwable) {
            }
        }

        if (track != null) {
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
        PDLog.i(PDLog.PLAYBACK, "KEEPALIVE_STOP")
    }

    // ═══ Private Helpers ═══
    private fun findBuiltinSpeakerDevice(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /**
     * Generates a zero-DC alternating `±1` LSB PCM16 buffer (`-90.3 dBFS`) so OEM silence detectors
     * observe non-zero audio energy without introducing audible output or speaker DC offset.
     */
    private fun buildSubAudiblePcmChunk(sampleCount: Int): ShortArray {
        val samples = ShortArray(sampleCount)
        for (i in samples.indices) {
            samples[i] = when (i and 3) {
                0 -> 1
                1 -> -1
                else -> 0
            }
        }
        return samples
    }
}
