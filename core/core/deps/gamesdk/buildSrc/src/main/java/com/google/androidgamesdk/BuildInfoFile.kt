package com.google.androidgamesdk

import com.google.gson.GsonBuilder
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.collections.List

class BuildInfoFile {
    private data class ArtifactBuildInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val path: String = "",
        val sha: String,
        val groupZipPath: String = "",
        val projectZipPath: String,
        val groupIdRequiresSameVersion: Boolean = false,
        val dependencies: List<Dependency> = arrayListOf(),
        val checks: List<Check> = arrayListOf(),
        // This is used by Jetpad, and should always be "gamesdk-full" unless the target changes
        val target: String = "gamesdk-full",
    ) {
        data class Dependency(
            val groupId: String,
            val artifactId: String,
            val version: String,
            val isTipOfTree: Boolean
        )

        data class Check(
            val name: String,
            val passing: Boolean
        )
    }

    private data class ArtifactsBuildInfo(
        val artifacts: List<ArtifactBuildInfo>
    )

    fun getGitCommitShaAtHead(): String {
        val parts = "git rev-parse HEAD".split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        proc.waitFor(10, TimeUnit.SECONDS)
        return proc.inputStream.bufferedReader().readText().trim()
    }

    fun writeLibrariesAggregateBuildInfoFile(
        nativeLibraries: Collection<NativeLibrary>,
        androidArchiveLibraries: Collection<AndroidArchiveLibrary>,
        distPath: String,
        packageName: String
    ) {
        val headCommitSha = getGitCommitShaAtHead()

        val artifactsBuildInfo: List<ArtifactBuildInfo> = androidArchiveLibraries.map {
            androidArchiveLibrary: AndroidArchiveLibrary ->
            ArtifactBuildInfo(
                groupId = "androidx.games",
                artifactId = androidArchiveLibrary.aarLibraryName,
                path = androidArchiveLibrary.projectName,
                projectZipPath = packageName + '/' +
                    androidArchiveLibrary.aarLibraryName + "-maven-zip.zip",
                sha = headCommitSha,
                version = androidArchiveLibrary.aarVersion
            )
        }

        val gson = GsonBuilder().setPrettyPrinting().create()
        val serializedInfo: String = gson.toJson(
            ArtifactsBuildInfo(
                artifacts = artifactsBuildInfo
            )
        )
        val outputFile = Paths.get(
            distPath,
            "androidx_aggregate_build_info.txt"
        ).toFile()
        outputFile.writeText(serializedInfo)
    }

    fun writeLibrariesIndividualBuildInfoFiles(
        androidArchiveLibraries: Collection<AndroidArchiveLibrary>,
        distPath: String,
        packageName: String
    ) {
        val headCommitSha = getGitCommitShaAtHead()

        val artifactsBuildInfo: List<ArtifactBuildInfo> = androidArchiveLibraries.map {
            androidArchiveLibrary: AndroidArchiveLibrary ->
            ArtifactBuildInfo(
                groupId = "androidx.games",
                artifactId = androidArchiveLibrary.aarLibraryName,
                path = androidArchiveLibrary.projectName,
                projectZipPath = packageName + '/' +
                    androidArchiveLibrary.aarLibraryName + "-maven-zip.zip",
                sha = headCommitSha,
                version = androidArchiveLibrary.aarVersion
            )
        }

        for (artifact in artifactsBuildInfo){
            val gson = GsonBuilder().setPrettyPrinting().create()
            val serializedInfo: String = gson.toJson(
                artifact
            )
            val filename = artifact.groupId + "_" + artifact.artifactId + "_build_info.txt"

            val outputFile = Paths.get(
                distPath,
                filename
            ).toFile()
            outputFile.writeText(serializedInfo)
        }
    }

}
