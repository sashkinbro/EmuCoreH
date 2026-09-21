package com.sbro.emucoreh.ui.settings

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import com.sbro.emucoreh.core.AudioDefaults
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.GpuHardwareProfiles
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.core.buildUpscaleOptions
import com.sbro.emucoreh.core.upscaleKeyToMultiplier
import com.sbro.emucoreh.core.upscaleMultiplierKey
import com.sbro.emucoreh.core.CoreOptionStore
import com.sbro.emucoreh.core.FlycastCoreOptions
import androidx.compose.material.icons.rounded.Tune
import com.sbro.emucoreh.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.drawable.toDrawable
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.DisplayCrop
import com.sbro.emucoreh.data.GameLibraryCacheRepository
import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.GameRepository
import com.sbro.emucoreh.data.PerGameSettings
import com.sbro.emucoreh.data.PerGameSettingsRepository
import com.sbro.emucoreh.data.RetroArchShaderPreset
import com.sbro.emucoreh.data.RetroArchShaderRepository
import com.sbro.emucoreh.data.SettingsSnapshot
import com.sbro.emucoreh.data.TouchControlPressEffect
import com.sbro.emucoreh.data.TouchControlVisualStyle
import com.sbro.emucoreh.ui.common.GameCoverArt
import com.sbro.emucoreh.ui.common.ScreenTopBar
import com.sbro.emucoreh.ui.common.appScreenTopPadding
import com.sbro.emucoreh.ui.common.SettingHelpButton
import com.sbro.emucoreh.ui.common.gamepadFocusableCard
import com.sbro.emucoreh.ui.common.tvGamepadFocusableCard
import com.sbro.emucoreh.ui.common.tvFocusGroup
import com.sbro.emucoreh.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucoreh.ui.theme.ScreenHorizontalPadding
import com.sbro.emucoreh.ui.theme.neon.LocalNeonTheme
import com.sbro.emucoreh.ui.theme.neon.NeonSystemBanner
import org.json.JSONObject
import java.text.DateFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sbro.emucoreh.ui.theme.neon.neonShape

private enum class GameSettingsManagerTab {
    Core,
    Runtime,
    Network,
    Audio,
    Controls,
}

private val SupportedGameSettingsManagerTabs = listOf(
    GameSettingsManagerTab.Core,
    GameSettingsManagerTab.Runtime,
    GameSettingsManagerTab.Network,
    GameSettingsManagerTab.Audio,
    GameSettingsManagerTab.Controls
)

