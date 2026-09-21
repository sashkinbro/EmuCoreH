// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
//
// Generated from core/shell/libretro/libretro_core_options.h (Flycast
// libretro core). Regenerate with: python tools/gen_flycast_options.py
//
// Every user-visible string is a string resource (see res/values*/
// flycast_options.xml); this model only carries stable keys, persisted
// values and @StringRes references.
package com.sbro.emucoreh.core

import androidx.annotation.StringRes
import com.sbro.emucoreh.R

@Suppress("MagicNumber")
object FlycastCoreOptions {
    data class Choice(val value: String, @StringRes val labelRes: Int)

    data class Option(
        val key: String,
        @StringRes val labelRes: Int,
        @StringRes val descriptionRes: Int,
        val category: String,
        val choices: List<Choice>,
        val defaultValue: String,
    ) {
        /** The option's short key without the `reicast_` prefix. */
        val shortKey: String get() = key.removePrefix("reicast_")

        /** True for plain on/off options whose only values are enabled/disabled. */
        val isBooleanToggle: Boolean
            get() = choices.map { it.value.lowercase() }.toSet() == setOf("enabled", "disabled")

        private val enabledValue: String?
            get() = choices.firstOrNull { it.value.equals("enabled", ignoreCase = true) }?.value

        private val disabledValue: String?
            get() = choices.firstOrNull { it.value.equals("disabled", ignoreCase = true) }?.value

        /** Reads a persisted value with the enabled/disabled and true/false conventions. */
        fun isEnabled(current: String?): Boolean = current != null &&
            (current.equals(enabledValue, ignoreCase = true) || current.equals("true", ignoreCase = true))

        /** Value to persist for a toggle interaction. */
        fun valueForEnabled(enabled: Boolean): String = if (enabled) {
            enabledValue ?: "true"
        } else {
            disabledValue ?: "false"
        }
    }

    data class Category(
        val key: String,
        @StringRes val labelRes: Int,
        @StringRes val descriptionRes: Int,
    )

    private val categoryList: List<Category> = listOf(
        Category(
            key = "system",
            labelRes = R.string.flycast_cat_system_label,
            descriptionRes = R.string.flycast_cat_system_info,
        ),
        Category(
            key = "video",
            labelRes = R.string.flycast_cat_video_label,
            descriptionRes = R.string.flycast_cat_video_info,
        ),
        Category(
            key = "performance",
            labelRes = R.string.flycast_cat_performance_label,
            descriptionRes = R.string.flycast_cat_performance_info,
        ),
        Category(
            key = "hacks",
            labelRes = R.string.flycast_cat_hacks_label,
            descriptionRes = R.string.flycast_cat_hacks_info,
        ),
        Category(
            key = "input",
            labelRes = R.string.flycast_cat_input_label,
            descriptionRes = R.string.flycast_cat_input_info,
        ),
        Category(
            key = "expansions",
            labelRes = R.string.flycast_cat_expansions_label,
            descriptionRes = R.string.flycast_cat_expansions_info,
        ),
        Category(
            key = "vmu",
            labelRes = R.string.flycast_cat_vmu_label,
            descriptionRes = R.string.flycast_cat_vmu_info,
        ),
    )

