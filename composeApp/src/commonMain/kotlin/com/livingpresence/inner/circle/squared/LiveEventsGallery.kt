package com.livingpresence.inner.circle.squared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livingpresence.mediakit.EventInfo
import kotlin.math.ceil

/**
 * The feed of available live/recorded events, replacing the old VideosDialog.
 *
 * Shows loading / error+retry / empty / populated states. When populated, events
 * render as a horizontal feed of 16:9 tiles (a 2-row grid when more than will
 * fit on one row), each with a poster thumbnail, title, and a LIVE badge (when
 * [EventInfo.isLive]) or a duration label. Tapping a tile plays the event.
 */
@Composable
fun LiveEventsGallery(
    events: List<EventInfo>,
    isLoading: Boolean,
    error: String?,
    onPlayEvent: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Per-event download state; when non-null the tile shows a download affordance. */
    downloadStates: Map<Int, EventDownloadState>? = null,
    onDownload: ((EventInfo) -> Unit)? = null,
    onRemoveDownload: ((Int) -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading && events.isEmpty() -> {
                CircularProgressIndicator()
            }

            error != null && events.isEmpty() -> {
                GalleryErrorState(error = error, onRetry = onRetry)
            }

            events.isNotEmpty() -> {
                EventFeed(
                    events = events,
                    onPlayEvent = onPlayEvent,
                    downloadStates = downloadStates,
                    onDownload = onDownload,
                    onRemoveDownload = onRemoveDownload,
                )
            }

            else -> {
                Text(
                    text = "No events available",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun EventFeed(
    events: List<EventInfo>,
    onPlayEvent: (Int) -> Unit,
    downloadStates: Map<Int, EventDownloadState>?,
    onDownload: ((EventInfo) -> Unit)?,
    onRemoveDownload: ((Int) -> Unit)?,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        // One row if the events fit, two rows once they exceed a single screen's
        // worth (keeps the feed scannable for the ~20-event catalog).
        val tileWidth = 220.dp
        val gap = 12.dp
        val perRow = (maxWidth / (tileWidth + gap)).toInt().coerceAtLeast(1)
        val rowCount = if (events.size > perRow * 2) 2 else 1

        if (rowCount == 1) {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(events, key = { it.eventNumber }) { event ->
                    LiveEventTile(
                        event = event,
                        onPlayEvent = onPlayEvent,
                        downloadState = downloadStates?.get(event.eventNumber),
                        onDownload = onDownload,
                        onRemoveDownload = onRemoveDownload,
                        modifier = Modifier.width(tileWidth),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                val chunked = chunkBalanced(events, 2)
                for (row in chunked) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        items(row, key = { it.eventNumber }) { event ->
                            LiveEventTile(
                                event = event,
                                onPlayEvent = onPlayEvent,
                                downloadState = downloadStates?.get(event.eventNumber),
                                onDownload = onDownload,
                                onRemoveDownload = onRemoveDownload,
                                modifier = Modifier.width(tileWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Split [items] into [rows] row-lists as evenly as possible, preserving order. */
private fun <T> chunkBalanced(items: List<T>, rows: Int): List<List<T>> {
    if (items.isEmpty()) return List(rows) { emptyList() }
    val perRow = ceil(items.size.toDouble() / rows).toInt()
    return items.chunked(perRow).let { chunks ->
        if (chunks.size < rows) chunks + List(rows - chunks.size) { emptyList() }
        else chunks
    }
}

/**
 * One event tile: thumbnail (platform-provided), title, LIVE badge or duration,
 * and a download affordance for bounded (non-live) events when a download
 * controller is available.
 */
@Composable
private fun LiveEventTile(
    event: EventInfo,
    onPlayEvent: (Int) -> Unit,
    modifier: Modifier = Modifier,
    downloadState: EventDownloadState? = null,
    onDownload: ((EventInfo) -> Unit)? = null,
    onRemoveDownload: ((Int) -> Unit)? = null,
) {
    val tileShape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .clip(tileShape)
            .clickable { onPlayEvent(event.eventNumber) },
        color = Color.Black.copy(alpha = 0.7f),
        shape = tileShape,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                LiveEventThumbnail(
                    eventNumber = event.eventNumber,
                    contentDescription = "Event ${event.eventNumber} thumbnail",
                    modifier = Modifier.fillMaxSize(),
                )
                if (event.isLive) {
                    LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Event ${event.eventNumber}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (event.isLive) "Live now" else formatDuration(event.durationMs),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!event.isLive && onDownload != null) {
                    DownloadAffordance(
                        state = downloadState,
                        onDownload = { onDownload(event) },
                        onRemove = { onRemoveDownload?.invoke(event.eventNumber) },
                    )
                }
            }
        }
    }
}

/**
 * Download button reflecting state: not-downloaded (download), in-progress
 * (percentage ring), completed (check / remove), failed (retry).
 */
@Composable
private fun DownloadAffordance(
    state: EventDownloadState?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = state?.state ?: DownloadStatus.NOT_DOWNLOADED
    when (status) {
        DownloadStatus.COMPLETED -> {
            TextButton(onClick = onRemove) {
                Text("✓", color = Color(0xFFB9F6CA))
            }
        }
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
            val percent = state?.percent ?: 0f
            TextButton(onClick = onRemove) {
                Text(
                    "${percent.toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        DownloadStatus.FAILED, DownloadStatus.NOT_DOWNLOADED, DownloadStatus.REMOVING -> {
            TextButton(onClick = onDownload) {
                Text("⬇", color = Color.White)
            }
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFFEF5350),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = "LIVE",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GalleryErrorState(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Error: $error",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "Recorded"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${pad2(minutes)}:${pad2(seconds)}"
    } else {
        "$minutes:${pad2(seconds)}"
    }
}

private fun pad2(value: Long): String =
    if (value < 10L) "0$value" else value.toString()
