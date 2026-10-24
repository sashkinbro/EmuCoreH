package com.sbro.emucoreh.core

/** Release reports contain failures only; normal diagnostics never build a message. */
internal class CrashLogWriter(
    private val debug: Boolean,
    private val append: (String, String) -> Unit
) {
    private val reportedFailures = mutableSetOf<Pair<String, String>>()

    @Synchronized
    fun write(level: String, message: () -> String) {
        if (!debug && level != "ERROR" && level != "CRASH") return
        val body = message()
        if (!debug && !reportedFailures.add(level to body)) return
        append(level, body)
    }
}
