package com.sbro.emucoreh.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BundledCoreMetadataTest {
    private val root by lazy {
        listOf(File("."), File("..")).first {
            File(it, "core-jgenesis/frontend/jgenesis-libretro/src/lib.rs").isFile
        }
    }

    @Test fun profileDisplayNamesAllBundledLibrariesWithoutAdvertisingPlaceholderFlycastVersion() {
        val flycast = File(root, "core/shell/libretro/libretro.cpp").readText()
        val flycastName = Regex("info->library_name\\s*=\\s*\"([^\"]+)\"")
            .find(flycast)?.groupValues?.get(1) ?: error("Missing Flycast library name")
        val jgenesis = File(root, "core-jgenesis/frontend/jgenesis-libretro/src/lib.rs").readText()
        val jgenesisName = Regex("LIBRARY_NAME:.*?b\"([^\"\\\\]+)")
            .find(jgenesis)?.groupValues?.get(1) ?: error("Missing jgenesis library name")
        val supermodel = File(root, "core-supermodel/libretro/libretro.cpp").readText()
        val supermodelName = Regex("\\*info=\\{\"([^\"]+)\",\"[^\"]+\"")
            .find(supermodel)?.groupValues?.get(1) ?: error("Missing Supermodel library name")
        val saturn = File(root, "core-saturn/src/libretro/libretro.c").readText()
        val saturnName = Regex("info->library_name\\s*=\\s*\"([^\"]+)\"")
            .find(saturn)?.groupValues?.get(1) ?: error("Missing Saturn library name")
        assertEquals(listOf(flycastName, jgenesisName, supermodelName, saturnName).joinToString("/"),
            BundledCoreMetadata.DISPLAY_VERSION)
        assertTrue(BundledCoreMetadata.DISPLAY_VERSION.length <= 40)
        assertFalse(BundledCoreMetadata.DISPLAY_VERSION.contains("v0.0.0-0-g000000000"))
    }

    @Test fun displayChangesDoNotInvalidateExistingBackupCompatibilityToken() {
        assertEquals("1.0.0", BundledCoreMetadata.BACKUP_COMPATIBILITY_VERSION)
        assertNotEquals(BundledCoreMetadata.BACKUP_COMPATIBILITY_VERSION, BundledCoreMetadata.DISPLAY_VERSION)
    }

    @Test fun firestoreMetadataFieldsCanStoreTheBundledCoreDisplay() {
        val rules = File(root, "firestore.rules").readText()
        val limits = Regex("optionalString\\([^,]+,\\s*'coreVersion',\\s*(\\d+)\\)")
            .findAll(rules).map { it.groupValues[1].toInt() }.toList()
        assertEquals("Public device, private device and cloud settings must all validate core metadata", 3, limits.size)
        limits.forEach { limit ->
            assertTrue("Firestore coreVersion limit $limit rejects the bundled display",
                BundledCoreMetadata.DISPLAY_VERSION.length <= limit)
        }
    }
}
