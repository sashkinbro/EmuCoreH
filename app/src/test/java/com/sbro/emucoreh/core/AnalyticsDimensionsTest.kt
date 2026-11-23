package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsDimensionsTest {
    @Test
    fun sanitizersMapUnknownValuesToUnknown() {
        assertEquals("game", AnalyticsDimensions.sanitizeLaunchType("game"))
        assertEquals("bios", AnalyticsDimensions.sanitizeLaunchType("bios"))
        assertEquals("unknown", AnalyticsDimensions.sanitizeLaunchType("/sdcard/Game.gdi"))
        assertEquals("native_start", AnalyticsDimensions.sanitizeFailureReason("native_start"))
        assertEquals("unknown", AnalyticsDimensions.sanitizeFailureReason("whatever"))
        assertEquals("load", AnalyticsDimensions.sanitizeSaveStateAction("load"))
        assertEquals("unknown", AnalyticsDimensions.sanitizeSaveStateAction("delete"))
    }

    @Test
    fun launchTypeAndRenderer() {
        assertEquals("bios", AnalyticsDimensions.launchType(true))
        assertEquals("game", AnalyticsDimensions.launchType(false))
        assertEquals("vulkan", AnalyticsDimensions.renderer(RendererDefaults.VULKAN))
        assertEquals("opengl", AnalyticsDimensions.renderer(RendererDefaults.OPENGL))
        assertEquals("unknown", AnalyticsDimensions.renderer(99))
    }

    @Test
    fun upscaleBuckets() {
        assertEquals("native", AnalyticsDimensions.upscale(1f))
        assertEquals("native", AnalyticsDimensions.upscale(Float.NaN))
        assertEquals("2x", AnalyticsDimensions.upscale(2f))
        assertEquals("3x", AnalyticsDimensions.upscale(3f))
        assertEquals("4x_plus", AnalyticsDimensions.upscale(4f))
    }

    @Test
    fun durationBuckets() {
        assertEquals("under_1m", AnalyticsDimensions.durationBucket(59_999L))
        assertEquals("1_5m", AnalyticsDimensions.durationBucket(60_000L))
        assertEquals("5_15m", AnalyticsDimensions.durationBucket(5 * 60_000L))
        assertEquals("15_30m", AnalyticsDimensions.durationBucket(15 * 60_000L))
        assertEquals("30_60m", AnalyticsDimensions.durationBucket(30 * 60_000L))
        assertEquals("over_60m", AnalyticsDimensions.durationBucket(60 * 60_000L))
    }
}
