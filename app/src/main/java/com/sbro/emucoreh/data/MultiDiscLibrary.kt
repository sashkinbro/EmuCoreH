package com.sbro.emucoreh.data

import com.sbro.emucoreh.core.GameFormats
import com.sbro.emucoreh.core.EmulationPlatform
import java.io.File
import java.util.Locale

/** Groups physical images without changing their launch paths or edition tags. */
object MultiDiscLibrary {
    private val discMarker = Regex(
        """\(\s*(?:disc|disk|cd)\s*(\d+)(?:\s*(?:of|/)\s*\d+)?\s*\)|\[\s*(?:disc|disk|cd)\s*(\d+)(?:\s*(?:of|/)\s*\d+)?\s*]|(?<![\p{L}\p{N}])(?:disc|disk|cd)[\s_-]*(\d+)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )
    private val whitespace = Regex("\\s+")
    private val absoluteReference = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** Playlist values must be actual scanned image paths, in playlist order. */
    fun group(
        games: List<GameItem>,
        playlistMembers: Map<String, List<String>> = emptyMap(),
        parentIdentities: Map<String, String> = emptyMap(),
        identity: (String) -> String = GameRepository::libraryIdentity
    ): List<GameItem> {
        val byPath = games.groupBy { it.path }
        val replacements = linkedMapOf<String, GameItem>()
        val consumed = mutableSetOf<String>()
        games.filter { it.discs.size >= 2 }.sortedBy { extension(it.fileName) != "m3u" }.forEach { existing ->
            if (identity(existing.path) in consumed) return@forEach
            replacements[identity(existing.path)] = existing
            consumed.addAll(existing.discs.map { identity(it.path) }.filter { it != identity(existing.path) })
        }
        games.filter { extension(it.fileName) == "m3u" }.forEach { playlist ->
            val paths = playlistMembers[playlist.path] ?: return@forEach
            if (paths.size < 2 || paths.distinct().size != paths.size) return@forEach
            val members = paths.map { path ->
                byPath[path]?.singleOrNull()?.takeIf { GameFormats.isDiscImageName(it.fileName) }
            }
            if (members.any { it == null }) return@forEach
            val physical = members.filterNotNull()
            val platforms = physical.map { EmulationPlatform.fromName(it.platform) }.filterNotNull().distinct()
            if (platforms.size > 1) return@forEach
            val platform = platforms.singleOrNull()?.name
            if (playlist.platform != null && platform != null && playlist.platform != platform) return@forEach
            replacements[identity(playlist.path)] = playlist.copy(
                platform = playlist.platform ?: platform,
                serial = playlist.serial ?: physical.first().serial,
                coverArtPath = playlist.coverArtPath ?: physical.firstNotNullOfOrNull { it.coverArtPath },
                discSize = physical.sumOf { (it.discSize ?: it.fileSize).coerceAtLeast(0L) },
                discs = physical.mapIndexed { index, game -> game.toDisc(index + 1) }
            )
            consumed.addAll(paths.map(identity))
        }

        data class Candidate(val game: GameItem, val number: Int, val key: String)
        val candidates = games.mapNotNull { game ->
            if (identity(game.path) in consumed || identity(game.path) in replacements ||
                !GameFormats.isDiscImageName(game.fileName)) return@mapNotNull null
            val stem = game.fileName.substringBeforeLast('.')
            val marker = discMarker.findAll(stem).toList().singleOrNull() ?: return@mapNotNull null
            val number = marker.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: return@mapNotNull null
            val parent = parentIdentities[game.path] ?: localParent(game.path) ?: return@mapNotNull null
            val name = withoutDiscMarker(stem).lowercase(Locale.ROOT)
            if (name.isEmpty()) return@mapNotNull null
            val platform = EmulationPlatform.fromName(game.platform) ?: EmulationPlatform.DREAMCAST
            Candidate(game, number, "$parent\u0000${platform.name}\u0000${extension(game.fileName)}\u0000$name")
        }
        candidates.groupBy { it.key }.values.forEach { group ->
            if (group.size < 2 || group.map { it.number }.distinct().size != group.size) return@forEach
            val ordered = group.sortedBy { it.number }
            val primary = ordered.first().game
            replacements[identity(primary.path)] = primary.copy(
                title = withoutDiscMarker(primary.title),
                coverArtPath = primary.coverArtPath ?: ordered.firstNotNullOfOrNull { it.game.coverArtPath },
                discSize = ordered.sumOf { (it.game.discSize ?: it.game.fileSize).coerceAtLeast(0L) },
                discs = ordered.map { it.game.toDisc(it.number) }
            )
            consumed.addAll(ordered.drop(1).map { identity(it.game.path) })
        }
        return games.mapNotNull { game ->
            replacements[identity(game.path)] ?: game.takeUnless { identity(it.path) in consumed }
        }.distinctBy { identity(it.path) }
    }

    fun parsePlaylist(contents: String): List<String> = contents.removePrefix("\uFEFF")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .map { line ->
            if (line.length >= 2 && ((line.first() == '"' && line.last() == '"') ||
                    (line.first() == '\'' && line.last() == '\''))) line.substring(1, line.lastIndex) else line
        }
        .toList()

    /** Resolve names within the scanned tree; callers map the result to real entries. */
    fun resolveRelativeSegments(parent: List<String>, reference: String): List<String>? {
        val normalized = reference.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith('/') || absoluteReference.containsMatchIn(normalized)) return null
        val segments = parent.toMutableList()
        normalized.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.takeIf { it.isNotEmpty() }
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun localParent(path: String): String? = if (path.startsWith("content://")) null else
        runCatching { File(path).canonicalFile.parentFile?.path }.getOrNull()

    private fun withoutDiscMarker(value: String): String = discMarker.replace(value, " ")
        .replace(whitespace, " ").trim(' ', '-', '_')
}

fun GameItem.launchDiscs(): List<GameDisc> = discs.ifEmpty { listOf(toDisc(1)) }

fun GameDisc.asGameItem(group: GameItem): GameItem = group.copy(
    path = path,
    fileName = fileName,
    serial = serial,
    fileSize = fileSize,
    lastModified = lastModified,
    discSize = discSize,
    discs = emptyList()
)

private fun GameItem.toDisc(number: Int): GameDisc = GameDisc(
    path = path, fileName = fileName, number = number, serial = serial,
    fileSize = fileSize, lastModified = lastModified, discSize = discSize
)
