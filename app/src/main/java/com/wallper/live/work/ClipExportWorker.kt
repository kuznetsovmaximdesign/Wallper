package com.wallper.live.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallper.live.R
import com.wallper.live.WallperApplication
import com.wallper.live.core.Clip
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Cuts a wallpaper clip (download + remux) and adds it to the library. */
class ClipExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as WallperApplication).container

    override suspend fun doWork(): Result {
        val request = ExportRequest.fromData(inputData) ?: return Result.failure()
        runCatching { setForeground(foregroundInfo(request.title, 0)) }

        val clipId = UUID.randomUUID().toString()
        val video = container.library.newFile("$clipId.mp4")
        val cover = container.library.newFile("$clipId.jpg")
        var lastReported = -1
        return try {
            val exported = container.exporter.export(
                source = request.source,
                startMs = request.startMs,
                lengthMs = request.loopMs + request.fadeMs,
                output = video,
            ) { progress ->
                val percent = (progress * 100).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    setProgressAsync(workDataOf(ExportScheduler.PROGRESS to progress))
                    notify(request.title, percent)
                }
            }
            // A short source may yield less than requested: keep the fade proportional.
            val fadeMs = minOf(request.fadeMs, exported.durationMs / 4)
            val loopMs = exported.durationMs - fadeMs
            val hasCover = container.exporter.writeCover(video, loopMs / 3, cover)
            val clip = Clip(
                id = clipId,
                title = request.title,
                origin = request.origin,
                footageId = request.footageId,
                fileName = video.name,
                coverFileName = cover.name.takeIf { hasCover },
                loopMs = loopMs,
                fadeMs = fadeMs,
                width = exported.width,
                height = exported.height,
                createdAt = System.currentTimeMillis(),
            )
            container.library.add(clip)
            container.settings.setSpeed(clipId, request.speed)
            container.settings.setFocus(clipId, request.focus)
            request.assign?.let { container.settings.assign(clipId, it) }
            Result.success(workDataOf(ExportScheduler.CLIP_ID to clipId))
        } catch (e: CancellationException) {
            cleanUp(video, cover)
            throw e
        } catch (e: Exception) {
            cleanUp(video, cover)
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(ExportRequest.fromData(inputData)?.title.orEmpty(), 0)

    private fun cleanUp(vararg files: File) = files.forEach { it.delete() }

    private fun notify(title: String, percent: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(id.hashCode(), notification(title, percent)) }
    }

    private fun foregroundInfo(title: String, percent: Int) =
        ForegroundInfo(id.hashCode(), notification(title, percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(title: String, percent: Int): android.app.Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(title)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "export"
    }
}
