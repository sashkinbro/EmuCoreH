package com.sbro.emucoreh.core

import java.security.MessageDigest

/** Versioned import history shared by explicit selection and startup migration. */
internal class BiosImportHistory(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit
) {
    fun recordImported(path: String) {
        write("selection_${selectionKey(listOf(path))}", VERSION)
    }

    fun restore(paths: List<String>, force: Boolean = false, import: (String) -> Boolean) {
        val selections = paths.distinct().filter(String::isNotBlank)
        if (selections.isEmpty()) return
        val migration = selectionKey(selections)
        if (!force && read("completed") == migration) return
        // A failed forced refresh must not leave the previous success marker
        // hiding a provider that needs retry on the next ordinary startup.
        if (force) write("completed", "")
        var completed = true
        for (path in selections) {
            val key = "selection_${selectionKey(listOf(path))}"
            if (!force && read(key) == VERSION) continue
            if (force) write(key, "")
            if (runCatching { import(path) }.getOrDefault(false)) recordImported(path)
            else completed = false
        }
        if (completed) write("completed", migration)
    }

    companion object {
        private const val VERSION = "header-import-v2-saturn"

        internal fun selectionKey(paths: List<String>): String = MessageDigest.getInstance("SHA-256")
            .digest((VERSION + "\u0000" + paths.distinct().filter(String::isNotBlank).sorted()
                .joinToString("\u0000")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
