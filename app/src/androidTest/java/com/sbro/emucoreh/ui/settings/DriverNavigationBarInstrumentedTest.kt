package com.sbro.emucoreh.ui.settings

import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucoreh.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriverNavigationBarInstrumentedTest {
    @Test fun navigationBarRemainsTransparentWhenActivityResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertFalse(activity.window.isNavigationBarContrastEnforced)
                }
            }
        }
    }
}
