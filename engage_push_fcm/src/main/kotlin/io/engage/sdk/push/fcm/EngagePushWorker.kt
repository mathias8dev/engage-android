package io.engage.sdk.push.fcm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.engage.sdk.EngageLogger
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class PushWorkOutcome { PROCESSED, NOT_READY }

internal object PushWorkScheduler {
    private const val INPUT_PAYLOAD = "engage_push_payload"
    private const val UNIQUE_WORK_PREFIX = "engage-push-delivery-"
    private const val ENQUEUE_TIMEOUT_SECONDS = 8L

    fun enqueue(context: Context, data: Map<String, String>): Boolean {
        val payload = EngagePushPayload.from(data) ?: return false
        val request = OneTimeWorkRequestBuilder<EngagePushWorker>()
            .setInputData(Data.Builder().putString(INPUT_PAYLOAD, JSONObject(data).toString()).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_PREFIX + payload.deliveryId)
            .build()
        return try {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(payload.deliveryId), ExistingWorkPolicy.KEEP, request)
                .result
                .get(ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            EngageLogger.info(
                "Push",
                "durable delivery work enqueued deliveryId=${payload.deliveryId} workId=${request.id}",
            )
            true
        } catch (error: Exception) {
            EngageLogger.error(
                "Push",
                "durable delivery work enqueue failed deliveryId=${payload.deliveryId}",
                error,
            )
            false
        }
    }

    fun decode(inputData: Data): Map<String, String>? = inputData.getString(INPUT_PAYLOAD)?.let { serialized ->
        runCatching {
            val json = JSONObject(serialized)
            json.keys().asSequence().associateWith { key -> json.getString(key) }
        }.onFailure { error ->
            EngageLogger.warn("Push", "durable delivery work payload decode failed", error)
        }.getOrNull()
    }

    fun workName(deliveryId: String): String = UNIQUE_WORK_PREFIX + deliveryId
}

public class EngagePushWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val data = PushWorkScheduler.decode(inputData) ?: return Result.failure()
        val payload = EngagePushPayload.from(data) ?: return Result.failure()
        EngageLogger.info(
            "Push",
            "durable delivery work started deliveryId=${payload.deliveryId} attempt=$runAttemptCount",
        )
        return try {
            when (EngagePushModule.processMessage(data)) {
                PushWorkOutcome.PROCESSED -> {
                    EngageLogger.info("Push", "durable delivery work completed deliveryId=${payload.deliveryId}")
                    Result.success()
                }
                PushWorkOutcome.NOT_READY -> Result.retry()
            }
        } catch (error: Exception) {
            EngageLogger.warn(
                "Push",
                "durable delivery work failed deliveryId=${payload.deliveryId} attempt=$runAttemptCount",
                error,
            )
            Result.retry()
        }
    }
}
