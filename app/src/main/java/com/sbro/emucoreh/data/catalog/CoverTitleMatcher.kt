package com.sbro.emucoreh.data.catalog

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Conservative filename aliases shared by fast and metadata-based cover lookups. */
internal object CoverTitleMatcher {
    private val imageExtension = Regex("(?i)\\.(?:chd|gdi|cdi|cue|iso|bin|m3u|zip|7z|dat|lst)$")
    private val trailingTag = Regex("[\\s_]*(?:\\(([^()]*)\\)|\\[([^\\[\\]]*)])[\\s_]*$")
    private val yearTag = Regex("\\((?:19|20)\\d{2}\\)[\\s_]*$")
    private val year = Regex("(?:19|20)\\d{2}")
    private val revisionTag = Regex("(?i)rev(?:ision)?\\.?[\\s_-]*(?:[a-z]|\\d+(?:[._]\\d+)*)")
    private val dumpFlag = Regex("(?i)(?:!|[abcfhopt]\\d*|[abcfhopt][+-].+|cr(?:\\d+|[+-].+)?|\\d+s|m\\d+|multi\\d+)")
    private val dottedVersion = Regex("(?i)[\\s_]+v(?:er(?:sion)?)?\\.?\\s*(\\d+(?:[._]\\d+)+[a-z]?)[\\s_]*$")
    private val parenthesizedVersion = Regex("(?i)\\(v(?:er(?:sion)?)?\\.?\\s*(\\d+(?:[._]\\d+)+[a-z]?)\\)")
    private val discTag = Regex("(?i)[\\s_]*[\\[(](?:disc|disk|cd)\\s*\\d+(?:\\s*(?:of|/)\\s*\\d+)?[\\])][\\s_]*$")
    private val leadingArticle = Regex("^(the|an|a) ")
    private val trailingArticle = Regex(" (the|an|a)$")
    private val numbers = Regex("\\d+|\\b(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)\\b")
    private val editions = Regex("\\b(?:pro|plus|international|kanzenban|limited|special|edition|ver|version|demo|trial)\\b")
    private val metadataWords = setOf(
        "usa", "us", "u", "europe", "eur", "eu", "e", "japan", "jpn", "jp", "j", "world",
        "australia", "au", "brazil", "br", "korea", "kr", "taiwan", "tw", "china", "cn",
        "france", "germany", "italy", "spain", "uk", "pal", "ntsc",
        "en", "fr", "de", "es", "it", "ja", "ko", "zh", "pt", "nl", "sv", "no", "da", "fi", "ru"
    )

    fun resolve(titles: JSONObject, title: String?, allowFuzzy: Boolean): String? {
        val raw = Normalizer.normalize(title.orEmpty().trim().replace(Regex("[™®©]"), ""), Normalizer.Form.NFKC)
        val filename = imageExtension.replace(raw, "")
        val variants = mutableListOf(raw, filename).distinct().toMutableList()
        fun add(value: String) { if (value !in variants) variants += value }
        var next = 0
        // Revisit derived stems: dump tags, disc numbers and revisions occur in either order.
        while (next < variants.size) {
            val value = variants[next++]
            add(stripDumpTags(value))
            if (parenthesizedVersion.containsMatchIn(value)) {
                add(parenthesizedVersion.replace(value) { "(${it.groupValues[1]})" })
            }
            discTag.find(value)?.let { add(value.substring(0, it.range.first).trimEnd(' ', '_')) }
            dottedVersion.find(value)?.let { version ->
                val stem = value.substring(0, version.range.first).trimEnd(' ', '_')
                // DUX v1.5 names a different release, while v1.000 is usually dump metadata.
                add("$stem ${version.groupValues[1]}")
                add(stem)
            }
        }
        val queries = variants.flatMap { value ->
            val query = CoverIndexRepository.normalizeTitle(value)
            val article = trailingArticle.find(query)
            if (article == null) listOf(query)
            else listOf(query, "${article.groupValues[1]} ${query.substring(0, article.range.first)}")
        }.filter { it.length >= 2 }.distinct()
        // Preserve all explicit spelling, regional and edition choices first.
        for (query in queries) titles.optString(query).takeIf(String::isNotBlank)?.let { return it }
        for (keyOf in listOf<(String) -> String>({ it.replace(" ", "") }, ::articleKey)) {
            for (query in queries.filter { it.length >= 3 }) {
                val key = keyOf(query)
                val paths = titles.keys().asSequence().filter { keyOf(it) == key }
                    .map { titles.optString(it) }.filter(String::isNotBlank).distinct().take(2).toList()
                if (paths.size > 1) return null
                if (paths.size == 1) return paths.single()
            }
        }
        // An unknown parenthesized qualifier may be a real subtitle or edition.
        if (!allowFuzzy || variants.last().any { it in "()[]" }) return null
        val query = queries.lastOrNull() ?: return null
        if (query.length < 4) return null
        val queryNumbers = numbers.findAll(query).map { it.value }.toList()
        val queryEditions = editions.findAll(query).map { it.value }.toSet()
        var bestLength = 0
        val bestPaths = mutableSetOf<String>()
        for (key in titles.keys()) {
            if (key.length < 4 || !(" $key ".contains(" $query ") || " $query ".contains(" $key "))) continue
            if (numbers.findAll(key).map { it.value }.toList() != queryNumbers) continue
            if (editions.findAll(key).map { it.value }.toSet() != queryEditions) continue
            val score = minOf(key.length, query.length)
            if (score < bestLength) continue
            if (score > bestLength) { bestPaths.clear(); bestLength = score }
            titles.optString(key).takeIf(String::isNotBlank)?.let(bestPaths::add)
        }
        return bestPaths.singleOrNull()
    }

    private fun articleKey(value: String): String = value.replace(leadingArticle, "")
        .replace(trailingArticle, "").replace(" ", "")

    private fun stripDumpTags(filename: String): String {
        var value = filename
        while (true) {
            val suffix = trailingTag.find(value) ?: return value.trimEnd(' ', '_')
            val tag = suffix.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty().trim()
            val prefix = value.substring(0, suffix.range.first)
            val words = tag.lowercase(Locale.ROOT).split(Regex("[,;/+\\s-]+"))
            val metadata = year.matches(tag) || revisionTag.matches(tag) ||
                (suffix.groupValues[2].isNotEmpty() && dumpFlag.matches(tag)) ||
                (words.isNotEmpty() && words.all { it in metadataWords })
            // TOSEC appends (year)(publisher); unrelated edition tags are retained.
            val publisher = yearTag.containsMatchIn(prefix) && !editions.containsMatchIn(tag.lowercase(Locale.ROOT)) &&
                !Regex("(?i)^(?:v\\.?\\s*\\d|disc|disk|cd)\\b").containsMatchIn(tag)
            if (!metadata && !publisher) return value.trimEnd(' ', '_')
            value = prefix
        }
    }
}
