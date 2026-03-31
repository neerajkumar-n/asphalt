package io.asphalt.sdk.detection

import io.asphalt.sdk.internal.AsphaltLog
import kotlin.math.abs

/**
 * Suppression filter for signals that are normal auto rickshaw dynamics,
 * not road anomalies.
 *
 * Auto rickshaws generate three categories of false-positive signals:
 *
 * ## 1. Engine Vibration (periodic Z-axis oscillation)
 *
 * A 3-cylinder 4-stroke CNG/petrol engine at 1500-3000 RPM produces power
 * strokes at:
 *   - 1500 RPM: 1500/60 * 3/2 = 37.5 Hz
 *   - 2500 RPM: 1500/60 * 3/2 = 62.5 Hz
 *   (4-stroke: 2 power strokes per revolution per cylinder, 3 cylinders)
 *
 * Older 2-stroke engines (still common in India) produce:
 *   - 2000 RPM: 2000/60 * 3 = 100 Hz (but attenuated through frame to ~20-25 Hz felt)
 *
 * The mechanical resonance of the lightweight tubular frame amplifies
 * specific harmonics. In practice, the dominant felt frequency through the
 * phone mount is 15-30 Hz. At 50Hz sampling this is undersampled (the
 * Nyquist frequency is 25Hz) but the aliased waveform still produces a
 * periodic pattern.
 *
 * Detection: Count zero-crossings of the AC component (Z minus baseline)
 * in the last 500ms window. Genuine impacts produce 2-6 crossings.
 * Engine vibration produces 15-40+ crossings. Values above the profile
 * threshold indicate vibration, not a real anomaly.
 *
 * ## 2. Turning (yaw + roll combined)
 *
 * An auto negotiating a typical urban turn:
 * - Speed: 15-25 km/h
 * - Turn radius: 8-15 m
 * - Centripetal acceleration: v^2/r = (20/3.6)^2/10 = 3.1 m/s^2 lateral
 * - The body rolls outward: gyro X (roll) reaches 0.5-0.8 rad/s
 * - The vehicle yaws: gyro Z (yaw) reaches 0.4-0.7 rad/s
 *
 * The COMBINED effect on the accelerometer: Z is slightly reduced (body
 * tilting) and X increases. This combination can cross the detection
 * threshold, particularly on rough urban roads during a turn.
 *
 * Detection: if yaw rate (gyro Z) or the combined lateral magnitude exceeds
 * [VehicleProfile.turnSuppressionThresholdRadS] for more than
 * [VehicleProfile.sustainedLateralMinDurationMs], suppress event detection
 * for [VehicleProfile.turnSuppressionDurationMs] after the turn ends.
 *
 * ## 3. Structural Lateral Wobble
 *
 * At 15-30 km/h on uneven surfaces (common in Indian cities), the single-
 * front-wheel geometry of an auto causes a characteristic side-to-side
 * oscillation: the front wheel tracks irregularities and the body sways.
 * This produces a sustained gyro roll signal of 0.3-0.5 rad/s for
 * several seconds, even on roads with no significant anomalies.
 *
 * This is distinct from a turn (no yaw component) and distinct from a
 * pothole impact (sustained, not a brief spike). Detection uses the
 * same turn suppression mechanism: sustained lateral elevation triggers
 * suppression.
 *
 * ## What this filter does NOT suppress
 *
 * - Genuine pothole impacts: these produce a brief (50-200ms) lateral spike
 *   that is shorter than [VehicleProfile.sustainedLateralMinDurationMs].
 * - Speed bumps: the forward pitch component (gyro Y) is large, while
 *   the lateral (gyro X and Z) is moderate. The combined magnitude check
 *   in [AnomalyDetector] handles these.
 *
 * ## Applicability
 *
 * This filter is instantiated only when [VehicleProfile.vehicleType] is
 * [VehicleType.THREE_WHEELER]. It is called from [AnomalyDetector.evaluate].
 * For other vehicle types, the standard gyro confirmation path is used,
 * which already handles turning and vibration for cars and bikes.
 */
class ThreeWheelerFilter(private val profile: VehicleProfile) {

    // Lateral gyro components tracked separately from the magnitude
    private val rollBuffer = SlidingWindowBuffer(capacitySamples = 256)   // gyro X
    private val yawBuffer = SlidingWindowBuffer(capacitySamples = 256)    // gyro Z
    private val accelZBuffer = SlidingWindowBuffer(capacitySamples = 256)  // for zero-crossing

    // Turn suppression state
    private var suppressUntilMs: Long = 0L

