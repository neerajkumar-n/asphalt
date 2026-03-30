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

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1_000L  // 1 second interval
    ).apply {
        setMinUpdateIntervalMillis(500L)
        setMinUpdateDistanceMeters(5f)
        setWaitForAccurateLocation(false)
    }.build()

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
        fusedClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        AsphaltLog.d("LocationTracker", "Location updates started.")
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        active = false
        AsphaltLog.d("LocationTracker", "Location updates stopped.")
    }

    fun getLastLocation(): Location? = lastLocation
    fun isActive() = active
}
