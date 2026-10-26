package com.sbro.emucoreh.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.data.SegaTouchLayouts
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucoreh.data.CustomTouchControl
import com.sbro.emucoreh.data.CustomTouchControlLibrary
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsEditorScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showEditor(
        customControls: CustomTouchControlLibrary,
        onCustomControlsChange: (CustomTouchControlLibrary) -> Unit,
        platform: EmulationPlatform = EmulationPlatform.DREAMCAST,
        onOffsets: (Map<String, Pair<Float, Float>>) -> Unit = {}
    ) {
        composeRule.setContent {
            EmuCoreHTheme {
                ControlsEditorScreen(
                    state = ControlsEditorState(customControls = customControls,
                        controlLayouts = SegaTouchLayouts.defaultProfile(platform).controlLayouts),
                    platform = platform,
                    manageActivityOrientation = false,
                    onBackClick = {},
                    onUpdateControlOffset = { id, offset -> onOffsets(mapOf(id to offset)) },
                    onUpdateControlOffsets = onOffsets,
                    onUpdateControlScale = { _, _ -> },
                    onUpdateControlWidthScale = { _, _ -> },
                    onUpdateControlOpacity = { _, _ -> },
                    onToggleLeftInputMode = {},
                    onSetControlVisible = { _, _ -> },
                    onSetStickSurfaceMode = { _, _ -> },
                    onResetLayout = {},
                    onCustomControlsChange = onCustomControlsChange
                )
            }
        }
    }

    @Test fun hardwareModeLegendStaysEnglishInEverySupportedLocale() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                for (language in listOf("en", "ar", "cs", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "ru", "tr", "uk", "zh")) {
                    val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
                    val localized = context.createConfigurationContext(config)
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalContext provides localized,
                        androidx.compose.ui.platform.LocalConfiguration provides config
                    ) {
                        androidx.compose.material3.Text(SegaTouchLayouts.label(EmulationPlatform.GENESIS, "select")!!,
                            modifier = androidx.compose.ui.Modifier.testTag("mode_$language"))
                    }
                }
            }
        }
        for (language in listOf("en", "ar", "cs", "de", "es", "fa", "fr", "hi", "id", "it", "ja", "ko", "pl", "pt", "ru", "tr", "uk", "zh")) {
            composeRule.onNodeWithTag("mode_$language").assertTextEquals("Mode")
        }
    }

    @Test fun firstDragOnSegaArrowMovesAllFourDirectionsAndToggleRetainsIndividualEditing() {
        val saved = AtomicReference<Map<String, Pair<Float, Float>>>()
        val ids = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right")
        showEditor(CustomTouchControlLibrary.Empty, {}, EmulationPlatform.GENESIS, saved::set)
        val before = ids.associateWith { id ->
            composeRule.onNodeWithTag("controls_editor_control_$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        composeRule.onNodeWithTag("controls_editor_control_dpad_up", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(30f, -30f), 400)
        }
        composeRule.runOnIdle {
            val offsets = saved.get()
            assertEquals(ids, offsets.keys)
            val delta = offsets.getValue("dpad_up")
            offsets.forEach { (id, offset) ->
                assertEquals("Unequal group X offset for $id: $offsets", delta.first, offset.first, .01f)
                assertEquals("Unequal group Y offset for $id: $offsets", delta.second, offset.second, .01f)
            }
            assertTrue("Group did not move right: $offsets", delta.first > 0f)
        }
        // Layout places controls on integer pixels. Their rendered movement must match
        // the common continuous offset to within one pixel of placement rounding.
        val offsets = saved.get()
        ids.forEach { id ->
            val after = composeRule.onNodeWithTag("controls_editor_control_$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val old = before.getValue(id)
            val delta = offsets.getValue(id)
            assertEquals("Rendered X movement for $id: $offsets", delta.first, after.left - old.left, 1f)
            assertEquals("Rendered Y movement for $id: $offsets", delta.second, after.top - old.top, 1f)
            assertEquals(old.width, after.width, .01f)
            assertEquals(old.height, after.height, .01f)
        }
        composeRule.onNodeWithTag("controls_editor_group_mode").performClick()
        composeRule.onNodeWithTag("controls_editor_control_dpad_up", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(30f, -30f), 400)
        }
        composeRule.runOnIdle {
            assertEquals(setOf("dpad_up"), saved.get().keys)
        }
    }

    @Test
    fun addDialogCreatesComboButtonWithTwoActions() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        showEditor(CustomTouchControlLibrary.Empty, saved::set)

        composeRule.onNodeWithTag("controls_editor_add_button").performClick()
        composeRule.onNodeWithTag("controls_editor_combo_primary_square").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_secondary_l1").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()

        composeRule.runOnIdle {
            val control = saved.get()?.controls?.single()
            assertEquals("square", control?.actionId)
            assertEquals("l1", control?.secondaryActionId)
        }
    }

    @Test
    fun duplicateCreatesOffsetCopyThatKeepsComboAction() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        val initial = CustomTouchControlLibrary(
            controls = listOf(
                CustomTouchControl(
                    id = "jump",
                    name = "Jump",
                    actionId = "cross",
                    secondaryActionId = "l1",
                    positionX = 0.3f,
                    positionY = 0.4f
                )
            )
        )
        showEditor(initial, saved::set)

        composeRule.onNodeWithTag("controls_editor_custom_jump", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("controls_editor_duplicate").performClick()

        composeRule.runOnIdle {
            val controls = saved.get()?.controls.orEmpty()
            assertEquals(2, controls.size)
            val copy = controls.first { it.id != "jump" }
            assertEquals("cross", copy.actionId)
            assertEquals("l1", copy.secondaryActionId)
            assertTrue(copy.positionX > 0.3f)
            assertTrue(copy.positionY > 0.4f)
        }
    }

    @Test
    fun comboDialogUpdatesSecondaryActionOfSelectedControl() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        val initial = CustomTouchControlLibrary(
            controls = listOf(
                CustomTouchControl(id = "jump", name = "Jump", actionId = "cross")
            )
        )
        showEditor(initial, saved::set)

        composeRule.onNodeWithTag("controls_editor_custom_jump", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("controls_editor_combo").performClick()
        composeRule.onNodeWithTag("controls_editor_combo_secondary_circle").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()

        composeRule.runOnIdle {
            val control = saved.get()?.controls?.single()
            assertEquals("cross", control?.actionId)
            assertEquals("circle", control?.secondaryActionId)
        }
    }
    @Test fun genesisEditorCreatesSixButtonCombosAndHidesUnsupportedHardware() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        showEditor(CustomTouchControlLibrary.Empty, saved::set, EmulationPlatform.GENESIS)
        composeRule.onNodeWithTag("controls_editor_add_button").performClick()
        composeRule.onNodeWithTag("controls_editor_combo_primary_l2").assertDoesNotExist()
        composeRule.onNodeWithTag("controls_editor_combo_primary_x").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_secondary_l1").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()
        composeRule.runOnIdle {
            val button = saved.get()?.controls?.single()
            assertEquals("x", button?.actionId)
            assertEquals("l1", button?.secondaryActionId)
            assertEquals(99, SegaTouchLayouts.keyCode(EmulationPlatform.GENESIS, button!!.actionId))
        }
    }

    @Test fun sg1000EditorOffersTwoButtonsWithoutStartOrSixButtonActions() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        showEditor(CustomTouchControlLibrary.Empty, saved::set, EmulationPlatform.SG_1000)
        composeRule.onNodeWithTag("controls_editor_add_button").performClick()
        for (unsupported in listOf("start", "select", "x", "y", "l1", "r1", "l2")) {
            composeRule.onNodeWithTag("controls_editor_combo_primary_$unsupported").assertDoesNotExist()
        }
        composeRule.onNodeWithTag("controls_editor_combo_primary_a").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("a", SegaTouchLayouts.canonicalId(saved.get()!!.controls.single().actionId)) }
    }

    @Test fun model3EditorCreatesServiceTestCombo() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        showEditor(CustomTouchControlLibrary.Empty, saved::set, EmulationPlatform.MODEL_3)
        composeRule.onNodeWithTag("controls_editor_add_button").performClick()
        composeRule.onNodeWithTag("controls_editor_combo_primary_l3").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_secondary_r3").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()
        composeRule.runOnIdle {
            val button = saved.get()!!.controls.single()
            assertEquals("l3", button.actionId)
            assertEquals("r3", button.secondaryActionId)
            assertEquals(106, SegaTouchLayouts.keyCode(EmulationPlatform.MODEL_3, button.actionId))
            assertEquals(107, SegaTouchLayouts.keyCode(EmulationPlatform.MODEL_3, button.secondaryActionId!!))
        }
    }

    @Test fun model3EditorCreatesPedalCombo() {
        val saved = AtomicReference<CustomTouchControlLibrary?>()
        showEditor(CustomTouchControlLibrary.Empty, saved::set, EmulationPlatform.MODEL_3)
        composeRule.onNodeWithTag("controls_editor_add_button").performClick()
        composeRule.onNodeWithTag("controls_editor_combo_primary_l2").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_secondary_r2").performScrollTo().performClick()
        composeRule.onNodeWithTag("controls_editor_combo_confirm").performScrollTo().performClick()
        composeRule.runOnIdle {
            val button = saved.get()!!.controls.single()
            assertEquals("l2", button.actionId)
            assertEquals("r2", button.secondaryActionId)
        }
    }

}
