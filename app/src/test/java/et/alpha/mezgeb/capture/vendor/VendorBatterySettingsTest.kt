package et.alpha.mezgeb.capture.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorBatterySettingsTest {

    @Test
    fun `manufacturer strings map to their vendor whatever the casing`() {
        assertEquals(DeviceVendor.XIAOMI, VendorBatterySettings.vendorFor("Xiaomi"))
        assertEquals(DeviceVendor.XIAOMI, VendorBatterySettings.vendorFor("REDMI"))
        assertEquals(DeviceVendor.SAMSUNG, VendorBatterySettings.vendorFor("samsung"))
        assertEquals(DeviceVendor.HUAWEI, VendorBatterySettings.vendorFor("HONOR"))
    }

    @Test
    fun `the transsion brands share one power manager`() {
        // Tecno, Infinix and itel are a large share of the phones in this market and
        // all three ship the same battery manager.
        assertEquals(DeviceVendor.TRANSSION, VendorBatterySettings.vendorFor("TECNO MOBILE LIMITED"))
        assertEquals(DeviceVendor.TRANSSION, VendorBatterySettings.vendorFor("Infinix"))
        assertEquals(DeviceVendor.TRANSSION, VendorBatterySettings.vendorFor("itel"))
    }

    @Test
    fun `an unknown or absent manufacturer is not guessed at`() {
        assertEquals(DeviceVendor.OTHER, VendorBatterySettings.vendorFor("Google"))
        assertEquals(DeviceVendor.OTHER, VendorBatterySettings.vendorFor(null))
        assertEquals(DeviceVendor.OTHER, VendorBatterySettings.vendorFor("  "))
    }

    @Test
    fun `every vendor with a screen offers at least one candidate`() {
        DeviceVendor.entries
            .filter { it != DeviceVendor.OTHER }
            .forEach { vendor ->
                assertTrue(
                    "$vendor has no candidate activity",
                    VendorBatterySettings.targetsFor(vendor).isNotEmpty(),
                )
            }
    }

    @Test
    fun `stock android is sent to the stock screen`() {
        assertTrue(VendorBatterySettings.targetsFor(DeviceVendor.OTHER).isEmpty())
    }
}
