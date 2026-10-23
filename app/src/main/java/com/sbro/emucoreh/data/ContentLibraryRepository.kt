package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.EmulatorBridge
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.core.SegaPlatformDetector
import com.sbro.emucoreh.data.catalog.ArcadeCatalogRepository
import kotlinx.coroutines.flow.first
import java.util.Locale

data class SelectedGameIdentity(
    val serial: String?,
    val crc: String?,
    /** Product code or romset name exactly as the core reports it. */
    val rawSerial: String? = null
)

class ContentLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val cache = GameLibraryCacheRepository(appContext)
    private val arcadeCatalog by lazy { ArcadeCatalogRepository(appContext) }

    suspend fun loadGames(includeSega: Boolean = false): List<GameItem> {
        val paths = preferences.gamePaths.first()
        if (paths.isEmpty()) return emptyList()
        val preferEnglish = preferences.preferEnglishGameTitles.first()
        return cache.loadSnapshot(GameLibraryCacheRepository.libraryKey(paths), preferEnglish)
            .games
            .filter { includeSega || !it.isSegaLibraryEntry() }
            .filter { (includeSega && it.isSegaLibraryEntry()) || !it.serial.isNullOrBlank() || arcadeCatalog.isKnownRomset(it.fileName) }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    fun resolveIdentity(game: GameItem): SelectedGameIdentity {
        if (game.isSegaLibraryEntry()) return SelectedGameIdentity(
            serial = SegaCheatIdentity.key(game.path, game.platform ?: SegaPlatformDetector.detectName(game.fileName).name),
            crc = null,
            rawSerial = game.serial
        )
        val metadata = runCatching { EmulatorBridge.getGameMetadata(game.path) }.getOrNull()
        val rawSerial = metadata?.serial?.trim()?.takeIf { it.isNotBlank() }
            ?: game.serial?.trim()?.takeIf { it.isNotBlank() }
            ?: arcadeRomsetFor(game)
        // Arcade romset names and compact/revision product codes do not parse
        // as Dreamcast serials, so keep the raw value instead of dropping it.
        val serial = rawSerial?.normalizeGameSerial() ?: rawSerial
        val crc = metadata?.serialWithCrc
            ?.let(CRC_PATTERN::find)
            ?.value
            ?.uppercase(Locale.US)
        return SelectedGameIdentity(serial = serial, crc = crc, rawSerial = rawSerial)
    }

    /** Romset name of a library archive, the id Flycast uses for arcade games. */
    private fun arcadeRomsetFor(game: GameItem): String? {
        val romset = ArcadeCatalogRepository.romsetKey(game.fileName) ?: return null
        return romset.takeIf { arcadeCatalog.isKnownRomset(it) }
    }

    /**
     * The cache stores an explicit platform for Sega content. Never re-open a
     * multi-gigabyte disc just to classify a legacy Dreamcast/arcade entry;
     * the filename is enough for these manager lists and keeps both screens
     * responsive.
     */
    private fun GameItem.isSegaLibraryEntry(): Boolean =
        EmulationPlatform.fromName(platform)?.isSega
            ?: SegaPlatformDetector.detectName(fileName).isSega

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
private fun String.normalizeGameSerial(): String? = DiscSerial.normalize(this)
