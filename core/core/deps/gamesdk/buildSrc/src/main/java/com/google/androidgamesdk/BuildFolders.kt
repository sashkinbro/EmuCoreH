package com.google.androidgamesdk

/**
 * Describes folders to use for a build of a library with CMake.
 */
data class BuildFolders(
    val projectFolder: String,
    val workingFolder: String,
    val outputFolder: String
)
