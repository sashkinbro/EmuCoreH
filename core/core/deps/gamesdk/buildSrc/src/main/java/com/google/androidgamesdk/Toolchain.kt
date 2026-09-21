package com.google.androidgamesdk

import com.google.androidgamesdk.OsSpecificTools.Companion.joinPath
import com.google.androidgamesdk.OsSpecificTools.Companion.osExecutableSuffix
import com.google.androidgamesdk.OsSpecificTools.Companion.osFolderName
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties
import java.util.regex.Pattern

abstract class Toolchain {
    protected abstract var project_: Project
    protected abstract var androidVersion_: String
    protected abstract var ndkVersion_: String

    override fun toString(): String {
        return "Toolchain(androidVersion=$androidVersion_, " +
            "ndkVersion=$ndkVersion_)"
    }

    abstract fun getAndroidNDKPath(): String

    fun getAndroidVersion(): String {
        return androidVersion_
    }

    fun getNdkVersion(): String {
        return ndkVersion_
    }

    fun getNdkVersionNumber(): String {
        return extractNdkMajorVersion(ndkVersion_)
    }

    fun getBuildKey(buildOptions: BuildOptions): String {
        return buildOptions.arch + "_API" + androidVersion_ +
            "_NDK" + getNdkVersionNumber() + '_' +
            sanitize(buildOptions.stl) + '_' + buildOptions.buildType
    }

    protected fun getNdkVersionFromPropertiesFile(): String {
        val file = File(getAndroidNDKPath(), "source.properties")
        if (!file.exists()) {
            println("Warning: can't get NDK version from " + getAndroidNDKPath()+ "/source.properties")
            return "UNKNOWN"
        } else {
            val props = loadPropertiesFromFile(file)
            val ver = props["Pkg.Revision"]
            if (ver is String) {
                return extractNdkMajorVersion(ver)
            }
            /* ktlint-disable max-line-length */
            println("Warning: can't get NDK version from source.properties (Pkg.Revision is not a string)")
            /* ktlint-enable max-line-length */
            return "UNKNOWN"
        }
    }

    private fun sanitize(s: String): String {
        return s.replace('+', 'p')
    }

    fun findNDKTool(name: String): String {
        val searchedPath = joinPath(getAndroidNDKPath(), "toolchains")
        val tools = project_.fileTree(searchedPath).matching {
            include("**/bin/" + name + osExecutableSuffix())
            // Be sure not to use tools that would not support architectures
            // for which we're building:
            exclude("**/aarch64-*", "**/arm-*")
        }
        if (tools.isEmpty) {
            throw GradleException("No $name found in $searchedPath")
        }
        return tools.files.last().toString()
    }

    fun getArPath(): String {
        return findNDKTool("ar")
    }

    fun getCMakePath(): String {
        return File(
            "${project_.projectDir}/../prebuilts/cmake/" +
                osFolderName(ExternalToolName.CMAKE) +
                "/bin/cmake" + osExecutableSuffix()
        ).path
    }

    fun getNinjaPath(): String {
        return File(
            "${project_.projectDir}/../prebuilts/ninja/" +
                osFolderName(ExternalToolName.CMAKE) +
                "/ninja" + osExecutableSuffix()
        ).path
    }

    fun getProtobufInstallPath(): String {
        return File(
            "${project_.projectDir}/third_party/protoc-3.21.7/" +
                osFolderName(ExternalToolName.PROTOBUF)
        ).path
    }

    protected fun extractNdkMajorVersion(ndkVersion: String): String {
        val majorVersionPattern = Pattern.compile("[0-9]+")
        val matcher = majorVersionPattern.matcher(ndkVersion)
        if (matcher.find()) {
            return matcher.group()
        }
        return "UNKNOWN"
    }

    protected fun loadPropertiesFromFile(file: File): Properties {
        val props = Properties()
        val inputStream = file.inputStream()
        props.load(inputStream)
        inputStream.close()

        return props
    }
}
