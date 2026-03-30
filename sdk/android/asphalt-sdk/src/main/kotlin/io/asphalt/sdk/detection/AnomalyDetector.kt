package io.asphalt.sdk.detection

import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.SensorSummary
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
 *    forward without dropping, so the suspension extends and the car
 *    body *rises* relative to the wheel. The accelerometer sees a brief
 *    REDUCTION in Z (the "dip") -- gravity component decreases as the
 *    body accelerates upward relative to the ground.
 * 2. The tyre impacts the far edge of the pothole and rebounds upward.
 *    The car body is then pushed sharply upward. The accelerometer sees
 *    a sharp INCREASE in Z above 9.81 (the "spike").
 * 3. The suspension absorbs the rebound and Z returns to baseline.
 *
 * The full signature: baseline -> dip -> spike -> return.
 * Time span at 50 km/h: roughly 150-400ms.
 *
 * Speed affects interpretation: the same pothole at 30 km/h may read
 * 3 m/s^2 delta; at 80 km/h, the same pothole may read 8 m/s^2 delta.
 * Intensity is therefore speed-normalised.
 *
 * ### Speed bump signature (dip-spike)
 *
 * Speed bumps (intentional) create the inverse order: the front wheel
 * climbs the bump face (Z increases = spike first), then drops off the
 * back (Z decreases = dip). This ordering difference, combined with
 * the longer duration, helps classify bump vs pothole.
 *
 * ### Gyroscope role
 *
 * Gyroscope measures angular velocity (rad/s) around the three axes.
 * A real road anomaly causes pitching (rotation around Y axis) and
 * sometimes rolling (X axis). Pure sensor noise typically shows near-zero
 * gyro magnitude even when the accelerometer records a spike. Requiring
 * gyro magnitude > threshold filters out:
 * - Phone vibrations from music
 * - Cargo shifts inside the vehicle
 * - Engine idle vibration at low speed
 *
 * ### Speed filter
 *
 * At speeds below 15 km/h:
 * - Pedestrian walking gait creates Z-axis oscillations of 2-4 m/s^2
 * - Slow vehicle manoeuvres over kerbs are difficult to distinguish
 *   from genuine potholes
 * - The phone is more likely to be hand-held, changing orientation
 *
 * The SDK does not activate sensor collection until GPS speed exceeds
 * the configured threshold, eliminating this noise class entirely.
 */
class AnomalyDetector(private val config: AsphaltConfig) {

    private val accelBuffer = SlidingWindowBuffer(capacitySamples = 256)
    private val gyroBuffer = SlidingWindowBuffer(capacitySamples = 256)

    // Cooldown after detecting an event to avoid duplicate reports
    private var lastEventTimestampMs: Long = 0L
    private val cooldownMs: Long = 1500L

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
    }

    /**
     * Feed a new gyroscope sample into the detector.
     *
     * @param timestampMs System uptime in milliseconds
     * @param x Angular velocity around X axis (rad/s)
     * @param y Angular velocity around Y axis (rad/s)
     * @param z Angular velocity around Z axis (rad/s)
     */
    fun feedGyroscope(timestampMs: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt(x * x + y * y + z * z)
        gyroBuffer.add(timestampMs, magnitude)
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

        // Enforce cooldown between events
        if (currentTimeMs - lastEventTimestampMs < cooldownMs) {
            return noEvent
        }

        val windowSamples = accelBuffer.snapshotInWindow(config.detectionWindowMs)
        if (windowSamples.size < 10) {
            return noEvent
        }

        // Compute rolling baseline from samples older than the window
        val baseline = accelBuffer.rollingMedian(lastN = 64)

        val peakZ = windowSamples.maxOf { it.value }
        val minZ = windowSamples.minOf { it.value }
        val deltaFromPeak = abs(peakZ - baseline)
        val deltaFromDip = abs(minZ - baseline)

        // Primary threshold check: peak OR dip must exceed threshold
        val maxDelta = maxOf(deltaFromPeak, deltaFromDip)
        if (maxDelta < config.detectionThresholdMs2) {
            return noEvent
        }

        // Gyroscope confirmation
        val gyroSamples = gyroBuffer.snapshotInWindow(config.detectionWindowMs)
        val gyroPeak = if (gyroSamples.isNotEmpty()) gyroSamples.maxOf { it.value } else 0f

        if (gyroPeak < config.gyroConfirmationThresholdRadS) {
            // The accelerometer spiked but gyroscope shows no physical motion.
            // This is likely engine vibration, music, or a loose sensor mount.
            return noEvent
        }

        // Classify anomaly type by signature shape
        val anomalyType = classifySignature(windowSamples, baseline, peakZ, minZ)

        // Intensity: normalise delta by a reference value and clamp to [0, 1]
        // Speed normalisation: higher speed yields higher G-forces for same anomaly,
        // so we reduce intensity slightly at very high speeds to avoid over-reporting.
        val referenceMs2 = 12f
        val rawIntensity = (maxDelta / referenceMs2).coerceIn(0f, 1f)
        val speedFactor = when {
            speedKmh > 100f -> 0.85f
            speedKmh > 60f -> 1.0f
            speedKmh > 30f -> 1.1f
            else -> 1.2f  // slower speed -> same delta is more impactful
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
     * Rough patch: neither peak is dominant; variance is high throughout.
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

        // Neither extreme is large enough to classify
        if (deltaSpike < 2f && deltaDip < 2f) return AnomalyType.ROUGH_PATCH

        return when {
            dipIdx >= 0 && peakIdx >= 0 && dipIdx < peakIdx && deltaDip > 1.5f -> AnomalyType.POTHOLE
            dipIdx >= 0 && peakIdx >= 0 && peakIdx < dipIdx && deltaSpike > 1.5f -> AnomalyType.BUMP
            else -> AnomalyType.UNKNOWN
        }
    }

    fun reset() {
        accelBuffer.clear()
        gyroBuffer.clear()
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