private val GameSettingsSectionContentPadding = 16.dp
private const val SHADER_PRESET_USE_GLOBAL = "__emucorea_use_global_shader__"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerGameSettingsManagerScreen(
    initialGamePath: String? = null,
    onOpenControlsLayoutEditor: (GameItem) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { PerGameSettingsRepository(context) }
    val shaderRepository = remember(context) { RetroArchShaderRepository(context) }
    val preferences = remember(context) { AppPreferences(context) }
    val libraryCacheRepository = remember(context) { GameLibraryCacheRepository(context) }
    val settingsSnapshot by preferences.settingsSnapshot.collectAsState(initial = SettingsSnapshot())
    val rootPaths by preferences.gamePaths.collectAsState(initial = emptyList())
    val preferEnglishTitles by preferences.preferEnglishGameTitles.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val profileMutationMutex = remember { Mutex() }
    var profiles by remember { mutableStateOf(emptyList<PerGameSettings>()) }
    var shaderPresets by remember { mutableStateOf(emptyList<RetroArchShaderPreset>()) }
    var libraryGames by remember { mutableStateOf(emptyList<GameItem>()) }
    var selectedGamePath by rememberSaveable { mutableStateOf(initialGamePath) }
    var selectedTab by rememberSaveable { mutableStateOf(GameSettingsManagerTab.Core) }
    var isOpeningControlsEditor by remember { mutableStateOf(false) }
    var showTopBarMenu by remember { mutableStateOf(false) }
    val pendingDeleteProfile = remember { mutableStateOf<PerGameSettings?>(null) }
    val showResetAllDialog = remember { mutableStateOf(false) }
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()
    val exportSuccess = stringResource(R.string.game_settings_manager_export_success)
    val exportFailure = stringResource(R.string.game_settings_manager_export_failure)
    val importSuccess = stringResource(R.string.game_settings_manager_import_success)
    val importFailure = stringResource(R.string.game_settings_manager_import_failure)
    val controlsLayoutSaveFailure = stringResource(R.string.controls_editor_save_failed)

    fun refreshProfiles() {
        scope.launch {
            profiles = profileMutationMutex.withLock {
                withContext(Dispatchers.IO) { repository.getAll() }
            }
        }
    }

    suspend fun persistDraft(draft: PerGameSettings): List<PerGameSettings> {
        return profileMutationMutex.withLock {
            withContext(Dispatchers.IO) {
                val currentLayout = repository.get(draft.gameKey)?.touchControlsLayout
                repository.save(
                    draft.copy(
                        touchControlsLayout = currentLayout,
                        providedKeys = null
                    )
                )
                repository.getAll()
            }
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(repository) {
        profiles = withContext(Dispatchers.IO) {
            repository.getAll()
        }
    }

    LaunchedEffect(shaderRepository) {
        shaderPresets = withContext(Dispatchers.IO) {
            shaderRepository.listPresets()
        }
    }

    DisposableEffect(lifecycleOwner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOpeningControlsEditor = false
                refreshProfiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(rootPaths, preferEnglishTitles) {
        libraryGames = withContext(Dispatchers.IO) {
            rootPaths.takeIf { it.isNotEmpty() }
                ?.let { libraryCacheRepository.loadSnapshot(GameLibraryCacheRepository.libraryKey(it), preferEnglishTitles).games }
                .orEmpty()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(repository.exportJson().toString(2).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            toast(if (success) exportSuccess else exportFailure)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.use { input ->
                        JSONObject(input.readBytes().decodeToString())
                    } ?: return@runCatching false
                    repository.importJson(json)
                    true
                }.getOrDefault(false)
            }
            if (success) refreshProfiles()
            toast(if (success) importSuccess else importFailure)
        }
    }

    val availableGames = remember(libraryGames, profiles) {
        val libraryByPath = libraryGames.associateBy { it.path }
        val profileOnlyGames = profiles
            .filterNot { it.gameKey in libraryByPath }
            .map { profile ->
                GameItem(
                    path = profile.gameKey,
                    title = profile.gameTitle,
                    fileName = profile.gameTitle,
                    fileSize = 0L,
                    lastModified = profile.updatedAt,
                    serial = profile.gameSerial
                )
            }
        (libraryGames + profileOnlyGames).sortedBy { it.title.lowercase() }
    }
    val profileGameKeys = remember(profiles) {
        profiles.mapTo(HashSet()) { it.gameKey }
    }
    val selectedGame = remember(availableGames, selectedGamePath) {
        selectedGamePath?.let { path -> availableGames.firstOrNull { it.path == path } }
    }
    val selectedProfile = remember(profiles, selectedGamePath) {
        selectedGamePath?.let { path -> profiles.firstOrNull { it.gameKey == path } }
    }

    LaunchedEffect(initialGamePath, availableGames, profiles) {
        val requestedGame = initialGamePath?.let { path -> availableGames.firstOrNull { it.path == path } }
        when {
            requestedGame != null && selectedGamePath != requestedGame.path -> {
                selectedGamePath = requestedGame.path
            }
            selectedGamePath == null || availableGames.none { it.path == selectedGamePath } -> {
                selectedGamePath = profiles.firstOrNull()?.gameKey ?: availableGames.firstOrNull()?.path
            }
        }
    }

    val neonThemeActive = LocalNeonTheme.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = topInset,
            bottom = 24.dp + bottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenTopBar(
                title = stringResource(R.string.game_settings_manager_title),
                onBackClick = onBackClick,
                actions = {
                    Box {
                        IconButton(onClick = { showTopBarMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.settings_more_options),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = showTopBarMenu,
                            onDismissRequest = { showTopBarMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.game_settings_manager_export_title)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showTopBarMenu = false
                                    exportLauncher.launch("emucorea-game-settings.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.game_settings_manager_import_title)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderOpen,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showTopBarMenu = false
                                    importLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            )
                            selectedProfile?.let { profile ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showTopBarMenu = false
                                        pendingDeleteProfile.value = profile
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.game_settings_manager_reset_all_title)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Restore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showTopBarMenu = false
                                    showResetAllDialog.value = true
                                }
                            )
                        }
                    }
                }
            )
        }

        if (neonThemeActive) {
            item {
                NeonSystemBanner()
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.game_settings_manager_choose_game),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = selectedGame?.let { game ->
                            listOfNotNull(game.title, game.serial?.takeIf { it.isNotBlank() }).joinToString("  /  ")
                        } ?: stringResource(R.string.game_settings_manager_no_games),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                selectedGame?.let { game ->
                    IconButton(
                        onClick = {
                            scope.launch {
                                profiles = withContext(Dispatchers.IO) {
                                    repository.delete(game.path)
                                    repository.getAll()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Restore,
                            contentDescription = stringResource(R.string.game_settings_manager_reset_game_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        if (availableGames.isNotEmpty()) {
            item {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .gameManagerFullBleed()
                        .tvFocusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        start = ScreenHorizontalPadding,
                        end = ScreenHorizontalPadding
                    )
                ) {
                    items(availableGames, key = { it.path }) { game ->
                        GamePickerCard(
                            game = game,
                            selected = game.path == selectedGamePath,
                            hasProfile = game.path in profileGameKeys,
                            onClick = { selectedGamePath = game.path }
                        )
                    }
                }
            }

            item {
                GameSettingsManagerTabRow(
                    selectedTab = selectedTab,
                    onSelected = { selectedTab = it }
                )
            }

            selectedGame?.let { game ->
                item {
                    val storedProfile = profiles.firstOrNull { it.gameKey == game.path }
                    val defaultProfile = remember(settingsSnapshot, game) {
                        settingsSnapshot.toPerGameSettings(game)
                    }
                    val editableProfile = remember(storedProfile, defaultProfile) {
                        (storedProfile ?: defaultProfile).resolveAgainst(defaultProfile)
                    }
                    var draft by remember(game.path, editableProfile) { mutableStateOf(editableProfile) }
                    var hasUserChange by remember(game.path, editableProfile) { mutableStateOf(false) }
                    val maxUpscaleMultiplier = remember(draft.renderer) {
                        EmulatorBridge.getMaxUpscaleMultiplier(normalizeManagerRenderer(draft.renderer))
                    }

                    LaunchedEffect(draft) {
                        if (hasUserChange) {
                            profiles = persistDraft(draft)
                        } else {
                            hasUserChange = true
                        }
                    }

                    GameSettingsManagerEditorPanel(
                        game = game,
                        draft = draft,
                        defaultProfile = defaultProfile,
                        selectedTab = selectedTab,
                        maxUpscaleMultiplier = maxUpscaleMultiplier,
                        shaderPresets = shaderPresets,
                        onOpenControlsLayoutEditor = {
                            if (isOpeningControlsEditor) return@GameSettingsManagerEditorPanel
                            isOpeningControlsEditor = true
                            scope.launch {
                                val savedProfiles = runCatching { persistDraft(draft) }.getOrNull()
                                if (savedProfiles != null) {
                                    profiles = savedProfiles
                                    onOpenControlsLayoutEditor(game)
                                } else {
                                    isOpeningControlsEditor = false
                                    toast(controlsLayoutSaveFailure)
                                }
                            }
                        },
                        onDraftChange = { draft = it }
                    )
                }
            }
        } else {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = neonShape(20.dp),
                    tonalElevation = 1.dp,
                    shadowElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.game_settings_manager_empty_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.game_settings_manager_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    pendingDeleteProfile.value?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile.value = null },
            title = { Text(stringResource(R.string.game_settings_manager_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.game_settings_manager_delete_desc,
                        profile.gameTitle
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            profiles = withContext(Dispatchers.IO) {
                                repository.delete(profile.gameKey)
                                repository.getAll()
                            }
                            pendingDeleteProfile.value = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showResetAllDialog.value) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog.value = false },
            title = { Text(stringResource(R.string.game_settings_manager_reset_all_title)) },
            text = { Text(stringResource(R.string.game_settings_manager_reset_all_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            profiles = withContext(Dispatchers.IO) {
                                repository.deleteAll()
                                repository.getAll()
                            }
                            showResetAllDialog.value = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.game_settings_manager_reset_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun GamePickerCard(
    game: GameItem,
    selected: Boolean,
    hasProfile: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(280.dp)
            .height(96.dp),
        shape = neonShape(22.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.width(48.dp).aspectRatio(com.sbro.emucoreh.ui.common.GameCoverAspectRatio),
                shape = neonShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
            ) {
                GameCoverArt(
                    coverPath = game.coverArtPath,
                    fallbackTitle = game.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(neonShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                game.serial?.takeIf { it.isNotBlank() }?.let { serial ->
                    Text(
                        text = serial,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasProfile) {
                    Text(
                        text = stringResource(R.string.game_settings_manager_profile_active_short),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun GameSettingsManagerTabRow(
    selectedTab: GameSettingsManagerTab,
    onSelected: (GameSettingsManagerTab) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .gameManagerFullBleed()
            .tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding
        )
    ) {
        items(SupportedGameSettingsManagerTabs, key = { it.name }) { tab ->
            val interactionSource = remember { MutableInteractionSource() }
            FilterChip(
                modifier = Modifier.tvGamepadFocusableCard(
                    shape = neonShape(16.dp),
                    interactionSource = interactionSource,
                    addFocusTarget = false
                ),
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                interactionSource = interactionSource,
                label = {
                    Text(
                        text = when (tab) {
                            GameSettingsManagerTab.Core -> stringResource(R.string.settings_graphics_tab)
                            GameSettingsManagerTab.Runtime -> stringResource(R.string.game_settings_manager_tab_system)
                            GameSettingsManagerTab.Network -> stringResource(R.string.settings_network_tab)
                            GameSettingsManagerTab.Audio -> stringResource(R.string.settings_audio_tab)
                            GameSettingsManagerTab.Controls -> stringResource(R.string.settings_controls_tab)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun GameSettingsManagerEditorPanel(
    game: GameItem,
    draft: PerGameSettings,
    defaultProfile: PerGameSettings,
    selectedTab: GameSettingsManagerTab,
    maxUpscaleMultiplier: Int,
    shaderPresets: List<RetroArchShaderPreset>,
    onOpenControlsLayoutEditor: () -> Unit,
    onDraftChange: (PerGameSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = selectedTab.title(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.game_settings_manager_editor_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selectedTab == GameSettingsManagerTab.Controls) {
            ManagerActionButton(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.game_settings_edit_controls),
                onClick = onOpenControlsLayoutEditor
            )
            Text(
                text = stringResource(R.string.game_settings_edit_controls_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        GameSettingsTabContent(
            draft = draft,
            defaultProfile = defaultProfile,
            selectedTab = selectedTab,
            maxUpscaleMultiplier = maxUpscaleMultiplier,
            shaderPresets = shaderPresets,
            onDraftChange = onDraftChange
        )
    }
}

@Composable
private fun GameSettingsTabContent(
    draft: PerGameSettings,
    defaultProfile: PerGameSettings,
    selectedTab: GameSettingsManagerTab,
    maxUpscaleMultiplier: Int,
    shaderPresets: List<RetroArchShaderPreset>,
    onDraftChange: (PerGameSettings) -> Unit
) {
    val nativeUpscaleLabel = stringResource(R.string.settings_upscale_native)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (selectedTab) {
            GameSettingsManagerTab.Core -> {
                EditorSection(title = stringResource(R.string.game_settings_manager_section_profile)) {
                    SelectionRow(
                        title = stringResource(R.string.settings_renderer),
                        options = listOf(
                            12 to stringResource(R.string.settings_renderer_opengl),
                            14 to stringResource(R.string.settings_renderer_vulkan),
                            13 to stringResource(R.string.settings_renderer_software)
                        ),
                        selectedValue = normalizeManagerRenderer(draft.renderer),
                        onSelected = { onDraftChange(draft.copy(renderer = it)) },
                        helpText = stringResource(R.string.settings_help_renderer),
                        onResetToDefault = {
                            onDraftChange(draft.copy(renderer = normalizeManagerRenderer(defaultProfile.renderer)))
                        }
                    )
                    // Internal resolution is the app-level per-game setting
                    // (draft.upscaleMultiplier) - the same value the in-game
                    // resolution selector edits. Exposing the raw core option
                    // (swanstation_GPU_ResolutionScale) here duplicated the
                    // setting: the manager showed the catalogue default (1x)
                    // while the game ran the in-game value, and a single edit
                    // here pinned a core-option override that shadowed the
                    // app-level value at launch forever.
                    SelectionRow(
                        title = stringResource(R.string.settings_upscale),
                        options = buildUpscaleOptions(nativeUpscaleLabel, maxUpscaleMultiplier),
                        selectedValue = upscaleMultiplierKey(draft.upscaleMultiplier),
                        onSelected = { key ->
                            onDraftChange(draft.copy(upscaleMultiplier = upscaleKeyToMultiplier(key)))
                        },
                        helpText = stringResource(R.string.settings_help_upscale),
                        onResetToDefault = {
                            onDraftChange(
                                draft.copy(
                                    upscaleMultiplier = upscaleKeyToMultiplier(
                                        upscaleMultiplierKey(defaultProfile.upscaleMultiplier)
                                    )
                                )
                            )
                        }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_aspect_ratio),
                        options = listOf(
                            1 to stringResource(R.string.settings_aspect_ratio_auto),
                            2 to stringResource(R.string.settings_aspect_ratio_43),
                            3 to stringResource(R.string.settings_aspect_ratio_169),
                            4 to stringResource(R.string.settings_aspect_ratio_107),
                            0 to stringResource(R.string.emulation_aspect_stretch)
                        ),
                        selectedValue = draft.aspectRatio,
                        onSelected = { onDraftChange(draft.copy(aspectRatio = it)) },
                        helpText = stringResource(R.string.settings_help_aspect_ratio),
                        onResetToDefault = {
                            onDraftChange(draft.copy(aspectRatio = defaultProfile.aspectRatio))
                        }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_display_crop),
                        options = DisplayCrop.PRESET_PIXELS.map { it to stringResource(R.string.settings_display_crop_pixels, it) },
                        selectedValue = draft.displayCrop.presetPixels,
                        onSelected = { onDraftChange(draft.copy(displayCrop = DisplayCrop.uniform(it))) },
                        helpText = stringResource(R.string.settings_display_crop_desc),
                        onResetToDefault = { onDraftChange(draft.copy(displayCrop = defaultProfile.displayCrop)) }
                    )
                    CoreOptionManagerRows(
                        options = FlycastCoreOptions.videoOptions(),
                        draft = draft,
                        onDraftChange = onDraftChange
                    )
                    ShaderPresetSelector(
                        title = stringResource(R.string.settings_shader_preset),
                        presets = shaderPresets,
                        selectedPath = when (draft.shaderChainOverrideEnabled) {
                            null -> SHADER_PRESET_USE_GLOBAL
                            false -> ""
                            true -> draft.shaderChainPreset
                        },
                        onSelect = { selectedPath ->
                            onDraftChange(
                                when (selectedPath) {
                                    SHADER_PRESET_USE_GLOBAL -> draft.copy(
                                        shaderChainOverrideEnabled = null,
                                        shaderChainPreset = ""
                                    )
                                    "" -> draft.copy(
                                        shaderChainOverrideEnabled = false,
                                        shaderChainPreset = ""
                                    )
                                    else -> draft.copy(
                                        shaderChainOverrideEnabled = true,
                                        shaderChainPreset = selectedPath
                                    )
                                }
                            )
                        },
                        helpText = stringResource(R.string.settings_help_shader_preset),
                        leadingOptions = listOf(
                            SHADER_PRESET_USE_GLOBAL to stringResource(R.string.controls_editor_global_scope)
                        ),
                        cardHorizontalPadding = 0.dp
                    )
                }


            }
            GameSettingsManagerTab.Runtime -> {
                EditorSection(title = stringResource(R.string.game_settings_manager_section_runtime)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_frame_limit),
                        checked = draft.frameLimitEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(frameLimitEnabled = it)) },
                        helpText = stringResource(R.string.settings_frame_limit_desc),
                        onResetToDefault = { onDraftChange(draft.copy(frameLimitEnabled = defaultProfile.frameLimitEnabled)) }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_target_fps),
                        options = listOf(
                            0 to stringResource(R.string.settings_aspect_ratio_auto),
                            50 to "50 Hz",
                            60 to "60 Hz"
                        ),
                        selectedValue = draft.targetFps,
                        onSelected = { onDraftChange(draft.copy(targetFps = it)) },
                        helpText = stringResource(R.string.settings_target_fps_desc),
                        onResetToDefault = { onDraftChange(draft.copy(targetFps = defaultProfile.targetFps)) }
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_show_fps),
                        checked = draft.showFps,
                        onCheckedChange = { onDraftChange(draft.copy(showFps = it)) },
                        helpText = stringResource(R.string.settings_help_show_fps),
                        onResetToDefault = { onDraftChange(draft.copy(showFps = defaultProfile.showFps)) }
                    )
                    ToggleRow(
                        title = stringResource(R.string.emulation_auto_save_on_exit),
                        checked = draft.autoSaveOnExit,
                        onCheckedChange = { onDraftChange(draft.copy(autoSaveOnExit = it)) },
                        helpText = stringResource(R.string.emulation_auto_save_on_exit_desc),
                        onResetToDefault = {
                            onDraftChange(draft.copy(autoSaveOnExit = defaultProfile.autoSaveOnExit))
                        }
                    )
                    ToggleRow(
                        title = stringResource(R.string.emulation_auto_load_on_start),
                        checked = draft.autoLoadOnStart,
                        onCheckedChange = { onDraftChange(draft.copy(autoLoadOnStart = it)) },
                        helpText = stringResource(R.string.emulation_auto_load_on_start_desc),
                        onResetToDefault = {
                            onDraftChange(draft.copy(autoLoadOnStart = defaultProfile.autoLoadOnStart))
                        }
                    )
                }

                EditorSection(title = stringResource(R.string.settings_core_cpu)) {
                    CoreOptionManagerRows(
                        options = FlycastCoreOptions.systemOptions(),
                        draft = draft,
                        onDraftChange = onDraftChange
                    )
                }

            }
            GameSettingsManagerTab.Network -> {
                EditorSection(title = stringResource(R.string.settings_network_tab)) {
                    CoreOptionManagerRows(
                        options = FlycastCoreOptions.networkOptions(),
                        draft = draft,
                        onDraftChange = onDraftChange
                    )
                }
            }
            GameSettingsManagerTab.Audio -> {
                EditorSection(title = stringResource(R.string.settings_audio_control)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_audio_mute),
                        checked = draft.audioMuted,
                        onCheckedChange = { onDraftChange(draft.copy(audioMuted = it)) },
                        helpText = stringResource(R.string.settings_help_audio_mute),
                        onResetToDefault = {
                            onDraftChange(draft.copy(audioMuted = defaultProfile.audioMuted))
                        }
                    )
                    SliderRow(
                        title = stringResource(R.string.settings_audio_volume),
                        value = draft.audioVolume.toFloat(),
                        valueLabel = "${draft.audioVolume}%",
                        range = AudioDefaults.VOLUME_MIN.toFloat()..AudioDefaults.VOLUME_MAX.toFloat(),
                        steps = 0,
                        onValueChange = { onDraftChange(draft.copy(audioVolume = it.roundToInt())) },
                        helpText = stringResource(R.string.settings_help_audio_volume),
                        onResetToDefault = {
                            onDraftChange(draft.copy(audioVolume = defaultProfile.audioVolume))
                        }
                    )
                }
                EditorSection(title = stringResource(R.string.settings_audio_output)) {
                    SliderRow(
                        title = stringResource(R.string.settings_audio_output_latency),
                        value = draft.audioOutputLatencyMs.toFloat(),
                        valueLabel = "${draft.audioOutputLatencyMs} ms",
                        range = AudioDefaults.OUTPUT_LATENCY_MS_MIN.toFloat()..
                            AudioDefaults.OUTPUT_LATENCY_MS_MAX.toFloat(),
                        steps = 0,
                        onValueChange = { onDraftChange(draft.copy(audioOutputLatencyMs = it.roundToInt())) },
                        helpText = stringResource(R.string.settings_help_audio_output_latency),
                        onResetToDefault = {
                            onDraftChange(
                                draft.copy(audioOutputLatencyMs = defaultProfile.audioOutputLatencyMs)
                            )
                        }
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_audio_minimal_latency),
                        checked = draft.audioMinimalOutputLatency,
                        onCheckedChange = { onDraftChange(draft.copy(audioMinimalOutputLatency = it)) },
                        helpText = stringResource(R.string.settings_help_audio_minimal_latency),
                        onResetToDefault = {
                            onDraftChange(
                                draft.copy(
                                    audioMinimalOutputLatency = defaultProfile.audioMinimalOutputLatency
                                )
                            )
                        }
                    )
                }
            }
            GameSettingsManagerTab.Controls -> {
                EditorSection(title = stringResource(R.string.settings_customization_touch_controls_section)) {
                    SelectionRow(
                        title = stringResource(R.string.settings_customization_touch_controls_style),
                        options = listOf(
                            -1 to stringResource(R.string.settings_use_global),
                            TouchControlVisualStyle.CLASSIC.preferenceValue to stringResource(R.string.settings_customization_touch_style_classic),
                            TouchControlVisualStyle.LEGACY.preferenceValue to stringResource(R.string.settings_customization_touch_style_glass),
                            TouchControlVisualStyle.MODERN.preferenceValue to stringResource(R.string.settings_customization_touch_style_neon),
                            TouchControlVisualStyle.ARCADE.preferenceValue to stringResource(R.string.settings_customization_touch_style_arcade),
                            TouchControlVisualStyle.MINIMAL.preferenceValue to stringResource(R.string.settings_customization_touch_style_minimal)
                        ),
                        selectedValue = draft.touchControlVisualStyle?.preferenceValue ?: -1,
                        onSelected = { value ->
                            onDraftChange(
                                draft.copy(
                                    touchControlVisualStyle = value.takeIf { it >= 0 }
                                        ?.let { TouchControlVisualStyle.fromPreference(it) }
                                )
                            )
                        },
                        helpText = stringResource(R.string.settings_customization_touch_controls_help),
                        onResetToDefault = { onDraftChange(draft.copy(touchControlVisualStyle = null)) }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_customization_touch_press_effect),
                        options = listOf(
                            -1 to stringResource(R.string.settings_use_global),
                            TouchControlPressEffect.GROW.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_grow),
                            TouchControlPressEffect.SHRINK.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_shrink),
                            TouchControlPressEffect.SPRING.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_spring),
                            TouchControlPressEffect.GLOW.preferenceValue to stringResource(R.string.settings_customization_touch_press_effect_glow)
                        ),
                        selectedValue = draft.touchControlPressEffect?.preferenceValue ?: -1,
                        onSelected = { value ->
                            onDraftChange(
                                draft.copy(
                                    touchControlPressEffect = value.takeIf { it >= 0 }
                                        ?.let { TouchControlPressEffect.fromPreference(it) }
                                )
                            )
                        },
                        helpText = stringResource(R.string.settings_customization_touch_press_effect_help),
                        onResetToDefault = { onDraftChange(draft.copy(touchControlPressEffect = null)) }
                    )
                }
                EditorSection(title = stringResource(R.string.settings_controls_tab)) {
                    ToggleRow(
                        title = stringResource(R.string.settings_racing_mode),
                        checked = draft.racingMode,
                        onCheckedChange = { onDraftChange(draft.copy(racingMode = it)) },
                        helpText = stringResource(R.string.settings_help_racing_mode),
                        onResetToDefault = { onDraftChange(draft.copy(racingMode = defaultProfile.racingMode)) }
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_touchscreen_right_stick),
                        checked = draft.touchscreenRightStick,
                        onCheckedChange = { onDraftChange(draft.copy(touchscreenRightStick = it)) },
                        helpText = stringResource(R.string.settings_help_touchscreen_right_stick),
                        onResetToDefault = { onDraftChange(draft.copy(touchscreenRightStick = defaultProfile.touchscreenRightStick)) }
                    )
                    if (draft.touchscreenRightStick) {
                        SliderRow(
                            title = stringResource(R.string.settings_touchscreen_right_stick_sensitivity),
                            value = draft.touchscreenRightStickSensitivity.toFloat(),
                            valueLabel = "${draft.touchscreenRightStickSensitivity}%",
                            range = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN.toFloat()..
                                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX.toFloat(),
                            steps = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX -
                                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN - 1,
                            onValueChange = {
                                onDraftChange(draft.copy(touchscreenRightStickSensitivity = it.roundToInt()))
                            },
                            helpText = stringResource(R.string.settings_help_touchscreen_right_stick_sensitivity),
                            onResetToDefault = {
                                onDraftChange(
                                    draft.copy(
                                        touchscreenRightStickSensitivity = defaultProfile.touchscreenRightStickSensitivity
                                    )
                                )
                            }
                        )
                    }
                    ToggleRow(
                        title = stringResource(R.string.settings_touch_haptics),
                        checked = draft.touchHaptics,
                        onCheckedChange = { onDraftChange(draft.copy(touchHaptics = it)) },
                        helpText = stringResource(R.string.settings_help_touch_haptics),
                        onResetToDefault = { onDraftChange(draft.copy(touchHaptics = defaultProfile.touchHaptics)) }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_touch_haptics_preset),
                        options = touchHapticsPresetOptions(),
                        selectedValue = draft.touchHapticsPreset,
                        onSelected = { onDraftChange(draft.copy(touchHapticsPreset = it)) },
                        helpText = stringResource(R.string.settings_help_touch_haptics_preset),
                        onResetToDefault = { onDraftChange(draft.copy(touchHapticsPreset = defaultProfile.touchHapticsPreset)) }
                    )
                    SelectionRow(
                        title = stringResource(R.string.settings_gyro_mode),
                        options = gyroModeOptions(),
                        selectedValue = draft.gyroMode,
                        onSelected = { onDraftChange(draft.copy(gyroMode = it)) },
                        helpText = stringResource(R.string.settings_help_gyro_mode),
                        onResetToDefault = { onDraftChange(draft.copy(gyroMode = defaultProfile.gyroMode)) }
                    )
                    if (draft.gyroMode != AppPreferences.GYRO_MODE_OFF) {
                        SliderRow(stringResource(R.string.settings_gyro_sensitivity), draft.gyroSensitivity.toFloat(), "${draft.gyroSensitivity}%", 25f..300f, 10, { onDraftChange(draft.copy(gyroSensitivity = it.roundToInt())) }, helpText = stringResource(R.string.settings_help_gyro_sensitivity), onResetToDefault = { onDraftChange(draft.copy(gyroSensitivity = defaultProfile.gyroSensitivity)) })
                        SliderRow(stringResource(R.string.settings_gyro_smoothing), draft.gyroSmoothing.toFloat(), "${draft.gyroSmoothing}%", 0f..90f, 8, { onDraftChange(draft.copy(gyroSmoothing = it.roundToInt())) }, helpText = stringResource(R.string.settings_help_gyro_smoothing), onResetToDefault = { onDraftChange(draft.copy(gyroSmoothing = defaultProfile.gyroSmoothing)) })
                        ToggleRow(stringResource(R.string.settings_gyro_invert_x), draft.gyroInvertX, { onDraftChange(draft.copy(gyroInvertX = it)) }, helpText = stringResource(R.string.settings_help_gyro_invert_x), onResetToDefault = { onDraftChange(draft.copy(gyroInvertX = defaultProfile.gyroInvertX)) })
                        if (draft.gyroMode == AppPreferences.GYRO_MODE_AIM) {
                            ToggleRow(stringResource(R.string.settings_gyro_invert_y), draft.gyroInvertY, { onDraftChange(draft.copy(gyroInvertY = it)) }, helpText = stringResource(R.string.settings_help_gyro_invert_y), onResetToDefault = { onDraftChange(draft.copy(gyroInvertY = defaultProfile.gyroInvertY)) })
                        }
                    }
                    ToggleRow(
                        title = stringResource(R.string.settings_gamepad_button_haptics),
                        checked = draft.gamepadButtonHaptics,
                        onCheckedChange = { onDraftChange(draft.copy(gamepadButtonHaptics = it)) },
                        helpText = stringResource(R.string.settings_help_gamepad_button_haptics),
                        onResetToDefault = { onDraftChange(draft.copy(gamepadButtonHaptics = defaultProfile.gamepadButtonHaptics)) }
                    )
                    SliderRow(
                        title = stringResource(R.string.settings_gamepad_stick_deadzone),
                        value = draft.gamepadStickDeadzone.toFloat(),
                        valueLabel = "${draft.gamepadStickDeadzone}%",
                        range = 0f..35f,
                        steps = 34,
                        onValueChange = { onDraftChange(draft.copy(gamepadStickDeadzone = it.roundToInt())) },
                        helpText = stringResource(R.string.settings_help_gamepad_stick_deadzone),
                        onResetToDefault = { onDraftChange(draft.copy(gamepadStickDeadzone = defaultProfile.gamepadStickDeadzone)) }
                    )
                    SliderRow(
                        title = stringResource(R.string.settings_gamepad_left_stick_sensitivity),
                        value = draft.gamepadLeftStickSensitivity.toFloat(),
                        valueLabel = "${draft.gamepadLeftStickSensitivity}%",
                        range = 50f..200f,
                        steps = 149,
                        onValueChange = { onDraftChange(draft.copy(gamepadLeftStickSensitivity = it.roundToInt())) },
                        helpText = stringResource(R.string.settings_help_gamepad_left_stick_sensitivity),
                        onResetToDefault = { onDraftChange(draft.copy(gamepadLeftStickSensitivity = defaultProfile.gamepadLeftStickSensitivity)) }
                    )
                    SliderRow(
                        title = stringResource(R.string.settings_gamepad_right_stick_sensitivity),
                        value = draft.gamepadRightStickSensitivity.toFloat(),
                        valueLabel = "${draft.gamepadRightStickSensitivity}%",
                        range = 50f..200f,
                        steps = 149,
                        onValueChange = { onDraftChange(draft.copy(gamepadRightStickSensitivity = it.roundToInt())) },
                        helpText = stringResource(R.string.settings_help_gamepad_right_stick_sensitivity),
                        onResetToDefault = { onDraftChange(draft.copy(gamepadRightStickSensitivity = defaultProfile.gamepadRightStickSensitivity)) }
                    )
                }

                EditorSection(title = stringResource(R.string.settings_core_input)) {
                    CoreOptionManagerRows(
                        options = FlycastCoreOptions.inputOptions(),
                        draft = draft,
                        onDraftChange = onDraftChange
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = neonShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(GameSettingsSectionContentPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun CoreOptionManagerRows(
    options: List<FlycastCoreOptions.Option>,
    draft: PerGameSettings,
    onDraftChange: (PerGameSettings) -> Unit
) {
    options.forEach { option ->
        val values = option.choices.map { it.value }
        val current = draft.coreOptions[option.key]
            ?: CoreOptionStore.value(option.key)
            ?: option.defaultValue
        val index = values.indexOf(current).let { if (it >= 0) it else 0 }
        val title = stringResource(option.labelRes)
        val help = stringResource(option.descriptionRes).takeIf { it.isNotBlank() }
        if (option.isBooleanToggle) {
            ToggleRow(
                title = title,
                checked = option.isEnabled(current),
                onCheckedChange = { enabled ->
                    onDraftChange(draft.copy(coreOptions = draft.coreOptions + (option.key to option.valueForEnabled(enabled))))
                },
                helpText = help,
                onResetToDefault = {
                    onDraftChange(draft.copy(coreOptions = draft.coreOptions - option.key))
                }
            )
        } else {
            SelectionRow(
                title = title,
                options = option.choices.mapIndexed { choiceIndex, choice ->
                    choiceIndex to stringResource(choice.labelRes)
                },
                selectedValue = index,
                onSelected = { selected ->
                    values.getOrNull(selected)?.let { value ->
                        onDraftChange(draft.copy(coreOptions = draft.coreOptions + (option.key to value)))
                    }
                },
                helpText = help,
                onResetToDefault = {
                    onDraftChange(draft.copy(coreOptions = draft.coreOptions - option.key))
                }
            )
        }
    }
}

@Composable
private fun SelectionRow(
    title: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    onSelected: (Int) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
                onLongClick = onResetToDefault?.let {
                    {
                        it()
                        Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        if (options.size > 3) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .sectionContentFullBleed(GameSettingsSectionContentPadding)
                    .tvFocusGroup(),
                contentPadding = PaddingValues(horizontal = GameSettingsSectionContentPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(options, key = { it.first }) { (value, label) ->
                    val optionInteractionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        modifier = Modifier.tvGamepadFocusableCard(
                            shape = neonShape(16.dp),
                            interactionSource = optionInteractionSource,
                            addFocusTarget = false
                        ),
                        selected = selectedValue == value,
                        onClick = { onSelected(value) },
                        interactionSource = optionInteractionSource,
                        label = { Text(label) }
                    )
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (value, label) ->
                    val optionInteractionSource = remember { MutableInteractionSource() }
                    FilterChip(
                        modifier = Modifier.tvGamepadFocusableCard(
                            shape = neonShape(16.dp),
                            interactionSource = optionInteractionSource,
                            addFocusTarget = false
                        ),
                        selected = selectedValue == value,
                        onClick = { onSelected(value) },
                        interactionSource = optionInteractionSource,
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)
    val shape = neonShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) },
                onLongClick = onResetToDefault?.let {
                    {
                        it()
                        Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            .gamepadFocusableCard(
                shape = shape,
                interactionSource = interactionSource,
                addFocusTarget = false
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f, fill = false),
                color = MaterialTheme.colorScheme.onSurface
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueLabelForValue: ((Float) -> String)? = null,
    helpText: String? = null,
    onResetToDefault: (() -> Unit)? = null
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val resetToast = stringResource(R.string.settings_reset_to_default_toast)

    LaunchedEffect(value) {
        sliderValue = value
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onResetToDefault?.let {
                        {
                            it()
                            Toast.makeText(context, resetToast, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurface
                )
                helpText?.let {
                    SettingHelpButton(title = title, description = it)
                }
            }
            Text(
                text = valueLabelForValue?.invoke(sliderValue) ?: valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onValueChange(it)
            },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
@Suppress("unused")
private fun ManagerActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = neonShape(16.dp),
        color = if (destructive) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (destructive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            }
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun Modifier.gameManagerFullBleed(): Modifier {
    return layout { measurable, constraints ->
        val sidePadding = ScreenHorizontalPadding.roundToPx()
        val expandedConstraints = constraints.copy(
            minWidth = (constraints.minWidth + sidePadding * 2).coerceAtLeast(0),
            maxWidth = (constraints.maxWidth + sidePadding * 2).coerceAtLeast(0)
        )
        val placeable = measurable.measure(expandedConstraints)
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-sidePadding, 0)
        }
    }
}

private fun Modifier.sectionContentFullBleed(horizontalPadding: Dp): Modifier {
    return layout { measurable, constraints ->
        val sidePadding = horizontalPadding.roundToPx()
        val expandedConstraints = constraints.copy(
            minWidth = (constraints.minWidth + sidePadding * 2).coerceAtLeast(0),
            maxWidth = (constraints.maxWidth + sidePadding * 2).coerceAtLeast(0)
        )
        val placeable = measurable.measure(expandedConstraints)
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-sidePadding, 0)
        }
    }
}

@Composable
private fun GameSettingsManagerTab.title(): String = when (this) {
    GameSettingsManagerTab.Core -> stringResource(R.string.settings_graphics_tab)
    GameSettingsManagerTab.Runtime -> stringResource(R.string.game_settings_manager_tab_system)
    GameSettingsManagerTab.Network -> stringResource(R.string.settings_network_tab)
    GameSettingsManagerTab.Audio -> stringResource(R.string.settings_audio_tab)
    GameSettingsManagerTab.Controls -> stringResource(R.string.settings_controls_tab)
}

@Composable
private fun touchHapticsPresetOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.TOUCH_HAPTICS_PRESET_SOFT to stringResource(R.string.settings_touch_haptics_preset_soft),
    AppPreferences.TOUCH_HAPTICS_PRESET_BALANCED to stringResource(R.string.settings_touch_haptics_preset_balanced),
    AppPreferences.TOUCH_HAPTICS_PRESET_CRISP to stringResource(R.string.settings_touch_haptics_preset_crisp),
    AppPreferences.TOUCH_HAPTICS_PRESET_STRONG to stringResource(R.string.settings_touch_haptics_preset_strong)
)

@Composable
private fun gyroModeOptions(): List<Pair<Int, String>> = listOf(
    AppPreferences.GYRO_MODE_OFF to stringResource(R.string.settings_gyro_off),
    AppPreferences.GYRO_MODE_AIM to stringResource(R.string.settings_gyro_aim),
    AppPreferences.GYRO_MODE_STEERING to stringResource(R.string.settings_gyro_steering)
)

private fun normalizeManagerRenderer(renderer: Int): Int {
    return RendererDefaults.normalizeAndroidRenderer(renderer)
}

private fun SettingsSnapshot.toPerGameSettings(game: GameItem): PerGameSettings {
    return PerGameSettings(
        gameKey = game.path,
        gameTitle = game.title,
        gameSerial = game.serial,
        renderer = renderer,
        gpuDriverType = gpuDriverType,
        customDriverPath = customDriverPath,
        mediatekAngleOpenGl = mediatekAngleOpenGl,
        upscaleMultiplier = upscaleMultiplier,
        aspectRatio = aspectRatio,
        localMultiplayerMode = localMultiplayerMode,
        displayCrop = displayCrop,
        showFps = showFps,
        fpsOverlayMode = fpsOverlayMode,
        racingMode = racingMode,
        touchscreenRightStick = touchscreenRightStick,
        touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
        touchHaptics = touchHaptics,
        touchHapticsPreset = touchHapticsPreset,
        gyroMode = gyroMode,
        gyroSensitivity = gyroSensitivity,
        gyroSmoothing = gyroSmoothing,
        gyroInvertX = gyroInvertX,
        gyroInvertY = gyroInvertY,
        gamepadRightStickUpToR2 = gamepadRightStickUpToR2,
        gamepadRightStickDownToL2 = gamepadRightStickDownToL2,
        gamepadButtonHaptics = gamepadButtonHaptics,
        pressureModifierAmount = pressureModifierAmount,
        audioVolume = audioVolume,
        audioMuted = audioMuted,
        audioOutputLatencyMs = audioOutputLatencyMs,
        audioMinimalOutputLatency = audioMinimalOutputLatency,
        autoSaveOnExit = false,
        autoLoadOnStart = false,
        frameLimitEnabled = frameLimitEnabled,
        targetFps = targetFps,
        ntscFramerate = ntscFramerate,
        palFramerate = palFramerate,
    )
}

private fun PerGameSettings.resolveAgainst(defaultProfile: PerGameSettings): PerGameSettings {
    val keys = providedKeys ?: return copy(providedKeys = null)
    fun <T> pick(key: String, current: T, fallback: T): T = if (key in keys) current else fallback
    return defaultProfile.copy(
        gameKey = gameKey,
        gameTitle = gameTitle,
        gameSerial = gameSerial,
        renderer = pick("renderer", renderer, defaultProfile.renderer),
        gpuDriverType = pick("gpuDriverType", gpuDriverType, defaultProfile.gpuDriverType),
        customDriverPath = pick("customDriverPath", customDriverPath, defaultProfile.customDriverPath),
        mediatekAngleOpenGl = pick("mediatekAngleOpenGl", mediatekAngleOpenGl, defaultProfile.mediatekAngleOpenGl),
        upscaleMultiplier = pick("upscaleMultiplier", upscaleMultiplier, defaultProfile.upscaleMultiplier),
        aspectRatio = pick("aspectRatio", aspectRatio, defaultProfile.aspectRatio),
        localMultiplayerMode = pick(
            "localMultiplayerMode",
            localMultiplayerMode,
            defaultProfile.localMultiplayerMode
        ),
        displayCrop = pick("displayCrop", displayCrop, defaultProfile.displayCrop),
        showFps = pick("showFps", showFps, defaultProfile.showFps),
        fpsOverlayMode = pick("fpsOverlayMode", fpsOverlayMode, defaultProfile.fpsOverlayMode),
        racingMode = pick("racingMode", racingMode, defaultProfile.racingMode),
        touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick, defaultProfile.touchscreenRightStick),
        touchscreenRightStickSensitivity = pick(
            "touchscreenRightStickSensitivity",
            touchscreenRightStickSensitivity,
            defaultProfile.touchscreenRightStickSensitivity
        ),
        touchHaptics = pick("touchHaptics", touchHaptics, defaultProfile.touchHaptics),
        touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset, defaultProfile.touchHapticsPreset),
        gyroMode = pick("gyroMode", gyroMode, defaultProfile.gyroMode),
        gyroSensitivity = pick("gyroSensitivity", gyroSensitivity, defaultProfile.gyroSensitivity),
        gyroSmoothing = pick("gyroSmoothing", gyroSmoothing, defaultProfile.gyroSmoothing),
        gyroInvertX = pick("gyroInvertX", gyroInvertX, defaultProfile.gyroInvertX),
        gyroInvertY = pick("gyroInvertY", gyroInvertY, defaultProfile.gyroInvertY),
        gamepadRightStickUpToR2 = pick("gamepadRightStickUpToR2", gamepadRightStickUpToR2, defaultProfile.gamepadRightStickUpToR2),
        gamepadRightStickDownToL2 = pick("gamepadRightStickDownToL2", gamepadRightStickDownToL2, defaultProfile.gamepadRightStickDownToL2),
        gamepadButtonHaptics = pick("gamepadButtonHaptics", gamepadButtonHaptics, defaultProfile.gamepadButtonHaptics),
        pressureModifierAmount = pick("pressureModifierAmount", pressureModifierAmount, defaultProfile.pressureModifierAmount),
        autoSaveOnExit = pick("autoSaveOnExit", autoSaveOnExit, defaultProfile.autoSaveOnExit),
        autoLoadOnStart = pick("autoLoadOnStart", autoLoadOnStart, defaultProfile.autoLoadOnStart),
        frameLimitEnabled = pick("frameLimitEnabled", frameLimitEnabled, defaultProfile.frameLimitEnabled),
        targetFps = pick("targetFps", targetFps, defaultProfile.targetFps),
        ntscFramerate = pick("ntscFramerate", ntscFramerate, defaultProfile.ntscFramerate),
        palFramerate = pick("palFramerate", palFramerate, defaultProfile.palFramerate),
        shaderChainOverrideEnabled = shaderChainOverrideEnabled,
        shaderChainPreset = shaderChainPreset,
        audioVolume = pick("audioVolume", audioVolume, defaultProfile.audioVolume),
        audioMuted = pick("audioMuted", audioMuted, defaultProfile.audioMuted),
        audioOutputLatencyMs = pick("audioOutputLatencyMs", audioOutputLatencyMs, defaultProfile.audioOutputLatencyMs),
        audioMinimalOutputLatency = pick(
            "audioMinimalOutputLatency",
            audioMinimalOutputLatency,
            defaultProfile.audioMinimalOutputLatency
        ),
        coreOptions = coreOptions,
        touchControlVisualStyle = pick("touchControlVisualStyle", touchControlVisualStyle, defaultProfile.touchControlVisualStyle),
        touchControlPressEffect = pick("touchControlPressEffect", touchControlPressEffect, defaultProfile.touchControlPressEffect),
        providedKeys = null,
        updatedAt = updatedAt
    )
}
