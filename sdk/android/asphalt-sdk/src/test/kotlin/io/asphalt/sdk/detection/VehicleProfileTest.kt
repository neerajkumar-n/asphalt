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
        assertSame(VehicleProfile.TWO_WHEELER, VehicleProfile.forVehicleType(VehicleType.TWO_WHEELER))
    }

    @Test
    fun `each profile carries the matching VehicleType tag`() {
        assertEquals(VehicleType.FOUR_WHEELER, VehicleProfile.FOUR_WHEELER.vehicleType)
        assertEquals(VehicleType.TWO_WHEELER, VehicleProfile.TWO_WHEELER.vehicleType)
    }

    // -------------------------------------------------------------------------
    // Detection threshold ordering
    // TWO_WHEELER has a higher ambient noise floor (lean, handlebar vibration),
    // so its detection threshold must be higher than a car's.
    // -------------------------------------------------------------------------

    @Test
    fun `detection threshold - TWO_WHEELER greater than FOUR_WHEELER`() {
        val car = VehicleProfile.FOUR_WHEELER.detectionThresholdMs2
        val bike = VehicleProfile.TWO_WHEELER.detectionThresholdMs2
        assertTrue("Expected car($car) < bike($bike)", car < bike)
    }

    // -------------------------------------------------------------------------
    // Gyro confirmation threshold ordering
    // Bikes pitch and roll more during normal riding, so their confirmation
    // threshold must be higher.
    // -------------------------------------------------------------------------

    @Test
    fun `gyro confirmation threshold - TWO_WHEELER greater than FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.TWO_WHEELER.gyroConfirmationThresholdRadS >
                    VehicleProfile.FOUR_WHEELER.gyroConfirmationThresholdRadS
        )
    }

    // -------------------------------------------------------------------------
    // Baseline window: noisier vehicles need longer windows for stable median
    // -------------------------------------------------------------------------

    @Test
    fun `baseline window - TWO_WHEELER longer than FOUR_WHEELER`() {
        assertTrue(
            VehicleProfile.TWO_WHEELER.baselineWindowSamples >
                    VehicleProfile.FOUR_WHEELER.baselineWindowSamples
        )
    }

    // -------------------------------------------------------------------------
    // Signal weight ordering
    // FOUR_WHEELER (cleanest signal) must have the highest weight.
    // -------------------------------------------------------------------------

    @Test
    fun `signal weight - FOUR_WHEELER highest`() {
        assertTrue(VehicleProfile.FOUR_WHEELER.signalWeight > VehicleProfile.TWO_WHEELER.signalWeight)
    }

    @Test
    fun `signal weight - all profiles in range 0 to 1`() {
        for (profile in listOf(VehicleProfile.FOUR_WHEELER, VehicleProfile.TWO_WHEELER)) {
            assertTrue(
                "${profile.vehicleType} signalWeight ${profile.signalWeight} out of [0,1]",
                profile.signalWeight in 0f..1f
            )
        }
    }

    // -------------------------------------------------------------------------
    // Sanity bounds: all critical fields must be strictly positive
    // -------------------------------------------------------------------------

    @Test
    fun `all numeric thresholds are strictly positive`() {
        for (profile in listOf(VehicleProfile.FOUR_WHEELER, VehicleProfile.TWO_WHEELER)) {
            val type = profile.vehicleType
            assertTrue("$type detectionThresholdMs2 <= 0", profile.detectionThresholdMs2 > 0f)
            assertTrue("$type gyroConfirmationThresholdRadS <= 0", profile.gyroConfirmationThresholdRadS > 0f)
            assertTrue("$type baselineWindowSamples <= 0", profile.baselineWindowSamples > 0)
            assertTrue("$type cooldownMs <= 0", profile.cooldownMs > 0L)
        }
    }
}
