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

    @UnstableApi
    override fun onCreate() {
        super.onCreate()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this)
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
                        DefaultDataSource.Factory(
                            this,
                            DataSource.Factory { DataSchemeDataSource() },
                        ),
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
