package io.asphalt.sdk.detection

import io.asphalt.sdk.AsphaltConfig
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AnomalyDetector].
 *
 * Tests feed synthetic sensor streams directly into the detector, bypassing
 * Android APIs. Each test verifies a specific aspect of the detection logic.
 *
 * Sensor stream helpers:
 * - [flatRoad]: flat-road baseline at 9.81 m/s^2 with near-zero gyro
 * - [pothole]: dip-first then spike signature (wheel drops, then rebounds)
 * - [speedBump]: spike-first then dip signature (wheel climbs, then drops)
 * - [engineVibration]: periodic 20Hz oscillation as seen on a three-wheeler at idle
 */
class AnomalyDetectorTest {

    // Config with low thresholds to simplify test signal generation
    private lateinit var carConfig: AsphaltConfig
    private lateinit var autoConfig: AsphaltConfig

    @Before
    fun setUp() {
        carConfig = AsphaltConfig(
            ingestUrl = "http://localhost/",
            vehicleType = VehicleType.FOUR_WHEELER,
            detectionThresholdMs2 = 4.0f,
            detectionWindowMs = 500L,
            gyroConfirmationThresholdRadS = 0.3f
        )
        autoConfig = AsphaltConfig(
            ingestUrl = "http://localhost/",
            vehicleType = VehicleType.THREE_WHEELER,
            detectionWindowMs = 500L
        )
    }

    // -------------------------------------------------------------------------
    // Flat road: no events
    // -------------------------------------------------------------------------

