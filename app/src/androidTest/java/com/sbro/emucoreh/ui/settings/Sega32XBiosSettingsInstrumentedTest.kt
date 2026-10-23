package com.sbro.emucoreh.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.Sega32XBios
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sega32XBiosSettingsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun allThreeInstalledRolesHaveReachableImportControls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imported = mutableListOf<Sega32XBios.Part>()
        composeRule.setContent {
            EmuCoreHTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Sega32XBiosSlots(Sega32XBios.Part.entries.toSet(), false) { imported.add(it) }
                }
            }
        }
        Sega32XBios.Part.entries.forEach { part ->
            composeRule.onNodeWithTag("sega_32x_bios_status_${part.name}")
                .performScrollTo().assertTextEquals(context.getString(R.string.sega_bios_installed))
            composeRule.onNodeWithTag("sega_32x_bios_import_${part.name}")
                .performScrollTo().assertIsDisplayed().performClick()
        }
        composeRule.runOnIdle { assertEquals(Sega32XBios.Part.entries.toList(), imported) }
    }

    @Test fun pendingImportDisablesEverySlotWithoutHidingIt() {
        composeRule.setContent {
            EmuCoreHTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Sega32XBiosSlots(emptySet(), true) {}
                }
            }
        }
        Sega32XBios.Part.entries.forEach { part ->
            composeRule.onNodeWithTag("sega_32x_bios_import_${part.name}")
                .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        }
    }

    @Test fun invalidImportsPreserveValidatedRolesAndValidImportReplacesThem() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixtures = File(context.filesDir, "qa-32x-bios")
        assumeTrue(Sega32XBios.Part.entries.all { File(fixtures, it.fileName).isFile })
        val directory = Sega32XBios.directory(context).apply { mkdirs() }
        for (part in Sega32XBios.Part.entries) {
            val target = File(directory, part.fileName)
            val previous = target.takeIf { it.isFile }?.readBytes()
            try {
                target.delete()
                val fixture = File(fixtures, part.fileName)
                assertTrue(Sega32XBios.install(context, Uri.fromFile(fixture), part))
                assertTrue(Sega32XBios.isInstalled(context, part))
                val valid = target.readBytes()
                assertArrayEquals(fixture.readBytes(), valid)

                val oversized = File(context.cacheDir, "oversized.bin").apply {
                    writeBytes(ByteArray(part.size + 1))
                }
                assertFalse(Sega32XBios.install(context, Uri.fromFile(oversized), part))
                assertArrayEquals(valid, target.readBytes())

                val reversed = File(context.cacheDir, "reversed.bin").apply {
                    writeBytes(fixture.readBytes().reversedArray())
                }
                assertFalse(Sega32XBios.install(context, Uri.fromFile(reversed), part))
                assertArrayEquals(valid, target.readBytes())

                val wrongRole = Sega32XBios.Part.entries.first { it != part }
                assertFalse(Sega32XBios.install(context, Uri.fromFile(File(fixtures, wrongRole.fileName)), part))
                assertArrayEquals(valid, target.readBytes())
            } finally {
                if (previous != null) target.writeBytes(previous) else target.delete()
            }
        }
    }
}
