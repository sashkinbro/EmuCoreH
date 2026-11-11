package com.sbro.emucoreh.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.sbro.emucoreh.data.RetroArchShaderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.maxInternalResolutionMultiplier
import com.sbro.emucoreh.core.supportsInternalResolution
import com.sbro.emucoreh.data.PerGameSettings
import com.sbro.emucoreh.ui.theme.EmuCoreHTheme
import org.junit.Rule
import org.junit.Test

class SharedGraphicsSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun model3HidesRendererAndCustomDriverChoicesForLegacyVulkanProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = mutableStateOf(EmulationPlatform.MODEL_3)
        val legacy = PerGameSettings("qa-model3-renderer", "QA Model 3", renderer = 14)
        compose.setContent {
            EmuCoreHTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GameSettingsTabContent(platform.value, legacy, legacy, GameSettingsManagerTab.Core,
                        4, emptyList(), { true }, {}, true, {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_renderer)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_manage)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_renderer_vulkan)).assertDoesNotExist()
        compose.runOnIdle { platform.value = EmulationPlatform.DREAMCAST }
        compose.onNodeWithText(context.getString(R.string.settings_renderer)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_gpu_driver_manage)).assertExists()
    }

    @Test fun everyCoreExposesShadersAndOnlySupportedCoresExposeInternalResolution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = mutableStateOf(EmulationPlatform.DREAMCAST)
        val draft = PerGameSettings("qa-graphics", "QA graphics")
        compose.setContent {
            EmuCoreHTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GameSettingsTabContent(platform.value, draft, draft, GameSettingsManagerTab.Core,
                        platform.value.maxInternalResolutionMultiplier, emptyList(), { true }, {}, false, {})
                }
            }
        }
        for (value in EmulationPlatform.entries) {
            compose.runOnIdle { platform.value = value }
            compose.onNodeWithText(context.getString(R.string.settings_shader_preset))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.settings_retroarch_shaders)).assertExists()
            val resolution = compose.onNodeWithText(context.getString(R.string.settings_upscale))
            if (value.supportsInternalResolution) resolution.assertExists() else resolution.assertDoesNotExist()
        }
    }
    @Test fun perGameShaderChoiceSupportsOwnPresetDisabledAndGlobalInheritance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initial = PerGameSettings("qa-saturn-shader", "QA Saturn")
        val draft = mutableStateOf(initial)
        val preset = RetroArchShaderPreset("QA/Nearest", "/qa/nearest.slangp")
        compose.setContent {
            EmuCoreHTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GameSettingsTabContent(EmulationPlatform.SATURN, draft.value, initial,
                        GameSettingsManagerTab.Core, 1, listOf(preset), { true }, {}, false,
                        { draft.value = it })
                }
            }
        }
        val global = context.getString(R.string.controls_editor_global_scope)
        compose.onNodeWithText(global, substring = true).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("QA").performClick()
        compose.onNodeWithText("Nearest").performClick()
        compose.runOnIdle {
            assertEquals(true, draft.value.shaderChainOverrideEnabled)
            assertEquals(preset.absolutePath, draft.value.shaderChainPreset)
        }
        android.os.SystemClock.sleep(600) // Match the settings card's physical-tap debounce.
        compose.onNodeWithText("Nearest").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(context.getString(R.string.settings_general_tab)).performClick()
        val none = context.getString(R.string.settings_shader_preset_none)
        compose.onNodeWithText(none).performClick()
        compose.runOnIdle {
            assertEquals(false, draft.value.shaderChainOverrideEnabled)
            assertEquals("", draft.value.shaderChainPreset)
        }
        android.os.SystemClock.sleep(600)
        compose.onNodeWithText(none).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(context.getString(R.string.settings_general_tab)).performClick()
        compose.onNodeWithText(global, substring = true).performClick()
        compose.runOnIdle { assertNull(draft.value.shaderChainOverrideEnabled) }
    }

}
