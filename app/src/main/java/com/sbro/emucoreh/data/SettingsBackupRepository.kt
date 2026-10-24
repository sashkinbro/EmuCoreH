package com.sbro.emucoreh.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sbro.emucoreh.core.BackupSessionGate
import com.sbro.emucoreh.core.EmulatorStorage
import com.sbro.emucoreh.data.drive.DriveBackupArchive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first

class SettingsBackupRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val perGameSettingsRepository: PerGameSettingsRepository,
    private val cheatRepository: CheatRepository
) {
    private val homeBackgroundRepository = HomeBackgroundRepository(context)
    private val customFontRepository = CustomFontRepository(context)

    suspend fun backup(destination: Uri, includeSaveStates: Boolean = false): Boolean {
        return runCatching {
            val output = context.contentResolver.openOutputStream(destination) ?: return@runCatching false
            output.use {
                BackupSessionGate.whileStopped {
                    DriveBackupArchive(context).recoverPending()
                    writeBackup(it, includeSaveStates)
                }
            }
            true
        }.onFailure { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Unable to export settings backup", error)
        }.getOrDefault(false)
    }

    suspend fun restore(source: Uri): Boolean {
        return runCatching {
            val input = context.contentResolver.openInputStream(source) ?: return@runCatching false
            input.use {
                BackupSessionGate.whileStopped {
                    DriveBackupArchive(context).recoverPending()
                    restoreBackup(it)
                }
            }
            true
        }.onFailure { error ->
            if (com.sbro.emucoreh.BuildConfig.DEBUG) Log.e(TAG, "Unable to restore settings backup", error)
        }.getOrDefault(false)
    }

    internal suspend fun writeBackup(output: OutputStream, includeSaveStates: Boolean = false) {
        ZipOutputStream(output).use { zip ->
            zip.writeJsonEntry("settings.json", preferences.exportJson().put("segaSettings",
                com.sbro.emucoreh.core.SegaSettingsBackup.export(context)))
            zip.writeJsonEntry("per-game-settings.json", perGameSettingsRepository.exportJson())
            zip.writeJsonEntry("cheats.json", cheatRepository.exportJson())
            zip.writeFlatDirectory(
                "cheat-files",
                EmulatorStorage.importedCheatsDir(context),
                ::isCheatFile
            )
            zip.writeDirectory("memory-cards", memoryCardsDir())
            zip.writeDirectory("battery-saves", com.sbro.emucoreh.core.SegaSaveFiles.directory(
                context, preferences.getEmulatorDataPathSync())) { file ->
                val root = com.sbro.emucoreh.core.SegaSaveFiles.directory(context, preferences.getEmulatorDataPathSync())
                com.sbro.emucoreh.core.SegaSaveFiles.isBackupPath(file.relativeTo(root).invariantSeparatorsPath)
            }
            com.sbro.emucoreh.core.Model3SaveFiles.backupFiles(
                com.sbro.emucoreh.core.Model3SaveFiles.directory(context, preferences.getEmulatorDataPathSync())
            ).forEach { (relative, file) -> zip.writeFileEntry("model3-nvram/$relative", file) }
            if (includeSaveStates) {
                zip.writeFlatDirectory("save-states", saveStatesDir()) { file ->
                    isQuickSaveState(file) || (isSaveStatePreviewName(file.name) &&
                        File(file.path.removeSuffix(".png")).isFile && isValidSaveStatePreview(file))
                }
            }
            val activeBackgroundType = preferences.homeBackgroundType.first()
            homeBackgroundRepository.existingFile(activeBackgroundType)?.let { file ->
                zip.writeFileEntry(
                    "customization/home-background.${activeBackgroundType.backupExtension}",
                    file
                )
            }
            customFontRepository.installedFile()?.let { file ->
                zip.writeFileEntry(CustomFontRepository.BACKUP_ENTRY, file)
            }
        }
    }

    internal suspend fun restoreBackup(input: InputStream) {
        var restoredBackgroundType: HomeBackgroundType? = null
        var restoredCustomFont = false
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                when (entry.name) {
                    "settings.json" -> {
                        val settings = JSONObject(zip.readBytes().decodeToString())
                        preferences.importJson(settings)
                        com.sbro.emucoreh.core.SegaSettingsBackup.restore(context, settings.optJSONObject("segaSettings"))
                    }
                    "per-game-settings.json" -> perGameSettingsRepository.importJson(JSONObject(zip.readBytes().decodeToString()))
                    "cheats.json" -> cheatRepository.importJson(JSONObject(zip.readBytes().decodeToString()))
                    CustomFontRepository.BACKUP_ENTRY -> {
                        customFontRepository.restoreFromBackup(zip).getOrThrow()
                        restoredCustomFont = true
                    }
                    else -> {
                        val backgroundType = entry.name.toBackgroundBackupType()
                        if (backgroundType != null && !entry.isDirectory) {
                            homeBackgroundRepository.restoreFromBackup(backgroundType, zip).getOrThrow()
                            restoredBackgroundType = backgroundType
                        } else if (entry.name.startsWith("memory-cards/") && !entry.isDirectory) {
                            val relative = entry.name.removePrefix("memory-cards/")
                            val root = memoryCardsDir().canonicalFile
                            val target = File(root, relative).canonicalFile
                            require(target.path.startsWith(root.path + File.separator)) {
                                "Invalid memory-card backup path"
                            }
                            restoreBackupSaveFile(zip, target, 128L * 1024 * 1024, allowEmpty = false)
                        } else if (entry.name.startsWith("battery-saves/") && !entry.isDirectory) {
                            val relative = entry.name.removePrefix("battery-saves/")
                            require(com.sbro.emucoreh.core.SegaSaveFiles.isBackupPath(relative)) { "Invalid battery-save path" }
                            val root = com.sbro.emucoreh.core.SegaSaveFiles.directory(context, preferences.getEmulatorDataPathSync()).canonicalFile
                            val target = File(root, relative).canonicalFile
                            require(target.path.startsWith(root.path + File.separator)) { "Invalid battery-save path" }
                            restoreBackupSaveFile(zip, target, 16L * 1024 * 1024, allowEmpty = false)
                        } else if (entry.name.startsWith("model3-nvram/") && !entry.isDirectory) {
                            com.sbro.emucoreh.core.Model3SaveFiles.restore(zip,
                                com.sbro.emucoreh.core.Model3SaveFiles.directory(context, preferences.getEmulatorDataPathSync()),
                                entry.name.removePrefix("model3-nvram/"))
                        } else if (entry.name.startsWith("save-states/") && !entry.isDirectory) {
                            val relative = entry.name.removePrefix("save-states/")
                            require(relative == File(relative).name &&
                                (isQuickSaveState(File(relative)) || isSaveStatePreviewName(relative))) {
                                "Invalid save-state backup path"
                            }
                            val root = saveStatesDir().canonicalFile
                            val target = File(root, relative).canonicalFile
                            require(target.parentFile == root) {
                                "Invalid save-state backup path"
                            }
                            root.mkdirs()
                            if (isSaveStatePreviewName(relative)) copySaveStatePreview(zip, target)
                            else restoreBackupSaveFile(zip, target, 1024L * 1024 * 1024, allowEmpty = false)
                        } else if (entry.name.startsWith("cheat-files/") && !entry.isDirectory) {
                            val relative = entry.name.removePrefix("cheat-files/")
                            require(relative == File(relative).name && isCheatFile(File(relative))) {
                                "Invalid cheat backup path"
                            }
                            val root = EmulatorStorage.importedCheatsDir(context).canonicalFile
                            val target = File(root, relative).canonicalFile
                            require(target.parentFile == root) { "Invalid cheat backup path" }
                            restoreBackupSaveFile(zip, target, 16L * 1024 * 1024, allowEmpty = true)
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        restoredBackgroundType?.let { preferences.setHomeBackgroundType(it) }
        if (restoredCustomFont) {
            preferences.setCustomFontInstalled(
                preferences.customFontName.first() ?: CustomFontRepository.DEFAULT_DISPLAY_NAME
            )
        }
    }

    private fun memoryCardsDir(): File {
        return EmulatorStorage.memoryCardsDir(context, preferences.getEmulatorDataPathSync())
    }

    private fun saveStatesDir(): File {
        return EmulatorStorage.saveStatesDir(context, preferences.getEmulatorDataPathSync())
    }

    private companion object {
        const val TAG = "SettingsBackup"
    }
}

private val HomeBackgroundType.backupExtension: String
    get() = when (this) {
        HomeBackgroundType.IMAGE -> "image"
        HomeBackgroundType.GIF -> "gif"
        HomeBackgroundType.VIDEO -> "video"
        HomeBackgroundType.NONE,
        HomeBackgroundType.BUILT_IN -> error("Bundled background has no backup file")
    }

private fun String.toBackgroundBackupType(): HomeBackgroundType? = when (this) {
    "customization/home-background.image" -> HomeBackgroundType.IMAGE
    "customization/home-background.gif" -> HomeBackgroundType.GIF
    "customization/home-background.video" -> HomeBackgroundType.VIDEO
    else -> null
}

private fun ZipOutputStream.writeJsonEntry(name: String, json: JSONObject) {
    putNextEntry(ZipEntry(name))
    write(json.toString(2).toByteArray())
    closeEntry()
}

private fun isQuickSaveState(file: File): Boolean {
    return file.name.endsWith(".rstate", ignoreCase = true) &&
        !file.name.contains(".resume.", ignoreCase = true)
}

private fun isCheatFile(file: File): Boolean {
    return file.extension.equals("pnach", ignoreCase = true)
}

private fun ZipOutputStream.writeDirectory(
    prefix: String,
    directory: File,
    include: (File) -> Boolean = { true }
) {
    directory.listFiles().orEmpty().forEach { file ->
        if (file.isDirectory) {
            writeDirectory("$prefix/${file.name}", file, include)
        } else if (include(file)) {
            putNextEntry(ZipEntry("$prefix/${file.name}"))
            file.inputStream().use { it.copyTo(this) }
            closeEntry()
        }
    }
}

private fun ZipOutputStream.writeFlatDirectory(
    prefix: String,
    directory: File,
    include: (File) -> Boolean
) {
    directory.listFiles().orEmpty()
        .filter { it.isFile && include(it) }
        .forEach { file ->
            putNextEntry(ZipEntry("$prefix/${file.name}"))
            file.inputStream().use { it.copyTo(this) }
            closeEntry()
        }
}

private fun ZipOutputStream.writeFileEntry(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}
