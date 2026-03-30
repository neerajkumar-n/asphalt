package io.asphalt.sdk.model

import java.util.UUID

/**
 * A single detected road anomaly event.
 *
 * This is the canonical in-memory and on-disk representation within the SDK.
 * When uploaded, it maps directly to the event.schema.json contract.
 *
 * Design note: no PII fields exist here. latitude/longitude are the only
 * location data stored, and they describe road conditions, not the user.
 */
data class RoadEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val intensity: Float,          // 0.0 to 1.0
    val speedKmh: Float,
    val anomalyType: AnomalyType,
    val sensorSummary: SensorSummary,
    val deviceMeta: DeviceMeta,
    val sdkVersion: String,
    val sessionId: String
)

enum class AnomalyType(val value: String) {
    POTHOLE("pothole"),
    BUMP("bump"),
    ROUGH_PATCH("rough_patch"),
    UNKNOWN("unknown")
}

/**
 * Condensed sensor statistics from the detection window.
 * Raw samples are never stored beyond the sliding detection buffer.
 */
data class SensorSummary(
    val accelPeakZ: Float,
    val accelBaselineZ: Float,
    val accelDeltaZ: Float,
    val gyroPeakMagnitude: Float,
    val sampleCount: Int,
    val windowDurationMs: Long
)

/**
 * Anonymous device metadata for sensor calibration purposes.
 * No identifiers, no IMEI, no advertising IDs.
 */
data class DeviceMeta(
    val platform: String = "android",
    val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    val manufacturer: String = android.os.Build.MANUFACTURER,
    val model: String = android.os.Build.MODEL,
    val sensorVendor: String = ""
)
