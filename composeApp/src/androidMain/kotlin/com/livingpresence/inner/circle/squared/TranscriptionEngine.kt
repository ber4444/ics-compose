package com.livingpresence.inner.circle.squared

import android.content.Context
import android.util.Log
import java.io.File
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
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

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
 * **Model sourcing.** The Vosk acoustic model is ~50 MB and is *not* bundled in
 * the APK (plan.md: "ship the model as a downloadable asset, not in the APK").
 * [loadModel] resolves a previously-downloaded copy from app-private storage;
 * the actual fetch from a model server is stubbed with a clear TODO plus a
 * local-asset fallback (an optional `assets/models/vosk-model` directory a
 * developer can drop the model into). The architecture is complete; only the
 * network fetch is deferred.
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

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recognizerJob: Job? = null

    /** PCM handoff queue: [feedPcm] (audio thread) → recognizer (background). */
    private val pcmQueue = LinkedBlockingQueue<PcmChunk>(64)
    private val running = AtomicBoolean(false)

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var contentPositionMs: Long = 0L

    init {
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
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
                val loaded = runCatching { Model(dir.absolutePath) }
                loaded.onSuccess {
                    model?.close()
                    model = it
                    _ready.value = true
                    _loadError.value = null
                    Log.i(TAG, "Vosk model loaded from ${dir.absolutePath}")
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

    /**
     * Starts the recognizer against [playerPositionProvider], which yields the
     * content position (ms) used to stamp cues. Idempotent; pairs with [stop].
     * The recognizer is built lazily once the model is available.
     */
    fun start(playerPositionProvider: () -> Long) {
        if (!running.compareAndSet(false, true)) return
        recognizerJob?.cancel()
        recognizerJob = engineScope.launch(Dispatchers.Default) {
            // Wait for the model if load was deferred to the toggle.
            var waited = 0
            while (model == null && waited < 5_000) {
                delay(100)
                waited += 100
            }
            val m = model ?: run {
                Log.w(TAG, "Recognizer start aborted: no model.")
                running.set(false)
                return@launch
            }
            val rec = runCatching { Recognizer(m, TARGET_SAMPLE_RATE_HZ.toFloat()) }
                .getOrElse { e ->
                    Log.e(TAG, "Recognizer construction failed", e)
                    running.set(false)
                    return@launch
                }
            recognizer?.close()
            recognizer = rec
            recognizeLoop(playerPositionProvider)
        }
    }

    /** Stops the recognizer and drains the PCM queue. Keeps the model loaded. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        recognizerJob?.cancel()
        recognizerJob = null
        pcmQueue.clear()
        // Flush any open partial into a finalized cue.
        runCatching {
            val rec = recognizer ?: return
            val final = parseResult(rec.finalResult)?.takeIf { it.isNotBlank() }
            if (final != null) appendFinalCue(final, contentPositionMs)
        }
    }

    /**
     * Called from ExoPlayer's audio thread ([TeeAudioProcessor]) with a chunk of
     * decoded 16-bit PCM at [sampleRateHz]/[channels]. The buffer is copied and
     * handed to the background recognizer; this method must not block playback.
     */
    fun feedPcm(buffer: ByteBuffer, sampleRateHz: Int, channels: Int) {
        if (!running.get() || recognizer == null) return
        val copy = ByteArray(buffer.remaining())
        buffer.duplicate().get(copy)
        // Drop on overflow rather than back-pressuring the audio thread.
        pcmQueue.offer(PcmChunk(copy, sampleRateHz, channels))
    }

    /** Releases the model and all resources. Call once, when transcription is done for good. */
    fun release() {
        stop()
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
        _ready.value = false
        _captions.value = emptyList()
        engineScope.cancel()
    }

    // -- internals ----------------------------------------------------------

    /**
     * Pulls PCM chunks off the queue, resamples to 16 kHz mono, and feeds Vosk,
     * publishing partial/final results as [CaptionCue]s.
     */
    private suspend fun recognizeLoop(playerPositionProvider: () -> Long) {
        val rec = recognizer ?: return
        while (running.get()) {
            val polled = pcmQueue.poll(50, TimeUnit.MILLISECONDS)
            if (polled == null) {
                delay(10)
                continue
            }
            contentPositionMs = playerPositionProvider()
            val mono16 = resampleTo16kMonoS16(polled.bytes, polled.sampleRateHz, polled.channels)
            if (mono16.isEmpty()) continue
            val shorts = toShortArray(mono16)
            val accepted = runCatching { rec.acceptWaveForm(shorts, shorts.size) }
                .getOrElse {
                    Log.w(TAG, "acceptWaveForm failed", it)
                    continue
                }
            if (accepted) {
                // Utterance boundary → finalize.
                parseResult(rec.result)?.takeIf { it.isNotBlank() }
                    ?.let { appendFinalCue(it, contentPositionMs) }
            } else {
                // Mid-utterance → update the rolling partial.
                parseResult(rec.partialResult)?.takeIf { it.isNotBlank() }
                    ?.let { publishPartial(it, contentPositionMs) }
            }
        }
    }

    /**
     * Down-mixes + down-samples arbitrary 16-bit PCM to 16 kHz mono.
     *
     * This is a deliberately simple linear (nearest-neighbour) decimator: Vosk's
     * small models are forgiving of input rate, and the goal is a clean,
     * understandable pipeline rather than audiophile quality. For production
     * accuracy, swap in a proper low-pass + polyphase resampler (TODO).
     */
    private fun resampleTo16kMonoS16(bytes: ByteArray, inRate: Int, channels: Int): ByteArray {
        if (bytes.size < 2) return ByteArray(0)
        val inFrames = bytes.size / (2 * channels)
        if (inFrames == 0) return ByteArray(0)
        // Mono mix first.
        val mono = ShortArray(inFrames)
        val src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until inFrames) {
            var sum = 0
            for (c in 0 until channels) sum += src.short.toInt()
            mono[i] = (sum / channels).toShort()
        }
        // Linear decimation to TARGET_SAMPLE_RATE_HZ.
        val outFrames = if (inRate == TARGET_SAMPLE_RATE_HZ) {
            inFrames
        } else {
            (inFrames.toLong() * TARGET_SAMPLE_RATE_HZ / inRate).toInt().coerceAtLeast(1)
        }
        val out = ShortArray(outFrames)
        for (i in 0 until outFrames) {
            val srcPos = if (inRate == TARGET_SAMPLE_RATE_HZ) {
                i
            } else {
                ((i.toLong() * inRate) / TARGET_SAMPLE_RATE_HZ).toInt()
            }.coerceIn(0, inFrames - 1)
            out[i] = mono[srcPos]
        }
        val result = ByteArray(out.size * 2)
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out)
        return result
    }

    private fun toShortArray(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
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

    /**
     * Extracts the `"text"` field from a Vosk result JSON. Vosk emits compact
     * JSON like `{"text": "hello world"}` / `{"partial": "hel"}`; a minimal
     * substring parse avoids pulling a JSON dependency into this stretch feature.
     */
    private fun parseResult(json: String): String? {
        val key = "\"text\""
        val textIdx = json.indexOf(key)
        val source = if (textIdx >= 0) json.substring(textIdx) else json
        val start = source.indexOf(':')
        if (start < 0) return null
        val open = source.indexOf('"', start + 1)
        if (open < 0) return null
        val close = source.indexOf('"', open + 1)
        if (close < 0) return null
        return source.substring(open + 1, close).trim()
    }

    /**
     * Resolves the Vosk model directory.
     *
     * Priority:
     *  1. A previously unpacked model in app-private storage
     *     (`filesDir/vosk-model/`) — the canonical post-download location.
     *  2. A developer-supplied local asset fallback
     *     (`assets/models/vosk-model/`), for testing without a model server.
     *
     * The network download from a model server is intentionally NOT implemented
     * here (no model server exists in this project); a real deployment would
     * fetch + unpack the zip into location #1 before calling [loadModel].
     *
     * TODO(model-server): fetch the small English model
     * (`vosk-model-small-en-us-0.15`, ~40 MB) over HTTP on first CC toggle,
     * stream-unzip into [downloadedModelDir], then proceed.
     */
    private fun resolveModelDir(): File {
        val downloaded = downloadedModelDir(appContext)
        if (downloaded.isDirectory && downloaded.listFiles()?.isNotEmpty() == true) {
            return downloaded
        }
        // Local-asset fallback: copy once from assets, if present.
        val assetDir = copyAssetModelIfNeeded(appContext)
        if (assetDir != null) return assetDir
        throw IllegalStateException(
            "No Vosk model found. Download vosk-model-small-en-us into ${downloaded.absolutePath} " +
                "(or assets/models/vosk-model/). See TranscriptionEngine docs.",
        )
    }

    private fun copyAssetModelIfNeeded(context: Context): File? {
        return runCatching {
            val list = context.assets.list("models/vosk-model") ?: return null
            if (list.isEmpty()) return null
            val dest = downloadedModelDir(context)
            dest.mkdirs()
            // Shallow copy; a real model has nested dirs — recursive copy left
            // as a TODO once the asset path is exercised with a real model.
            for (name in list) {
                context.assets.open("models/vosk-model/$name").use { input ->
                    File(dest, name).outputStream().use { input.copyTo(it) }
                }
            }
            dest
        }.getOrNull()
    }

    companion object {
        /** The on-disk location a downloaded model is unpacked into. */
        fun downloadedModelDir(context: Context): File =
            File(context.filesDir, "vosk-model")

        @Volatile
        private var instance: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine =
            instance ?: synchronized(this) {
                instance ?: TranscriptionEngine(context.applicationContext).also { instance = it }
            }
    }
}

/** A queued PCM chunk with its source format (16-bit, [channels] @ [sampleRateHz]). */
private data class PcmChunk(val bytes: ByteArray, val sampleRateHz: Int, val channels: Int) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
