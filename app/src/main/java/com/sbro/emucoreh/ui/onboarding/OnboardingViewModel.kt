package com.sbro.emucoreh.ui.onboarding

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.core.AppAnalytics
import com.sbro.emucoreh.core.DreamcastBios
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.EmulatorDataLocation
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.core.FlycastCoreOptions
import com.sbro.emucoreh.core.GpuHardwareProfiles
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.core.PerformanceProfiles
import com.sbro.emucoreh.core.ProProductOffer
import com.sbro.emucoreh.core.ProPurchaseManager
import com.sbro.emucoreh.core.ProPurchaseTier
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.core.StorageAccess
import com.sbro.emucoreh.core.UPSCALE_DEFAULT
import com.sbro.emucoreh.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnboardingUiState(
    val biosPath: String? = null,
    val gamePath: String? = null,
    val gamePaths: List<String> = emptyList(),
    val emulatorDataPath: String? = null,
    val sdCardDataPath: String? = null,
    val biosValid: Boolean = false,
    val gamePathValid: Boolean = false,
    val canContinue: Boolean = false,
    val currentPage: Int = 0,
    val totalPages: Int = 5,
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

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AppPreferences(application)
    private val proPurchaseManager = ProPurchaseManager.getInstance(application)
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            launch {
                proPurchaseManager.state.collect { proState ->
                    updateState(
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
            launch {
                preferences.biosPath.distinctUntilChanged().collect { path ->
                    val biosValid = withContext(Dispatchers.IO) {
                        DreamcastBios.hasAnyBios(flycastSystemDir())
                    }
                    updateState(
                        biosPath = path,
                        biosValid = biosValid
                    )
                }
            }
            launch {
                preferences.gamePaths.distinctUntilChanged().collect { paths ->
                    val gamePathValid = withContext(Dispatchers.IO) {
                        SetupValidator.hasCoreReadableGameFile(getApplication(), paths)
                    }
                    updateState(
                        gamePath = paths.firstOrNull(),
                        gamePaths = paths,
                        gamePathValid = gamePathValid
                    )
                }
            }
            launch {
                preferences.emulatorDataPath.collect { path ->
                    updateState(emulatorDataPath = path)
                }
            }
            refreshEmulatorDataLocations()
        }
    }

    fun setBiosPath(uri: Uri) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val previousPath = preferences.biosPath.first()
            StorageAccess.takePersistableReadPermission(application, uri)
            val systemDir = flycastSystemDir()
            val installed = DreamcastBios.installSelection(application, uri, systemDir)
            if (installed) {
                preferences.setBiosPath(uri.toString())
                if (previousPath != uri.toString()) {
                    StorageAccess.releasePersistedPermission(application, previousPath)
                }
                // Adding a dump must not silently switch the core to the real
                // BIOS path; keep HLE BIOS enabled so boot behaviour is stable.
                NativeApp.setCoreOption(FlycastCoreOptions.HLE_BIOS_KEY, "enabled")
            }
            updateState(
                biosPath = if (installed) uri.toString() else previousPath,
                biosValid = DreamcastBios.hasAnyBios(systemDir)
            )
            val audioSettings = preferences.settingsSnapshot.first()
            EmulatorBridge.applyRuntimeConfig(
                biosPath = uri.toString(),
                emulatorDataPath = _uiState.value.emulatorDataPath,
                renderer = audioSettings.renderer,
                gpuHardwareProfile = GpuHardwareProfiles.detectHardwareProfile(),
                audioVolume = audioSettings.audioVolume,
                audioFastForwardVolume = audioSettings.audioFastForwardVolume,
                audioMuted = audioSettings.audioMuted,
                audioOutputLatencyMs = audioSettings.audioOutputLatencyMs,
                audioMinimalOutputLatency = audioSettings.audioMinimalOutputLatency,
                upscaleMultiplier = EmulatorBridge.getSetting("EmuCoreH", "UpscaleMultiplier", "float")?.toFloatOrNull()
                    ?: EmulatorBridge.getSetting("EmuCoreH", "UpscaleMultiplier", "int")?.toIntOrNull()?.toFloat()
                    ?: UPSCALE_DEFAULT
            )
        }
    }

    fun setGamePath(uri: Uri) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            StorageAccess.takePersistableReadPermission(application, uri)
            val rawPath = uri.toString()
            // Persist the user's valid SAF selection immediately. Game discovery runs in
            // the gamePaths collector on Dispatchers.IO and must not make the selection vanish.
            preferences.addGamePath(rawPath)
        }
    }

    fun removeGamePath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            preferences.removeGamePath(path)
            StorageAccess.releasePersistedPermission(getApplication(), path)
        }
    }

    fun setEmulatorDataLocation(location: EmulatorDataLocation) {
        val application = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val preparedRoot = EmulatorStorage.prepareStandardDataRoot(application, location)
            if (preparedRoot == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        application,
                        com.sbro.emucoreh.R.string.emulator_data_location_error,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                refreshEmulatorDataLocations()
                return@launch
            }
            preferences.setEmulatorDataPath(preparedRoot.preferencePath)
            NativeApp.reloadDataRoot(preparedRoot.preferencePath ?: "")
        }
    }

    fun refreshEmulatorDataLocations() {
        viewModelScope.launch(Dispatchers.IO) {
            val sdCardDataPath = EmulatorStorage.sdCardRoot(getApplication())?.absolutePath
            withContext(Dispatchers.Main) {
                updateState(sdCardDataPath = sdCardDataPath)
            }
        }
    }

    fun setCurrentPage(page: Int) {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(currentPage = page.coerceIn(0, currentState.totalPages - 1))
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

    fun completeOnboarding(onFinished: () -> Unit) {
        if (!_uiState.value.canContinue) return
        viewModelScope.launch {
            preferences.setOnboardingCompleted(true)
            AppAnalytics.logOnboardingCompleted(PerformanceProfiles.SAFE)
            onFinished()
        }
    }

    private fun flycastSystemDir(): String =
        EmulatorStorage.flycastSystemDir(getApplication()).absolutePath

    private fun updateState(
        biosPath: String? = _uiState.value.biosPath,
        gamePath: String? = _uiState.value.gamePath,
        gamePaths: List<String> = _uiState.value.gamePaths,
        emulatorDataPath: String? = _uiState.value.emulatorDataPath,
        sdCardDataPath: String? = _uiState.value.sdCardDataPath,
        biosValid: Boolean = _uiState.value.biosValid,
        gamePathValid: Boolean = _uiState.value.gamePathValid,
        currentPage: Int = _uiState.value.currentPage,
        isProUnlocked: Boolean = _uiState.value.isProUnlocked,
        proPrice: String? = _uiState.value.proPrice,
        proProducts: List<ProProductOffer> = _uiState.value.proProducts,
        ownedProProductIds: Set<String> = _uiState.value.ownedProProductIds,
        isProPurchaseStatusVerified: Boolean = _uiState.value.isProPurchaseStatusVerified,
        isProProductLoading: Boolean = _uiState.value.isProProductLoading,
        isProProductAvailable: Boolean = _uiState.value.isProProductAvailable,
        isProPurchaseInProgress: Boolean = _uiState.value.isProPurchaseInProgress,
        proPurchaseMessageResId: Int? = _uiState.value.proPurchaseMessageResId
    ) {
        _uiState.value = OnboardingUiState(
            biosPath = biosPath,
            gamePath = gamePath,
            gamePaths = gamePaths,
            emulatorDataPath = emulatorDataPath,
            sdCardDataPath = sdCardDataPath,
            biosValid = biosValid,
            gamePathValid = gamePathValid,
            canContinue = gamePathValid,
            currentPage = currentPage.coerceIn(0, 4),
            totalPages = 5,
            isProUnlocked = isProUnlocked,
            proPrice = proPrice,
            proProducts = proProducts,
            ownedProProductIds = ownedProProductIds,
            isProPurchaseStatusVerified = isProPurchaseStatusVerified,
            isProProductLoading = isProProductLoading,
            isProProductAvailable = isProProductAvailable,
            isProPurchaseInProgress = isProPurchaseInProgress,
            proPurchaseMessageResId = proPurchaseMessageResId
        )
    }
}
