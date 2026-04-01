package io.asphalt.sdk.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.internal.AsphaltLog

/**
 * GPS location tracker using the Fused Location Provider.
 *
 * ## Why FusedLocationProvider over raw GPS
 *
 * The Fused Location Provider (FLP) uses a combination of GPS, Wi-Fi, and
 * cell tower data to produce location fixes with lower latency and battery
 * cost than raw GPS alone. For road anomaly detection we need:
 * - Coordinates accurate to ~5-10 metres (GPS provides this in open sky)
 * - Speed data for the speed-based filter
 * - Reasonable update rate (1 second is sufficient for tagging events)
 *
 * ## Speed filter
 *
 * The [onSpeedChanged] callback informs the sensor layer when to activate
 * or deactivate sensor collection. This is the primary battery optimisation:
 * sensors are only sampling when the vehicle is moving above [AsphaltConfig.minSpeedKmh].
 *
 * ## Privacy
 *
 * Location is used only to tag detected anomalies. We do not record or
 * upload location continuously. No location data is transmitted unless an
 * anomaly event is detected.
 */
class LocationTracker(
    context: Context,
    private val config: AsphaltConfig,
    private val onSpeedChanged: (active: Boolean, speedKmh: Float) -> Unit,
    private val onLocationUpdate: (location: Location) -> Unit
) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var lastLocation: Location? = null
    private var active = false

    // Dual-mode GPS: lower accuracy/frequency when idle saves battery while waiting
    // for the vehicle to reach detection speed. High accuracy activates once moving.

    private val idleLocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        2_000L  // 2 second interval when idle (speed < minSpeedKmh)
    ).apply {
        setMinUpdateIntervalMillis(1_000L)
        setMinUpdateDistanceMeters(10f)
        setWaitForAccurateLocation(false)
    }.build()

    private val activeLocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1_000L  // 1 second interval during active anomaly detection
    ).apply {
        setMinUpdateIntervalMillis(500L)
        setMinUpdateDistanceMeters(5f)
        setWaitForAccurateLocation(false)
    }.build()

    // Tracks which request is currently registered so we only swap when state changes.
    private var currentRequest = idleLocationRequest

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation = location
            onLocationUpdate(location)

            val speedMs = location.speed  // metres per second
            val speedKmh = speedMs * 3.6f
            val shouldBeActive = speedKmh >= config.minSpeedKmh

            if (shouldBeActive != active) {
                active = shouldBeActive
                // Switch GPS accuracy mode to match detection state.
                // Idle → BALANCED_POWER_ACCURACY saves battery while waiting for speed.
                // Active → HIGH_ACCURACY for precise anomaly coordinates.
                switchLocationMode(shouldBeActive)
                AsphaltLog.d("LocationTracker", "Speed: ${"%.1f".format(speedKmh)} km/h. Sensors active: $active")
                onSpeedChanged(active, speedKmh)
            } else {
                // Keep the sensor layer updated with current speed even without state change
                if (active) onSpeedChanged(true, speedKmh)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        currentRequest = idleLocationRequest  // always begin in idle mode
        fusedClient.requestLocationUpdates(
            currentRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        AsphaltLog.d("LocationTracker", "Location updates started (idle mode).")
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        active = false
        currentRequest = idleLocationRequest  // reset so next start() begins idle
        AsphaltLog.d("LocationTracker", "Location updates stopped.")
    }

    /**
     * Switches the active location request between idle (balanced power) and
     * active (high accuracy) modes. Removes the current registration and
     * re-registers with the new request. No-op if the mode has not changed.
     */
    @SuppressLint("MissingPermission")
    private fun switchLocationMode(useHighAccuracy: Boolean) {
        val newRequest = if (useHighAccuracy) activeLocationRequest else idleLocationRequest
        if (currentRequest === newRequest) return
        currentRequest = newRequest
        fusedClient.removeLocationUpdates(locationCallback)
        fusedClient.requestLocationUpdates(newRequest, locationCallback, Looper.getMainLooper())
        AsphaltLog.d("LocationTracker", "GPS mode: ${if (useHighAccuracy) "HIGH_ACCURACY" else "BALANCED_POWER"}")
    }

    fun getLastLocation(): Location? = lastLocation
    fun isActive() = active
}
