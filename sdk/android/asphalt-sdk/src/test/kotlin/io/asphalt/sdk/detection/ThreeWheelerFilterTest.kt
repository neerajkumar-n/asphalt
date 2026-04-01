package io.asphalt.sdk.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ThreeWheelerFilter].
 *
 * Tests feed synthetic sensor streams directly into the filter buffers and
 * verify suppression decisions. No Android APIs are used; all test dependencies
 * are pure Kotlin.
 *
 * ## Test organisation
 *
 * - **Empty buffers**: no data → no suppression
 * - **Engine vibration**: high zero-crossing rate suppresses
 * - **Brief genuine spike**: low zero-crossing rate passes through
 * - **Turn suppression**: sustained roll + yaw above threshold → TURNING
 * - **Lateral wobble**: sustained roll without yaw → LATERAL_WOBBLE
 * - **Brief lateral impact**: single-sample spike → passes through
 * - **Active suppression window**: evaluate within the post-turn window → still suppressed
 * - **Window expiry**: evaluate after turnSuppressionDurationMs with calm data → not suppressed
 * - **Reset**: clears buffers and suppression window
 *
 * ## Sensor helper conventions
 *
 * Samples are fed at 20ms intervals (50 Hz, matching [AsphaltConfig.sensorSamplingRateUs]).
 * A "baseline phase" of 96 stable accelZ samples establishes a stable rolling median
 * before the test event window begins. The THREE_WHEELER profile values used here:
 *
 *   turnSuppressionThresholdRadS   = 0.55 rad/s
 *   sustainedLateralMinDurationMs  = 250 ms → window = 350 ms
 *   turnSuppressionDurationMs      = 1200 ms
 *   engineVibrationZeroCrossPerSec = 25.0 crossings/sec
 */
class ThreeWheelerFilterTest {

    private lateinit var profile: VehicleProfile
    private lateinit var filter: ThreeWheelerFilter

    // 96 stable baseline samples at 20ms each → ends at t = 96*20 = 1920 ms
    private val baselineCount = 96
    private val stepMs = 20L
    private val baseTime = baselineCount * stepMs   // 1920 ms

    @Before
    fun setUp() {
        profile = VehicleProfile.THREE_WHEELER
        filter = ThreeWheelerFilter(profile)
    }

    // -------------------------------------------------------------------------
    // Empty buffers
    // -------------------------------------------------------------------------

    @Test
    fun `empty buffers are not suppressed`() {
        val result = filter.evaluate(1000L)
        assertFalse("Empty buffers should not suppress", result.suppressed)
        assertNull(result.reason)
    }

    // -------------------------------------------------------------------------
    // Engine vibration suppression
    // -------------------------------------------------------------------------

    @Test
    fun `high zero-crossing rate triggers ENGINE_VIBRATION suppression`() {
        // Establish a stable median baseline (96 samples at 9.81)
        feedFlatAccel(0L, baselineCount)

        // Add alternating samples over 500 ms: +1 / -1 around baseline
        // At 50 Hz, 26 samples over 500 ms → 25 sign changes → ~50 crossings/sec >> 25 threshold
        for (i in 0 until 26) {
            val z = if (i % 2 == 0) 10.81f else 8.81f
            filter.feedAccelZ(baseTime + i * stepMs, z)
        }

        val result = filter.evaluate(baseTime + 25 * stepMs)
        assertTrue("Alternating accelZ should trigger engine vibration suppression", result.suppressed)
        assertEquals(ThreeWheelerFilter.SuppressReason.ENGINE_VIBRATION, result.reason)
    }

    @Test
    fun `single spike has low zero-crossing rate and passes engine vibration check`() {
        // Establish baseline
        feedFlatAccel(0L, baselineCount)

        // 26-sample window with one spike at index 12, rest flat
        // AC changes sign at most twice (baseline→spike, spike→baseline) = 0 strict crossings
        // because (9.81 - 9.81) * (14.0 - 9.81) = 0 (no strict negative product)
        for (i in 0 until 26) {
            val z = if (i == 12) 14.0f else 9.81f
            filter.feedAccelZ(baseTime + i * stepMs, z)
        }

        val result = filter.evaluate(baseTime + 25 * stepMs)
        assertFalse("Single spike should not be classified as engine vibration", result.suppressed)
    }

    // -------------------------------------------------------------------------
    // Turn suppression
    // -------------------------------------------------------------------------

    @Test
    fun `sustained roll and yaw above threshold triggers TURNING`() {
        feedFlatAccelAndLateralForTurnSetup()

        // 20 samples of elevated roll + yaw: 0.70 > threshold 0.55
        for (i in 0 until 20) {
            filter.feedLateralGyro(baseTime + i * stepMs, rollRadS = 0.7f, yawRadS = 0.65f)
            filter.feedAccelZ(baseTime + i * stepMs, 9.81f)
        }

        // snapshotInWindow(350): last sample at baseTime+380; cutoff = baseTime+30
        // Samples i=2..19 are in window (18 samples), all > 0.55
        val result = filter.evaluate(baseTime + 19 * stepMs)
        assertTrue("Sustained turn should suppress", result.suppressed)
        assertEquals(ThreeWheelerFilter.SuppressReason.TURNING, result.reason)
    }

