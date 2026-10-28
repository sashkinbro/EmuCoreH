package com.sbro.emucoreh.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaliSx2DriverCompatibilityTest {
    private val maliDevice = GpuDriverVendor.MALI
    private val sx2Driver = driver(id = "mali-sx2-0.1.4", gpu = "Mali SX2")
    private val ordinaryMaliDriver = driver(id = "panvk-generic", gpu = "Mali PanVK (Valhall)")

    @Test
    fun supportedMaliV9AndV11RendererModelsAllowTheSx2CatalogEntry() {
        listOf(
            "Mali-G57 MC4", "Mali-G68 MP5", "Mali-G77 MC9", "Mali-G78 MC5",
            "Mali-G78AE MC4", "Mali-G615 MC6", "Mali-G715 MP7", "Immortalis-G715"
        )
            .forEach { renderer ->
                val model = DeviceGpuInfoResolver.resolve(renderer, null)
                assertTrue("$renderer should support the Mali SX2 catalog row", GpuDriverCompatibility.isCatalogDriverCompatible(
                    sx2Driver, maliDevice, model
                ))
            }
    }

    @Test
    fun sx2FailsClosedForUnknownOrUnsupportedMaliModelsWhileOtherMaliDriversKeepExistingBehavior() {
        val unknown = DeviceGpuInfoResolver.resolve(null, null)
        val unsupported = DeviceGpuInfoResolver.resolve("Mali-G610 MC4", null)
        for (model in listOf(unknown, unsupported)) {
            assertFalse(GpuDriverCompatibility.isCatalogDriverCompatible(sx2Driver, maliDevice, model))
            assertTrue(GpuDriverCompatibility.isCatalogDriverCompatible(ordinaryMaliDriver, maliDevice, model))
        }
    }

    @Test
    fun onlyTheSx2InstalledLibraryGetsRendererSpecificValidation() {
        val supported = DeviceGpuInfoResolver.resolve("ARM Mali-G78AE", null)
        val unsupported = DeviceGpuInfoResolver.resolve("ARM Mali-G610", null)
        assertTrue(GpuDriverCompatibility.isInstalledDriverCompatible(
            "libvulkan_malisx2.so", maliDevice, maliDevice, supported
        ))
        assertTrue(GpuDriverCompatibility.isInstalledDriverCompatible(
            "drivers/vendor/libvulkan_malisx2.so", maliDevice, maliDevice, supported
        ))
        assertFalse(GpuDriverCompatibility.isInstalledDriverCompatible(
            "libvulkan_malisx2.so", maliDevice, maliDevice, unsupported
        ))
        assertFalse(GpuDriverCompatibility.isInstalledDriverCompatible(
            "libvulkan_malisx2.so", maliDevice, maliDevice, null
        ))
        assertTrue(GpuDriverCompatibility.isInstalledDriverCompatible(
            "libvulkan_panvk.so", maliDevice, maliDevice, null
        ))
    }

    @Test
    fun sx2RequiresAValidatedGlRendererRatherThanASocCatalogGuess() {
        val socOnly = DeviceGpuInfoResolver.resolve(null, "MediaTek Dimensity 9400")
        assertTrue(socOnly.model != null)
        assertFalse(GpuDriverCompatibility.isCatalogDriverCompatible(sx2Driver, maliDevice, socOnly))
    }

    @Test
    fun mediatekMaliDevicesAreEligibleForTheCustomDriverManager() {
        assertTrue(GpuDriverCompatibility.supportsCustomDrivers("MediaTek MT6989 Mali-G610"))
        assertTrue(GpuDriverCompatibility.supportsCustomDrivers("Samsung Exynos 2400 Xclipse 940"))
        assertFalse(GpuDriverCompatibility.supportsCustomDrivers("generic board"))
    }

    private fun driver(id: String, gpu: String) = RemoteGpuDriver(
        id = id,
        name = id,
        variant = "Mali SX2",
        gpu = gpu,
        description = "",
        recommended = false,
        downloadUrl = "https://example.com/$id.zip",
        sourceUrl = "",
        credits = "",
        sizeBytes = null
    )
}
