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
     * **Model sourcing.** The Vosk acoustic model is ~41 MB and is *not* bundled in
     * the APK (plan.md: "ship the model as a downloadable asset, not in the APK").
     * [loadModel] resolves a previously-unpacked copy from app-private storage;
     * if none is present it downloads the small English model
     * (`vosk-model-small-en-us-0.15`) from the official Vosk CDN and
     * stream-unzips it on first use — so the first CC toggle self-provisions the
     * model with no manual setup. A developer-supplied `assets/models/vosk-model`
     * fallback is also honored (e.g. for offline testing).
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
     *  1. A previously-unpacked model in app-private storage
     *     (`filesDir/vosk-model/`) — the canonical post-download location.
     *  2. A developer-supplied local asset fallback
     *     (`assets/models/vosk-model/`), for testing without a download.
     *
     * If neither is present, the small English model
     * (`vosk-model-small-en-us-0.15`, ~41 MB) is downloaded from the official
     * Vosk model CDN and stream-unzipped into location #1, so the first CC
     * toggle self-provisions the model (no manual setup). The download runs on
     * [Dispatchers.IO]; [loadError] surfaces any failure.
     */
    private suspend fun resolveModelDir(): File {
        val downloaded = downloadedModelDir(appContext)
        if (downloaded.isDirectory && downloaded.listFiles()?.isNotEmpty() == true) {
            return downloaded
        }
        // Local-asset fallback: copy once from assets, if present.
        val assetDir = copyAssetModelIfNeeded(appContext)
        if (assetDir != null) return assetDir
        // Network self-provision: download + unzip the small model on first use.
        downloadAndUnzipModel(downloaded)
        return downloaded
    }

    /**
     * Downloads the small English model zip from the Vosk CDN and stream-unzips
     * it into [dest]. The zip's top-level dir (`vosk-model-small-en-us-0.15/`)
     * is stripped so the model files land directly in [dest].
     */
    private suspend fun downloadAndUnzipModel(dest: File) {
        withContext(Dispatchers.IO) {
            dest.mkdirs()
            val tmpZip = File(dest, "model.zip")
            try {
                downloadTo(MODEL_URL, tmpZip)
                unzipFlatteningTopDir(tmpZip, dest)
            } finally {
                tmpZip.delete()
            }
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        if (connection.responseCode !in 200..299) {
            throw IOException("Model download failed: HTTP ${connection.responseCode}")
        }
    }

    /** Unzips [zip] into [dest], stripping a single top-level directory if present. */
    private fun unzipFlatteningTopDir(zip: File, dest: File) {
        // Canonical target dir, used to reject entries that escape it (see below).
        val destCanonicalPath = dest.canonicalPath
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                // Strip the first path segment if the archive nests under one dir
                // (vosk-model-small-en-us-0.15/...  →  ...).
                val rawPath = entry.name
                val topDirEnd = rawPath.indexOf('/')
                val relativePath = if (topDirEnd >= 0 && topDirEnd < rawPath.length - 1) {
                    rawPath.substring(topDirEnd + 1)
                } else {
                    rawPath
                }
                val outFile = File(dest, relativePath)
                if (entry.name.contains("..")) {
                    throw SecurityException("Zip entry contains path traversal characters: $rawPath")
                }
                val destCanonicalDir = destCanonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(destCanonicalDir) && outFile.canonicalPath != destCanonicalPath) {
                    throw SecurityException("Zip entry escapes target directory: $rawPath")
                }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { zis.copyTo(it) }
                zis.closeEntry()
            }
        }
    }

    private suspend fun copyAssetModelIfNeeded(context: Context): File? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val list = context.assets.list("models/vosk-model") ?: return@withContext null
                if (list.isEmpty()) return@withContext null
                val dest = downloadedModelDir(context)
                dest.mkdirs()
                // Recursive copy: a real model has nested dirs.
                copyAssetDir(context, "models/vosk-model", dest)
                dest
            }.getOrNull()
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val list = context.assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // Leaf file.
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        } else {
            dest.mkdirs()
            for (name in list) {
                copyAssetDir(context, "$assetPath/$name", File(dest, name))
            }
        }
    }

    companion object {
        /**
         * The small English Vosk model, downloaded on first CC toggle. The
         * official Vosk model CDN; ~41 MB. Ship-the-model-as-an-asset is
         * intentionally avoided (~50 MB in the APK) — this self-provisions.
         */
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

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
