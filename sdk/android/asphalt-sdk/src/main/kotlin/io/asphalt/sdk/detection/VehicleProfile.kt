package io.asphalt.sdk.detection

import io.asphalt.sdk.model.VehicleType

/**
 * Per-vehicle-type signal processing parameters.
 *
 * Each vehicle category produces a distinct vibration environment. A single
 * set of thresholds cannot serve all vehicles without either missing events
 * on noisier platforms (motorcycles) or flooding the database with false
 * positives from normal vehicle dynamics.
 *
 * ## Why vehicle type changes the signal
 *
 * ### Four-wheelers (cars, SUVs, pickups)
 * Four points of road contact. Well-damped suspension. Engine vibration
 * transmitted to the cabin is relatively low (~0.1-0.5 m/s^2 on Z at idle).
 * Lateral stability is high; gyroscope roll rate on straight roads rarely
 * exceeds 0.2 rad/s. Pothole signals are clean, well-separated from the
 * noise floor. The standard 4.0 m/s^2 threshold works well.
 *
 * ### Two-wheelers (motorcycles, scooters, mopeds)
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
 *
 * These weights are multiplied against raw intensity when computing
 * cluster average intensity, and factored into confidence scoring.
 * A cluster confirmed by both two-wheelers and cars earns a diversity
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
            cooldownMs = 1500L,
            signalWeight = 1.0f
        )

        val TWO_WHEELER = VehicleProfile(
            vehicleType = VehicleType.TWO_WHEELER,
            detectionThresholdMs2 = 5.0f,
            gyroConfirmationThresholdRadS = 0.45f,
            baselineWindowSamples = 80,
            cooldownMs = 1500L,
            signalWeight = 0.8f
        )

        fun forVehicleType(type: VehicleType): VehicleProfile = when (type) {
            VehicleType.FOUR_WHEELER -> FOUR_WHEELER
            VehicleType.TWO_WHEELER -> TWO_WHEELER
        }
    }
}
