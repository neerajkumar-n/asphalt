package io.asphalt.sdk.detection

import io.asphalt.sdk.model.VehicleType
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that VehicleProfile constants encode the correct ordering of
 * thresholds and weights. These are physics-grounded invariants: changing a
 * value in VehicleProfile.kt should fail a test here so the change is
 * deliberate, not accidental.
 */
class VehicleProfileTest {

    // -------------------------------------------------------------------------
    // forVehicleType factory
    // -------------------------------------------------------------------------

    @Test
    fun `forVehicleType returns the correct singleton for each type`() {
        assertSame(VehicleProfile.FOUR_WHEELER, VehicleProfile.forVehicleType(VehicleType.FOUR_WHEELER))
        assertSame(VehicleProfile.THREE_WHEELER, VehicleProfile.forVehicleType(VehicleType.THREE_WHEELER))
        assertSame(VehicleProfile.TWO_WHEELER, VehicleProfile.forVehicleType(VehicleType.TWO_WHEELER))
    }

    @Test
    fun `each profile carries the matching VehicleType tag`() {
        assertEquals(VehicleType.FOUR_WHEELER, VehicleProfile.FOUR_WHEELER.vehicleType)
        assertEquals(VehicleType.THREE_WHEELER, VehicleProfile.THREE_WHEELER.vehicleType)
        assertEquals(VehicleType.TWO_WHEELER, VehicleProfile.TWO_WHEELER.vehicleType)
    }

    // -------------------------------------------------------------------------
    // Detection threshold ordering
    // THREE_WHEELER has the highest ambient noise floor, so its detection
    // threshold must be the highest. TWO_WHEELER is between car and auto.
    // -------------------------------------------------------------------------

    @Test
    fun `detection threshold: THREE_WHEELER greater than TWO_WHEELER greater than FOUR_WHEELER`() {
        val car = VehicleProfile.FOUR_WHEELER.detectionThresholdMs2
        val bike = VehicleProfile.TWO_WHEELER.detectionThresholdMs2
        val auto = VehicleProfile.THREE_WHEELER.detectionThresholdMs2
        assertTrue(
            "Expected car($car) < bike($bike) < auto($auto)",
            car < bike && bike < auto
        )
    }

    // -------------------------------------------------------------------------
    // Gyro confirmation threshold ordering
    // Autos have sustained low-level gyro from structural flex, so their
    // confirmation threshold must be the highest.
    // -------------------------------------------------------------------------

    @Test
    fun `gyro confirmation threshold: THREE_WHEELER highest`() {
        assertTrue(
            VehicleProfile.THREE_WHEELER.gyroConfirmationThresholdRadS >
                    VehicleProfile.FOUR_WHEELER.gyroConfirmationThresholdRadS
        )
        assertTrue(
            VehicleProfile.THREE_WHEELER.gyroConfirmationThresholdRadS >
                    VehicleProfile.TWO_WHEELER.gyroConfirmationThresholdRadS
        )
    }

    // -------------------------------------------------------------------------
    // Baseline window: noisier vehicles need longer windows for stable median
    // -------------------------------------------------------------------------

    @Test
    fun `baseline window: THREE_WHEELER longer than FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.THREE_WHEELER.baselineWindowSamples >
                    VehicleProfile.FOUR_WHEELER.baselineWindowSamples
        )
    }

    @Test
    fun `baseline window: TWO_WHEELER longer than FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.TWO_WHEELER.baselineWindowSamples >
                    VehicleProfile.FOUR_WHEELER.baselineWindowSamples
        )
    }

    // -------------------------------------------------------------------------
    // Engine vibration filter
    // Only THREE_WHEELER should have the zero-crossings filter set.
    // -------------------------------------------------------------------------

    @Test
    fun `engine vibration filter only set for THREE_WHEELER`() {
        assertNotNull(VehicleProfile.THREE_WHEELER.engineVibrationZeroCrossingsPerSecond)
        assertNull(VehicleProfile.FOUR_WHEELER.engineVibrationZeroCrossingsPerSecond)
        assertNull(VehicleProfile.TWO_WHEELER.engineVibrationZeroCrossingsPerSecond)
    }

    @Test
    fun `THREE_WHEELER zero-crossing threshold is positive`() {
        val threshold = VehicleProfile.THREE_WHEELER.engineVibrationZeroCrossingsPerSecond!!
        assertTrue("zero-crossings threshold must be > 0, got $threshold", threshold > 0f)
    }

    // -------------------------------------------------------------------------
    // Signal weight ordering
    // FOUR_WHEELER (cleanest signal) must have the highest weight.
    // -------------------------------------------------------------------------

    @Test
    fun `signal weight: FOUR_WHEELER highest`() {
        assertTrue(VehicleProfile.FOUR_WHEELER.signalWeight > VehicleProfile.TWO_WHEELER.signalWeight)
        assertTrue(VehicleProfile.FOUR_WHEELER.signalWeight > VehicleProfile.THREE_WHEELER.signalWeight)
    }

    @Test
    fun `signal weight: all profiles in range 0 to 1`() {
        for (profile in listOf(
            VehicleProfile.FOUR_WHEELER,
            VehicleProfile.TWO_WHEELER,
            VehicleProfile.THREE_WHEELER
        )) {
            assertTrue(
                "${profile.vehicleType} signalWeight ${profile.signalWeight} out of [0,1]",
                profile.signalWeight in 0f..1f
            )
        }
    }

    // -------------------------------------------------------------------------
    // Turn suppression: bikes suppress at lower threshold than cars (lean-sensitive)
    // -------------------------------------------------------------------------

    @Test
    fun `turn suppression threshold: TWO_WHEELER lower than FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.TWO_WHEELER.turnSuppressionThresholdRadS <
                    VehicleProfile.FOUR_WHEELER.turnSuppressionThresholdRadS
        )
    }

    // -------------------------------------------------------------------------
    // Cooldown: autos need longer cooldown due to post-impact ring-out
    // -------------------------------------------------------------------------

    @Test
    fun `cooldown: THREE_WHEELER at least as long as FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.THREE_WHEELER.cooldownMs >= VehicleProfile.FOUR_WHEELER.cooldownMs
        )
    }

    // -------------------------------------------------------------------------
    // Sanity bounds: all critical fields must be strictly positive
    // -------------------------------------------------------------------------

    @Test
    fun `all numeric thresholds are strictly positive`() {
        for (profile in listOf(
            VehicleProfile.FOUR_WHEELER,
            VehicleProfile.TWO_WHEELER,
            VehicleProfile.THREE_WHEELER
        )) {
            val type = profile.vehicleType
            assertTrue("$type detectionThresholdMs2 <= 0", profile.detectionThresholdMs2 > 0f)
            assertTrue("$type gyroConfirmationThresholdRadS <= 0", profile.gyroConfirmationThresholdRadS > 0f)
            assertTrue("$type baselineWindowSamples <= 0", profile.baselineWindowSamples > 0)
            assertTrue("$type turnSuppressionThresholdRadS <= 0", profile.turnSuppressionThresholdRadS > 0f)
            assertTrue("$type turnSuppressionDurationMs <= 0", profile.turnSuppressionDurationMs > 0L)
            assertTrue("$type sustainedLateralMinDurationMs <= 0", profile.sustainedLateralMinDurationMs > 0L)
            assertTrue("$type cooldownMs <= 0", profile.cooldownMs > 0L)
        }
    }
}
