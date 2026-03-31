package io.asphalt.sdk.detection

import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.SensorSummary
import io.asphalt.sdk.model.VehicleType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Core anomaly detection engine.
 *
 * ## Sensor Physics
 *
 * The accelerometer measures the specific force experienced by the device,
 * which at rest equals gravity (~9.81 m/s^2 on the Z axis when the phone
 * lies flat). While driving on a flat road, Z hovers near 9.81 with low
 * variance.
 *
 * ### Pothole signature (spike-dip)
 *
 * When a wheel hits a pothole:
 * 1. The tyre drops into the hole. The car body momentarily continues
 *    forward without dropping, so the suspension extends. The accelerometer
 *    sees a REDUCTION in Z (the "dip").
 * 2. The tyre impacts the far edge and rebounds. The accelerometer sees
 *    a sharp INCREASE in Z above 9.81 (the "spike").
 * 3. The suspension absorbs the rebound and Z returns to baseline.
 *
 * Time span at 50 km/h: roughly 150-400ms.
 *
 * ### Speed bump signature (spike before dip)
 *
 * The front wheel climbs the bump face first (Z increases = spike first),
 * then drops off the back (Z decreases = dip). Temporal ordering of peak
 * vs. dip distinguishes pothole from bump.
 *
 * ### Gyroscope role
 *
 * Gyroscope measures angular velocity (rad/s) around the three axes.
 * A real road anomaly causes pitching (gyro Y axis) and sometimes rolling
 * (gyro X axis). Pure sensor noise shows near-zero gyro magnitude.
 *
 * ### Vehicle-specific processing
 *
 * Detection parameters are driven by [VehicleProfile], which is selected
 * based on [AsphaltConfig.vehicleType]. See [VehicleProfile] for a detailed
 * explanation of why each vehicle class requires different thresholds.
 *
 * For three-wheelers, an additional [ThreeWheelerFilter] is applied before
 * reporting any event. The filter suppresses:
 * - Periodic engine vibration (zero-crossing rate check)
 * - Turn dynamics (sustained lateral gyro elevation)
 * - Structural lateral wobble (sustained roll without yaw)
 *
 * ### Speed filter
 *
 * At speeds below the configured threshold (default 15 km/h):
 * - Pedestrian walking gait: ~2-4 m/s^2 Z oscillation at ~2Hz
 * - Slow vehicle manoeuvres: difficult to distinguish from genuine anomalies
 * The SDK does not activate sensor collection until GPS speed exceeds this
 * threshold.
 */
class AnomalyDetector(private val config: AsphaltConfig) {

    private val profile: VehicleProfile = VehicleProfile.forVehicleType(config.vehicleType)

    private val accelBuffer = SlidingWindowBuffer(capacitySamples = 256)
    private val gyroMagBuffer = SlidingWindowBuffer(capacitySamples = 256)

    // Lateral components stored separately for three-wheeler turn/wobble detection
    private val gyroRollBuffer = SlidingWindowBuffer(capacitySamples = 256)  // gyro X
    private val gyroYawBuffer = SlidingWindowBuffer(capacitySamples = 256)   // gyro Z

    // Three-wheeler specific filter; null for other vehicle types
    private val threeWheelerFilter: ThreeWheelerFilter? =
        if (config.vehicleType == VehicleType.THREE_WHEELER) ThreeWheelerFilter(profile) else null

    private var lastEventTimestampMs: Long = 0L

    data class DetectionResult(
        val detected: Boolean,
        val anomalyType: AnomalyType,
        val intensity: Float,
        val sensorSummary: SensorSummary
    )

    /**
     * Feed a new accelerometer Z-axis sample into the detector.
     *
     * @param timestampMs System uptime in milliseconds
     * @param z Raw Z-axis value in m/s^2 (includes gravity)
     */
    fun feedAccelerometer(timestampMs: Long, z: Float) {
        accelBuffer.add(timestampMs, z)
        threeWheelerFilter?.feedAccelZ(timestampMs, z)
    }

