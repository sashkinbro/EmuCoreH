/*
	Copyright 2025 flyinghead

	This file is part of Flycast.

    Flycast is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Flycast is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Flycast.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sbro.emucoreh.data.boxart

import java.net.URLEncoder
import java.util.Locale

/**
 * Kotlin port of Flycast's arcade box art scraper
 * (core/core/ui/boxart/arcade_scraper.h). Arcade covers live on the
 * flycast-content GitHub Pages site used by the official emulator.
 */
object FlycastBoxartSource {

    fun searchName(name: String, arcade: Boolean): String {
        if (!arcade) return name
        var result = name.substringBefore('/').trimEnd()
        while (result.isNotEmpty()) {
            val start = when (result.last()) {
                ')' -> result.lastIndexOf('(')
                ']' -> result.lastIndexOf('[')
                else -> -1
            }
            if (start < 0) break
            result = result.substring(0, start).trimEnd()
        }
        return result
    }

    fun uniqueIdCandidates(discUniqueId: String?, librarySerial: String?): List<String> =
        listOfNotNull(discUniqueId, librarySerial).map(String::trim)
            .filter { it.isNotEmpty() && it != "T0000" && it != "T0000M" }.distinct()

    private const val ARCADE_ARTWORK_BASE_URL = "https://flyinghead.github.io/flycast-content/arcade/jpg/"

    /**
     * Candidate cover URLs for an arcade ROM set, in the same order Flycast
     * tries them: the archive file name first (zip/7z), then the ROM's unique
     * id as read from the ROM header.
     */
    fun arcadeImageUrls(fileName: String, uniqueId: String?): List<String> {
        val urls = ArrayList<String>(2)
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension == "zip" || extension == "7z") {
            val baseName = fileName.substringBeforeLast('.')
            if (baseName.isNotBlank()) {
                urls += ARCADE_ARTWORK_BASE_URL + urlEncode(baseName) + ".jpg"
            }
        }
        if (!uniqueId.isNullOrBlank() && uniqueId.all { it in ' '..'~' }) {
            urls += ARCADE_ARTWORK_BASE_URL + urlEncode(uniqueId) + ".jpg"
        }
        return urls
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
