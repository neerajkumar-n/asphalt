# SDK Integration Guide

## Requirements

- Android API level 24+ (Android 7.0)
- Kotlin 1.9+
- Google Play Services (for FusedLocationProvider)

---

## Installation

Add the Asphalt SDK to your project. Until the SDK is published to Maven
Central, build it locally and publish to a local Maven repository.

**Option A: Local module (monorepo)**

In your root `settings.gradle.kts`:
```kotlin
include(":asphalt-sdk")
project(":asphalt-sdk").projectDir = File("path/to/asphalt/sdk/android/asphalt-sdk")
```

In your app `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":asphalt-sdk"))
}
```

**Option B: Local Maven (after running `./gradlew publishToMavenLocal`)**

In your project `build.gradle.kts`:
```kotlin
repositories {
    mavenLocal()
}
```

In your app `build.gradle.kts`:
```kotlin
dependencies {
    implementation("io.asphalt:asphalt-sdk:1.0.0")
}
```

---

## Permissions

Declare the following in your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Request `ACCESS_FINE_LOCATION` at runtime before calling `Asphalt.start()`.

**Background collection (Android 10+):** On API 29+, Android stops delivering
location updates to `FusedLocationProviderClient` once the app leaves the
foreground, even if the app has a running foreground service, unless
`ACCESS_BACKGROUND_LOCATION` is also declared and granted.

If your app needs to collect while the screen is locked:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

This permission requires a separate runtime prompt (it cannot be bundled with
`ACCESS_FINE_LOCATION`) and a Google Play policy disclosure explaining the
use case. For demo or screen-on-only apps, it can be omitted.

---

## Initialisation

Initialise the SDK once in your `Application.onCreate()`:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = AsphaltConfig(
            ingestUrl = "https://your-backend.example.com/v1/ingest/batch",
            minSpeedKmh = 15f,
            detectionThresholdMs2 = 4.0f,
            uploadIntervalSeconds = 300L
        )
        Asphalt.init(this, config)
    }
}
```

---

## Starting and Stopping Detection

```kotlin
// Start detection (e.g. when user begins a trip)
Asphalt.start()

// Stop detection (e.g. when user ends a trip or app goes to background)
Asphalt.stop()
```

`Asphalt.start()` begins GPS tracking immediately. Accelerometer and gyroscope
are only activated once the GPS speed exceeds `minSpeedKmh`. You do not need to
manage this transition yourself.

---

## Receiving Events

Implement `AsphaltCallback` to receive real-time detection events:

```kotlin
Asphalt.setCallback(object : AsphaltCallback {
    override fun onEventDetected(event: RoadEvent): Boolean {
        Log.d("Asphalt", "Detected ${event.anomalyType} at ${event.latitude},${event.longitude}")
        Log.d("Asphalt", "Intensity: ${event.intensity}")
        // Return false to suppress this event (it will not be stored or uploaded)
        return true
    }

    override fun onStateChanged(active: Boolean) {
        Log.d("Asphalt", "Collection active: $active")
    }

    override fun onBatchUploaded(eventCount: Int) {
        Log.d("Asphalt", "Uploaded $eventCount events")
    }

    override fun onUploadFailed(error: Throwable) {
        Log.w("Asphalt", "Upload failed: ${error.message}")
        // Events are retained locally. WorkManager will retry automatically.
    }
})
```

---

## Vehicle Type Configuration

The SDK supports three vehicle profiles that affect detection thresholds and
false-positive filtering. Set `vehicleType` in `AsphaltConfig` based on the
vehicle your app will be deployed in.

```kotlin
// Four-wheeler (car, taxi, SUV) — default
val config = AsphaltConfig(
    ingestUrl = "https://your-backend.example.com/v1/ingest/batch",
    vehicleType = VehicleType.FOUR_WHEELER
)

// Three-wheeler (auto rickshaw, tuk-tuk)
val config = AsphaltConfig(
    ingestUrl = "https://your-backend.example.com/v1/ingest/batch",
    vehicleType = VehicleType.THREE_WHEELER
)

