package com.livingpresence.inner.circle.squared

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the playback [ExoPlayer] + [MediaSession] so playback survives config
 * changes and continues in the background (audio-only tier) or PiP.
 *
 * The foreground player screen binds via a [androidx.media3.session.MediaController]
 * (which implements [androidx.media3.common.Player], so it renders to a surface).
 * The screen resolves the ABR ladder just-in-time and hands the service a
 * `data:`-URI media item (the synthesized multivariant playlist); this service
 * player reads it via [DataSchemeDataSource] so genuine ABR keeps working under
 * service ownership.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /**
     * Builds a [androidx.media3.datasource.DataSource.Factory] that prefers the
     * download [SimpleCache], then falls back to HTTP, with `data:` scheme support
     * for the synthesized-ladder multivariant playlist. Downloaded events thus
     * play from disk (airplane-mode playback is the acceptance test).
     */
    @UnstableApi
    private fun playbackDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        val upstream = DefaultDataSource.Factory(
            this,
            DataSource.Factory { DataSchemeDataSource() },
        )
        return runCatching { DownloadCenter.get(this).cacheDataSourceFactory() }
            .getOrElse { upstream }
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
        // FU-5: viewport-aware ABR. Cap the highest decoded video to what the
        // physical display can actually show, so the player never decodes a
        // rendition larger than the screen (saves bandwidth + decode on the
        // synthesized ladder and demo streams). `setViewportSizeToPhysicalDisplaySize`
        // is the media3 1.10 Builder API that reads the display size from the
        // service Context across all API levels (no manual WindowMetrics wiring).
        // No-op when a master advertises a single variant ≤ display (this server's
        // one-720p master), correct on multi-rung ladders. `orientationMayChange`
        // keeps the constraint valid after rotation by taking the max orientation.
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setViewportSizeToPhysicalDisplaySize(
                    /* context = */ this,
                    /* orientationMayChange = */ true,
                )
                .build(),
        )
        val player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(MemoryGovernor.adaptiveLoadControl(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        // Prefer the download cache for playback (downloaded events
                        // play from disk — airplane-mode is the acceptance test),
                        // falling back to HTTP, with data: scheme support for the
                        // synthesized-ladder multivariant playlist.
                        playbackDataSourceFactory(),
                    ),
            )
            .build()

        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val sessionBuilder = MediaSession.Builder(this, player)
        sessionActivityIntent?.let { sessionBuilder.setSessionActivity(it) }
        session = sessionBuilder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
