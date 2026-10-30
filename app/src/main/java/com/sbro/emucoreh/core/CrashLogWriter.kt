package com.sbro.emucoreh.core

/**
 * Crash reporting is always on, including release builds: a failed game start
 * must leave a reason in the log. Routine diagnostics stay debug-only so a
 * release build never evaluates or formats them.
 */
internal class CrashLogWriter(
    private val debug: Boolean,
    private val append: (String, String) -> Unit
) {
    @Synchronized
    fun write(level: String, message: () -> String) {
        if (!debug && level !in CRITICAL_LEVELS) return
        append(level, message())
    }

    companion object {
        /** Entries that explain a failure and must survive in release builds. */
        private val CRITICAL_LEVELS = setOf("START", "LAUNCH", "STEP", "ERROR", "CRASH")
    }
}
