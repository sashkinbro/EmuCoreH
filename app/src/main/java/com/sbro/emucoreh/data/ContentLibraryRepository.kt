package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.EmulatorBridge
import kotlinx.coroutines.flow.first
import java.util.Locale

data class SelectedGameIdentity(
    val serial: String?,
    val crc: String?
)

class ContentLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val cache = GameLibraryCacheRepository(appContext)

    suspend fun loadGames(): List<GameItem> {
        val paths = preferences.gamePaths.first()
        if (paths.isEmpty()) return emptyList()
        val preferEnglish = preferences.preferEnglishGameTitles.first()
        return cache.loadSnapshot(GameLibraryCacheRepository.libraryKey(paths), preferEnglish)
            .games
            .filter { !it.serial.isNullOrBlank() }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    fun resolveIdentity(game: GameItem): SelectedGameIdentity {
        val metadata = runCatching { EmulatorBridge.getGameMetadata(game.path) }.getOrNull()
        val serial = metadata?.serial?.normalizeGameSerial() ?: game.serial?.normalizeGameSerial()
        val crc = metadata?.serialWithCrc
            ?.let(CRC_PATTERN::find)
            ?.value
            ?.uppercase(Locale.US)
        return SelectedGameIdentity(serial = serial, crc = crc)
    }

    private companion object {
        val CRC_PATTERN = Regex("(?i)(?<![0-9A-F])[0-9A-F]{8}(?![0-9A-F])")
    }
}

/**
 * Dreamcast product codes come from the disc IP.BIN (for example `MK-51035` or
 * `T-9701N`). Flycast trims trailing whitespace and turns spaces into
 * underscores when it builds the game id, so the canonical form keeps the
 * separator untouched.
 */
private fun String.normalizeGameSerial(): String? {
    val candidate = trim().uppercase(Locale.US).replace(' ', '_')
    val match = Regex("\\b[A-Z0-9]{1,4}[-_][0-9]{3,5}[A-Z]?\\b").find(candidate) ?: return null
    return match.value
}
