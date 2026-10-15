package com.sbro.emucoreh.data.boxart

import com.sbro.emucoreh.data.GameItem
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Publishes every fast catalog result before any library-wide disc scraping. */
internal data class InitialCoverSyncResult(val deferred: Boolean, val canUpgradeOnline: Boolean)

internal suspend fun syncInitialCovers(
    games: List<GameItem>,
    networkAvailable: Boolean,
    shouldPause: () -> Boolean,
    fetch: suspend (GameItem, CoverFetchMode) -> String?,
    publish: (GameItem, String) -> Unit,
    isFinalCover: (String) -> Boolean = { true },
    allowRepository: () -> Boolean = { true }
): InitialCoverSyncResult {
    val deferred = AtomicBoolean(false)
    val resolved = ConcurrentHashMap.newKeySet<String>()
    val semaphore = Semaphore(3)
    suspend fun pass(mode: CoverFetchMode) = coroutineScope {
        games.forEach { game ->
            launch {
                semaphore.withPermit {
                    if (shouldPause()) deferred.set(true)
                    else if (mode == CoverFetchMode.REPOSITORY_ONLY && !allowRepository()) return@withPermit
                    else if (mode == CoverFetchMode.OFFLINE_ONLY && game.path in resolved) return@withPermit
                    else fetch(game, mode)?.let { path ->
                        publish(game, path)
                        if (mode == CoverFetchMode.REPOSITORY_ONLY && isFinalCover(path)) resolved.add(game.path)
                    }
                }
            }
        }
    }
    if (networkAvailable) pass(CoverFetchMode.REPOSITORY_ONLY)
    pass(CoverFetchMode.OFFLINE_ONLY)
    return InitialCoverSyncResult(deferred.get(), networkAvailable && allowRepository())
}
