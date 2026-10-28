package com.sbro.emucoreh.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.R
import com.sbro.emucoreh.ui.common.SupportedSystemsCard
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class SupportedSystemsCardInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun supportedFormatsExpandAndCollapse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme { SupportedSystemsCard() } }
        compose.onNodeWithTag("supported_systems_card").assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("captureCard") == "true") {
            File(context.cacheDir, "supported-systems-card.png").outputStream().use {
                check(compose.onNodeWithTag("supported_systems_card").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithTag("supported_systems_formats").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.formats_title)).performClick()
        compose.onNodeWithText(".32x", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText(".chd", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText(".zip", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(context.getString(R.string.formats_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("supported_systems_formats").assertDoesNotExist()
    }
}
