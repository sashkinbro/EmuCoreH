package com.sbro.emucoreh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sbro.emucoreh.core.AudioDefaults
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.GpuHardwareProfiles
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.core.TvInterfaceMode
import com.sbro.emucoreh.core.UPSCALE_DEFAULT
import com.sbro.emucoreh.core.normalizeUpscale
import com.sbro.emucoreh.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentGameEntry(
    val path: String,
    val title: String,
    val lastPlayedAt: Long,
    val serial: String? = null
)

data class SettingsSnapshot(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val customTheme: CustomThemeConfig = CustomThemeConfig.Default,
    val customThemeLibrary: CustomThemeLibrary = CustomThemeLibrary.Empty,
    val customTouchControls: CustomTouchControlLibrary = CustomTouchControlLibrary.Empty,
    val appFontChoice: AppFontChoice = AppFontChoice.SYSTEM,
    val appFontScale: Float = AppPreferences.DEFAULT_APP_FONT_SCALE,
    val customFontName: String? = null,
    val customFontRevision: Int = 0,
    val homeGridScale: Float = AppPreferences.DEFAULT_HOME_GRID_SCALE,
    val homeBackgroundType: HomeBackgroundType = HomeBackgroundType.NONE,
    val homeBackgroundPreset: HomeBackgroundPreset = HomeBackgroundPreset.OLYMPUS,
    val homeBackgroundRevision: Int = 0,
    val homeBackgroundDim: Int = AppPreferences.DEFAULT_HOME_BACKGROUND_DIM,
    val emulationSideArtwork: EmulationSideArtwork = EmulationSideArtwork.NONE,
    val emulationSideArtworkRevision: Int = 0,
    val emulationSideArtworkDim: Int = AppPreferences.DEFAULT_EMULATION_SIDE_ARTWORK_DIM,
    val localMultiplayerMode: Int = AppPreferences.LOCAL_MULTIPLAYER_OFF,
    val touchControlVisualStyle: TouchControlVisualStyle = TouchControlVisualStyle.CLASSIC,
    val touchControlPressEffect: TouchControlPressEffect = TouchControlPressEffect.GROW,
    val gameMenuLayoutStyle: GameMenuLayoutStyle = GameMenuLayoutStyle.SIDEBAR,
    val drawerVisualStyle: DrawerVisualStyle = DrawerVisualStyle.CLASSIC,
    val hiddenDrawerItems: Set<DrawerItemId> = emptySet(),
    val gameMenuTabOrder: List<GameMenuTabId> = DefaultGameMenuTabOrder,
    val hiddenGameMenuTabs: Set<GameMenuTabId> = emptySet(),
    val gameMenuSectionOrder: List<GameMenuSectionId> = DefaultGameMenuSectionOrder,
    val hiddenGameMenuSections: Set<GameMenuSectionId> = emptySet(),
    val languageTag: String? = null,
    val tvInterfaceMode: TvInterfaceMode = TvInterfaceMode.AUTO,
    val gpuHardwareProfile: Int = GpuHardwareProfiles.ADRENO,
    val renderer: Int = RendererDefaults.defaultForHardware(),
    val upscaleMultiplier: Float = UPSCALE_DEFAULT,
    val aspectRatio: Int = 1,
    val displayCrop: DisplayCrop = DisplayCrop.None,
    val shaderChainEnabled: Boolean = false,
    val shaderChainPreset: String = "",
    val audioVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioFastForwardVolume: Int = AudioDefaults.VOLUME_DEFAULT,
    val audioMuted: Boolean = false,
    val audioOutputLatencyMs: Int = AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT,
    val audioMinimalOutputLatency: Boolean = AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
    val padVibration: Boolean = true,
    val padVibrationStrength: Int = AppPreferences.DEFAULT_PAD_VIBRATION_STRENGTH,
    val padVibrationFallback: Boolean = true,
    val showFps: Boolean = false,
    val floatingQuickActionsEnabled: Boolean = false,
    val fpsOverlayMode: Int = AppPreferences.FPS_OVERLAY_MODE_DETAILED,
    val fpsOverlayCorner: Int = AppPreferences.FPS_OVERLAY_CORNER_TOP_RIGHT,
    val fpsOverlayScale: Int = AppPreferences.DEFAULT_FPS_OVERLAY_SCALE,
    val fpsOverlayMetrics: Int = PerformanceOverlayMetrics.DEFAULT,
    val confirmSaveLoadActions: Boolean = true,
    val backButtonExitsGame: Boolean = false,
    val compactControls: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showRecentGames: Boolean = true,
    val showHomeSearch: Boolean = false,
    val showDebugOptions: Boolean = false,
    val profilerLogcat: Boolean = false,
    val preferEnglishGameTitles: Boolean = false,
    val biosPath: String? = null,
    val biosValid: Boolean = false,
    val gamePath: String? = null,
    val gamePaths: List<String> = emptyList(),
    val emulatorDataPath: String? = null,
    val coverDownloadBaseUrl: String? = null,
    val coverArtStyle: Int = AppPreferences.COVER_ART_STYLE_3D,
    val setupComplete: Boolean = false,
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
    val overlayShow: Boolean = true,
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
    val leftStickSensitivity: Int = AppPreferences.DEFAULT_STICK_SENSITIVITY,
    val rightStickSensitivity: Int = AppPreferences.DEFAULT_STICK_SENSITIVITY,
    val invertLeftStick: Boolean = false,
    val invertRightStick: Boolean = false,
    val invertLeftStickHorizontal: Boolean = false,
    val invertRightStickHorizontal: Boolean = false,
    val enableAutoGamepad: Boolean = true,
    val hideOverlayOnGamepad: Boolean = true,
    val gamepadStickDeadzone: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_DEADZONE,
    val gamepadLeftStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickSensitivity: Int = AppPreferences.DEFAULT_GAMEPAD_STICK_SENSITIVITY,
    val gamepadRightStickUpToR2: Boolean = false,
    val gamepadRightStickDownToL2: Boolean = false,
    val gamepadButtonHaptics: Boolean = false,
    val pressureModifierAmount: Int = AppPreferences.DEFAULT_PRESSURE_MODIFIER_AMOUNT,
    val gamepadBindings: Map<String, Int> = emptyMap(),
    val gamepadBindingsByPad: Map<Int, Map<String, Int>> = emptyMap(),
    val gamepadDeviceAssignments: Map<Int, String> = emptyMap(),
    val ignoredGamepadDevices: Set<String> = emptySet(),
    val gpuDriverType: Int = 0,
    val mediatekAngleOpenGl: Boolean = false,
    val customDriverPath: String? = null,
    val frameLimitEnabled: Boolean = true,
    val vSyncEnabled: Boolean = false,
    val fastForwardSpeed: Float = AppPreferences.DEFAULT_FAST_FORWARD_SPEED,
    val targetFps: Int = 0,
    val ntscFramerate: Float = AppPreferences.DEFAULT_NTSC_FRAMERATE,
    val palFramerate: Float = AppPreferences.DEFAULT_PAL_FRAMERATE
)

data class OverlayLayoutSnapshot(
    val overlayScale: Int = 100,
    val overlayOpacity: Int = AppPreferences.DEFAULT_OVERLAY_OPACITY,
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
    val stickSurfaceMode: Boolean = false,
    val controlLayouts: Map<String, OverlayControlLayout> = AppPreferences.defaultOverlayControlLayouts()
)

