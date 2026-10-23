package com.sbro.emucoreh.data.catalog

import com.sbro.emucoreh.data.igdb.catalogPlatformName
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Replays actual owner-supplied RG556 names; contains metadata only, no ROM data. */
class SegaCoverDeviceNamesTest {
    @Test fun ownerLibraryResolvesEveryVerifiedCatalogGameWithoutBorrowingBruceLeeStory() {
        val project = listOf(File("."), File("..")).first { File(it, "app/src/main/assets/catalog/sega_covers.json").isFile }
        val index = JSONObject(File(project, "app/src/main/assets/catalog/sega_covers.json").readText())
            .getJSONObject("platforms")
        val cases = JSONArray(javaClass.getResource("/sega_rg_cover_cases.json")!!.readText())
        val report = JSONArray()
        for (position in 0 until cases.length()) {
            val entry = cases.getJSONObject(position)
            val platform = catalogPlatformName(entry.getString("platform"))
            val name = entry.getString("fileName")
            val title = entry.getString("headerTitle")
            val section = index.getJSONObject(platform)
            val titles = section.getJSONObject("titles")
            val path = SegaCoverTitleMatcher.resolve(titles, title, name)
            if (name.startsWith("Bruce Lee (")) assertNull("Unindexed homebrew must not borrow Dragon: The Bruce Lee Story", path)
            else assertNotNull("$platform: $name (header=$title)", path)
            CoverIndexRepository.normalizeSerial(entry.optString("serial"))?.let { serial ->
                assertEquals("Serial and audited filename must identify the same platform cover: $name",
                    path, section.getJSONObject("serials").optString(serial).takeIf(String::isNotBlank))
            }
            report.put(JSONObject().put("platform", platform).put("file_name", name).put("header_title", title)
                .put("igdb_id", path?.substringAfterLast('/')?.substringBefore('.')?.toLongOrNull() ?: JSONObject.NULL)
                .put("path", path ?: JSONObject.NULL)
                .put("flat_url", path?.let { "${CoverIndexRepository.BASE_URL}/$it" } ?: JSONObject.NULL)
                .put("dark_url", path?.let { CoverIndexRepository.makeCoverUrl(it) } ?: JSONObject.NULL)
                .put("light_url", path?.let { CoverIndexRepository.makeCoverUrl(it, isLight = true) } ?: JSONObject.NULL)
                .put("matched_by", if (path == null) "missing: no verified platform catalog entry"
                    else if (SegaCoverTitleMatcher.resolve(titles, title, null) != null) "header title" else "filename"))
        }
        val output = File(project, "build/qa-rg/sega-cover-audit.json")
        output.parentFile!!.mkdirs()
        output.writeText(report.toString(2))
        assertEquals(24, cases.length())
    }
}
