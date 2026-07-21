package com.livingpresence.inner.circle.squared

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

/**
 * A [DefaultRenderersFactory] that taps decoded audio PCM for live transcription
 * while leaving every other renderer (video, **built-in text**, metadata) untouched.
 *
 * The tap is a [TeeAudioProcessor] whose [AudioBufferSink] forwards each buffer to
 * a [PcmTapSink] (the [CaptionAudioRouter], which resamples and streams audio to
 * the selected cloud ASR provider); the processor is installed via
 * [DefaultAudioSink.Builder.setAudioProcessors] inside [buildAudioSink]. Because
 * the audio sink is the single choke point for all decoded PCM, this captures
 * audio regardless of rendition/codec.
 *
 * The built-in text renderer is intentionally left enabled (inherited from
 * [DefaultRenderersFactory]) so that if a stream ever carries CEA-608/708
 * captions, those would also be available on the player's `currentCues`. Streaming
 * ASR is the primary path because the production server carries no embedded captions.
 *
 * @param context used to build the audio sink.
 * @param sink the PCM tap the decoded audio is forwarded to.
 */
@UnstableApi
internal class TranscriptionRenderersFactory(
    context: Context,
    private val sink: PcmTapSink,
) : DefaultRenderersFactory(context) {

    init {
        // Keep extension renderers (e.g. a future subtitle decoder) preferred
        // over the built-ins where available.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
    }

    /**
     * Builds the audio sink with the transcription [TeeAudioProcessor] prepended
     * to the sink's processor chain. The tee is a non-mutating pass-through: it
     * copies each buffer to the engine and returns the buffer unchanged, so
     * playback audio is unaffected.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val transcriptionTap = TeeAudioProcessor(CaptionAudioBufferSink(sink))
        val builder = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(transcriptionTap))
            .setEnableFloatOutput(enableFloatOutput)
        // Forwarded from DefaultRenderersFactory to keep playback behavior
        // identical; deprecated on the Builder but still the supported knob.
        @Suppress("DEPRECATION")
        builder.setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        return builder.build()
    }
}

/**
 * Forwards each decoded PCM buffer to the transcription engine without
 * consuming it. [flush] is called by the sink on format changes and lets the
 * sink track the active sample rate / channel count.
 */
@UnstableApi
private class CaptionAudioBufferSink(
    private val sink: PcmTapSink,
) : TeeAudioProcessor.AudioBufferSink {

    private var sampleRate = 48000
    private var channelCount = 2
    private var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT

    override fun flush(sampleRate: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.encoding = encoding
        android.util.Log.d("CaptionAudioRouter", "AudioSink flush: rate=$sampleRate, channels=$channelCount, encoding=$encoding")
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT &&
            encoding != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            return
        }
        if (!buffer.hasRemaining()) return
        sink.onPcm(buffer, sampleRate, channelCount, encoding)
    }
}
