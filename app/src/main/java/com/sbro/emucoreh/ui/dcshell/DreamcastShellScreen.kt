package com.sbro.emucoreh.ui.dcshell

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucoreh.BuildConfig
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.DreamcastBios
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.core.SaturnBios
import com.sbro.emucoreh.core.Sega32XBios
import com.sbro.emucoreh.core.SegaCdBios
import com.sbro.emucoreh.core.UiSoundManager
import com.sbro.emucoreh.data.CoverArtRepository
import com.sbro.emucoreh.data.CustomGameCoverRepository
import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.LibraryPlatform
import com.sbro.emucoreh.data.LibraryPlatformResolver
import com.sbro.emucoreh.data.SaveStateEntryInfo
import com.sbro.emucoreh.data.SaveStateRepository
import com.sbro.emucoreh.data.VmuCardSummary
import com.sbro.emucoreh.data.VmuFilesystem
import com.sbro.emucoreh.data.VmuRepository
import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import com.sbro.emucoreh.dcui.DcImageLoader
import com.sbro.emucoreh.dcui.DcShellAudio
import com.sbro.emucoreh.dcui.DcShellHost
import com.sbro.emucoreh.dcui.DcShellNative
import com.sbro.emucoreh.dcui.DcShellSurfaceRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private class DcGlSurfaceView(context: Context) : GLSurfaceView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            holder.setSizeFromLayout()
            post {
                requestLayout()
                queueEvent { DcShellNative.nativeResize(w, h) }
                requestRender()
            }
        }
    }
}

private data class DcResolvedGame(
    val title: String,
    val path: String,
    val serial: String,
    val coverPath: String,
    val sizeStr: String,
    val dateStr: String,
    val platformId: String,
    val platformLabel: String,
    val saveCount: Int,
    val latestSaveSlot: Int,
    val discCount: Int,
    val discLabelsPacked: String,
    val discPathsPacked: String,
    val recentRank: Int,
)

private data class DcResolvedSave(
    val info: SaveStateEntryInfo,
    val platformLabel: String,
    val previewPath: String,
)

private data class DcSystemSnapshot(
    val batteryLevel: Int = 100,
    val batteryCharging: Boolean = false,
    val wifiConnected: Boolean = false,
    val bluetoothConnected: Boolean = false,
    val audioMuted: Boolean = false,
    val freeStorage: String = "",
    val deviceName: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val biosSummary: String = "",
    val biosReadyCount: Int = 0,
    val biosTotalCount: Int = 5,
)

private object DcShellSessionCache {
    @Volatile
    var lastSaves: List<DcResolvedSave> = emptyList()

    @Volatile
    var hasLoadedSaves: Boolean = false

    @Volatile
    var lastVmuCards: List<VmuCardSummary> = emptyList()

    @Volatile
    var hasLoadedVmus: Boolean = false

    @Volatile
    var returnedFromGame: Boolean = false

    @Volatile
    var lastSystemSnapshot: DcSystemSnapshot = DcSystemSnapshot()
}

private val DC_ORDERED_PLATFORMS: List<LibraryPlatform> = listOf(
    LibraryPlatform.DREAMCAST,
    LibraryPlatform.SATURN,
    LibraryPlatform.GENESIS,
    LibraryPlatform.SEGA_CD,
    LibraryPlatform.SEGA_32X,
    LibraryPlatform.MASTER_SYSTEM,
    LibraryPlatform.GAME_GEAR,
    LibraryPlatform.SG_1000,
    LibraryPlatform.NAOMI,
    LibraryPlatform.NAOMI_2,
    LibraryPlatform.ATOMISWAVE,
    LibraryPlatform.SYSTEM_SP,
    LibraryPlatform.MODEL_3,
    LibraryPlatform.ARCADE,
)

