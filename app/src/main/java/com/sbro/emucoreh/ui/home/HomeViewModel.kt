package com.sbro.emucoreh.ui.home

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.R
import com.sbro.emucoreh.core.BiosImport
import com.sbro.emucoreh.core.BiosValidator
import com.sbro.emucoreh.core.CoreBinaryFingerprint
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.ProProductOffer
import com.sbro.emucoreh.core.ProPurchaseManager
import com.sbro.emucoreh.core.ProPurchaseTier
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.StorageAccess
import com.sbro.emucoreh.data.AppPreferences
import com.sbro.emucoreh.data.HomeBackgroundRepository
import com.sbro.emucoreh.data.HomeBackgroundPreset
import com.sbro.emucoreh.data.HomeBackgroundType
import com.sbro.emucoreh.data.CoverArtRepository
import com.sbro.emucoreh.data.boxart.BoxartRetryPolicy
import com.sbro.emucoreh.data.boxart.syncInitialCovers
import com.sbro.emucoreh.data.CoreMaintenanceRepository
import com.sbro.emucoreh.data.CoreUpdateResetAction
import com.sbro.emucoreh.data.CustomGameCoverRepository
import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.LibraryPlatform
import com.sbro.emucoreh.data.LibraryPlatformResolver
import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import com.sbro.emucoreh.data.MultiDiscLibrary
import com.sbro.emucoreh.data.flattenLibraryGroups
import com.sbro.emucoreh.data.recentLibraryGroups
import com.sbro.emucoreh.data.GameLibraryCacheRepository
import com.sbro.emucoreh.data.GameLibraryCacheSnapshot
import com.sbro.emucoreh.data.GameRepository
import com.sbro.emucoreh.data.RecentGameEntry
import com.sbro.emucoreh.data.decideCoreUpdateResetAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.time.Duration.Companion.milliseconds

enum class HomeLibraryViewMode {
    GRID, LIST, SHELF
}

data class HomeUiState(
    val games: List<GameItem> = emptyList(),
    val librarySections: List<HomeLibrarySection> = emptyList(),
    val recentGames: List<GameItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isBootstrapping: Boolean = true,
    val gameFolderSet: Boolean = false,
    val biosConfigured: Boolean = false,
    val biosValid: Boolean = false,
    val setupComplete: Boolean = false,
    val showRecentGames: Boolean = true,
    val showHomeSearch: Boolean = false,
    val homeGridScale: Float = AppPreferences.DEFAULT_HOME_GRID_SCALE,
    val homeBackgroundType: HomeBackgroundType = HomeBackgroundType.NONE,
    val homeBackgroundPreset: HomeBackgroundPreset = HomeBackgroundPreset.OLYMPUS,
    val homeBackgroundRevision: Int = 0,
    val homeBackgroundDim: Int = AppPreferences.DEFAULT_HOME_BACKGROUND_DIM,
    val searchQuery: String = "",
    val sortOption: HomeSortOption = HomeSortOption.PLATFORM,
    val platformFilter: LibraryPlatform? = null,
    val availablePlatforms: List<LibraryPlatform> = emptyList(),
    val libraryViewMode: HomeLibraryViewMode = HomeLibraryViewMode.GRID,
    val lastStandardLibraryViewMode: HomeLibraryViewMode = HomeLibraryViewMode.GRID,
    val isCoverArtDisabled: Boolean = true,
    val showWelcomeDialog: Boolean = false,
    val isProUnlocked: Boolean = false,
    val proPrice: String? = null,
    val proProducts: List<ProProductOffer> = emptyList(),
    val ownedProProductIds: Set<String> = emptySet(),
    val isProPurchaseStatusVerified: Boolean = false,
    val isProProductLoading: Boolean = false,
    val isProProductAvailable: Boolean = false,
    val isProPurchaseInProgress: Boolean = false,
    val proPurchaseMessageResId: Int? = null
)

