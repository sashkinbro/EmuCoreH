package com.sbro.emucoreh.core

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sbro.emucoreh.MainActivity
import com.sbro.emucoreh.data.PerGameSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

/** Exercises the real launcher/Compose/SurfaceView path, including a second unflagged VIEW intent. */
class ExternalGameLaunchPresentationInstrumentedTest {
    @Test fun externalSwitchReusesActivityAndPresentsTheNewGamesPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        // The existing production autotest namespace is excluded from playtime sync.
        val directory = File(context.cacheDir, "autotest-elf/external-presentation-qa").apply { mkdirs() }
        val red = File(directory, "red.sms").apply { writeBytes(solidSmsRom(0x03)) }
        val green = File(directory, "green.sms").apply { writeBytes(solidSmsRom(0x0c)) }
        val resumed = AtomicReference<MainActivity?>()
        val created = AtomicInteger()
        val activities = java.util.concurrent.CopyOnWriteArrayList<MainActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) { created.incrementAndGet(); activities += activity }
            }
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumed.set(activity)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        fun launchIntent(file: File) = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("ROM", file.absolutePath)
        }
        try {
            instrumentation.startActivitySync(launchIntent(red).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertPresentedColor(resumed, red.absolutePath, red = true)
            val firstActivity = resumed.get()!!
            val initialCreated = created.get()

            // No CLEAR_TOP/SINGLE_TOP flags: this is the external frontend path
            // that previously created two Activities sharing a core/surface epoch.
            instrumentation.runOnMainSync { firstActivity.startActivity(launchIntent(green)) }
            assertPresentedColor(resumed, green.absolutePath, red = false)
            assertSame("External game launch must reuse MainActivity", firstActivity, resumed.get())
            assertEquals("Do not create a hidden competing MainActivity", initialCreated, created.get())

            // Repeat in the other direction; a retired callback must not regain
            // ownership after the new session publishes its generation.
            instrumentation.runOnMainSync { firstActivity.startActivity(launchIntent(red)) }
            assertPresentedColor(resumed, red.absolutePath, red = true)
            assertSame(firstActivity, resumed.get())
            assertEquals(initialCreated, created.get())

            instrumentation.runOnMainSync {
                firstActivity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            awaitCondition("Activity reaches background") {
                var background = false
                instrumentation.runOnMainSync { background = !firstActivity.hasWindowFocus() }
                background
            }
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            assertPresentedColor(resumed, red.absolutePath, red = true)
            assertFrameProgress("Home/resume")

            val originalOrientation = firstActivity.requestedOrientation
            try {
                for ((orientation, configuration) in listOf(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE)) {
                    instrumentation.runOnMainSync { firstActivity.requestedOrientation = orientation }
                    awaitCondition("Requested rotation is applied") {
                        firstActivity.resources.configuration.orientation == configuration
                    }
                    assertPresentedColor(resumed, red.absolutePath, red = true)
                    assertFrameProgress("rotation $orientation")
                }
            } finally {
                instrumentation.runOnMainSync { firstActivity.requestedOrientation = originalOrientation }
            }
        } finally {
            runBlocking { EmulatorBridge.shutdown() }
            instrumentation.runOnMainSync { activities.forEach { it.finish() } }
            awaitCondition("Fixture activities are destroyed") { activities.isEmpty() }
            application.unregisterActivityLifecycleCallbacks(callbacks)
            CoreRuntime.setHostForeground(true)
            cleanupFixtureData(context, listOf(directory, File(context.cacheDir, "external-presentation-qa")))
        }
    }

    private fun assertFrameProgress(reason: String) {
        val before = CoreRuntime.bridge.audioOutputStats(0)?.get(9) ?: 0L
        awaitCondition("Emulated frames advance after $reason") {
            (CoreRuntime.bridge.audioOutputStats(0)?.get(9) ?: 0L) >= before + 5
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail(description)
    }

    private fun cleanupFixtureData(context: Context, directories: List<File>) {
        val paths = directories.flatMap { folder -> listOf(File(folder, "red.sms").path, File(folder, "green.sms").path) }.toSet()
        paths.forEach { path ->
            PerGameSettingsRepository(context).delete(path)
            for (slot in 0..10) NativeApp.getSaveStatePathForFile(path, slot)?.let { statePath ->
                File(statePath).delete()
                File("$statePath.png").delete()
            }
        }
        fun withoutFixtures(raw: String?): String {
            val entries = JSONArray(raw ?: "[]")
            return JSONArray().apply {
                for (index in 0 until entries.length()) entries.optJSONObject(index)?.let {
                    if (it.optString("path") !in paths) put(it)
                }
            }.toString()
        }
        // Reuse the app's existing DataStore instance; never open a second store
        // or restore an entire preference snapshot over unrelated user settings.
        val getter = Class.forName("com.sbro.emucoreh.data.AppPreferencesKt")
            .getDeclaredMethod("getDataStore", Context::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = getter.invoke(null, context) as DataStore<Preferences>
        runBlocking {
            store.edit { preferences ->
                val key = stringPreferencesKey("recent_games")
                preferences[key] = withoutFixtures(preferences[key])
            }
        }
        val pending = context.getSharedPreferences("play_time_sync_cache", Context.MODE_PRIVATE)
        pending.edit().putString("pending_entries", withoutFixtures(pending.getString("pending_entries", "[]"))).commit()
        directories.forEach { folder ->
            check(folder.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            folder.deleteRecursively()
        }
    }

    private fun assertPresentedColor(activity: AtomicReference<MainActivity?>, path: String, red: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var lastColor: Int? = null
        while (System.nanoTime() < deadline) {
            if (CoreRuntime.isRunning() && CoreRuntime.launchIdentityPath == path) {
                var surface: SurfaceView? = null
                instrumentation.runOnMainSync {
                    surface = activity.get()?.window?.decorView?.let(::visibleSurface)
                }
                surface?.takeIf { it.holder.surface.isValid && it.width > 0 && it.height > 0 }?.let { view ->
                    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    try {
                        val copied = CountDownLatch(1)
                        val result = AtomicInteger(-1)
                        PixelCopy.request(view.holder.surface, bitmap, { code ->
                            result.set(code); copied.countDown()
                        }, Handler(Looper.getMainLooper()))
                        if (copied.await(2, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS) {
                            lastColor = bitmap.getPixel(8, 8)
                            val color = lastColor!!
                            if (if (red) Color.red(color) > 180 && Color.green(color) < 80
                                else Color.green(color) > 180 && Color.red(color) < 80) return
                        }
                    } finally { bitmap.recycle() }
                }
            }
            Thread.sleep(50)
        }
        fail("Visible SurfaceView never presented ${if (red) "red" else "green"} game; " +
            "color=$lastColor identity=${CoreRuntime.launchIdentityPath} failure=${CoreRuntime.failure.value}")
    }

    private fun visibleSurface(view: View): SurfaceView? {
        if (!view.isShown) return null
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            visibleSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun solidSmsRom(color: Int): ByteArray = ByteArray(32768).apply {
        // Mode 4, display enabled, then fill every CRAM entry with one color.
        val code = intArrayOf(0xf3, 0x3e, 0x04, 0xd3, 0xbf, 0x3e, 0x80, 0xd3, 0xbf,
            0x3e, 0x40, 0xd3, 0xbf, 0x3e, 0x81, 0xd3, 0xbf,
            0x3e, 0x00, 0xd3, 0xbf, 0x3e, 0xc0, 0xd3, 0xbf,
            0x3e, color, 0x06, 0x20, 0xd3, 0xbe, 0x10, 0xfc, 0x18, 0xfe)
        code.forEachIndexed { index, value -> this[index] = value.toByte() }
    }
}
