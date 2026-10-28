package com.sbro.emucoreh.core

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidTouchHapticsInstrumentedTest {
    @Test fun shortAndLongFeedbackAreAvailableOnSupportedAndroidVersions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidTouchHaptics.play(context, strengthPercent = 60, durationMs = 80)
        AndroidTouchHaptics.play(context, strengthPercent = 100, durationMs = 260)
        for (preset in 0..3) {
            for (phase in AndroidTouchHaptics.ButtonPhase.entries) {
                AndroidTouchHaptics.playButton(context, strengthPercent = 60, preset = preset, phase = phase)
            }
        }
    }
}
