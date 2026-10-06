// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

/** Audio defaults used by the Android frontend and sanitized before reaching the core. */
object AudioDefaults {
    const val VOLUME_DEFAULT = 100
    const val VOLUME_MIN = 0
    const val VOLUME_MAX = 100

    // Capacity ceiling for the shared-mode AAudio buffer. Emulation frames are
    // not isochronous (shader compilation, GC, DVFS), so the stable shared-mode
    // baseline with a comfortable margin beats a tight low-latency stream that
    // crackles under load. The native layer still sizes the stream from the
    // device burst.
    const val OUTPUT_LATENCY_MS_DEFAULT = 120
    const val OUTPUT_LATENCY_MS_MIN = 1
    const val OUTPUT_LATENCY_MS_MAX = 500
    // Do not request the platform's low latency path by default; the smaller
    // device buffer it produces leaves no room for frame-time spikes.
    const val MINIMAL_OUTPUT_LATENCY_DEFAULT = false

    fun coerceVolume(value: Int): Int = value.coerceIn(VOLUME_MIN, VOLUME_MAX)

    fun coerceOutputLatencyMs(value: Int): Int =
        value.coerceIn(OUTPUT_LATENCY_MS_MIN, OUTPUT_LATENCY_MS_MAX)
}
