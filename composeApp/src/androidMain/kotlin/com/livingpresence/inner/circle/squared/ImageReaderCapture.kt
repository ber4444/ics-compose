package com.livingpresence.inner.circle.squared

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps an [ImageReader] as an off-screen surface ExoPlayer can render into,
 * producing a [Bitmap] when a frame arrives. Used by [PreviewFrameEngine] to
 * grab poster/scrub-preview frames without a visible view.
 *
 * RGBA_8888 gives a directly-copyable bitmap (one pixel → one int). Requires
 * API 19 (ImageReader) — fine since minSdk 23.
 */
class ImageReaderCapture(width: Int, height: Int) {

    private val handlerThread = HandlerThread("ImageReaderCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, /* maxImages = */ 2)

    /** The surface the player should render onto via [androidx.media3.common.Player.setVideoSurface]. */
    val surface: Surface get() = reader.surface

    /**
     * Suspends until the next image is available, copies it into a [Bitmap], then
     * returns it. The caller wraps this in `withTimeoutOrNull` for the cutoff.
     */
    suspend fun awaitFrame(): Bitmap? = suspendCancellableCoroutine { cont ->
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image == null || !cont.isActive) {
                image?.close()
                return@setOnImageAvailableListener
            }
            val bmp = image.toBitmap()
            image.close()
            if (bmp != null) {
                r.setOnImageAvailableListener(null, null)
                cont.resume(bmp)
            }
        }, handler)
    }

    fun release() {
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        surface.release()
        handlerThread.quitSafely()
    }

    private fun Image.toBitmap(): Bitmap? {
        val plane = planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bmp
        } else {
            Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
        }
    }
}