data class OverlayControlLayout(
    val offset: Pair<Float, Float> = 0f to 0f,
    val scale: Int = 100,
    val widthScale: Int = 100,
    val opacity: Int = 100,
    val visible: Boolean = true,
    val surfaceOnly: Boolean = false
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    private val localePrefs = context.getSharedPreferences("ui_locale", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_LOCAL_LINK_PORT = 19072
        private const val CURRENT_OVERLAY_LAYOUT_VERSION = 17
        const val DEFAULT_NTSC_FRAMERATE = 59.94f
        const val MIN_REGION_FRAMERATE = 50f
        const val MAX_REGION_FRAMERATE = 65f
        const val DEFAULT_PAL_FRAMERATE = 50f
        const val DEFAULT_FAST_FORWARD_SPEED = 2.0f
        const val DEFAULT_APP_FONT_SCALE = 1.0f
        const val MIN_APP_FONT_SCALE = 0.75f
        const val MAX_APP_FONT_SCALE = 1.50f
        const val DEFAULT_HOME_GRID_SCALE = 1.0f
        const val MIN_HOME_GRID_SCALE = 0.60f
        const val MAX_HOME_GRID_SCALE = 1.60f
        const val DEFAULT_HOME_BACKGROUND_DIM = 48
        const val DEFAULT_EMULATION_SIDE_ARTWORK_DIM = 0
        const val LOCAL_MULTIPLAYER_OFF = 0
        const val LOCAL_MULTIPLAYER_SIDE_BY_SIDE = 1
        const val LOCAL_MULTIPLAYER_STACKED = 2
        const val LOCAL_MULTIPLAYER_HORIZONTAL_CROP = 3
        const val LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED = 4
        const val MIN_FAST_FORWARD_SPEED = 1.25f
        const val MAX_FAST_FORWARD_SPEED = 5.0f
        private const val LEGACY_DEFAULT_LSTICK_OFFSET_X = 18f
        private const val LEGACY_DEFAULT_LSTICK_OFFSET_Y = -214f
        private const val LEFT_SIDE_LAYOUT_SHIFT_X = -8f
        private const val PREVIOUS_DEFAULT_DPAD_OFFSET_X = 20f
        const val DEFAULT_DPAD_OFFSET_X = 0f
        const val DEFAULT_DPAD_OFFSET_Y = -141f
        private const val PREVIOUS_DEFAULT_LSTICK_OFFSET_X = 20f
        const val DEFAULT_LSTICK_OFFSET_X = 0f
        const val DEFAULT_LSTICK_OFFSET_Y = -141f
        const val DEFAULT_RSTICK_OFFSET_X = 0f
        const val DEFAULT_RSTICK_OFFSET_Y = 0f
        const val DEFAULT_ACTION_OFFSET_X = 40f
        const val DEFAULT_ACTION_OFFSET_Y = -176f
        const val DEFAULT_LBTN_OFFSET_X = 74f
        const val DEFAULT_LBTN_OFFSET_Y = 78f
        const val DEFAULT_RBTN_OFFSET_X = -74f
        const val DEFAULT_RBTN_OFFSET_Y = 78f
        private const val PREVIOUS_DEFAULT_CENTER_OFFSET_X = 32f
        private const val PREVIOUS_DEFAULT_CENTER_OFFSET_Y = 10f
        const val DEFAULT_CENTER_OFFSET_X = 0f
        const val DEFAULT_CENTER_OFFSET_Y = 10f
        const val DEFAULT_STICK_SENSITIVITY = 100
        const val OVERLAY_CONTROL_SCALE_MIN = 50
        const val OVERLAY_CONTROL_SCALE_MAX = 500
        const val OVERLAY_CONTROL_SCALE_DEFAULT = 100
        const val OVERLAY_CONTROL_OPACITY_MIN = 20
        const val OVERLAY_CONTROL_OPACITY_MAX = 100
        const val OVERLAY_CONTROL_OPACITY_DEFAULT = 100
        const val OVERLAY_OPACITY_MIN = 0
        const val OVERLAY_OPACITY_MAX = 100
        const val DEFAULT_OVERLAY_OPACITY = 80
        const val DEFAULT_TOUCHSCREEN_RIGHT_STICK = false
        const val TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN = 50
        const val TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX = 200
        const val DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY = 100
        const val DEFAULT_GAMEPAD_STICK_DEADZONE = 15
        const val DEFAULT_GAMEPAD_STICK_SENSITIVITY = 100
        const val DEFAULT_FLOATING_QUICK_SAVE_POSITION_X = 0.88f
        const val DEFAULT_FLOATING_QUICK_SAVE_POSITION_Y = 0.42f
        const val DEFAULT_FLOATING_QUICK_LOAD_POSITION_X = 0.88f
        const val DEFAULT_FLOATING_QUICK_LOAD_POSITION_Y = 0.6f
        const val FLOATING_QUICK_ACTION_MIN = 0.03f
        const val FLOATING_QUICK_ACTION_MAX = 0.97f
        const val ORIENTATION_LOCK_AUTO = 0
        const val ORIENTATION_LOCK_PORTRAIT = 1
        const val ORIENTATION_LOCK_LANDSCAPE = 2

        fun normalizeOrientationLock(value: Int?): Int = when (value) {
            ORIENTATION_LOCK_PORTRAIT, ORIENTATION_LOCK_LANDSCAPE -> value
            else -> ORIENTATION_LOCK_AUTO
        }
        const val DEFAULT_PRESSURE_MODIFIER_AMOUNT = 50
        const val DEFAULT_PAD_VIBRATION_STRENGTH = 100
        const val DEFAULT_TOUCH_HAPTICS_STRENGTH = 60
        const val TOUCH_HAPTICS_PRESET_SOFT = 0
        const val TOUCH_HAPTICS_PRESET_BALANCED = 1
        const val TOUCH_HAPTICS_PRESET_CRISP = 2
        const val TOUCH_HAPTICS_PRESET_STRONG = 3
        const val DEFAULT_TOUCH_HAPTICS_PRESET = TOUCH_HAPTICS_PRESET_BALANCED
        const val GYRO_MODE_OFF = 0
        const val GYRO_MODE_AIM = 1
        const val GYRO_MODE_STEERING = 2
        const val DEFAULT_GYRO_SENSITIVITY = 100
        const val DEFAULT_GYRO_SMOOTHING = 45
        const val COVER_ART_STYLE_DISABLED = -1
        const val COVER_ART_STYLE_DEFAULT = 0
        const val COVER_ART_STYLE_3D = 1
        const val FPS_OVERLAY_MODE_SIMPLE = 0
        const val FPS_OVERLAY_MODE_DETAILED = 1
        const val FPS_OVERLAY_CORNER_TOP_LEFT = 0
        const val FPS_OVERLAY_CORNER_TOP_RIGHT = 1
        const val FPS_OVERLAY_CORNER_BOTTOM_LEFT = 2
        const val FPS_OVERLAY_CORNER_BOTTOM_RIGHT = 3
        const val MIN_FPS_OVERLAY_SCALE = 75
        const val MAX_FPS_OVERLAY_SCALE = 200
        const val DEFAULT_FPS_OVERLAY_SCALE = 100

        fun defaultOverlayControlLayouts(stickScale: Int = OVERLAY_CONTROL_SCALE_DEFAULT): Map<String, OverlayControlLayout> = mapOf(
            "l1" to OverlayControlLayout(),
            "r1" to OverlayControlLayout(),
            "dpad_up" to OverlayControlLayout(visible = false),
            "dpad_down" to OverlayControlLayout(visible = false),
            "dpad_left" to OverlayControlLayout(visible = false),
            "dpad_right" to OverlayControlLayout(visible = false),
            "dpad_cluster" to OverlayControlLayout(visible = true),
            "left_stick" to OverlayControlLayout(scale = stickScale, widthScale = 160, visible = true),
            "a" to OverlayControlLayout(),
            "b" to OverlayControlLayout(),
            "x" to OverlayControlLayout(),
            "y" to OverlayControlLayout(),
            "right_stick" to OverlayControlLayout(scale = stickScale, widthScale = 160, visible = false),
            // The Dreamcast pad has no Select button; the id stays available for
            // custom controls and older layouts.
            "select" to OverlayControlLayout(scale = 80, visible = false),
            // Arcade service switches (Naomi/Atomiswave). Hidden by default;
            // the controls editor can show them like any other button.
            "coin" to OverlayControlLayout(scale = 80, visible = false),
            "test" to OverlayControlLayout(scale = 80, visible = false),
            "service" to OverlayControlLayout(scale = 80, visible = false),
            "left_input_toggle" to OverlayControlLayout(scale = 80, visible = false),
            "start" to OverlayControlLayout(scale = 80),
            "fast_forward" to OverlayControlLayout(scale = 80, visible = false)
        )

        private val THEME_MODE = intPreferencesKey("theme_mode")
        private val CUSTOM_THEME_JSON = stringPreferencesKey("custom_theme_json")
        private val CUSTOM_THEME_LIBRARY_JSON = stringPreferencesKey("custom_theme_library_json")
        private val TV_INTERFACE_MODE = intPreferencesKey("tv_interface_mode")
        private val APP_FONT_CHOICE = intPreferencesKey("app_font_choice")
        private val APP_FONT_SCALE = floatPreferencesKey("app_font_scale")
        private val CUSTOM_FONT_NAME = stringPreferencesKey("custom_font_name")
        private val CUSTOM_FONT_REVISION = intPreferencesKey("custom_font_revision")
        private val HOME_GRID_SCALE = floatPreferencesKey("home_grid_scale")
        private val HOME_BACKGROUND_TYPE = intPreferencesKey("home_background_type")
        private val HOME_BACKGROUND_PRESET = intPreferencesKey("home_background_preset")
        private val HOME_BACKGROUND_REVISION = intPreferencesKey("home_background_revision")
        private val HOME_BACKGROUND_DIM = intPreferencesKey("home_background_dim")
        private val EMULATION_SIDE_ARTWORK = intPreferencesKey("emulation_side_artwork")
        private val EMULATION_SIDE_ARTWORK_REVISION = intPreferencesKey("emulation_side_artwork_revision")
        private val EMULATION_SIDE_ARTWORK_DIM = intPreferencesKey("emulation_side_artwork_dim")
        private val LOCAL_MULTIPLAYER_MODE = intPreferencesKey("local_multiplayer_mode")
        private val COVER_CACHE_REVISION = intPreferencesKey("cover_cache_revision")
        private val TOUCH_CONTROL_VISUAL_STYLE = intPreferencesKey("touch_control_visual_style")
        private val TOUCH_CONTROL_PRESS_EFFECT = intPreferencesKey("touch_control_press_effect")
        private val CUSTOM_TOUCH_CONTROLS_JSON = stringPreferencesKey("custom_touch_controls_json")
        private val GAME_MENU_LAYOUT_STYLE = intPreferencesKey("game_menu_layout_style")
        private val DRAWER_VISUAL_STYLE = intPreferencesKey("drawer_visual_style")
        private val HIDDEN_DRAWER_ITEMS = stringPreferencesKey("hidden_drawer_items")
        private val GAME_MENU_TAB_ORDER = stringPreferencesKey("game_menu_tab_order")
        private val HIDDEN_GAME_MENU_TABS = stringPreferencesKey("hidden_game_menu_tabs")
        private val GAME_MENU_SECTION_ORDER = stringPreferencesKey("game_menu_section_order")
        private val HIDDEN_GAME_MENU_SECTIONS = stringPreferencesKey("hidden_game_menu_sections")
        private val MEDIATEK_SETTINGS_NOTICE_SHOWN =
            booleanPreferencesKey("mediatek_settings_notice_shown")
        private val MEMORY_CARDS_INITIALIZED = booleanPreferencesKey("memory_cards_initialized")
        private val IN_APP_REVIEW_QUALIFYING_SESSION_COUNT =
            intPreferencesKey("in_app_review_qualifying_session_count")
        private val IN_APP_REVIEW_TOTAL_ACTIVE_PLAY_TIME_MS =
            longPreferencesKey("in_app_review_total_active_play_time_ms")
        private val IN_APP_REVIEW_LAST_ATTEMPT_AT_MS =
            longPreferencesKey("in_app_review_last_attempt_at_ms")
        private val IN_APP_REVIEW_REQUESTED = booleanPreferencesKey("in_app_review_requested")
        private val RENDERER = intPreferencesKey("renderer")
        private val UPSCALE = floatPreferencesKey("upscale_multiplier_v2")
        private val UPSCALE_LEGACY = intPreferencesKey("upscale_multiplier")
        private val SHADER_CHAIN_ENABLED = booleanPreferencesKey("shader_chain_enabled")
        private val SHADER_CHAIN_PRESET = stringPreferencesKey("shader_chain_preset")
        private val BIOS_PATH = stringPreferencesKey("bios_path")
        private val GAME_PATH = stringPreferencesKey("game_path")
        private val GAME_PATHS = stringPreferencesKey("game_paths")
        private val EMULATOR_DATA_PATH = stringPreferencesKey("emulator_data_path")
        private val COVER_DOWNLOAD_BASE_URL = stringPreferencesKey("cover_download_base_url")
        private val LEGACY_COVER_ART_STYLE = intPreferencesKey("cover_art_style")
        private val COVER_ART_STYLE = intPreferencesKey("psp_cover_art_style_v2")
        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val GPU_HARDWARE_PROFILE = intPreferencesKey("gpu_hardware_profile")
        private val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        private val ASPECT_RATIO = intPreferencesKey("aspect_ratio")
        private val DISPLAY_CROP_LEFT = intPreferencesKey("display_crop_left")
        private val DISPLAY_CROP_TOP = intPreferencesKey("display_crop_top")
        private val DISPLAY_CROP_RIGHT = intPreferencesKey("display_crop_right")
        private val DISPLAY_CROP_BOTTOM = intPreferencesKey("display_crop_bottom")
        private val AUDIO_VOLUME = intPreferencesKey("audio_volume")
        private val AUDIO_FAST_FORWARD_VOLUME = intPreferencesKey("audio_fast_forward_volume")
        private val AUDIO_MUTED = booleanPreferencesKey("audio_muted")
        private val AUDIO_OUTPUT_LATENCY_MS = intPreferencesKey("audio_output_latency_ms")
        private val AUDIO_MINIMAL_OUTPUT_LATENCY = booleanPreferencesKey("audio_minimal_output_latency")
        private val PAD_VIBRATION = booleanPreferencesKey("pad_vibration")
        private val PAD_VIBRATION_STRENGTH = intPreferencesKey("pad_vibration_strength")
        private val PAD_VIBRATION_FALLBACK = booleanPreferencesKey("pad_vibration_fallback")
        private val SHOW_FPS = booleanPreferencesKey("show_fps")
        private val FPS_OVERLAY_MODE = intPreferencesKey("fps_overlay_mode")
        private val FPS_OVERLAY_CORNER = intPreferencesKey("fps_overlay_corner")
        private val FPS_OVERLAY_SCALE = intPreferencesKey("fps_overlay_scale")
        private val FPS_OVERLAY_METRICS = intPreferencesKey("fps_overlay_metrics")
        private val CONFIRM_SAVE_LOAD_ACTIONS = booleanPreferencesKey("confirm_save_load_actions")
        private val BACK_BUTTON_EXITS_GAME = booleanPreferencesKey("back_button_exits_game")
        private val COMPACT_CONTROLS = booleanPreferencesKey("compact_controls")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val SHOW_RECENT_GAMES = booleanPreferencesKey("show_recent_games")
        private val SHOW_HOME_SEARCH = booleanPreferencesKey("show_home_search")
        private val SHOW_DEBUG_OPTIONS = booleanPreferencesKey("show_debug_options")
        private val PROFILER_LOGCAT = booleanPreferencesKey("profiler_logcat")
        private val PREFER_ENGLISH_GAME_TITLES = booleanPreferencesKey("prefer_english_game_titles")
        private val RECENT_GAMES = stringPreferencesKey("recent_games")
        private val HOME_LIBRARY_VIEW_MODE = intPreferencesKey("home_library_view_mode")
        private const val MAX_RECENT_GAMES = 8
        // Overlay customization
        private val OVERLAY_SCALE = intPreferencesKey("overlay_scale")
        private val OVERLAY_OPACITY = intPreferencesKey("overlay_opacity")
        private val OVERLAY_SHOW = booleanPreferencesKey("overlay_show")
        private val RACING_MODE = booleanPreferencesKey("racing_mode")
        private val TOUCHSCREEN_RIGHT_STICK = booleanPreferencesKey("touchscreen_right_stick")
        private val TOUCHSCREEN_RIGHT_STICK_SENSITIVITY = intPreferencesKey("touchscreen_right_stick_sensitivity")
        // Extended emulator settings
        private val TEXTURE_REPLACEMENTS_ENABLED = booleanPreferencesKey("texture_replacements_enabled")
        private val TEXTURE_REPLACEMENTS_ASYNC = booleanPreferencesKey("texture_replacements_async")
        private val TEXTURE_REPLACEMENTS_PRECACHE = booleanPreferencesKey("texture_replacements_precache")
        private val TEXTURE_DUMPING_ENABLED = booleanPreferencesKey("texture_dumping_enabled")
        private val ENABLE_AUTO_GAMEPAD = booleanPreferencesKey("enable_auto_gamepad")
        private val HIDE_OVERLAY_ON_GAMEPAD = booleanPreferencesKey("hide_overlay_on_gamepad")
        private val FLOATING_QUICK_ACTIONS_ENABLED = booleanPreferencesKey("floating_quick_actions_enabled")
        private val FLOATING_QUICK_SAVE_POSITION = stringPreferencesKey("floating_quick_save_position")
        private val FLOATING_QUICK_LOAD_POSITION = stringPreferencesKey("floating_quick_load_position")
        private val ORIENTATION_LOCK = intPreferencesKey("orientation_lock")
        private val EMULATION_ALLOWS_BOTH_ORIENTATIONS =
            booleanPreferencesKey("emulation_allows_both_orientations")
        private val RETRO_ACHIEVEMENTS_ENABLED = booleanPreferencesKey("retro_achievements_enabled")
        private val RETRO_ACHIEVEMENTS_USERNAME = stringPreferencesKey("retro_achievements_username")
        private val RETRO_ACHIEVEMENTS_TOKEN = stringPreferencesKey("retro_achievements_token")
        private val RETRO_ACHIEVEMENTS_HARDCORE = booleanPreferencesKey("retro_achievements_hardcore")
        private val RETRO_ACHIEVEMENTS_UNOFFICIAL = booleanPreferencesKey("retro_achievements_unofficial")
        private val RETRO_ACHIEVEMENTS_ENCORE = booleanPreferencesKey("retro_achievements_encore")
        private val TOUCH_HAPTICS = booleanPreferencesKey("touch_haptics")
        private val TOUCH_HAPTICS_PRESET = intPreferencesKey("touch_haptics_preset")
        private val TOUCH_HAPTICS_STRENGTH = intPreferencesKey("touch_haptics_strength")
        private val GYRO_MODE = intPreferencesKey("gyro_mode")
        private val GYRO_SENSITIVITY = intPreferencesKey("gyro_sensitivity")
        private val GYRO_SMOOTHING = intPreferencesKey("gyro_smoothing")
        private val GYRO_INVERT_X = booleanPreferencesKey("gyro_invert_x")
        private val GYRO_INVERT_Y = booleanPreferencesKey("gyro_invert_y")
        private val GAMEPAD_BUTTON_HAPTICS = booleanPreferencesKey("gamepad_button_haptics")
        private val PRESSURE_MODIFIER_AMOUNT = intPreferencesKey("pressure_modifier_amount")
        private val GAMEPAD_STICK_DEADZONE = intPreferencesKey("gamepad_stick_deadzone")
        private val GAMEPAD_LEFT_STICK_SENSITIVITY = intPreferencesKey("gamepad_left_stick_sensitivity")
        private val GAMEPAD_RIGHT_STICK_SENSITIVITY = intPreferencesKey("gamepad_right_stick_sensitivity")
        private val GAMEPAD_RIGHT_STICK_UP_TO_R2 = booleanPreferencesKey("gamepad_right_stick_up_to_r2")
        private val GAMEPAD_RIGHT_STICK_DOWN_TO_L2 = booleanPreferencesKey("gamepad_right_stick_down_to_l2")
        private val GAMEPAD_BINDINGS = stringPreferencesKey("gamepad_bindings")
        private val GAMEPAD_DEVICE_ASSIGNMENTS = stringPreferencesKey("gamepad_device_assignments")
        private val GAMEPAD_IGNORED_DEVICES = stringPreferencesKey("gamepad_ignored_devices")
        private val GPU_DRIVER_TYPE = intPreferencesKey("gpu_driver_type")
        private val MEDIATEK_ANGLE_OPENGL = booleanPreferencesKey("mediatek_angle_opengl")
        private val CUSTOM_DRIVER_PATH = stringPreferencesKey("custom_driver_path")
        private val FRAME_LIMIT_ENABLED = booleanPreferencesKey("frame_limit_enabled")
        private val VSYNC_ENABLED = booleanPreferencesKey("vsync_enabled")
        private val FAST_FORWARD_SPEED = floatPreferencesKey("fast_forward_speed")
        private val TARGET_FPS = intPreferencesKey("target_fps")
        private val NTSC_FRAMERATE = floatPreferencesKey("ntsc_framerate")
        private val PAL_FRAMERATE = floatPreferencesKey("pal_framerate")
        private val AUTO_SAVE_ENABLED = booleanPreferencesKey("auto_save_enabled")
        private val AUTO_SAVE_INTERVAL_MINUTES = intPreferencesKey("auto_save_interval_minutes")
        private val MEMORY_CARD_SLOT1 = stringPreferencesKey("memory_card_slot_1")
        private val MEMORY_CARD_SLOT2 = stringPreferencesKey("memory_card_slot_2")

        // Control Layout Customization
        private val DPAD_OFFSET = stringPreferencesKey("dpad_offset")
        private val LSTICK_OFFSET = stringPreferencesKey("lstick_offset")
        private val RSTICK_OFFSET = stringPreferencesKey("rstick_offset")
        private val ACTION_OFFSET = stringPreferencesKey("action_offset")
        private val LBTN_OFFSET = stringPreferencesKey("lbtn_offset")
        private val RBTN_OFFSET = stringPreferencesKey("rbtn_offset")
        private val CENTER_OFFSET = stringPreferencesKey("center_offset")
        private val STICK_SCALE = intPreferencesKey("stick_scale")
        private val LEFT_STICK_SENSITIVITY = intPreferencesKey("left_stick_sensitivity")
        private val RIGHT_STICK_SENSITIVITY = intPreferencesKey("right_stick_sensitivity")
        private val INVERT_LEFT_STICK = booleanPreferencesKey("invert_left_stick")
        private val INVERT_RIGHT_STICK = booleanPreferencesKey("invert_right_stick")
        private val INVERT_LEFT_STICK_HORIZONTAL = booleanPreferencesKey("invert_left_stick_horizontal")
        private val INVERT_RIGHT_STICK_HORIZONTAL = booleanPreferencesKey("invert_right_stick_horizontal")
        private val STICK_SURFACE_MODE = booleanPreferencesKey("stick_surface_mode")
        private val CONTROL_LAYOUTS = stringPreferencesKey("control_layouts")
        private val OVERLAY_LAYOUT_VERSION = intPreferencesKey("overlay_layout_version")
    }
    private fun readThemeMode(prefs: Preferences): ThemeMode {
        return when (prefs[THEME_MODE]) {
            1 -> ThemeMode.LIGHT
            2 -> ThemeMode.DARK
            3 -> ThemeMode.DARK
            4 -> ThemeMode.CUSTOM
            5 -> ThemeMode.NEON
            else -> ThemeMode.SYSTEM
        }
    }

    private fun readCustomThemeLibrary(prefs: Preferences): CustomThemeLibrary {
        return CustomThemeLibrary.decode(
            raw = prefs[CUSTOM_THEME_LIBRARY_JSON],
            legacyThemeRaw = prefs[CUSTOM_THEME_JSON]
        )
    }

    // Theme
    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { prefs -> readThemeMode(prefs) }
        .distinctUntilChanged()

    val customThemeLibrary: Flow<CustomThemeLibrary> = context.dataStore.data
        .map(::readCustomThemeLibrary)
        .distinctUntilChanged()

    val customTheme: Flow<CustomThemeConfig> = customThemeLibrary
        .map { library -> library.activeTheme()?.config ?: CustomThemeConfig.Default }
        .distinctUntilChanged()

    val customTouchControls: Flow<CustomTouchControlLibrary> = context.dataStore.data
        .map { prefs -> CustomTouchControlLibrary.decode(prefs[CUSTOM_TOUCH_CONTROLS_JSON]) }
        .distinctUntilChanged()

    val tvInterfaceMode: Flow<TvInterfaceMode> = context.dataStore.data
        .map { prefs -> TvInterfaceMode.fromPreference(prefs[TV_INTERFACE_MODE]) }
        .distinctUntilChanged()

    val appFontChoice: Flow<AppFontChoice> = context.dataStore.data
        .map { prefs -> AppFontChoice.fromPreference(prefs[APP_FONT_CHOICE]) }
        .distinctUntilChanged()

    val appFontScale: Flow<Float> = context.dataStore.data
        .map { prefs -> (prefs[APP_FONT_SCALE] ?: DEFAULT_APP_FONT_SCALE).coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE) }
        .distinctUntilChanged()

    val customFontName: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[CUSTOM_FONT_NAME]?.takeIf(String::isNotBlank) }
        .distinctUntilChanged()

    val customFontRevision: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[CUSTOM_FONT_REVISION] ?: 0).coerceAtLeast(0) }
        .distinctUntilChanged()

    val homeGridScale: Flow<Float> = context.dataStore.data
        .map { prefs -> (prefs[HOME_GRID_SCALE] ?: DEFAULT_HOME_GRID_SCALE).coerceIn(MIN_HOME_GRID_SCALE, MAX_HOME_GRID_SCALE) }
        .distinctUntilChanged()

    val homeBackgroundType: Flow<HomeBackgroundType> = context.dataStore.data
        .map { prefs -> HomeBackgroundType.fromPreference(prefs[HOME_BACKGROUND_TYPE]) }
        .distinctUntilChanged()

    val homeBackgroundPreset: Flow<HomeBackgroundPreset> = context.dataStore.data
        .map { prefs -> HomeBackgroundPreset.fromPreference(prefs[HOME_BACKGROUND_PRESET]) }
        .distinctUntilChanged()

    val homeBackgroundRevision: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[HOME_BACKGROUND_REVISION] ?: 0).coerceAtLeast(0) }
        .distinctUntilChanged()

    val homeBackgroundDim: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[HOME_BACKGROUND_DIM] ?: DEFAULT_HOME_BACKGROUND_DIM).coerceIn(0, 85) }
        .distinctUntilChanged()

    val emulationSideArtwork: Flow<EmulationSideArtwork> = context.dataStore.data
        .map { prefs -> EmulationSideArtwork.fromPreference(prefs[EMULATION_SIDE_ARTWORK]) }
        .distinctUntilChanged()

    val emulationSideArtworkRevision: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[EMULATION_SIDE_ARTWORK_REVISION] ?: 0).coerceAtLeast(0) }
        .distinctUntilChanged()

    val emulationSideArtworkDim: Flow<Int> = context.dataStore.data
        .map { prefs ->
            (prefs[EMULATION_SIDE_ARTWORK_DIM] ?: DEFAULT_EMULATION_SIDE_ARTWORK_DIM).coerceIn(0, 85)
        }
        .distinctUntilChanged()

    val localMultiplayerMode: Flow<Int> = context.dataStore.data
        .map { prefs -> normalizeLocalMultiplayerMode(prefs[LOCAL_MULTIPLAYER_MODE]) }
        .distinctUntilChanged()

    val coverCacheRevision: Flow<Int> = context.dataStore.data
        .map { prefs -> (prefs[COVER_CACHE_REVISION] ?: 0).coerceAtLeast(0) }
        .distinctUntilChanged()

    val touchControlVisualStyle: Flow<TouchControlVisualStyle> = context.dataStore.data
        .map { prefs -> TouchControlVisualStyle.fromPreference(prefs[TOUCH_CONTROL_VISUAL_STYLE]) }
        .distinctUntilChanged()

    val touchControlPressEffect: Flow<TouchControlPressEffect> = context.dataStore.data
        .map { prefs -> TouchControlPressEffect.fromPreference(prefs[TOUCH_CONTROL_PRESS_EFFECT]) }
        .distinctUntilChanged()

    val gameMenuLayoutStyle: Flow<GameMenuLayoutStyle> = context.dataStore.data
        .map { prefs -> GameMenuLayoutStyle.fromPreference(prefs[GAME_MENU_LAYOUT_STYLE]) }
        .distinctUntilChanged()

    val drawerVisualStyle: Flow<DrawerVisualStyle> = context.dataStore.data
        .map { prefs -> DrawerVisualStyle.fromPreference(prefs[DRAWER_VISUAL_STYLE]) }
        .distinctUntilChanged()

    val hiddenDrawerItems: Flow<Set<DrawerItemId>> = context.dataStore.data
        .map { prefs -> sanitizeHiddenDrawerItems(prefs[HIDDEN_DRAWER_ITEMS]) }
        .distinctUntilChanged()

    val gameMenuTabOrder: Flow<List<GameMenuTabId>> = context.dataStore.data
        .map { prefs -> sanitizeGameMenuTabOrder(prefs[GAME_MENU_TAB_ORDER]) }
        .distinctUntilChanged()

    val hiddenGameMenuTabs: Flow<Set<GameMenuTabId>> = context.dataStore.data
        .map { prefs -> sanitizeHiddenGameMenuTabs(prefs[HIDDEN_GAME_MENU_TABS]) }
        .distinctUntilChanged()

    val gameMenuSectionOrder: Flow<List<GameMenuSectionId>> = context.dataStore.data
        .map { prefs -> sanitizeGameMenuSectionOrder(prefs[GAME_MENU_SECTION_ORDER]) }
        .distinctUntilChanged()

    val hiddenGameMenuSections: Flow<Set<GameMenuSectionId>> = context.dataStore.data
        .map { prefs -> sanitizeHiddenGameMenuSections(prefs[HIDDEN_GAME_MENU_SECTIONS]) }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE] = when (mode) {
                ThemeMode.SYSTEM -> 0
                ThemeMode.LIGHT -> 1
                ThemeMode.DARK -> 2
                ThemeMode.CUSTOM -> 4
                ThemeMode.NEON -> 5
            }
        }
    }

    suspend fun setCustomTheme(config: CustomThemeConfig) {
        context.dataStore.edit { prefs ->
            val safeConfig = config.sanitized()
            val current = readCustomThemeLibrary(prefs)
            val activeId = current.activeThemeId ?: CustomThemeLibrary.LEGACY_THEME_ID
            val existing = current.themes.firstOrNull { it.id == activeId }
            val updated = SavedCustomTheme(
                id = activeId,
                config = safeConfig,
                createdAtMillis = existing?.createdAtMillis ?: 0L,
                updatedAtMillis = existing?.updatedAtMillis ?: 0L
            )
            val themes = current.themes.filterNot { it.id == activeId } + updated
            val library = current.copy(activeThemeId = activeId, themes = themes).sanitized()
            prefs[CUSTOM_THEME_LIBRARY_JSON] = library.encode()
            prefs[CUSTOM_THEME_JSON] = safeConfig.encode()
        }
    }

    suspend fun applyCustomTheme(config: CustomThemeConfig) {
        setCustomTheme(config)
        setThemeMode(ThemeMode.CUSTOM)
    }

    suspend fun setCustomThemeLibrary(library: CustomThemeLibrary, activate: Boolean) {
        context.dataStore.edit { prefs ->
            val safe = library.sanitized()
            prefs[CUSTOM_THEME_LIBRARY_JSON] = safe.encode()
            val activeConfig = safe.activeTheme()?.config
            if (activeConfig != null) {
                prefs[CUSTOM_THEME_JSON] = activeConfig.encode()
            } else {
                prefs.remove(CUSTOM_THEME_JSON)
                if (readThemeMode(prefs) == ThemeMode.CUSTOM) {
                    prefs[THEME_MODE] = 0
                }
            }
            if (activate && activeConfig != null) {
                prefs[THEME_MODE] = 4
            }
        }
    }

    suspend fun setCustomTouchControls(library: CustomTouchControlLibrary) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_TOUCH_CONTROLS_JSON] = library.sanitized().encode()
        }
    }

    suspend fun setTvInterfaceMode(mode: TvInterfaceMode) {
        context.dataStore.edit { prefs -> prefs[TV_INTERFACE_MODE] = mode.preferenceValue }
    }

    suspend fun setAppFontChoice(choice: AppFontChoice) {
        context.dataStore.edit { it[APP_FONT_CHOICE] = choice.preferenceValue }
    }

    suspend fun setCustomFontInstalled(displayName: String?) {
        context.dataStore.edit { prefs ->
            val cleanName = displayName?.trim()?.takeIf(String::isNotEmpty)
            if (cleanName == null) prefs.remove(CUSTOM_FONT_NAME) else prefs[CUSTOM_FONT_NAME] = cleanName
            prefs[CUSTOM_FONT_REVISION] = (prefs[CUSTOM_FONT_REVISION] ?: 0) + 1
            prefs[APP_FONT_CHOICE] = AppFontChoice.CUSTOM.preferenceValue
        }
    }

    suspend fun clearCustomFont() {
        context.dataStore.edit { prefs ->
            prefs.remove(CUSTOM_FONT_NAME)
            prefs[CUSTOM_FONT_REVISION] = (prefs[CUSTOM_FONT_REVISION] ?: 0) + 1
            if (AppFontChoice.fromPreference(prefs[APP_FONT_CHOICE]) == AppFontChoice.CUSTOM) {
                prefs[APP_FONT_CHOICE] = AppFontChoice.SYSTEM.preferenceValue
            }
        }
    }

    suspend fun setAppFontScale(scale: Float) {
        context.dataStore.edit { it[APP_FONT_SCALE] = scale.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE) }
    }

    suspend fun setHomeGridScale(scale: Float) {
        context.dataStore.edit { it[HOME_GRID_SCALE] = scale.coerceIn(MIN_HOME_GRID_SCALE, MAX_HOME_GRID_SCALE) }
    }

    suspend fun setHomeBackgroundType(type: HomeBackgroundType) {
        context.dataStore.edit { prefs ->
            prefs[HOME_BACKGROUND_TYPE] = type.preferenceValue
            // A separate revision makes replacing a file with the same type/path observable.
            prefs[HOME_BACKGROUND_REVISION] = (prefs[HOME_BACKGROUND_REVISION] ?: 0) + 1
        }
    }

    suspend fun setHomeBackgroundPreset(preset: HomeBackgroundPreset) {
        context.dataStore.edit { prefs ->
            prefs[HOME_BACKGROUND_PRESET] = preset.preferenceValue
            prefs[HOME_BACKGROUND_TYPE] = HomeBackgroundType.BUILT_IN.preferenceValue
            prefs[HOME_BACKGROUND_REVISION] = (prefs[HOME_BACKGROUND_REVISION] ?: 0) + 1
        }
    }

    suspend fun setHomeBackgroundDim(dim: Int) {
        context.dataStore.edit { it[HOME_BACKGROUND_DIM] = dim.coerceIn(0, 85) }
    }

    suspend fun setEmulationSideArtwork(artwork: EmulationSideArtwork) {
        context.dataStore.edit { prefs ->
            prefs[EMULATION_SIDE_ARTWORK] = artwork.preferenceValue
            prefs[EMULATION_SIDE_ARTWORK_REVISION] =
                (prefs[EMULATION_SIDE_ARTWORK_REVISION] ?: 0) + 1
        }
    }

    suspend fun setEmulationSideArtworkDim(dim: Int) {
        context.dataStore.edit { it[EMULATION_SIDE_ARTWORK_DIM] = dim.coerceIn(0, 85) }
    }

    suspend fun setLocalMultiplayerMode(mode: Int) {
        context.dataStore.edit { it[LOCAL_MULTIPLAYER_MODE] = normalizeLocalMultiplayerMode(mode) }
    }

    private fun normalizeLocalMultiplayerMode(mode: Int?): Int = when (mode) {
        LOCAL_MULTIPLAYER_SIDE_BY_SIDE,
        LOCAL_MULTIPLAYER_STACKED,
        LOCAL_MULTIPLAYER_HORIZONTAL_CROP,
        LOCAL_MULTIPLAYER_HORIZONTAL_CROP_SWAPPED -> mode
        else -> LOCAL_MULTIPLAYER_OFF
    }

    suspend fun notifyCoverCacheCleared() {
        context.dataStore.edit { prefs ->
            prefs[COVER_CACHE_REVISION] = (prefs[COVER_CACHE_REVISION] ?: 0) + 1
        }
    }

    suspend fun setTouchControlVisualStyle(style: TouchControlVisualStyle) {
        context.dataStore.edit { it[TOUCH_CONTROL_VISUAL_STYLE] = style.preferenceValue }
    }

    suspend fun setTouchControlPressEffect(effect: TouchControlPressEffect) {
        context.dataStore.edit { it[TOUCH_CONTROL_PRESS_EFFECT] = effect.preferenceValue }
    }

    suspend fun setGameMenuLayoutStyle(style: GameMenuLayoutStyle) {
        context.dataStore.edit { it[GAME_MENU_LAYOUT_STYLE] = style.preferenceValue }
    }

    suspend fun setDrawerVisualStyle(style: DrawerVisualStyle) {
        context.dataStore.edit { it[DRAWER_VISUAL_STYLE] = style.preferenceValue }
    }

    suspend fun setHiddenDrawerItems(hidden: Set<DrawerItemId>) {
        val normalized = hidden.filterNot(DrawerItemId::required).toSet()
        context.dataStore.edit {
            it[HIDDEN_DRAWER_ITEMS] = normalized.joinToString(",") { item -> item.name }
        }
    }

    suspend fun setGameMenuTabOrder(order: List<GameMenuTabId>) {
        val normalized = (order + DefaultGameMenuTabOrder).distinct()
        context.dataStore.edit { it[GAME_MENU_TAB_ORDER] = normalized.joinToString(",") { tab -> tab.name } }
    }

    suspend fun setHiddenGameMenuTabs(hidden: Set<GameMenuTabId>) {
        val normalized = hidden.filterNot { it == GameMenuTabId.SESSION }.toSet()
        context.dataStore.edit { it[HIDDEN_GAME_MENU_TABS] = normalized.joinToString(",") { tab -> tab.name } }
    }

    suspend fun setGameMenuSectionOrder(order: List<GameMenuSectionId>) {
        val normalized = sanitizeGameMenuSectionOrder(order.joinToString(",") { it.name })
        context.dataStore.edit {
            it[GAME_MENU_SECTION_ORDER] = normalized.joinToString(",") { section -> section.name }
        }
    }

    suspend fun setHiddenGameMenuSections(hidden: Set<GameMenuSectionId>) {
        context.dataStore.edit {
            it[HIDDEN_GAME_MENU_SECTIONS] = hidden.joinToString(",") { section -> section.name }
        }
    }

    val mediatekSettingsNoticeShown: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[MEDIATEK_SETTINGS_NOTICE_SHOWN] ?: false }
        .distinctUntilChanged()

    suspend fun markMediatekSettingsNoticeShown() {
        context.dataStore.edit { prefs -> prefs[MEDIATEK_SETTINGS_NOTICE_SHOWN] = true }
    }

    suspend fun recordInAppReviewSession(activePlayTimeMs: Long) {
        context.dataStore.edit { prefs ->
            val updated = InAppReviewPolicy.recordSession(
                progress = prefs.inAppReviewProgress(),
                activePlayTimeMs = activePlayTimeMs
            )
            prefs[IN_APP_REVIEW_QUALIFYING_SESSION_COUNT] = updated.qualifyingSessionCount
            prefs[IN_APP_REVIEW_TOTAL_ACTIVE_PLAY_TIME_MS] = updated.totalActivePlayTimeMs
        }
    }

    suspend fun claimInAppReviewAttempt(nowMs: Long = System.currentTimeMillis()): Long? {
        val claimedAtMs = nowMs.coerceAtLeast(1L)
        var claimed = false
        context.dataStore.edit { prefs ->
            if (!InAppReviewPolicy.canAttempt(prefs.inAppReviewProgress(), claimedAtMs)) return@edit
            prefs[IN_APP_REVIEW_LAST_ATTEMPT_AT_MS] = claimedAtMs
            claimed = true
        }
        return claimedAtMs.takeIf { claimed }
    }

    suspend fun releaseInAppReviewAttempt(claimedAtMs: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[IN_APP_REVIEW_LAST_ATTEMPT_AT_MS] == claimedAtMs &&
                prefs[IN_APP_REVIEW_REQUESTED] != true
            ) {
                prefs.remove(IN_APP_REVIEW_LAST_ATTEMPT_AT_MS)
            }
        }
    }

    suspend fun markInAppReviewRequested(claimedAtMs: Long) {
        context.dataStore.edit { prefs ->
            if (prefs[IN_APP_REVIEW_LAST_ATTEMPT_AT_MS] == claimedAtMs) {
                prefs[IN_APP_REVIEW_REQUESTED] = true
            }
        }
    }

    private fun Preferences.inAppReviewProgress(): InAppReviewProgress = InAppReviewProgress(
        qualifyingSessionCount = this[IN_APP_REVIEW_QUALIFYING_SESSION_COUNT] ?: 0,
        totalActivePlayTimeMs = this[IN_APP_REVIEW_TOTAL_ACTIVE_PLAY_TIME_MS] ?: 0L,
        reviewRequested = this[IN_APP_REVIEW_REQUESTED] ?: false,
        lastAttemptAtMs = this[IN_APP_REVIEW_LAST_ATTEMPT_AT_MS] ?: 0L
    )

    val renderer: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizeRendererPreference(prefs[RENDERER])
    }

    val gpuHardwareProfile: Flow<Int> = context.dataStore.data.map { prefs ->
        GpuHardwareProfiles.normalize(prefs[GPU_HARDWARE_PROFILE] ?: GpuHardwareProfiles.ADRENO)
    }


    val gpuDriverType: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[GPU_DRIVER_TYPE] ?: 0
    }

    val customDriverPath: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_DRIVER_PATH]
    }

    suspend fun setRenderer(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[RENDERER] = normalizeRendererPreference(value)
        }
    }

    suspend fun setShaderChain(enabled: Boolean, preset: String) {
        context.dataStore.edit { prefs ->
            prefs[SHADER_CHAIN_ENABLED] = enabled
            prefs[SHADER_CHAIN_PRESET] = preset
        }
    }

    private fun normalizeRendererPreference(value: Int?): Int {
        return RendererDefaults.normalizeAndroidRenderer(value ?: RendererDefaults.AUTO)
    }

    private fun resolveGpuHardwareProfile(): Int {
        return GpuHardwareProfiles.detectHardwareProfile()
    }

    suspend fun setGpuDriverType(value: Int) {
        context.dataStore.edit { it[GPU_DRIVER_TYPE] = value }
    }

    suspend fun setCustomDriverPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path == null) prefs.remove(CUSTOM_DRIVER_PATH)
            else prefs[CUSTOM_DRIVER_PATH] = path
        }
    }

    val frameLimitEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[FRAME_LIMIT_ENABLED] ?: true
    }

    val audioVolume: Flow<Int> = context.dataStore.data.map { prefs ->
        AudioDefaults.coerceVolume(prefs[AUDIO_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT)
    }

    suspend fun setAudioVolume(value: Int) {
        context.dataStore.edit { it[AUDIO_VOLUME] = AudioDefaults.coerceVolume(value) }
    }

    val audioFastForwardVolume: Flow<Int> = context.dataStore.data.map { prefs ->
        AudioDefaults.coerceVolume(prefs[AUDIO_FAST_FORWARD_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT)
    }

    suspend fun setAudioFastForwardVolume(value: Int) {
        context.dataStore.edit { it[AUDIO_FAST_FORWARD_VOLUME] = AudioDefaults.coerceVolume(value) }
    }

    val audioMuted: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[AUDIO_MUTED] ?: false }

    suspend fun setAudioMuted(muted: Boolean) {
        context.dataStore.edit { it[AUDIO_MUTED] = muted }
    }


    val audioOutputLatencyMs: Flow<Int> = context.dataStore.data.map { prefs ->
        AudioDefaults.coerceOutputLatencyMs(
            prefs[AUDIO_OUTPUT_LATENCY_MS] ?: AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT
        )
    }

    suspend fun setAudioOutputLatencyMs(value: Int) {
        context.dataStore.edit { it[AUDIO_OUTPUT_LATENCY_MS] = AudioDefaults.coerceOutputLatencyMs(value) }
    }

    val audioMinimalOutputLatency: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUDIO_MINIMAL_OUTPUT_LATENCY] ?: AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT
    }

    suspend fun setAudioMinimalOutputLatency(enabled: Boolean) {
        context.dataStore.edit { it[AUDIO_MINIMAL_OUTPUT_LATENCY] = enabled }
    }

    suspend fun setFrameLimitEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FRAME_LIMIT_ENABLED] = enabled
        }
    }

    val vSyncEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[VSYNC_ENABLED] ?: false
    }

    suspend fun setVSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VSYNC_ENABLED] = enabled }
    }

    val fastForwardSpeed: Flow<Float> = context.dataStore.data.map { prefs ->
        sanitizeFastForwardSpeed(prefs[FAST_FORWARD_SPEED])
    }

    suspend fun setFastForwardSpeed(value: Float) {
        context.dataStore.edit { it[FAST_FORWARD_SPEED] = sanitizeFastForwardSpeed(value) }
    }

    val targetFps: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TARGET_FPS] ?: 0
    }

    suspend fun setTargetFps(value: Int) {
        context.dataStore.edit { it[TARGET_FPS] = if (value <= 0) 0 else value.coerceIn(20, 120) }
    }

    val ntscFramerate: Flow<Float> = context.dataStore.data.map { prefs ->
        sanitizeRegionFramerate(prefs[NTSC_FRAMERATE], DEFAULT_NTSC_FRAMERATE)
    }

    suspend fun setNtscFramerate(value: Float) {
        context.dataStore.edit { it[NTSC_FRAMERATE] = sanitizeRegionFramerate(value, DEFAULT_NTSC_FRAMERATE) }
    }

    val palFramerate: Flow<Float> = context.dataStore.data.map { prefs ->
        sanitizeRegionFramerate(prefs[PAL_FRAMERATE], DEFAULT_PAL_FRAMERATE)
    }

    suspend fun setPalFramerate(value: Float) {
        context.dataStore.edit { it[PAL_FRAMERATE] = sanitizeRegionFramerate(value, DEFAULT_PAL_FRAMERATE) }
    }

    val autoSaveEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_SAVE_ENABLED] ?: false
    }

    suspend fun setAutoSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_SAVE_ENABLED] = enabled }
    }

    val autoSaveIntervalMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[AUTO_SAVE_INTERVAL_MINUTES] ?: 1).coerceIn(1, 999)
    }

    suspend fun setAutoSaveIntervalMinutes(value: Int) {
        context.dataStore.edit { it[AUTO_SAVE_INTERVAL_MINUTES] = value.coerceIn(1, 999) }
    }


    suspend fun resetAllSettings() {
        context.dataStore.edit { prefs ->
            // Acknowledged compatibility notices are not user settings. Preserve them so a
            // settings reset does not make a one-time notice appear again.
            val mediatekNoticeWasShown = prefs[MEDIATEK_SETTINGS_NOTICE_SHOWN] == true
            prefs.clear()
            if (mediatekNoticeWasShown) {
                prefs[MEDIATEK_SETTINGS_NOTICE_SHOWN] = true
            }
        }
        localePrefs.edit().remove("language_tag").apply()
    }


    val memoryCardSlot1: Flow<String?> = context.dataStore.data.map { prefs -> prefs[MEMORY_CARD_SLOT1] }
    val memoryCardSlot2: Flow<String?> = context.dataStore.data.map { prefs -> prefs[MEMORY_CARD_SLOT2] }

    val memoryCardsInitialized: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[MEMORY_CARDS_INITIALIZED] == true }
        .distinctUntilChanged()

    suspend fun markMemoryCardsInitialized() {
        context.dataStore.edit { prefs -> prefs[MEMORY_CARDS_INITIALIZED] = true }
    }

    suspend fun setMemoryCardAssignments(slot1: String?, slot2: String?) {
        context.dataStore.edit { prefs ->
            slot1?.let { prefs[MEMORY_CARD_SLOT1] = it } ?: prefs.remove(MEMORY_CARD_SLOT1)
            slot2?.let { prefs[MEMORY_CARD_SLOT2] = it } ?: prefs.remove(MEMORY_CARD_SLOT2)
        }
    }

    val upscaleMultiplier: Flow<Float> = context.dataStore.data.map { prefs ->
        readUpscale(prefs)
    }

    suspend fun setUpscaleMultiplier(value: Float) {
        context.dataStore.edit { it[UPSCALE] = normalizeUpscale(value) }
    }

    // BIOS Path
    val biosPath: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[BIOS_PATH]
    }

    suspend fun setBiosPath(path: String) {
        context.dataStore.edit { it[BIOS_PATH] = path }
    }

    // Game Path
    val gamePaths: Flow<List<String>> = context.dataStore.data.map(::readGamePaths)

    val gamePath: Flow<String?> = gamePaths.map { it.firstOrNull() }

    suspend fun setGamePath(path: String) {
        setGamePaths(listOf(path))
    }

    suspend fun setGamePaths(paths: List<String>) {
        val normalized = paths.map(String::trim).filter(String::isNotBlank).distinct()
        context.dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(GAME_PATHS)
                prefs.remove(GAME_PATH)
            } else {
                prefs[GAME_PATHS] = JSONArray(normalized).toString()
                prefs[GAME_PATH] = normalized.first()
            }
        }
    }

    suspend fun addGamePath(path: String) {
        val normalized = path.trim()
        if (normalized.isBlank()) return
        context.dataStore.edit { prefs ->
            val paths = (readGamePaths(prefs) + normalized).distinct()
            prefs[GAME_PATHS] = JSONArray(paths).toString()
            prefs[GAME_PATH] = paths.first()
        }
    }

    suspend fun removeGamePath(path: String) {
        context.dataStore.edit { prefs ->
            val paths = readGamePaths(prefs).filterNot { it == path }
            if (paths.isEmpty()) {
                prefs.remove(GAME_PATHS)
                prefs.remove(GAME_PATH)
            } else {
                prefs[GAME_PATHS] = JSONArray(paths).toString()
                prefs[GAME_PATH] = paths.first()
            }
        }
    }

    private fun readGamePaths(prefs: Preferences): List<String> {
        val stored = prefs[GAME_PATHS]
        val paths = stored?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.getOrNull()
        }.orEmpty()
        return (paths.ifEmpty { listOfNotNull(prefs[GAME_PATH]?.trim()?.takeIf(String::isNotBlank)) }).distinct()
    }

    val emulatorDataPath: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[EMULATOR_DATA_PATH]
    }

    suspend fun setEmulatorDataPath(path: String?) {
        context.dataStore.edit { prefs ->
            path?.takeIf { it.isNotBlank() }?.let {
                prefs[EMULATOR_DATA_PATH] = it
            } ?: prefs.remove(EMULATOR_DATA_PATH)
        }
    }

    fun getEmulatorDataPathSync(): String? {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.map { it[EMULATOR_DATA_PATH] }.first()
        }
    }

    val coverDownloadBaseUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[COVER_DOWNLOAD_BASE_URL]
    }

    suspend fun setCoverDownloadBaseUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(COVER_DOWNLOAD_BASE_URL)
            } else {
                prefs[COVER_DOWNLOAD_BASE_URL] = url.trim().trimEnd('/')
            }
        }
    }

    fun getCoverDownloadBaseUrlSync(): String? {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.map { it[COVER_DOWNLOAD_BASE_URL] }.first()
        }
    }

    private fun readCoverArtStyle(prefs: Preferences): Int = when (prefs[COVER_ART_STYLE]) {
        COVER_ART_STYLE_DISABLED -> COVER_ART_STYLE_DISABLED
        COVER_ART_STYLE_DEFAULT -> COVER_ART_STYLE_DEFAULT
        COVER_ART_STYLE_3D -> COVER_ART_STYLE_3D
        // Migrate old automatic artwork to PSP 3D once, preserving disabled artwork.
        else -> if (prefs[LEGACY_COVER_ART_STYLE] == COVER_ART_STYLE_DISABLED)
            COVER_ART_STYLE_DISABLED else COVER_ART_STYLE_3D
    }

    val coverArtStyle: Flow<Int> = context.dataStore.data.map { prefs ->
        readCoverArtStyle(prefs)
    }

    suspend fun setCoverArtStyle(style: Int) {
        context.dataStore.edit { prefs ->
            prefs[COVER_ART_STYLE] = when (style) {
                COVER_ART_STYLE_DISABLED -> COVER_ART_STYLE_DISABLED
                COVER_ART_STYLE_3D -> COVER_ART_STYLE_3D
                else -> COVER_ART_STYLE_DEFAULT
            }
        }
    }

    fun getCoverArtStyleSync(): Int {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.map { prefs ->
                readCoverArtStyle(prefs)
            }.first()
        }
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    val languageTag: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[LANGUAGE_TAG]
    }

    suspend fun setLanguageTag(tag: String?) {
        localePrefs.edit().putString("language_tag", tag).apply()
        context.dataStore.edit { prefs ->
            if (tag.isNullOrBlank()) {
                prefs.remove(LANGUAGE_TAG)
            } else {
                prefs[LANGUAGE_TAG] = tag
            }
        }
    }

    fun getStoredLanguageTagSync(): String? {
        return localePrefs.getString("language_tag", null)
    }

    val settingsSnapshot: Flow<SettingsSnapshot> = context.dataStore.data
        .map { prefs ->
            val biosPath = prefs[BIOS_PATH]
            val gpuHardwareProfile = resolveGpuHardwareProfile()
            SettingsSnapshot(
                themeMode = readThemeMode(prefs),
                customTheme = readCustomThemeLibrary(prefs).activeTheme()?.config
                    ?: CustomThemeConfig.Default,
                customThemeLibrary = readCustomThemeLibrary(prefs),
                customTouchControls = CustomTouchControlLibrary.decode(
                    prefs[CUSTOM_TOUCH_CONTROLS_JSON]
                ),
                appFontChoice = AppFontChoice.fromPreference(prefs[APP_FONT_CHOICE]),
                appFontScale = (prefs[APP_FONT_SCALE] ?: DEFAULT_APP_FONT_SCALE)
                    .coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE),
                customFontName = prefs[CUSTOM_FONT_NAME]?.takeIf(String::isNotBlank),
                customFontRevision = (prefs[CUSTOM_FONT_REVISION] ?: 0).coerceAtLeast(0),
                homeGridScale = (prefs[HOME_GRID_SCALE] ?: DEFAULT_HOME_GRID_SCALE)
                    .coerceIn(MIN_HOME_GRID_SCALE, MAX_HOME_GRID_SCALE),
                homeBackgroundType = HomeBackgroundType.fromPreference(prefs[HOME_BACKGROUND_TYPE]),
                homeBackgroundPreset = HomeBackgroundPreset.fromPreference(prefs[HOME_BACKGROUND_PRESET]),
                homeBackgroundRevision = (prefs[HOME_BACKGROUND_REVISION] ?: 0).coerceAtLeast(0),
                homeBackgroundDim = (prefs[HOME_BACKGROUND_DIM] ?: DEFAULT_HOME_BACKGROUND_DIM)
                    .coerceIn(0, 85),
                emulationSideArtwork = EmulationSideArtwork.fromPreference(prefs[EMULATION_SIDE_ARTWORK]),
                emulationSideArtworkRevision = (prefs[EMULATION_SIDE_ARTWORK_REVISION] ?: 0)
                    .coerceAtLeast(0),
                emulationSideArtworkDim =
                    (prefs[EMULATION_SIDE_ARTWORK_DIM] ?: DEFAULT_EMULATION_SIDE_ARTWORK_DIM)
                        .coerceIn(0, 85),
                localMultiplayerMode = normalizeLocalMultiplayerMode(prefs[LOCAL_MULTIPLAYER_MODE]),
                touchControlVisualStyle = TouchControlVisualStyle.fromPreference(prefs[TOUCH_CONTROL_VISUAL_STYLE]),
                touchControlPressEffect = TouchControlPressEffect.fromPreference(prefs[TOUCH_CONTROL_PRESS_EFFECT]),
                gameMenuLayoutStyle = GameMenuLayoutStyle.fromPreference(prefs[GAME_MENU_LAYOUT_STYLE]),
                drawerVisualStyle = DrawerVisualStyle.fromPreference(prefs[DRAWER_VISUAL_STYLE]),
                hiddenDrawerItems = sanitizeHiddenDrawerItems(prefs[HIDDEN_DRAWER_ITEMS]),
                gameMenuTabOrder = sanitizeGameMenuTabOrder(prefs[GAME_MENU_TAB_ORDER]),
                hiddenGameMenuTabs = sanitizeHiddenGameMenuTabs(prefs[HIDDEN_GAME_MENU_TABS]),
                gameMenuSectionOrder = sanitizeGameMenuSectionOrder(prefs[GAME_MENU_SECTION_ORDER]),
                hiddenGameMenuSections = sanitizeHiddenGameMenuSections(prefs[HIDDEN_GAME_MENU_SECTIONS]),
                languageTag = prefs[LANGUAGE_TAG],
                tvInterfaceMode = TvInterfaceMode.fromPreference(prefs[TV_INTERFACE_MODE]),
                gpuHardwareProfile = gpuHardwareProfile,
                renderer = normalizeRendererPreference(prefs[RENDERER]),
                upscaleMultiplier = readUpscale(prefs),
                aspectRatio = normalizeAspectRatioPreference(prefs[ASPECT_RATIO]),
                displayCrop = readDisplayCrop(prefs),
                shaderChainEnabled = prefs[SHADER_CHAIN_ENABLED] ?: false,
                shaderChainPreset = prefs[SHADER_CHAIN_PRESET].orEmpty(),
                audioVolume = AudioDefaults.coerceVolume(
                    prefs[AUDIO_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT
                ),
                audioFastForwardVolume = AudioDefaults.coerceVolume(
                    prefs[AUDIO_FAST_FORWARD_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT
                ),
                audioMuted = prefs[AUDIO_MUTED] ?: false,
                audioOutputLatencyMs = AudioDefaults.coerceOutputLatencyMs(
                    prefs[AUDIO_OUTPUT_LATENCY_MS] ?: AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT
                ),
                audioMinimalOutputLatency = prefs[AUDIO_MINIMAL_OUTPUT_LATENCY]
                    ?: AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT,
                padVibration = prefs[PAD_VIBRATION] ?: true,
                padVibrationStrength = (prefs[PAD_VIBRATION_STRENGTH] ?: DEFAULT_PAD_VIBRATION_STRENGTH).coerceIn(0, 150),
                padVibrationFallback = prefs[PAD_VIBRATION_FALLBACK] ?: true,
                showFps = prefs[SHOW_FPS] ?: false,
                fpsOverlayMode = prefs[FPS_OVERLAY_MODE] ?: FPS_OVERLAY_MODE_DETAILED,
                fpsOverlayCorner = when (prefs[FPS_OVERLAY_CORNER]) {
                    FPS_OVERLAY_CORNER_TOP_LEFT,
                    FPS_OVERLAY_CORNER_TOP_RIGHT,
                    FPS_OVERLAY_CORNER_BOTTOM_LEFT,
                    FPS_OVERLAY_CORNER_BOTTOM_RIGHT -> prefs[FPS_OVERLAY_CORNER] ?: FPS_OVERLAY_CORNER_TOP_RIGHT
                    else -> FPS_OVERLAY_CORNER_TOP_RIGHT
                },
                fpsOverlayScale = (prefs[FPS_OVERLAY_SCALE] ?: DEFAULT_FPS_OVERLAY_SCALE).coerceIn(
                    MIN_FPS_OVERLAY_SCALE,
                    MAX_FPS_OVERLAY_SCALE
                ),
                fpsOverlayMetrics = PerformanceOverlayMetrics.sanitize(
                    prefs[FPS_OVERLAY_METRICS] ?: PerformanceOverlayMetrics.DEFAULT
                ),
                confirmSaveLoadActions = prefs[CONFIRM_SAVE_LOAD_ACTIONS] ?: true,
                backButtonExitsGame = prefs[BACK_BUTTON_EXITS_GAME] ?: false,
                compactControls = prefs[COMPACT_CONTROLS] ?: true,
                keepScreenOn = prefs[KEEP_SCREEN_ON] ?: true,
                showRecentGames = prefs[SHOW_RECENT_GAMES] ?: true,
                showHomeSearch = prefs[SHOW_HOME_SEARCH] ?: false,
                showDebugOptions = prefs[SHOW_DEBUG_OPTIONS] ?: false,
                profilerLogcat = prefs[PROFILER_LOGCAT] ?: false,
                preferEnglishGameTitles = prefs[PREFER_ENGLISH_GAME_TITLES] ?: false,
                biosPath = biosPath,
                gamePath = readGamePaths(prefs).firstOrNull(),
                gamePaths = readGamePaths(prefs),
                emulatorDataPath = prefs[EMULATOR_DATA_PATH],
                coverDownloadBaseUrl = prefs[COVER_DOWNLOAD_BASE_URL],
                coverArtStyle = readCoverArtStyle(prefs),
                setupComplete = prefs[ONBOARDING_COMPLETED] ?: false,
                overlayScale = prefs[OVERLAY_SCALE] ?: 100,
                overlayOpacity = (prefs[OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY)
                    .coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX),
                overlayShow = prefs[OVERLAY_SHOW] ?: true,
                racingMode = prefs[RACING_MODE] ?: false,
                touchscreenRightStick = prefs[TOUCHSCREEN_RIGHT_STICK] ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK,
                touchscreenRightStickSensitivity = (prefs[TOUCHSCREEN_RIGHT_STICK_SENSITIVITY]
                    ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY).coerceIn(
                    TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                    TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
                ),
                touchHaptics = prefs[TOUCH_HAPTICS] ?: false,
                touchHapticsPreset = (prefs[TOUCH_HAPTICS_PRESET] ?: DEFAULT_TOUCH_HAPTICS_PRESET).coerceIn(TOUCH_HAPTICS_PRESET_SOFT, TOUCH_HAPTICS_PRESET_STRONG),
                touchHapticsStrength = (prefs[TOUCH_HAPTICS_STRENGTH] ?: DEFAULT_TOUCH_HAPTICS_STRENGTH).coerceIn(10, 100),
                gyroMode = (prefs[GYRO_MODE] ?: GYRO_MODE_OFF).coerceIn(GYRO_MODE_OFF, GYRO_MODE_STEERING),
                gyroSensitivity = (prefs[GYRO_SENSITIVITY] ?: DEFAULT_GYRO_SENSITIVITY).coerceIn(25, 300),
                gyroSmoothing = (prefs[GYRO_SMOOTHING] ?: DEFAULT_GYRO_SMOOTHING).coerceIn(0, 90),
                gyroInvertX = prefs[GYRO_INVERT_X] ?: false,
                gyroInvertY = prefs[GYRO_INVERT_Y] ?: false,
                leftStickSensitivity = prefs[LEFT_STICK_SENSITIVITY] ?: DEFAULT_STICK_SENSITIVITY,
                rightStickSensitivity = prefs[RIGHT_STICK_SENSITIVITY] ?: DEFAULT_STICK_SENSITIVITY,
                invertLeftStick = prefs[INVERT_LEFT_STICK] ?: false,
                invertRightStick = prefs[INVERT_RIGHT_STICK] ?: false,
                invertLeftStickHorizontal = prefs[INVERT_LEFT_STICK_HORIZONTAL] ?: false,
                invertRightStickHorizontal = prefs[INVERT_RIGHT_STICK_HORIZONTAL] ?: false,
                enableAutoGamepad = prefs[ENABLE_AUTO_GAMEPAD] ?: true,
                hideOverlayOnGamepad = prefs[HIDE_OVERLAY_ON_GAMEPAD] ?: true,
                gamepadStickDeadzone = prefs[GAMEPAD_STICK_DEADZONE] ?: DEFAULT_GAMEPAD_STICK_DEADZONE,
                gamepadLeftStickSensitivity = prefs[GAMEPAD_LEFT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY,
                gamepadRightStickSensitivity = prefs[GAMEPAD_RIGHT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY,
                gamepadRightStickUpToR2 = prefs[GAMEPAD_RIGHT_STICK_UP_TO_R2] ?: false,
                gamepadRightStickDownToL2 = prefs[GAMEPAD_RIGHT_STICK_DOWN_TO_L2] ?: false,
                gamepadButtonHaptics = prefs[GAMEPAD_BUTTON_HAPTICS] ?: false,
                pressureModifierAmount = (prefs[PRESSURE_MODIFIER_AMOUNT] ?: DEFAULT_PRESSURE_MODIFIER_AMOUNT).coerceIn(1, 100),
                gamepadBindings = decodeGamepadBindings(prefs[GAMEPAD_BINDINGS]),
                gamepadBindingsByPad = decodeGamepadBindingsByPad(prefs[GAMEPAD_BINDINGS]),
                gamepadDeviceAssignments = decodeGamepadDeviceAssignments(prefs[GAMEPAD_DEVICE_ASSIGNMENTS]),
                ignoredGamepadDevices = decodeIgnoredGamepadDevices(prefs[GAMEPAD_IGNORED_DEVICES]),
                gpuDriverType = prefs[GPU_DRIVER_TYPE] ?: 0,
                mediatekAngleOpenGl = prefs[MEDIATEK_ANGLE_OPENGL] ?: false,
                customDriverPath = prefs[CUSTOM_DRIVER_PATH],
                frameLimitEnabled = prefs[FRAME_LIMIT_ENABLED] ?: true,
                floatingQuickActionsEnabled = prefs[FLOATING_QUICK_ACTIONS_ENABLED] ?: false,
                vSyncEnabled = prefs[VSYNC_ENABLED] ?: false,
                fastForwardSpeed = sanitizeFastForwardSpeed(prefs[FAST_FORWARD_SPEED]),
                targetFps = prefs[TARGET_FPS] ?: 0,
                ntscFramerate = sanitizeRegionFramerate(prefs[NTSC_FRAMERATE], DEFAULT_NTSC_FRAMERATE),
                palFramerate = sanitizeRegionFramerate(prefs[PAL_FRAMERATE], DEFAULT_PAL_FRAMERATE)
            )
        }
        .distinctUntilChanged()

    val overlayLayoutSnapshot: Flow<OverlayLayoutSnapshot> = context.dataStore.data
        .map { prefs ->
            OverlayLayoutSnapshot(
                overlayScale = prefs[OVERLAY_SCALE] ?: 100,
                overlayOpacity = (prefs[OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY)
                    .coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX),
                hideOverlayOnGamepad = prefs[HIDE_OVERLAY_ON_GAMEPAD] ?: true,
                dpadOffset = parseOffsetStr(
                    prefs[DPAD_OFFSET],
                    DEFAULT_DPAD_OFFSET_X to DEFAULT_DPAD_OFFSET_Y
                ),
                lstickOffset = parseOffsetStr(
                    prefs[LSTICK_OFFSET],
                    DEFAULT_LSTICK_OFFSET_X to DEFAULT_LSTICK_OFFSET_Y
                ),
                rstickOffset = parseOffsetStr(
                    prefs[RSTICK_OFFSET],
                    DEFAULT_RSTICK_OFFSET_X to DEFAULT_RSTICK_OFFSET_Y
                ),
                actionOffset = parseOffsetStr(
                    prefs[ACTION_OFFSET],
                    DEFAULT_ACTION_OFFSET_X to DEFAULT_ACTION_OFFSET_Y
                ),
                lbtnOffset = parseOffsetStr(
                    prefs[LBTN_OFFSET],
                    DEFAULT_LBTN_OFFSET_X to DEFAULT_LBTN_OFFSET_Y
                ),
                rbtnOffset = parseOffsetStr(
                    prefs[RBTN_OFFSET],
                    DEFAULT_RBTN_OFFSET_X to DEFAULT_RBTN_OFFSET_Y
                ),
                centerOffset = parseOffsetStr(
                    prefs[CENTER_OFFSET],
                    DEFAULT_CENTER_OFFSET_X to DEFAULT_CENTER_OFFSET_Y
                ),
                stickScale = (prefs[STICK_SCALE] ?: OVERLAY_CONTROL_SCALE_DEFAULT)
                    .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX),
                leftStickSensitivity = prefs[LEFT_STICK_SENSITIVITY] ?: 100,
                rightStickSensitivity = prefs[RIGHT_STICK_SENSITIVITY] ?: 100,
                invertLeftStick = prefs[INVERT_LEFT_STICK] ?: false,
                invertRightStick = prefs[INVERT_RIGHT_STICK] ?: false,
                invertLeftStickHorizontal = prefs[INVERT_LEFT_STICK_HORIZONTAL] ?: false,
                invertRightStickHorizontal = prefs[INVERT_RIGHT_STICK_HORIZONTAL] ?: false,
                stickSurfaceMode = prefs[STICK_SURFACE_MODE] ?: false,
                controlLayouts = decodeControlLayouts(prefs[CONTROL_LAYOUTS])
            )
        }
        .distinctUntilChanged()

    val aspectRatio: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizeAspectRatioPreference(prefs[ASPECT_RATIO])
    }

    suspend fun setAspectRatio(value: Int) {
        context.dataStore.edit { it[ASPECT_RATIO] = normalizeAspectRatioPreference(value) }
    }

    val displayCrop: Flow<DisplayCrop> = context.dataStore.data
        .map(::readDisplayCrop)
        .distinctUntilChanged()

    suspend fun setDisplayCrop(value: DisplayCrop) {
        val crop = value.sanitized()
        context.dataStore.edit { prefs ->
            prefs[DISPLAY_CROP_LEFT] = crop.left
            prefs[DISPLAY_CROP_TOP] = crop.top
            prefs[DISPLAY_CROP_RIGHT] = crop.right
            prefs[DISPLAY_CROP_BOTTOM] = crop.bottom
        }
    }

    private fun readDisplayCrop(prefs: Preferences): DisplayCrop = DisplayCrop(
        left = prefs[DISPLAY_CROP_LEFT] ?: 0,
        top = prefs[DISPLAY_CROP_TOP] ?: 0,
        right = prefs[DISPLAY_CROP_RIGHT] ?: 0,
        bottom = prefs[DISPLAY_CROP_BOTTOM] ?: 0
    ).sanitized()


    private fun normalizeAspectRatioPreference(value: Int?): Int {
        return when (value) {
            0, 1, 2, 3, 4 -> value
            else -> 1
        }
    }

    val padVibration: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PAD_VIBRATION] ?: true
    }

    suspend fun setPadVibration(enabled: Boolean) {
        context.dataStore.edit { it[PAD_VIBRATION] = enabled }
    }

    val padVibrationStrength: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[PAD_VIBRATION_STRENGTH] ?: DEFAULT_PAD_VIBRATION_STRENGTH).coerceIn(0, 150)
    }

    suspend fun setPadVibrationStrength(value: Int) {
        context.dataStore.edit { it[PAD_VIBRATION_STRENGTH] = value.coerceIn(0, 150) }
    }

    val padVibrationFallback: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PAD_VIBRATION_FALLBACK] ?: true
    }

    suspend fun setPadVibrationFallback(enabled: Boolean) {
        context.dataStore.edit { it[PAD_VIBRATION_FALLBACK] = enabled }
    }

    val touchHaptics: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TOUCH_HAPTICS] ?: false
    }

    suspend fun setTouchHaptics(enabled: Boolean) {
        context.dataStore.edit { it[TOUCH_HAPTICS] = enabled }
    }

    val touchHapticsPreset: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[TOUCH_HAPTICS_PRESET] ?: DEFAULT_TOUCH_HAPTICS_PRESET)
            .coerceIn(TOUCH_HAPTICS_PRESET_SOFT, TOUCH_HAPTICS_PRESET_STRONG)
    }

    suspend fun setTouchHapticsPreset(value: Int) {
        context.dataStore.edit {
            it[TOUCH_HAPTICS_PRESET] = value.coerceIn(TOUCH_HAPTICS_PRESET_SOFT, TOUCH_HAPTICS_PRESET_STRONG)
        }
    }

    val touchHapticsStrength: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[TOUCH_HAPTICS_STRENGTH] ?: DEFAULT_TOUCH_HAPTICS_STRENGTH).coerceIn(10, 100)
    }

    suspend fun setTouchHapticsStrength(value: Int) {
        context.dataStore.edit { it[TOUCH_HAPTICS_STRENGTH] = value.coerceIn(10, 100) }
    }

    val gyroMode: Flow<Int> = context.dataStore.data.map { (it[GYRO_MODE] ?: GYRO_MODE_OFF).coerceIn(GYRO_MODE_OFF, GYRO_MODE_STEERING) }
    suspend fun setGyroMode(value: Int) { context.dataStore.edit { it[GYRO_MODE] = value.coerceIn(GYRO_MODE_OFF, GYRO_MODE_STEERING) } }
    val gyroSensitivity: Flow<Int> = context.dataStore.data.map { (it[GYRO_SENSITIVITY] ?: DEFAULT_GYRO_SENSITIVITY).coerceIn(25, 300) }
    suspend fun setGyroSensitivity(value: Int) { context.dataStore.edit { it[GYRO_SENSITIVITY] = value.coerceIn(25, 300) } }
    val gyroSmoothing: Flow<Int> = context.dataStore.data.map { (it[GYRO_SMOOTHING] ?: DEFAULT_GYRO_SMOOTHING).coerceIn(0, 90) }
    suspend fun setGyroSmoothing(value: Int) { context.dataStore.edit { it[GYRO_SMOOTHING] = value.coerceIn(0, 90) } }
    val gyroInvertX: Flow<Boolean> = context.dataStore.data.map { it[GYRO_INVERT_X] ?: false }
    suspend fun setGyroInvertX(value: Boolean) { context.dataStore.edit { it[GYRO_INVERT_X] = value } }
    val gyroInvertY: Flow<Boolean> = context.dataStore.data.map { it[GYRO_INVERT_Y] ?: false }
    suspend fun setGyroInvertY(value: Boolean) { context.dataStore.edit { it[GYRO_INVERT_Y] = value } }

    val gamepadButtonHaptics: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_BUTTON_HAPTICS] ?: false
    }

    suspend fun setGamepadButtonHaptics(enabled: Boolean) {
        context.dataStore.edit { it[GAMEPAD_BUTTON_HAPTICS] = enabled }
    }

    val pressureModifierAmount: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[PRESSURE_MODIFIER_AMOUNT] ?: DEFAULT_PRESSURE_MODIFIER_AMOUNT).coerceIn(1, 100)
    }

    suspend fun setPressureModifierAmount(value: Int) {
        context.dataStore.edit { it[PRESSURE_MODIFIER_AMOUNT] = value.coerceIn(1, 100) }
    }

    val showFps: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_FPS] ?: false
    }

    suspend fun setShowFps(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_FPS] = enabled }
    }

    val fpsOverlayMode: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[FPS_OVERLAY_MODE] ?: FPS_OVERLAY_MODE_DETAILED
    }

    suspend fun setFpsOverlayMode(mode: Int) {
        context.dataStore.edit { it[FPS_OVERLAY_MODE] = mode }
    }

    val fpsOverlayCorner: Flow<Int> = context.dataStore.data.map { prefs ->
        when (prefs[FPS_OVERLAY_CORNER]) {
            FPS_OVERLAY_CORNER_TOP_LEFT,
            FPS_OVERLAY_CORNER_TOP_RIGHT,
            FPS_OVERLAY_CORNER_BOTTOM_LEFT,
            FPS_OVERLAY_CORNER_BOTTOM_RIGHT -> prefs[FPS_OVERLAY_CORNER] ?: FPS_OVERLAY_CORNER_TOP_RIGHT
            else -> FPS_OVERLAY_CORNER_TOP_RIGHT
        }
    }

    suspend fun setFpsOverlayCorner(corner: Int) {
        context.dataStore.edit {
            it[FPS_OVERLAY_CORNER] = when (corner) {
                FPS_OVERLAY_CORNER_TOP_LEFT,
                FPS_OVERLAY_CORNER_TOP_RIGHT,
                FPS_OVERLAY_CORNER_BOTTOM_LEFT,
                FPS_OVERLAY_CORNER_BOTTOM_RIGHT -> corner
                else -> FPS_OVERLAY_CORNER_TOP_RIGHT
            }
        }
    }

    val fpsOverlayScale: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[FPS_OVERLAY_SCALE] ?: DEFAULT_FPS_OVERLAY_SCALE).coerceIn(
            MIN_FPS_OVERLAY_SCALE,
            MAX_FPS_OVERLAY_SCALE
        )
    }.distinctUntilChanged()

    suspend fun setFpsOverlayScale(scale: Int) {
        context.dataStore.edit {
            it[FPS_OVERLAY_SCALE] = scale.coerceIn(MIN_FPS_OVERLAY_SCALE, MAX_FPS_OVERLAY_SCALE)
        }
    }

    val fpsOverlayMetrics: Flow<Int> = context.dataStore.data.map { prefs ->
        PerformanceOverlayMetrics.sanitize(
            prefs[FPS_OVERLAY_METRICS] ?: PerformanceOverlayMetrics.DEFAULT
        )
    }.distinctUntilChanged()

    suspend fun setFpsOverlayMetrics(metrics: Int) {
        context.dataStore.edit {
            it[FPS_OVERLAY_METRICS] = PerformanceOverlayMetrics.sanitize(metrics)
        }
    }

    val confirmSaveLoadActions: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[CONFIRM_SAVE_LOAD_ACTIONS] ?: true
    }

    suspend fun setConfirmSaveLoadActions(enabled: Boolean) {
        context.dataStore.edit { it[CONFIRM_SAVE_LOAD_ACTIONS] = enabled }
    }

    val backButtonExitsGame: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BACK_BUTTON_EXITS_GAME] ?: false
    }.distinctUntilChanged()

    suspend fun setBackButtonExitsGame(enabled: Boolean) {
        context.dataStore.edit { it[BACK_BUTTON_EXITS_GAME] = enabled }
    }

    val compactControls: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[COMPACT_CONTROLS] ?: true
    }

    suspend fun setCompactControls(enabled: Boolean) {
        context.dataStore.edit { it[COMPACT_CONTROLS] = enabled }
    }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEEP_SCREEN_ON] ?: true
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    val showRecentGames: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_RECENT_GAMES] ?: true
    }

    suspend fun setShowRecentGames(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_RECENT_GAMES] = enabled }
    }

    val showHomeSearch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_HOME_SEARCH] ?: false
    }

    suspend fun setShowHomeSearch(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_HOME_SEARCH] = enabled }
    }

    val showDebugOptions: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SHOW_DEBUG_OPTIONS] ?: false
    }

    suspend fun setShowDebugOptions(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_DEBUG_OPTIONS] = enabled }
    }


    val profilerLogcat: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PROFILER_LOGCAT] ?: false
    }

    suspend fun setProfilerLogcat(enabled: Boolean) {
        context.dataStore.edit { it[PROFILER_LOGCAT] = enabled }
    }

    fun profilerLogcatSync(): Boolean {
        return kotlinx.coroutines.runBlocking {
            context.dataStore.data.map { it[PROFILER_LOGCAT] ?: false }.first()
        }
    }

    val preferEnglishGameTitles: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PREFER_ENGLISH_GAME_TITLES] ?: false
    }

    suspend fun setPreferEnglishGameTitles(enabled: Boolean) {
        context.dataStore.edit { it[PREFER_ENGLISH_GAME_TITLES] = enabled }
    }

    val recentGames: Flow<List<RecentGameEntry>> = context.dataStore.data.map { prefs ->
        decodeRecentGames(prefs[RECENT_GAMES])
    }

    val homeLibraryViewMode: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HOME_LIBRARY_VIEW_MODE] ?: 0
    }

    suspend fun setHomeLibraryViewMode(mode: Int) {
        context.dataStore.edit { it[HOME_LIBRARY_VIEW_MODE] = mode.coerceIn(0, 2) }
    }

    suspend fun markGameLaunched(path: String, title: String, serial: String? = null) {
        context.dataStore.edit { prefs ->
            val cleanTitle = sanitizeRecentTitle(path, title, serial)
            val updated = buildList {
                add(
                    RecentGameEntry(
                        path = path,
                        title = cleanTitle,
                        lastPlayedAt = System.currentTimeMillis(),
                        serial = serial
                    )
                )
                addAll(
                    decodeRecentGames(prefs[RECENT_GAMES]).filterNot { it.path == path }
                )
            }.take(MAX_RECENT_GAMES)
            prefs[RECENT_GAMES] = encodeRecentGames(updated)
        }
    }

    private fun decodeRecentGames(raw: String?): List<RecentGameEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    val serial = item.optString("serial").takeIf { it.isNotBlank() }
                    if (path.isBlank()) continue
                    add(
                        RecentGameEntry(
                            path = path,
                            title = sanitizeRecentTitle(path, item.optString("title"), serial),
                            lastPlayedAt = item.optLong("lastPlayedAt"),
                            serial = serial
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun sanitizeRecentTitle(path: String, rawTitle: String, serial: String?): String {
        return EmulatorBridge.cleanGameDisplayTitle(rawTitle, path)
    }

    private fun encodeRecentGames(items: List<RecentGameEntry>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("path", item.path)
                    put("title", item.title)
                    put("lastPlayedAt", item.lastPlayedAt)
                    put("serial", item.serial ?: "")
                }
            )
        }
        return array.toString()
    }

    private fun decodeGamepadBindingsByPad(raw: String?): Map<Int, Map<String, Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val nested = buildMap {
                json.keys().forEach { key ->
                    val padIndex = key.toIntOrNull() ?: return@forEach
                    val valueObject = json.optJSONObject(key) ?: return@forEach
                    val bindings = buildMap {
                        valueObject.keys().forEach { actionId ->
                            val keyCode = valueObject.optInt(actionId, Int.MIN_VALUE)
                            if (keyCode != Int.MIN_VALUE) put(actionId, keyCode)
                        }
                    }
                    if (bindings.isNotEmpty()) put(padIndex.coerceIn(0, 1), bindings)
                }
            }
            nested.ifEmpty {
                val legacy = buildMap {
                    json.keys().forEach { key ->
                        val value = json.optInt(key, Int.MIN_VALUE)
                        if (value != Int.MIN_VALUE) put(key, value)
                    }
                }
                if (legacy.isEmpty()) emptyMap() else mapOf(0 to legacy)
            }
        }.getOrDefault(emptyMap())
    }

    private fun decodeGamepadBindings(raw: String?): Map<String, Int> {
        return decodeGamepadBindingsByPad(raw)[0].orEmpty()
    }

    private fun encodeGamepadBindingsByPad(bindingsByPad: Map<Int, Map<String, Int>>): String {
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
        }.toString()
    }

    private fun decodeGamepadDeviceAssignments(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val padIndex = key.toIntOrNull() ?: return@forEach
                    val deviceKey = json.optString(key).takeIf { it.isNotBlank() } ?: return@forEach
                    put(padIndex.coerceIn(0, 1), deviceKey)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeGamepadDeviceAssignments(assignments: Map<Int, String>): String {
        return JSONObject().apply {
            assignments.toSortedMap().forEach { (padIndex, deviceKey) ->
                if (deviceKey.isNotBlank()) put(padIndex.toString(), deviceKey)
            }
        }.toString()
    }

    private fun decodeIgnoredGamepadDevices(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val json = JSONArray(raw)
            buildSet {
                for (index in 0 until json.length()) {
                    json.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun encodeIgnoredGamepadDevices(deviceKeys: Set<String>): String {
        return JSONArray().apply {
            deviceKeys.filter { it.isNotBlank() }.sorted().forEach(::put)
        }.toString()
    }

    private fun normalizeGamepadPadIndex(padIndex: Int): Int = padIndex.coerceIn(0, 1)

    private fun updateGamepadBindingsForPad(
        prefs: MutablePreferences,
        padIndex: Int,
        transform: (MutableMap<String, Int>) -> Unit
    ) {
        val normalizedPadIndex = normalizeGamepadPadIndex(padIndex)
        val updated = decodeGamepadBindingsByPad(prefs[GAMEPAD_BINDINGS])
            .mapValues { (_, bindings) -> bindings.toMutableMap() }
            .toMutableMap()
        val padBindings = updated[normalizedPadIndex]?.toMutableMap() ?: mutableMapOf()
        transform(padBindings)
        if (padBindings.isEmpty()) {
            updated.remove(normalizedPadIndex)
        } else {
            updated[normalizedPadIndex] = padBindings
        }
        if (updated.isEmpty()) {
            prefs.remove(GAMEPAD_BINDINGS)
        } else {
            prefs[GAMEPAD_BINDINGS] = encodeGamepadBindingsByPad(updated)
        }
    }

    // Overlay customization
    val overlayScale: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[OVERLAY_SCALE] ?: 100
    }

    suspend fun setOverlayScale(scale: Int) {
        context.dataStore.edit { it[OVERLAY_SCALE] = scale.coerceIn(50, 150) }
    }

    val overlayOpacity: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY)
            .coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX)
    }

    suspend fun setOverlayOpacity(opacity: Int) {
        context.dataStore.edit {
            it[OVERLAY_OPACITY] = opacity.coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX)
        }
    }

    val overlayShow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[OVERLAY_SHOW] ?: true
    }

    suspend fun setOverlayShow(enabled: Boolean) {
        context.dataStore.edit { it[OVERLAY_SHOW] = enabled }
    }

    val racingMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[RACING_MODE] ?: false
    }

    suspend fun setRacingMode(enabled: Boolean) {
        context.dataStore.edit { it[RACING_MODE] = enabled }
    }

    val touchscreenRightStick: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TOUCHSCREEN_RIGHT_STICK] ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK
    }

    suspend fun setTouchscreenRightStick(enabled: Boolean) {
        context.dataStore.edit { it[TOUCHSCREEN_RIGHT_STICK] = enabled }
    }

    val touchscreenRightStickSensitivity: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[TOUCHSCREEN_RIGHT_STICK_SENSITIVITY]
            ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY).coerceIn(
            TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
            TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
        )
    }

    suspend fun setTouchscreenRightStickSensitivity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[TOUCHSCREEN_RIGHT_STICK_SENSITIVITY] = value.coerceIn(
                TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
            )
        }
    }

    val gamepadStickDeadzone: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_STICK_DEADZONE] ?: DEFAULT_GAMEPAD_STICK_DEADZONE
    }

    suspend fun setGamepadStickDeadzone(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[GAMEPAD_STICK_DEADZONE] = value.coerceIn(0, 35)
        }
    }

    val gamepadLeftStickSensitivity: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_LEFT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY
    }

    suspend fun setGamepadLeftStickSensitivity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[GAMEPAD_LEFT_STICK_SENSITIVITY] = value.coerceIn(50, 200)
        }
    }

    val gamepadRightStickSensitivity: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_RIGHT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY
    }

    suspend fun setGamepadRightStickSensitivity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[GAMEPAD_RIGHT_STICK_SENSITIVITY] = value.coerceIn(50, 200)
        }
    }

    val gamepadRightStickUpToR2: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_RIGHT_STICK_UP_TO_R2] ?: false
    }

    suspend fun setGamepadRightStickUpToR2(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[GAMEPAD_RIGHT_STICK_UP_TO_R2] = enabled
        }
    }

    val gamepadRightStickDownToL2: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[GAMEPAD_RIGHT_STICK_DOWN_TO_L2] ?: false
    }

    suspend fun setGamepadRightStickDownToL2(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[GAMEPAD_RIGHT_STICK_DOWN_TO_L2] = enabled
        }
    }

    val mediatekAngleOpenGl: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[MEDIATEK_ANGLE_OPENGL] ?: false
    }

    suspend fun setMediatekAngleOpenGl(enabled: Boolean) {
        context.dataStore.edit { it[MEDIATEK_ANGLE_OPENGL] = enabled }
    }


    val textureReplacementsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TEXTURE_REPLACEMENTS_ENABLED] ?: false
    }

    suspend fun setTextureReplacementsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TEXTURE_REPLACEMENTS_ENABLED] = enabled }
    }

    val textureReplacementsAsync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TEXTURE_REPLACEMENTS_ASYNC] ?: true
    }

    suspend fun setTextureReplacementsAsync(enabled: Boolean) {
        context.dataStore.edit { it[TEXTURE_REPLACEMENTS_ASYNC] = enabled }
    }

    val textureReplacementsPrecache: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TEXTURE_REPLACEMENTS_PRECACHE] ?: false
    }

    suspend fun setTextureReplacementsPrecache(enabled: Boolean) {
        context.dataStore.edit { it[TEXTURE_REPLACEMENTS_PRECACHE] = enabled }
    }

    val textureDumpingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[TEXTURE_DUMPING_ENABLED] ?: false
    }

    suspend fun setTextureDumpingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[TEXTURE_DUMPING_ENABLED] = enabled }
    }

    // Gamepad auto-detect
    val enableAutoGamepad: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_AUTO_GAMEPAD] ?: true
    }

    suspend fun setEnableAutoGamepad(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_AUTO_GAMEPAD] = enabled }
    }

    // Hide overlay when gamepad connected
    val hideOverlayOnGamepad: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HIDE_OVERLAY_ON_GAMEPAD] ?: true
    }

    suspend fun setHideOverlayOnGamepad(enabled: Boolean) {
        context.dataStore.edit { it[HIDE_OVERLAY_ON_GAMEPAD] = enabled }
    }

    // Floating quick save/load buttons
    val floatingQuickActionsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[FLOATING_QUICK_ACTIONS_ENABLED] ?: false
    }

    suspend fun setFloatingQuickActionsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[FLOATING_QUICK_ACTIONS_ENABLED] = enabled }
    }

    val floatingQuickSavePosition: Flow<Pair<Float, Float>> = context.dataStore.data.map { prefs ->
        sanitizeFloatingQuickActionPosition(
            parseOffsetStr(
                prefs[FLOATING_QUICK_SAVE_POSITION],
                DEFAULT_FLOATING_QUICK_SAVE_POSITION_X to DEFAULT_FLOATING_QUICK_SAVE_POSITION_Y
            )
        )
    }

    suspend fun setFloatingQuickSavePosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[FLOATING_QUICK_SAVE_POSITION] = formatOffsetStr(
                x.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX),
                y.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX)
            )
        }
    }

    val floatingQuickLoadPosition: Flow<Pair<Float, Float>> = context.dataStore.data.map { prefs ->
        sanitizeFloatingQuickActionPosition(
            parseOffsetStr(
                prefs[FLOATING_QUICK_LOAD_POSITION],
                DEFAULT_FLOATING_QUICK_LOAD_POSITION_X to DEFAULT_FLOATING_QUICK_LOAD_POSITION_Y
            )
        )
    }

    suspend fun setFloatingQuickLoadPosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[FLOATING_QUICK_LOAD_POSITION] = formatOffsetStr(
                x.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX),
                y.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX)
            )
        }
    }

    private fun sanitizeFloatingQuickActionPosition(position: Pair<Float, Float>): Pair<Float, Float> {
        return position.first.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX) to
            position.second.coerceIn(FLOATING_QUICK_ACTION_MIN, FLOATING_QUICK_ACTION_MAX)
    }

    // Screen orientation
    val orientationLock: Flow<Int> = context.dataStore.data.map { prefs ->
        normalizeOrientationLock(prefs[ORIENTATION_LOCK])
    }

    suspend fun setOrientationLock(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[ORIENTATION_LOCK] = normalizeOrientationLock(value)
        }
    }

    val emulationAllowsBothOrientations: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[EMULATION_ALLOWS_BOTH_ORIENTATIONS] ?: false
    }

    suspend fun setEmulationAllowsBothOrientations(enabled: Boolean) {
        context.dataStore.edit { it[EMULATION_ALLOWS_BOTH_ORIENTATIONS] = enabled }
    }

    // RetroAchievements account
    val retroAchievementsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_ENABLED] ?: false
    }

    suspend fun setRetroAchievementsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RETRO_ACHIEVEMENTS_ENABLED] = enabled }
    }

    val retroAchievementsUsername: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_USERNAME]?.takeIf { it.isNotBlank() }
    }

    suspend fun setRetroAchievementsUsername(value: String?) {
        context.dataStore.edit { prefs ->
            value?.takeIf { it.isNotBlank() }?.let { prefs[RETRO_ACHIEVEMENTS_USERNAME] = it }
                ?: prefs.remove(RETRO_ACHIEVEMENTS_USERNAME)
        }
    }

    val retroAchievementsToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_TOKEN]?.takeIf { it.isNotBlank() }
    }

    suspend fun setRetroAchievementsToken(value: String?) {
        context.dataStore.edit { prefs ->
            value?.takeIf { it.isNotBlank() }?.let { prefs[RETRO_ACHIEVEMENTS_TOKEN] = it }
                ?: prefs.remove(RETRO_ACHIEVEMENTS_TOKEN)
        }
    }

    val retroAchievementsHardcore: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_HARDCORE] ?: false
    }

    suspend fun setRetroAchievementsHardcore(enabled: Boolean) {
        context.dataStore.edit { it[RETRO_ACHIEVEMENTS_HARDCORE] = enabled }
    }

    val retroAchievementsUnofficial: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_UNOFFICIAL] ?: false
    }

    suspend fun setRetroAchievementsUnofficial(enabled: Boolean) {
        context.dataStore.edit { it[RETRO_ACHIEVEMENTS_UNOFFICIAL] = enabled }
    }

    val retroAchievementsEncore: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[RETRO_ACHIEVEMENTS_ENCORE] ?: false
    }

    suspend fun setRetroAchievementsEncore(enabled: Boolean) {
        context.dataStore.edit { it[RETRO_ACHIEVEMENTS_ENCORE] = enabled }
    }

    val gamepadBindingsByPad: Flow<Map<Int, Map<String, Int>>> = context.dataStore.data.map { prefs ->
        decodeGamepadBindingsByPad(prefs[GAMEPAD_BINDINGS])
    }

    suspend fun setGamepadBinding(padIndex: Int, actionId: String, keyCode: Int) {
        context.dataStore.edit { prefs ->
            updateGamepadBindingsForPad(prefs, padIndex) { updated ->
                updated.entries.removeAll { it.value == keyCode }
                updated[actionId] = keyCode
            }
        }
    }

    suspend fun clearGamepadBinding(padIndex: Int, actionId: String) {
        context.dataStore.edit { prefs ->
            updateGamepadBindingsForPad(prefs, padIndex) { updated ->
                updated.remove(actionId)
            }
        }
    }

    suspend fun resetGamepadBindingsForPad(padIndex: Int) {
        context.dataStore.edit { prefs ->
            updateGamepadBindingsForPad(prefs, padIndex) { updated ->
                updated.clear()
            }
        }
    }

    val gamepadDeviceAssignments: Flow<Map<Int, String>> = context.dataStore.data.map { prefs ->
        decodeGamepadDeviceAssignments(prefs[GAMEPAD_DEVICE_ASSIGNMENTS])
    }

    suspend fun setGamepadDeviceAssignment(padIndex: Int, deviceKey: String?) {
        context.dataStore.edit { prefs ->
            val normalizedPadIndex = normalizeGamepadPadIndex(padIndex)
            val updated = decodeGamepadDeviceAssignments(prefs[GAMEPAD_DEVICE_ASSIGNMENTS]).toMutableMap()
            if (deviceKey.isNullOrBlank()) {
                updated.remove(normalizedPadIndex)
            } else {
                updated.entries.removeAll { it.value == deviceKey }
                updated[normalizedPadIndex] = deviceKey
            }
            if (updated.isEmpty()) {
                prefs.remove(GAMEPAD_DEVICE_ASSIGNMENTS)
            } else {
                prefs[GAMEPAD_DEVICE_ASSIGNMENTS] = encodeGamepadDeviceAssignments(updated)
            }
        }
    }

    val ignoredGamepadDevices: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        decodeIgnoredGamepadDevices(prefs[GAMEPAD_IGNORED_DEVICES])
    }

    suspend fun setGamepadDeviceIgnored(deviceKey: String, ignored: Boolean) {
        if (deviceKey.isBlank()) return
        context.dataStore.edit { prefs ->
            val updated = decodeIgnoredGamepadDevices(prefs[GAMEPAD_IGNORED_DEVICES]).toMutableSet()
            if (ignored) updated.add(deviceKey) else updated.remove(deviceKey)
            if (updated.isEmpty()) {
                prefs.remove(GAMEPAD_IGNORED_DEVICES)
            } else {
                prefs[GAMEPAD_IGNORED_DEVICES] = encodeIgnoredGamepadDevices(updated)
            }
        }
    }

    suspend fun resetGamepadDeviceAssignments() {
        context.dataStore.edit { prefs ->
            prefs.remove(GAMEPAD_DEVICE_ASSIGNMENTS)
            prefs.remove(GAMEPAD_IGNORED_DEVICES)
        }
    }

    // Custom Layout Offsets
    private fun parseOffsetStr(raw: String?, default: Pair<Float, Float> = 0f to 0f): Pair<Float, Float> {
        if (raw.isNullOrBlank()) return default
        val parts = raw.split(",")
        if (parts.size != 2) return default
        return (parts[0].toFloatOrNull() ?: default.first) to (parts[1].toFloatOrNull() ?: default.second)
    }

    private fun formatOffsetStr(x: Float, y: Float): String = "$x,$y"

    private fun decodeControlLayouts(raw: String?): Map<String, OverlayControlLayout> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val item = json.optJSONObject(id) ?: continue
                    put(
                        id,
                        OverlayControlLayout(
                            offset = (item.optDouble("x", 0.0).toFloat()) to (item.optDouble("y", 0.0).toFloat()),
                            scale = item.optInt("scale", OVERLAY_CONTROL_SCALE_DEFAULT)
                                .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX),
                            widthScale = item.optInt(
                                "widthScale",
                                if (id.contains("stick")) 160 else 100
                            ).coerceIn(100, 240),
                            opacity = item.optInt("opacity", OVERLAY_CONTROL_OPACITY_DEFAULT)
                                .coerceIn(OVERLAY_CONTROL_OPACITY_MIN, OVERLAY_CONTROL_OPACITY_MAX),
                            visible = item.optBoolean("visible", true),
                            surfaceOnly = item.optBoolean("surfaceOnly", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeControlLayouts(layouts: Map<String, OverlayControlLayout>): String? {
        if (layouts.isEmpty()) return null
        return JSONObject().apply {
            layouts.toSortedMap().forEach { (id, layout) ->
                put(
                    id,
                    JSONObject().apply {
                        put("x", layout.offset.first.toDouble())
                        put("y", layout.offset.second.toDouble())
                        put("scale", layout.scale.coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX))
                        put("widthScale", layout.widthScale.coerceIn(100, 240))
                        put(
                            "opacity",
                            layout.opacity.coerceIn(OVERLAY_CONTROL_OPACITY_MIN, OVERLAY_CONTROL_OPACITY_MAX)
                        )
                        put("visible", layout.visible)
                        put("surfaceOnly", layout.surfaceOnly)
                    }
                )
            }
        }.toString()
    }

    private fun migrateGlobalStickSurfaceMode(prefs: MutablePreferences) {
        if (prefs[STICK_SURFACE_MODE] == true) {
            val stickScale = (prefs[STICK_SCALE] ?: OVERLAY_CONTROL_SCALE_DEFAULT)
                .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX)
            val defaults = defaultOverlayControlLayouts(stickScale)
            val layouts = decodeControlLayouts(prefs[CONTROL_LAYOUTS]).toMutableMap()
            listOf("left_stick", "right_stick").forEach { id ->
                val current = layouts[id] ?: defaults[id] ?: OverlayControlLayout(scale = stickScale)
                layouts[id] = current.copy(surfaceOnly = true)
            }
            encodeControlLayouts(layouts)?.let { prefs[CONTROL_LAYOUTS] = it }
        }
        prefs.remove(STICK_SURFACE_MODE)
    }

    val dpadOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[DPAD_OFFSET], DEFAULT_DPAD_OFFSET_X to DEFAULT_DPAD_OFFSET_Y)
    }
    val lstickOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[LSTICK_OFFSET], DEFAULT_LSTICK_OFFSET_X to DEFAULT_LSTICK_OFFSET_Y)
    }
    val rstickOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[RSTICK_OFFSET], DEFAULT_RSTICK_OFFSET_X to DEFAULT_RSTICK_OFFSET_Y)
    }
    val actionOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[ACTION_OFFSET], DEFAULT_ACTION_OFFSET_X to DEFAULT_ACTION_OFFSET_Y)
    }
    val lbtnOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[LBTN_OFFSET], DEFAULT_LBTN_OFFSET_X to DEFAULT_LBTN_OFFSET_Y)
    }
    val rbtnOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[RBTN_OFFSET], DEFAULT_RBTN_OFFSET_X to DEFAULT_RBTN_OFFSET_Y)
    }
    val centerOffset: Flow<Pair<Float, Float>> = context.dataStore.data.map {
        parseOffsetStr(it[CENTER_OFFSET], DEFAULT_CENTER_OFFSET_X to DEFAULT_CENTER_OFFSET_Y)
    }

    val stickScale: Flow<Int> = context.dataStore.data.map {
        (it[STICK_SCALE] ?: OVERLAY_CONTROL_SCALE_DEFAULT)
            .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX)
    }

    val leftStickSensitivity: Flow<Int> = context.dataStore.data.map { it[LEFT_STICK_SENSITIVITY] ?: 100 }

    suspend fun setLeftStickSensitivity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[LEFT_STICK_SENSITIVITY] = value.coerceIn(50, 200)
        }
    }

    val rightStickSensitivity: Flow<Int> = context.dataStore.data.map { it[RIGHT_STICK_SENSITIVITY] ?: 100 }

    suspend fun setRightStickSensitivity(value: Int) {
        context.dataStore.edit { prefs ->
            prefs[RIGHT_STICK_SENSITIVITY] = value.coerceIn(50, 200)
        }
    }

    val invertLeftStick: Flow<Boolean> = context.dataStore.data.map { it[INVERT_LEFT_STICK] ?: false }

    suspend fun setInvertLeftStick(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[INVERT_LEFT_STICK] = enabled
        }
    }

    val invertRightStick: Flow<Boolean> = context.dataStore.data.map { it[INVERT_RIGHT_STICK] ?: false }

    suspend fun setInvertRightStick(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[INVERT_RIGHT_STICK] = enabled
        }
    }

    val invertLeftStickHorizontal: Flow<Boolean> = context.dataStore.data.map { it[INVERT_LEFT_STICK_HORIZONTAL] ?: false }

    suspend fun setInvertLeftStickHorizontal(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[INVERT_LEFT_STICK_HORIZONTAL] = enabled
        }
    }

    val invertRightStickHorizontal: Flow<Boolean> = context.dataStore.data.map { it[INVERT_RIGHT_STICK_HORIZONTAL] ?: false }

    suspend fun setInvertRightStickHorizontal(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[INVERT_RIGHT_STICK_HORIZONTAL] = enabled
        }
    }

    suspend fun setControlsLayout(
        dpadX: Float, dpadY: Float,
        lstickX: Float, lstickY: Float,
        rstickX: Float, rstickY: Float,
        actionX: Float, actionY: Float,
        lbtnX: Float, lbtnY: Float,
        rbtnX: Float, rbtnY: Float,
        centerX: Float, centerY: Float,
        stickScaleVal: Int,
        controlLayouts: Map<String, OverlayControlLayout> = emptyMap()
    ) {
        context.dataStore.edit { prefs ->
            prefs[DPAD_OFFSET] = formatOffsetStr(dpadX, dpadY)
            prefs[LSTICK_OFFSET] = formatOffsetStr(lstickX, lstickY)
            prefs[RSTICK_OFFSET] = formatOffsetStr(rstickX, rstickY)
            prefs[ACTION_OFFSET] = formatOffsetStr(actionX, actionY)
            prefs[LBTN_OFFSET] = formatOffsetStr(lbtnX, lbtnY)
            prefs[RBTN_OFFSET] = formatOffsetStr(rbtnX, rbtnY)
            prefs[CENTER_OFFSET] = formatOffsetStr(centerX, centerY)
            prefs[STICK_SCALE] = stickScaleVal.coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX)
            prefs[OVERLAY_LAYOUT_VERSION] = CURRENT_OVERLAY_LAYOUT_VERSION
            encodeControlLayouts(controlLayouts)?.let { prefs[CONTROL_LAYOUTS] = it } ?: prefs.remove(CONTROL_LAYOUTS)
        }
    }

    suspend fun resetControlsLayout() {
        context.dataStore.edit { prefs ->
            prefs.remove(DPAD_OFFSET)
            prefs.remove(LSTICK_OFFSET)
            prefs.remove(RSTICK_OFFSET)
            prefs.remove(ACTION_OFFSET)
            prefs.remove(LBTN_OFFSET)
            prefs.remove(RBTN_OFFSET)
            prefs.remove(CENTER_OFFSET)
            prefs.remove(STICK_SCALE)
            prefs.remove(STICK_SURFACE_MODE)
            prefs.remove(CONTROL_LAYOUTS)
            prefs[OVERLAY_LAYOUT_VERSION] = CURRENT_OVERLAY_LAYOUT_VERSION
        }
    }

    suspend fun migrateOverlayLayoutIfNeeded() {
        context.dataStore.edit { prefs ->
            val currentVersion = prefs[OVERLAY_LAYOUT_VERSION] ?: 0
            if (currentVersion >= CURRENT_OVERLAY_LAYOUT_VERSION) return@edit

            if (currentVersion < 10) {
                prefs.remove(DPAD_OFFSET)
                prefs.remove(LSTICK_OFFSET)
                prefs.remove(RSTICK_OFFSET)
                prefs.remove(ACTION_OFFSET)
                prefs.remove(LBTN_OFFSET)
                prefs.remove(RBTN_OFFSET)
                prefs.remove(CENTER_OFFSET)
                prefs.remove(STICK_SCALE)
                prefs.remove(LEFT_STICK_SENSITIVITY)
                prefs.remove(RIGHT_STICK_SENSITIVITY)
                prefs.remove(TOUCHSCREEN_RIGHT_STICK_SENSITIVITY)
                prefs.remove(STICK_SURFACE_MODE)
                prefs.remove(CONTROL_LAYOUTS)
            } else {
                val savedLeftStickOffset = parseOffsetStr(
                    prefs[LSTICK_OFFSET],
                    LEGACY_DEFAULT_LSTICK_OFFSET_X to LEGACY_DEFAULT_LSTICK_OFFSET_Y
                )
                if (savedLeftStickOffset == (LEGACY_DEFAULT_LSTICK_OFFSET_X to LEGACY_DEFAULT_LSTICK_OFFSET_Y)) {
                    prefs[LSTICK_OFFSET] = formatOffsetStr(DEFAULT_LSTICK_OFFSET_X, DEFAULT_LSTICK_OFFSET_Y)
                }
            }
            if (currentVersion >= 11) {
                val savedDpadOffset = parseOffsetStr(
                    prefs[DPAD_OFFSET],
                    PREVIOUS_DEFAULT_DPAD_OFFSET_X - LEFT_SIDE_LAYOUT_SHIFT_X to DEFAULT_DPAD_OFFSET_Y
                )
                val savedLstickOffset = parseOffsetStr(
                    prefs[LSTICK_OFFSET],
                    PREVIOUS_DEFAULT_LSTICK_OFFSET_X - LEFT_SIDE_LAYOUT_SHIFT_X to DEFAULT_LSTICK_OFFSET_Y
                )
                prefs[DPAD_OFFSET] = formatOffsetStr(savedDpadOffset.first + LEFT_SIDE_LAYOUT_SHIFT_X, savedDpadOffset.second)
                prefs[LSTICK_OFFSET] = formatOffsetStr(savedLstickOffset.first + LEFT_SIDE_LAYOUT_SHIFT_X, savedLstickOffset.second)
            }
            if (currentVersion < 13) {
                val savedDpadOffset = parseOffsetStr(
                    prefs[DPAD_OFFSET],
                    DEFAULT_DPAD_OFFSET_X to DEFAULT_DPAD_OFFSET_Y
                )
                val savedLstickOffset = parseOffsetStr(
                    prefs[LSTICK_OFFSET],
                    DEFAULT_LSTICK_OFFSET_X to DEFAULT_LSTICK_OFFSET_Y
                )
                if (savedDpadOffset == (PREVIOUS_DEFAULT_DPAD_OFFSET_X + LEFT_SIDE_LAYOUT_SHIFT_X to DEFAULT_DPAD_OFFSET_Y) ||
                    savedDpadOffset == (12f to DEFAULT_DPAD_OFFSET_Y) ||
                    savedDpadOffset == (8f to DEFAULT_DPAD_OFFSET_Y)) {
                    prefs[DPAD_OFFSET] = formatOffsetStr(DEFAULT_DPAD_OFFSET_X, DEFAULT_DPAD_OFFSET_Y)
                }
                if (savedLstickOffset == (PREVIOUS_DEFAULT_LSTICK_OFFSET_X + LEFT_SIDE_LAYOUT_SHIFT_X to DEFAULT_LSTICK_OFFSET_Y) ||
                    savedLstickOffset == (12f to DEFAULT_LSTICK_OFFSET_Y) ||
                    savedLstickOffset == (8f to DEFAULT_LSTICK_OFFSET_Y)) {
                    prefs[LSTICK_OFFSET] = formatOffsetStr(DEFAULT_LSTICK_OFFSET_X, DEFAULT_LSTICK_OFFSET_Y)
                }
            }
            if (currentVersion < 14) {
                val savedCenterOffset = parseOffsetStr(
                    prefs[CENTER_OFFSET],
                    PREVIOUS_DEFAULT_CENTER_OFFSET_X to PREVIOUS_DEFAULT_CENTER_OFFSET_Y
                )
                if (savedCenterOffset == (PREVIOUS_DEFAULT_CENTER_OFFSET_X to PREVIOUS_DEFAULT_CENTER_OFFSET_Y)) {
                    prefs[CENTER_OFFSET] = formatOffsetStr(DEFAULT_CENTER_OFFSET_X, DEFAULT_CENTER_OFFSET_Y)
                }
            }
            if (currentVersion < 15) {
                val layouts = decodeControlLayouts(prefs[CONTROL_LAYOUTS]).toMutableMap()
                val select = layouts["select"]
                val toggle = layouts["left_input_toggle"]
                val start = layouts["start"]

                val hasDefaultCenterControls = listOf(select, toggle, start).all { layout ->
                    layout == null || (
                        layout.offset == (0f to 0f) &&
                            layout.scale == 80 &&
                            layout.visible
                        )
                }

                if (hasDefaultCenterControls) {
                    prefs.remove(CENTER_OFFSET)
                    prefs.remove(CONTROL_LAYOUTS)
                }
            }
            if (currentVersion < 17) {
                val layouts = decodeControlLayouts(prefs[CONTROL_LAYOUTS]).toMutableMap()
                listOf("l3", "r3").forEach { id ->
                    val layout = layouts[id] ?: return@forEach
                    if (layout == OverlayControlLayout(scale = 76, visible = true)) {
                        layouts[id] = layout.copy(visible = false)
                    }
                }
                encodeControlLayouts(layouts)?.let { prefs[CONTROL_LAYOUTS] = it }
            }
            migrateGlobalStickSurfaceMode(prefs)
            prefs[OVERLAY_LAYOUT_VERSION] = CURRENT_OVERLAY_LAYOUT_VERSION
        }
    }

    suspend fun exportJson(): JSONObject {
        val prefs = context.dataStore.data.first()
        val gpuHardwareProfile = resolveGpuHardwareProfile()
        return JSONObject().apply {
            put("themeMode", prefs[THEME_MODE] ?: 0)
            val customThemeLibrary = readCustomThemeLibrary(prefs)
            put(
                "customTheme",
                (customThemeLibrary.activeTheme()?.config ?: CustomThemeConfig.Default).encode()
            )
            put("customThemeLibrary", customThemeLibrary.encode())
            put(
                "customTouchControls",
                CustomTouchControlLibrary.decode(prefs[CUSTOM_TOUCH_CONTROLS_JSON]).encode()
            )
            put("tvInterfaceMode", TvInterfaceMode.fromPreference(prefs[TV_INTERFACE_MODE]).preferenceValue)
            put("appFontChoice", prefs[APP_FONT_CHOICE] ?: AppFontChoice.SYSTEM.preferenceValue)
            put("appFontScale", (prefs[APP_FONT_SCALE] ?: DEFAULT_APP_FONT_SCALE).toDouble())
            put("customFontName", prefs[CUSTOM_FONT_NAME])
            put("homeGridScale", (prefs[HOME_GRID_SCALE] ?: DEFAULT_HOME_GRID_SCALE).toDouble())
            put("homeBackgroundDim", prefs[HOME_BACKGROUND_DIM] ?: DEFAULT_HOME_BACKGROUND_DIM)
            put(
                "emulationSideArtworkDim",
                prefs[EMULATION_SIDE_ARTWORK_DIM] ?: DEFAULT_EMULATION_SIDE_ARTWORK_DIM
            )
            put("localMultiplayerMode", normalizeLocalMultiplayerMode(prefs[LOCAL_MULTIPLAYER_MODE]))
            put("homeBackgroundType", prefs[HOME_BACKGROUND_TYPE] ?: HomeBackgroundType.NONE.preferenceValue)
            put("homeBackgroundPreset", prefs[HOME_BACKGROUND_PRESET] ?: HomeBackgroundPreset.OLYMPUS.preferenceValue)
            put("touchControlVisualStyle", prefs[TOUCH_CONTROL_VISUAL_STYLE] ?: TouchControlVisualStyle.CLASSIC.preferenceValue)
            put("touchControlPressEffect", prefs[TOUCH_CONTROL_PRESS_EFFECT] ?: TouchControlPressEffect.GROW.preferenceValue)
            put("gameMenuLayoutStyle", prefs[GAME_MENU_LAYOUT_STYLE] ?: GameMenuLayoutStyle.SIDEBAR.preferenceValue)
            put("drawerVisualStyle", prefs[DRAWER_VISUAL_STYLE] ?: DrawerVisualStyle.CLASSIC.preferenceValue)
            put("hiddenDrawerItems", prefs[HIDDEN_DRAWER_ITEMS] ?: "")
            put("gameMenuTabOrder", prefs[GAME_MENU_TAB_ORDER] ?: DefaultGameMenuTabOrder.joinToString(",") { it.name })
            put("hiddenGameMenuTabs", prefs[HIDDEN_GAME_MENU_TABS] ?: "")
            put("gameMenuSectionOrder", prefs[GAME_MENU_SECTION_ORDER] ?: DefaultGameMenuSectionOrder.joinToString(",") { it.name })
            put("hiddenGameMenuSections", prefs[HIDDEN_GAME_MENU_SECTIONS] ?: "")
            put("gpuHardwareProfile", gpuHardwareProfile)
            put("renderer", normalizeRendererPreference(prefs[RENDERER]))
            put("mediatekAngleOpenGl", prefs[MEDIATEK_ANGLE_OPENGL] ?: false)
            put("upscaleMultiplier", readUpscale(prefs).toDouble())
            put("shaderChainEnabled", prefs[SHADER_CHAIN_ENABLED] ?: false)
            put("shaderChainPreset", prefs[SHADER_CHAIN_PRESET].orEmpty())
            put("biosPath", prefs[BIOS_PATH])
            put("gamePath", prefs[GAME_PATH])
            put("gamePaths", JSONArray(readGamePaths(prefs)))
            put("emulatorDataPath", prefs[EMULATOR_DATA_PATH])
            put("coverDownloadBaseUrl", prefs[COVER_DOWNLOAD_BASE_URL])
            put("coverArtStyle", readCoverArtStyle(prefs))
            put("onboardingCompleted", prefs[ONBOARDING_COMPLETED] ?: false)
            put("languageTag", prefs[LANGUAGE_TAG])
            put("aspectRatio", normalizeAspectRatioPreference(prefs[ASPECT_RATIO]))
            readDisplayCrop(prefs).let { crop ->
                put("displayCropLeft", crop.left)
                put("displayCropTop", crop.top)
                put("displayCropRight", crop.right)
                put("displayCropBottom", crop.bottom)
            }
            put("audioVolume", AudioDefaults.coerceVolume(prefs[AUDIO_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT))
            put("audioFastForwardVolume", AudioDefaults.coerceVolume(prefs[AUDIO_FAST_FORWARD_VOLUME] ?: AudioDefaults.VOLUME_DEFAULT))
            put("audioMuted", prefs[AUDIO_MUTED] ?: false)
            put("audioOutputLatencyMs", AudioDefaults.coerceOutputLatencyMs(prefs[AUDIO_OUTPUT_LATENCY_MS] ?: AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT))
            put("audioMinimalOutputLatency", prefs[AUDIO_MINIMAL_OUTPUT_LATENCY] ?: AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT)
            put("padVibration", prefs[PAD_VIBRATION] ?: true)
            put("padVibrationStrength", (prefs[PAD_VIBRATION_STRENGTH] ?: DEFAULT_PAD_VIBRATION_STRENGTH).coerceIn(0, 150))
            put("padVibrationFallback", prefs[PAD_VIBRATION_FALLBACK] ?: true)
            put("showFps", prefs[SHOW_FPS] ?: false)
            put("fpsOverlayMode", prefs[FPS_OVERLAY_MODE] ?: FPS_OVERLAY_MODE_DETAILED)
            put("fpsOverlayCorner", prefs[FPS_OVERLAY_CORNER] ?: FPS_OVERLAY_CORNER_TOP_RIGHT)
            put("fpsOverlayScale", (prefs[FPS_OVERLAY_SCALE] ?: DEFAULT_FPS_OVERLAY_SCALE).coerceIn(MIN_FPS_OVERLAY_SCALE, MAX_FPS_OVERLAY_SCALE))
            put("fpsOverlayMetrics", PerformanceOverlayMetrics.sanitize(prefs[FPS_OVERLAY_METRICS] ?: PerformanceOverlayMetrics.DEFAULT))
            put("confirmSaveLoadActions", prefs[CONFIRM_SAVE_LOAD_ACTIONS] ?: true)
            put("backButtonExitsGame", prefs[BACK_BUTTON_EXITS_GAME] ?: false)
            put("compactControls", prefs[COMPACT_CONTROLS] ?: true)
            put("keepScreenOn", prefs[KEEP_SCREEN_ON] ?: true)
            put("showRecentGames", prefs[SHOW_RECENT_GAMES] ?: true)
            put("showHomeSearch", prefs[SHOW_HOME_SEARCH] ?: false)
            put("showDebugOptions", prefs[SHOW_DEBUG_OPTIONS] ?: false)
            put("profilerLogcat", prefs[PROFILER_LOGCAT] ?: false)
            put("preferEnglishGameTitles", prefs[PREFER_ENGLISH_GAME_TITLES] ?: false)
            put("recentGames", prefs[RECENT_GAMES] ?: "[]")
            put("homeLibraryViewMode", prefs[HOME_LIBRARY_VIEW_MODE] ?: 0)
            put("overlayScale", prefs[OVERLAY_SCALE] ?: 100)
            put(
                "overlayOpacity",
                (prefs[OVERLAY_OPACITY] ?: DEFAULT_OVERLAY_OPACITY)
                    .coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX)
            )
            put("overlayShow", prefs[OVERLAY_SHOW] ?: true)
            put("racingMode", prefs[RACING_MODE] ?: false)
            put(
                "touchscreenRightStick",
                prefs[TOUCHSCREEN_RIGHT_STICK] ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK
            )
            put(
                "touchscreenRightStickSensitivity",
                (prefs[TOUCHSCREEN_RIGHT_STICK_SENSITIVITY]
                    ?: DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY).coerceIn(
                    TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                    TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
                )
            )
            put("touchHaptics", prefs[TOUCH_HAPTICS] ?: false)
            put("touchHapticsPreset", (prefs[TOUCH_HAPTICS_PRESET] ?: DEFAULT_TOUCH_HAPTICS_PRESET).coerceIn(TOUCH_HAPTICS_PRESET_SOFT, TOUCH_HAPTICS_PRESET_STRONG))
            put("touchHapticsStrength", (prefs[TOUCH_HAPTICS_STRENGTH] ?: DEFAULT_TOUCH_HAPTICS_STRENGTH).coerceIn(10, 100))
            put("gyroMode", (prefs[GYRO_MODE] ?: GYRO_MODE_OFF).coerceIn(GYRO_MODE_OFF, GYRO_MODE_STEERING))
            put("gyroSensitivity", (prefs[GYRO_SENSITIVITY] ?: DEFAULT_GYRO_SENSITIVITY).coerceIn(25, 300))
            put("gyroSmoothing", (prefs[GYRO_SMOOTHING] ?: DEFAULT_GYRO_SMOOTHING).coerceIn(0, 90))
            put("gyroInvertX", prefs[GYRO_INVERT_X] ?: false)
            put("gyroInvertY", prefs[GYRO_INVERT_Y] ?: false)
            put("gamepadStickDeadzone", prefs[GAMEPAD_STICK_DEADZONE] ?: DEFAULT_GAMEPAD_STICK_DEADZONE)
            put("gamepadLeftStickSensitivity", prefs[GAMEPAD_LEFT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY)
            put("gamepadRightStickSensitivity", prefs[GAMEPAD_RIGHT_STICK_SENSITIVITY] ?: DEFAULT_GAMEPAD_STICK_SENSITIVITY)
            put("gamepadRightStickUpToR2", prefs[GAMEPAD_RIGHT_STICK_UP_TO_R2] ?: false)
            put("gamepadRightStickDownToL2", prefs[GAMEPAD_RIGHT_STICK_DOWN_TO_L2] ?: false)
            put("gamepadButtonHaptics", prefs[GAMEPAD_BUTTON_HAPTICS] ?: false)
            put("pressureModifierAmount", (prefs[PRESSURE_MODIFIER_AMOUNT] ?: DEFAULT_PRESSURE_MODIFIER_AMOUNT).coerceIn(1, 100))
            put("textureReplacementsEnabled", prefs[TEXTURE_REPLACEMENTS_ENABLED] ?: false)
            put("textureReplacementsAsync", prefs[TEXTURE_REPLACEMENTS_ASYNC] ?: true)
            put("textureReplacementsPrecache", prefs[TEXTURE_REPLACEMENTS_PRECACHE] ?: false)
            put("textureDumpingEnabled", prefs[TEXTURE_DUMPING_ENABLED] ?: false)
            put("enableAutoGamepad", prefs[ENABLE_AUTO_GAMEPAD] ?: true)
            put("hideOverlayOnGamepad", prefs[HIDE_OVERLAY_ON_GAMEPAD] ?: true)
            put("floatingQuickActionsEnabled", prefs[FLOATING_QUICK_ACTIONS_ENABLED] ?: false)
            put("floatingQuickSavePosition", prefs[FLOATING_QUICK_SAVE_POSITION])
            put("floatingQuickLoadPosition", prefs[FLOATING_QUICK_LOAD_POSITION])
            put("orientationLock", normalizeOrientationLock(prefs[ORIENTATION_LOCK]))
            put("emulationAllowsBothOrientations", prefs[EMULATION_ALLOWS_BOTH_ORIENTATIONS] ?: false)
            put("retroAchievementsEnabled", prefs[RETRO_ACHIEVEMENTS_ENABLED] ?: false)
            put("retroAchievementsUsername", prefs[RETRO_ACHIEVEMENTS_USERNAME])
            put("retroAchievementsToken", prefs[RETRO_ACHIEVEMENTS_TOKEN])
            put("retroAchievementsHardcore", prefs[RETRO_ACHIEVEMENTS_HARDCORE] ?: false)
            put("retroAchievementsUnofficial", prefs[RETRO_ACHIEVEMENTS_UNOFFICIAL] ?: false)
            put("retroAchievementsEncore", prefs[RETRO_ACHIEVEMENTS_ENCORE] ?: false)
            put("gamepadBindings", prefs[GAMEPAD_BINDINGS])
            put("gamepadDeviceAssignments", prefs[GAMEPAD_DEVICE_ASSIGNMENTS])
            put("gamepadIgnoredDevices", prefs[GAMEPAD_IGNORED_DEVICES])
            put("gpuDriverType", prefs[GPU_DRIVER_TYPE] ?: 0)
            put("customDriverPath", prefs[CUSTOM_DRIVER_PATH])
            put("frameLimitEnabled", prefs[FRAME_LIMIT_ENABLED] ?: true)
            put("vSyncEnabled", prefs[VSYNC_ENABLED] ?: false)
            put("fastForwardSpeed", sanitizeFastForwardSpeed(prefs[FAST_FORWARD_SPEED]).toDouble())
            put("targetFps", prefs[TARGET_FPS] ?: 0)
            put("ntscFramerate", sanitizeRegionFramerate(prefs[NTSC_FRAMERATE], DEFAULT_NTSC_FRAMERATE).toDouble())
            put("palFramerate", sanitizeRegionFramerate(prefs[PAL_FRAMERATE], DEFAULT_PAL_FRAMERATE).toDouble())
            put("autoSaveEnabled", prefs[AUTO_SAVE_ENABLED] ?: false)
            put("autoSaveIntervalMinutes", (prefs[AUTO_SAVE_INTERVAL_MINUTES] ?: 1).coerceIn(1, 999))
            put("overlayLayoutVersion", prefs[OVERLAY_LAYOUT_VERSION] ?: 0)
            put("dpadOffset", prefs[DPAD_OFFSET])
            put("lstickOffset", prefs[LSTICK_OFFSET])
            put("rstickOffset", prefs[RSTICK_OFFSET])
            put("actionOffset", prefs[ACTION_OFFSET])
            put("lbtnOffset", prefs[LBTN_OFFSET])
            put("rbtnOffset", prefs[RBTN_OFFSET])
            put("centerOffset", prefs[CENTER_OFFSET])
            put(
                "stickScale",
                (prefs[STICK_SCALE] ?: OVERLAY_CONTROL_SCALE_DEFAULT)
                    .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX)
            )
            put("leftStickSensitivity", prefs[LEFT_STICK_SENSITIVITY] ?: 100)
            put("rightStickSensitivity", prefs[RIGHT_STICK_SENSITIVITY] ?: 100)
            put("invertLeftStick", prefs[INVERT_LEFT_STICK] ?: false)
            put("invertRightStick", prefs[INVERT_RIGHT_STICK] ?: false)
            put("invertLeftStickHorizontal", prefs[INVERT_LEFT_STICK_HORIZONTAL] ?: false)
            put("invertRightStickHorizontal", prefs[INVERT_RIGHT_STICK_HORIZONTAL] ?: false)
            put("stickSurfaceMode", prefs[STICK_SURFACE_MODE] ?: false)
            put("controlLayouts", prefs[CONTROL_LAYOUTS])
            put("memoryCardSlot1", prefs[MEMORY_CARD_SLOT1])
            put("memoryCardSlot2", prefs[MEMORY_CARD_SLOT2])
        }
    }

    suspend fun importJson(json: JSONObject) {
        val languageTag = json.optString("languageTag").takeIf { it.isNotBlank() }
        localePrefs.edit().putString("language_tag", languageTag).apply()
        context.dataStore.edit { prefs ->
            prefs[THEME_MODE] = json.optInt("themeMode", 0).takeIf { it in 0..5 } ?: 0
            val importedLibrary = CustomThemeLibrary.decode(
                raw = json.optString("customThemeLibrary").takeIf(String::isNotBlank),
                legacyThemeRaw = json.optString("customTheme").takeIf(String::isNotBlank)
            )
            prefs[CUSTOM_THEME_LIBRARY_JSON] = importedLibrary.encode()
            val customTheme = importedLibrary.activeTheme()?.config ?: CustomThemeConfig.Default
            prefs[CUSTOM_THEME_JSON] = customTheme.encode()
            prefs[CUSTOM_TOUCH_CONTROLS_JSON] = CustomTouchControlLibrary.decode(
                json.optString("customTouchControls").takeIf(String::isNotBlank)
            ).encode()
            prefs[TV_INTERFACE_MODE] = TvInterfaceMode.fromPreference(
                json.optInt("tvInterfaceMode", TvInterfaceMode.AUTO.preferenceValue)
            ).preferenceValue
            prefs[APP_FONT_CHOICE] = AppFontChoice.fromPreference(
                json.optInt("appFontChoice", AppFontChoice.SYSTEM.preferenceValue)
            ).preferenceValue
            prefs[APP_FONT_SCALE] = json.optDouble("appFontScale", DEFAULT_APP_FONT_SCALE.toDouble())
                .toFloat().coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE)
            json.optString("customFontName").trim().takeIf(String::isNotEmpty)?.let {
                prefs[CUSTOM_FONT_NAME] = it
            } ?: prefs.remove(CUSTOM_FONT_NAME)
            prefs[HOME_GRID_SCALE] = json.optDouble("homeGridScale", DEFAULT_HOME_GRID_SCALE.toDouble())
                .toFloat().coerceIn(MIN_HOME_GRID_SCALE, MAX_HOME_GRID_SCALE)
            prefs[HOME_BACKGROUND_DIM] = json.optInt("homeBackgroundDim", DEFAULT_HOME_BACKGROUND_DIM)
                .coerceIn(0, 85)
            prefs[EMULATION_SIDE_ARTWORK_DIM] = json.optInt(
                "emulationSideArtworkDim",
                DEFAULT_EMULATION_SIDE_ARTWORK_DIM
            ).coerceIn(0, 85)
            prefs[LOCAL_MULTIPLAYER_MODE] = normalizeLocalMultiplayerMode(
                json.optInt("localMultiplayerMode", LOCAL_MULTIPLAYER_OFF)
            )
            prefs[HOME_BACKGROUND_TYPE] = HomeBackgroundType.fromPreference(
                json.optInt("homeBackgroundType", HomeBackgroundType.NONE.preferenceValue)
            ).preferenceValue
            prefs[HOME_BACKGROUND_PRESET] = HomeBackgroundPreset.fromPreference(
                json.optInt("homeBackgroundPreset", HomeBackgroundPreset.OLYMPUS.preferenceValue)
            ).preferenceValue
            prefs[TOUCH_CONTROL_VISUAL_STYLE] = TouchControlVisualStyle.fromPreference(
                json.optInt("touchControlVisualStyle", TouchControlVisualStyle.CLASSIC.preferenceValue)
            ).preferenceValue
            prefs[TOUCH_CONTROL_PRESS_EFFECT] = TouchControlPressEffect.fromPreference(
                json.optInt("touchControlPressEffect", TouchControlPressEffect.GROW.preferenceValue)
            ).preferenceValue
            prefs[GAME_MENU_LAYOUT_STYLE] = GameMenuLayoutStyle.fromPreference(
                json.optInt("gameMenuLayoutStyle", GameMenuLayoutStyle.SIDEBAR.preferenceValue)
            ).preferenceValue
            prefs[DRAWER_VISUAL_STYLE] = DrawerVisualStyle.fromPreference(
                json.optInt("drawerVisualStyle", DrawerVisualStyle.CLASSIC.preferenceValue)
            ).preferenceValue
            prefs[HIDDEN_DRAWER_ITEMS] = sanitizeHiddenDrawerItems(json.optString("hiddenDrawerItems"))
                .joinToString(",") { it.name }
            prefs[GAME_MENU_TAB_ORDER] = sanitizeGameMenuTabOrder(json.optString("gameMenuTabOrder"))
                .joinToString(",") { it.name }
            prefs[HIDDEN_GAME_MENU_TABS] = sanitizeHiddenGameMenuTabs(json.optString("hiddenGameMenuTabs"))
                .joinToString(",") { it.name }
            prefs[GAME_MENU_SECTION_ORDER] = sanitizeGameMenuSectionOrder(json.optString("gameMenuSectionOrder"))
                .joinToString(",") { it.name }
            prefs[HIDDEN_GAME_MENU_SECTIONS] = sanitizeHiddenGameMenuSections(json.optString("hiddenGameMenuSections"))
                .joinToString(",") { it.name }
            val gpuHardwareProfile = GpuHardwareProfiles.normalize(
                json.optInt("gpuHardwareProfile", GpuHardwareProfiles.ADRENO)
            )
            prefs[GPU_HARDWARE_PROFILE] = gpuHardwareProfile
            val importedRenderer = normalizeRendererPreference(
                if (json.has("renderer")) json.optInt("renderer") else null
            )
            prefs[RENDERER] = importedRenderer
            prefs[MEDIATEK_ANGLE_OPENGL] = json.optBoolean("mediatekAngleOpenGl", false) &&
                GpuHardwareProfiles.isMediatekProfile(gpuHardwareProfile)
            prefs[UPSCALE] = json.readUpscaleMultiplier()
            prefs[SHADER_CHAIN_ENABLED] = json.optBoolean("shaderChainEnabled", false)
            json.optString("shaderChainPreset").trim().takeIf(String::isNotEmpty)?.let {
                prefs[SHADER_CHAIN_PRESET] = it
            } ?: prefs.remove(SHADER_CHAIN_PRESET)
            json.optString("biosPath").takeIf { it.isNotBlank() }?.let { prefs[BIOS_PATH] = it } ?: prefs.remove(BIOS_PATH)
            val importedGamePaths = json.optJSONArray("gamePaths")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty().ifEmpty {
                listOfNotNull(json.optString("gamePath").trim().takeIf(String::isNotBlank))
            }.distinct()
            if (importedGamePaths.isEmpty()) {
                prefs.remove(GAME_PATHS)
                prefs.remove(GAME_PATH)
            } else {
                prefs[GAME_PATHS] = JSONArray(importedGamePaths).toString()
                prefs[GAME_PATH] = importedGamePaths.first()
            }
            json.optString("emulatorDataPath").takeIf { it.isNotBlank() }?.let {
                prefs[EMULATOR_DATA_PATH] = it
            } ?: prefs.remove(EMULATOR_DATA_PATH)
            json.optString("coverDownloadBaseUrl").takeIf { it.isNotBlank() }?.let {
                prefs[COVER_DOWNLOAD_BASE_URL] = it.trim().trimEnd('/')
            } ?: prefs.remove(COVER_DOWNLOAD_BASE_URL)
            prefs[COVER_ART_STYLE] = when (json.optInt("coverArtStyle", COVER_ART_STYLE_3D)) {
                COVER_ART_STYLE_DISABLED -> COVER_ART_STYLE_DISABLED
                COVER_ART_STYLE_3D -> COVER_ART_STYLE_3D
                else -> COVER_ART_STYLE_DEFAULT
            }
            prefs[ONBOARDING_COMPLETED] = json.optBoolean("onboardingCompleted", false)
            languageTag?.let { prefs[LANGUAGE_TAG] = it } ?: prefs.remove(LANGUAGE_TAG)
            prefs[ASPECT_RATIO] = normalizeAspectRatioPreference(json.optInt("aspectRatio", 1))
            DisplayCrop(
                left = json.optInt("displayCropLeft", 0),
                top = json.optInt("displayCropTop", 0),
                right = json.optInt("displayCropRight", 0),
                bottom = json.optInt("displayCropBottom", 0)
            ).sanitized().let { crop ->
                prefs[DISPLAY_CROP_LEFT] = crop.left
                prefs[DISPLAY_CROP_TOP] = crop.top
                prefs[DISPLAY_CROP_RIGHT] = crop.right
                prefs[DISPLAY_CROP_BOTTOM] = crop.bottom
            }
            prefs[AUDIO_VOLUME] = AudioDefaults.coerceVolume(json.optInt("audioVolume", AudioDefaults.VOLUME_DEFAULT))
            prefs[AUDIO_FAST_FORWARD_VOLUME] = AudioDefaults.coerceVolume(
                json.optInt("audioFastForwardVolume", AudioDefaults.VOLUME_DEFAULT)
            )
            prefs[AUDIO_MUTED] = json.optBoolean("audioMuted", false)
            prefs[AUDIO_OUTPUT_LATENCY_MS] = AudioDefaults.coerceOutputLatencyMs(
                json.optInt("audioOutputLatencyMs", AudioDefaults.OUTPUT_LATENCY_MS_DEFAULT)
            )
            prefs[AUDIO_MINIMAL_OUTPUT_LATENCY] = json.optBoolean(
                "audioMinimalOutputLatency",
                AudioDefaults.MINIMAL_OUTPUT_LATENCY_DEFAULT
            )
            prefs[PAD_VIBRATION] = json.optBoolean("padVibration", true)
            prefs[PAD_VIBRATION_STRENGTH] = json.optInt("padVibrationStrength", DEFAULT_PAD_VIBRATION_STRENGTH).coerceIn(0, 150)
            prefs[PAD_VIBRATION_FALLBACK] = json.optBoolean("padVibrationFallback", true)
            prefs[SHOW_FPS] = json.optBoolean("showFps", false)
            prefs[FPS_OVERLAY_MODE] = json.optInt("fpsOverlayMode", FPS_OVERLAY_MODE_DETAILED)
            prefs[FPS_OVERLAY_CORNER] = json.optInt("fpsOverlayCorner", FPS_OVERLAY_CORNER_TOP_RIGHT).coerceIn(
                FPS_OVERLAY_CORNER_TOP_LEFT,
                FPS_OVERLAY_CORNER_BOTTOM_RIGHT
            )
            prefs[FPS_OVERLAY_SCALE] = json.optInt("fpsOverlayScale", DEFAULT_FPS_OVERLAY_SCALE).coerceIn(
                MIN_FPS_OVERLAY_SCALE,
                MAX_FPS_OVERLAY_SCALE
            )
            prefs[FPS_OVERLAY_METRICS] = PerformanceOverlayMetrics.sanitize(
                json.optInt("fpsOverlayMetrics", PerformanceOverlayMetrics.DEFAULT)
            )
            prefs[CONFIRM_SAVE_LOAD_ACTIONS] = json.optBoolean("confirmSaveLoadActions", true)
            prefs[BACK_BUTTON_EXITS_GAME] = json.optBoolean("backButtonExitsGame", false)
            prefs[COMPACT_CONTROLS] = json.optBoolean("compactControls", true)
            prefs[KEEP_SCREEN_ON] = json.optBoolean("keepScreenOn", true)
            prefs[SHOW_RECENT_GAMES] = json.optBoolean("showRecentGames", true)
            prefs[SHOW_HOME_SEARCH] = json.optBoolean("showHomeSearch", false)
            prefs[SHOW_DEBUG_OPTIONS] = json.optBoolean("showDebugOptions", false)
            prefs[PROFILER_LOGCAT] = json.optBoolean("profilerLogcat", false)
            prefs[PREFER_ENGLISH_GAME_TITLES] = json.optBoolean("preferEnglishGameTitles", false)
            prefs[RECENT_GAMES] = json.optString("recentGames", "[]")
            prefs[HOME_LIBRARY_VIEW_MODE] = json.optInt("homeLibraryViewMode", 0).coerceIn(0, 2)
            prefs[OVERLAY_SCALE] = json.optInt("overlayScale", 100)
            prefs[OVERLAY_OPACITY] = json.optInt("overlayOpacity", DEFAULT_OVERLAY_OPACITY)
                .coerceIn(OVERLAY_OPACITY_MIN, OVERLAY_OPACITY_MAX)
            prefs[OVERLAY_SHOW] = json.optBoolean("overlayShow", true)
            prefs[RACING_MODE] = json.optBoolean("racingMode", false)
            prefs[TOUCHSCREEN_RIGHT_STICK] = json.optBoolean(
                "touchscreenRightStick",
                DEFAULT_TOUCHSCREEN_RIGHT_STICK
            )
            prefs[TOUCHSCREEN_RIGHT_STICK_SENSITIVITY] = json.optInt(
                "touchscreenRightStickSensitivity",
                DEFAULT_TOUCHSCREEN_RIGHT_STICK_SENSITIVITY
            ).coerceIn(
                TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN,
                TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX
            )
            prefs[TOUCH_HAPTICS] = json.optBoolean("touchHaptics", false)
            prefs[TOUCH_HAPTICS_PRESET] = json.optInt("touchHapticsPreset", DEFAULT_TOUCH_HAPTICS_PRESET).coerceIn(TOUCH_HAPTICS_PRESET_SOFT, TOUCH_HAPTICS_PRESET_STRONG)
            prefs[TOUCH_HAPTICS_STRENGTH] = json.optInt("touchHapticsStrength", DEFAULT_TOUCH_HAPTICS_STRENGTH).coerceIn(10, 100)
            prefs[GYRO_MODE] = json.optInt("gyroMode", GYRO_MODE_OFF).coerceIn(GYRO_MODE_OFF, GYRO_MODE_STEERING)
            prefs[GYRO_SENSITIVITY] = json.optInt("gyroSensitivity", DEFAULT_GYRO_SENSITIVITY).coerceIn(25, 300)
            prefs[GYRO_SMOOTHING] = json.optInt("gyroSmoothing", DEFAULT_GYRO_SMOOTHING).coerceIn(0, 90)
            prefs[GYRO_INVERT_X] = json.optBoolean("gyroInvertX", false)
            prefs[GYRO_INVERT_Y] = json.optBoolean("gyroInvertY", false)
            prefs[GAMEPAD_STICK_DEADZONE] = json.optInt("gamepadStickDeadzone", DEFAULT_GAMEPAD_STICK_DEADZONE).coerceIn(0, 35)
            prefs[GAMEPAD_LEFT_STICK_SENSITIVITY] = json.optInt("gamepadLeftStickSensitivity", DEFAULT_GAMEPAD_STICK_SENSITIVITY).coerceIn(50, 200)
            prefs[GAMEPAD_RIGHT_STICK_SENSITIVITY] = json.optInt("gamepadRightStickSensitivity", DEFAULT_GAMEPAD_STICK_SENSITIVITY).coerceIn(50, 200)
            prefs[GAMEPAD_RIGHT_STICK_UP_TO_R2] = json.optBoolean("gamepadRightStickUpToR2", false)
            prefs[GAMEPAD_RIGHT_STICK_DOWN_TO_L2] = json.optBoolean("gamepadRightStickDownToL2", false)
            prefs[GAMEPAD_BUTTON_HAPTICS] = json.optBoolean("gamepadButtonHaptics", false)
            prefs[PRESSURE_MODIFIER_AMOUNT] = json.optInt("pressureModifierAmount", DEFAULT_PRESSURE_MODIFIER_AMOUNT).coerceIn(1, 100)
            prefs[TEXTURE_REPLACEMENTS_ENABLED] = json.optBoolean("textureReplacementsEnabled", false)
            prefs[TEXTURE_REPLACEMENTS_ASYNC] = json.optBoolean("textureReplacementsAsync", true)
            prefs[TEXTURE_REPLACEMENTS_PRECACHE] = json.optBoolean("textureReplacementsPrecache", false)
            prefs[TEXTURE_DUMPING_ENABLED] = json.optBoolean("textureDumpingEnabled", false)
            prefs[ENABLE_AUTO_GAMEPAD] = json.optBoolean("enableAutoGamepad", true)
            prefs[HIDE_OVERLAY_ON_GAMEPAD] = json.optBoolean("hideOverlayOnGamepad", true)
            prefs[FLOATING_QUICK_ACTIONS_ENABLED] = json.optBoolean("floatingQuickActionsEnabled", false)
            json.optString("floatingQuickSavePosition").takeIf { it.isNotBlank() }?.let { prefs[FLOATING_QUICK_SAVE_POSITION] = it } ?: prefs.remove(FLOATING_QUICK_SAVE_POSITION)
            json.optString("floatingQuickLoadPosition").takeIf { it.isNotBlank() }?.let { prefs[FLOATING_QUICK_LOAD_POSITION] = it } ?: prefs.remove(FLOATING_QUICK_LOAD_POSITION)
            prefs[ORIENTATION_LOCK] = normalizeOrientationLock(json.optInt("orientationLock", ORIENTATION_LOCK_AUTO))
            prefs[EMULATION_ALLOWS_BOTH_ORIENTATIONS] = json.optBoolean("emulationAllowsBothOrientations", false)
            // Guarded: backups created before RetroAchievements existed must not
            // wipe the account token or the user's opt-in choice.
            if (json.has("retroAchievementsEnabled")) {
                prefs[RETRO_ACHIEVEMENTS_ENABLED] = json.optBoolean("retroAchievementsEnabled", false)
            }
            if (json.has("retroAchievementsUsername")) {
                json.optString("retroAchievementsUsername").takeIf { it.isNotBlank() }?.let { prefs[RETRO_ACHIEVEMENTS_USERNAME] = it } ?: prefs.remove(RETRO_ACHIEVEMENTS_USERNAME)
            }
            if (json.has("retroAchievementsToken")) {
                json.optString("retroAchievementsToken").takeIf { it.isNotBlank() }?.let { prefs[RETRO_ACHIEVEMENTS_TOKEN] = it } ?: prefs.remove(RETRO_ACHIEVEMENTS_TOKEN)
            }
            if (json.has("retroAchievementsHardcore")) {
                prefs[RETRO_ACHIEVEMENTS_HARDCORE] = json.optBoolean("retroAchievementsHardcore", false)
            }
            if (json.has("retroAchievementsUnofficial")) {
                prefs[RETRO_ACHIEVEMENTS_UNOFFICIAL] = json.optBoolean("retroAchievementsUnofficial", false)
            }
            if (json.has("retroAchievementsEncore")) {
                prefs[RETRO_ACHIEVEMENTS_ENCORE] = json.optBoolean("retroAchievementsEncore", false)
            }
            json.optString("gamepadBindings").takeIf { it.isNotBlank() }?.let { prefs[GAMEPAD_BINDINGS] = it } ?: prefs.remove(GAMEPAD_BINDINGS)
            json.optString("gamepadDeviceAssignments").takeIf { it.isNotBlank() }?.let { prefs[GAMEPAD_DEVICE_ASSIGNMENTS] = it } ?: prefs.remove(GAMEPAD_DEVICE_ASSIGNMENTS)
            json.optString("gamepadIgnoredDevices").takeIf { it.isNotBlank() }?.let { prefs[GAMEPAD_IGNORED_DEVICES] = it } ?: prefs.remove(GAMEPAD_IGNORED_DEVICES)
            prefs[GPU_DRIVER_TYPE] = json.optInt("gpuDriverType", 0)
            json.optString("customDriverPath").takeIf { it.isNotBlank() }?.let { prefs[CUSTOM_DRIVER_PATH] = it } ?: prefs.remove(CUSTOM_DRIVER_PATH)
            prefs[FRAME_LIMIT_ENABLED] = json.optBoolean("frameLimitEnabled", true)
            prefs[VSYNC_ENABLED] = json.optBoolean("vSyncEnabled", false)
            prefs[FAST_FORWARD_SPEED] = sanitizeFastForwardSpeed(json.optDouble("fastForwardSpeed", DEFAULT_FAST_FORWARD_SPEED.toDouble()).toFloat())
            prefs[TARGET_FPS] = json.optInt("targetFps", 0).let { if (it <= 0) 0 else it.coerceIn(20, 120) }
            prefs[NTSC_FRAMERATE] = sanitizeRegionFramerate(json.optDouble("ntscFramerate", DEFAULT_NTSC_FRAMERATE.toDouble()).toFloat(), DEFAULT_NTSC_FRAMERATE)
            prefs[PAL_FRAMERATE] = sanitizeRegionFramerate(json.optDouble("palFramerate", DEFAULT_PAL_FRAMERATE.toDouble()).toFloat(), DEFAULT_PAL_FRAMERATE)
            prefs[AUTO_SAVE_ENABLED] = json.optBoolean("autoSaveEnabled", false)
            prefs[AUTO_SAVE_INTERVAL_MINUTES] = json.optInt("autoSaveIntervalMinutes", 1).coerceIn(1, 999)
            val importedOverlayVersion = json.optInt("overlayLayoutVersion", 0)
            json.optString("dpadOffset").takeIf { it.isNotBlank() }?.let {
                prefs[DPAD_OFFSET] = if (importedOverlayVersion >= 12) {
                    it
                } else {
                    val (x, y) = parseOffsetStr(it, DEFAULT_DPAD_OFFSET_X to DEFAULT_DPAD_OFFSET_Y)
                    formatOffsetStr(x + LEFT_SIDE_LAYOUT_SHIFT_X, y)
                }
            } ?: prefs.remove(DPAD_OFFSET)
            json.optString("lstickOffset").takeIf { it.isNotBlank() }?.let {
                prefs[LSTICK_OFFSET] = if (importedOverlayVersion >= 12) {
                    it
                } else {
                    val (x, y) = parseOffsetStr(it, DEFAULT_LSTICK_OFFSET_X to DEFAULT_LSTICK_OFFSET_Y)
                    formatOffsetStr(x + LEFT_SIDE_LAYOUT_SHIFT_X, y)
                }
            } ?: prefs.remove(LSTICK_OFFSET)
            json.optString("rstickOffset").takeIf { it.isNotBlank() }?.let { prefs[RSTICK_OFFSET] = it } ?: prefs.remove(RSTICK_OFFSET)
            json.optString("actionOffset").takeIf { it.isNotBlank() }?.let { prefs[ACTION_OFFSET] = it } ?: prefs.remove(ACTION_OFFSET)
            json.optString("lbtnOffset").takeIf { it.isNotBlank() }?.let { prefs[LBTN_OFFSET] = it } ?: prefs.remove(LBTN_OFFSET)
            json.optString("rbtnOffset").takeIf { it.isNotBlank() }?.let { prefs[RBTN_OFFSET] = it } ?: prefs.remove(RBTN_OFFSET)
            json.optString("centerOffset").takeIf { it.isNotBlank() }?.let { prefs[CENTER_OFFSET] = it } ?: prefs.remove(CENTER_OFFSET)
            prefs[STICK_SCALE] = json.optInt("stickScale", OVERLAY_CONTROL_SCALE_DEFAULT)
                .coerceIn(OVERLAY_CONTROL_SCALE_MIN, OVERLAY_CONTROL_SCALE_MAX)
            prefs[LEFT_STICK_SENSITIVITY] = json.optInt("leftStickSensitivity", 100).coerceIn(50, 200)
            prefs[RIGHT_STICK_SENSITIVITY] = json.optInt("rightStickSensitivity", 100).coerceIn(50, 200)
            prefs[INVERT_LEFT_STICK] = json.optBoolean("invertLeftStick", false)
            prefs[INVERT_RIGHT_STICK] = json.optBoolean("invertRightStick", false)
            prefs[INVERT_LEFT_STICK_HORIZONTAL] = json.optBoolean("invertLeftStickHorizontal", false)
            prefs[INVERT_RIGHT_STICK_HORIZONTAL] = json.optBoolean("invertRightStickHorizontal", false)
            prefs[STICK_SURFACE_MODE] = json.optBoolean("stickSurfaceMode", false)
            json.optString("controlLayouts").takeIf { it.isNotBlank() }?.let { prefs[CONTROL_LAYOUTS] = it } ?: prefs.remove(CONTROL_LAYOUTS)
            migrateGlobalStickSurfaceMode(prefs)
            json.optString("memoryCardSlot1").takeIf { it.isNotBlank() }?.let { prefs[MEMORY_CARD_SLOT1] = it } ?: prefs.remove(MEMORY_CARD_SLOT1)
            json.optString("memoryCardSlot2").takeIf { it.isNotBlank() }?.let { prefs[MEMORY_CARD_SLOT2] = it } ?: prefs.remove(MEMORY_CARD_SLOT2)
        }
    }

    private fun readUpscale(prefs: Preferences): Float {
        return (prefs[UPSCALE]
            ?: prefs[UPSCALE_LEGACY]?.toFloat()
            ?: UPSCALE_DEFAULT).let(::normalizeUpscale)
    }

    private fun sanitizeRegionFramerate(value: Float?, fallback: Float): Float {
        val raw = value ?: fallback
        return if (raw.isFinite()) raw.coerceIn(20f, 120f) else fallback
    }


    private fun sanitizeFastForwardSpeed(value: Float?): Float {
        val raw = value ?: DEFAULT_FAST_FORWARD_SPEED
        return if (raw.isFinite()) raw.coerceIn(MIN_FAST_FORWARD_SPEED, MAX_FAST_FORWARD_SPEED) else DEFAULT_FAST_FORWARD_SPEED
    }

    private fun JSONObject.readUpscaleMultiplier(): Float {
        val doubleValue = optDouble("upscaleMultiplier", Double.NaN)
        return when {
            !doubleValue.isNaN() -> doubleValue.toFloat()
            has("upscaleMultiplier") -> optInt("upscaleMultiplier", UPSCALE_DEFAULT.toInt()).toFloat()
            else -> UPSCALE_DEFAULT
        }.let(::normalizeUpscale)
    }

}
