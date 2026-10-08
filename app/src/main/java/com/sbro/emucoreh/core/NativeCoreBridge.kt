// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.view.Surface

/**
 * JNI surface over the bundled Flycast libretro frontend.
 *
 * The native side drives the core through the libretro API (video, audio,
 * input, environment); the frame loop and pad state stay on the Kotlin side.
 */
class NativeCoreBridge {
    companion object {
        init {
            System.loadLibrary("emucoreh_jni")
        }
    }

    external fun apiVersion(): Int

    // ---------------------------------------------------------------------
    // Lifecycle / configuration.
    // ---------------------------------------------------------------------
    external fun nativeInit(systemDir: String, saveDir: String, coreAssetsDir: String)
    external fun createSession(): Long
    external fun destroySession(handle: Long)
    external fun nativeSetOption(key: String, value: String)
    /** Frontend post-processing effect derived from the selected shader preset. */
    external fun nativeSetShaderEffect(effect: Int)

    /** RetroArch (.slangp) shader chain executed through librashader. */
    external fun nativeSetShaderPreset(path: String, enabled: Boolean)

    // ---------------------------------------------------------------------
    // Content.
    // ---------------------------------------------------------------------
    /** Boots the core with no content into the Dreamcast BIOS. */
    external fun loadBiosOnly(handle: Long): Int
    external fun loadDisc(handle: Long, path: String): Int

    /** Runs one guest frame; audio is pulled by the output stream callback. */
    external fun runFrame(handle: Long)
    /** 0 normal, 1 fast forward. */
    external fun setTimeControl(mode: Int)
    /**
     * Creates/rebinds the hardware renderer context on the calling thread.
     * Must be invoked from the frame worker so GL state stays thread-affine.
     */
    external fun ensureHardwareContext(): Boolean
    external fun setSurface(handle: Long, surface: Surface?, renderer: Int): Int
    /** 0 stretch, 1 core aspect, 2 4:3, 3 16:9, 4 10:7. */
    external fun setDisplayAspectRatio(mode: Int)

    // ---------------------------------------------------------------------
    // Input.
    // ---------------------------------------------------------------------
    external fun setPadButtons(handle: Long, port: Int, activeLowButtons: Int)
    external fun setPadAnalog(handle: Long, port: Int, lx: Int, ly: Int, rx: Int, ry: Int)
    external fun setPadAnalogMode(handle: Long, port: Int, enabled: Boolean)
    /** Bit 16 analog mode, bits 8..15 large motor, bits 0..7 small motor. */
    external fun getPadState(handle: Long, port: Int): Int

    // ---------------------------------------------------------------------
    // Save states.
    // ---------------------------------------------------------------------
    external fun saveState(handle: Long, path: String): Int
    external fun loadState(handle: Long, path: String): Int

    // ---------------------------------------------------------------------
    // Diagnostics.
    // ---------------------------------------------------------------------
    external fun getSystemInfo(): String
    external fun getDiagnostics(): String
    external fun getDisplayRect(handle: Long): IntArray?
    /**
     * Presenter destination rect in window pixels as
     * `{left, top, right, bottom}` (null until a window is attached).
     */
    external fun getPresentRect(): FloatArray?
    /** Emulated vertical refresh in Hz, used for audio-synced frame pacing. */
    external fun getFrameRate(handle: Long): Double

    /**
     * Product code the core read from the loaded disc, or null while no disc
     * bootstrap has been parsed yet.
     */
    external fun nativeGameSerial(): String?

    /** Human-readable core name/version used by statistics and the About screen. */
    fun coreName(): String? = getSystemInfo().substringBefore(' ').takeIf { it.isNotBlank() } ?: "Flycast"
    fun coreVersion(): String? = getSystemInfo().substringAfter(' ', "").trim()
        .takeUnless {
            it.isBlank() || it == "?" || it == "-" || it.equals("unknown", ignoreCase = true) ||
                it.startsWith("v0.0.0-0-g000000000")
        }

    // Compatibility surface used by the app layer. The libretro frontend owns
    // AAudio buffering; cheats go through retro_cheat_set in the native bridge.
    /** Loads active GameShark-style cheat codes staged for the core. */
    external fun loadCheats(path: String)
    external fun clearCheats()

    /**
     * Overrides the data root Flycast writes its save data to (null or blank
     * restores the core default). Replacement textures are resolved by the core
     * from its own system directory, not from this root.
     */
    external fun setDataRootOverride(path: String?)

    // ---------------------------------------------------------------------
    // RetroAchievements (rcheevos). The client lives in native code; Kotlin
    // polls JSON state/events and persists the account token.
    // ---------------------------------------------------------------------
    external fun achievementsSetEnabled(enabled: Boolean)
    external fun achievementsSetHardcore(enabled: Boolean)
    external fun achievementsSetUnofficial(enabled: Boolean)
    external fun achievementsSetEncore(enabled: Boolean)
    external fun achievementsLoginWithPassword(user: String, password: String): String?
    external fun achievementsLoginWithToken(user: String, token: String): String?
    external fun achievementsLogout()
    external fun achievementsLoadGame(path: String)
    external fun achievementsUnloadGame()
    external fun achievementsPump()
    external fun achievementsStateJson(): String
    external fun achievementsAchievementsJson(): String
    external fun achievementsPollEventsJson(): String

    /** True when the running session has a disc image mounted. */
    external fun hasDiscMedia(handle: Long): Boolean

    // ---------------------------------------------------------------------
    // AAudio output tuning (applied when the next stream is opened).
    // ---------------------------------------------------------------------
    external fun setAudioOutputLatencyMs(milliseconds: Int)
    external fun setAudioLowLatency(enabled: Boolean)
    /** Frontend presentation frame skip (0..4). */
    external fun setFrameSkip(frames: Int)
    /** Display crop in source pixels, applied before aspect-ratio scaling. */
    external fun setDisplayCrop(left: Int, top: Int, right: Int, bottom: Int)

    // ---------------------------------------------------------------------
    // AAudio output (owned by NativeAudioOutput).
    // ---------------------------------------------------------------------
    external fun createAudioOutput(): Long
    external fun destroyAudioOutput(handle: Long)
    external fun startAudioOutput(handle: Long): Int
    external fun pauseAudioOutput(handle: Long): Int
    external fun flushAudioOutput(handle: Long): Int
    /** Empties the shared ring so a new session cannot replay old frames. */
    external fun resetAudioQueue()
    /** Linear gain in 0..1 applied on the output callback thread. */
    external fun setAudioGain(gain: Float)
    external fun setAudioPlaybackRate(rate: Double)
    /** Frames queued for the output; negative when the stream needs recovery. */
    external fun audioOutputBufferedFrames(handle: Long): Int
    /** Queue level the frame loop keeps the output at for audio-synced pacing. */
    external fun audioOutputPacingHighWaterFrames(handle: Long): Int
    /** state, error, device Hz, burst, queued, source frames, callback frames,
     * silence frames, source Hz, new video frames. Source/video totals are monotonic. */
    external fun audioOutputStats(handle: Long): LongArray?

    // ---------------------------------------------------------------------
    // Disc metadata read straight from the image. The library layer falls
    // back to filename-derived titles when this returns null.
    // ---------------------------------------------------------------------
    external fun getDiscMetadata(path: String): String?

    external fun getDiscMetadataFd(fd: Int, offset: Long, size: Long): String?
}
