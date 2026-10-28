// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import android.content.Context
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import com.sbro.emucoreh.data.resolveSaveStateSlotFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NativeApp {

    const val PAD_GUN_TRIGGER = 135
    const val PAD_GUN_PEDAL = 136
    const val PAD_GUN_RELOAD = 137
    const val PAD_GUN_RECALIBRATE = 138
    const val PAD_ARCADE_COIN = 139
    const val PAD_ARCADE_TEST = 140
    const val PAD_ARCADE_SERVICE = 141
    private val _lightGunRecenterRequests = MutableStateFlow(listOf(0, 0))
    val lightGunRecenterRequests = _lightGunRecenterRequests.asStateFlow()

    @JvmStatic fun getArcadeInputMode(): Int = CoreRuntime.getArcadeInputMode()
    @JvmStatic fun setArcadeControllerDevice(port: Int, device: Int) = CoreRuntime.setArcadeControllerDevice(port, device)
    @JvmStatic fun setLightGunAim(port: Int, x: Float, y: Float, offscreen: Boolean = false) =
        CoreRuntime.setLightGunAim(port, x, y, offscreen)
    @JvmStatic fun setLightGunCursorEnabled(enabled: Boolean) = CoreRuntime.setLightGunCursorEnabled(enabled)
    @JvmStatic fun resetLightGunInput(port: Int) = CoreRuntime.resetLightGunInput(port)

    private const val TAG = "NativeApp"
    @JvmStatic
    val hasNativeTools: Boolean

    @JvmStatic
    val loadedCoreLibraryName: String

    @JvmStatic
    val hasNativeCore: Boolean

    private var contextRef: WeakReference<Context>? = null
    private var dataRootOverride: String? = null


    init {
        loadedCoreLibraryName = "emucoreh_jni"
        hasNativeCore = runCatching { CoreRuntime.bridge.apiVersion() }
            .onFailure { if (com.sbro.emucoreh.BuildConfig.DEBUG) CrashLogger.logError(TAG, "Unable to load $loadedCoreLibraryName", it) }
            .isSuccess
        hasNativeTools = false
    }

    @Volatile private var currentGamePath: String = ""
    // The path the user actually launched, used to name save-state files so
    // the writer and every listing agree even when the core needs a prepared
    // (materialized) launch path.
    @Volatile private var saveStateIdentityPath: String? = null
    @Volatile private var activeSavePaths: Model3ActiveSavePaths? = null
    // Keyboard and mouse publish into separate source slots. All sources are merged
    // before the active-low pad state is sent to the core, so releasing one physical
    // source can never clear a button another source is still holding.
    private val padButtons = intArrayOf(ALL_BUTTONS_RELEASED, ALL_BUTTONS_RELEASED)
    private val padKeyboardButtons = intArrayOf(ALL_BUTTONS_RELEASED, ALL_BUTTONS_RELEASED)
    private val padMouseButtons = intArrayOf(ALL_BUTTONS_RELEASED, ALL_BUTTONS_RELEASED)
    private val padAnalogMode = booleanArrayOf(false, false)
    private val padAnalogHalfAxes = Array(2) { IntArray(8) }
    private val padKeyboardAnalogHalfAxes = Array(2) { IntArray(8) }
    private val padMouseAnalogHalfAxes = Array(2) { IntArray(8) }
    // Bitmask of input sources holding a momentary action (Start, Start tap, light gun
    // buttons, cabinet switches and the analog toggle) per pad and pad key.
    private val specialButtonSources = Array(2) { mutableMapOf<Int, Int>() }
    private val timeControlHandler = Handler(Looper.getMainLooper())
    private val timeControlButtons = Array(2) { Array(4) { HoldButton() } }
    private val timeControlTapPulse = Array(2) { BooleanArray(2) }
    private val _timeControlMode = MutableStateFlow(0)
    val timeControlMode: StateFlow<Int> = _timeControlMode.asStateFlow()

    @JvmStatic fun reloadDataRoot(path: String) { dataRootOverride = path.takeIf(String::isNotBlank) }
    @JvmStatic fun setSaveStateIdentityPath(path: String?) {
        activeSavePaths = null
        saveStateIdentityPath = path?.takeIf(String::isNotBlank)
    }
    @JvmStatic fun getGameTitle(path: String): String? {
        val fallback = if (path.startsWith("content://")) {
            contextRef?.get()?.let { DocumentPathResolver.getDisplayName(it, path) }
                ?.substringBeforeLast('.')
        } else {
            File(path).nameWithoutExtension
        }.orEmpty().takeIf(String::isNotBlank)
        contextRef?.get()?.let { context ->
            GameMetadataReader.read(context, path)?.let { metadata ->
                val title = metadata.title ?: fallback
                val serial = metadata.serial
                if (!serial.isNullOrBlank()) return "${title.orEmpty()}|$serial|$serial"
                if (!title.isNullOrBlank()) return title
            }
        }
        val rawMetadata = runCatching {
            if (path.startsWith("content://")) {
                val appContext = contextRef?.get() ?: return@runCatching null
                // For a prepared CUE launch the BIN descriptor is already open;
                // metadata must be read from the BIN, not the tiny CUE text.
                val preparedCue = DocumentPathResolver.getPreparedCueLaunch(path)
                if (preparedCue != null && preparedCue.descriptors.isNotEmpty()) {
                    CoreRuntime.bridge.getDiscMetadataFd(
                        preparedCue.descriptors[0],
                        0L,
                        preparedCue.sizes.getOrElse(0) { 0L }
                    )
                } else {
                    appContext.contentResolver.openFileDescriptor(path.toUri(), "r")?.use { descriptor ->
                        CoreRuntime.bridge.getDiscMetadataFd(
                            descriptor.fd,
                            0L,
                            descriptor.statSize.coerceAtLeast(0L)
                        )
                    }
                }
            } else {
                CoreRuntime.bridge.getDiscMetadata(path)
            }
        }.onFailure { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Unable to read disc metadata: $path", error)
        }.getOrNull()
        val fields = rawMetadata?.split('\n', limit = 3).orEmpty()
        val serial = fields.getOrNull(1).orEmpty().trim()
        val nativeTitle = fields.getOrNull(0).orEmpty().trim()
        if (serial.isBlank()) return nativeTitle.ifBlank { fallback }
        return "${nativeTitle.ifBlank { fallback.orEmpty() }}|$serial|$serial"
    }

    /**
     * Scrapes a disc image through the core's own disc drivers (including CHD),
     * like Flycast's offline scraper. Returns `searchName\nuniqueId\ncoverWritten`
     * or null when the image cannot be opened.
     */
    @JvmStatic fun scrapeDisc(path: String, pngPath: String?): String? = runCatching {
        CoreRuntime.bridge.scrapeDisc(path, pngPath)
    }.getOrNull()
    @JvmStatic fun isBiosPath(path: String): Boolean = File(path).let { it.isFile && it.length() == BIOS_SIZE_BYTES }
    /** Takes ownership of [fd] and always closes it before returning. */
    @JvmStatic fun isBiosFd(fd: Int): Boolean = runCatching {
        ParcelFileDescriptor.adoptFd(fd).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                var total = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > BIOS_SIZE_BYTES) break
                }
                total == BIOS_SIZE_BYTES
            }
        }
    }.getOrDefault(false)
    @JvmStatic fun setPerformanceMetricsEnabled(visible: Boolean, detailed: Boolean) {
        CoreRuntime.setPerformanceMetricsEnabled(visible, detailed)
    }
    @JvmStatic fun getPerformanceMetricsSnapshot(): String? = CoreRuntime.performanceMetricsSnapshot()
    @JvmStatic fun getDisplayDrawRect(): FloatArray? = CoreRuntime.displayRect()
    @JvmStatic fun getCoreName(): String? = runCatching {
        CoreRuntime.bridge.coreName()?.takeIf { it.isNotBlank() }
    }.getOrNull()
    @JvmStatic fun getCoreVersion(): String? = runCatching {
        CoreRuntime.bridge.coreVersion()
    }.getOrNull()
    @JvmStatic fun setAudioOutputGain(volume: Int, muted: Boolean) =
        CoreRuntime.setAudioGain(volume, muted)
    @JvmStatic fun setAudioOutputLatencyMs(milliseconds: Int) = runCatching {
        CoreRuntime.bridge.setAudioOutputLatencyMs(milliseconds)
    }
    @JvmStatic fun setAudioLowLatency(enabled: Boolean) = runCatching {
        CoreRuntime.bridge.setAudioLowLatency(enabled)
    }
    @JvmStatic @Synchronized fun setPadButton(padIndex: Int, index: Int, range: Int, pressed: Boolean) {
        setPadButtonFromSource(padIndex, index, range, pressed, INPUT_SOURCE_LEGACY)
    }

    /**
     * Applies one physical source without touching the other sources' state.
     * [KeyboardMouseBindings.PAD_SOURCE_KEYBOARD] and
     * [KeyboardMouseBindings.PAD_SOURCE_MOUSE] use their own slots; anything else
     * behaves exactly like [setPadButton].
     */
    @JvmStatic @Synchronized fun setPadButtonFromSource(
        padIndex: Int,
        index: Int,
        range: Int,
        pressed: Boolean,
        source: Int
    ) {
        if (padIndex !in 0..1) return
        val normalizedSource = if (source == KeyboardMouseBindings.PAD_SOURCE_KEYBOARD ||
            source == KeyboardMouseBindings.PAD_SOURCE_MOUSE
        ) {
            source
        } else {
            INPUT_SOURCE_LEGACY
        }

        if (index in PAD_GUN_TRIGGER..PAD_GUN_RELOAD) {
            updateSpecialButton(padIndex, index, normalizedSource, pressed) { isPressed ->
                CoreRuntime.setLightGunButton(
                    padIndex,
                    index - PAD_GUN_TRIGGER,
                    isPressed
                )
            }
            return
        }
        if (index in PAD_ARCADE_COIN..PAD_ARCADE_SERVICE) {
            updateSpecialButton(padIndex, index, normalizedSource, pressed) { isPressed ->
                CoreRuntime.setLightGunButton(
                    padIndex,
                    index - PAD_ARCADE_COIN + 3,
                    isPressed
                )
            }
            return
        }
        if (index == PAD_GUN_RECALIBRATE) {
            updateSpecialButton(padIndex, index, normalizedSource, pressed) { isPressed ->
                if (isPressed) {
                    CoreRuntime.setLightGunAim(padIndex, .5f, .5f, false)
                    _lightGunRecenterRequests.value = _lightGunRecenterRequests.value
                        .mapIndexed { port, value ->
                            if (port == padIndex) value + 1 else value
                        }
                }
            }
            return
        }
        // Fast forward only: holding Start (or the dedicated button) speeds the
        // emulation up. Model 3 racing games use Start as a normal coin/start
        // button, so it must not go through tap-vs-hold detection there.
        if ((index == PAD_START && !CoreRuntime.platform.isModel3) || index == PAD_FAST_FORWARD) {
            updateSpecialButton(padIndex, index, normalizedSource, pressed) { isPressed ->
                handleTimeControlHold(padIndex, index, isPressed)
            }
            return
        }
        if (index == PAD_ANALOG_TOGGLE) {
            updateSpecialButton(padIndex, index, normalizedSource, pressed) { isPressed ->
                if (isPressed) {
                    val analog = CoreRuntime.togglePadAnalogMode(padIndex)
                    if (analog != null) {
                        padAnalogMode[padIndex] = analog
                    }
                }
            }
            return
        }
        val halfAxis = analogHalfAxisIndex(index)
        if (halfAxis != null) {
            val axes = when (normalizedSource) {
                KeyboardMouseBindings.PAD_SOURCE_KEYBOARD -> padKeyboardAnalogHalfAxes
                KeyboardMouseBindings.PAD_SOURCE_MOUSE -> padMouseAnalogHalfAxes
                else -> padAnalogHalfAxes
            }
            axes[padIndex][halfAxis] = if (pressed) range.coerceIn(0, 255) else 0
            dispatchPadAnalog(padIndex)
            return
        }
        val bit = padButtonBit(index) ?: return
        val model3Trigger = CoreRuntime.platform.isModel3 && (bit == 8 || bit == 9)
        if (model3Trigger) CoreRuntime.setPadTriggerPressure(padIndex, normalizedSource, bit == 9, range, pressed)
        val buttons = when (normalizedSource) {
            KeyboardMouseBindings.PAD_SOURCE_KEYBOARD -> padKeyboardButtons
            KeyboardMouseBindings.PAD_SOURCE_MOUSE -> padMouseButtons
            else -> padButtons
        }
        // Axis events carry their own pressure. Do not turn them into held digital
        // tap edges (which would briefly become full throttle after axis release).
        val digitalPressed = if (model3Trigger) Model3TriggerPressure.digitalPress(range, pressed) else pressed
        buttons[padIndex] = if (digitalPressed) {
            buttons[padIndex] and (1 shl bit).inv()
        } else {
            buttons[padIndex] or (1 shl bit)
        }
        CoreRuntime.setPadButtons(padIndex, mergedPadButtons(padIndex))
    }

    private fun mergedPadButtons(padIndex: Int): Int =
        padButtons[padIndex] and padKeyboardButtons[padIndex] and padMouseButtons[padIndex]

    private fun updateSpecialButton(
        padIndex: Int,
        index: Int,
        source: Int,
        pressed: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val sourceBit = 1 shl source
        val holds = specialButtonSources[padIndex]
        val previous = holds[index] ?: 0
        val updated = if (pressed) previous or sourceBit else previous and sourceBit.inv()
        val wasHeld = previous != 0
        val isHeld = updated != 0
        if (isHeld) {
            holds[index] = updated
        } else {
            holds.remove(index)
        }
        if (wasHeld != isHeld) onChange(isHeld)
    }
    @JvmStatic fun onHostKeyEvent(keyCode: Int, pressed: Boolean) {
        setPadButton(0, keyCode, 0, pressed)
    }
    @JvmStatic fun resetKeyStatus() { resetPadState(0); resetPadState(1) }
    @JvmStatic @Synchronized fun resetPadState(padIndex: Int) {
        if (padIndex !in 0..1) return
        timeControlButtons[padIndex].forEach { it.reset() }
        timeControlTapPulse[padIndex].fill(false)
        updateTimeControl()
        padButtons[padIndex] = ALL_BUTTONS_RELEASED
        padKeyboardButtons[padIndex] = ALL_BUTTONS_RELEASED
        padMouseButtons[padIndex] = ALL_BUTTONS_RELEASED
        padAnalogHalfAxes[padIndex].fill(0)
        padKeyboardAnalogHalfAxes[padIndex].fill(0)
        padMouseAnalogHalfAxes[padIndex].fill(0)
        specialButtonSources[padIndex].clear()
        CoreRuntime.resetPadInput(padIndex)
    }

    @JvmStatic fun setPadAnalogMode(padIndex: Int, enabled: Boolean): Boolean {
        if (padIndex !in 0..1) return false
        val applied = CoreRuntime.setPadAnalogMode(padIndex, enabled)
        if (applied) {
            padAnalogMode[padIndex] = enabled
        }
        return applied
    }
    @JvmStatic fun setAspectRatio(type: Int) { CoreRuntime.setDisplayAspectRatio(type) }
    @JvmStatic fun renderUpscalemultiplier(value: Float) { setSetting("EmuCoreH/Display", "Upscale", "float", value.toString()) }
    // The software renderer is a CPU rasterizer: the only real internal
    // resolution increase is the 2x "enhanced resolution" buffer.
    @JvmStatic fun getMaxUpscaleMultiplier(renderer: Int): Int =
        if (RendererDefaults.toCoreRenderer(renderer) == RendererDefaults.CORE_SOFTWARE) 1 else UPSCALE_MAX.toInt()
    @JvmStatic fun setSetting(section: String, key: String, type: String, value: String): Boolean =
        CoreRuntime.updateSetting(section, key, value)
    @JvmStatic fun getSetting(section: String, key: String, type: String): String? = CoreRuntime.settings["$section:$key"]
    @JvmStatic fun setCoreOption(key: String, value: String) {
        CoreRuntime.setCoreOption(key, value)
    }
    @JvmStatic fun getCoreOption(key: String): String? = CoreRuntime.coreOptionValue(key)
    @JvmStatic fun applyCoreOption(key: String, value: String) {
        CoreRuntime.applyCoreOption(key, value)
    }
    @JvmStatic fun setFrameSkip(frames: Int) {
        val clamped = frames.coerceIn(0, 4)
        CoreRuntime.updateSetting("EmuCoreH/Runtime", "FrameSkip", clamped.toString())
        runCatching { CoreRuntime.bridge.setFrameSkip(clamped) }
    }
    @JvmStatic fun setDisplayCrop(crop: com.sbro.emucoreh.data.DisplayCrop) {
        val value = crop.sanitized()
        runCatching {
            CoreRuntime.bridge.setDisplayCrop(value.left, value.top, value.right, value.bottom)
        }
    }
    @JvmStatic fun setFrameLimitEnabled(enabled: Boolean) =
        CoreRuntime.updateSetting("EmuCoreH/Runtime", "FrameLimitEnable", enabled.toString())
    @JvmStatic fun loadCheats(path: String) = CoreRuntime.loadCheats(path)
    @JvmStatic fun clearCheats() = CoreRuntime.clearCheats()
    @JvmStatic fun setDataRootOverride(path: String?) = CoreRuntime.setDataRootOverride(path)
    @JvmStatic fun hasDiscMedia(): Boolean = CoreRuntime.hasDiscMedia()

    // RetroAchievements: every call degrades to a no-op when the bundled core
    // does not expose the rcheevos JNI surface.
    @JvmStatic fun achievementsSetEnabled(enabled: Boolean) =
        runCatching { CoreRuntime.bridge.achievementsSetEnabled(enabled) }
    @JvmStatic fun achievementsSetHardcore(enabled: Boolean) =
        runCatching { CoreRuntime.bridge.achievementsSetHardcore(enabled) }
    @JvmStatic fun achievementsSetUnofficial(enabled: Boolean) =
        runCatching { CoreRuntime.bridge.achievementsSetUnofficial(enabled) }
    @JvmStatic fun achievementsSetEncore(enabled: Boolean) =
        runCatching { CoreRuntime.bridge.achievementsSetEncore(enabled) }
    @JvmStatic fun achievementsLoginWithPassword(user: String, password: String): String? =
        runCatching { CoreRuntime.bridge.achievementsLoginWithPassword(user, password) }.getOrNull()
    @JvmStatic fun achievementsLoginWithToken(user: String, token: String): String? =
        runCatching { CoreRuntime.bridge.achievementsLoginWithToken(user, token) }.getOrNull()
    @JvmStatic fun achievementsLogout() =
        runCatching { CoreRuntime.bridge.achievementsLogout() }
    @JvmStatic fun achievementsLoadGame(path: String) =
        runCatching { CoreRuntime.bridge.achievementsLoadGame(path) }
    @JvmStatic fun achievementsUnloadGame() =
        runCatching { CoreRuntime.bridge.achievementsUnloadGame() }
    @JvmStatic fun achievementsPump() =
        runCatching { CoreRuntime.bridge.achievementsPump() }
    @JvmStatic fun achievementsStateJson(): String =
        runCatching { CoreRuntime.bridge.achievementsStateJson() }.getOrNull().orEmpty()
    @JvmStatic fun achievementsAchievementsJson(): String =
        runCatching { CoreRuntime.bridge.achievementsAchievementsJson() }.getOrNull().orEmpty()
    @JvmStatic fun achievementsPollEventsJson(): String =
        runCatching { CoreRuntime.bridge.achievementsPollEventsJson() }.getOrNull().orEmpty()
    @JvmStatic fun onNativeSurfaceCreated() = Unit
    @JvmStatic fun onNativeSurfaceChanged(surface: Surface, width: Int, height: Int) = CoreRuntime.attachSurface(surface, width, height)
    @JvmStatic fun hasAttachedSurface(surface: Surface, width: Int, height: Int): Boolean =
        CoreRuntime.hasAttachedSurface(surface, width, height)
    @JvmStatic fun onNativeSurfaceDestroyed() = CoreRuntime.detachSurface()
    @JvmStatic fun runVMThread(path: String): Boolean {
        activeSavePaths = null
        currentGamePath = path
        val identity = saveStateIdentityPath ?: path
        CoreRuntime.launchIdentityPath = identity
        val started = CoreRuntime.start(path, biosOnly = path.isBlank())
        if (started && currentGamePath == path && saveStatePathSource() == identity) {
            activeSavePaths = Model3ActiveSavePaths(path, identity)
        }
        return started
    }
    /** Disc product code reported by the core after the current game booted. */
    @JvmStatic fun currentGameSerial(): String? = runCatching {
        CoreRuntime.bridge.nativeGameSerial()
    }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

    @JvmStatic fun restartRenderer(renderer: Int): Boolean = CoreRuntime.restartWithRenderer(renderer)
    @JvmStatic fun changeDisc(path: String): Boolean = CoreRuntime.changeDisc(path)
    @JvmStatic fun pause() = CoreRuntime.pause()
    @JvmStatic fun resume() = CoreRuntime.resume()
    @JvmStatic fun shutdown() { activeSavePaths = null; CoreRuntime.shutdown() }
    @JvmStatic fun hasValidVm(): Boolean = CoreRuntime.isRunning()
    /** Ownership remains after a worker failure until explicit shutdown completes. */
    @JvmStatic fun hasOwnedVm(): Boolean = CoreRuntime.hasSession()
    val runtimeFailure get() = CoreRuntime.failure
    private fun saveStatePathSource(): String =
        saveStateIdentityPath?.takeIf(String::isNotBlank) ?: currentGamePath

    /** Uses current verified preparation only; never scans or copies a library game's ROM. */
    internal fun loadedModel3SetForPath(path: String): String? {
        val paths = activeSavePaths ?: return null
        if (!paths.matches(path, currentGamePath, saveStatePathSource(), CoreRuntime.hasSession())) return null
        return Model3ContentPreparer.preparedSetForPath(paths.launchPath)
    }

    internal fun legacySaveStatePathForActiveGame(path: String, slot: Int): String? =
        loadedModel3SetForPath(path)?.let { getSaveStatePathForFile(currentGamePath, slot) }

    @JvmStatic fun saveStateToSlot(slot: Int): Boolean =
        getSaveStatePathForFile(saveStatePathSource(), slot)?.let(CoreRuntime::saveState) == true

    @JvmStatic fun loadStateFromSlot(slot: Int): Boolean {
        val identityPath = getSaveStatePathForFile(saveStatePathSource(), slot)
        val context = getContext()
        if (context != null && SegaPlatformDetector.detect(context, saveStatePathSource()).isModel3) {
            val actualSet = loadedModel3SetForPath(saveStatePathSource()) ?: return false
            val selected = resolveSaveStateSlotFile(
                nativePath = identityPath,
                legacyPath = getSaveStatePathForFile(currentGamePath, slot),
                files = EmulatorStorage.saveStatesDir(context, dataRootOverride).listFiles().orEmpty(),
                gameSerial = null,
                slot = slot,
                expectedModel3Set = actualSet,
            ) ?: return false
            return CoreRuntime.loadState(selected.absolutePath)
        }
        if (identityPath != null && File(identityPath).exists()) {
            return CoreRuntime.loadState(identityPath)
        }

        // Keep saves written with the prepared launch path (before the identity
        // split was fixed) loadable through the legacy name.
        val legacyPath = getSaveStatePathForFile(currentGamePath, slot)
        if (legacyPath != null && legacyPath != identityPath && File(legacyPath).exists()) {
            return CoreRuntime.loadState(legacyPath)
        }

        return identityPath?.let(CoreRuntime::loadState) == true
    }

    @JvmStatic fun getSaveStatePathForFile(path: String, slot: Int): String? {
        return saveStatePathForFile(path, slot, null)
    }

    /**
     * Same identity as [getSaveStatePathForFile], but reuses the platform that
     * the library cache already stored. Bulk save-list enrichment must not run
     * the native CHD probe for every game in the library.
     */
    @JvmStatic fun getSaveStatePathForFile(path: String, slot: Int, platformName: String?): String? {
        return saveStatePathForFile(path, slot, EmulationPlatform.fromName(platformName))
    }

    private fun saveStatePathForFile(path: String, slot: Int, knownPlatform: EmulationPlatform?): String? {
        if (path.isBlank()) return null
        val context = getContext() ?: return null
        val directory = EmulatorStorage.saveStatesDir(context, dataRootOverride)
        val platform = knownPlatform ?: SegaPlatformDetector.detect(context, path)
        val identity = if (platform.isModel3) {
            "M3-${path.sha256().take(16).uppercase()}"
        } else if (platform.isSaturn) {
            SaturnSaveFiles.stateIdentity(path)
        } else if (platform.isSega) {
            "JG-${platform.name}-${path.sha256().take(16).uppercase()}"
        } else extractDiscSerial(path) ?: path.sha256().take(16).uppercase()
        return File(directory, "$identity.${slot.coerceIn(0, 99).toString().padStart(2, '0')}.rstate").absolutePath
    }
    @JvmStatic fun getCurrentSaveStatePath(slot: Int): String? =
        getSaveStatePathForFile(saveStatePathSource(), slot)
    @JvmStatic fun getCurrentSaveStatePath(slot: Int, platformName: String?): String? =
        getSaveStatePathForFile(saveStatePathSource(), slot, platformName)
    @JvmStatic fun getSaveStateScreenshot(path: String): ByteArray? = null
    @JvmStatic fun convertIsoToChd(inputIsoPath: String): Int = -1

    @JvmStatic fun startJitProfiler() = Unit
    @JvmStatic fun stopJitProfiler() = Unit
    @JvmStatic fun startHangTrace() = Unit
    @JvmStatic fun stopHangTrace() = Unit

    @JvmStatic
    fun initializeOnce(context: Context) {
        contextRef = WeakReference(context.applicationContext)
        val dataRoot = resolveDataRoot(context.applicationContext)
        dataRootOverride = dataRoot
        prepareNativeDataRoot(File(dataRoot))
        CoreRuntime.initialize(context.applicationContext)
    }

    @JvmStatic
    fun getContext(): Context? = contextRef?.get()

    @JvmStatic
    fun onPadVibration(index: Int, largeMotor: Float, smallMotor: Float) {
        GamepadManager.onPadVibration(index, largeMotor, smallMotor)
    }

    @JvmStatic
    fun getPadRumble(index: Int): FloatArray? {
        if (!hasNativeCore) return null
        return runCatching { CoreRuntime.getPadRumble(index) }.getOrNull()
    }

    @JvmStatic
    fun setCrashContextString(key: String, value: String?) {
        if (com.sbro.emucoreh.BuildConfig.DEBUG) CrashLogger.logContext(key, value)
    }

    @JvmStatic
    fun setCrashContextInt(key: String, value: Int) {
        if (com.sbro.emucoreh.BuildConfig.DEBUG) CrashLogger.logContext(key, value)
    }

    @JvmStatic
    fun setCrashContextBool(key: String, value: Boolean) {
        if (com.sbro.emucoreh.BuildConfig.DEBUG) CrashLogger.logContext(key, value)
    }

    @JvmStatic
    fun logCrashBreadcrumb(message: String) {
        if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.i(TAG, message)
        if (com.sbro.emucoreh.BuildConfig.DEBUG) CrashLogger.logInfo("Native", message)
    }

    @JvmStatic
    fun openContentUri(uriString: String): Int {
        val context = getContext() ?: return -1
        return try {
            val sanitized = uriString.substringBefore('|')
            val descriptor = context.contentResolver.openFileDescriptor(sanitized.toUri(), "r")
            descriptor?.detachFd() ?: -1
        } catch (_: Exception) {
            -1
        }
    }


    /** Root directory that stores native core data such as caches, settings and memory cards. */
    @JvmStatic
    fun dataRoot(context: Context): File = File(resolveDataRoot(context.applicationContext))

    private fun resolveDataRoot(context: Context): String {
        val override = dataRootOverride
        if (!override.isNullOrBlank()) {
            val dir = File(override)
            if (prepareNativeDataRoot(dir)) {
                return dir.absolutePath
            }
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Configured data root is not writable, falling back to app internal files: $override")
        }

        val external = context.getExternalFilesDir(null)
        if (external != null && prepareNativeDataRoot(external)) {
            return external.absolutePath
        }

        val internal = context.filesDir
        prepareNativeDataRoot(internal)
        return internal.absolutePath
    }

    private fun prepareNativeDataRoot(root: File): Boolean {
        return runCatching {
            if (!root.exists() && !root.mkdirs()) {
                return@runCatching false
            }

            val requiredDirectories = arrayOf(
                File(root, "cache"),
                File(root, "resources"),
                File(root, "sstates"),
                File(root, "memcards")
            )
            requiredDirectories.forEach { dir ->
                if (!dir.exists() && !dir.mkdirs()) {
                    return@runCatching false
                }
            }

            val probe = File(root, ".native-write-probe")
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrElse { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.w(TAG, "Native data root is not writable: ${root.absolutePath}", error)
            false
        }
    }

    private fun handleTimeControlHold(padIndex: Int, index: Int, pressed: Boolean) {
        val slot = when (index) {
            PAD_START -> 0
            PAD_SELECT -> 1
            PAD_FAST_FORWARD -> 2
            else -> 3
        }
        val button = timeControlButtons[padIndex][slot]
        if (button.pressed == pressed) return
        if (pressed) {
            val generation = button.press() ?: return
            if (slot < 2 && timeControlTapPulse[padIndex][slot]) {
                timeControlTapPulse[padIndex][slot] = false
                val bit = padButtonBit(index) ?: return
                padButtons[padIndex] = padButtons[padIndex] or (1 shl bit)
                CoreRuntime.setPadButtons(padIndex, mergedPadButtons(padIndex))
            }
            timeControlHandler.postDelayed({
                synchronized(this) {
                    if (button.activate(generation)) {
                        updateTimeControl()
                    }
                }
            }, TIME_CONTROL_HOLD_MS)
        } else {
            val wasTap = button.release()
            val generation = button.generation
            updateTimeControl()
            if (wasTap && slot < 2) {
                // A tap is still forwarded as a normal Start/Select press.
                val bit = padButtonBit(index) ?: return
                timeControlTapPulse[padIndex][slot] = true
                padButtons[padIndex] = padButtons[padIndex] and (1 shl bit).inv()
                CoreRuntime.setPadButtons(padIndex, mergedPadButtons(padIndex))
                timeControlHandler.postDelayed({
                    synchronized(this) {
                        if (button.generation == generation) {
                            timeControlTapPulse[padIndex][slot] = false
                            padButtons[padIndex] = padButtons[padIndex] or (1 shl bit)
                            CoreRuntime.setPadButtons(padIndex, mergedPadButtons(padIndex))
                        }
                    }
                }, TIME_CONTROL_TAP_MS)
            }
        }
    }

    private fun updateTimeControl() {
        // Rewind is not supported by the core; only fast forward is exposed.
        val fastForward = (0..1).any { pad ->
            timeControlButtons[pad][0].active || timeControlButtons[pad][2].active
        }
        val mode = if (fastForward) 1 else 0
        _timeControlMode.value = mode
        CoreRuntime.setTimeControl(mode)
    }

    private fun padButtonBit(index: Int): Int? = when (index) {
        109 -> 0  // Select
        106 -> 1  // L3
        107 -> 2  // R3
        108 -> 3  // Start
        19 -> 4   // Up
        22 -> 5   // Right
        20 -> 6   // Down
        21 -> 7   // Left
        104 -> 8  // L2
        105 -> 9  // R2
        102 -> 10 // L1
        103 -> 11 // R1
        100 -> 12 // Triangle
        97 -> 13  // Circle
        96 -> 14  // Cross
        99 -> 15  // Square
        else -> null
    }

    private fun analogHalfAxisIndex(index: Int): Int? = when (index) {
        110 -> 0 // Left stick up
        111 -> 1 // Left stick right
        112 -> 2 // Left stick down
        113 -> 3 // Left stick left
        120 -> 4 // Right stick up
        121 -> 5 // Right stick right
        122 -> 6 // Right stick down
        123 -> 7 // Right stick left
        else -> null
    }

    private fun mergeHalfAxes(negative: Int, positive: Int): Int {
        val delta = positive - negative
        return if (delta >= 0) 128 + ((delta * 127 + 127) / 255)
        else 128 - (((-delta) * 128 + 127) / 255)
    }

    private fun dispatchPadAnalog(padIndex: Int) {
        val legacy = padAnalogHalfAxes[padIndex]
        val keyboard = padKeyboardAnalogHalfAxes[padIndex]
        val mouse = padMouseAnalogHalfAxes[padIndex]
        fun merged(axis: Int): Int =
            maxOf(legacy[axis], keyboard[axis], mouse[axis])
        val lx = mergeHalfAxes(merged(3), merged(1))
        val ly = mergeHalfAxes(merged(0), merged(2))
        val rx = mergeHalfAxes(merged(7), merged(5))
        val ry = mergeHalfAxes(merged(4), merged(6))
        CoreRuntime.setPadAnalog(padIndex, lx, ly, rx, ry)
    }

    private fun extractDiscSerial(value: String): String? {
        // Reading game metadata opens the disc image, which is far too heavy
        // for the save-state paths that are computed for every game and slot.
        // Only pay for it when the name can actually carry such a serial.
        val embedded = if (discSerialCandidate.containsMatchIn(value)) {
            contextRef?.get()?.let { context ->
                runCatching { GameMetadataReader.read(context, value)?.serial }.getOrNull()
            }
        } else {
            null
        }
        val normalized = (embedded ?: value).uppercase(java.util.Locale.ROOT)
        val match = discSerialPattern.find(normalized) ?: return null
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val BIOS_SIZE_BYTES = 512L * 1024L
    private const val INPUT_SOURCE_LEGACY = 0
    private const val ALL_BUTTONS_RELEASED = 0xFFFF
    private const val PAD_ANALOG_TOGGLE = 125
    private const val PAD_FAST_FORWARD = 126
    private const val PAD_START = 108
    private const val PAD_SELECT = 109
    private const val TIME_CONTROL_HOLD_MS = 450L
    private const val TIME_CONTROL_TAP_MS = 60L
    private val discSerialCandidate = Regex("\\b[A-Z]{4}[-_. ]?\\d{5}\\b", RegexOption.IGNORE_CASE)
    private val discSerialPattern = Regex("\\b([A-Z]{4})[-_. ]?(\\d{5})\\b")
}
