package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogWriterTest {
    @Test fun releaseDoesNotBuildOrWriteRoutineDiagnostics() {
        val entries = mutableListOf<String>()
        val writer = CrashLogWriter(false) { _, body -> entries.add(body) }
        for (level in listOf("START", "INFO", "CTX", "TRACE")) {
            writer.write(level) { error("Release must not evaluate $level diagnostics") }
        }
        assertEquals(emptyList<String>(), entries)
    }

    @Test fun releaseDoesNotBuildOrWriteFatalOrCrashReports() {
        val entries = mutableListOf<Pair<String, String>>()
        val writer = CrashLogWriter(false) { level, body -> entries.add(level to body) }
        for (level in listOf("ERROR", "CRASH")) {
            writer.write(level) { error("Release must not evaluate $level diagnostics") }
        }
        assertEquals(emptyList<Pair<String, String>>(), entries)
    }

    @Test fun debugStillAllowsDiagnosticMessages() {
        val entries = mutableListOf<String>()
        val writer = CrashLogWriter(true) { _, body -> entries.add(body) }
        writer.write("INFO") { "startup" }
        assertEquals(listOf("startup"), entries)
    }
}
