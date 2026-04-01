package io.asphalt.sdk

import android.content.Context
import android.location.Location
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
 * ## Typical usage
 *
 * ```kotlin
 * // In Application.onCreate()
 * Asphalt.init(this, AsphaltConfig(ingestUrl = "https://your-server/v1/ingest/batch"))
 *
 * // Start collection (e.g., when user begins a trip)
 * Asphalt.start()
 *
 * // Stop collection (e.g., when user ends a trip)
 * Asphalt.stop()
 * ```
 *
 * ## Threading model
 *
 * Sensor callbacks arrive on a dedicated background thread.
 * [AsphaltCallback] methods are posted to the main thread.
 * Database operations use Dispatchers.IO.
 *
 * ## Permissions required
 *
 * Declare in your AndroidManifest.xml:
 * ```xml
 * <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 * ```
 */
object Asphalt {

    private const val SDK_VERSION = "1.0.0"

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
     * Sets an optional callback for receiving real-time detection events.
     * Must be called after [init].
     */
    @JvmStatic
    fun setCallback(callback: AsphaltCallback?) {
        this.callback = callback
    }

    /**
     * Starts sensor collection and location tracking.
     *
     * Sensors are not activated immediately; they activate when GPS speed
     * exceeds [AsphaltConfig.minSpeedKmh]. Location tracking starts immediately.
     *
     * A new anonymous session ID is generated on each call.
     */
    @JvmStatic
    fun start() {
        val ctx = appContext ?: error("Asphalt.init() must be called before start()")
        val cfg = config ?: error("Asphalt.init() must be called before start()")

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
        callback?.onStateChanged(false)
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
