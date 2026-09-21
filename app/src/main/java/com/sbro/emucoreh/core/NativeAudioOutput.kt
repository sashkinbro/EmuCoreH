// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

/**
 * Owns the native AAudio stream. Sample data never crosses JNI: the output
 * callback pulls from the native ring and the frame loop only keeps the
 * queued level under [pacingHighWaterFrames].
 */
internal class NativeAudioOutput {
    private val bridge = NativeCoreBridge()
    private var handle = createHandle()
    private var playing = false

    private fun createHandle(): Long = bridge.createAudioOutput().also {
        check(it != 0L) { "Could not open native AAudio output" }
    }

    /** AAudio reports disconnect asynchronously; recovery belongs to this
     * owner thread and rebuilds the stream around the existing ring.
     */
    private fun recreate(start: Boolean) {
        val old = handle
        handle = 0L
        if (old != 0L) bridge.destroyAudioOutput(old)
        val replacement = createHandle()
        handle = replacement
        if (start && bridge.startAudioOutput(replacement) != 0) {
            handle = 0L
            bridge.destroyAudioOutput(replacement)
            error("AAudio recovery start failed")
        }
        playing = start
    }

    @Synchronized fun play() {
        check(handle != 0L) { "AAudio output is closed" }
        if (bridge.startAudioOutput(handle) != 0) recreate(start = true)
        playing = true
    }

    @Synchronized fun pause() {
        check(handle != 0L) { "AAudio output is closed" }
        if (bridge.pauseAudioOutput(handle) != 0) recreate(start = false)
        playing = false
    }

    @Synchronized fun flush() {
        check(handle != 0L) { "AAudio output is closed" }
        if (bridge.flushAudioOutput(handle) != 0) recreate(start = false)
    }

    @Synchronized fun release() {
        if (handle != 0L) {
            bridge.destroyAudioOutput(handle)
            handle = 0L
        }
        playing = false
    }

    @Synchronized fun stats(): LongArray? =
        if (handle == 0L) null else bridge.audioOutputStats(handle)

    @Synchronized fun bufferedFrames(): Int {
        if (handle == 0L) return -1
        val frames = bridge.audioOutputBufferedFrames(handle)
        if (frames < 0) {
            recreate(playing)
            return 0
        }
        return frames
    }

    @Synchronized fun pacingHighWaterFrames(): Int =
        if (handle == 0L) 0 else bridge.audioOutputPacingHighWaterFrames(handle)
}
