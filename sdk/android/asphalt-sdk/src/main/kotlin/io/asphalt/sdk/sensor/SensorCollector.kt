package io.asphalt.sdk.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.detection.AnomalyDetector
import io.asphalt.sdk.internal.AsphaltLog
import kotlin.math.sqrt

/**
 * Manages registration and deregistration of motion sensors.
 *
 * ## Battery design
 *
 * Sensors are registered lazily: [start] activates them, [stop] unregisters them.
 * The location layer (LocationTracker) controls when [start]/[stop] are called
 * based on vehicle speed, so sensors are only active during driving at speed.
 *
 * We do NOT hold a WakeLock. Android's SensorManager delivers events via a
 * dedicated hardware FIFO when the SoC is in low-power states. When the FIFO
 * fills, the processor wakes to drain it. This batching behaviour is automatic
 * at most sampling rates and does not require explicit FIFO configuration.
 *
 * ## Orientation-agnostic detection
 *
 * Phones in the real world are mounted in landscape, portrait, upside-down,
 * wedged in cup holders, or stuffed in pockets at arbitrary angles. The raw
 * device-frame axes (X/Y/Z) are not reliable stand-ins for road-normal force.
 *
 * This collector uses two OS-fused sensors to transform all measurements into
 * a world-frame road-normal coordinate before passing to the detector:
 *
 * - TYPE_GRAVITY provides a continuous low-pass gravity vector in device
 *   coordinates. Its direction is always "down" regardless of phone orientation.
 *   It is used as the road-normal unit vector (roads are horizontal).
 *
 * - TYPE_LINEAR_ACCELERATION is the raw accelerometer output with the gravity
 *   component already removed by the OS fusion stack. Its road-normal projection
 *   gives the net vertical force from road impacts only, independent of the
 *   phone's orientation in the mount.
 *
 * For the gyroscope:
 * - The component of angular velocity along the gravity direction equals yaw
 *   in world frame (rotation around the vertical = turning the vehicle).
 * - The magnitude of the component perpendicular to gravity equals lateral
 *   rotation (roll/wobble of the vehicle body).
 *
 * When the gravity sensor is unavailable, the collector falls back to a
 * default gravity vector pointing along device Z (portrait/flat assumption),
 * reproducing the pre-Strategy-A behaviour.
 *
 * ## Callback design
 *
 * [onEventDetected] receives the full [AnomalyDetector.DetectionResult] from the
 * same evaluation that triggered detection. This avoids a second [AnomalyDetector.evaluate]
 * call in the caller, which would always return no-event due to the cooldown guard
 * that was just set by the first call.
 */
