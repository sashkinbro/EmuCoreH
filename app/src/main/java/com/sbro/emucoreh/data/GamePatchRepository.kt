package com.sbro.emucoreh.data

import android.content.Context
import com.sbro.emucoreh.core.EmulatorStorage
import java.io.File
import java.util.Locale

enum class GamePatchKind {
    WIDESCREEN,
    NO_INTERLACING,
    GENERAL
}

data class GamePatchEntry(
    val file: File,
    val kind: GamePatchKind,
    val aspectRatioOverride: String? = null,
    val disableWidescreenHack: Boolean = false,
    val author: String? = null
)

class GamePatchRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val cheatRepository = CheatRepository(appContext)

    fun patchesDirectory(): File =
        EmulatorStorage.patchesDir(appContext, preferences.getEmulatorDataPathSync())

    fun findPatchFiles(serial: String?, crc: String?): List<GamePatchEntry> {
        val serialKey = serial?.let(::compactKey)?.takeIf { it.length >= 8 }
        val crcKey = crc?.let(::compactKey)?.takeIf { it.length == 8 }
        if (serialKey == null && crcKey == null) return emptyList()
        return patchesDirectory()
            .listFiles { file ->
                file.isFile && file.extension.lowercase(Locale.US) in SUPPORTED_PATCH_EXTENSIONS
            }
            .orEmpty()
            .filter { file ->
                val key = compactKey(file.nameWithoutExtension)
                (serialKey != null && key.startsWith(serialKey)) ||
                    (crcKey != null && key.contains(crcKey))
            }
            .map { file -> entryFor(file) }
            .sortedBy { it.file.name.lowercase(Locale.US) }
    }

    private fun entryFor(file: File): GamePatchEntry {
        val header = runCatching {
            file.bufferedReader().use { reader ->
                buildString {
                    repeat(80) {
                        val line = reader.readLine() ?: return@repeat
                        appendLine(line)
                    }
                }
            }
        }.getOrDefault("")
        val aspect = Regex("(?im)^\\s*OverrideAspectRatio\\s*=\\s*(\\S+)")
            .find(header)?.groupValues?.get(1)?.trim()
        val disableHack = Regex("(?im)^\\s*DisableWidescreenRendering\\s*=\\s*(true|1)\\s*$")
            .containsMatchIn(header)
        val author = Regex("(?im)^\\s*Author\\s*=\\s*(.+)$")
            .find(header)?.groupValues?.get(1)?.trim()
        return GamePatchEntry(
            file = file,
            kind = classify(file.nameWithoutExtension),
            aspectRatioOverride = aspect,
            disableWidescreenHack = disableHack,
            author = author
        )
    }

    data class GamePatchDirectives(
        val aspectRatioOverride: String? = null,
        val disableWidescreenHack: Boolean = false,
        val author: String? = null
    )

    fun resolveDirectives(
        serial: String?,
        crc: String?,
        widescreen: Boolean,
        noInterlacing: Boolean
    ): GamePatchDirectives {
        if (!widescreen && !noInterlacing) return GamePatchDirectives()
        val entries = findPatchFiles(serial, crc).filter { entry ->
            when (entry.kind) {
                GamePatchKind.WIDESCREEN -> widescreen
                GamePatchKind.NO_INTERLACING -> noInterlacing
                GamePatchKind.GENERAL -> widescreen || noInterlacing
            }
        }
        return GamePatchDirectives(
            aspectRatioOverride = entries.firstNotNullOfOrNull { it.aspectRatioOverride },
            disableWidescreenHack = entries.any { it.disableWidescreenHack },
            author = entries.firstNotNullOfOrNull { it.author }
        )
    }

    fun buildPatchBlocks(
        serial: String?,
        crc: String?,
        widescreen: Boolean,
        noInterlacing: Boolean
    ): List<CheatBlock> {
        if (!widescreen && !noInterlacing) return emptyList()
        val entries = findPatchFiles(serial, crc).filter { entry ->
            when (entry.kind) {
                GamePatchKind.WIDESCREEN -> widescreen
                GamePatchKind.NO_INTERLACING -> noInterlacing
                GamePatchKind.GENERAL -> widescreen || noInterlacing
            }
        }
        val merged = linkedMapOf<String, CheatBlock>()
        entries.forEach { entry ->
            val blocks = runCatching { cheatRepository.parsePatchBlocks(entry.file.readText()) }
                .getOrDefault(emptyList())
            blocks.forEach { block ->
                val signature = block.title.trim().lowercase(Locale.US) + "\u0000" +
                    block.author?.trim().orEmpty() + "\u0000" +
                    block.lines.joinToString("\n")
                merged.putIfAbsent(signature, block)
            }
        }
        return merged.values.toList()
    }

    private fun classify(baseName: String): GamePatchKind {
        val tokens = baseName.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).toSet()
        val noInterlacing = tokens.any { it == "ni" || it == "progressive" || it == "interlace" } ||
            baseName.contains("interlac", ignoreCase = true)
        val widescreen = tokens.any { it == "ws" || it == "wide" || it == "widescreen" } ||
            baseName.contains("widescreen", ignoreCase = true)
        return when {
            widescreen && !noInterlacing -> GamePatchKind.WIDESCREEN
            noInterlacing && !widescreen -> GamePatchKind.NO_INTERLACING
            widescreen && noInterlacing -> GamePatchKind.WIDESCREEN
            else -> GamePatchKind.GENERAL
        }
    }

    private fun compactKey(value: String): String =
        value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")

    companion object {
        private val SUPPORTED_PATCH_EXTENSIONS = setOf("pnach", "cht")
    }
}
