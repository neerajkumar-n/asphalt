package io.asphalt.sdk.detection

import io.asphalt.sdk.model.VehicleType

/**
 * Per-vehicle-type signal processing parameters.
 *
 * Each vehicle category produces a distinct vibration environment. A single
 * set of thresholds cannot serve all vehicles without either missing events
 * on noisier platforms (three-wheelers) or flooding the database with false
 * positives from normal vehicle dynamics.
 *
 * ## Why vehicle type changes the signal
 *
 * ### Four-wheelers (cars, SUVs)
 * Four points of road contact. Well-damped suspension. Engine vibration
 * transmitted to the cabin is relatively low (~0.1-0.5 m/s^2 on Z at idle).
 * Lateral stability is high; gyroscope roll rate on straight roads rarely
 * exceeds 0.2 rad/s. Pothole signals are clean, well-separated from the
 * noise floor. The standard 4.0 m/s^2 threshold works well.
 *
 * ### Three-wheelers (auto rickshaws)
 * One front wheel, two rear wheels. The asymmetric structure creates:
 *
 * - **Higher structural noise**: The lightweight tubular frame transmits
 *   engine vibration directly to the phone. A typical CNG/petrol auto at
 *   idle shows Z oscillation of 1.5-3.0 m/s^2, significantly above the
 *   0.1-0.5 m/s^2 seen in cars. The raw threshold must be higher.
 *
 * - **Engine frequency signature**: 3-cylinder 4-stroke autos at 1500-3000 RPM
 *   produce ~25-50 Hz mechanical vibration through the powertrain. 2-stroke
 *   engines (older autos) produce ~15-25 Hz. This periodic Z oscillation
 *   looks like many small anomalies to a naive detector. The `ThreeWheelerFilter`
 *   identifies and suppresses this periodic pattern.
 *
 * - **Lateral instability**: The tricycle geometry causes measurable body roll
 *   even on straight roads, especially at 15-30 km/h (the urban speed range
 *   where autos operate most). Gyroscope roll rate (X axis) shows sustained
 *   oscillation of 0.3-0.6 rad/s. This is normal, not an anomaly.
 *
 * - **Turn dynamics**: When an auto turns, the body leans outward (opposite
 *   to a motorcycle). At a typical urban turn (radius ~10m, speed ~20 km/h),
 *   the roll angle can reach 5-10 degrees. The gyroscope sees ~0.5-0.8 rad/s
 *   roll and ~0.4-0.7 rad/s yaw simultaneously. Without turn suppression, every
 *   urban turn would register as a rough patch.
 *
 * - **Load sensitivity**: An auto carrying three passengers at 150kg total has
 *   materially different suspension compression than an empty auto. The same
 *   pothole produces different Z signatures under different loads. This is
 *   a known limitation that per-device calibration would address in a future
 *   version.
 *
 * ### Two-wheelers (motorcycles, scooters)
 * Single-track vehicle. Stability is maintained by lean angle, not geometry.
 * The phone orientation problem is acute here: a phone mounted on the handlebars
 * tilts with the bike. During a lean at 30 km/h the Z axis may shift by several
 * m/s^2 from baseline just from the lean angle, independent of any road anomaly.
 * The detection threshold is raised and the rolling baseline window is widened
 * to account for dynamic orientation changes. Gyroscope sensitivity is also
 * higher: bikes produce more pitch/roll during normal riding than cars.
 *
 * ## Signal weights for backend aggregation
 *
 * [signalWeight] is a normalisation factor used by the backend clustering
 * pipeline. It reflects how much each event type should be trusted relative
 * to a car event:
 *
 * - Car (1.0): cleanest signal, well-established threshold calibration
 * - Two-wheeler (0.8): noisier, orientation-sensitive
 * - Three-wheeler (0.7): highest ambient noise floor, most susceptible to
 *   false positives even after on-device filtering
 *
 * These weights are multiplied against raw intensity when computing
 * cluster average intensity, and factored into confidence scoring.
 * A cluster confirmed by both three-wheelers and cars earns a diversity
 * bonus that can offset the lower per-type weight.
 */
