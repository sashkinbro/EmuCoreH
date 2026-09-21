package com.google.androidgamesdk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class ToolchainEnumeratorTest {
    private fun createMockProject(): Project {
        return ProjectBuilder.builder()
            .withProjectDir(File("/tmp/test"))
            .withName("Mock GameSDK project")
            .build()
    }

    private fun makeUpLibraryInfo(name: String) =
        LibraryInfo(name, name + "_j", null, SemanticVersion(1, 0, 0), "alpha")

    @Test
    fun canFilterLibraries() {
        val alwaysLibrary = NativeLibrary(makeUpLibraryInfo("alwayslib"))
        val apiMin24Library = NativeLibrary(makeUpLibraryInfo("apiMin24Lib"))
            .setMinimumAndroidApiLevel(24)
        val ndkMin18Library = NativeLibrary(makeUpLibraryInfo("ndkMin28Lib"))
            .setMinimumNdkVersion(18)
        val someStlOnlyLibrary =
            NativeLibrary(makeUpLibraryInfo("someStlOnlyLib"))
                .setSupportedStlVersions(listOf("c++_static"))

        val allLibraries = listOf(
            alwaysLibrary, apiMin24Library, ndkMin18Library, someStlOnlyLibrary
        )
        val project = createMockProject()

        assertEquals(
            allLibraries,
            ToolchainEnumerator.filterBuiltLibraries(
                allLibraries,
                BuildOptions(
                    stl = "c++_static",
                    arch = "armeabi-v7a",
                    buildType = "Release",
                    threadChecks = true
                ),
                SpecificToolchain(project, "25", "r20")
            )
        )
        assertEquals(
            listOf(alwaysLibrary, apiMin24Library, ndkMin18Library),
            ToolchainEnumerator.filterBuiltLibraries(
                allLibraries,
                BuildOptions(
                    stl = "c++_shared",
                    arch = "armeabi-v7a",
                    buildType = "Release",
                    threadChecks = true
                ),
                SpecificToolchain(project, "25", "r20")
            )
        )
        assertEquals(
            listOf(alwaysLibrary, ndkMin18Library),
            ToolchainEnumerator.filterBuiltLibraries(
                allLibraries,
                BuildOptions(
                    stl = "c++_shared",
                    arch = "armeabi-v7a",
                    buildType = "Release",
                    threadChecks = true
                ),
                SpecificToolchain(project, "16", "r20")
            )
        )
        assertEquals(
            listOf(alwaysLibrary),
            ToolchainEnumerator.filterBuiltLibraries(
                allLibraries,
                BuildOptions(
                    stl = "c++_shared",
                    arch = "armeabi-v7a",
                    buildType = "Release",
                    threadChecks = true
                ),
                SpecificToolchain(project, "16", "r17")
            )
        )
    }
}
