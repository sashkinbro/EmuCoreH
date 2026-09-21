package com.google.androidgamesdk

/**
 * Describe an Android project, usually a sample, that can be configured
 * to be included and/or built into the Game SDK archive.
 */
data class ExternalAndroidProject(val projectRootPath: String) {
    var debugApkDestinationPath: String? = null
        private set
    var debugApkDestinationName: String? = null
        private set
    var projectDestinationPath: String? = null
        private set
    var debugApkLocation: String = OsSpecificTools.joinPath(
        projectRootPath,
        "app/build/outputs/apk/debug/app-debug.apk"
    )

    /**
     * Mark the project to be built and the resulting APK to be included
     * in the GameSDK archive, in the specified path with the specified name.
     */
    fun buildDebugApkTo(
        debugApkDestinationPath: String,
        debugApkDestinationName: String
    ): ExternalAndroidProject {
        this.debugApkDestinationPath = debugApkDestinationPath
        this.debugApkDestinationName = debugApkDestinationName
        return this
    }

    /**
     * Mark the project to be included in the Game SDK archive, using
     * the specified destination path.
     */
    fun copySourcesTo(projectDestinationPath: String): ExternalAndroidProject {
        this.projectDestinationPath = projectDestinationPath
        return this
    }

    /**
     * Override the location where the APK resulting from building the project
     * can be found. By default, the APK is found in "app/build/outputs/apk/debug/app-debug.apk",
     * relative to the Android project folder root.
     */
    fun setDebugApkLocation(debugApkLocation: String): ExternalAndroidProject {
        this.debugApkLocation = debugApkLocation
        return this
    }
}
