package com.livingpresence.inner.circle.squared

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import io.github.givimad.whisperjni.WhisperContext

private const val TAG = "TranscriptionEngine"

/**
 * Vosk consumes **16 kHz mono 16-bit PCM**. ExoPlayer's audio sink runs at the
 * content's native rate (commonly 48 kHz, stereo), so the tapped PCM is
 * down-mixed to mono and down-sampled here before being handed to the
 * recognizer. 16 kHz is the canonical small-model sample rate (see Vosk docs).
 */
internal const val TARGET_SAMPLE_RATE_HZ = 16_000

/** How many finalized cues are retained in the overlay (a rolling transcript). */
private const val MAX_CUES = 30

/**
 * On-device speech recognition (plan.md Phase 8, part B).
 *
 * The pipeline:
 *  1. ExoPlayer taps decoded PCM via a [TeeAudioProcessor]
 *     ([TranscriptionRenderersFactory]).
 *  2. [feedPcm] hands that buffer to this engine, which resamples it to
 *     16 kHz mono on a background thread.
 *  3. A [Recognizer] running on that thread emits partial/final JSON results.
 *  4. Results are parsed into [CaptionCue]s and published via [captions].
 *
     * **Model sourcing.** The Whisper large-v3-turbo model (Q5_0) is ~500+ MB and is *not* bundled.
     * [loadModel] resolves a previously-unpacked copy from app-private storage;
     * if none is present it downloads the model from Hugging Face.
 *
 * **Engine lifetime.** A singleton bound to the app process — the player is
 * service-owned ([PlaybackService]) and outlives the composable, so the engine
 * must too. [start] is idempotent and tied to a player position clock so cues
 * carry content-accurate timestamps.
 */
internal class TranscriptionEngine private constructor(private val appContext: Context) {

    /** Whether the engine currently has a loaded model and is accepting PCM. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Last error encountered while loading the model (cleared on a successful load). */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** The rolling transcript the caption overlay renders. */
    private val _captions = MutableStateFlow<List<CaptionCue>>(emptyList())
    val captions: StateFlow<List<CaptionCue>> = _captions.asStateFlow()

    /** Model download progress from 0 to 100. */
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recognizerJob: Job? = null

    /** PCM handoff queue: [feedPcm] (audio thread) → recognizer (background). */
    private val pcmQueue = LinkedBlockingQueue<PcmChunk>(64)
    private val running = AtomicBoolean(false)

    @Volatile private var whisperContext: WhisperContext? = null
    @Volatile private var contentPositionMs: Long = 0L
    private val whisper = WhisperJNI()
    
    init {
        runCatching { System.loadLibrary("whisperjni") }
            .onFailure { it.printStackTrace() }
    }

    /**
     * Loads the Vosk model asynchronously. Safe to await before enabling the CC
     * toggle; also called lazily by [start] if the model isn't ready yet. Sets
     * [ready] on success or [loadError] on failure.
     */
    suspend fun loadModel() {
        if (_ready.value) return
        engineScope.launch {
            val outcome = runCatching { resolveModelDir() }
            outcome.onSuccess { dir ->
                val loaded = runCatching { whisper.init(java.nio.file.Paths.get(dir.absolutePath)) }
                loaded.onSuccess { ctx ->
                    whisperContext?.close()
                    whisperContext = ctx
                    _ready.value = true
                    _loadError.value = null
                    Log.i(TAG, "Whisper model loaded from ${dir.absolutePath}")
                }.onFailure { e ->
                    _loadError.value = "Model load failed: ${e.message}"
                    Log.e(TAG, "Model load failed", e)
                }
            }.onFailure { e ->
                _loadError.value = "Model not available: ${e.message}"
                Log.w(TAG, "Model not available", e)
            }
        }.join()
    }

    /** Starts listening to [feedPcm] and pushing [captions]. Models must be loaded. */
    fun start(playerPositionProvider: suspend () -> Long) {
        if (!running.compareAndSet(false, true)) return
        recognizerJob?.cancel()
        recognizerJob = engineScope.launch(Dispatchers.Default) {
            // Wait for the model if load was deferred to the toggle.
            var waited = 0
            while (whisperContext == null && waited < 5_000) {
                delay(100)
                waited += 100
            }
            if (whisperContext == null) {
                Log.w(TAG, "Recognizer start aborted: no model.")
                running.set(false)
                return@launch
            }
            recognizeLoop(playerPositionProvider)
        }
    }