private val DC_STRING_BINDINGS: List<Pair<String, Int>> = listOf(
    "dcui_menu_play" to R.string.dcui_menu_play,
    "dcui_menu_file" to R.string.dcui_menu_file,
    "dcui_menu_file_mgr" to R.string.dcui_menu_file_mgr,
    "dcui_menu_saves" to R.string.dcui_menu_saves,
    "dcui_menu_settings" to R.string.dcui_menu_settings,
    "dcui_menu_settings_sub" to R.string.dcui_menu_settings_sub,
    "dcui_hub_systems_title" to R.string.dcui_hub_systems_title,
    "dcui_tab_recent" to R.string.dcui_tab_recent,
    "dcui_games_word" to R.string.dcui_games_word,
    "dcui_systems_word" to R.string.dcui_systems_word,
    "dcui_vmu_cards_word" to R.string.dcui_vmu_cards_word,
    "dcui_vmu_subtitle_empty" to R.string.dcui_vmu_subtitle_empty,
    "dcui_saves_word" to R.string.dcui_saves_word,
    "dcui_disc" to R.string.dcui_disc,
    "dcui_discs" to R.string.dcui_discs,
    "dcui_quick_saves_lbl" to R.string.dcui_quick_saves_lbl,
    "dcui_slot_auto" to R.string.dcui_slot_auto,
    "dcui_loading_games" to R.string.dcui_loading_games,
    "dcui_no_games" to R.string.dcui_no_games,
    "dcui_loading_saves" to R.string.dcui_loading_saves,
    "dcui_btn_start" to R.string.dcui_btn_start,
    "dcui_btn_continue" to R.string.dcui_btn_continue,
    "dcui_btn_options" to R.string.dcui_btn_options,
    "dcui_btn_exit" to R.string.dcui_btn_exit,
    "dcui_hint_select" to R.string.dcui_hint_select,
    "dcui_hint_back" to R.string.dcui_hint_back,
    "dcui_hint_exit" to R.string.dcui_hint_exit,
    "dcui_hint_tab" to R.string.dcui_hint_tab,
    "dcui_hint_change" to R.string.dcui_hint_change,
    "dcui_mc_tab_all" to R.string.dcui_mc_tab_all,
    "dcui_mc_tab_game" to R.string.dcui_mc_tab_game,
    "dcui_mc_tab_vmu" to R.string.dcui_mc_tab_vmu,
    "dcui_mc_no_saves" to R.string.dcui_mc_no_saves,
    "dcui_mc_empty_block" to R.string.dcui_mc_empty_block,
    "dcui_mc_load" to R.string.dcui_mc_load,
    "dcui_mc_delete" to R.string.dcui_mc_delete,
    "dcui_mc_exit" to R.string.dcui_mc_exit,
    "dcui_vmu_formatted" to R.string.dcui_vmu_formatted,
    "dcui_vmu_unformatted" to R.string.dcui_vmu_unformatted,
    "dcui_vmu_blocks_free" to R.string.dcui_vmu_blocks_free,
    "dcui_vmu_none" to R.string.dcui_vmu_none,
    "dcui_vmu_create_btn" to R.string.dcui_vmu_create_btn,
    "dcui_vmu_format_btn" to R.string.dcui_vmu_format_btn,
    "dcui_vmu_files_word" to R.string.dcui_vmu_files_word,
    "dcui_vmu_empty_hint" to R.string.dcui_vmu_empty_hint,
    "dcui_vmu_loading" to R.string.dcui_vmu_loading,
    "dcui_vmu_need_format" to R.string.dcui_vmu_need_format,
    "dcui_vmu_no_files" to R.string.dcui_vmu_no_files,
    "dcui_theme_pearl" to R.string.dcui_theme_pearl,
    "dcui_theme_pal_blue" to R.string.dcui_theme_pal_blue,
    "dcui_theme_sports" to R.string.dcui_theme_sports,
    "dcui_theme_katana" to R.string.dcui_theme_katana,
    "dcui_theme_saturn" to R.string.dcui_theme_saturn,
    "dcui_aspect_auto" to R.string.dcui_aspect_auto,
    "dcui_aspect_16_9" to R.string.dcui_aspect_16_9,
    "dcui_aspect_16_10" to R.string.dcui_aspect_16_10,
    "dcui_aspect_4_3" to R.string.dcui_aspect_4_3,
    "dcui_motion_smooth" to R.string.dcui_motion_smooth,
    "dcui_motion_calm" to R.string.dcui_motion_calm,
    "dcui_motion_eco" to R.string.dcui_motion_eco,
    "dcui_motion_static" to R.string.dcui_motion_static,
    "dcui_scan_vga" to R.string.dcui_scan_vga,
    "dcui_scan_crt" to R.string.dcui_scan_crt,
    "dcui_boot_full" to R.string.dcui_boot_full,
    "dcui_boot_fast" to R.string.dcui_boot_fast,
    "dcui_on" to R.string.dcui_on,
    "dcui_off" to R.string.dcui_off,
    "dcui_time_24h" to R.string.dcui_time_24h,
    "dcui_time_12h" to R.string.dcui_time_12h,
    "dcui_open" to R.string.dcui_open,
    "dcui_view" to R.string.dcui_view,
    "dcui_reset" to R.string.dcui_reset,
    "dcui_set_theme" to R.string.dcui_set_theme,
    "dcui_set_aspect" to R.string.dcui_set_aspect,
    "dcui_set_motion" to R.string.dcui_set_motion,
    "dcui_set_scanlines" to R.string.dcui_set_scanlines,
    "dcui_set_boot_anim" to R.string.dcui_set_boot_anim,
    "dcui_set_key_tone" to R.string.dcui_set_key_tone,
    "dcui_set_ambient" to R.string.dcui_set_ambient,
    "dcui_set_confirm_btn" to R.string.dcui_set_confirm_btn,
    "dcui_set_touch" to R.string.dcui_set_touch,
    "dcui_set_touch_guide" to R.string.dcui_set_touch_guide,
    "dcui_set_time_fmt" to R.string.dcui_set_time_fmt,
    "dcui_set_date_fmt" to R.string.dcui_set_date_fmt,
    "dcui_set_keep_screen" to R.string.dcui_set_keep_screen,
    "dcui_set_sysinfo" to R.string.dcui_set_sysinfo,
    "dcui_set_restore" to R.string.dcui_set_restore,
    "dcui_launching_save" to R.string.dcui_launching_save,
    "dcui_launching_game" to R.string.dcui_launching_game,
    "dcui_exit_title" to R.string.dcui_exit_title,
    "dcui_exit_msg" to R.string.dcui_exit_msg,
    "dcui_del_title" to R.string.dcui_del_title,
    "dcui_del_msg" to R.string.dcui_del_msg,
    "dcui_vmu_del_title" to R.string.dcui_vmu_del_title,
    "dcui_vmu_del_msg" to R.string.dcui_vmu_del_msg,
    "dcui_vmu_fmt_title" to R.string.dcui_vmu_fmt_title,
    "dcui_vmu_fmt_msg" to R.string.dcui_vmu_fmt_msg,
    "dcui_restore_title" to R.string.dcui_restore_title,
    "dcui_restore_msg" to R.string.dcui_restore_msg,
    "dcui_yes" to R.string.dcui_yes,
    "dcui_no" to R.string.dcui_no,
    "dcui_opt_start" to R.string.dcui_opt_start,
    "dcui_opt_continue" to R.string.dcui_opt_continue,
    "dcui_opt_saves" to R.string.dcui_opt_saves,
    "dcui_opt_info" to R.string.dcui_opt_info,
    "dcui_opt_settings" to R.string.dcui_opt_settings,
    "dcui_select_disc" to R.string.dcui_select_disc,
    "dcui_info_title" to R.string.dcui_info_title,
    "dcui_info_console" to R.string.dcui_info_console,
    "dcui_info_serial" to R.string.dcui_info_serial,
    "dcui_info_size" to R.string.dcui_info_size,
    "dcui_info_discs" to R.string.dcui_info_discs,
    "dcui_close" to R.string.dcui_close,
    "dcui_sys_app" to R.string.dcui_sys_app,
    "dcui_sys_device" to R.string.dcui_sys_device,
    "dcui_sys_storage" to R.string.dcui_sys_storage,
    "dcui_sys_bios" to R.string.dcui_sys_bios,
    "dcui_touch_guide_title" to R.string.dcui_touch_guide_title,
    "dcui_touch_tip_1" to R.string.dcui_touch_tip_1,
    "dcui_touch_tip_2" to R.string.dcui_touch_tip_2,
    "dcui_touch_tip_3" to R.string.dcui_touch_tip_3,
    "dcui_touch_tip_4" to R.string.dcui_touch_tip_4,
    "dcui_got_it" to R.string.dcui_got_it,
)

