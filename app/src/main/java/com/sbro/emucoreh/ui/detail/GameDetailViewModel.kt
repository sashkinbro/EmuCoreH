package com.sbro.emucoreh.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.data.ps1.Ps1CatalogDetails
import com.sbro.emucoreh.data.ps1.Ps1CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GameDetailUiState(
    val isLoading: Boolean = false,
    val catalogDetails: Ps1CatalogDetails? = null,
    val isCatalogAvailable: Boolean = false
)

class GameDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val catalogRepository = Ps1CatalogRepository(application)
    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()
    private var lastCatalogGameId: Long? = null

    fun loadGame(catalogGameId: Long) {
        if (lastCatalogGameId == catalogGameId && (_uiState.value.isLoading || _uiState.value.catalogDetails != null)) {
            return
        }
        lastCatalogGameId = catalogGameId
        _uiState.value = GameDetailUiState(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            val hasCatalog = catalogRepository.hasCatalog()
            val details = if (hasCatalog) catalogRepository.getDetails(catalogGameId) else null
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    catalogDetails = details,
                    isCatalogAvailable = hasCatalog
                )
            }
        }
    }

    override fun onCleared() {
        catalogRepository.close()
    }
}
