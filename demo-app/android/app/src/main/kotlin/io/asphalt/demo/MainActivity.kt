package io.asphalt.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import android.widget.Button
import android.widget.TextView
import io.asphalt.sdk.Asphalt

/**
 * Demo application entry point.
 *
 * This activity demonstrates:
 * 1. Runtime location permission request
 * 2. Starting and stopping detection via the SDK
 * 3. Receiving real-time event callbacks via DemoViewModel
 * 4. Triggering simulated bump events for testing without driving
 *
 * SDK initialisation (Asphalt.init) happens in [AsphaltDemoApplication.onCreate],
 * not here. This ensures WorkManager can restart the upload worker after a
 * process death without needing an activity to run first.
 *
 * In a production app, replace the TextView event log with a map fragment
 * (e.g. Google Maps or Mapbox) that plots detected events as markers.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: DemoViewModel
    private lateinit var statusText: TextView
    private lateinit var eventCountText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var simulateButton: Button
    private lateinit var eventLog: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            onLocationPermissionGranted()
        } else {
            statusText.text = "Location permission is required for road detection."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[DemoViewModel::class.java]

        statusText    = findViewById(R.id.statusText)
        eventCountText = findViewById(R.id.eventCountText)
        startButton   = findViewById(R.id.startButton)
        stopButton    = findViewById(R.id.stopButton)
        simulateButton = findViewById(R.id.simulateButton)
        eventLog      = findViewById(R.id.eventLog)

        startButton.setOnClickListener {
            Asphalt.start()
            startButton.isEnabled = false
            stopButton.isEnabled = true
        }

        stopButton.setOnClickListener {
            Asphalt.stop()
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }

        simulateButton.setOnClickListener {
            viewModel.simulateBump()
        }

        // Show last 10 events in reverse-chronological order.
        viewModel.events.observe(this) { events ->
            eventCountText.text = "Events detected: ${events.size}"
            if (events.isNotEmpty()) {
                eventLog.text = events.takeLast(10).reversed().joinToString("\n") { e ->
                    "[${e.anomalyType.value}] intensity=${"%.2f".format(e.intensity)} " +
                    "speed=${"%.1f".format(e.speedKmh)}km/h " +
                    "lat=${"%.5f".format(e.latitude)},lon=${"%.5f".format(e.longitude)}"
                }
            }
        }

        // Show the actual speed threshold so debug vs release are clearly different.
        val speedThreshold = if (BuildConfig.DEBUG) "5" else "15"
        viewModel.sdkActive.observe(this) { active ->
            statusText.text = if (active) {
                "Detecting road anomalies..."
            } else {
                "Idle (waiting for speed > $speedThreshold km/h)"
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val allGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (allGranted) {
            onLocationPermissionGranted()
        } else {
            // On Android 10+ (API 29), ACCESS_BACKGROUND_LOCATION must be requested
            // in a separate prompt AFTER ACCESS_FINE_LOCATION is granted. The system
            // rejects bundling them together. For this demo, we only ask for foreground
            // location here. Add a second prompt if you need background collection.
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    /**
     * Called once ACCESS_FINE_LOCATION is confirmed granted.
     *
     * The SDK was already initialised in [AsphaltDemoApplication]; we only need
     * to wire up the callback and enable the Start button. We do not call
     * Asphalt.init() again here -- doing so would reset the config that
     * Application already set.
     */
    private fun onLocationPermissionGranted() {
        Asphalt.setCallback(viewModel.asphaltCallback)
        statusText.text = "SDK ready. Press Start to begin."
        startButton.isEnabled = true
    }
}