private fun platformLabelRes(platform: LibraryPlatform): Int = when (platform) {
    LibraryPlatform.DREAMCAST -> R.string.home_platform_dreamcast
    LibraryPlatform.NAOMI -> R.string.home_platform_naomi
    LibraryPlatform.NAOMI_2 -> R.string.home_platform_naomi_2
    LibraryPlatform.ATOMISWAVE -> R.string.home_platform_atomiswave
    LibraryPlatform.SYSTEM_SP -> R.string.home_platform_system_sp
    LibraryPlatform.MODEL_3 -> R.string.home_platform_model_3
    LibraryPlatform.GENESIS -> R.string.home_platform_genesis
    LibraryPlatform.SEGA_CD -> R.string.home_platform_sega_cd
    LibraryPlatform.SATURN -> R.string.home_platform_saturn
    LibraryPlatform.SEGA_32X -> R.string.home_platform_sega_32x
    LibraryPlatform.MASTER_SYSTEM -> R.string.home_platform_master_system
    LibraryPlatform.GAME_GEAR -> R.string.home_platform_game_gear
    LibraryPlatform.SG_1000 -> R.string.home_platform_sg_1000
    LibraryPlatform.ARCADE -> R.string.home_platform_arcade
}

private fun platformAccentArgb(platform: LibraryPlatform): Int = when (platform) {
    LibraryPlatform.DREAMCAST -> 0xFFEE5522.toInt()
    LibraryPlatform.SATURN -> 0xFF2B7BFF.toInt()
    LibraryPlatform.GENESIS -> 0xFF4B6FFF.toInt()
    LibraryPlatform.SEGA_CD -> 0xFF0099D6.toInt()
    LibraryPlatform.SEGA_32X -> 0xFFE63946.toInt()
    LibraryPlatform.MASTER_SYSTEM -> 0xFFD62828.toInt()
    LibraryPlatform.GAME_GEAR -> 0xFF2EA843.toInt()
    LibraryPlatform.SG_1000 -> 0xFFD97736.toInt()
    LibraryPlatform.NAOMI -> 0xFFFF6B00.toInt()
    LibraryPlatform.NAOMI_2 -> 0xFFE87A00.toInt()
    LibraryPlatform.ATOMISWAVE -> 0xFF00A6CE.toInt()
    LibraryPlatform.SYSTEM_SP -> 0xFF7B3FE4.toInt()
    LibraryPlatform.MODEL_3 -> 0xFFE63288.toInt()
    LibraryPlatform.ARCADE -> 0xFF2A85D8.toInt()
}

private fun normalizeSerialKey(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val upper = raw.trim().uppercase(Locale.ROOT)
    val dashPat = Regex("([A-Z]{4})[^A-Z0-9]*(\\d{3})[^A-Z0-9]*(\\d{2})")
    dashPat.find(upper)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}${it.groupValues[3]}" }
    val compactPat = Regex("([A-Z]{4})[^A-Z0-9]*(\\d{5})")
    compactPat.find(upper)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
    return upper.replace(Regex("[^A-Z0-9]"), "").takeIf { it.isNotBlank() }
}

private fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes <= 0L -> ""
    sizeBytes < 1024L * 1024L -> "${((sizeBytes + 1023L) / 1024L).coerceAtLeast(1L)} KB"
    else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}

private fun formatTimestamp(ms: Long): String = if (ms > 0L) {
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
} else {
    ""
}

private fun hasPhysicalGamepadConnected(): Boolean = runCatching {
    InputDevice.getDeviceIds().any { id ->
        val dev = InputDevice.getDevice(id) ?: return@any false
        if (dev.isVirtual) return@any false
        val sources = dev.sources
        val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        isGamepad && dev.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_UP,
        ).any { it }
    }
}.getOrDefault(false)

private fun readSystemSnapshot(context: Context): DcSystemSnapshot {
    val batteryIntent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val lvl = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val pct = if (scale > 0 && lvl >= 0) (lvl * 100) / scale else 100

    val wifiConnected = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    val btEnabled = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    }.getOrDefault(false)
    val btAudio = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        } == true
    }.getOrDefault(false)
    val muted = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1) <= 0
    }.getOrDefault(false)

    val freeText = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val avail = stat.availableBytes
        if (avail >= 1024L * 1024L * 1024L) {
            String.format(Locale.US, "%.1f GB", avail / (1024.0 * 1024.0 * 1024.0))
        } else {
            "${(avail / (1024L * 1024L)).coerceAtLeast(0L)} MB"
        }
    }.getOrDefault("")

    val flycastSys = EmulatorStorage.flycastSystemDir(context).absolutePath
    val dcOk = DreamcastBios.hasBootRom(flycastSys)
    val arcOk = DreamcastBios.hasArcadeBios(flycastSys)
    val satOk = SaturnBios.isInstalled(context)
    val scdOk = SegaCdBios.Region.entries.any { SegaCdBios.isInstalled(context, it) }
    val s32xOk = Sega32XBios.Part.entries.all { Sega32XBios.isInstalled(context, it) }
    val readyCount = listOf(dcOk, arcOk, satOk, scdOk, s32xOk).count { it }
    val summary = buildString {
        append("DC").append(if (dcOk) "✓" else "–")
        append(" · ARC").append(if (arcOk) "✓" else "–")
        append(" · SAT").append(if (satOk) "✓" else "–")
        append(" · CD").append(if (scdOk) "✓" else "–")
        append(" · 32X").append(if (s32xOk) "✓" else "–")
    }

    return DcSystemSnapshot(
        batteryLevel = pct,
        batteryCharging = charging,
        wifiConnected = wifiConnected,
        bluetoothConnected = btEnabled && btAudio,
        audioMuted = muted,
        freeStorage = freeText,
        deviceName = Build.MODEL.orEmpty().trim().ifBlank { "Android" },
        appVersion = BuildConfig.VERSION_NAME,
        biosSummary = summary,
        biosReadyCount = readyCount,
        biosTotalCount = 5,
    )
}

private fun pushStringsToNative(context: Context) {
    val keys = Array(DC_STRING_BINDINGS.size) { i -> DC_STRING_BINDINGS[i].first }
    val values = Array(DC_STRING_BINDINGS.size) { i ->
        runCatching { context.getString(DC_STRING_BINDINGS[i].second) }.getOrDefault("")
    }
    DcShellNative.nativeSetStrings(keys, values)
}

