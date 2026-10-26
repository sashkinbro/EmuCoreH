package com.sbro.emucoreh.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.ui.common.premiumFilterChipColors
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaCdBios
import com.sbro.emucoreh.core.SegaCoreOptions
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.core.SegaPlatformDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberGamePlatform(path: String?, stored: String? = null): EmulationPlatform {
    val context = LocalContext.current
    val known = stored?.let { runCatching { EmulationPlatform.valueOf(it) }.getOrNull() }
    var platform by remember(path, stored) { mutableStateOf(known) }
    LaunchedEffect(path, stored) {
        platform = known ?: path?.let { withContext(Dispatchers.IO) { SegaPlatformDetector.detect(context, it) } }
            ?: EmulationPlatform.DREAMCAST
    }
    // Fail closed while header detection is in progress: don't flash unsupported Flycast rows.
    return platform ?: EmulationPlatform.GENESIS
}

@Composable
internal fun platformLabel(platform: EmulationPlatform): String = stringResource(when (platform) {
    EmulationPlatform.MODEL_3 -> R.string.home_platform_model_3
    EmulationPlatform.DREAMCAST -> R.string.sega_flycast_systems
    EmulationPlatform.GENESIS -> R.string.home_platform_genesis
    EmulationPlatform.SATURN -> R.string.home_platform_saturn
    EmulationPlatform.SEGA_CD -> R.string.home_platform_sega_cd
    EmulationPlatform.SEGA_32X -> R.string.home_platform_sega_32x
    EmulationPlatform.MASTER_SYSTEM -> R.string.home_platform_master_system
    EmulationPlatform.GAME_GEAR -> R.string.home_platform_game_gear
    EmulationPlatform.SG_1000 -> R.string.home_platform_sg_1000
})

@Composable
internal fun PlatformSelector(platform: EmulationPlatform, onSelect: (EmulationPlatform) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EmulationPlatform.entries.forEach { candidate ->
            FilterChip(selected = candidate == platform, onClick = { onSelect(candidate) }, colors = premiumFilterChipColors(),
                label = { Text(platformLabel(candidate)) })
        }
    }
}

@Composable
internal fun SegaSettingsPanel(platform: EmulationPlatform) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(platformLabel(platform), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (platform == EmulationPlatform.SG_1000) Text("1 �� 2", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(stringResource(if (platform in setOf(EmulationPlatform.MASTER_SYSTEM, EmulationPlatform.GAME_GEAR, EmulationPlatform.SG_1000))
            R.string.sega_two_button_controls else R.string.sega_six_button_controls),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Model3SettingsInfo(showNativeVideo: Boolean = true, showCabinetSetup: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showNativeVideo) SettingsInlineNote(stringResource(R.string.model3_native_video))
        if (showCabinetSetup) SettingsInlineNote(stringResource(R.string.model3_cabinet_setup_help))
    }
}

@Composable
internal fun SegaCoreOptionRows(
    platform: EmulationPlatform,
    overrides: Map<String, String> = emptyMap(),
    liveOnly: Boolean = false,
    category: String? = null,
    onChange: ((String, String) -> Unit)? = null
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val options = if (platform.isSaturn) com.sbro.emucoreh.core.SaturnCoreOptions.options(platform, liveOnly) else SegaCoreOptions.options(platform, liveOnly)
    options.filter { category == null || it.category == category }.forEach { option ->
        val current = remember(platform, option.key, overrides, version) {
            overrides[option.key] ?: (if (platform.isSaturn) com.sbro.emucoreh.core.SaturnCoreOptions.value(context, option.key, platform) else SegaCoreOptions.value(context, option.key, platform)) ?: option.defaultValue
        }
        val help = buildList {
            val description = stringResource(option.descriptionRes)
            if (description.isNotBlank()) add(description)
            if (!liveOnly && (option.key in setOf("jgenesis_region", "jgenesis_timing") ||
                    (platform.isSaturn && com.sbro.emucoreh.core.SaturnCoreOptions.requiresRestart(option.key)))) {
                add(stringResource(R.string.sega_restart_required))
            }
        }.distinct().joinToString("\n\n")
        CoreStringChoiceSection(
            title = stringResource(option.labelRes),
            options = option.choices.map { choice -> choice.value to stringResource(choice.labelRes) },
            selectedValue = current,
            onSelect = { value ->
                if (onChange != null) onChange(option.key, value)
                else {
                    if (platform.isSaturn) com.sbro.emucoreh.core.SaturnCoreOptions.set(context, option.key, value, platform)
                    else SegaCoreOptions.set(context, option.key, value, platform)
                    if (com.sbro.emucoreh.core.CoreRuntime.platform == platform) NativeApp.setCoreOption(option.key, value)
                }
                version++
            },
            helpText = help.ifBlank { null }
        )
    }
}

@Composable
internal fun SegaGlobalGamepadSettings(platform: EmulationPlatform) {
    val context = LocalContext.current
    var overrides by remember(platform) { mutableStateOf(com.sbro.emucoreh.core.SegaControlProfiles.load(context, platform)) }
    var showMapping by remember(platform) { mutableStateOf(false) }
    SettingsItem(
        icon = Icons.Rounded.Gamepad,
        label = stringResource(R.string.settings_gamepad_mapping_configure),
        value = stringResource(R.string.settings_gamepad_mapping_title),
        onClick = { showMapping = true }
    )
    if (overrides.isNotEmpty()) {
        SettingsItem(
            icon = Icons.Rounded.RestartAlt,
            label = stringResource(R.string.settings_gamepad_mapping_reset_title),
            value = stringResource(R.string.settings_gamepad_mapping_reset_desc),
            onClick = {
                overrides = emptyMap()
                com.sbro.emucoreh.core.SegaControlProfiles.save(context, platform, overrides)
            }
        )
    }
    if (showMapping) PerGameGamepadMappingDialog(platform = platform,
        globalBindingsByPad = com.sbro.emucoreh.core.SegaControlProfiles.resolve(platform, emptyMap()),
        bindingsByPad = overrides,
        onBindingsChange = { updated ->
            overrides = updated
            com.sbro.emucoreh.core.SegaControlProfiles.save(context, platform, updated)
        }, onDismiss = { showMapping = false })
}