data class VehicleProfile(
    val vehicleType: VehicleType,

    /** Z-axis delta required to flag a candidate event (m/s^2). */
    val detectionThresholdMs2: Float,

    /** Minimum gyro magnitude to confirm physical motion (rad/s). */
    val gyroConfirmationThresholdRadS: Float,

    /**
     * Number of samples to use for the rolling median baseline.
     * Noisier vehicles need a longer window to produce a stable baseline.
     */
    val baselineWindowSamples: Int,

    /**
     * Sustained lateral gyro rate (rad/s) above which event detection is
     * suppressed. This catches turns and body roll that would otherwise
     * look like anomalies.
     *
     * Measured on the roll axis (gyro X when phone is flat).
     */
    val turnSuppressionThresholdRadS: Float,

    /**
     * Duration (ms) to suppress detection after a turn is detected.
     * The suppression window covers the turn itself plus settling time.
     */
    val turnSuppressionDurationMs: Long,

    /**
     * Minimum duration (ms) that lateral gyro rate must be elevated
     * before it is classified as a sustained turn rather than a transient
     * pothole-induced roll.
     *
     * A pothole on an auto causes a brief (50-150ms) lateral spike.
     * A turn causes sustained elevation for 500-3000ms.
     * This duration threshold distinguishes the two.
     */
    val sustainedLateralMinDurationMs: Long,

    /**
     * Z-axis zero-crossing rate threshold (crossings per second) above
     * which the signal is treated as periodic engine vibration rather than
     * an impact anomaly. Only relevant for three-wheelers.
     *
     * A pothole: 2-4 zero-crossings per second (single impact, brief oscillation)
     * Engine vibration at 20Hz: ~40 zero-crossings per second
     *
     * Null means no engine vibration filter is applied (four-wheelers, bikes).
     */
    val engineVibrationZeroCrossingsPerSecond: Float?,

    /** Cooldown between detected events (ms). */
    val cooldownMs: Long,

    /**
     * Relative signal reliability weight for backend aggregation.
     * Range: 0.0 to 1.0. Higher = more trusted.
     */
    val signalWeight: Float
) {
    companion object {

        val FOUR_WHEELER = VehicleProfile(
            vehicleType = VehicleType.FOUR_WHEELER,
            detectionThresholdMs2 = 4.0f,
            gyroConfirmationThresholdRadS = 0.3f,
            baselineWindowSamples = 64,
            turnSuppressionThresholdRadS = 0.8f,
            turnSuppressionDurationMs = 800L,
            sustainedLateralMinDurationMs = 300L,
            engineVibrationZeroCrossingsPerSecond = null,
            cooldownMs = 1500L,
            signalWeight = 1.0f
        )

        val THREE_WHEELER = VehicleProfile(
            vehicleType = VehicleType.THREE_WHEELER,
            // 5.5 m/s^2 accounts for the elevated baseline noise floor.
            // At this threshold a pothole that would read 4.0 on a car reads
            // 5.5-7.0 on an auto because the lighter frame amplifies the jolt.
            // Setting lower would flood the queue with engine vibration events.
            detectionThresholdMs2 = 5.5f,
            // Higher gyro threshold: autos have constant low-level gyro activity
            // from structural flex. The confirmation must be a clear spike above that.
            gyroConfirmationThresholdRadS = 0.55f,
            // Longer baseline window to average out the ~20Hz engine oscillation.
            // At 50Hz sampling, 96 samples = ~1.9 seconds = ~38 cycles at 20Hz.
            // The median of this window is stable against periodic noise.
            baselineWindowSamples = 96,
            // Autos roll more during turns; suppress at a lower threshold than cars.
            turnSuppressionThresholdRadS = 0.55f,
            turnSuppressionDurationMs = 1200L,  // turns take longer to settle at low speed
            sustainedLateralMinDurationMs = 250L,
            // 20Hz engine vibration = 40 zero-crossings/sec.
            // Threshold at 25: catches vibration, allows genuine impacts through.
            engineVibrationZeroCrossingsPerSecond = 25f,
            // Longer cooldown: auto vibration after a real pothole rings longer.
            cooldownMs = 2000L,
            signalWeight = 0.7f
        )

        val TWO_WHEELER = VehicleProfile(
            vehicleType = VehicleType.TWO_WHEELER,
            detectionThresholdMs2 = 5.0f,
            gyroConfirmationThresholdRadS = 0.45f,
            baselineWindowSamples = 80,
            // Bikes lean steeply on turns; suppress detection more aggressively.
            turnSuppressionThresholdRadS = 0.5f,
            turnSuppressionDurationMs = 1000L,
            sustainedLateralMinDurationMs = 200L,
            engineVibrationZeroCrossingsPerSecond = null,
            cooldownMs = 1500L,
            signalWeight = 0.8f
        )

        fun forVehicleType(type: VehicleType): VehicleProfile = when (type) {
            VehicleType.FOUR_WHEELER -> FOUR_WHEELER
            VehicleType.THREE_WHEELER -> THREE_WHEELER
            VehicleType.TWO_WHEELER -> TWO_WHEELER
        }
    }
}
