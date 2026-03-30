package io.asphalt.sdk

/**
 * Configuration for the Asphalt SDK.
 *
 * Sensible defaults are chosen to balance detection sensitivity and battery life.
 * Most applications should only need to set [ingestUrl].
 */
data class AsphaltConfig(

    /**
     * URL of the Asphalt ingestion API endpoint.
     * Required. Example: "https://api.asphalt.io/v1/ingest/batch"
     */
    val ingestUrl: String,

    /**
     * Minimum vehicle speed in km/h before sensor collection is activated.
     *
     * Below this threshold, the SDK stays idle to avoid:
     * - False positives from pedestrians or slow vehicle manoeuvres
     * - Battery drain from unnecessary sensor sampling
     *
     * Default: 15 km/h. Setting below 10 km/h is not recommended.
     */
    val minSpeedKmh: Float = 15f,

    /**
     * Sampling rate for the accelerometer in microseconds.
     *
     * SENSOR_DELAY_GAME (~20ms, 50Hz) is the recommended value.
     * Higher rates increase detection fidelity but drain battery faster.
     * SENSOR_DELAY_FASTEST (~1ms) is excessive and not needed here.
     *
     * Valid Android constants:
     * - SensorManager.SENSOR_DELAY_UI       (~60ms, 16Hz)  - too slow
     * - SensorManager.SENSOR_DELAY_GAME     (~20ms, 50Hz)  - recommended
     * - SensorManager.SENSOR_DELAY_FASTEST  (~1ms,  1000Hz) - excessive
     */
    val sensorSamplingRateUs: Int = android.hardware.SensorManager.SENSOR_DELAY_GAME,

    /**
     * Z-axis acceleration delta required to flag a candidate event, in m/s^2.
     *
     * This is the absolute deviation from the rolling baseline.
     * A value of 4.0 m/s^2 corresponds to roughly 0.4g, which is a moderate
     * pothole at highway speed. Rough patches may be closer to 2.5 m/s^2.
     *
     * Reduce to increase sensitivity (more false positives).
     * Increase to reduce noise (may miss minor anomalies).
     */
    val detectionThresholdMs2: Float = 4.0f,

    /**
     * Width of the sliding detection window in milliseconds.
     *
     * A pothole at 50 km/h occupies roughly 100-300ms of sensor time.
     * 500ms captures the full spike-dip-recovery signature with margin.
     */
    val detectionWindowMs: Long = 500L,

    /**
     * Minimum gyroscope magnitude (rad/s) required to confirm an event.
     *
     * Pure vertical jolts (real potholes) cause measurable pitch and roll.
     * Pure accelerometer spikes with near-zero gyroscope activity suggest
     * sensor noise or a stationary bump (e.g. cargo sliding inside the car).
     *
     * Set to 0.0 to disable gyroscope confirmation (not recommended).
     */
    val gyroConfirmationThresholdRadS: Float = 0.3f,

    /**
     * Maximum GPS accuracy in metres required to attach location to an event.
     *
     * Events recorded with accuracy worse than this threshold are still stored
     * locally but marked low-confidence. The backend treats them accordingly.
     */
    val maxGpsAccuracyMeters: Float = 50f,

    /**
     * How often (in seconds) to flush the local event batch to the server.
     *
     * Events are accumulated locally and sent in batches to reduce radio
     * wake-ups (one of the larger battery consumers on Android).
     *
     * Default: 300 seconds (5 minutes). Minimum enforced: 60 seconds.
     */
    val uploadIntervalSeconds: Long = 300L,

    /**
     * Maximum number of events to hold in the local buffer before forcing
     * an upload regardless of [uploadIntervalSeconds].
     *
     * Prevents unbounded growth if the device is offline for an extended period.
     */
    val maxBufferSize: Int = 200,

    /**
     * Require the device to be connected to an unmetered network (Wi-Fi)
     * before uploading. Recommended for users on limited data plans.
     *
     * When true, uploads are deferred by WorkManager until Wi-Fi is available.
     */
    val requireUnmeteredNetwork: Boolean = false,

    /**
     * Enable verbose logging. Should be false in production builds.
     */
    val debugLogging: Boolean = false
)
