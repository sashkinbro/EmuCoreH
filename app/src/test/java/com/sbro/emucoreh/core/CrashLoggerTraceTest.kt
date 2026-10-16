// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreH-Proprietary
package com.sbro.emucoreh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLoggerTraceTest {

    @Test
    fun `plain text is preserved`() {
        val trace = "signal 11 (SIGSEGV), code 1\n  #00 pc 0000000000123456 libfoo.so\n"
        assertEquals(
            "signal 11 (SIGSEGV), code 1\n  #00 pc 0000000000123456 libfoo.so",
            CrashLogger.sanitizeTrace(trace)
        )
    }

    @Test
    fun `tabs and newlines survive`() {
        assertEquals("a\tb\nc", CrashLogger.sanitizeTrace("a\tb\nc"))
    }

    @Test
    fun `binary runs collapse to a single dot`() {
        val binary = "ok\u0000\u0001\u0002\u0003ok\uFFFD\uFFFD\uFFFDend"
        assertEquals("ok·ok·end", CrashLogger.sanitizeTrace(binary))
    }

    @Test
    fun `readable strings inside a protobuf blob stay visible`() {
        val blob = "\u0000\u0001libc.so\u0002\u0003B 26a5ee10\u0000android::Thread"
        val sanitized = CrashLogger.sanitizeTrace(blob)
        assertEquals("·libc.so·B 26a5ee10·android::Thread", sanitized)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("#00 pc", CrashLogger.sanitizeTrace("\n  #00 pc \n"))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", CrashLogger.sanitizeTrace(""))
        // A pure binary blob folds into a single placeholder.
        assertEquals("·", CrashLogger.sanitizeTrace("\u0000\u0000\u0000"))
    }
}
