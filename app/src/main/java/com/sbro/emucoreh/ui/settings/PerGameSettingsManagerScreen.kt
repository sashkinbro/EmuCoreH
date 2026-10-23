package com.sbro.emucoreh.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import com.sbro.emucoreh.core.AudioDefaults
import com.sbro.emucoreh.core.AndroidGyroscopeInput
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.RendererDefaults
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.buildUpscaleOptions
import com.sbro.emucoreh.core.upscaleKeyToMultiplier
import com.sbro.emucoreh.core.upscaleMultiplierKey
import com.sbro.emucoreh.core.CoreOptionStore
import com.sbro.emucoreh.core.FlycastCoreOptions
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.GamepadManager
import androidx.compose.material.icons.rounded.Tune
import com.sbro.emucoreh.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.DisplayCrop
import com.sbro.emucoreh.data.KeyboardMouseSettings
import com.sbro.emucoreh.data.GameLibraryCacheRepository
import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.GamepadBindingRules
import com.sbro.emucoreh.data.flattenLibraryGroups
import com.sbro.emucoreh.data.PerGameSettings
import com.sbro.emucoreh.data.PerGameSettingsRepository
import com.sbro.emucoreh.data.RetroArchShaderPreset
import com.sbro.emucoreh.data.RetroArchShaderRepository
import com.sbro.emucoreh.data.SettingsSnapshot
import com.sbro.emucoreh.data.TouchControlPressEffect
import com.sbro.emucoreh.data.TouchControlVisualStyle
import androidx.compose.ui.graphics.Color
import com.sbro.emucoreh.ui.common.GameCoverArt
import com.sbro.emucoreh.ui.common.isGenerated3dCover
import com.sbro.emucoreh.ui.common.ScreenTopBar
import com.sbro.emucoreh.ui.common.gameCoverContentScale
import com.sbro.emucoreh.ui.common.gameCoverShape
import com.sbro.emucoreh.ui.common.gamepadActionLabel
import com.sbro.emucoreh.ui.common.gamepadActionSupported
import com.sbro.emucoreh.ui.common.appScreenTopPadding
import com.sbro.emucoreh.ui.common.SettingHelpButton
import com.sbro.emucoreh.ui.common.SettingsStyledDialog
import com.sbro.emucoreh.ui.common.StickyTabHeader
import com.sbro.emucoreh.ui.common.gamepadFocusableCard
import com.sbro.emucoreh.ui.common.premiumFilterChipColors
import com.sbro.emucoreh.ui.common.rememberStickyTabHeaderPinned
import com.sbro.emucoreh.ui.common.stickyTabTopInset
import com.sbro.emucoreh.ui.common.tvGamepadFocusableCard
import com.sbro.emucoreh.ui.common.tvFocusGroup
import com.sbro.emucoreh.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucoreh.ui.theme.ScreenHorizontalPadding
import com.sbro.emucoreh.ui.theme.neon.LocalNeonTheme
import com.sbro.emucoreh.ui.theme.neon.NeonSystemBanner
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sbro.emucoreh.ui.theme.neon.neonChipShape
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
    val listState = rememberLazyListState()
    val stickyTabInset = stickyTabTopInset()
    val tabsPinned = rememberStickyTabHeaderPinned(listState, "game-settings-tabs")
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
            val readableRoots = rootPaths.filter { SetupValidator.hasCoreReadableGameFile(context, it) }
            readableRoots.takeIf { it.isNotEmpty() }
                ?.let {
                    flattenLibraryGroups(
                        libraryCacheRepository.loadSnapshot(GameLibraryCacheRepository.libraryKey(it), preferEnglishTitles).games
                    )
                }
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

    val platform = rememberGamePlatform(selectedGame?.path, selectedGame?.platform)
    val supportedTabs = if (platform.isSega) SupportedGameSettingsManagerTabs.filter { it != GameSettingsManagerTab.Network } else SupportedGameSettingsManagerTabs
    LaunchedEffect(platform) { if (selectedTab !in supportedTabs) selectedTab = GameSettingsManagerTab.Core }
    val neonThemeActive = LocalNeonTheme.current
    LaunchedEffect(selectedTab) {
        listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontalSystemBarPadding)
            .padding(top = stickyTabInset),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            bottom = 24.dp + bottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenTopBar(
                title = stringResource(R.string.game_settings_manager_title),
                onBackClick = onBackClick,
                modifier = Modifier.padding(
                    top = (topInset - stickyTabInset).coerceAtLeast(0.dp)
                ),
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

            stickyHeader(key = "game-settings-tabs") {
                StickyTabHeader(
                    showDivider = tabsPinned,
                    modifier = Modifier.gameManagerFullBleed()
                ) {
                    GameSettingsManagerTabRow(
                        tabs = supportedTabs,
                        selectedTab = selectedTab,
                        onSelected = { selectedTab = it }
                    )
                }
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
    val is3d = isGenerated3dCover(game.coverArtPath)
    Surface(
        modifier = Modifier
            .width(286.dp)
            .height(106.dp),
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
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                shape = gameCoverShape(game.coverArtPath, neonShape(12.dp)),
                color = if (is3d) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
                }
            ) {
                GameCoverArt(
                    coverPath = game.coverArtPath,
                    fallbackTitle = game.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(gameCoverShape(game.coverArtPath, neonShape(12.dp))),
                    contentScale = gameCoverContentScale(game.coverArtPath, ContentScale.Fit)
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
    tabs: List<GameSettingsManagerTab>,
    selectedTab: GameSettingsManagerTab,
    onSelected: (GameSettingsManagerTab) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding
        )
    ) {
        items(tabs, key = { it.name }) { tab ->
            val interactionSource = remember { MutableInteractionSource() }
            FilterChip(
                modifier = Modifier.tvGamepadFocusableCard(
                    shape = neonShape(16.dp),
                    interactionSource = interactionSource,
                    addFocusTarget = false
                ),
                shape = neonChipShape(),
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                interactionSource = interactionSource,
                colors = premiumFilterChipColors(),
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
    val platform = rememberGamePlatform(game.path, game.platform)
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
            platform = platform,
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
    platform: EmulationPlatform,
    draft: PerGameSettings,
    defaultProfile: PerGameSettings,
    selectedTab: GameSettingsManagerTab,
    maxUpscaleMultiplier: Int,
    shaderPresets: List<RetroArchShaderPreset>,
    onDraftChange: (PerGameSettings) -> Unit
) {
    val nativeUpscaleLabel = stringResource(R.string.settings_upscale_native)
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val globalKeyboardMouseSettings by preferences.keyboardMouseSettings.collectAsState(
        initial = KeyboardMouseSettings()
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (selectedTab) {
            GameSettingsManagerTab.Core -> {
                EditorSection(title = stringResource(R.string.game_settings_manager_section_profile)) {
                    if (!platform.isSega) {
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
                    }
                    // Internal resolution is the app-level per-game setting
                    // (draft.upscaleMultiplier) - the same value the in-game
                    // resolution selector edits. Exposing the raw core option
                    // (reicast_internal_resolution) here duplicated the
                    // setting: the manager showed the catalogue default (1x)
                    // while the game ran the in-game value, and a single edit
                    // here pinned a core-option override that shadowed the
                    // app-level value at launch forever.
                    if (!platform.isSega) {
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
                    }
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
                        options = if (platform.isSega) com.sbro.emucoreh.core.SegaCoreOptions.options(platform).filter { it.category == "video" } else FlycastCoreOptions.videoOptions(),
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
                    if (!platform.isSega) ToggleRow(
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
                    if (platform.isSega) SegaCoreOptionRows(platform, overrides = draft.coreOptions, category = "system",
                        onChange = { key, value -> onDraftChange(draft.copy(coreOptions = draft.coreOptions + (key to value))) })
                    else CoreOptionManagerRows(
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
                if (platform.isSega) SegaCoreOptionRows(platform, overrides = draft.coreOptions, category = "audio",
                    onChange = { key, value -> onDraftChange(draft.copy(coreOptions = draft.coreOptions + (key to value))) })
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
                    if (!platform.isSega) {
                    ToggleRow(
                        title = stringResource(R.string.settings_racing_mode),
                        checked = draft.racingMode,
                        onCheckedChange = { onDraftChange(draft.copy(racingMode = it)) },
                        helpText = stringResource(R.string.settings_help_racing_mode),
                        onResetToDefault = { onDraftChange(draft.copy(racingMode = defaultProfile.racingMode)) }
                    )
                    }
                    StickyButtonsSelector(
                        platform = platform,
                        selected = draft.stickyButtons,
                        onSelectionChange = { onDraftChange(draft.copy(stickyButtons = it)) },
                        helpText = stringResource(R.string.settings_help_sticky_buttons),
                        onResetToDefault = {
                            onDraftChange(draft.copy(stickyButtons = defaultProfile.stickyButtons))
                        },
                        horizontalPadding = GameSettingsSectionContentPadding,
                        fullBleedPadding = GameSettingsSectionContentPadding
                    )
                    if (!platform.isSega) {
                    ToggleRow(
                        title = stringResource(R.string.settings_touchscreen_left_stick),
                        checked = draft.touchscreenLeftStick,
                        onCheckedChange = { onDraftChange(draft.copy(touchscreenLeftStick = it)) },
                        helpText = stringResource(R.string.settings_help_touchscreen_left_stick),
                        onResetToDefault = { onDraftChange(draft.copy(touchscreenLeftStick = defaultProfile.touchscreenLeftStick)) }
                    )
                    }
                    if (!platform.isSega && draft.touchscreenLeftStick) {
                        SliderRow(
                            title = stringResource(R.string.settings_touchscreen_left_stick_sensitivity),
                            value = draft.touchscreenLeftStickSensitivity.toFloat(),
                            valueLabel = "${draft.touchscreenLeftStickSensitivity}%",
                            range = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN.toFloat()..
                                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX.toFloat(),
                            steps = AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MAX -
                                AppPreferences.TOUCHSCREEN_RIGHT_STICK_SENSITIVITY_MIN - 1,
                            onValueChange = {
                                onDraftChange(draft.copy(touchscreenLeftStickSensitivity = it.roundToInt()))
                            },
                            helpText = stringResource(R.string.settings_help_touchscreen_left_stick_sensitivity),
                            onResetToDefault = {
                                onDraftChange(
                                    draft.copy(
                                        touchscreenLeftStickSensitivity = defaultProfile.touchscreenLeftStickSensitivity
                                    )
                                )
                            }
                        )
                    }
                    if (!platform.isSega) {
                    ToggleRow(
                        title = stringResource(R.string.settings_touchscreen_right_stick),
                        checked = draft.touchscreenRightStick,
                        onCheckedChange = { onDraftChange(draft.copy(touchscreenRightStick = it)) },
                        helpText = stringResource(R.string.settings_help_touchscreen_right_stick),
                        onResetToDefault = { onDraftChange(draft.copy(touchscreenRightStick = defaultProfile.touchscreenRightStick)) }
                    )
                    }
                    if (!platform.isSega && draft.touchscreenRightStick) {
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
                    if (!platform.isSega) {
                    SelectionRow(
                        title = stringResource(R.string.settings_gyro_mode),
                        options = gyroModeOptions(draft.gyroStickTarget),
                        selectedValue = draft.gyroMode,
                        onSelected = { onDraftChange(draft.copy(gyroMode = it)) },
                        helpText = stringResource(R.string.settings_help_gyro_mode),
                        onResetToDefault = { onDraftChange(draft.copy(gyroMode = defaultProfile.gyroMode)) }
                    )
                    }
                    if (!platform.isSega && draft.gyroMode == AppPreferences.GYRO_MODE_AIM) {
                        SelectionRow(
                            title = stringResource(R.string.settings_gyro_stick),
                            options = gyroStickTargetOptions(),
                            selectedValue = draft.gyroStickTarget,
                            onSelected = { onDraftChange(draft.copy(gyroStickTarget = it)) },
                            onResetToDefault = { onDraftChange(draft.copy(gyroStickTarget = defaultProfile.gyroStickTarget)) }
                        )
                    }
                    if (!platform.isSega) {
                    SelectionRow(
                        title = stringResource(R.string.settings_arcade_device_player_1),
                        options = arcadeDeviceOptions(),
                        selectedValue = draft.arcadeDevice1,
                        onSelected = { onDraftChange(draft.copy(arcadeDevice1 = it)) },
                        onResetToDefault = { onDraftChange(draft.copy(arcadeDevice1 = defaultProfile.arcadeDevice1)) }
                    )
                    }
                    if (!platform.isSega) {
                    SelectionRow(
                        title = stringResource(R.string.settings_arcade_device_player_2),
                        options = arcadeDeviceOptions(),
                        selectedValue = draft.arcadeDevice2,
                        onSelected = { onDraftChange(draft.copy(arcadeDevice2 = it)) },
                        onResetToDefault = { onDraftChange(draft.copy(arcadeDevice2 = defaultProfile.arcadeDevice2)) }
                    )
                    }
                    if (!platform.isSega) {
                    SelectionRow(
                        title = stringResource(R.string.settings_light_gun_aim),
                        options = lightGunAimSourceOptions(),
                        selectedValue = draft.lightGunAimSource,
                        onSelected = { onDraftChange(draft.copy(lightGunAimSource = it)) },
                        onResetToDefault = { onDraftChange(draft.copy(lightGunAimSource = defaultProfile.lightGunAimSource)) }
                    )
                    }
                    if (!platform.isSega) {
                    ToggleRow(
                        title = stringResource(R.string.settings_light_gun_cursor),
                        checked = draft.lightGunCursorEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(lightGunCursorEnabled = it)) },
                        helpText = stringResource(R.string.settings_help_light_gun_cursor),
                        onResetToDefault = { onDraftChange(draft.copy(lightGunCursorEnabled = defaultProfile.lightGunCursorEnabled)) }
                    )
                    }
                    val gunUsesGyro = draft.lightGunAimSource == AppPreferences.LIGHT_GUN_AIM_GYRO
                    val sensorControlsVisible = gunUsesGyro ||
                        draft.gyroMode == AppPreferences.GYRO_MODE_AIM ||
                        draft.gyroMode == AppPreferences.GYRO_MODE_STEERING
                    val sensorMode = if (draft.gyroMode == AppPreferences.GYRO_MODE_OFF && gunUsesGyro) {
                        AppPreferences.GYRO_MODE_LIGHT_GUN
                    } else draft.gyroMode
                    if (!platform.isSega && sensorControlsVisible &&
                        !AndroidGyroscopeInput.isModeAvailable(LocalContext.current, sensorMode)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_gyro_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!platform.isSega && sensorControlsVisible) {
                        SliderRow(stringResource(R.string.settings_gyro_sensitivity), draft.gyroSensitivity.toFloat(), "${draft.gyroSensitivity}%", 25f..300f, 10, { onDraftChange(draft.copy(gyroSensitivity = it.roundToInt())) }, helpText = stringResource(R.string.settings_help_gyro_sensitivity), onResetToDefault = { onDraftChange(draft.copy(gyroSensitivity = defaultProfile.gyroSensitivity)) })
                        SliderRow(stringResource(R.string.settings_gyro_smoothing), draft.gyroSmoothing.toFloat(), "${draft.gyroSmoothing}%", 0f..90f, 8, { onDraftChange(draft.copy(gyroSmoothing = it.roundToInt())) }, helpText = stringResource(R.string.settings_help_gyro_smoothing), onResetToDefault = { onDraftChange(draft.copy(gyroSmoothing = defaultProfile.gyroSmoothing)) })
                        ToggleRow(stringResource(R.string.settings_gyro_invert_x), draft.gyroInvertX, { onDraftChange(draft.copy(gyroInvertX = it)) }, helpText = stringResource(R.string.settings_help_gyro_invert_x), onResetToDefault = { onDraftChange(draft.copy(gyroInvertX = defaultProfile.gyroInvertX)) })
                        if (gunUsesGyro || draft.gyroMode == AppPreferences.GYRO_MODE_AIM) {
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
                    if (!platform.isSega) {
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
                }

                PerGameGamepadMappingSection(
                    platform = platform,
                    bindingsByPad = draft.gamepadBindingsByPad,
                    onBindingsChange = { bindings ->
                        onDraftChange(draft.copy(gamepadBindingsByPad = bindings))
                    }
                )
                PerGameKeyboardMouseSettingsSection(
                    platform = platform,
                    override = draft.keyboardMouse,
                    globalSettings = globalKeyboardMouseSettings,
                    onOverrideChange = { keyboardMouse ->
                        onDraftChange(draft.copy(keyboardMouse = keyboardMouse))
                    }
                )

                if (!platform.isSega) EditorSection(title = stringResource(R.string.settings_core_input)) {
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
    val context = LocalContext.current
    val platform = rememberGamePlatform(draft.gameKey)
    options.forEach { option ->
        val values = option.choices.map { it.value }
        val current = draft.coreOptions[option.key]
            ?: (if (option.key.startsWith("jgenesis_")) com.sbro.emucoreh.core.SegaCoreOptions.value(context, option.key, platform)
                else CoreOptionStore.value(option.key))
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
                    shape = neonChipShape(),
                    selected = selectedValue == value,
                    onClick = { onSelected(value) },
                    interactionSource = optionInteractionSource,
                    colors = premiumFilterChipColors(),
                    label = { Text(label) }
                )
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
private fun gyroModeOptions(stickTarget: Int = AppPreferences.GYRO_STICK_RIGHT): List<Pair<Int, String>> = listOf(
    AppPreferences.GYRO_MODE_OFF to stringResource(R.string.settings_gyro_off),
    AppPreferences.GYRO_MODE_AIM to stringResource(
        if (stickTarget == AppPreferences.GYRO_STICK_LEFT) R.string.settings_gyro_aim_left else R.string.settings_gyro_aim
    ),
    AppPreferences.GYRO_MODE_STEERING to stringResource(R.string.settings_gyro_steering),
    AppPreferences.GYRO_MODE_LIGHT_GUN to stringResource(R.string.settings_gyro_mode_light_gun)
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
        upscaleMultiplier = upscaleMultiplier,
        aspectRatio = aspectRatio,
        localMultiplayerMode = localMultiplayerMode,
        displayCrop = displayCrop,
        showFps = showFps,
        fpsOverlayMode = fpsOverlayMode,
        racingMode = racingMode,
        stickyButtons = stickyButtons,
        touchscreenRightStick = touchscreenRightStick,
        touchscreenRightStickSensitivity = touchscreenRightStickSensitivity,
        touchscreenLeftStick = touchscreenLeftStick,
        touchscreenLeftStickSensitivity = touchscreenLeftStickSensitivity,
        touchHaptics = touchHaptics,
        touchHapticsPreset = touchHapticsPreset,
        gyroMode = gyroMode,
        gyroStickTarget = gyroStickTarget,
        lightGunAimSource = lightGunAimSource,
        lightGunCursorEnabled = lightGunCursorEnabled,
        arcadeDevice1 = arcadeDevice1,
        arcadeDevice2 = arcadeDevice2,
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

internal fun PerGameSettings.resolveAgainst(defaultProfile: PerGameSettings): PerGameSettings {
    val keys = providedKeys ?: return copy(providedKeys = null)
    fun <T> pick(key: String, current: T, fallback: T): T = if (key in keys) current else fallback
    return defaultProfile.copy(
        gameKey = gameKey,
        gameTitle = gameTitle,
        gameSerial = gameSerial,
        keyboardMouse = pick("keyboardMouse", keyboardMouse, defaultProfile.keyboardMouse),
        renderer = pick("renderer", renderer, defaultProfile.renderer),
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
        stickyButtons = pick("stickyButtons", stickyButtons, defaultProfile.stickyButtons),
        touchscreenRightStick = pick("touchscreenRightStick", touchscreenRightStick, defaultProfile.touchscreenRightStick),
        touchscreenRightStickSensitivity = pick(
            "touchscreenRightStickSensitivity",
            touchscreenRightStickSensitivity,
            defaultProfile.touchscreenRightStickSensitivity
        ),
        touchscreenLeftStick = pick("touchscreenLeftStick", touchscreenLeftStick, defaultProfile.touchscreenLeftStick),
        touchscreenLeftStickSensitivity = pick(
            "touchscreenLeftStickSensitivity",
            touchscreenLeftStickSensitivity,
            defaultProfile.touchscreenLeftStickSensitivity
        ),
        touchHaptics = pick("touchHaptics", touchHaptics, defaultProfile.touchHaptics),
        touchHapticsPreset = pick("touchHapticsPreset", touchHapticsPreset, defaultProfile.touchHapticsPreset),
        gyroMode = pick("gyroMode", gyroMode, defaultProfile.gyroMode),
        gyroStickTarget = pick("gyroStickTarget", gyroStickTarget, defaultProfile.gyroStickTarget),
        lightGunAimSource = pick("lightGunAimSource", lightGunAimSource, defaultProfile.lightGunAimSource),
        lightGunCursorEnabled = pick("lightGunCursorEnabled", lightGunCursorEnabled, defaultProfile.lightGunCursorEnabled),
        arcadeDevice1 = pick("arcadeDevice1", arcadeDevice1, defaultProfile.arcadeDevice1),
        arcadeDevice2 = pick("arcadeDevice2", arcadeDevice2, defaultProfile.arcadeDevice2),
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

@Composable
private fun PerGameGamepadMappingSection(
    platform: EmulationPlatform = EmulationPlatform.DREAMCAST,
    bindingsByPad: Map<Int, Map<String, Int>>,
    onBindingsChange: (Map<Int, Map<String, Int>>) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val globalBindingsByPad by preferences.gamepadBindingsByPad.collectAsState(initial = emptyMap())
    var showMappingDialog by remember { mutableStateOf(false) }

    EditorSection(title = stringResource(R.string.settings_gamepad_mapping_title)) {
        Text(
            text = stringResource(R.string.settings_gamepad_mapping_game_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ManagerActionButton(
            icon = Icons.Rounded.Gamepad,
            title = stringResource(R.string.settings_gamepad_mapping_configure),
            onClick = { showMappingDialog = true }
        )
        if (bindingsByPad.isNotEmpty()) {
            ManagerActionButton(
                icon = Icons.Rounded.Restore,
                title = stringResource(R.string.settings_gamepad_mapping_use_global),
                onClick = { onBindingsChange(emptyMap()) }
            )
        }
    }

    if (showMappingDialog) {
        PerGameGamepadMappingDialog(
            platform = platform,
            globalBindingsByPad = if (platform.isSega) com.sbro.emucoreh.core.SegaControlProfiles.resolve(platform,
                com.sbro.emucoreh.core.SegaControlProfiles.load(context, platform)) else globalBindingsByPad,
            bindingsByPad = bindingsByPad,
            onBindingsChange = onBindingsChange,
            onDismiss = { showMappingDialog = false }
        )
    }
}

@Composable
internal fun PerGameGamepadMappingDialog(
    platform: EmulationPlatform,
    globalBindingsByPad: Map<Int, Map<String, Int>>,
    bindingsByPad: Map<Int, Map<String, Int>>,
    onBindingsChange: (Map<Int, Map<String, Int>>) -> Unit,
    onDismiss: () -> Unit
) {
    val gamepadActions = remember { GamepadManager.mappableButtonActions() }
    var selectedPadIndex by remember { mutableIntStateOf(0) }
    var pendingActionId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(pendingActionId) {
        val actionId = pendingActionId
        if (actionId != null) {
            GamepadManager.startBindingCapture(selectedPadIndex) { keyCode ->
                val padBindings = GamepadBindingRules.assign(
                    bindingsByPad[selectedPadIndex].orEmpty(),
                    actionId,
                    keyCode
                )
                onBindingsChange(bindingsByPad + (selectedPadIndex to padBindings))
                pendingActionId = null
            }
        } else {
            GamepadManager.cancelBindingCapture()
        }
        onDispose { GamepadManager.cancelBindingCapture() }
    }

    val effectiveBindings = remember(globalBindingsByPad, bindingsByPad) {
        GamepadBindingRules.merge(globalBindingsByPad, bindingsByPad)
    }
    val overrides = bindingsByPad[selectedPadIndex].orEmpty()
    val selectedBindings = effectiveBindings[selectedPadIndex].orEmpty()
    val connectedControllerName = GamepadManager.connectedControllerName(selectedPadIndex)

    SettingsStyledDialog(
        title = stringResource(R.string.settings_gamepad_mapping_title),
        eyebrow = stringResource(R.string.app_name),
        icon = Icons.Rounded.Gamepad,
        onDismissRequest = { if (pendingActionId == null) onDismiss() }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 1).forEach { padIndex ->
                FilterChip(
                    shape = neonChipShape(),
                    selected = selectedPadIndex == padIndex,
                    onClick = {
                        if (pendingActionId == null) selectedPadIndex = padIndex
                    },
                    colors = premiumFilterChipColors(),
                    label = { Text(gamepadPlayerLabel(padIndex)) }
                )
            }
        }
        Text(
            text = connectedControllerName?.let {
                stringResource(
                    R.string.settings_gamepad_mapping_player_connected,
                    gamepadPlayerLabel(selectedPadIndex),
                    it
                )
            } ?: stringResource(
                R.string.settings_gamepad_mapping_player_disconnected,
                gamepadPlayerLabel(selectedPadIndex)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        pendingActionId?.let { actionId ->
            Text(
                text = stringResource(
                    R.string.settings_gamepad_mapping_listening_player_desc,
                    gamepadPlayerLabel(selectedPadIndex),
                    gamepadActionLabel(actionId, platform = platform)
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            gamepadActions.filter { gamepadActionSupported(it.id, platform) }.forEach { action ->
                val assignedKeyCode = GamepadManager.resolveBindingForAction(
                    actionId = action.id,
                    customBindings = selectedBindings
                )
                val isOverride = overrides.containsKey(action.id)
                ManagerGamepadBindingRow(
                    title = gamepadActionLabel(action.id, platform = platform),
                    value = assignedKeyCode?.let(GamepadManager::keyCodeLabel)
                        ?: stringResource(R.string.settings_not_set),
                    autoLabel = if (isOverride || action.defaultKeyCodes.isEmpty()) {
                        null
                    } else {
                        stringResource(R.string.settings_gamepad_mapping_auto_format)
                    },
                    highlight = pendingActionId == action.id,
                    onBindClick = { pendingActionId = action.id },
                    onClearClick = if (isOverride) {
                        {
                            val updatedPad = overrides - action.id
                            val updated = if (updatedPad.isEmpty()) {
                                bindingsByPad - selectedPadIndex
                            } else {
                                bindingsByPad + (selectedPadIndex to updatedPad)
                            }
                            onBindingsChange(updated)
                        }
                    } else {
                        null
                    }
                )
            }
        }
        ManagerActionButton(
            icon = Icons.Rounded.Restore,
            title = stringResource(R.string.settings_gamepad_mapping_use_global),
            onClick = { onBindingsChange(emptyMap()) }
        )
        Button(
            onClick = onDismiss,
            enabled = pendingActionId == null,
            modifier = Modifier.fillMaxWidth(),
            shape = neonShape(18.dp)
        ) {
            Text(
                text = stringResource(R.string.close),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ManagerGamepadBindingRow(
    title: String,
    value: String,
    autoLabel: String?,
    highlight: Boolean,
    onBindClick: () -> Unit,
    onClearClick: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(16.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        },
        border = if (highlight) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        } else {
            null
        },
        onClick = onBindClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    autoLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            onClearClick?.let { clear ->
                TextButton(onClick = clear) {
                    Text(stringResource(R.string.settings_gamepad_mapping_clear))
                }
            }
        }
    }
}

@Composable
private fun gamepadPlayerLabel(padIndex: Int): String {
    return stringResource(
        if (padIndex == 0) R.string.settings_gamepad_player_1 else R.string.settings_gamepad_player_2
    )
}
