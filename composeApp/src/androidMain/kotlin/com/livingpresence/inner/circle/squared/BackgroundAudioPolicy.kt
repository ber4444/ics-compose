package com.livingpresence.inner.circle.squared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Backgrounding policy (plan.md Scrutiny #9): with muxed HLS (video+audio in
 * the same TS segments), disabling the video renderer stops *decode* but not
 * *download* — the muxed segments keep streaming at full bitrate. Instead, when
 * the video is no longer visible (PiP without video, or background), we
 * constrain track selection so the player drops to the ladder's audio-only tier
 * (~51 kbps vs ~1 Mbps), real savings while the [MediaSession] keeps audio going.
 *
 * When video becomes visible again, the video track is re-enabled and ABR
 * resumes.
 *
 * Note: in PiP the video surface *is* usually still shown, so [isVideoVisible]
 * controls whether we constrain — the caller passes the actual visibility.
 */
@Composable
internal fun BackgroundAudioPolicy(player: Player, isVideoVisible: Boolean) {
    LaunchedEffect(player, isVideoVisible) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !isVideoVisible)
            .build()
    }
}
