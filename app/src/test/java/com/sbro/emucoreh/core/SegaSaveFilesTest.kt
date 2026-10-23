package com.sbro.emucoreh.core

import org.junit.Assert.*
import org.junit.Test

class SegaSaveFilesTest {
    @Test fun permitsOnlyBatterySavesWithinKnownSystemNamespaces() {
        listOf("genesis/sonic.sav", "sega_cd/game.bram", "sega_cd/game.ramc", "game_gear/a.sav")
            .forEach { assertTrue(it, SegaSaveFiles.isBackupPath(it)) }
        listOf("../bios.sav", "genesis/../../bios.sav", "genesis/bios.bin", "genesis/game.md",
            "unknown/game.sav", "genesis/sub/game.sav", "genesis\\game.sav", "genesis/.tmp.sav")
            .forEach { assertFalse(it, SegaSaveFiles.isBackupPath(it)) }
    }
}