    data class FilterResult(
        val suppressed: Boolean,
        val reason: SuppressReason?
    )

    enum class SuppressReason {
        ENGINE_VIBRATION,
        TURNING,
        LATERAL_WOBBLE
    }

    fun feedLateralGyro(timestampMs: Long, rollRadS: Float, yawRadS: Float) {
        rollBuffer.add(timestampMs, rollRadS)
        yawBuffer.add(timestampMs, yawRadS)
    }

    fun feedAccelZ(timestampMs: Long, z: Float) {
        accelZBuffer.add(timestampMs, z)
    }

    /**
     * Evaluate whether the current signal state should suppress an event.
     *
     * Called from [AnomalyDetector.evaluate] before reporting a detection.
     * Returns [FilterResult.suppressed] = true if the signal is explained
     * by normal auto dynamics.
     */
    fun evaluate(currentTimeMs: Long): FilterResult {
        // Check active suppression window (from a recently detected turn)
        if (currentTimeMs < suppressUntilMs) {
            return FilterResult(suppressed = true, reason = SuppressReason.TURNING)
        }

        // --- Engine vibration check ---
        val zeroCrossThreshold = profile.engineVibrationZeroCrossingsPerSecond
        if (zeroCrossThreshold != null) {
            val crossingsPerSec = countZeroCrossingsPerSec(accelZBuffer, windowMs = 500L)
            if (crossingsPerSec > zeroCrossThreshold) {
                AsphaltLog.d("ThreeWheelerFilter",
                    "Engine vibration detected: ${"%.1f".format(crossingsPerSec)} zero-crossings/sec")
                return FilterResult(suppressed = true, reason = SuppressReason.ENGINE_VIBRATION)
            }
        }

        // --- Turn and lateral wobble check ---
        val sustainThreshold = profile.sustainedLateralMinDurationMs
        val suppressThreshold = profile.turnSuppressionThresholdRadS

        val rollSamples = rollBuffer.snapshotInWindow(sustainThreshold + 100L)
        val yawSamples = yawBuffer.snapshotInWindow(sustainThreshold + 100L)

        if (rollSamples.size > 3 && yawSamples.size > 3) {
            // Count how many samples within the sustain window exceed the threshold
            val elevatedRoll = rollSamples.count { abs(it.value) > suppressThreshold }
            val elevatedYaw = yawSamples.count { abs(it.value) > suppressThreshold }

            // Suppress if more than 60% of samples in the window are elevated
            val rollFraction = elevatedRoll.toFloat() / rollSamples.size
            val yawFraction = elevatedYaw.toFloat() / yawSamples.size

            if (rollFraction > 0.6f || yawFraction > 0.6f) {
                // Mark a suppression window so we don't re-check every sample
                suppressUntilMs = currentTimeMs + profile.turnSuppressionDurationMs

                val reason = if (yawFraction > 0.5f) SuppressReason.TURNING else SuppressReason.LATERAL_WOBBLE
                AsphaltLog.d("ThreeWheelerFilter",
                    "Lateral suppression: roll=${"%.2f".format(rollFraction)} yaw=${"%.2f".format(yawFraction)} reason=$reason")
                return FilterResult(suppressed = true, reason = reason)
            }
        }

        return FilterResult(suppressed = false, reason = null)
    }

    /**
     * Counts zero-crossings of the AC component of the Z-axis accelerometer
     * within the last [windowMs] milliseconds.
     *
     * The AC component is Z minus the rolling median (baseline subtraction).
     * A zero-crossing occurs when consecutive samples straddle zero.
     *
     * Returns crossings per second, normalised to the actual window duration.
     */
    private fun countZeroCrossingsPerSec(buffer: SlidingWindowBuffer, windowMs: Long): Float {
        val samples = buffer.snapshotInWindow(windowMs)
        if (samples.size < 4) return 0f

        val baseline = buffer.rollingMedian(lastN = 96)
        var crossings = 0
        var prevAc = samples[0].value - baseline

        for (i in 1 until samples.size) {
            val ac = samples[i].value - baseline
            if (prevAc * ac < 0f) {  // sign changed = zero crossing
                crossings++
            }
            prevAc = ac
        }

        val durationSec = (samples.last().timestampMs - samples.first().timestampMs) / 1000f
        return if (durationSec > 0f) crossings / durationSec else 0f
    }

    fun reset() {
        rollBuffer.clear()
        yawBuffer.clear()
        accelZBuffer.clear()
        suppressUntilMs = 0L
    }
}
