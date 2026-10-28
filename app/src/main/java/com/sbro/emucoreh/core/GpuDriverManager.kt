package com.sbro.emucoreh.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class InstalledGpuDriver(
    val name: String,
    val mainLibrary: String,
    val mainLibraryPath: String,
    val isUsable: Boolean,
    val vendor: GpuDriverVendor = GpuDriverVendor.UNKNOWN
)

class GpuDriverManager(private val context: Context) {

    fun listInstalledDrivers(): List<InstalledGpuDriver> {
        val root = driversRoot()
        if (!root.exists()) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".install-") && !it.name.startsWith(".backup-") }
            .mapNotNull { driverDir ->
                val mainLibrary = readMainLibraryName(driverDir) ?: return@mapNotNull null
                val mainLibraryFile = libraryInDirectory(driverDir, mainLibrary) ?: return@mapNotNull null
                if (mainLibraryFile.isFile) {
                    ensureDriverLibraryPermissions(mainLibraryFile)
                }
                val vendor = GpuDriverCompatibility.installedDriverVendor(mainLibraryFile)
                InstalledGpuDriver(
                    name = driverDir.name,
                    mainLibrary = mainLibrary,
                    mainLibraryPath = mainLibraryFile.absolutePath,
                    isUsable = isValidDriverLibrary(mainLibraryFile) &&
                        isCompatibleForCurrentDevice(mainLibraryFile, vendor),
                    vendor = vendor
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun installFromArchive(uri: Uri, expectedVendor: GpuDriverVendor? = null): String {
        val archiveName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "custom-driver.zip"
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open archive")
        return input.use { installFromArchive(it, archiveName, expectedVendor) }
    }

    fun installFromArchive(file: File, expectedVendor: GpuDriverVendor? = null): String {
        file.inputStream().use { input ->
            return installFromArchive(input, file.name, expectedVendor)
        }
    }

    fun remove(driverName: String) {
        driverDirectory(driverName).deleteRecursively()
    }

    fun readMainLibraryPath(driverName: String): String? {
        val driverDir = driverDirectory(driverName)
        val mainLibrary = readMainLibraryName(driverDir) ?: return null
        return libraryInDirectory(driverDir, mainLibrary)?.absolutePath
    }

    fun resolveUsableDriverPath(preferredPath: String?): String? {
        if (!GpuDriverCompatibility.supportsCustomDrivers(context)) {
            return null
        }

        preferredPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { file -> listInstalledDrivers().any { it.mainLibraryPath == file.absolutePath && it.isUsable } }
            ?.let { file ->
                ensureDriverLibraryPermissions(file)
                return file.absolutePath
            }

        return null
    }

    fun isVendorCompatible(libraryFile: File): Boolean =
        isCompatibleForCurrentDevice(libraryFile, GpuDriverCompatibility.installedDriverVendor(libraryFile))

    fun isVendorCompatible(libraryPath: String?): Boolean {
        val file = libraryPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return false
        return isVendorCompatible(file)
    }

    private fun installFromArchive(
        input: InputStream,
        archiveName: String,
        expectedVendor: GpuDriverVendor? = null
    ): String {
        val driverName = archiveName.substringBeforeLast('.').ifBlank { "custom-driver" }
        val targetDir = driverDirectory(driverName)
        val root = driversRoot()
        check(root.isDirectory || root.mkdirs()) { "Unable to create driver directory" }
        val stagedDir = File(root, ".install-${UUID.randomUUID()}")
        check(stagedDir.mkdir()) { "Unable to stage driver" }
        try {
            extractAndValidate(input, stagedDir, expectedVendor)
            val backupDir = File(root, ".backup-${UUID.randomUUID()}")
            val hadPrevious = targetDir.exists()
            if (hadPrevious) check(targetDir.renameTo(backupDir)) { "Unable to stage previous driver" }
            if (!stagedDir.renameTo(targetDir)) {
                if (hadPrevious) check(backupDir.renameTo(targetDir)) { "Unable to restore previous driver" }
                error("Unable to install driver")
            }
            if (hadPrevious) backupDir.deleteRecursively()
            return driverName
        } finally {
            stagedDir.deleteRecursively()
        }
    }

    private fun extractAndValidate(input: InputStream, targetDir: File, expectedVendor: GpuDriverVendor?) {

        val extractedFiles = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val entryName = entry.name ?: return@forEach
                if (entry.isDirectory) return@forEach
                val normalizedEntryName = entryName.replace('\\', '/').trimStart('/')
                if (normalizedEntryName.isBlank() || normalizedEntryName.contains("..")) {
                    error("Archive contains an invalid file path")
                }
                val outFile = File(targetDir, normalizedEntryName)
                val canonicalTarget = targetDir.canonicalFile
                val canonicalOutFile = outFile.canonicalFile
                if (!canonicalOutFile.toPath().startsWith(canonicalTarget.toPath())) {
                    error("Archive contains an invalid file path")
                }
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    zip.copyTo(output)
                }
                if (outFile.extension.equals("so", ignoreCase = true)) {
                    ensureDriverLibraryPermissions(outFile)
                }
                extractedFiles += normalizedEntryName
            }
        }

        val selectedDriver = selectMainDriverFile(extractedFiles) ?: run {
            targetDir.deleteRecursively()
            error("Archive does not contain a Vulkan driver file")
        }

        // A corrupt or truncated .so would only explode later as a native
        // crash inside the third-party driver (e.g. Turnip SIGSEGV in
        // strncmp). Reject it here while we can still report a clean error.
        val driverLibrary = File(targetDir, selectedDriver)
        if (!isValidDriverLibrary(driverLibrary)) {
            targetDir.deleteRecursively()
            error("Archive contains a corrupt Vulkan driver file")
        }

        // Refuse cross-vendor packages outright: an Adreno Turnip pack must
        // not install on a Mali device and a PanVK pack must not install on an
        // Adreno device. The catalog `gpu` field is trusted when the caller
        // supplies it; local imports are classified from ELF markers.
        val detectedVendor = expectedVendor
            ?.takeIf { it != GpuDriverVendor.UNKNOWN }
            ?: GpuDriverCompatibility.vendorOfDriverLibrary(driverLibrary)
        val deviceVendor = GpuDriverCompatibility.deviceVendor(context)
        if (!GpuDriverCompatibility.isInstalledDriverCompatible(
                selectedDriver,
                detectedVendor,
                deviceVendor,
                DeviceGpuInfoProvider.get(context)
            )
        ) {
            targetDir.deleteRecursively()
            error(
                "This driver is for ${detectedVendor.displayName} GPUs and cannot be used on this " +
                    "${deviceVendor.displayName} device"
            )
        }

        File(targetDir, "driver_name.txt").writeText("$selectedDriver\n")
        GpuDriverCompatibility.writeInstalledDriverVendor(driverLibrary, detectedVendor)
    }

    private fun driverDirectory(name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && !name.startsWith(".install-") && !name.startsWith(".backup-")) {
            "Invalid driver name"
        }
        val root = driversRoot().canonicalFile
        return File(root, name).canonicalFile.also { require(it.parentFile == root) { "Invalid driver name" } }
    }

    private fun libraryInDirectory(directory: File, library: String): File? {
        val root = directory.canonicalFile
        val file = File(root, library).canonicalFile
        return file.takeIf { it.toPath().startsWith(root.toPath()) && it != root }
    }

    private fun driversRoot(): File = File(context.filesDir, "driver")

    private fun isCompatibleForCurrentDevice(libraryFile: File, vendor: GpuDriverVendor): Boolean =
        GpuDriverCompatibility.isInstalledDriverCompatible(
            mainLibraryName = libraryFile.name,
            driverVendor = vendor,
            deviceVendor = GpuDriverCompatibility.deviceVendor(context),
            deviceGpu = DeviceGpuInfoProvider.get(context)
        )

    private fun readMainLibraryName(driverDir: File): String? {
        val metadataFile = File(driverDir, "driver_name.txt")
        if (!metadataFile.isFile) return null
        return metadataFile.readText()
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() }
    }

    private fun ensureDriverLibraryPermissions(file: File) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    private fun selectMainDriverFile(extractedFiles: List<String>): String? {
        val sharedLibraries = extractedFiles
            .filter { it.endsWith(".so", ignoreCase = true) }
        if (sharedLibraries.isEmpty()) return null

        return sharedLibraries.firstOrNull { file ->
            val name = file.substringAfterLast('/')
            name.equals("libvulkan.so", ignoreCase = true)
        } ?: sharedLibraries.firstOrNull { file ->
            val name = file.substringAfterLast('/')
            name.startsWith("vulkan.", ignoreCase = true) || name.startsWith("libvulkan.", ignoreCase = true)
        } ?: sharedLibraries.firstOrNull { file ->
            file.contains("vulkan", ignoreCase = true)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }
}

internal fun isValidDriverLibrary(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != magic.size) return false
            // ELF magic: 0x7F 'E' 'L' 'F'. Catches empty files, HTML error
            // pages and misnamed zips before they reach dlopen.
            magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }
}
