// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import java.io.File
import java.util.zip.ZipFile

internal data class Model3ProgramFingerprint(
    val name: String,
    val parent: String,
    val local: Set<Long>,
    val effective: Set<Long>,
)

/** Program CRC metadata identifies a revision; native loading still validates all ROM regions. */
internal object Model3ContentIdentity {
    fun readCrcs(file: File): Set<Long> = ZipFile(file).use { zip ->
        val entries = zip.entries()
        val crcs = mutableSetOf<Long>()
        var count = 0
        while (entries.hasMoreElements()) {
            require(++count <= 4096) { "Too many Model 3 ZIP entries" }
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.crc >= 0) crcs += entry.crc
        }
        crcs
    }

    fun identify(
        namedSet: String,
        selected: Set<Long>,
        fingerprints: List<Model3ProgramFingerprint>,
        parentCrcs: (String) -> Set<Long>?,
    ): String? {
        val parents = mutableMapOf<String, Set<Long>?>()
        fun matches(game: Model3ProgramFingerprint): Boolean {
            if (game.local.isEmpty() || game.effective.size != 4 || !selected.containsAll(game.local)) return false
            if (selected.containsAll(game.effective)) return true
            if (game.parent.isEmpty()) return false
            val parent = if (parents.containsKey(game.parent)) parents[game.parent] else
                parentCrcs(game.parent).also { parents[game.parent] = it }
            return parent != null && (selected + parent).containsAll(game.effective)
        }
        fingerprints.firstOrNull { it.name == namedSet }?.takeIf(::matches)?.let { return it.name }
        return fingerprints.filter(::matches).singleOrNull()?.name
    }

    fun resolve(file: File, named: Model3Game, parentFile: (Model3Game) -> File?): Model3Game? {
        val set = identify(named.name, readCrcs(file), Model3ProgramCrcs.fingerprints) { parent ->
            Model3Romsets.find("$parent.zip")?.let(parentFile)?.let(::readCrcs)
        } ?: return null
        return Model3Romsets.find("$set.zip")
    }
}
