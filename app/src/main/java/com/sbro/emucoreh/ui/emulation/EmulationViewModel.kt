
package com.sbro.emucoreh.ui.emulation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.EmuCoreHApp
import com.sbro.emucoreh.discord.DiscordIntegration
import com.sbro.emucoreh.core.AndroidGamePerformance
import com.sbro.emucoreh.core.AndroidGamePhase
import com.sbro.emucoreh.core.AudioDefaults
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.core.GamepadManager
import com.sbro.emucoreh.core.GpuHardwareProfiles
import com.sbro.emucoreh.core.MobileSocNameMapper
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.core.RuntimeFailure
import com.sbro.emucoreh.core.FlycastCoreOptions
import com.sbro.emucoreh.core.resolveAndroidGamePhase
import com.sbro.emucoreh.core.normalizeUpscale
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.SettingsSnapshot
import com.sbro.emucoreh.data.AppPreferences.Companion.FPS_OVERLAY_MODE_SIMPLE
import com.sbro.emucoreh.data.AppPreferences.Companion.FPS_OVERLAY_MODE_DETAILED
import com.sbro.emucoreh.data.CheatBlock
import com.sbro.emucoreh.data.DisplayCrop
import com.sbro.emucoreh.data.OverlayControlLayout
import com.sbro.emucoreh.data.CheatRepository
import com.sbro.emucoreh.data.GameRepository
import com.sbro.emucoreh.data.OverlayLayoutSnapshot
import com.sbro.emucoreh.data.PerGameSettings
import com.sbro.emucoreh.data.PerGameSettingsRepository
import com.sbro.emucoreh.data.RetroAchievementsRepository
import com.sbro.emucoreh.data.resolveShaderChain
import com.sbro.emucoreh.data.TouchControlsLayoutProfile
import com.sbro.emucoreh.data.PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
import com.sbro.emucoreh.data.saveTouchControlsLayout
import com.sbro.emucoreh.data.withTouchControlsLayout
import com.sbro.emucoreh.data.withoutTouchControlsLayout
import com.sbro.emucoreh.data.TouchControlVisualStyle
import com.sbro.emucoreh.data.TouchControlPressEffect
import com.sbro.emucoreh.data.CustomTouchControlLibrary
import com.sbro.emucoreh.data.GameMenuLayoutStyle
import com.sbro.emucoreh.data.GameMenuTabId
import com.sbro.emucoreh.data.GameMenuSectionId
import com.sbro.emucoreh.data.DefaultGameMenuTabOrder
import com.sbro.emucoreh.data.DefaultGameMenuSectionOrder
import com.sbro.emucoreh.data.PerformanceOverlayMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private const val BIOS_SESSION_TITLE = "Dreamcast BIOS"

private val PER_GAME_GPU_DRIVER_KEYS = setOf(
    "gpuDriverType",
    "customDriverPath",
    "mediatekAngleOpenGl"
)

private val PER_GAME_AUDIO_KEYS = setOf(
    "audioVolume",
    "audioMuted",
    "audioOutputLatencyMs",
    "audioMinimalOutputLatency"
)

private fun buildPerformanceOverlayHeader(application: Application): String {
    val packageInfo = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0)
    }.getOrNull()
    val appVersion = packageInfo?.versionName?.takeIf(String::isNotBlank) ?: "?"
    val buildNumber = packageInfo?.longVersionCode?.toString() ?: "?"
    val coreName = runCatching { NativeApp.getCoreName().orEmpty() }
        .getOrDefault("")
        .ifBlank { "?" }
    val coreVersion = runCatching { NativeApp.getCoreVersion().orEmpty() }
        .getOrDefault("")
    return "EmuCoreH-$appVersion | $buildNumber | $coreName" +
        coreVersion.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
}

internal fun replacePerformanceCpuName(text: String, cpuName: String): String {
    if (cpuName.isBlank() || cpuName == "Unknown") return text
    return text.lineSequence().joinToString("\n") { line ->
        if (!line.startsWith("CPU:")) return@joinToString line
        val valuesStart = line.indexOf(" | ")
        if (valuesStart < 0) "CPU:$cpuName" else "CPU:$cpuName${line.substring(valuesStart)}"
    }
}

internal fun replacePerformanceGpuName(text: String, gpuName: String): String {
    if (gpuName.isBlank() || gpuName == "Unknown") return text
    return text.lineSequence().joinToString("\n") { line ->
        if (!line.contains("GPU:")) return@joinToString line
        line.split(" | ").joinToString(" | ") { segment ->
            if (segment.startsWith("GPU:")) "GPU:$gpuName" else segment
        }
    }
}

enum class EmulationTransportMode { None, FastForward, Rewind }

data class EmulationUiState(
    val runtimeFailure: RuntimeFailure? = null,
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val isPaused: Boolean = false,
    val showMenu: Boolean = false,
    val transportMode: EmulationTransportMode = EmulationTransportMode.None,
    val isActionInProgress: Boolean = false,
    val actionLabel: String? = null,
    val controlsVisible: Boolean = true,
    val showFps: Boolean = true,
    val confirmSaveLoadActions: Boolean = true,
    val backButtonExitsGame: Boolean = false,
    val compactControls: Boolean = true,
    val keepScreenOn: Boolean = true,
    val fpsOverlayCorner: Int = AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT,
    val fpsOverlayScale: Int = AppPreferences.DEFAULT_FPS_OVERLAY_SCALE,
    val fpsOverlayMetrics: Int = PerformanceOverlayMetrics.DEFAULT,
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
    val touchControlVisualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    val touchControlPressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    val customTouchControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
    val gameMenuLayoutStyle: GameMenuLayoutStyle = GameMenuLayoutStyle.SIDEBAR,
    val gameMenuTabOrder: List<GameMenuTabId> = DefaultGameMenuTabOrder,
    val hiddenGameMenuTabs: Set<GameMenuTabId> = emptySet(),
    val gameMenuSectionOrder: List<GameMenuSectionId> = DefaultGameMenuSectionOrder,
    val hiddenGameMenuSections: Set<GameMenuSectionId> = emptySet(),
    val hideOverlayOnGamepad: Boolean = true,
    val dpadOffset: Pair<Float, Float> = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
    val lstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
    val rstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
    val actionOffset: Pair<Float, Float> = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
    val lbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
    val rbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
    val centerOffset: Pair<Float, Float> = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
    val stickScale: Int = 100,
    val leftStickSensitivity: Int = 100,
    val rightStickSensitivity: Int = 100,
    val invertLeftStick: Boolean = false,
    val invertRightStick: Boolean = false,
    val invertLeftStickHorizontal: Boolean = false,
    val invertRightStickHorizontal: Boolean = false,
    val racingMode: Boolean = false,
    val touchscreenRightStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK,
    val touchscreenRightStickSensitivity: Int = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY,
    val touchHaptics: Boolean = false,
    val touchHapticsPreset: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET,
    val touchHapticsStrength: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_STRENGTH,
    val gyroMode: Int = AppPreferences.GYRO_MODE_OFF,
    val gyroSensitivity: Int = AppPreferences.DEFAULT_GYRO_SENSITIVITY,
    val gyroSmoothing: Int = AppPreferences.DEFAULT_GYRO_SMOOTHING,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
    val stickSurfaceMode: Boolean = false,
    val controlLayouts: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts(),
    val fps: String = "0.0",
    val fpsOverlayMode: Int = FPS_OVERLAY_MODE_DETAILED,
    val performanceOverlayText: String = "",
    val performanceOverlayHeader: String = "",
    val speedPercent: Float = 100f,
    val toastMessage: String? = null,
    val statusMessage: String? = null,
    val currentSlot: Int = 1,
    val renderer: Int = RendererDefaults.defaultForHardware(),
    val upscale: Float = 1f,
    val aspectRatio: Int = 1,
    val localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val cheatsGameKey: String? = null,
    val availableCheats: List<CheatBlock> = emptyList(),
    val frameLimitEnabled: Boolean = true,
    val fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
    val targetFps: Int = 0,
    val ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
    val palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE,
    val currentGameTitle: String = "",
    val currentGameSubtitle: String = "",
    val currentGameCoverPath: String? = null,
    val gameSettingsProfileActive: Boolean = false,
    val perGameCoreOptions: Map<String, String> = emptyMap(),
    val currentSlotLastModified: Long = 0L,
    val autoSaveEnabled: Boolean = false,
    val autoSaveIntervalMinutes: Int = 1,
    val autoSaveOnExit: Boolean = false,
    val autoLoadOnStart: Boolean = false,
    val autoSaveLastModified: Long = 0L,
    val isAutoSaveInProgress: Boolean = false,
    val activePlayTimeMs: Long = 0L,
    val showDebugOptions: Boolean = false,
    val isJitProfilerActive: Boolean = false,
    val isHangTraceActive: Boolean = false,
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
)

private data class EmulationLaunchConfig(
    val biosPath: String?,
    val emulatorDataPath: String?,
    val memoryCardSlot1: String?,
    val memoryCardSlot2: String?,
    val renderer: Int,
    val upscaleMultiplier: Float,
    val gpuDriverType: Int,
    val customDriverPath: String?,
    val gpuHardwareProfile: Int,
    val mediatekAngleOpenGl: Boolean,
    val aspectRatio: Int,
    val localMultiplayerMode: Int,
    val displayCrop: DisplayCrop,
    val audioVolume: Int,
    val audioFastForwardVolume: Int,
    val audioMuted: Boolean,
    val audioOutputLatencyMs: Int,
    val audioMinimalOutputLatency: Boolean,
    val frameLimitEnabled: Boolean,
    val vSyncEnabled: Boolean,
    val fastForwardSpeed: Float,
    val targetFps: Int,
    val ntscFramerate: Float,
    val palFramerate: Float,
    val shaderChainEnabled: Boolean,
    val shaderChainPreset: String,
    val pressureModifierAmount: Int,
)

private data class LiveRuntimeSnapshot(
    val showFps: Boolean,
    val fpsOverlayMode: Int,
    val confirmSaveLoadActions: Boolean,
    val backButtonExitsGame: Boolean,
    val renderer: Int,
    val upscale: Float,
    val aspectRatio: Int,
    val localMultiplayerMode: Int,
    val displayCrop: DisplayCrop,
    val frameLimitEnabled: Boolean,
    val fastForwardSpeed: Float,
    val racingMode: Boolean,
    val touchscreenRightStick: Boolean,
    val touchscreenRightStickSensitivity: Int,
    val touchHaptics: Boolean,
    val touchHapticsPreset: Int,
    val touchHapticsStrength: Int,
    val touchControlVisualStyle: TouchControlVisualStyle,
    val touchControlPressEffect: TouchControlPressEffect,
    val gyroMode: Int,
    val gyroSensitivity: Int,
    val gyroSmoothing: Int,
    val gyroInvertX: Boolean,
    val gyroInvertY: Boolean,
    val gamepadRightStickUpToR2: Boolean,
    val gamepadRightStickDownToL2: Boolean,
    val gamepadButtonHaptics: Boolean,
    val gamepadStickDeadzone: Int,
    val gamepadLeftStickSensitivity: Int,
    val gamepadRightStickSensitivity: Int,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>>,
    val pressureModifierAmount: Int,
    val autoSaveOnExit: Boolean,
    val autoLoadOnStart: Boolean,
    val targetFps: Int,
    val ntscFramerate: Float,
    val palFramerate: Float,
)

class EmulationViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val TAG = "EmulationViewModel"
        private const val AUTO_SAVE_SLOT = 0
        private val SAVE_STATE_FILE_REGEX = Regex("""^(.+?)\.(\d{2})\.rstate$""")
    }


    private val preferences = AppPreferences(application)
    private val cheatRepository = CheatRepository(application)
    private val perGameSettingsRepository = PerGameSettingsRepository(application)
    private val gameRepository = GameRepository()
    private val performanceCpuName = MobileSocNameMapper.currentDeviceName()
    private val performanceGpuName = GpuHardwareProfiles.gpuDisplayName()
    private val androidGamePerformance = AndroidGamePerformance(application)
    private val _uiState = MutableStateFlow(
        EmulationUiState(performanceOverlayHeader = buildPerformanceOverlayHeader(application))
    )
    // Derive terminal presentation from authoritative runtime state. Deferred
    // startup/save-load coroutines cannot overwrite a fatal error with Running.
    val uiState: StateFlow<EmulationUiState> = combine(_uiState, EmulatorBridge.runtimeFailure) {
        state, failure -> state.withRuntimeFailure(failure)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        _uiState.value.withRuntimeFailure(EmulatorBridge.runtimeFailure.value))
    private val lifecycleMutex = Mutex()
    private var pausedForBackground = false
    @Volatile
    private var isShuttingDown = false
    @Volatile
    private var cancelPendingStart = false
    @Volatile
    private var currentGameTitle: String = ""
    @Volatile
    private var currentGamePath: String? = null
    @Volatile
    private var currentGameSerial: String = ""
    @Volatile
    private var currentGameRegionLabel: String = ""
    @Volatile
    private var currentGameCoverArtPath: String? = null
    @Volatile
    private var currentGameCrc: String = ""
    @Volatile
    private var currentGameSource: String = ""
    /** Per-game core option overrides resolved at launch, applied after boot. */
    private var pendingPerGameCoreOptions: Map<String, String> = emptyMap()
    private var currentTouchControlsLayoutProfile: TouchControlsLayoutProfile? = null
    private var lastAutoSavePlayTimeMs: Long = 0L
    init {
        viewModelScope.launch {
            preferences.migrateOverlayLayoutIfNeeded()
        }
        viewModelScope.launch {
            uiState
                .map { state ->
                    resolveAndroidGamePhase(
                        isStarting = state.isStarting,
                        isRunning = state.isRunning,
                        isPaused = state.isPaused,
                        showMenu = state.showMenu
                    )
                }
                .distinctUntilChanged()
                .collect(androidGamePerformance::update)
        }
    }

    private inline fun applyGlobalRuntimePreferenceUpdate(
        crossinline transform: (EmulationUiState) -> EmulationUiState
    ) {
        val current = _uiState.value
        // A running game session owns its per-game overrides: a late global
        // preference emission must not overwrite them (e.g. upscale 2x set in
        // the in-game menu being reset to the global 1x a moment later).
        if (current.gameSettingsProfileActive || activePerGameKey() != null) return
        _uiState.value = transform(current)
        syncNativePerformanceOverlayState(_uiState.value)
        syncGamepadRuntimeSettings(_uiState.value)
    }

    private fun syncNativePerformanceOverlayState(state: EmulationUiState) {
        val detailed = state.showFps && state.fpsOverlayMode != FPS_OVERLAY_MODE_SIMPLE
        NativeApp.setPerformanceMetricsEnabled(visible = state.showFps, detailed = detailed)
    }

    private fun syncGamepadRightStickTriggerMapping(state: EmulationUiState) {
        GamepadManager.setRightStickTriggerMapping(
            upToR2 = state.gamepadRightStickUpToR2,
            downToL2 = state.gamepadRightStickDownToL2
        )
    }

    private fun syncGamepadRuntimeSettings(state: EmulationUiState) {
        syncGamepadRightStickTriggerMapping(state)
        GamepadManager.setButtonHapticsEnabled(
            enabled = state.gamepadButtonHaptics,
            strengthPercent = state.touchHapticsStrength,
            preset = state.touchHapticsPreset
        )
        NativeApp.setPadPressureModifierAmount(state.pressureModifierAmount.coerceIn(1, 100))
    }

    private fun stopHiddenDebugTools(state: EmulationUiState) {
        if (!state.isJitProfilerActive && !state.isHangTraceActive) return
        viewModelScope.launch {
            if (state.isJitProfilerActive) {
                EmulatorBridge.stopJitProfiler()
            }
            if (state.isHangTraceActive) {
                EmulatorBridge.stopHangTrace()
            }
            _uiState.value = _uiState.value.copy(
                isJitProfilerActive = false,
                isHangTraceActive = false
            )
        }
    }

    init {
        viewModelScope.launch {
            NativeApp.timeControlMode.collect { mode ->
                val transport = when (mode) {
                    1 -> EmulationTransportMode.FastForward
                    2 -> EmulationTransportMode.Rewind
                    else -> EmulationTransportMode.None
                }
                if (_uiState.value.transportMode != transport) {
                    _uiState.value = _uiState.value.copy(transportMode = transport)
                }
            }
        }
        viewModelScope.launch {
            preferences.overlayShow.collect { enabled ->
                _uiState.value = _uiState.value.copy(controlsVisible = enabled)
            }
        }
        viewModelScope.launch {
            preferences.showFps.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(showFps = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.overlayLayoutSnapshot.collect { snapshot ->
                applyOverlayLayoutSnapshot(snapshot)
            }
        }
        viewModelScope.launch {
            preferences.touchControlVisualStyle.collect { style ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlVisualStyle
                }
                _uiState.value = _uiState.value.copy(touchControlVisualStyle = override ?: style)
            }
        }
        viewModelScope.launch {
            preferences.touchControlPressEffect.collect { effect ->
                val override = withContext(Dispatchers.IO) {
                    activePerGameKey()
                        ?.let(perGameSettingsRepository::get)
                        ?.touchControlPressEffect
                }
                _uiState.value = _uiState.value.copy(touchControlPressEffect = override ?: effect)
            }
        }
        viewModelScope.launch {
            preferences.customTouchControls.collect { library ->
                _uiState.value = _uiState.value.copy(customTouchControls = library)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuLayoutStyle.collect { style ->
                _uiState.value = _uiState.value.copy(gameMenuLayoutStyle = style)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuTabOrder.collect { order ->
                _uiState.value = _uiState.value.copy(gameMenuTabOrder = order)
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuTabs.collect { hidden ->
                _uiState.value = _uiState.value.copy(hiddenGameMenuTabs = hidden)
            }
        }
        viewModelScope.launch {
            preferences.gameMenuSectionOrder.collect { order ->
                _uiState.value = _uiState.value.copy(gameMenuSectionOrder = order)
            }
        }
        viewModelScope.launch {
            preferences.hiddenGameMenuSections.collect { hidden ->
                _uiState.value = _uiState.value.copy(hiddenGameMenuSections = hidden)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMode.collect { mode ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fpsOverlayMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayCorner.collect { corner ->
                _uiState.value = _uiState.value.copy(fpsOverlayCorner = corner)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayScale.collect { scale ->
                _uiState.value = _uiState.value.copy(fpsOverlayScale = scale)
            }
        }
        viewModelScope.launch {
            preferences.fpsOverlayMetrics.collect { metrics ->
                val updated = _uiState.value.copy(fpsOverlayMetrics = metrics)
                _uiState.value = updated
                syncNativePerformanceOverlayState(updated)
            }
        }
        viewModelScope.launch {
            preferences.showDebugOptions.collect { enabled ->
                val state = _uiState.value
                _uiState.value = state.copy(showDebugOptions = enabled)
                if (!enabled) {
                    stopHiddenDebugTools(state)
                }
            }
        }
        viewModelScope.launch {
            preferences.compactControls.collect { enabled ->
                _uiState.value = _uiState.value.copy(compactControls = enabled)
            }
        }
        viewModelScope.launch {
            preferences.gamepadStickDeadzone.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadStickDeadzone = value)
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStick.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertLeftStick = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertRightStick.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertRightStick = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertLeftStickHorizontal.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertLeftStickHorizontal = enabled)
            }
        }
        viewModelScope.launch {
            preferences.invertRightStickHorizontal.collect { enabled ->
                _uiState.value = _uiState.value.copy(invertRightStickHorizontal = enabled)
            }
        }
        viewModelScope.launch {
            preferences.gamepadLeftStickSensitivity.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadLeftStickSensitivity = value)
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickSensitivity.collect { value ->
                _uiState.value = _uiState.value.copy(gamepadRightStickSensitivity = value)
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickUpToR2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickUpToR2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadRightStickDownToL2.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadRightStickDownToL2 = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.gamepadButtonHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(gamepadButtonHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.pressureModifierAmount.collect { amount ->
                applyGlobalRuntimePreferenceUpdate { it.copy(pressureModifierAmount = amount.coerceIn(1, 100)) }
            }
        }
        viewModelScope.launch {
            preferences.confirmSaveLoadActions.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(confirmSaveLoadActions = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.backButtonExitsGame.collect { enabled ->
                _uiState.value = _uiState.value.copy(backButtonExitsGame = enabled)
            }
        }
        viewModelScope.launch {
            preferences.keepScreenOn.collect { enabled ->
                _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
            }
        }
        viewModelScope.launch {
            preferences.renderer.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(renderer = value) }
            }
        }
        viewModelScope.launch {
            preferences.upscaleMultiplier.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(upscale = value) }
            }
        }
        viewModelScope.launch {
            preferences.aspectRatio.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(aspectRatio = value) }
            }
        }
        viewModelScope.launch {
            preferences.localMultiplayerMode.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(localMultiplayerMode = value) }
            }
        }
        viewModelScope.launch {
            preferences.displayCrop.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(displayCrop = value) }
            }
        }
        viewModelScope.launch {
            preferences.frameLimitEnabled.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(frameLimitEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.targetFps.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(targetFps = value) }
            }
        }
        viewModelScope.launch {
            preferences.fastForwardSpeed.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(fastForwardSpeed = value) }
            }
        }
        viewModelScope.launch {
            preferences.racingMode.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(racingMode = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStick.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStick = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchscreenRightStickSensitivity.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchscreenRightStickSensitivity = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHaptics.collect { enabled ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHaptics = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsPreset.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsPreset = value) }
            }
        }
        viewModelScope.launch {
            preferences.touchHapticsStrength.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(touchHapticsStrength = value) }
            }
        }
        viewModelScope.launch { preferences.gyroMode.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroMode = value) } } }
        viewModelScope.launch { preferences.gyroSensitivity.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSensitivity = value) } } }
        viewModelScope.launch { preferences.gyroSmoothing.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroSmoothing = value) } } }
        viewModelScope.launch { preferences.gyroInvertX.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertX = value) } } }
        viewModelScope.launch { preferences.gyroInvertY.collect { value -> applyGlobalRuntimePreferenceUpdate { it.copy(gyroInvertY = value) } } }
        viewModelScope.launch {
            preferences.ntscFramerate.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(ntscFramerate = value) }
            }
        }
        viewModelScope.launch {
            preferences.palFramerate.collect { value ->
                applyGlobalRuntimePreferenceUpdate { it.copy(palFramerate = value) }
            }
        }
        viewModelScope.launch {
            preferences.autoSaveEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoSaveEnabled = enabled)
                if (enabled) {
                    lastAutoSavePlayTimeMs = _uiState.value.activePlayTimeMs
                }
            }
        }
        viewModelScope.launch {
            preferences.autoSaveIntervalMinutes.collect { value ->
                _uiState.value = _uiState.value.copy(autoSaveIntervalMinutes = value.coerceIn(1, 999))
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(1_000.milliseconds)
                pollNativePerformanceMetrics()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000.milliseconds)
                tickActivePlayTimeAndAutoSave()
            }
        }
        syncNativePerformanceOverlayState(_uiState.value)
    }

    private fun tickActivePlayTimeAndAutoSave() {
        val state = _uiState.value
        if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown ||
            EmulatorBridge.runtimeFailure.value != null) {
            return
        }

        val nextPlayTimeMs = state.activePlayTimeMs + 1_000L
        _uiState.value = state.copy(activePlayTimeMs = nextPlayTimeMs)

        val intervalMs = state.autoSaveIntervalMinutes.coerceIn(1, 999) * 60_000L
        if (!state.autoSaveEnabled ||
            state.isActionInProgress ||
            state.isAutoSaveInProgress ||
            nextPlayTimeMs - lastAutoSavePlayTimeMs < intervalMs
        ) {
            return
        }

        lastAutoSavePlayTimeMs = nextPlayTimeMs
        performAutoSave()
    }

    private fun performAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            saveAutoSaveSlot(allowWhileMenu = false, allowPaused = false, showActionProgress = false)
        }
    }

    private suspend fun saveAutoSaveSlot(
        allowWhileMenu: Boolean,
        allowPaused: Boolean,
        showActionProgress: Boolean
    ): Boolean {
        val path = currentGamePath ?: return false
        val previousModified = saveStateLastModified(path, AUTO_SAVE_SLOT)
        val before = _uiState.value
        _uiState.value = before.copy(
            isAutoSaveInProgress = true,
            isActionInProgress = if (showActionProgress) true else before.isActionInProgress,
            actionLabel = if (showActionProgress) "saving" else before.actionLabel
        )
        val scheduled = lifecycleMutex.withLock {
            val state = _uiState.value
            if (isShuttingDown ||
                !state.isRunning ||
                (!allowPaused && state.isPaused) ||
                (!allowWhileMenu && state.showMenu)
            ) {
                false
            } else {
                try {
                    EmulatorBridge.saveState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        val success = scheduled && waitForSaveStateUpdate(path, AUTO_SAVE_SLOT, previousModified)
        val after = _uiState.value
        _uiState.value = after.copy(
            isAutoSaveInProgress = false,
            isActionInProgress = if (showActionProgress) false else after.isActionInProgress,
            actionLabel = if (showActionProgress) null else after.actionLabel
        )
        refreshSaveStateMetadata()
        return success
    }

    private fun pollNativePerformanceMetrics() {
        val state = _uiState.value
        if (!state.isRunning || isShuttingDown || state.isPaused || !state.showFps) return
        val raw = NativeApp.getPerformanceMetricsSnapshot().orEmpty()
        if (raw.isBlank()) return

        val parts = raw.split('\n', limit = 3)
        if (parts.size < 3) return
        val fps = parts[0].toFloatOrNull() ?: return
        val speedPercent = parts[1].toFloatOrNull() ?: return
        val overlayText = replacePerformanceGpuName(
            replacePerformanceCpuName(parts[2], performanceCpuName),
            performanceGpuName
        )
        _uiState.value = state.copy(
            performanceOverlayText = overlayText,
            fps = "%.1f".format(fps),
            speedPercent = speedPercent
        )
    }

    fun startEmulation(
        path: String?,
        slotToLoad: Int? = null,
        bootToBios: Boolean = false,
        bootSmokeProbe: Boolean = false,
        autotestMode: Boolean = false,
        rendererOverride: Int? = null,
        gsDumpFrames: Int? = null,
        gsDumpDelayMs: Int? = null
    ) {
        val analyticsLaunchType = when {
            bootSmokeProbe -> "smoke_test"
            autotestMode -> "autotest"
            bootToBios -> "bios"
            else -> "game"
        }
        Log.i(
            TAG,
            "startEmulation requested path=$path bootBios=$bootToBios bootSmoke=$bootSmokeProbe autotest=$autotestMode"
        )
        if (_uiState.value.isStarting) {
            Log.w(TAG, "startEmulation skipped because another start is in progress")
            return
        }
        val normalizedSlotToLoad = slotToLoad?.let { normalizeSaveSlot(it) }
        val hasPendingStateLoad = !bootToBios && !bootSmokeProbe && normalizedSlotToLoad != null
        cancelPendingStart = false
        pausedForBackground = false
        currentGamePath = if (bootToBios) null else path?.takeIf { it.isNotBlank() }
        currentTouchControlsLayoutProfile = null
        currentGameCoverArtPath = null
        lastAutoSavePlayTimeMs = 0L
        _uiState.value = _uiState.value.copy(
            activePlayTimeMs = 0L,
            currentSlotLastModified = 0L,
            autoSaveLastModified = 0L,
            isAutoSaveInProgress = false
        )

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "status_preparing"
            )

            if (_uiState.value.isRunning || EmulatorBridge.hasValidVm()) {
                performShutdown()
                delay(300.milliseconds)
            }

            var finalLaunchPath: String? = null
            lifecycleMutex.withLock {
                if (isShuttingDown || cancelPendingStart) return@launch

                _uiState.value = _uiState.value.copy(
                    isStarting = true,
                    statusMessage = "status_checking_bios"
                )

                val config = loadLaunchConfig()
                val renderer = rendererOverride ?: config.renderer

                // PPSSPP boots without a firmware dump; a configured BIOS is
                // staged by CoreRuntime when one is present and usable.

                _uiState.value = _uiState.value.copy(
                    statusMessage = "status_applying_config"
                )
                delay(200.milliseconds)

                EmulatorBridge.applyRuntimeConfig(
                    biosPath = config.biosPath,
                    emulatorDataPath = config.emulatorDataPath,
                    memoryCardSlot1 = config.memoryCardSlot1,
                    memoryCardSlot2 = config.memoryCardSlot2,
                    renderer = renderer,
                    upscaleMultiplier = config.upscaleMultiplier,
                    gpuDriverType = config.gpuDriverType,
                    customDriverPath = config.customDriverPath,
                    gpuHardwareProfile = config.gpuHardwareProfile,
                    mediatekAngleOpenGl = config.mediatekAngleOpenGl,
                    aspectRatio = config.aspectRatio,
                    localMultiplayerMode = config.localMultiplayerMode,
                    displayCrop = config.displayCrop,
                    audioVolume = config.audioVolume,
                    audioFastForwardVolume = config.audioFastForwardVolume,
                    audioMuted = config.audioMuted,
                    audioOutputLatencyMs = config.audioOutputLatencyMs,
                    audioMinimalOutputLatency = config.audioMinimalOutputLatency,
                    frameLimitEnabled = config.frameLimitEnabled,
                    vSyncEnabled = config.vSyncEnabled,
                    fastForwardSpeed = config.fastForwardSpeed,
                    targetFps = config.targetFps,
                    ntscFramerate = config.ntscFramerate,
                    palFramerate = config.palFramerate,
                    shaderChainEnabled = config.shaderChainEnabled,
                    shaderChainPreset = config.shaderChainPreset,
                    pressureModifierAmount = config.pressureModifierAmount,
                    autotestMode = autotestMode || bootSmokeProbe,
                )

                _uiState.value = _uiState.value.copy(
                    statusMessage = "status_loading_game"
                )
                delay(200.milliseconds)

                val launchPath = when {
                    bootToBios -> ""
                    path.isNullOrBlank() -> null
                    path.startsWith("content://") && DocumentPathResolver.getDisplayName(getApplication(), path)
                        .substringAfterLast('.', "").equals("elf", ignoreCase = true) ->
                            DocumentPathResolver.prepareElfLaunchPath(getApplication(), path)
                    else -> DocumentPathResolver.prepareGameLaunchPath(getApplication(), path)
                }

                if (!bootToBios && launchPath.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        statusMessage = null,
                        toastMessage = "launch_path_error"
                    )
                    delay(2500.milliseconds)
                    _uiState.value = _uiState.value.copy(toastMessage = null)
                    return@withLock
                }

                finalLaunchPath = launchPath
                Log.i(TAG, "Prepared launch path=$launchPath originalPath=$path bootBios=$bootToBios")
                if (bootToBios) {
                    currentGameTitle = BIOS_SESSION_TITLE
                    currentGamePath = null
                    currentGameSerial = ""
                    currentGameRegionLabel = ""
                    currentGameCoverArtPath = null
                    currentGameCrc = ""
                    currentGameSource = "bios_only"
                    pendingPerGameCoreOptions = emptyMap()
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = false,
                        perGameCoreOptions = emptyMap(),
                        cheatsGameKey = null,
                        availableCheats = emptyList()
                    )
                } else if (autotestMode) {
                    val safePath = path.orEmpty()
                    currentGameTitle = File(safePath).nameWithoutExtension.ifBlank { "Autotest ELF" }
                    currentGameSerial = ""
                    currentGameRegionLabel = ""
                    currentGameCoverArtPath = null
                    currentGameCrc = ""
                    currentGameSource = "autotest_elf"
                    pendingPerGameCoreOptions = emptyMap()
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = false,
                        perGameCoreOptions = emptyMap(),
                        cheatsGameKey = null,
                        availableCheats = emptyList()
                    )
                } else {
                    val safePath = path.orEmpty()
                    val existingProfile = currentGamePath?.let(perGameSettingsRepository::get)
                    currentTouchControlsLayoutProfile = existingProfile?.touchControlsLayout
                    val metadata = EmulatorBridge.getGameMetadata(safePath)
                    currentGameTitle = EmulatorBridge.cleanGameDisplayTitle(metadata.title, safePath)
                    currentGameSerial = metadata.serial?.takeIf { it.isNotBlank() }
                        ?.let(::formatDiscSerial).orEmpty()
                    currentGameRegionLabel = metadata.region
                        ?: resolveRegionLabel(currentGameSerial, safePath)
                    currentGameCoverArtPath = gameRepository.findCoverForGame(
                        path = safePath,
                        context = getApplication(),
                        serial = currentGameSerial.takeIf { it.isNotBlank() },
                        title = currentGameTitle
                    )
                    currentGameCrc = metadata.serialWithCrc.extractCrc().orEmpty()
                    currentGameSource = when {
                        safePath.startsWith("content://") -> "content_uri"
                        launchPath?.startsWith("/") == true -> "file"
                        else -> "unknown"
                    }
                    pendingPerGameCoreOptions = (
                        existingProfile
                            ?: safePath.takeIf { it.isNotBlank() }?.let(perGameSettingsRepository::get)
                        )?.coreOptions.orEmpty()
                        .filterKeys { !FlycastCoreOptions.isManagedKey(it) }
                    refreshCurrentGameCheats(metadata)
                    _uiState.value = _uiState.value.copy(
                        currentGameTitle = currentGameTitle,
                        currentGameSubtitle = currentGameSubtitle(),
                        currentGameCoverPath = currentGameCoverArtPath,
                        gameSettingsProfileActive = existingProfile != null,
                        perGameCoreOptions = pendingPerGameCoreOptions
                    )
                    syncCurrentGameProfileMetadata()
                    if (!bootSmokeProbe) {
                        DiscordIntegration.setPlaying(
                            title = currentGameTitle,
                            serial = currentGameSerial.takeIf { it.isNotBlank() }
                        )
                    }
                }
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )

                _uiState.value = _uiState.value.copy(
                    isRunning = true,
                    isStarting = false,
                    statusMessage = "status_starting_core"
                )
                refreshSaveStateMetadata()
                if (!autotestMode && !bootSmokeProbe && !bootToBios && !path.isNullOrBlank()) {
                    preferences.markGameLaunched(
                        path = path,
                        title = currentGameTitle.ifBlank {
                            DocumentPathResolver.getDisplayName(getApplication(), path).substringBeforeLast('.')
                        },
                        serial = currentGameSerial.takeIf { it.isNotBlank() }
                    )
                }

                val liveRuntime = loadLiveRuntimeSnapshot()
                val overlaySnapshot = preferences.overlayLayoutSnapshot.first()

                val runtimeState = _uiState.value.copy(
                    showFps = liveRuntime.showFps,
                    fpsOverlayMode = liveRuntime.fpsOverlayMode,
                    confirmSaveLoadActions = liveRuntime.confirmSaveLoadActions,
                    backButtonExitsGame = liveRuntime.backButtonExitsGame,
                    renderer = liveRuntime.renderer,
                    upscale = liveRuntime.upscale,
                    aspectRatio = liveRuntime.aspectRatio,
                    localMultiplayerMode = liveRuntime.localMultiplayerMode,
                    displayCrop = liveRuntime.displayCrop,
                    frameLimitEnabled = liveRuntime.frameLimitEnabled,
                    fastForwardSpeed = liveRuntime.fastForwardSpeed,
                    racingMode = liveRuntime.racingMode,
                    touchscreenRightStick = liveRuntime.touchscreenRightStick,
                    touchscreenRightStickSensitivity = liveRuntime.touchscreenRightStickSensitivity,
                    touchHaptics = liveRuntime.touchHaptics,
                    touchHapticsPreset = liveRuntime.touchHapticsPreset,
                    touchHapticsStrength = liveRuntime.touchHapticsStrength,
                    touchControlVisualStyle = liveRuntime.touchControlVisualStyle,
                    touchControlPressEffect = liveRuntime.touchControlPressEffect,
                    gyroMode = liveRuntime.gyroMode,
                    gyroSensitivity = liveRuntime.gyroSensitivity,
                    gyroSmoothing = liveRuntime.gyroSmoothing,
                    gyroInvertX = liveRuntime.gyroInvertX,
                    gyroInvertY = liveRuntime.gyroInvertY,
                    gamepadRightStickUpToR2 = liveRuntime.gamepadRightStickUpToR2,
                    gamepadRightStickDownToL2 = liveRuntime.gamepadRightStickDownToL2,
                    gamepadButtonHaptics = liveRuntime.gamepadButtonHaptics,
                    gamepadStickDeadzone = liveRuntime.gamepadStickDeadzone,
                    gamepadLeftStickSensitivity = liveRuntime.gamepadLeftStickSensitivity,
                    gamepadRightStickSensitivity = liveRuntime.gamepadRightStickSensitivity,
                    gamepadBindingsByPad = liveRuntime.gamepadBindingsByPad,
                    pressureModifierAmount = liveRuntime.pressureModifierAmount,
                    autoSaveOnExit = liveRuntime.autoSaveOnExit,
                    autoLoadOnStart = liveRuntime.autoLoadOnStart,
                    targetFps = liveRuntime.targetFps,
                    ntscFramerate = liveRuntime.ntscFramerate,
                    palFramerate = liveRuntime.palFramerate,
                )
                val overlayState = runtimeState.withOverlayLayoutSnapshot(overlaySnapshot)
                _uiState.value = currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
                syncNativePerformanceOverlayState(_uiState.value)
                syncGamepadRuntimeSettings(_uiState.value)
                if (_uiState.value.gameSettingsProfileActive) {
                    val state = _uiState.value
                    GamepadManager.applyPerGameOverrides(
                        bindingsByPad = state.gamepadBindingsByPad,
                        deadzone = state.gamepadStickDeadzone,
                        leftSensitivity = state.gamepadLeftStickSensitivity,
                        rightSensitivity = state.gamepadRightStickSensitivity
                    )
                }
                updateCrashContext(
                    launchState = "starting",
                    launchPath = path
                )
            }

            if (hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var vmReadyWaitFrames = 0
                    while (vmReadyWaitFrames < 60 && isActive) {
                        try {
                            if (EmulatorBridge.hasValidVm()) break
                        } catch (_: Exception) { }
                        delay(250.milliseconds)
                        vmReadyWaitFrames++
                    }
                    if (!isActive) return@launch

                    if (!EmulatorBridge.hasValidVm()) {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = null,
                            toastMessage = "load_failed"
                        )
                        delay(2500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.value = _uiState.value.copy(toastMessage = null)
                        }
                        return@launch
                    }

                    delay(500.milliseconds)
                    val loaded = EmulatorBridge.loadState(normalizedSlotToLoad)
                    _uiState.value = _uiState.value.copy(
                        isRunning = true,
                        isPaused = false,
                        statusMessage = if (loaded) "status_running" else null,
                        toastMessage = if (loaded) null else "load_failed"
                    )
                    refreshSaveStateMetadata()
                    delay(2000.milliseconds)
                    if (_uiState.value.statusMessage == "status_running") {
                        _uiState.value = _uiState.value.copy(statusMessage = null)
                    }
                    if (_uiState.value.toastMessage == "load_failed") {
                        delay(500.milliseconds)
                        if (_uiState.value.toastMessage == "load_failed") {
                            _uiState.value = _uiState.value.copy(toastMessage = null)
                        }
                    }
                }
            }

            val pathToLaunch = finalLaunchPath ?: return@launch

            if (cancelPendingStart) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isStarting = false,
                    statusMessage = null
                )
                return@launch
            }

            if (!hasPendingStateLoad) {
                viewModelScope.launch(Dispatchers.IO) {
                    var waitFrames = 0
                    while (waitFrames < 60 && isActive) {
                        if (EmulatorBridge.hasValidVm()) {
                            if (tryAutoLoadOnStart()) {
                                break
                            }
                            _uiState.value = _uiState.value.copy(statusMessage = "status_running")
                            delay(2000.milliseconds)
                            if (_uiState.value.statusMessage == "status_running") {
                                _uiState.value = _uiState.value.copy(statusMessage = null)
                            }
                            break
                        }
                        delay(250.milliseconds)
                        waitFrames++
                    }
                }
            }

            val started = try {
                EmulatorBridge.startEmulation(
                    pathToLaunch,
                    saveStateIdentityPath = currentGamePath,
                    bootSmokeProbe = bootSmokeProbe,
                    allowBiosBoot = bootToBios
                )
            } catch (error: Exception) {
                Log.e(TAG, "EmulatorBridge.startEmulation failed", error)
                false
            }
            Log.i(TAG, "EmulatorBridge.startEmulation returned $started path=$pathToLaunch")
            if (started) {
                RetroAchievementsRepository.get(getApplication()).onGameStarted(pathToLaunch)
                syncCheatsForCurrentGame()
                // Per-game core options win over the global store.
                pendingPerGameCoreOptions.forEach { (coreKey, coreValue) ->
                    NativeApp.applyCoreOption(coreKey, coreValue)
                }
                syncPadAnalogModeForLaunch()
            }
            if (started && gsDumpFrames != null && gsDumpFrames > 0) {
                val delayMs = gsDumpDelayMs?.coerceAtLeast(0) ?: 0
                viewModelScope.launch(Dispatchers.IO) {
                    delay(delayMs.milliseconds)
                    if (EmulatorBridge.hasValidVm()) {
                        Log.i(TAG, "Queueing GS dump frames=$gsDumpFrames delayMs=$delayMs")
                        NativeApp.queueGsDump(gsDumpFrames)
                    }
                }
            }
            updateCrashContext(
                launchState = if (started) "running" else "launch_failed",
                launchPath = path
            )

            if (!started &&
                !_uiState.value.isPaused &&
                !cancelPendingStart &&
                !isShuttingDown
            ) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    statusMessage = null,
                    toastMessage = "launch_failed"
                )
                delay(2500.milliseconds)
                _uiState.value = _uiState.value.copy(toastMessage = null)
            }
        }
    }

    fun toggleJitProfiler() {
        val state = _uiState.value
        val nextState = !state.isJitProfilerActive
        _uiState.value = state.copy(isJitProfilerActive = nextState)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (nextState) {
                    EmulatorBridge.startJitProfiler()
                } else {
                    EmulatorBridge.stopJitProfiler()
                }
            } catch (_: Exception) {}
        }
    }

    fun toggleHangTrace() {
        val state = _uiState.value
        val nextState = !state.isHangTraceActive
        _uiState.value = state.copy(isHangTraceActive = nextState)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (nextState) {
                    EmulatorBridge.startHangTrace()
                } else {
                    EmulatorBridge.stopHangTrace()
                }
            } catch (_: Exception) {}
        }
    }

    fun togglePause() {
        val state = _uiState.value
        if (state.showMenu) {
            closeMenu()
            return
        }

        val isPaused = state.isPaused
        pausedForBackground = false
        _uiState.value = _uiState.value.copy(
            isPaused = !isPaused,
            showMenu = if (isPaused) false else _uiState.value.showMenu
        )
        DiscordIntegration.setPaused(!isPaused)
        updateCrashContext(launchState = if (!isPaused) "paused" else "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isPaused) {
                    EmulatorBridge.resume()
                } else {
                    EmulatorBridge.pause()
                }
            } catch (_: Exception) { }
        }
    }

    fun toggleMenu() {
        val showMenu = !_uiState.value.showMenu
        if (showMenu) {
            pausedForBackground = false
            EmulatorBridge.resetKeyStatus()
            refreshSaveStateMetadata()
            _uiState.value = _uiState.value.copy(showMenu = true, isPaused = true)
            DiscordIntegration.setPaused(true)
            updateCrashContext(launchState = "paused")
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    EmulatorBridge.pause()
                } catch (_: Exception) { }
            }
        } else {
            closeMenu()
        }
    }

    fun swapDisc(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val displayName = DocumentPathResolver.getDisplayName(context, uri.toString())
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.statSize != 0L
                } ?: false
            }.getOrDefault(false)

            if (!SetupValidator.isSupportedDiscImageName(displayName) || !readable) {
                _uiState.value = _uiState.value.copy(toastMessage = "disc_swap_invalid")
                delay(2500.milliseconds)
                if (_uiState.value.toastMessage == "disc_swap_invalid") {
                    _uiState.value = _uiState.value.copy(toastMessage = null)
                }
                return@launch
            }

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "swapping_disc"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown || !_uiState.value.isRunning) {
                    false
                } else {
                    EmulatorBridge.changeDisc(uri.toString())
                }
            }

            // VMManager restores the old image when opening the selected image fails.
            // Either way the tray cycle must continue, so close the menu and resume.
            runCatching { EmulatorBridge.resume() }
            pausedForBackground = false
            _uiState.value = _uiState.value.copy(
                isPaused = false,
                showMenu = false,
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "disc_swap_success" else "disc_swap_failed"
            )
            DiscordIntegration.setPaused(false)
            updateCrashContext(launchState = "running")
            delay(3000.milliseconds)
            val expectedToast = if (success) "disc_swap_success" else "disc_swap_failed"
            if (_uiState.value.toastMessage == expectedToast) {
                _uiState.value = _uiState.value.copy(toastMessage = null)
            }
        }
    }

    private fun closeMenu() {
        pausedForBackground = false
        _uiState.value = _uiState.value.copy(showMenu = false, isPaused = false)
        DiscordIntegration.setPaused(false)
        updateCrashContext(launchState = "running")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                EmulatorBridge.resume()
            } catch (_: Exception) { }
        }
    }

    fun toggleControlsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.controlsVisible
            preferences.setOverlayShow(newValue)
            _uiState.value = _uiState.value.copy(controlsVisible = newValue)
        }
    }

    fun saveCurrentGameSettingsProfile() {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value)
        }
    }

    fun resetCurrentGameSettingsProfile() {
        viewModelScope.launch {
            resetCurrentGameProfile()
        }
    }

    /**
     * Applies a SwanStation core option while a game session is running.
     *
     * Core options edited in-game belong to the running game, so they are stored
     * in that game's profile instead of the global core-option store. A change
     * made while the core is live (BIOS only) still persists globally.
     */
    fun setCoreOption(key: String, value: String) {
        viewModelScope.launch {
            val gameKey = activePerGameKey()
            if (gameKey == null) {
                NativeApp.setCoreOption(key, value)
                return@launch
            }
            NativeApp.applyCoreOption(key, value)
            val existing = perGameSettingsRepository.get(gameKey)
            val coreOptions = (existing?.coreOptions ?: emptyMap()) + (key to value)
            val profile = existing?.copy(coreOptions = coreOptions)
                ?: PerGameSettings(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(_uiState.value),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    coreOptions = coreOptions,
                    providedKeys = setOf("coreOptions")
                )
            perGameSettingsRepository.save(profile)
            pendingPerGameCoreOptions =
                coreOptions.filterKeys { !FlycastCoreOptions.isManagedKey(it) }
            _uiState.value = _uiState.value.copy(
                gameSettingsProfileActive = true,
                perGameCoreOptions = pendingPerGameCoreOptions
            )
        }
    }

    fun setOverlayScale(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayScale(value)
            _uiState.value = _uiState.value.copy(overlayScale = value.coerceIn(50, 150))
        }
    }

    fun setOverlayOpacity(value: Int) {
        viewModelScope.launch {
            preferences.setOverlayOpacity(value)
            _uiState.value = _uiState.value.copy(
                overlayOpacity = value.coerceIn(
                    AppPreferences.OVERLAY_OPACITY_MIN,
                    AppPreferences.OVERLAY_OPACITY_MAX
                )
            )
        }
    }

    fun setHideOverlayOnGamepad(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHideOverlayOnGamepad(enabled)
            _uiState.value = _uiState.value.copy(hideOverlayOnGamepad = enabled)
        }
    }

    fun setCompactControls(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setCompactControls(enabled)
            _uiState.value = _uiState.value.copy(compactControls = enabled)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setKeepScreenOn(enabled)
            _uiState.value = _uiState.value.copy(keepScreenOn = enabled)
        }
    }

    fun setStickScale(value: Int) {
        viewModelScope.launch {
            val scaledValue = value.coerceIn(
                AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                AppPreferences.OVERLAY_CONTROL_SCALE_MAX
            )
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            listOf("left_stick", "right_stick").forEach { id ->
                val existing = updatedLayouts[id]
                if (existing != null) {
                    updatedLayouts[id] = existing.copy(scale = scaledValue)
                }
            }
            persistTouchControlsLayout(
                current.copy(
                    stickScale = scaledValue,
                    controlLayouts = updatedLayouts
                )
            )
        }
    }

    fun setLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setLeftStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(leftStickSensitivity = normalized)
        }
    }

    fun setRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setRightStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(rightStickSensitivity = normalized)
        }
    }

    fun setInvertLeftStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStick(enabled)
            _uiState.value = _uiState.value.copy(invertLeftStick = enabled)
        }
    }

    fun setInvertRightStick(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStick(enabled)
            _uiState.value = _uiState.value.copy(invertRightStick = enabled)
        }
    }

    fun setInvertLeftStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertLeftStickHorizontal(enabled)
            _uiState.value = _uiState.value.copy(invertLeftStickHorizontal = enabled)
        }
    }

    fun setInvertRightStickHorizontal(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setInvertRightStickHorizontal(enabled)
            _uiState.value = _uiState.value.copy(invertRightStickHorizontal = enabled)
        }
    }

    fun setGamepadStickDeadzone(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(0, 35)
            preferences.setGamepadStickDeadzone(normalized)
            _uiState.value = _uiState.value.copy(gamepadStickDeadzone = normalized)
        }
    }

    fun setGamepadLeftStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadLeftStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(gamepadLeftStickSensitivity = normalized)
        }
    }

    fun setGamepadRightStickSensitivity(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(50, 200)
            preferences.setGamepadRightStickSensitivity(normalized)
            _uiState.value = _uiState.value.copy(gamepadRightStickSensitivity = normalized)
        }
    }

    fun setGamepadRightStickUpToR2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadRightStickUpToR2 = enabled)
            persistRuntimeState(newState) {
                preferences.setGamepadRightStickUpToR2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadRightStickDownToL2(enabled: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadRightStickDownToL2 = enabled)
            persistRuntimeState(newState) {
                preferences.setGamepadRightStickDownToL2(enabled)
            }
            syncGamepadRightStickTriggerMapping(newState)
        }
    }

    fun setGamepadBindingsByPad(bindingsByPad: Map<Int, Map<String, Int>>) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(gamepadBindingsByPad = bindingsByPad)
            _uiState.value = newState
            GamepadManager.applyPerGameOverrides(
                bindingsByPad = bindingsByPad,
                deadzone = null,
                leftSensitivity = null,
                rightSensitivity = null
            )
        }
    }

    fun toggleLeftInputMode() {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val leftStickLayout = updatedLayouts["left_stick"] ?: defaults["left_stick"] ?: OverlayControlLayout(scale = current.stickScale)
            val showingStick = leftStickLayout.visible

            // Keep the DualShock: hiding the touch stick only stops stick input,
            // it must not demote the port to a digital pad (which kills rumble).
            NativeApp.setPadAnalogMode(0, true)

            updatedLayouts["left_stick"] = leftStickLayout.copy(visible = !showingStick)
            listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right").forEach { id ->
                val currentLayout = updatedLayouts[id] ?: defaults[id] ?: OverlayControlLayout()
                updatedLayouts[id] = currentLayout.copy(visible = showingStick)
            }

            persistTouchControlsLayout(
                current.copy(
                    controlLayouts = updatedLayouts,
                    dpadOffset = current.lstickOffset,
                    lstickOffset = current.dpadOffset
                )
            )
        }
    }

    fun updateTouchControlOffset(controlId: String, offset: Pair<Float, Float>) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(offset = offset)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOffsets(offsets: Map<String, Pair<Float, Float>>) {
        if (offsets.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            offsets.forEach { (controlId, offset) ->
                val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
                updatedLayouts[controlId] = control.copy(offset = offset)
            }
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlScale(controlId: String, scale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                scale = scale.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_SCALE_MIN,
                    AppPreferences.OVERLAY_CONTROL_SCALE_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlWidthScale(controlId: String, widthScale: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(widthScale = widthScale.coerceIn(100, 240))
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun updateTouchControlOpacity(controlId: String, opacity: Int) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(
                opacity = opacity.coerceIn(
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MIN,
                    AppPreferences.OVERLAY_CONTROL_OPACITY_MAX
                )
            )
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchControlVisible(controlId: String, visible: Boolean) {
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(visible = visible)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun setTouchStickSurfaceMode(controlId: String, enabled: Boolean) {
        if (controlId != "left_stick" && controlId != "right_stick") return
        viewModelScope.launch {
            val current = _uiState.value
            val updatedLayouts = current.controlLayouts.toMutableMap()
            val defaults = AppPreferences.defaultOverlayControlLayouts(current.stickScale)
            val control = updatedLayouts[controlId] ?: defaults[controlId] ?: OverlayControlLayout()
            updatedLayouts[controlId] = control.copy(surfaceOnly = enabled)
            persistTouchControlsLayout(current.copy(controlLayouts = updatedLayouts))
        }
    }

    fun resetTouchControlsLayout() {
        viewModelScope.launch {
            resetTouchControlsLayoutForCurrentScope()
        }
    }
    fun toggleFpsVisibility() {
        viewModelScope.launch {
            val newValue = !_uiState.value.showFps
            persistRuntimeState(_uiState.value.copy(showFps = newValue)) {
                preferences.setShowFps(newValue)
            }
        }
    }

    fun setFpsOverlayMode(mode: Int) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(fpsOverlayMode = mode)) {
                preferences.setFpsOverlayMode(mode)
            }
        }
    }

    fun setFpsOverlayCorner(corner: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayCorner(corner)
            _uiState.value = _uiState.value.copy(fpsOverlayCorner = corner)
        }
    }

    fun setRenderer(renderer: Int) {
        viewModelScope.launch {
            if (!EmulatorBridge.setRenderer(renderer)) return@launch
            // A renderer switch recreates the core session, which drops the
            // per-game core-option overrides applied after the last launch.
            pendingPerGameCoreOptions.forEach { (key, value) -> NativeApp.applyCoreOption(key, value) }
            persistRuntimeState(_uiState.value.copy(renderer = renderer)) {
                preferences.setRenderer(renderer)
            }
            updateCrashContext()
            // The session was rebooted on the new backend; close the menu so the
            // restarted game is visible immediately.
            closeMenu()
        }
    }

    fun setUpscale(upscale: Float) {
        viewModelScope.launch {
            val normalizedUpscale = normalizeUpscale(upscale)
            persistRuntimeState(_uiState.value.copy(upscale = normalizedUpscale)) {
                preferences.setUpscaleMultiplier(normalizedUpscale)
            }
            EmulatorBridge.setUpscaleMultiplier(normalizedUpscale)
            updateCrashContext()
        }
    }

    fun setAspectRatio(value: Int) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(aspectRatio = value)) {
                preferences.setAspectRatio(value)
            }
            EmulatorBridge.setAspectRatio(value)
            updateCrashContext()
        }
    }

    fun setLocalMultiplayerMode(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                AppPreferences.LOCAL_MULTIPLAYER_OFF,
                AppPreferences.LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED
            )
            persistRuntimeState(_uiState.value.copy(localMultiplayerMode = normalized)) {
                preferences.setLocalMultiplayerMode(normalized)
            }
            EmulatorBridge.setLocalMultiplayerMode(normalized)
            updateCrashContext()
        }
    }

    fun setDisplayCrop(value: DisplayCrop) {
        viewModelScope.launch {
            val crop = value.sanitized()
            persistRuntimeState(_uiState.value.copy(displayCrop = crop)) {
                preferences.setDisplayCrop(crop)
            }
            EmulatorBridge.setDisplayCrop(crop)
            updateCrashContext()
        }
    }

    fun setFpsOverlayScale(scale: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayScale(scale)
        }
    }

    fun setFpsOverlayMetrics(metrics: Int) {
        viewModelScope.launch {
            preferences.setFpsOverlayMetrics(metrics)
        }
    }

    fun setCheatEnabled(blockId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id == blockId) block.copy(enabled = enabled) else block
            }
            // Only this ID is written, so toggles made in the cheat manager
            // (which share the same state file) are preserved.
            cheatRepository.setBlockEnabled(gameKey, blockId, enabled)
            persistRuntimeState(_uiState.value.copy(
                availableCheats = updatedBlocks
            )) {
            }
            syncCheatsForCurrentGame(gameKey)
        }
    }

    /** Enables or disables every cheat in a group (category) at once. */
    fun setCheatGroupEnabled(blockIds: Collection<String>, enabled: Boolean) {
        if (blockIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value
            val gameKey = currentState.cheatsGameKey ?: return@launch
            val idSet = blockIds.toSet()
            val updatedBlocks = currentState.availableCheats.map { block ->
                if (block.id in idSet) block.copy(enabled = enabled) else block
            }
            cheatRepository.setBlocksEnabled(gameKey, idSet, enabled)
            persistRuntimeState(_uiState.value.copy(
                availableCheats = updatedBlocks
            )) {
            }
            syncCheatsForCurrentGame(gameKey)
        }
    }

    /**
     * Re-reads the installed cheats from storage so changes made in the cheat
     * manager (or a freshly installed pack) are reflected in the in-game menu.
     */
    fun refreshAvailableCheats() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = currentGamePath
            val metadata = path?.takeIf { it.isNotBlank() }?.let { EmulatorBridge.getGameMetadata(it) }
            val serial = currentGameSerial.takeIf { it.isNotBlank() }
                ?: metadata?.serial?.takeIf { it.isNotBlank() }
                .orEmpty()
            val crc = currentGameCrc.takeIf { it.isNotBlank() }
                ?: metadata?.serialWithCrc.extractCrc()
                .orEmpty()
            val keys = linkedSetOf<String>()
            metadata?.let { keys.addAll(cheatLookupKeys(it)) }
            if (serial.isNotBlank() && crc.isNotBlank()) keys.add("${serial}_$crc")
            if (crc.isNotBlank()) keys.add(crc)
            if (serial.isNotBlank()) keys.add(serial)
            val config = cheatRepository.getGameConfig(
                gameKeys = keys.toList(),
                serial = serial,
                crc = crc.takeIf { it.isNotBlank() }
            ) ?: return@launch
            _uiState.value = _uiState.value.copy(
                cheatsGameKey = config.gameKey,
                availableCheats = config.blocks
            )
        }
    }

    fun toggleFrameLimit() {
        setFrameLimitEnabled(!_uiState.value.frameLimitEnabled)
    }

    fun setFrameLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            persistRuntimeState(_uiState.value.copy(frameLimitEnabled = enabled)) {
                preferences.setFrameLimitEnabled(enabled)
            }
            EmulatorBridge.setFrameLimitEnabled(enabled)
            updateCrashContext()
        }
    }

    fun setTargetFps(value: Int) {
        viewModelScope.launch {
            val clamped = if (value <= 0) 0 else value.coerceIn(20, 120)
            persistRuntimeState(_uiState.value.copy(targetFps = clamped)) {
                preferences.setTargetFps(clamped)
            }
            EmulatorBridge.setTargetFps(clamped, _uiState.value.ntscFramerate, _uiState.value.palFramerate)
            updateCrashContext()
        }
    }

    fun setNtscFramerate(value: Float) {
        viewModelScope.launch {
            val sanitized = if (value.isFinite()) {
                value.coerceIn(AppPreferences.MIN_REGION_FRAMERATE, AppPreferences.MAX_REGION_FRAMERATE)
            } else {
                AppPreferences.DEFAULT_NTSC_FRAMERATE
            }
            persistRuntimeState(_uiState.value.copy(ntscFramerate = sanitized)) {
                preferences.setNtscFramerate(sanitized)
            }
            EmulatorBridge.setTargetFps(
                _uiState.value.targetFps,
                sanitized,
                _uiState.value.palFramerate
            )
            updateCrashContext()
        }
    }

    fun setPalFramerate(value: Float) {
        viewModelScope.launch {
            val sanitized = if (value.isFinite()) {
                value.coerceIn(AppPreferences.MIN_REGION_FRAMERATE, AppPreferences.MAX_REGION_FRAMERATE)
            } else {
                AppPreferences.DEFAULT_PAL_FRAMERATE
            }
            persistRuntimeState(_uiState.value.copy(palFramerate = sanitized)) {
                preferences.setPalFramerate(sanitized)
            }
            EmulatorBridge.setTargetFps(
                _uiState.value.targetFps,
                _uiState.value.ntscFramerate,
                sanitized
            )
            updateCrashContext()
        }
    }

    fun setAudioVolume(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(AudioDefaults.VOLUME_MIN, AudioDefaults.VOLUME_MAX)
            val newState = _uiState.value.copy(audioVolume = clamped)
            persistRuntimeState(newState) {
                preferences.setAudioVolume(clamped)
            }
            EmulatorBridge.setSetting("EmuCoreH/Audio", "Volume", "int", clamped.toString())
            updateCrashContext()
        }
    }

    fun setAudioMuted(muted: Boolean) {
        viewModelScope.launch {
            val newState = _uiState.value.copy(audioMuted = muted)
            persistRuntimeState(newState) {
                preferences.setAudioMuted(muted)
            }
            EmulatorBridge.setSetting("EmuCoreH/Audio", "Mute", "bool", muted.toString())
            updateCrashContext()
        }
    }

    private fun applyOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot) {
        val overlayState = _uiState.value.withOverlayLayoutSnapshot(snapshot)
        _uiState.value = currentTouchControlsLayoutProfile?.let { overlayState.withTouchControlsLayout(it) } ?: overlayState
    }

    private fun EmulationUiState.withOverlayLayoutSnapshot(snapshot: OverlayLayoutSnapshot): EmulationUiState {
        return copy(
            overlayScale = snapshot.overlayScale,
            overlayOpacity = snapshot.overlayOpacity,
            hideOverlayOnGamepad = snapshot.hideOverlayOnGamepad,
            dpadOffset = snapshot.dpadOffset,
            lstickOffset = snapshot.lstickOffset,
            rstickOffset = snapshot.rstickOffset,
            actionOffset = snapshot.actionOffset,
            lbtnOffset = snapshot.lbtnOffset,
            rbtnOffset = snapshot.rbtnOffset,
            centerOffset = snapshot.centerOffset,
            stickScale = snapshot.stickScale,
            leftStickSensitivity = snapshot.leftStickSensitivity,
            rightStickSensitivity = snapshot.rightStickSensitivity,
            invertLeftStick = snapshot.invertLeftStick,
            invertRightStick = snapshot.invertRightStick,
            invertLeftStickHorizontal = snapshot.invertLeftStickHorizontal,
            invertRightStickHorizontal = snapshot.invertRightStickHorizontal,
            stickSurfaceMode = snapshot.stickSurfaceMode,
            controlLayouts = snapshot.controlLayouts
        )
    }

    private fun EmulationUiState.withTouchControlsLayout(profile: TouchControlsLayoutProfile): EmulationUiState {
        return copy(
            dpadOffset = profile.dpadOffset,
            lstickOffset = profile.lstickOffset,
            rstickOffset = profile.rstickOffset,
            actionOffset = profile.actionOffset,
            lbtnOffset = profile.lbtnOffset,
            rbtnOffset = profile.rbtnOffset,
            centerOffset = profile.centerOffset,
            stickScale = profile.stickScale,
            controlLayouts = profile.controlLayouts
        )
    }

    private fun EmulationUiState.toTouchControlsLayoutProfile(): TouchControlsLayoutProfile {
        return TouchControlsLayoutProfile(
            dpadOffset = dpadOffset,
            lstickOffset = lstickOffset,
            rstickOffset = rstickOffset,
            actionOffset = actionOffset,
            lbtnOffset = lbtnOffset,
            rbtnOffset = rbtnOffset,
            centerOffset = centerOffset,
            stickScale = stickScale,
            controlLayouts = controlLayouts
        )
    }

    private fun currentGameSubtitle(): String = buildList {
        currentGameSerial.takeIf { it.isNotBlank() }?.let(::add)
        currentGameRegionLabel.takeIf { it.isNotBlank() }?.let(::add)
        currentGameCrc.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("  /  ")

    private fun formatDiscSerial(serial: String): String {
        val trimmed = serial.uppercase().trim()
        // Dreamcast product codes are already hyphenated (MK-51035, T-17702D-50).
        if (trimmed.contains('-')) return trimmed
        val compact = trimmed.replace(Regex("[^A-Z0-9]"), "")
        return if (compact.length >= 8) "${compact.take(4)}-${compact.substring(4)}" else serial
    }

    private fun resolveRegionLabel(serial: String, path: String): String {
        return serialRegionLabel(serial) ?: filenameRegionLabel(path).orEmpty()
    }

    private fun serialRegionLabel(serial: String): String? {
        val prefix = serial.uppercase().replace(Regex("[^A-Z0-9]"), "").take(4)
        if (prefix.length < 4) return null
        return when (prefix) {
            "UCUS", "ULUS", "NPUH", "UCJS", "ULJS", "ULJM", "NPJH",
            "UCAS", "ULAS", "NPUG" -> "NTSC"
            "UCES", "ULES", "NPEH", "NPEG" -> "PAL"
            else -> null
        }
    }

    private fun filenameRegionLabel(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = if (path.startsWith("content://")) {
            DocumentPathResolver.getDisplayName(getApplication(), path)
        } else {
            File(path).name
        }
        val lower = name.lowercase()
        return when {
            "usa" in lower || "(u)" in lower || "ntsc-u" in lower || "us " in lower -> "NTSC"
            "japan" in lower || "jpn" in lower || "(j)" in lower || "ntsc-j" in lower -> "NTSC"
            "europe" in lower || "eur" in lower || "(e)" in lower || "pal" in lower -> "PAL"
            else -> null
        }
    }

    private fun activePerGameKey(): String? = currentGamePath?.takeIf { it.isNotBlank() }

    private fun resolvePerGameTitle(state: EmulationUiState): String {
        return state.currentGameTitle
            .takeIf { it.isNotBlank() && it != BIOS_SESSION_TITLE }
            ?: currentGameTitle.takeIf { it.isNotBlank() && it != BIOS_SESSION_TITLE }
            ?: activePerGameKey()?.let { DocumentPathResolver.getDisplayName(getApplication(), it).substringBeforeLast('.') }
            ?: "Unknown Game"
    }

    private suspend fun persistGlobalTouchControlsLayout(state: EmulationUiState) {
        preferences.saveTouchControlsLayout(state.toTouchControlsLayoutProfile())
    }

    private suspend fun persistTouchControlsLayout(updatedState: EmulationUiState): EmulationUiState {
        val gameKey = activePerGameKey()
        return if (gameKey != null) {
            val layout = updatedState.toTouchControlsLayoutProfile()
            val existing = perGameSettingsRepository.get(gameKey)
            perGameSettingsRepository.save(
                existing.withTouchControlsLayout(
                    gameKey = gameKey,
                    gameTitle = resolvePerGameTitle(updatedState),
                    gameSerial = currentGameSerial.takeIf { it.isNotBlank() },
                    layout = layout
                )
            )
            currentTouchControlsLayoutProfile = layout
            val finalState = updatedState.copy(gameSettingsProfileActive = true)
            _uiState.value = finalState
            finalState
        } else {
            persistGlobalTouchControlsLayout(updatedState)
            currentTouchControlsLayoutProfile = null
            _uiState.value = updatedState
            updatedState
        }
    }

    private suspend fun resetTouchControlsLayoutForCurrentScope() {
        val gameKey = activePerGameKey()
        if (gameKey == null) {
            currentTouchControlsLayoutProfile = null
            preferences.resetControlsLayout()
            _uiState.value = _uiState.value.withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
            return
        }

        val existing = perGameSettingsRepository.get(gameKey)
        if (existing != null) {
            val updated = existing.withoutTouchControlsLayout()
            if (updated == null) {
                perGameSettingsRepository.delete(gameKey)
            } else {
                perGameSettingsRepository.save(updated)
            }
        }

        currentTouchControlsLayoutProfile = null
        val profileStillActive = perGameSettingsRepository.get(gameKey) != null
        _uiState.value = _uiState.value
            .withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
            .copy(gameSettingsProfileActive = profileStillActive)
    }

    private suspend fun persistRuntimeState(
        updatedState: EmulationUiState,
        persistGlobal: suspend () -> Unit = {}
    ): EmulationUiState {
        val gameKey = activePerGameKey()
        return if (gameKey != null) {
            val existingProfile = perGameSettingsRepository.get(gameKey)
            val touchControlsLayout = existingProfile?.touchControlsLayout ?: currentTouchControlsLayoutProfile
            val runtimeProfile = updatedState.toPerGameSettings(
                gameKey = gameKey,
                gameTitle = resolvePerGameTitle(updatedState),
                gameSerial = currentGameSerial.takeIf { it.isNotBlank() }
            )
            val visualStyleOverride = existingProfile?.touchControlVisualStyle
                ?: runtimeProfile.touchControlVisualStyle
            val pressEffectOverride = existingProfile?.touchControlPressEffect
                ?: runtimeProfile.touchControlPressEffect
            val driverOverrideKeys = when {
                existingProfile == null -> emptySet()
                existingProfile.providedKeys == null -> PER_GAME_GPU_DRIVER_KEYS
                else -> existingProfile.providedKeys.intersect(PER_GAME_GPU_DRIVER_KEYS)
            }
            val visualOverrideKeys = buildSet {
                if (visualStyleOverride != null) add("touchControlVisualStyle")
                if (pressEffectOverride != null) add("touchControlPressEffect")
            }
            val audioOverrideKeys = existingProfile?.let { profile ->
                if (profile.providedKeys == null) {
                    PER_GAME_AUDIO_KEYS
                } else {
                    profile.providedKeys.intersect(PER_GAME_AUDIO_KEYS)
                }
            }.orEmpty()
            val providedKeys = when {
                runtimeProfile.providedKeys == null -> null
                touchControlsLayout == null ->
                    runtimeProfile.providedKeys + visualOverrideKeys + driverOverrideKeys + audioOverrideKeys
                else -> runtimeProfile.providedKeys + visualOverrideKeys + driverOverrideKeys +
                    audioOverrideKeys + PER_GAME_TOUCH_CONTROLS_LAYOUT_KEY
            }
            perGameSettingsRepository.save(
                runtimeProfile.copy(
                    touchControlsLayout = touchControlsLayout,
                    touchControlVisualStyle = visualStyleOverride,
                    touchControlPressEffect = pressEffectOverride,
                    coreOptions = existingProfile?.coreOptions ?: runtimeProfile.coreOptions,
                    gpuDriverType = existingProfile?.gpuDriverType ?: runtimeProfile.gpuDriverType,
                    customDriverPath = existingProfile?.customDriverPath ?: runtimeProfile.customDriverPath,
                    mediatekAngleOpenGl = existingProfile?.mediatekAngleOpenGl ?: runtimeProfile.mediatekAngleOpenGl,
                    shaderChainOverrideEnabled = existingProfile?.shaderChainOverrideEnabled,
                    shaderChainPreset = existingProfile?.shaderChainPreset.orEmpty(),
                    audioVolume = if ("audioVolume" in audioOverrideKeys) {
                        existingProfile?.audioVolume ?: runtimeProfile.audioVolume
                    } else {
                        runtimeProfile.audioVolume
                    },
                    audioMuted = if ("audioMuted" in audioOverrideKeys) {
                        existingProfile?.audioMuted ?: runtimeProfile.audioMuted
                    } else {
                        runtimeProfile.audioMuted
                    },
                    audioOutputLatencyMs = if ("audioOutputLatencyMs" in audioOverrideKeys) {
                        existingProfile?.audioOutputLatencyMs ?: runtimeProfile.audioOutputLatencyMs
                    } else {
                        runtimeProfile.audioOutputLatencyMs
                    },
                    audioMinimalOutputLatency = if ("audioMinimalOutputLatency" in audioOverrideKeys) {
                        existingProfile?.audioMinimalOutputLatency ?: runtimeProfile.audioMinimalOutputLatency
                    } else {
                        runtimeProfile.audioMinimalOutputLatency
                    },
                    providedKeys = providedKeys
                )
            )
            val finalState = updatedState.copy(gameSettingsProfileActive = true)
            _uiState.value = finalState
            syncNativePerformanceOverlayState(finalState)
            finalState
        } else {
            persistGlobal()
            _uiState.value = updatedState
            syncNativePerformanceOverlayState(updatedState)
            updatedState
        }
    }

    private fun syncCurrentGameProfileMetadata() {
        val gameKey = activePerGameKey() ?: return
        val profile = perGameSettingsRepository.get(gameKey) ?: return
        val resolvedTitle = resolvePerGameTitle(_uiState.value)
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        if (profile.gameTitle != resolvedTitle || profile.gameSerial != serial) {
            perGameSettingsRepository.save(
                profile.copy(
                    gameTitle = resolvedTitle,
                    gameSerial = serial
                )
            )
        }
    }

    private fun resetCurrentGameProfile() {
        val gameKey = activePerGameKey() ?: return
        perGameSettingsRepository.delete(gameKey)
        currentTouchControlsLayoutProfile = null
        GamepadManager.clearPerGameOverrides()
        // Restore the global/core-default value of any option that was overridden
        // only by this game, so the live core matches the reset profile.
        pendingPerGameCoreOptions.keys.forEach { key ->
            NativeApp.getCoreOption(key)?.let { baseValue -> NativeApp.applyCoreOption(key, baseValue) }
        }
        pendingPerGameCoreOptions = emptyMap()
        _uiState.value = _uiState.value.copy(
            gameSettingsProfileActive = false,
            perGameCoreOptions = emptyMap(),
            gamepadBindingsByPad = emptyMap()
        )
        viewModelScope.launch {
            val settings = preferences.settingsSnapshot.first()
            _uiState.value = _uiState.value
                .withOverlayLayoutSnapshot(preferences.overlayLayoutSnapshot.first())
                .copy(
                    touchControlVisualStyle = settings.touchControlVisualStyle,
                    touchControlPressEffect = settings.touchControlPressEffect,
                    gameSettingsProfileActive = false,
                    perGameCoreOptions = emptyMap(),
                    gamepadStickDeadzone = settings.gamepadStickDeadzone,
                    gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
                    gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity
                )
        }
    }

    private suspend fun loadLaunchConfig(): EmulationLaunchConfig {
        val profile = activePerGameKey()?.let(perGameSettingsRepository::get)
        val settings = preferences.settingsSnapshot.first()
        val savedGpuDriverType = settings.gpuDriverType
        val savedCustomDriverPath = settings.customDriverPath
        // Custom driver support is gone with the CPU-rasterizer-only core; a
        // legacy custom path is only honored when the file still exists.
        val resolvedCustomDriverPath = savedCustomDriverPath
            ?.takeIf { savedGpuDriverType == 1 && File(it).isFile }
        val resolvedGpuDriverType = if (savedGpuDriverType == 1 && !resolvedCustomDriverPath.isNullOrBlank()) 1 else 0
        if (savedGpuDriverType == 1 && resolvedGpuDriverType == 1 && resolvedCustomDriverPath != savedCustomDriverPath) {
            preferences.setCustomDriverPath(resolvedCustomDriverPath)
        }
        val mergedConfig = EmulationLaunchConfig(
            biosPath = settings.biosPath,
            emulatorDataPath = settings.emulatorDataPath,
            memoryCardSlot1 = null,
            memoryCardSlot2 = null,
            renderer = settings.renderer,
            upscaleMultiplier = settings.upscaleMultiplier,
            gpuDriverType = resolvedGpuDriverType,
            customDriverPath = resolvedCustomDriverPath,
            gpuHardwareProfile = settings.gpuHardwareProfile,
            mediatekAngleOpenGl = settings.mediatekAngleOpenGl,
            aspectRatio = settings.aspectRatio,
            localMultiplayerMode = settings.localMultiplayerMode,
            displayCrop = settings.displayCrop,
            audioVolume = settings.audioVolume,
            audioFastForwardVolume = settings.audioFastForwardVolume,
            audioMuted = settings.audioMuted,
            audioOutputLatencyMs = settings.audioOutputLatencyMs,
            audioMinimalOutputLatency = settings.audioMinimalOutputLatency,
            frameLimitEnabled = settings.frameLimitEnabled,
            vSyncEnabled = settings.vSyncEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            targetFps = settings.targetFps,
            ntscFramerate = settings.ntscFramerate,
            palFramerate = settings.palFramerate,
            shaderChainEnabled = settings.shaderChainEnabled,
            shaderChainPreset = settings.shaderChainPreset,
            pressureModifierAmount = settings.pressureModifierAmount,
        ).applyProfile(profile)
        val mergedDriverPath = mergedConfig.customDriverPath?.takeIf { File(it).isFile }
        return mergedConfig.copy(
            gpuDriverType = if (mergedConfig.gpuDriverType == 1 && !mergedDriverPath.isNullOrBlank()) 1 else 0,
            customDriverPath = mergedDriverPath
        )
    }

    private suspend fun loadLiveRuntimeSnapshot(): LiveRuntimeSnapshot {
        val profile = activePerGameKey()?.let(perGameSettingsRepository::get)
        val settings = preferences.settingsSnapshot.first()
        return LiveRuntimeSnapshot(
            showFps = settings.showFps,
            fpsOverlayMode = settings.fpsOverlayMode,
            confirmSaveLoadActions = settings.confirmSaveLoadActions,
            backButtonExitsGame = settings.backButtonExitsGame,
            renderer = settings.renderer,
            upscale = settings.upscaleMultiplier,
            aspectRatio = settings.aspectRatio,
            localMultiplayerMode = settings.localMultiplayerMode,
            displayCrop = settings.displayCrop,
            frameLimitEnabled = settings.frameLimitEnabled,
            fastForwardSpeed = settings.fastForwardSpeed,
            racingMode = settings.racingMode,
            touchscreenRightStick = settings.touchscreenRightStick,
            touchscreenRightStickSensitivity = settings.touchscreenRightStickSensitivity,
            touchHaptics = settings.touchHaptics,
            touchHapticsPreset = settings.touchHapticsPreset,
            touchHapticsStrength = settings.touchHapticsStrength,
            touchControlVisualStyle = settings.touchControlVisualStyle,
            touchControlPressEffect = settings.touchControlPressEffect,
            gyroMode = settings.gyroMode,
            gyroSensitivity = settings.gyroSensitivity,
            gyroSmoothing = settings.gyroSmoothing,
            gyroInvertX = settings.gyroInvertX,
            gyroInvertY = settings.gyroInvertY,
            gamepadRightStickUpToR2 = settings.gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = settings.gamepadRightStickDownToL2,
            gamepadButtonHaptics = settings.gamepadButtonHaptics,
            gamepadStickDeadzone = settings.gamepadStickDeadzone,
            gamepadLeftStickSensitivity = settings.gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = settings.gamepadRightStickSensitivity,
            gamepadBindingsByPad = settings.gamepadBindingsByPad,
            pressureModifierAmount = settings.pressureModifierAmount,
            autoSaveOnExit = false,
            autoLoadOnStart = false,
            targetFps = settings.targetFps,
            ntscFramerate = settings.ntscFramerate,
            palFramerate = settings.palFramerate,
        ).applyProfile(profile)
    }

    private fun EmulationLaunchConfig.applyProfile(profile: PerGameSettings?): EmulationLaunchConfig {
        if (profile == null) return this
        val resolvedShaderChain = profile.resolveShaderChain(
            globalEnabled = shaderChainEnabled,
            globalPreset = shaderChainPreset
        )
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            renderer = pick("renderer", renderer) { renderer },
            gpuDriverType = pick("gpuDriverType", gpuDriverType) { gpuDriverType },
            customDriverPath = pick("customDriverPath", customDriverPath) { customDriverPath },
            mediatekAngleOpenGl = pick("mediatekAngleOpenGl", mediatekAngleOpenGl) { mediatekAngleOpenGl },
            upscaleMultiplier = pick("upscaleMultiplier", upscaleMultiplier) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            localMultiplayerMode = pick("localMultiplayerMode", localMultiplayerMode) { localMultiplayerMode },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            ntscFramerate = pick("ntscFramerate", ntscFramerate) { ntscFramerate },
            palFramerate = pick("palFramerate", palFramerate) { palFramerate },
            shaderChainEnabled = resolvedShaderChain.enabled,
            shaderChainPreset = resolvedShaderChain.preset,
            pressureModifierAmount = pick("pressureModifierAmount", pressureModifierAmount) { pressureModifierAmount },
            audioVolume = pick("audioVolume", audioVolume) { audioVolume },
            audioMuted = pick("audioMuted", audioMuted) { audioMuted },
            audioOutputLatencyMs = pick("audioOutputLatencyMs", audioOutputLatencyMs) { audioOutputLatencyMs },
            audioMinimalOutputLatency = pick("audioMinimalOutputLatency", audioMinimalOutputLatency) { audioMinimalOutputLatency },
        )
    }

    private fun LiveRuntimeSnapshot.applyProfile(profile: PerGameSettings?): LiveRuntimeSnapshot {
        if (profile == null) return this
        fun <T> pick(key: String, current: T, value: PerGameSettings.() -> T): T {
            val keys = profile.providedKeys
            return if (keys == null || key in keys) profile.value() else current
        }
        return copy(
            showFps = pick("showFps", showFps) { showFps },
            fpsOverlayMode = pick("fpsOverlayMode", fpsOverlayMode) { fpsOverlayMode },
            racingMode = pick("racingMode", racingMode) { racingMode },
            touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick) { touchscreenRightStick },
            touchscreenRightStickSensitivity = pick(
                "touchscreenRightStickSensitivity",
                touchscreenRightStickSensitivity
            ) { touchscreenRightStickSensitivity },
            touchHaptics = pick("touchHaptics", touchHaptics) { touchHaptics },
            touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset) { touchHapticsPreset },
            touchHapticsStrength = touchHapticsStrength,
            touchControlVisualStyle = profile.touchControlVisualStyle ?: touchControlVisualStyle,
            touchControlPressEffect = profile.touchControlPressEffect ?: touchControlPressEffect,
            gyroMode = pick("gyroMode", gyroMode) { gyroMode },
            gyroSensitivity = pick("gyroSensitivity", gyroSensitivity) { gyroSensitivity },
            gyroSmoothing = pick("gyroSmoothing", gyroSmoothing) { gyroSmoothing },
            gyroInvertX = pick("gyroInvertX", gyroInvertX) { gyroInvertX },
            gyroInvertY = pick("gyroInvertY", gyroInvertY) { gyroInvertY },
            gamepadRightStickUpToR2 = pick("gamepadRightStickUpToR2", gamepadRightStickUpToR2) { gamepadRightStickUpToR2 },
            gamepadRightStickDownToL2 = pick("gamepadRightStickDownToL2", gamepadRightStickDownToL2) { gamepadRightStickDownToL2 },
            gamepadButtonHaptics = pick("gamepadButtonHaptics", gamepadButtonHaptics) { gamepadButtonHaptics },
            gamepadStickDeadzone = pick("gamepadStickDeadzone", gamepadStickDeadzone) { gamepadStickDeadzone },
            gamepadLeftStickSensitivity = pick("gamepadLeftStickSensitivity", gamepadLeftStickSensitivity) { gamepadLeftStickSensitivity },
            gamepadRightStickSensitivity = pick("gamepadRightStickSensitivity", gamepadRightStickSensitivity) { gamepadRightStickSensitivity },
            gamepadBindingsByPad = if (profile.providedKeys == null || "gamepadBindingsByPad" in profile.providedKeys) profile.gamepadBindingsByPad else gamepadBindingsByPad,
            pressureModifierAmount = pick("pressureModifierAmount", pressureModifierAmount) { pressureModifierAmount },
            autoSaveOnExit = pick("autoSaveOnExit", autoSaveOnExit) { autoSaveOnExit },
            autoLoadOnStart = pick("autoLoadOnStart", autoLoadOnStart) { autoLoadOnStart },
            renderer = pick("renderer", renderer) { renderer },
            upscale = pick("upscaleMultiplier", upscale) { upscaleMultiplier },
            aspectRatio = pick("aspectRatio", aspectRatio) { aspectRatio },
            localMultiplayerMode = pick("localMultiplayerMode", localMultiplayerMode) { localMultiplayerMode },
            displayCrop = pick("displayCrop", displayCrop) { displayCrop },
            frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled) { frameLimitEnabled },
            targetFps = pick("targetFps", targetFps) { targetFps },
            ntscFramerate = pick("ntscFramerate", ntscFramerate) { ntscFramerate },
            palFramerate = pick("palFramerate", palFramerate) { palFramerate },
        )
    }

    private suspend fun EmulationUiState.toPerGameSettings(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?
    ): PerGameSettings {
        val settings = preferences.settingsSnapshot.first()
        val profile = buildPerGameSettingsProfile(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            settings = settings
        )
        val providedKeys = computeProvidedKeys(settings, profile)
        return profile.copy(providedKeys = providedKeys)
    }

    private fun EmulationUiState.buildPerGameSettingsProfile(
        gameKey: String,
        gameTitle: String,
        gameSerial: String?,
        settings: SettingsSnapshot
    ): PerGameSettings {
        return PerGameSettings(
            gameKey = gameKey,
            gameTitle = gameTitle,
            gameSerial = gameSerial,
            renderer = renderer,
            upscaleMultiplier = upscale,
            aspectRatio = aspectRatio,
            localMultiplayerMode = localMultiplayerMode,
            displayCrop = displayCrop,
            showFps = showFps,
            fpsOverlayMode = fpsOverlayMode,
            frameLimitEnabled = frameLimitEnabled,
            racingMode = racingMode,
            touchscreenRightStick = touchscreenRightStick,
            touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
            touchHaptics = touchHaptics,
            touchHapticsPreset = touchHapticsPreset,
            touchControlVisualStyle = touchControlVisualStyle.takeIf { it != settings.touchControlVisualStyle },
            touchControlPressEffect = touchControlPressEffect.takeIf { it != settings.touchControlPressEffect },
            gyroMode = gyroMode,
            gyroSensitivity = gyroSensitivity,
            gyroSmoothing = gyroSmoothing,
            gyroInvertX = gyroInvertX,
            gyroInvertY = gyroInvertY,
            gamepadRightStickUpToR2 = gamepadRightStickUpToR2,
            gamepadRightStickDownToL2 = gamepadRightStickDownToL2,
            gamepadButtonHaptics = gamepadButtonHaptics,
            gamepadStickDeadzone = gamepadStickDeadzone,
            gamepadLeftStickSensitivity = gamepadLeftStickSensitivity,
            gamepadRightStickSensitivity = gamepadRightStickSensitivity,
            gamepadBindingsByPad = gamepadBindingsByPad,
            pressureModifierAmount = pressureModifierAmount,
            autoSaveOnExit = autoSaveOnExit,
            autoLoadOnStart = autoLoadOnStart,
            targetFps = targetFps,
            ntscFramerate = ntscFramerate,
            palFramerate = palFramerate,
        )
    }

    private fun EmulationUiState.computeProvidedKeys(
        settings: SettingsSnapshot,
        profile: PerGameSettings
    ): Set<String> = buildSet {
        if (renderer != settings.renderer) add("renderer")
        if (upscale != settings.upscaleMultiplier) add("upscaleMultiplier")
        if (aspectRatio != settings.aspectRatio) add("aspectRatio")
        if (localMultiplayerMode != settings.localMultiplayerMode) add("localMultiplayerMode")
        if (displayCrop != settings.displayCrop) add("displayCrop")
        if (showFps != settings.showFps) add("showFps")
        if (fpsOverlayMode != settings.fpsOverlayMode) add("fpsOverlayMode")
        if (frameLimitEnabled != settings.frameLimitEnabled) add("frameLimitEnabled")
        if (racingMode != settings.racingMode) add("racingMode")
        if (touchscreenRightStick != settings.touchscreenRightStick) add("touchscreenRightStick")
        if (touchscreenRightStickSensitivity != settings.touchscreenRightStickSensitivity) {
            add("touchscreenRightStickSensitivity")
        }
        if (touchHaptics != settings.touchHaptics) add("touchHaptics")
        if (touchHapticsPreset != settings.touchHapticsPreset) add("touchHapticsPreset")
        if (profile.touchControlVisualStyle != null) add("touchControlVisualStyle")
        if (profile.touchControlPressEffect != null) add("touchControlPressEffect")
        if (gyroMode != settings.gyroMode) add("gyroMode")
        if (gyroSensitivity != settings.gyroSensitivity) add("gyroSensitivity")
        if (gyroSmoothing != settings.gyroSmoothing) add("gyroSmoothing")
        if (gyroInvertX != settings.gyroInvertX) add("gyroInvertX")
        if (gyroInvertY != settings.gyroInvertY) add("gyroInvertY")
        if (gamepadRightStickUpToR2 != settings.gamepadRightStickUpToR2) add("gamepadRightStickUpToR2")
        if (gamepadRightStickDownToL2 != settings.gamepadRightStickDownToL2) add("gamepadRightStickDownToL2")
        if (gamepadButtonHaptics != settings.gamepadButtonHaptics) add("gamepadButtonHaptics")
        if (gamepadStickDeadzone != settings.gamepadStickDeadzone) add("gamepadStickDeadzone")
        if (gamepadLeftStickSensitivity != settings.gamepadLeftStickSensitivity) add("gamepadLeftStickSensitivity")
        if (gamepadRightStickSensitivity != settings.gamepadRightStickSensitivity) add("gamepadRightStickSensitivity")
        if (gamepadBindingsByPad.isNotEmpty()) add("gamepadBindingsByPad")
        if (pressureModifierAmount != settings.pressureModifierAmount) add("pressureModifierAmount")
        if (autoSaveOnExit) add("autoSaveOnExit")
        if (autoLoadOnStart) add("autoLoadOnStart")
        if (targetFps != settings.targetFps) add("targetFps")
        if (ntscFramerate != settings.ntscFramerate) add("ntscFramerate")
        if (palFramerate != settings.palFramerate) add("palFramerate")
    }

    private fun refreshCurrentGameCheats(
        metadata: com.sbro.emucoreh.core.GameMetadata
    ) {
        val serial = metadata.serial.orEmpty()
        val crc = metadata.serialWithCrc.extractCrc()
        val config = cheatRepository.getGameConfig(
            gameKeys = cheatLookupKeys(metadata),
            serial = serial,
            crc = crc
        )
        _uiState.value = _uiState.value.copy(
            cheatsGameKey = config?.gameKey,
            availableCheats = config?.blocks.orEmpty()
        )
        if (config != null) {
            cheatRepository.syncActiveCheats(config.gameKey, serial, crc)
        }
    }

    /**
     * Presents a DualShock on both ports. The core keeps it in digital mode
     * until a game enables analog/rumble, and DualShock is the only controller
     * class that can drive vibration, so rumble works with touch controls and
     * gamepads alike.
     */
    private fun syncPadAnalogModeForLaunch() {
        NativeApp.setPadAnalogMode(0, true)
        NativeApp.setPadAnalogMode(1, true)
    }

    private fun syncCheatsForCurrentGame(gameKeyOverride: String? = null) {
        val state = _uiState.value
        val serial = currentGameSerial.takeIf { it.isNotBlank() }
        val crc = currentGameCrc.takeIf { it.isNotBlank() }
        val gameKey = gameKeyOverride
            ?: state.cheatsGameKey
            ?: serial
            ?: crc
            ?: "game"
        cheatRepository.syncActiveCheats(
            gameKey = gameKey,
            serial = serial,
            crc = crc
        )
        val coreCheatFile = cheatRepository.activeCoreCheatFile(gameKey, serial, crc)
        if (coreCheatFile != null) {
            NativeApp.loadCheats(coreCheatFile.absolutePath)
        } else {
            NativeApp.clearCheats()
        }
    }

    private fun cheatLookupKeys(metadata: com.sbro.emucoreh.core.GameMetadata): List<String> {
        val keys = linkedSetOf<String>()
        metadata.serialWithCrc?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.serialWithCrc.extractSerialAndCrcKey()?.let(keys::add)
        metadata.serialWithCrc.extractCrc()?.let(keys::add)
        metadata.serial?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        metadata.title.trim().takeIf { it.isNotBlank() }?.let(keys::add)
        return keys.toList()
    }

    fun setSlot(slot: Int) {
        _uiState.value = _uiState.value.copy(currentSlot = normalizeManualSaveSlot(slot))
        refreshSaveStateMetadata()
    }

    private fun normalizeSaveSlot(slot: Int): Int = slot.coerceIn(AUTO_SAVE_SLOT, 10)

    private fun normalizeManualSaveSlot(slot: Int): Int = slot.coerceIn(1, 10)

    fun setAutoSaveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoSaveEnabled(enabled)
        }
    }

    fun setAutoSaveIntervalMinutes(value: Int) {
        viewModelScope.launch {
            preferences.setAutoSaveIntervalMinutes(value)
        }
    }

    fun setAutoSaveOnExit(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _uiState.value.copy(autoSaveOnExit = enabled)
            persistRuntimeState(updated)
        }
    }

    fun setAutoLoadOnStart(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _uiState.value.copy(autoLoadOnStart = enabled)
            persistRuntimeState(updated)
        }
    }

    private fun refreshSaveStateMetadata() {
        val path = currentGamePath
        if (path.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                currentSlotLastModified = 0L,
                autoSaveLastModified = 0L
            )
            return
        }

        val currentSlot = _uiState.value.currentSlot
        val currentSlotModified = saveStateLastModified(path, currentSlot)
        val autoSaveModified = saveStateLastModified(path, AUTO_SAVE_SLOT)
        _uiState.value = _uiState.value.copy(
            currentSlotLastModified = currentSlotModified,
            autoSaveLastModified = autoSaveModified
        )
    }

    private fun saveStateLastModified(gamePath: String, slot: Int): Long {
        val file = resolveSaveStateFile(gamePath, slot) ?: return 0L
        return file.takeIf { it.exists() }?.lastModified() ?: 0L
    }

    private suspend fun waitForSaveStateUpdate(gamePath: String, slot: Int, previousModified: Long): Boolean {
        val statePath = runCatching { NativeApp.getCurrentSaveStatePath(slot) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }.getOrNull()
            ?: return false
        val fallbackFile = File(statePath)
        repeat(40) {
            val file = resolveSaveStateFile(gamePath, slot) ?: fallbackFile
            val modified = file.takeIf { it.exists() }?.lastModified() ?: 0L
            if (modified > 0L && modified != previousModified) {
                return true
            }
            delay(250.milliseconds)
        }
        return (resolveSaveStateFile(gamePath, slot) ?: fallbackFile).exists()
    }

    private fun resolveSaveStateFile(gamePath: String, slot: Int): File? {
        // The identity path is what the native writer actually uses; fall back
        // to the caller's path and finally to a serial-named scan.
        val identityFile = runCatching { NativeApp.getCurrentSaveStatePath(slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (identityFile?.exists() == true) return identityFile
        val nativeFile = runCatching { NativeApp.getSaveStatePathForFile(gamePath, slot) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (nativeFile?.exists() == true) return nativeFile
        return findSaveStateFileForCurrentGame(slot) ?: nativeFile
    }

    private fun findSaveStateFileForCurrentGame(slot: Int): File? {
        val targetSerial = currentGameSerial.normalizeSaveSerialKey() ?: return null
        val matches = EmulatorStorage.saveStatesDir(getApplication(), preferences.getEmulatorDataPathSync())
            .listFiles()
            .orEmpty()
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val parsed = SAVE_STATE_FILE_REGEX.matchEntire(file.name) ?: return@mapNotNull null
                val fileSlot = parsed.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (fileSlot != slot) return@mapNotNull null
                val fileSerial = parsed.groupValues[1].normalizeSaveSerialKey() ?: return@mapNotNull null
                if (fileSerial != targetSerial) return@mapNotNull null
                file
            }
        if (matches.isEmpty()) return null
        return matches.maxByOrNull { it.lastModified() }
    }

    fun quickSave() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            val path = currentGamePath
            val previousModified = path?.let { saveStateLastModified(it, slot) } ?: 0L
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "saving"
            )
            val scheduled = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.saveState(slot)
                    } catch (_: Exception) { false }
                }
            }
            val success = scheduled && path != null && waitForSaveStateUpdate(path, slot, previousModified)
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "saved" else "save_failed"
            )
            // Refresh even on failure: whatever is on disk is the truth, and a
            // silent failure used to leave the slot looking empty.
            refreshSaveStateMetadata()
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    fun quickLoad() {
        val slot = _uiState.value.currentSlot
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(slot)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            )
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    fun loadAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isActionInProgress = true,
                actionLabel = "loading"
            )
            val success = lifecycleMutex.withLock {
                if (isShuttingDown) {
                    false
                } else {
                    try {
                        EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                    } catch (_: Exception) { false }
                }
            }
            _uiState.value = _uiState.value.copy(
                isActionInProgress = false,
                actionLabel = null,
                toastMessage = if (success) "loaded" else null
            )
            delay(2000.milliseconds)
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
    }

    private suspend fun tryAutoLoadOnStart(): Boolean {
        val path = currentGamePath ?: return false
        if (!_uiState.value.autoLoadOnStart || saveStateLastModified(path, AUTO_SAVE_SLOT) <= 0L) {
            return false
        }
        _uiState.value = _uiState.value.copy(
            isActionInProgress = true,
            actionLabel = "loading",
            statusMessage = "status_loading_state"
        )
        val success = lifecycleMutex.withLock {
            if (isShuttingDown || !_uiState.value.isRunning) {
                false
            } else {
                try {
                    EmulatorBridge.loadState(AUTO_SAVE_SLOT)
                } catch (_: Exception) {
                    false
                }
            }
        }
        _uiState.value = _uiState.value.copy(
            isActionInProgress = false,
            actionLabel = null,
            statusMessage = if (success) "status_running" else null,
            toastMessage = if (success) "loaded" else "load_failed"
        )
        refreshSaveStateMetadata()
        delay(2000.milliseconds)
        if (_uiState.value.statusMessage == "status_running") {
            _uiState.value = _uiState.value.copy(statusMessage = null)
        }
        if (_uiState.value.toastMessage == "loaded" || _uiState.value.toastMessage == "load_failed") {
            _uiState.value = _uiState.value.copy(toastMessage = null)
        }
        return success
    }

    fun stopEmulation(onExit: (() -> Unit)? = null) {
        cancelPendingStart = true
        pausedForBackground = false
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.shouldAutoSaveOnExit(currentGamePath, EmulatorBridge.runtimeFailure.value)) {
                saveAutoSaveSlot(
                    allowWhileMenu = true,
                    allowPaused = true,
                    showActionProgress = true
                )
            }
            performShutdown()
            if (onExit != null) {
                withContext(Dispatchers.Main) {
                    onExit.invoke()
                }
            }
        }
    }

    fun onHostBackgrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!state.isRunning || state.isStarting || state.isPaused || state.showMenu || isShuttingDown) {
                    return@withLock
                }
                try {
                    EmulatorBridge.pause()
                    pausedForBackground = true
                    _uiState.value = state.copy(isPaused = true)
                    DiscordIntegration.setPaused(true)
                    updateCrashContext(launchState = "paused")
                } catch (_: Exception) { }
            }
        }
    }

    fun onHostForegrounded() {
        viewModelScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val state = _uiState.value
                if (!pausedForBackground ||
                    !state.isRunning ||
                    state.isStarting ||
                    !state.isPaused ||
                    state.showMenu ||
                    isShuttingDown
                ) {
                    return@withLock
                }
                try {
                    EmulatorBridge.resume()
                    pausedForBackground = false
                    _uiState.value = state.copy(isPaused = false)
                    DiscordIntegration.setPaused(false)
                    updateCrashContext(launchState = "running")
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun performShutdown() {
        lifecycleMutex.withLock {
            if (!_uiState.value.isRunning && !_uiState.value.isStarting &&
                !EmulatorBridge.isVmActive() && EmulatorBridge.runtimeFailure.value == null) return
            if (isShuttingDown) return
            val analyticsState = _uiState.value
            val completedRunningSession = analyticsState.isRunning
            isShuttingDown = true
            pausedForBackground = false
            try {
                try {
                    EmulatorBridge.resetKeyStatus()
                } catch (_: Exception) { }
                try {
                    GamepadManager.clearPerGameOverrides()
                } catch (_: Exception) { }
                try {
                    if (_uiState.value.isHangTraceActive) {
                        EmulatorBridge.stopHangTrace()
                    }
                } catch (_: Exception) { }
                try {
                    EmulatorBridge.shutdown()
                    var waitTime = 0
                    while (EmulatorBridge.isVmActive() && waitTime < 2000) {
                        delay(50.milliseconds)
                        waitTime += 50
                    }
                    DocumentPathResolver.releasePreparedLaunchHandles()
                } catch (_: Exception) { }
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    isStarting = false,
                    isPaused = false,
                    showMenu = false,
                    isActionInProgress = false,
                    actionLabel = null,
                    fps = "0",
                    performanceOverlayText = "",
                    speedPercent = 100f,
                    isJitProfilerActive = false,
                    isHangTraceActive = false,
                    statusMessage = null
                )
                syncNativePerformanceOverlayState(_uiState.value)
                clearCrashContext()
                RetroAchievementsRepository.get(getApplication()).onGameStopped()
            } finally {
                isShuttingDown = false
            }
        }
    }

    private fun updateCrashContext(
        launchState: String? = null,
        launchPath: String? = null
    ) {
        val state = _uiState.value
        NativeApp.setCrashContextString("emu_launch_state", launchState ?: when {
            state.isStarting -> "starting"
            state.isPaused -> "paused"
            state.isRunning -> "running"
            else -> "idle"
        })
        NativeApp.setCrashContextString("emu_game_title", currentGameTitle)
        NativeApp.setCrashContextString("emu_game_serial", currentGameSerial)
        NativeApp.setCrashContextString("emu_game_source", currentGameSource)
        NativeApp.setCrashContextString("emu_game_path_hint", launchPath?.let { File(it).name }.orEmpty())
        NativeApp.setCrashContextInt("emu_renderer", state.renderer)
        NativeApp.setCrashContextString("emu_renderer_name", when (state.renderer) {
            12 -> "OpenGL"
            13 -> "Software"
            14 -> "Vulkan"
            else -> "Unknown(${state.renderer})"
        })
        NativeApp.setCrashContextString("emu_upscale", state.upscale.toString())
        NativeApp.setCrashContextInt("emu_aspect_ratio", state.aspectRatio)
        NativeApp.setCrashContextInt("emu_local_multiplayer_mode", state.localMultiplayerMode)
        NativeApp.setCrashContextInt("emu_crop_left", state.displayCrop.left)
        NativeApp.setCrashContextInt("emu_crop_top", state.displayCrop.top)
        NativeApp.setCrashContextInt("emu_crop_right", state.displayCrop.right)
        NativeApp.setCrashContextInt("emu_crop_bottom", state.displayCrop.bottom)
        NativeApp.setCrashContextBool("emu_frame_limit_enabled", state.frameLimitEnabled)
        NativeApp.setCrashContextInt("emu_target_fps", state.targetFps)
        NativeApp.setCrashContextString("emu_device_model", Build.MODEL.orEmpty())
        NativeApp.setCrashContextString("emu_soc_model", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "")
        NativeApp.setCrashContextBool("emu_running", state.isRunning)
        NativeApp.setCrashContextBool("emu_paused", state.isPaused)
    }

    private fun clearCrashContext() {
        DiscordIntegration.clearGame()
        currentGameTitle = ""
        currentGamePath = null
        currentTouchControlsLayoutProfile = null
        currentGameSerial = ""
        currentGameCoverArtPath = null
        currentGameCrc = ""
        _uiState.value = _uiState.value.copy(
            currentGameTitle = "",
            currentGameSubtitle = "",
            currentGameCoverPath = null,
            gameSettingsProfileActive = false
        )
        currentGameSource = ""
        NativeApp.setCrashContextString("emu_launch_state", "idle")
        NativeApp.setCrashContextString("emu_game_title", "")
        NativeApp.setCrashContextString("emu_game_serial", "")
        NativeApp.setCrashContextString("emu_game_source", "")
        NativeApp.setCrashContextString("emu_game_path_hint", "")
        NativeApp.setCrashContextBool("emu_running", false)
        NativeApp.setCrashContextBool("emu_paused", false)
    }

    fun onPadInput(padIndex: Int, keyCode: Int, range: Int = 0, pressed: Boolean) {
        try {
            EmulatorBridge.setPadButton(padIndex, keyCode, range, pressed)
        } catch (_: Exception) { }
    }

    override fun onCleared() {
        DiscordIntegration.clearGame()
        androidGamePerformance.update(AndroidGamePhase.Idle)
        NativeApp.setPerformanceMetricsEnabled(visible = false, detailed = false)
        if (_uiState.value.isRunning) {
            EmulatorBridge.resetKeyStatus()
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    EmulatorBridge.shutdown()
                }
            }
        }
    }
}

private fun String?.extractCrc(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val parenthesized = raw.substringAfter('(', "").substringBefore(')').trim()
    if (parenthesized.matches(Regex("[0-9A-Fa-f]{8}"))) return parenthesized.uppercase()
    return Regex("([0-9A-Fa-f]{8})(?!.*[0-9A-Fa-f]{8})")
        .find(raw)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase()
}

private fun String?.extractSerialAndCrcKey(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val crc = raw.extractCrc()
    val serial = raw.substringBefore('(')
        .replace(Regex("_[0-9A-Fa-f]{8}$"), "")
        .trim()
    return if (serial.isNotBlank() && !crc.isNullOrBlank()) "${serial}_$crc" else null
}

private fun String?.normalizeSaveSerialKey(): String? {
    if (this.isNullOrBlank()) return null
    val cleanSerial = trim().uppercase(Locale.ROOT)
    val splitRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{3})[^A-Z0-9]*([0-9]{2})")
    val compactRegex = Regex("([A-Z]{4})[^A-Z0-9]*([0-9]{5})")
    splitRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
    }
    compactRegex.find(cleanSerial)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}"
    }
    return cleanSerial.replace(Regex("[^A-Z0-9_-]"), "").takeIf { it.isNotBlank() }
}
