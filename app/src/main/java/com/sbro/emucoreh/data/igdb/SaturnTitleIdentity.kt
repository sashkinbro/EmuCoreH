package com.sbro.emucoreh.data.igdb

import java.text.Normalizer
import java.util.Locale

/** Saturn dump metadata can be removed; edition and sequel identity must survive. */
internal object SaturnTitleIdentity {
    private val extension = Regex("(?i)\\.(?:chd|cue|iso|bin|m3u|zip)$")
    private val trailingTag = Regex("[\\s_]*(?:\\(([^()]*)\\)|\\[([^\\[\\]]*)])[\\s_]*$")
    private val pressingCode = Regex("(?i)\\d{1,3}(?:[ms]\\d{0,2})?")
    private val parentheses = Regex("\\(([^()]*)\\)")
    private val revision = Regex("(?i)(?:rev(?:ision)?[ ._-]*[a-z0-9]+|(?:disc|disk|cd)\\s*\\d+(?:\\s*(?:of|/)\\s*\\d+)?)")
    private val year = Regex("(?:19|20)\\d{2}")
    private val dumpFlag = Regex("(?i)(?:!|[abcfhopt]\\d*|[abcfhopt][+-].+)")
    private val regions = setOf("usa", "us", "u", "europe", "eur", "eu", "e", "japan", "jpn", "jp", "j",
        "world", "australia", "au", "brazil", "br", "korea", "kr", "taiwan", "tw", "china", "cn", "france", "germany",
        "italy", "spain", "uk", "pal", "ntsc", "en", "fr", "de", "es", "it", "ja", "ko", "zh", "pt", "nl", "sv",
        "no", "da", "fi", "ru")
    private val numbers = Regex("\\d+|\\b(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)\\b")
    private val roman = mapOf("i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
    private val editionWords = setOf("demo", "trial", "special", "limited", "edition", "plus", "pro", "international",
        "kanzenban", "version", "ver", "remix", "championship", "circuit", "premium", "collection", "expanded", "ex")

    fun normalize(value: String): String = normalizeWords(clean(value))

    fun permitsFuzzy(value: String): Boolean = clean(value).none { it in "()[]" }

    fun resolveExactCandidates(ids: Set<Long>, indexedId: Long?): Long? =
        ids.singleOrNull() ?: indexedId?.takeIf { it in ids }

    fun similarity(left: String, right: String): Double {
        val leftNumbers = numbers.findAll(left).map { roman[it.value] ?: it.value }.toList()
        val rightNumbers = numbers.findAll(right).map { roman[it.value] ?: it.value }.toList()
        if (leftNumbers != rightNumbers) return 0.0
        if (left.split(' ').filter { it in editionWords }.toSet() != right.split(' ').filter { it in editionWords }.toSet()) return 0.0
        return catalogTitleSimilarity(left, right)
    }

    private fun region(value: String): Boolean = normalizeWords(value).split(' ').all { it in regions }

    private fun clean(value: String): String {
        var stem = extension.replace(Normalizer.normalize(value.trim(), Normalizer.Form.NFKC), "")
        while (true) {
            val tag = trailingTag.find(stem) ?: return stem
            val text = tag.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty().trim()
            val rest = stem.substring(0, tag.range.first).trimEnd(' ', '_')
            val hasRegion = parentheses.findAll(rest).any { region(it.groupValues[1]) }
            val known = region(text) || revision.matches(text) ||
                (tag.groupValues[2].isNotEmpty() && dumpFlag.matches(text)) ||
                ((pressingCode.matches(text) || year.matches(text)) && hasRegion)
            if (!known) return stem
            stem = rest
        }
    }

    private fun normalizeWords(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
