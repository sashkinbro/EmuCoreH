package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogWriterTest {
    @Test fun releaseDoesNotBuildOrWriteRoutineDiagnostics() {
        val entries = mutableListOf<String>()
        val writer = CrashLogWriter(false) { _, body -> entries.add(body) }
        for (level in listOf("INFO", "CTX", "TRACE")) {
            writer.write(level) { error("Release must not evaluate $level diagnostics") }
        }
        assertEquals(emptyList<String>(), entries)
    }

    @Test fun releaseKeepsStartLaunchAndCrashReports() {
        val entries = mutableListOf<Pair<String, String>>()
        val writer = CrashLogWriter(false) { level, body -> entries.add(level to body) }
        for (level in listOf("START", "LAUNCH", "STEP", "ERROR", "CRASH")) {
            writer.write(level) { "$level body" }
        }
        assertEquals(
            listOf(
                "START" to "START body",
                "LAUNCH" to "LAUNCH body",
                "STEP" to "STEP body",
                "ERROR" to "ERROR body",
                "CRASH" to "CRASH body"
            ),
            entries
        )
    }

    @Test fun debugStillAllowsDiagnosticMessages() {
        val entries = mutableListOf<String>()
        val writer = CrashLogWriter(true) { _, body -> entries.add(body) }
        writer.write("INFO") { "startup" }
        assertEquals(listOf("startup"), entries)
    }
}
