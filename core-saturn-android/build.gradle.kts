// SPDX-License-Identifier: GPL-3.0-only
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.sbro.emucoreh.core.saturn"
    compileSdk { version = release(37) }
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += "-DANDROID_STL=c++_static"
            arguments += "-DSATURN_SYNCHRONOUS_SCSP=${if (providers.gradleProperty("saturnSynchronousScsp").orElse("true").get() == "true") "ON" else "OFF"}"
        } }
    }
    buildTypes {
        getByName("debug") { externalNativeBuild { cmake {
            arguments += "-DSATURN_ENABLE_SCSP_DIAGNOSTICS=${if (providers.gradleProperty("saturnScspDiagnostics").orNull == "true") "ON" else "OFF"}"
        } } }
        getByName("release") { externalNativeBuild { cmake {
            arguments += "-DSATURN_ENABLE_SCSP_DIAGNOSTICS=OFF"
        } } }
    }
    externalNativeBuild { cmake { path = file("../core-saturn/CMakeLists.txt"); version = "3.30.5" } }
}
val prepareSaturnAssets = tasks.register<Sync>("prepareSaturnAssets") {
    from("../core-saturn") { include("LICENSE", "THIRD-PARTY-NOTICES.txt", "UPSTREAM.txt", "licenses/**"); into("licenses/saturn") }
    into(layout.buildDirectory.dir("generated/saturnAssets"))
}
tasks.named("preBuild") { dependsOn(prepareSaturnAssets) }
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/saturnAssets").get().asFile.absolutePath)
}
