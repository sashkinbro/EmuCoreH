package com.sbro.emucoreh.data

internal fun flattenLibraryGroups(games: List<GameItem>): List<GameItem> = games.flatMap { group ->
    listOf(group) + group.discs.filter { it.path != group.path }.map { it.asGameItem(group) }
}

internal fun recentLibraryGroups(
    games: List<GameItem>,
    recentPaths: List<String>,
    identity: (String) -> String = GameRepository::libraryIdentity
): List<GameItem> {
    val byPath = buildMap {
        games.forEach { group ->
            put(identity(group.path), group)
            group.discs.forEach { put(identity(it.path), group) }
        }
    }
    return recentPaths.mapNotNull { byPath[identity(it)] }.distinctBy { identity(it.path) }
}
