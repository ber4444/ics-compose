package com.livingpresence.inner.circle.squared

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Memory governance for playback (plan.md Phase 4): adapts buffer sizing to the
 * device's RAM and reacts to [ComponentCallbacks2][android.content.ComponentCallbacks2]
 * trim levels by shedding decoder/cache memory in tiers.
 */
internal object MemoryGovernor {

    /**
     * A [DefaultLoadControl] tuned to the device: low-RAM devices get tighter
     * buffers (10–30 s, 1.5 s initial) and a capped target buffer; everything
     * else uses ExoPlayer defaults. The numbers are documented tunables, not
     * measurements.
     */
    @UnstableApi
    fun adaptiveLoadControl(context: Context): DefaultLoadControl {
        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
        val builder = DefaultLoadControl.Builder()
        if (isLowRam) {
            builder
                .setBufferDurationsMs(
                    /* minBufferMs = */ 10_000,
                    /* maxBufferMs = */ 30_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                )
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
        }
        return builder.build()
    }

    /**
     * A sheddable memory target (the thumbnail frame engine). Extracted as an
     * interface so the trim-tier logic is unit-testable without an Android Context.
     */
    fun interface TrimTarget {
        fun apply(tier: TrimTier)
    }

    enum class TrimTier { SHRINK, RELEASE }

    /**
     * Memory tier applied on `onTrimMemory`. MODERATE → shrink the thumbnail LRU;
     * LOW → also release the shared PreviewFrameEngine; CRITICAL → (foreground
     * player only — the service keeps the playback player).
     */
    fun onTrim(level: Int, target: TrimTarget?) {
        val tier = tierFor(level) ?: return
        target?.apply(tier)
    }

    /** The trim action for a given [ComponentCallbacks2][android.content.ComponentCallbacks2] level. */
    fun tierFor(level: Int): TrimTier? = when (level) {
        in MODERATE_RANGE -> TrimTier.SHRINK
        in LOW_RANGE -> TrimTier.RELEASE
        else -> null
    }

    // Trim-level mapping (ComponentCallbacks2 constants):
    //   RUNNING_MODERATE=5, RUNNING_LOW=10           → SHRINK the thumbnail LRU
    //   RUNNING_CRITICAL=15, MODERATE=60,
    //   COMPLETE=80, UI_HIDDEN=20                    → RELEASE the frame engine
    //   BACKGROUND=40 (process in background, not a
    //   memory-pressure signal we act on here)       → no-op
    private val MODERATE_RANGE = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
    )
    private val LOW_RANGE = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
    )
}

/** Adapts a [PreviewFrameEngine] to the [MemoryGovernor.TrimTarget] interface. */
internal fun PreviewFrameEngine.asTrimTarget(): MemoryGovernor.TrimTarget =
    MemoryGovernor.TrimTarget { tier ->
        when (tier) {
            MemoryGovernor.TrimTier.SHRINK -> trimToCount(keep = 8)
            MemoryGovernor.TrimTier.RELEASE -> release()
        }
    }
