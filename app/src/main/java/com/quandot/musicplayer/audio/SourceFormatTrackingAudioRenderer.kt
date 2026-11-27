/**
 * SourceFormatTrackingAudioRenderer — Zero-overhead Media3 Renderer decorator that forwards the
 * exact container/extractor `Format` directly to [UsbDacAudioSink] on the ExoPlayer Playback Thread.
 *
 * @remarks
 * `FfmpegAudioRenderer.getOutputFormat()` emits a stripped `MimeTypes.AUDIO_RAW` `PCM_FLOAT`
 * `Format` without the source container's bit depth or codec metadata, and ExoPlayer routinely
 * calls `AudioSink.configure()` for the next track during gapless transitions 75 ms to 2.6 s
 * ahead of `Player.Listener.onMediaItemTransition()` on the Main Thread.
 *
 * By intercepting `SampleStream.readData() == C.RESULT_FORMAT_READ` (and initial `enable()`)
 * synchronously on the Playback Thread, this decorator delivers the incoming track's exact
 * `Format` (FLAC `pcmEncoding`/STREAMINFO, ALAC `ALACSpecificConfig`, WAV/AIFF raw PCM/float,
 * DSD, or lossy MIME) immediately before the decoder opens and invokes `AudioSink.configure()`,
 * eliminating both gapless bit-depth races and async container header probes.
 *
 * `getStream()` strictly returns the unwrapped original `SampleStream` so `ExoPlayerImplInternal`
 * stream identity comparisons (`renderer.getStream() == holder.sampleStreams[i]`) remain intact.
 *
 * @see UsbDacAudioSink
 * @see WavFloatFixExtractorsFactory
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

// ═══ Audio Renderer Decorator ═══
@UnstableApi
class SourceFormatTrackingAudioRenderer(
    private val delegate: Renderer,
    private val onInputFormatRead: (Format) -> Unit
) : Renderer {

    private var originalStream: SampleStream? = null

    override fun getName(): String = delegate.name

    override fun getTrackType(): Int = delegate.trackType

    override fun getCapabilities(): RendererCapabilities = delegate.capabilities

    override fun init(index: Int, playerId: PlayerId, clock: Clock) {
        delegate.init(index, playerId, clock)
    }

    override fun getMediaClock(): MediaClock? = delegate.mediaClock

    override fun getState(): Int = delegate.state

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        originalStream = stream
        if (formats.isNotEmpty()) {
            onInputFormatRead(formats[0])
        }
        delegate.enable(
            configuration,
            formats,
            InterceptingSampleStream(stream, onInputFormatRead),
            positionUs,
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId
        )
    }

    override fun start() {
        delegate.start()
    }

    override fun replaceStream(
        formats: Array<Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        originalStream = stream
        // Do NOT emit formats[0] eagerly here: during gapless transitions the decoder may still
        // be draining the previous track's output buffers; wait until the renderer actually reads
        // C.RESULT_FORMAT_READ from the new stream right before reconfiguring AudioSink.
        delegate.replaceStream(
            formats,
            InterceptingSampleStream(stream, onInputFormatRead),
            startPositionUs,
            offsetUs,
            mediaPeriodId
        )
    }

    override fun getStream(): SampleStream? = if (delegate.stream != null) originalStream else null

    override fun hasReadStreamToEnd(): Boolean = delegate.hasReadStreamToEnd()

    override fun getReadingPositionUs(): Long = delegate.readingPositionUs

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        delegate.getDurationToProgressUs(positionUs, elapsedRealtimeUs)

    override fun setCurrentStreamFinal() {
        delegate.setCurrentStreamFinal()
    }

    override fun isCurrentStreamFinal(): Boolean = delegate.isCurrentStreamFinal()

    override fun maybeThrowStreamError() {
        delegate.maybeThrowStreamError()
    }

    override fun resetPosition(positionUs: Long) {
        delegate.resetPosition(positionUs)
    }

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        delegate.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    }

    override fun enableMayRenderStartOfStream() {
        delegate.enableMayRenderStartOfStream()
    }

    override fun setTimeline(timeline: Timeline) {
        delegate.setTimeline(timeline)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(positionUs, elapsedRealtimeUs)
    }

    override fun isReady(): Boolean = delegate.isReady()

    override fun isEnded(): Boolean = delegate.isEnded()

    override fun stop() {
        delegate.stop()
    }

    override fun disable() {
        originalStream = null
        delegate.disable()
    }

    override fun reset() {
        originalStream = null
        delegate.reset()
    }

    override fun release() {
        originalStream = null
        delegate.release()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        delegate.handleMessage(messageType, message)
    }

    // ═══ SampleStream Format Interceptor ═══
    private class InterceptingSampleStream(
        private val delegate: SampleStream,
        private val onFormatRead: (Format) -> Unit
    ) : SampleStream {

        override fun isReady(): Boolean = delegate.isReady()

        override fun maybeThrowError() {
            delegate.maybeThrowError()
        }

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int
        ): Int {
            val result = delegate.readData(formatHolder, buffer, readFlags)
            if (result == C.RESULT_FORMAT_READ) {
                formatHolder.format?.let(onFormatRead)
            }
            return result
        }

        override fun skipData(positionUs: Long): Int = delegate.skipData(positionUs)
    }
}
