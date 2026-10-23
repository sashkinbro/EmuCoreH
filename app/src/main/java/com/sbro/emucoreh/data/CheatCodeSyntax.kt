package com.sbro.emucoreh.data

/** Code spelling is preserved until the selected core decodes it. */
internal object CheatCodeSyntax {
    private val flycast = Regex("[0-9A-Fa-f]{8}[\\s:+-]+[0-9A-Fa-f]{1,8}")
    private val sega = Regex(
        "(?:[A-HJ-NPR-TV-Z0-9]{4}-[A-HJ-NPR-TV-Z0-9]{4}|" +
            "[0-9A-F]{6}:[0-9A-F]{4}|[0-9A-F]{5}[ -]?[0-9A-F]{5}|" +
            "[0-9A-F]{3}-[0-9A-F]{3}(?:-[0-9A-F]{3})?|00[0-9A-F]{2}[ -]?[0-9A-F]{4})",
        RegexOption.IGNORE_CASE
    )

    fun isSupportedLine(code: String): Boolean = flycast.matches(code) || sega.matches(code)

    fun splitLibretroCodes(raw: String): List<String> {
        val segments = raw.split('+').map(String::trim).filter(String::isNotEmpty)
        // Older Flycast files encode each address and payload as separate + segments.
        return if (segments.all { sega.matches(it) }) {
            segments
        } else if (segments.all { Regex("[0-9A-Fa-f]{8}").matches(it) } && segments.size % 2 == 0) {
            segments.chunked(2).map { "${it[0]} ${it[1]}" }
        } else segments.filter(::isSupportedLine)
    }
}
