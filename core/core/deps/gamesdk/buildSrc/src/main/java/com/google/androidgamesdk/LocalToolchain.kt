package com.google.androidgamesdk

import com.google.androidgamesdk.OsSpecificTools.Companion.joinPath
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Toolchain finding the locally installed Android SDK and NDK.
 */
class LocalToolchain(
    override var project_: Project,
    override var androidVersion_: String,
    val requestedNdkRevision: String?
) : Toolchain() {
    private var ndkPath: String? = null
    private var adbPath: String? = null
    override var ndkVersion_: String = "UNKNOWN"

    init {

        // Find the local SDK and the local NDK, optionally the specific
        // version that was requested.
        val sdkPath = findSdkPath()
        ndkPath = findNdkPath(requestedNdkRevision, sdkPath)
        adbPath = joinPath(sdkPath, "platform-tools", "adb")

        // Get the version of the NDK that was found and sanity check that it's the proper one.
        ndkVersion_ = getNdkVersionFromPropertiesFile()
        if (requestedNdkRevision != null) {
            val requestNDKMajorVersion =
                extractNdkMajorVersion(requestedNdkRevision)
            if (ndkVersion_ != requestNDKMajorVersion) {
                throw GradleException(
                    /* ktlint-disable max-line-length */
                    "You specified NDK $requestedNdkRevision but only NDK $ndkVersion_ was found in $ndkPath. Verify that you have the requested NDK installed with the SDK manager."
                    /* ktlint-enable max-line-length */
                )
            }
        }
    }

    /**
     * Try to find the requested NDK in the specified SDK, or then fallbacks to
     * the NDK specified by ANDROID_NDK, ANDROID_NDK_HOME, local.properties file
     * or the default NDK found in "ndk-bundle" in the SDK.
     */
    private fun findNdkPath(
        requestedNdkRevision: String?,
        sdkPath: String
    ): String {
        var foundNdkPath: String? = null

        // Try to find a side-by-side NDK if a specific version is specified
        if (requestedNdkRevision != null) {
            val ndkSideBySidePath = joinPath(
                sdkPath, "ndk",
                requestedNdkRevision
            )
            if (project_.file(ndkSideBySidePath).exists())
                foundNdkPath = ndkSideBySidePath

            // Don't throw an error if not found, as the version might be the default one
            // installed (see next lines). We do a sanity check of the version
            // later.
        }

        // Try to find the NDK specified in ANDROID_NDK or the default one.
        if (foundNdkPath == null) {
            foundNdkPath = System.getenv("ANDROID_NDK")
        }
        if (foundNdkPath == null) {
            foundNdkPath = System.getenv("ANDROID_NDK_HOME")
        }
        if (foundNdkPath == null) {
            foundNdkPath = joinPath(sdkPath, "ndk-bundle")
            if (!project_.file(foundNdkPath).exists())
                throw GradleException(
                    /* ktlint-disable max-line-length */
                    "No NDK found in SDK: must set ANDROID_NDK or ANDROID_NDK_REVISION with the version of a NDK installed in the SDK \"ndk\" folder (NDK side-by-side)."
                    /* ktlint-enable max-line-length */
                )
        }

        return foundNdkPath
    }

    /**
     * Find the Android SDK using ANDROID_HOME environment variable or
     * local.properties file as a fallback.
     */
    private fun findSdkPath(): String {
        var sdkPath = System.getenv("ANDROID_HOME")
        if (sdkPath == null &&
            project_.rootProject.file("local.properties").isFile
        ) {
            val properties = loadPropertiesFromFile(
                project_.rootProject.file("local.properties")
            )
            sdkPath = properties.getProperty("sdk.dir")
        }
        if (sdkPath == null)
            throw GradleException(
                "Must set ANDROID_HOME or sdk.dir in local.properties"
            )
        return sdkPath
    }

    override fun getAndroidNDKPath(): String {
        return ndkPath!!
    }

    fun getAdbPath(): String {
        return adbPath!!
    }
}
