package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.AudioDefaults
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.core.UPSCALE_DEFAULT
import com.sbro.emucoreh.core.normalizeUpscale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PerGameSettings(
    val gameKey: String,
    val gameTitle: String,
    val gameSerial: String? = null,
    val renderer: Int = EmulatorBridge.AUTO_RENDERER,
    val gpuDriverType: Int = 0,
    val customDriverPath: String? = null,
    val mediatekAngleOpenGl: Boolean = false,
    val upscaleMultiplier: Float = UPSCALE_DEFAULT,
    val aspectRatio: Int = 1,
    val localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val showFps: Boolean = false,
    val fpsOverlayMode: Int = AppPreferences.FPS_OVERLAY_MODE_DETAILED,
    val racingMode: Boolean = false,
    val touchscreenRightStick: Boolean = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK,
    val touchscreenRightStickSensitivity: Int = AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY,
    val touchHaptics: Boolean = false,
    val touchHapticsPreset: Int = AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET,
    val gyroMode: Int = AppPreferences.GYRO_MODE_OFF,
    val gyroSensitivity: Int = AppPreferences.DEFAULT_GYRO_SENSITIVITY,
    val gyroSmoothing: Int = AppPreferences.DEFAULT_GYRO_SMOOTHING,
    val gyroInvertX: Boolean = false,
    val gyroInvertY: Boolean = false,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
    val autoSaveOnExit: Boolean = false,
    val autoLoadOnStart: Boolean = false,
    val frameLimitEnabled: Boolean = true,
    val targetFps: Int = 0,
    val ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
    val palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE,
    val shaderChainOverrideEnabled: Boolean? = null,
    val shaderChainPreset: String = "",
    val touchControlVisualStyle: TouchControlVisualStyle? = null,
    val touchControlPressEffect: TouchControlPressEffect? = null,
    val touchControlsLayout: TouchControlsLayoutProfile? = null,
    val customTouchControls: CustomTouchControlLibrary? = null,
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
    val audioOutputLatencyMs: Int = AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT,
    val audioMinimalOutputLatency: Boolean = AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
    /** Per-game Flycast libretro core option overrides (key -> value). */
    val coreOptions: Map<String, String> = emptyMap(),
    val providedKeys: Set<String>? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

internal data class ResolvedShaderChain(
    val enabled: Boolean,
    val preset: String
)

internal fun PerGameSettings.resolveShaderChain(
    globalEnabled: Boolean,
    globalPreset: String
): ResolvedShaderChain {
    val preset = when (shaderChainOverrideEnabled) {
        null -> globalPreset
        false -> ""
        true -> shaderChainPreset
    }.trim()
    val requestedEnabled = shaderChainOverrideEnabled ?: globalEnabled
    return ResolvedShaderChain(
        enabled = requestedEnabled && preset.isNotEmpty(),
        preset = preset.takeIf { requestedEnabled }.orEmpty()
    )
}

data class TouchControlsLayoutProfile(
    val dpadOffset: Pair<Float, Float> = AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y,
    val lstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y,
    val rstickOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y,
    val actionOffset: Pair<Float, Float> = AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y,
    val lbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y,
    val rbtnOffset: Pair<Float, Float> = AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y,
    val centerOffset: Pair<Float, Float> = AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y,
    val stickScale: Int = 100,
    val controlLayouts: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts()
)

class PerGameSettingsRepository(context: Context) {
    private val file = File(EmulatorStorage.appStateDir(context), "per-game-settings.json")

    fun get(gameKey: String): PerGameSettings? = loadAll().firstOrNull { it.gameKey == gameKey }

    fun getAll(): List<PerGameSettings> = loadAll()

    fun save(settings: PerGameSettings) {
        val items = loadAll()
            .filterNot { it.gameKey == settings.gameKey } +
            settings.copy(updatedAt = System.currentTimeMillis())
        writeAll(items.sortedBy { it.gameTitle.lowercase() })
    }

    fun setGpuDriverOverride(gameKey: String, customDriverPath: String?): Boolean {
        val profile = get(gameKey) ?: return false
        val overrideKeys = setOf("gpuDriverType", "customDriverPath")
        save(
            profile.copy(
                gpuDriverType = if (customDriverPath.isNullOrBlank()) 0 else 1,
                customDriverPath = customDriverPath?.takeIf { it.isNotBlank() },
                providedKeys = profile.providedKeys?.plus(overrideKeys)
            )
        )
        return true
    }

    fun delete(gameKey: String) {
        writeAll(loadAll().filterNot { it.gameKey == gameKey })
    }

    fun deleteAll() {
        writeAll(emptyList())
    }

    fun exportJson(): JSONObject {
        return JSONObject().put(
            "profiles",
            JSONArray().apply {
                loadAll().forEach { put(it.toJson()) }
            }
        )
    }

    fun importJson(json: JSONObject) {
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        val items = buildList {
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                add(item.toPerGameSettings())
            }
        }
        writeAll(items.sortedBy { it.gameTitle.lowercase() })
    }

    private fun loadAll(): List<PerGameSettings> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val profiles = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (index in 0 until profiles.length()) {
                    val item = profiles.optJSONObject(index) ?: continue
                    add(item.toPerGameSettings())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<PerGameSettings>) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().put(
                "profiles",
                JSONArray().apply {
                    items.forEach { put(it.toJson()) }
                }
            ).toString(2)
        )
    }
}

