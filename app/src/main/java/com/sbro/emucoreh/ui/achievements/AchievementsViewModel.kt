package com.sbro.emucoreh.ui.achievements

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucoreh.data.AchievementItem
import com.sbro.emucoreh.data.RetroAchievementsEvent
import com.sbro.emucoreh.data.RetroAchievementsGameData
import com.sbro.emucoreh.data.RetroAchievementsLibraryGame
import com.sbro.emucoreh.data.RetroAchievementsRepository
import com.sbro.emucoreh.data.RetroAchievementsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AchievementsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RetroAchievementsRepository.get(application)

    private val _state = MutableStateFlow(RetroAchievementsState())
    val state: StateFlow<RetroAchievementsState> = _state.asStateFlow()

    private val _achievements = MutableStateFlow<List<AchievementItem>>(emptyList())
    val achievements: StateFlow<List<AchievementItem>> = _achievements.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _libraryGames = MutableStateFlow<List<RetroAchievementsLibraryGame>>(emptyList())
    val libraryGames: StateFlow<List<RetroAchievementsLibraryGame>> = _libraryGames.asStateFlow()

    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()

    private val _libraryError = MutableStateFlow(false)
    val libraryError: StateFlow<Boolean> = _libraryError.asStateFlow()

    private val _selectedGame = MutableStateFlow<RetroAchievementsLibraryGame?>(null)
    val selectedGame: StateFlow<RetroAchievementsLibraryGame?> = _selectedGame.asStateFlow()

    private val _gameData = MutableStateFlow<RetroAchievementsGameData?>(null)
    val gameData: StateFlow<RetroAchievementsGameData?> = _gameData.asStateFlow()

    private val _gameDataLoading = MutableStateFlow(false)
    val gameDataLoading: StateFlow<Boolean> = _gameDataLoading.asStateFlow()

    private val _events = MutableSharedFlow<RetroAchievementsEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RetroAchievementsEvent> = _events.asSharedFlow()

    init {
        repository.ensureInitialized()
        viewModelScope.launch {
            // First snapshot is applied before the screen marks itself ready so
            // toggles never animate from a stale default into their real value.
            _state.value = withContext(Dispatchers.IO) { repository.state() }
            _achievements.value = withContext(Dispatchers.IO) { repository.achievements() }

            // A stored token signs in asynchronously; keep the restoring skeleton
            // up instead of flashing the login form for a second.
            val hasStoredCredentials = withContext(Dispatchers.IO) {
                repository.refreshCredentials()
                repository.hasStoredCredentials()
            }
            if (_state.value.available && !_state.value.loggedIn &&
                _state.value.lastError == null && hasStoredCredentials
            ) {
                var attempts = 0
                while (isActive && attempts < 15 && !_state.value.loggedIn &&
                    _state.value.lastError == null
                ) {
                    delay(400L)
                    _state.value = withContext(Dispatchers.IO) { repository.state() }
                    attempts++
                }
                _achievements.value = withContext(Dispatchers.IO) { repository.achievements() }
            }
            _ready.value = true
            if (_state.value.enabled && _state.value.loggedIn) {
                refreshLibrary()
            }

            var wasLoggedIn = _state.value.loggedIn && _state.value.enabled
            while (isActive) {
                _state.value = withContext(Dispatchers.IO) { repository.state() }
                _achievements.value = withContext(Dispatchers.IO) { repository.achievements() }
                withContext(Dispatchers.IO) { repository.pollEvents() }.forEach { _events.tryEmit(it) }

                if (_state.value.loggedIn && _state.value.enabled && !wasLoggedIn) {
                    refreshLibrary()
                }
                wasLoggedIn = _state.value.loggedIn && _state.value.enabled

                _selectedGame.value?.let { selected ->
                    if (_state.value.game?.id == selected.gameId && _state.value.gameLoaded) {
                        _gameData.value = activeGameData()
                    }
                }
                delay(1_000L)
            }
        }
    }

    fun refreshLibrary(force: Boolean = false) {
        if (_libraryLoading.value) return
        viewModelScope.launch {
            _libraryLoading.value = true
            _libraryError.value = false
            runCatching { repository.loadLibraryAchievementGames(force) }
                .onSuccess { _libraryGames.value = it }
                .onFailure { _libraryError.value = true }
            _libraryLoading.value = false
        }
    }

    fun openGame(game: RetroAchievementsLibraryGame) {
        _selectedGame.value = game
        _gameData.value = if (_state.value.game?.id == game.gameId) activeGameData() else null
        viewModelScope.launch {
            _gameDataLoading.value = true
            val data = runCatching { repository.loadGameAchievements(game) }.getOrNull()
            _gameData.value = data ?: activeGameData()?.takeIf { it.gameId == game.gameId }
            _gameDataLoading.value = false
            _libraryGames.value = repository.currentLibraryGames()
        }
    }

    fun closeGame() {
        _selectedGame.value = null
        _gameData.value = null
    }

    fun login(username: String, password: String) = repository.login(username, password)

    fun logout() {
        _libraryGames.value = emptyList()
        _libraryError.value = false
        closeGame()
        repository.logout()
    }

    // Toggles update the snapshot immediately; the native write and the next
    // poll only confirm the value, so switches never lag behind the tap.
    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        repository.setEnabled(enabled)
    }

    fun setHardcore(enabled: Boolean) {
        _state.value = _state.value.copy(hardcore = enabled)
        repository.setHardcore(enabled)
    }

    fun setUnofficial(enabled: Boolean) {
        _state.value = _state.value.copy(unofficial = enabled)
        repository.setUnofficial(enabled)
    }

    fun setEncore(enabled: Boolean) {
        _state.value = _state.value.copy(encore = enabled)
        repository.setEncore(enabled)
    }

    private fun activeGameData(): RetroAchievementsGameData? {
        val state = _state.value
        val game = state.game ?: return null
        return RetroAchievementsGameData(
            gameId = game.id,
            title = game.title,
            imageUrl = game.badgeUrl,
            achievements = _achievements.value,
            earnedCount = state.summary.unlocked,
            totalCount = state.summary.total,
            earnedPoints = state.summary.pointsUnlocked,
            totalPoints = state.summary.points
        )
    }
}
