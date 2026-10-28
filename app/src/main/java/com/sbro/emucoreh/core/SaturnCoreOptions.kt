package com.sbro.emucoreh.core

import android.content.Context
import com.sbro.emucoreh.R

/** Values mirror the bundled Beetle Saturn (Mednafen) option table. */
object SaturnCoreOptions {
    private const val CART_KEY = "beetle_saturn_cart"
    private const val REGION_KEY = "beetle_saturn_region"
    private const val LANGUAGE_KEY = "beetle_saturn_autortc_lang"
    private const val CD_CACHE_KEY = "beetle_saturn_cdimagecache"
    private const val MIDSYNC_KEY = "beetle_saturn_midsync"
    private const val LEGACY_CART_KEY = "yabasanshiro_addon_cart"
    private const val LEGACY_LANGUAGE_KEY = "yabasanshiro_system_language"

    private fun choice(value: String, label: Int) = FlycastCoreOptions.Choice(value, label)
    private val definitions = listOf(
        FlycastCoreOptions.Option(CART_KEY, R.string.saturn_addon_cart, R.string.saturn_cart_help, "system",
            listOf(choice("Auto Detect", R.string.settings_arcade_device_auto),
                choice("Extended RAM (1MB)", R.string.saturn_cart_1m),
                choice("Extended RAM (4MB)", R.string.saturn_cart_4m),
                choice("Backup Memory", R.string.saturn_cart_backup),
                choice("None", R.string.touch_control_creator_none)), "Auto Detect"),
        FlycastCoreOptions.Option(REGION_KEY, R.string.sega_region, R.string.sega_region_help, "system",
            listOf(choice("Auto Detect", R.string.settings_arcade_device_auto),
                choice("Japan", R.string.flycast_opt_region_choice_japan),
                choice("North America", R.string.saturn_region_north_america),
                choice("Europe", R.string.flycast_opt_region_choice_europe),
                choice("South Korea", R.string.saturn_region_south_korea),
                choice("Asia (NTSC)", R.string.saturn_region_asia_ntsc),
                choice("Asia (PAL)", R.string.saturn_region_asia_pal),
                choice("Brazil", R.string.saturn_region_brazil),
                choice("Latin America", R.string.saturn_region_latin_america)), "Auto Detect"),
        FlycastCoreOptions.Option(LANGUAGE_KEY, R.string.flycast_opt_language_label, R.string.saturn_language_help, "system",
            listOf(choice("english", R.string.settings_language_english), choice("german", R.string.settings_language_german),
                choice("french", R.string.settings_language_french), choice("spanish", R.string.settings_language_spanish),
                choice("italian", R.string.settings_language_italian), choice("japanese", R.string.settings_language_japanese)), "english"),
        FlycastCoreOptions.Option(CD_CACHE_KEY, R.string.saturn_cd_image_cache, R.string.saturn_cd_image_cache_desc, "video",
            listOf(choice("disabled", R.string.flycast_choice_disabled),
                choice("enabled", R.string.flycast_choice_enabled)), "disabled"),
        FlycastCoreOptions.Option(MIDSYNC_KEY, R.string.saturn_midsync, R.string.saturn_midsync_desc, "system",
            listOf(choice("disabled", R.string.flycast_choice_disabled),
                choice("enabled", R.string.flycast_choice_enabled)), "disabled")
    )

    fun options(platform: EmulationPlatform = EmulationPlatform.SATURN, liveOnly: Boolean = false): List<FlycastCoreOptions.Option> =
        if (!platform.isSaturn) emptyList() else definitions.filter { !liveOnly || !requiresRestart(it.key) }

    fun requiresRestart(key: String): Boolean = key != MIDSYNC_KEY && definitions.any { it.key == key }
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("saturn_core_options", Context.MODE_PRIVATE)

    /** Reads the new Beetle key, migrating the two equivalent YabaSanshiro settings once. */
    fun value(context: Context, key: String, platform: EmulationPlatform = EmulationPlatform.SATURN): String? {
        if (!platform.isSaturn) return null
        val preferences = preferences(context)
        val raw = preferences.getString(key, null) ?: when (key) {
            CART_KEY -> preferences.getString(LEGACY_CART_KEY, null)?.let {
                when (it) {
                    "4M_extended_ram" -> "Extended RAM (4MB)"
                    "1M_extended_ram" -> "Extended RAM (1MB)"
                    else -> null
                }
            }
            LANGUAGE_KEY -> preferences.getString(LEGACY_LANGUAGE_KEY, null)?.let {
                if (it == "deutsch") "german" else it
            }
            else -> null
        }
        return raw?.takeIf { candidate -> definitions.firstOrNull { it.key == key }?.choices?.any { it.value == candidate } == true }
    }

    fun persistedEntries(context: Context, platform: EmulationPlatform = EmulationPlatform.SATURN): Map<String, String> =
        options(platform).mapNotNull { option -> value(context, option.key, platform)?.let { option.key to it } }.toMap()

    fun set(context: Context, key: String, value: String, platform: EmulationPlatform = EmulationPlatform.SATURN) {
        val option = options(platform).firstOrNull { it.key == key } ?: return
        if (option.choices.none { it.value == value }) return
        preferences(context).edit().putString(key, value).apply()
    }

    fun resetAll(context: Context) { check(preferences(context).edit().clear().commit()) }
}
