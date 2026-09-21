package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.DocumentPathResolver
import com.sbro.emucoreh.core.NativeApp
import com.sbro.emucoreh.core.SetupValidator
import com.sbro.emucoreh.data.RetroAchievementsCatalog.parseAccountProgress
import com.sbro.emucoreh.data.RetroAchievementsCatalog.parseGameTitles
import com.sbro.emucoreh.data.RetroAchievementsCatalog.titleKey
import com.sbro.emucoreh.data.RetroAchievementsCatalog.titleKeys
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RetroAchievementsUser(
    val username: String,
    val displayName: String,
    val token: String,
    val score: Int,
    val softcoreScore: Int = 0,
    val avatarUrl: String
)

data class RetroAchievementsGame(
    val id: Int,
    val title: String,
    val badgeUrl: String
)

data class RetroAchievementsSummary(
    val total: Int,
    val unlocked: Int,
    val points: Int,
    val pointsUnlocked: Int,
    val completed: Boolean
) {
    val progressFraction: Float
        get() = if (total <= 0) 0f else unlocked.toFloat() / total.toFloat()
}

data class RetroAchievementsState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val hardcore: Boolean = false,
    val unofficial: Boolean = false,
    val encore: Boolean = false,
    val loggedIn: Boolean = false,
    val gameLoaded: Boolean = false,
    val loading: Boolean = false,
    val unsupportedImage: Boolean = false,
    val imageReadError: Boolean = false,
    val richPresence: String = "",
    val lastError: String? = null,
    val user: RetroAchievementsUser? = null,
    val game: RetroAchievementsGame? = null,
    val summary: RetroAchievementsSummary = RetroAchievementsSummary(0, 0, 0, 0, false)
)

data class AchievementItem(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val unlockTime: Long,
    val progressText: String,
    val progressPercent: Float,
    val rarity: Float,
    val badgeUrl: String,
    val badgeLockedUrl: String,
    val type: Int
) {
    val isWarning: Boolean
        get() = id == 101000001 || title.equals("Warning: Unknown Emulator", ignoreCase = true)
}

data class RetroAchievementsEvent(
    val type: String,
    val title: String = "",
    val description: String = "",
    val points: Int = 0,
    val badgeUrl: String = "",
    val message: String = ""
) {
    val isAchievementUnlock: Boolean get() = type == "achievement_triggered"
}

data class RetroAchievementsGameData(
    val gameId: Int,
    val title: String,
    val imageUrl: String,
    val achievements: List<AchievementItem>,
    val earnedCount: Int,
    val totalCount: Int,
    val earnedPoints: Int,
    val totalPoints: Int
) {
    val progressFraction: Float
        get() = if (totalCount <= 0) 0f else earnedCount.toFloat() / totalCount.toFloat()
}

data class RetroAchievementsLibraryGame(
    val title: String,
    val path: String,
    val coverArtPath: String?,
    val serial: String?,
    val gameId: Int,
    val raTitle: String,
    val imageUrl: String,
    val earned: Int,
    val total: Int
) {
    val progressFraction: Float
        get() = if (total <= 0) 0f else earned.toFloat() / total.toFloat()
}

internal data class AccountProgressEntry(
    val gameId: Int,
    val earned: Int,
    val hardcore: Int,
    val total: Int
)

/**
 * Account/state facade over the native rcheevos client. Native owns the
 * runtime; this repository persists the token, keeps the toggles in sync and
 * parses the JSON snapshots that the UI polls.
 */
class RetroAchievementsRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var loadedGamePath: String? = null

    @Volatile
    private var pendingGamePath: String? = null

    // RetroAchievements is opt-in; until the user enables it the native client
    // is neither fed a game nor allowed to process frames.
    @Volatile
    private var enabled = false

    // The last game snapshot survives the emulation session so the standalone
    // screen can still show progress after the game has been closed.
    @Volatile
    private var cachedGameState: RetroAchievementsState? = null

    @Volatile
    private var cachedAchievements: List<AchievementItem> = emptyList()

    @Volatile
    private var nativeGameLoaded = false

    // Account credentials are mirrored in memory so HTTP helpers and the UI can
    // read them synchronously.
    @Volatile
    private var storedUsername: String? = null

    @Volatile
    private var storedToken: String? = null

    private val gameDataCache = java.util.concurrent.ConcurrentHashMap<Int, RetroAchievementsGameData>()

    private val gameTitleCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>()

    @Volatile
    private var cachedActiveGameData: RetroAchievementsGameData? = null

    @Volatile
    private var cachedAccountProgress: Map<Int, AccountProgressEntry> = emptyMap()

    @Volatile
    private var cachedAccountProgressAt = 0L

    @Volatile
    private var cachedLibraryGames: List<RetroAchievementsLibraryGame> = emptyList()

    fun hasStoredCredentials(): Boolean =
        !storedUsername.isNullOrBlank() && !storedToken.isNullOrBlank()

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        scope.launch {
            enabled = preferences.retroAchievementsEnabled.first()
            NativeApp.achievementsSetEnabled(enabled)
            NativeApp.achievementsSetHardcore(preferences.retroAchievementsHardcore.first())
            NativeApp.achievementsSetUnofficial(preferences.retroAchievementsUnofficial.first())
            NativeApp.achievementsSetEncore(preferences.retroAchievementsEncore.first())

            val username = preferences.retroAchievementsUsername.first()
            val token = preferences.retroAchievementsToken.first()
            storedUsername = username
            storedToken = token
            if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
                NativeApp.achievementsLoginWithToken(username, token)
            }

            scope.launch {
                preferences.retroAchievementsEnabled.collect { value ->
                    val wasEnabled = enabled
                    enabled = value
                    NativeApp.achievementsSetEnabled(value)
                    if (value && !wasEnabled) {
                        pendingGamePath?.let { path ->
                            if (loadedGamePath != path) {
                                loadedGamePath = path
                                NativeApp.achievementsLoadGame(path)
                            }
                        }
                    } else if (!value && loadedGamePath != null) {
                        loadedGamePath = null
                        clearCachedGame()
                        NativeApp.achievementsUnloadGame()
                    }
                }
            }
            scope.launch {
                preferences.retroAchievementsHardcore.collect { NativeApp.achievementsSetHardcore(it) }
            }
            scope.launch {
                preferences.retroAchievementsUnofficial.collect { NativeApp.achievementsSetUnofficial(it) }
            }
            scope.launch {
                preferences.retroAchievementsEncore.collect { NativeApp.achievementsSetEncore(it) }
            }
        }
    }

    fun state(): RetroAchievementsState {
        ensureInitialized()
        val raw = NativeApp.achievementsStateJson()
        if (raw.isBlank()) return RetroAchievementsState(available = false)
        return runCatching {
            val json = JSONObject(raw)
            val user = json.optJSONObject("user")?.let {
                RetroAchievementsUser(
                    username = it.optString("username"),
                    displayName = it.optString("displayName"),
                    token = it.optString("token"),
                    score = it.optInt("score"),
                    softcoreScore = it.optInt("scoreSoftcore"),
                    avatarUrl = it.optString("avatarUrl")
                )
            }
            val game = json.optJSONObject("game")?.takeIf { it.optInt("id") > 0 }?.let {
                RetroAchievementsGame(
                    id = it.optInt("id"),
                    title = it.optString("title"),
                    badgeUrl = it.optString("badgeUrl")
                )
            }
            val summaryJson = json.optJSONObject("summary") ?: JSONObject()
            val summary = RetroAchievementsSummary(
                total = summaryJson.optInt("total"),
                unlocked = summaryJson.optInt("unlocked"),
                points = summaryJson.optInt("points"),
                pointsUnlocked = summaryJson.optInt("pointsUnlocked"),
                completed = summaryJson.optLong("completedTime") > 0L ||
                    (summaryJson.optInt("total") > 0 && summaryJson.optInt("total") == summaryJson.optInt("unlocked"))
            )
            val parsed = RetroAchievementsState(
                available = json.optBoolean("available"),
                enabled = json.optBoolean("enabled", false),
                hardcore = json.optBoolean("hardcore"),
                unofficial = json.optBoolean("unofficial"),
                encore = json.optBoolean("encore"),
                loggedIn = json.optBoolean("loggedIn"),
                gameLoaded = json.optBoolean("gameLoaded") && game != null,
                unsupportedImage = json.optBoolean("unsupportedImage"),
                imageReadError = json.optBoolean("imageReadError"),
                loading = json.optInt("loadState") in 1..4,
                richPresence = json.optString("richPresence"),
                lastError = json.optString("lastError").takeIf { it.isNotBlank() },
                user = user,
                game = game,
                summary = summary
            )
            nativeGameLoaded = parsed.gameLoaded
            if (parsed.gameLoaded) {
                if (game != null && game.id != cachedGameState?.game?.id) {
                    cachedAchievements = emptyList()
                }
                cachedGameState = parsed
                parsed
            } else {
                val cached = cachedGameState
                if (parsed.enabled && parsed.loggedIn && !parsed.loading && parsed.lastError == null &&
                    !parsed.unsupportedImage && loadedGamePath == null && cached?.game != null
                ) {
                    // No active session: keep showing the last played game.
                    parsed.copy(game = cached.game, summary = cached.summary, gameLoaded = true)
                } else {
                    parsed
                }
            }
        }.getOrDefault(RetroAchievementsState(available = false))
    }

    fun achievements(): List<AchievementItem> {
        ensureInitialized()
        val raw = NativeApp.achievementsAchievementsJson()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    if (item.optInt("id") == 101000001 ||
                        item.optString("title").equals("Warning: Unknown Emulator", ignoreCase = true)
                    ) {
                        continue
                    }
                    add(
                        AchievementItem(
                            id = item.optInt("id"),
                            title = item.optString("title"),
                            description = item.optString("description"),
                            points = item.optInt("points"),
                            unlocked = item.optBoolean("unlocked"),
                            unlockTime = item.optLong("unlockTime"),
                            progressText = item.optString("measuredProgress"),
                            progressPercent = item.optDouble("measuredPercent", 0.0).toFloat(),
                            rarity = item.optDouble("rarity", 0.0).toFloat(),
                            badgeUrl = item.optString("badgeUrl"),
                            badgeLockedUrl = item.optString("badgeLockedUrl"),
                            type = item.optInt("type")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        if (parsed.isNotEmpty()) {
            cachedAchievements = parsed
            return parsed
        }
        // No active session: fall back to the last game's cached list.
        return if (!nativeGameLoaded && cachedGameState != null) cachedAchievements else parsed
    }

    private fun clearCachedGame() {
        cachedGameState = null
        cachedAchievements = emptyList()
    }

    fun pollEvents(): List<RetroAchievementsEvent> {
        ensureInitialized()
        val raw = NativeApp.achievementsPollEventsJson()
        if (raw.isBlank()) return emptyList()
        val events = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        RetroAchievementsEvent(
                            type = item.optString("type"),
                            title = item.optString("title"),
                            description = item.optString("description"),
                            points = item.optInt("points"),
                            badgeUrl = item.optString("badgeUrl"),
                            message = item.optString("message")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        if (events.any { it.type == "login_success" }) {
            val user = state().user
            if (user != null && user.token.isNotBlank()) {
                storedUsername = user.username
                storedToken = user.token
                scope.launch {
                    preferences.setRetroAchievementsUsername(user.username)
                    preferences.setRetroAchievementsToken(user.token)
                }
            }
        }
        return events
    }

    // ---------------------------------------------------------------------
    // Library browsing: games with achievements and per-game details
    // ---------------------------------------------------------------------

    suspend fun loadLibraryAchievementGames(force: Boolean = false): List<RetroAchievementsLibraryGame> =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            refreshCredentials()
            if (!enabled || !hasStoredCredentials()) return@withContext emptyList()

            val libraryGames = scanLibraryGames()
            val progress = loadAccountProgress(force)
            val result = LinkedHashMap<String, RetroAchievementsLibraryGame>()

            if (progress.isNotEmpty()) {
                val titleMap = loadGameTitles(progress.keys)
                val remoteByTitle = titleMap.entries.flatMap { entry ->
                    titleKeys(entry.value.first).map { it to entry }
                }.groupBy({ it.first }, { it.second })
                libraryGames.forEach { match ->
                    // Names only select a set to browse. Runtime support is always determined by hash.
                    val keys = titleKeys(match.title) + titleKeys(match.fileName)
                    val exact = titleMap.entries.filter { titleKey(it.value.first) in keys }
                    val candidates = exact.ifEmpty { keys.flatMap { remoteByTitle[it].orEmpty() }.distinctBy { it.key } }
                    val info = candidates.singleOrNull() ?: return@forEach
                    val entry = progress[info.key] ?: return@forEach
                    result[match.path] = RetroAchievementsLibraryGame(
                        title = match.title,
                        path = match.path,
                        coverArtPath = match.coverArtPath,
                        serial = match.serial,
                        gameId = entry.gameId,
                        raTitle = info.value.first,
                        imageUrl = info.value.second,
                        earned = entry.earned,
                        total = entry.total
                    )
                }
            }

            val activeState = state()
            val activeGame = activeState.game
            if (activeState.gameLoaded && activeGame != null && activeGame.id > 0) {
                val activeItems = achievements()
                val activeData = RetroAchievementsGameData(
                    gameId = activeGame.id,
                    title = activeGame.title,
                    imageUrl = activeGame.badgeUrl,
                    achievements = activeItems,
                    earnedCount = activeState.summary.unlocked,
                    totalCount = activeState.summary.total,
                    earnedPoints = activeState.summary.pointsUnlocked,
                    totalPoints = activeState.summary.points
                )
                if (activeGame.id > 0) {
                    cachedActiveGameData = activeData
                    gameDataCache[activeGame.id] = activeData
                }
                val match = libraryGames.firstOrNull {
                    titleKey(it.title) == titleKey(activeGame.title)
                }
                val key = match?.path?.takeIf { it.isNotBlank() } ?: "id:${activeGame.id}"
                result[key] = RetroAchievementsLibraryGame(
                    title = match?.title ?: activeGame.title,
                    path = match?.path.orEmpty(),
                    coverArtPath = match?.coverArtPath,
                    serial = match?.serial,
                    gameId = activeGame.id,
                    raTitle = activeGame.title,
                    imageUrl = activeGame.badgeUrl,
                    earned = activeState.summary.unlocked,
                    total = activeState.summary.total
                )
            }

            val sorted = result.values.sortedBy { it.title.lowercase() }
            cachedLibraryGames = sorted
            cachedLibraryGames
        }

    fun currentLibraryGames(): List<RetroAchievementsLibraryGame> = cachedLibraryGames

    suspend fun loadGameAchievements(game: RetroAchievementsLibraryGame): RetroAchievementsGameData? =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            refreshCredentials()
            gameDataCache[game.gameId]
                ?.takeIf { it.achievements.isNotEmpty() || it.totalCount > 0 }
                ?.let { return@withContext it }
            cachedActiveGameData
                ?.takeIf { it.gameId == game.gameId && it.totalCount > 0 }
                ?.let { return@withContext it }
            if (!hasStoredCredentials()) return@withContext null

            val username = storedUsername.orEmpty()
            val token = storedToken.orEmpty()
            val gameIdText = game.gameId.toString()
            val patch = postRequest(
                "r" to "patch", "u" to username, "t" to token, "g" to gameIdText
            ) ?: return@withContext null
            val softcore = postRequest(
                "r" to "unlocks", "u" to username, "t" to token, "g" to gameIdText, "h" to "0"
            )?.parseUnlockIds() ?: return@withContext null
            val hardcore = postRequest(
                "r" to "unlocks", "u" to username, "t" to token, "g" to gameIdText, "h" to "1"
            )?.parseUnlockIds() ?: return@withContext null
            val data = patch.parsePatchGameData(game, softcore, hardcore) ?: return@withContext null
            gameDataCache[game.gameId] = data
            updateCachedLibraryGame(game.gameId, data.earnedCount, data.totalCount)
            data
        }

    private fun updateCachedLibraryGame(gameId: Int, earned: Int, total: Int) {
        val current = cachedLibraryGames
        val index = current.indexOfFirst { it.gameId == gameId }
        if (index < 0) return
        cachedLibraryGames = current.toMutableList().also { list ->
            list[index] = list[index].copy(
                earned = earned,
                total = if (total > 0) total else list[index].total
            )
        }
    }

    suspend fun refreshCredentials() {
        if (hasStoredCredentials()) return
        storedUsername = preferences.retroAchievementsUsername.first()
        storedToken = preferences.retroAchievementsToken.first()
    }

    private suspend fun scanLibraryGames(): List<GameItem> {
        val roots = preferences.gamePaths.first()
        if (roots.isEmpty()) return emptyList()
        val cacheRepository = GameLibraryCacheRepository(appContext)
        val readableRoots = roots.filter { SetupValidator.hasCoreReadableGameFile(appContext, it) }
        return cacheRepository.loadSnapshot(GameLibraryCacheRepository.libraryKey(readableRoots)).games
            .distinctBy { it.path }
            .sortedBy { it.title.lowercase() }
    }

    private fun loadAccountProgress(force: Boolean = false): Map<Int, AccountProgressEntry> {
        val now = System.currentTimeMillis()
        if (!force && cachedAccountProgress.isNotEmpty() &&
            now - cachedAccountProgressAt < ACCOUNT_PROGRESS_TTL_MS
        ) {
            return cachedAccountProgress
        }
        val json = postRequest(
            "r" to "allprogress",
            "u" to storedUsername.orEmpty(),
            "t" to storedToken.orEmpty(),
            "c" to RetroAchievementsCatalog.PSP_CONSOLE_ID.toString()
        ) ?: error("Could not load PSP achievement catalog")
        val parsed = json.parseAccountProgress()
        cachedAccountProgress = parsed
        cachedAccountProgressAt = now
        return parsed
    }

    private fun loadGameTitles(ids: Set<Int>): Map<Int, Pair<String, String>> {
        val missing = ids.filterNot { gameTitleCache.containsKey(it) }
        missing.chunked(100).forEach { chunk ->
            val json = postRequest(
                "r" to "gameinfolist",
                "u" to storedUsername.orEmpty(),
                "t" to storedToken.orEmpty(),
                "g" to chunk.joinToString(",")
            ) ?: error("Could not load achievement game titles")
            json.parseGameTitles().forEach { (id, info) -> gameTitleCache[id] = info }
        }
        check(ids.all { gameTitleCache.containsKey(it) }) { "Incomplete achievement game titles" }
        return ids.mapNotNull { id -> gameTitleCache[id]?.let { id to it } }.toMap()
    }

    private fun postRequest(vararg params: Pair<String, String>): String? {
        var connection: HttpURLConnection? = null
        return runCatching {
            val body = params.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection = (URL("https://retroachievements.org/dorequest.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "EmuCoreH/1.0")
            }
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?.takeIf { responseCode in 200..299 && it.isNotBlank() }
        }.getOrNull().also { connection?.disconnect() }
    }

    private fun String.parseUnlockIds(): Set<Int> {
        val root = JSONObject(this)
        check(root.optBoolean("Success")) { "Could not load achievement progress" }
        val ids = root.getJSONArray("UserUnlocks")
        return buildSet {
            for (index in 0 until ids.length()) {
                val id = ids.optInt(index)
                if (id > 0 && id != 101000001) add(id)
            }
        }
    }

    private fun String.parsePatchGameData(
        game: RetroAchievementsLibraryGame,
        softcoreUnlocks: Set<Int>,
        hardcoreUnlocks: Set<Int>
    ): RetroAchievementsGameData? = runCatching {
        val root = JSONObject(this)
        if (!root.optBoolean("Success")) return@runCatching null
        val patch = root.optJSONObject("PatchData") ?: return@runCatching null
        val array = patch.optJSONArray("Achievements") ?: return@runCatching null
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optInt("Flags", 3) != 3) continue
                val title = item.optString("Title")
                val description = item.optString("Description")
                if (title.equals("Warning: Unknown Emulator", ignoreCase = true) ||
                    description.contains("Hardcore unlocks cannot be earned", ignoreCase = true)
                ) {
                    continue
                }
                val id = item.optInt("ID")
                val badgeName = item.optString("BadgeName")
                val badge = normalizeImageUrl(item.optString("BadgeURL"))
                    .ifBlank { badgeName.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org/Badge/$it.png" }.orEmpty() }
                val badgeLocked = normalizeImageUrl(item.optString("BadgeLockedURL"))
                    .ifBlank { badgeName.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org/Badge/${it}_lock.png" }.orEmpty() }
                add(
                    AchievementItem(
                        id = id,
                        title = title,
                        description = description,
                        points = item.optInt("Points"),
                        unlocked = id in softcoreUnlocks || id in hardcoreUnlocks,
                        unlockTime = 0L,
                        progressText = "",
                        progressPercent = 0f,
                        rarity = item.optDouble("Rarity", 0.0).toFloat(),
                        badgeUrl = badge,
                        badgeLockedUrl = badgeLocked,
                        type = 0
                    )
                )
            }
        }
        RetroAchievementsGameData(
            gameId = patch.optInt("ID", game.gameId),
            title = patch.optString("Title").takeIf { it.isNotBlank() } ?: game.raTitle,
            imageUrl = normalizeImageUrl(patch.optString("ImageIconURL")),
            achievements = items,
            earnedCount = items.count { it.unlocked },
            totalCount = items.size,
            earnedPoints = items.filter { it.unlocked }.sumOf { it.points },
            totalPoints = items.sumOf { it.points }
        )
    }.getOrNull()

    private fun normalizeImageUrl(value: String?): String {
        val clean = value?.trim().orEmpty()
        return when {
            clean.startsWith("http://", ignoreCase = true) ||
                clean.startsWith("https://", ignoreCase = true) -> clean

            clean.startsWith("/Images/", ignoreCase = true) ||
                clean.startsWith("/Badge/", ignoreCase = true) -> "https://media.retroachievements.org$clean"

            clean.startsWith("Images/", ignoreCase = true) ||
                clean.startsWith("Badge/", ignoreCase = true) -> "https://media.retroachievements.org/$clean"

            else -> clean
        }
    }

    fun onGameStarted(path: String) {
        ensureInitialized()
        val hashablePath = runCatching { resolveHashablePath(path) }.getOrNull() ?: path
        pendingGamePath = hashablePath
        if (!enabled || loadedGamePath == hashablePath) return
        clearCachedGame()
        loadedGamePath = hashablePath
        NativeApp.achievementsLoadGame(hashablePath)
    }

    /** Hash the original game through the same seekable SAF VFS used by the core. */
    private fun resolveHashablePath(path: String): String? {
        val prepared = DocumentPathResolver.prepareGameLaunchPath(appContext, path) ?: return null
        return com.sbro.emucoreh.core.SafStorageBridge.prepare(appContext, prepared)
    }

    fun onGameStopped() {
        pendingGamePath = null
        if (loadedGamePath == null) return
        loadedGamePath = null
        NativeApp.achievementsUnloadGame()
    }

    fun login(username: String, password: String) {
        ensureInitialized()
        NativeApp.achievementsLoginWithPassword(username, password)
    }

    fun logout() {
        ensureInitialized()
        loadedGamePath = null
        pendingGamePath = null
        clearCachedGame()
        storedUsername = null
        storedToken = null
        cachedAccountProgress = emptyMap()
        cachedAccountProgressAt = 0L
        cachedLibraryGames = emptyList()
        gameDataCache.clear()
        gameTitleCache.clear()
        cachedActiveGameData = null
        NativeApp.achievementsLogout()
        NativeApp.achievementsUnloadGame()
        scope.launch {
            preferences.setRetroAchievementsUsername(null)
            preferences.setRetroAchievementsToken(null)
        }
    }

    fun setEnabled(enabled: Boolean) = scope.launch { preferences.setRetroAchievementsEnabled(enabled) }

    fun setHardcore(enabled: Boolean) = scope.launch { preferences.setRetroAchievementsHardcore(enabled) }

    fun setUnofficial(enabled: Boolean) = scope.launch { preferences.setRetroAchievementsUnofficial(enabled) }

    fun setEncore(enabled: Boolean) = scope.launch { preferences.setRetroAchievementsEncore(enabled) }

    companion object {
        private const val ACCOUNT_PROGRESS_TTL_MS = 15L * 60L * 1000L

        @Volatile
        private var instance: RetroAchievementsRepository? = null

        fun get(context: Context): RetroAchievementsRepository {
            return instance ?: synchronized(this) {
                instance ?: RetroAchievementsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
