package com.sbro.emucoreh.data

import android.content.Context
import android.util.AtomicFile
import com.sbro.emucoreh.core.EmulatorStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class CheatBlock(
    val id: String,
    val title: String,
    val lines: List<String>,
    val enabled: Boolean,
    val author: String? = null
)

data class CheatGameConfig(
    val gameKey: String,
    val serial: String,
    val crc: String?,
    val sourceFileName: String?,
    val blocks: List<CheatBlock>
)

data class CheatFileEntry(
    val gameKey: String,
    val fileName: String,
    val displayName: String,
    val blockCount: Int
)

class CheatRepository(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val importedDir = EmulatorStorage.importedCheatsDir(context)
    private val stateFile = File(EmulatorStorage.appStateDir(context), "cheat-state.json")
    private val activeTargetsFile = File(EmulatorStorage.appStateDir(context), "cheat-active-targets.json")

    fun getGameConfig(gameKey: String, serial: String, crc: String?): CheatGameConfig? {
        val sourceFile = resolveImportedFile(gameKey)
        if (!sourceFile.exists()) return null
        val normalizedGameKey = sourceFile.nameWithoutExtension
        val raw = runCatching { sourceFile.readText() }.getOrNull() ?: return null
        val enabledIds = storedValues(loadEnabledIds(), normalizedGameKey, gameKey)
        val blocks = parseCheatBlocks(raw).map { it.copy(enabled = enabledIds.contains(it.id)) }
        return CheatGameConfig(
            gameKey = normalizedGameKey,
            serial = serial,
            crc = crc,
            sourceFileName = sourceFile.name,
            blocks = blocks
        )
    }

    fun getGameConfig(gameKeys: List<String>, serial: String, crc: String?): CheatGameConfig? {
        return gameKeys.firstNotNullOfOrNull { gameKey ->
            getGameConfig(gameKey, serial, crc)
        }
    }

    fun listImportedCheatFiles(): List<CheatFileEntry> {
        return importedDir.listFiles { file -> file.isFile && file.extension.equals("pnach", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { file ->
                val raw = runCatching { file.readText() }.getOrDefault("")
                CheatFileEntry(
                    gameKey = file.nameWithoutExtension,
                    fileName = file.name,
                    displayName = file.nameWithoutExtension.replace('_', ' '),
                    blockCount = parseCheatBlocks(raw).size
                )
            }
            .orEmpty()
    }

    fun getImportedCheatText(gameKey: String): String? {
        val file = resolveImportedFile(gameKey)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun updateImportedCheatText(gameKey: String, contents: String): Int {
        return importCheatFile(gameKey = gameKey, contents = contents)
    }

    fun importCheatFile(
        gameKey: String,
        contents: String,
        enableAllByDefault: Boolean = false,
        mergeWithExisting: Boolean = false
    ): Int = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val target = importedFile(normalizedGameKey)
        val state = loadEnabledIds()
        val old = storedValues(state, normalizedGameKey, gameKey)
        val existingBlocks = if (target.exists()) {
            runCatching { parseCheatBlocks(target.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val oldEnabledSignatures = existingBlocks
                .filter { old.contains(it.id) }
                .map(::cheatBlockSignature)
                .toSet()
        val importedBlocks = parseCheatBlocks(contents)
        if (importedBlocks.isEmpty()) return@synchronized 0
        val blocks = if (mergeWithExisting) {
            mergeCheatBlocks(existingBlocks, importedBlocks)
        } else {
            importedBlocks
        }
        val storedContents = if (mergeWithExisting) serializeCheatBlocks(blocks) else contents
        target.parentFile?.mkdirs()
        writeTextAtomically(target, storedContents)
        val enabledIds = if (enableAllByDefault) {
            blocks.map { it.id }
        } else {
            blocks.filter { old.contains(it.id) || cheatBlockSignature(it) in oldEnabledSignatures }
                .map { it.id }
        }
        state.put(normalizedGameKey, JSONArray(enabledIds))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
        blocks.size
    }

    private fun mergeCheatBlocks(
        existing: List<CheatBlock>,
        imported: List<CheatBlock>
    ): List<CheatBlock> {
        val merged = linkedMapOf<String, CheatBlock>()
        (existing + imported).forEach { block ->
            val key = block.title.trim().lowercase(Locale.US)
            val previous = merged[key]
            merged[key] = if (previous == null) {
                block
            } else {
                previous.copy(
                    lines = (previous.lines + block.lines).distinct(),
                    author = previous.author ?: block.author
                )
            }
        }
        return merged.values.toList()
    }

    private fun serializeCheatBlocks(blocks: List<CheatBlock>): String = buildString {
        blocks.forEachIndexed { index, block ->
            if (index > 0) append('\n')
            append("// ").append(block.title).append('\n')
            block.author?.takeIf { it.isNotBlank() }?.let { author ->
                append("Author = ").append(author).append('\n')
            }
            block.lines.forEach { line -> append(line).append('\n') }
        }
    }

    fun setEnabledBlocks(gameKey: String, enabledIds: Set<String>) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    /**
     * Adds or removes a single block from the stored enabled set. Reads the
     * latest state from disk first so toggles made in another screen (the cheat
     * manager vs. the in-game menu) are never overwritten with a stale copy.
     */
    fun setBlockEnabled(gameKey: String, blockId: String, enabled: Boolean) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        val enabledIds = storedValues(state, normalizedGameKey, gameKey).toMutableSet()
        if (enabled) enabledIds.add(blockId) else enabledIds.remove(blockId)
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    /** Enables or disables a whole group of blocks with the same read-modify-write. */
    fun setBlocksEnabled(gameKey: String, blockIds: Collection<String>, enabled: Boolean) = synchronized(CHEAT_IO_LOCK) {
        val normalizedGameKey = normalizeGameKey(gameKey)
        val state = loadEnabledIds()
        val enabledIds = storedValues(state, normalizedGameKey, gameKey).toMutableSet()
        if (enabled) enabledIds.addAll(blockIds) else enabledIds.removeAll(blockIds.toSet())
        state.put(normalizedGameKey, JSONArray(enabledIds.toList()))
        if (normalizedGameKey != gameKey) state.remove(gameKey)
        writeEnabledIds(state)
    }

    fun syncActiveCheats(
        gameKey: String,
        serial: String?,
        crc: String?,
        includeCheats: Boolean = true,
        patchBlocks: List<CheatBlock> = emptyList()
    ) = synchronized(CHEAT_IO_LOCK) {
        val source = resolveImportedFile(gameKey)
        val normalizedGameKey = source.nameWithoutExtension
        val normalizedCrc = effectiveCrc(crc, serial, normalizedGameKey)
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val enabledCheatBlocks = if (source.exists() && includeCheats) {
            val enabledIds = storedValues(loadEnabledIds(), normalizedGameKey, gameKey)
            runCatching { parseCheatBlocks(source.readText()) }
                .getOrDefault(emptyList())
                .filter { enabledIds.contains(it.id) }
        } else {
            emptyList()
        }
        val blocks = (enabledCheatBlocks + patchBlocks).distinctBy(::cheatBlockSignature)
        val candidates = activeCheatFileCandidates(normalizedSerial, normalizedCrc)
        val target = canonicalActiveCheatFile(normalizedSerial, normalizedCrc)
        if (blocks.isEmpty()) {
            val stale = (candidates + recordedActiveCheatFiles(normalizedGameKey))
                .distinctBy(File::getAbsolutePath)
            stale.forEach { if (it.exists()) it.delete() }
            stale.map(::coreCheatFile).distinctBy(File::getAbsolutePath)
                .forEach { if (it.exists()) it.delete() }
            clearRecordedActiveCheatFiles(normalizedGameKey)
            return@synchronized
        }
        activeDir().mkdirs()
        val contents = buildString {
            blocks.forEach { block ->
                append("// ").append(cheatBlockLabel(block)).append('\n')
                block.lines.forEach { append(it).append('\n') }
                append('\n')
            }
        }.trim() + "\n"
        candidates.filterNot { it == target }.forEach { if (it.exists()) it.delete() }
        recordedActiveCheatFiles(normalizedGameKey).filterNot { it == target }.forEach { if (it.exists()) it.delete() }
        candidates.filterNot { it == target }.map(::coreCheatFile).forEach { if (it.exists()) it.delete() }
        recordedActiveCheatFiles(normalizedGameKey).filterNot { it == target }.map(::coreCheatFile)
            .forEach { if (it.exists()) it.delete() }
        writeTextAtomically(target, contents)
        recordActiveCheatFile(normalizedGameKey, target)
        val coreTarget = coreCheatFile(target)
        val coreContents = buildCoreCheatContents(blocks)
        if (coreContents == null) {
            if (coreTarget.exists()) coreTarget.delete()
        } else {
            writeTextAtomically(coreTarget, coreContents)
        }
    }

    /** PPSSPP CWCheat file matching the active imported cheat selection. */
    fun activeCoreCheatFile(gameKey: String, serial: String?, crc: String?): File? {
        val normalizedCrc = effectiveCrc(crc, serial, normalizeGameKey(gameKey))
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val canonical = coreCheatFile(canonicalActiveCheatFile(normalizedSerial, normalizedCrc))
        if (canonical.exists()) return canonical
        return recordedActiveCheatFiles(normalizeGameKey(gameKey))
            .map(::coreCheatFile)
            .firstOrNull { it.exists() }
    }

    /**
     * Disc CRC is only known while a session is running, so imported cheats are
     * still usable outside a game by falling back to the serial or game key.
     */
    private fun effectiveCrc(crc: String?, serial: String?, gameKey: String): String {
        crc?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { return it }
        serial?.trim()?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")?.takeIf { it.isNotBlank() }?.let { return it }
        return normalizeGameKey(gameKey).uppercase().takeIf { it.isNotBlank() } ?: "UNKNOWN"
    }

    fun deleteImportedCheats(gameKey: String, serial: String?, crc: String?) = synchronized(CHEAT_IO_LOCK) {
        val source = resolveImportedFile(gameKey)
        val normalizedGameKey = source.nameWithoutExtension
        source.delete()
        setEnabledBlocks(normalizedGameKey, emptySet())
        recordedActiveCheatFiles(normalizedGameKey).forEach { if (it.exists()) it.delete() }
        recordedActiveCheatFiles(normalizedGameKey).map(::coreCheatFile)
            .forEach { if (it.exists()) it.delete() }
        clearRecordedActiveCheatFiles(normalizedGameKey)
        val inferred = inferSerialAndCrc(normalizedGameKey)
        val normalizedCrc = crc?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: inferred?.second
            ?: return@synchronized
        val normalizedSerial = serial?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: inferred?.first
        activeCheatFileCandidates(normalizedSerial, normalizedCrc).forEach { if (it.exists()) it.delete() }
        activeCheatFileCandidates(normalizedSerial, normalizedCrc).map(::coreCheatFile)
            .forEach { if (it.exists()) it.delete() }
    }

    fun exportJson(): JSONObject {
        return JSONObject().put("enabled", loadEnabledIds())
    }

    fun importJson(json: JSONObject) {
        writeEnabledIds(json.optJSONObject("enabled") ?: JSONObject())
    }

    private fun importedFile(gameKey: String): File = File(importedDir, "${sanitizeFileName(gameKey)}.pnach")

    private fun resolveImportedFile(gameKey: String): File {
        // Serial-style keys reach this point with either '-' or '_' as the
        // separator depending on whether they came from the imported file
        // name, the game metadata or the cheat catalog. Try every spelling
        // before giving up, otherwise an existing pack is ignored (and its
        // active cheats get cleared) on a mere formatting difference.
        val exact = importedFile(normalizeGameKey(gameKey))
        val variants = linkedSetOf(
            exact.nameWithoutExtension,
            sanitizeFileName(gameKey.replace('-', '_')),
            sanitizeFileName(gameKey.replace('_', '-'))
        )
        for (variant in variants) {
            val candidate = importedFile(variant)
            val matches = importedDir.listFiles { file ->
                file.isFile && file.extension.equals("pnach", ignoreCase = true) &&
                    file.nameWithoutExtension.equals(candidate.nameWithoutExtension, ignoreCase = true)
            }.orEmpty()
            matches.firstOrNull { it.name == candidate.name }?.let { return it }
            matches.firstOrNull()?.let { return it }
        }
        return exact
    }

    private fun loadEnabledIds(): JSONObject {
        if (!stateFile.exists()) return JSONObject()
        return runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeEnabledIds(json: JSONObject) {
        writeTextAtomically(stateFile, json.toString())
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun normalizeGameKey(gameKey: String): String = sanitizeFileName(gameKey).ifBlank { "cheat" }

    private fun inferSerialAndCrc(gameKey: String): Pair<String?, String>? {
        val serialAndCrc = Regex("^([A-Z]{4}[-_]?\\d{5})[_-]([0-9A-F]{8})$", RegexOption.IGNORE_CASE)
            .matchEntire(gameKey)
        if (serialAndCrc != null) {
            return serialAndCrc.groupValues[1].uppercase().replace('_', '-') to
                serialAndCrc.groupValues[2].uppercase()
        }
        val crcOnly = gameKey.uppercase().takeIf { it.matches(Regex("[0-9A-F]{8}")) }
        return crcOnly?.let { null to it }
    }

    private fun storedValues(state: JSONObject, normalizedKey: String, legacyKey: String): Set<String> {
        val caseInsensitiveKey = state.keys().asSequence().firstOrNull { key ->
            key.equals(normalizedKey, ignoreCase = true) || key.equals(legacyKey, ignoreCase = true)
        }
        return (state.optJSONArray(normalizedKey)
            ?: state.optJSONArray(legacyKey)
            ?: caseInsensitiveKey?.let(state::optJSONArray))
            ?.toStringSet()
            .orEmpty()
    }

    private fun activeCheatFileCandidates(serial: String?, crc: String): List<File> {
        val names = linkedSetOf<String>()
        if (!serial.isNullOrBlank()) names += "${sanitizeFileName(serial)}_$crc.pnach"
        names += "$crc.pnach"
        val root = activeDir().canonicalFile
        return names.map { File(root, it) }
    }

    private fun canonicalActiveCheatFile(serial: String?, crc: String): File {
        val name = if (serial.isNullOrBlank()) {
            "$crc.pnach"
        } else {
            "${sanitizeFileName(serial)}_$crc.pnach"
        }
        return File(activeDir().canonicalFile, name)
    }

    private fun coreCheatFile(pnachFile: File): File =
        File(pnachFile.parentFile, "${pnachFile.nameWithoutExtension}.ini")

    /**
     * Staging file handed to the native bridge, which forwards every block to
     * the core's cheat API. The core parses the "ADDRESS VALUE" pairs.
     */
    private fun buildCoreCheatContents(blocks: List<CheatBlock>): String? {
        val usable = blocks.filter { !convertCheatBlock(it).isNullOrEmpty() }
        if (usable.isEmpty()) return null
        return buildString {
            usable.forEach { block ->
                append("// ").append(cheatBlockLabel(block).replace('\n', ' ').replace('\r', ' ')).append('\n')
                block.lines.forEach { line -> append(line.trim()).append('\n') }
                append('\n')
            }
        }
    }

    private fun convertCheatBlock(block: CheatBlock): List<String>? {
        val codeLines = mutableListOf<String>()
        block.lines.forEach { rawLine ->
            val line = rawLine.substringBefore("//").substringBefore("#").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("//") || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (RAW_CODE_REGEX.matchEntire(line) == null) return null
            codeLines += line.uppercase(Locale.US)
        }
        return codeLines.ifEmpty { null }
    }

    private fun recordedActiveCheatFiles(gameKey: String): List<File> {
        val names = loadActiveTargets().optJSONArray(gameKey)?.toStringSet().orEmpty()
        val root = activeDir().canonicalFile
        return names.mapNotNull { name ->
            if (name != File(name).name || !name.endsWith(".pnach", ignoreCase = true)) return@mapNotNull null
            File(root, name).canonicalFile.takeIf { it.parentFile == root }
        }
    }

    private fun recordActiveCheatFile(gameKey: String, target: File) {
        val state = loadActiveTargets()
        state.put(gameKey, JSONArray(listOf(target.name)))
        writeActiveTargets(state)
    }

    private fun clearRecordedActiveCheatFiles(gameKey: String) {
        val state = loadActiveTargets()
        state.remove(gameKey)
        writeActiveTargets(state)
    }

    private fun loadActiveTargets(): JSONObject {
        if (!activeTargetsFile.exists()) return JSONObject()
        return runCatching { JSONObject(activeTargetsFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeActiveTargets(json: JSONObject) {
        writeTextAtomically(activeTargetsFile, json.toString())
    }

    private fun writeTextAtomically(file: File, contents: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(contents.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun activeDir(): File {
        return EmulatorStorage.cheatsDir(context, preferences.getEmulatorDataPathSync())
    }

    private companion object {
        val CHEAT_IO_LOCK = Any()

        val PATCH_LINE_REGEX = Regex(
            pattern = "patch\\s*=\\s*[0-2]\\s*,\\s*(?:EE|IOP|(?:0x)?[0-9A-Fa-f]+)\\s*,\\s*" +
                "([0-9A-Fa-f]{8})\\s*,\\s*(byte|short|word|[0-2])\\s*,\\s*([0-9A-Fa-f]+)",
            option = RegexOption.IGNORE_CASE
        )

        val RAW_CODE_REGEX = Regex("[0-9A-Fa-f]{8}[\\s:+-]+[0-9A-Fa-f]{1,8}")

        val AUTHOR_LINE_REGEX = Regex("^author\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE)

        val LIBRETRO_DESC_REGEX = Regex("^cheat\\d+_desc\\s*=\\s*\"?(.*?)\"?\\s*$", RegexOption.IGNORE_CASE)
        val LIBRETRO_CODE_REGEX = Regex("^cheat\\d+_code\\s*=\\s*\"?(.+?)\"?\\s*$", RegexOption.IGNORE_CASE)
        val LIBRETRO_COUNT_REGEX = Regex("^cheats\\s*=\\s*\\d+\\s*$", RegexOption.IGNORE_CASE)
        val LIBRETRO_TOGGLE_REGEX = Regex("^cheat\\d+_enable\\s*=.*$", RegexOption.IGNORE_CASE)
        val METADATA_LINE_REGEX = Regex("^[A-Za-z][A-Za-z0-9 _]*\\s*=.*$")
    }

    internal fun parsePatchBlocks(raw: String): List<CheatBlock> = parseCheatBlocks(raw)

    private fun parseCheatBlocks(raw: String): List<CheatBlock> {
        val lines = raw.lineSequence().map { it.trimEnd() }.toList()
        val blocks = mutableListOf<CheatBlock>()
        var currentTitle: String? = null
        var currentAuthor: String? = null
        var currentLines = mutableListOf<String>()
        var index = 1

        fun flush() {
            val usefulLines = currentLines.filter { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("patch=", ignoreCase = true) ||
                    trimmed.startsWith("dpatch=", ignoreCase = true) ||
                    RAW_CODE_REGEX.matchEntire(trimmed) != null
            }
            if (usefulLines.isEmpty()) {
                currentLines = mutableListOf()
                return
            }
            val title = currentTitle?.takeIf { it.isNotBlank() } ?: "Cheat $index"
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            blocks += CheatBlock(
                id = "${slug.ifBlank { "cheat" }}_$index",
                title = title,
                lines = usefulLines,
                enabled = false,
                author = currentAuthor?.takeIf { it.isNotBlank() }
            )
            index++
            currentTitle = null
            currentAuthor = null
            currentLines = mutableListOf()
        }

        lines.forEach { line ->
            val trimmed = line.trim()
            val libretroDesc = LIBRETRO_DESC_REGEX.matchEntire(trimmed)
            val libretroCode = LIBRETRO_CODE_REGEX.matchEntire(trimmed)
            val semicolonLabel = trimmed.takeIf { it.startsWith(";") }
                ?.removePrefix(";")
                ?.trim()
                ?.takeIf { it.startsWith("[") && it.endsWith("]") }
                ?.removeSurrounding("[", "]")
                ?.trim()
            val label = when {
                trimmed.startsWith("//") -> trimmed.removePrefix("//").trim()
                trimmed.startsWith("comment=", ignoreCase = true) -> trimmed.substringAfter('=').trim()
                trimmed.startsWith("[") && trimmed.endsWith("]") -> trimmed.removeSurrounding("[", "]").trim()
                !semicolonLabel.isNullOrBlank() -> semicolonLabel
                libretroDesc != null -> libretroDesc.groupValues[1].trim()
                else -> null
            }
            val isPatchLine = trimmed.startsWith("patch=", ignoreCase = true) ||
                trimmed.startsWith("dpatch=", ignoreCase = true)
            val codeCandidate = trimmed.substringBefore("//").substringBefore("#").trim()
            val isRawCode = RAW_CODE_REGEX.matchEntire(codeCandidate) != null
            val libretroCodes = libretroCode
                ?.groupValues
                ?.get(1)
                ?.split('+')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
                .chunked(2)
                .filter { it.size == 2 }
                .map { "${it[0]} ${it[1]}" }
                .filter { RAW_CODE_REGEX.matchEntire(it) != null }
            when {
                libretroCode != null -> {
                    if (libretroCodes.isNotEmpty()) currentLines += libretroCodes
                }
                !label.isNullOrBlank() -> {
                    if (currentLines.isNotEmpty()) {
                        flush()
                    }
                    currentTitle = label
                    currentAuthor = null
                }
                AUTHOR_LINE_REGEX.matchEntire(trimmed) != null -> {
                    currentAuthor = AUTHOR_LINE_REGEX.matchEntire(trimmed)
                        ?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                isPatchLine -> currentLines += trimmed
                METADATA_LINE_REGEX.matchEntire(trimmed) != null ||
                    LIBRETRO_COUNT_REGEX.matchEntire(trimmed) != null ||
                    LIBRETRO_TOGGLE_REGEX.matchEntire(trimmed) != null -> Unit
                isRawCode && currentTitle != null -> currentLines += codeCandidate
            }
        }
        flush()
        val parsedBlocks = blocks.ifEmpty {
            lines
                .mapNotNull { line ->
                    line.trim().takeIf { value ->
                        value.startsWith("patch=", ignoreCase = true) ||
                            value.startsWith("dpatch=", ignoreCase = true) ||
                            RAW_CODE_REGEX.matchEntire(value) != null
                    }
                }
                .mapIndexed { idx, line ->
                    CheatBlock(
                        id = "cheat_${idx + 1}",
                        title = "Cheat ${idx + 1}",
                        lines = listOf(line),
                        enabled = false
                    )
                }
        }
        val merged = linkedMapOf<String, CheatBlock>()
        parsedBlocks.forEach { block ->
            val key = block.title.trim().lowercase()
            val existing = merged[key]
            merged[key] = if (existing == null) {
                block
            } else {
                existing.copy(
                    lines = (existing.lines + block.lines).distinct(),
                    author = existing.author ?: block.author
                )
            }
        }
        return merged.values.toList()
    }

    private fun cheatBlockLabel(block: CheatBlock): String {
        val author = block.author?.trim().orEmpty()
        return if (author.isEmpty()) block.title else "${block.title} (by $author)"
    }

    private fun cheatBlockSignature(block: CheatBlock): String {
        return block.title.trim().lowercase() + "\u0000" +
            block.author?.trim().orEmpty() + "\u0000" +
            block.lines.joinToString("\n")
    }
}

private fun JSONArray.toStringSet(): Set<String> {
    return buildSet {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}
