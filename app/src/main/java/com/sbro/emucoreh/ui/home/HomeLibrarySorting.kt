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
    val titleOrder = compareBy<GameItem> { sortToken(it.title) }
        .thenBy { sortToken(it.fileName) }.thenBy { it.path }
    if (sortOption == HomeSortOption.PLATFORM) {
        val byPlatform = games.groupBy(platformFor)
        return LibraryPlatform.entries.mapNotNull { platform ->
            byPlatform[platform]?.let { HomeLibrarySection(platform, it.sortedWith(titleOrder)) }
        }
    }
    val comparator = when (sortOption) {
        HomeSortOption.TITLE_ASC -> titleOrder
        HomeSortOption.TITLE_DESC -> compareByDescending<GameItem> { sortToken(it.title) }
            .thenByDescending { sortToken(it.fileName) }.thenBy { it.path }
        HomeSortOption.RECENT_DESC -> compareByDescending<GameItem> { it.lastModified }.then(titleOrder)
        HomeSortOption.RECENT_ASC -> compareBy<GameItem> { it.lastModified }.then(titleOrder)
        HomeSortOption.SIZE_DESC -> compareByDescending<GameItem> { it.discSize ?: it.fileSize }.then(titleOrder)
        HomeSortOption.SIZE_ASC -> compareBy<GameItem> { it.discSize ?: it.fileSize }.then(titleOrder)
        HomeSortOption.PLATFORM -> error("Platform sorting is handled above")
    }
    return listOf(HomeLibrarySection(null, games.sortedWith(comparator)))
}

private fun sortToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .lowercase(Locale.ROOT).trim()
