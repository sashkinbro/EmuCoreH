// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.ui.emulation

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.RuntimeTestActivity
import com.sbro.emucoreh.core.RuntimeFailure
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFailureDialogInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun showsDiagnosticAndRequiresExplicitExitInsteadOfResumingOnBack() {
        val exits = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Own this target-package Activity explicitly. ActivityScenario's
        // separate test-package EmptyActivity triggers Lenovo association-launch
        // permission UI during teardown; no device permissions are changed here.
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RuntimeTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as RuntimeTestActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        RuntimeFailureDialog(RuntimeFailure("probe: graphics device lost")) { exits.incrementAndGet() }
                    }
                }
            }
            compose.onNodeWithTag("runtime-failure-detail")
                .assertIsDisplayed().assertTextEquals("probe: graphics device lost")
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            checkNotNull(screenshot)
            File(instrumentation.targetContext.getExternalFilesDir(null), "runtime-failure-dialog-test.png")
                .outputStream().use { stream ->
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            screenshot.recycle()
            Espresso.pressBack()
            compose.onNodeWithTag("runtime-failure-exit").assertIsDisplayed()
            assertEquals(0, exits.get())
            compose.onNodeWithTag("runtime-failure-exit").performClick()
            assertEquals(1, exits.get())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
