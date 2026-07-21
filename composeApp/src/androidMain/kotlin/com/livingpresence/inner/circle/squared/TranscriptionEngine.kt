package com.livingpresence.inner.circle.squared

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * Whisper consumes **16 kHz mono 32-bit float PCM**. ExoPlayer's audio sink runs
 * at the content's native rate (commonly 48 kHz, stereo), so the tapped PCM is
 * down-mixed to mono and down-sampled here before being handed to the model.
 * 16 kHz is the rate every Whisper model expects (see whisper.cpp docs).
 */
internal const val TARGET_SAMPLE_RATE_HZ = 16_000

/** How many finalized cues are retained in the overlay (a rolling transcript). */
private const val MAX_CUES = 30

/**
 * Recognition cadence. Audio is transcribed in fixed windows so each Whisper run
 * produces a distinct caption; consecutive windows overlap slightly so a word
 * straddling a boundary isn't chopped.
 */
private const val CHUNK_SECONDS = 4
private const val OVERLAP_SECONDS = 1

/**
 * Upper bound on buffered-but-not-yet-transcribed audio. Inference is slower than
 * real time on-device, so when it can't keep up the oldest audio past this cap is
 * dropped — we always transcribe *recent* speech instead of falling ever further
 * behind, and memory stays bounded regardless of how long CC is left on.
 */
private const val MAX_BACKLOG_SECONDS = 8

/**
 * Whisper worker-thread count. The 0%-CPU deadlock we hit earlier was on the
 * unoptimized (`-O0`) debug native build; with the optimized (`-O3 -DNDEBUG`)
 * build the GGML barrier is stable, so we use multiple threads to keep the
 * heavier base.en model near real time. Drop back to 1 if a hang ever recurs.
 */
private const val INFERENCE_THREADS = 4

/** Peak |sample| below which a window is treated as silence and skipped (Whisper hallucinates on silence). */
private const val SILENCE_PEAK = 0.005f

/**
 * The on-device Whisper model. `base.en` (~147 MB) is used over `tiny.en` for
 * markedly better accuracy; with the optimized (`-O3`) native build and
 * multi-threaded inference it still keeps up with playback. The file is named
 * after the model so switching models auto-replaces the old one on disk.
 */
private const val MODEL_FILE = "ggml-base.en.bin"
private const val MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"

