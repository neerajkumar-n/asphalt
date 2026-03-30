package io.asphalt.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import io.asphalt.sdk.Asphalt
import io.asphalt.sdk.AsphaltCallback
import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.model.RoadEvent

/**
 * Demo application entry point.
 *
 * This activity demonstrates:
 * 1. SDK initialisation and permission flow
 * 2. Starting and stopping detection
 * 3. Receiving real-time event callbacks
 * 4. Triggering simulated bump events for testing without driving
 *
 * In a production app, you would replace the TextView event list with
 * a proper map fragment (e.g. Google Maps or Mapbox).
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
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            initSdk()
        } else {
            statusText.text = "Location permission is required for road detection."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[DemoViewModel::class.java]

        statusText = findViewById(R.id.statusText)
        eventCountText = findViewById(R.id.eventCountText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        simulateButton = findViewById(R.id.simulateButton)
        eventLog = findViewById(R.id.eventLog)

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

        // Observe live events
        viewModel.events.observe(this) { events ->
            eventCountText.text = "Events detected: ${events.size}"
            if (events.isNotEmpty()) {
                val last = events.last()
                eventLog.text = events.takeLast(10).reversed().joinToString("\n") { e ->
                    "[${e.anomalyType.value}] intensity=${"%.2f".format(e.intensity)} " +
                    "speed=${"%.1f".format(e.speedKmh)}km/h " +
                    "lat=${"%.5f".format(e.latitude)},lon=${"%.5f".format(e.longitude)}"
                }
            }
        }

        viewModel.sdkActive.observe(this) { active ->
            statusText.text = if (active) "Detecting road anomalies..." else "Idle (waiting for speed > 15 km/h)"
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            initSdk()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun initSdk() {
        // In a real app, the ingestUrl would be a real server address.
        // For demo purposes, a localhost URL is used.
        val config = AsphaltConfig(
            ingestUrl = "http://10.0.2.2:8080/v1/ingest/batch",  // 10.0.2.2 = Android emulator host
            minSpeedKmh = 15f,
            debugLogging = true
        )
        Asphalt.init(this, config)
        Asphalt.setCallback(viewModel.asphaltCallback)

        statusText.text = "SDK ready. Press Start to begin."
        startButton.isEnabled = true
    }
}