    /** Stops the recognizer and drains the PCM queue. Keeps the model loaded. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        recognizerJob?.cancel()
        recognizerJob = null
        pcmQueue.clear()
    }

    /**
     * Called from ExoPlayer's audio thread ([TeeAudioProcessor]) with a chunk of
     * decoded 16-bit PCM at [sampleRateHz]/[channels]. The buffer is copied and
     * handed to the background recognizer; this method must not block playback.
     */
    fun feedPcm(buffer: ByteBuffer, sampleRateHz: Int, channels: Int, encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT) {
        if (!running.get() || whisperContext == null) return
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        // Drop on overflow rather than back-pressuring the audio thread.
        pcmQueue.offer(PcmChunk(copy, sampleRateHz, channels, encoding))
    }

    /** Releases the model and all resources. Call once, when transcription is done for good. */
    fun release() {
        stop()
        runCatching { whisperContext?.close() }
        whisperContext = null
        _ready.value = false
        _captions.value = emptyList()
        engineScope.cancel()
    }

    // -- internals ----------------------------------------------------------

    /**
     * Pulls PCM chunks off the queue, resamples to 16 kHz mono Float32, and feeds Whisper.
     */
    private suspend fun recognizeLoop(playerPositionProvider: suspend () -> Long) {
        val ctx = whisperContext ?: return
        var accumulatedFloats = FloatArray(0)
        
        android.util.Log.d(TAG, "recognizeLoop started")
        while (running.get()) {
            val polled = pcmQueue.poll(50, TimeUnit.MILLISECONDS)
            if (polled == null) {
                delay(10)
                continue
            }
            contentPositionMs = playerPositionProvider()
            val floats = resampleTo16kMonoFloat(polled.bytes, polled.sampleRateHz, polled.channels, polled.encoding)
            if (floats.isEmpty()) continue
            
            // Accumulate up to ~5 seconds of audio for streaming chunks
            val newAccumulated = FloatArray(accumulatedFloats.size + floats.size)
            System.arraycopy(accumulatedFloats, 0, newAccumulated, 0, accumulatedFloats.size)
            System.arraycopy(floats, 0, newAccumulated, accumulatedFloats.size, floats.size)
            accumulatedFloats = newAccumulated
            
            // If we have > 3 seconds of audio, process it
            if (accumulatedFloats.size > TARGET_SAMPLE_RATE_HZ * 3) {
                android.util.Log.d(TAG, "Running inference on ${accumulatedFloats.size} floats")
                val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY)
                params.printProgress = false
                params.printSpecial = false
                params.printRealtime = false
                
                runCatching { whisper.full(ctx, params, accumulatedFloats, accumulatedFloats.size) }
                    .onSuccess {
                        val numSegments = whisper.fullNSegments(ctx)
                        android.util.Log.d(TAG, "Inference success, numSegments=$numSegments")
                        val textBuilder = StringBuilder()
                        for (i in 0 until numSegments) {
                            textBuilder.append(whisper.fullGetSegmentText(ctx, i))
                        }
                        val final = textBuilder.toString().trim()
                        android.util.Log.d(TAG, "Recognized text: '$final'")
                        if (final.isNotBlank()) {
                            appendFinalCue(final, contentPositionMs)
                        }
                        // Keep the last second for overlap context to avoid chopping words
                        val keepSize = TARGET_SAMPLE_RATE_HZ * 1
                        val keepArray = FloatArray(keepSize)
                        if (accumulatedFloats.size > keepSize) {
                            System.arraycopy(accumulatedFloats, accumulatedFloats.size - keepSize, keepArray, 0, keepSize)
                            accumulatedFloats = keepArray
                        } else {
                            accumulatedFloats = FloatArray(0)
                        }
                    }
                    .onFailure {
                        Log.w(TAG, "Whisper inference failed", it)
                    }
            }
        }
    }

    /**
     * Down-mixes + down-samples arbitrary 16-bit PCM to 16 kHz mono Float32 (-1.0 to 1.0).
     */
    private fun resampleTo16kMonoFloat(bytes: ByteArray, inRate: Int, channels: Int, encoding: Int): FloatArray {
        if (bytes.size < 2) return FloatArray(0)
        
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mono = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            val inFrames = bytes.size / (4 * channels)
            if (inFrames == 0) return FloatArray(0)
            val out = FloatArray(inFrames)
            for (i in 0 until inFrames) {
                var sum = 0f
                for (c in 0 until channels) sum += src.float
                out[i] = sum / channels
            }
            out
        } else {
            val inFrames = bytes.size / (2 * channels)
            if (inFrames == 0) return FloatArray(0)
            val out = FloatArray(inFrames)
            for (i in 0 until inFrames) {
                var sum = 0f
                for (c in 0 until channels) sum += src.short.toFloat() / 32768.0f
                out[i] = sum / channels
            }
            out
        }
        
        // Linear decimation to TARGET_SAMPLE_RATE_HZ.
        val inFrames = mono.size
        val outFrames = if (inRate == TARGET_SAMPLE_RATE_HZ) {
            inFrames
        } else {
            (inFrames * TARGET_SAMPLE_RATE_HZ.toDouble() / inRate).toInt()
        }
        val out = FloatArray(outFrames)
        if (inRate == TARGET_SAMPLE_RATE_HZ) {
            System.arraycopy(mono, 0, out, 0, outFrames)
        } else {
            val ratio = inRate.toFloat() / TARGET_SAMPLE_RATE_HZ
            for (i in 0 until outFrames) {
                val inIdx = (i * ratio).toInt().coerceAtMost(mono.size - 1)
                out[i] = mono[inIdx]
            }
        }
        return out
    }

    /** Publishes an in-flight partial, replacing the trailing partial cue. */
    private fun publishPartial(text: String, positionMs: Long) {
        val current = _captions.value
        val withoutPartial = current.filter { it.endMs != -1L }
        val updated = withoutPartial + CaptionCue(text = text, startMs = positionMs, endMs = -1L)
        _captions.value = updated.takeLast(MAX_CUES)
    }

    /** Finalizes a cue with a closed [endMs]. */
    private fun appendFinalCue(text: String, positionMs: Long) {
        val current = _captions.value
        val start = current.lastOrNull()?.let { if (it.endMs == -1L) it.startMs else positionMs }
            ?: positionMs
        val withoutPartial = current.filter { it.endMs != -1L }
        val updated = withoutPartial + CaptionCue(text = text, startMs = start, endMs = positionMs)
        _captions.value = updated.takeLast(MAX_CUES)
    }

    private suspend fun resolveModelDir(): java.io.File = withContext(Dispatchers.IO) {
        val dest = java.io.File(appContext.filesDir, "whisper-model.bin")
        if (dest.exists() && dest.length() > 200_000_000L) {
            Log.i(TAG, "Deleting old large model to replace with base.en")
            dest.delete()
        }
        if (dest.exists() && dest.length() > 0) return@withContext dest

        // Download from HuggingFace directly to the bin file.
        val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
        val temp = java.io.File(appContext.filesDir, "whisper-model.tmp")
        Log.i(TAG, "Downloading Whisper model from $url")
        var bytesRead = 0L
        var lastLog = System.currentTimeMillis()
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connect()
            val totalBytes = connection.contentLength.toLong()
            
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        
                        if (totalBytes > 0) {
                            val percent = ((bytesRead.toDouble() / totalBytes) * 100).toInt()
                            _downloadProgress.value = percent.coerceIn(0, 100)
                        }
                        
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 2000) {
                            Log.d(TAG, "Downloading model... ${bytesRead / 1024 / 1024} MB")
                            lastLog = now
                        }
                    }
                }
            }
            temp.renameTo(dest)
            _downloadProgress.value = 100
            Log.i(TAG, "Whisper model successfully downloaded to ${dest.absolutePath}")
            return@withContext dest
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Failed to download Whisper model", e)
        }
    }

    companion object {
        @Volatile
        private var instance: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine =
            instance ?: synchronized(this) {
                instance ?: TranscriptionEngine(context.applicationContext).also { instance = it }
            }
    }
}

/** A queued PCM chunk with its source format (16-bit, [channels] @ [sampleRateHz]). */
private data class PcmChunk(val bytes: ByteArray, val sampleRateHz: Int, val channels: Int, val encoding: Int) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
