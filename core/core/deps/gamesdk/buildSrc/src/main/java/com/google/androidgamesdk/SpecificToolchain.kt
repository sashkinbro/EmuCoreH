package com.google.androidgamesdk

import org.gradle.api.Project
import java.io.File

class SpecificToolchain(
    override var project_: Project,
    override var androidVersion_: String,
    override var ndkVersion_: String
) : Toolchain() {
    override fun getAndroidNDKPath(): String {
        return File(
            "${project_.projectDir}/../prebuilts/ndk/" + ndkVersion_
        ).path
    }
}