    /**
     * Feed a new gyroscope sample into the detector.
     *
     * All three axes are accepted. The magnitude is used for the standard
     * gyro confirmation check. Lateral axes (X = roll, Z = yaw) are used
     * by the three-wheeler filter for turn and wobble suppression.
     *
     * @param timestampMs System uptime in milliseconds
     * @param x Angular velocity around X axis (roll, rad/s)
     * @param y Angular velocity around Y axis (pitch, rad/s)
     * @param z Angular velocity around Z axis (yaw, rad/s)
     */
    fun feedGyroscope(timestampMs: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z)
        gyroMagBuffer.add(timestampMs, magnitude)
        gyroRollBuffer.add(timestampMs, x)
        gyroYawBuffer.add(timestampMs, z)
        threeWheelerFilter?.feedLateralGyro(timestampMs, rollRadS = x, yawRadS = z)
    }

    /**
     * Attempt to detect an anomaly using the current buffer contents.
     *
     * Should be called after each accelerometer sample. Returns a result
     * indicating whether an anomaly was found and its characteristics.
     *
     * @param currentTimeMs Current system uptime in milliseconds
     * @param speedKmh Current GPS speed, used for speed-normalised intensity
     */
    fun evaluate(currentTimeMs: Long, speedKmh: Float): DetectionResult {
        val noEvent = DetectionResult(
            detected = false,
            anomalyType = AnomalyType.UNKNOWN,
            intensity = 0f,
            sensorSummary = emptySummary()
        )

        // Enforce vehicle-profile cooldown between events
        if (currentTimeMs - lastEventTimestampMs < profile.cooldownMs) {
            return noEvent
        }

        val windowSamples = accelBuffer.snapshotInWindow(config.detectionWindowMs)
        if (windowSamples.size < 10) {
            return noEvent
        }

        // Rolling baseline: use the vehicle-profile-specific window size.
        // Noisier vehicles (three-wheelers) use a longer window so the
        // median is stable against the periodic engine vibration.
        val baseline = accelBuffer.rollingMedian(lastN = profile.baselineWindowSamples)

        val peakZ = windowSamples.maxOf { it.value }
        val minZ = windowSamples.minOf { it.value }
        val deltaFromPeak = abs(peakZ - baseline)
        val deltaFromDip = abs(minZ - baseline)
        val maxDelta = maxOf(deltaFromPeak, deltaFromDip)

        // Primary threshold: vehicle-profile-specific
        if (maxDelta < profile.detectionThresholdMs2) {
            return noEvent
        }

        // Standard gyro confirmation: applies to all vehicle types
        val gyroSamples = gyroMagBuffer.snapshotInWindow(config.detectionWindowMs)
        val gyroPeak = if (gyroSamples.isNotEmpty()) gyroSamples.maxOf { it.value } else 0f

        if (gyroPeak < profile.gyroConfirmationThresholdRadS) {
            // Accelerometer spiked but gyroscope shows no physical rotation.
            // Likely engine vibration, music, or a loose phone mount.
            return noEvent
        }

        // Three-wheeler specific filter: checks for engine vibration, turns,
        // and lateral wobble patterns before accepting the event.
        val filterResult = threeWheelerFilter?.evaluate(currentTimeMs)
        if (filterResult?.suppressed == true) {
            return noEvent
        }

        // Classify anomaly type by temporal ordering of dip vs spike
        val anomalyType = classifySignature(windowSamples, baseline, peakZ, minZ)

        // Intensity: normalise delta by a reference value and clamp to [0, 1].
        // Speed normalisation: higher speed yields higher G-forces for the
        // same anomaly; reduce intensity slightly at very high speeds.
        val referenceMs2 = 12f
        val rawIntensity = (maxDelta / referenceMs2).coerceIn(0f, 1f)
        val speedFactor = when {
            speedKmh > 100f -> 0.85f
            speedKmh > 60f -> 1.0f
            speedKmh > 30f -> 1.1f
            else -> 1.2f
        }
        val intensity = (rawIntensity * speedFactor).coerceIn(0f, 1f)

        val summary = SensorSummary(
            accelPeakZ = peakZ,
            accelBaselineZ = baseline,
            accelDeltaZ = maxDelta,
            gyroPeakMagnitude = gyroPeak,
            sampleCount = windowSamples.size,
            windowDurationMs = config.detectionWindowMs
        )

        lastEventTimestampMs = currentTimeMs

        return DetectionResult(
            detected = true,
            anomalyType = anomalyType,
            intensity = intensity,
            sensorSummary = summary
        )
    }

    /**
     * Classify signature by examining the temporal ordering of dip vs spike.
     *
     * Pothole: dip arrives before spike (wheel falls in, then rebounds).
     * Bump: spike arrives before dip (wheel climbs, then drops).
     * Rough patch: no dominant single peak; variance is elevated throughout.
     */
    private fun classifySignature(
        samples: List<SlidingWindowBuffer.Sample>,
        baseline: Float,
        peakZ: Float,
        minZ: Float
    ): AnomalyType {
        if (samples.isEmpty()) return AnomalyType.UNKNOWN

        val peakIdx = samples.indexOfFirst { it.value == peakZ }
        val dipIdx = samples.indexOfFirst { it.value == minZ }
        val deltaSpike = abs(peakZ - baseline)
        val deltaDip = abs(minZ - baseline)

        if (deltaSpike < 2f && deltaDip < 2f) return AnomalyType.ROUGH_PATCH

        return when {
            dipIdx >= 0 && peakIdx >= 0 && dipIdx < peakIdx && deltaDip > 1.5f -> AnomalyType.POTHOLE
            dipIdx >= 0 && peakIdx >= 0 && peakIdx < dipIdx && deltaSpike > 1.5f -> AnomalyType.BUMP
            else -> AnomalyType.UNKNOWN
        }
    }

    fun reset() {
        accelBuffer.clear()
        gyroMagBuffer.clear()
        gyroRollBuffer.clear()
        gyroYawBuffer.clear()
        threeWheelerFilter?.reset()
        lastEventTimestampMs = 0L
    }

    private fun emptySummary() = SensorSummary(
        accelPeakZ = 0f,
        accelBaselineZ = 0f,
        accelDeltaZ = 0f,
        gyroPeakMagnitude = 0f,
        sampleCount = 0,
        windowDurationMs = 0L
    )
}
