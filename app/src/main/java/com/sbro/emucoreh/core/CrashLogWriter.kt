package com.sbro.emucoreh.core

/** Diagnostics are debug-only; release never evaluates the message or writes a file. */
internal class CrashLogWriter(
    private val debug: Boolean,
    private val append: (String, String) -> Unit
) {
    @Synchronized
    fun write(level: String, message: () -> String) {
        if (!debug) return
        val body = message()
        append(level, body)
    }
}
