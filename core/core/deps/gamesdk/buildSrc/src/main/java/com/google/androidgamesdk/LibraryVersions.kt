package com.google.androidgamesdk

import java.io.File

data class SemanticVersion(val major: Int, val minor: Int, val bugfix: Int) {
    override fun toString(): String {
        return "${major}.${minor}.${bugfix}"
    }
}

data class LibraryInfo(
    val nickname: String,
    val jetpackName: String,
    val projectName: String?, // the Java project for AAR and hybrid libraries
    val version: SemanticVersion,
    val jetpackSuffix: String
) {
    fun jetpackVersion() =
        if (jetpackSuffix.isEmpty()) version.toString() else "${version}-${jetpackSuffix}"
}

/**
 * Class that parses the VERSIONS file in the top-level directory
 * and provides library information to the top-level build.gradle.
 */

class LibraryVersions(val versions_file_path: String) {
    var libraries = mutableMapOf<String, LibraryInfo>()

    init {
        val file = File(versions_file_path)
        if (!file.exists()) {
            throw Exception("Can't find versions file: " + versions_file_path)
        }
        parseLines(file.readLines()).associateByTo(this.libraries) { it.nickname }
    }

    fun get(nickname: String): LibraryInfo {
        if (!libraries.contains(nickname))
            throw Exception("Can't find a library version with nickname '" + nickname + "'")
        return libraries[nickname]!!
    }

    companion object LibraryVersions {
        fun parseLines(lines: List<String>): List<LibraryInfo> {
            val line_re =
                Regex("(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([0-9]+)[.]([0-9]+)[.]([0-9]+)(\\s+(\\S+))?")
            var result = mutableListOf<LibraryInfo>()
            lines.forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith('#')) {
                    val match = line_re.matchEntire(trimmed)
                    if (match == null) {
                        throw Exception("Error parsing versions at line " + i + ", '" + line + "'")
                    } else {
                        result.add(
                            LibraryInfo(
                                match.groupValues[1], match.groupValues[2],
                                if (match.groupValues[3] == "None") null else match.groupValues[3],
                                SemanticVersion(
                                    match.groupValues[4].toInt(),
                                    match.groupValues[5].toInt(),
                                    match.groupValues[6].toInt()
                                ),
                                match.groupValues[8]
                            )
                        )
                    }
                }
            }
            return result
        }
    }
}