private fun JSONObject.toPerGameSettings(): PerGameSettings {
    val providedKeys = keys().asSequence().toSet()
    return PerGameSettings(
        gameKey = optString("gameKey"),
        gameTitle = optString("gameTitle"),
        gameSerial = optString("gameSerial").takeIf { it.isNotBlank() },
        renderer = optInt("renderer", RendererDefaults.AUTO).let(::sanitizeRendererValue),
        gpuDriverType = optInt("gpuDriverType", 0).let { if (it == 1) 1 else 0 },
        customDriverPath = optString("customDriverPath").takeIf { it.isNotBlank() },
        mediatekAngleOpenGl = optBoolean("mediatekAngleOpenGl", false),
        upscaleMultiplier = readUpscaleMultiplier(),
        aspectRatio = optInt("aspectRatio", 1).let(::sanitizeAspectRatioValue),
        localMultiplayerMode = optInt(
            "localMultiplayerMode",
            AppPreferences.LOCAL_MULTIPLAYER_OFF
        ).let(::sanitizeLocalMultiplayerMode),
        displayCrop = optJSONObject("displayCrop")?.let { crop ->
            DisplayCrop(
                left = crop.optInt("left", 0),
                top = crop.optInt("top", 0),
                right = crop.optInt("right", 0),
                bottom = crop.optInt("bottom", 0)
            ).sanitized()
        } ?: DisplayCrop.None,
        showFps = optBoolean("showFps", false),
        fpsOverlayMode = optInt("fpsOverlayMode", AppPreferences.FPS_OVERLAY_MODE_DETAILED),
        racingMode = optBoolean("racingMode", false),
        touchscreenRightStick = optBoolean(
            "touchscreenRightStick",
            AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK
        ),
        touchscreenRightStickSensitivity = optInt(
            "touchscreenRightStickSensitivity",
            AppPreferences.DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY
        ).coerceIn(
            AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
            AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
        ),
        touchHaptics = optBoolean("touchHaptics", false),
        touchHapticsPreset = optInt("touchHapticsPreset", AppPreferences.DEFAULT_TOUCH_HAPTICS_PRESET)
            .coerceIn(AppPreferences.TOUCH_HAPTICS_PRESET_SOFT, AppPreferences.TOUCH_HAPTICS_PRESET_STRONG),
        gyroMode = optInt("gyroMode", AppPreferences.GYRO_MODE_OFF).coerceIn(AppPreferences.GYRO_MODE_OFF, AppPreferences.GYRO_MODE_STEERING),
        gyroSensitivity = optInt("gyroSensitivity", AppPreferences.DEFAULT_GYRO_SENSITIVITY).coerceIn(25, 300),
        gyroSmoothing = optInt("gyroSmoothing", AppPreferences.DEFAULT_GYRO_SMOOTHING).coerceIn(0, 90),
        gyroInvertX = optBoolean("gyroInvertX", false),
        gyroInvertY = optBoolean("gyroInvertY", false),
        gamepadRightStickUpToR2 = optBoolean("gamepadRightStickUpToR2", false),
        gamepadRightStickDownToL2 = optBoolean("gamepadRightStickDownToL2", false),
        gamepadButtonHaptics = optBoolean("gamepadButtonHaptics", false),
        gamepadStickDeadzone = optInt("gamepadStickDeadzone", AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE)
            .coerceIn(0, 35),
        gamepadLeftStickSensitivity = optInt("gamepadLeftStickSensitivity", AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY)
            .coerceIn(50, 200),
        gamepadRightStickSensitivity = optInt("gamepadRightStickSensitivity", AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY)
            .coerceIn(50, 200),
        gamepadBindingsByPad = decodeGamepadBindingsByPerGameJson(optJSONObject("gamepadBindingsByPad")),
        pressureModifierAmount = optInt("pressureModifierAmount", AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT).coerceIn(1, 100),
        autoSaveOnExit = optBoolean("autoSaveOnExit", false),
        autoLoadOnStart = optBoolean("autoLoadOnStart", false),
        frameLimitEnabled = optBoolean("frameLimitEnabled", true),
        targetFps = optInt("targetFps", 0).let { if (it <= 0) 0 else it.coerceIn(20, 120) },
        ntscFramerate = sanitizeRegionFramerate(
            optDouble("ntscFramerate", AppPreferences.DEFAULT_NTSC_FRAMERATE.toDouble()).toFloat(),
            AppPreferences.DEFAULT_NTSC_FRAMERATE
        ),
        palFramerate = sanitizeRegionFramerate(
            optDouble("palFramerate", AppPreferences.DEFAULT_PAL_FRAMERATE.toDouble()).toFloat(),
            AppPreferences.DEFAULT_PAL_FRAMERATE
        ),
        shaderChainOverrideEnabled = if (has("shaderChainOverrideEnabled")) {
            optBoolean("shaderChainOverrideEnabled", false)
        } else {
            null
        },
        shaderChainPreset = optString("shaderChainPreset").trim(),
        touchControlVisualStyle = if (has("touchControlVisualStyle")) {
            TouchControlVisualStyle.fromPreference(optInt("touchControlVisualStyle"))
        } else {
            null
        },
        touchControlPressEffect = if (has("touchControlPressEffect")) {
            TouchControlPressEffect.fromPreference(optInt("touchControlPressEffect"))
        } else {
            null
        },
        touchControlsLayout = optJSONObject("touchControlsLayout")?.toTouchControlsLayoutProfile(),
        customTouchControls = CustomTouchControlLibrary.decodeOrNull(
            optString("customTouchControls")
        ),
        coreOptions = optJSONObject("coreOptions")?.let { obj ->
            buildMap {
                obj.keys().forEach { optionKey ->
                    obj.optString(optionKey).takeIf { it.isNotBlank() }?.let { put(optionKey, it) }
                }
            }
        } ?: emptyMap(),
        providedKeys = providedKeys,
        updatedAt = optLong("updatedAt", System.currentTimeMillis())
    )
}