private fun pushConsoleTabsToNative(context: Context) {
    val tabIds = Array(DC_ORDERED_PLATFORMS.size) { i -> DC_ORDERED_PLATFORMS[i].name }
    val tabLabels = Array(DC_ORDERED_PLATFORMS.size) { i ->
        context.getString(platformLabelRes(DC_ORDERED_PLATFORMS[i]))
    }
    val accentColors = IntArray(DC_ORDERED_PLATFORMS.size) { i ->
        platformAccentArgb(DC_ORDERED_PLATFORMS[i])
    }
    DcShellNative.nativeSetConsoleTabs(tabIds, tabLabels, accentColors)
}

private fun pushGamesToNative(games: List<DcResolvedGame>) {
    DcShellNative.nativeSetGames(
        titles = games.map { it.title }.toTypedArray(),
        paths = games.map { it.path }.toTypedArray(),
        serials = games.map { it.serial }.toTypedArray(),
        covers = games.map { it.coverPath }.toTypedArray(),
        sizes = games.map { it.sizeStr }.toTypedArray(),
        dates = games.map { it.dateStr }.toTypedArray(),
        platformIds = games.map { it.platformId }.toTypedArray(),
        platformLabels = games.map { it.platformLabel }.toTypedArray(),
        saveCounts = games.map { it.saveCount }.toIntArray(),
        latestSaveSlots = games.map { it.latestSaveSlot }.toIntArray(),
        discCounts = games.map { it.discCount }.toIntArray(),
        discLabelsPacked = games.map { it.discLabelsPacked }.toTypedArray(),
        discPathsPacked = games.map { it.discPathsPacked }.toTypedArray(),
        recentRanks = games.map { it.recentRank }.toIntArray(),
    )
}

private fun pushSavesToNative(context: Context, saves: List<DcResolvedSave>) {
    val autoLabel = context.getString(R.string.dcui_slot_auto)
    val titles = Array(saves.size) { i -> saves[i].info.gameTitle.ifBlank { saves[i].info.serial } }
    val slots = Array(saves.size) { i ->
        val s = saves[i].info
        if (s.isAutoSave) autoLabel else context.getString(R.string.dcui_slot_fmt, s.slot)
    }
    val slotNums = IntArray(saves.size) { i -> saves[i].info.slot }
    val dates = Array(saves.size) { i -> formatTimestamp(saves[i].info.lastModified) }
    val sizes = Array(saves.size) { i -> formatFileSize(saves[i].info.sizeBytes) }
    val serials = Array(saves.size) { i -> saves[i].info.serial }
    val platforms = Array(saves.size) { i -> saves[i].platformLabel }
    val previews = Array(saves.size) { i -> saves[i].previewPath }
    val savePaths = Array(saves.size) { i -> saves[i].info.absolutePath }
    val gamePaths = Array(saves.size) { i -> saves[i].info.gamePath.orEmpty() }
    DcShellNative.nativeSetSaves(titles, slots, slotNums, dates, sizes, serials, platforms, previews, savePaths, gamePaths)
}

private fun pushVmuCardsToNative(cards: List<VmuCardSummary>) {
    val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    DcShellNative.nativeSetVmuCards(
        portNames = cards.map { it.portName }.toTypedArray(),
        fileNames = cards.map { it.fileName }.toTypedArray(),
        sizes = cards.map { formatFileSize(it.sizeBytes) }.toTypedArray(),
        dates = cards.map { if (it.modifiedAt > 0L) dateFmt.format(Date(it.modifiedAt)) else "" }.toTypedArray(),
        freeBlocks = cards.map { it.freeBlocks }.toIntArray(),
        totalBlocks = cards.map { it.totalBlocks }.toIntArray(),
        formattedFlags = cards.map { it.isFormatted }.toBooleanArray(),
        fileCounts = cards.map { it.files.size }.toIntArray(),
    )
}

private fun pushVmuFilesToNative(cardIndex: Int, cards: List<VmuCardSummary>) {
    val card = cards.getOrNull(cardIndex)
    val files = card?.files.orEmpty()
    DcShellNative.nativeSetVmuFiles(
        cardIndex = cardIndex,
        fileNames = files.map { it.fileName }.toTypedArray(),
        descriptions = files.map { it.shortDesc }.toTypedArray(),
        dates = files.map { it.timestamp }.toTypedArray(),
        blockCounts = files.map { it.blocks }.toIntArray(),
        iconPaths = files.map { it.iconPath.orEmpty() }.toTypedArray(),
    )
}

private fun pushSystemSnapshotToNative(snap: DcSystemSnapshot) {
    DcShellNative.nativeSetSystemStatus(
        batteryLevel = snap.batteryLevel,
        batteryCharging = snap.batteryCharging,
        wifiConnected = snap.wifiConnected,
        bluetoothConnected = snap.bluetoothConnected,
        audioMuted = snap.audioMuted,
        freeStorage = snap.freeStorage,
        deviceName = snap.deviceName,
        appVersion = snap.appVersion,
        biosSummary = snap.biosSummary,
        biosReadyCount = snap.biosReadyCount,
        biosTotalCount = snap.biosTotalCount,
    )
}

private fun inferSavePlatformLabel(
    context: Context,
    entry: SaveStateEntryInfo,
    matchingGame: GameItem?,
    arcadeCatalog: ArcadeCatalogRepository,
): String {
    if (matchingGame != null) {
        val plat = LibraryPlatformResolver.resolve(matchingGame, arcadeCatalog::find)
        return context.getString(platformLabelRes(plat))
    }
    val fn = entry.fileName.uppercase(Locale.ROOT)
    val guessed = when {
        fn.startsWith("JG-GENESIS-") -> LibraryPlatform.GENESIS
        fn.startsWith("JG-SEGA_CD-") -> LibraryPlatform.SEGA_CD
        fn.startsWith("JG-SEGA_32X-") -> LibraryPlatform.SEGA_32X
        fn.startsWith("JG-MASTER_SYSTEM-") -> LibraryPlatform.MASTER_SYSTEM
        fn.startsWith("JG-GAME_GEAR-") -> LibraryPlatform.GAME_GEAR
        fn.startsWith("JG-SG_1000-") -> LibraryPlatform.SG_1000
        fn.startsWith("SS-") -> LibraryPlatform.SATURN
        fn.startsWith("M3-") -> LibraryPlatform.MODEL_3
        else -> LibraryPlatform.DREAMCAST
    }
    return context.getString(platformLabelRes(guessed))
}

