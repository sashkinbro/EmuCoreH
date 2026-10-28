package com.sbro.emucoreh.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.ui.theme.neon.neonShape

private data class SupportedSystem(@StringRes val label: Int, val formats: List<String>)

private val supportedSystems = listOf(
    SupportedSystem(R.string.home_platform_dreamcast, listOf(".cdi", ".chd", ".cue", ".gdi", ".iso", ".m3u")),
    SupportedSystem(R.string.home_platform_naomi, listOf(".7z", ".bin", ".dat", ".lst", ".zip")),
    SupportedSystem(R.string.home_platform_naomi_2, listOf(".7z", ".bin", ".dat", ".lst", ".zip")),
    SupportedSystem(R.string.home_platform_atomiswave, listOf(".7z", ".bin", ".dat", ".lst", ".zip")),
    SupportedSystem(R.string.home_platform_system_sp, listOf(".7z", ".bin", ".dat", ".lst", ".zip")),
    SupportedSystem(R.string.home_platform_model_3, listOf(".zip")),
    SupportedSystem(R.string.home_platform_genesis, listOf(".bin", ".gen", ".md", ".smd", ".zip")),
    SupportedSystem(R.string.home_platform_sega_cd, listOf(".chd", ".cue", ".m3u")),
    SupportedSystem(R.string.home_platform_saturn, listOf(".bin", ".chd", ".cue", ".iso", ".m3u")),
    SupportedSystem(R.string.home_platform_sega_32x, listOf(".32x", ".bin", ".zip")),
    SupportedSystem(R.string.home_platform_master_system, listOf(".bin", ".sms", ".zip")),
    SupportedSystem(R.string.home_platform_game_gear, listOf(".bin", ".gg", ".zip")),
    SupportedSystem(R.string.home_platform_sg_1000, listOf(".bin", ".sg", ".zip")),
)

@Composable
fun SupportedSystemsCard(modifier: Modifier = Modifier) {
    var showFormats by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("supported_systems_card"),
        shape = neonShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier.size(52.dp).clip(neonShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Gamepad, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_supported_consoles),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val consoleNames = supportedSystems.map { stringResource(it.label) }
                    Text(
                        text = consoleNames.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showFormats = !showFormats },
                shape = neonShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
            ) {
                Text(stringResource(R.string.formats_title))
                Icon(
                    if (showFormats) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = showFormats) {
                Column(Modifier.testTag("supported_systems_formats"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    supportedSystems.forEach { console ->
                        Column {
                            Text(stringResource(console.label), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(console.formats.joinToString("  "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
