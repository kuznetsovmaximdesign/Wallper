package com.wallper.live.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ExportJob(
    val id: UUID,
    val footageId: String?,
    val state: State,
    val progress: Float,
    val clipId: String?,
) {
    enum class State { Running, Succeeded, Failed }
}

class ExportScheduler(private val workManager: WorkManager) {

    fun enqueue(request: ExportRequest): UUID {
        val work = OneTimeWorkRequestBuilder<ClipExportWorker>()
            .setInputData(request.toData())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .apply { request.footageId?.let { addTag(FOOTAGE_TAG_PREFIX + it) } }
            .build()
        val name = request.footageId?.let { "export-$it" } ?: "export-${work.id}"
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        return work.id
    }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    val jobs: Flow<List<ExportJob>> = workManager.getWorkInfosByTagFlow(TAG).map { infos ->
        infos.map { info ->
            ExportJob(
                id = info.id,
                footageId = info.tags.firstOrNull { it.startsWith(FOOTAGE_TAG_PREFIX) }?.removePrefix(FOOTAGE_TAG_PREFIX),
                state = when (info.state) {
                    WorkInfo.State.SUCCEEDED -> ExportJob.State.Succeeded
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ExportJob.State.Failed
                    else -> ExportJob.State.Running
                },
                progress = info.progress.getFloat(PROGRESS, 0f),
                clipId = info.outputData.getString(CLIP_ID),
            )
        }
    }

    companion object {
        const val TAG = "export"
        private const val FOOTAGE_TAG_PREFIX = "footage:"
        const val PROGRESS = "progress"
        const val CLIP_ID = "clip_id"
    }
}