    @Test
    fun `sustained roll without yaw triggers LATERAL_WOBBLE`() {
        feedFlatAccelAndLateralForTurnSetup()

        // High roll, low yaw (well below threshold 0.55)
        for (i in 0 until 20) {
            filter.feedLateralGyro(baseTime + i * stepMs, rollRadS = 0.70f, yawRadS = 0.10f)
            filter.feedAccelZ(baseTime + i * stepMs, 9.81f)
        }

        val result = filter.evaluate(baseTime + 19 * stepMs)
        assertTrue("Sustained roll without yaw should suppress as wobble", result.suppressed)
        assertEquals(ThreeWheelerFilter.SuppressReason.LATERAL_WOBBLE, result.reason)
    }

    @Test
    fun `brief lateral spike passes turn check as genuine pothole`() {
        feedFlatAccelAndLateralForTurnSetup()

        // 20 samples with only a single elevated spike at index 10
        for (i in 0 until 20) {
            val roll = if (i == 10) 0.70f else 0.05f
            val yaw  = if (i == 10) 0.65f else 0.03f
            filter.feedLateralGyro(baseTime + i * stepMs, rollRadS = roll, yawRadS = yaw)
            filter.feedAccelZ(baseTime + i * stepMs, 9.81f)
        }

        // 18 samples in window; only 1 elevated → fraction = 1/18 = 0.056 << 0.60
        val result = filter.evaluate(baseTime + 19 * stepMs)
        assertFalse("Single lateral spike should pass turn check (genuine impact)", result.suppressed)
    }

    // -------------------------------------------------------------------------
    // Active suppression window
    // -------------------------------------------------------------------------

    @Test
    fun `active suppression window returns TURNING within turnSuppressionDurationMs`() {
        // Trigger turn suppression
        val turnDetectedAt = triggerTurnSuppression()

        // THREE_WHEELER turnSuppressionDurationMs = 1200 ms; evaluate at +600 ms
        val result = filter.evaluate(turnDetectedAt + 600L)
        assertTrue("Should be suppressed within window", result.suppressed)
        assertEquals(ThreeWheelerFilter.SuppressReason.TURNING, result.reason)
    }

    @Test
    fun `suppression window expires after turnSuppressionDurationMs with calm data`() {
        val turnDetectedAt = triggerTurnSuppression()

        // Feed calm data starting at turnDetectedAt + 1300 ms (window ends at +1200)
        val afterWindowStart = turnDetectedAt + 1300L
        for (i in 0 until 30) {
            filter.feedLateralGyro(afterWindowStart + i * stepMs, rollRadS = 0.04f, yawRadS = 0.03f)
            filter.feedAccelZ(afterWindowStart + i * stepMs, 9.81f)
        }

        // Evaluate at the end of the calm phase; all buffers show quiet signal
        val result = filter.evaluate(afterWindowStart + 29 * stepMs)
        assertFalse("Suppression window should have expired", result.suppressed)
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears suppression window so evaluation returns not suppressed`() {
        val turnDetectedAt = triggerTurnSuppression()

        // Confirm we are suppressed before reset
        assertTrue(filter.evaluate(turnDetectedAt + 300L).suppressed)

        filter.reset()

        // After reset: suppressUntilMs=0 and all buffers empty → not suppressed
        assertFalse("Post-reset should not be suppressed", filter.evaluate(turnDetectedAt + 400L).suppressed)
    }

    @Test
    fun `reset allows fresh engine vibration detection after a previous one`() {
        feedFlatAccel(0L, baselineCount)
        for (i in 0 until 26) {
            val z = if (i % 2 == 0) 10.81f else 8.81f
            filter.feedAccelZ(baseTime + i * stepMs, z)
        }
        val t = baseTime + 25 * stepMs
        assertTrue("Should be suppressed before reset", filter.evaluate(t).suppressed)

        filter.reset()

        // After reset, accelZBuffer is empty → snapshotInWindow returns 0 samples < 4 → 0 crossings
        assertFalse("Post-reset should not be suppressed", filter.evaluate(t + 100L).suppressed)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Feeds [count] flat accelerometer samples starting at [startMs]. */
    private fun feedFlatAccel(startMs: Long, count: Int) {
        for (i in 0 until count) {
            filter.feedAccelZ(startMs + i * stepMs, 9.81f)
        }
    }

    /**
     * Feeds the flat accel baseline AND zero lateral gyro samples needed so
     * the accel baseline check doesn't interfere with turn/wobble tests.
     * No lateral gyro is fed here; test methods add their own.
     */
    private fun feedFlatAccelAndLateralForTurnSetup() {
        feedFlatAccel(0L, baselineCount)
    }

    /**
     * Triggers turn suppression and returns the timestamp at which the turn
     * was detected (so callers can reference it for window checks).
     */
    private fun triggerTurnSuppression(): Long {
        feedFlatAccelAndLateralForTurnSetup()
        for (i in 0 until 20) {
            filter.feedLateralGyro(baseTime + i * stepMs, rollRadS = 0.70f, yawRadS = 0.65f)
            filter.feedAccelZ(baseTime + i * stepMs, 9.81f)
        }
        val turnDetectedAt = baseTime + 19 * stepMs
        filter.evaluate(turnDetectedAt)  // sets suppressUntilMs = turnDetectedAt + 1200
        return turnDetectedAt
    }
}
