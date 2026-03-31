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
    val vehicleType: VehicleType,
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
 * Vehicle category, set by the integrating application via [AsphaltConfig.vehicleType].
 *
 * This is not auto-detected. The app deployer sets it at SDK initialisation
 * time based on the known context (e.g. an auto rickshaw fleet management app
 * sets THREE_WHEELER; a consumer driving app defaults to FOUR_WHEELER).
 *
 * The value travels with every event to the backend where it drives signal
 * normalisation and cross-vehicle confidence scoring.
 */
enum class VehicleType(val value: String) {
    TWO_WHEELER("two_wheeler"),
    THREE_WHEELER("three_wheeler"),
    FOUR_WHEELER("four_wheeler")
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
