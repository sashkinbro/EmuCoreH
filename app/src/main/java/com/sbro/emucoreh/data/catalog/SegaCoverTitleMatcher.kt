package com.sbro.emucoreh.data.catalog

import java.util.Locale
import org.json.JSONObject

/** Sega-only dump-name cleanup; legacy Dreamcast matching is intentionally independent. */
internal object SegaCoverTitleMatcher {
    private val extension = Regex("(?i)\\.(?:md|gen|smd|32x|sms|gg|sg|bin|cue|iso|chd|m3u|zip)$")
    private val trailingTag = Regex("[\\s_]*(?:\\(([^()]*)\\)|\\[([^\\[\\]]*)])[\\s_]*$")
    private val translation = Regex("(?i)(?:t[+-].*|tr(?:anslated|anslation)?(?:[ +:_-].*)?)")
    private val dumpFlag = Regex("(?i)(?:!|s|[abcfhopt]\\d*[a-z]?|[abcfhopt][+-].*)")
    private val articleBeforeSubtitle = Regex("(?i)^(.+?),\\s*(the|an|a)(\\s*[-:]\\s*.+)$")
    private val pressingCode = Regex("(?i)\\d{1,3}(?:[ms]\\d{0,2})?")
    private val parenthesizedTag = Regex("\\(([^()]*)\\)")
    private val extraTags = setOf("aftermarket", "unl", "unlicensed", "enhanced", "translated", "translation",
        "aleste collection", "sonic classic collection", "game gear micro")

    fun resolve(titles: JSONObject, title: String?, fileName: String?): String? {
        for (candidate in listOfNotNull(title, fileName).filter(String::isNotBlank).distinct()) {
            val stem = extension.replace(candidate.trim(), "")
            exact(titles, stem)?.let { return it }
            var cleaned = stem
            while (true) {
                val tag = trailingTag.find(cleaned) ?: break
                val text = tag.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty().trim()
                val remaining = cleaned.substring(0, tag.range.first).trimEnd(' ', '_')
                // Let the legacy parser remove known region/revision tags before
                // removing Sega translation and provenance tags behind them.
                val known = text.lowercase(Locale.ROOT) in extraTags || translation.matches(text) ||
                    (tag.groupValues[2].isNotEmpty() && dumpFlag.matches(text)) ||
                    (pressingCode.matches(text) && parenthesizedTag.findAll(remaining).any { previous ->
                        CoverIndexRepository.normalizeTitle(previous.groupValues[1]).split(' ')
                            .all { it in regionsAndLanguages }
                    })
                if (known) {
                    cleaned = remaining
                } else {
                    val normalized = CoverIndexRepository.normalizeTitle(text)
                    val regionOrLanguage = normalized.split(' ').all { it in regionsAndLanguages }
                    val revision = Regex("(?i)(?:rev(?:ision)?[ ._-]*[a-z0-9]+|(?:disc|disk|cd)\\s*\\d+(?:\\s*(?:of|/)\\s*\\d+)?|(?:19|20)\\d{2})").matches(text)
                    if (!regionOrLanguage && !revision) break
                    cleaned = remaining
                }
                exact(titles, cleaned)?.let { return it }
            }
        }
        return null
    }

    private fun exact(titles: JSONObject, value: String): String? {
        // Real Sega titles include D and Z; only accept their explicit index key.
        val normalized = CoverIndexRepository.normalizeTitle(value)
        if (normalized.length == 1) titles.optString(normalized).takeIf(String::isNotBlank)?.let { return it }
        CoverTitleMatcher.resolve(titles, value, false)?.let { return it }
        val article = articleBeforeSubtitle.matchEntire(value) ?: return null
        return CoverTitleMatcher.resolve(titles, "${article.groupValues[2]} ${article.groupValues[1]}${article.groupValues[3]}", false)
    }

    private val regionsAndLanguages = setOf("usa", "us", "u", "europe", "eur", "eu", "e", "japan", "jpn", "jp", "j",
        "world", "australia", "au", "brazil", "br", "korea", "kr", "taiwan", "tw", "china", "cn", "france", "germany",
        "italy", "spain", "uk", "pal", "ntsc", "en", "fr", "de", "es", "it", "ja", "ko", "zh", "pt", "nl", "sv",
        "no", "da", "fi", "ru")
}
