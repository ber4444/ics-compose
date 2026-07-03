package com.livingpresence.inner.circle.squared

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler

private const val DOWNLOAD_WORK_ID = "ics-download-work"
private const val FOREGROUND_NOTIFICATION_ID = 4242
private const val CHANNEL_ID = "ics_downloads"

/**
 * Foreground [DownloadService] that drives the [DownloadCenter]'s
 * [DownloadManager]. Requirement-based restarts (e.g. wifi regained after
 * process death) route through [WorkManagerScheduler].
 */
@UnstableApi
class DownloadsService : DownloadService(FOREGROUND_NOTIFICATION_ID) {
    override fun getDownloadManager(): DownloadManager =
        DownloadCenter.get(this).downloadManager

    override fun getScheduler(): Scheduler =
        WorkManagerScheduler(applicationContext, DOWNLOAD_WORK_ID)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): android.app.Notification {
        ensureChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading events")
            .setOngoing(true)
        if (downloads.isNotEmpty()) {
            val active = downloads.count {
                it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
            }
            builder.setContentText("$active event(s) downloading")
        } else if (notMetRequirements != 0) {
            builder.setContentText("Waiting for Wi-Fi")
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
