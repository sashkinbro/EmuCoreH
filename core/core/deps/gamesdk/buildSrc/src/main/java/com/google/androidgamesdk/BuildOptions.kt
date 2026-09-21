package com.google.androidgamesdk

/**
 * Describes the options to build a library with CMake.
 */
data class BuildOptions(
    val buildType: String,
    val threadChecks: Boolean,
    val stl: String,
    val arch: String
)
