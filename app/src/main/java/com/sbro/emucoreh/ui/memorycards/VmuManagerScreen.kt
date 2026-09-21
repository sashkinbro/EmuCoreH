package com.sbro.emucoreh.ui.memorycards

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.VmuFile
import com.sbro.emucoreh.data.VmuRepository
import com.sbro.emucoreh.ui.common.ScreenTopBar
import com.sbro.emucoreh.ui.common.appScreenTopPadding
import com.sbro.emucoreh.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucoreh.ui.theme.ScreenHorizontalPadding
import com.sbro.emucoreh.ui.theme.neon.LocalNeonTheme
import com.sbro.emucoreh.ui.theme.neon.NeonSystemBanner
import com.sbro.emucoreh.ui.theme.neon.neonButtonShape
import com.sbro.emucoreh.ui.theme.neon.neonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmuManagerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { VmuRepository(context) }
    val scope = rememberCoroutineScope()
    var vmus by remember { mutableStateOf<List<VmuFile>>(emptyList()) }
    var directoryExists by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isWorking by remember { mutableStateOf(false) }
    val backupSuccess = stringResource(R.string.psp_memstick_backup_success)
    val backupFailure = stringResource(R.string.psp_memstick_backup_failure)
    val restoreSuccess = stringResource(R.string.psp_memstick_restore_success)
    val restoreFailure = stringResource(R.string.psp_memstick_restore_failure)
    val createSuccess = stringResource(R.string.psp_memstick_create_success)
    val createFailure = stringResource(R.string.psp_memstick_create_failure)
    val neonThemeActive = LocalNeonTheme.current

    val cards = remember(vmus) {
        vmus.filter { it.name.startsWith("vmu_save_", ignoreCase = true) }
    }
    val systemFiles = remember(vmus) {
        vmus.filterNot { it.name.startsWith("vmu_save_", ignoreCase = true) }
    }

    fun refresh() {
        scope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                repository.saveDirectory.exists() to repository.vmus()
            }
            directoryExists = result.first
            vmus = result.second
            isLoading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            val result = withContext(Dispatchers.IO) { repository.backup(uri) }
            isWorking = false
            Toast.makeText(context, if (result) backupSuccess else backupFailure, Toast.LENGTH_SHORT).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            val result = withContext(Dispatchers.IO) { repository.restore(uri) }
            isWorking = false
            Toast.makeText(context, if (result) restoreSuccess else restoreFailure, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .padding(navigationBarsHorizontalPaddingValues())) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding, bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenTopBar(title = stringResource(R.string.psp_memstick_title),
                    onBackClick = onBackClick,
                    modifier = Modifier.padding(top = appScreenTopPadding(), bottom = 4.dp))
            }
            if (neonThemeActive) item { NeonSystemBanner() }
            item {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(shape = neonButtonShape(),
                        onClick = {
                            scope.launch {
                                isWorking = true
                                val created = withContext(Dispatchers.IO) { repository.createDefaultVmus() }
                                isWorking = false
                                Toast.makeText(context, if (created) createSuccess else createFailure,
                                    Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }, enabled = !isWorking,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            contentColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.psp_memstick_create_action))
                    }
                    OutlinedButton(shape = neonButtonShape(),
                        onClick = { backupLauncher.launch("EmuCoreH-VMU.zip") },
                        enabled = !isWorking && vmus.isNotEmpty()) {
                        Icon(Icons.Rounded.Save, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.psp_memstick_backup))
                    }
                    OutlinedButton(shape = neonButtonShape(),
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
                        enabled = !isWorking) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.psp_memstick_restore))
                    }
                }
            }
            if (isLoading || isWorking) item {
                Surface(shape = neonShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.memory_card_loading),
                            Modifier.padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!isLoading && cards.isNotEmpty()) item {
                Surface(modifier = Modifier.fillMaxWidth(), shape = neonShape(20.dp),
                    tonalElevation = 1.dp, shadowElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(54.dp).background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                neonShape(16.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Memory, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.padding(start = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.shell_memory_cards),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(R.string.vmu_cards_count, cards.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (!isLoading && vmus.isEmpty()) item {
                Surface(modifier = Modifier.fillMaxWidth(), shape = neonShape(22.dp),
                    color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.psp_memstick_empty_card),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.psp_memstick_create_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!isLoading && cards.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.shell_memory_cards),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp))
                }
                items(cards, key = { it.name }) { vmu ->
                    VmuRow(vmu = vmu, title = vmuCardTitle(vmu.name))
                }
            }
            if (!isLoading && systemFiles.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.vmu_system_memory),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 10.dp))
                }
                items(systemFiles, key = { it.name }) { vmu ->
                    VmuRow(vmu = vmu, title = vmu.name)
                }
            }
        }
    }
}

private val vmuCardPattern = Regex("^vmu_save_([A-D])([12])\\.bin$", RegexOption.IGNORE_CASE)

@Composable
private fun vmuCardTitle(name: String): String {
    val match = vmuCardPattern.find(name) ?: return name
    return stringResource(
        R.string.vmu_port_slot,
        match.groupValues[1].uppercase(Locale.US),
        match.groupValues[2]
    )
}

@Composable
private fun VmuRow(vmu: VmuFile, title: String) {
    val context = LocalContext.current
    val meta = stringResource(
        R.string.memory_card_meta,
        vmu.name,
        formatTimestamp(context, vmu.modifiedAt)
    )
    Surface(
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(42.dp).background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    neonShape(12.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                formatVmuSize(vmu.bytes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatVmuSize(bytes: Long): String =
    if (bytes >= 1024L) "${bytes / 1024L} KiB" else "$bytes B"

private fun formatTimestamp(context: android.content.Context, millis: Long): String {
    if (millis <= 0L) return "-"
    val moment = Date(millis)
    val date = android.text.format.DateFormat.getDateFormat(context).format(moment)
    val time = android.text.format.DateFormat.getTimeFormat(context).format(moment)
    return "$date $time"
}
