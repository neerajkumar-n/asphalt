package io.asphalt.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.asphalt.sdk.detection.AnomalyDetector
import io.asphalt.sdk.internal.AsphaltLog
import io.asphalt.sdk.location.LocationTracker
import io.asphalt.sdk.model.DeviceMeta
import io.asphalt.sdk.model.RoadEvent
import io.asphalt.sdk.sensor.SensorCollector
import io.asphalt.sdk.storage.EventDatabase
import io.asphalt.sdk.storage.toEntity
import io.asphalt.sdk.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main entry point for the Asphalt SDK.
 *
 * ## Quick start
 *
 * Put the ingest URL in AndroidManifest.xml, inside `<application>`:
 * ```xml
 * <meta-data android:name="io.asphalt.INGEST_URL"
 *            android:value="https://your-server/v1/ingest/batch" />
 * ```
 * Then, from an Activity, when the user begins a trip:
 * ```kotlin
 * Asphalt.requestPermissionAndStart(this)   // asks for location if needed, then starts
 * Asphalt.stop()                            // when the trip ends
 * ```
 *
 * ## Full control
 *
 * ```kotlin
 * Asphalt.init(this, AsphaltConfig(ingestUrl = "https://your-server/v1/ingest/batch"))
 * Asphalt.start()   // once location permission is granted
 * ```
 *
 * ## Threading model
 *
 * Sensor callbacks arrive on a dedicated background thread.
 * [AsphaltCallback] methods are posted to the main thread.
 * Database operations use Dispatchers.IO.
 *
 * ## Permissions
 *
 * The SDK manifest declares location and network permissions; they merge into
 * the app automatically. Location still needs a runtime grant, which
 * [requestPermissionAndStart] handles.
 */
object Asphalt {

    private val SDK_VERSION get() = io.asphalt.sdk.BuildConfig.SDK_VERSION

    private var config: AsphaltConfig? = null
    private var appContext: Context? = null
    private var callback: AsphaltCallback? = null

    private var detector: AnomalyDetector? = null
    private var sensorCollector: SensorCollector? = null
    private var locationTracker: LocationTracker? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionId: String = UUID.randomUUID().toString()
    private var currentLocation: Location? = null
    private var currentSpeedKmh: Float = 0f

    /**
     * Initialises the SDK. Must be called once before [start], typically in
     * Application.onCreate().
     */
    @JvmStatic
    fun init(context: Context, config: AsphaltConfig) {
        appContext = context.applicationContext
        this.config = config
        AsphaltLog.enabled = config.debugLogging
        AsphaltLog.d("Asphalt", "SDK initialised. Version: $SDK_VERSION, vehicle: ${config.vehicleType.value}")
    }

    /**
     * Returns true if the SDK is currently started (location tracking active).
     * Sensors may still be idle waiting for the speed threshold.
     */
    @JvmStatic
    fun isRunning(): Boolean = locationTracker != null

    /**
     * Sets an optional callback for receiving real-time detection events.
     * Must be called after [init].
     */
    @JvmStatic
    fun setCallback(callback: AsphaltCallback?) {
        this.callback = callback
    }

    /** True when the app holds ACCESS_FINE_LOCATION, which [start] needs. */
    @JvmStatic
    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * One-call start. If [init] has not been called, the config is read from the
     * `io.asphalt.INGEST_URL` (and optional `io.asphalt.VEHICLE_TYPE`) manifest
     * meta-data.
     *
     * @return true if collection started, false if location permission is missing.
     */
    @JvmStatic
    fun start(context: Context): Boolean {
        if (config == null) init(context, configFromManifest(context))
        return start()
    }

    /**
     * Asks for location permission if it has not been granted, then starts.
     * [onResult] receives true if collection started.
     */
    @JvmStatic
    @JvmOverloads
    fun requestPermissionAndStart(activity: ComponentActivity, onResult: (Boolean) -> Unit = {}) {
        if (hasLocationPermission(activity)) {
            onResult(start(activity))
            return
        }
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            "io.asphalt.location-permission",
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            launcher.unregister()
            onResult(granted && start(activity))
        }
        launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Starts sensor collection and location tracking.
     *
     * Sensors are not activated immediately; they activate when GPS speed
     * exceeds [AsphaltConfig.minSpeedKmh]. Location tracking starts immediately.
     *
     * A new anonymous session ID is generated on each call. Calling it while
     * already running does nothing.
     *
     * @return true if collection is running, false if location permission is missing.
     */
    @JvmStatic
    fun start(): Boolean {
        val ctx = appContext ?: error("Asphalt.init() must be called before start()")
        val cfg = config ?: error("Asphalt.init() must be called before start()")

        if (isRunning()) return true
        if (!hasLocationPermission(ctx)) {
            AsphaltLog.w("Asphalt", "ACCESS_FINE_LOCATION not granted. Use requestPermissionAndStart(activity) or request it first.")
            return false
        }

        sessionId = UUID.randomUUID().toString()

        detector = AnomalyDetector(cfg)

        sensorCollector = SensorCollector(
            context = ctx,
            config = cfg,
            detector = detector!!,
            onEventDetected = ::handleDetection
        )

        locationTracker = LocationTracker(
            context = ctx,
            config = cfg,
            onSpeedChanged = ::handleSpeedChanged,
            onLocationUpdate = { location -> currentLocation = location }
        )

        locationTracker!!.start()

        UploadWorker.schedulePeriodicUpload(
            ctx,
            cfg.ingestUrl,
            cfg.uploadIntervalSeconds,
            cfg.requireUnmeteredNetwork
        )

        AsphaltLog.d("Asphalt", "Session started. ID: $sessionId")
        scope.launch { callback?.onStateChanged(false) }
        return true
    }

