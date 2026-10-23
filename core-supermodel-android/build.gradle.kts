// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: GPL-3.0-only
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.sbro.emucoreh.core.supermodel"
    compileSdk { version = release(37) }
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../core-supermodel/CMakeLists.txt")
            version = "3.30.5"
        }
    }
}

val prepareSupermodelAssets = tasks.register<Sync>("prepareSupermodelAssets") {
    description = "Package pinned Model 3 ROM definitions and emulator source notices"
    from("../core-supermodel/Config/Games.xml") { into("supermodel") }
    from("../core-supermodel") {
        include("LICENSE", "THIRD-PARTY-NOTICES.txt", "UPSTREAM.txt", "README-ANDROID.md", "licenses/**")
        into("licenses/supermodel")
    }
    into(layout.buildDirectory.dir("generated/supermodelAssets"))
}
tasks.named("preBuild") { dependsOn(prepareSupermodelAssets) }

androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/supermodelAssets").get().asFile.absolutePath)
}
