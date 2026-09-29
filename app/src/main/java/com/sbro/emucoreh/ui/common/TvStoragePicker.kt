package com.sbro.emucoreh.ui.common

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sbro.emucoreh.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import com.sbro.emucoreh.ui.theme.neon.neonShape
import com.sbro.emucoreh.ui.theme.neon.neonButtonShape

enum class TvStorageRequest {
    BIOS_FOLDER,
    GAME_FOLDER
}

/**
 * TV-only helper around Android's Storage Access Framework.
 *
 * The existing phone/tablet picker path remains untouched. This layer only gives a remote user
 * a deterministic storage-volume choice before Android grants the actual file-system permission.
 */
@Composable
fun TvStoragePickerHost(
    request: TvStorageRequest?,
    onDismiss: () -> Unit,
    onBiosSelected: (Uri) -> Unit,
    onGameFolderSelected: (Uri) -> Unit
) {
    if (request == null) return

    val context = LocalContext.current
    val sources = remember(context) { TvStorageAccess.availableSources(context) }
    val firstSourceFocusRequester = remember { FocusRequester() }
    val unavailableMessage = stringResource(R.string.tv_storage_picker_unavailable)
    val storeUnavailableMessage = stringResource(R.string.tv_storage_picker_store_unavailable)
    // Many Android TV boxes ship without DocumentsUI, so the system has no folder picker at all.
    // Asking the user to choose a storage volume first would only lead to a dead end, therefore the
    // file-manager prompt replaces the volume chooser until a compatible picker is installed.
    var showFileManagerPrompt by remember(request) {
        mutableStateOf(!TvStorageAccess.isPickerAvailable(context))
    }

    val biosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let(onBiosSelected)
        onDismiss()
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let(onGameFolderSelected)
        onDismiss()
    }

    fun launch(source: TvStorageSource) {
        val intent = TvStorageAccess.createCompatiblePickerIntent(
            context = context,
            request = request,
            volume = source.volume
        )
        val launchResult = intent?.let { pickerIntent ->
            runCatching {
                when (request) {
                    TvStorageRequest.BIOS_FOLDER -> biosLauncher.launch(pickerIntent)
                    TvStorageRequest.GAME_FOLDER -> folderLauncher.launch(pickerIntent)
                }
            }
        }
        if (launchResult == null || launchResult.isFailure) {
            launchResult?.exceptionOrNull()?.let { error ->
                Log.w("TvStoragePicker", "Unable to launch storage picker: $intent", error)
            }
            showFileManagerPrompt = true
        }
    }

    if (showFileManagerPrompt) {
        FileManagerRequiredDialog(
            onDismiss = onDismiss,
            onInstall = {
                if (!TvStorageAccess.openFileManagerStorePage(context)) {
                    Toast.makeText(context, storeUnavailableMessage, Toast.LENGTH_LONG).show()
                }
            },
            onRetry = {
                if (TvStorageAccess.isPickerAvailable(context)) {
                    showFileManagerPrompt = false
                } else {
                    Toast.makeText(context, unavailableMessage, Toast.LENGTH_LONG).show()
                }
            }
        )
        return
    }

    LaunchedEffect(request, sources) {
        delay(100.milliseconds)
        runCatching { firstSourceFocusRequester.requestFocus() }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.tv_storage_source_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(
                        if (request == TvStorageRequest.BIOS_FOLDER) {
                            R.string.onboarding_bios_desc
                        } else {
                            R.string.onboarding_games_desc
                        }
                    )
                )
                sources.forEachIndexed { index, source ->
                    val interactionSource = remember(index, source.label) { MutableInteractionSource() }
                    OutlinedButton(
                        shape = neonButtonShape(),
                        onClick = { launch(source) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == 0) {
                                    Modifier.focusRequester(firstSourceFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .gamepadFocusableCard(
                                shape = neonShape(18.dp),
                                interactionSource = interactionSource,
                                addFocusTarget = false,
                                focusHighlightMode = GamepadFocusHighlightMode.Always
                            ),
                        interactionSource = interactionSource
                    ) {
                        Text(
                            text = source.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private data class TvStorageSource(
    val label: String,
    val volume: StorageVolume?
)

@Composable
private fun FileManagerRequiredDialog(
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.tv_storage_picker_install_title)) },
        text = { Text(stringResource(R.string.tv_storage_picker_install_message)) },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.tv_storage_picker_install_confirm))
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.tv_storage_picker_install_retry))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

