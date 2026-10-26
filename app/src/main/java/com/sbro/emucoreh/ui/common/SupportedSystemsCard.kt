package com.sbro.emucoreh.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.ui.theme.neon.neonShape

/** Shared setup guide, placed immediately above the game-folder picker. */
@Composable
fun SupportedSystemsCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("supported_systems_card"),
        shape = neonShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Gamepad, contentDescription = null,
                    modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.supported_systems_title), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Text(stringResource(R.string.supported_systems_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SupportedSystemGroup(stringResource(R.string.supported_systems_no_bios_title),
                stringResource(R.string.supported_systems_no_bios_body))
            SupportedSystemGroup("Dreamcast", stringResource(R.string.supported_systems_dreamcast_body))
            SupportedSystemGroup(stringResource(R.string.supported_systems_required_title),
                stringResource(R.string.supported_systems_required_body))
            SupportedSystemGroup("Naomi · Naomi 2 · Atomiswave · System SP",
                stringResource(R.string.supported_systems_arcade_body))
        }
    }
}

@Composable
private fun SupportedSystemGroup(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
