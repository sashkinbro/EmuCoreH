package com.sbro.emucoreh.data

import java.util.Locale

/**
 * Canonical disc product code. Flycast reports the Dreamcast IP.BIN value
 * (for example `MK-51035` or `T-3601N`), while some metadata readers hand back
 * the compact form without a separator, so both are accepted and rebuilt.
 */
object DiscSerial {
    private val pattern = Regex("^([A-Z0-9]{1,4})[-_ ]?([0-9]{3,7})([A-Z]?)$")
    private val compactPattern = Regex("^([A-Z]{1,4})([0-9]{3,5})([A-Z]?)$")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val upper = trimmed.uppercase(Locale.US)
        val match = compactPattern.matchEntire(upper) ?: pattern.matchEntire(upper) ?: return null
        val prefix = match.groupValues[1]
        val rawDigits = match.groupValues[2]
        val suffix = match.groupValues[3]
        if (prefix.all { it.isDigit() } && prefix.length < 2) return null
        // The console stores a two-digit revision after the product number
        // (for example MK-5103750 is MK-51037 revision 50), while the
        // catalogs list the product number alone.
        val digits = if (rawDigits.length >= 6) rawDigits.dropLast(2) else rawDigits
        if (digits.length !in 3..5) return null
        val separator = if ('_' in upper) '_' else '-'
        return "$prefix$separator$digits$suffix"
    }
}
