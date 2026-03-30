package io.asphalt.sdk.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.asphalt.sdk.internal.AsphaltLog
import io.asphalt.sdk.storage.EventDatabase
import io.asphalt.sdk.storage.toModel
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker responsible for batch-uploading pending road events.
 *
 * ## WorkManager as the upload mechanism
 *
 * WorkManager is the correct Android API for guaranteed background work:
 * - It respects Doze mode and app standby
 * - It survives process restarts and device reboots
 * - It handles network constraints natively
 * - It provides exponential backoff on failure without manual retry logic
 *
 * ## Periodicity
 *
 * The worker is scheduled as a periodic task with a minimum interval of
 * 15 minutes (WorkManager's floor). The SDK's [AsphaltConfig.uploadIntervalSeconds]
 * sets the desired interval; intervals below 15 minutes are silently floored.
 *
 * A separate immediate work request can be enqueued by [scheduleImmediate]
 * when the local buffer exceeds [AsphaltConfig.maxBufferSize].
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = EventDatabase.getInstance(applicationContext)
        val dao = db.eventDao()

        val pending = dao.getPendingEvents(limit = 500)
        if (pending.isEmpty()) {
            AsphaltLog.d("UploadWorker", "No pending events. Skipping upload.")
            return Result.success()
        }

        val ingestUrl = inputData.getString(KEY_INGEST_URL)
            ?: return Result.failure()

        AsphaltLog.d("UploadWorker", "Uploading ${pending.size} events to $ingestUrl")

        val uploader = BatchUploader(ingestUrl)
        val result = uploader.upload(pending.map { it.toModel() })

        return if (result.success) {
            dao.markUploadedAndPrune(pending.map { it.eventId })
            AsphaltLog.d("UploadWorker", "Upload successful. Accepted: ${result.acceptedCount}")
            Result.success()
        } else {
            AsphaltLog.w("UploadWorker", "Upload failed (HTTP ${result.httpStatus}): ${result.errorMessage}")
            // Retry with exponential backoff for transient errors (5xx, network)
            if (result.httpStatus in 500..599 || result.httpStatus == -1) {
                Result.retry()
            } else {
                // 4xx errors are not retryable (bad data, auth failure)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_INGEST_URL = "ingest_url"
        private const val WORK_NAME_PERIODIC = "asphalt_periodic_upload"
        private const val WORK_NAME_IMMEDIATE = "asphalt_immediate_upload"

        fun schedulePeriodicUpload(
            context: Context,
            ingestUrl: String,
            intervalSeconds: Long,
            requireUnmeteredNetwork: Boolean
        ) {
            val networkType = if (requireUnmeteredNetwork) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()

            val intervalMinutes = (intervalSeconds / 60).coerceAtLeast(15L)

            val request = PeriodicWorkRequestBuilder<UploadWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_INGEST_URL, ingestUrl)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleImmediate(context: Context, ingestUrl: String) {
            val request = androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(KEY_INGEST_URL, ingestUrl)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_IMMEDIATE)
        }
    }
}