private fun PerGameSettings.toJson(): JSONObject {
    return JSONObject().apply {
        put("gameKey", gameKey)
        put("gameTitle", gameTitle)
        put("gameSerial", gameSerial)
        val keys = providedKeys
        fun shouldWrite(key: String): Boolean = keys == null || key in keys
        if (shouldWrite("renderer")) put("renderer", sanitizeRendererValue(renderer))
        if (shouldWrite("gpuDriverType")) put("gpuDriverType", if (gpuDriverType == 1) 1 else 0)
        if (shouldWrite("customDriverPath")) put("customDriverPath", customDriverPath)
        if (shouldWrite("mediatekAngleOpenGl")) put("mediatekAngleOpenGl", mediatekAngleOpenGl)
        if (shouldWrite("upscaleMultiplier")) put("upscaleMultiplier", upscaleMultiplier.toDouble())
        if (shouldWrite("aspectRatio")) put("aspectRatio", sanitizeAspectRatioValue(aspectRatio))
        if (shouldWrite("localMultiplayerMode")) {
            put("localMultiplayerMode", sanitizeLocalMultiplayerMode(localMultiplayerMode))
        }
        if (shouldWrite("displayCrop")) put("displayCrop", JSONObject().apply {
            val crop = displayCrop.sanitized()
            put("left", crop.left)
            put("top", crop.top)
            put("right", crop.right)
            put("bottom", crop.bottom)
        })
        if (shouldWrite("showFps")) put("showFps", showFps)
        if (shouldWrite("fpsOverlayMode")) put("fpsOverlayMode", fpsOverlayMode)
        if (shouldWrite("racingMode")) put("racingMode", racingMode)
        if (shouldWrite("touchscreenRightStick")) put("touchscreenRightStick", touchscreenRightStick)
        if (shouldWrite("touchscreenRightStickSensitivity")) {
            put("touchscreenRightStickSensitivity", touchscreenRightStickSensitivity)
        }
        if (shouldWrite("touchHaptics")) put("touchHaptics", touchHaptics)
        if (shouldWrite("touchHapticsPreset")) put("touchHapticsPreset", touchHapticsPreset)
        if (shouldWrite("gyroMode")) put("gyroMode", gyroMode)
        if (shouldWrite("gyroSensitivity")) put("gyroSensitivity", gyroSensitivity)
        if (shouldWrite("gyroSmoothing")) put("gyroSmoothing", gyroSmoothing)
        if (shouldWrite("gyroInvertX")) put("gyroInvertX", gyroInvertX)
        if (shouldWrite("gyroInvertY")) put("gyroInvertY", gyroInvertY)
        if (shouldWrite("gamepadRightStickUpToR2")) put("gamepadRightStickUpToR2", gamepadRightStickUpToR2)
        if (shouldWrite("gamepadRightStickDownToL2")) put("gamepadRightStickDownToL2", gamepadRightStickDownToL2)
        if (shouldWrite("gamepadButtonHaptics")) put("gamepadButtonHaptics", gamepadButtonHaptics)
        if (shouldWrite("gamepadStickDeadzone")) put("gamepadStickDeadzone", gamepadStickDeadzone.coerceIn(0, 35))
        if (shouldWrite("gamepadLeftStickSensitivity")) put("gamepadLeftStickSensitivity", gamepadLeftStickSensitivity.coerceIn(50, 200))
        if (shouldWrite("gamepadRightStickSensitivity")) put("gamepadRightStickSensitivity", gamepadRightStickSensitivity.coerceIn(50, 200))
        if (shouldWrite("gamepadBindingsByPad") && gamepadBindingsByPad.isNotEmpty()) {
            put("gamepadBindingsByPad", encodeGamepadBindingsPerGameJson(gamepadBindingsByPad))
        }
        if (shouldWrite("pressureModifierAmount")) put("pressureModifierAmount", pressureModifierAmount.coerceIn(1, 100))
        if (shouldWrite("autoSaveOnExit")) put("autoSaveOnExit", autoSaveOnExit)
        if (shouldWrite("autoLoadOnStart")) put("autoLoadOnStart", autoLoadOnStart)
        if (shouldWrite("frameLimitEnabled")) put("frameLimitEnabled", frameLimitEnabled)
        if (shouldWrite("targetFps")) put("targetFps", targetFps)
        if (shouldWrite("ntscFramerate")) put("ntscFramerate", ntscFramerate.toDouble())
        if (shouldWrite("palFramerate")) put("palFramerate", palFramerate.toDouble())
        shaderChainOverrideEnabled?.let { overrideEnabled ->
            put("shaderChainOverrideEnabled", overrideEnabled)
            put("shaderChainPreset", shaderChainPreset.trim())
        }
        if (shouldWrite("audioVolume")) put("audioVolume", audioVolume)
        if (shouldWrite("audioMuted")) put("audioMuted", audioMuted)
        if (shouldWrite("audioOutputLatencyMs")) put("audioOutputLatencyMs", audioOutputLatencyMs)
        if (shouldWrite("audioMinimalOutputLatency")) put("audioMinimalOutputLatency", audioMinimalOutputLatency)
        if (coreOptions.isNotEmpty()) {
            put("coreOptions", JSONObject().apply {
                coreOptions.forEach { (optionKey, optionValue) -> put(optionKey, optionValue) }
            })
        }
        if (shouldWrite("touchControlVisualStyle")) {
            touchControlVisualStyle?.let { put("touchControlVisualStyle", it.preferenceValue) }
        }
        if (shouldWrite("touchControlPressEffect")) {
            touchControlPressEffect?.let { put("touchControlPressEffect", it.preferenceValue) }
        }
        if (shouldWrite("touchControlsLayout")) touchControlsLayout?.let { put("touchControlsLayout", it.toJson()) }
        if (shouldWrite("customTouchControls")) {
            customTouchControls?.sanitized()?.let { put("customTouchControls", it.encode()) }
        }
        put("updatedAt", updatedAt)
    }
}

