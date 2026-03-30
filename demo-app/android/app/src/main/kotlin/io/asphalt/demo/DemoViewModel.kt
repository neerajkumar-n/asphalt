package io.asphalt.demo

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.asphalt.sdk.AsphaltCallback
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.DeviceMeta
import io.asphalt.sdk.model.RoadEvent
import io.asphalt.sdk.model.SensorSummary

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

    val asphaltCallback = object : AsphaltCallback {
        override fun onEventDetected(event: RoadEvent): Boolean {
            val current = _events.value ?: emptyList()
            _events.postValue(current + event)
            return true  // Allow normal SDK processing
        }

        override fun onStateChanged(active: Boolean) {
            _sdkActive.postValue(active)
        }

        override fun onBatchUploaded(eventCount: Int) {
            // Could show a subtle notification or update a sync indicator
        }

        override fun onUploadFailed(error: Throwable) {
            // Events are retained locally; silent retry by WorkManager
        }
    }

    /**
     * Injects a synthetic bump event for testing the UI and upload pipeline
     * without needing to physically drive over a pothole.
     *
     * The simulated event uses realistic sensor values at highway speed.
     * It is flagged as a real event to test the full end-to-end path.
     */
    fun simulateBump() {
        val simulatedEvent = RoadEvent(
            timestampMs = System.currentTimeMillis(),
            latitude = 37.7749 + (Math.random() - 0.5) * 0.01,   // Near San Francisco
            longitude = -122.4194 + (Math.random() - 0.5) * 0.01,
            accuracyMeters = 5f,
            intensity = (0.4f + Math.random().toFloat() * 0.5f).coerceIn(0f, 1f),
            speedKmh = 45f + Math.random().toFloat() * 30f,
            anomalyType = listOf(AnomalyType.POTHOLE, AnomalyType.BUMP, AnomalyType.ROUGH_PATCH).random(),
            sensorSummary = SensorSummary(
                accelPeakZ = 15.2f,
                accelBaselineZ = 9.81f,
                accelDeltaZ = 5.39f,
                gyroPeakMagnitude = 0.85f,
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
}