    private val optionList: List<Option> = listOf(
        // system
        Option(
            key = "reicast_region",
            labelRes = R.string.flycast_opt_region_label,
            descriptionRes = R.string.flycast_opt_region_info,
            category = "system",
            choices = listOf(
                Choice("Japan", R.string.flycast_opt_region_choice_japan),
                Choice("USA", R.string.flycast_opt_region_choice_usa),
                Choice("Europe", R.string.flycast_opt_region_choice_europe),
                Choice("Default", R.string.flycast_choice_default),
            ),
            defaultValue = "USA",
        ),
        // system
        Option(
            key = "reicast_language",
            labelRes = R.string.flycast_opt_language_label,
            descriptionRes = R.string.flycast_opt_language_info,
            category = "system",
            choices = listOf(
                Choice("Japanese", R.string.flycast_opt_language_choice_japanese),
                Choice("English", R.string.flycast_opt_language_choice_english),
                Choice("German", R.string.flycast_opt_language_choice_german),
                Choice("French", R.string.flycast_opt_language_choice_french),
                Choice("Spanish", R.string.flycast_opt_language_choice_spanish),
                Choice("Italian", R.string.flycast_opt_language_choice_italian),
                Choice("Default", R.string.flycast_choice_default),
            ),
            defaultValue = "English",
        ),
        // system
        Option(
            key = "reicast_hle_bios",
            labelRes = R.string.flycast_opt_hle_bios_label,
            descriptionRes = R.string.flycast_opt_hle_bios_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // system
        Option(
            key = "reicast_enable_dsp",
            labelRes = R.string.flycast_opt_enable_dsp_label,
            descriptionRes = R.string.flycast_opt_enable_dsp_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // system
        Option(
            key = "reicast_allow_service_buttons",
            labelRes = R.string.flycast_opt_allow_service_buttons_label,
            descriptionRes = R.string.flycast_opt_allow_service_buttons_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // system
        Option(
            key = "reicast_force_freeplay",
            labelRes = R.string.flycast_opt_force_freeplay_label,
            descriptionRes = R.string.flycast_opt_force_freeplay_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // system
        Option(
            key = "reicast_coin_limit",
            labelRes = R.string.flycast_opt_coin_limit_label,
            descriptionRes = R.string.flycast_opt_coin_limit_info,
            category = "system",
            choices = listOf(
                Choice("0", R.string.flycast_choice_disabled),
                Choice("1", R.string.flycast_opt_coin_limit_choice_v1),
                Choice("2", R.string.flycast_opt_coin_limit_choice_v2),
                Choice("3", R.string.flycast_opt_coin_limit_choice_v3),
                Choice("4", R.string.flycast_opt_coin_limit_choice_v4),
                Choice("5", R.string.flycast_opt_coin_limit_choice_v5),
                Choice("6", R.string.flycast_opt_coin_limit_choice_v6),
                Choice("7", R.string.flycast_opt_coin_limit_choice_v7),
                Choice("8", R.string.flycast_opt_coin_limit_choice_v8),
                Choice("9", R.string.flycast_opt_coin_limit_choice_v9),
                Choice("10", R.string.flycast_opt_coin_limit_choice_v10),
                Choice("11", R.string.flycast_opt_coin_limit_choice_v11),
                Choice("12", R.string.flycast_opt_coin_limit_choice_v12),
                Choice("13", R.string.flycast_opt_coin_limit_choice_v13),
                Choice("14", R.string.flycast_opt_coin_limit_choice_v14),
                Choice("15", R.string.flycast_opt_coin_limit_choice_v15),
                Choice("16", R.string.flycast_opt_coin_limit_choice_v16),
                Choice("17", R.string.flycast_opt_coin_limit_choice_v17),
                Choice("18", R.string.flycast_opt_coin_limit_choice_v18),
                Choice("19", R.string.flycast_opt_coin_limit_choice_v19),
                Choice("20", R.string.flycast_opt_coin_limit_choice_v20),
            ),
            defaultValue = "0",
        ),
        // system
        Option(
            key = "reicast_emulate_bba",
            labelRes = R.string.flycast_opt_emulate_bba_label,
            descriptionRes = R.string.flycast_opt_emulate_bba_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // system
        Option(
            key = "reicast_upnp",
            labelRes = R.string.flycast_opt_upnp_label,
            descriptionRes = R.string.flycast_opt_upnp_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // system
        Option(
            key = "reicast_dcnet",
            labelRes = R.string.flycast_opt_dcnet_label,
            descriptionRes = R.string.flycast_opt_dcnet_info,
            category = "system",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // video
        Option(
            key = "reicast_internal_resolution",
            labelRes = R.string.flycast_opt_internal_resolution_label,
            descriptionRes = R.string.flycast_opt_internal_resolution_info,
            category = "video",
            choices = listOf(
                Choice("320x240", R.string.flycast_opt_internal_resolution_choice_v320x240),
                Choice("640x480", R.string.flycast_opt_internal_resolution_choice_v640x480),
                Choice("800x600", R.string.flycast_opt_internal_resolution_choice_v800x600),
                Choice("960x720", R.string.flycast_opt_internal_resolution_choice_v960x720),
                Choice("1024x768", R.string.flycast_opt_internal_resolution_choice_v1024x768),
                Choice("1280x960", R.string.flycast_opt_internal_resolution_choice_v1280x960),
                Choice("1440x1080", R.string.flycast_opt_internal_resolution_choice_v1440x1080),
                Choice("1600x1200", R.string.flycast_opt_internal_resolution_choice_v1600x1200),
                Choice("1920x1440", R.string.flycast_opt_internal_resolution_choice_v1920x1440),
                Choice("2560x1920", R.string.flycast_opt_internal_resolution_choice_v2560x1920),
                Choice("2880x2160", R.string.flycast_opt_internal_resolution_choice_v2880x2160),
                Choice("3200x2400", R.string.flycast_opt_internal_resolution_choice_v3200x2400),
                Choice("3840x2880", R.string.flycast_opt_internal_resolution_choice_v3840x2880),
                Choice("4480x3360", R.string.flycast_opt_internal_resolution_choice_v4480x3360),
                Choice("5120x3840", R.string.flycast_opt_internal_resolution_choice_v5120x3840),
                Choice("5760x4320", R.string.flycast_opt_internal_resolution_choice_v5760x4320),
                Choice("6400x4800", R.string.flycast_opt_internal_resolution_choice_v6400x4800),
                Choice("7040x5280", R.string.flycast_opt_internal_resolution_choice_v7040x5280),
                Choice("7680x5760", R.string.flycast_opt_internal_resolution_choice_v7680x5760),
                Choice("8320x6240", R.string.flycast_opt_internal_resolution_choice_v8320x6240),
                Choice("8960x6720", R.string.flycast_opt_internal_resolution_choice_v8960x6720),
                Choice("9600x7200", R.string.flycast_opt_internal_resolution_choice_v9600x7200),
                Choice("10240x7680", R.string.flycast_opt_internal_resolution_choice_v10240x7680),
                Choice("10880x8160", R.string.flycast_opt_internal_resolution_choice_v10880x8160),
                Choice("11520x8640", R.string.flycast_opt_internal_resolution_choice_v11520x8640),
                Choice("12160x9120", R.string.flycast_opt_internal_resolution_choice_v12160x9120),
                Choice("12800x9600", R.string.flycast_opt_internal_resolution_choice_v12800x9600),
            ),
            defaultValue = "640x480",
        ),
        // video
        Option(
            key = "reicast_cable_type",
            labelRes = R.string.flycast_opt_cable_type_label,
            descriptionRes = R.string.flycast_opt_cable_type_info,
            category = "video",
            choices = listOf(
                Choice("VGA", R.string.flycast_opt_cable_type_choice_vga),
                Choice("TV (RGB)", R.string.flycast_opt_cable_type_choice_tv_rgb),
                Choice("TV (Composite)", R.string.flycast_opt_cable_type_choice_tv_composite),
            ),
            defaultValue = "TV (Composite)",
        ),
        // video
        Option(
            key = "reicast_broadcast",
            labelRes = R.string.flycast_opt_broadcast_label,
            descriptionRes = R.string.flycast_opt_broadcast_info,
            category = "video",
            choices = listOf(
                Choice("NTSC", R.string.flycast_opt_broadcast_choice_ntsc),
                Choice("PAL", R.string.flycast_opt_broadcast_choice_pal),
                Choice("PAL_N", R.string.flycast_opt_broadcast_choice_pal_n),
                Choice("PAL_M", R.string.flycast_opt_broadcast_choice_pal_m),
                Choice("Default", R.string.flycast_choice_default),
            ),
            defaultValue = "NTSC",
        ),
        // video
        Option(
            key = "reicast_screen_rotation",
            labelRes = R.string.flycast_opt_screen_rotation_label,
            descriptionRes = R.string.flycast_opt_screen_rotation_info,
            category = "video",
            choices = listOf(
                Choice("horizontal", R.string.flycast_choice_horizontal),
                Choice("vertical", R.string.flycast_choice_vertical),
            ),
            defaultValue = "horizontal",
        ),
        // video
        Option(
            key = "reicast_alpha_sorting",
            labelRes = R.string.flycast_opt_alpha_sorting_label,
            descriptionRes = R.string.flycast_opt_alpha_sorting_info,
            category = "video",
            choices = listOf(
                Choice("per-strip (fast, least accurate)", R.string.flycast_opt_alpha_sorting_choice_per_strip_fast_least_accurate),
                Choice("per-triangle (normal)", R.string.flycast_opt_alpha_sorting_choice_per_triangle_normal),
                Choice("per-pixel (accurate)", R.string.flycast_opt_alpha_sorting_choice_per_pixel_accurate),
            ),
            defaultValue = "per-triangle (normal)",
        ),
        // video
        Option(
            key = "reicast_oit_abuffer_size",
            labelRes = R.string.flycast_opt_oit_abuffer_size_label,
            descriptionRes = R.string.flycast_opt_oit_abuffer_size_info,
            category = "video",
            choices = listOf(
                Choice("512MB", R.string.flycast_opt_oit_abuffer_size_choice_v512mb),
                Choice("1GB", R.string.flycast_opt_oit_abuffer_size_choice_v1gb),
                Choice("2GB", R.string.flycast_opt_oit_abuffer_size_choice_v2gb),
                Choice("4GB", R.string.flycast_opt_oit_abuffer_size_choice_v4gb),
            ),
            defaultValue = "512MB",
        ),
        // video
        Option(
            key = "reicast_oit_layers",
            labelRes = R.string.flycast_opt_oit_layers_label,
            descriptionRes = R.string.flycast_opt_oit_layers_info,
            category = "video",
            choices = listOf(
                Choice("8", R.string.flycast_opt_oit_layers_choice_v8),
                Choice("16", R.string.flycast_opt_oit_layers_choice_v16),
                Choice("32", R.string.flycast_opt_oit_layers_choice_v32),
                Choice("64", R.string.flycast_opt_oit_layers_choice_v64),
                Choice("96", R.string.flycast_opt_oit_layers_choice_v96),
                Choice("128", R.string.flycast_opt_oit_layers_choice_v128),
            ),
            defaultValue = "32",
        ),
        // video
        Option(
            key = "reicast_emulate_framebuffer",
            labelRes = R.string.flycast_opt_emulate_framebuffer_label,
            descriptionRes = R.string.flycast_opt_emulate_framebuffer_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // video
        Option(
            key = "reicast_enable_rttb",
            labelRes = R.string.flycast_opt_enable_rttb_label,
            descriptionRes = R.string.flycast_opt_enable_rttb_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // video
        Option(
            key = "reicast_mipmapping",
            labelRes = R.string.flycast_opt_mipmapping_label,
            descriptionRes = R.string.flycast_opt_mipmapping_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // video
        Option(
            key = "reicast_fog",
            labelRes = R.string.flycast_opt_fog_label,
            descriptionRes = R.string.flycast_opt_fog_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // video
        Option(
            key = "reicast_volume_modifier_enable",
            labelRes = R.string.flycast_opt_volume_modifier_enable_label,
            descriptionRes = R.string.flycast_opt_volume_modifier_enable_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // video
        Option(
            key = "reicast_anisotropic_filtering",
            labelRes = R.string.flycast_opt_anisotropic_filtering_label,
            descriptionRes = R.string.flycast_opt_anisotropic_filtering_info,
            category = "video",
            choices = listOf(
                Choice("off", R.string.flycast_choice_disabled),
                Choice("2", R.string.flycast_opt_anisotropic_filtering_choice_v2),
                Choice("4", R.string.flycast_opt_anisotropic_filtering_choice_v4),
                Choice("8", R.string.flycast_opt_anisotropic_filtering_choice_v8),
                Choice("16", R.string.flycast_opt_anisotropic_filtering_choice_v16),
            ),
            defaultValue = "4",
        ),
        // video
        Option(
            key = "reicast_texture_filtering",
            labelRes = R.string.flycast_opt_texture_filtering_label,
            descriptionRes = R.string.flycast_opt_texture_filtering_info,
            category = "video",
            choices = listOf(
                Choice("0", R.string.flycast_choice_default),
                Choice("1", R.string.flycast_opt_texture_filtering_choice_v1),
                Choice("2", R.string.flycast_opt_texture_filtering_choice_v2),
            ),
            defaultValue = "0",
        ),
        // video
        Option(
            key = "reicast_delay_frame_swapping",
            labelRes = R.string.flycast_opt_delay_frame_swapping_label,
            descriptionRes = R.string.flycast_opt_delay_frame_swapping_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // video
        Option(
            key = "reicast_detect_vsync_swap_interval",
            labelRes = R.string.flycast_opt_detect_vsync_swap_interval_label,
            descriptionRes = R.string.flycast_opt_detect_vsync_swap_interval_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // video
        Option(
            key = "reicast_pvr2_filtering",
            labelRes = R.string.flycast_opt_pvr2_filtering_label,
            descriptionRes = R.string.flycast_opt_pvr2_filtering_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // video
        Option(
            key = "reicast_texupscale",
            labelRes = R.string.flycast_opt_texupscale_label,
            descriptionRes = R.string.flycast_opt_texupscale_info,
            category = "video",
            choices = listOf(
                Choice("1", R.string.flycast_choice_disabled),
                Choice("2", R.string.flycast_opt_texupscale_choice_v2),
                Choice("4", R.string.flycast_opt_texupscale_choice_v4),
                Choice("6", R.string.flycast_opt_texupscale_choice_v6),
            ),
            defaultValue = "1",
        ),
        // video
        Option(
            key = "reicast_texupscale_max_filtered_texture_size",
            labelRes = R.string.flycast_opt_texupscale_max_filtered_texture_size_label,
            descriptionRes = R.string.flycast_opt_texupscale_max_filtered_texture_size_info,
            category = "video",
            choices = listOf(
                Choice("256", R.string.flycast_opt_texupscale_max_filtered_texture_size_choice_v256),
                Choice("512", R.string.flycast_opt_texupscale_max_filtered_texture_size_choice_v512),
                Choice("1024", R.string.flycast_opt_texupscale_max_filtered_texture_size_choice_v1024),
            ),
            defaultValue = "256",
        ),
        // video
        Option(
            key = "reicast_native_depth_interpolation",
            labelRes = R.string.flycast_opt_native_depth_interpolation_label,
            descriptionRes = R.string.flycast_opt_native_depth_interpolation_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // video
        Option(
            key = "reicast_fix_upscale_bleeding_edge",
            labelRes = R.string.flycast_opt_fix_upscale_bleeding_edge_label,
            descriptionRes = R.string.flycast_opt_fix_upscale_bleeding_edge_info,
            category = "video",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // performance
        Option(
            key = "reicast_threaded_rendering",
            labelRes = R.string.flycast_opt_threaded_rendering_label,
            descriptionRes = R.string.flycast_opt_threaded_rendering_info,
            category = "performance",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "enabled",
        ),
        // performance
        Option(
            key = "reicast_auto_skip_frame",
            labelRes = R.string.flycast_opt_auto_skip_frame_label,
            descriptionRes = R.string.flycast_opt_auto_skip_frame_info,
            category = "performance",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("some", R.string.flycast_choice_normal),
                Choice("more", R.string.flycast_choice_maximum),
            ),
            defaultValue = "disabled",
        ),
        // performance
        Option(
            key = "reicast_frame_skipping",
            labelRes = R.string.flycast_opt_frame_skipping_label,
            descriptionRes = R.string.flycast_opt_frame_skipping_info,
            category = "performance",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("1", R.string.flycast_opt_frame_skipping_choice_v1),
                Choice("2", R.string.flycast_opt_frame_skipping_choice_v2),
                Choice("3", R.string.flycast_opt_frame_skipping_choice_v3),
                Choice("4", R.string.flycast_opt_frame_skipping_choice_v4),
                Choice("5", R.string.flycast_opt_frame_skipping_choice_v5),
                Choice("6", R.string.flycast_opt_frame_skipping_choice_v6),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_widescreen_cheats",
            labelRes = R.string.flycast_opt_widescreen_cheats_label,
            descriptionRes = R.string.flycast_opt_widescreen_cheats_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_widescreen_hack",
            labelRes = R.string.flycast_opt_widescreen_hack_label,
            descriptionRes = R.string.flycast_opt_widescreen_hack_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_gdrom_fast_loading",
            labelRes = R.string.flycast_opt_gdrom_fast_loading_label,
            descriptionRes = R.string.flycast_opt_gdrom_fast_loading_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_dc_32mb_mod",
            labelRes = R.string.flycast_opt_dc_32mb_mod_label,
            descriptionRes = R.string.flycast_opt_dc_32mb_mod_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_sh4clock",
            labelRes = R.string.flycast_opt_sh4clock_label,
            descriptionRes = R.string.flycast_opt_sh4clock_info,
            category = "hacks",
            choices = listOf(
                Choice("100", R.string.flycast_opt_sh4clock_choice_v100),
                Choice("110", R.string.flycast_opt_sh4clock_choice_v110),
                Choice("120", R.string.flycast_opt_sh4clock_choice_v120),
                Choice("130", R.string.flycast_opt_sh4clock_choice_v130),
                Choice("140", R.string.flycast_opt_sh4clock_choice_v140),
                Choice("150", R.string.flycast_opt_sh4clock_choice_v150),
                Choice("160", R.string.flycast_opt_sh4clock_choice_v160),
                Choice("170", R.string.flycast_opt_sh4clock_choice_v170),
                Choice("180", R.string.flycast_opt_sh4clock_choice_v180),
                Choice("190", R.string.flycast_opt_sh4clock_choice_v190),
                Choice("200", R.string.flycast_opt_sh4clock_choice_v200),
                Choice("210", R.string.flycast_opt_sh4clock_choice_v210),
                Choice("220", R.string.flycast_opt_sh4clock_choice_v220),
                Choice("230", R.string.flycast_opt_sh4clock_choice_v230),
                Choice("240", R.string.flycast_opt_sh4clock_choice_v240),
                Choice("250", R.string.flycast_opt_sh4clock_choice_v250),
                Choice("260", R.string.flycast_opt_sh4clock_choice_v260),
                Choice("270", R.string.flycast_opt_sh4clock_choice_v270),
                Choice("280", R.string.flycast_opt_sh4clock_choice_v280),
                Choice("290", R.string.flycast_opt_sh4clock_choice_v290),
                Choice("300", R.string.flycast_opt_sh4clock_choice_v300),
                Choice("310", R.string.flycast_opt_sh4clock_choice_v310),
                Choice("320", R.string.flycast_opt_sh4clock_choice_v320),
                Choice("330", R.string.flycast_opt_sh4clock_choice_v330),
                Choice("340", R.string.flycast_opt_sh4clock_choice_v340),
                Choice("350", R.string.flycast_opt_sh4clock_choice_v350),
                Choice("360", R.string.flycast_opt_sh4clock_choice_v360),
                Choice("370", R.string.flycast_opt_sh4clock_choice_v370),
                Choice("380", R.string.flycast_opt_sh4clock_choice_v380),
                Choice("390", R.string.flycast_opt_sh4clock_choice_v390),
                Choice("400", R.string.flycast_opt_sh4clock_choice_v400),
                Choice("410", R.string.flycast_opt_sh4clock_choice_v410),
                Choice("420", R.string.flycast_opt_sh4clock_choice_v420),
                Choice("430", R.string.flycast_opt_sh4clock_choice_v430),
                Choice("440", R.string.flycast_opt_sh4clock_choice_v440),
                Choice("450", R.string.flycast_opt_sh4clock_choice_v450),
                Choice("460", R.string.flycast_opt_sh4clock_choice_v460),
                Choice("470", R.string.flycast_opt_sh4clock_choice_v470),
                Choice("480", R.string.flycast_opt_sh4clock_choice_v480),
                Choice("490", R.string.flycast_opt_sh4clock_choice_v490),
                Choice("500", R.string.flycast_opt_sh4clock_choice_v500),
            ),
            defaultValue = "200",
        ),
        // hacks
        Option(
            key = "reicast_custom_textures",
            labelRes = R.string.flycast_opt_custom_textures_label,
            descriptionRes = R.string.flycast_opt_custom_textures_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_preload_custom_textures",
            labelRes = R.string.flycast_opt_preload_custom_textures_label,
            descriptionRes = R.string.flycast_opt_preload_custom_textures_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_dump_textures",
            labelRes = R.string.flycast_opt_dump_textures_label,
            descriptionRes = R.string.flycast_opt_dump_textures_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // hacks
        Option(
            key = "reicast_dump_replaced_textures",
            labelRes = R.string.flycast_opt_dump_replaced_textures_label,
            descriptionRes = R.string.flycast_opt_dump_replaced_textures_info,
            category = "hacks",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_analog_stick_deadzone",
            labelRes = R.string.flycast_opt_analog_stick_deadzone_label,
            descriptionRes = R.string.flycast_opt_analog_stick_deadzone_info,
            category = "input",
            choices = listOf(
                Choice("0%", R.string.flycast_opt_analog_stick_deadzone_choice_v0),
                Choice("5%", R.string.flycast_opt_analog_stick_deadzone_choice_v5),
                Choice("10%", R.string.flycast_opt_analog_stick_deadzone_choice_v10),
                Choice("15%", R.string.flycast_opt_analog_stick_deadzone_choice_v15),
                Choice("20%", R.string.flycast_opt_analog_stick_deadzone_choice_v20),
                Choice("25%", R.string.flycast_opt_analog_stick_deadzone_choice_v25),
                Choice("30%", R.string.flycast_opt_analog_stick_deadzone_choice_v30),
            ),
            defaultValue = "15%",
        ),
        // input
        Option(
            key = "reicast_trigger_deadzone",
            labelRes = R.string.flycast_opt_trigger_deadzone_label,
            descriptionRes = R.string.flycast_opt_trigger_deadzone_info,
            category = "input",
            choices = listOf(
                Choice("0%", R.string.flycast_opt_trigger_deadzone_choice_v0),
                Choice("5%", R.string.flycast_opt_trigger_deadzone_choice_v5),
                Choice("10%", R.string.flycast_opt_trigger_deadzone_choice_v10),
                Choice("15%", R.string.flycast_opt_trigger_deadzone_choice_v15),
                Choice("20%", R.string.flycast_opt_trigger_deadzone_choice_v20),
                Choice("25%", R.string.flycast_opt_trigger_deadzone_choice_v25),
                Choice("30%", R.string.flycast_opt_trigger_deadzone_choice_v30),
            ),
            defaultValue = "0%",
        ),
        // input
        Option(
            key = "reicast_digital_triggers",
            labelRes = R.string.flycast_opt_digital_triggers_label,
            descriptionRes = R.string.flycast_opt_digital_triggers_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_network_output",
            labelRes = R.string.flycast_opt_network_output_label,
            descriptionRes = R.string.flycast_opt_network_output_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_show_lightgun_settings",
            labelRes = R.string.flycast_opt_show_lightgun_settings_label,
            descriptionRes = R.string.flycast_opt_show_lightgun_settings_info,
            category = "input",
            choices = listOf(
                Choice("enabled", R.string.flycast_choice_enabled),
                Choice("disabled", R.string.flycast_choice_disabled),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_lightgun_crosshair_size_scaling",
            labelRes = R.string.flycast_opt_lightgun_crosshair_size_scaling_label,
            descriptionRes = R.string.flycast_opt_lightgun_crosshair_size_scaling_info,
            category = "input",
            choices = listOf(
                Choice("50%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v50),
                Choice("60%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v60),
                Choice("70%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v70),
                Choice("80%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v80),
                Choice("90%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v90),
                Choice("100%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v100),
                Choice("110%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v110),
                Choice("120%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v120),
                Choice("130%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v130),
                Choice("140%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v140),
                Choice("150%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v150),
                Choice("160%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v160),
                Choice("170%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v170),
                Choice("180%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v180),
                Choice("190%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v190),
                Choice("200%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v200),
                Choice("210%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v210),
                Choice("220%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v220),
                Choice("230%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v230),
                Choice("240%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v240),
                Choice("250%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v250),
                Choice("260%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v260),
                Choice("270%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v270),
                Choice("280%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v280),
                Choice("290%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v290),
                Choice("300%", R.string.flycast_opt_lightgun_crosshair_size_scaling_choice_v300),
            ),
            defaultValue = "100%",
        ),
        // input
        Option(
            key = "reicast_lightgun1_crosshair",
            labelRes = R.string.flycast_opt_lightgun1_crosshair_label,
            descriptionRes = R.string.flycast_opt_lightgun1_crosshair_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("White", R.string.flycast_choice_color_white),
                Choice("Red", R.string.flycast_choice_color_red),
                Choice("Green", R.string.flycast_choice_color_green),
                Choice("Blue", R.string.flycast_choice_color_blue),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_lightgun2_crosshair",
            labelRes = R.string.flycast_opt_lightgun2_crosshair_label,
            descriptionRes = R.string.flycast_opt_lightgun2_crosshair_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("White", R.string.flycast_choice_color_white),
                Choice("Red", R.string.flycast_choice_color_red),
                Choice("Green", R.string.flycast_choice_color_green),
                Choice("Blue", R.string.flycast_choice_color_blue),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_lightgun3_crosshair",
            labelRes = R.string.flycast_opt_lightgun3_crosshair_label,
            descriptionRes = R.string.flycast_opt_lightgun3_crosshair_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("White", R.string.flycast_choice_color_white),
                Choice("Red", R.string.flycast_choice_color_red),
                Choice("Green", R.string.flycast_choice_color_green),
                Choice("Blue", R.string.flycast_choice_color_blue),
            ),
            defaultValue = "disabled",
        ),
        // input
        Option(
            key = "reicast_lightgun4_crosshair",
            labelRes = R.string.flycast_opt_lightgun4_crosshair_label,
            descriptionRes = R.string.flycast_opt_lightgun4_crosshair_info,
            category = "input",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("White", R.string.flycast_choice_color_white),
                Choice("Red", R.string.flycast_choice_color_red),
                Choice("Green", R.string.flycast_choice_color_green),
                Choice("Blue", R.string.flycast_choice_color_blue),
            ),
            defaultValue = "disabled",
        ),
        // expansions
        Option(
            key = "reicast_device_port1_slot1",
            labelRes = R.string.flycast_opt_device_port1_slot1_label,
            descriptionRes = R.string.flycast_opt_device_port1_slot1_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port1_slot1_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port1_slot1_choice_purupuru),
                Choice("DreamPotato", R.string.flycast_opt_device_port1_slot1_choice_dreampotato),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "VMU",
        ),
        // expansions
        Option(
            key = "reicast_device_port1_slot2",
            labelRes = R.string.flycast_opt_device_port1_slot2_label,
            descriptionRes = R.string.flycast_opt_device_port1_slot2_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port1_slot2_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port1_slot2_choice_purupuru),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "Purupuru",
        ),
        // expansions
        Option(
            key = "reicast_device_port2_slot1",
            labelRes = R.string.flycast_opt_device_port2_slot1_label,
            descriptionRes = R.string.flycast_opt_device_port2_slot1_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port2_slot1_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port2_slot1_choice_purupuru),
                Choice("DreamPotato", R.string.flycast_opt_device_port2_slot1_choice_dreampotato),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "VMU",
        ),
        // expansions
        Option(
            key = "reicast_device_port2_slot2",
            labelRes = R.string.flycast_opt_device_port2_slot2_label,
            descriptionRes = R.string.flycast_opt_device_port2_slot2_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port2_slot2_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port2_slot2_choice_purupuru),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "Purupuru",
        ),
        // expansions
        Option(
            key = "reicast_device_port3_slot1",
            labelRes = R.string.flycast_opt_device_port3_slot1_label,
            descriptionRes = R.string.flycast_opt_device_port3_slot1_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port3_slot1_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port3_slot1_choice_purupuru),
                Choice("DreamPotato", R.string.flycast_opt_device_port3_slot1_choice_dreampotato),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "VMU",
        ),
        // expansions
        Option(
            key = "reicast_device_port3_slot2",
            labelRes = R.string.flycast_opt_device_port3_slot2_label,
            descriptionRes = R.string.flycast_opt_device_port3_slot2_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port3_slot2_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port3_slot2_choice_purupuru),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "Purupuru",
        ),
        // expansions
        Option(
            key = "reicast_device_port4_slot1",
            labelRes = R.string.flycast_opt_device_port4_slot1_label,
            descriptionRes = R.string.flycast_opt_device_port4_slot1_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port4_slot1_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port4_slot1_choice_purupuru),
                Choice("DreamPotato", R.string.flycast_opt_device_port4_slot1_choice_dreampotato),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "VMU",
        ),
        // expansions
        Option(
            key = "reicast_device_port4_slot2",
            labelRes = R.string.flycast_opt_device_port4_slot2_label,
            descriptionRes = R.string.flycast_opt_device_port4_slot2_info,
            category = "expansions",
            choices = listOf(
                Choice("VMU", R.string.flycast_opt_device_port4_slot2_choice_vmu),
                Choice("Purupuru", R.string.flycast_opt_device_port4_slot2_choice_purupuru),
                Choice("None", R.string.flycast_choice_none),
            ),
            defaultValue = "Purupuru",
        ),
        // vmu
        Option(
            key = "reicast_per_content_vmus",
            labelRes = R.string.flycast_opt_per_content_vmus_label,
            descriptionRes = R.string.flycast_opt_per_content_vmus_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("VMU A1", R.string.flycast_opt_per_content_vmus_choice_vmu_a1),
                Choice("All VMUs", R.string.flycast_opt_per_content_vmus_choice_all_vmus),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu_sound",
            labelRes = R.string.flycast_opt_vmu_sound_label,
            descriptionRes = R.string.flycast_opt_vmu_sound_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_linked_vmu_storage",
            labelRes = R.string.flycast_opt_linked_vmu_storage_label,
            descriptionRes = R.string.flycast_opt_linked_vmu_storage_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_show_vmu_screen_settings",
            labelRes = R.string.flycast_opt_show_vmu_screen_settings_label,
            descriptionRes = R.string.flycast_opt_show_vmu_screen_settings_info,
            category = "vmu",
            choices = listOf(
                Choice("enabled", R.string.flycast_choice_enabled),
                Choice("disabled", R.string.flycast_choice_disabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_screen_display",
            labelRes = R.string.flycast_opt_vmu1_screen_display_label,
            descriptionRes = R.string.flycast_opt_vmu1_screen_display_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_screen_position",
            labelRes = R.string.flycast_opt_vmu1_screen_position_label,
            descriptionRes = R.string.flycast_opt_vmu1_screen_position_info,
            category = "vmu",
            choices = listOf(
                Choice("Upper Left", R.string.flycast_choice_upper_left),
                Choice("Upper Right", R.string.flycast_choice_upper_right),
                Choice("Lower Left", R.string.flycast_choice_lower_left),
                Choice("Lower Right", R.string.flycast_choice_lower_right),
            ),
            defaultValue = "Upper Left",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_screen_size_mult",
            labelRes = R.string.flycast_opt_vmu1_screen_size_mult_label,
            descriptionRes = R.string.flycast_opt_vmu1_screen_size_mult_info,
            category = "vmu",
            choices = listOf(
                Choice("1x", R.string.flycast_opt_vmu1_screen_size_mult_choice_v1x),
                Choice("2x", R.string.flycast_opt_vmu1_screen_size_mult_choice_v2x),
                Choice("3x", R.string.flycast_opt_vmu1_screen_size_mult_choice_v3x),
                Choice("4x", R.string.flycast_opt_vmu1_screen_size_mult_choice_v4x),
                Choice("5x", R.string.flycast_opt_vmu1_screen_size_mult_choice_v5x),
            ),
            defaultValue = "1x",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_pixel_on_color",
            labelRes = R.string.flycast_opt_vmu1_pixel_on_color_label,
            descriptionRes = R.string.flycast_opt_vmu1_pixel_on_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_ON 00",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_pixel_off_color",
            labelRes = R.string.flycast_opt_vmu1_pixel_off_color_label,
            descriptionRes = R.string.flycast_opt_vmu1_pixel_off_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_OFF 01",
        ),
        // vmu
        Option(
            key = "reicast_vmu1_screen_opacity",
            labelRes = R.string.flycast_opt_vmu1_screen_opacity_label,
            descriptionRes = R.string.flycast_opt_vmu1_screen_opacity_info,
            category = "vmu",
            choices = listOf(
                Choice("10%", R.string.flycast_opt_vmu1_screen_opacity_choice_v10),
                Choice("20%", R.string.flycast_opt_vmu1_screen_opacity_choice_v20),
                Choice("30%", R.string.flycast_opt_vmu1_screen_opacity_choice_v30),
                Choice("40%", R.string.flycast_opt_vmu1_screen_opacity_choice_v40),
                Choice("50%", R.string.flycast_opt_vmu1_screen_opacity_choice_v50),
                Choice("60%", R.string.flycast_opt_vmu1_screen_opacity_choice_v60),
                Choice("70%", R.string.flycast_opt_vmu1_screen_opacity_choice_v70),
                Choice("80%", R.string.flycast_opt_vmu1_screen_opacity_choice_v80),
                Choice("90%", R.string.flycast_opt_vmu1_screen_opacity_choice_v90),
                Choice("100%", R.string.flycast_opt_vmu1_screen_opacity_choice_v100),
            ),
            defaultValue = "100%",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_screen_display",
            labelRes = R.string.flycast_opt_vmu2_screen_display_label,
            descriptionRes = R.string.flycast_opt_vmu2_screen_display_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_screen_position",
            labelRes = R.string.flycast_opt_vmu2_screen_position_label,
            descriptionRes = R.string.flycast_opt_vmu2_screen_position_info,
            category = "vmu",
            choices = listOf(
                Choice("Upper Left", R.string.flycast_choice_upper_left),
                Choice("Upper Right", R.string.flycast_choice_upper_right),
                Choice("Lower Left", R.string.flycast_choice_lower_left),
                Choice("Lower Right", R.string.flycast_choice_lower_right),
            ),
            defaultValue = "Upper Right",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_screen_size_mult",
            labelRes = R.string.flycast_opt_vmu2_screen_size_mult_label,
            descriptionRes = R.string.flycast_opt_vmu2_screen_size_mult_info,
            category = "vmu",
            choices = listOf(
                Choice("1x", R.string.flycast_opt_vmu2_screen_size_mult_choice_v1x),
                Choice("2x", R.string.flycast_opt_vmu2_screen_size_mult_choice_v2x),
                Choice("3x", R.string.flycast_opt_vmu2_screen_size_mult_choice_v3x),
                Choice("4x", R.string.flycast_opt_vmu2_screen_size_mult_choice_v4x),
                Choice("5x", R.string.flycast_opt_vmu2_screen_size_mult_choice_v5x),
            ),
            defaultValue = "1x",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_pixel_on_color",
            labelRes = R.string.flycast_opt_vmu2_pixel_on_color_label,
            descriptionRes = R.string.flycast_opt_vmu2_pixel_on_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_ON 00",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_pixel_off_color",
            labelRes = R.string.flycast_opt_vmu2_pixel_off_color_label,
            descriptionRes = R.string.flycast_opt_vmu2_pixel_off_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_OFF 01",
        ),
        // vmu
        Option(
            key = "reicast_vmu2_screen_opacity",
            labelRes = R.string.flycast_opt_vmu2_screen_opacity_label,
            descriptionRes = R.string.flycast_opt_vmu2_screen_opacity_info,
            category = "vmu",
            choices = listOf(
                Choice("10%", R.string.flycast_opt_vmu2_screen_opacity_choice_v10),
                Choice("20%", R.string.flycast_opt_vmu2_screen_opacity_choice_v20),
                Choice("30%", R.string.flycast_opt_vmu2_screen_opacity_choice_v30),
                Choice("40%", R.string.flycast_opt_vmu2_screen_opacity_choice_v40),
                Choice("50%", R.string.flycast_opt_vmu2_screen_opacity_choice_v50),
                Choice("60%", R.string.flycast_opt_vmu2_screen_opacity_choice_v60),
                Choice("70%", R.string.flycast_opt_vmu2_screen_opacity_choice_v70),
                Choice("80%", R.string.flycast_opt_vmu2_screen_opacity_choice_v80),
                Choice("90%", R.string.flycast_opt_vmu2_screen_opacity_choice_v90),
                Choice("100%", R.string.flycast_opt_vmu2_screen_opacity_choice_v100),
            ),
            defaultValue = "100%",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_screen_display",
            labelRes = R.string.flycast_opt_vmu3_screen_display_label,
            descriptionRes = R.string.flycast_opt_vmu3_screen_display_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_screen_position",
            labelRes = R.string.flycast_opt_vmu3_screen_position_label,
            descriptionRes = R.string.flycast_opt_vmu3_screen_position_info,
            category = "vmu",
            choices = listOf(
                Choice("Upper Left", R.string.flycast_choice_upper_left),
                Choice("Upper Right", R.string.flycast_choice_upper_right),
                Choice("Lower Left", R.string.flycast_choice_lower_left),
                Choice("Lower Right", R.string.flycast_choice_lower_right),
            ),
            defaultValue = "Lower Left",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_screen_size_mult",
            labelRes = R.string.flycast_opt_vmu3_screen_size_mult_label,
            descriptionRes = R.string.flycast_opt_vmu3_screen_size_mult_info,
            category = "vmu",
            choices = listOf(
                Choice("1x", R.string.flycast_opt_vmu3_screen_size_mult_choice_v1x),
                Choice("2x", R.string.flycast_opt_vmu3_screen_size_mult_choice_v2x),
                Choice("3x", R.string.flycast_opt_vmu3_screen_size_mult_choice_v3x),
                Choice("4x", R.string.flycast_opt_vmu3_screen_size_mult_choice_v4x),
                Choice("5x", R.string.flycast_opt_vmu3_screen_size_mult_choice_v5x),
            ),
            defaultValue = "1x",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_pixel_on_color",
            labelRes = R.string.flycast_opt_vmu3_pixel_on_color_label,
            descriptionRes = R.string.flycast_opt_vmu3_pixel_on_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_ON 00",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_pixel_off_color",
            labelRes = R.string.flycast_opt_vmu3_pixel_off_color_label,
            descriptionRes = R.string.flycast_opt_vmu3_pixel_off_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_OFF 01",
        ),
        // vmu
        Option(
            key = "reicast_vmu3_screen_opacity",
            labelRes = R.string.flycast_opt_vmu3_screen_opacity_label,
            descriptionRes = R.string.flycast_opt_vmu3_screen_opacity_info,
            category = "vmu",
            choices = listOf(
                Choice("10%", R.string.flycast_opt_vmu3_screen_opacity_choice_v10),
                Choice("20%", R.string.flycast_opt_vmu3_screen_opacity_choice_v20),
                Choice("30%", R.string.flycast_opt_vmu3_screen_opacity_choice_v30),
                Choice("40%", R.string.flycast_opt_vmu3_screen_opacity_choice_v40),
                Choice("50%", R.string.flycast_opt_vmu3_screen_opacity_choice_v50),
                Choice("60%", R.string.flycast_opt_vmu3_screen_opacity_choice_v60),
                Choice("70%", R.string.flycast_opt_vmu3_screen_opacity_choice_v70),
                Choice("80%", R.string.flycast_opt_vmu3_screen_opacity_choice_v80),
                Choice("90%", R.string.flycast_opt_vmu3_screen_opacity_choice_v90),
                Choice("100%", R.string.flycast_opt_vmu3_screen_opacity_choice_v100),
            ),
            defaultValue = "100%",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_screen_display",
            labelRes = R.string.flycast_opt_vmu4_screen_display_label,
            descriptionRes = R.string.flycast_opt_vmu4_screen_display_info,
            category = "vmu",
            choices = listOf(
                Choice("disabled", R.string.flycast_choice_disabled),
                Choice("enabled", R.string.flycast_choice_enabled),
            ),
            defaultValue = "disabled",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_screen_position",
            labelRes = R.string.flycast_opt_vmu4_screen_position_label,
            descriptionRes = R.string.flycast_opt_vmu4_screen_position_info,
            category = "vmu",
            choices = listOf(
                Choice("Upper Left", R.string.flycast_choice_upper_left),
                Choice("Upper Right", R.string.flycast_choice_upper_right),
                Choice("Lower Left", R.string.flycast_choice_lower_left),
                Choice("Lower Right", R.string.flycast_choice_lower_right),
            ),
            defaultValue = "Lower Right",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_screen_size_mult",
            labelRes = R.string.flycast_opt_vmu4_screen_size_mult_label,
            descriptionRes = R.string.flycast_opt_vmu4_screen_size_mult_info,
            category = "vmu",
            choices = listOf(
                Choice("1x", R.string.flycast_opt_vmu4_screen_size_mult_choice_v1x),
                Choice("2x", R.string.flycast_opt_vmu4_screen_size_mult_choice_v2x),
                Choice("3x", R.string.flycast_opt_vmu4_screen_size_mult_choice_v3x),
                Choice("4x", R.string.flycast_opt_vmu4_screen_size_mult_choice_v4x),
                Choice("5x", R.string.flycast_opt_vmu4_screen_size_mult_choice_v5x),
            ),
            defaultValue = "1x",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_pixel_on_color",
            labelRes = R.string.flycast_opt_vmu4_pixel_on_color_label,
            descriptionRes = R.string.flycast_opt_vmu4_pixel_on_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_ON 00",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_pixel_off_color",
            labelRes = R.string.flycast_opt_vmu4_pixel_off_color_label,
            descriptionRes = R.string.flycast_opt_vmu4_pixel_off_color_info,
            category = "vmu",
            choices = listOf(
                Choice("DEFAULT_OFF 01", R.string.flycast_choice_color_default_off),
                Choice("DEFAULT_ON 00", R.string.flycast_choice_color_default_on),
                Choice("BLACK 02", R.string.flycast_choice_color_black),
                Choice("BLUE 03", R.string.flycast_choice_color_blue),
                Choice("LIGHT_BLUE 04", R.string.flycast_choice_color_light_blue),
                Choice("GREEN 05", R.string.flycast_choice_color_green),
                Choice("CYAN 06", R.string.flycast_choice_color_cyan),
                Choice("CYAN_BLUE 07", R.string.flycast_choice_color_cyan_blue),
                Choice("LIGHT_GREEN 08", R.string.flycast_choice_color_light_green),
                Choice("CYAN_GREEN 09", R.string.flycast_choice_color_cyan_green),
                Choice("LIGHT_CYAN 10", R.string.flycast_choice_color_light_cyan),
                Choice("RED 11", R.string.flycast_choice_color_red),
                Choice("PURPLE 12", R.string.flycast_choice_color_purple),
                Choice("LIGHT_PURPLE 13", R.string.flycast_choice_color_light_purple),
                Choice("YELLOW 14", R.string.flycast_choice_color_yellow),
                Choice("GRAY 15", R.string.flycast_choice_color_gray),
                Choice("LIGHT_PURPLE_2 16", R.string.flycast_choice_color_light_purple_2),
                Choice("LIGHT_GREEN_2 17", R.string.flycast_choice_color_light_green_2),
                Choice("LIGHT_GREEN_3 18", R.string.flycast_choice_color_light_green_3),
                Choice("LIGHT_CYAN_2 19", R.string.flycast_choice_color_light_cyan_2),
                Choice("LIGHT_RED_2 20", R.string.flycast_choice_color_light_red_2),
                Choice("MAGENTA 21", R.string.flycast_choice_color_magenta),
                Choice("LIGHT_PURPLE_3 22", R.string.flycast_choice_color_light_purple_3),
                Choice("LIGHT_ORANGE 23", R.string.flycast_choice_color_light_orange),
                Choice("ORANGE 24", R.string.flycast_choice_color_orange),
                Choice("LIGHT_PURPLE_4 25", R.string.flycast_choice_color_light_purple_4),
                Choice("LIGHT_YELLOW 26", R.string.flycast_choice_color_light_yellow),
                Choice("LIGHT_YELLOW_2 27", R.string.flycast_choice_color_light_yellow_2),
                Choice("WHITE 28", R.string.flycast_choice_color_white),
            ),
            defaultValue = "DEFAULT_OFF 01",
        ),
        // vmu
        Option(
            key = "reicast_vmu4_screen_opacity",
            labelRes = R.string.flycast_opt_vmu4_screen_opacity_label,
            descriptionRes = R.string.flycast_opt_vmu4_screen_opacity_info,
            category = "vmu",
            choices = listOf(
                Choice("10%", R.string.flycast_opt_vmu4_screen_opacity_choice_v10),
                Choice("20%", R.string.flycast_opt_vmu4_screen_opacity_choice_v20),
                Choice("30%", R.string.flycast_opt_vmu4_screen_opacity_choice_v30),
                Choice("40%", R.string.flycast_opt_vmu4_screen_opacity_choice_v40),
                Choice("50%", R.string.flycast_opt_vmu4_screen_opacity_choice_v50),
                Choice("60%", R.string.flycast_opt_vmu4_screen_opacity_choice_v60),
                Choice("70%", R.string.flycast_opt_vmu4_screen_opacity_choice_v70),
                Choice("80%", R.string.flycast_opt_vmu4_screen_opacity_choice_v80),
                Choice("90%", R.string.flycast_opt_vmu4_screen_opacity_choice_v90),
                Choice("100%", R.string.flycast_opt_vmu4_screen_opacity_choice_v100),
            ),
            defaultValue = "100%",
        ),
    )

    private val optionsByKey: Map<String, Option> by lazy { optionList.associateBy { it.key } }

    private val managedKeys: Set<String> = setOf(
        "reicast_custom_textures",
        "reicast_dump_replaced_textures",
        "reicast_dump_textures",
        "reicast_internal_resolution",
        "reicast_preload_custom_textures",
    )

    fun all(): List<Option> = optionList

    fun categories(): List<Category> = categoryList

    fun forCategory(categoryKey: String): List<Option> = optionList.filter { it.category == categoryKey }

    fun option(key: String): Option? = optionsByKey[key]

    fun isManagedKey(key: String): Boolean = key in managedKeys

    fun categoryLabel(categoryKey: String): String =
        categoryList.firstOrNull { it.key == categoryKey }?.key ?: categoryKey

    fun systemOptions(): List<Option> = forCategory("system")

    fun videoOptions(): List<Option> = forCategory("video")

    fun performanceOptions(): List<Option> = forCategory("performance")

    fun hacksOptions(): List<Option> = forCategory("hacks")

    fun inputOptions(): List<Option> = forCategory("input")

    fun expansionsOptions(): List<Option> = forCategory("expansions")

    fun vmuOptions(): List<Option> = forCategory("vmu")

    private val networkKeys: Set<String> = setOf(
        "reicast_emulate_bba",
        "reicast_upnp",
        "reicast_dcnet",
        "reicast_network_output",
    )

    /** Network options, grouped for the Network settings tab. */
    fun networkOptions(): List<Option> = optionList.filter { it.key in networkKeys }

    /** In-game menu subsets. Managed options stay in the main settings only. */
    fun gameMenuGraphicsOptions(): List<Option> =
        videoOptions().filterNot { isManagedKey(it.key) }

    fun gameMenuEmulationOptions(): List<Option> =
        performanceOptions() + systemOptions().filter {
            it.shortKey in setOf("region", "language", "hle_bios")
        }

    fun gameMenuControlsOptions(): List<Option> = inputOptions()

    /** Options owned by the emulation thread and not persisted per game. */
    val sessionKeys: Set<String> = setOf(
        "reicast_frame_skipping",
        "reicast_auto_skip_frame",
    )
}