private object TvStorageAccess {
    fun availableSources(context: Context): List<TvStorageSource> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val mountedVolumes = storageManager?.storageVolumes.orEmpty()
            .filter { volume ->
                volume.state == Environment.MEDIA_MOUNTED ||
                    volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
            }
            .sortedWith(compareByDescending<StorageVolume> { it.isPrimary }.thenBy { it.getDescription(context) })
            .map { volume -> TvStorageSource(volume.getDescription(context), volume) }

        return listOf(TvStorageSource(context.getString(R.string.tv_storage_all_locations), null)) + mountedVolumes
    }

    fun createCompatiblePickerIntent(
        context: Context,
        request: TvStorageRequest,
        volume: StorageVolume?
    ): Intent? {
        val candidates = pickerCandidates(context, volume)

        candidates.firstOrNull { hasCompatiblePicker(context, it) }?.let { intent ->
            return intent
        }

        // Some TV variants of AnExplorer implement folder selection but accidentally omit the
        // OPEN_DOCUMENT_TREE manifest filter. Reuse its exported document activity explicitly
        // only when Android has no real tree picker of its own.
        val anExplorerActivity = findAnExplorerDocumentActivity(context)
        if (anExplorerActivity != null) {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .setComponent(anExplorerActivity)
                .withPickerFlags()
        }
        return null
    }

    /** True when at least one app can answer a folder-picking intent on this device. */
    fun isPickerAvailable(context: Context): Boolean {
        return pickerCandidates(context, volume = null).any { hasCompatiblePicker(context, it) } ||
            findAnExplorerDocumentActivity(context) != null
    }

    /**
     * Opens the Play Store listing of the file manager recommended for TV boxes without a system
     * picker. Returns false when neither the store nor a browser can handle the link.
     */
    fun openFileManagerStorePage(context: Context): Boolean {
        val storeLinks = listOf(
            "market://details?id=$ANEXPLORER_PACKAGE",
            "https://play.google.com/store/apps/details?id=$ANEXPLORER_PACKAGE"
        )
        for (link in storeLinks) {
            val intent = Intent(Intent.ACTION_VIEW, link.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                intent.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            if (!resolvable) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private fun pickerCandidates(context: Context, volume: StorageVolume?): List<Intent> {
        val baseIntent = volume
            ?.let { runCatching { createVolumeTreeIntent(it) }.getOrNull() }
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return buildList {
            // Known pickers go before the bare intent: on Android TV the framework documents stub
            // still answers OPEN_DOCUMENT_TREE, which would otherwise make the system chooser
            // appear next to the real file manager.
            knownTreePickerComponents(context).forEach { component ->
                add(Intent(baseIntent).setComponent(component))
            }
            add(baseIntent)
        }.map { intent -> intent.withPickerFlags() }
    }

    /**
     * [StorageVolume.createOpenDocumentTreeIntent] exists from API 29. Android TV
     * devices still run API 26-28, so older builds get a plain tree intent with
     * the volume root as the initial location instead of a crash.
     */
    private fun createVolumeTreeIntent(volume: StorageVolume): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return volume.createOpenDocumentTreeIntent()
        }
        val uuid = volume.uuid?.takeIf { it.isNotBlank() } ?: "primary"
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/root/${Uri.encode(uuid)}")
            )
        }
    }

    private fun Intent.withPickerFlags(): Intent = addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    )

    @Suppress("DEPRECATION")
    private fun knownTreePickerComponents(context: Context): List<ComponentName> {
        return context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val rank = KNOWN_PICKER_PACKAGES.indexOf(activityInfo.packageName)
                if (activityInfo.exported && rank >= 0) {
                    rank to ComponentName(activityInfo.packageName, activityInfo.name)
                } else {
                    null
                }
            }
            .sortedBy { (rank, _) -> rank }
            .map { (_, component) -> component }
    }

    @Suppress("DEPRECATION")
    fun hasCompatiblePicker(context: Context, intent: Intent): Boolean {
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { resolveInfo ->
                resolveInfo.activityInfo?.packageName != TV_FRAMEWORK_STUB_PACKAGE
            }
    }

    @Suppress("DEPRECATION")
    private fun findAnExplorerDocumentActivity(context: Context): ComponentName? {
        val probeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        return context.packageManager
            .queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstNotNullOfOrNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@firstNotNullOfOrNull null
                if (activityInfo.packageName == ANEXPLORER_PACKAGE && activityInfo.exported) {
                    ComponentName(activityInfo.packageName, activityInfo.name)
                } else {
                    null
                }
            }
    }

    private const val TV_FRAMEWORK_STUB_PACKAGE = "com.android.tv.frameworkpackagestubs"
    private const val ANEXPLORER_PACKAGE = "dev.dworks.apps.anexplorer"
    private val KNOWN_PICKER_PACKAGES = listOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        ANEXPLORER_PACKAGE
    )
}
