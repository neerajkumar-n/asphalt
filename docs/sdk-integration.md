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

## Configuration Reference

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ingestUrl` | (required) | Backend ingestion endpoint URL |
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

For continuous collection while the app is fully backgrounded (foreground
service required on Android 8+), you must:

1. Create a foreground service that calls `Asphalt.start()`
2. Add `ACCESS_BACKGROUND_LOCATION` permission and justify it in your Play
   Store submission
3. Display a persistent notification as required by Android policy

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
