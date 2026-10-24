package com.sbro.emucoreh.ui.home

import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.LibraryPlatform
import java.text.Normalizer
import java.util.Locale

enum class HomeSortOption {
    TITLE_ASC, TITLE_DESC, RECENT_DESC, RECENT_ASC, SIZE_DESC, SIZE_ASC, PLATFORM;

    companion object {
        fun fromPreference(value: String?): HomeSortOption = entries.firstOrNull { it.name == value } ?: PLATFORM
    }
}

data class HomeLibrarySection(val platform: LibraryPlatform?, val games: List<GameItem>)

internal fun buildHomeLibrarySections(
    games: List<GameItem>,
    sortOption: HomeSortOption,
    platformFor: (GameItem) -> LibraryPlatform
): List<HomeLibrarySection> {
    if (games.isEmpty()) return emptyList()
    if (sortOption == HomeSortOption.PLATFORM) {
        val byPlatform = games.groupBy(platformFor)
        return LibraryPlatform.entries.mapNotNull { platform ->
            byPlatform[platform]?.let { HomeLibrarySection(platform, it.sortedByTitle()) }
        }
    }
    val keyed = games.map(::HomeGameSortKey)
    val comparator = when (sortOption) {
        HomeSortOption.TITLE_ASC -> titleAscending
        HomeSortOption.TITLE_DESC -> titleDescending
        HomeSortOption.RECENT_DESC -> compareByDescending<HomeGameSortKey> { it.game.lastModified }
            .then(titleAscending)
        HomeSortOption.RECENT_ASC -> compareBy<HomeGameSortKey> { it.game.lastModified }
            .then(titleAscending)
        HomeSortOption.SIZE_DESC -> compareByDescending<HomeGameSortKey> {
            it.game.discSize ?: it.game.fileSize
        }.then(titleAscending)
        HomeSortOption.SIZE_ASC -> compareBy<HomeGameSortKey> {
            it.game.discSize ?: it.game.fileSize
        }.then(titleAscending)
        HomeSortOption.PLATFORM -> error("Platform sorting is handled above")
    }
    return listOf(HomeLibrarySection(null, keyed.sortedWith(comparator).map { it.game }))
}

/** Normalizes each title once; sorting otherwise repeats this work per comparison. */
private class HomeGameSortKey(game: GameItem) {
    val game = game
    val title = sortToken(game.title)
    val fileName = sortToken(game.fileName)
}

private val titleAscending = compareBy<HomeGameSortKey> { it.title }
    .thenBy { it.fileName }.thenBy { it.game.path }

private val titleDescending = compareByDescending<HomeGameSortKey> { it.title }
    .thenByDescending { it.fileName }.thenBy { it.game.path }

private fun List<GameItem>.sortedByTitle(): List<GameItem> =
    map(::HomeGameSortKey).sortedWith(titleAscending).map { it.game }

private fun sortToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .lowercase(Locale.ROOT).trim()
