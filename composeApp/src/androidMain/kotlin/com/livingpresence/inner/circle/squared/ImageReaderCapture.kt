package com.livingpresence.inner.circle.squared

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Renders ExoPlayer video into an off-screen surface and captures a frame via
 * [PixelCopy]. Used by [PreviewFrameEngine] to grab poster/scrub frames without
 * a visible view.
 *
 * **Reliability.** Frame capture is best-effort: hardware decoders output
 * vendor-private buffer formats that [android.media.ImageReader] rejects
 * (a hard crash on Mali/Samsung devices), and a detached [SurfaceTexture]
 * can't be sampled without an EGL context. This class therefore uses
 * [PixelCopy] against a plain [Surface] (no GL), and **every** capture failure
 * returns null — the caller falls back to the placeholder tile. A capture
 * failure must never crash playback.
 *
 * Requires API 24 ([PixelCopy]); on API 23 the engine falls back to the
 * placeholder (no thumbnail).
 *
 * @param width  Capture width (px).
 * @param height Capture height (px).
 */
class ImageReaderCapture(width: Int, height: Int) {

    private val handlerThread = HandlerThread("FrameCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * A SurfaceTexture used purely to give ExoPlayer a surface to render into.
     * We never call [SurfaceTexture.updateTexImage] (that needs an EGL context
     * and throws on a detached texture) — [PixelCopy.request] samples the
     * Surface's buffers directly via the framework, regardless of buffer format.
     */
    private val surfaceTexture: SurfaceTexture = SurfaceTexture(0).apply {
        setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    /** The surface the player should render onto via [androidx.media3.common.Player.setVideoSurface]. */
    val surface: Surface = Surface(surfaceTexture)

    private val captureWidth: Int = width.coerceAtLeast(1)
    private val captureHeight: Int = height.coerceAtLeast(1)

    /**
     * Suspends briefly to let frames render, then copies one into a [Bitmap]
     * via [PixelCopy]. Returns null on API 23, on copy failure, or if the
     * request throws — never raises. The caller wraps this in
     * `withTimeoutOrNull` for the outer cutoff.
     */
    suspend fun awaitFrame(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        // Give the decoder a beat to push at least one buffer to the surface.
        kotlinx.coroutines.delay(FIRST_FRAME_SETTLE_MS)
        val bitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (result == PixelCopy.SUCCESS && cont.isActive) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                PixelCopy.request(surface, bitmap, listener, handler)
            } catch (t: Throwable) {
                bitmap.recycle()
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        handlerThread.quitSafely()
    }

    private companion object {
        // Let the first frames land before sampling. PixelCopy needs a buffer
        // present; sampling too early yields SUCCESS but a black frame or none.
        const val FIRST_FRAME_SETTLE_MS = 400L
    }
}