private fun TouchControlsLayoutProfile.toJson(): JSONObject {
    return JSONObject().apply {
        put("dpadOffset", dpadOffset.toJson())
        put("lstickOffset", lstickOffset.toJson())
        put("rstickOffset", rstickOffset.toJson())
        put("actionOffset", actionOffset.toJson())
        put("lbtnOffset", lbtnOffset.toJson())
        put("rbtnOffset", rbtnOffset.toJson())
        put("centerOffset", centerOffset.toJson())
        put(
            "stickScale",
            stickScale.coerceIn(AppPreferences.OVERLAY_CONTROL_SCALE_MIN, AppPreferences.OVERLAY_CONTROL_SCALE_MAX)
        )
        put("controlLayouts", controlLayouts.toJson())
    }
}

private fun JSONObject.toTouchControlsLayoutProfile(): TouchControlsLayoutProfile {
    val stickScale = optInt("stickScale", AppPreferences.OVERLAY_CONTROL_SCALE_DEFAULT)
        .coerceIn(AppPreferences.OVERLAY_CONTROL_SCALE_MIN, AppPreferences.OVERLAY_CONTROL_SCALE_MAX)
    val layouts = optJSONObject("controlLayouts")
        ?.toOverlayControlLayouts()
        ?.takeIf { it.isNotEmpty() }
        ?: AppPreferences.defaultOverlayControlLayouts(stickScale)
    return TouchControlsLayoutProfile(
        dpadOffset = readOffset("dpadOffset", AppPreferences.DEFAULT_DPAD_OFFSET_X to AppPreferences.DEFAULT_DPAD_OFFSET_Y),
        lstickOffset = readOffset("lstickOffset", AppPreferences.DEFAULT_LSTICK_OFFSET_X to AppPreferences.DEFAULT_LSTICK_OFFSET_Y),
        rstickOffset = readOffset("rstickOffset", AppPreferences.DEFAULT_RSTICK_OFFSET_X to AppPreferences.DEFAULT_RSTICK_OFFSET_Y),
        actionOffset = readOffset("actionOffset", AppPreferences.DEFAULT_ACTION_OFFSET_X to AppPreferences.DEFAULT_ACTION_OFFSET_Y),
        lbtnOffset = readOffset("lbtnOffset", AppPreferences.DEFAULT_LBTN_OFFSET_X to AppPreferences.DEFAULT_LBTN_OFFSET_Y),
        rbtnOffset = readOffset("rbtnOffset", AppPreferences.DEFAULT_RBTN_OFFSET_X to AppPreferences.DEFAULT_RBTN_OFFSET_Y),
        centerOffset = readOffset("centerOffset", AppPreferences.DEFAULT_CENTER_OFFSET_X to AppPreferences.DEFAULT_CENTER_OFFSET_Y),
        stickScale = stickScale,
        controlLayouts = layouts
    )
}

