package com.sbro.emucoreh.ui.gamedb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

enum class GameDbFilter {
    ALL,
    CORE,
    GRAPHICS
}

data class GameDbBrowserUiState(
    val loading: Boolean = true,
    val query: String = "",
    val filter: GameDbFilter = GameDbFilter.ALL,
    val totalCount: Int = 0,
    val entries: List<GameDbCatalogEntry> = emptyList(),
    val loadFailed: Boolean = false
)

private data class GameDbBrowserInputs(
    val entries: List<GameDbCatalogEntry>,
    val rawQuery: String,
    val debouncedQuery: String,
    val filter: GameDbFilter,
    val loading: Boolean,
    val loadFailed: Boolean
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GameDbBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GameDbCatalogRepository(application)
    private val allEntries = MutableStateFlow<List<GameDbCatalogEntry>>(emptyList())
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(GameDbFilter.ALL)
    private val loading = MutableStateFlow(true)
    private val loadFailed = MutableStateFlow(false)
    private var appliedInitialQuery: String? = null

    val queryText = query.asStateFlow()

    private val searchState = combine(
        query,
        query.debounce(120.milliseconds),
        filter
    ) { rawQuery, debouncedQuery, currentFilter ->
        Triple(rawQuery, debouncedQuery, currentFilter)
    }

    private val inputs = combine(
        allEntries,
        searchState,
        loading,
        loadFailed
    ) { entries, currentSearch, isLoading, failed ->
        val (rawQuery, debouncedQuery, currentFilter) = currentSearch
        GameDbBrowserInputs(
            entries = entries,
            rawQuery = rawQuery,
            debouncedQuery = debouncedQuery,
            filter = currentFilter,
            loading = isLoading,
            loadFailed = failed
        )
    }

    val uiState = inputs.mapLatest { input ->
        withContext(Dispatchers.Default) {
            val normalizedQuery = input.debouncedQuery.trim().lowercase(Locale.ROOT)
            val filtered = input.entries.filter { entry ->
                val matchesQuery = normalizedQuery.isBlank() || entry.searchableText.contains(normalizedQuery)
                val matchesFilter = when (input.filter) {
                    GameDbFilter.ALL -> true
                    GameDbFilter.CORE -> entry.coreSettingCount > 0
                    GameDbFilter.GRAPHICS -> entry.graphicsSettingCount > 0
                }
                matchesQuery && matchesFilter
            }
            GameDbBrowserUiState(
                loading = input.loading,
                query = input.rawQuery,
                filter = input.filter,
                totalCount = input.entries.size,
                entries = filtered,
                loadFailed = input.loadFailed
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameDbBrowserUiState()
    )

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.loadEntries() }
            }.onSuccess {
                allEntries.value = it
            }.onFailure {
                loadFailed.value = true
            }
            loading.value = false
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: GameDbFilter) {
        filter.value = value
    }

    fun openQuery(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank() || appliedInitialQuery == normalized) return
        appliedInitialQuery = normalized
        filter.value = GameDbFilter.ALL
        query.value = normalized
    }
}
