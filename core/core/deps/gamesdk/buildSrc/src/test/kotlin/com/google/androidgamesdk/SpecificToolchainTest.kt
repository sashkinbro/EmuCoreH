package com.google.androidgamesdk

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecificToolchainTest {
    private fun createMockProject(): Project {
        return ProjectBuilder.builder()
            .withProjectDir(File("/tmp/test"))
            .withName("Mock GameSDK project")
            .build()
    }

    @Test
    fun generateProperBuildKeys() {
        val project = createMockProject()
        val toolchain = SpecificToolchain(project, "15", "21")
        val buildOptions = BuildOptions("release", false, "c++_static", "x86")
        assertEquals(
            "x86_API15_NDK21_cpp_static_release",
            toolchain.getBuildKey(buildOptions)
        )
    }
}
