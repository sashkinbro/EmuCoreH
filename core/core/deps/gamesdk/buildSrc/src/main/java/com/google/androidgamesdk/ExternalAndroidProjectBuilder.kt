package com.google.androidgamesdk

import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem

/**
 * Build the samples for a given native library and copy the resulting APK
 * of each project to the specified output path.
 */
class ExternalAndroidProjectBuilder(
    private val project: Project,
    private val outputBasePath: String
) {
    var skipSamplesBuild: Boolean = false

    /**
     * Build the sample/projects of the specified library, then copy the
     * resulting artifact to the output folder. Throws if one or
     * more builds failed.
     */
    fun buildSampleArtifacts(nativeLibrary: NativeLibrary) {
        val buildFailures = mutableMapOf<ExternalAndroidProject, Throwable>()
        nativeLibrary.sampleAndroidProjects.forEach { externalAndroidProject ->
            try {
                buildSampleApk(externalAndroidProject)
            } catch (executionException: Throwable) {
                buildFailures[externalAndroidProject] = executionException
            }
        }

        if (buildFailures.isNotEmpty()) {
            project.logger.error(
                "Unable to build ${buildFailures.size} project(s)."
            )
            throw buildFailures.values.first()
        }
    }

    private fun buildSampleApk(externalAndroidProject: ExternalAndroidProject) {
        val destinationPath = externalAndroidProject.debugApkDestinationPath
        val destinationName = externalAndroidProject.debugApkDestinationName
        if (destinationPath == null || destinationName == null) return

        val projectRootPath = externalAndroidProject.projectRootPath

        if (!skipSamplesBuild) {
            project.logger.info("Launching build of $projectRootPath.")
            project.exec {
                workingDir(externalAndroidProject.projectRootPath)
                commandLine(getGradleBuildCommandLine())
            }

            project.logger.info(
                "Finished build of $projectRootPath without errors."
            )
        } else {
            project.logger.warn("Skipped build of $projectRootPath.")
        }

        if (!project.file(externalAndroidProject.debugApkLocation).exists()) {
            throw RuntimeException(
                externalAndroidProject.debugApkLocation +
                    " was not found after building " +
                    "the sample. Verify that the build " +
                    "was properly done."
            )
        }

        project.copy {
            from(
                externalAndroidProject.debugApkLocation
            )
            into(
                OsSpecificTools.joinPath(
                    outputBasePath,
                    destinationPath
                )
            )
            rename("app-debug.apk", destinationName)
        }
    }

    private fun getGradleBuildCommandLine(): List<String> {
        if (OperatingSystem.current().isWindows) {
            return listOf("cmd", "/c", "gradlew.bat build")
        }

        return listOf("/bin/bash", "-c", "./gradlew build")
    }
}
