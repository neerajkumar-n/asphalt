package io.asphalt.sdk.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.detection.AnomalyDetector
import io.asphalt.sdk.internal.AsphaltLog

/**
 * Manages registration and deregistration of accelerometer and gyroscope sensors.
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
 * ## Coordinate system
 *
 * Android accelerometer axes relative to a phone in portrait orientation:
 * - X: points right along the screen
 * - Y: points up along the screen
 * - Z: points out of the screen face
 *
 * When the phone is lying flat (face up) on a car dashboard, Z points upward
 * and gravity reads approximately +9.81 m/s^2 on Z. This is the orientation
 * assumed by the detector. Phone orientation changes are a known limitation
 * (see docs/limitations.md).
 */
class SensorCollector(
    context: Context,
    private val config: AsphaltConfig,
    private val detector: AnomalyDetector,
    private val onEventDetected: (timestampMs: Long, speedKmh: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var currentSpeedKmh: Float = 0f
    private var running = false

    fun start() {
        if (running) return

        if (accelerometer == null) {
            AsphaltLog.w("SensorCollector", "Device has no accelerometer. Detection disabled.")
            return
        }

        sensorManager.registerListener(this, accelerometer, config.sensorSamplingRateUs)

        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, config.sensorSamplingRateUs)
        } else {
            AsphaltLog.w("SensorCollector", "Device has no gyroscope. Gyro confirmation disabled.")
        }

        running = true
        AsphaltLog.d("SensorCollector", "Sensor collection started.")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        detector.reset()
        AsphaltLog.d("SensorCollector", "Sensor collection stopped.")
    }

    fun updateSpeed(speedKmh: Float) {
        currentSpeedKmh = speedKmh
    }

    fun isRunning() = running

    override fun onSensorChanged(event: SensorEvent) {
        val timestampMs = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // event.values[2] = Z axis
                detector.feedAccelerometer(timestampMs, event.values[2])

                val result = detector.evaluate(timestampMs, currentSpeedKmh)
                if (result.detected) {
                    onEventDetected(timestampMs, currentSpeedKmh)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                detector.feedGyroscope(
                    timestampMs,
                    event.values[0],  // X
                    event.values[1],  // Y
                    event.values[2]   // Z
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Accelerometer accuracy rarely changes during a drive session.
        // SENSOR_STATUS_UNRELIABLE could indicate physical shock to the device.
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            AsphaltLog.w("SensorCollector", "Sensor accuracy dropped to UNRELIABLE for ${sensor.name}")
        }
    }
}