/**
 * Full-screen native GLES 3.0 Sega Dreamcast BIOS shell replacing EmuCoreH's 3D shelf carousel.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun DreamcastShellScreen(
    games: List<GameItem>,
    recentGames: List<GameItem>,
    isLoadingGames: Boolean,
    isCoverArtDisabled: Boolean,
    onGamePathSelected: (String) -> Unit,
    onSaveSelected: (gamePath: String, slot: Int) -> Unit,
    onManageGame: (GameItem) -> Unit,
    onExitShell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val localeTag = context.resources.configuration.locales.toLanguageTags()

    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    val insetLeftPx = safeInsets.getLeft(density, layoutDir).toFloat()
    val insetTopPx = safeInsets.getTop(density).toFloat()
    val insetRightPx = safeInsets.getRight(density, layoutDir).toFloat()
    val insetBottomPx = safeInsets.getBottom(density).toFloat()

    val currentGames = rememberUpdatedState(games)
    val currentRecentGames = rememberUpdatedState(recentGames)
    val currentLoadingGames = rememberUpdatedState(isLoadingGames)
    val currentGameLaunch = rememberUpdatedState(onGamePathSelected)
    val currentSaveLaunch = rememberUpdatedState(onSaveSelected)
    val currentManageGame = rememberUpdatedState(onManageGame)
    val currentExit = rememberUpdatedState(onExitShell)

    val customCoverRepo = remember(context) { CustomGameCoverRepository(context) }
    val coverArtRepo = remember(context) { CoverArtRepository(context) }
    val arcadeCatalog = remember(context) { ArcadeCatalogRepository(context) }
    val saveRepo = remember(context) { SaveStateRepository(context) }
    val vmuRepo = remember(context) { VmuRepository(context) }
    val vmuIconCacheDir = remember(context) { File(context.cacheDir, "vmu-icons").apply { mkdirs() } }

    val shellPrefs = remember(context) {
        context.getSharedPreferences("dc_shell_state", Context.MODE_PRIVATE)
    }

    var savesRevision by remember { mutableIntStateOf(0) }
    var vmuRevision by remember { mutableIntStateOf(0) }
    var selectedVmuCardIdx by remember { mutableIntStateOf(shellPrefs.getInt("vmu_card_index", 0)) }
    var currentMotionMode by remember { mutableIntStateOf(shellPrefs.getInt("motion_mode", 0)) }

    val saveStates by produceState<List<DcResolvedSave>>(
        initialValue = DcShellSessionCache.lastSaves,
        savesRevision,
        games,
        context,
    ) {
        val resolved = withContext(Dispatchers.IO) {
            val raw = runCatching { saveRepo.listEntries() }.getOrDefault(emptyList())
            val firstPass = runCatching { saveRepo.enrichGlobalEntries(raw) }.getOrDefault(raw)
            val byPath = games.associateBy { it.path }
            val bySerial = games.mapNotNull { g -> normalizeSerialKey(g.serial)?.let { it to g } }.toMap()

            firstPass.map { entry ->
                val match = entry.gamePath?.let { byPath[it] }
                    ?: normalizeSerialKey(entry.serial)?.let { bySerial[it] }
                val base = if (match != null && (entry.gamePath.isNullOrBlank() || entry.coverArtPath.isNullOrBlank())) {
                    entry.copy(
                        gamePath = entry.gamePath ?: match.path,
                        gameTitle = entry.gameTitle.ifBlank { match.title },
                        coverArtPath = match.coverArtPath ?: entry.coverArtPath,
                    )
                } else {
                    entry
                }
                val repoPreview = runCatching { saveRepo.getPreviewImagePath(base) }.getOrNull()
                val sidecar = File("${base.absolutePath}.png")
                val resolvedPreview = when {
                    !repoPreview.isNullOrBlank() && File(repoPreview).isFile -> repoPreview
                    sidecar.isFile && sidecar.length() > 0L -> sidecar.absolutePath
                    !base.coverArtPath.isNullOrBlank() && File(base.coverArtPath).isFile -> base.coverArtPath
                    else -> ""
                }
                val platLabel = inferSavePlatformLabel(context, base, match, arcadeCatalog)
                DcResolvedSave(
                    info = base,
                    platformLabel = platLabel,
                    previewPath = resolvedPreview,
                )
            }
        }
        DcShellSessionCache.lastSaves = resolved
        DcShellSessionCache.hasLoadedSaves = true
        value = resolved
    }
    val currentSaves = rememberUpdatedState(saveStates)

    val resolvedGames by produceState<List<DcResolvedGame>>(
        initialValue = emptyList(),
        games,
        recentGames,
        saveStates,
        isCoverArtDisabled,
        localeTag,
        context,
    ) {
        value = withContext(Dispatchers.IO) {
            val coverSettings = coverArtRepo.displayCoverSettingsAsync()
            val customIndex = customCoverRepo.customCoverIndex()
            val recentRankByPath = HashMap<String, Int>(recentGames.size)
            recentGames.forEachIndexed { idx, item -> recentRankByPath[item.path] = idx }

            val savesByGamePath = saveStates.groupBy { it.info.gamePath.orEmpty() }
            val savesBySerial = saveStates.mapNotNull { sv ->
                normalizeSerialKey(sv.info.serial)?.let { it to sv }
            }.groupBy({ it.first }, { it.second })

            games.map { game ->
                val plat = LibraryPlatformResolver.resolve(game, arcadeCatalog::find)
                val platLabel = context.getString(platformLabelRes(plat))
                val customCover = customCoverRepo.findCustomCoverPath(game.path, customIndex)
                val cachedCover = if (!isCoverArtDisabled) {
                    game.coverArtPath?.takeIf { File(it).isFile }
                        ?: coverArtRepo.findCachedCoverPath(
                            serial = game.serial,
                            title = game.title,
                            ignoreDisabled = false,
                            platform = game.platform ?: plat.name,
                            settings = coverSettings,
                        )
                } else {
                    null
                }
                val resolvedCover = customCover ?: cachedCover.orEmpty()

                val discPaths = if (game.discs.size > 1) {
                    game.discs.map { it.path }
                } else {
                    listOf(game.path)
                }
                val discLabels = if (game.discs.size > 1) {
                    game.discs.map { d -> context.getString(R.string.dcui_disc) + " " + d.number }
                } else {
                    emptyList()
                }
                val matchingSaves = buildList {
                    discPaths.forEach { dp -> addAll(savesByGamePath[dp].orEmpty()) }
                    normalizeSerialKey(game.serial)?.let { ser -> addAll(savesBySerial[ser].orEmpty()) }
                }.distinctBy { it.info.absolutePath }
                val latestSlot = matchingSaves.maxByOrNull { it.info.lastModified }?.info?.slot ?: -1

                val recentRank = recentRankByPath[game.path]
                    ?: discPaths.mapNotNull { recentRankByPath[it] }.minOrNull()
                    ?: -1

                DcResolvedGame(
                    title = game.title,
                    path = game.path,
                    serial = game.serial.orEmpty(),
                    coverPath = resolvedCover,
                    sizeStr = formatFileSize(game.discSize ?: game.fileSize),
                    dateStr = formatTimestamp(game.lastModified),
                    platformId = plat.name,
                    platformLabel = platLabel,
                    saveCount = matchingSaves.size,
                    latestSaveSlot = latestSlot,
                    discCount = discPaths.size.coerceAtLeast(1),
                    discLabelsPacked = discLabels.joinToString("\n"),
                    discPathsPacked = discPaths.joinToString("\n"),
                    recentRank = recentRank,
                )
            }
        }
    }
    val currentResolvedGames = rememberUpdatedState(resolvedGames)

    val vmuCards by produceState<List<VmuCardSummary>>(
        initialValue = DcShellSessionCache.lastVmuCards,
        vmuRevision,
        context,
    ) {
        val loaded = withContext(Dispatchers.IO) {
            val dir = vmuRepo.saveDirectory
            vmuRepo.vmus()
                .map { File(dir, it.name) }
                .filter { VmuFilesystem.isVmuImageFile(it) }
                .map { file -> VmuFilesystem.inspectCard(file, vmuIconCacheDir) }
        }
        DcShellSessionCache.lastVmuCards = loaded
        DcShellSessionCache.hasLoadedVmus = true
        value = loaded
    }
    val currentVmuCards = rememberUpdatedState(vmuCards)

    fun applyHostSettings(
        keyTone: Boolean,
        ambientSound: Boolean,
        swapConfirm: Boolean,
        keepScreenOn: Boolean,
    ) {
        DcShellAudio.keyToneEnabled = keyTone
        DcShellAudio.ambientEnabled = ambientSound
        DcShellHost.swapConfirmButton = swapConfirm
        val window = (context as? Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun pushSettingsToNativeOnGlThread() {
        val theme = shellPrefs.getInt("theme", 0)
        val aspectMode = shellPrefs.getInt("aspect_mode", 0)
        val motionMode = shellPrefs.getInt("motion_mode", 0)
        val scanlinesMode = shellPrefs.getInt("scanlines_mode", 0)
        val bootAnimMode = shellPrefs.getInt("boot_anim_mode", 0)
        val keyTone = shellPrefs.getBoolean("key_tone", true)
        val ambientSound = shellPrefs.getBoolean("ambient_sound", false)
        val swapConfirm = shellPrefs.getBoolean("swap_confirm", false)
        val touchControls = shellPrefs.getBoolean("touch_controls", true)
        val time24h = shellPrefs.getBoolean("time_24h", true)
        val dateFormat = shellPrefs.getInt("date_format", 0)
        val keepScreenOn = shellPrefs.getBoolean("keep_screen_on", true)
        DcShellNative.nativeSetSettings(
            theme = theme,
            aspectMode = aspectMode,
            motionMode = motionMode,
            scanlinesMode = scanlinesMode,
            bootAnimMode = bootAnimMode,
            keyTone = keyTone,
            ambientSound = ambientSound,
            swapConfirm = swapConfirm,
            touchControls = touchControls,
            time24h = time24h,
            dateFormat = dateFormat,
            keepScreenOn = keepScreenOn,
        )
    }

    fun syncTouchModeOnGlThread() {
        val touchEnabled = shellPrefs.getBoolean("touch_controls", true)
        val touchActive = touchEnabled && !hasPhysicalGamepadConnected()
        val tutorialShown = shellPrefs.getBoolean("touch_tutorial_shown", false)
        val showGuide = touchActive && !tutorialShown
        if (showGuide) {
            shellPrefs.edit().putBoolean("touch_tutorial_shown", true).apply()
        }
        DcShellNative.nativeSetTouchMode(touchActive, showGuide)
    }

    fun saveShellNavigationState() {
        val state = DcShellNative.nativeGetState()
        if (state.size >= 7) {
            shellPrefs.edit()
                .putInt("screen", state[0])
                .putInt("main_index", state[1])
                .putInt("tab_index", state[2])
                .putInt("game_index", state[3])
                .putInt("file_tab", state[4])
                .putInt("vmu_card_index", state[5])
                .putInt("save_index", state[6])
                .apply()
        }
    }

    lateinit var surfaceRef: DcGlSurfaceView
    lateinit var rendererRef: DcShellSurfaceRenderer

    val surface = remember(context) {
        var downX = 0f
        var downY = 0f
        var multiTouchHandled = false
        var longPressTriggered = false
        var longPressRunnable: Runnable? = null

        val view = DcGlSurfaceView(context)
        surfaceRef = view
        rendererRef = DcShellSurfaceRenderer(
            context = context,
            onRenderModeNeeded = { continuous ->
                view.post {
                    view.renderMode = if (continuous) {
                        GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    } else {
                        GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    }
                }
            },
            onReady = {
                pushStringsToNative(context)
                pushConsoleTabsToNative(context)
                pushSettingsToNativeOnGlThread()
                pushSystemSnapshotToNative(DcShellSessionCache.lastSystemSnapshot)
                syncTouchModeOnGlThread()

                val gamesSnap = currentResolvedGames.value
                val savesSnap = currentSaves.value
                val cardsSnap = currentVmuCards.value
                DcShellNative.nativeSetLoading(
                    gamesLoading = currentLoadingGames.value && gamesSnap.isEmpty(),
                    savesLoading = !DcShellSessionCache.hasLoadedSaves && savesSnap.isEmpty(),
                    vmuLoading = !DcShellSessionCache.hasLoadedVmus && cardsSnap.isEmpty(),
                )
                pushGamesToNative(gamesSnap)
                pushSavesToNative(context, savesSnap)
                pushVmuCardsToNative(cardsSnap)
                val safeCardIdx = selectedVmuCardIdx.coerceIn(0, (cardsSnap.size - 1).coerceAtLeast(0))
                pushVmuFilesToNative(safeCardIdx, cardsSnap)

                DcShellNative.nativeSetState(
                    screen = shellPrefs.getInt("screen", 0),
                    mainIndex = shellPrefs.getInt("main_index", 0),
                    tabIndex = shellPrefs.getInt("tab_index", 0),
                    gameIndex = shellPrefs.getInt("game_index", 0),
                    fileTab = shellPrefs.getInt("file_tab", 0),
                    vmuCardIndex = safeCardIdx,
                    saveIndex = shellPrefs.getInt("save_index", 0),
                )
                if (DcShellSessionCache.returnedFromGame) {
                    DcShellSessionCache.returnedFromGame = false
                    DcShellNative.nativeTriggerTransition(1)
                } else {
                    DcShellNative.nativeTriggerTransition(0)
                }
            },
        ).also { it.motionMode = shellPrefs.getInt("motion_mode", 0) }

        view.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(rendererRef)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            requestFocus()

            setOnKeyListener { _, _, event -> DcShellHost.handleKeyEvent(event) }

            setOnTouchListener { v, event ->
                val touchEnabled = shellPrefs.getBoolean("touch_controls", true)
                if (!touchEnabled && event.pointerCount < 2) {
                    return@setOnTouchListener true
                }
                val w = v.width.coerceAtLeast(1).toFloat()
                val h = v.height.coerceAtLeast(1).toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        multiTouchHandled = false
                        longPressTriggered = false
                        val nx = event.x / w
                        val ny = event.y / h
                        longPressRunnable?.let(v::removeCallbacks)
                        val lp = Runnable {
                            if (!multiTouchHandled) {
                                longPressTriggered = true
                                queueEvent { DcShellNative.nativeTouchLongPress(nx, ny) }
                                requestRender()
                            }
                        }
                        longPressRunnable = lp
                        v.postDelayed(lp, 440L)
                        queueEvent { DcShellNative.nativeTouchDown(nx, ny) }
                        requestRender()
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount >= 2 && !multiTouchHandled) {
                            longPressRunnable?.let(v::removeCallbacks)
                            multiTouchHandled = true
                            queueEvent { DcShellNative.nativeTouchTwoFingerBack() }
                            requestRender()
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.x - downX) > 28f || abs(event.y - downY) > 28f) {
                            longPressRunnable?.let(v::removeCallbacks)
                        }
                        if (!multiTouchHandled && !longPressTriggered) {
                            val nx = event.x / w
                            val ny = event.y / h
                            queueEvent { DcShellNative.nativeTouchMove(nx, ny) }
                            requestRender()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let(v::removeCallbacks)
                        if (!multiTouchHandled && !longPressTriggered) {
                            val nx = event.x / w
                            val ny = event.y / h
                            queueEvent { DcShellNative.nativeTouchUp(nx, ny) }
                            requestRender()
                        } else {
                            queueEvent { DcShellNative.nativeTouchCancel() }
                            requestRender()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let(v::removeCallbacks)
                        queueEvent { DcShellNative.nativeTouchCancel() }
                        requestRender()
                        true
                    }
                    else -> true
                }
            }
        }
        view
    }

    fun dispatchGl(block: () -> Unit) {
        surface.queueEvent(block)
        surface.requestRender()
    }

    fun refreshSystemStatusAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            val snap = readSystemSnapshot(context)
            DcShellSessionCache.lastSystemSnapshot = snap
            dispatchGl { pushSystemSnapshotToNative(snap) }
        }
    }

    BackHandler {
        dispatchGl {
            DcShellNative.nativeKey(DcShellHost.KEY_BACK)
        }
    }

    val statusReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshSystemStatusAsync()
            }
        }
    }

    // 1-Hz clock tick only while in Static idle mode so the Dreamcast BIOS clock stays live at 0% idle GPU
    LaunchedEffect(currentMotionMode) {
        if (currentMotionMode == 3) {
            while (true) {
                delay(1000L)
                surface.requestRender()
            }
        }
    }

    LaunchedEffect(insetLeftPx, insetTopPx, insetRightPx, insetBottomPx) {
        dispatchGl {
            DcShellNative.nativeSetSafeInsets(insetLeftPx, insetTopPx, insetRightPx, insetBottomPx)
        }
    }

    DisposableEffect(surface, lifecycleOwner) {
        UiSoundManager.setNativeShellActive(true)
        DcShellAudio.resumeAudio()
        DcShellHost.setRequestRenderListener { surface.requestRender() }

        applyHostSettings(
            keyTone = shellPrefs.getBoolean("key_tone", true),
            ambientSound = shellPrefs.getBoolean("ambient_sound", false),
            swapConfirm = shellPrefs.getBoolean("swap_confirm", false),
            keepScreenOn = shellPrefs.getBoolean("keep_screen_on", true),
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        runCatching { context.registerReceiver(statusReceiver, filter) }

        DcShellHost.setGameLaunchListener { gamePath ->
            saveShellNavigationState()
            DcShellSessionCache.returnedFromGame = true
            DcShellAudio.pauseAudio()
            currentGameLaunch.value(gamePath)
        }

        DcShellHost.setSaveLaunchListener { gamePath, slot ->
            saveShellNavigationState()
            DcShellSessionCache.returnedFromGame = true
            DcShellAudio.pauseAudio()
            currentSaveLaunch.value(gamePath, slot)
        }

        DcShellHost.setDeleteSaveListener { index ->
            val entry = currentSaves.value.getOrNull(index)
            if (entry != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    saveRepo.deleteEntry(entry.info)
                    DcShellSessionCache.lastSaves = DcShellSessionCache.lastSaves.filterNot {
                        it.info.absolutePath == entry.info.absolutePath
                    }
                    withContext(Dispatchers.Main) {
                        savesRevision++
                    }
                }
            }
        }

        DcShellHost.setVmuActionListener { action, cardIndex, fileIndex ->
            when (action) {
                DcShellHost.VMU_ACTION_SELECT_CARD -> {
                    selectedVmuCardIdx = cardIndex
                    val cardsSnap = currentVmuCards.value
                    dispatchGl { pushVmuFilesToNative(cardIndex, cardsSnap) }
                }
                DcShellHost.VMU_ACTION_CREATE_DEFAULTS -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        vmuRepo.createDefaultVmus()
                        withContext(Dispatchers.Main) { vmuRevision++ }
                    }
                }
                DcShellHost.VMU_ACTION_DELETE_FILE -> {
                    val card = currentVmuCards.value.getOrNull(cardIndex) ?: return@setVmuActionListener
                    val item = card.files.getOrNull(fileIndex) ?: return@setVmuActionListener
                    coroutineScope.launch(Dispatchers.IO) {
                        VmuFilesystem.deleteFile(File(card.filePath), item)
                        withContext(Dispatchers.Main) { vmuRevision++ }
                    }
                }
                DcShellHost.VMU_ACTION_FORMAT_CARD -> {
                    val card = currentVmuCards.value.getOrNull(cardIndex) ?: return@setVmuActionListener
                    coroutineScope.launch(Dispatchers.IO) {
                        VmuFilesystem.formatCard(File(card.filePath))
                        withContext(Dispatchers.Main) { vmuRevision++ }
                    }
                }
            }
        }

        DcShellHost.setOpenGameSettingsListener { index ->
            val resolved = currentResolvedGames.value.getOrNull(index)
            val game = resolved?.let { rg -> currentGames.value.firstOrNull { it.path == rg.path } }
                ?: currentGames.value.getOrNull(index)
            if (game != null) {
                saveShellNavigationState()
                DcShellSessionCache.returnedFromGame = true
                DcShellAudio.pauseAudio()
                currentManageGame.value(game)
            }
        }

        DcShellHost.setSettingsListener { arr ->
            if (arr.size >= 12) {
                val motionMode = arr[2]
                val keyTone = arr[5] != 0
                val ambientSound = arr[6] != 0
                val swapConfirm = arr[7] != 0
                val touchControls = arr[8] != 0
                val time24h = arr[9] != 0
                val keepScreenOn = arr[11] != 0
                shellPrefs.edit()
                    .putInt("theme", arr[0])
                    .putInt("aspect_mode", arr[1])
                    .putInt("motion_mode", motionMode)
                    .putInt("scanlines_mode", arr[3])
                    .putInt("boot_anim_mode", arr[4])
                    .putBoolean("key_tone", keyTone)
                    .putBoolean("ambient_sound", ambientSound)
                    .putBoolean("swap_confirm", swapConfirm)
                    .putBoolean("touch_controls", touchControls)
                    .putBoolean("time_24h", time24h)
                    .putInt("date_format", arr[10])
                    .putBoolean("keep_screen_on", keepScreenOn)
                    .apply()
                currentMotionMode = motionMode
                rendererRef.motionMode = motionMode
                applyHostSettings(keyTone, ambientSound, swapConfirm, keepScreenOn)
                dispatchGl { syncTouchModeOnGlThread() }
            }
        }

        DcShellHost.setExitListener {
            saveShellNavigationState()
            DcShellAudio.pauseAudio()
            UiSoundManager.setNativeShellActive(false)
            currentExit.value()
        }

        DcShellHost.setKeyListener { code ->
            dispatchGl {
                DcShellNative.nativeKey(code)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    surface.onResume()
                    surface.requestFocus()
                    DcShellAudio.resumeAudio()
                    savesRevision++
                    vmuRevision++
                    refreshSystemStatusAsync()
                    dispatchGl {
                        syncTouchModeOnGlThread()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    saveShellNavigationState()
                    DcShellAudio.pauseAudio()
                    surface.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            saveShellNavigationState()
            DcImageLoader.cancelPending()
            DcShellAudio.pauseAudio()
            UiSoundManager.setNativeShellActive(false)
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { context.unregisterReceiver(statusReceiver) }
            lifecycleOwner.lifecycle.removeObserver(observer)
            DcShellHost.setGameLaunchListener(null)
            DcShellHost.setSaveLaunchListener(null)
            DcShellHost.setDeleteSaveListener(null)
            DcShellHost.setVmuActionListener(null)
            DcShellHost.setOpenGameSettingsListener(null)
            DcShellHost.setSettingsListener(null)
            DcShellHost.setExitListener(null)
            DcShellHost.setKeyListener(null)
            DcShellHost.setRequestRenderListener(null)
        }
    }

    LaunchedEffect(localeTag) {
        val saves = currentSaves.value
        refreshSystemStatusAsync()
        dispatchGl {
            pushStringsToNative(context)
            pushConsoleTabsToNative(context)
            pushSavesToNative(context, saves)
        }
    }

    LaunchedEffect(isLoadingGames, resolvedGames) {
        val snap = resolvedGames
        val loading = isLoadingGames && snap.isEmpty()
        dispatchGl {
            DcShellNative.nativeSetLoading(
                gamesLoading = loading,
                savesLoading = !DcShellSessionCache.hasLoadedSaves && currentSaves.value.isEmpty(),
                vmuLoading = !DcShellSessionCache.hasLoadedVmus && currentVmuCards.value.isEmpty(),
            )
            pushGamesToNative(snap)
        }
    }

    LaunchedEffect(saveStates) {
        val saves = saveStates
        dispatchGl {
            DcShellNative.nativeSetLoading(
                gamesLoading = currentLoadingGames.value && currentResolvedGames.value.isEmpty(),
                savesLoading = !DcShellSessionCache.hasLoadedSaves && saves.isEmpty(),
                vmuLoading = !DcShellSessionCache.hasLoadedVmus && currentVmuCards.value.isEmpty(),
            )
            pushSavesToNative(context, saves)
        }
    }

    LaunchedEffect(vmuCards) {
        val cards = vmuCards
        val safeIdx = selectedVmuCardIdx.coerceIn(0, (cards.size - 1).coerceAtLeast(0))
        dispatchGl {
            DcShellNative.nativeSetLoading(
                gamesLoading = currentLoadingGames.value && currentResolvedGames.value.isEmpty(),
                savesLoading = !DcShellSessionCache.hasLoadedSaves && currentSaves.value.isEmpty(),
                vmuLoading = !DcShellSessionCache.hasLoadedVmus && cards.isEmpty(),
            )
            pushVmuCardsToNative(cards)
            pushVmuFilesToNative(safeIdx, cards)
        }
    }

    AndroidView(
        factory = { surface },
        update = { view ->
            if (view.width > 0 && view.height > 0) {
                view.holder.setSizeFromLayout()
            }
        },
        modifier = modifier,
    )
}
