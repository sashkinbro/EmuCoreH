package com.sbro.emucoreh.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.Sega32XBios
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Sega32XBiosSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPart by rememberSaveable { mutableStateOf<Sega32XBios.Part?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val success = stringResource(R.string.sega_bios_installed)
    val failure = stringResource(R.string.sega_32x_bios_invalid)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val part = pendingPart
        pendingPart = null
        if (uri != null && part != null) {
            busy = true
            scope.launch {
                val installed = withContext(Dispatchers.IO) { Sega32XBios.install(context, uri, part) }
                busy = false
                version++
                Toast.makeText(context, if (installed) success else failure, Toast.LENGTH_LONG).show()
            }
        }
    }
    val installed = remember(context, version) {
        Sega32XBios.Part.entries.filterTo(mutableSetOf()) { Sega32XBios.isInstalled(context, it) }
    }
    Sega32XBiosSlots(installed, busy || pendingPart != null) { part ->
        pendingPart = part
        launcher.launch(arrayOf("*/*"))
    }
}

/** Shared setup and settings presentation; BIOS bytes are handled only by the import helper. */
@Composable
internal fun Sega32XBiosSlots(
    installedParts: Set<Sega32XBios.Part>,
    busy: Boolean,
    onImport: (Sega32XBios.Part) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.sega_32x_bios_title), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.sega_32x_bios_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Sega32XBios.Part.entries.forEachIndexed { index, part ->
            if (index > 0) HorizontalDivider()
            Text(stringResource(when (part) {
                Sega32XBios.Part.M68K -> R.string.sega_32x_bios_m68k
                Sega32XBios.Part.MASTER -> R.string.sega_32x_bios_master
                Sega32XBios.Part.SLAVE -> R.string.sega_32x_bios_slave
            }), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.sega_32x_bios_file, part.fileName, part.size,
                String.format(Locale.ROOT, "%08X", part.crc32)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (part in installedParts) R.string.sega_bios_installed else R.string.settings_not_set),
                modifier = Modifier.testTag("sega_32x_bios_status_${part.name}"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = !busy, onClick = { onImport(part) },
                modifier = Modifier.testTag("sega_32x_bios_import_${part.name}")) {
                Text(stringResource(R.string.sega_bios_import))
            }
        }
    }
}