private data class DeferredLibraryScan(
    val rootPaths: List<String>,
    val isInitialLoad: Boolean,
    val showRefreshIndicator: Boolean
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val AUTO_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    }

    private val repository = GameRepository()
    private val arcadeCatalog = ArcadeCatalogRepository(application)
    private val libraryCacheRepository = GameLibraryCacheRepository(application)
    private val customGameCoverRepository = CustomGameCoverRepository(application)
    private val preferences = AppPreferences(application)
    private val homeBackgroundRepository = HomeBackgroundRepository(application)
    private val proPurchaseManager = ProPurchaseManager.getInstance(application)
    private var allGames: List<GameItem> = emptyList()
    private var recentEntries: List<RecentGameEntry> = emptyList()
    private var coverSyncJob: Job? = null
    private var coverRetryJob: Job? = null
    private var searchJob: Job? = null
    private var biosInitialized = false
    private var libraryInitialized = false
    private var currentLibraryRoot: String? = null
    private var currentLibraryPaths: List<String> = emptyList()
    private var preferEnglishGameTitles = false
    private var titlesPreferenceInitialized = false
    private var fetchBoxartInitialized = false
    private var coverCacheRevisionInitialized = false
    private var customCoversRevisionInitialized = false
    private var currentFetchBoxart = true
    private val scanMutex = Mutex()
    private var deferredLibraryScan: DeferredLibraryScan? = null
    private var deferredWorkJob: Job? = null
    private var deferredCoverSync = false

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.homeGridScale.collect { scale ->
                _uiState.value = _uiState.value.copy(homeGridScale = scale)
            }
        }
        viewModelScope.launch {
            preferences.homeBackgroundType.collect { type ->
                val availableType = if (
                    type == HomeBackgroundType.BUILT_IN ||
                    homeBackgroundRepository.existingFile(type) != null
                ) {
                    type
                } else {
                    HomeBackgroundType.NONE
                }
                _uiState.value = _uiState.value.copy(homeBackgroundType = availableType)
            }
        }
        viewModelScope.launch {
            preferences.homeBackgroundPreset.collect { preset ->
                _uiState.value = _uiState.value.copy(homeBackgroundPreset = preset)
            }
        }
        viewModelScope.launch {
            preferences.homeBackgroundRevision.collect { revision ->
                _uiState.value = _uiState.value.copy(homeBackgroundRevision = revision)
            }
        }
        viewModelScope.launch {
            preferences.homeBackgroundDim.collect { dim ->
                _uiState.value = _uiState.value.copy(homeBackgroundDim = dim)
            }
        }
        viewModelScope.launch {
            preferences.welcomeDialogShown.distinctUntilChanged().collect { shown ->
                _uiState.value = _uiState.value.copy(showWelcomeDialog = !shown)
            }
        }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val currentFingerprint = withContext(Dispatchers.IO) {
                CoreBinaryFingerprint.current(context)
            }
            val storedFingerprint = preferences.lastCoreBinaryFingerprint.first()
            val hadExistingInstall = preferences.welcomeDialogShown.first()
            when (decideCoreUpdateResetAction(storedFingerprint, currentFingerprint, hadExistingInstall)) {
                CoreUpdateResetAction.STORE_SILENTLY ->
                    preferences.setLastCoreBinaryFingerprint(currentFingerprint)
                CoreUpdateResetAction.RESET_AUTOMATICALLY -> {
                    withContext(Dispatchers.IO) {
                        CoreMaintenanceRepository(context).resetGeneratedCoreState()
                        preferences.setLastCoreBinaryFingerprint(currentFingerprint)
                    }
                    android.widget.Toast.makeText(
                        context,
                        R.string.core_update_reset_done,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                CoreUpdateResetAction.NONE -> Unit
            }
        }
        viewModelScope.launch {
            proPurchaseManager.state.collect { proState ->
                _uiState.value = _uiState.value.copy(
                    isProUnlocked = proState.isProUnlocked,
                    proPrice = proState.productPrice,
                    proProducts = proState.products,
                    ownedProProductIds = proState.ownedProductIds,
                    isProPurchaseStatusVerified = proState.isPurchaseStatusVerified,
                    isProProductLoading = proState.isProductLoading,
                    isProProductAvailable = proState.isProductAvailable,
                    isProPurchaseInProgress = proState.isPurchaseInProgress,
                    proPurchaseMessageResId = proState.messageResId
                )
            }
        }
        viewModelScope.launch {
            preferences.gamePaths.distinctUntilChanged().collect { paths ->
                val context = getApplication<Application>()
                val effectivePaths = withContext(Dispatchers.IO) {
                    paths.filter { SetupValidator.hasCoreReadableGameFile(context, it) }
                }
                val libraryKey = libraryKey(effectivePaths)
                if (currentLibraryRoot != libraryKey) {
                    allGames = emptyList()
                }
                currentLibraryPaths = effectivePaths
                currentLibraryRoot = libraryKey
                if (effectivePaths.isNotEmpty()) {
                    val cacheSnapshot = resolveCacheSnapshot(libraryKey)
                    val hasCachedGames = cacheSnapshot.games.isNotEmpty()
                    if (hasCachedGames) {
                        allGames = cacheSnapshot.games
                        currentLibraryRoot = libraryKey
                        libraryInitialized = true
                        _uiState.value = _uiState.value.copy(
                            gameFolderSet = true,
                            isLoading = false,
                            isRefreshing = false
                        )
                        publishVisibleGames()
                        updateBootstrapState()
                    } else {
                        libraryInitialized = false
                        updateBootstrapState()
                        _uiState.value = _uiState.value.copy(
                            gameFolderSet = true,
                            isLoading = true,
                            isRefreshing = false
                        )
                    }
                    requestLibraryScan(effectivePaths, isInitialLoad = true)
                } else {
                    allGames = emptyList()
                    currentLibraryRoot = null
                    currentLibraryPaths = emptyList()
                    libraryInitialized = true
                    _uiState.value = _uiState.value.copy(
                        gameFolderSet = false,
                        isLoading = false,
                        isRefreshing = false
                    )
                    publishVisibleGames()
                    updateBootstrapState()
                }
            }
        }
        viewModelScope.launch {
            preferences.biosPath.distinctUntilChanged().collect { path ->
                val bios = withContext(Dispatchers.IO) {
                    val hasBios = BiosImport.hasAnyBios(getApplication())
                    (hasBios || path != null) to hasBios
                }
                _uiState.value = _uiState.value.copy(
                    biosConfigured = bios.first,
                    biosValid = bios.second
                )
                biosInitialized = true
                updateBootstrapState()
            }
        }
        viewModelScope.launch {
            preferences.onboardingCompleted.distinctUntilChanged().collect { completed ->
                _uiState.value = _uiState.value.copy(setupComplete = completed)
            }
        }
        viewModelScope.launch {
            preferences.preferEnglishGameTitles.distinctUntilChanged().collect { enabled ->
                val shouldRefreshLibrary = titlesPreferenceInitialized && preferEnglishGameTitles != enabled
                preferEnglishGameTitles = enabled
                titlesPreferenceInitialized = true
                if (!shouldRefreshLibrary) return@collect
                if (currentLibraryPaths.isEmpty()) return@collect
                allGames = emptyList()
                requestLibraryScan(currentLibraryPaths)
            }
        }
        viewModelScope.launch {
            preferences.recentGames.distinctUntilChanged().collect { entries ->
                recentEntries = entries
                publishVisibleGames()
            }
        }
        viewModelScope.launch {
            preferences.showRecentGames.distinctUntilChanged().collect { enabled ->
                _uiState.value = _uiState.value.copy(showRecentGames = enabled)
                publishVisibleGames()
            }
        }
        viewModelScope.launch {
            preferences.showHomeSearch.distinctUntilChanged().collect { enabled ->
                _uiState.value = _uiState.value.copy(showHomeSearch = enabled)
            }
        }
        viewModelScope.launch {
            preferences.homeSortOption.distinctUntilChanged().collect { saved ->
                _uiState.value = _uiState.value.copy(sortOption = HomeSortOption.fromPreference(saved))
                publishVisibleGames()
            }
        }
        viewModelScope.launch {
            preferences.homeLibraryViewMode.distinctUntilChanged().collect { mode ->
                val resolvedMode = when (mode) {
                    1 -> HomeLibraryViewMode.LIST
                    2 -> HomeLibraryViewMode.SHELF
                    else -> HomeLibraryViewMode.GRID
                }
                val lastStandardMode = when (resolvedMode) {
                    HomeLibraryViewMode.SHELF -> _uiState.value.lastStandardLibraryViewMode
                    else -> resolvedMode
                }
                _uiState.value = _uiState.value.copy(
                    libraryViewMode = resolvedMode,
                    lastStandardLibraryViewMode = lastStandardMode
                )
            }
        }
        viewModelScope.launch {
            preferences.fetchBoxart.distinctUntilChanged().collect { enabled ->
                _uiState.value = _uiState.value.copy(
                    isCoverArtDisabled = !enabled
                )
                val shouldRefreshLibrary = fetchBoxartInitialized && currentFetchBoxart != enabled
                currentFetchBoxart = enabled
                fetchBoxartInitialized = true
                if (shouldRefreshLibrary) {
                    handleCoverSourceChanged()
                }
            }
        }
        viewModelScope.launch {
            preferences.coverCacheRevision.collect {
                if (coverCacheRevisionInitialized) {
                    handleCoverCacheCleared()
                } else {
                    coverCacheRevisionInitialized = true
                }
            }
        }
        viewModelScope.launch {
            preferences.customCoversRevision.collect {
                if (customCoversRevisionInitialized) handleCustomCoversReset()
                else customCoversRevisionInitialized = true
            }
        }
        viewModelScope.launch {
            // A finished session can leave covers stale: temp files may have
            // been evicted while the core owned the device and serials may
            // have been learned at boot. Repair the library before the user
            // starts browsing again.
            EmulatorBridge.sessionEnded.collect {
                syncMissingCovers()
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            StorageAccess.takePersistableReadPermission(context, uri)
            val rawPath = uri.toString()
            preferences.addGamePath(rawPath)
        }
    }

    fun onBiosFolderSelected(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            StorageAccess.takePersistableReadPermission(context, uri)
            val result = BiosImport.installSelection(context, uri)
            if (result.installed) {
                preferences.addBiosPath(uri.toString())
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(
                        com.sbro.emucoreh.R.string.bios_import_result,
                        result.dreamcast,
                        result.arcade,
                        result.segaCd,
                        result.sega32x
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            val hasBios = BiosImport.hasAnyBios(context)
            _uiState.value = _uiState.value.copy(
                biosConfigured = hasBios,
                biosValid = hasBios
            )
            updateBootstrapState()
        }
    }

    fun refreshGames() {
        viewModelScope.launch {
            if (_uiState.value.isLoading || _uiState.value.isRefreshing) return@launch
            val paths = preferences.gamePaths.first()
            if (paths.isEmpty()) return@launch
            requestLibraryScan(paths, showRefreshIndicator = true)
        }
    }

    suspend fun setCustomCover(game: GameItem, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val customCoverPath = customGameCoverRepository.saveCustomCover(game.path, sourceUri)
                ?: return@withLock false
            synchronized(this@HomeViewModel) {
                // Reset may have removed the file after its save completed.
                if (customGameCoverRepository.findCustomCoverPath(game.path) != customCoverPath)
                    return@withLock false
                allGames = allGames.map { current ->
                    if (current.path == game.path) current.copy(coverArtPath = customCoverPath) else current
                }
            }
            publishVisibleGames()
            currentLibraryRoot?.let { libraryCacheRepository.save(it, allGames, preferEnglishGameTitles) }
            true
        }
    }

    suspend fun resetCustomCover(game: GameItem): Boolean = withContext(Dispatchers.IO) {
        val result = customGameCoverRepository.removeCustomCover(game.path)
        preferences.notifyCustomCoversReset()
        result
    }

    private suspend fun handleCustomCoversReset() = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            coverSyncJob?.cancel()
            val context = getApplication<Application>()
            val replacements = allGames.filter { customGameCoverRepository.isCustomCoverPath(it.coverArtPath) }
                .associate { game ->
                    game.path to (game.coverArtPath to repository.findCoverForGame(game.path, context, game.serial, game.title))
                }
            synchronized(this@HomeViewModel) {
                allGames = allGames.map { game ->
                    val replacement = replacements[game.path]
                    if (replacement != null && game.coverArtPath == replacement.first &&
                        customGameCoverRepository.findCustomCoverPath(game.path) == null) {
                        game.copy(coverArtPath = replacement.second)
                    } else game
                }
            }
            publishVisibleGames()
            currentLibraryRoot?.let { libraryCacheRepository.save(it, allGames, preferEnglishGameTitles) }
            syncMissingCovers()
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180.milliseconds)
            publishVisibleGames()
        }
    }

    fun updatePlatformFilter(platform: LibraryPlatform?) {
        _uiState.value = _uiState.value.copy(platformFilter = platform)
        publishVisibleGames()
    }

    fun updateSortOption(option: HomeSortOption) {
        _uiState.value = _uiState.value.copy(sortOption = option)
        publishVisibleGames()
        viewModelScope.launch { preferences.setHomeSortOption(option.name) }
    }

    fun toggleLibraryViewMode() {
        viewModelScope.launch {
            val state = _uiState.value
            val targetMode = when (state.libraryViewMode) {
                HomeLibraryViewMode.GRID -> HomeLibraryViewMode.LIST
                HomeLibraryViewMode.LIST -> HomeLibraryViewMode.GRID
                HomeLibraryViewMode.SHELF -> state.lastStandardLibraryViewMode
            }
            preferences.setHomeLibraryViewMode(targetMode.toPreferenceValue())
        }
    }

    fun toggleShelfMode() {
        viewModelScope.launch {
            val state = _uiState.value
            val targetMode = if (state.libraryViewMode == HomeLibraryViewMode.SHELF) {
                state.lastStandardLibraryViewMode
            } else {
                HomeLibraryViewMode.SHELF
            }
            preferences.setHomeLibraryViewMode(targetMode.toPreferenceValue())
        }
    }

    fun enableBoxart() {
        viewModelScope.launch {
            preferences.setFetchBoxart(true)
        }
    }

    private fun scanGames(
        paths: List<String>,
        isInitialLoad: Boolean = false,
        showRefreshIndicator: Boolean = false
    ) {
        val rootPath = libraryKey(paths)
        viewModelScope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                try {
                    if (shouldDeferLibraryWork(paths, isInitialLoad, showRefreshIndicator)) return@withLock
                    val cacheSnapshot = resolveCacheSnapshot(rootPath)
                    val cachedGames = cacheSnapshot.games
                    val showFullScreenLoader = false
                    _uiState.value = _uiState.value.copy(
                        isLoading = showFullScreenLoader,
                        isRefreshing = showRefreshIndicator && !showFullScreenLoader
                    )
                    publishCachedGamesIfAvailable(rootPath, cachedGames)
                    if (shouldSkipAutoRescan(isInitialLoad, cacheSnapshot)) {
                        libraryInitialized = true
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isRefreshing = false
                        )
                        updateBootstrapState()
                        syncMissingCovers()
                        return@withLock
                    }
                    val context = getApplication<Application>()
                    val cachedByPath = flattenLibraryGroups(cachedGames).associateBy { it.path }
                    val scannedGames = MultiDiscLibrary.group(paths.flatMap { path ->
                        if (path.startsWith("content://")) {
                            repository.scanDirectoryFromUri(
                                path.toUri(), context, cachedByPath,
                                shouldAbort = { EmulatorBridge.isVmActive() },
                                preferEnglishTitles = preferEnglishGameTitles
                            )
                        } else {
                            repository.scanDirectory(
                                path, context, cachedByPath,
                                shouldAbort = { EmulatorBridge.isVmActive() },
                                preferEnglishTitles = preferEnglishGameTitles
                            )
                        }
                    }.distinctBy { GameRepository.libraryIdentity(it.path) })
                        .sortedBy { it.title.lowercase() }
                    if (EmulatorBridge.isVmActive()) {
                        queueDeferredLibraryWork(
                            rootPaths = paths,
                            isInitialLoad = false,
                            showRefreshIndicator = showRefreshIndicator
                        )
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isRefreshing = false
                        )
                        return@withLock
                    }
                    allGames = scannedGames
                    currentLibraryRoot = rootPath
                    currentLibraryPaths = paths
                    libraryInitialized = true
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false
                    )
                    publishVisibleGames()
                    updateBootstrapState()
                    libraryCacheRepository.save(rootPath, allGames, preferEnglishGameTitles)
                    syncMissingCovers()
                } catch (_: Exception) {
                    completeLibraryScanWithFallback(rootPath)
                }
            }
        }
    }

    private fun completeLibraryScanWithFallback(rootPath: String) {
        currentLibraryRoot = rootPath
        libraryInitialized = true
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isRefreshing = false
        )
        publishVisibleGames()
        updateBootstrapState()
    }

    private fun requestLibraryScan(
        rootPaths: List<String>,
        isInitialLoad: Boolean = false,
        showRefreshIndicator: Boolean = false
    ) {
        val normalized = rootPaths.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return
        if (shouldDeferLibraryWork(normalized, isInitialLoad, showRefreshIndicator)) return
        scanGames(normalized, isInitialLoad, showRefreshIndicator)
    }

    private fun shouldDeferLibraryWork(
        rootPaths: List<String>,
        isInitialLoad: Boolean,
        showRefreshIndicator: Boolean
    ): Boolean {
        if (!EmulatorBridge.isVmActive()) return false
        val rootPath = libraryKey(rootPaths)
        val cacheSnapshot = resolveCacheSnapshot(rootPath)
        publishCachedGamesIfAvailable(rootPath, cacheSnapshot.games)
        queueDeferredLibraryWork(rootPaths, isInitialLoad, showRefreshIndicator)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isRefreshing = false
        )
        updateBootstrapState()
        return true
    }

    private fun queueDeferredLibraryWork(
        rootPaths: List<String>,
        isInitialLoad: Boolean,
        showRefreshIndicator: Boolean
    ) {
        val existing = deferredLibraryScan
        deferredLibraryScan = DeferredLibraryScan(
            rootPaths = rootPaths,
            isInitialLoad = isInitialLoad || existing?.isInitialLoad == true,
            showRefreshIndicator = showRefreshIndicator || existing?.showRefreshIndicator == true
        )
        startDeferredWorkMonitor()
    }

    private fun startDeferredWorkMonitor() {
        if (deferredWorkJob?.isActive == true) return
        deferredWorkJob = viewModelScope.launch {
            try {
                while (true) {
                    if (EmulatorBridge.isVmActive()) {
                        delay(1500.milliseconds)
                        continue
                    }

                    val pendingScan = deferredLibraryScan
                    if (pendingScan != null) {
                        deferredLibraryScan = null
                        requestLibraryScan(
                            rootPaths = pendingScan.rootPaths,
                            isInitialLoad = pendingScan.isInitialLoad,
                            showRefreshIndicator = pendingScan.showRefreshIndicator
                        )
                        return@launch
                    }

                    if (deferredCoverSync) {
                        deferredCoverSync = false
                        syncMissingCovers()
                        return@launch
                    }

                    return@launch
                }
            } finally {
                deferredWorkJob = null
                if (!EmulatorBridge.isVmActive() && (deferredLibraryScan != null || deferredCoverSync)) {
                    startDeferredWorkMonitor()
                }
            }
        }
    }

    private fun resolveCacheSnapshot(rootPath: String): GameLibraryCacheSnapshot {
        val inMemoryGames = allGames.takeIf { currentLibraryRoot == rootPath && it.isNotEmpty() }
        return if (inMemoryGames != null) {
            GameLibraryCacheSnapshot(inMemoryGames, System.currentTimeMillis())
        } else {
            libraryCacheRepository.loadSnapshot(rootPath, preferEnglishGameTitles)
        }
    }

    private fun libraryKey(paths: List<String>): String =
        GameLibraryCacheRepository.libraryKey(paths)

    private fun shouldSkipAutoRescan(isInitialLoad: Boolean, cacheSnapshot: GameLibraryCacheSnapshot): Boolean {
        if (!isInitialLoad) return false
        if (cacheSnapshot.games.isEmpty()) return false
        // Missing serials/covers are repaired by the background cover sync;
        // they must not force a full metadata rescan on every launch.
        val cacheAge = System.currentTimeMillis() - cacheSnapshot.savedAt
        return cacheAge in 0 until AUTO_REFRESH_INTERVAL_MS
    }

    private fun publishCachedGamesIfAvailable(rootPath: String, cachedGames: List<GameItem>) {
        if (cachedGames.isEmpty()) return
        if (currentLibraryRoot == rootPath && allGames.isNotEmpty()) return
        allGames = cachedGames
        currentLibraryRoot = rootPath
        libraryInitialized = true
        publishVisibleGames()
        updateBootstrapState()
    }

    private fun publishVisibleGames() {
        val covers = CoverArtRepository(getApplication())
        allGames = allGames.map { game ->
            val selected = covers.displayCoverPath(game.serial, game.title, game.coverArtPath, platform = game.platform)
            when {
                selected == game.coverArtPath -> game
                selected != null -> game.copy(coverArtPath = selected)
                // Resolution failed: keep the previous path. The background
                // cover sync repairs missing files, so a transient miss must
                // not erase the artwork from every screen.
                else -> game
            }
        }
        val storedState = _uiState.value
        val presentPlatforms = allGames.filterNot { game ->
            BiosValidator.isLikelyBiosLibraryEntry(game.fileName, game.title, game.serial)
        }.mapTo(mutableSetOf()) { LibraryPlatformResolver.resolve(it, arcadeCatalog::find) }
        val availablePlatforms = LibraryPlatform.entries.filter { it in presentPlatforms }
        val state = if (storedState.platformFilter != null && storedState.platformFilter !in presentPlatforms)
            storedState.copy(platformFilter = null) else storedState
        val query = normalizeSearchToken(state.searchQuery)
        val platformGames = allGames.filter { game ->
            state.platformFilter == null || LibraryPlatformResolver.resolve(game, arcadeCatalog::find) == state.platformFilter
        }
        val filtered = platformGames.filter { game ->
            !BiosValidator.isLikelyBiosLibraryEntry(
                fileName = game.fileName,
                title = game.title,
                serial = game.serial
            ) && (
            query.isBlank() ||
                normalizeSearchToken(game.title).contains(query) ||
                normalizeSearchToken(game.fileName).contains(query) ||
                normalizeSearchToken(game.serial).contains(query) ||
                game.discs.any { disc -> normalizeSearchToken(disc.fileName).contains(query) ||
                    normalizeSearchToken(disc.serial).contains(query) }
            )
        }
        val sections = buildHomeLibrarySections(filtered, state.sortOption) { game ->
            LibraryPlatformResolver.resolve(game, arcadeCatalog::find)
        }
        val recentGames = recentLibraryGroups(platformGames, recentEntries.map { it.path }).filter { game ->
            query.isBlank() ||
                normalizeSearchToken(game.title).contains(query) ||
                normalizeSearchToken(game.fileName).contains(query) ||
                normalizeSearchToken(game.serial).contains(query) ||
                game.discs.any { disc -> normalizeSearchToken(disc.fileName).contains(query) ||
                    normalizeSearchToken(disc.serial).contains(query) }
        }.takeIf { state.showRecentGames }.orEmpty()
        _uiState.value = _uiState.value.copy(
            games = sections.flatMap { it.games },
            availablePlatforms = availablePlatforms,
            platformFilter = state.platformFilter,
            librarySections = sections,
            recentGames = recentGames
        )
    }

    private fun updateBootstrapState() {
        _uiState.value = _uiState.value.copy(
            isBootstrapping = !(biosInitialized && libraryInitialized)
        )
    }

    private fun syncMissingCovers() {
        coverRetryJob?.cancel()
        coverRetryJob = null
        if (EmulatorBridge.isVmActive()) {
            deferredCoverSync = true
            startDeferredWorkMonitor()
            return
        }
        coverSyncJob?.cancel()
        val context = getApplication<Application>()
        coverSyncJob = viewModelScope.launch(Dispatchers.IO) {
            val coverRepository = CoverArtRepository(context)
            var removedStalePaths = false
            synchronized(this@HomeViewModel) {
                allGames = allGames.map { game ->
                    val hasSerial = !game.serial.isNullOrBlank()
                    val resolvedCoverPath = game.serial
                        ?.takeIf { it.isNotBlank() }
                        ?.let(coverRepository::findCachedCoverPath)
                    when {
                        coverRepository.isMissingManagedCover(game.coverArtPath) ||
                            customGameCoverRepository.isMissingCustomCover(game.coverArtPath) -> {
                            removedStalePaths = true
                            game.copy(coverArtPath = null)
                        }
                        hasSerial && resolvedCoverPath != null && resolvedCoverPath != game.coverArtPath &&
                            coverRepository.isManagedCoverCachePath(game.coverArtPath) -> {
                            removedStalePaths = true
                            game.copy(coverArtPath = resolvedCoverPath)
                        }
                        else -> game
                    }
                }
            }
            if (removedStalePaths) publishVisibleGames()
            val gamesToProcess = allGames.filter {
                it.coverArtPath == null || it.coverArtPath.startsWith("http") ||
                    coverRepository.isManagedCoverCachePath(it.coverArtPath)
            }
            if (gamesToProcess.isEmpty()) {
                if (removedStalePaths) {
                    currentLibraryRoot?.let { rootPath ->
                        libraryCacheRepository.save(rootPath, allGames, preferEnglishGameTitles)
                    }
                }
                return@launch
            }
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)

            val shouldReschedule = AtomicBoolean(false)
            val initial = syncInitialCovers(
                games = gamesToProcess,
                networkAvailable = coverRepository.isNetworkAvailable(),
                shouldPause = { EmulatorBridge.isVmActive() },
                fetch = { game, mode -> repository.downloadCoverForGame(game, context, coverRepository, mode) },
                publish = { game, downloadedCover ->
                    if (downloadedCover != game.coverArtPath) {
                        synchronized(this@HomeViewModel) {
                            allGames = allGames.map {
                                if (it.path == game.path && !customGameCoverRepository.isCustomCoverPath(it.coverArtPath))
                                    it.copy(coverArtPath = downloadedCover) else it
                            }
                        }
                        publishVisibleGames()
                    }
                },
                isFinalCover = { !coverRepository.isPendingOnlineCover(it) },
                allowRepository = { coverRepository.nextOnlineRetryAtMillis == 0L }
            )
            if (initial.deferred) shouldReschedule.set(true)
            // Only unresolved covers reach disc metadata and external services;
            // known repository artwork has already been published above.
            for (attempt in 0 until 3) {
                val upgradeTargets = allGames.filter {
                    it.coverArtPath == null || it.coverArtPath.startsWith("http") ||
                        coverRepository.isPendingOnlineCover(it.coverArtPath)
                }
                if (upgradeTargets.isEmpty()) break
                if (EmulatorBridge.isVmActive()) {
                    shouldReschedule.set(true)
                    break
                }
                if (!initial.canUpgradeOnline || !coverRepository.isNetworkAvailable()) {
                    coverRepository.postponeOnlineCovers()
                    break
                }
                coverRepository.prepareOnlineCovers(upgradeTargets) { EmulatorBridge.isVmActive() }
                coroutineScope {
                    upgradeTargets.forEach { game ->
                        launch {
                            semaphore.withPermit {
                                if (EmulatorBridge.isVmActive()) {
                                    shouldReschedule.set(true)
                                    return@withPermit
                                }
                                val upgradedCover = repository.downloadCoverForGame(game, context, coverRepository)
                                if (upgradedCover != null && upgradedCover != game.coverArtPath) {
                                    synchronized(this@HomeViewModel) {
                                        allGames = allGames.map {
                                            if (it.path == game.path && !customGameCoverRepository.isCustomCoverPath(it.coverArtPath))
                                                it.copy(coverArtPath = upgradedCover) else it
                                        }
                                    }
                                    publishVisibleGames()
                                }
                            }
                        }
                    }
                }
                if (shouldReschedule.get()) break
                val retryAt = coverRepository.nextOnlineRetryAtMillis
                if (retryAt == 0L) break
                val waitMillis = (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                // Retry short outages within this sync. Allowance exhaustion
                // keeps its 24-hour backoff; never hold this job for a day.
                if (waitMillis > 10_000L || attempt == 2) break
                delay(waitMillis.coerceAtLeast(1000L))
            }
            if (shouldReschedule.get()) {
                deferredCoverSync = true
                startDeferredWorkMonitor()
            }
            currentLibraryRoot?.let { rootPath ->
                libraryCacheRepository.save(rootPath, allGames, preferEnglishGameTitles)
            }
            if (!shouldReschedule.get() && currentFetchBoxart) {
                BoxartRetryPolicy.nextSyncDelay(coverRepository.nextOnlineRetryAtMillis, System.currentTimeMillis())
                    ?.let { retryDelay ->
                        coverRetryJob = viewModelScope.launch {
                            delay(retryDelay)
                            coverRetryJob = null
                            if (currentFetchBoxart) syncMissingCovers()
                        }
                    }
            }
        }
    }

    private fun handleCoverSourceChanged() {
        val rootPath = currentLibraryRoot ?: return
        val context = getApplication<Application>()
        val coverRepository = CoverArtRepository(context)
        val customCoverRepository = CustomGameCoverRepository(context)
        val cachePrefix = java.io.File(context.cacheDir, "game-covers").absolutePath

        synchronized(this) {
            allGames = allGames.map { game ->
                val currentPath = game.coverArtPath
                if (customCoverRepository.isCustomCoverPath(currentPath)) {
                    return@map game
                }
                if (currentPath != null && currentPath.startsWith(cachePrefix, ignoreCase = true)) {
                    game.copy(coverArtPath = coverRepository.findCachedCoverPath(game.serial, title = game.title, platform = game.platform))
                } else {
                    game
                }
            }
        }
        publishVisibleGames()
        libraryCacheRepository.save(rootPath, allGames, preferEnglishGameTitles)

        requestLibraryScan(currentLibraryPaths)
    }

    private fun handleCoverCacheCleared() {
        if (EmulatorBridge.isVmActive()) {
            deferredCoverSync = true
            startDeferredWorkMonitor()
            return
        }
        val rootPath = currentLibraryRoot ?: return
        val coverRepository = CoverArtRepository(getApplication())
        synchronized(this) {
            allGames = allGames.map { game ->
                if (coverRepository.isManagedCoverCachePath(game.coverArtPath)) {
                    game.copy(coverArtPath = null)
                } else {
                    game
                }
            }
        }
        publishVisibleGames()
        libraryCacheRepository.save(rootPath, allGames, preferEnglishGameTitles)
        syncMissingCovers()
    }

    private fun normalizeSearchToken(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }

    fun dismissWelcomeDialog() {
        viewModelScope.launch { preferences.setWelcomeDialogShown(true) }
    }

    fun purchasePro(
        activity: Activity,
        tier: ProPurchaseTier = ProPurchaseTier.BASE
    ) {
        proPurchaseManager.purchase(activity, tier)
    }

    fun clearProPurchaseMessage() {
        proPurchaseManager.clearMessage()
    }
}

private fun HomeLibraryViewMode.toPreferenceValue(): Int = when (this) {
    HomeLibraryViewMode.GRID -> 0
    HomeLibraryViewMode.LIST -> 1
    HomeLibraryViewMode.SHELF -> 2
}
