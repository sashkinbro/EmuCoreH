package com.sbro.emucoreh.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Remembers the disc serial the emulator core reports after a game boots.
 *
 * Disc images whose serial cannot be read while scanning (CHD and similar
 * containers) only reveal their product code at runtime, so the library cache
 * fills the gap from here once a title has been started at least once.
 */
class LearnedSerialRepository private constructor(private val file: File) {

    companion object {
        private val lock = Any()

        fun key(fileName: String): String = fileName.trim().lowercase(Locale.US)

        fun forContext(context: Context): LearnedSerialRepository =
            LearnedSerialRepository(File(context.applicationContext.filesDir, "library/learned-serials.json"))
    }

    fun all(): Map<String, String> = synchronized(lock) { readLocked() }

    fun serialFor(fileName: String): String? {
        if (fileName.isBlank()) return null
        return all()[key(fileName)]
    }

    fun record(fileName: String, serial: String) {
        if (fileName.isBlank() || serial.isBlank()) return
        synchronized(lock) {
            val current = readLocked().toMutableMap()
            if (current[key(fileName)] == serial) return
            current[key(fileName)] = serial
            writeLocked(current)
        }
    }

    private fun readLocked(): Map<String, String> = runCatching {
        if (!file.isFile) return emptyMap()
        val json = JSONObject(file.readText())
        buildMap {
            json.keys().forEach { name ->
                json.optString(name).takeIf { it.isNotBlank() }?.let { put(name, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeLocked(values: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            values.forEach { (name, serial) -> json.put(name, serial) }
            file.parentFile?.mkdirs()
            file.writeText(json.toString())
        }
    }
}
