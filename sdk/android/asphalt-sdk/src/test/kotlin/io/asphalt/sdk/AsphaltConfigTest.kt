package io.asphalt.sdk

import io.asphalt.sdk.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsphaltConfigTest {

    private val url = "https://example.org/v1/ingest/batch"

    @Test
    fun `manifest url alone gives a car config with defaults`() {
        val config = AsphaltConfig.fromManifestValues(url, null)
        assertEquals(url, config.ingestUrl)
        assertEquals(VehicleType.FOUR_WHEELER, config.vehicleType)
        assertEquals(AsphaltConfig(ingestUrl = url), config)
    }

    @Test
    fun `vehicle type accepts friendly and wire names`() {
        assertEquals(VehicleType.TWO_WHEELER, AsphaltConfig.fromManifestValues(url, "motorcycle").vehicleType)
        assertEquals(VehicleType.TWO_WHEELER, AsphaltConfig.fromManifestValues(url, " Two_Wheeler ").vehicleType)
        assertEquals(VehicleType.FOUR_WHEELER, AsphaltConfig.fromManifestValues(url, "car").vehicleType)
        assertEquals(VehicleType.FOUR_WHEELER, AsphaltConfig.fromManifestValues(url, "").vehicleType)
    }

    @Test
    fun `missing url explains the meta-data to add`() {
        val e = runCatching { AsphaltConfig.fromManifestValues(null, null) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains(AsphaltConfig.META_INGEST_URL))
        assertTrue(runCatching { AsphaltConfig.fromManifestValues("  ", null) }.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown vehicle type is rejected`() {
        AsphaltConfig.fromManifestValues(url, "bus")
    }
}
