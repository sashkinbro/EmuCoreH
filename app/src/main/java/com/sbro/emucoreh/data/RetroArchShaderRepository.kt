package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class RetroArchShaderPreset(val label: String, val absolutePath: String)

/**
 * Maps an installed RetroArch preset onto a frontend post-processing effect.
 *
 * The libretro core cannot execute slang/SPIR-V pass chains itself, so the
 * frontend renders the equivalent screen effect for the presets it can
 * approximate and keeps the core's native texture filter for the rest.
 */
object RetroArchShaderEffects {
    const val NONE = 0
    const val CRT = 1
    const val LCD = 2
    const val SHARP_BILINEAR = 3
    const val NEAREST = 4
    const val BILINEAR = 5

    fun classify(presetPath: String?): Int {
        if (presetPath.isNullOrBlank()) return NONE
        val file = File(presetPath)
        if (!file.isFile) return NONE

        val tokens = StringBuilder(presetPath.lowercase())
        runCatching {
            var inspected = 0
            file.forEachLine { rawLine ->
                if (inspected >= 64) return@forEachLine
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachLine
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEachLine
                val key = line.substring(0, separator).trim().lowercase()
                if (key.startsWith("shader") || key.startsWith("texture") || key.startsWith("feedback") ||
                    key.startsWith("parameters")) {
                    tokens.append(' ').append(line.substring(separator + 1).lowercase())
                    inspected++
                }
            }
        }
        val text = tokens.toString()
        return when {
            text.containsAny("lcd", "grid", "gameboy", "dmg", "gba", "handheld") -> LCD
            text.containsAny(
                "crt", "scanline", "shadowmask", "aperture", "slotmask", "trinitron", "zfast",
                "geom", "royale", "ntsc", "composite", "s-video", "svideo", "tvout", "tv-out"
            ) -> CRT
            text.containsAny("sharp") -> SHARP_BILINEAR
            text.containsAny("xbr", "jinc", "sabr", "scalenx", "nnedi", "xbrz") -> NEAREST
            text.containsAny("nearest", "pixel") -> NEAREST
            else -> BILINEAR
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }
}

enum class ShaderPackInstallStage {
    DOWNLOADING,
    INSTALLING
}

data class ShaderPackInstallProgress(
    val stage: ShaderPackInstallStage,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let { total ->
            (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/** Installs complete RetroArch shader packs while preserving their relative-path structure. */
class RetroArchShaderRepository(private val context: Context) {
    companion object {
        const val OFFICIAL_PACK_URL =
            "https://buildbot.libretro.com/assets/frontend/shaders_slang.zip"
        private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
        private const val PROGRESS_UPDATE_INTERVAL_NANOS = 100_000_000L

        internal fun commonArchiveRoot(paths: List<String>): String {
            if (paths.isEmpty()) return ""
            val first = paths.first().substringBefore('/', missingDelimiterValue = "")
            if (first.isBlank()) return ""
            return if (paths.all { it == first || it.startsWith("$first/") }) "$first/" else ""
        }

        internal fun isSafeArchiveEntry(destination: File, relative: String): Boolean {
            val canonicalRoot = destination.canonicalPath + File.separator
            return File(destination, relative).canonicalPath.startsWith(canonicalRoot)
        }

        internal fun containsPresetFiles(directory: File): Boolean =
            ShaderPackStore.usablePresetFiles(directory).isNotEmpty()

    }

    private val root: File
        get() = File(context.filesDir, "retroarch-shaders")

    private val store: ShaderPackStore get() = ShaderPackStore(root, context.cacheDir)

    fun listPresets(): List<RetroArchShaderPreset> = store.listPresets()
    fun hasInstalledPack(): Boolean = store.hasInstalledPack()
    fun removePresets(paths: Set<String>): Result<Int> = store.removePresets(paths)

    fun downloadOfficialPack(
        onProgress: (ShaderPackInstallProgress) -> Unit = {}
    ): Result<Int> = runCatching {
        val archive = File(context.cacheDir, "retroarch-shaders-${UUID.randomUUID()}.zip")
        try {
            val connection = (URL(OFFICIAL_PACK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "EmuCoreH-Android")
            }
            try {
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val totalBytes = connection.contentLengthLong.coerceAtLeast(0L)
                require(totalBytes == 0L || totalBytes <= MAX_ARCHIVE_BYTES) {
                    "Shader archive is too large"
                }
                onProgress(
                    ShaderPackInstallProgress(
                        stage = ShaderPackInstallStage.DOWNLOADING,
                        totalBytes = totalBytes
                    )
                )
                connection.inputStream.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastReportedAtNanos = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_ARCHIVE_BYTES) { "Shader archive is too large" }
                            output.write(buffer, 0, read)
                            val now = System.nanoTime()
                            if (now - lastReportedAtNanos >= PROGRESS_UPDATE_INTERVAL_NANOS) {
                                onProgress(
                                    ShaderPackInstallProgress(
                                        stage = ShaderPackInstallStage.DOWNLOADING,
                                        downloadedBytes = total,
                                        totalBytes = totalBytes
                                    )
                                )
                                lastReportedAtNanos = now
                            }
                        }
                        require(totalBytes == 0L || total == totalBytes) { "Shader download is incomplete" }
                        output.fd.sync()
                        onProgress(
                            ShaderPackInstallProgress(
                                stage = ShaderPackInstallStage.DOWNLOADING,
                                downloadedBytes = total,
                                totalBytes = totalBytes.takeIf { it > 0L } ?: total
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
            onProgress(ShaderPackInstallProgress(stage = ShaderPackInstallStage.INSTALLING))
            store.installArchive(archive, official = true)
        } finally {
            archive.delete()
        }
    }

    fun importArchive(uri: Uri): Result<Int> = runCatching {
        val archive = File(context.cacheDir, "shader-import-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ARCHIVE_BYTES) { "Shader archive is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("Unable to open shader archive")
            store.installArchive(archive, official = false)
        } finally {
            archive.delete()
        }
    }

}