private fun Pair<Float, Float>.toJson(): JSONObject {
    return JSONObject()
        .put("x", first.toDouble())
        .put("y", second.toDouble())
}

private fun JSONObject.readOffset(key: String, fallback: Pair<Float, Float>): Pair<Float, Float> {
    val json = optJSONObject(key) ?: return fallback
    return json.optDouble("x", fallback.first.toDouble()).toFloat() to
        json.optDouble("y", fallback.second.toDouble()).toFloat()
}

private fun Map<String, OverlayControlLayout>.toJson(): JSONObject {
    return JSONObject().apply {
        forEach { (id, layout) ->
            put(id, layout.toJson())
        }
    }
}

private fun JSONObject.toOverlayControlLayouts(): Map<String, OverlayControlLayout> {
    return keys().asSequence().associateWith { id ->
        optJSONObject(id)?.toOverlayControlLayout() ?: OverlayControlLayout()
    }
}

private fun OverlayControlLayout.toJson(): JSONObject {
    return JSONObject()
        .put("offset", offset.toJson())
        .put(
            "scale",
            scale.coerceIn(AppPreferences.OVERLAY_CONTROL_SCALE_MIN, AppPreferences.OVERLAY_CONTROL_SCALE_MAX)
        )
        .put("widthScale", widthScale.coerceIn(100, 240))
        .put(
            "opacity",
            opacity.coerceIn(
                AppPreferences.OVERLAY_CONTROL_OPACITY_MIN,
                AppPreferences.OVERLAY_CONTROL_OPACITY_MAX
            )
        )
        .put("visible", visible)
        .put("surfaceOnly", surfaceOnly)
        .apply {
            secondaryActionId
                ?.takeIf { it in CustomTouchControl.ALLOWED_ACTION_IDS }
                ?.let { put("secondaryActionId", it) }
        }
}

