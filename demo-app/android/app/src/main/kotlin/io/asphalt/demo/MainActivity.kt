package io.asphalt.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
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
 * 1. DPDP-compliant consent gate on first launch
 * 2. Foreground (fine) location permission request
 * 3. Background location permission request on Android 10+ (H)
 * 4. Starting and stopping detection via [DetectionForegroundService]
 * 5. Receiving real-time event callbacks via [DemoViewModel]
 * 6. Triggering simulated bump events for screen-off testing
 *
 * ## SDK initialisation
 *
 * [Asphalt.init] is called in [AsphaltDemoApplication.onCreate], not here.
 * This ensures WorkManager can restart the upload worker after process death
 * without needing an activity to run first.
 *
 * [Asphalt.start] and [Asphalt.stop] are called by [DetectionForegroundService]
 * to keep detection alive when the user switches apps or locks the screen.
 *
 * ## Permission flow
 *
 * First launch:
 *   DPDP consent → ACCESS_FINE_LOCATION → (Android 10+) ACCESS_BACKGROUND_LOCATION → SDK ready
 *
 * Subsequent launches (consent stored):
 *   Already granted check → SDK ready (or prompt if somehow revoked)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: DemoViewModel
    private lateinit var statusText: TextView
    private lateinit var eventCountText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var simulateButton: Button
    private lateinit var eventLog: TextView

    // -------------------------------------------------------------------------
    // Permission launchers
    // -------------------------------------------------------------------------

    /** Step 1: request ACCESS_FINE_LOCATION */
    private val requestFineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            handleFineLocationGranted()
        } else {
            statusText.text = "Location permission is required for road detection."
        }
    }

    /**
     * Step 2 (Android 10+ only): request ACCESS_BACKGROUND_LOCATION.
     *
     * Background location allows the SDK to keep collecting while the screen
     * is locked or the user is in another app. Declining is non-fatal for the
     * demo — the app still works when the screen is on.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Background location result is informational only; SDK starts regardless.
        // Without it, detection stops when the app is backgrounded.
        onLocationPermissionGranted()
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[DemoViewModel::class.java]

        statusText     = findViewById(R.id.statusText)
        eventCountText = findViewById(R.id.eventCountText)
        startButton    = findViewById(R.id.startButton)
        stopButton     = findViewById(R.id.stopButton)
        simulateButton = findViewById(R.id.simulateButton)
        eventLog       = findViewById(R.id.eventLog)

        // Start / stop via DetectionForegroundService so detection survives backgrounding.
        startButton.setOnClickListener {
            val intent = Intent(this, DetectionForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            startButton.isEnabled = false
            stopButton.isEnabled = true
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, DetectionForegroundService::class.java))
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }

        simulateButton.setOnClickListener {
            viewModel.simulateBump()
        }

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

        val speedThreshold = if (BuildConfig.DEBUG) "5" else "15"
        viewModel.sdkActive.observe(this) { active ->
            statusText.text = if (active) {
                "Detecting road anomalies..."
            } else {
                "Idle (waiting for speed > $speedThreshold km/h)"
            }
        }

        // I: DPDP consent gate — must be accepted before permission requests
        if (ConsentManager.hasConsent(this)) {
            checkPermissions()
        } else {
            showConsentDialog()
        }
    }

    // -------------------------------------------------------------------------
    // I: DPDP consent dialog
    // -------------------------------------------------------------------------

    /**
     * Displays a one-time data collection notice compliant with India's
     * Digital Personal Data Protection Act 2023.
     *
     * Consent is stored in SharedPreferences. The dialog is not shown again
     * on subsequent launches unless app data is cleared.
     */
    private fun showConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("Road Data Collection")
            .setMessage(
                "This app collects anonymised road quality data to help map potholes " +
                "and road damage in your area.\n\n" +
                "What is collected:\n" +
                "• Sensor readings (accelerometer, gyroscope) while driving\n" +
                "• GPS coordinates when a road anomaly is detected\n" +
                "• Anonymous device info (manufacturer, model, Android version)\n\n" +
                "What is NOT collected:\n" +
                "• No names, accounts, or personal identifiers\n" +
                "• No continuous location tracking\n" +
                "• No raw sensor data\n\n" +
                "Data is used solely to improve road quality maps. " +
                "You can withdraw at any time by clearing app data."
            )
            .setPositiveButton("Accept") { _, _ ->
                ConsentManager.setConsent(this, true)
                checkPermissions()
            }
            .setNegativeButton("Decline") { _, _ ->
                ConsentManager.setConsent(this, false)
                statusText.text = "Road data collection declined. Detection is disabled."
                startButton.isEnabled = false
            }
            .setCancelable(false)  // user must make an explicit choice
            .show()
    }

    // -------------------------------------------------------------------------
    // Permission flow
    // -------------------------------------------------------------------------

    private fun checkPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            handleFineLocationGranted()
        } else {
            requestFineLocationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    /**
     * Called once ACCESS_FINE_LOCATION is confirmed granted.
     *
     * On Android 10+, Android requires a separate runtime prompt for
     * ACCESS_BACKGROUND_LOCATION after the foreground permission is granted.
     * The system rejects attempts to request both permissions in the same prompt.
     */
    private fun handleFineLocationGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestBackgroundLocationIfNeeded()
        } else {
            onLocationPermissionGranted()
        }
    }

    /** H: Request ACCESS_BACKGROUND_LOCATION on Android 10+ if not already granted. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestBackgroundLocationIfNeeded() {
        val bgGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (bgGranted) {
            onLocationPermissionGranted()
            return
        }

        // Android policy requires showing a rationale before requesting background
        // location. The dialog explains the benefit before the system prompt appears.
        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage(
                "To detect potholes while you're in another app or the screen is locked, " +
                "Asphalt needs the \"Allow all the time\" location option.\n\n" +
                "On the next screen, select \"Allow all the time\" to enable background detection. " +
                "You can change this in Settings at any time."
            )
            .setPositiveButton("Continue") { _, _ ->
                requestBackgroundLocationLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
            .setNegativeButton("Skip") { _, _ ->
                // Non-fatal: app still works when screen is on
                onLocationPermissionGranted()
            }
            .show()
    }

    /**
     * Called once all required permissions are resolved (fine location granted;
     * background location either granted or user chose to skip).
     *
     * Wires the SDK callback to the ViewModel and enables the Start button.
     * The SDK was already initialised in [AsphaltDemoApplication]; we only
     * need to register the callback here. Never call [Asphalt.init] again.
     */
    private fun onLocationPermissionGranted() {
        Asphalt.setCallback(viewModel.asphaltCallback)
        statusText.text = "SDK ready. Press Start to begin."
        startButton.isEnabled = true
    }
}