    /**
     * Stops sensor collection and location tracking.
     *
     * Pending events remain in the local database and will be uploaded by
     * WorkManager on the next scheduled flush.
     */
    @JvmStatic
    fun stop() {
        sensorCollector?.stop()
        locationTracker?.stop()
        detector?.reset()

        sensorCollector = null
        locationTracker = null
        detector = null

        AsphaltLog.d("Asphalt", "Session stopped.")
        scope.launch { callback?.onStateChanged(false) }
    }

    /**
     * Updates the SDK configuration at runtime.
     *
     * Note: [AsphaltConfig.vehicleType] changes require calling [stop] then [start]
     * to take effect, since the vehicle profile is baked into [AnomalyDetector] at
     * session start.
     */
    @JvmStatic
    fun setConfig(newConfig: AsphaltConfig) {
        config = newConfig
        AsphaltLog.enabled = newConfig.debugLogging
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun configFromManifest(context: Context): AsphaltConfig {
        val app = context.applicationContext
        @Suppress("DEPRECATION") // getApplicationInfo(String, ApplicationInfoFlags) needs API 33
        val meta = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        return AsphaltConfig.fromManifestValues(
            meta?.getString(AsphaltConfig.META_INGEST_URL),
            meta?.getString(AsphaltConfig.META_VEHICLE_TYPE)
        )
    }

    private fun handleSpeedChanged(active: Boolean, speedKmh: Float) {
        currentSpeedKmh = speedKmh
        sensorCollector?.updateSpeed(speedKmh)

        if (active && sensorCollector?.isRunning() == false) {
            sensorCollector?.start()
            scope.launch { callback?.onStateChanged(true) }
        } else if (!active && sensorCollector?.isRunning() == true) {
            sensorCollector?.stop()
            scope.launch { callback?.onStateChanged(false) }
        }
    }

    /**
     * Receives a confirmed detection from [SensorCollector].
     *
     * The [result] is passed directly from the [AnomalyDetector.evaluate] call
     * that triggered detection. We do not re-evaluate the detector here because
     * the cooldown guard would make the second call return no-event.
     */
    private fun handleDetection(
        timestampMs: Long,
        speedKmh: Float,
        result: AnomalyDetector.DetectionResult
    ) {
        val ctx = appContext ?: return
        val cfg = config ?: return
        val loc = currentLocation

        if (loc == null) {
            AsphaltLog.d("Asphalt", "Event detected but no GPS fix available. Discarding.")
            return
        }

        if (loc.accuracy > cfg.maxGpsAccuracyMeters) {
            AsphaltLog.d("Asphalt", "GPS accuracy ${loc.accuracy}m exceeds threshold. Storing as low-confidence.")
        }

        val event = RoadEvent(
            timestampMs = timestampMs,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            intensity = result.intensity,
            speedKmh = speedKmh,
            anomalyType = result.anomalyType,
            vehicleType = cfg.vehicleType,
            sensorSummary = result.sensorSummary,
            deviceMeta = DeviceMeta(sensorVendor = sensorCollector?.getSensorVendor() ?: ""),
            sdkVersion = SDK_VERSION,
            sessionId = sessionId
        )

        val allowed = callback?.onEventDetected(event) ?: true
        if (!allowed) {
            AsphaltLog.d("Asphalt", "Event suppressed by callback.")
            return
        }

        scope.launch(Dispatchers.IO) {
            EventDatabase.getInstance(ctx).eventDao().insert(event.toEntity())
            AsphaltLog.d("Asphalt", "Event stored: ${event.anomalyType.value} intensity=${"%.2f".format(event.intensity)}")

            val pendingCount = EventDatabase.getInstance(ctx).eventDao().pendingCount()
            if (pendingCount >= cfg.maxBufferSize) {
                AsphaltLog.d("Asphalt", "Buffer full ($pendingCount events). Triggering immediate upload.")
                UploadWorker.scheduleImmediate(ctx, cfg.ingestUrl)
            }
        }
    }
}