private fun JSONObject.toOverlayControlLayout(): OverlayControlLayout {
    return OverlayControlLayout(
        offset = readOffset("offset", 0f to 0f),
        scale = optInt("scale", AppPreferences.OVERLAY_CONTROL_SCALE_DEFAULT)
            .coerceIn(AppPreferences.OVERLAY_CONTROL_SCALE_MIN, AppPreferences.OVERLAY_CONTROL_SCALE_MAX),
        widthScale = optInt("widthScale", 100).coerceIn(100, 240),
        opacity = optInt("opacity", AppPreferences.OVERLAY_CONTROL_OPACITY_DEFAULT)
            .coerceIn(
                AppPreferences.OVERLAY_CONTROL_OPACITY_MIN,
                AppPreferences.OVERLAY_CONTROL_OPACITY_MAX
            ),
        visible = optBoolean("visible", true),
        surfaceOnly = optBoolean("surfaceOnly", false),
        secondaryActionId = optString("secondaryActionId")
            .takeIf { it in CustomTouchControl.ALLOWED_ACTION_IDS }
    )
}

private fun sanitizeRendererValue(value: Int): Int {
    return RendererDefaults.normalizeAndroidRenderer(value)
}

private fun sanitizeAspectRatioValue(value: Int): Int {
    return if (value in 0..4) value else 1
}

private fun sanitizeLocalMultiplayerMode(value: Int): Int {
    return value.coerceIn(
        AppPreferences.LOCAL_MULTIPLAYER_OFF,
        AppPreferences.LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED
    )
}

private fun sanitizeRegionFramerate(value: Float, fallback: Float): Float {
    return if (value.isFinite()) value.coerceIn(20f, 120f) else fallback
}

private fun JSONObject.readUpscaleMultiplier(): Float {
    val doubleValue = optDouble("upscaleMultiplier", Double.NaN)
    return when {
        !doubleValue.isNaN() -> doubleValue.toFloat()
        has("upscaleMultiplier") -> optInt("upscaleMultiplier", UPSCALE_DEFAULT.toInt()).toFloat()
        else -> UPSCALE_DEFAULT
    }.let(::normalizeUpscale)
}

private fun decodeGamepadBindingsByPerGameJson(json: JSONObject?): Map<Int, Map<String, Int>> {
    if (json == null) return emptyMap()
    return runCatching {
        val result = mutableMapOf<Int, Map<String, Int>>()
        json.keys().forEach { key ->
            val padIndex = key.toIntOrNull() ?: return@forEach
            val bindingsObj = json.optJSONObject(key) ?: return@forEach
            val bindings = mutableMapOf<String, Int>()
            bindingsObj.keys().forEach { actionId ->
                val keyCode = bindingsObj.optInt(actionId, Int.MIN_VALUE)
                if (keyCode != Int.MIN_VALUE) bindings[actionId] = keyCode
            }
            if (bindings.isNotEmpty()) result[padIndex.coerceIn(0, 1)] = bindings
        }
        result
    }.getOrDefault(emptyMap())
}

private fun encodeGamepadBindingsPerGameJson(bindingsByPad: Map<Int, Map<String, Int>>): JSONObject {
    return JSONObject().apply {
        bindingsByPad.toSortedMap().forEach { (padIndex, bindings) ->
            if (bindings.isEmpty()) return@forEach
            put(
                padIndex.toString(),
                JSONObject().apply {
                    bindings.toSortedMap().forEach { (actionId, keyCode) ->
                        put(actionId, keyCode)
                    }
                }
            )
        }
    }
}
