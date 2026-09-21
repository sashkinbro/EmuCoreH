package com.sbro.emucoreh.core

import android.util.Log
import java.io.File

internal object GpuLoadReader {
    private const val TAG = "GpuLoadReader"
    private const val MIN_READ_INTERVAL_NANOS = 500_000_000L

    private class Source(val path: String, val parse: (String) -> Float?)

    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")
    private val whitespaceRegex = Regex("""\s+""")

    private val sources = listOf(
        Source("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage") { raw -> firstNumber(raw)?.let(::percent) },
        Source("/sys/class/kgsl/kgsl-3d0/gpubusy") { raw ->
            val parts = raw.trim().split(whitespaceRegex)
            val busy = parts.getOrNull(0)?.toDoubleOrNull()
            val total = parts.getOrNull(1)?.toDoubleOrNull()
            if (busy == null || total == null || total <= 0.0) null else percent(busy * 100.0 / total)
        },
        Source("/sys/kernel/ged/hal/gpu_utilization") { raw -> firstNumber(raw)?.let(::percent) },
        Source("/sys/module/ged/parameters/gpu_loading") { raw -> firstNumber(raw)?.let(::percent) },
        Source("/sys/kernel/gpu/gpu_busy") { raw -> firstNumber(raw)?.let(::percent) }
    )

    private var activeSource = -1
    private var lastValue: Float? = null
    private var lastReadNanos = 0L
    private var warnedUnavailable = false

    @Synchronized
    fun loadPercent(): Float? {
        val now = System.nanoTime()
        if (now - lastReadNanos < MIN_READ_INTERVAL_NANOS) return lastValue
        lastReadNanos = now

        val cached = activeSource
        if (cached >= 0) {
            read(sources[cached])?.let {
                lastValue = it
                return it
            }
            activeSource = -1
        }

        for (index in sources.indices) {
            val value = read(sources[index]) ?: continue
            activeSource = index
            lastValue = value
            warnedUnavailable = false
            return value
        }

        if (!warnedUnavailable) {
            Log.i(TAG, "GPU load is not exposed by this device")
            warnedUnavailable = true
        }
        lastValue = null
        return null
    }

    private fun read(source: Source): Float? = runCatching {
        val file = File(source.path)
        if (!file.canRead()) return@runCatching null
        source.parse(file.readText())
    }.getOrNull()

    private fun firstNumber(raw: String): Double? = numberRegex.find(raw)?.value?.toDoubleOrNull()

    private fun percent(value: Double): Float? =
        value.takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 100f)
}
