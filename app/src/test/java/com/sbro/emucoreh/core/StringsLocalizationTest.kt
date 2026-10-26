// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsLocalizationTest {
    @Test fun saturnSettingsLabelsHaveFullLocaleCoverage() {
        val res = resolveResDir()
        val english = readStrings(File(res, "values/strings.xml"))
        val keys = english.keys.filter { it.startsWith("saturn_") }
        val files = res.listFiles().orEmpty().map { File(it, "strings.xml") }.filter(File::isFile)
        for (file in files) {
            val localized = readStrings(file)
            for (key in keys) assertTrue("Missing $key in ${file.parentFile.name}", !localized[key].isNullOrBlank())
        }
    }
    @Test fun supportedSystemsGuideAndSaturnErrorsAreTranslatedInEveryLocale() {
        val res = resolveResDir()
        val english = readStrings(File(res, "values/strings.xml"))
        val keys = english.keys.filter { it.startsWith("supported_systems_") || it == "saturn_bios_missing" }
        assertEquals(9, keys.size)
        val files = res.listFiles().orEmpty().map { File(it, "strings.xml") }.filter(File::isFile)
        assertEquals(18, files.size)
        for (file in files) {
            val strings = readStrings(file)
            for (key in keys) {
                val translated = strings[key]
                assertTrue("Missing $key in ${file.parentFile.name}", !translated.isNullOrBlank())
                if (file.parentFile.name != "values" && key != "supported_systems_no_bios_body") {
                    assertTrue("English fallback $key in ${file.parentFile.name}", translated != english[key])
                }
            }
        }
    }
    private val requiredKeys = listOf(
        "settings_keyboard_mouse_section",
        "settings_keyboard_input",
        "settings_keyboard_input_desc",
        "settings_keyboard_target_player",
        "settings_keyboard_mapping_title",
        "settings_keyboard_mapping_desc",
        "settings_mouse_input",
        "settings_mouse_input_desc",
        "settings_mouse_target_player",
        "settings_mouse_mapping_title",
        "settings_mouse_mapping_desc",
        "settings_keyboard_mapping_listening_title",
        "settings_keyboard_mapping_listening_desc",
        "settings_mouse_mapping_listening_title",
        "settings_mouse_mapping_listening_desc",
        "settings_keyboard_connected",
        "settings_mouse_connected",
        "settings_mouse_aim_mode",
        "settings_mouse_aim_off",
        "settings_mouse_aim_right_stick",
        "settings_mouse_aim_left_stick",
        "settings_mouse_aim_mode_desc",
        "settings_mouse_sensitivity",
        "settings_mouse_sensitivity_desc",
        "settings_mouse_vertical_sensitivity",
        "settings_mouse_smoothing",
        "settings_mouse_smoothing_desc",
        "settings_mouse_deadzone",
        "settings_mouse_wheel_pulse",
        "settings_mouse_invert_x",
        "settings_mouse_invert_x_desc",
        "settings_mouse_invert_y",
        "settings_mouse_invert_y_desc",
        "settings_mouse_capture",
        "settings_mouse_capture_desc",
        "settings_mouse_left_handed",
        "settings_mouse_left_handed_desc",
        "settings_mouse_wheel",
        "settings_mouse_wheel_off",
        "settings_mouse_wheel_dpad",
        "settings_mouse_wheel_triggers",
        "settings_mouse_wheel_left_stick",
        "settings_mouse_wheel_right_stick",
        "settings_mouse_wheel_shoulders",
        "settings_keyboard_mouse_mapping_reset_title",
        "settings_keyboard_mouse_mapping_reset_desc",
        "settings_keyboard_mouse_global_note",
        "settings_keyboard_mouse_game_desc",
        "settings_keyboard_mouse_use_global",
        "settings_keyboard_mouse_use_global_desc",
        "settings_keyboard_mouse_source_keyboard",
        "settings_keyboard_mouse_source_mouse",
        "sega_32x_bios_title",
        "sega_32x_bios_description",
        "sega_32x_bios_m68k",
        "sega_32x_bios_master",
        "sega_32x_bios_slave",
        "sega_32x_bios_invalid",
        "sega_32x_bios_missing",
        "sega_32x_bios_file",
        "bios_supported_files",
        "bios_import_result"
    )

    @Test
    fun `keyboard and mouse strings exist in every locale`() {
        val resDir = resolveResDir()
        val localeDirs = resDir
            .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty()
            .filter { dir -> File(dir, "strings.xml").isFile }
        assertTrue("Expected at least 18 locale string files", localeDirs.size >= 18)
        localeDirs.forEach { dir ->
            val content = File(dir, "strings.xml").readText()
            requiredKeys.forEach { key ->
                assertTrue("Missing $key in ${dir.name}/strings.xml", content.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun `supported arcade cabinet and firmware guidance includes System SP in every locale`() {
        resolveResDir().listFiles().orEmpty().map { File(it, "strings.xml") }.filter(File::isFile).forEach { file ->
            val strings = readStrings(file)
            assertTrue("System SP is missing from supported arcade formats in ${file.parentFile?.name}",
                strings.getValue("formats_psp_homebrew_body").contains("System SP"))
            assertTrue("System SP firmware is missing from BIOS help in ${file.parentFile?.name}",
                strings.getValue("bios_supported_files").contains("segasp.zip"))
        }
    }

    @Test
    fun `catalog platform and core attribution resources preserve locale and format contracts`() {
        val resDir = resolveResDir()
        val english = readStrings(File(resDir, "values/strings.xml"))
        val prefixes = listOf("catalog_", "home_platform_", "formats_", "settings_about_", "model3_")
        val profileCoreLabels = setOf("profile_cloud_version", "profile_device_app_core")
        val keys = english.keys.filter { key -> key in profileCoreLabels || prefixes.any(key::startsWith) }
        resDir.listFiles().orEmpty().map { File(it, "strings.xml") }.filter(File::isFile).forEach { file ->
            val localized = readStrings(file)
            keys.forEach { key ->
                val value = localized[key]
                assertTrue("Missing or empty $key in ${file.parentFile?.name}", !value.isNullOrBlank())
                assertEquals("Format arguments for $key in ${file.parentFile?.name}",
                    formatArguments(english.getValue(key)), formatArguments(value.orEmpty()))
                if (key.endsWith("_url")) {
                    assertEquals("Core and catalog links must use HTTPS: $key", "https",
                        java.net.URI(value.orEmpty()).scheme)
                }
            }
        }
    }

    private fun readStrings(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes.getNamedItem("name").nodeValue
                assertTrue("Duplicate string $name in $file", !containsKey(name))
                put(name, node.textContent)
            }
        }
    }

    private fun formatArguments(value: String): List<String> {
        var nextIndex = 1
        return Regex("%(?:(\\d+)\\$)?[0-9.]*([a-zA-Z])").findAll(value).map { match ->
            val index = match.groupValues[1].toIntOrNull() ?: nextIndex++
            "$index:${match.groupValues[2]}"
        }.sorted().toList()
    }

    private fun resolveResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File(System.getProperty("user.dir").orEmpty(), "src/main/res"),
            File(System.getProperty("user.dir").orEmpty(), "app/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate the Android res directory")
    }
}