// Two-wheeler (motorcycle, scooter)
val config = AsphaltConfig(
    ingestUrl = "https://your-backend.example.com/v1/ingest/batch",
    vehicleType = VehicleType.TWO_WHEELER
)
```

**How vehicle type affects detection:**

| Vehicle | Detection threshold | Gyro confirmation | Baseline window | Cooldown | Special filtering |
|---------|-------------------|-------------------|-----------------|----------|-------------------|
| `FOUR_WHEELER` | 4.0 m/s^2 | 0.30 rad/s | 64 samples (~1.3 s) | 1500 ms | None |
| `TWO_WHEELER` | 5.0 m/s^2 | 0.45 rad/s | 80 samples (~1.6 s) | 1500 ms | None |
| `THREE_WHEELER` | 5.5 m/s^2 | 0.55 rad/s | 96 samples (~1.9 s) | 2000 ms | Engine vibration, turn, and wobble suppression |

The higher threshold and larger baseline window for three-wheelers compensate for
their characteristic engine vibration (typically 15-25 Hz at idle) and lateral
body sway, which would otherwise generate false-positive detections on smooth roads.

**Important: vehicle type is fixed per session.**
`vehicleType` is baked into `AnomalyDetector` when `Asphalt.start()` is called.
To change vehicle type mid-trip, call `Asphalt.stop()`, update the config, then
call `Asphalt.start()` again:

```kotlin
Asphalt.stop()
Asphalt.setConfig(config.copy(vehicleType = VehicleType.THREE_WHEELER))
Asphalt.start()
```

---

## Configuration Reference

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ingestUrl` | (required) | Backend ingestion endpoint URL |
| `vehicleType` | `FOUR_WHEELER` | Vehicle profile for detection tuning |
| `minSpeedKmh` | 15.0 | Minimum speed to activate sensors |
| `detectionThresholdMs2` | 4.0 | Minimum Z-axis delta to flag a candidate |
| `detectionWindowMs` | 500 | Detection window width in ms |
| `gyroConfirmationThresholdRadS` | 0.3 | Minimum gyro magnitude to confirm event |
| `maxGpsAccuracyMeters` | 50.0 | GPS accuracy threshold for event tagging |
| `uploadIntervalSeconds` | 300 | Seconds between background upload attempts |
| `maxBufferSize` | 200 | Max events before forcing an upload |
| `requireUnmeteredNetwork` | false | Only upload on Wi-Fi |
| `debugLogging` | false | Enable verbose SDK logs |

---

## ProGuard / R8

Add to your `proguard-rules.pro`:

```
# Asphalt SDK
-keep class io.asphalt.sdk.** { *; }
-keep interface io.asphalt.sdk.** { *; }
```

---

## Background Collection

The SDK does not require a Foreground Service for normal operation. WorkManager
handles uploads in the background without keeping the process alive.

For continuous collection while the app is fully backgrounded (required on
Android 10+ for uninterrupted location access), you must:

1. Declare `ACCESS_BACKGROUND_LOCATION` in the manifest (see Permissions above)
2. Create a foreground service with `android:foregroundServiceType="location"`
   that calls `Asphalt.start()`
3. Display a persistent notification as required by Android policy (API 26+)
4. Justify background location use in your Google Play policy disclosure

This is outside the scope of the demo app but follows standard Android
foreground service patterns.

---

## Testing Without Driving

The `DemoViewModel.simulateBump()` method creates a synthetic `RoadEvent` and
injects it directly into the event list. This allows testing the UI and upload
pipeline on an emulator or stationary device.

For detector unit testing, use `AnomalyDetector` directly:

```kotlin
val config = AsphaltConfig(ingestUrl = "http://localhost/")
val detector = AnomalyDetector(config)

// Feed a synthetic pothole signature
for (i in 0..50) {
    val z = when (i) {
        in 10..14 -> 7.0f  // dip
        in 15..20 -> 15.5f // spike
        else -> 9.81f
    }
    detector.feedAccelerometer(i * 20L, z)
    detector.feedGyroscope(i * 20L, 0.1f, 0.7f, 0.1f)
}

val result = detector.evaluate(1000L, speedKmh = 50f)
assert(result.detected)
assert(result.anomalyType == AnomalyType.POTHOLE)
```
