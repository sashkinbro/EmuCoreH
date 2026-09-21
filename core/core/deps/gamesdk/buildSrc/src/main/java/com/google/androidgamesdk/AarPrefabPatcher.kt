package com.google.androidgamesdk

import com.google.androidgamesdk.OsSpecificTools.Companion.joinPath
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import java.io.File

/**
 * Helper to inject prefab files into an AAR. Can be removed once we can use
 * prefabPublishing in build.gradle of the projects.
 */
class AarPrefabPatcher {
    fun injectPrefabFolder(aarPath: String, prefabFolderPath: String) {
        val aarZipFile = ZipFile(aarPath)
        val prefabFolderFile = File(prefabFolderPath)
        val zipParameters = ZipParameters()
        zipParameters.isIncludeRootFolder = false
        zipParameters.rootFolderNameInZip = "prefab"
        aarZipFile.addFolder(prefabFolderFile, zipParameters)
    }

    // Copy classes.jar from $aarPath to $prefabFolderPath, if it's not there already, filtering
    // the classes to remove non-gamecontroller classes.
    fun extractAarClasses(aarPath: String, prefabFolderPath: String): File {
        val jarName = "classes.jar"
        val jarPath = joinPath(prefabFolderPath, jarName)

        val outputFile = File(jarPath)
        if (!outputFile.exists()) {
            val aarZipFile = ZipFile(aarPath)
            aarZipFile.extractFile(jarName, prefabFolderPath)
            aarZipFile.removeFile(jarName)
            // Remove the non-GameController classes and
            // put the modified classes.jar back in the .aar
            val directoryToRemove = "com/google/androidgamesdk"
            val directoryNotToRemove = "com/google/android/games/paddleboat"
            removeClasses(jarPath, directoryToRemove, directoryNotToRemove)
            addClassesJarToAar(jarPath, aarZipFile)
        }
        return outputFile
    }

    // Filter classes in aarPath->classes.jar.
    fun filterClasses(aarPath: String, directoryToRemove: String,
                      directoryNotToRemove: String?) {
        val temporaryDirectory =
            createTempDir("gamesdk-remove-classes")
        var aarZipFile = ZipFile(aarPath)
        val jarName = "classes.jar"
        var jarPath = joinPath(temporaryDirectory.absolutePath, jarName)
        aarZipFile.extractFile(jarName, temporaryDirectory.absolutePath)
        aarZipFile.removeFile(jarName)
        removeClasses(jarPath, directoryToRemove, directoryNotToRemove)
        addClassesJarToAar(jarPath, aarZipFile)
    }

    private fun removeClasses(jarPath: String, directoryToRemove: String,
                              directoryNotToRemove: String?) {
        var jarZipFile = ZipFile(jarPath)

        val fileHeaders = jarZipFile.getFileHeaders();
        val removeList = mutableListOf<String>()
        for (fileHeader in fileHeaders) {
            val fileName = fileHeader.getFileName()
            if (directoryNotToRemove==null || !fileName.startsWith(directoryNotToRemove)) {
                if ( fileName.startsWith(directoryToRemove)) {
                    removeList.add(fileName)
                }
            }
        }

        for (removeFileName in removeList) {
            jarZipFile.removeFile(removeFileName)
        }
    }

    private fun addClassesJarToAar(jarPath: String, aarZipFile: ZipFile) {
        val zipParameters = ZipParameters()
        zipParameters.isIncludeRootFolder = false
        aarZipFile.addFile(jarPath, zipParameters)
    }
}