class SensorCollector(
    context: Context,
    private val config: AsphaltConfig,
    private val detector: AnomalyDetector,
    private val onEventDetected: (timestampMs: Long, speedKmh: Float, result: AnomalyDetector.DetectionResult) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Kept for hardware vendor metadata only — never registered for events.
    private val accelerometerForMeta: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // OS-fused sensors used for orientation-agnostic detection.
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Latest gravity vector in device frame. Default = phone flat face-up (Z down = 9.81).
    // Updated continuously by TYPE_GRAVITY events. If the gravity sensor is unavailable,
    // this default makes projectOntoGravity return event.values[2] — identical to the
    // previous raw-Z behaviour.
    private val gravityVec = floatArrayOf(0f, 0f, 9.81f)

    private var currentSpeedKmh: Float = 0f
    private var running = false

    fun start() {
        if (running) return

        if (linearAccelSensor == null) {
            AsphaltLog.w(TAG, "TYPE_LINEAR_ACCELERATION unavailable. Detection disabled.")
            return
        }

        sensorManager.registerListener(this, linearAccelSensor, config.sensorSamplingRateUs)

        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, config.sensorSamplingRateUs)
        } else {
            AsphaltLog.w(TAG, "TYPE_GRAVITY unavailable; defaulting to vertical-Z assumption.")
        }

        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, config.sensorSamplingRateUs)
        } else {
            AsphaltLog.w(TAG, "No gyroscope. Gyro confirmation disabled.")
        }

        running = true
        AsphaltLog.d(TAG, "Sensor collection started.")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        detector.reset()
        AsphaltLog.d(TAG, "Sensor collection stopped.")
    }

    fun updateSpeed(speedKmh: Float) {
        currentSpeedKmh = speedKmh
    }

    fun isRunning() = running

    /**
     * Returns the hardware vendor string of the underlying accelerometer.
     *
     * Used to populate [DeviceMeta.sensorVendor] so the backend can account for
     * known per-manufacturer sensor calibration differences. Returns an empty
     * string if no accelerometer is present or the vendor string is unavailable.
     */
    fun getSensorVendor(): String = accelerometerForMeta?.vendor ?: ""

    override fun onSensorChanged(event: SensorEvent) {
        val timestampMs = System.currentTimeMillis()

        when (event.sensor.type) {

            Sensor.TYPE_GRAVITY -> {
                // Update the gravity vector used for all subsequent projections.
                event.values.copyInto(gravityVec)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Project the gravity-free linear acceleration onto the road-normal
                // direction. The result is the net vertical force from road impacts,
                // regardless of phone orientation in the mount.
                val roadNormal = projectOntoGravity(event.values, gravityVec)
                detector.feedAccelerometer(timestampMs, roadNormal)

                val result = detector.evaluate(timestampMs, currentSpeedKmh)
                if (result.detected) {
                    // Pass the result directly. Do NOT call detector.evaluate() again —
                    // the cooldown guard was just set, so a second call returns no-event.
                    onEventDetected(timestampMs, currentSpeedKmh, result)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                // Decompose angular velocity into world-frame components:
                //   verticalRadS  = rotation around the vertical axis (= vehicle yaw / turning)
                //   lateralMagRadS = magnitude of rotation in the horizontal plane (= vehicle roll/wobble)
                val verticalRadS = projectOntoGravity(event.values, gravityVec)
                val lateralMagRadS = lateralMagnitude(event.values, gravityVec, verticalRadS)
                detector.feedGyroscope(timestampMs, verticalRadS, lateralMagRadS)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            AsphaltLog.w(TAG, "Sensor accuracy dropped to UNRELIABLE for ${sensor.name}")
        }
    }

    // ── Projection helpers ────────────────────────────────────────────────────

    /**
     * Returns the scalar projection of [v] onto the unit vector of [g].
     *
     * When the phone is flat (g = [0, 0, 9.81]), this returns v[2] — identical
     * to the previous raw-Z read, so the degenerate case is backward-compatible.
     *
     * Falls back to v[2] when |g| < 0.1 (should never happen in practice).
     */
    private fun projectOntoGravity(v: FloatArray, g: FloatArray): Float {
        val gMag = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gMag < 0.1f) return v[2]
        return (v[0] * g[0] + v[1] * g[1] + v[2] * g[2]) / gMag
    }

    /**
     * Returns the magnitude of the component of [v] perpendicular to [g].
     *
     * [proj] is the already-computed projection of [v] onto [g] (passed in to
     * avoid recomputing it). Together with [projectOntoGravity], these two values
     * fully decompose [v] into road-normal and horizontal-plane components:
     *
     *   |v|² = proj² + lateralMag²
     *
     * Falls back to sqrt(v[0]²+v[1]²) when |g| < 0.1.
     */
    private fun lateralMagnitude(v: FloatArray, g: FloatArray, proj: Float): Float {
        val gMag = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (gMag < 0.1f) return sqrt(v[0] * v[0] + v[1] * v[1])
        val gux = g[0] / gMag; val guy = g[1] / gMag; val guz = g[2] / gMag
        val lx = v[0] - proj * gux
        val ly = v[1] - proj * guy
        val lz = v[2] - proj * guz
        return sqrt(lx * lx + ly * ly + lz * lz)
    }

    private companion object {
        const val TAG = "SensorCollector"
    }
}
