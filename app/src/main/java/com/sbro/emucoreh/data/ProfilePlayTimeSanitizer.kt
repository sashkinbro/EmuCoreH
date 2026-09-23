package com.sbro.emucoreh.data

import java.util.Locale

internal data class LegacyAutotestGameCleanup(
    val remainingGames: Map<String, Any?>,
    val removedGames: Map<String, Map<String, Any?>>,
    val removedPlayTimeMs: Long
)

internal fun isAutotestPlayTimeEntry(entry: PlayerPlayTimeDelta): Boolean {
    val normalizedPath = entry.gamePath
        .orEmpty()
        .replace('\\', '/')
        .lowercase(Locale.ROOT)
    return normalizedPath.contains("/autotest-elf/")
}

internal fun sanitizeLegacyAutotestGames(games: Map<String, Any?>): LegacyAutotestGameCleanup {
    val removedGames = games.mapNotNull { (gameKey, rawGame) ->
        val game = rawGame.asProfileGameMap()
        if (isLegacyAutotestGame(gameKey, game)) gameKey to game else null
    }.toMap()
    return LegacyAutotestGameCleanup(
        remainingGames = games.filterKeys { it !in removedGames },
        removedGames = removedGames,
        removedPlayTimeMs = removedGames.values.sumOf { it.profileLongValue("ms") }
    )
}

private fun isLegacyAutotestGame(gameKey: String, game: Map<String, Any?>): Boolean {
    // Autotest ELF entries created by the old timer have a path-derived key, no disc serial,
    // positive play time, and zero sessions. Canonical tests cover every affected profile entry
    // while preserving serial games and normally launched homebrew ELF sessions.
    return gameKey.startsWith("P_") &&
        (game["s"] as? String).isNullOrBlank() &&
        game.profileLongValue("ms") > 0L &&
        game.profileLongValue("n") == 0L
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asProfileGameMap(): Map<String, Any?> =
    this as? Map<String, Any?> ?: emptyMap()

internal fun Map<String, Any?>.profileLongValue(key: String): Long {
    return when (val value = this[key]) {
        is Number -> value.toLong()
        else -> 0L
    }
}
