package io.asphalt.demo

import android.app.Application
import io.asphalt.sdk.Asphalt
import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.model.VehicleType

/**
 * Application subclass for the Asphalt demo.
 *
 * Why init here and not in MainActivity:
 *   WorkManager survives process death and can restart [UploadWorker] without
 *   any activity running. If Asphalt.init() were only called from MainActivity,
 *   the worker would find appContext = null on a cold-restart and crash.
 *   Calling init() from Application.onCreate() guarantees the SDK is ready
 *   before any component (activity, service, or WorkManager worker) uses it.
 *
 * vehicleType defaults to FOUR_WHEELER here.
 *   The demo UI does not yet expose a vehicle selector (the selector is wired
 *   in DemoViewModel for simulate-bump purposes only). To test three-wheeler or
 *   two-wheeler detection on a real drive, change the vehicleType constant
 *   below and reinstall.
 */
class AsphaltDemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val config = AsphaltConfig(
            ingestUrl = BuildConfig.INGEST_URL,
            vehicleType = VehicleType.FOUR_WHEELER,
            // Debug builds lower the speed gate to 5 km/h so detection can be
            // verified indoors or while walking. Release builds use 15 km/h to
            // avoid false positives from pedestrian movement.
            minSpeedKmh = if (BuildConfig.DEBUG) 5f else 15f,
            debugLogging = BuildConfig.DEBUG
        )

        Asphalt.init(this, config)
    }
}
