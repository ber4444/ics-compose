package com.livingpresence.inner.circle.squared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.livingpresence.mediakit.MediaKitConfig
import com.livingpresence.mediakit.RenditionTier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlinx.cinterop.ObjCObjectVar

@OptIn(ExperimentalForeignApi::class)
class PreviewFrameEngine {

    private val cacheDir: NSURL by lazy {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
        val caches = urls.first() as NSURL
        val dir = caches.URLByAppendingPathComponent("ics-cache")!!
        NSFileManager.defaultManager.createDirectoryAtURL(
            url = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        dir
    }

    /**
     * Synchronously fetches the frame from disk cache or AVAssetImageGenerator.
     * Must be called off the main thread.
     */
    suspend fun getFrame(eventNumber: Int, timeMs: Long): ImageBitmap? = withContext(Dispatchers.IO) {
        val fileName = "${eventNumber}_${timeMs}.jpg"
        val fileUrl = cacheDir.URLByAppendingPathComponent(fileName)!!

        // 1. Check disk cache
        if (NSFileManager.defaultManager.fileExistsAtPath(fileUrl.path!!)) {
            val nsData = NSData.dataWithContentsOfURL(fileUrl)
            if (nsData != null) {
                return@withContext nsData.toImageBitmap()
            }
        }

        // 2. Fetch from AVAssetImageGenerator (160p stream)
        val urlString = MediaKitConfig.Default.renditionUrl(eventNumber, RenditionTier.P160)
        val asset = AVURLAsset(uRL = NSURL.URLWithString(urlString)!!, options = null)
        val generator = AVAssetImageGenerator(asset).apply {
            appliesPreferredTrackTransform = true
            maximumSize = CGSizeMake(320.0, 180.0)
        }

        var resultBitmap: ImageBitmap? = null

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val actualTime = alloc<CMTime>()
            val cgImage = generator.copyCGImageAtTime(
                requestedTime = CMTimeMakeWithSeconds(timeMs / 1000.0, 600),
                actualTime = actualTime.ptr,
                error = errorPtr.ptr
            )

            if (cgImage != null) {
                val uiImage = UIImage.imageWithCGImage(cgImage)
                val nsData = UIImageJPEGRepresentation(uiImage, 0.7)
                
                // Write to disk
                nsData?.writeToURL(fileUrl, atomically = true)
                
                resultBitmap = nsData?.toImageBitmap()
                CGImageRelease(cgImage)
            }
        }

        resultBitmap
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toImageBitmap(): ImageBitmap? {
        val size = this.length.toInt()
        if (size == 0) return null
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
        return try {
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
