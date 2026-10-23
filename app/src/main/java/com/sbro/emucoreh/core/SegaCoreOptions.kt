package com.sbro.emucoreh.core

import android.content.Context
import com.sbro.emucoreh.R

/** Only options actually implemented by the jgenesis libretro shim. */
object SegaCoreOptions {
    private fun toggle(key: String, label: Int, default: String = "disabled") = FlycastCoreOptions.Option(
        key, label, label, if (key in setOf("jgenesis_genesis_lpf", "jgenesis_sms_fm")) "audio" else "video", listOf(
            FlycastCoreOptions.Choice("enabled", R.string.flycast_choice_enabled),
            FlycastCoreOptions.Choice("disabled", R.string.flycast_choice_disabled)
        ), default
    )
    private val region = FlycastCoreOptions.Option("jgenesis_region", R.string.sega_region,
        R.string.sega_restart_required, "system", listOf(
            FlycastCoreOptions.Choice("auto", R.string.settings_aspect_ratio_auto),
            FlycastCoreOptions.Choice("americas", R.string.flycast_opt_region_choice_usa),
            FlycastCoreOptions.Choice("europe", R.string.flycast_opt_region_choice_europe),
            FlycastCoreOptions.Choice("japan", R.string.flycast_opt_region_choice_japan)
        ), "auto")
    private val timing = FlycastCoreOptions.Option("jgenesis_timing", R.string.sega_timing,
        R.string.sega_restart_required, "system", listOf(
            FlycastCoreOptions.Choice("auto", R.string.settings_aspect_ratio_auto),
            FlycastCoreOptions.Choice("ntsc", R.string.sega_ntsc),
            FlycastCoreOptions.Choice("pal", R.string.sega_pal)
        ), "auto")

    fun options(platform: EmulationPlatform, liveOnly: Boolean = false): List<FlycastCoreOptions.Option> {
        if (!platform.isSega) return emptyList()
        return buildList {
            if (!liveOnly) {
                if (platform != EmulationPlatform.SG_1000) add(region)
                if (platform != EmulationPlatform.GAME_GEAR) add(timing)
            }
            if (platform != EmulationPlatform.SG_1000) add(toggle("jgenesis_remove_sprite_limit", R.string.sega_remove_sprite_limit))
            if (platform in setOf(EmulationPlatform.GENESIS, EmulationPlatform.SEGA_CD, EmulationPlatform.SEGA_32X))
                add(toggle("jgenesis_genesis_lpf", R.string.sega_low_pass_filter, "enabled"))
            if (platform == EmulationPlatform.MASTER_SYSTEM) {
                add(toggle("jgenesis_sms_fm", R.string.sega_fm_audio, "enabled"))
                add(toggle("jgenesis_sms_crop_vertical_border", R.string.sega_crop_border, "enabled"))
            }
            if (platform == EmulationPlatform.GAME_GEAR)
                add(toggle("jgenesis_gg_sms_resolution", R.string.sega_gg_resolution))
        }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("jgenesis_core_options", Context.MODE_PRIVATE)

    // Preserve historical unscoped overrides in the default Genesis profile only; keep other systems independent.
    fun value(context: Context, key: String, platform: EmulationPlatform = CoreRuntime.platform): String? {
        if (!platform.isSega) return null
        return preferences(context).getString(segaOptionStorageKey(platform, key), null)
            ?: if (platform == EmulationPlatform.GENESIS) preferences(context).getString(key, null) else null
    }

    fun persistedEntries(context: Context, platform: EmulationPlatform = CoreRuntime.platform): Map<String, String> =
        options(platform).mapNotNull { option -> value(context, option.key, platform)?.let { option.key to it } }.toMap()

    fun resetAll(context: Context) { check(preferences(context).edit().clear().commit()) }

    fun set(context: Context, key: String, value: String, platform: EmulationPlatform = CoreRuntime.platform) {
        val option = options(platform).firstOrNull { it.key == key } ?: return
        if (option.choices.none { it.value == value }) return
        preferences(context).edit().putString(segaOptionStorageKey(platform, key), value).apply()
    }
}

internal fun segaOptionStorageKey(platform: EmulationPlatform, key: String): String = "${platform.name}:$key"