    @Test
    fun `flat road produces no event`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 2000L, stepMs = 20L)

        val result = detector.evaluate(currentTimeMs = 2000L, speedKmh = 50f)
        assertFalse("Flat road should not produce a detection", result.detected)
    }

    @Test
    fun `below speed threshold: evaluate is still callable but detection reflects signal`() {
        // Speed gating is handled by LocationTracker/SensorCollector; the detector
        // itself does not check speed for threshold purposes (only for intensity).
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 500L, stepMs = 20L)
        val result = detector.evaluate(currentTimeMs = 500L, speedKmh = 5f)
        assertFalse(result.detected)
    }

    // -------------------------------------------------------------------------
    // Pothole detection
    // -------------------------------------------------------------------------

    @Test
    fun `pothole signature is detected with car config`() {
        val detector = AnomalyDetector(carConfig)

        // Establish baseline
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)

        // Inject pothole: dip first (7.0), then spike (16.0)
        val baseTime = 1000L
        feedPothole(detector, startMs = baseTime, dipZ = 7.0f, spikeZ = 16.0f, gyroMag = 0.9f)

        val result = detector.evaluate(baseTime + 500L, speedKmh = 50f)
        assertTrue("Pothole should be detected", result.detected)
        assertEquals(AnomalyType.POTHOLE, result.anomalyType)
        assertTrue("Intensity should be positive", result.intensity > 0f)
        assertTrue("Intensity should be <= 1", result.intensity <= 1f)
    }

    @Test
    fun `pothole intensity scales with delta magnitude`() {
        val detector1 = AnomalyDetector(carConfig)
        feedFlatRoad(detector1, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector1, startMs = 1000L, dipZ = 7.0f, spikeZ = 14.0f, gyroMag = 0.8f)
        val result1 = detector1.evaluate(1500L, speedKmh = 50f)

        val detector2 = AnomalyDetector(carConfig)
        feedFlatRoad(detector2, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector2, startMs = 1000L, dipZ = 5.0f, spikeZ = 18.0f, gyroMag = 0.9f)
        val result2 = detector2.evaluate(1500L, speedKmh = 50f)

        assertTrue("Larger delta should produce higher intensity", result2.intensity > result1.intensity)
    }

    @Test
    fun `intensity is clamped to 1_0 for extreme signals`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
        // Extreme jolt: delta of 25 m/s^2
        feedPothole(detector, startMs = 1000L, dipZ = 2.0f, spikeZ = 27.0f, gyroMag = 2.0f)
        val result = detector.evaluate(1500L, speedKmh = 50f)
        assertTrue(result.detected)
        assertEquals(1.0f, result.intensity)
    }

    // -------------------------------------------------------------------------
    // Speed bump classification
    // -------------------------------------------------------------------------

    @Test
    fun `speed bump (spike before dip) is classified as BUMP`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)

        // Spike first (front wheel climbs), then dip (rear drops off back)
        feedSpeedBump(detector, startMs = 1000L, spikeZ = 15.5f, dipZ = 7.5f, gyroMag = 0.7f)

        val result = detector.evaluate(1500L, speedKmh = 30f)
        assertTrue("Speed bump should be detected", result.detected)
        assertEquals(AnomalyType.BUMP, result.anomalyType)
    }

    // -------------------------------------------------------------------------
    // Gyroscope confirmation gate
    // -------------------------------------------------------------------------

    @Test
    fun `accelerometer spike without gyro confirmation is rejected`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)

        // Large Z spike but gyro stays near zero (engine vibration scenario)
        val baseTime = 1000L
        for (i in 0..24) {
            val t = baseTime + i * 20L
            val z = if (i in 5..10) 16.0f else 9.81f
            detector.feedAccelerometer(t, z)
            detector.feedGyroscope(t, 0.02f, 0.01f, 0.02f)  // near-zero gyro
        }

        val result = detector.evaluate(baseTime + 500L, speedKmh = 50f)
        assertFalse("Spike without gyro confirmation should be rejected", result.detected)
    }

    // -------------------------------------------------------------------------
    // Cooldown
    // -------------------------------------------------------------------------

    @Test
    fun `cooldown prevents duplicate events from same impact`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector, startMs = 1000L, dipZ = 7.0f, spikeZ = 16.0f, gyroMag = 0.9f)

        val result1 = detector.evaluate(1500L, speedKmh = 50f)
        assertTrue(result1.detected)

        // Evaluate again 100ms later - should be blocked by cooldown
        val result2 = detector.evaluate(1600L, speedKmh = 50f)
        assertFalse("Second evaluation within cooldown should return no event", result2.detected)
    }

    @Test
    fun `after cooldown expires, new events can be detected`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector, startMs = 1000L, dipZ = 7.0f, spikeZ = 16.0f, gyroMag = 0.9f)

        val result1 = detector.evaluate(1500L, speedKmh = 50f)
        assertTrue(result1.detected)

        // Feed new flat road and another pothole after cooldown
        val afterCooldown = 1500L + 2000L  // 2s after first event > 1500ms cooldown
        feedFlatRoad(detector, durationMs = 500L, stepMs = 20L, startMs = 1500L)
        feedPothole(detector, startMs = afterCooldown, dipZ = 7.0f, spikeZ = 16.0f, gyroMag = 0.9f)

        val result2 = detector.evaluate(afterCooldown + 500L, speedKmh = 50f)
        assertTrue("Event after cooldown should be detected", result2.detected)
    }

    // -------------------------------------------------------------------------
    // SensorSummary contents
    // -------------------------------------------------------------------------

    @Test
    fun `detected event has correct sensor summary fields`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector, startMs = 1000L, dipZ = 7.0f, spikeZ = 16.5f, gyroMag = 0.9f)

        val result = detector.evaluate(1500L, speedKmh = 50f)
        assertTrue(result.detected)

        val summary = result.sensorSummary
        assertTrue("accelPeakZ should be > baseline", summary.accelPeakZ > summary.accelBaselineZ)
        assertTrue("accelDeltaZ should be positive", summary.accelDeltaZ > 0f)
        assertTrue("gyroPeakMagnitude should be positive", summary.gyroPeakMagnitude > 0f)
        assertTrue("sampleCount should be positive", summary.sampleCount > 0)
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears buffers and allows fresh detection`() {
        val detector = AnomalyDetector(carConfig)
        feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
        feedPothole(detector, startMs = 1000L, dipZ = 7.0f, spikeZ = 16.0f, gyroMag = 0.9f)
        detector.evaluate(1500L, speedKmh = 50f)

        detector.reset()

        // After reset, buffer is empty so insufficient samples for evaluation
        val result = detector.evaluate(1600L, speedKmh = 50f)
        assertFalse("No detection expected after reset with empty buffer", result.detected)
    }

    // -------------------------------------------------------------------------
    // Three-wheeler config uses VehicleProfile thresholds
    // -------------------------------------------------------------------------

    @Test
    fun `signal below three-wheeler threshold but above car threshold is not detected on auto config`() {
        // Delta of ~4.5 m/s^2: detectable by car (threshold 4.0) but below auto (threshold 5.5)
        val carDetector = AnomalyDetector(carConfig)
        val autoDetector = AnomalyDetector(autoConfig)

        for (detector in listOf(carDetector, autoDetector)) {
            feedFlatRoad(detector, durationMs = 1000L, stepMs = 20L)
            // spikeZ = 14.3 -> delta from 9.81 baseline = 4.49 m/s^2
            feedPothole(detector, startMs = 1000L, dipZ = 8.5f, spikeZ = 14.3f, gyroMag = 0.6f)
        }

        val carResult = carDetector.evaluate(1500L, speedKmh = 40f)
        val autoResult = autoDetector.evaluate(1500L, speedKmh = 40f)

        assertTrue("Car detector should detect 4.49 m/s^2 delta", carResult.detected)
        assertFalse("Auto detector should NOT detect 4.49 m/s^2 delta (threshold 5.5)", autoResult.detected)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun feedFlatRoad(
        detector: AnomalyDetector,
        durationMs: Long,
        stepMs: Long,
        startMs: Long = 0L
    ) {
        var t = startMs
        while (t < startMs + durationMs) {
            detector.feedAccelerometer(t, 9.81f + (Math.random() * 0.1 - 0.05).toFloat())
            detector.feedGyroscope(t, 0.05f, 0.04f, 0.03f)
            t += stepMs
        }
    }

    /**
     * Feeds a dip-then-spike signature (pothole): wheel falls in, then rebounds.
     * Flat road before and after the event fills the detection window.
     */
    private fun feedPothole(
        detector: AnomalyDetector,
        startMs: Long,
        dipZ: Float,
        spikeZ: Float,
        gyroMag: Float
    ) {
        for (i in 0..24) {
            val t = startMs + i * 20L
            val z = when (i) {
                in 4..6  -> dipZ               // dip phase
                in 7..10 -> spikeZ             // spike phase
                else     -> 9.81f
            }
            val gx = if (i in 4..10) gyroMag * 0.6f else 0.05f
            val gy = if (i in 4..10) gyroMag else 0.04f
            detector.feedAccelerometer(t, z)
            detector.feedGyroscope(t, gx, gy, 0.03f)
        }
    }

    /**
     * Feeds a spike-then-dip signature (speed bump): front wheel climbs, then drops.
     */
    private fun feedSpeedBump(
        detector: AnomalyDetector,
        startMs: Long,
        spikeZ: Float,
        dipZ: Float,
        gyroMag: Float
    ) {
        for (i in 0..24) {
            val t = startMs + i * 20L
            val z = when (i) {
                in 3..7  -> spikeZ             // spike phase (front wheel on bump)
                in 8..12 -> dipZ               // dip phase (rear drops off)
                else     -> 9.81f
            }
            val gy = if (i in 3..12) gyroMag else 0.04f
            detector.feedAccelerometer(t, z)
            detector.feedGyroscope(t, 0.1f, gy, 0.03f)
        }
    }
}
