package com.sbro.emucoreh.core

import android.content.Context
import com.sbro.emucoreh.R

/** Values mirror the bundled YabaSanshiro option table; profiles stay separate from jgenesis. */
object SaturnCoreOptions {
    private fun choice(value: String, label: Int) = FlycastCoreOptions.Choice(value, label)
    private val definitions = listOf(
        FlycastCoreOptions.Option("yabasanshiro_sh2coretype", R.string.saturn_sh2_core, R.string.sega_restart_required, "system",
            listOf(choice("dynarec", R.string.saturn_dynarec), choice("interpreter", R.string.saturn_interpreter)), "dynarec"),
        FlycastCoreOptions.Option("yabasanshiro_frameskip", R.string.saturn_frameskip, R.string.saturn_frameskip_help, "video",
            listOf(choice("disabled", R.string.flycast_choice_disabled), choice("enabled", R.string.flycast_choice_enabled)), "disabled"),
        FlycastCoreOptions.Option("yabasanshiro_addon_cart", R.string.saturn_addon_cart, R.string.sega_restart_required, "system",
            listOf(choice("4M_extended_ram", R.string.saturn_cart_4m), choice("1M_extended_ram", R.string.saturn_cart_1m)), "4M_extended_ram"),
        FlycastCoreOptions.Option("yabasanshiro_system_language", R.string.flycast_opt_language_label, R.string.sega_restart_required, "system",
            listOf(choice("english", R.string.settings_language_english), choice("deutsch", R.string.settings_language_german),
                choice("french", R.string.settings_language_french), choice("spanish", R.string.settings_language_spanish),
                choice("italian", R.string.settings_language_italian), choice("japanese", R.string.settings_language_japanese)), "english"),
        FlycastCoreOptions.Option("yabasanshiro_resolution_mode", R.string.settings_upscale, R.string.sega_restart_required, "video",
            listOf(choice("original", R.string.settings_upscale_native), choice("2x", R.string.flycast_opt_texupscale_choice_v2),
                choice("4x", R.string.flycast_opt_texupscale_choice_v4)), "original")
    )

    fun options(platform: EmulationPlatform = EmulationPlatform.SATURN, liveOnly: Boolean = false): List<FlycastCoreOptions.Option> =
        if (!platform.isSaturn) emptyList() else definitions.filter { !liveOnly || !requiresRestart(it.key) }

    fun requiresRestart(key: String): Boolean = key != "yabasanshiro_frameskip" && definitions.any { it.key == key }
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("saturn_core_options", Context.MODE_PRIVATE)
    fun value(context: Context, key: String, platform: EmulationPlatform = EmulationPlatform.SATURN): String? =
        if (!platform.isSaturn) null else preferences(context).getString(key, null)

    fun persistedEntries(context: Context, platform: EmulationPlatform = EmulationPlatform.SATURN): Map<String, String> =
        options(platform).mapNotNull { option -> value(context, option.key, platform)?.let { option.key to it } }.toMap()

    fun set(context: Context, key: String, value: String, platform: EmulationPlatform = EmulationPlatform.SATURN) {
        val option = options(platform).firstOrNull { it.key == key } ?: return
        if (option.choices.none { it.value == value }) return
        preferences(context).edit().putString(key, value).apply()
    }

    fun resetAll(context: Context) { check(preferences(context).edit().clear().commit()) }
}