/**
 * On-device speech recognition (plan.md Phase 8, part B).
 *
 * The pipeline:
 *  1. ExoPlayer taps decoded PCM via a [TeeAudioProcessor]
 *     ([TranscriptionRenderersFactory]).
 *  2. [feedPcm] hands that buffer to this engine, which resamples it to
 *     16 kHz mono 32-bit float on a background thread.
 *  3. whisper.cpp (via WhisperJNI) transcribes a rolling audio window on that
 *     thread and emits recognized text segments.
 *  4. Segments are turned into [CaptionCue]s and published via [captions].
 *
 * **Model sourcing.** The Whisper `ggml-base.en` model (~147 MB) is *not*
 * bundled in the APK. [loadModel] resolves a previously-downloaded copy from
 * app-private storage; if none is present it downloads it from Hugging Face on
 * first CC toggle.
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

    /** Drains [pcmQueue] → resamples → cuts fixed windows for the recognizer. Never blocks on inference. */
    private var ingestJob: Job? = null

    /** Runs the (blocking, sometimes-slow) Whisper calls, decoupled from ingestion. */
    private var inferenceJob: Job? = null

    /**
     * Single-slot handoff from [ingestLoop] to [inferenceLoop]: the latest window
     * awaiting transcription. Ingest only fills it when empty, so while a run is
     * in flight new audio keeps accumulating and only the newest window is handed
     * over next — old backlog is dropped, keeping captions current.
     */
    private val readyChunk = AtomicReference<FloatArray?>(null)

    /**
     * Dedicated single worker for [WhisperJNI.full]. One thread only, so calls are
     * serialized and the (non-thread-safe) [WhisperContext] is never touched by two
     * runs at once.
     */
    private val inferenceExecutor: ExecutorService = newInferenceExecutor()

    /** PCM handoff queue: [feedPcm] (audio thread) → [ingestLoop] (background). */
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
     * Loads the Whisper model asynchronously. Safe to await before enabling the
     * CC toggle; also called lazily by [start] if the model isn't ready yet. Sets
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

    /**
     * Starts the decoupled ingest + inference loops and begins pushing [captions].
     * The model must be (or become) loaded. Idempotent; pairs with [stop].
     */
    fun start(playerPositionProvider: suspend () -> Long) {
        if (!running.compareAndSet(false, true)) return
        ingestJob?.cancel()
        inferenceJob?.cancel()
        readyChunk.set(null)
        // Ingestion runs regardless of model state; [feedPcm] gates PCM on the
        // model being loaded, so nothing accumulates until the recognizer is ready.
        ingestJob = engineScope.launch(Dispatchers.Default) {
            ingestLoop(playerPositionProvider)
        }
        inferenceJob = engineScope.launch(Dispatchers.Default) {
            // Wait for the model if load was deferred to the toggle.
            var waited = 0
            while (whisperContext == null && waited < 5_000) {
                delay(100)
                waited += 100
            }
            val ctx = whisperContext ?: run {
                Log.w(TAG, "Recognizer start aborted: no model.")
                running.set(false)
                return@launch
            }
            inferenceLoop(ctx)
        }
    }

    /** Stops both loops and drains buffered audio. Keeps the model loaded. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ingestJob?.cancel()
        inferenceJob?.cancel()
        ingestJob = null
        inferenceJob = null
        pcmQueue.clear()
        readyChunk.set(null)
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
        inferenceExecutor.shutdownNow()
        engineScope.cancel()
    }

    // -- internals ----------------------------------------------------------

    /**
     * Ingestion: pulls PCM off the queue, resamples to 16 kHz mono float, and cuts
     * fixed [CHUNK_SECONDS] windows for the recognizer. Runs independently of
     * inference — it never blocks on a Whisper call, so audio is never dropped
     * just because a transcription is in flight. When inference falls behind, only
     * the newest window is handed over next (see [readyChunk]) and stale backlog
     * beyond [MAX_BACKLOG_SECONDS] is discarded.
     */
    private suspend fun ingestLoop(playerPositionProvider: suspend () -> Long) {
        Log.d(TAG, "ingestLoop started")
        val chunkSamples = TARGET_SAMPLE_RATE_HZ * CHUNK_SECONDS
        val overlapSamples = TARGET_SAMPLE_RATE_HZ * OVERLAP_SECONDS
        val maxBacklogSamples = TARGET_SAMPLE_RATE_HZ * MAX_BACKLOG_SECONDS
        var pending = FloatArray(0)
        while (running.get()) {
            val polled = pcmQueue.poll(50, TimeUnit.MILLISECONDS)
            if (polled == null) {
                delay(10)
                continue
            }
            contentPositionMs = playerPositionProvider()
            val floats = resampleTo16kMonoFloat(polled.bytes, polled.sampleRateHz, polled.channels, polled.encoding)
            if (floats.isEmpty()) continue
            pending = appendFloats(pending, floats)
            // If inference falls behind, drop the OLDEST audio so latency (and
            // memory) stay bounded. Because we process front-to-back, this sheds
            // the least-recent unseen audio rather than the speech playing now.
            if (pending.size > maxBacklogSamples) {
                pending = pending.copyOfRange(pending.size - maxBacklogSamples, pending.size)
            }
            // Hand the OLDEST full window to the recognizer whenever it's idle, so
            // captions cover speech contiguously and in order; keep a short overlap
            // tail so a word crossing the boundary isn't chopped in two.
            if (pending.size >= chunkSamples && readyChunk.get() == null) {
                val chunk = pending.copyOfRange(0, chunkSamples)
                val advance = (chunkSamples - overlapSamples).coerceAtLeast(1)
                pending = pending.copyOfRange(advance, pending.size)
                if (!isSilent(chunk)) readyChunk.set(chunk)
            }
        }
    }

    /**
     * Inference: transcribes whatever window [ingestLoop] hands over, one at a
     * time via [runInference]. Publishes each result as a finalized [CaptionCue].
     */
    private suspend fun inferenceLoop(ctx: WhisperContext) {
        Log.d(TAG, "inferenceLoop started")
        while (running.get()) {
            val chunk = readyChunk.get()
            if (chunk == null) {
                delay(20)
                continue
            }
            val positionMs = contentPositionMs
            val text = runInference(ctx, chunk)
            // Free the slot so ingestion can hand over the next (newest) window.
            readyChunk.set(null)
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Recognized text: '$text'")
                appendFinalCue(text, positionMs)
            }
        }
    }

    /**
     * Runs a single [WhisperJNI.full] on the dedicated [inferenceExecutor] and
     * returns the recognized text (null on failure).
     *
     * Calls are **strictly serial** on one thread and we always wait for each to
     * finish: a [WhisperContext] is not thread-safe, so a second `full()` must
     * never touch it while a prior one is still running. (An earlier version used
     * a timeout that abandoned a slow call and started the next — two `full()`s
     * then raced on the same context and crashed the process natively.) Ingestion
     * runs on its own coroutine and keeps draining regardless, so blocking here
     * doesn't drop audio; a slow run just means the next window is newer.
     */
    private suspend fun runInference(ctx: WhisperContext, samples: FloatArray): String? {
        val task = inferenceExecutor.submit(Callable {
            val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
                printProgress = false
                printSpecial = false
                printRealtime = false
                printTimestamps = false
                noTimestamps = true
                noContext = true
                singleSegment = false
                suppressBlank = true
                suppressNonSpeechTokens = true
                nThreads = INFERENCE_THREADS
                language = "en"
            }
            val startedAt = System.currentTimeMillis()
            whisper.full(ctx, params, samples, samples.size)
            val numSegments = whisper.fullNSegments(ctx)
            val text = buildString {
                for (i in 0 until numSegments) append(whisper.fullGetSegmentText(ctx, i))
            }.trim()
            Log.d(TAG, "inference: ${samples.size} samples in ${System.currentTimeMillis() - startedAt}ms → $numSegments seg")
            text
        })
        return withContext(Dispatchers.IO) {
            runCatching { task.get() }.getOrElse { e -> Log.w(TAG, "Whisper inference failed", e); null }
        }
    }

    private fun newInferenceExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "whisper-infer").apply { isDaemon = true } }

    /** True when every sample is below [SILENCE_PEAK] (skip to avoid Whisper hallucinating on silence). */
    private fun isSilent(samples: FloatArray): Boolean {
        for (s in samples) if (kotlin.math.abs(s) > SILENCE_PEAK) return false
        return true
    }

    private fun appendFloats(head: FloatArray, tail: FloatArray): FloatArray {
        val out = FloatArray(head.size + tail.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(tail, 0, out, head.size, tail.size)
        return out
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
        
        val inFrames = mono.size
        if (inRate == TARGET_SAMPLE_RATE_HZ) return mono
        // Anti-aliased decimation. Taking every Nth sample (what this used to do)
        // aliases all energy above 8 kHz back into the speech band and audibly
        // garbles what Whisper hears; averaging each source window is a cheap
        // low-pass that removes most of that and materially improves accuracy.
        val outFrames = (inFrames.toLong() * TARGET_SAMPLE_RATE_HZ / inRate).toInt()
        if (outFrames <= 0) return FloatArray(0)
        val out = FloatArray(outFrames)
        val step = inFrames.toDouble() / outFrames
        for (i in 0 until outFrames) {
            val start = (i * step).toInt()
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, inFrames)
            var sum = 0f
            for (j in start until end) sum += mono[j]
            out[i] = sum / (end - start)
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
        val dest = java.io.File(appContext.filesDir, MODEL_FILE)
        // Remove any earlier model file (e.g. the old base.en `whisper-model.bin`,
        // ~147 MB) so switching models doesn't leave a large unused blob behind.
        appContext.filesDir.listFiles()
            ?.filter { it.isFile && it.name != MODEL_FILE && (it.name.startsWith("ggml-") || it.name == "whisper-model.bin") }
            ?.forEach { stale ->
                Log.i(TAG, "Removing stale Whisper model ${stale.name} (${stale.length() / 1024 / 1024} MB)")
                stale.delete()
            }
        if (dest.exists() && dest.length() > 0) return@withContext dest

        // Download from HuggingFace directly to the bin file.
        val temp = java.io.File(appContext.filesDir, "$MODEL_FILE.tmp")
        Log.i(TAG, "Downloading Whisper model from $MODEL_URL")
        var bytesRead = 0L
        var lastLog = System.currentTimeMillis()
        try {
            val connection = java.net.URL(MODEL_URL).openConnection() as java.net.HttpURLConnection
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
