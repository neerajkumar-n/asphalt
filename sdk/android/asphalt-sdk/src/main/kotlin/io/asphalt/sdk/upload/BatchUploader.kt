package io.asphalt.sdk.upload

import io.asphalt.sdk.model.RoadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Serialises and POSTs a batch of road events to the ingestion API.
 *
 * Uses only the standard library (no OkHttp, no Retrofit) to keep the
 * SDK dependency footprint minimal. Integrators who already have OkHttp
 * in their project can swap this implementation without changing the
 * public API.
 *
 * ## Retry policy
 *
 * Retry is handled by WorkManager (see UploadWorker) using exponential
 * backoff. This class does a single attempt and returns success/failure.
 * Transient failures (5xx, network timeout) should be retried by the caller.
 *
 * ## Idempotency
 *
 * Each batch has a stable [batchId] (UUID). The server deduplicates on
 * this field, so re-submitting the same batch after a timeout is safe.
 */
class BatchUploader(private val ingestUrl: String) {

    data class UploadResult(
        val success: Boolean,
        val batchId: String,
        val acceptedCount: Int,
        val httpStatus: Int,
        val errorMessage: String? = null
    )

    suspend fun upload(events: List<RoadEvent>): UploadResult = withContext(Dispatchers.IO) {
        val batchId = UUID.randomUUID().toString()
        val payload = buildBatchJson(batchId, events)

        var connection: HttpURLConnection? = null
        return@withContext try {
            connection = (URL(ingestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "AsphaltSDK/1.0 Android")
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val status = connection.responseCode
            if (status == 200 || status == 201) {
                val body = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                UploadResult(
                    success = true,
                    batchId = batchId,
                    acceptedCount = json.optInt("accepted_count", events.size),
                    httpStatus = status
                )
            } else {
                val error = runCatching { connection.errorStream.bufferedReader().readText() }
                    .getOrDefault("HTTP $status")
                UploadResult(
                    success = false,
                    batchId = batchId,
                    acceptedCount = 0,
                    httpStatus = status,
                    errorMessage = error
                )
            }
        } catch (e: Exception) {
            UploadResult(
                success = false,
                batchId = batchId,
                acceptedCount = 0,
                httpStatus = -1,
                errorMessage = e.message
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildBatchJson(batchId: String, events: List<RoadEvent>): String {
        val batch = JSONObject().apply {
            put("batch_id", batchId)
            put("submitted_at_ms", System.currentTimeMillis())
            put("events", JSONArray().also { arr ->
                events.forEach { arr.put(eventToJson(it)) }
            })
        }
        return batch.toString()
    }

    private fun eventToJson(event: RoadEvent): JSONObject = JSONObject().apply {
        put("event_id", event.eventId)
        put("timestamp_ms", event.timestampMs)
        put("latitude", event.latitude)
        put("longitude", event.longitude)
        put("accuracy_m", event.accuracyMeters)
        put("intensity", event.intensity)
        put("speed_kmh", event.speedKmh)
        put("anomaly_type", event.anomalyType.value)
        put("vehicle_type", event.vehicleType.value)
        put("sensor_summary", JSONObject().apply {
            put("accel_peak_z", event.sensorSummary.accelPeakZ)
            put("accel_baseline_z", event.sensorSummary.accelBaselineZ)
            put("accel_delta_z", event.sensorSummary.accelDeltaZ)
            put("gyro_peak_magnitude", event.sensorSummary.gyroPeakMagnitude)
            put("sample_count", event.sensorSummary.sampleCount)
            put("window_duration_ms", event.sensorSummary.windowDurationMs)
        })
        put("device_meta", JSONObject().apply {
            put("platform", event.deviceMeta.platform)
            put("sdk_int", event.deviceMeta.sdkInt)
            put("manufacturer", event.deviceMeta.manufacturer)
            put("model", event.deviceMeta.model)
            put("sensor_vendor", event.deviceMeta.sensorVendor)
        })
        put("sdk_version", event.sdkVersion)
        put("session_id", event.sessionId)
    }
}
