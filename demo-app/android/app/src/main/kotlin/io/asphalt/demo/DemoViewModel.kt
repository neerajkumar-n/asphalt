package io.asphalt.demo

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.asphalt.sdk.AsphaltCallback
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.DeviceMeta
import io.asphalt.sdk.model.RoadEvent
import io.asphalt.sdk.model.SensorSummary
import io.asphalt.sdk.model.VehicleType

/**
 * ViewModel for the demo app.
 *
 * Holds the list of detected events and exposes the [AsphaltCallback] to wire
 * the SDK into the UI.
 */
class DemoViewModel : ViewModel() {

    private val _events = MutableLiveData<List<RoadEvent>>(emptyList())
    val events: LiveData<List<RoadEvent>> = _events

    private val _sdkActive = MutableLiveData(false)
    val sdkActive: LiveData<Boolean> = _sdkActive

    // Vehicle type selected by the user in the demo UI. Defaults to four-wheeler.
    var selectedVehicleType: VehicleType = VehicleType.FOUR_WHEELER

    val asphaltCallback = object : AsphaltCallback {
        override fun onEventDetected(event: RoadEvent): Boolean {
            val current = _events.value ?: emptyList()
            _events.postValue(current + event)
            return true
        }

        override fun onStateChanged(active: Boolean) {
            _sdkActive.postValue(active)
        }

        override fun onBatchUploaded(eventCount: Int) {}

        override fun onUploadFailed(error: Throwable) {
            // Events are retained locally; WorkManager retries automatically.
        }
    }

    /**
     * Injects a synthetic bump event for testing the UI and upload pipeline
     * without needing to physically drive.
     *
     * Uses [selectedVehicleType] so the demo can exercise all three vehicle
     * profiles. Sensor values are tuned to realistic ranges per type:
     * - Four-wheeler: clean signal, delta ~5.4 m/s^2
     * - Three-wheeler: noisier baseline, delta ~6.2 m/s^2 (higher threshold)
     * - Two-wheeler: moderate noise, delta ~5.8 m/s^2
     */
    fun simulateBump() {
        val (peakZ, baselineZ, gyro, speedKmh) = when (selectedVehicleType) {
            VehicleType.FOUR_WHEELER  -> SimValues(15.2f, 9.81f, 0.85f, 52f)
            VehicleType.THREE_WHEELER -> SimValues(16.5f, 9.81f, 1.10f, 28f)
            VehicleType.TWO_WHEELER   -> SimValues(15.8f, 9.81f, 0.95f, 38f)
        }

        // Bengaluru coordinates with small random scatter to simulate different road segments
        val lat = 12.9716 + (Math.random() - 0.5) * 0.02
        val lon = 77.5946 + (Math.random() - 0.5) * 0.02

        val simulatedEvent = RoadEvent(
            timestampMs = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            accuracyMeters = 8f,
            intensity = ((peakZ - baselineZ) / 12f).coerceIn(0f, 1f),
            speedKmh = speedKmh + Math.random().toFloat() * 10f,
            anomalyType = listOf(AnomalyType.POTHOLE, AnomalyType.BUMP, AnomalyType.ROUGH_PATCH).random(),
            vehicleType = selectedVehicleType,
            sensorSummary = SensorSummary(
                accelPeakZ = peakZ,
                accelBaselineZ = baselineZ,
                accelDeltaZ = peakZ - baselineZ,
                gyroPeakMagnitude = gyro,
                sampleCount = 24,
                windowDurationMs = 500L
            ),
            deviceMeta = DeviceMeta(
                platform = "android",
                sdkInt = android.os.Build.VERSION.SDK_INT,
                manufacturer = "Simulated",
                model = "DemoDevice",
                sensorVendor = "Simulated"
            ),
            sdkVersion = "1.0.0",
            sessionId = "demo-session"
        )

        val current = _events.value ?: emptyList()
        _events.postValue(current + simulatedEvent)
    }

    private data class SimValues(
        val peakZ: Float,
        val baselineZ: Float,
        val gyro: Float,
        val speedKmh: Float
    )
}
